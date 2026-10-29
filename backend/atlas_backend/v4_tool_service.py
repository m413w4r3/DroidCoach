"""Local, typed V4 tool functions. This module deliberately defines no HTTP routes."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
import uuid
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from typing import Any, Callable

from .v4_projection_store import (
    IdempotencyConflict,
    V4ProjectionStore,
    canonical_json,
    content_hash,
)


CAPABILITY_BY_TOOL = {
    "atlas_get_day_plan": "day_plan.read",
    "atlas_get_activity_status": "activity_status.read",
    "atlas_propose_plan_revision": "plan_revision.propose",
    "atlas_get_usage_summary": "usage_summary.read",
    "atlas_get_goal_progress": "goal_progress.read",
    "atlas_get_training_plan": "training_plan.read",
}

CONSENT_BY_CAPABILITY = {
    "day_plan.read": "DAY_PLAN",
    "activity_status.read": "ACTIVITY_STATUS",
    "plan_revision.propose": "DAY_PLAN",
    "usage_summary.read": "USAGE_SUMMARY",
    "goal_progress.read": "GOAL_PROGRESS",
    "training_plan.read": "TRAINING_PLAN",
}

FRESHNESS_AGE = {
    "day_plan.read": timedelta(minutes=5),
    "activity_status.read": timedelta(minutes=5),
    "usage_summary.read": timedelta(minutes=30),
    "goal_progress.read": timedelta(minutes=60),
    "training_plan.read": timedelta(hours=24),
    "plan_revision.propose": timedelta(minutes=5),
}


class V4ToolError(ValueError):
    """Stable typed error for an invalid or unavailable tool operation."""

    def __init__(self, code: str, message: str, *, capability: str | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.capability = capability

    def envelope(self, request_id: str) -> dict[str, Any]:
        retryable = self.code in {"TIMEOUT", "UPSTREAM_UNAVAILABLE", "RATE_LIMITED"}
        return {
            "contract_version": 4,
            "schema_version": 4,
            "request_id": request_id,
            "error": {
                "code": self.code,
                "message": str(self)[:240] or self.code,
                "retryable": retryable,
                "capability": self.capability,
                "retry_after_seconds": None,
            },
        }


@dataclass(frozen=True)
class Consent:
    state: str
    version: int | None


class V4ToolService:
    """Serves projections and stores proposals; Android remains the plan authority."""

    def __init__(
        self,
        projections: V4ProjectionStore,
        *,
        clock: Callable[[], datetime] | None = None,
        capabilities: dict[str, bool] | None = None,
        consent_lookup: Callable[[str, str], Consent | dict[str, Any] | tuple[str, int | None] | None] | None = None,
        request_id_factory: Callable[[], str] | None = None,
        cursor_secret: bytes | None = None,
        proposal_ttl: timedelta = timedelta(hours=24),
    ) -> None:
        self.projections = projections
        self.clock = clock or projections.store.store.clock
        self.capabilities = dict(capabilities or {})
        self.consent_lookup = consent_lookup or (lambda _device, _category: None)
        self.request_id_factory = request_id_factory or (lambda: str(uuid.uuid4()))
        self.cursor_secret = cursor_secret or secrets.token_bytes(32)
        self.proposal_ttl = proposal_ttl

    def atlas_get_day_plan(self, device_ref: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        capability = "day_plan.read"
        args = self._arguments(arguments or {}, {"local_date", "page_size", "cursor"}, capability)
        page_size = self._page_size(args.get("page_size", 32), 32, capability)
        if args.get("local_date") is not None:
            self._validate_date(args["local_date"], capability)
        consent = self._require_capability_and_consent(device_ref, capability)
        row = self.projections.latest_day_plan_snapshot(device_ref, local_date=args.get("local_date"))
        if row is None:
            return self._empty_result(capability, "UNAVAILABLE")
        projection = row["projection"]
        observed_at = row["observed_at"]
        received_at = row["received_at"]
        freshness, can_read = self._freshness(
            capability, observed_at, int(row["consent_version"]), consent
        )
        if projection.get("origin") == "DEMO":
            freshness, can_read = "UNKNOWN", False
        if not can_read:
            return self._empty_result(capability, freshness)
        activities = [self._clean_activity(activity) for activity in projection.get("activities", [])]
        binding = f"{device_ref}|{capability}|{row['snapshot_hash']}|{args.get('local_date', '')}"
        offset = self._decode_cursor(args.get("cursor"), binding, capability) if args.get("cursor") else 0
        page = activities[offset : offset + page_size]
        end = offset + len(page)
        truncated = end < len(activities)
        next_cursor = self._encode_cursor(end, binding) if truncated else None
        plan = {
            "plan_id": row["plan_id"],
            "local_date": row["local_date"],
            "timezone": row["timezone"],
            "version": row["plan_version"],
            "current_revision_id": row["revision_id"],
            "origin": projection.get("origin", "LOCAL"),
            "state": projection.get("state", "APPROVED"),
            "activities": page,
        }
        data = {"state": "READY", "plan": plan, "next_cursor": next_cursor, "truncated": truncated}
        return self._result(
            capability, data, freshness=freshness, source="ANDROID_SYNC", observed_at=observed_at,
            last_synced_at=received_at, plan_id=row["plan_id"], version=row["plan_version"],
        )

    def atlas_get_activity_status(self, device_ref: str, arguments: dict[str, Any]) -> dict[str, Any]:
        capability = "activity_status.read"
        args = self._arguments(arguments, {"activity_id", "plan_id", "include_details"}, capability,
                               required={"activity_id"})
        if not isinstance(args["activity_id"], str) or not args["activity_id"]:
            raise V4ToolError("BAD_REQUEST", "activity_id doit être une chaîne non vide", capability=capability)
        if args.get("plan_id") is not None and not isinstance(args["plan_id"], str):
            raise V4ToolError("BAD_REQUEST", "plan_id doit être une chaîne", capability=capability)
        if "include_details" in args and not isinstance(args["include_details"], bool):
            raise V4ToolError("BAD_REQUEST", "include_details doit être booléen", capability=capability)
        consent = self._require_capability_and_consent(device_ref, capability)
        row = self.projections.latest_day_plan_snapshot(device_ref, plan_id=args.get("plan_id"))
        if row is None:
            return self._empty_result(capability, "UNAVAILABLE")
        projection = row["projection"]
        if projection.get("origin") == "DEMO":
            return self._empty_result(capability, "UNKNOWN")
        activity = next(
            (item for item in projection.get("activities", []) if item.get("activity_id") == args["activity_id"]),
            None,
        )
        if activity is None:
            raise V4ToolError("ACTIVITY_NOT_FOUND", "activité absente du snapshot Android", capability=capability)
        if self.projections.has_activity_status_sequence_gap(device_ref):
            return self._empty_result(capability, "UNKNOWN")
        event = self.projections.latest_activity_status_event(device_ref, row["plan_id"], args["activity_id"])
        status = event["new_status"] if event else activity.get("status", "PLANNED")
        provenance = event["provenance"] if event else activity.get("status_provenance", "UNKNOWN")
        status_source = event["source"] if event else (activity.get("status_source") or "UNKNOWN")
        status_decided_at = event["decided_at"] if event else activity.get("status_decided_at")
        status_observed_at = event["observed_at"] if event else activity.get("status_observed_at") or row["observed_at"]
        status_received_at = event["received_at"] if event else row["received_at"]
        version = event["plan_version"] if event else row["plan_version"]
        consent_version = event["consent_version"] if event else int(row["consent_version"])
        freshness, can_read = self._freshness(capability, status_observed_at, consent_version, consent)
        if not can_read:
            return self._empty_result(capability, freshness)
        details = None
        if args.get("include_details") is True:
            details = {
                "kind": activity.get("kind"),
                "title": activity.get("title"),
                "scheduled_local": activity.get("scheduled_local"),
                "priority": activity.get("priority"),
            }
        data = {
            "activity_id": args["activity_id"],
            "plan_id": row["plan_id"],
            "version": version,
            "status": status,
            "status_provenance": provenance,
            "status_source": status_source,
            "status_decided_at": status_decided_at,
            "status_observed_at": status_observed_at,
            "last_synced_at": status_received_at,
            "sync_result": "ACKED",
            "freshness": freshness,
            "details": details,
        }
        return self._result(
            capability, data, freshness=freshness, source="ANDROID_SYNC", observed_at=status_observed_at,
            last_synced_at=status_received_at, plan_id=row["plan_id"], version=version,
        )

    def atlas_get_usage_summary(self, device_ref: str, arguments: dict[str, Any]) -> dict[str, Any]:
        capability = "usage_summary.read"
        args = self._arguments(
            arguments, {"window_start", "window_end", "include_app_labels", "app_limit"}, capability,
            required={"window_start", "window_end"},
        )
        start = str(args["window_start"])
        end = str(args["window_end"])
        start_dt, end_dt = self._parse_time(start), self._parse_time(end)
        if end_dt <= start_dt:
            raise V4ToolError("BAD_REQUEST", "window_end doit suivre window_start", capability=capability)
        if end_dt - start_dt > timedelta(days=14):
            raise V4ToolError("PERIOD_TOO_LARGE", "fenêtre d'usage supérieure à 14 jours", capability=capability)
        if "include_app_labels" in args and not isinstance(args["include_app_labels"], bool):
            raise V4ToolError("BAD_REQUEST", "include_app_labels doit être booléen", capability=capability)
        app_limit = self._page_size(args.get("app_limit", 10), 10, capability)
        consent = self._require_capability_and_consent(device_ref, capability)
        row = self.projections.latest_usage_summary(device_ref, start, end)
        if row is None:
            return self._empty_result(capability, "UNAVAILABLE")
        projection = row["projection"]
        freshness, can_read = self._freshness(
            capability, row["generated_at"], int(row["consent_version"]), consent
        )
        if not can_read:
            return self._empty_result(capability, freshness)
        app_labels = self._consent(device_ref, "USAGE_APP_LABELS")
        can_show_labels = args.get("include_app_labels", False) is True and app_labels.state == "GRANTED"
        apps = []
        for app in projection.get("apps", [])[:app_limit]:
            apps.append({
                "app_ref": app["app_ref"],
                "display_name": app.get("display_name") if can_show_labels else None,
                "foreground_ms": app["foreground_ms"],
                "launch_count": app["launch_count"],
                "last_used_at": app.get("last_used_at"),
                "reliability": app["reliability"],
            })
        data = {
            "window_start": row["window_start"],
            "window_end": row["window_end"],
            "timezone": row["timezone"],
            "total_foreground_ms": projection["total_foreground_ms"],
            "apps": apps,
            "coverage": projection["coverage"],
            "consent_state": "GRANTED",
            "consent_version": row["consent_version"],
            "generated_at": row["generated_at"],
            "freshness": freshness,
        }
        return self._result(
            capability, data, freshness=freshness, source="ANDROID_SYNC", observed_at=row["generated_at"],
            last_synced_at=row["received_at"],
        )

    def atlas_get_goal_progress(self, device_ref: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        capability = "goal_progress.read"
        args = self._arguments(arguments or {}, {"page_size", "cursor", "include_subgoals"}, capability)
        page_size = self._page_size(args.get("page_size", 20), 20, capability)
        if "include_subgoals" in args and not isinstance(args["include_subgoals"], bool):
            raise V4ToolError("BAD_REQUEST", "include_subgoals doit être booléen", capability=capability)
        consent = self._require_capability_and_consent(device_ref, capability)
        row = self.projections.latest_goal_progress_snapshot(device_ref)
        if row is None:
            return self._empty_result(capability, "UNAVAILABLE")
        freshness, can_read = self._freshness(
            capability, row["observed_at"], int(row["consent_version"]), consent
        )
        if not can_read:
            return self._empty_result(capability, freshness)
        projection = row["projection"]
        goals = []
        for goal in projection.get("goals", []):
            raw_progress = goal.get("progress")
            value = raw_progress.get("value") if isinstance(raw_progress, dict) else goal.get("progress_value")
            unit = raw_progress.get("unit") if isinstance(raw_progress, dict) else goal.get("unit")
            target_value = (
                raw_progress.get("target_value") if isinstance(raw_progress, dict)
                else goal.get("target_value")
            )
            progress = (
                {"value": value, "unit": unit, "target_value": target_value}
                if value is not None and isinstance(unit, str) and unit.strip()
                else None
            )
            clean = {
                "goal_id": goal.get("goal_id"),
                "title": goal.get("title"),
                "status": goal.get("status"),
                "progress": progress,
                "progress_source": goal.get("progress_source"),
                "observed_at": goal.get("observed_at"),
                "subgoals": goal.get("subgoals", []),
                "confirmed_commitments": goal.get("confirmed_commitments", []),
                "missing_data": goal.get("missing_data", []),
            }
            if args.get("include_subgoals", True) is False:
                clean["subgoals"] = []
            goals.append(clean)
        binding = f"{device_ref}|{capability}|{row['snapshot_hash']}|{bool(args.get('include_subgoals', True))}"
        offset = self._decode_cursor(args.get("cursor"), binding, capability) if args.get("cursor") else 0
        page = goals[offset : offset + page_size]
        end = offset + len(page)
        truncated = end < len(goals)
        data = {
            "goals_version": row["goals_version"],
            "goals": page,
            "next_cursor": self._encode_cursor(end, binding) if truncated else None,
            "truncated": truncated,
        }
        return self._result(
            capability, data, freshness=freshness, source="ANDROID_SYNC", observed_at=row["observed_at"],
            last_synced_at=row["received_at"],
        )

    def atlas_get_training_plan(self, device_ref: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        capability = "training_plan.read"
        args = self._arguments(
            arguments or {}, {"local_date", "include_results", "page_size", "cursor"}, capability
        )
        page_size = self._page_size(args.get("page_size", 20), 20, capability)
        if args.get("local_date") is not None:
            self._validate_date(args["local_date"], capability)
        if "include_results" in args and not isinstance(args["include_results"], bool):
            raise V4ToolError("BAD_REQUEST", "include_results doit être booléen", capability=capability)
        consent = self._require_capability_and_consent(device_ref, capability)
        row = self.projections.latest_training_snapshot(device_ref, provider="wger")
        if row is None:
            return self._empty_result(capability, "UNAVAILABLE")
        projection = row["projection"]
        freshness, can_read = self._freshness(
            capability, row["observed_at"], int(row["consent_version"]), consent
        )
        if not can_read:
            return self._empty_result(capability, freshness)
        sessions = [
            item for item in projection.get("sessions", [])
            if args.get("local_date") is None or item.get("scheduled_date") in (None, args.get("local_date"))
        ]
        binding = f"{device_ref}|{capability}|{row['snapshot_hash']}|{args.get('local_date', '')}"
        offset = self._decode_cursor(args.get("cursor"), binding, capability) if args.get("cursor") else 0
        page = sessions[offset : offset + page_size]
        end = offset + len(page)
        truncated = end < len(sessions)
        source = projection.get("source", "WGER_API")
        if source == "DEMO":
            source = "WGER_API"
            freshness = "UNKNOWN"
            can_read = False
        data = {
            "state": projection.get("state", "READY"),
            "provider": "wger",
            "source": source,
            "sync_state": projection.get("sync_state", freshness),
            "source_updated_at": projection.get("source_updated_at"),
            "observed_at": row["observed_at"],
            "last_synced_at": row["received_at"],
            "routines": projection.get("routines", [])[:20],
            "sessions": page,
            "results": projection.get("results", [])[:50] if args.get("include_results", False) is True else [],
            "freshness": freshness,
            "next_cursor": self._encode_cursor(end, binding) if truncated else None,
            "truncated": truncated,
        }
        if not can_read:
            return self._empty_result(capability, freshness)
        return self._result(
            capability, data, freshness=freshness, source=source, observed_at=row["observed_at"],
            last_synced_at=row["received_at"],
        )

    def atlas_propose_plan_revision(self, device_ref: str, arguments: dict[str, Any]) -> dict[str, Any]:
        capability = "plan_revision.propose"
        allowed = {"plan_id", "base_version", "changes", "justification", "idempotency_key"}
        args = self._arguments(arguments, allowed, capability, required=allowed)
        request_consent = self._require_capability_and_consent(device_ref, capability, require_granted=True)
        self._validate_proposal_args(args, capability)
        body = {
            "plan_id": args["plan_id"],
            "base_version": args["base_version"],
            "changes": args["changes"],
            "justification": args["justification"],
        }
        body_hash = content_hash(body)
        existing = self.projections.proposal_by_idempotency_key(args["idempotency_key"])
        if existing is not None:
            if existing.get("body_hash") != body_hash:
                raise V4ToolError("IDEMPOTENCY_CONFLICT", "idempotency_key réutilisée avec un autre corps", capability=capability)
            return self._proposal_result(existing, body_hash=body_hash)

        row = self.projections.latest_day_plan_snapshot(device_ref, plan_id=args["plan_id"])
        if row is None:
            raise V4ToolError("PLAN_NOT_FOUND", "aucun snapshot Android pour ce plan", capability=capability)
        consent = request_consent
        if consent.version != int(row["consent_version"]):
            raise V4ToolError("CONSENT_REQUIRED", "le snapshot ne correspond plus au consentement courant", capability=capability)
        freshness, can_read = self._freshness(capability, row["observed_at"], int(row["consent_version"]), consent)
        if not can_read:
            raise V4ToolError("STALE_DATA", "l'horodatage du snapshot est inconnu", capability=capability)
        if freshness != "FRESH":
            raise V4ToolError("STALE_DATA", "le snapshot Android est trop ancien ou son horloge est incertaine", capability=capability)
        projection = row["projection"]
        if projection.get("origin") == "DEMO":
            raise V4ToolError("DATA_UNAVAILABLE", "un plan de démonstration n'est pas une projection Android", capability=capability)
        observed_version = int(row["plan_version"])
        now = self._now()
        now_text = self._format_time(now)
        if args["base_version"] != observed_version:
            data = {
                "proposal_id": None,
                "status": "STALE_VERSION",
                "plan_id": args["plan_id"],
                "base_version": args["base_version"],
                "observed_version": observed_version,
                "diff_hash": content_hash([]),
                "diff": [],
                "conflicts": [],
                "confirmation_mode": "A0_USER_REVIEW",
                "auto_application": "NONE_AT_CORE",
                "created_at": now_text,
                "expires_at": None,
            }
            return self._result(
                capability, data, freshness=freshness, source="ANDROID_SYNC", observed_at=row["observed_at"],
                last_synced_at=row["received_at"], plan_id=args["plan_id"], version=observed_version,
            )
        diff, conflicts = self._build_diff(projection, args["changes"], capability)
        if conflicts:
            data = {
                "proposal_id": None,
                "status": "BLOCKED",
                "plan_id": args["plan_id"],
                "base_version": args["base_version"],
                "observed_version": observed_version,
                "diff_hash": content_hash(diff),
                "diff": diff,
                "conflicts": conflicts,
                "confirmation_mode": "A0_USER_REVIEW",
                "auto_application": "NONE_AT_CORE",
                "created_at": now_text,
                "expires_at": None,
            }
            return self._result(
                capability, data, freshness=freshness, source="ANDROID_SYNC", observed_at=row["observed_at"],
                last_synced_at=row["received_at"], plan_id=args["plan_id"], version=observed_version,
            )
        proposal_id = str(uuid.uuid4())
        expires_at = self._format_time(now + self.proposal_ttl)
        proposal_hash = content_hash(body)
        proposal = {
            "contract_version": 4,
            "schema_version": 4,
            "proposal_id": proposal_id,
            "capability": "plan_revision.propose",
            "idempotency_key": args["idempotency_key"],
            "proposal_hash": proposal_hash,
            "created_at": now_text,
            "expires_at": expires_at,
            "state": "PENDING_ANDROID",
            "plan_id": args["plan_id"],
            "base_version": args["base_version"],
            "origin": "AGENT",
            "justification": args["justification"],
            "changes": args["changes"],
            "diff": diff,
            "conflicts": [],
            "confirmation_mode": "A0_USER_REVIEW",
            "auto_application": "NONE_AT_CORE",
            "body_hash": body_hash,
        }
        try:
            saved, _inserted = self.projections.create_agent_proposal(
                proposal, idempotency_key=args["idempotency_key"], body_hash=body_hash
            )
        except IdempotencyConflict as exc:
            raise V4ToolError("IDEMPOTENCY_CONFLICT", str(exc), capability=capability) from exc
        data = {
            "proposal_id": saved["proposal_id"],
            "status": "QUEUED",
            "plan_id": saved["plan_id"],
            "base_version": saved["base_version"],
            "observed_version": observed_version,
            "diff_hash": content_hash(saved["diff"]),
            "diff": saved["diff"],
            "conflicts": saved["conflicts"],
            "confirmation_mode": "A0_USER_REVIEW",
            "auto_application": "NONE_AT_CORE",
            "created_at": saved["created_at"],
            "expires_at": saved["expires_at"],
        }
        result = self._result(
            capability, data, freshness=freshness, source="CORE", observed_at=row["observed_at"],
            last_synced_at=row["received_at"], plan_id=args["plan_id"], version=observed_version,
        )
        return result

    def _proposal_result(self, proposal: dict[str, Any], *, body_hash: str) -> dict[str, Any]:
        capability = "plan_revision.propose"
        data = {
            "proposal_id": proposal["proposal_id"],
            "status": "QUEUED",
            "plan_id": proposal["plan_id"],
            "base_version": proposal["base_version"],
            "observed_version": proposal["base_version"],
            "diff_hash": content_hash(proposal["diff"]),
            "diff": proposal["diff"],
            "conflicts": proposal.get("conflicts", []),
            "confirmation_mode": proposal.get("confirmation_mode", "A0_USER_REVIEW"),
            "auto_application": "NONE_AT_CORE",
            "created_at": proposal["created_at"],
            "expires_at": proposal["expires_at"],
        }
        return self._result(
            capability, data, freshness="FRESH", source="CORE", observed_at=proposal["created_at"],
            last_synced_at=proposal["created_at"], plan_id=proposal["plan_id"], version=proposal["base_version"],
        )

    def _validate_proposal_args(self, args: dict[str, Any], capability: str) -> None:
        try:
            uuid.UUID(str(args["plan_id"]))
            uuid.UUID(str(args["idempotency_key"]))
        except (ValueError, TypeError, AttributeError) as exc:
            raise V4ToolError("BAD_REQUEST", "plan_id et idempotency_key doivent être des UUID", capability=capability) from exc
        if not isinstance(args["base_version"], int) or isinstance(args["base_version"], bool) or args["base_version"] < 1:
            raise V4ToolError("BAD_REQUEST", "base_version doit être un entier positif", capability=capability)
        if not isinstance(args["justification"], str) or not 1 <= len(args["justification"]) <= 280:
            raise V4ToolError("BAD_REQUEST", "justification doit contenir 1 à 280 caractères", capability=capability)
        changes = args["changes"]
        if not isinstance(changes, list) or not 1 <= len(changes) <= 8:
            raise V4ToolError("BAD_REQUEST", "changes doit contenir de 1 à 8 opérations", capability=capability)
        for change in changes:
            if not isinstance(change, dict):
                raise V4ToolError("BAD_REQUEST", "chaque changement doit être un objet", capability=capability)

    def _build_diff(self, plan: dict[str, Any], changes: list[dict[str, Any]], capability: str) -> tuple[list[dict[str, Any]], list[str]]:
        activities = plan.get("activities", [])
        by_id = {item.get("activity_id"): item for item in activities}
        used: set[str] = set()
        diff: list[dict[str, Any]] = []
        conflicts: list[str] = []
        projected_times = {
            item.get("activity_id"): item.get("scheduled_local", {})
            for item in activities
        }
        schedule_changed: set[str] = set()
        for change in changes:
            op = change.get("op")
            if op == "reschedule":
                if set(change) - {"op", "activity_id", "target_start_minute", "duration_minutes"}:
                    raise V4ToolError("BAD_REQUEST", "champ inconnu dans reschedule", capability=capability)
                activity_id = change.get("activity_id")
                item = by_id.get(activity_id)
                if item is None:
                    raise V4ToolError("VALIDATION_FAILED", "activité absente du plan de base", capability=capability)
                if item.get("status") in {"DONE", "MISSED", "CANCELLED", "POSTPONED"}:
                    raise V4ToolError("VALIDATION_FAILED", "activité terminale non modifiable", capability=capability)
                if activity_id in used:
                    raise V4ToolError("VALIDATION_FAILED", "une activité ne peut être modifiée deux fois", capability=capability)
                used.add(activity_id)
                target = change.get("target_start_minute")
                duration = change.get("duration_minutes", item["scheduled_local"]["duration_minutes"])
                self._minute_values(target, duration, capability)
                before = {
                    "start_minute": item["scheduled_local"]["start_minute"],
                    "duration_minutes": item["scheduled_local"]["duration_minutes"],
                }
                after = {"start_minute": target, "duration_minutes": duration}
                if after != before:
                    schedule_changed.add(activity_id)
                projected_times[activity_id] = {**item["scheduled_local"], **after}
                diff.append({"op": op, "activity_id": activity_id, "client_ref": None, "before": before, "after": after})
            elif op == "edit_activity":
                if set(change) - {"op", "activity_id", "title", "priority", "estimate_minutes"}:
                    raise V4ToolError("BAD_REQUEST", "champ inconnu dans edit_activity", capability=capability)
                activity_id = change.get("activity_id")
                item = by_id.get(activity_id)
                if item is None:
                    raise V4ToolError("VALIDATION_FAILED", "activité absente du plan de base", capability=capability)
                if item.get("status") in {"DONE", "MISSED", "CANCELLED", "POSTPONED"}:
                    raise V4ToolError("VALIDATION_FAILED", "activité terminale non modifiable", capability=capability)
                if activity_id in used:
                    raise V4ToolError("VALIDATION_FAILED", "une activité ne peut être modifiée deux fois", capability=capability)
                if not any(key in change for key in ("title", "priority", "estimate_minutes")):
                    raise V4ToolError("BAD_REQUEST", "edit_activity ne contient aucun champ modifiable", capability=capability)
                used.add(activity_id)
                before: dict[str, Any] = {}
                after: dict[str, Any] = {}
                if "title" in change:
                    if not isinstance(change["title"], str) or not 1 <= len(change["title"]) <= 80:
                        raise V4ToolError("BAD_REQUEST", "title invalide", capability=capability)
                    before["title"], after["title"] = item["title"], change["title"]
                if "priority" in change:
                    if change["priority"] not in {"REQUIRED", "IMPORTANT", "OPTIONAL"}:
                        raise V4ToolError("BAD_REQUEST", "priority invalide", capability=capability)
                    before["priority"], after["priority"] = item["priority"], change["priority"]
                if "estimate_minutes" in change:
                    estimate = change["estimate_minutes"]
                    if not isinstance(estimate, int) or isinstance(estimate, bool) or not 1 <= estimate <= 480:
                        raise V4ToolError("BAD_REQUEST", "estimate_minutes invalide", capability=capability)
                    before["duration_minutes"] = item["scheduled_local"]["duration_minutes"]
                    after["duration_minutes"] = estimate
                    projected_times[activity_id] = {**item["scheduled_local"], "duration_minutes": estimate}
                    if estimate != item["scheduled_local"]["duration_minutes"]:
                        schedule_changed.add(activity_id)
                diff.append({"op": op, "activity_id": activity_id, "client_ref": None, "before": before, "after": after})
            elif op == "create_activity":
                allowed = {"op", "client_ref", "kind", "title", "start_minute", "duration_minutes", "priority", "goal_id"}
                if set(change) - allowed or not allowed - {"goal_id"} <= set(change):
                    raise V4ToolError("BAD_REQUEST", "create_activity a des champs manquants ou inconnus", capability=capability)
                ref = change["client_ref"]
                if not isinstance(ref, str) or not 1 <= len(ref) <= 40 or any(not (c.isalnum() or c in "_-") for c in ref):
                    raise V4ToolError("BAD_REQUEST", "client_ref invalide", capability=capability)
                if ref in used:
                    raise V4ToolError("VALIDATION_FAILED", "client_ref dupliqué", capability=capability)
                if change["kind"] not in {"FOCUS", "ADMIN", "MEAL", "REST", "BUFFER", "OTHER"}:
                    raise V4ToolError("BAD_REQUEST", "kind invalide", capability=capability)
                if not isinstance(change["title"], str) or not 1 <= len(change["title"]) <= 80:
                    raise V4ToolError("BAD_REQUEST", "title invalide", capability=capability)
                if change["priority"] not in {"REQUIRED", "IMPORTANT", "OPTIONAL"}:
                    raise V4ToolError("BAD_REQUEST", "priority invalide", capability=capability)
                if change.get("goal_id") is not None:
                    try:
                        uuid.UUID(str(change["goal_id"]))
                    except (ValueError, TypeError, AttributeError) as exc:
                        raise V4ToolError("BAD_REQUEST", "goal_id invalide", capability=capability) from exc
                self._minute_values(change["start_minute"], change["duration_minutes"], capability)
                used.add(ref)
                after = {
                    "start_minute": change["start_minute"],
                    "duration_minutes": change["duration_minutes"],
                    "title": change["title"],
                    "priority": change["priority"],
                }
                diff.append({"op": op, "activity_id": None, "client_ref": ref, "before": None, "after": after})
                projected_times[f"new:{ref}"] = {"local_date": plan["local_date"], "timezone": plan["timezone"], **after}
                schedule_changed.add(f"new:{ref}")
            else:
                raise V4ToolError("BAD_REQUEST", "opération de révision inconnue", capability=capability)

        entries = list(projected_times.items())
        for index, (left_id, left) in enumerate(entries):
            for right_id, right in entries[index + 1 :]:
                if left_id not in schedule_changed and right_id not in schedule_changed:
                    continue
                if left_id == right_id or left.get("local_date") != right.get("local_date"):
                    continue
                l0, l1 = left.get("start_minute", 0), left.get("start_minute", 0) + left.get("duration_minutes", 0)
                r0, r1 = right.get("start_minute", 0), right.get("start_minute", 0) + right.get("duration_minutes", 0)
                if l0 < r1 and r0 < l1:
                    conflicts.append(f"OVERLAP:{left_id}:{right_id}")
        return diff, conflicts[:16]

    @staticmethod
    def _minute_values(start: Any, duration: Any, capability: str) -> None:
        if not isinstance(start, int) or isinstance(start, bool) or not 0 <= start <= 1439:
            raise V4ToolError("BAD_REQUEST", "start_minute invalide", capability=capability)
        if not isinstance(duration, int) or isinstance(duration, bool) or not 1 <= duration <= 1440:
            raise V4ToolError("BAD_REQUEST", "duration_minutes invalide", capability=capability)

    def _require_capability_and_consent(
        self, device_ref: str, capability: str, *, require_granted: bool = False
    ) -> Consent:
        if not self.capabilities.get(capability, False):
            raise V4ToolError("CAPABILITY_UNAVAILABLE", f"capability désactivée: {capability}", capability=capability)
        consent = self._consent(device_ref, CONSENT_BY_CAPABILITY[capability])
        if require_granted and consent.state != "GRANTED":
            raise V4ToolError("CONSENT_REQUIRED", "consentement requis ou inconnu", capability=capability)
        return consent

    def _consent(self, device_ref: str, category: str) -> Consent:
        raw = self.consent_lookup(device_ref, category)
        if isinstance(raw, Consent):
            return raw
        if isinstance(raw, dict):
            state = str(raw.get("state", "UNKNOWN")).upper()
            version = raw.get("version", raw.get("consent_version"))
            return Consent(state, int(version) if version is not None else None)
        if isinstance(raw, tuple) and len(raw) == 2:
            state, version = raw
            return Consent(str(state).upper(), int(version) if version is not None else None)
        return Consent("UNKNOWN", None)

    def _freshness(self, capability: str, observed_at: str | None, row_version: int, consent: Consent) -> tuple[str, bool]:
        if consent.state != "GRANTED" or consent.version is None:
            return "UNKNOWN", False
        if consent.version != row_version:
            return "UNKNOWN", False
        try:
            observed = self._parse_time(observed_at)
            now = self._now()
        except (TypeError, ValueError):
            return "UNKNOWN", False
        if observed > now + timedelta(minutes=2):
            return "UNKNOWN", False
        if now - observed > FRESHNESS_AGE[capability]:
            return "STALE", True
        return "FRESH", True

    def _result(
        self,
        capability: str,
        data: Any,
        *,
        freshness: str,
        source: str,
        observed_at: str | None = None,
        last_synced_at: str | None = None,
        plan_id: str | None = None,
        version: int | None = None,
    ) -> dict[str, Any]:
        result = {
            "contract_version": 4,
            "schema_version": 4,
            "capability": capability,
            "request_id": self.request_id_factory(),
            "source": source,
            "observed_at": observed_at,
            "last_synced_at": last_synced_at,
            "is_stale": freshness == "STALE",
            "freshness": freshness,
            "data": data,
        }
        if plan_id is not None:
            result["plan_id"] = plan_id
        if version is not None:
            result["version"] = version
        return result

    def _empty_result(self, capability: str, freshness: str) -> dict[str, Any]:
        source = "NONE"
        return self._result(capability, None, freshness=freshness, source=source)

    @staticmethod
    def _clean_activity(activity: dict[str, Any]) -> dict[str, Any]:
        scheduled = activity.get("scheduled_local", {})
        return {
            "activity_id": activity["activity_id"],
            "kind": activity["kind"],
            "title": activity["title"],
            "status": activity["status"],
            "scheduled_local": {
                "local_date": scheduled["local_date"],
                "start_minute": scheduled["start_minute"],
                "duration_minutes": scheduled["duration_minutes"],
                "timezone": scheduled["timezone"],
            },
            "priority": activity["priority"],
            "goal_id": activity.get("goal_id"),
            "continuity_id": activity.get("continuity_id"),
            "supersedes_activity_id": activity.get("supersedes_activity_id"),
            "status_provenance": activity.get("status_provenance", "UNKNOWN"),
            "status_decided_at": activity.get("status_decided_at"),
            "status_observed_at": activity.get("status_observed_at"),
            "status_sync_state": activity.get("status_sync_state", "ACKED"),
        }

    @staticmethod
    def _arguments(
        arguments: dict[str, Any], allowed: set[str], capability: str, *, required: set[str] | None = None
    ) -> dict[str, Any]:
        if not isinstance(arguments, dict):
            raise V4ToolError("BAD_REQUEST", "arguments doit être un objet JSON", capability=capability)
        unknown = set(arguments) - allowed
        if unknown:
            raise V4ToolError("BAD_REQUEST", f"champ inconnu: {sorted(unknown)[0]}", capability=capability)
        missing = (required or set()) - set(arguments)
        if missing:
            raise V4ToolError("BAD_REQUEST", f"champ requis absent: {sorted(missing)[0]}", capability=capability)
        return dict(arguments)

    @staticmethod
    def _page_size(value: Any, maximum: int, capability: str) -> int:
        if not isinstance(value, int) or isinstance(value, bool) or not 1 <= value <= maximum:
            raise V4ToolError("BAD_REQUEST", f"page size doit être compris entre 1 et {maximum}", capability=capability)
        return value

    @staticmethod
    def _validate_date(value: Any, capability: str) -> None:
        if not isinstance(value, str):
            raise V4ToolError("BAD_REQUEST", "date invalide", capability=capability)
        try:
            parsed = date.fromisoformat(value)
        except ValueError as exc:
            raise V4ToolError("BAD_REQUEST", "date invalide", capability=capability) from exc
        if parsed.isoformat() != value:
            raise V4ToolError("BAD_REQUEST", "date doit utiliser le format YYYY-MM-DD", capability=capability)

    def _encode_cursor(self, offset: int, binding: str) -> str:
        body = canonical_json({"offset": offset, "binding": hashlib.sha256(binding.encode()).hexdigest()}).encode()
        signature = hmac.new(self.cursor_secret, body, hashlib.sha256).digest()
        return base64.urlsafe_b64encode(body + signature).decode().rstrip("=")

    def _decode_cursor(self, cursor: Any, binding: str, capability: str) -> int:
        try:
            raw = base64.urlsafe_b64decode(str(cursor) + "=" * (-len(str(cursor)) % 4))
            body, signature = raw[:-32], raw[-32:]
            if not hmac.compare_digest(signature, hmac.new(self.cursor_secret, body, hashlib.sha256).digest()):
                raise ValueError("bad signature")
            decoded = json.loads(body)
            if decoded.get("binding") != hashlib.sha256(binding.encode()).hexdigest():
                raise ValueError("binding mismatch")
            offset = decoded["offset"]
            if not isinstance(offset, int) or offset < 1:
                raise ValueError("offset invalid")
            return offset
        except (ValueError, TypeError, KeyError, json.JSONDecodeError) as exc:
            raise V4ToolError("CURSOR_INVALID", "curseur invalide pour cet instantané", capability=capability) from exc

    def _now(self) -> datetime:
        value = self.clock()
        if isinstance(value, str):
            return self._parse_time(value)
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc)

    @staticmethod
    def _parse_time(value: str | None) -> datetime:
        if not isinstance(value, str):
            raise ValueError("timestamp missing")
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            raise ValueError("timestamp has no timezone")
        return parsed.astimezone(timezone.utc)

    @staticmethod
    def _format_time(value: datetime) -> str:
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
