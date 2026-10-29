"""SQLite persistence for V1 records and V2 proposals, memory, and idempotence."""

from __future__ import annotations

import json
import hashlib
import sqlite3
from contextlib import contextmanager
from contextvars import ContextVar
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterator


_V4_MIGRATION_V1_STATEMENTS = (
    "CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, checksum TEXT NOT NULL, applied_at TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS v4_quarantine (record_id TEXT PRIMARY KEY, kind TEXT NOT NULL, device_ref TEXT, schema_version INTEGER, record_key TEXT, payload BLOB NOT NULL, payload_hash TEXT NOT NULL, error_code TEXT NOT NULL, received_at TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS day_plan_snapshots_v4 (device_ref TEXT NOT NULL, plan_id TEXT NOT NULL, local_date TEXT NOT NULL, timezone TEXT NOT NULL, plan_version INTEGER NOT NULL CHECK(plan_version > 0), revision_id TEXT NOT NULL, schema_version INTEGER NOT NULL, observed_at TEXT NOT NULL, received_at TEXT NOT NULL, consent_version INTEGER NOT NULL CHECK(consent_version > 0), snapshot_hash TEXT NOT NULL, projection_json TEXT NOT NULL, PRIMARY KEY(device_ref, plan_id, plan_version))",
    "CREATE INDEX IF NOT EXISTS day_plan_snapshots_v4_date_idx ON day_plan_snapshots_v4(device_ref, local_date, received_at)",
    "CREATE TABLE IF NOT EXISTS activity_status_events_v4 (event_id TEXT PRIMARY KEY, device_ref TEXT NOT NULL, plan_id TEXT NOT NULL, activity_id TEXT NOT NULL, continuity_id TEXT, sequence INTEGER NOT NULL CHECK(sequence > 0), old_status TEXT NOT NULL, new_status TEXT NOT NULL, provenance TEXT NOT NULL, source TEXT NOT NULL, decided_at TEXT NOT NULL, observed_at TEXT NOT NULL, plan_version INTEGER NOT NULL CHECK(plan_version > 0), consent_version INTEGER NOT NULL CHECK(consent_version > 0), event_hash TEXT NOT NULL, UNIQUE(device_ref, sequence))",
    "CREATE INDEX IF NOT EXISTS activity_status_events_v4_activity_idx ON activity_status_events_v4(device_ref, plan_id, activity_id, sequence)",
    "CREATE TABLE IF NOT EXISTS usage_summaries_v4 (device_ref TEXT NOT NULL, window_start TEXT NOT NULL, window_end TEXT NOT NULL, timezone TEXT NOT NULL, generated_at TEXT NOT NULL, received_at TEXT NOT NULL, consent_version INTEGER NOT NULL CHECK(consent_version > 0), summary_hash TEXT NOT NULL, summary_json TEXT NOT NULL, PRIMARY KEY(device_ref, window_start, window_end, consent_version))",
    "CREATE TABLE IF NOT EXISTS goal_progress_snapshots_v4 (device_ref TEXT NOT NULL, goals_version INTEGER NOT NULL CHECK(goals_version > 0), observed_at TEXT NOT NULL, received_at TEXT NOT NULL, consent_version INTEGER NOT NULL CHECK(consent_version > 0), snapshot_hash TEXT NOT NULL, projection_json TEXT NOT NULL, PRIMARY KEY(device_ref, goals_version))",
    "CREATE TABLE IF NOT EXISTS training_snapshots_v4 (device_ref TEXT NOT NULL, provider TEXT NOT NULL, observed_at TEXT NOT NULL, received_at TEXT NOT NULL, sync_state TEXT NOT NULL, source_version TEXT, consent_version INTEGER NOT NULL CHECK(consent_version > 0), snapshot_hash TEXT NOT NULL, projection_json TEXT NOT NULL, PRIMARY KEY(device_ref, provider, observed_at))",
    "CREATE TABLE IF NOT EXISTS agent_proposals_v4 (proposal_id TEXT PRIMARY KEY, idempotency_key TEXT NOT NULL UNIQUE, body_hash TEXT NOT NULL, plan_id TEXT NOT NULL, base_version INTEGER NOT NULL CHECK(base_version > 0), capability TEXT NOT NULL, created_at TEXT NOT NULL, expires_at TEXT NOT NULL, state TEXT NOT NULL, proposal_json TEXT NOT NULL)",
    "CREATE INDEX IF NOT EXISTS agent_proposals_v4_state_expiry_idx ON agent_proposals_v4(state, expires_at)",
    "CREATE TABLE IF NOT EXISTS agent_proposal_events_v4 (ack_id TEXT PRIMARY KEY, proposal_id TEXT NOT NULL REFERENCES agent_proposals_v4(proposal_id), sequence INTEGER NOT NULL CHECK(sequence > 0), state TEXT NOT NULL, result_code TEXT NOT NULL, applied_plan_version INTEGER, revision_id TEXT, scope_hash TEXT, effect_ids_json TEXT NOT NULL, occurred_at TEXT NOT NULL, received_at TEXT NOT NULL, body_hash TEXT NOT NULL, UNIQUE(proposal_id, sequence))",
    "CREATE INDEX IF NOT EXISTS agent_proposal_events_v4_proposal_idx ON agent_proposal_events_v4(proposal_id, sequence)",
    "CREATE TABLE IF NOT EXISTS tool_audit_v4 (call_id TEXT PRIMARY KEY, tool_name TEXT NOT NULL, capability TEXT NOT NULL, request_hash TEXT NOT NULL, result_code TEXT NOT NULL, started_at TEXT NOT NULL, completed_at TEXT NOT NULL, latency_ms INTEGER NOT NULL CHECK(latency_ms >= 0))",
    "CREATE TRIGGER IF NOT EXISTS activity_status_events_v4_no_update BEFORE UPDATE ON activity_status_events_v4 BEGIN SELECT RAISE(ABORT, 'append-only V4 status event'); END",
    "CREATE TRIGGER IF NOT EXISTS activity_status_events_v4_no_delete BEFORE DELETE ON activity_status_events_v4 BEGIN SELECT RAISE(ABORT, 'append-only V4 status event'); END",
    "CREATE TRIGGER IF NOT EXISTS agent_proposal_events_v4_no_update BEFORE UPDATE ON agent_proposal_events_v4 BEGIN SELECT RAISE(ABORT, 'append-only V4 proposal event'); END",
    "CREATE TRIGGER IF NOT EXISTS agent_proposal_events_v4_no_delete BEFORE DELETE ON agent_proposal_events_v4 BEGIN SELECT RAISE(ABORT, 'append-only V4 proposal event'); END",
)

