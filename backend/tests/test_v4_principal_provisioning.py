from __future__ import annotations

import base64
import fcntl
import json
import os
from pathlib import Path
import runpy
import sqlite3
import subprocess
import sys

import pytest

from atlas_backend.store import SQLiteStore
from atlas_backend.v4_auth import (
    ANDROID_RELAY_CAPABILITIES,
    TOOL_RELAY_CAPABILITIES,
    PrincipalAuthError,
    V4PrincipalRegistry,
)


BACKEND = Path(__file__).resolve().parents[1]
PROVISION = BACKEND.parent / "scripts" / "v7-12" / "provision-relay-principal.py"
VERIFY = BACKEND.parent / "scripts" / "v7-12" / "verify-v4-cutover.py"
RELAY_ID = "relay-device-synthetic-001"
TOOL_TOKEN = "synthetic-tool-token-only-for-v7-12-tests"
PROPOSAL_MARKER = "private-proposal-fixture-must-not-appear-in-snapshot"


def _counts(store: SQLiteStore) -> tuple[int, int, int, int, int]:
    with store.connect() as connection:
        return tuple(
            int(connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0])
            for table in (
                "principals_v4",
                "credentials_v4",
                "relay_principal_bindings_v4",
                "principal_capabilities_v4",
                "principal_events_v4",
            )
        )


def test_registry_batch_provisions_exact_identities_and_event_linkage(tmp_path):
    store = SQLiteStore(tmp_path / "registry.sqlite3")
    relay_token = "synthetic-relay-token-for-batch-test-0001"
    tool_token = "synthetic-tool-token-for-batch-test-0001"
    receipt = V4PrincipalRegistry(store).provision_relay_identity(RELAY_ID, relay_token, tool_token)

    assert receipt["counts"] == {"principals": 2, "credentials": 2, "bindings": 1, "grants": 20, "events": 25}
    assert receipt["capabilities"]["ANDROID"] == sorted(ANDROID_RELAY_CAPABILITIES)
    assert receipt["capabilities"]["TOOL"] == sorted(TOOL_RELAY_CAPABILITIES)
    assert V4PrincipalRegistry(store).resolve(relay_token).audience == "RELAY"
    assert V4PrincipalRegistry(store).resolve(tool_token).audience == "TOOL"
    with store.connect() as connection:
        event_counts = {
            row["event_type"]: int(row["count"])
            for row in connection.execute(
                "SELECT event_type,COUNT(*) AS count FROM principal_events_v4 GROUP BY event_type"
            )
        }
        assert event_counts == {"CAPABILITY_CHANGED": 20, "PROVISIONED": 4, "RELAY_BOUND": 1}
        assert connection.execute(
            "SELECT COUNT(*) FROM principal_events_v4 WHERE event_type='CAPABILITY_CHANGED' AND credential_id IS NOT NULL"
        ).fetchone()[0] == 0
        assert connection.execute(
            "SELECT COUNT(*) FROM principal_events_v4 WHERE event_type='PROVISIONED' AND credential_id IS NULL"
        ).fetchone()[0] == 2
        assert connection.execute("SELECT COUNT(*) FROM relay_principal_bindings_v4 WHERE principal_id=?",
                                  (receipt["principal_ids"]["TOOL"],)).fetchone()[0] == 0


@pytest.mark.parametrize("failure", ["bad_capability", "duplicate_tokens", "callback"])
def test_registry_batch_rolls_back_every_row_on_failure(tmp_path, failure):
    store = SQLiteStore(tmp_path / f"rollback-{failure}.sqlite3")
    registry = V4PrincipalRegistry(store)
    relay_token = "synthetic-relay-token-for-rollback-0001"
    tool_token = "synthetic-tool-token-for-rollback-0001"
    kwargs = {}
    if failure == "bad_capability":
        kwargs["android_capabilities"] = (*ANDROID_RELAY_CAPABILITIES, "not.allowed")
    elif failure == "duplicate_tokens":
        tool_token = relay_token
    else:
        kwargs["before_commit"] = lambda _receipt: (_ for _ in ()).throw(RuntimeError("synthetic callback failure"))

    with pytest.raises((PrincipalAuthError, RuntimeError)):
        registry.provision_relay_identity(RELAY_ID, relay_token, tool_token, **kwargs)
    assert _counts(store) == (0, 0, 0, 0, 0)


