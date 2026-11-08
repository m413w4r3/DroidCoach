from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import stat
from datetime import datetime, timezone

import pytest

from atlas_backend import store as store_module
from atlas_backend.store import MigrationChecksumConflict, MigrationIntegrityError, SQLiteStore


NOW = datetime(2026, 10, 29, 8, 2, tzinfo=timezone.utc)


def fixed_clock():
    return lambda: NOW


def _create_v3_database(path):
    migrations = (
        (1, "v4_core_projections", store_module._V4_MIGRATION_V1_STATEMENTS),
        (2, "v4_status_received_at", store_module._V4_MIGRATION_V2_STATEMENTS),
        (3, "v4_explicit_consent_states", store_module._V4_MIGRATION_V3_STATEMENTS),
    )
    with sqlite3.connect(path) as connection:
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
            INSERT INTO events VALUES
                ('event-a','sync','2026-10-01T00:00:00Z','{}','{}'),
                ('event-b','sync','2026-10-02T00:00:00Z','{}','{}');
            INSERT INTO agent_proposals VALUES
                ('proposal-a','key-a',NULL,'2026-10-01T00:00:00Z','2026-10-02T00:00:00Z','proposed','{}');
            INSERT INTO agent_proposal_acks VALUES
                ('proposal-a','accepted',NULL,'2026-10-01T00:00:00Z');
            """
        )
        counts = {
            table: connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
            for table in ("events", "agent_proposals", "agent_proposal_acks")
        }
        for version, name, statements in migrations:
            for statement in statements:
                connection.execute(statement)
            checksum = hashlib.sha256(
                (name + "\n" + "\n".join(statement.strip() for statement in statements)).encode("utf-8")
            ).hexdigest()
            connection.execute(
                "INSERT INTO schema_migrations(version,name,checksum,applied_at) VALUES(?,?,?,?)",
                (version, name, checksum, "2026-10-01T00:00:00Z"),
            )
            connection.execute(f"PRAGMA user_version={version}")
        assert connection.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    return counts


def _backup_path(path):
    return path.with_suffix(path.suffix + ".pre-v4.bak")


def test_fresh_and_legacy_databases_migrate_additively_and_idempotently(tmp_path):
    fresh_path = tmp_path / "fresh.sqlite3"
    fresh = SQLiteStore(fresh_path, clock=fixed_clock())
    with fresh.connect() as connection:
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 5
        assert connection.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert connection.execute("SELECT COUNT(*) FROM schema_migrations").fetchone()[0] == 5
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
            "consent_states_v4",
            "consent_state_events_v4",
            "principals_v4",
            "credentials_v4",
            "principal_capabilities_v4",
            "principal_events_v4",
            "relay_principal_bindings_v4",
            "agent_proposal_expiration_events_v4",
        } <= names
    backup_path = fresh_path.with_suffix(fresh_path.suffix + ".pre-v4.bak")
    assert backup_path.exists()

    # Reopening does not add migration rows or mutate the V4 schema.
    fresh.initialize()
    with fresh.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM schema_migrations").fetchone()[0] == 5
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 5
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
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 5
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
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 5


def test_pre_migration_backup_is_private_and_preserves_v3_before_migration(tmp_path):
    path = tmp_path / "legacy-v3.sqlite3"
    legacy_counts = _create_v3_database(path)

    previous_umask = os.umask(0o022)
    try:
        store = SQLiteStore(path, clock=fixed_clock())
    finally:
        os.umask(previous_umask)

    backup_path = _backup_path(path)
    backup_stat = backup_path.lstat()
    assert stat.S_ISREG(backup_stat.st_mode)
    assert not backup_path.is_symlink()
    assert backup_stat.st_uid == os.geteuid()
    assert stat.S_IMODE(backup_stat.st_mode) == 0o600, (
        f"backup mode is {stat.S_IMODE(backup_stat.st_mode):04o}"
    )
    with sqlite3.connect(backup_path) as backup:
        assert backup.execute("PRAGMA user_version").fetchone()[0] == 3
        assert backup.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert backup.execute("SELECT COUNT(*) FROM schema_migrations").fetchone()[0] == 3

    with store.connect() as migrated:
        assert migrated.execute("PRAGMA user_version").fetchone()[0] == 5
        assert migrated.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert {
            table: migrated.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
            for table in legacy_counts
        } == legacy_counts

    reopened = SQLiteStore(path, clock=fixed_clock())
    with reopened.connect() as connection:
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 5


@pytest.mark.parametrize("mode", [0o600, 0o644])
def test_preexisting_pre_migration_backup_fails_closed_without_modification(tmp_path, mode):
    path = tmp_path / "legacy-v3.sqlite3"
    _create_v3_database(path)
    backup_path = _backup_path(path)
    with sqlite3.connect(backup_path) as backup:
        backup.execute("CREATE TABLE unrelated(value TEXT)")
        backup.execute("INSERT INTO unrelated VALUES('existing backup')")
    backup_path.chmod(mode)
    before = backup_path.read_bytes()

    with pytest.raises(MigrationIntegrityError, match="vérifier puis déplacer"):
        SQLiteStore(path, clock=fixed_clock())

    assert backup_path.read_bytes() == before
    assert stat.S_IMODE(backup_path.lstat().st_mode) == mode
    with sqlite3.connect(path) as live:
        assert live.execute("PRAGMA user_version").fetchone()[0] == 3


@pytest.mark.parametrize("dangling", [False, True])
def test_pre_migration_backup_symlink_fails_closed_without_touching_target(tmp_path, dangling):
    path = tmp_path / "legacy-v3.sqlite3"
    _create_v3_database(path)
    backup_path = _backup_path(path)
    target = tmp_path / ("missing-target.sqlite3" if dangling else "target.sqlite3")
    if not dangling:
        with sqlite3.connect(target) as connection:
            connection.execute("CREATE TABLE target_data(value TEXT)")
            connection.execute("INSERT INTO target_data VALUES('keep')")
    before = target.read_bytes() if target.exists() else None
    target_mode = stat.S_IMODE(target.lstat().st_mode) if target.exists() else None
    backup_path.symlink_to(target)

    with pytest.raises(MigrationIntegrityError, match="vérifier puis déplacer"):
        SQLiteStore(path, clock=fixed_clock())

    assert backup_path.is_symlink()
    assert (target.read_bytes() if target.exists() else None) == before
    assert (stat.S_IMODE(target.lstat().st_mode) if target.exists() else None) == target_mode
    with sqlite3.connect(path) as live:
        assert live.execute("PRAGMA user_version").fetchone()[0] == 3


def test_failed_backup_creation_removes_partial_backup(tmp_path, monkeypatch):
    path = tmp_path / "legacy-v3.sqlite3"
    _create_v3_database(path)

    def fail_write(_fd, _data):
        raise OSError("simulated backup write failure")

    monkeypatch.setattr(os, "write", fail_write)
    with pytest.raises(MigrationIntegrityError, match="sauvegarde pré-migration"):
        SQLiteStore(path, clock=fixed_clock())

    assert not _backup_path(path).exists()
    with sqlite3.connect(path) as live:
        assert live.execute("PRAGMA user_version").fetchone()[0] == 3
