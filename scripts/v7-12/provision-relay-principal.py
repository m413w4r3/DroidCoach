#!/usr/bin/env python3
"""Offline, one-shot provisioning for the V7-12 Relay Android and TOOL principals."""

from __future__ import annotations

import argparse
import errno
import fcntl
import hashlib
import json
import os
from pathlib import Path
import secrets
import sqlite3
import stat
import sys
from contextlib import contextmanager
from typing import Any
from urllib.parse import quote


BACKEND = Path(__file__).resolve().parents[2] / "backend"
if str(BACKEND) not in sys.path:
    sys.path.insert(0, str(BACKEND))

from atlas_backend.store import SQLiteStore  # noqa: E402
from atlas_backend.v4_auth import (  # noqa: E402
    PrincipalAuthError,
    V4PrincipalRegistry,
    bearer_sha256,
)


SNAPSHOT_TABLES = (
    "consent_states_v4",
    "consent_state_events_v4",
    "agent_proposals_v4",
    "agent_proposal_events_v4",
    "agent_proposal_expiration_events_v4",
    "agent_proposals",
    "agent_proposal_acks",
)


class ProvisioningError(RuntimeError):
    pass


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", required=True, type=Path)
    relay = parser.add_mutually_exclusive_group(required=True)
    relay.add_argument("--relay-devices-file", type=Path)
    relay.add_argument("--relay-device-id")
    parser.add_argument("--relay-credential-output", required=True, type=Path)
    parser.add_argument("--map-output", required=True, type=Path)
    parser.add_argument("--receipt", required=True, type=Path)
    parser.add_argument("--snapshot", required=True, type=Path)
    parser.add_argument("--credential-container-path", default="/state/v7-12/relay-credential")
    tool = parser.add_mutually_exclusive_group(required=True)
    tool.add_argument("--tool-token-stdin", action="store_true")
    tool.add_argument("--tool-token-file", type=Path)
    return parser


def _absolute_regular(path: Path, *, label: str, max_bytes: int | None = None) -> os.stat_result:
    if not path.is_absolute():
        raise ProvisioningError(f"{label} path must be absolute")
    try:
        info = path.lstat()
    except OSError:
        raise ProvisioningError(f"{label} is unavailable") from None
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        raise ProvisioningError(f"{label} must be a regular non-symlink file")
    if max_bytes is not None and info.st_size > max_bytes:
        raise ProvisioningError(f"{label} is too large")
    return info


def _read_bytes(path: Path, *, label: str, max_bytes: int, private: bool = False) -> bytes:
    before = _absolute_regular(path, label=label, max_bytes=max_bytes)
    if private and (before.st_uid != os.geteuid() or stat.S_IMODE(before.st_mode) != 0o600):
        raise ProvisioningError(f"{label} must be owned by this process with mode 0600")
    if not hasattr(os, "O_NOFOLLOW"):
        raise ProvisioningError("O_NOFOLLOW is required")
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0))
    except OSError:
        raise ProvisioningError(f"{label} is unavailable or unsafe") from None
    try:
        opened = os.fstat(descriptor)
        if (
            not stat.S_ISREG(opened.st_mode)
            or opened.st_dev != before.st_dev
            or opened.st_ino != before.st_ino
            or opened.st_size > max_bytes
            or (private and (opened.st_uid != os.geteuid() or stat.S_IMODE(opened.st_mode) != 0o600))
        ):
            raise ProvisioningError(f"{label} changed or is unsafe")
        result = bytearray()
        while len(result) <= max_bytes:
            chunk = os.read(descriptor, min(8192, max_bytes + 1 - len(result)))
            if not chunk:
                break
            result.extend(chunk)
        after_fd = os.fstat(descriptor)
        after_path = path.lstat()
        stable = ("st_dev", "st_ino", "st_uid", "st_mode", "st_size", "st_mtime_ns", "st_ctime_ns")
        if (
            len(result) > max_bytes
            or len(result) != before.st_size
            or any(getattr(opened, field) != getattr(after_fd, field) for field in stable)
            or any(getattr(before, field) != getattr(after_path, field) for field in stable)
        ):
            raise ProvisioningError(f"{label} changed or is too large")
        return bytes(result)
    except OSError:
        raise ProvisioningError(f"{label} could not be read safely") from None
    finally:
        os.close(descriptor)


def _token_bytes(raw: bytes, *, label: str) -> str:
    if raw.endswith(b"\r\n"):
        raw = raw[:-2]
    elif raw.endswith(b"\n"):
        raw = raw[:-1]
    try:
        token = raw.decode("ascii", errors="strict")
        bearer_sha256(token)
    except (UnicodeDecodeError, PrincipalAuthError):
        raise ProvisioningError(f"{label} is invalid") from None
    return token