def test_registry_batch_rejects_preexisting_binding_without_adding_principals(tmp_path):
    store = SQLiteStore(tmp_path / "binding-conflict.sqlite3")
    with sqlite3.connect(store.path) as connection:
        connection.execute("PRAGMA foreign_keys=OFF")
        connection.execute(
            "INSERT INTO relay_principal_bindings_v4(relay_device_id,principal_id,state,created_at,event_id) "
            "VALUES(?,?,'ACTIVE','2026-11-09T00:00:00Z','synthetic-event')",
            (RELAY_ID, "orphan-principal-fixture"),
        )
    registry = V4PrincipalRegistry(store)
    with pytest.raises(PrincipalAuthError):
        registry.provision_relay_identity(
            RELAY_ID,
            "synthetic-relay-token-for-binding-0001",
            "synthetic-tool-token-for-binding-0001",
        )
    assert _counts(store) == (0, 0, 1, 0, 0)


def test_registry_batch_never_reuses_historical_device_refs(tmp_path, monkeypatch):
    from atlas_backend import v4_auth

    store = SQLiteStore(tmp_path / "historic-ref.sqlite3")
    with store.transaction() as connection:
        connection.execute(
            "INSERT INTO consent_states_v4(device_ref,category,consent_version,state,updated_at,source) "
            "VALUES('device-occupied','DAY_PLAN',1,'GRANTED','2026-11-08T00:00:00Z','synthetic-test')"
        )
    values = iter(("android-principal", "tool-principal", "occupied", "new-tool-ref"))
    monkeypatch.setattr(v4_auth.secrets, "token_urlsafe", lambda _size: next(values))
    with pytest.raises(PrincipalAuthError, match="consentement"):
        V4PrincipalRegistry(store).provision_relay_identity(
            RELAY_ID,
            "synthetic-relay-token-historic-ref-test",
            "synthetic-tool-token-historic-ref-test",
        )
    assert _counts(store) == (0, 0, 0, 0, 0)


