from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys

import pytest

from atlas_backend.main import create_app
from atlas_backend.store import SQLiteStore
from atlas_backend.v4_projection_store import V4ProjectionStore, expire_due_proposals


ROOT = Path(__file__).resolve().parents[2]
NOW = datetime(2026, 11, 6, 12, 0, tzinfo=timezone.utc)
NOW_ISO = "2026-11-06T12:00:00Z"
ACTIVE_STATES = ("PENDING_ANDROID", "RECEIVED", "AWAITING_USER")


def stamp(value: datetime) -> str:
    return value.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def make_store(tmp_path: Path) -> SQLiteStore:
    return SQLiteStore(tmp_path / "expiration.sqlite3", clock=lambda: NOW)


def insert_proposal(
    store: SQLiteStore,
    proposal_id: str,
    expires_at: str,
    *,
    state: str = "PENDING_ANDROID",
    device_ref: str = "device-a",
) -> None:
    with store.connect() as connection:
        connection.execute(
            "INSERT INTO agent_proposals_v4 "
            "(proposal_id,idempotency_key,body_hash,plan_id,base_version,capability,created_at,expires_at,state,proposal_json,device_ref) "
            "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            (proposal_id, f"key-{proposal_id}", "sha256:test", "plan-a", 1,
             "plan_revision.propose", "2026-11-05T00:00:00Z", expires_at, state, "{}", device_ref),
        )


def read_state(store: SQLiteStore, proposal_id: str) -> str:
    with store.connect() as connection:
        row = connection.execute(
            "SELECT state FROM agent_proposals_v4 WHERE proposal_id=?", (proposal_id,)
        ).fetchone()
    assert row is not None
    return str(row[0])


def test_expiration_boundary_is_inclusive_atomic_append_only_and_ack_independent(tmp_path):
    store = make_store(tmp_path)
    proposal_id = "00000000-0000-4000-8000-000000000901"
    insert_proposal(store, proposal_id, NOW_ISO)

    with store.connect() as connection:
        assert expire_due_proposals(
            connection, now=stamp(NOW - timedelta(seconds=1)), batch_size=10, source="PULL"
        ) == 0
    assert read_state(store, proposal_id) == "PENDING_ANDROID"

    with store.connect() as connection:
        assert expire_due_proposals(connection, now=NOW_ISO, batch_size=10, source="PULL") == 1
    assert read_state(store, proposal_id) == "EXPIRED"

    with store.connect() as connection:
        assert expire_due_proposals(
            connection, now=stamp(NOW + timedelta(seconds=1)), batch_size=10, source="SWEEPER"
        ) == 0
        event = connection.execute(
            "SELECT proposal_id,from_state,to_state,expires_at,occurred_at,source,event_version "
            "FROM agent_proposal_expiration_events_v4"
        ).fetchone()
        assert dict(event) == {
            "proposal_id": proposal_id,
            "from_state": "PENDING_ANDROID",
            "to_state": "EXPIRED",
            "expires_at": NOW_ISO,
            "occurred_at": NOW_ISO,
            "source": "PULL",
            "event_version": 1,
        }
        assert connection.execute("SELECT COUNT(*) FROM agent_proposal_events_v4").fetchone()[0] == 0
        with pytest.raises(sqlite3.IntegrityError):
            connection.execute("UPDATE agent_proposal_expiration_events_v4 SET source='ACK'")
        with pytest.raises(sqlite3.IntegrityError):
            connection.execute("DELETE FROM agent_proposal_expiration_events_v4")


