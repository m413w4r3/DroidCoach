"""SQLite persistence for V1 records and V2 proposals, memory, and idempotence."""

from __future__ import annotations

import json
import sqlite3
from pathlib import Path
from typing import Any


class SQLiteStore:
    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.initialize()

    def connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path, timeout=5)
        connection.row_factory = sqlite3.Row
        return connection

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


class UnknownProposal(ValueError):
    """A proposal ID does not exist in the proposal store."""


class UnknownProposalCursor(ValueError):
    """An inbox cursor does not refer to a stored proposal."""


class InvalidProposalTransition(ValueError):
    """A client acknowledgement attempted an invalid state transition."""
