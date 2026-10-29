from __future__ import annotations

import hashlib
import json
import sqlite3
from datetime import datetime, timezone

import pytest

from atlas_backend.store import MigrationChecksumConflict, SQLiteStore


NOW = datetime(2026, 10, 29, 8, 2, tzinfo=timezone.utc)


def fixed_clock():
    return lambda: NOW


def test_fresh_and_legacy_databases_migrate_additively_and_idempotently(tmp_path):
    fresh_path = tmp_path / "fresh.sqlite3"
    fresh = SQLiteStore(fresh_path, clock=fixed_clock())
    with fresh.connect() as connection:
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 2
        assert connection.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert connection.execute("SELECT COUNT(*) FROM schema_migrations").fetchone()[0] == 2
        names = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            ).fetchall()
        }
        assert {
            "day_plan_snapshots_v4",
            "activity_status_events_v4",
            "usage_summaries_v4",
            "goal_progress_snapshots_v4",
            "training_snapshots_v4",
            "agent_proposals_v4",
            "agent_proposal_events_v4",
            "tool_audit_v4",
            "v4_quarantine",
        } <= names
    backup_path = fresh_path.with_suffix(fresh_path.suffix + ".pre-v4.bak")
    assert backup_path.exists()

    # Reopening does not add migration rows or mutate the V4 schema.
    fresh.initialize()
    with fresh.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM schema_migrations").fetchone()[0] == 2
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 2
        assert connection.execute("PRAGMA integrity_check").fetchone()[0] == "ok"

    legacy_path = tmp_path / "legacy.sqlite3"
    with sqlite3.connect(legacy_path) as connection:
        connection.executescript(
            """
            CREATE TABLE events (
                event_id TEXT PRIMARY KEY, event_type TEXT NOT NULL, occurred_at TEXT NOT NULL,
                payload_json TEXT NOT NULL, result_json TEXT NOT NULL
            );
            CREATE TABLE agent_proposals (
                proposal_id TEXT PRIMARY KEY, idempotency_key TEXT NOT NULL UNIQUE,
                conversation_id TEXT, created_at TEXT NOT NULL, expires_at TEXT NOT NULL,
                state TEXT NOT NULL, proposal_json TEXT NOT NULL
            );
            CREATE TABLE agent_proposal_acks (
                proposal_id TEXT PRIMARY KEY, state TEXT NOT NULL, effect TEXT,
                acknowledged_at TEXT NOT NULL
            );
            INSERT INTO events VALUES('legacy-event','sync','2026-10-01T00:00:00Z','{}','{}');
            INSERT INTO agent_proposals VALUES('legacy-proposal','legacy-key',NULL,'2026-10-01T00:00:00Z','2026-10-02T00:00:00Z','proposed','{}');
            INSERT INTO agent_proposal_acks VALUES('legacy-proposal','accepted',NULL,'2026-10-01T00:00:00Z');
            """
        )
        before_sql = {
            row[0]: row[1]
            for row in connection.execute(
                "SELECT name,sql FROM sqlite_master WHERE type='table' AND name IN ('events','agent_proposals','agent_proposal_acks')"
            )
        }
    legacy = SQLiteStore(legacy_path, clock=fixed_clock())
    with legacy.connect() as connection:
        after_sql = {
            row[0]: row[1]
            for row in connection.execute(
                "SELECT name,sql FROM sqlite_master WHERE type='table' AND name IN ('events','agent_proposals','agent_proposal_acks')"
            )
        }
        assert after_sql == before_sql
        assert connection.execute("SELECT event_id FROM events").fetchone()[0] == "legacy-event"
        assert connection.execute("SELECT proposal_id FROM agent_proposals").fetchone()[0] == "legacy-proposal"
        assert connection.execute("SELECT proposal_id FROM agent_proposal_acks").fetchone()[0] == "legacy-proposal"
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 2
        assert connection.execute("PRAGMA integrity_check").fetchone()[0] == "ok"


def test_unknown_user_version_is_quarantined_without_lowering_version(tmp_path):
    path = tmp_path / "future.sqlite3"
    with sqlite3.connect(path) as connection:
        connection.execute("CREATE TABLE future_data(value TEXT NOT NULL)")
        connection.execute("INSERT INTO future_data VALUES('keep-me')")
        connection.execute("PRAGMA user_version=77")

    store = SQLiteStore(path, clock=fixed_clock())
    with store.connect() as connection:
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 77
        assert connection.execute("SELECT value FROM future_data").fetchone()[0] == "keep-me"
        quarantined = connection.execute(
            "SELECT schema_version,error_code,payload FROM v4_quarantine WHERE kind='SCHEMA_VERSION'"
        ).fetchone()
        assert quarantined["schema_version"] == 77
        assert quarantined["error_code"] == "UNSUPPORTED_SCHEMA_VERSION"
        assert json.loads(quarantined["payload"])["user_version"] == 77
        assert connection.execute("PRAGMA integrity_check").fetchone()[0] == "ok"


def test_migration_checksum_mismatch_is_quarantined_and_fails_closed(tmp_path):
    path = tmp_path / "tampered.sqlite3"
    store = SQLiteStore(path, clock=fixed_clock())
    with store.connect() as connection:
        connection.execute("UPDATE schema_migrations SET checksum='tampered' WHERE version=1")

    with pytest.raises(MigrationChecksumConflict):
        SQLiteStore(path, clock=fixed_clock())
    with sqlite3.connect(path) as connection:
        row = connection.execute(
            "SELECT error_code,payload,payload_hash FROM v4_quarantine WHERE kind='MIGRATION'"
        ).fetchone()
        assert row is not None
        assert row[0] == "MIGRATION_CHECKSUM_MISMATCH"
        assert hashlib.sha256(row[1]).hexdigest() in row[2]
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 2