def test_provision_cli_core_lock_is_fail_closed_and_held_until_release(tmp_path):
    db = tmp_path / "core-lock.sqlite3"
    SQLiteStore(db)
    lock_path = Path(f"{db.resolve()}.lock")
    _private(lock_path, b"")
    acquire = runpy.run_path(str(PROVISION))["_acquire_core_stopped_lock"]

    active_fd = os.open(lock_path, os.O_RDONLY)
    try:
        fcntl.flock(active_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        with pytest.raises(RuntimeError, match="Core is active"):
            acquire(db)
    finally:
        fcntl.flock(active_fd, fcntl.LOCK_UN)
        os.close(active_fd)

    offline_fd = acquire(db)
    try:
        contender = os.open(lock_path, os.O_RDONLY)
        try:
            with pytest.raises(BlockingIOError):
                fcntl.flock(contender, fcntl.LOCK_EX | fcntl.LOCK_NB)
        finally:
            os.close(contender)
    finally:
        fcntl.flock(offline_fd, fcntl.LOCK_UN)
        os.close(offline_fd)


def _seed_unchanged_sensitive_tables(store: SQLiteStore) -> None:
    with store.transaction() as connection:
        connection.execute(
            "INSERT INTO consent_states_v4(device_ref,category,consent_version,state,updated_at,source) "
            "VALUES('historic-ref','DAY_PLAN',1,'GRANTED','2026-11-08T00:00:00Z','synthetic-test')"
        )
        connection.execute(
            "INSERT INTO consent_state_events_v4(idempotency_key,device_ref,category,consent_version,state,updated_at,source,body_hash) "
            "VALUES('synthetic-consent-event','historic-ref','DAY_PLAN',1,'GRANTED','2026-11-08T00:00:00Z','synthetic-test','sha256:test')"
        )
        connection.execute(
            "INSERT INTO agent_proposals_v4(proposal_id,idempotency_key,body_hash,plan_id,base_version,capability,created_at,expires_at,state,proposal_json,device_ref) "
            "VALUES('synthetic-proposal','synthetic-idempotency','sha256:body','synthetic-plan',1,'plan_revision.propose',"
            "'2026-11-08T00:00:00Z','2026-11-10T00:00:00Z','PENDING_ANDROID',?, 'historic-ref')",
            (json.dumps({"fixture": PROPOSAL_MARKER}),),
        )
        connection.execute(
            "INSERT INTO agent_proposal_events_v4(ack_id,proposal_id,sequence,state,result_code,applied_plan_version,revision_id,scope_hash,effect_ids_json,occurred_at,received_at,body_hash) "
            "VALUES('synthetic-ack','synthetic-proposal',1,'RECEIVED','OK',NULL,NULL,NULL,'[]','2026-11-08T00:00:00Z','2026-11-08T00:00:00Z','sha256:ack')"
        )
        connection.execute(
            "INSERT INTO agent_proposals(proposal_id,idempotency_key,conversation_id,created_at,expires_at,state,proposal_json) "
            "VALUES('legacy-synthetic-proposal','legacy-synthetic-idem',NULL,'2026-11-08T00:00:00Z','2026-11-10T00:00:00Z','PENDING',?)",
            (json.dumps({"fixture": PROPOSAL_MARKER}),),
        )
        connection.execute(
            "INSERT INTO agent_proposal_acks(proposal_id,state,effect,acknowledged_at) "
            "VALUES('legacy-synthetic-proposal','APPLIED','synthetic','2026-11-08T00:00:00Z')"
        )


def _private(path: Path, content: bytes) -> Path:
    path.write_bytes(content)
    path.chmod(0o600)
    return path


def _fixture(tmp_path: Path) -> dict[str, Path | str]:
    db = tmp_path / "synthetic-v5.sqlite3"
    store = SQLiteStore(db)
    _seed_unchanged_sensitive_tables(store)
    _private(Path(f"{db}.lock"), b"")
    output = tmp_path / "private-output"
    output.mkdir(mode=0o700)
    output.chmod(0o700)
    devices = _private(tmp_path / "devices.json", json.dumps({
        "schema_version": 1,
        "devices": [{
            "device_id": RELAY_ID,
            "label": "pixel-v7",
            "token_sha256": "a" * 64,
            "capabilities": [],
            "created_at": "2026-11-01T00:00:00Z",
            "revoked_at": None,
        }],
    }).encode())
    tool_file = _private(tmp_path / "atlas-tool-token", (TOOL_TOKEN + "\n").encode())
    outputs = {
        "credential": output / "relay-credential",
        "map": output / "principal-map.json",
        "receipt": output / "provision-receipt.json",
        "snapshot": output / "pre-mutation-snapshot.json",
    }
    result = subprocess.run(
        [
            sys.executable, str(PROVISION), "--db", str(db),
            "--relay-devices-file", str(devices),
            "--relay-credential-output", str(outputs["credential"]),
            "--map-output", str(outputs["map"]),
            "--receipt", str(outputs["receipt"]),
            "--snapshot", str(outputs["snapshot"]),
            "--credential-container-path", "/state/v7-12/relay-credential",
            "--tool-token-stdin",
        ],
        cwd=BACKEND,
        input=TOOL_TOKEN + "\n",
        text=True,
        capture_output=True,
        check=False,
    )
    assert result.returncode == 0, result.stderr
    assert TOOL_TOKEN not in result.stdout + result.stderr
    snapshot_raw = outputs["snapshot"].read_bytes()
    assert PROPOSAL_MARKER.encode() not in snapshot_raw
    snapshot_doc = json.loads(snapshot_raw)
    assert all(set(value) == {"count", "sha256"} for value in snapshot_doc["tables"].values())
    return {
        "db": db,
        "devices": devices,
        "tool_file": tool_file,
        **outputs,
    }


def _run_verifier(
    files: dict[str, Path | str], *, map_value: dict | None = None, keyring_file: Path | None = None,
) -> subprocess.CompletedProcess[str]:
    if map_value is not None:
        mapping_path = Path(files["map"])
        mapping_path.write_text(json.dumps(map_value), encoding="utf-8")
        mapping_path.chmod(0o600)
    command = [
            sys.executable, str(VERIFY),
            "--db", str(files["db"]),
            "--relay-devices-file", str(files["devices"]),
            "--relay-credential", str(files["credential"]),
            "--tool-token-file", str(files["tool_file"]),
            "--map", str(files["map"]),
            "--receipt", str(files["receipt"]),
            "--snapshot", str(files["snapshot"]),
        ]
    if keyring_file is not None:
        command.extend(["--keyring-file", str(keyring_file)])
    return subprocess.run(
        command,
        cwd=BACKEND,
        text=True,
        capture_output=True,
        check=False,
    )


def test_offline_cli_provisions_and_verifies_exact_synthetic_state(tmp_path):
    files = _fixture(tmp_path)
    store = SQLiteStore(files["db"])
    assert _counts(store) == (2, 2, 1, 20, 25)
    assert all((Path(files[name]).stat().st_mode & 0o777) == 0o600
               for name in ("credential", "map", "receipt", "snapshot"))
    result = _run_verifier(files)
    assert result.returncode == 0, result.stdout + result.stderr
    assert json.loads(result.stdout)["status"] == "PASS"
    assert TOOL_TOKEN not in result.stdout + result.stderr
    assert PROPOSAL_MARKER not in result.stdout + result.stderr


@pytest.mark.parametrize(
    "mutation",
    ["empty", "missing_active", "extra_active", "wrong_principal", "wrong_path", "relative_path", "nul_path", "extra_key"],
)
def test_verifier_rejects_invalid_principal_map(tmp_path, mutation):
    files = _fixture(tmp_path)
    correct = json.loads(Path(files["map"]).read_text(encoding="utf-8"))
    if mutation == "empty":
        invalid = {}
    elif mutation == "missing_active":
        invalid = {"another-device": next(iter(correct.values()))}
    elif mutation == "extra_active":
        invalid = {**correct, "extra-device": next(iter(correct.values()))}
    elif mutation == "wrong_principal":
        invalid = {key: {**value, "principal_id": "prn_wrong"} for key, value in correct.items()}
    elif mutation == "wrong_path":
        invalid = {key: {**value, "credential_file": "/wrong/token"} for key, value in correct.items()}
    elif mutation == "relative_path":
        invalid = {key: {**value, "credential_file": "state/relay-credential"} for key, value in correct.items()}
    elif mutation == "nul_path":
        invalid = {key: {**value, "credential_file": "/state/relay\u0000token"} for key, value in correct.items()}
    else:
        invalid = {key: {**value, "unexpected": "tool"} for key, value in correct.items()}
    result = _run_verifier(files, map_value=invalid)
    assert result.returncode != 0
    report = json.loads(result.stdout)
    map_check = next(item for item in report["checks"] if item["name"] == "principal_map_coverage")
    assert map_check["status"] == "FAIL"
    assert RELAY_ID not in result.stdout


def test_verifier_rejects_changed_snapshot_and_credential_hash(tmp_path):
    files = _fixture(tmp_path)
    snapshot_path = Path(files["snapshot"])
    snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))
    snapshot["tables"]["consent_states_v4"]["sha256"] = "0" * 64
    snapshot_path.write_text(json.dumps(snapshot), encoding="utf-8")
    snapshot_path.chmod(0o600)
    result = _run_verifier(files)
    assert result.returncode != 0
    assert "consent_proposal_ack_snapshot_hashes" in result.stdout

    files = _fixture(tmp_path / "hash-mismatch")
    credential_path = Path(files["credential"])
    credential_path.write_text("synthetic-wrong-relay-token-for-verification\n", encoding="ascii")
    credential_path.chmod(0o600)
    result = _run_verifier(files)
    assert result.returncode != 0
    assert "principal_credential_kinds_audiences_and_hashes" in result.stdout