def test_expiration_batch_is_bounded_deterministic_and_empty_database_is_noop(tmp_path):
    store = make_store(tmp_path)
    insert_proposal(store, "p-b", stamp(NOW - timedelta(hours=1)), state="RECEIVED")
    insert_proposal(store, "p-a", stamp(NOW - timedelta(hours=1)), state="AWAITING_USER")
    insert_proposal(store, "p-old", stamp(NOW - timedelta(days=1)))

    with store.connect() as connection:
        assert expire_due_proposals(connection, now=NOW_ISO, batch_size=2, source="SWEEPER") == 2
        expired_ids = [row[0] for row in connection.execute(
            "SELECT proposal_id FROM agent_proposal_expiration_events_v4 ORDER BY expires_at,proposal_id"
        )]
    assert expired_ids == ["p-old", "p-a"]
    assert read_state(store, "p-b") == "RECEIVED"
    with store.connect() as connection:
        assert expire_due_proposals(connection, now=NOW_ISO, batch_size=2, source="SWEEPER") == 1
    assert read_state(store, "p-b") == "EXPIRED"

    empty_store = SQLiteStore(tmp_path / "empty.sqlite3", clock=lambda: NOW)
    with empty_store.connect() as connection:
        assert expire_due_proposals(connection, now=NOW_ISO, batch_size=1, source="SWEEPER") == 0
        assert connection.execute("SELECT COUNT(*) FROM agent_proposal_expiration_events_v4").fetchone()[0] == 0
    with empty_store.connect() as connection:
        with pytest.raises(ValueError):
            expire_due_proposals(connection, now=NOW_ISO, batch_size=501, source="SWEEPER")


def test_audit_insert_failure_rolls_back_state_transition(tmp_path):
    store = make_store(tmp_path)
    proposal_id = "proposal-rollback"
    insert_proposal(store, proposal_id, stamp(NOW - timedelta(seconds=1)))
    with store.connect() as connection:
        connection.execute(
            "CREATE TRIGGER fail_expiration_audit BEFORE INSERT ON agent_proposal_expiration_events_v4 "
            "BEGIN SELECT RAISE(ABORT,'synthetic audit insert failure'); END"
        )
    with store.connect() as connection:
        with pytest.raises(sqlite3.IntegrityError):
            expire_due_proposals(connection, now=NOW_ISO, batch_size=1, source="ACK")
    assert read_state(store, proposal_id) == "PENDING_ANDROID"
    with store.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM agent_proposal_expiration_events_v4").fetchone()[0] == 0


def test_sweeper_busy_timeout_rolls_back_then_retry_expires(tmp_path):
    store = make_store(tmp_path)
    proposal_id = "proposal-busy"
    insert_proposal(store, proposal_id, stamp(NOW - timedelta(seconds=1)))
    blocker = sqlite3.connect(store.path, timeout=0.1)
    blocker.execute("BEGIN IMMEDIATE")

    def tick() -> int:
        with store.transaction(timeout=0.05) as connection:
            return expire_due_proposals(connection, now=NOW_ISO, batch_size=1, source="SWEEPER")

    try:
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(tick)
            with pytest.raises(sqlite3.OperationalError, match="locked"):
                future.result(timeout=2)
    finally:
        blocker.rollback()
        blocker.close()
    assert read_state(store, proposal_id) == "PENDING_ANDROID"
    with store.transaction(timeout=0.25) as connection:
        assert expire_due_proposals(connection, now=NOW_ISO, batch_size=1, source="SWEEPER") == 1
    assert read_state(store, proposal_id) == "EXPIRED"


def test_reopening_and_repeated_sweep_are_idempotent_and_uncertain_is_not_expired(tmp_path):
    store = make_store(tmp_path)
    expired_id = "proposal-restart"
    uncertain_id = "proposal-uncertain"
    insert_proposal(store, expired_id, stamp(NOW - timedelta(days=2)))
    insert_proposal(store, uncertain_id, stamp(NOW - timedelta(days=2)), state="UNCERTAIN")
    with store.connect() as connection:
        assert expire_due_proposals(connection, now=NOW_ISO, batch_size=10, source="SWEEPER") == 1
    store = SQLiteStore(store.path, clock=lambda: NOW)
    with store.connect() as connection:
        assert expire_due_proposals(connection, now=NOW_ISO, batch_size=10, source="SWEEPER") == 0
        assert connection.execute(
            "SELECT COUNT(*) FROM agent_proposal_expiration_events_v4 WHERE proposal_id=?", (expired_id,)
        ).fetchone()[0] == 1
    assert read_state(store, expired_id) == "EXPIRED"
    assert read_state(store, uncertain_id) == "UNCERTAIN"


