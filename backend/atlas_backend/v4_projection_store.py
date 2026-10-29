"""Additive V4 projection and proposal persistence on the existing SQLite store."""

from __future__ import annotations

import hashlib
import json
import re
import sqlite3
import uuid
from dataclasses import dataclass
from typing import Any

from .store import SQLiteStore


class V4ProjectionError(ValueError):
    """Base class for stable V4 projection-store failures."""

    code = "CONFLICT"


class ProjectionConflict(V4ProjectionError):
    code = "CONFLICT"


class StaleProjectionVersion(V4ProjectionError):
    code = "STALE_VERSION"


class EventIdConflict(V4ProjectionError):
    code = "EVENT_ID_CONFLICT"


class AckIdConflict(V4ProjectionError):
    code = "ACK_ID_CONFLICT"


class AckConflict(V4ProjectionError):
    code = "ACK_CONFLICT"


class AckValidationError(V4ProjectionError):
    code = "VALIDATION_FAILED"


class SequenceGap(V4ProjectionError):
    code = "STALE_DATA"


class UnsupportedPayloadVersion(V4ProjectionError):
    code = "UNSUPPORTED_VERSION"


@dataclass(frozen=True)
class WriteReceipt:
    accepted: bool
    replayed: bool
    record_id: str
    record_hash: str
    received_at: str | None = None


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def content_hash(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


_CONSENT_CATEGORIES = frozenset({
    "DAY_PLAN", "ACTIVITY_STATUS", "USAGE_SUMMARY", "GOAL_PROGRESS", "TRAINING_PLAN",
})
_CONSENT_STATES = frozenset({"GRANTED", "REVOKED", "UNKNOWN"})


class V4ProjectionStore:
    """Persists only dated projections and append-only receipts; never applies a plan."""

    def __init__(self, store: SQLiteStore) -> None:
        self.store = store

    def consent_state(self, device_ref: str, category: str) -> dict[str, Any] | None:
        if category not in _CONSENT_CATEGORIES:
            raise ValueError("catégorie de consentement V4 inconnue")
        with self.store.connect() as connection:
            row = connection.execute(
                "SELECT device_ref,category,consent_version,state,updated_at,source "
                "FROM consent_states_v4 WHERE device_ref=? AND category=?",
                (device_ref, category),
            ).fetchone()
        return dict(row) if row is not None else None

    def save_consent_state(
        self,
        device_ref: str,
        category: str,
        consent_version: int,
        state: str,
        *,
        idempotency_key: str,
        source: str = "ANDROID_SYNC",
        updated_at: str | None = None,
    ) -> dict[str, Any]:
        if (
            not isinstance(device_ref, str)
            or not device_ref
            or not isinstance(category, str)
            or category not in _CONSENT_CATEGORIES
        ):
            raise V4ProjectionError("device_ref ou catégorie de consentement V4 invalide")
        if (
            not isinstance(consent_version, int)
            or isinstance(consent_version, bool)
            or consent_version < 1
            or not isinstance(state, str)
            or state not in _CONSENT_STATES
            or not isinstance(idempotency_key, str)
            or not idempotency_key
            or not isinstance(source, str)
            or source != "ANDROID_SYNC"
        ):
            raise V4ProjectionError("état de consentement V4 invalide")
        try:
            idempotency_key = str(uuid.UUID(idempotency_key))
        except ValueError:
            raise V4ProjectionError("clé d’idempotence de consentement V4 invalide") from None
        request_hash = content_hash({
            "device_ref": device_ref,
            "category": category,
            "consent_version": consent_version,
            "state": state,
            "source": source,
        })
        proposed_timestamp = updated_at or self.store.now_iso()
        timestamp = proposed_timestamp
        replayed = False
        with self.store.connect() as connection:
            self.store.begin_immediate(connection)
            row = connection.execute(
                "SELECT consent_version,state,updated_at,source FROM consent_states_v4 "
                "WHERE device_ref=? AND category=?",
                (device_ref, category),
            ).fetchone()
            if row is not None and consent_version < int(row["consent_version"]):
                raise StaleProjectionVersion("version de consentement régressive")
            prior_event = connection.execute(
                "SELECT device_ref,category,consent_version,state,updated_at,source,body_hash "
                "FROM consent_state_events_v4 WHERE idempotency_key=?",
                (idempotency_key,),
            ).fetchone()
            if prior_event is not None:
                if prior_event["body_hash"] != request_hash:
                    raise IdempotencyConflict("clé d’idempotence de consentement utilisée avec un autre état")
                replayed = True
                timestamp = str(prior_event["updated_at"])
                result_state = {
                    "device_ref": str(prior_event["device_ref"]),
                    "category": str(prior_event["category"]),
                    "consent_version": int(prior_event["consent_version"]),
                    "state": str(prior_event["state"]),
                    "updated_at": timestamp,
                    "source": str(prior_event["source"]),
                    "replayed": True,
                }
                return result_state
            if row is not None:
                current_version = int(row["consent_version"])
                if consent_version == current_version:
                    if state != row["state"]:
                        raise ProjectionConflict("état différent pour la même version de consentement")
                    replayed = True
                    timestamp = str(row["updated_at"])
                    source = str(row["source"])
                else:
                    connection.execute(
                        "UPDATE consent_states_v4 SET consent_version=?,state=?,updated_at=?,source=? "
                        "WHERE device_ref=? AND category=?",
                        (consent_version, state, timestamp, source, device_ref, category),
                    )
            else:
                connection.execute(
                    "INSERT INTO consent_states_v4(device_ref,category,consent_version,state,updated_at,source) "
                    "VALUES(?,?,?,?,?,?)",
                    (device_ref, category, consent_version, state, timestamp, source),
                )
            connection.execute(
                "INSERT INTO consent_state_events_v4(idempotency_key,device_ref,category,consent_version,state,updated_at,source,body_hash) "
                "VALUES(?,?,?,?,?,?,?,?)",
                (idempotency_key, device_ref, category, consent_version, state, timestamp, source, request_hash),
            )
        return {
            "device_ref": device_ref,
            "category": category,
            "consent_version": consent_version,
            "state": state,
            "updated_at": timestamp,
            "source": source,
            "replayed": replayed,
        }

    @staticmethod
    def _raw_payload(payload: Any) -> bytes:
        if isinstance(payload, bytes):
            return payload
        if isinstance(payload, str):
            return payload.encode("utf-8")
        return canonical_json(payload).encode("utf-8")

    def _quarantine(
        self,
        *,
        kind: str,
        device_ref: str | None,
        schema_version: int | None,
        record_key: str | None,
        payload: Any,
        error_code: str,
        received_at: str | None,
    ) -> str:
        raw = self._raw_payload(payload)
        with self.store.connect() as connection:
            return self.store._insert_quarantine(
                connection,
                kind,
                device_ref,
                schema_version,
                record_key,
                raw,
                error_code,
                received_at or self.store.now_iso(),
            )

    def quarantine_payload(
        self,
        payload: Any,
        *,
        kind: str,
        schema_version: int | None,
        error_code: str,
        device_ref: str | None = None,
        record_key: str | None = None,
        received_at: str | None = None,
    ) -> str:
        return self._quarantine(
            kind=kind,
            device_ref=device_ref,
            schema_version=schema_version,
            record_key=record_key,
            payload=payload,
            error_code=error_code,
            received_at=received_at,
        )

    def _require_v4(
        self,
        payload: Any,
        schema_version: int | None,
        *,
        kind: str,
        device_ref: str | None,
        record_key: str | None,
        received_at: str | None,
    ) -> None:
        if isinstance(payload, dict) and "schema_version" in payload:
            try:
                schema_version = int(payload["schema_version"])
            except (TypeError, ValueError):
                schema_version = None
        if schema_version == 4:
            return
        self._quarantine(
            kind=kind,
            device_ref=device_ref,
            schema_version=schema_version,
            record_key=record_key,
            payload=payload,
            error_code="UNSUPPORTED_VERSION",
            received_at=received_at,
        )
        raise UnsupportedPayloadVersion(f"schema_version V4 inconnu: {schema_version!r}")

    @staticmethod
    def _required(value: dict[str, Any], key: str) -> Any:
        if key not in value or value[key] is None:
            raise V4ProjectionError(f"champ requis absent: {key}")
        return value[key]

    def save_day_plan_snapshot(
        self,
        device_ref: str,
        snapshot: dict[str, Any],
        *,
        consent_version: int,
        received_at: str | None = None,
        schema_version: int = 4,
    ) -> WriteReceipt:
        received_at = received_at or self.store.now_iso()
        plan_id = str(snapshot.get("plan_id", ""))
        self._require_v4(
            snapshot, schema_version, kind="DAY_PLAN", device_ref=device_ref,
            record_key=f"{plan_id}:{snapshot.get('version', '?')}", received_at=received_at,
        )
        version = int(self._required(snapshot, "version"))
        if version < 1 or consent_version < 1:
            raise V4ProjectionError("version et consent_version doivent être positifs")
        local_date = str(self._required(snapshot, "local_date"))
        timezone_name = str(self._required(snapshot, "timezone"))
        revision_id = str(self._required(snapshot, "current_revision_id"))
        observed_at = str(self._required(snapshot, "observed_at"))
        if not device_ref or not plan_id:
            raise V4ProjectionError("device_ref et plan_id sont requis")
        projection = self._clean_plan_projection(snapshot)
        snapshot_hash = content_hash({"snapshot": projection, "consent_version": consent_version})
        projection_json = canonical_json(projection)
        replayed = False
        with self.store.connect() as connection:
            self.store.begin_immediate(connection)
            latest = connection.execute(
                "SELECT plan_version FROM day_plan_snapshots_v4 "
                "WHERE device_ref=? AND plan_id=? ORDER BY plan_version DESC LIMIT 1",
                (device_ref, plan_id),
            ).fetchone()
            existing = connection.execute(
                "SELECT snapshot_hash,consent_version,projection_json FROM day_plan_snapshots_v4 "
                "WHERE device_ref=? AND plan_id=? AND plan_version=?",
                (device_ref, plan_id, version),
            ).fetchone()
            if existing is not None:
                if json.loads(existing["projection_json"]) != projection:
                    raise ProjectionConflict("snapshot de plan différent pour la même version")
                existing_consent_version = int(existing["consent_version"])
                if consent_version < existing_consent_version:
                    raise StaleProjectionVersion("version de consentement régressive")
                if consent_version > existing_consent_version:
                    connection.execute(
                        "UPDATE day_plan_snapshots_v4 SET consent_version=?,snapshot_hash=?,received_at=? "
                        "WHERE device_ref=? AND plan_id=? AND plan_version=?",
                        (consent_version, snapshot_hash, received_at, device_ref, plan_id, version),
                    )
                elif existing["snapshot_hash"] != snapshot_hash:
                    raise ProjectionConflict("hash de plan différent pour la même version")
                replayed = True
            elif latest is not None and version < int(latest["plan_version"]):
                raise StaleProjectionVersion("version de plan régressive")
            else:
                connection.execute(
                    "INSERT INTO day_plan_snapshots_v4(device_ref,plan_id,local_date,timezone,plan_version,revision_id,schema_version,observed_at,received_at,consent_version,snapshot_hash,projection_json) "
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    (
                        device_ref, plan_id, local_date, timezone_name, version, revision_id,
                        schema_version, observed_at, received_at, consent_version, snapshot_hash,
                        projection_json,
                    ),
                )
        return WriteReceipt(True, replayed, f"{plan_id}:{version}", snapshot_hash)

    def latest_day_plan_snapshot(
        self,
        device_ref: str,
        *,
        plan_id: str | None = None,
        local_date: str | None = None,
    ) -> dict[str, Any] | None:
        clauses = ["device_ref=?"]
        values: list[Any] = [device_ref]
        if plan_id is not None:
            clauses.append("plan_id=?")
            values.append(plan_id)
        if local_date is not None:
            clauses.append("local_date=?")
            values.append(local_date)
        with self.store.connect() as connection:
            row = connection.execute(
                "SELECT * FROM day_plan_snapshots_v4 WHERE " + " AND ".join(clauses) +
                (" ORDER BY received_at DESC, plan_version DESC LIMIT 1" if plan_id is None else
                 " ORDER BY plan_version DESC, received_at DESC LIMIT 1"),
                values,
            ).fetchone()
        return self._decode_row(row, "projection_json")

    @staticmethod
    def _activity_status_event_hash(device_ref: str, event: dict[str, Any], consent_version: int) -> str:
        return content_hash({"device_ref": device_ref, "event": event, "consent_version": consent_version})

    def existing_activity_status_event(
        self, device_ref: str, event: dict[str, Any]
    ) -> WriteReceipt | None:
        """Return a same-device event receipt before current-consent checks, if present."""
        event_id = str(event.get("event_id", ""))
        if not event_id or not device_ref:
            return None
        with self.store.connect() as connection:
            existing = connection.execute(
                "SELECT event_hash,consent_version,received_at FROM activity_status_events_v4 "
                "WHERE device_ref=? AND event_id=?",
                (device_ref, event_id),
            ).fetchone()
        if existing is None:
            return None
        stored_hash = str(existing["event_hash"])
        expected_hash = self._activity_status_event_hash(
            device_ref, event, int(existing["consent_version"])
        )
        if stored_hash != expected_hash:
            raise EventIdConflict("event_id déjà utilisé pour un corps différent")
        return WriteReceipt(
            True,
            True,
            event_id,
            stored_hash,
            str(existing["received_at"]) if existing["received_at"] is not None else None,
        )

    def save_activity_status_event(
        self,
        device_ref: str,
        event: dict[str, Any],
        *,
        consent_version: int,
        received_at: str | None = None,
        schema_version: int = 4,
    ) -> WriteReceipt:
        received_at = received_at or self.store.now_iso()
        event_id = str(event.get("event_id", ""))
        self._require_v4(
            event, schema_version, kind="ACTIVITY_STATUS", device_ref=device_ref,
            record_key=event_id or None, received_at=received_at,
        )
        sequence = int(self._required(event, "sequence"))
        if sequence < 1 or consent_version < 1:
            raise V4ProjectionError("sequence et consent_version doivent être positifs")
        values = {
            "event_id": event_id,
            "device_ref": device_ref,
            "plan_id": str(self._required(event, "plan_id")),
            "activity_id": str(self._required(event, "activity_id")),
            "continuity_id": event.get("continuity_id"),
            "sequence": sequence,
            "old_status": str(event.get("from_status", event.get("old_status")) or self._required(event, "from_status")),
            "new_status": str(event.get("to_status", event.get("new_status")) or self._required(event, "to_status")),
            "provenance": str(self._required(event, "provenance")),
            "source": str(self._required(event, "source")),
            "decided_at": str(self._required(event, "decided_at")),
            "observed_at": str(self._required(event, "observed_at")),
            "plan_version": int(self._required(event, "plan_version")),
            "consent_version": consent_version,
        }
        if not event_id or not device_ref:
            raise V4ProjectionError("device_ref et event_id sont requis")
        event_hash = self._activity_status_event_hash(device_ref, event, consent_version)
        raw = self._raw_payload(event)
        gap = False
        replayed = False
        receipt_hash = event_hash
        receipt_received_at = received_at
        with self.store.connect() as connection:
            self.store.begin_immediate(connection)
            existing = connection.execute(
                "SELECT event_hash,consent_version,received_at FROM activity_status_events_v4 "
                "WHERE device_ref=? AND event_id=?",
                (device_ref, event_id),
            ).fetchone()
            if existing is not None:
                expected_hash = self._activity_status_event_hash(
                    device_ref, event, int(existing["consent_version"])
                )
                if existing["event_hash"] != expected_hash:
                    raise EventIdConflict("event_id déjà utilisé pour un corps différent")
                replayed = True
                receipt_hash = str(existing["event_hash"])
                if existing["received_at"] is not None:
                    receipt_received_at = str(existing["received_at"])
            else:
                latest = connection.execute(
                    "SELECT MAX(sequence) AS sequence FROM activity_status_events_v4 WHERE device_ref=?",
                    (device_ref,),
                ).fetchone()["sequence"]
                expected = 1 if latest is None else int(latest) + 1
                if sequence <= (int(latest) if latest is not None else 0):
                    raise ProjectionConflict("séquence de statut déjà utilisée par un autre événement")
                if sequence != expected:
                    self.store._insert_quarantine(
                        connection, "ACTIVITY_STATUS", device_ref, schema_version, event_id,
                        raw, "SEQUENCE_GAP", received_at,
                    )
                    gap = True
                else:
                    try:
                        connection.execute(
                            "INSERT INTO activity_status_events_v4(event_id,device_ref,plan_id,activity_id,continuity_id,sequence,old_status,new_status,provenance,source,decided_at,observed_at,received_at,plan_version,consent_version,event_hash) "
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            tuple(values[key] for key in (
                                "event_id", "device_ref", "plan_id", "activity_id", "continuity_id",
                                "sequence", "old_status", "new_status", "provenance", "source",
                                "decided_at", "observed_at",
                            )) + (received_at, values["plan_version"], values["consent_version"], event_hash),
                        )
                    except sqlite3.IntegrityError as exc:
                        global_event = connection.execute(
                            "SELECT 1 FROM activity_status_events_v4 WHERE event_id=?", (event_id,)
                        ).fetchone()
                        if global_event is not None:
                            raise EventIdConflict("event_id indisponible") from exc
                        raise ProjectionConflict("séquence de statut déjà utilisée") from exc
        if gap:
            raise SequenceGap(f"séquence de statut attendue {expected}, reçue {sequence}")
        return WriteReceipt(True, replayed, event_id, receipt_hash, receipt_received_at)

    def latest_activity_status_event(
        self, device_ref: str, plan_id: str, activity_id: str
    ) -> dict[str, Any] | None:
        with self.store.connect() as connection:
            row = connection.execute(
                "SELECT * FROM activity_status_events_v4 WHERE device_ref=? AND plan_id=? AND activity_id=? "
                "ORDER BY sequence DESC LIMIT 1",
                (device_ref, plan_id, activity_id),
            ).fetchone()
        return dict(row) if row else None

    def has_activity_status_sequence_gap(self, device_ref: str) -> bool:
        with self.store.connect() as connection:
            row = connection.execute(
                "SELECT 1 FROM v4_quarantine WHERE kind='ACTIVITY_STATUS' AND device_ref=? "
                "AND error_code='SEQUENCE_GAP' LIMIT 1",
                (device_ref,),
            ).fetchone()
        return row is not None

    def save_usage_summary(
        self,
        device_ref: str,
        summary: dict[str, Any],
        *,
        consent_version: int,
        received_at: str | None = None,
        schema_version: int = 4,
    ) -> WriteReceipt:
        received_at = received_at or self.store.now_iso()
        self._require_v4(
            summary, schema_version, kind="USAGE_SUMMARY", device_ref=device_ref,
            record_key=f"{summary.get('window_start')}:{summary.get('window_end')}", received_at=received_at,
        )
        window_start = str(self._required(summary, "window_start"))
        window_end = str(self._required(summary, "window_end"))
        timezone_name = str(self._required(summary, "timezone"))
        generated_at = str(self._required(summary, "generated_at"))
        if consent_version < 1 or window_end <= window_start:
            raise V4ProjectionError("fenêtre d'usage ou consent_version invalide")
        summary_hash = content_hash({"summary": summary, "consent_version": consent_version})
        with self.store.connect() as connection:
            self.store.begin_immediate(connection)
            row = connection.execute(
                "SELECT summary_hash FROM usage_summaries_v4 WHERE device_ref=? AND window_start=? AND window_end=? AND consent_version=?",
                (device_ref, window_start, window_end, consent_version),
            ).fetchone()
            if row is not None:
                if row["summary_hash"] != summary_hash:
                    raise ProjectionConflict("résumé d'usage différent pour la même fenêtre/consentement")
                replayed = True
            else:
                connection.execute(
                    "INSERT INTO usage_summaries_v4(device_ref,window_start,window_end,timezone,generated_at,received_at,consent_version,summary_hash,summary_json) VALUES(?,?,?,?,?,?,?,?,?)",
                    (device_ref, window_start, window_end, timezone_name, generated_at, received_at,
                     consent_version, summary_hash, canonical_json(summary)),
                )
                replayed = False
        return WriteReceipt(True, replayed, f"{window_start}:{window_end}", summary_hash)

    def latest_usage_summary(
        self, device_ref: str, window_start: str | None = None, window_end: str | None = None
    ) -> dict[str, Any] | None:
        clauses = ["device_ref=?"]
        values: list[Any] = [device_ref]
        if window_start is not None:
            clauses.append("window_start=?")
            values.append(window_start)
        if window_end is not None:
            clauses.append("window_end=?")
            values.append(window_end)
        with self.store.connect() as connection:
            row = connection.execute(
                "SELECT * FROM usage_summaries_v4 WHERE " + " AND ".join(clauses) +
                " ORDER BY received_at DESC LIMIT 1", values,
            ).fetchone()
        return self._decode_row(row, "summary_json")

    def save_goal_progress_snapshot(
        self,
        device_ref: str,
        snapshot: dict[str, Any],
        *,
        consent_version: int,
        received_at: str | None = None,
        schema_version: int = 4,
    ) -> WriteReceipt:
        received_at = received_at or self.store.now_iso()
        goals_version = int(self._required(snapshot, "goals_version"))
        self._require_v4(
            snapshot, schema_version, kind="GOAL_PROGRESS", device_ref=device_ref,
            record_key=str(goals_version), received_at=received_at,
        )
        observed_at = str(self._required(snapshot, "observed_at"))
        if goals_version < 1 or consent_version < 1:
            raise V4ProjectionError("goals_version et consent_version doivent être positifs")
        snapshot_hash = content_hash({"snapshot": snapshot, "consent_version": consent_version})
        with self.store.connect() as connection:
            self.store.begin_immediate(connection)
            latest = connection.execute(
                "SELECT MAX(goals_version) AS goals_version FROM goal_progress_snapshots_v4 WHERE device_ref=?",
                (device_ref,),
            ).fetchone()["goals_version"]
            existing = connection.execute(
                "SELECT snapshot_hash,consent_version,projection_json FROM goal_progress_snapshots_v4 "
                "WHERE device_ref=? AND goals_version=?",
                (device_ref, goals_version),
            ).fetchone()
            if existing is not None:
                if json.loads(existing["projection_json"]) != snapshot:
                    raise ProjectionConflict("snapshot d'objectifs différent pour la même version")
                existing_consent_version = int(existing["consent_version"])
                if consent_version < existing_consent_version:
                    raise StaleProjectionVersion("version de consentement régressive")
                if consent_version > existing_consent_version:
                    connection.execute(
                        "UPDATE goal_progress_snapshots_v4 SET consent_version=?,snapshot_hash=?,received_at=? "
                        "WHERE device_ref=? AND goals_version=?",
                        (consent_version, snapshot_hash, received_at, device_ref, goals_version),
                    )
                elif existing["snapshot_hash"] != snapshot_hash:
                    raise ProjectionConflict("hash d'objectifs différent pour la même version")
                replayed = True
            elif latest is not None and goals_version < int(latest):
                raise StaleProjectionVersion("version d'objectifs régressive")
            else:
                connection.execute(
                    "INSERT INTO goal_progress_snapshots_v4(device_ref,goals_version,observed_at,received_at,consent_version,snapshot_hash,projection_json) VALUES(?,?,?,?,?,?,?)",
                    (device_ref, goals_version, observed_at, received_at, consent_version,
                     snapshot_hash, canonical_json(snapshot)),
                )
                replayed = False
        return WriteReceipt(True, replayed, str(goals_version), snapshot_hash)

    def latest_goal_progress_snapshot(self, device_ref: str) -> dict[str, Any] | None:
        with self.store.connect() as connection:
            row = connection.execute(
                "SELECT * FROM goal_progress_snapshots_v4 WHERE device_ref=? ORDER BY goals_version DESC LIMIT 1",
                (device_ref,),
            ).fetchone()
        return self._decode_row(row, "projection_json")

    def save_training_snapshot(
        self,
        device_ref: str,
        snapshot: dict[str, Any],
        *,
        consent_version: int,
        received_at: str | None = None,
        schema_version: int = 4,
    ) -> WriteReceipt:
        received_at = received_at or self.store.now_iso()
        provider = str(self._required(snapshot, "provider"))
        observed_at = str(self._required(snapshot, "observed_at"))
        self._require_v4(
            snapshot, schema_version, kind="TRAINING", device_ref=device_ref,
            record_key=f"{provider}:{observed_at}", received_at=received_at,
        )
        if consent_version < 1:
            raise V4ProjectionError("consent_version doit être positif")
        snapshot_hash = content_hash({"snapshot": snapshot, "consent_version": consent_version})
        with self.store.connect() as connection:
            self.store.begin_immediate(connection)
            row = connection.execute(
                "SELECT snapshot_hash FROM training_snapshots_v4 WHERE device_ref=? AND provider=? AND observed_at=?",
                (device_ref, provider, observed_at),
            ).fetchone()
            if row is not None:
                if row["snapshot_hash"] != snapshot_hash:
                    raise ProjectionConflict("snapshot training différent pour la même observation")
                replayed = True
            else:
                connection.execute(
                    "INSERT INTO training_snapshots_v4(device_ref,provider,observed_at,received_at,sync_state,source_version,consent_version,snapshot_hash,projection_json) VALUES(?,?,?,?,?,?,?,?,?)",
                    (device_ref, provider, observed_at, received_at,
                     str(snapshot.get("sync_state", "UNKNOWN")), snapshot.get("source_version"),
                     consent_version, snapshot_hash, canonical_json(snapshot)),
                )
                replayed = False
        return WriteReceipt(True, replayed, f"{provider}:{observed_at}", snapshot_hash)

    def latest_training_snapshot(self, device_ref: str, provider: str | None = None) -> dict[str, Any] | None:
        with self.store.connect() as connection:
            if provider is None:
                row = connection.execute(
                    "SELECT * FROM training_snapshots_v4 WHERE device_ref=? ORDER BY observed_at DESC LIMIT 1",
                    (device_ref,),
                ).fetchone()
            else:
                row = connection.execute(
                    "SELECT * FROM training_snapshots_v4 WHERE device_ref=? AND provider=? ORDER BY observed_at DESC LIMIT 1",
                    (device_ref, provider),
                ).fetchone()
        return self._decode_row(row, "projection_json")

    def create_agent_proposal(
        self,
        proposal: dict[str, Any],
        *,
        idempotency_key: str,
        body_hash: str,
    ) -> tuple[dict[str, Any], bool]:
        proposal_json = canonical_json(proposal)
        with self.store.connect() as connection:
            self.store.begin_immediate(connection)
            existing = connection.execute(
                "SELECT body_hash,proposal_json,state FROM agent_proposals_v4 WHERE idempotency_key=?",
                (idempotency_key,),
            ).fetchone()
            if existing is not None:
                if existing["body_hash"] != body_hash:
                    raise IdempotencyConflict("idempotency_key réutilisée avec un autre corps")
                value = json.loads(existing["proposal_json"])
                value["state"] = existing["state"]
                return value, False
            connection.execute(
                "INSERT INTO agent_proposals_v4(proposal_id,idempotency_key,body_hash,plan_id,base_version,capability,created_at,expires_at,state,proposal_json) VALUES(?,?,?,?,?,?,?,?,?,?)",
                (
                    proposal["proposal_id"], idempotency_key, body_hash, proposal["plan_id"],
                    proposal["base_version"], proposal["capability"], proposal["created_at"],
                    proposal["expires_at"], proposal["state"], proposal_json,
                ),
            )
        return proposal, True

    def proposal_by_idempotency_key(self, idempotency_key: str) -> dict[str, Any] | None:
        with self.store.connect() as connection:
            row = connection.execute(
                "SELECT proposal_json,state FROM agent_proposals_v4 WHERE idempotency_key=?",
                (idempotency_key,),
            ).fetchone()
        if row is None:
            return None
        proposal = json.loads(row["proposal_json"])
        proposal["state"] = row["state"]
        return proposal

    def append_proposal_ack(
        self,
        ack: dict[str, Any],
        *,
        received_at: str | None = None,
        schema_version: int = 4,
    ) -> WriteReceipt:
        received_at = received_at or self.store.now_iso()
        ack_id = str(ack.get("ack_id", ""))
        self._require_v4(
            ack, schema_version, kind="PROPOSAL_ACK", device_ref=None,
            record_key=ack_id or None, received_at=received_at,
        )
        ack_id = str(self._required(ack, "ack_id"))
        proposal_id = str(self._required(ack, "proposal_id"))
        sequence = int(self._required(ack, "sequence"))
        state = str(self._required(ack, "state"))
        result_code = str(self._required(ack, "result_code"))
        occurred_at = str(self._required(ack, "occurred_at"))
        if sequence < 1:
            raise V4ProjectionError("sequence ACK doit être positif")
        immutable_body = dict(ack)
        body_hash = content_hash(immutable_body)
        replayed = False
        with self.store.connect() as connection:
            self.store.begin_immediate(connection)
            existing = connection.execute(
                "SELECT body_hash FROM agent_proposal_events_v4 WHERE ack_id=?", (ack_id,)
            ).fetchone()
            if existing is not None:
                if existing["body_hash"] != body_hash:
                    raise AckIdConflict("ack_id déjà utilisé avec un corps différent")
                replayed = True
            else:
                self._validate_proposal_ack(ack, state, result_code)
                proposal = connection.execute(
                    "SELECT state FROM agent_proposals_v4 WHERE proposal_id=?", (proposal_id,)
                ).fetchone()
                if proposal is None:
                    raise V4ProjectionError("proposition V4 inconnue")
                latest = connection.execute(
                    "SELECT MAX(sequence) AS sequence FROM agent_proposal_events_v4 WHERE proposal_id=?",
                    (proposal_id,),
                ).fetchone()["sequence"]
                if latest is not None and sequence <= int(latest):
                    raise AckConflict("séquence ACK déjà utilisée par un autre ack_id")
                prior_state = str(proposal["state"])
                allowed = {
                    "PENDING_ANDROID": {"RECEIVED", "AWAITING_USER", "APPLIED", "REJECTED", "STALE_VERSION", "EXPIRED", "VALIDATION_REJECTED", "UNCERTAIN"},
                    "RECEIVED": {"AWAITING_USER", "APPLIED", "REJECTED", "STALE_VERSION", "EXPIRED", "VALIDATION_REJECTED", "UNCERTAIN"},
                    "AWAITING_USER": {"APPLIED", "REJECTED", "STALE_VERSION", "EXPIRED", "VALIDATION_REJECTED", "UNCERTAIN"},
                    "UNCERTAIN": {"RECONCILED"},
                }
                if state not in allowed.get(prior_state, set()):
                    raise AckConflict("transition ACK interdite")
                effect_ids = ack.get("effect_ids", [])
                connection.execute(
                    "INSERT INTO agent_proposal_events_v4(ack_id,proposal_id,sequence,state,result_code,applied_plan_version,revision_id,scope_hash,effect_ids_json,occurred_at,received_at,body_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    (
                        ack_id, proposal_id, sequence, state, result_code,
                        ack.get("applied_plan_version"), ack.get("revision_id"),
                        ack.get("scope_hash"), canonical_json(effect_ids), occurred_at,
                        received_at, body_hash,
                    ),
                )
                connection.execute(
                    "UPDATE agent_proposals_v4 SET state=? WHERE proposal_id=?",
                    (state, proposal_id),
                )
        return WriteReceipt(True, replayed, ack_id, body_hash)

    @staticmethod
    def _validate_proposal_ack(ack: dict[str, Any], state: str, result_code: str) -> None:
        result_by_state = {
            "APPLIED": {"APPLIED"},
            "REJECTED": {"USER_REJECTED"},
            "STALE_VERSION": {"STALE_VERSION"},
            "EXPIRED": {"PROPOSAL_EXPIRED"},
            "VALIDATION_REJECTED": {"VALIDATION_FAILED"},
            "UNCERTAIN": {"RESULT_UNCERTAIN"},
            "RECONCILED": {"RECONCILED_APPLIED", "RECONCILED_NOT_APPLIED", "USER_REJECTED"},
        }
        if result_code not in result_by_state.get(state, set()):
            raise AckValidationError("state et result_code ACK incompatibles")

        proof_fields = (
            "plan_id", "applied_plan_version", "revision_id", "scope_hash", "effect_ids",
        )
        applied = state == "APPLIED" or (state == "RECONCILED" and result_code == "RECONCILED_APPLIED")
        if not applied:
            if any(field in ack for field in proof_fields):
                raise AckValidationError("un ACK sans application ne doit pas porter de preuve")
            return

        missing = [field for field in proof_fields if field not in ack]
        if missing:
            raise AckValidationError("preuve d'application incomplète: " + ", ".join(missing))
        plan_id = ack["plan_id"]
        applied_version = ack["applied_plan_version"]
        revision_id = ack["revision_id"]
        scope_hash = ack["scope_hash"]
        effect_ids = ack["effect_ids"]
        if not isinstance(plan_id, str) or not plan_id:
            raise AckValidationError("plan_id de preuve invalide")
        try:
            uuid.UUID(plan_id)
        except (ValueError, AttributeError):
            raise AckValidationError("plan_id de preuve invalide") from None
        if not isinstance(applied_version, int) or isinstance(applied_version, bool) or applied_version < 1:
            raise AckValidationError("applied_plan_version de preuve invalide")
        if not isinstance(revision_id, str) or not revision_id:
            raise AckValidationError("revision_id de preuve invalide")
        try:
            uuid.UUID(revision_id)
        except (ValueError, AttributeError):
            raise AckValidationError("revision_id de preuve invalide") from None
        if not isinstance(scope_hash, str) or len(scope_hash) != 71 or not scope_hash.startswith("sha256:"):
            raise AckValidationError("scope_hash de preuve invalide")
        digest = scope_hash.removeprefix("sha256:")
        if any(character not in "0123456789abcdef" for character in digest):
            raise AckValidationError("scope_hash de preuve invalide")
        effect_pattern = re.compile(
            r"^(?:PLAN_ACTIVITY_(?:CREATE|UPDATE|DELETE):[0-9a-f-]{36}|PLAN_APPLY:[0-9a-f-]{36})$"
        )
        if not isinstance(effect_ids, list) or not 1 <= len(effect_ids) <= 32:
            raise AckValidationError("effect_ids de preuve invalides")
        if any(not isinstance(effect_id, str) for effect_id in effect_ids):
            raise AckValidationError("effect_ids de preuve invalides")
        if len(set(effect_ids)) != len(effect_ids):
            raise AckValidationError("effect_ids de preuve invalides")
        if any(
            len(effect_id) > 96
            or effect_pattern.fullmatch(effect_id) is None
            for effect_id in effect_ids
        ):
            raise AckValidationError("effect_ids de preuve invalides")

    @staticmethod
    def _decode_row(row: sqlite3.Row | None, json_column: str) -> dict[str, Any] | None:
        if row is None:
            return None
        result = dict(row)
        result["projection"] = json.loads(result[json_column])
        return result

    @staticmethod
    def _clean_plan_projection(snapshot: dict[str, Any]) -> dict[str, Any]:
        """Keep the contracted plan fields and drop any uncontracted private payload."""
        allowed_plan = {
            "plan_id", "local_date", "timezone", "version", "current_revision_id",
            "schema_version", "state", "origin", "observed_at", "activities",
        }
        allowed_activity = {
            "activity_id", "kind", "title", "status", "scheduled_local", "priority", "goal_id",
            "continuity_id", "supersedes_activity_id", "status_provenance", "status_decided_at",
            "status_observed_at", "status_sync_state",
        }
        result = {key: value for key, value in snapshot.items() if key in allowed_plan}
        activities = []
        for activity in snapshot.get("activities", []):
            item = {key: value for key, value in activity.items() if key in allowed_activity}
            scheduled = activity.get("scheduled_local", {})
            item["scheduled_local"] = {
                key: scheduled[key]
                for key in ("local_date", "start_minute", "duration_minutes", "timezone")
                if key in scheduled
            }
            activities.append(item)
        result["activities"] = activities
        return result


class IdempotencyConflict(V4ProjectionError):
    code = "IDEMPOTENCY_CONFLICT"
