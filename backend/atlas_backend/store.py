"""Minimal SQLite persistence for idempotent events and directive responses."""

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
                """
            )

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