_V4_MIGRATION_V2_STATEMENTS = (
    "ALTER TABLE activity_status_events_v4 ADD COLUMN received_at TEXT",
)

_V4_MIGRATION_V3_STATEMENTS = (
    "CREATE TABLE IF NOT EXISTS consent_states_v4 (device_ref TEXT NOT NULL, category TEXT NOT NULL CHECK(category IN ('DAY_PLAN','ACTIVITY_STATUS','USAGE_SUMMARY','GOAL_PROGRESS','TRAINING_PLAN')), consent_version INTEGER NOT NULL CHECK(consent_version > 0), state TEXT NOT NULL CHECK(state IN ('GRANTED','REVOKED','UNKNOWN')), updated_at TEXT NOT NULL, source TEXT NOT NULL, PRIMARY KEY(device_ref, category))",
    "CREATE TRIGGER IF NOT EXISTS consent_states_v4_no_delete BEFORE DELETE ON consent_states_v4 BEGIN SELECT RAISE(ABORT, 'V4 consent state tombstone cannot be deleted'); END",
    "CREATE TRIGGER IF NOT EXISTS consent_states_v4_monotonic_update BEFORE UPDATE ON consent_states_v4 WHEN NEW.consent_version <= OLD.consent_version BEGIN SELECT RAISE(ABORT, 'V4 consent version must increase'); END",
    "CREATE TABLE IF NOT EXISTS consent_state_events_v4 (idempotency_key TEXT PRIMARY KEY, device_ref TEXT NOT NULL, category TEXT NOT NULL CHECK(category IN ('DAY_PLAN','ACTIVITY_STATUS','USAGE_SUMMARY','GOAL_PROGRESS','TRAINING_PLAN')), consent_version INTEGER NOT NULL CHECK(consent_version > 0), state TEXT NOT NULL CHECK(state IN ('GRANTED','REVOKED','UNKNOWN')), updated_at TEXT NOT NULL, source TEXT NOT NULL, body_hash TEXT NOT NULL)",
    "CREATE TRIGGER IF NOT EXISTS consent_state_events_v4_no_update BEFORE UPDATE ON consent_state_events_v4 BEGIN SELECT RAISE(ABORT, 'V4 consent events are immutable'); END",
    "CREATE TRIGGER IF NOT EXISTS consent_state_events_v4_no_delete BEFORE DELETE ON consent_state_events_v4 BEGIN SELECT RAISE(ABORT, 'V4 consent events are immutable'); END",
)