def test_upgrade_from_v4_adds_audit_table_without_changing_prior_ledger(tmp_path):
    path = tmp_path / "upgrade-v4.sqlite3"
    store = SQLiteStore(path, clock=lambda: NOW)
    with store.connect() as connection:
        original_ledger = [
            tuple(row)
            for row in connection.execute(
                "SELECT version,name,checksum FROM schema_migrations WHERE version<=4 ORDER BY version"
            )
        ]
        connection.execute("DROP TRIGGER agent_proposal_expiration_events_v4_no_update")
        connection.execute("DROP TRIGGER agent_proposal_expiration_events_v4_no_delete")
        connection.execute("DROP TABLE agent_proposal_expiration_events_v4")
        connection.execute("DELETE FROM schema_migrations WHERE version=5")
        connection.execute("PRAGMA user_version=4")
    reopened = SQLiteStore(path, clock=lambda: NOW)
    with reopened.connect() as connection:
        rows = connection.execute("SELECT version,name,checksum FROM schema_migrations ORDER BY version").fetchall()
        assert [row[0] for row in rows] == [1, 2, 3, 4, 5]
        assert [tuple(row) for row in rows[:4]] == original_ledger
        assert rows[3][1] == "v4_device_principals_and_proposal_owners"
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 5
        assert connection.execute("PRAGMA foreign_keys").fetchone()[0] == 1
        assert connection.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert connection.execute("PRAGMA foreign_key_check").fetchall() == []
        with pytest.raises(sqlite3.IntegrityError):
            connection.execute(
                "INSERT INTO agent_proposal_expiration_events_v4 "
                "(proposal_id,from_state,to_state,expires_at,occurred_at,source,event_version) "
                "VALUES('missing','PENDING_ANDROID','EXPIRED',?,?, 'SWEEPER',1)",
                (NOW_ISO, NOW_ISO),
            )


def test_lifespan_sweeper_logs_tick_error_and_stops_cleanly(tmp_path, monkeypatch, caplog):
    monkeypatch.delenv("ATLAS_V4_PROPOSAL_EXPIRY_SWEEPER_ENABLED", raising=False)
    monkeypatch.setenv("ATLAS_V4_PROPOSAL_EXPIRY_INTERVAL_SECONDS", "30")
    app = create_app(db_path=tmp_path / "lifespan.sqlite3", token="synthetic-lifespan-token")
    proposal_id = "proposal-lifespan"
    insert_proposal(app.state.store, proposal_id, stamp(NOW - timedelta(seconds=1)))
    monkeypatch.setattr("atlas_backend.main.cleanup_v4_chat_cache", lambda _store: None)

    to_thread_calls: list[str] = []

    async def run_in_test_thread(function, *args, **kwargs):
        to_thread_calls.append(function.__name__)
        return function(*args, **kwargs)

    monkeypatch.setattr(asyncio, "to_thread", run_in_test_thread)

    calls = 0

    def fail_once(self, connection, **kwargs):
        nonlocal calls
        calls += 1
        raise sqlite3.OperationalError("synthetic private lock detail")

    monkeypatch.setattr(V4ProjectionStore, "expire_due_proposals", fail_once)

    async def run_lifespan():
        async with app.router.lifespan_context(app):
            await asyncio.sleep(0.25)

    asyncio.run(run_lifespan())
    assert calls == 1
    assert "expire_one_batch" in to_thread_calls
    assert read_state(app.state.store, proposal_id) == "PENDING_ANDROID"
    with app.state.store.connect() as connection:
        assert connection.execute(
            "SELECT COUNT(*) FROM agent_proposal_expiration_events_v4 WHERE proposal_id=?", (proposal_id,)
        ).fetchone()[0] == 0
    assert "V4 proposal expiration sweep tick failed" in caplog.text
    assert "synthetic private lock detail" not in caplog.text


