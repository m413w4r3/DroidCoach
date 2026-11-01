#!/usr/bin/env python3
"""Run one V7-06C Android QA action and independently verify Core and Relay state."""

from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import subprocess
import sys
import uuid
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[3]
ANDROID_RUNNER = ROOT / "scripts" / "v7-06b" / "android" / "run_qa.py"
BACKEND_SCRIPTS = ROOT / "scripts" / "v7-06c" / "backend"
if str(BACKEND_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(BACKEND_SCRIPTS))

from stack import StackError, load_and_verify_root  # noqa: E402

PRODUCT_PACKAGE = "dev.atlas.coach"
QA_PACKAGE_PREFIX = "dev.atlas.coach.qa.v706b."
ALLOWED_TOOLS = {
    "atlas_get_day_plan", "atlas_get_activity_status", "atlas_propose_plan_revision",
    "atlas_get_usage_summary", "atlas_get_goal_progress", "atlas_get_training_plan",
}
FORBIDDEN_TOOLS = {
    "exec", "shell", "process", "code_execution", "read", "write", "edit", "apply_patch",
    "gateway", "session_status", "presence", "wait", "ls",
}
SAFE_TRACE_FIELDS = {
    "model", "request_id_hash", "conversation_id_hash", "message_roles", "role_tool_present",
    "role_tool_call_id_hashes", "requested_tool_names", "requested_tool_count", "finish_reason",
    "returned_tool_names", "returned_tool_call_id_hashes", "http_status", "request_bytes",
    "response_bytes", "duration_ms", "recorded_at", "index", "upstream_error",
    "request_json_valid", "response_json_valid",
}
COMPLETED_OUTCOMES = {"completed", "completed_redacted"}
SECRET_FIELD_PATTERN = re.compile(r"(?i)\b(?:device_)?token\b\s*[:=]\s*([^\s,}]+)")
BEARER_PATTERN = re.compile(r"(?i)\bBearer\s+[^\s,}]+")


class ScenarioFailure(RuntimeError):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_target_package(package: str) -> str:
    if package == PRODUCT_PACKAGE or not package.startswith(QA_PACKAGE_PREFIX):
        raise ScenarioFailure("PACKAGE_OUTSIDE_V7_06B_QA_NAMESPACE")
    suffix = package[len("dev.atlas.coach"):]
    if not re.fullmatch(r"\.qa\.v706b\.v706c(?:apply|reject)\.[0-9a-f]{8,12}", suffix):
        raise ScenarioFailure("QA_SUFFIX_INVALID")
    return package


def action_suffix(action: str, suffix: str | None = None) -> str:
    if action not in {"apply", "reject"}:
        raise ScenarioFailure("ACTION_INVALID")
    value = suffix if suffix is not None else f".qa.v706b.v706c{action}.{uuid.uuid4().hex[:12]}"
    if not re.fullmatch(r"\.qa\.v706b\.v706c(?:apply|reject)\.[0-9a-f]{8,12}", value):
        raise ScenarioFailure("QA_SUFFIX_INVALID")
    if f".v706c{action}." not in value:
        raise ScenarioFailure("QA_SUFFIX_ACTION_MISMATCH")
    package = f"{PRODUCT_PACKAGE}{value}"
    validate_target_package(package)
    return value


def readonly_db(path: Path) -> sqlite3.Connection:
    uri = path.resolve(strict=True).as_uri() + "?mode=ro"
    connection = sqlite3.connect(uri, uri=True)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA query_only=ON")
    return connection


def parse_android_relay_url(relay_url: str) -> tuple[str, dict]:
    if not isinstance(relay_url, str) or "?" in relay_url or "#" in relay_url:
        raise ScenarioFailure("ANDROID_RELAY_URL_INVALID")
    try:
        parsed = urlsplit(relay_url)
        host = parsed.hostname
        port = parsed.port
    except (TypeError, ValueError):
        raise ScenarioFailure("ANDROID_RELAY_URL_INVALID") from None
    if (parsed.scheme not in {"ws", "wss"} or not host or parsed.username is not None
            or parsed.password is not None or parsed.query or parsed.fragment
            or parsed.netloc.endswith(":")
            or (port is not None and not 1 <= port <= 65535)):
        raise ScenarioFailure("ANDROID_RELAY_URL_INVALID")
    try:
        ipaddress.ip_address(host)
        valid_host = True
    except ValueError:
        try:
            ascii_host = host.encode("idna").decode("ascii")
        except UnicodeError:
            ascii_host = ""
        label = r"[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?"
        valid_host = bool(re.fullmatch(rf"{label}(?:\.{label})*\.?", ascii_host)) and len(ascii_host) <= 254
    if not valid_host:
        raise ScenarioFailure("ANDROID_RELAY_URL_INVALID")
    mode = "public_wss" if parsed.scheme == "wss" else "loopback_ws"
    if parsed.scheme == "ws":
        try:
            loopback = ipaddress.ip_address(host).is_loopback
        except ValueError:
            loopback = host.lower() == "localhost"
        if not loopback and host != "10.0.2.2":
            raise ScenarioFailure("ANDROID_RELAY_WS_HOST_NOT_ALLOWED")
    else:
        try:
            public_host = ipaddress.ip_address(host).is_global
        except ValueError:
            normalized_host = host.rstrip(".").lower()
            public_host = not normalized_host.endswith((".localhost", ".local", ".internal")) and normalized_host != "localhost"
        if not public_host:
            raise ScenarioFailure("ANDROID_RELAY_WSS_HOST_NOT_PUBLIC")
    endpoint = {"scheme": parsed.scheme, "host": host,
                "port": port or (443 if parsed.scheme == "wss" else 80)}
    return mode, endpoint


