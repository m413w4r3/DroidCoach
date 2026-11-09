#!/usr/bin/env python3
"""Read-only verifier for a synthetic or staged V7-12 V4 Relay cutover."""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import hmac
import json
import os
from pathlib import Path
import sqlite3
import stat
import sys
from typing import Any, Callable
from urllib.parse import quote


BACKEND = Path(__file__).resolve().parents[2] / "backend"
if str(BACKEND) not in sys.path:
    sys.path.insert(0, str(BACKEND))

from atlas_backend.v4_assertion import load_assertion_keys  # noqa: E402
from atlas_backend.v4_auth import (  # noqa: E402
    ANDROID_RELAY_CAPABILITIES,
    TOOL_RELAY_CAPABILITIES,
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


class VerificationError(RuntimeError):
    pass


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", required=True, type=Path)
    parser.add_argument("--relay-devices-file", required=True, type=Path)
    parser.add_argument("--relay-credential", required=True, type=Path)
    parser.add_argument("--tool-token-file", required=True, type=Path)
    parser.add_argument("--map", required=True, type=Path)
    parser.add_argument("--receipt", required=True, type=Path)
    parser.add_argument("--snapshot", required=True, type=Path)
    parser.add_argument("--keyring-file", type=Path)
    parser.add_argument("--require-keyring", action="store_true")
    return parser


def _private_bytes(path: Path, *, label: str, max_bytes: int) -> bytes:
    if not path.is_absolute():
        raise VerificationError(f"{label} path must be absolute")
    try:
        before = path.lstat()
    except OSError:
        raise VerificationError(f"{label} is unavailable") from None
    if (
        stat.S_ISLNK(before.st_mode)
        or not stat.S_ISREG(before.st_mode)
        or before.st_uid != os.geteuid()
        or stat.S_IMODE(before.st_mode) != 0o600
        or not 0 <= before.st_size <= max_bytes
    ):
        raise VerificationError(f"{label} must be an owner-only regular file with mode 0600")
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0))
    except OSError:
        raise VerificationError(f"{label} is unavailable or unsafe") from None
    try:
        opened = os.fstat(descriptor)
        if (
            not stat.S_ISREG(opened.st_mode)
            or opened.st_uid != os.geteuid()
            or stat.S_IMODE(opened.st_mode) != 0o600
            or (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino)
            or opened.st_size > max_bytes
        ):
            raise VerificationError(f"{label} changed or is unsafe")
        chunks = bytearray()
        while len(chunks) <= max_bytes:
            chunk = os.read(descriptor, min(8192, max_bytes + 1 - len(chunks)))
            if not chunk:
                break
            chunks.extend(chunk)
        after_fd = os.fstat(descriptor)
        after_path = path.lstat()
        stable = ("st_dev", "st_ino", "st_uid", "st_mode", "st_size", "st_mtime_ns", "st_ctime_ns")
        if (
            len(chunks) > max_bytes
            or len(chunks) != before.st_size
            or any(getattr(opened, field) != getattr(after_fd, field) for field in stable)
            or any(getattr(before, field) != getattr(after_path, field) for field in stable)
        ):
            raise VerificationError(f"{label} changed or is too large")
        return bytes(chunks)
    except OSError:
        raise VerificationError(f"{label} could not be read safely") from None
    finally:
        os.close(descriptor)


def _read_untrusted_regular(path: Path, *, label: str, max_bytes: int) -> bytes:
    if not path.is_absolute():
        raise VerificationError(f"{label} path must be absolute")
    try:
        info = path.lstat()
        if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or info.st_size > max_bytes:
            raise VerificationError(f"{label} is unsafe or too large")
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0))
    except OSError:
        raise VerificationError(f"{label} is unavailable") from None
    try:
        opened = os.fstat(descriptor)
        if (opened.st_dev, opened.st_ino) != (info.st_dev, info.st_ino) or opened.st_size > max_bytes:
            raise VerificationError(f"{label} changed while opening")
        data = bytearray()
        while len(data) <= max_bytes:
            chunk = os.read(descriptor, min(8192, max_bytes + 1 - len(data)))
            if not chunk:
                break
            data.extend(chunk)
        if len(data) > max_bytes or len(data) != info.st_size:
            raise VerificationError(f"{label} changed or is too large")
        return bytes(data)
    except OSError:
        raise VerificationError(f"{label} could not be read safely") from None
    finally:
        os.close(descriptor)