def test_lifespan_sweeper_can_be_disabled(tmp_path, monkeypatch):
    monkeypatch.setenv("ATLAS_V4_PROPOSAL_EXPIRY_SWEEPER_ENABLED", "0")
    app = create_app(db_path=tmp_path / "lifespan-disabled.sqlite3", token="synthetic-lifespan-token")
    calls = 0
    original = V4ProjectionStore.expire_due_proposals

    def counted(self, connection, **kwargs):
        nonlocal calls
        calls += 1
        return original(self, connection, **kwargs)

    monkeypatch.setattr(V4ProjectionStore, "expire_due_proposals", counted)

    async def run_lifespan():
        async with app.router.lifespan_context(app):
            assert calls == 0

    asyncio.run(run_lifespan())
    assert calls == 0


def test_reconciliation_dry_run_and_apply_require_verified_exclusive_backup(tmp_path):
    store = make_store(tmp_path)
    proposal_id = "proposal-reconcile"
    insert_proposal(store, proposal_id, stamp(NOW - timedelta(days=1)))
    before_hash = hashlib.sha256(store.path.read_bytes()).hexdigest()
    script = ROOT / "backend" / "scripts" / "atlas_v4_expiration_reconcile.py"
    environment = dict(os.environ)
    dry_run = subprocess.run(
        [sys.executable, str(script), "--db", str(store.path), "--now", NOW_ISO],
        capture_output=True, text=True, env=environment, check=False,
    )
    assert dry_run.returncode == 0, dry_run.stderr
    report = json.loads(dry_run.stdout)
    assert report["mode"] == "dry_run"
    assert report["before"]["candidate_count"] == 1
    assert report["before"]["candidates_with_ack"] == 0
    assert hashlib.sha256(store.path.read_bytes()).hexdigest() == before_hash

    no_backup = subprocess.run(
        [sys.executable, str(script), "--db", str(store.path), "--now", NOW_ISO, "--apply"],
        capture_output=True, text=True, env=environment, check=False,
    )
    assert no_backup.returncode == 2
    assert "--backup est obligatoire" in no_backup.stderr
    assert read_state(store, proposal_id) == "PENDING_ANDROID"

    backup_path = tmp_path / "verified-pre-expiry.sqlite3"
    applied = subprocess.run(
        [sys.executable, str(script), "--db", str(store.path), "--now", NOW_ISO,
         "--apply", "--backup", str(backup_path), "--batch-size", "1"],
        capture_output=True, text=True, env=environment, check=False,
    )
    assert applied.returncode == 0, applied.stderr
    applied_report = json.loads(applied.stdout)
    assert applied_report["before"]["candidate_count"] == 1
    assert applied_report["after"]["candidate_count"] == 0
    assert applied_report["after"]["by_state"]["EXPIRED"] == 1
    assert applied_report["backup"]["integrity_check"] == "ok"
    assert applied_report["backup"]["foreign_key_check"] == []
    assert applied_report["backup"]["user_version"] == 5
    assert backup_path.stat().st_size == applied_report["backup"]["size_bytes"]
    assert backup_path.stat().st_mode & 0o777 == 0o600
    assert hashlib.sha256(backup_path.read_bytes()).hexdigest() == applied_report["backup"]["sha256"]
    with sqlite3.connect(backup_path) as backup:
        assert backup.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert backup.execute("SELECT state FROM agent_proposals_v4 WHERE proposal_id=?", (proposal_id,)).fetchone()[0] == "PENDING_ANDROID"
    with store.connect() as connection:
        event = connection.execute(
            "SELECT source FROM agent_proposal_expiration_events_v4 WHERE proposal_id=?", (proposal_id,)
        ).fetchone()
        assert event[0] == "RECONCILIATION"