def _load_tool_token(args: argparse.Namespace) -> str:
    if args.tool_token_file is not None:
        raw = _read_bytes(args.tool_token_file, label="TOOL token file", max_bytes=4098, private=True)
    else:
        raw = sys.stdin.buffer.read(4099)
        if len(raw) > 4098:
            raise ProvisioningError("TOOL token input is too large")
    return _token_bytes(raw, label="TOOL token")


def _relay_device_id(args: argparse.Namespace) -> str:
    if args.relay_device_id is not None:
        device_id = args.relay_device_id
    else:
        raw = _read_bytes(args.relay_devices_file, label="Relay devices file", max_bytes=2_097_152)
        try:
            document = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=_unique_object)
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError):
            raise ProvisioningError("Relay devices file is invalid") from None
        if not isinstance(document, dict) or document.get("schema_version") != 1 or not isinstance(document.get("devices"), list):
            raise ProvisioningError("Relay devices file has an invalid schema")
        devices = document["devices"]
        if any(not isinstance(item, dict) for item in devices):
            raise ProvisioningError("Relay devices file has an invalid device record")
        active = [item for item in devices if item.get("revoked_at") is None]
        if len(active) != 1 or active[0].get("label") != "pixel-v7":
            raise ProvisioningError("Relay registry must contain exactly one active pixel-v7 device")
        device_id = active[0].get("device_id")
    if not isinstance(device_id, str) or not device_id or len(device_id) > 256:
        raise ProvisioningError("Relay device ID is invalid")
    return device_id


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def _connect_readonly(path: Path) -> sqlite3.Connection:
    uri = f"file:{quote(str(path), safe='/')}?mode=ro"
    connection = sqlite3.connect(uri, uri=True, timeout=1)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA query_only=ON")
    return connection


@contextmanager
def _readonly_database(path: Path):
    connection = _connect_readonly(path)
    try:
        yield connection
    finally:
        connection.close()


def _validate_database(path: Path) -> None:
    if not path.is_absolute():
        raise ProvisioningError("DB path must be absolute")
    _absolute_regular(path, label="DB")
    try:
        with _readonly_database(path) as connection:
            if int(connection.execute("PRAGMA user_version").fetchone()[0]) != 5:
                raise ProvisioningError("DB schema must be v5")
            if connection.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                raise ProvisioningError("DB integrity check failed")
            if connection.execute("PRAGMA foreign_key_check").fetchone() is not None:
                raise ProvisioningError("DB foreign key check failed")
            required = {
                "schema_migrations", "principals_v4", "credentials_v4", "principal_capabilities_v4",
                "principal_events_v4", "relay_principal_bindings_v4", *SNAPSHOT_TABLES,
            }
            tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            if not required <= tables:
                raise ProvisioningError("DB v5 schema is incomplete")
            migration = connection.execute("SELECT 1 FROM schema_migrations WHERE version=5").fetchone()
            if migration is None:
                raise ProvisioningError("DB v5 migration ledger is incomplete")
            registry_counts = tuple(
                int(connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0])
                for table in ("principals_v4", "credentials_v4", "relay_principal_bindings_v4")
            )
            if registry_counts != (0, 0, 0):
                raise ProvisioningError("V4 registry is not empty")
    except sqlite3.Error:
        raise ProvisioningError("DB could not be validated read-only") from None


def _acquire_core_stopped_lock(db_path: Path) -> int:
    """Hold Core's existing startup lock throughout provisioning; never create it."""
    if not hasattr(os, "O_NOFOLLOW"):
        raise ProvisioningError("cannot safely verify Core stopped state")
    lock_path = Path(f"{db_path.resolve(strict=False)}.lock")
    try:
        before = lock_path.lstat()
    except OSError:
        raise ProvisioningError("Core lock file is absent; stopped state cannot be verified") from None
    if (
        stat.S_ISLNK(before.st_mode)
        or not stat.S_ISREG(before.st_mode)
        or before.st_uid != os.geteuid()
        or stat.S_IMODE(before.st_mode) != 0o600
    ):
        raise ProvisioningError("Core lock file is unsafe")
    try:
        descriptor = os.open(lock_path, os.O_RDONLY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0))
    except OSError:
        raise ProvisioningError("Core lock file could not be opened safely") from None
    try:
        opened = os.fstat(descriptor)
        if (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino):
            raise ProvisioningError("Core lock file changed while opening")
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        after = os.fstat(descriptor)
        if (
            (after.st_dev, after.st_ino) != (before.st_dev, before.st_ino)
            or after.st_uid != os.geteuid()
            or stat.S_IMODE(after.st_mode) != 0o600
        ):
            raise ProvisioningError("Core lock file changed while locking")
        return descriptor
    except OSError as exc:
        os.close(descriptor)
        if exc.errno in {errno.EACCES, errno.EAGAIN}:
            raise ProvisioningError("Core is active; provisioning requires it stopped") from None
        raise ProvisioningError("Core stopped state could not be verified") from None
    except BaseException:
        os.close(descriptor)
        raise


