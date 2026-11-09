#!/usr/bin/env python3
"""Verify a private ATLAS backup and optionally restore it to a fresh directory."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sqlite3
import stat
import sys


EXCLUDED_OPENCLAW_DIRS = {"cache", ".cache", "tmp", "npm", "completions"}
CORE_SECRET_NAMES = (
    "atlas_token", "atlas_tool_token", "openclaw_gateway_token",
    "atlas_v4_relay_assertion_keys", "atlas_v4_cursor",
)
CONNECTOR_PRIVATE_REQUIRED = (
    "relay-assertion-secret", "service-token", "principal-map.json", "relay-credential",
)
CONNECTOR_PRIVATE_OPTIONAL = ("android-token", "pixel-v7.token", "pixel-v7-pairing.json")


class RestoreError(RuntimeError):
    pass


def safe_relative(value: str) -> PurePosixPath:
    path = PurePosixPath(value)
    if path.is_absolute() or not path.parts or any(part in {"", ".", ".."} for part in path.parts):
        raise RestoreError("manifest contains an unsafe path")
    if "\n" in value or "\r" in value:
        raise RestoreError("manifest contains an unsafe path")
    return path


def walk_entries(root: Path) -> dict[str, Path]:
    entries: dict[str, Path] = {}
    for current, dirnames, filenames in os.walk(root, topdown=True, followlinks=False):
        current_path = Path(current)
        for name in list(dirnames):
            path = current_path / name
            rel = path.relative_to(root).as_posix()
            if path.is_symlink():
                dirnames.remove(name)
                entries[rel] = path
            elif path.is_dir():
                entries[rel + "/"] = path
            else:
                raise RestoreError("backup contains an unsupported filesystem entry")
        for name in filenames:
            path = current_path / name
            rel = path.relative_to(root).as_posix()
            if not path.is_file() and not path.is_symlink():
                raise RestoreError("backup contains an unsupported filesystem entry")
            entries[rel] = path
    return entries


def path_digest(path: Path) -> str:
    if path.is_symlink():
        raw = os.readlink(path).encode("utf-8", errors="surrogateescape")
        return hashlib.sha256(b"SYMLINK\0" + raw).hexdigest()
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_manifest(root: Path) -> int:
    manifest = root / "manifest.sha256"
    if manifest.is_symlink() or not manifest.is_file() or stat.S_IMODE(manifest.stat().st_mode) != 0o600:
        raise RestoreError("backup manifest is missing or has unsafe permissions")
    expected: dict[str, str] = {}
    for line in manifest.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if not match:
            raise RestoreError("backup manifest is malformed")
        digest, relative = match.groups()
        rel = safe_relative(relative).as_posix()
        if rel == "manifest.sha256" or rel in expected:
            raise RestoreError("backup manifest contains a duplicate or recursive path")
        expected[rel] = digest

    actual: dict[str, Path] = {}
    for relative, path in walk_entries(root).items():
        is_directory_marker = relative.endswith("/")
        if is_directory_marker:
            continue
        if relative != "manifest.sha256":
            actual[relative] = path
    if set(expected) != set(actual):
        raise RestoreError("backup contents do not match the manifest")
    for relative, path in actual.items():
        if path_digest(path) != expected[relative]:
            raise RestoreError("backup manifest hash mismatch")
    return len(expected)


def verify_permissions(root: Path) -> tuple[int, int]:
    info = root.lstat()
    if not stat.S_ISDIR(info.st_mode) or stat.S_ISLNK(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o700:
        raise RestoreError("backup root must be a real directory with mode 0700")
    regular_files = 0
    symlinks = 0
    for current, dirnames, filenames in os.walk(root, topdown=True, followlinks=False):
        parent = Path(current)
        for name in list(dirnames):
            path = parent / name
            item = path.lstat()
            if stat.S_ISLNK(item.st_mode):
                dirnames.remove(name)
                symlinks += 1
            elif stat.S_ISDIR(item.st_mode):
                if stat.S_IMODE(item.st_mode) != 0o700:
                    raise RestoreError("backup directory permissions must be 0700")
            else:
                raise RestoreError("backup contains an unsupported filesystem entry")
        for name in filenames:
            path = parent / name
            item = path.lstat()
            if stat.S_ISREG(item.st_mode):
                if stat.S_IMODE(item.st_mode) != 0o600:
                    raise RestoreError("backup file permissions must be 0600")
                regular_files += 1
            elif not stat.S_ISLNK(item.st_mode):
                raise RestoreError("backup contains an unsupported filesystem entry")
            else:
                symlinks += 1
    return regular_files, symlinks


def verify_sqlite(path: Path) -> tuple[int, int]:
    if path.is_symlink() or not path.is_file():
        raise RestoreError("SQLite snapshot is missing or unsafe")
    try:
        uri = path.resolve().as_uri() + "?mode=ro"
        with sqlite3.connect(uri, uri=True) as connection:
            integrity = str(connection.execute("PRAGMA integrity_check").fetchone()[0])
            violations = connection.execute("PRAGMA foreign_key_check").fetchall()
            version = int(connection.execute("PRAGMA user_version").fetchone()[0])
    except sqlite3.Error:
        raise RestoreError("SQLite snapshot could not be checked") from None
    if integrity != "ok" or violations:
        raise RestoreError("SQLite integrity or foreign-key check failed")
    return version, len(violations)


def verify_backup(root: Path) -> tuple[int, int, int]:
    if root.is_symlink() or not root.is_dir():
        raise RestoreError("backup directory is missing or unsafe")
    files, symlinks = verify_permissions(root)
    manifest_entries = verify_manifest(root)
    try:
        metadata = json.loads((root / "metadata.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        raise RestoreError("backup metadata is missing or malformed") from None
    if metadata.get("format_version") != 1:
        raise RestoreError("unsupported backup format")

    stack_env = root / "stack.env"
    if stack_env.is_symlink() or not stack_env.is_file() or stack_env.stat().st_size == 0:
        raise RestoreError("stack.env restore configuration is missing or unsafe")
    for name in CORE_SECRET_NAMES:
        path = root / "core" / "secrets" / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size == 0:
            raise RestoreError("Core restore secret set is incomplete")

    version, foreign_keys = verify_sqlite(root / "core" / "atlas.sqlite3")
    lock = root / "core" / "atlas.sqlite3.lock"
    if lock.is_symlink() or not lock.is_file():
        raise RestoreError("Core lock file is missing or unsafe")
    replay = root / "core" / "replay"
    if replay.is_symlink() or not replay.is_dir():
        raise RestoreError("replay cache snapshot is missing or unsafe")

    connector = root / "connector"
    for name in ("identity.json", "devices.json", "operations.json"):
        path = connector / name
        if path.is_symlink() or not path.is_file():
            raise RestoreError("connector backup is incomplete")
    connector_private = connector / "private"
    if connector_private.is_symlink() or not connector_private.is_dir():
        raise RestoreError("connector private restore directory is missing or unsafe")
    for name in CONNECTOR_PRIVATE_REQUIRED:
        path = connector_private / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size == 0:
            raise RestoreError("connector private restore material is incomplete")
    for name in CONNECTOR_PRIVATE_OPTIONAL:
        path = connector_private / name
        if path.exists() or path.is_symlink():
            if path.is_symlink() or not path.is_file() or path.stat().st_size == 0:
                raise RestoreError("optional connector restore material is unsafe or empty")
    openclaw = root / "openclaw"
    if (openclaw.is_symlink() or not openclaw.is_dir()
            or (openclaw / "openclaw.json").is_symlink()
            or not (openclaw / "openclaw.json").is_file()):
        raise RestoreError("OpenClaw backup is incomplete")
    excluded = EXCLUDED_OPENCLAW_DIRS | {"backups"}
    for current, dirnames, _files in os.walk(openclaw, topdown=True, followlinks=False):
        if any(name.lower() in excluded for name in dirnames):
            raise RestoreError("excluded OpenClaw cache or temporary content is present")
    if manifest_entries != files - 1 + symlinks:
        raise RestoreError("backup manifest entry count is inconsistent")
    return files, version, manifest_entries


def copy_restore(source: Path, destination: Path) -> None:
    if not destination.is_absolute() or destination.exists() or destination.is_symlink():
        raise RestoreError("restore destination must be a new absolute path")
    if destination.resolve().is_relative_to(source.resolve()):
        raise RestoreError("restore destination must not be inside the source backup")
    if destination.resolve().is_relative_to(Path(__file__).resolve().parents[2]):
        raise RestoreError("restore destination must be outside the repository")
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    shutil.copytree(source, destination, symlinks=True)
    for current, dirnames, filenames in os.walk(destination, topdown=True, followlinks=False):
        parent = Path(current)
        os.chmod(parent, 0o700, follow_symlinks=False)
        for name in list(dirnames):
            path = parent / name
            if path.is_symlink():
                dirnames.remove(name)
            else:
                os.chmod(path, 0o700, follow_symlinks=False)
        for name in filenames:
            path = parent / name
            if not path.is_symlink():
                os.chmod(path, 0o600, follow_symlinks=False)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("backup_dir", type=Path)
    parser.add_argument("--restore-to", type=Path,
                        help="copy the verified snapshot to a new private directory")
    args = parser.parse_args()
    source = args.backup_dir.expanduser().absolute()
    files, version, entries = verify_backup(source)
    restored = False
    if args.restore_to is not None:
        target = args.restore_to.expanduser().absolute()
        copy_restore(source, target)
        restored_files, restored_version, restored_entries = verify_backup(target)
        if (restored_files, restored_version, restored_entries) != (files, version, entries):
            raise RestoreError("restored copy verification did not match the source")
        restored = True
        print(f"restore_copy=verified path={target}")
    print(f"manifest=verified entries={entries}")
    print("restore_materials=PASS stack_env=present core_secrets=5 connector_private=4 optional_connector_files=verified_if_present")
    print(f"sqlite_integrity=ok foreign_key_violations=0 user_version={version}")
    print("permissions=0700_directories_0600_files")
    print(f"files_verified={files} restore_copy={'PASS' if restored else 'NOT_REQUESTED'}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RestoreError as error:
        print(f"restore check failed: {error}", file=sys.stderr)
        raise SystemExit(1)