@pytest.mark.parametrize("mutation", ["tool_kind", "tool_audience", "tool_owner"])
def test_verifier_rejects_tool_identity_mismatch(tmp_path, mutation):
    files = _fixture(tmp_path)
    receipt = json.loads(Path(files["receipt"]).read_text(encoding="utf-8"))
    with sqlite3.connect(files["db"]) as connection:
        if mutation == "tool_kind":
            connection.execute(
                "UPDATE principals_v4 SET kind='ANDROID' WHERE principal_id=?",
                (receipt["principal_ids"]["TOOL"],),
            )
        elif mutation == "tool_audience":
            connection.execute(
                "UPDATE credentials_v4 SET audience='RELAY' WHERE credential_id=?",
                (receipt["credential_ids"]["TOOL"],),
            )
        else:
            connection.execute(
                "UPDATE credentials_v4 SET principal_id=? WHERE credential_id=?",
                (receipt["principal_ids"]["ANDROID"], receipt["credential_ids"]["TOOL"]),
            )
    result = _run_verifier(files)
    assert result.returncode != 0
    assert "principal_credential_kinds_audiences_and_hashes" in result.stdout


def test_verifier_requires_distinct_assertion_keyring_secrets_when_provided(tmp_path):
    files = _fixture(tmp_path)
    keyring = tmp_path / "keyring.json"
    encoded_a = base64.urlsafe_b64encode(b"a" * 32).decode("ascii").rstrip("=")
    encoded_b = base64.urlsafe_b64encode(b"b" * 32).decode("ascii").rstrip("=")
    _private(keyring, json.dumps({"active": encoded_a, "previous": encoded_b}).encode())
    passed = _run_verifier(files, keyring_file=keyring)
    assert passed.returncode == 0, passed.stdout + passed.stderr

    _private(keyring, json.dumps({"active": encoded_a, "previous": encoded_a}).encode())
    failed = _run_verifier(files, keyring_file=keyring)
    assert failed.returncode != 0
    assert "assertion_keyring_distinct_secrets" in failed.stdout
    assert encoded_a not in failed.stdout + failed.stderr


def test_provision_cli_refuses_nonempty_registry_and_leaves_existing_rows_unchanged(tmp_path):
    files = _fixture(tmp_path)
    output_dir = tmp_path / "refused"
    output_dir.mkdir(mode=0o700)
    output_dir.chmod(0o700)
    command = [
        sys.executable, str(PROVISION), "--db", str(files["db"]),
        "--relay-device-id", "another-synthetic-relay-id",
        "--relay-credential-output", str(output_dir / "relay-credential"),
        "--map-output", str(output_dir / "principal-map.json"),
        "--receipt", str(output_dir / "receipt.json"),
        "--snapshot", str(output_dir / "snapshot.json"),
        "--tool-token-stdin",
    ]
    result = subprocess.run(command, cwd=BACKEND, input=TOOL_TOKEN + "\n", text=True, capture_output=True, check=False)
    assert result.returncode != 0
    assert TOOL_TOKEN not in result.stdout + result.stderr
    assert _counts(SQLiteStore(files["db"])) == (2, 2, 1, 20, 25)