def _decode_token(raw: bytes, *, label: str) -> str:
    if raw.endswith(b"\r\n"):
        raw = raw[:-2]
    elif raw.endswith(b"\n"):
        raw = raw[:-1]
    try:
        token = raw.decode("ascii", errors="strict")
        bearer_sha256(token)
    except (UnicodeDecodeError, ValueError):
        raise VerificationError(f"{label} is invalid") from None
    return token


def _connect_readonly(path: Path) -> sqlite3.Connection:
    if not path.is_absolute():
        raise VerificationError("DB path must be absolute")
    try:
        info = path.lstat()
    except OSError:
        raise VerificationError("DB is unavailable") from None
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        raise VerificationError("DB must be a regular non-symlink file")
    uri = f"file:{quote(str(path), safe='/')}?mode=ro"
    try:
        connection = sqlite3.connect(uri, uri=True, timeout=2)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA query_only=ON")
        return connection
    except sqlite3.Error:
        raise VerificationError("DB could not be opened read-only") from None


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
    raise VerificationError("unsupported SQLite value type in snapshot")


def _snapshot(connection: sqlite3.Connection) -> dict[str, Any]:
    snapshot: dict[str, Any] = {"schema_version": 1, "tables": {}}
    for table in SNAPSHOT_TABLES:
        rows = connection.execute(f'SELECT * FROM "{table}"').fetchall()
        encoded_rows = [
            json.dumps(
                {key: _typed_value(value) for key, value in dict(row).items()},
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8")
            for row in rows
        ]
        encoded_rows.sort()
        digest = hashlib.sha256()
        for encoded in encoded_rows:
            digest.update(len(encoded).to_bytes(8, "big"))
            digest.update(encoded)
        snapshot["tables"][table] = {"count": len(encoded_rows), "sha256": digest.hexdigest()}
    return snapshot


def _active_relay_devices(path: Path) -> dict[str, dict[str, Any]]:
    raw = _read_untrusted_regular(path, label="Relay devices file", max_bytes=2_097_152)
    try:
        document = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=_unique_object)
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError):
        raise VerificationError("Relay devices file is invalid") from None
    if not isinstance(document, dict) or document.get("schema_version") != 1 or not isinstance(document.get("devices"), list):
        raise VerificationError("Relay devices file has an invalid schema")
    active: dict[str, dict[str, Any]] = {}
    for device in document["devices"]:
        if not isinstance(device, dict):
            raise VerificationError("Relay devices file has an invalid record")
        if device.get("revoked_at") is None:
            device_id = device.get("device_id")
            if not isinstance(device_id, str) or not device_id or len(device_id) > 256 or device_id in active:
                raise VerificationError("Relay active device IDs are invalid or duplicated")
            active[device_id] = device
    if len(active) != 1 or next(iter(active.values())).get("label") != "pixel-v7":
        raise VerificationError("Relay registry must contain exactly one active pixel-v7 device")
    return active


def _json_document(raw: bytes, *, label: str) -> dict[str, Any]:
    try:
        document = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=_unique_object)
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError):
        raise VerificationError(f"{label} is invalid JSON") from None
    if not isinstance(document, dict):
        raise VerificationError(f"{label} must be a JSON object")
    return document


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def _redacted(value: Any) -> str:
    if not isinstance(value, str):
        return "<missing>"
    return value[:7] + "…" + value[-3:] if len(value) > 12 else "<redacted>"


