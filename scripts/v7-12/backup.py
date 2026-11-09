#!/usr/bin/env python3
"""Create a private, coherent backup of an ATLAS Compose stack."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sqlite3
import stat
import subprocess
import sys
import uuid


ROOT = Path(__file__).resolve().parents[2]
EXCLUDED_OPENCLAW_DIRS = {"cache", ".cache", "tmp", "npm", "completions", "backups"}
ROTATABLE_BACKUP = re.compile(r"^atlas-backup-\d{8}T\d{6}Z-[0-9a-f]{8}$")
CORE_SECRET_NAMES = (
    "atlas_token", "atlas_tool_token", "openclaw_gateway_token",
    "atlas_v4_relay_assertion_keys", "atlas_v4_cursor",
)
CONNECTOR_PRIVATE_REQUIRED = (
    "relay-assertion-secret", "service-token", "principal-map.json", "relay-credential",
)
CONNECTOR_PRIVATE_OPTIONAL = ("android-token", "pixel-v7.token", "pixel-v7-pairing.json")


class BackupError(RuntimeError):
    pass


def read_env_file(path: Path) -> dict[str, str]:
    if path.is_symlink() or not path.is_file():
        raise BackupError("stack env file is missing or unsafe")
    if stat.S_IMODE(path.stat().st_mode) != 0o600:
        raise BackupError("stack env file must have mode 0600")
    values: dict[str, str] = {}
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if "=" not in line:
            raise BackupError(f"malformed stack env file at line {number}")
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            raise BackupError(f"malformed stack env file at line {number}")
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key] = value

    def expand(value: str, seen: frozenset[str] = frozenset()) -> str:
        pattern = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}")

        def replace(match: re.Match[str]) -> str:
            name, default = match.group(1), match.group(2)
            if name in seen:
                raise BackupError("recursive variable in stack env file")
            candidate = values.get(name, os.environ.get(name))
            if candidate is None or candidate == "":
                candidate = default
            if candidate is None:
                return match.group(0)
            return expand(candidate, seen | {name})

        return pattern.sub(replace, value)

    return {key: expand(value) for key, value in values.items()}


class Compose:
    def __init__(self, env_file: Path, compose_file: Path, project: str) -> None:
        self.env_file = env_file
        self.compose_file = compose_file
        self.project = project

    def command(self, *args: str) -> list[str]:
        return [
            "docker", "compose", "--env-file", str(self.env_file), "-p", self.project,
            "-f", str(self.compose_file), *args,
        ]

    def run(self, *args: str, label: str, check: bool = True) -> str:
        try:
            result = subprocess.run(self.command(*args), text=True, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, check=False)
        except FileNotFoundError:
            raise BackupError("Docker Compose is unavailable") from None
        if check and result.returncode != 0:
            raise BackupError(f"Docker Compose failed during {label}; output suppressed")
        return result.stdout


def require_path(env: dict[str, str], key: str) -> Path:
    value = env.get(key)
    if not value:
        raise BackupError(f"required stack path is missing: {key}")
    path = Path(value).expanduser()
    if not path.is_absolute():
        raise BackupError(f"required stack path must be absolute: {key}")
    return path


def detect_encryption(path: Path) -> dict[str, object]:
    result: dict[str, object] = {"verified": False, "filesystem": "unknown", "algorithm": "unknown"}
    try:
        mount = subprocess.run(["findmnt", "-n", "-o", "FSTYPE,SOURCE", "--target", str(path)],
                               text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False)
        fields = mount.stdout.strip().split(None, 1)
        if mount.returncode != 0 or len(fields) != 2:
            return result
        filesystem, source = fields
        result["filesystem"] = filesystem
        if filesystem != "zfs" or shutil.which("zfs") is None:
            return result
        encryption = subprocess.run(["zfs", "get", "-H", "-o", "value", "encryption", source],
                                    text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False)
        keystatus = subprocess.run(["zfs", "get", "-H", "-o", "value", "keystatus", source],
                                  text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False)
        algorithm = encryption.stdout.strip() if encryption.returncode == 0 else "unknown"
        key_state = keystatus.stdout.strip() if keystatus.returncode == 0 else "unknown"
        result["algorithm"] = algorithm
        result["verified"] = algorithm == "aes-256-gcm" and key_state == "available"
    except OSError:
        return result
    return result


def run_quiet(command: list[str], *, label: str) -> None:
    try:
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    except FileNotFoundError:
        raise BackupError(f"required tool unavailable during {label}") from None
    if result.returncode != 0:
        raise BackupError(f"operation failed during {label}; output suppressed")


def running(compose: Compose, service: str) -> bool:
    return bool(compose.run("ps", "--status", "running", "-q", service,
                            label=f"checking {service} state").strip())


def container_id(compose: Compose, service: str) -> str:
    value = compose.run("ps", "--status", "running", "-q", service,
                        label=f"locating {service}").strip().splitlines()
    if len(value) != 1 or not re.fullmatch(r"[0-9a-f]{12,64}", value[0]):
        raise BackupError(f"expected one running {service} container")
    return value[0]


def normalize_tree(root: Path) -> None:
    for current, dirnames, filenames in os.walk(root, topdown=True, followlinks=False):
        current_path = Path(current)
        os.chmod(current_path, 0o700, follow_symlinks=False)
        for name in list(dirnames):
            child = current_path / name
            info = child.lstat()
            if stat.S_ISLNK(info.st_mode):
                dirnames.remove(name)
            elif stat.S_ISDIR(info.st_mode):
                os.chmod(child, 0o700, follow_symlinks=False)
            else:
                raise BackupError("backup contains a special filesystem entry")
        for name in filenames:
            child = current_path / name
            info = child.lstat()
            if stat.S_ISREG(info.st_mode):
                os.chmod(child, 0o600, follow_symlinks=False)
            elif not stat.S_ISLNK(info.st_mode):
                raise BackupError("backup contains a special filesystem entry")


def safe_relative(path: Path, root: Path) -> str:
    relative = path.relative_to(root).as_posix()
    candidate = PurePosixPath(relative)
    if candidate.is_absolute() or any(part in {"", ".", ".."} for part in candidate.parts):
        raise BackupError("backup contains an unsafe path")
    if "\n" in relative or "\r" in relative:
        raise BackupError("backup contains an unsafe filename")
    return relative


def write_manifest(root: Path) -> None:
    entries: list[tuple[str, str]] = []
    for current, dirnames, filenames in os.walk(root, topdown=True, followlinks=False):
        directory = Path(current)
        for name in list(dirnames):
            path = directory / name
            if path.is_symlink():
                dirnames.remove(name)
                rel = safe_relative(path, root)
                raw = os.readlink(path).encode("utf-8", errors="surrogateescape")
                entries.append((hashlib.sha256(b"SYMLINK\0" + raw).hexdigest(), rel))
        for name in filenames:
            path = directory / name
            rel = safe_relative(path, root)
            if path.is_symlink():
                raw = os.readlink(path).encode("utf-8", errors="surrogateescape")
                digest = hashlib.sha256(b"SYMLINK\0" + raw).hexdigest()
            else:
                digest = hashlib.sha256(path.read_bytes()).hexdigest()
            entries.append((digest, rel))
    manifest = root / "manifest.sha256"
    with manifest.open("x", encoding="utf-8") as output:
        os.chmod(manifest, 0o600)
        for digest, relative in sorted(entries, key=lambda item: item[1]):
            output.write(f"{digest}  {relative}\n")
        output.flush()
        os.fsync(output.fileno())


def sqlite_checks(path: Path) -> tuple[str, int, int]:
    uri = path.resolve().as_uri() + "?mode=ro"
    try:
        with sqlite3.connect(uri, uri=True) as connection:
            integrity = str(connection.execute("PRAGMA integrity_check").fetchone()[0])
            foreign_keys = connection.execute("PRAGMA foreign_key_check").fetchall()
            version = int(connection.execute("PRAGMA user_version").fetchone()[0])
    except sqlite3.Error:
        raise BackupError("SQLite backup could not be checked") from None
    if integrity != "ok" or foreign_keys:
        raise BackupError("SQLite integrity or foreign-key check failed")
    return integrity, len(foreign_keys), version


def copy_openclaw_state(source: Path, destination: Path) -> None:
    if source.is_symlink() or not source.is_dir():
        raise BackupError("OpenClaw state directory is missing or unsafe")
    config = source / "openclaw.json"
    if config.is_symlink() or not config.is_file():
        raise BackupError("OpenClaw configuration is missing or unsafe")

    def ignore(_directory: str, names: list[str]) -> set[str]:
        return {name for name in names if name.lower() in EXCLUDED_OPENCLAW_DIRS}

    shutil.copytree(source, destination, symlinks=True, ignore=ignore)


def copy_core_secrets(core_id: str, destination: Path) -> None:
    destination.mkdir(mode=0o700)
    for name in CORE_SECRET_NAMES:
        try:
            result = subprocess.run(["docker", "exec", core_id, "cat", f"/run/secrets/{name}"],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        except FileNotFoundError:
            raise BackupError("Docker is unavailable while copying Core restore material") from None
        if result.returncode != 0 or not result.stdout:
            raise BackupError("a required Core restore secret could not be copied or was empty")
        path = destination / name
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(fd, "wb") as output:
            output.write(result.stdout)
            output.flush()
            os.fsync(output.fileno())


def copy_connector_state(source: Path, destination: Path) -> None:
    if source.is_symlink() or not source.is_dir():
        raise BackupError("connector state directory is missing or unsafe")
    destination.mkdir(mode=0o700)
    for name in ("identity.json", "devices.json", "operations.json"):
        source_file = source / name
        if source_file.is_symlink() or not source_file.is_file():
            raise BackupError(f"connector state file is missing or unsafe: {name}")
        shutil.copyfile(source_file, destination / name)


def copy_connector_private_state(source: Path, destination: Path) -> None:
    if source.is_symlink() or not source.is_dir():
        raise BackupError("connector state directory is missing or unsafe")
    private_source = source / "v7-12"
    if private_source.is_symlink() or not private_source.is_dir():
        raise BackupError("connector v7-12 private state directory is missing or unsafe")
    destination.mkdir(mode=0o700)
    for name in CONNECTOR_PRIVATE_REQUIRED:
        source_file = private_source / name
        if source_file.is_symlink() or not source_file.is_file() or source_file.stat().st_size == 0:
            raise BackupError("required connector v7-12 restore material is missing or empty")
        shutil.copyfile(source_file, destination / name)
        os.chmod(destination / name, 0o600)
    for name in CONNECTOR_PRIVATE_OPTIONAL:
        source_file = source / name
        if not source_file.exists() and not source_file.is_symlink():
            continue
        if source_file.is_symlink() or not source_file.is_file() or source_file.stat().st_size == 0:
            raise BackupError("an optional connector restore file exists but is unsafe or empty")
        shutil.copyfile(source_file, destination / name)
        os.chmod(destination / name, 0o600)


def copy_stack_env(source: Path, destination: Path) -> None:
    if source.is_symlink() or not source.is_file() or stat.S_IMODE(source.stat().st_mode) != 0o600:
        raise BackupError("stack env file is missing or unsafe")
    shutil.copyfile(source, destination)
    os.chmod(destination, 0o600)


def rotate(root: Path, *, now: float) -> int:
    removed = 0
    for candidate in root.iterdir():
        if not ROTATABLE_BACKUP.fullmatch(candidate.name):
            continue
        info = candidate.lstat()
        if stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode) and now - info.st_mtime > 7 * 24 * 60 * 60:
            shutil.rmtree(candidate)
            removed += 1
    return removed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", required=True, type=Path)
    parser.add_argument("--compose-file", type=Path, default=ROOT / "compose.yaml")
    parser.add_argument("--project", default="atlas-core")
    args = parser.parse_args()

    env_file = args.env_file.expanduser()
    compose_file = args.compose_file.resolve()
    env = read_env_file(env_file)
    backup_root_value = env.get("ATLAS_BACKUP_DIR") or str(Path.home() / ".local/state/atlas-stack/backups")
    backup_root = Path(backup_root_value).expanduser()
    if not backup_root.is_absolute() or backup_root.is_relative_to(ROOT):
        raise BackupError("backup directory must be absolute and outside the repository")
    if backup_root.is_symlink():
        raise BackupError("backup directory must not be a symlink")
    backup_root.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(backup_root, 0o700)
    encryption = detect_encryption(backup_root)
    require_encryption = env.get("ATLAS_BACKUP_REQUIRE_ZFS_ENCRYPTION", "1").lower() not in {"0", "false", "no"}
    if require_encryption and not encryption["verified"]:
        raise BackupError("backup target is not verified as ZFS aes-256-gcm with an available key")

    connector_state = require_path(env, "ATLAS_CONNECTOR_STATE_DIR")
    openclaw_state = require_path(env, "OPENCLAW_STATE_DIR")
    compose = Compose(env_file, compose_file, args.project)
    backup_name = "atlas-backup-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    destination = backup_root / backup_name
    destination.mkdir(mode=0o700)
    core_dir = destination / "core"
    core_dir.mkdir(mode=0o700)
    stopped: list[str] = []
    started: list[str] = []
    success = False
    restart_failed = False
    sqlite_info: tuple[str, int, int] | None = None
    try:
        core_id = container_id(compose, "atlas-core")
        if args.project == "atlas-core" and shutil.which("systemctl") is not None:
            systemd_gateway = subprocess.run(["systemctl", "--user", "is-active", "--quiet", "openclaw-gateway.service"],
                                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            if systemd_gateway.returncode == 0:
                raise BackupError("systemd OpenClaw Gateway is active; refusing an inconsistent state snapshot")
        for service in ("atlas-connector", "openclaw-gateway"):
            if running(compose, service):
                stopped.append(service)
        if stopped:
            compose.run("stop", "--timeout", "30", *stopped, label="stopping snapshot writers")

        temporary_db = f"/data/.atlas-backup-{uuid.uuid4().hex}.sqlite3"
        backup_sql = (
            "import sqlite3,sys; "
            "src=sqlite3.connect('file:/data/atlas.sqlite3?mode=ro',uri=True); "
            "dst=sqlite3.connect(sys.argv[1]); src.backup(dst); dst.close(); src.close()"
        )
        database_path = core_dir / "atlas.sqlite3"
        try:
            run_quiet(["docker", "exec", core_id, "python", "-c", backup_sql, temporary_db],
                      label="SQLite online backup API")
            run_quiet(["docker", "cp", f"{core_id}:{temporary_db}", str(database_path)],
                      label="copying SQLite backup")
        finally:
            try:
                run_quiet(["docker", "exec", core_id, "python", "-c", "import os,sys; os.unlink(sys.argv[1])", temporary_db],
                          label="removing temporary SQLite backup")
            except BackupError:
                pass
        os.chmod(database_path, 0o600)
        sqlite_info = sqlite_checks(database_path)
        copy_core_secrets(core_id, core_dir / "secrets")

        # The online SQLite backup above is coherent; stop Core before copying
        # the lock and replay sidecars so no nonce cache write races the copy.
        if running(compose, "atlas-core"):
            stopped.append("atlas-core")
            compose.run("stop", "--timeout", "30", "atlas-core", label="quiescing Core for replay cache copy")

        run_quiet(["docker", "cp", f"{core_id}:/data/atlas.sqlite3.lock", str(core_dir / "atlas.sqlite3.lock")],
                  label="copying Core lock file")
        os.chmod(core_dir / "atlas.sqlite3.lock", 0o600)
        run_quiet(["docker", "cp", f"{core_id}:/data/replay", str(core_dir / "replay")],
                  label="copying replay cache and lock")

        copy_connector_state(connector_state, destination / "connector")
        copy_connector_private_state(connector_state, destination / "connector" / "private")
        copy_openclaw_state(openclaw_state, destination / "openclaw")
        copy_stack_env(env_file, destination / "stack.env")

        metadata = {
            "format_version": 1,
            "created_at": datetime.now(timezone.utc).isoformat(),
            "project": args.project,
            "sqlite": {
                "integrity_check": sqlite_info[0],
                "foreign_key_violations": sqlite_info[1],
                "user_version": sqlite_info[2],
                "capture": "sqlite3.Connection.backup",
                "replay_capture": "stopped file copy after Core quiesce",
            },
            "connector_files": ["identity.json", "devices.json", "operations.json"],
            "openclaw_state_root": "openclaw",
            "excluded_openclaw_directory_names": sorted(EXCLUDED_OPENCLAW_DIRS),
            "encryption_at_rest": encryption,
        }
        metadata_path = destination / "metadata.json"
        metadata_path.write_text(json.dumps(metadata, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        normalize_tree(destination)
        write_manifest(destination)
        run_quiet([sys.executable, str(ROOT / "scripts/v7-12/restore-check.py"), str(destination)],
                  label="verifying newly created backup")
        success = True
    finally:
        started = list(reversed(stopped))
        if started:
            try:
                compose.run("start", *started, label="restarting snapshot writers")
            except BackupError:
                if success:
                    restart_failed = True
                print("warning: one or more previously running services did not restart", file=sys.stderr)
        if not success:
            shutil.rmtree(destination, ignore_errors=True)

    if restart_failed:
        raise BackupError(f"backup {destination} was created, but one or more previously running services did not restart")

    removed = rotate(backup_root, now=datetime.now(timezone.utc).timestamp())
    if encryption["verified"]:
        encryption_text = "ZFS aes-256-gcm verified"
    else:
        encryption_text = "at-rest encryption not verified"
    print(f"backup_created={destination}")
    print(f"sqlite_integrity={sqlite_info[0]} foreign_key_violations={sqlite_info[1]}")
    print(f"permissions=0700_directories_0600_files manifest=verified-after-write")
    print(f"encryption={encryption_text}")
    print(f"rotation_removed={removed} retention_days=7")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except BackupError as error:
        print(f"backup failed: {error}", file=sys.stderr)
        raise SystemExit(1)