def _read_core_state(core_db: Path, action: str) -> dict:
    expected_proposal_state = "APPLIED" if action == "apply" else "REJECTED"
    connection = readonly_db(core_db)
    try:
        proposals = connection.execute(
            "SELECT proposal_id,plan_id,base_version,state,proposal_json FROM agent_proposals_v4"
        ).fetchall()
        events = connection.execute(
            "SELECT ack_id,proposal_id,sequence,state,result_code,applied_plan_version,revision_id,"
            "scope_hash,effect_ids_json,occurred_at,received_at,body_hash FROM agent_proposal_events_v4"
        ).fetchall()
        if len(proposals) != 1 or len(events) != 1:
            raise ScenarioFailure("CORE_PROPOSAL_ACK_CARDINALITY")
        proposal = dict(proposals[0])
        event = dict(events[0])
        proposal_json = json.loads(proposal["proposal_json"])
        if proposal["state"] != expected_proposal_state or int(proposal["base_version"]) != 2:
            raise ScenarioFailure("CORE_PROPOSAL_STATE_MISMATCH")
        if proposal_json.get("confirmation_mode") != "A0_USER_REVIEW" or proposal_json.get("auto_application") != "NONE_AT_CORE":
            raise ScenarioFailure("CORE_CONFIRMATION_POLICY_MISMATCH")
        changes = proposal_json.get("changes")
        if not isinstance(changes, list) or len(changes) != 1 or not isinstance(changes[0], dict):
            raise ScenarioFailure("CORE_PROPOSAL_CHANGE_COUNT")
        if (changes[0].get("op") != "reschedule"
                or changes[0].get("target_start_minute") != 610
                or changes[0].get("duration_minutes", 45) != 45):
            raise ScenarioFailure("CORE_PROPOSAL_RESCHEDULE_MISMATCH")
        if event["proposal_id"] != proposal["proposal_id"]:
            raise ScenarioFailure("CORE_ACK_PROPOSAL_MISMATCH")
        expected_ack_state = "APPLIED" if action == "apply" else "REJECTED"
        expected_result = "APPLIED" if action == "apply" else "USER_REJECTED"
        if event["state"] != expected_ack_state or event["result_code"] != expected_result:
            raise ScenarioFailure("CORE_ACK_STATE_MISMATCH")
        if action == "apply":
            if int(event["applied_plan_version"] or 0) != 3:
                raise ScenarioFailure("CORE_ACK_APPLIED_VERSION_MISMATCH")
            try:
                revision_id = str(uuid.UUID(str(event["revision_id"])))
                effects = json.loads(event["effect_ids_json"])
            except (ValueError, TypeError, json.JSONDecodeError):
                raise ScenarioFailure("CORE_ACK_PROOF_INVALID") from None
            if revision_id != event["revision_id"] or not re.fullmatch(r"sha256:[0-9a-f]{64}", str(event["scope_hash"])):
                raise ScenarioFailure("CORE_ACK_PROOF_INVALID")
            if not isinstance(effects, list) or not any(isinstance(effect, str) and effect.startswith("PLAN_APPLY:") for effect in effects):
                raise ScenarioFailure("CORE_ACK_EFFECT_MISSING")
        elif event["applied_plan_version"] is not None or event["revision_id"] is not None or event["scope_hash"] is not None:
            raise ScenarioFailure("CORE_REJECT_ACK_HAS_APPLICATION_PROOF")

        plan_id = str(proposal["plan_id"])
        snapshot_rows = connection.execute(
            "SELECT plan_version,revision_id,observed_at,projection_json "
            "FROM day_plan_snapshots_v4 WHERE plan_id=? ORDER BY plan_version",
            (plan_id,),
        ).fetchall()
        snapshot_rows_by_version = {int(row["plan_version"]): dict(row) for row in snapshot_rows}
        snapshots = {version: json.loads(row["projection_json"])
                     for version, row in snapshot_rows_by_version.items()}
        expected_versions = {2, 3} if action == "apply" else {2}
        if set(snapshots) != expected_versions:
            raise ScenarioFailure("CORE_PLAN_SNAPSHOT_VERSIONS")
        starts = {}
        for version, snapshot in snapshots.items():
            activities = snapshot.get("activities") if isinstance(snapshot, dict) else None
            if not isinstance(activities, list) or len(activities) != 1:
                raise ScenarioFailure("CORE_PLAN_ACTIVITY_COUNT")
            schedule = activities[0].get("scheduled_local")
            if not isinstance(schedule, dict) or schedule.get("duration_minutes") != 45:
                raise ScenarioFailure("CORE_PLAN_DURATION_MISMATCH")
            starts[version] = schedule.get("start_minute")
        if starts.get(2) != 600 or (action == "apply" and starts.get(3) != 610):
            raise ScenarioFailure("CORE_PLAN_SNAPSHOT_SCHEDULE_MISMATCH")
        if action == "apply":
            revision = event["revision_id"]
            version_three = snapshot_rows_by_version[3]
            if (version_three["revision_id"] != revision
                    or snapshots[3].get("current_revision_id") != revision):
                raise ScenarioFailure("CORE_PLAN_V3_REVISION_MISMATCH")
            try:
                observed_at = datetime.fromisoformat(
                    str(version_three["observed_at"]).replace("Z", "+00:00")
                )
                if observed_at.tzinfo is None:
                    raise ValueError
            except (TypeError, ValueError):
                raise ScenarioFailure("CORE_PLAN_V3_OBSERVED_AT_INVALID") from None
            if observed_at.astimezone(timezone.utc) > datetime.now(timezone.utc) + timedelta(minutes=2):
                raise ScenarioFailure("CORE_PLAN_V3_OBSERVED_AT_IN_FUTURE")

        states = connection.execute(
            "SELECT category,consent_version,state FROM consent_states_v4 "
            "WHERE category IN ('DAY_PLAN','ACTIVITY_STATUS')"
        ).fetchall()
        state_by_category = {row["category"]: (int(row["consent_version"]), row["state"]) for row in states}
        if set(state_by_category) != {"DAY_PLAN", "ACTIVITY_STATUS"} or any(
            version < 3 or state != "REVOKED" for version, state in state_by_category.values()
        ):
            raise ScenarioFailure("CORE_CONSENT_NOT_REVOKED")
        consent_events = connection.execute(
            "SELECT category,consent_version,state FROM consent_state_events_v4 "
            "WHERE category IN ('DAY_PLAN','ACTIVITY_STATUS')"
        ).fetchall()
        consent_tuples = [(row["category"], int(row["consent_version"]), row["state"]) for row in consent_events]
        expected_consent = {
            (category, version, state)
            for category in ("DAY_PLAN", "ACTIVITY_STATUS")
            for version, state in ((2, "GRANTED"), (3, "REVOKED"))
        }
        if len(consent_tuples) != 4 or set(consent_tuples) != expected_consent:
            raise ScenarioFailure("CORE_CONSENT_EVENT_HISTORY_MISMATCH")
        if int(connection.execute("SELECT COUNT(*) FROM consent_state_events_v4").fetchone()[0]) != 4:
            raise ScenarioFailure("CORE_CONSENT_EVENT_TOTAL_COUNT")
        pending_pull_count = int(connection.execute(
            "SELECT COUNT(*) FROM agent_proposals_v4 "
            "WHERE state IN ('PENDING_ANDROID','RECEIVED','AWAITING_USER','UNCERTAIN')"
        ).fetchone()[0])
        if pending_pull_count != 0:
            raise ScenarioFailure("CORE_PULL_PROPOSALS_NOT_EMPTY")
        total_snapshots = int(connection.execute("SELECT COUNT(*) FROM day_plan_snapshots_v4").fetchone()[0])
        if total_snapshots != (2 if action == "apply" else 1):
            raise ScenarioFailure("CORE_TOTAL_SNAPSHOT_COUNT")
    finally:
        connection.close()
    return {
        "proposal_id": proposal["proposal_id"],
        "plan_id": plan_id,
        "proposal_state": proposal["state"],
        "base_version": int(proposal["base_version"]),
        "change_count": 1,
        "target_start_minute": 610,
        "duration_minutes": 45,
        "ack_id": event["ack_id"],
        "ack_event_count": 1,
        "ack_state": event["state"],
        "ack_result_code": event["result_code"],
        "applied_plan_version": event["applied_plan_version"],
        "snapshot_versions": sorted(snapshots),
        "snapshot_starts": {str(key): starts[key] for key in sorted(starts)},
        "consent_states": {key: {"version": value[0], "state": value[1]} for key, value in state_by_category.items()},
        "consent_event_count": len(consent_tuples),
        "pending_proposal_count": pending_pull_count,
        "snapshot_count": total_snapshots,
        "ack_event": event,
    }