def _no_secret_fields(value: Any) -> bool:
    forbidden = {"token", "token_hash", "relay_token", "tool_token", "secret", "hmac"}
    if isinstance(value, dict):
        return all(str(key).lower() not in forbidden and _no_secret_fields(item) for key, item in value.items())
    if isinstance(value, list):
        return all(_no_secret_fields(item) for item in value)
    return True


def main() -> int:
    args = _parser().parse_args()
    checks: list[dict[str, str]] = []

    def check(name: str, operation: Callable[[], Any]) -> Any:
        try:
            result = operation()
            checks.append({"name": name, "status": "PASS"})
            return result
        except Exception as exc:
            # Only locally-authored validation messages are emitted, never source data.
            reason = str(exc) if isinstance(exc, VerificationError) else "verification operation failed"
            checks.append({"name": name, "status": "FAIL", "reason": reason[:160]})
            return None

    relay_raw = check("relay_credential_file", lambda: _private_bytes(args.relay_credential, label="RELAY credential", max_bytes=4098))
    tool_raw = check("tool_credential_file", lambda: _private_bytes(args.tool_token_file, label="TOOL credential", max_bytes=4098))
    map_raw = check("principal_map_file", lambda: _private_bytes(args.map, label="principal map", max_bytes=262_144))
    receipt_raw = check("receipt_file", lambda: _private_bytes(args.receipt, label="provision receipt", max_bytes=262_144))
    snapshot_raw = check("snapshot_file", lambda: _private_bytes(args.snapshot, label="pre-mutation snapshot", max_bytes=262_144))
    relay_token = check("relay_token_format", lambda: _decode_token(relay_raw, label="RELAY credential")) if relay_raw is not None else None
    tool_token = check("tool_token_format", lambda: _decode_token(tool_raw, label="TOOL credential")) if tool_raw is not None else None
    mapping = check("map_json", lambda: _json_document(map_raw, label="principal map")) if map_raw is not None else None
    receipt = check("receipt_json", lambda: _json_document(receipt_raw, label="provision receipt")) if receipt_raw is not None else None
    snapshot = check("snapshot_json", lambda: _json_document(snapshot_raw, label="pre-mutation snapshot")) if snapshot_raw is not None else None
    if receipt is not None:
        check("receipt_has_no_secrets", lambda: True if _no_secret_fields(receipt) else (_ for _ in ()).throw(VerificationError("receipt contains a secret field")))

    active_devices = check("active_relay_device_set", lambda: _active_relay_devices(args.relay_devices_file))
    connection = None
    if args.db.is_absolute():
        connection = check("readonly_database_open", lambda: _connect_readonly(args.db))
    else:
        checks.append({"name": "readonly_database_open", "status": "FAIL", "reason": "DB path must be absolute"})

    if connection is not None:
        def db_health() -> None:
            if int(connection.execute("PRAGMA user_version").fetchone()[0]) != 5:
                raise VerificationError("DB schema is not v5")
            if connection.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                raise VerificationError("DB integrity check failed")
            if connection.execute("PRAGMA foreign_key_check").fetchone() is not None:
                raise VerificationError("DB foreign key check failed")
        check("schema_integrity_foreign_keys", db_health)

        def count_oracle() -> None:
            actual = {
                "principals": int(connection.execute("SELECT COUNT(*) FROM principals_v4").fetchone()[0]),
                "credentials": int(connection.execute("SELECT COUNT(*) FROM credentials_v4").fetchone()[0]),
                "bindings": int(connection.execute("SELECT COUNT(*) FROM relay_principal_bindings_v4").fetchone()[0]),
                "grants": int(connection.execute("SELECT COUNT(*) FROM principal_capabilities_v4").fetchone()[0]),
                "events": int(connection.execute("SELECT COUNT(*) FROM principal_events_v4").fetchone()[0]),
            }
            expected_events = {"CAPABILITY_CHANGED": 20, "PROVISIONED": 4, "RELAY_BOUND": 1}
            events = {
                str(row["event_type"]): int(row["count"])
                for row in connection.execute("SELECT event_type,COUNT(*) AS count FROM principal_events_v4 GROUP BY event_type")
            }
            if actual != {"principals": 2, "credentials": 2, "bindings": 1, "grants": 20, "events": 25}:
                raise VerificationError("registry counts differ from 2/2/1/20/25")
            if events != expected_events:
                raise VerificationError("event counts differ from 4/20/1")
            if connection.execute("SELECT 1 FROM credentials_v4 WHERE audience IN ('DIRECT','LEGACY') LIMIT 1").fetchone():
                raise VerificationError("DIRECT or LEGACY credential exists")
            if connection.execute("SELECT 1 FROM principals_v4 WHERE kind='LEGACY' LIMIT 1").fetchone():
                raise VerificationError("LEGACY principal exists")
            if connection.execute("SELECT 1 FROM principal_capabilities_v4 WHERE granted!=1 LIMIT 1").fetchone():
                raise VerificationError("a capability grant is inactive")
        check("registry_counts_and_event_types", count_oracle)

        def identity_oracle() -> None:
            if receipt is None:
                raise VerificationError("receipt is unavailable")
            if set(receipt) != {
                "schema_version", "principal_ids", "device_refs", "credential_ids", "audiences", "relay_device_id",
                "capabilities", "counts", "credential_container_path", "snapshot_file", "registry_counts",
            } or receipt.get("schema_version") != 1:
                raise VerificationError("receipt schema is invalid")
            principal_ids = receipt.get("principal_ids")
            device_refs = receipt.get("device_refs")
            credential_ids = receipt.get("credential_ids")
            if not all(isinstance(item, dict) for item in (principal_ids, device_refs, credential_ids)):
                raise VerificationError("receipt identity fields are invalid")
            if set(principal_ids) != {"ANDROID", "TOOL"} or set(device_refs) != {"ANDROID", "TOOL"}:
                raise VerificationError("receipt principal set is invalid")
            if set(credential_ids) != {"RELAY", "TOOL"}:
                raise VerificationError("receipt credential set is invalid")
            if receipt.get("counts") != {
                "principals": 2, "credentials": 2, "bindings": 1, "grants": 20, "events": 25,
            } or receipt.get("registry_counts") != receipt.get("counts"):
                raise VerificationError("receipt registry counts are invalid")
            principals = {
                str(row["principal_id"]): dict(row)
                for row in connection.execute("SELECT principal_id,device_ref,kind,state FROM principals_v4")
            }
            expected_principals = {
                principal_ids["ANDROID"]: (device_refs["ANDROID"], "ANDROID"),
                principal_ids["TOOL"]: (device_refs["TOOL"], "TOOL"),
            }
            if len(principals) != 2:
                raise VerificationError("principal row set is invalid")
            for principal_id, (device_ref, kind) in expected_principals.items():
                row = principals.get(principal_id)
                if row is None or (row["device_ref"], row["kind"], row["state"]) != (device_ref, kind, "ACTIVE"):
                    raise VerificationError("principal kind, device_ref, or state does not match receipt")
            credentials = connection.execute(
                "SELECT credential_id,principal_id,token_hash,hash_algorithm,audience,state FROM credentials_v4"
            ).fetchall()
            expected = {
                credential_ids["RELAY"]: (principal_ids["ANDROID"], "RELAY", relay_token),
                credential_ids["TOOL"]: (principal_ids["TOOL"], "TOOL", tool_token),
            }
            if len(credentials) != 2:
                raise VerificationError("credential row set is invalid")
            for row in credentials:
                expected_row = expected.get(str(row["credential_id"]))
                if expected_row is None:
                    raise VerificationError("unexpected credential ID")
                principal_id, audience, token = expected_row
                if token is None or (row["principal_id"], row["audience"], row["state"], row["hash_algorithm"]) != (
                    principal_id, audience, "ACTIVE", "SHA-256"
                ) or not hmac.compare_digest(hashlib.sha256(token.encode("ascii")).hexdigest(), row["token_hash"]):
                    raise VerificationError("credential audience, owner, state, or hash mismatch")
            if receipt.get("audiences") != {"RELAY": "RELAY", "TOOL": "TOOL"}:
                raise VerificationError("receipt audiences are invalid")
        check("principal_credential_kinds_audiences_and_hashes", identity_oracle)

        def binding_oracle() -> None:
            if receipt is None or active_devices is None:
                raise VerificationError("receipt or active Relay set is unavailable")
            relay_id = receipt.get("relay_device_id")
            if set(active_devices) != {relay_id}:
                raise VerificationError("active Relay device set differs from provision receipt")
            expected_android = receipt.get("principal_ids", {}).get("ANDROID")
            rows = connection.execute("SELECT relay_device_id,principal_id,state FROM relay_principal_bindings_v4").fetchall()
            if len(rows) != 1 or (rows[0]["relay_device_id"], rows[0]["principal_id"], rows[0]["state"]) != (
                relay_id, expected_android, "ACTIVE"
            ):
                raise VerificationError("Relay binding does not match active device and Android principal")
            tool_id = receipt.get("principal_ids", {}).get("TOOL")
            if connection.execute("SELECT 1 FROM relay_principal_bindings_v4 WHERE principal_id=?", (tool_id,)).fetchone():
                raise VerificationError("TOOL principal has a Relay binding")
        check("binding_exact_active_device_and_no_tool_binding", binding_oracle)

        def map_coverage_oracle() -> None:
            if mapping is None or receipt is None or active_devices is None:
                raise VerificationError("map, receipt, or active Relay set is unavailable")
            if set(mapping) != set(active_devices):
                raise VerificationError("principal map does not cover exactly the active Relay device set")
            android_id = receipt.get("principal_ids", {}).get("ANDROID")
            credential_path = receipt.get("credential_container_path")
            if (
                not isinstance(credential_path, str)
                or not credential_path.startswith("/")
                or "\0" in credential_path
                or os.path.normpath(credential_path) != credential_path
            ):
                raise VerificationError("receipt credential container path is invalid")
            for entry in mapping.values():
                if not isinstance(entry, dict) or set(entry) != {"principal_id", "credential_file"}:
                    raise VerificationError("map entry has an invalid shape or unexpected field")
                if entry.get("principal_id") != android_id:
                    raise VerificationError("map entry does not point to the Android principal")
                if entry.get("credential_file") != credential_path:
                    raise VerificationError("map credential path differs from receipt")
            if not _no_secret_fields(mapping):
                raise VerificationError("map contains a secret field")
        check("principal_map_coverage", map_coverage_oracle)

        def capability_oracle() -> None:
            if receipt is None:
                raise VerificationError("receipt is unavailable")
            expected = {
                receipt.get("principal_ids", {}).get("ANDROID"): set(ANDROID_RELAY_CAPABILITIES),
                receipt.get("principal_ids", {}).get("TOOL"): set(TOOL_RELAY_CAPABILITIES),
            }
            actual = {principal_id: set() for principal_id in expected}
            for row in connection.execute("SELECT principal_id,capability,granted FROM principal_capabilities_v4"):
                if row["principal_id"] not in actual or int(row["granted"]) != 1:
                    raise VerificationError("unexpected principal capability row")
                actual[row["principal_id"]].add(str(row["capability"]))
            if actual != expected:
                raise VerificationError("Android or TOOL capability set differs from contract")
            if receipt.get("capabilities") != {
                "ANDROID": sorted(ANDROID_RELAY_CAPABILITIES),
                "TOOL": sorted(TOOL_RELAY_CAPABILITIES),
            }:
                raise VerificationError("receipt capability sets are invalid")
        check("exact_android_and_tool_capabilities", capability_oracle)

        def event_linkage_oracle() -> None:
            rows = connection.execute("SELECT principal_id,credential_id,event_type,metadata_json FROM principal_events_v4").fetchall()
            decoded = [
                (row["principal_id"], row["credential_id"], row["event_type"], json.loads(row["metadata_json"]))
                for row in rows
            ]
            if receipt is None:
                raise VerificationError("receipt is unavailable")
            principal_ids = receipt["principal_ids"]
            credential_ids = receipt["credential_ids"]
            principal_events = {
                (principal_id, metadata.get("kind"))
                for principal_id, credential_id, kind, metadata in decoded
                if kind == "PROVISIONED" and credential_id is None
            }
            if principal_events != {
                (principal_ids["ANDROID"], "ANDROID"), (principal_ids["TOOL"], "TOOL"),
            }:
                raise VerificationError("principal PROVISIONED event linkage is invalid")
            credential_events = {
                (credential_id, metadata.get("audience"))
                for _principal_id, credential_id, kind, metadata in decoded
                if kind == "PROVISIONED" and credential_id is not None
            }
            if credential_events != {
                (credential_ids["RELAY"], "RELAY"), (credential_ids["TOOL"], "TOOL"),
            }:
                raise VerificationError("credential PROVISIONED event linkage is invalid")
            changes = [row for row in decoded if row[2] == "CAPABILITY_CHANGED"]
            if len(changes) != 20 or any(row[1] is not None for row in changes):
                raise VerificationError("CAPABILITY_CHANGED events must have no credential ID")
            expected_changes = Counter(
                [(principal_ids["ANDROID"], capability, True) for capability in ANDROID_RELAY_CAPABILITIES]
                + [(principal_ids["TOOL"], capability, True) for capability in TOOL_RELAY_CAPABILITIES]
            )
            actual_changes = []
            for principal_id, _credential_id, _kind, metadata in changes:
                if not isinstance(metadata, dict):
                    raise VerificationError("CAPABILITY_CHANGED event metadata is invalid")
                actual_changes.append((principal_id, metadata.get("capability"), metadata.get("granted")))
            if Counter(actual_changes) != expected_changes:
                raise VerificationError("CAPABILITY_CHANGED events differ from exact Android and TOOL grants")
            relay_events = [row for row in decoded if row[2] == "RELAY_BOUND"]
            if len(relay_events) != 1 or relay_events[0][1] is not None or relay_events[0][0] != principal_ids["ANDROID"]:
                raise VerificationError("RELAY_BOUND event linkage is invalid")
            if relay_events[0][3].get("relay_device_id") != receipt.get("relay_device_id"):
                raise VerificationError("RELAY_BOUND event device does not match receipt")
            if any(kind not in {"PROVISIONED", "CAPABILITY_CHANGED", "RELAY_BOUND"} for _pid, _cid, kind, _meta in decoded):
                raise VerificationError("unexpected principal event type")
        check("event_linkage", event_linkage_oracle)

        def unchanged_snapshot() -> None:
            if snapshot is None or snapshot.get("schema_version") != 1:
                raise VerificationError("snapshot is invalid")
            if snapshot != _snapshot(connection):
                raise VerificationError("consent/proposal/ack hashes differ from pre-mutation snapshot")
        check("consent_proposal_ack_snapshot_hashes", unchanged_snapshot)

    if args.keyring_file is not None:
        def keyring_oracle() -> None:
            keys = load_assertion_keys(raw_json=None, file_path=str(args.keyring_file), testing=False)
            if not keys or len(keys) > 2 or len(set(keys.values())) != len(keys):
                raise VerificationError("assertion keyring must have one or two distinct key secrets")
        check("assertion_keyring_distinct_secrets", keyring_oracle)
    elif args.require_keyring:
        checks.append({"name": "assertion_keyring_distinct_secrets", "status": "FAIL",
                       "reason": "keyring file is required for this cutover"})

    if connection is not None:
        connection.close()
    passed = all(item["status"] == "PASS" for item in checks)
    redacted = {}
    if receipt is not None:
        ids = receipt.get("principal_ids", {})
        if isinstance(ids, dict):
            redacted = {key.lower() + "_principal": _redacted(value) for key, value in ids.items()}
    print(json.dumps({"status": "PASS" if passed else "FAIL", "checks": checks, "redacted": redacted}, sort_keys=True))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