class _BorrowedConnection:
    """Nested store calls borrow an outer transaction without committing it."""

    def __init__(self, connection: sqlite3.Connection) -> None:
        self._connection = connection

    def __enter__(self) -> sqlite3.Connection:
        return self._connection

    def __exit__(self, exc_type, exc, traceback) -> bool:
        return False

    def close(self) -> None:
        return None

    def __getattr__(self, name: str) -> Any:
        return getattr(self._connection, name)


class SQLiteStore:
    def __init__(self, path: str | Path, *, clock: Callable[[], datetime] | None = None) -> None:
        self.path = Path(path)
        self.clock = clock or (lambda: datetime.now(timezone.utc))
        self._transaction_connection: ContextVar[sqlite3.Connection | None] = ContextVar(
            f"atlas_sqlite_transaction_{id(self)}", default=None
        )
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.initialize()

    def connect(self) -> sqlite3.Connection | _BorrowedConnection:
        active = self._transaction_connection.get()
        if active is not None:
            return _BorrowedConnection(active)
        connection = sqlite3.connect(self.path, timeout=5)
        connection.row_factory = sqlite3.Row
        return connection

    @contextmanager
    def transaction(self, *, immediate: bool = True, timeout: float = 5.0) -> Iterator[sqlite3.Connection]:
        if self._transaction_connection.get() is not None:
            raise RuntimeError("SQLiteStore transactions cannot be nested")
        connection = sqlite3.connect(self.path, timeout=timeout)
        connection.row_factory = sqlite3.Row
        try:
            connection.execute("BEGIN IMMEDIATE" if immediate else "BEGIN")
            token = self._transaction_connection.set(connection)
            try:
                yield connection
                connection.commit()
            except BaseException:
                connection.rollback()
                raise
            finally:
                self._transaction_connection.reset(token)
        finally:
            connection.close()

    def begin_immediate(self, connection: sqlite3.Connection) -> None:
        if self._transaction_connection.get() is not connection:
            connection.execute("BEGIN IMMEDIATE")

    def initialize(self) -> None:
        with self.connect() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS events (
                    event_id TEXT PRIMARY KEY,
                    event_type TEXT NOT NULL,
                    occurred_at TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    result_json TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS directive_responses (
                    directive_id TEXT NOT NULL,
                    choice TEXT NOT NULL,
                    result_json TEXT NOT NULL,
                    PRIMARY KEY (directive_id, choice)
                );
                CREATE TABLE IF NOT EXISTS agent_chat_cache (
                    cache_key TEXT PRIMARY KEY,
                    response_json TEXT NOT NULL,
                    expires_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS plan_revision_requests (
                    request_id TEXT PRIMARY KEY,
                    body_hash TEXT NOT NULL,
                    state TEXT NOT NULL CHECK (state IN ('pending', 'complete')),
                    created_at TEXT NOT NULL,
                    status_code INTEGER,
                    response_json TEXT
                );
                CREATE TABLE IF NOT EXISTS agent_proposals (
                    proposal_id TEXT PRIMARY KEY,
                    idempotency_key TEXT NOT NULL UNIQUE,
                    conversation_id TEXT,
                    created_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    state TEXT NOT NULL,
                    proposal_json TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS agent_proposals_created_idx
                    ON agent_proposals(created_at, proposal_id);
                CREATE TABLE IF NOT EXISTS agent_proposal_acks (
                    proposal_id TEXT PRIMARY KEY,
                    state TEXT NOT NULL,
                    effect TEXT,
                    acknowledged_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS project_memory (
                    memory_id INTEGER PRIMARY KEY CHECK (memory_id = 1),
                    memory_json TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                """
            )
        self.initialize_v4_migrations()

    def now_iso(self) -> str:
        value = self.clock()
        if isinstance(value, str):
            return value
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")

    def initialize_v4_migrations(self) -> None:
        """Apply the additive V4 schema, keeping all existing V1/V2 tables intact."""
        migrations = (
            (1, "v4_core_projections", _V4_MIGRATION_V1_STATEMENTS),
            (2, "v4_status_received_at", _V4_MIGRATION_V2_STATEMENTS),
            (3, "v4_explicit_consent_states", _V4_MIGRATION_V3_STATEMENTS),
        )
        checksums = {
            version: hashlib.sha256(
                (name + "\n" + "\n".join(statement.strip() for statement in statements)).encode("utf-8")
            ).hexdigest()
            for version, name, statements in migrations
        }
        with self.connect() as connection:
            user_version = int(connection.execute("PRAGMA user_version").fetchone()[0])
            has_migrations = connection.execute(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='schema_migrations'"
            ).fetchone() is not None
            known_rows = connection.execute(
                "SELECT version,name,checksum,applied_at FROM schema_migrations ORDER BY version"
            ).fetchall() if has_migrations else []

        current_version = migrations[-1][0]
        unknown_rows = [row for row in known_rows if int(row["version"]) > current_version]
        if user_version > current_version or unknown_rows:
            self._quarantine_unknown_schema_version(user_version, unknown_rows)
            return

        applied_by_version = {int(row["version"]): row for row in known_rows}
        for version, name, _statements in migrations:
            applied = applied_by_version.get(version)
            if applied is not None and (applied["name"] != name or applied["checksum"] != checksums[version]):
                self._record_migration_quarantine(
                    "MIGRATION_CHECKSUM_MISMATCH",
                    json.dumps(dict(applied), sort_keys=True, default=str).encode("utf-8"),
                )
                raise MigrationChecksumConflict(f"la checksum de la migration V4 version {version} ne correspond pas")

        applied_max = max(applied_by_version, default=0)
        expected_applied = set(range(1, applied_max + 1))
        if user_version > applied_max or set(applied_by_version) != expected_applied:
            payload = json.dumps(
                {
                    "user_version": user_version,
                    "applied_versions": sorted(applied_by_version),
                    "expected_applied_versions": sorted(expected_applied),
                },
                sort_keys=True,
            ).encode("utf-8")
            self._record_migration_quarantine("MIGRATION_LEDGER_MISMATCH", payload)
            return

        if any(version not in applied_by_version for version, _name, _statements in migrations):
            self._create_coherent_pre_migration_backup()
        with self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute(
                "CREATE TABLE IF NOT EXISTS schema_migrations ("
                "version INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, "
                "checksum TEXT NOT NULL, applied_at TEXT NOT NULL)"
            )
            for version, name, statements in migrations:
                if version in applied_by_version:
                    connection.execute(f"PRAGMA user_version={version}")
                    continue
                for statement in statements:
                    connection.execute(statement)
                connection.execute(
                    "INSERT INTO schema_migrations(version,name,checksum,applied_at) VALUES(?,?,?,?)",
                    (version, name, checksums[version], self.now_iso()),
                )
                connection.execute(f"PRAGMA user_version={version}")
                result = connection.execute("PRAGMA integrity_check").fetchone()[0]
                if result != "ok":
                    raise MigrationIntegrityError(f"integrity_check SQLite a échoué: {result}")
        self._verify_v4_integrity()

    def _create_coherent_pre_migration_backup(self) -> None:
        if not self.path.exists():
            return
        backup_path = self.path.with_suffix(self.path.suffix + ".pre-v4.bak")
        if backup_path.exists():
            with sqlite3.connect(backup_path) as backup:
                result = backup.execute("PRAGMA integrity_check").fetchone()[0]
            if result != "ok":
                raise MigrationIntegrityError("la sauvegarde pré-migration existante est invalide")
            return
        with self.connect() as source, sqlite3.connect(backup_path) as destination:
            source.backup(destination)
            result = destination.execute("PRAGMA integrity_check").fetchone()[0]
            if result != "ok":
                raise MigrationIntegrityError("la sauvegarde pré-migration est invalide")

    def _verify_v4_integrity(self) -> None:
        with self.connect() as connection:
            result = connection.execute("PRAGMA integrity_check").fetchone()[0]
            if result != "ok":
                raise MigrationIntegrityError(f"integrity_check SQLite a échoué: {result}")

    def _create_quarantine_table(self, connection: sqlite3.Connection) -> None:
        connection.execute(
            "CREATE TABLE IF NOT EXISTS v4_quarantine ("
            "record_id TEXT PRIMARY KEY, kind TEXT NOT NULL, device_ref TEXT, "
            "schema_version INTEGER, record_key TEXT, payload BLOB NOT NULL, "
            "payload_hash TEXT NOT NULL, error_code TEXT NOT NULL, received_at TEXT NOT NULL)"
        )

    def _quarantine_unknown_schema_version(self, user_version: int, rows: list[sqlite3.Row]) -> None:
        self._create_coherent_pre_migration_backup()
        with self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            self._create_quarantine_table(connection)
            payload = json.dumps(
                {
                    "user_version": user_version,
                    "migration_rows": [dict(row) for row in rows],
                },
                sort_keys=True,
                default=str,
            ).encode("utf-8")
            self._insert_quarantine(
                connection, "SCHEMA_VERSION", None, user_version, None, payload,
                "UNSUPPORTED_SCHEMA_VERSION", self.now_iso()
            )
            result = connection.execute("PRAGMA integrity_check").fetchone()[0]
            if result != "ok":
                raise MigrationIntegrityError(f"integrity_check SQLite a échoué: {result}")

    def _record_migration_quarantine(self, code: str, payload: bytes) -> None:
        with self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            self._create_quarantine_table(connection)
            self._insert_quarantine(connection, "MIGRATION", None, None, None, payload, code, self.now_iso())

    @staticmethod
    def _insert_quarantine(
        connection: sqlite3.Connection,
        kind: str,
        device_ref: str | None,
        schema_version: int | None,
        record_key: str | None,
        payload: bytes,
        error_code: str,
        received_at: str,
    ) -> str:
        import uuid

        record_id = str(uuid.uuid4())
        payload_hash = "sha256:" + hashlib.sha256(payload).hexdigest()
        connection.execute(
            "INSERT INTO v4_quarantine(record_id,kind,device_ref,schema_version,record_key,payload,payload_hash,error_code,received_at) "
            "VALUES(?,?,?,?,?,?,?,?,?)",
            (record_id, kind, device_ref, schema_version, record_key, payload, payload_hash, error_code, received_at),
        )
        return record_id


    def claim_plan_revision_request(
        self,
        request_id: str,
        body_hash: str,
        created_at: str,
    ) -> tuple[dict[str, Any], bool]:
        """Durably reserve a request id before contacting the model adapter."""
        with self.connect() as connection:
            inserted = connection.execute(
                "INSERT OR IGNORE INTO plan_revision_requests"
                "(request_id,body_hash,state,created_at) VALUES(?,?, 'pending', ?)",
                (request_id, body_hash, created_at),
            ).rowcount == 1
            row = connection.execute(
                "SELECT request_id,body_hash,state,status_code,response_json "
                "FROM plan_revision_requests WHERE request_id=?",
                (request_id,),
            ).fetchone()
        if row["body_hash"] != body_hash:
            raise PlanRevisionIdempotencyConflict("request_id reused with a different body")
        return {
            "request_id": row["request_id"],
            "body_hash": row["body_hash"],
            "state": row["state"],
            "status_code": row["status_code"],
            "response": json.loads(row["response_json"]) if row["response_json"] else None,
        }, inserted

    def complete_plan_revision_request(
        self,
        request_id: str,
        body_hash: str,
        status_code: int,
        response: dict[str, Any],
    ) -> dict[str, Any]:
        """Store the final HTTP status and response as one SQLite transaction."""
        response_json = json.dumps(response, ensure_ascii=False, sort_keys=True)
        with self.connect() as connection:
            connection.execute(
                "UPDATE plan_revision_requests SET state='complete',status_code=?,response_json=? "
                "WHERE request_id=? AND body_hash=? AND state='pending'",
                (status_code, response_json, request_id, body_hash),
            )
            row = connection.execute(
                "SELECT state,status_code,response_json FROM plan_revision_requests WHERE request_id=?",
                (request_id,),
            ).fetchone()
        if row is None or row["state"] != "complete" or row["response_json"] is None:
            raise RuntimeError("plan revision request could not be completed")
        return {
            "status_code": int(row["status_code"]),
            "response": json.loads(row["response_json"]),
        }

    def get_agent_chat(self, cache_key: str, now: str) -> dict[str, Any] | None:
        with self.connect() as connection:
            row = connection.execute(
                "SELECT response_json FROM agent_chat_cache WHERE cache_key=? AND expires_at>?",
                (cache_key, now),
            ).fetchone()
        return json.loads(row["response_json"]) if row else None

    def save_agent_chat(self, cache_key: str, response: dict[str, Any], expires_at: str) -> dict[str, Any]:
        response_json = json.dumps(response, ensure_ascii=False, sort_keys=True)
        with self.connect() as connection:
            connection.execute(
                "INSERT OR IGNORE INTO agent_chat_cache(cache_key,response_json,expires_at) VALUES(?,?,?)",
                (cache_key, response_json, expires_at),
            )
            row = connection.execute(
                "SELECT response_json FROM agent_chat_cache WHERE cache_key=?", (cache_key,)
            ).fetchone()
        return json.loads(row["response_json"])

    def save_proposal(self, proposal: dict[str, Any]) -> tuple[dict[str, Any], bool]:
        proposal_json = json.dumps(proposal, ensure_ascii=False, sort_keys=True)
        with self.connect() as connection:
            inserted = connection.execute(
                "INSERT OR IGNORE INTO agent_proposals"
                "(proposal_id,idempotency_key,conversation_id,created_at,expires_at,state,proposal_json) "
                "VALUES(?,?,?,?,?,?,?)",
                (
                    proposal["proposal_id"],
                    proposal["idempotency_key"],
                    proposal["conversation_id"],
                    proposal["created_at"],
                    proposal["expires_at"],
                    proposal["state"],
                    proposal_json,
                ),
            ).rowcount == 1
            row = connection.execute(
                "SELECT proposal_json,state FROM agent_proposals WHERE idempotency_key=?",
                (proposal["idempotency_key"],),
            ).fetchone()
        stored = json.loads(row["proposal_json"])
        stored["state"] = row["state"]
        return stored, inserted

    def proposal(self, proposal_id: str) -> dict[str, Any] | None:
        with self.connect() as connection:
            row = connection.execute(
                "SELECT proposal_json,state FROM agent_proposals WHERE proposal_id=?", (proposal_id,)
            ).fetchone()
        if row is None:
            return None
        proposal = json.loads(row["proposal_json"])
        proposal["state"] = row["state"]
        return proposal

    def proposal_state(self, proposal_id: str) -> str | None:
        with self.connect() as connection:
            row = connection.execute(
                "SELECT state FROM agent_proposals WHERE proposal_id=?", (proposal_id,)
            ).fetchone()
        return str(row["state"]) if row else None

    def focus_plan_count(self, since: str, until: str) -> int:
        with self.connect() as connection:
            rows = connection.execute(
                "SELECT proposal_json FROM agent_proposals WHERE created_at>=? AND created_at<?",
                (since, until),
            ).fetchall()
        return sum(
            1
            for row in rows
            if json.loads(row["proposal_json"]).get("action_type") == "focus.plan"
        )

    def accepted_focus_refs(self) -> set[str]:
        with self.connect() as connection:
            rows = connection.execute(
                "SELECT proposal_id,proposal_json FROM agent_proposals WHERE state='accepted'"
            ).fetchall()
        return {
            str(row["proposal_id"])
            for row in rows
            if json.loads(row["proposal_json"]).get("action_type") == "focus.propose"
        }

    def inbox(self, *, after: str | None, now: str, limit: int = 100) -> tuple[list[dict[str, Any]], str | None]:
        with self.connect() as connection:
            connection.execute(
                "UPDATE agent_proposals SET state='expired' WHERE state='proposed' AND expires_at<=?",
                (now,),
            )
            after_rowid = 0
            if after:
                cursor_row = connection.execute(
                    "SELECT rowid FROM agent_proposals WHERE proposal_id=?", (after,)
                ).fetchone()
                if cursor_row is None:
                    raise UnknownProposalCursor("cursor inconnu")
                after_rowid = int(cursor_row["rowid"])
            rows = connection.execute(
                "SELECT rowid,proposal_id,proposal_json FROM agent_proposals "
                "WHERE rowid>? ORDER BY rowid LIMIT ?",
                (after_rowid, max(limit * 5, limit)),
            ).fetchall()
            proposals: list[dict[str, Any]] = []
            cursor: str | None = after
            for row in rows:
                cursor = row["proposal_id"]
                ack = connection.execute(
                    "SELECT 1 FROM agent_proposal_acks WHERE proposal_id=?", (row["proposal_id"],)
                ).fetchone()
                proposal = json.loads(row["proposal_json"])
                proposal_row = connection.execute(
                    "SELECT state FROM agent_proposals WHERE proposal_id=?", (row["proposal_id"],)
                ).fetchone()
                proposal["state"] = proposal_row["state"]
                if not ack and proposal_row["state"] == "proposed" and proposal["expires_at"] > now:
                    proposals.append(proposal)
                    if len(proposals) >= limit:
                        break
        return proposals, cursor

    def acknowledge_proposal(
        self,
        proposal_id: str,
        *,
        state: str,
        effect: str | None,
        acknowledged_at: str,
        now: str,
    ) -> tuple[dict[str, Any], bool]:
        transitions = {
            "proposed": {"pending_confirmation", "accepted", "rejected", "expired", "cancelled"},
            "pending_confirmation": {"accepted", "rejected", "expired", "cancelled"},
            "accepted": {"executed", "rejected", "expired", "cancelled"},
        }
        with self.connect() as connection:
            row = connection.execute(
                "SELECT state,expires_at FROM agent_proposals WHERE proposal_id=?", (proposal_id,)
            ).fetchone()
            if row is None:
                raise UnknownProposal("proposition inconnue")
            prior_ack = connection.execute(
                "SELECT state,effect,acknowledged_at FROM agent_proposal_acks WHERE proposal_id=?",
                (proposal_id,),
            ).fetchone()
            if prior_ack:
                if prior_ack["state"] == state:
                    return {
                        "proposal_id": proposal_id,
                        "state": prior_ack["state"],
                        "effect": prior_ack["effect"],
                        "at": prior_ack["acknowledged_at"],
                    }, False
                if row["state"] not in transitions or state not in transitions[row["state"]]:
                    raise InvalidProposalTransition("transition de proposition interdite")
                connection.execute(
                    "UPDATE agent_proposal_acks SET state=?,effect=?,acknowledged_at=? WHERE proposal_id=?",
                    (state, effect, acknowledged_at, proposal_id),
                )
            else:
                current = row["state"]
                if current == "expired" and state == "expired":
                    pass
                elif current == "proposed" and row["expires_at"] <= now and state not in {"expired", "rejected", "cancelled"}:
                    raise InvalidProposalTransition("proposition expirée")
                elif current not in transitions or state not in transitions[current]:
                    raise InvalidProposalTransition("transition de proposition interdite")
                connection.execute(
                    "INSERT INTO agent_proposal_acks(proposal_id,state,effect,acknowledged_at) VALUES(?,?,?,?)",
                    (proposal_id, state, effect, acknowledged_at),
                )
            connection.execute(
                "UPDATE agent_proposals SET state=? WHERE proposal_id=?", (state, proposal_id)
            )
        return {"proposal_id": proposal_id, "state": state, "effect": effect, "at": acknowledged_at}, True

    def get_project_memory(self) -> dict[str, Any]:
        with self.connect() as connection:
            row = connection.execute(
                "SELECT memory_json FROM project_memory WHERE memory_id=1"
            ).fetchone()
        if row:
            return json.loads(row["memory_json"])
        return {"goals": [], "projects": [], "availability": [], "preferences": []}

    def set_project_memory(self, memory: dict[str, Any], updated_at: str) -> dict[str, Any]:
        memory_json = json.dumps(memory, ensure_ascii=False, sort_keys=True)
        with self.connect() as connection:
            connection.execute(
                "INSERT INTO project_memory(memory_id,memory_json,updated_at) VALUES(1,?,?) "
                "ON CONFLICT(memory_id) DO UPDATE SET memory_json=excluded.memory_json,updated_at=excluded.updated_at",
                (memory_json, updated_at),
            )
        return memory

    def delete_memory_entry(self, category: str, entry_id: str, updated_at: str) -> bool:
        memory = self.get_project_memory()
        entries = memory.get(category)
        if not isinstance(entries, list):
            return False
        updated = [entry for entry in entries if entry["id"] != entry_id]
        if len(updated) == len(entries):
            return False
        memory[category] = updated
        self.set_project_memory(memory, updated_at)
        return True

    def delete_project_memory(self) -> None:
        with self.connect() as connection:
            connection.execute("DELETE FROM project_memory WHERE memory_id=1")

    def insert_event(self, event: dict[str, Any], result: dict[str, Any]) -> tuple[dict[str, Any], bool]:
        event_id = event["event_id"]
        payload_json = json.dumps(event["payload"], ensure_ascii=False, sort_keys=True)
        with self.connect() as connection:
            inserted = connection.execute(
                "INSERT OR IGNORE INTO events(event_id,event_type,occurred_at,payload_json,result_json) "
                "VALUES(?,?,?,?,?)",
                (
                    event_id,
                    event["event_type"],
                    event["occurred_at"],
                    payload_json,
                    json.dumps(result, ensure_ascii=False, sort_keys=True),
                ),
            ).rowcount == 1
            if inserted:
                return result, True
            existing = connection.execute(
                "SELECT event_type,occurred_at,payload_json,result_json FROM events WHERE event_id = ?",
                (event_id,),
            ).fetchone()
            if (
                existing["event_type"] != event["event_type"]
                or existing["occurred_at"] != event["occurred_at"]
                or existing["payload_json"] != payload_json
            ):
                raise EventIdConflict("event_id déjà utilisé pour un événement différent")
            return json.loads(existing["result_json"]), False

    def event_result(self, event_id: str) -> dict[str, Any] | None:
        with self.connect() as connection:
            row = connection.execute(
                "SELECT result_json FROM events WHERE event_id = ?", (event_id,)
            ).fetchone()
        return json.loads(row["result_json"]) if row else None

    def event_count(self) -> int:
        with self.connect() as connection:
            return int(connection.execute("SELECT COUNT(*) FROM events").fetchone()[0])

    def save_response(self, directive_id: str, choice: str, result: dict[str, Any]) -> dict[str, Any]:
        with self.connect() as connection:
            inserted = connection.execute(
                "INSERT OR IGNORE INTO directive_responses(directive_id,choice,result_json) VALUES(?,?,?)",
                (directive_id, choice, json.dumps(result, ensure_ascii=False, sort_keys=True)),
            ).rowcount == 1
            if inserted:
                return result
            existing = connection.execute(
                "SELECT result_json FROM directive_responses WHERE directive_id=? AND choice=?",
                (directive_id, choice),
            ).fetchone()
            return json.loads(existing["result_json"])

    def export_data(self) -> dict[str, Any]:
        with self.connect() as connection:
            event_rows = connection.execute(
                "SELECT event_id,event_type,occurred_at,payload_json,result_json "
                "FROM events ORDER BY occurred_at,event_id"
            ).fetchall()
            response_rows = connection.execute(
                "SELECT directive_id,choice,result_json FROM directive_responses "
                "ORDER BY directive_id,choice"
            ).fetchall()
        return {
            "events": [
                {
                    "event_id": row["event_id"],
                    "event_type": row["event_type"],
                    "occurred_at": row["occurred_at"],
                    "payload": json.loads(row["payload_json"]),
                    "result": json.loads(row["result_json"]),
                }
                for row in event_rows
            ],
            "directive_responses": [
                {
                    "directive_id": row["directive_id"],
                    "choice": row["choice"],
                    "result": json.loads(row["result_json"]),
                }
                for row in response_rows
            ],
        }


class EventIdConflict(ValueError):
    """An idempotency key was reused with a different event body."""


class PlanRevisionIdempotencyConflict(ValueError):
    """A plan revision request id was reused with a different body."""


class UnknownProposal(ValueError):
    """A proposal ID does not exist in the proposal store."""


class UnknownProposalCursor(ValueError):
    """An inbox cursor does not refer to a stored proposal."""


class InvalidProposalTransition(ValueError):
    """A client acknowledgement attempted an invalid state transition."""


class MigrationChecksumConflict(ValueError):
    """An already recorded migration does not match the checked-in migration."""


class MigrationIntegrityError(ValueError):
    """SQLite integrity validation failed during an additive migration."""