def _typed_value(value: Any) -> dict[str, Any]:
    if value is None:
        return {"type": "null"}
    if isinstance(value, bytes):
        return {"type": "blob", "value": value.hex()}
    if isinstance(value, bool):
        return {"type": "integer", "value": "1" if value else "0"}
    if isinstance(value, int):
        return {"type": "integer", "value": str(value)}
    if isinstance(value, float):
        return {"type": "real", "value": value.hex()}
    if isinstance(value, str):
        return {"type": "text", "value": value}
    raise ProvisioningError("unsupported SQLite value type in snapshot")


def _snapshot(connection: sqlite3.Connection) -> dict[str, Any]:
    result: dict[str, Any] = {"schema_version": 1, "tables": {}}
    for table in SNAPSHOT_TABLES:
        rows = connection.execute(f'SELECT * FROM "{table}"').fetchall()
        encoded_rows = []
        for row in rows:
            typed = {key: _typed_value(value) for key, value in dict(row).items()}
            encoded_rows.append(json.dumps(typed, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8"))
        encoded_rows.sort()
        digest = hashlib.sha256()
        for encoded in encoded_rows:
            digest.update(len(encoded).to_bytes(8, "big"))
            digest.update(encoded)
        result["tables"][table] = {"count": len(encoded_rows), "sha256": digest.hexdigest()}
    return result


def _prepare_output_dir(paths: list[Path]) -> Path:
    normalized = [Path(os.path.abspath(path)) for path in paths]
    if any(not path.is_absolute() for path in paths):
        raise ProvisioningError("all output paths must be absolute")
    parents = {path.parent for path in normalized}
    if len(parents) != 1:
        raise ProvisioningError("all output files must share one private directory")
    directory = next(iter(parents))
    try:
        directory.lstat()
    except FileNotFoundError:
        try:
            os.mkdir(directory, 0o700)
        except OSError:
            raise ProvisioningError("output directory could not be created") from None
    except OSError:
        raise ProvisioningError("output directory is unavailable") from None
    try:
        info = directory.lstat()
        if (
            stat.S_ISLNK(info.st_mode)
            or not stat.S_ISDIR(info.st_mode)
            or info.st_uid != os.geteuid()
            or stat.S_IMODE(info.st_mode) != 0o700
            or directory.resolve(strict=True) != directory
        ):
            raise ProvisioningError("output directory must be non-symlink, owned, and mode 0700")
    except OSError:
        raise ProvisioningError("output directory is unsafe") from None
    if len({str(path) for path in normalized}) != len(normalized):
        raise ProvisioningError("output paths must be unique")
    for path in normalized:
        if os.path.lexists(path):
            raise ProvisioningError("an output path already exists")
    return directory


def _write_exclusive(path: Path, payload: bytes, created: list[tuple[Path, int, int]]) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0)
    try:
        descriptor = os.open(path, flags, 0o600)
    except OSError:
        raise ProvisioningError("private output could not be created exclusively") from None
    try:
        info = os.fstat(descriptor)
        created.append((path, info.st_dev, info.st_ino))
        os.fchmod(descriptor, 0o600)
        view = memoryview(payload)
        while view:
            written = os.write(descriptor, view)
            if written <= 0:
                raise OSError("short write")
            view = view[written:]
        os.fsync(descriptor)
    except OSError:
        raise ProvisioningError("private output could not be written") from None
    finally:
        os.close(descriptor)


def _cleanup_created(created: list[tuple[Path, int, int]]) -> None:
    for path, device, inode in reversed(created):
        try:
            current = path.lstat()
            if not stat.S_ISLNK(current.st_mode) and (current.st_dev, current.st_ino) == (device, inode):
                path.unlink()
        except OSError:
            pass


def _assert_postconditions(db_path: Path, before: dict[str, Any]) -> None:
    with _readonly_database(db_path) as connection:
        counts = {
            "principals": int(connection.execute("SELECT COUNT(*) FROM principals_v4").fetchone()[0]),
            "credentials": int(connection.execute("SELECT COUNT(*) FROM credentials_v4").fetchone()[0]),
            "bindings": int(connection.execute("SELECT COUNT(*) FROM relay_principal_bindings_v4").fetchone()[0]),
            "grants": int(connection.execute("SELECT COUNT(*) FROM principal_capabilities_v4 WHERE granted=1").fetchone()[0]),
            "events": int(connection.execute("SELECT COUNT(*) FROM principal_events_v4").fetchone()[0]),
        }
        event_counts = {
            str(row["event_type"]): int(row["count"])
            for row in connection.execute("SELECT event_type,COUNT(*) AS count FROM principal_events_v4 GROUP BY event_type")
        }
        if counts != {"principals": 2, "credentials": 2, "bindings": 1, "grants": 20, "events": 25}:
            raise ProvisioningError("post-provisioning counts differ; leave Core in legacy and do not rerun")
        if event_counts != {"CAPABILITY_CHANGED": 20, "PROVISIONED": 4, "RELAY_BOUND": 1}:
            raise ProvisioningError("post-provisioning event counts differ; leave Core in legacy and do not rerun")
        if _snapshot(connection) != before:
            raise ProvisioningError("consent/proposal/ack snapshot changed; leave Core in legacy and do not rerun")


def main() -> int:
    args = _parser().parse_args()
    core_lock_fd: int | None = None
    try:
        db_path = args.db
        if not db_path.is_absolute():
            raise ProvisioningError("DB path must be absolute")
        _validate_database(db_path)
        core_lock_fd = _acquire_core_stopped_lock(db_path)
        relay_device_id = _relay_device_id(args)
        credential_container_path = args.credential_container_path
        if (
            not isinstance(credential_container_path, str)
            or not credential_container_path.startswith("/")
            or "\0" in credential_container_path
            or os.path.normpath(credential_container_path) != credential_container_path
        ):
            raise ProvisioningError("credential container path must be absolute and normalized")
        outputs = [args.relay_credential_output, args.map_output, args.receipt, args.snapshot]
        _prepare_output_dir(outputs)
        tool_token = _load_tool_token(args)
        with _readonly_database(db_path) as connection:
            before_snapshot = _snapshot(connection)

        relay_token = "atlas_" + secrets.token_urlsafe(32)
        # Validate the generated credential before opening the registry transaction.
        bearer_sha256(relay_token)
        created: list[tuple[Path, int, int]] = []
        committed = False
        snapshot_path = Path(os.path.abspath(args.snapshot))
        try:
            _write_exclusive(
                snapshot_path,
                (json.dumps(before_snapshot, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8"),
                created,
            )
            store = SQLiteStore(db_path)
            registry = V4PrincipalRegistry(store)

            def persist_private_outputs(receipt: dict[str, Any]) -> None:
                mapping = {
                    relay_device_id: {
                        "principal_id": receipt["principal_ids"]["ANDROID"],
                        "credential_file": credential_container_path,
                    }
                }
                full_receipt = {
                    "schema_version": 1,
                    **receipt,
                    "credential_container_path": credential_container_path,
                    "snapshot_file": str(snapshot_path),
                    "registry_counts": receipt["counts"],
                }
                _write_exclusive(
                    Path(os.path.abspath(args.relay_credential_output)),
                    (relay_token + "\n").encode("ascii"),
                    created,
                )
                _write_exclusive(
                    Path(os.path.abspath(args.map_output)),
                    (json.dumps(mapping, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8"),
                    created,
                )
                _write_exclusive(
                    Path(os.path.abspath(args.receipt)),
                    (json.dumps(full_receipt, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8"),
                    created,
                )

            registry_receipt = registry.provision_relay_identity(
                relay_device_id,
                relay_token,
                tool_token,
                before_commit=persist_private_outputs,
            )
            committed = True
            try:
                _assert_postconditions(db_path, before_snapshot)
            except Exception:
                raise ProvisioningError(
                    "provisioning committed but postcondition verification failed; leave Core in legacy and do not rerun"
                ) from None
        except BaseException:
            if not committed:
                _cleanup_created(created)
            raise

        print(json.dumps({
            "status": "PASS",
            "counts": registry_receipt["counts"],
            "android_principal": _redacted(registry_receipt["principal_ids"]["ANDROID"]),
            "tool_principal": _redacted(registry_receipt["principal_ids"]["TOOL"]),
        }, sort_keys=True))
        return 0
    except (ProvisioningError, PrincipalAuthError, OSError, sqlite3.Error, ValueError) as exc:
        message = str(exc)
        if isinstance(exc, (PrincipalAuthError, OSError, sqlite3.Error)):
            message = "provisioning precondition or transaction failed; no secret values are emitted"
        print(json.dumps({"status": "FAIL", "error": message}, sort_keys=True), file=sys.stderr)
        return 2
    finally:
        if core_lock_fd is not None:
            try:
                fcntl.flock(core_lock_fd, fcntl.LOCK_UN)
            finally:
                os.close(core_lock_fd)


def _redacted(value: str) -> str:
    return value[:7] + "…" + value[-3:] if len(value) > 12 else "<redacted>"


if __name__ == "__main__":
    raise SystemExit(main())