def verify_operation_journal(connector_state_dir: Path, action: str, core: dict) -> dict:
    journal_path = connector_state_dir / "operations.json"
    if journal_path.is_symlink() or not journal_path.is_file() or stat.S_IMODE(journal_path.stat().st_mode) != 0o600:
        raise ScenarioFailure("CONNECTOR_JOURNAL_MISSING_OR_NOT_PRIVATE")
    try:
        journal = json.loads(journal_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        raise ScenarioFailure("CONNECTOR_JOURNAL_INVALID") from None
    entries = journal.get("entries") if isinstance(journal, dict) and journal.get("schema_version") == 1 else None
    if not isinstance(entries, list):
        raise ScenarioFailure("CONNECTOR_JOURNAL_INVALID")
    ack_entries = [item for item in entries if isinstance(item, dict) and item.get("operation") == "v4.proposals.ack"]
    if len(ack_entries) != 1 or ack_entries[0].get("idempotencyKey") != core["ack_id"]:
        raise ScenarioFailure("CONNECTOR_ACK_JOURNAL_CARDINALITY")
    if ack_entries[0].get("outcome") not in COMPLETED_OUTCOMES:
        raise ScenarioFailure("CONNECTOR_ACK_NOT_COMPLETED")
    sensitive_operations = [item for item in entries if isinstance(item, dict) and item.get("operation") in {
        "v4.proposals.ack", "v4.plan.snapshot.upsert",
    }]
    if any(item.get("outcome") in {"uncertain", "in_flight"} for item in sensitive_operations):
        raise ScenarioFailure("CONNECTOR_ACK_OR_SNAPSHOT_UNCERTAIN")
    snapshot_entries = [item for item in sensitive_operations if item.get("operation") == "v4.plan.snapshot.upsert"]
    expected_snapshot_count = 2 if action == "apply" else 1
    if len(snapshot_entries) != expected_snapshot_count or any(item.get("outcome") not in COMPLETED_OUTCOMES for item in snapshot_entries):
        raise ScenarioFailure("CONNECTOR_SNAPSHOT_JOURNAL_MISSING")
    chats = [item for item in entries if isinstance(item, dict) and item.get("operation") == "v4.coach.chat.request"]
    if not chats or not any(item.get("outcome") in COMPLETED_OUTCOMES for item in chats):
        raise ScenarioFailure("CONNECTOR_CHAT_NOT_COMPLETED")
    return {
        "ack_count": len(ack_entries),
        "ack_outcome": ack_entries[0]["outcome"],
        "snapshot_completed_count": len(snapshot_entries),
        "chat_completed_count": sum(item.get("outcome") in COMPLETED_OUTCOMES for item in chats),
        "uncertain_ack_or_snapshot_count": 0,
    }


def verify_tool_trace(path: Path, gateway_agent: str) -> dict:
    if path.is_symlink() or not path.is_file() or stat.S_IMODE(path.stat().st_mode) != 0o600:
        raise ScenarioFailure("PROXY_TRACE_MISSING_OR_NOT_PRIVATE")
    try:
        records = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    except (OSError, json.JSONDecodeError):
        raise ScenarioFailure("PROXY_TRACE_INVALID") from None
    if not records:
        raise ScenarioFailure("PROXY_TRACE_EMPTY")
    expected_model = f"openclaw/{gateway_agent}"
    if any(not isinstance(item, dict) or set(item) - SAFE_TRACE_FIELDS for item in records):
        raise ScenarioFailure("PROXY_TRACE_CONTAINS_UNSAFE_FIELDS")
    for item in records:
        if item.get("model") != expected_model:
            raise ScenarioFailure("PROXY_TRACE_MODEL_MISMATCH")
        requested = item.get("requested_tool_names")
        returned = item.get("returned_tool_names", [])
        if not isinstance(requested, list) or any(not isinstance(name, str) for name in requested):
            raise ScenarioFailure("PROXY_TRACE_TOOL_DEFINITION_MISMATCH")
        if len(set(requested)) != len(requested):
            raise ScenarioFailure("PROXY_TRACE_TOOL_DEFINITION_DUPLICATE")
        if set(requested) - ALLOWED_TOOLS:
            raise ScenarioFailure("PROXY_TRACE_FORBIDDEN_TOOL_DEFINITION")
        if item.get("requested_tool_count") != len(requested) or len(requested) != 6 or set(requested) != ALLOWED_TOOLS:
            raise ScenarioFailure("PROXY_TRACE_TOOL_DEFINITION_MISMATCH")
        if not isinstance(returned, list) or any(not isinstance(name, str) for name in returned):
            raise ScenarioFailure("PROXY_TRACE_TOOL_RESPONSE_INVALID")
        if set(returned) & FORBIDDEN_TOOLS:
            raise ScenarioFailure("PROXY_TRACE_FORBIDDEN_TOOL_RETURNED")
        if set(returned) - ALLOWED_TOOLS:
            raise ScenarioFailure("PROXY_TRACE_UNEXPECTED_TOOL_RETURNED")
        for field in ("role_tool_call_id_hashes", "returned_tool_call_id_hashes"):
            values = item.get(field, [])
            if not isinstance(values, list) or any(not isinstance(value, str) for value in values):
                raise ScenarioFailure("PROXY_TRACE_TOOL_CALL_ID_INVALID")
    returned_names = {name for item in records for name in item.get("returned_tool_names", [])}
    if not {"atlas_get_day_plan", "atlas_propose_plan_revision"}.issubset(returned_names):
        raise ScenarioFailure("PROXY_TRACE_EXPECTED_TOOL_CALLS_MISSING")
    known_ids: set[str] = set()
    correlated = False
    for item in records:
        role_ids = set(item.get("role_tool_call_id_hashes", []))
        if item.get("role_tool_present") and role_ids & known_ids:
            correlated = True
        known_ids.update(item.get("returned_tool_call_id_hashes", []))
    if not correlated:
        raise ScenarioFailure("PROXY_TRACE_TOOL_CALL_ID_UNCORRELATED")
    if records[-1].get("finish_reason") != "stop":
        raise ScenarioFailure("PROXY_TRACE_FINAL_COMPLETION_NOT_STOP")
    return {
        "trace_record_count": len(records),
        "model": expected_model,
        "requested_tool_count": 6,
        "requested_tool_names": sorted(ALLOWED_TOOLS),
        "returned_required_tools": ["atlas_get_day_plan", "atlas_propose_plan_revision"],
        "tool_call_ids_correlated": True,
        "finish_reason": "stop",
        "forbidden_tool_count": 0,
    }


def write_private(path: Path, content: str | bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    data = content.encode("utf-8") if isinstance(content, str) else content
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
    except Exception:
        try:
            path.unlink()
        except FileNotFoundError:
            pass
        raise
    os.chmod(path, 0o600)


def redact_text(text: str, secrets: list[str]) -> str:
    for secret in sorted((value for value in secrets if value), key=len, reverse=True):
        text = text.replace(secret, "<redacted>")
    text = SECRET_FIELD_PATTERN.sub("<redacted>", text)
    return BEARER_PATTERN.sub("Bearer <redacted>", text)


def _adb(serial: str, *arguments: str, timeout: int = 20) -> str:
    try:
        result = subprocess.run(
            ["adb", "-s", serial, *arguments], stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=timeout, check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        raise ScenarioFailure("ADB_COMMAND_UNAVAILABLE") from None
    if result.returncode != 0:
        raise ScenarioFailure("ADB_COMMAND_FAILED")
    return result.stdout.strip()


def _device_metadata(serial: str, require_api: int) -> dict:
    if not isinstance(serial, str) or not serial.strip() or any(char.isspace() for char in serial):
        raise ScenarioFailure("EXPLICIT_SERIAL_REQUIRED")
    if _adb(serial, "get-state") != "device":
        raise ScenarioFailure("SELECTED_DEVICE_NOT_READY")
    is_emulator = _adb(serial, "shell", "getprop", "ro.kernel.qemu") == "1"
    if not is_emulator and os.environ.get("ATLAS_ALLOW_PHYSICAL_QA") != "1":
        raise ScenarioFailure("PHYSICAL_QA_NOT_AUTHORIZED")
    sdk_text = _adb(serial, "shell", "getprop", "ro.build.version.sdk")
    try:
        sdk = int(sdk_text)
    except ValueError:
        raise ScenarioFailure("DEVICE_API_UNAVAILABLE") from None
    if require_api and sdk != require_api:
        raise ScenarioFailure("DEVICE_API_MISMATCH")
    fingerprint = _adb(serial, "shell", "getprop", "ro.build.fingerprint")
    metadata = {
        "serial_hash": sha256_text(serial),
        "model": _adb(serial, "shell", "getprop", "ro.product.model"),
        "fingerprint_hash": sha256_text(fingerprint),
        "sdk": sdk,
        "abi": _adb(serial, "shell", "getprop", "ro.product.cpu.abi"),
        "is_emulator": is_emulator,
    }
    return metadata


def _read_stack_file(stack_path: Path, action: str) -> tuple[dict, Path, dict, list[str]]:
    if stack_path.is_symlink():
        raise ScenarioFailure("STACK_FILE_SYMLINK")
    resolved_file = stack_path.resolve(strict=True)
    if not resolved_file.is_file() or stat.S_IMODE(resolved_file.stat().st_mode) != 0o600:
        raise ScenarioFailure("STACK_FILE_NOT_PRIVATE")
    try:
        stack = json.loads(resolved_file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        raise ScenarioFailure("STACK_FILE_INVALID") from None
    if not isinstance(stack, dict):
        raise ScenarioFailure("STACK_FILE_INVALID")
    root = Path(str(stack.get("private_root", "")))
    scenario = "android-apply" if action == "apply" else "android-reject"
    try:
        lifecycle = load_and_verify_root(root, expected_marker=None)
    except StackError:
        raise ScenarioFailure("STACK_IDENTITY_INVALID") from None
    if lifecycle is None or lifecycle.get("scenario") != scenario or stack.get("scenario") != scenario:
        raise ScenarioFailure("STACK_SCENARIO_MISMATCH")
    resolved_root = root.resolve(strict=True)
    if resolved_file.parent != resolved_root:
        raise ScenarioFailure("STACK_FILE_OUTSIDE_PRIVATE_ROOT")
    required = ("relay_url_android", "relay_transport", "server_id", "server_public_key", "gateway_agent",
                "pairing_file", "core_db", "core_token_file", "tool_token_file", "proxy_trace",
                "connector_state_dir", "ports", "pids")
    if any(key not in stack for key in required):
        raise ScenarioFailure("STACK_INTERFACE_INCOMPLETE")
    if any(not isinstance(stack[key], str) or not stack[key] for key in
           ("relay_url_android", "server_id", "server_public_key", "gateway_agent")):
        raise ScenarioFailure("STACK_INTERFACE_INVALID")
    for key in ("pairing_file", "core_db", "core_token_file", "tool_token_file", "proxy_trace", "connector_state_dir"):
        unresolved = Path(str(stack[key]))
        if unresolved.is_symlink():
            raise ScenarioFailure("STACK_PATH_SYMLINK")
        candidate = unresolved.resolve(strict=False)
        if not candidate.is_relative_to(resolved_root):
            raise ScenarioFailure("STACK_PATH_OUTSIDE_PRIVATE_ROOT")
    for name in ("pairing_file", "core_token_file", "tool_token_file"):
        value = Path(stack[name])
        if value.is_symlink() or not value.is_file() or stat.S_IMODE(value.stat().st_mode) != 0o600:
            raise ScenarioFailure("STACK_CREDENTIAL_FILE_NOT_PRIVATE")
    core_db = Path(stack["core_db"])
    connector_state = Path(stack["connector_state_dir"])
    if (not core_db.is_file() or stat.S_IMODE(core_db.stat().st_mode) != 0o600
            or not connector_state.is_dir() or stat.S_IMODE(connector_state.stat().st_mode) != 0o700):
        raise ScenarioFailure("STACK_STATE_NOT_PRIVATE")
    try:
        pairing = json.loads(Path(stack["pairing_file"]).read_text(encoding="utf-8"))
        if (not isinstance(pairing, dict) or not isinstance(pairing.get("device_id"), str)
                or not pairing.get("device_id") or not isinstance(pairing.get("token"), str)
                or not pairing.get("token")):
            raise ValueError
        secrets_list = [pairing["token"]]
        for name in ("core_token_file", "tool_token_file"):
            value = Path(stack[name]).read_text(encoding="utf-8").strip()
            if not value:
                raise ValueError
            secrets_list.append(value)
    except (OSError, ValueError, json.JSONDecodeError):
        raise ScenarioFailure("STACK_PAIRING_INVALID") from None
    relay_mode, _relay_endpoint = parse_android_relay_url(str(stack["relay_url_android"]))
    if stack["relay_transport"] != relay_mode:
        raise ScenarioFailure("RELAY_TRANSPORT_MISMATCH")
    return stack, root, pairing, [secret for secret in secrets_list if secret]


def _reverse_lines(serial: str) -> set[str]:
    output = _adb(serial, "reverse", "--list")
    return {" ".join(line.split()) for line in output.splitlines() if line.strip()}


def _reverse_has_port(lines: set[str], port: int) -> bool:
    pattern = re.compile(rf"(?:^|\s)tcp:{port}\s+tcp:{port}(?:$|\s)")
    return any(pattern.search(line) for line in lines)


def _run_runner(serial: str, action: str, stack_path: Path, suffix: str, root: Path,
                secrets_list: list[str]) -> tuple[str, dict]:
    package = validate_target_package(f"{PRODUCT_PACKAGE}{suffix}")
    command = [
        sys.executable, str(ANDROID_RUNNER), "run", "--serial", serial,
        "--action", action, "--stack-file", str(stack_path), "--suffix", suffix,
    ]
    try:
        result = subprocess.run(
            command, cwd=ROOT, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, timeout=1800, check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        raise ScenarioFailure("ANDROID_RUNNER_UNAVAILABLE") from None
    output = redact_text(result.stdout + "\n--- runner stderr ---\n" + result.stderr,
                         [*secrets_list, serial])
    log_path = root / f"android-{action}-{suffix.rsplit('.', 1)[-1]}.runner.log"
    write_private(log_path, output)
    marker = f"V7_06B_RUNTIME_PASS action={action} package={package}"
    if result.returncode != 0 or marker not in result.stdout.splitlines():
        raise ScenarioFailure("ANDROID_RUNNER_PASS_MARKER_MISSING")
    logcat_values = [line.partition("=")[2].strip() for line in result.stdout.splitlines()
                     if line.startswith("logcat_redacted=")]
    if len(logcat_values) != 1:
        raise ScenarioFailure("ANDROID_LOGCAT_ATTESTATION_MISSING")
    logcat_candidate = Path(logcat_values[0])
    expected_logcat_name = f"android-{action}-{suffix.rsplit('.', 1)[-1]}.logcat.txt"
    if logcat_candidate.is_symlink():
        raise ScenarioFailure("ANDROID_LOGCAT_PATH_INVALID")
    try:
        logcat_path = logcat_candidate.resolve(strict=True)
        if (not logcat_path.is_relative_to(root.resolve(strict=True))
                or logcat_path.name != expected_logcat_name
                or stat.S_IMODE(logcat_path.stat().st_mode) != 0o600):
            raise ScenarioFailure("ANDROID_LOGCAT_PATH_INVALID")
        logcat_text = logcat_path.read_text(encoding="utf-8", errors="replace")
        logcat_lines = logcat_text.splitlines()
    except OSError:
        raise ScenarioFailure("ANDROID_LOGCAT_ATTESTATION_MISSING") from None
    if any(secret and secret in logcat_text for secret in secrets_list) or serial in logcat_text:
        raise ScenarioFailure("ANDROID_LOGCAT_PRIVATE_VALUE_LEAK")
    bearer_values = re.findall(r"(?i)\bBearer\s+([^\s,}]+)", logcat_text)
    if any(value.lower() not in {"<redacted>", "[redacted]", "redacted"} for value in bearer_values):
        raise ScenarioFailure("ANDROID_LOGCAT_BEARER_PRESENT")
    attestation_lines = [line for line in logcat_lines
                         if "step=11 result=PASS" in line and "ack_replay=identical" in line]
    if len(attestation_lines) != 1:
        raise ScenarioFailure("ANDROID_ACK_REPLAY_ATTESTATION_MISSING")
    ack_replay_line = redact_text(attestation_lines[0], [*secrets_list, serial])
    if f"install_target={serial} package={package} action={action}" not in result.stdout:
        raise ScenarioFailure("ANDROID_RUNNER_PACKAGE_MISMATCH")
    apks: dict[str, str] = {}
    for line in result.stdout.splitlines():
        if line.startswith("app_apk="):
            apks["app"] = line.partition("=")[2].strip()
        elif line.startswith("android_test_apk="):
            apks["android_test"] = line.partition("=")[2].strip()
    if set(apks) != {"app", "android_test"}:
        raise ScenarioFailure("ANDROID_APK_PATHS_MISSING")
    for key, value in apks.items():
        apk = Path(value).resolve(strict=True)
        if not apk.is_file() or not apk.is_relative_to(ROOT / "android"):
            raise ScenarioFailure("ANDROID_APK_PATH_INVALID")
        apks[key] = sha256_file(apk)
    return marker, {
        "package": package,
        "runner_log": str(log_path),
        "apk_sha256": apks,
        "ack_replay_attested": True,
        "logcat_path": str(logcat_path),
        "logcat_sha256": sha256_file(logcat_path),
        "ack_replay_line": ack_replay_line,
    }


def _relay_mode(stack: dict, args: argparse.Namespace) -> tuple[str, dict]:
    stack_url = stack.get("relay_url_android")
    relay_url = args.relay_url if args.relay_url is not None else stack_url
    if not isinstance(relay_url, str) or (args.relay_url is not None and relay_url != stack_url):
        raise ScenarioFailure("RELAY_URL_MISMATCH")
    mode, endpoint = parse_android_relay_url(relay_url)
    declared_mode = stack.get("relay_transport")
    if declared_mode is not None and declared_mode != mode:
        raise ScenarioFailure("RELAY_TRANSPORT_MISMATCH")
    if mode == "public_wss":
        if args.adb_reverse is True:
            raise ScenarioFailure("PUBLIC_WSS_FORBIDS_ADB_REVERSE")
        if args.adb_reverse is not False:
            raise ScenarioFailure("PUBLIC_WSS_REQUIRES_EXPLICIT_NO_ADB_REVERSE")
    elif args.adb_reverse is True:
        try:
            is_loopback = ipaddress.ip_address(endpoint["host"]).is_loopback
        except ValueError:
            is_loopback = endpoint["host"].lower() == "localhost"
        if not is_loopback:
            raise ScenarioFailure("ADB_REVERSE_REQUIRES_LOOPBACK_RELAY_URL")
    return mode, endpoint


def _validate_device_relay(endpoint: dict, device: dict, adb_reverse: bool | None) -> None:
    if endpoint["scheme"] != "ws":
        return
    if endpoint["host"] == "10.0.2.2":
        if not device.get("is_emulator"):
            raise ScenarioFailure("EMULATOR_BRIDGE_REQUIRES_EMULATOR")
        return
    if adb_reverse is not True:
        raise ScenarioFailure("LOOPBACK_RELAY_REQUIRES_ADB_REVERSE")


def _assert_no_secret_leak(proof: dict, secrets_list: list[str], pairing: dict,
                           private_values: tuple[str, ...] = ()) -> None:
    encoded = json.dumps(proof, ensure_ascii=False, sort_keys=True)
    if any(secret and secret in encoded for secret in secrets_list):
        raise ScenarioFailure("PROOF_SECRET_LEAK")
    if any(value and value in encoded for value in private_values):
        raise ScenarioFailure("PROOF_PRIVATE_VALUE_LEAK")
    device_id = pairing.get("device_id")
    if not isinstance(device_id, str) or sha256_text(device_id) not in encoded or device_id in encoded:
        raise ScenarioFailure("PROOF_DEVICE_ID_REDACTION_FAILED")
    if re.search(r"(?i)Bearer\s+[^\s,}]+", encoded) or SECRET_FIELD_PATTERN.search(encoded):
        raise ScenarioFailure("PROOF_SECRET_FIELD_PRESENT")


def _write_evidence(evidence_dir: Path, proof: dict) -> tuple[Path, Path]:
    if evidence_dir.is_symlink():
        raise ScenarioFailure("EVIDENCE_DIR_SYMLINK")
    if evidence_dir.exists():
        if (not evidence_dir.is_dir() or stat.S_IMODE(evidence_dir.stat().st_mode) != 0o700
                or evidence_dir.stat().st_uid != os.getuid()):
            raise ScenarioFailure("EVIDENCE_DIR_NOT_PRIVATE")
    else:
        evidence_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(evidence_dir, 0o700)
    suffix = f"{proof['action']}-{uuid.uuid4().hex[:12]}"
    proof_path = evidence_dir / f"v7-06c-{suffix}.json"
    summary_path = evidence_dir / f"v7-06c-{suffix}.txt"
    summary = (
        f"V7-06C Android {proof['action'].upper()} — PASS\n"
        f"Runner: V7_06B_RUNTIME_PASS\n"
        f"Core proposal: {proof['proposal_state']} base_version={proof['base_version']}\n"
        f"ACK: {proof['ack_state']} applied_plan_version={proof['applied_plan_version']}\n"
        f"Tool trace: {proof['tool_trace']['trace_record_count']} records; correlated={proof['tool_trace']['tool_call_ids_correlated']}\n"
        f"ACK replay: {proof['ack_replay']}; event_count={proof['ack_event_count']}; body_hash_match={proof['ack_body_hash_match']}\n"
        f"Pull oracle: {proof['pull_count_after_runner']} pending proposals\n"
        f"Logcat: sha256={proof['runner']['logcat_sha256']}; attestation={proof['runner']['ack_replay_line']}\n"
        f"Network: {proof['network']} endpoint={proof['network_endpoint']['host']}:{proof['network_endpoint']['port']}\n"
        f"Consent events: {proof['consent_event_count']}; runner package={proof['runner']['package']}\n"
    )
    write_private(proof_path, json.dumps(proof, ensure_ascii=False, sort_keys=True, indent=2) + "\n")
    write_private(summary_path, summary)
    return proof_path, summary_path


def _default_evidence_dir(root: Path, action: str) -> Path:
    namespace = root.parent
    if namespace.is_symlink():
        raise ScenarioFailure("EVIDENCE_NAMESPACE_INVALID")
    metadata = namespace.stat()
    if metadata.st_uid != os.getuid() or stat.S_IMODE(metadata.st_mode) != 0o700:
        raise ScenarioFailure("EVIDENCE_NAMESPACE_NOT_PRIVATE")
    return namespace / f"evidence-{action}-{uuid.uuid4().hex[:12]}"


def run(args: argparse.Namespace) -> int:
    suffix = action_suffix(args.action, args.suffix)
    stack_file_arg = args.stack_file if args.stack_file is not None else args.stack_root / "stack.json"
    stack, root, pairing, secrets_list = _read_stack_file(stack_file_arg, args.action)
    relay_mode, relay_endpoint = _relay_mode(stack, args)
    if not args.serial or any(character.isspace() for character in args.serial):
        raise ScenarioFailure("EXPLICIT_SERIAL_REQUIRED")
    device = _device_metadata(args.serial, args.require_api)
    _validate_device_relay(relay_endpoint, device, args.adb_reverse)
    port = int(stack["ports"].get("relay", 0))
    if not 1 <= port <= 65535:
        raise ScenarioFailure("STACK_RELAY_PORT_INVALID")

    reverse_before: set[str] | None = None
    reverse_added = False
    reverse_verified = None
    if args.adb_reverse is True:
        if not re.search(r"^ws://127\.0\.0\.1:\d+(?:/|$)", stack["relay_url_android"]):
            raise ScenarioFailure("ADB_REVERSE_REQUIRES_LOOPBACK_RELAY_URL")
        relay_port_in_url = urlsplit(stack["relay_url_android"]).port
        if relay_port_in_url != port:
            raise ScenarioFailure("ADB_REVERSE_RELAY_PORT_MISMATCH")
        reverse_before = _reverse_lines(args.serial)
        had_mapping = _reverse_has_port(reverse_before, port)
        if not had_mapping:
            _adb(args.serial, "reverse", f"tcp:{port}", f"tcp:{port}")
            reverse_added = True
    runner = None
    try:
        assert_android_stack_empty(Path(stack["core_db"]))
        marker, runner = _run_runner(
            args.serial, args.action, stack_file_arg.resolve(strict=True), suffix, root,
            [*secrets_list, pairing["device_id"]],
        )
        core = _read_core_state(Path(stack["core_db"]), args.action)
        journal = verify_operation_journal(Path(stack["connector_state_dir"]), args.action, core)
        trace = verify_tool_trace(Path(stack["proxy_trace"]), str(stack.get("gateway_agent", "")))
        proof = {
            "schema_version": 1,
            "status": "PASS",
            "action": args.action,
            "device": device,
            "device_id_hash": sha256_text(pairing["device_id"]),
            "runner": runner,
            "runner_pass_marker": marker,
            "ack_replay": "ATTESTED_BY_ANDROID_TEST",
            "ack_event_count": core["ack_event_count"],
            "ack_body_hash_match": "not_applicable",
            "proposal_id": core["proposal_id"],
            "plan_id": core["plan_id"],
            "ack_id": core["ack_id"],
            "proposal_state": core["proposal_state"],
            "base_version": core["base_version"],
            "confirmation_mode": "A0_USER_REVIEW",
            "auto_application": "NONE_AT_CORE",
            "change_count": core["change_count"],
            "target_start_minute": core["target_start_minute"],
            "duration_minutes": core["duration_minutes"],
            "ack_state": core["ack_state"],
            "ack_result_code": core["ack_result_code"],
            "applied_plan_version": core["applied_plan_version"],
            "snapshot_versions": core["snapshot_versions"],
            "snapshot_starts": core["snapshot_starts"],
            "consent_states": core["consent_states"],
            "consent_event_count": core["consent_event_count"],
            "pending_proposal_count": core["pending_proposal_count"],
            "pull_count_after_runner": core["pending_proposal_count"],
            "snapshot_count": core["snapshot_count"],
            "connector_journal": journal,
            "tool_trace": trace,
            "network": "public_wss" if relay_mode == "public_wss" else (
                "local_adb_reverse" if args.adb_reverse is True else "local_emulator_bridge"
            ),
            "network_endpoint": relay_endpoint,
            "adb_reverse": {"requested": args.adb_reverse is True, "added_by_wrapper": reverse_added},
        }
        _assert_no_secret_leak(proof, secrets_list, pairing, private_values=(args.serial,))
    finally:
        if args.adb_reverse is True and reverse_before is not None:
            if reverse_added:
                _adb(args.serial, "reverse", "--remove", f"tcp:{port}")
            reverse_after = _reverse_lines(args.serial)
            reverse_verified = reverse_after == reverse_before
            if not reverse_verified:
                raise ScenarioFailure("ADB_REVERSE_STATE_NOT_RESTORED")
    proof["adb_reverse"]["state_restored"] = reverse_verified
    evidence_dir = args.evidence_dir if args.evidence_dir is not None else _default_evidence_dir(root, args.action)
    proof_path, summary_path = _write_evidence(evidence_dir, proof)
    print(f"V7_06C_RUNTIME_PASS action={args.action} proof={proof_path} summary={summary_path}")
    return 0


def assert_android_stack_empty(core_db: Path) -> None:
    connection = readonly_db(core_db)
    try:
        for table in ("agent_proposals_v4", "agent_proposal_events_v4", "day_plan_snapshots_v4",
                      "consent_states_v4", "consent_state_events_v4"):
            count = int(connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0])
            if count != 0:
                raise ScenarioFailure("ANDROID_STACK_NOT_EMPTY")
    finally:
        connection.close()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="explicit adb serial; no implicit device selection")
    parser.add_argument("--action", choices=("apply", "reject"), required=True)
    stack_group = parser.add_mutually_exclusive_group(required=True)
    stack_group.add_argument("--stack-root", type=Path)
    stack_group.add_argument("--stack-file", type=Path)
    parser.add_argument("--suffix")
    reverse = parser.add_mutually_exclusive_group()
    reverse.add_argument("--adb-reverse", action="store_const", const=True, dest="adb_reverse")
    reverse.add_argument("--no-adb-reverse", action="store_const", const=False, dest="adb_reverse")
    parser.set_defaults(adb_reverse=None)
    parser.add_argument("--relay-url", help="optional assertion matching stack.json relay_url_android")
    parser.add_argument("--require-api", type=int, default=37)
    parser.add_argument("--evidence-dir", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        return run(args)
    except ScenarioFailure as exc:
        print(f"V7_06C_RUNTIME_FAIL action={args.action} error_code={exc.code}")
        return 1
    except (OSError, sqlite3.Error, KeyError, TypeError, ValueError):
        print(f"V7_06C_RUNTIME_FAIL action={args.action} error_code=ORACLE_INPUT_INVALID")
        return 1
    except Exception:
        print(f"V7_06C_RUNTIME_FAIL action={args.action} error_code=HARNESS_FAILURE")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
