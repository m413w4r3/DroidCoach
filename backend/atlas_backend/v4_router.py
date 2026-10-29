"""Authenticated HTTP routes for the V4 Core tools and Android sync API."""

from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import ipaddress
import json
import logging
import os
import re
import sqlite3
import uuid
from collections.abc import Callable
from functools import wraps
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from jsonschema import Draft202012Validator, FormatChecker

from .openclaw_adapter import (
    CHAT_V4_FALLBACK_REPLY,
    ChatOrchestrationFailure,
    ChatToolFailure,
)
from .store import SQLiteStore
from .v4_projection_store import (
    AckConflict,
    AckIdConflict,
    AckValidationError,
    EventIdConflict,
    IdempotencyConflict,
    ProjectionConflict,
    SequenceGap,
    StaleProjectionVersion,
    UnsupportedPayloadVersion,
    V4ProjectionError,
    V4ProjectionStore,
    content_hash,
)
from .v4_tool_service import V4ToolError, V4ToolService

_CONTRACT_DIR = Path(__file__).resolve().parents[2] / "contracts" / "v4"
_LOGGER = logging.getLogger(__name__)
_TOOL_CAPABILITIES = {
    "atlas_get_day_plan": "day_plan.read",
    "atlas_get_activity_status": "activity_status.read",
    "atlas_propose_plan_revision": "plan_revision.propose",
    "atlas_get_usage_summary": "usage_summary.read",
    "atlas_get_goal_progress": "goal_progress.read",
    "atlas_get_training_plan": "training_plan.read",
}
_TOOL_CONSENT_CATEGORY = {
    "day_plan.read": "DAY_PLAN",
    "activity_status.read": "ACTIVITY_STATUS",
    "plan_revision.propose": "DAY_PLAN",
    "usage_summary.read": "USAGE_SUMMARY",
    "goal_progress.read": "GOAL_PROGRESS",
    "training_plan.read": "TRAINING_PLAN",
}
_TOOL_SCHEMA_KEYS = {
    "atlas_get_day_plan": "atlas_get_day_plan_arguments",
    "atlas_get_activity_status": "atlas_get_activity_status_arguments",
    "atlas_propose_plan_revision": "atlas_propose_plan_revision_arguments",
    "atlas_get_usage_summary": "atlas_get_usage_summary_arguments",
    "atlas_get_goal_progress": "atlas_get_goal_progress_arguments",
    "atlas_get_training_plan": "atlas_get_training_plan_arguments",
}
_SYNC_ROUTES = {
    "/v4/sync/day-plan-snapshots": ("day_plan.snapshot.write", "day_plan_snapshot"),
    "/v4/sync/activity-status-events": ("activity_status.write", "activity_status_event"),
    "/v4/sync/usage-summaries": ("usage_summary.write", "usage_summary"),
    "/v4/sync/goal-progress-snapshots": ("goal_progress.write", "goal_progress_snapshot"),
    "/v4/sync/training-snapshots": ("training_plan.write", "training_snapshot"),
}
_READ_CAPABILITIES = tuple(_TOOL_CAPABILITIES.values())
_CAPABILITY_ENV_ALLOWLIST = frozenset((*_READ_CAPABILITIES, "coach.chat"))
_CAPABILITY_TO_CONSENT_TABLE = {
    "DAY_PLAN": "day_plan_snapshots_v4",
    "ACTIVITY_STATUS": "activity_status_events_v4",
    "USAGE_SUMMARY": "usage_summaries_v4",
    "GOAL_PROGRESS": "goal_progress_snapshots_v4",
    "TRAINING_PLAN": "training_snapshots_v4",
}


class _RouteFailure(Exception):
    def __init__(
        self,
        code: str,
        message: str,
        *,
        status: int,
        capability: str | None = None,
        request_id: str | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status
        self.capability = capability
        self.request_id = request_id


def _schema(name: str) -> dict[str, Any]:
    return json.loads((_CONTRACT_DIR / name).read_text(encoding="utf-8"))


_SYNC_SCHEMA = _schema("sync.schema.json")
_SYNC_RECEIPTS_SCHEMA = _schema("sync-receipts.schema.json")
_CHAT_REQUEST_SCHEMA = _schema("chat-request.schema.json")
_CHAT_RESPONSE_SCHEMA = _schema("chat-response.schema.json")
_TOOL_REQUEST_SCHEMA = _schema("tool-requests.schema.json")
_TOOL_RESULT_SCHEMA = _schema("tool-results.schema.json")
_CAPABILITY_SCHEMA = _schema("capability-negotiation.schema.json")
_PROPOSAL_ACK_SCHEMA = _schema("proposal-inbox-ack.schema.json")
_ERROR_SCHEMA = _schema("error.schema.json")
_FORMAT_CHECKER = FormatChecker()
for _contract in (
    _SYNC_SCHEMA,
    _SYNC_RECEIPTS_SCHEMA,
    _CHAT_REQUEST_SCHEMA,
    _CHAT_RESPONSE_SCHEMA,
    _TOOL_REQUEST_SCHEMA,
    _TOOL_RESULT_SCHEMA,
    _CAPABILITY_SCHEMA,
    _PROPOSAL_ACK_SCHEMA,
    _ERROR_SCHEMA,
):
    Draft202012Validator.check_schema(_contract)


def _validate(schema: dict[str, Any], value: Any) -> bool:
    return not any(Draft202012Validator(schema, format_checker=_FORMAT_CHECKER).iter_errors(value))


def _strict_json_body(raw: bytes) -> Any:
    def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate JSON key")
            result[key] = value
        return result

    def reject_constant(_value: str) -> None:
        raise ValueError("non-JSON numeric constant")

    return json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=reject_duplicate_keys,
                      parse_constant=reject_constant)


def _utc_now(store: SQLiteStore) -> str:
    return store.now_iso()


def _request_id(request: Request, fallback: str | None = None) -> str:
    candidate = request.headers.get("x-atlas-request-id") or fallback
    try:
        return str(uuid.UUID(candidate)) if candidate else str(uuid.uuid4())
    except (ValueError, AttributeError, TypeError):
        return str(uuid.uuid4())


def _is_sqlite_busy(exc: sqlite3.OperationalError) -> bool:
    error_code = getattr(exc, "sqlite_errorcode", None)
    if isinstance(error_code, int) and (error_code & 0xFF) in {sqlite3.SQLITE_BUSY, sqlite3.SQLITE_LOCKED}:
        return True
    message = str(exc).lower()
    return any(
        phrase in message
        for phrase in ("database is locked", "database is busy", "database table is locked")
    )


def _error_payload(
    request: Request,
    code: str,
    message: str,
    capability: str | None = None,
    *,
    fallback_request_id: str | None = None,
) -> dict[str, Any]:
    payload = {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": _request_id(request, fallback_request_id),
        "error": {
            "code": code,
            "message": message[:240] or code,
            "retryable": code in {"TIMEOUT", "UPSTREAM_UNAVAILABLE", "RATE_LIMITED"},
            "capability": capability,
            "retry_after_seconds": None,
        },
    }
    if not _validate(_ERROR_SCHEMA, payload):
        payload["error"]["code"] = "INTERNAL"
        payload["error"]["message"] = "Erreur interne V4."
        payload["error"]["capability"] = None
    return payload


def _error_response(
    request: Request,
    code: str,
    message: str,
    capability: str | None = None,
    *,
    status: int | None = None,
    fallback_request_id: str | None = None,
) -> JSONResponse:
    status_by_code = {
        "BAD_REQUEST": 400,
        "UNKNOWN_FIELD": 400,
        "UNSUPPORTED_VERSION": 400,
        "AUTH_REQUIRED": 401,
        "CONSENT_REQUIRED": 403,
        "PERMISSION_REQUIRED": 403,
        "CAPABILITY_UNAVAILABLE": 403,
        "NOT_FOUND": 404,
        "PLAN_NOT_FOUND": 404,
        "ACTIVITY_NOT_FOUND": 404,
        "PLAN_MISMATCH": 409,
        "DATA_UNAVAILABLE": 503,
        "DATA_UNKNOWN": 503,
        "STALE_DATA": 409,
        "STALE_VERSION": 409,
        "CONFLICT": 409,
        "VALIDATION_FAILED": 422,
        "IDEMPOTENCY_CONFLICT": 409,
        "EVENT_ID_CONFLICT": 409,
        "ACK_ID_CONFLICT": 409,
        "ACK_CONFLICT": 409,
        "PROPOSAL_EXPIRED": 409,
        "REPLAY_PENDING": 409,
        "RESULT_UNCERTAIN": 409,
        "QUOTA_EXCEEDED": 429,
        "RATE_LIMITED": 429,
        "PERIOD_TOO_LARGE": 422,
        "CURSOR_INVALID": 400,
        "CONFIG_REQUIRED": 503,
        "AUTH_UPSTREAM_REQUIRED": 503,
        "UPSTREAM_UNAVAILABLE": 503,
        "TIMEOUT": 504,
        "PAYLOAD_TOO_LARGE": 413,
        "INTERNAL": 500,
    }
    payload = _error_payload(
        request, code, message, capability, fallback_request_id=fallback_request_id
    )
    return JSONResponse(status_code=status or status_by_code.get(code, 400), content=payload)


def _chat_error_response(request_id: str, code: str, capability: str = "coach.chat") -> JSONResponse:
    messages = {
        "BAD_REQUEST": "La requête ne respecte pas le schéma V4.",
        "AUTH_REQUIRED": "Authentification Android requise.",
        "CONSENT_REQUIRED": "Le consentement Android n’est pas GRANTED.",
        "STALE_VERSION": "La version de consentement est obsolète.",
        "CAPABILITY_UNAVAILABLE": "Capability désactivée.",
        "QUOTA_EXCEEDED": "La limite d’outils ou le quota du coach est atteint.",
        "RATE_LIMITED": "Le quota du fournisseur est temporairement limité.",
        "TIMEOUT": "Le délai total du coach est dépassé.",
        "PAYLOAD_TOO_LARGE": "La requête ou la réponse du fournisseur est trop volumineuse.",
        "AUTH_UPSTREAM_REQUIRED": "Le fournisseur du coach n’est pas authentifié.",
        "CONFIG_REQUIRED": "Le fournisseur du coach n’est pas configuré.",
        "UPSTREAM_UNAVAILABLE": "Le fournisseur du coach est indisponible ou a renvoyé une réponse invalide.",
        "DATA_UNAVAILABLE": "Aucune projection V4 disponible.",
        "DATA_UNKNOWN": "La projection V4 est inconnue.",
        "ACTIVITY_NOT_FOUND": "L’activité est absente du snapshot Android.",
        "PLAN_NOT_FOUND": "Aucun snapshot Android pour ce plan.",
        "IDEMPOTENCY_CONFLICT": "request_id réutilisé avec un corps différent.",
        "REPLAY_PENDING": "Une requête identique est déjà en cours.",
        "INTERNAL": "Erreur interne V4.",
    }
    status_by_code = {
        "BAD_REQUEST": 400,
        "AUTH_REQUIRED": 401,
        "CONSENT_REQUIRED": 403,
        "STALE_VERSION": 409,
        "CAPABILITY_UNAVAILABLE": 403,
        "QUOTA_EXCEEDED": 429,
        "RATE_LIMITED": 429,
        "TIMEOUT": 504,
        "PAYLOAD_TOO_LARGE": 413,
        "AUTH_UPSTREAM_REQUIRED": 503,
        "CONFIG_REQUIRED": 503,
        "UPSTREAM_UNAVAILABLE": 503,
        "DATA_UNAVAILABLE": 503,
        "DATA_UNKNOWN": 503,
        "ACTIVITY_NOT_FOUND": 404,
        "PLAN_NOT_FOUND": 404,
        "IDEMPOTENCY_CONFLICT": 409,
        "REPLAY_PENDING": 409,
        "INTERNAL": 500,
    }
    retryable = code in {"RATE_LIMITED", "TIMEOUT", "UPSTREAM_UNAVAILABLE"}
    payload = {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": request_id,
        "error": {
            "code": code if code in _ERROR_SCHEMA["properties"]["error"]["properties"]["code"]["enum"] else "INTERNAL",
            "message": messages.get(code, "Opération V4 refusée."),
            "retryable": retryable,
            "capability": capability,
            "retry_after_seconds": None,
        },
    }
    if not _validate(_ERROR_SCHEMA, payload):
        payload["error"].update(code="INTERNAL", message="Erreur interne V4.", capability=None, retryable=False)
    return JSONResponse(status_code=status_by_code.get(code, 400), content=payload)


def _schema_failure(request: Request, capability: str | None, *, fallback: str | None = None) -> JSONResponse:
    return _error_response(
        request,
        "BAD_REQUEST",
        "La requête ne respecte pas le schéma V4.",
        capability,
        fallback_request_id=fallback,
    )


def _configured_capabilities() -> dict[str, bool]:
    raw = os.getenv("ATLAS_V4_CAPABILITIES_JSON")
    result = {name: False for name in _CAPABILITY_ENV_ALLOWLIST}
    if not raw:
        return result
    try:
        supplied = json.loads(raw)
    except json.JSONDecodeError:
        raise ValueError("ATLAS_V4_CAPABILITIES_JSON doit être un objet JSON") from None
    if not isinstance(supplied, dict) or set(supplied) - _CAPABILITY_ENV_ALLOWLIST:
        raise ValueError("ATLAS_V4_CAPABILITIES_JSON contient des capabilities inconnues")
    if any(not isinstance(value, bool) for value in supplied.values()):
        raise ValueError("les capabilities V4 doivent être booléennes")
    result.update(supplied)
    return result


def _device_ref(android_token: str) -> str:
    return "android-" + hashlib.sha256(android_token.encode("utf-8")).hexdigest()[:32]


def _loopback_client(request: Request) -> bool:
    if request.client is None:
        return False
    try:
        return ipaddress.ip_address(request.client.host).is_loopback
    except ValueError:
        return False


def _bearer(request: Request) -> str | None:
    authorization = request.headers.get("authorization", "")
    scheme, separator, value = authorization.partition(" ")
    if not separator or scheme.lower() != "bearer" or not value or value.strip() != value:
        return None
    return value


def _query_arguments(request: Request, tool_name: str, path_values: dict[str, str] | None = None) -> dict[str, Any]:
    allowed = set(_TOOL_REQUEST_SCHEMA["$defs"][_TOOL_SCHEMA_KEYS[tool_name]]["properties"])
    values: dict[str, Any] = dict(path_values or {})
    for key, value in request.query_params.multi_items():
        if key not in allowed or key in values:
            raise _RouteFailure("UNKNOWN_FIELD", "Paramètre de requête V4 inconnu ou répété.",
                                status=400, capability=_TOOL_CAPABILITIES[tool_name])
        values[key] = value
    for key in ("page_size", "app_limit"):
        if key in values:
            try:
                if not re.fullmatch(r"[0-9]+", str(values[key])):
                    raise ValueError
                values[key] = int(values[key])
            except (TypeError, ValueError):
                raise _RouteFailure("BAD_REQUEST", f"{key} invalide.", status=400,
                                    capability=_TOOL_CAPABILITIES[tool_name]) from None
    for key in ("include_details", "include_app_labels", "include_subgoals", "include_results"):
        if key in values:
            text = str(values[key]).lower()
            if text not in {"true", "false"}:
                raise _RouteFailure("BAD_REQUEST", f"{key} doit être true ou false.", status=400,
                                    capability=_TOOL_CAPABILITIES[tool_name])
            values[key] = text == "true"
    return values


def _status_from_projection_error(exc: V4ProjectionError) -> tuple[str, int, str]:
    if isinstance(exc, UnsupportedPayloadVersion):
        return "UNSUPPORTED_VERSION", 400, "Version de schéma V4 non prise en charge."
    if isinstance(exc, StaleProjectionVersion):
        return "STALE_VERSION", 409, "La version de projection est obsolète."
    if isinstance(exc, EventIdConflict):
        return "EVENT_ID_CONFLICT", 409, "event_id réutilisé avec un contenu différent."
    if isinstance(exc, AckIdConflict):
        return "ACK_ID_CONFLICT", 409, "ack_id réutilisé avec un contenu différent."
    if isinstance(exc, AckConflict):
        return "ACK_CONFLICT", 409, "Transition ou séquence ACK en conflit."
    if isinstance(exc, AckValidationError):
        return "VALIDATION_FAILED", 422, "L’ACK ne respecte pas les règles V4."
    if isinstance(exc, IdempotencyConflict):
        return "IDEMPOTENCY_CONFLICT", 409, "Clé d’idempotence réutilisée avec un autre contenu."
    if isinstance(exc, SequenceGap):
        return "STALE_DATA", 409, "La séquence des événements de statut comporte une lacune."
    if isinstance(exc, ProjectionConflict):
        return "CONFLICT", 409, "La projection entre en conflit avec une version déjà reçue."
    return "VALIDATION_FAILED", 422, "La projection ne respecte pas les règles V4."


def create_v4_router(
    store: SQLiteStore,
    *,
    android_token: str | None = None,
    capabilities: dict[str, bool] | None = None,
    agent: Any | None = None,
) -> APIRouter:
    """Build the fixed V4 route set around the existing SQLite and projection services."""
    projections = V4ProjectionStore(store)
    active_capabilities = dict(capabilities if capabilities is not None else _configured_capabilities())

    class _RollbackRouteResponse(Exception):
        def __init__(self, response: JSONResponse) -> None:
            self.response = response

    def serialized_consent_route(endpoint: Callable[..., Any]) -> Callable[..., Any]:
        @wraps(endpoint)
        async def wrapped(*args: Any, **kwargs: Any) -> JSONResponse:
            request = kwargs.get("request")
            if not isinstance(request, Request):
                request = next((argument for argument in args if isinstance(argument, Request)), None)
            try:
                with store.transaction(immediate=True):
                    response = await endpoint(*args, **kwargs)
                    if response.status_code >= 400:
                        raise _RollbackRouteResponse(response)
                    return response
            except _RollbackRouteResponse as rollback:
                return rollback.response
            except sqlite3.OperationalError as exc:
                if request is None:
                    raise
                if _is_sqlite_busy(exc):
                    return _error_response(
                        request,
                        "UPSTREAM_UNAVAILABLE",
                        "Le stockage est temporairement indisponible.",
                        status=503,
                    )
                return _error_response(request, "INTERNAL", "Erreur interne V4.", status=500)
            except Exception:
                if request is None:
                    raise
                return _error_response(request, "INTERNAL", "Erreur interne V4.", status=500)

        return wrapped

    def current_android_token() -> str | None:
        return android_token if android_token is not None else os.getenv("ATLAS_TOKEN")

    def tool_consent_categories(capability: str) -> tuple[str, ...]:
        category = _TOOL_CONSENT_CATEGORY[capability]
        if capability == "activity_status.read":
            return category, "DAY_PLAN"
        return (category,)

    def consent_lookup(device_ref: str, category: str) -> dict[str, Any] | None:
        if category not in _CAPABILITY_TO_CONSENT_TABLE:
            return None
        state = projections.consent_state(device_ref, category)
        if state is None:
            return None
        return {"state": state["state"], "version": int(state["consent_version"])}

    def latest_projection_consent_version(device_ref: str, category: str) -> int | None:
        table = _CAPABILITY_TO_CONSENT_TABLE.get(category)
        if table is None:
            return None
        order_by = {
            "day_plan_snapshots_v4": "received_at DESC, plan_version DESC",
            "activity_status_events_v4": "sequence DESC",
            "usage_summaries_v4": "received_at DESC",
            "goal_progress_snapshots_v4": "received_at DESC, goals_version DESC",
            "training_snapshots_v4": "received_at DESC, observed_at DESC",
        }[table]
        with store.connect() as connection:
            row = connection.execute(
                f"SELECT consent_version FROM {table} WHERE device_ref=? ORDER BY {order_by} LIMIT 1",
                (device_ref,),
            ).fetchone()
        return int(row["consent_version"]) if row is not None else None

    service = V4ToolService(
        projections,
        clock=store.clock,
        capabilities=active_capabilities,
        consent_lookup=consent_lookup,
    )
    chat_tool_dispatch = {
        "atlas_get_day_plan": service.atlas_get_day_plan,
        "atlas_get_activity_status": service.atlas_get_activity_status,
        "atlas_propose_plan_revision": service.atlas_propose_plan_revision,
        "atlas_get_usage_summary": service.atlas_get_usage_summary,
        "atlas_get_goal_progress": service.atlas_get_goal_progress,
        "atlas_get_training_plan": service.atlas_get_training_plan,
    }
    router = APIRouter()
    chat_lock = asyncio.Lock()

    def require_tool(request: Request, capability: str) -> tuple[str, str] | JSONResponse:
        request_id = _request_id(request)
        tool_secret = os.getenv("ATLAS_TOOL_TOKEN")
        android_secret = current_android_token()
        supplied = _bearer(request)
        if (
            not _loopback_client(request)
            or not tool_secret
            or not android_secret
            or not supplied
            or hmac.compare_digest(tool_secret, android_secret or "")
            or not hmac.compare_digest(supplied, tool_secret)
        ):
            return _error_response(request, "AUTH_REQUIRED", "Authentification outil requise.", capability,
                                   status=401, fallback_request_id=request_id)
        if request.headers.get("x-atlas-contract-version") != "4" or request.headers.get("x-atlas-schema-version") != "4":
            return _error_response(request, "UNSUPPORTED_VERSION", "Version de contrat ou schéma V4 requise.",
                                   capability, status=400, fallback_request_id=request_id)
        if request.headers.get("x-atlas-capability") != capability:
            return _error_response(request, "BAD_REQUEST", "La capability de la route ne correspond pas.",
                                   capability, status=400, fallback_request_id=request_id)
        try:
            parsed_id = str(uuid.UUID(request.headers.get("x-atlas-request-id", "")))
        except ValueError:
            return _error_response(request, "BAD_REQUEST", "X-Atlas-Request-Id doit être un UUID.",
                                   capability, status=400, fallback_request_id=request_id)
        if capability in _READ_CAPABILITIES and not active_capabilities.get(capability, False):
            return _error_response(request, "CAPABILITY_UNAVAILABLE", "Capability désactivée.", capability,
                                   status=403, fallback_request_id=parsed_id)
        device_ref = _device_ref(android_secret)
        category = _TOOL_CONSENT_CATEGORY.get(capability)
        if category is not None:
            for consent_category in tool_consent_categories(capability):
                state = projections.consent_state(device_ref, consent_category)
                if state is None or state["state"] != "GRANTED":
                    return _error_response(request, "CONSENT_REQUIRED", "Le consentement Android n’est pas GRANTED.",
                                           capability, status=403, fallback_request_id=parsed_id)
                projection_failure = require_projection_consent(
                    device_ref, consent_category, int(state["consent_version"]), capability, request
                )
                if projection_failure is not None:
                    return projection_failure
        return parsed_id, device_ref

    def require_android(request: Request, capability: str | None, *, fallback: str | None = None) -> tuple[str, str] | JSONResponse:
        expected = current_android_token()
        supplied = _bearer(request)
        tool_secret = os.getenv("ATLAS_TOOL_TOKEN")
        if (
            not expected
            or not supplied
            or hmac.compare_digest(expected, tool_secret or "")
            or not hmac.compare_digest(supplied, expected)
        ):
            return _error_response(request, "AUTH_REQUIRED", "Authentification Android requise.", capability,
                                   status=401, fallback_request_id=fallback)
        return _request_id(request, fallback), _device_ref(expected)

    def require_current_consent(device_ref: str, category: str, version: int, capability: str,
                                request: Request) -> JSONResponse | None:
        state = projections.consent_state(device_ref, category)
        if state is None or state["state"] != "GRANTED":
            return _error_response(request, "CONSENT_REQUIRED", "Le consentement Android n’est pas GRANTED.",
                                   capability, status=403)
        current = int(state["consent_version"])
        if version < current:
            return _error_response(request, "STALE_VERSION", "La version de consentement est obsolète.",
                                   capability, status=409)
        if version > current:
            return _error_response(request, "CONSENT_REQUIRED", "L’état du consentement doit être synchronisé avant les données.",
                                   capability, status=403)
        return None

    def require_projection_consent(
        device_ref: str,
        category: str,
        current_version: int,
        capability: str,
        request: Request,
        *,
        require_projection: bool = False,
    ) -> JSONResponse | None:
        state = projections.consent_state(device_ref, category)
        if state is None or state["state"] != "GRANTED":
            return _error_response(request, "CONSENT_REQUIRED", "Le consentement Android n’est pas GRANTED.",
                                   capability, status=403)
        persisted_version = int(state["consent_version"])
        if persisted_version != current_version:
            return _error_response(request, "STALE_VERSION", "La version de consentement a changé pendant la requête.",
                                   capability, status=409)
        projection_version = latest_projection_consent_version(device_ref, category)
        projection_failure = None
        if projection_version is None:
            if require_projection:
                projection_failure = _error_response(
                    request, "STALE_DATA", "La projection doit être synchronisée avec le consentement courant.",
                    capability, status=409,
                )
        elif projection_version != persisted_version:
            projection_failure = _error_response(
                request, "STALE_DATA", "La projection ne correspond pas au consentement courant.",
                capability, status=409,
            )
        final_state = projections.consent_state(device_ref, category)
        if final_state is None or final_state["state"] != "GRANTED":
            return _error_response(request, "CONSENT_REQUIRED", "Le consentement Android n’est pas GRANTED.",
                                   capability, status=403)
        if int(final_state["consent_version"]) != persisted_version:
            return _error_response(request, "STALE_VERSION", "La version de consentement a changé pendant la requête.",
                                   capability, status=409)
        return projection_failure

    def revalidate_projection_consent(
        device_ref: str,
        category: str,
        capability: str,
        request: Request,
        *,
        expected_version: int | None = None,
        require_projection: bool = False,
    ) -> JSONResponse | None:
        state = projections.consent_state(device_ref, category)
        if state is None or state["state"] != "GRANTED":
            return _error_response(request, "CONSENT_REQUIRED", "Le consentement Android n’est pas GRANTED.",
                                   capability, status=403)
        version = int(state["consent_version"])
        if expected_version is not None and version != expected_version:
            return _error_response(request, "STALE_VERSION", "La version de consentement a changé pendant la requête.",
                                   capability, status=409)
        return require_projection_consent(
            device_ref,
            category,
            version,
            capability,
            request,
            require_projection=require_projection,
        )

    @serialized_consent_route
    async def handle_consent_state_sync(request: Request) -> JSONResponse:
        auth = require_android(request, None)
        if isinstance(auth, JSONResponse):
            return auth
        request_id, device_ref = auth
        body, failure = await read_json_body(request, None, fallback=request_id)
        if failure is not None:
            return failure
        schema = _SYNC_SCHEMA["$defs"]["consent_state_update"]
        if not isinstance(body, dict) or not _validate(schema, body):
            return _schema_failure(request, None, fallback=request_id)
        request_id = _request_id(request, body["request_id"])
        if body["capability"] != "consent.state.write":
            return _error_response(request, "BAD_REQUEST", "La capability du payload ne correspond pas à la route.",
                                   None, status=400, fallback_request_id=request_id)
        try:
            receipt = projections.save_consent_state(
                device_ref,
                body["category"],
                int(body["consent_version"]),
                body["state"],
                idempotency_key=body["idempotency_key"],
            )
        except V4ProjectionError as exc:
            code, status, message = _status_from_projection_error(exc)
            if isinstance(exc, IdempotencyConflict):
                code, status, message = "IDEMPOTENCY_CONFLICT", 409, "Clé d’idempotence réutilisée avec un autre état."
            return _error_response(request, code, message, None, status=status,
                                   fallback_request_id=request_id)
        response = {
            "contract_version": 4,
            "schema_version": 4,
            "request_id": request_id,
            "capability": "consent.state.write",
            "category": receipt["category"],
            "consent_version": receipt["consent_version"],
            "state": receipt["state"],
            "updated_at": receipt["updated_at"],
            "source": receipt["source"],
            "replayed": receipt["replayed"],
            "result_code": "DUPLICATE" if receipt["replayed"] else "ACCEPTED",
        }
        if not _validate(_SYNC_SCHEMA["$defs"]["consent_state_receipt"], response):
            return _error_response(request, "INTERNAL", "Le reçu de consentement ne respecte pas le schéma V4.",
                                   None, status=500, fallback_request_id=request_id)
        return JSONResponse(content=response)

    async def read_json_body(request: Request, capability: str | None, *, fallback: str | None = None) -> tuple[Any, JSONResponse | None]:
        try:
            body = await request.json()
        except (json.JSONDecodeError, UnicodeDecodeError, ValueError):
            return None, _schema_failure(request, capability, fallback=fallback)
        return body, None

    async def invoke_tool(
        request: Request,
        tool_name: str,
        arguments: dict[str, Any],
        request_id: str,
        device_ref: str,
    ) -> JSONResponse:
        cap = _TOOL_CAPABILITIES[tool_name]
        wrapped = {"tool_name": tool_name, "arguments": arguments}
        if not _validate(_TOOL_REQUEST_SCHEMA, wrapped):
            return _schema_failure(request, cap, fallback=request_id)
        operation_consent_versions: dict[str, int] = {}
        for consent_category in tool_consent_categories(cap):
            consent_state = projections.consent_state(device_ref, consent_category)
            if consent_state is None or consent_state["state"] != "GRANTED":
                return _error_response(request, "CONSENT_REQUIRED", "Le consentement Android n’est pas GRANTED.",
                                       cap, status=403, fallback_request_id=request_id)
            operation_consent_versions[consent_category] = int(consent_state["consent_version"])

        def final_consent_failure() -> JSONResponse | None:
            for consent_category, expected_version in operation_consent_versions.items():
                failure = revalidate_projection_consent(
                    device_ref, consent_category, cap, request, expected_version=expected_version
                )
                if failure is not None:
                    return failure
            return None

        try:
            result = getattr(service, tool_name)(device_ref, arguments)
        except V4ToolError as exc:
            consent_failure = final_consent_failure()
            if consent_failure is not None:
                return consent_failure
            status = {
                "AUTH_REQUIRED": 401,
                "CONSENT_REQUIRED": 403,
                "CAPABILITY_UNAVAILABLE": 403,
                "NOT_FOUND": 404,
                "PLAN_NOT_FOUND": 404,
                "ACTIVITY_NOT_FOUND": 404,
                "PLAN_MISMATCH": 409,
                "STALE_DATA": 409,
                "STALE_VERSION": 409,
                "CONFLICT": 409,
                "IDEMPOTENCY_CONFLICT": 409,
                "PERIOD_TOO_LARGE": 422,
                "CURSOR_INVALID": 400,
                "DATA_UNAVAILABLE": 503,
                "UPSTREAM_UNAVAILABLE": 503,
                "TIMEOUT": 504,
            }.get(exc.code, 400)
            return JSONResponse(status_code=status, content=exc.envelope(request_id))
        except Exception:
            consent_failure = final_consent_failure()
            if consent_failure is not None:
                return consent_failure
            return _error_response(request, "INTERNAL", "Erreur interne V4.", cap,
                                   status=500, fallback_request_id=request_id)
        consent_failure = final_consent_failure()
        if consent_failure is not None:
            return consent_failure
        result["request_id"] = request_id
        if result.get("data") is None:
            if tool_name == "atlas_get_day_plan":
                # The contract's NO_PLAN branch is the schema-valid form of an absent plan.
                result["data"] = {"state": "NO_PLAN", "next_cursor": None, "truncated": False}
            else:
                code = "DATA_UNKNOWN" if result.get("freshness") == "UNKNOWN" else "DATA_UNAVAILABLE"
                return _error_response(request, code, "Aucune projection V4 disponible.", cap,
                                       status=503, fallback_request_id=request_id)
        if not _validate(_TOOL_RESULT_SCHEMA, result):
            return _error_response(request, "INTERNAL", "Le résultat ne respecte pas le schéma V4.", cap,
                                   status=500, fallback_request_id=request_id)
        return JSONResponse(content=result)

    @router.get("/internal/v4/capabilities")
    async def get_capabilities(request: Request) -> JSONResponse:
        auth = require_tool(request, "tool_catalog.read")
        if isinstance(auth, JSONResponse):
            return auth
        request_id, _device = auth
        response = {
            "contract_version": 4,
            "schema_version": 4,
            "service": "atlas-core",
            "supported_contract_versions": [2, 3, 4],
            "capabilities": {
                **{name: bool(active_capabilities.get(name, False)) for name in _READ_CAPABILITIES},
                "coach.chat": bool(active_capabilities.get("coach.chat", False)),
            },
            "limits": {
                "max_tool_calls_per_turn": 6,
                "max_proposals_per_turn": 1,
                "max_page_size": 32,
                "max_usage_window_days": 14,
                "max_payload_bytes": 65_536,
                "max_response_bytes": 262_144,
                "chat_timeout_seconds": 20,
            },
        }
        if not _validate(_CAPABILITY_SCHEMA, response):
            return _error_response(request, "INTERNAL", "Le catalogue ne respecte pas le schéma V4.",
                                   "tool_catalog.read", status=500, fallback_request_id=request_id)
        return JSONResponse(content=response)

    @router.get("/internal/v4/day-plan")
    @serialized_consent_route
    async def get_day_plan(request: Request) -> JSONResponse:
        auth = require_tool(request, "day_plan.read")
        if isinstance(auth, JSONResponse):
            return auth
        request_id, device_ref = auth
        try:
            args = _query_arguments(request, "atlas_get_day_plan")
        except _RouteFailure as exc:
            return _error_response(request, exc.code, exc.message, exc.capability,
                                   status=exc.status, fallback_request_id=request_id)
        return await invoke_tool(request, "atlas_get_day_plan", args, request_id, device_ref)

    @router.get("/internal/v4/activity-status/{activity_id}")
    @serialized_consent_route
    async def get_activity_status(activity_id: str, request: Request) -> JSONResponse:
        auth = require_tool(request, "activity_status.read")
        if isinstance(auth, JSONResponse):
            return auth
        request_id, device_ref = auth
        try:
            args = _query_arguments(request, "atlas_get_activity_status", {"activity_id": activity_id})
        except _RouteFailure as exc:
            return _error_response(request, exc.code, exc.message, exc.capability,
                                   status=exc.status, fallback_request_id=request_id)
        return await invoke_tool(request, "atlas_get_activity_status", args, request_id, device_ref)

    @router.post("/internal/v4/plan-revision-proposals")
    @serialized_consent_route
    async def propose_plan_revision(request: Request) -> JSONResponse:
        auth = require_tool(request, "plan_revision.propose")
        if isinstance(auth, JSONResponse):
            return auth
        request_id, device_ref = auth
        if request.query_params:
            return _error_response(request, "BAD_REQUEST", "La route de proposition n’accepte aucun paramètre d’URL.",
                                   "plan_revision.propose", status=400, fallback_request_id=request_id)
        body, failure = await read_json_body(request, "plan_revision.propose", fallback=request_id)
        if failure is not None:
            return failure
        return await invoke_tool(request, "atlas_propose_plan_revision", body, request_id, device_ref)

    @router.get("/internal/v4/usage-summary")
    @serialized_consent_route
    async def get_usage_summary(request: Request) -> JSONResponse:
        auth = require_tool(request, "usage_summary.read")
        if isinstance(auth, JSONResponse):
            return auth
        request_id, device_ref = auth
        try:
            args = _query_arguments(request, "atlas_get_usage_summary")
        except _RouteFailure as exc:
            return _error_response(request, exc.code, exc.message, exc.capability,
                                   status=exc.status, fallback_request_id=request_id)
        return await invoke_tool(request, "atlas_get_usage_summary", args, request_id, device_ref)

    @router.get("/internal/v4/goal-progress")
    @serialized_consent_route
    async def get_goal_progress(request: Request) -> JSONResponse:
        auth = require_tool(request, "goal_progress.read")
        if isinstance(auth, JSONResponse):
            return auth
        request_id, device_ref = auth
        try:
            args = _query_arguments(request, "atlas_get_goal_progress")
        except _RouteFailure as exc:
            return _error_response(request, exc.code, exc.message, exc.capability,
                                   status=exc.status, fallback_request_id=request_id)
        return await invoke_tool(request, "atlas_get_goal_progress", args, request_id, device_ref)

    @router.get("/internal/v4/training-plan")
    @serialized_consent_route
    async def get_training_plan(request: Request) -> JSONResponse:
        auth = require_tool(request, "training_plan.read")
        if isinstance(auth, JSONResponse):
            return auth
        request_id, device_ref = auth
        try:
            args = _query_arguments(request, "atlas_get_training_plan")
        except _RouteFailure as exc:
            return _error_response(request, exc.code, exc.message, exc.capability,
                                   status=exc.status, fallback_request_id=request_id)
        return await invoke_tool(request, "atlas_get_training_plan", args, request_id, device_ref)

    @router.get("/internal/v4/agent/proposals/by-idempotency/{idempotency_key}")
    @serialized_consent_route
    async def lookup_proposal(idempotency_key: str, request: Request) -> JSONResponse:
        capability = "plan_revision.propose"
        auth = require_tool(request, capability)
        if isinstance(auth, JSONResponse):
            return auth
        request_id, device_ref = auth
        consent_state = projections.consent_state(device_ref, "DAY_PLAN")
        projection_failure = require_projection_consent(
            device_ref,
            "DAY_PLAN",
            int(consent_state["consent_version"]),
            capability,
            request,
            require_projection=True,
        ) if consent_state is not None else _error_response(
            request, "CONSENT_REQUIRED", "Le consentement Android n’est pas GRANTED.", capability, status=403
        )
        if projection_failure is not None:
            return projection_failure
        try:
            idempotency_key = str(uuid.UUID(idempotency_key))
        except ValueError:
            return _error_response(request, "BAD_REQUEST", "La clé d’idempotence doit être un UUID.",
                                   capability, status=400, fallback_request_id=request_id)
        if not active_capabilities.get(capability, False):
            return _error_response(request, "CAPABILITY_UNAVAILABLE", "Capability désactivée.", capability,
                                   status=403, fallback_request_id=request_id)
        proposal = projections.proposal_by_idempotency_key(idempotency_key)
        if proposal is None:
            return _error_response(request, "NOT_FOUND", "Proposition inconnue.", capability,
                                   status=404, fallback_request_id=request_id)
        consent_failure = revalidate_projection_consent(
            device_ref,
            "DAY_PLAN",
            capability,
            request,
            expected_version=int(consent_state["consent_version"]),
            require_projection=True,
        )
        if consent_failure is not None:
            return consent_failure
        public = _public_proposal(proposal)
        proposal_schema = _PROPOSAL_ACK_SCHEMA["$defs"]["proposal"]
        if not _validate(proposal_schema, public):
            return _error_response(request, "INTERNAL", "La proposition ne respecte pas le schéma V4.",
                                   capability, status=500, fallback_request_id=request_id)
        return JSONResponse(content=public)

    async def handle_sync(request: Request, route: str) -> JSONResponse:
        capability, schema_key = _SYNC_ROUTES[route]
        auth = require_android(request, capability)
        if isinstance(auth, JSONResponse):
            return auth
        request_id, device_ref = auth
        body, failure = await read_json_body(request, capability, fallback=request_id)
        if failure is not None:
            return failure
        if not isinstance(body, dict) or not _validate(_SYNC_SCHEMA, body):
            return _schema_failure(request, capability, fallback=request_id)
        request_id = _request_id(request, body.get("request_id"))
        if body.get("capability") != capability:
            return _error_response(request, "BAD_REQUEST", "La capability du payload ne correspond pas à la route.",
                                   capability, status=400, fallback_request_id=request_id)
        schema_for_route = _SYNC_SCHEMA["$defs"][schema_key]
        if not _validate(schema_for_route, body):
            return _schema_failure(request, capability, fallback=request_id)
        consent_version = int(body["consent_version"])
        consent_category = {
            "day_plan.snapshot.write": "DAY_PLAN",
            "activity_status.write": "ACTIVITY_STATUS",
            "usage_summary.write": "USAGE_SUMMARY",
            "goal_progress.write": "GOAL_PROGRESS",
            "training_plan.write": "TRAINING_PLAN",
        }[capability]
        stale_consent = require_current_consent(device_ref, consent_category, consent_version,
                                                capability, request)
        if stale_consent is not None:
            return stale_consent
        data = body["data"]
        if capability == "usage_summary.write" and int(data["consent_version"]) != consent_version:
            return _error_response(request, "CONSENT_REQUIRED", "Le résumé ne correspond pas au consentement courant.",
                                   capability, status=403, fallback_request_id=request_id)
        day_plan_consent_version: int | None = None
        if capability == "activity_status.write":
            day_plan_consent = projections.consent_state(device_ref, "DAY_PLAN")
            if day_plan_consent is None or day_plan_consent["state"] != "GRANTED":
                return _error_response(request, "CONSENT_REQUIRED", "Le consentement DAY_PLAN n’est pas GRANTED.",
                                       capability, status=403, fallback_request_id=request_id)
            day_plan_consent_version = int(day_plan_consent["consent_version"])
            plan_consent_failure = require_projection_consent(
                device_ref, "DAY_PLAN", day_plan_consent_version, capability, request, require_projection=True
            )
            if plan_consent_failure is not None:
                return plan_consent_failure
            plan = projections.latest_day_plan_snapshot(device_ref, plan_id=data["plan_id"])
            if plan is None:
                return _error_response(request, "PLAN_NOT_FOUND", "Snapshot de plan inconnu.", capability,
                                       status=404, fallback_request_id=request_id)
            plan_consent_failure = revalidate_projection_consent(
                device_ref, "DAY_PLAN", capability, request,
                expected_version=day_plan_consent_version, require_projection=True,
            )
            if plan_consent_failure is not None:
                return plan_consent_failure
            if int(plan["plan_version"]) != int(data["plan_version"]):
                return _error_response(request, "STALE_VERSION", "L’événement vise une version de plan obsolète.",
                                       capability, status=409, fallback_request_id=request_id)
            activity = next((item for item in plan["projection"].get("activities", [])
                             if item.get("activity_id") == data["activity_id"]), None)
            if activity is None:
                return _error_response(request, "ACTIVITY_NOT_FOUND", "Activité absente du snapshot de plan.",
                                       capability, status=404, fallback_request_id=request_id)
            prior_event = projections.latest_activity_status_event(device_ref, data["plan_id"], data["activity_id"])
            current_status = prior_event["new_status"] if prior_event else activity.get("status")
            with store.connect() as connection:
                existing_event = connection.execute(
                    "SELECT 1 FROM activity_status_events_v4 WHERE event_id=?",
                    (data["event_id"],),
                ).fetchone()
            if existing_event is None and current_status != data["from_status"]:
                return _error_response(request, "STALE_VERSION", "Le statut précédent ne correspond plus au plan.",
                                       capability, status=409, fallback_request_id=request_id)
            plan_consent_failure = revalidate_projection_consent(
                device_ref, "DAY_PLAN", capability, request,
                expected_version=day_plan_consent_version, require_projection=True,
            )
            if plan_consent_failure is not None:
                return plan_consent_failure
        try:
            if capability == "day_plan.snapshot.write":
                receipt = projections.save_day_plan_snapshot(device_ref, data, consent_version=consent_version)
                stored_version = int(data["version"])
            elif capability == "activity_status.write":
                receipt = projections.save_activity_status_event(device_ref, data, consent_version=consent_version)
                consent_failure = require_current_consent(
                    device_ref, consent_category, consent_version, capability, request
                )
                if consent_failure is not None:
                    return consent_failure
                projection_failure = require_projection_consent(
                    device_ref, consent_category, consent_version, capability, request, require_projection=True
                )
                if projection_failure is not None:
                    return projection_failure
                plan_consent_failure = revalidate_projection_consent(
                    device_ref, "DAY_PLAN", capability, request,
                    expected_version=day_plan_consent_version, require_projection=True,
                )
                if plan_consent_failure is not None:
                    return plan_consent_failure
                response = {
                    "contract_version": 4,
                    "schema_version": 4,
                    "request_id": request_id,
                    "capability": capability,
                    "items": [{
                        "event_id": data["event_id"],
                        "result_code": "DUPLICATE" if receipt.replayed else "ACCEPTED",
                        "last_synced_at": _utc_now(store),
                    }],
                }
                if not _validate(_SYNC_RECEIPTS_SCHEMA, response):
                    return _error_response(request, "INTERNAL", "Le reçu ne respecte pas le schéma V4.",
                                           capability, status=500, fallback_request_id=request_id)
                return JSONResponse(content=response)
            elif capability == "usage_summary.write":
                receipt = projections.save_usage_summary(device_ref, data, consent_version=consent_version)
                stored_version = consent_version
            elif capability == "goal_progress.write":
                receipt = projections.save_goal_progress_snapshot(device_ref, data, consent_version=consent_version)
                stored_version = int(data["goals_version"])
            else:
                receipt = projections.save_training_snapshot(device_ref, data, consent_version=consent_version)
                stored_version = consent_version
        except V4ProjectionError as exc:
            code, status, message = _status_from_projection_error(exc)
            return _error_response(request, code, message, capability, status=status,
                                   fallback_request_id=request_id)
        consent_failure = require_current_consent(
            device_ref, consent_category, consent_version, capability, request
        )
        if consent_failure is not None:
            return consent_failure
        projection_failure = require_projection_consent(
            device_ref, consent_category, consent_version, capability, request, require_projection=True
        )
        if projection_failure is not None:
            return projection_failure
        response = {
            "contract_version": 4,
            "schema_version": 4,
            "request_id": request_id,
            "capability": capability,
            "accepted": True,
            "replayed": receipt.replayed,
            "stored_version": stored_version,
            "snapshot_hash": receipt.record_hash,
            "last_synced_at": _utc_now(store),
            "result_code": "DUPLICATE" if receipt.replayed else "ACCEPTED",
        }
        if not _validate(_SYNC_RECEIPTS_SCHEMA, response):
            return _error_response(request, "INTERNAL", "Le reçu ne respecte pas le schéma V4.",
                                   capability, status=500, fallback_request_id=request_id)
        return JSONResponse(content=response)

    def sync_endpoint(path: str) -> Callable[[Request], Any]:
        @serialized_consent_route
        async def endpoint(request: Request) -> JSONResponse:
            return await handle_sync(request, path)

        endpoint.__name__ = f"sync_{path.rsplit('/', 1)[-1].replace('-', '_')}"
        return endpoint

    for sync_path in _SYNC_ROUTES:
        router.add_api_route(sync_path, sync_endpoint(sync_path), methods=["POST"],
                             name=f"sync_{sync_path.rsplit('/', 1)[-1]}", include_in_schema=False)
    router.add_api_route("/v4/sync/consent-state", handle_consent_state_sync, methods=["POST"],
                         name="sync_consent_state", include_in_schema=False)

    def consent_failure_for_version(device_ref: str, category: str, version: int) -> str | None:
        state = projections.consent_state(device_ref, category)
        if state is None or state["state"] != "GRANTED":
            return "CONSENT_REQUIRED"
        current = int(state["consent_version"])
        if version != current:
            return "STALE_VERSION" if version < current else "CONSENT_REQUIRED"
        return None

    @router.post("/v4/agent/chat")
    async def agent_chat(request: Request) -> JSONResponse:
        auth = require_android(request, "coach.chat")
        if isinstance(auth, JSONResponse):
            return auth
        auth_request_id, device_ref = auth
        try:
            body = _strict_json_body(await request.body())
        except (UnicodeError, ValueError, RecursionError):
            return _chat_error_response(auth_request_id, "BAD_REQUEST")
        if not _validate(_CHAT_REQUEST_SCHEMA, body):
            return _chat_error_response(auth_request_id, "BAD_REQUEST")

        request_id = body["request_id"]
        if not active_capabilities.get("coach.chat", False):
            return _chat_error_response(request_id, "CAPABILITY_UNAVAILABLE")
        for category in body["shared_categories"]:
            failure_code = consent_failure_for_version(device_ref, category, body["consent_version"])
            if failure_code is not None:
                return _chat_error_response(request_id, failure_code)

        try:
            canonical_body = json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
            canonical_body_bytes = canonical_body.encode("utf-8", errors="strict")
        except (TypeError, ValueError, UnicodeError):
            return _chat_error_response(request_id, "BAD_REQUEST")
        body_hash = hashlib.sha256(canonical_body_bytes).hexdigest()
        cache_key = "v4-chat:" + device_ref + ":" + str(uuid.UUID(request_id))

        async with chat_lock:
            try:
                with store.connect() as connection:
                    store.begin_immediate(connection)
                    row = connection.execute(
                        "SELECT response_json FROM agent_chat_cache WHERE cache_key=?", (cache_key,)
                    ).fetchone()
                    if row is None:
                        connection.execute(
                            "INSERT INTO agent_chat_cache(cache_key,response_json,expires_at) VALUES(?,?,?)",
                            (cache_key, json.dumps({"request_hash": body_hash, "state": "pending"}, sort_keys=True),
                             "9999-12-31T23:59:59Z"),
                        )
            except sqlite3.OperationalError:
                return _chat_error_response(request_id, "UPSTREAM_UNAVAILABLE")
            except Exception:  # noqa: BLE001 - storage details and request contents stay private
                return _chat_error_response(request_id, "INTERNAL")
            if row is not None:
                try:
                    cached = json.loads(row["response_json"])
                except (TypeError, ValueError):
                    cached = None
                if not isinstance(cached, dict) or cached.get("request_hash") != body_hash:
                    return _chat_error_response(request_id, "IDEMPOTENCY_CONFLICT")
                if cached.get("state") == "pending":
                    return _chat_error_response(request_id, "REPLAY_PENDING")
                cached_body = cached.get("body")
                cached_status = cached.get("status_code")
                if not isinstance(cached_body, dict) or type(cached_status) is not int:
                    return _chat_error_response(request_id, "IDEMPOTENCY_CONFLICT")
                return JSONResponse(status_code=cached_status, content=cached_body)

            def validate_chat_consent() -> None:
                if not active_capabilities.get("coach.chat", False):
                    raise ChatToolFailure("CAPABILITY_UNAVAILABLE")
                for category in body["shared_categories"]:
                    failure_code = consent_failure_for_version(device_ref, category, body["consent_version"])
                    if failure_code is not None:
                        raise ChatToolFailure(failure_code)

            def validate_tool(tool_name: str) -> None:
                capability = _TOOL_CAPABILITIES.get(tool_name)
                if capability is None:
                    raise ChatToolFailure("UPSTREAM_UNAVAILABLE")
                if not active_capabilities.get(capability, False):
                    raise ChatToolFailure("CAPABILITY_UNAVAILABLE")
                for category in tool_consent_categories(capability):
                    if category not in body["shared_categories"]:
                        raise ChatToolFailure("CONSENT_REQUIRED")
                    failure_code = consent_failure_for_version(device_ref, category, body["consent_version"])
                    if failure_code is not None:
                        raise ChatToolFailure(failure_code)

            def execute_tool(tool_name: str, arguments: dict[str, Any], tool_call_id: str) -> dict[str, Any]:
                validate_tool(tool_name)
                tool_arguments = dict(arguments)
                if tool_name == "atlas_propose_plan_revision":
                    tool_arguments["idempotency_key"] = str(uuid.uuid5(
                        uuid.UUID(request_id), device_ref + "\0" + canonical_body
                    ))
                if not _validate(_TOOL_REQUEST_SCHEMA, {"tool_name": tool_name, "arguments": tool_arguments}):
                    raise ChatToolFailure("UPSTREAM_UNAVAILABLE")
                result_request_id = str(uuid.uuid5(uuid.UUID(request_id), "tool:" + tool_call_id))
                try:
                    result = chat_tool_dispatch[tool_name](device_ref, tool_arguments)
                except V4ToolError as exc:
                    allowed_codes = _ERROR_SCHEMA["properties"]["error"]["properties"]["code"]["enum"]
                    raise ChatToolFailure(exc.code if exc.code in allowed_codes else "INTERNAL") from None
                except sqlite3.OperationalError:
                    raise ChatToolFailure(
                        "UPSTREAM_UNAVAILABLE", uncertain=tool_name == "atlas_propose_plan_revision"
                    ) from None
                except Exception:  # noqa: BLE001 - no arguments, results, or storage errors in responses
                    raise ChatToolFailure(
                        "INTERNAL", uncertain=tool_name == "atlas_propose_plan_revision"
                    ) from None
                result["request_id"] = result_request_id
                if result.get("data") is None:
                    if tool_name == "atlas_get_day_plan":
                        result["data"] = {"state": "NO_PLAN", "next_cursor": None, "truncated": False}
                    else:
                        code = "DATA_UNKNOWN" if result.get("freshness") == "UNKNOWN" else "DATA_UNAVAILABLE"
                        raise ChatToolFailure(code)
                if not _validate(_TOOL_RESULT_SCHEMA, result):
                    raise ChatToolFailure(
                        "INTERNAL", uncertain=tool_name == "atlas_propose_plan_revision"
                    )
                return result

            status_code = 200
            if agent is None:
                orchestration = {
                    "reply": "Je peux t’aider à choisir une prochaine étape claire.",
                    "proposal_ids": [],
                    "agent_status": "mock",
                    "tool_state": "none",
                    "tool_call_count": 0,
                }
            else:
                try:
                    agent_status = agent.status()
                except Exception:  # noqa: BLE001 - a status failure cannot authorize effects
                    agent_status = None
                if agent_status is not None and agent_status.adapter == "mock":
                    orchestration = {
                        "reply": "Je peux t’aider à choisir une prochaine étape claire.",
                        "proposal_ids": [],
                        "agent_status": "mock",
                        "tool_state": "none",
                        "tool_call_count": 0,
                    }
                else:
                    chat_method = getattr(agent, "chat_v4", None)
                    if not callable(chat_method):
                        error_response = _chat_error_response(request_id, "UPSTREAM_UNAVAILABLE")
                        status_code = error_response.status_code
                        orchestration = error_response.body
                        orchestration = json.loads(orchestration)
                    else:
                        try:
                            orchestration = chat_method(
                                conversation_id=body["conversation_id"],
                                message=body["message"],
                                shared_categories=body["shared_categories"],
                                validate_tool=validate_tool,
                                execute_tool=execute_tool,
                                validate_chat=validate_chat_consent,
                            )
                        except ChatOrchestrationFailure as exc:
                            if exc.tool_call_count > 0:
                                status_code = _chat_error_response(request_id, exc.code).status_code
                                orchestration = {
                                    "reply": CHAT_V4_FALLBACK_REPLY,
                                    "proposal_ids": exc.proposal_ids,
                                    "agent_status": "unavailable",
                                    "tool_state": exc.tool_state,
                                    "tool_call_count": exc.tool_call_count,
                                }
                            else:
                                error_response = _chat_error_response(request_id, exc.code)
                                status_code = error_response.status_code
                                orchestration = json.loads(error_response.body)
                        except Exception:  # noqa: BLE001 - never return adapter or credential details
                            error_response = _chat_error_response(request_id, "INTERNAL")
                            status_code = error_response.status_code
                            orchestration = json.loads(error_response.body)

            if "error" not in orchestration:
                response_body = {
                    "contract_version": 4,
                    "schema_version": 4,
                    "request_id": request_id,
                    **orchestration,
                }
                if not _validate(_CHAT_RESPONSE_SCHEMA, response_body):
                    if response_body.get("tool_call_count", 0) > 0:
                        response_body = {
                            "contract_version": 4,
                            "schema_version": 4,
                            "request_id": request_id,
                            "reply": CHAT_V4_FALLBACK_REPLY,
                            "proposal_ids": [],
                            "agent_status": "unavailable",
                            "tool_state": "uncertain",
                            "tool_call_count": min(6, max(0, response_body.get("tool_call_count", 0))),
                        }
                        status_code = 503
                    else:
                        error_response = _chat_error_response(request_id, "UPSTREAM_UNAVAILABLE")
                        status_code = error_response.status_code
                        response_body = json.loads(error_response.body)
            else:
                response_body = orchestration

            cached_value = {
                "request_hash": body_hash,
                "state": "complete",
                "status_code": status_code,
                "body": response_body,
            }
            try:
                with store.connect() as connection:
                    connection.execute(
                        "UPDATE agent_chat_cache SET response_json=?,expires_at=? WHERE cache_key=?",
                        (json.dumps(cached_value, ensure_ascii=False, sort_keys=True),
                         "9999-12-31T23:59:59Z", cache_key),
                    )
            except Exception:  # noqa: BLE001 - leave the reservation pending; never repeat tools
                _LOGGER.warning("V4 chat cache finalization failed; reservation retained pending")
            return JSONResponse(status_code=status_code, content=response_body)

    @router.post("/v4/agent/proposals/pull")
    @serialized_consent_route
    async def pull_proposals(request: Request) -> JSONResponse:
        capability = "proposal.ack"
        auth = require_android(request, capability)
        if isinstance(auth, JSONResponse):
            return auth
        request_id, device_ref = auth
        body, failure = await read_json_body(request, capability, fallback=request_id)
        if failure is not None:
            return failure
        required = {"contract_version", "schema_version", "request_id", "capability", "consent_version",
                    "page_size", "cursor"}
        if (
            not isinstance(body, dict)
            or set(body) != required
            or body.get("contract_version") != 4
            or body.get("schema_version") != 4
            or body.get("capability") != capability
            or not _is_uuid(body.get("request_id"))
            or not isinstance(body.get("consent_version"), int)
            or isinstance(body.get("consent_version"), bool)
            or body["consent_version"] < 1
            or not isinstance(body.get("page_size"), int)
            or isinstance(body.get("page_size"), bool)
            or not 1 <= body["page_size"] <= 32
            or (body.get("cursor") is not None and not isinstance(body.get("cursor"), str))
        ):
            return _schema_failure(request, capability, fallback=request_id)
        request_id = _request_id(request, body["request_id"])
        if not active_capabilities.get("plan_revision.propose", False):
            return _error_response(request, "CAPABILITY_UNAVAILABLE", "Capability désactivée.", capability,
                                   status=403, fallback_request_id=request_id)
        consent_failure = require_current_consent(
            device_ref, "DAY_PLAN", body["consent_version"], capability, request
        )
        if consent_failure is not None:
            return consent_failure
        projection_failure = require_projection_consent(
            device_ref, "DAY_PLAN", body["consent_version"], capability, request, require_projection=True
        )
        if projection_failure is not None:
            return projection_failure
        cursor_value = body.get("cursor")
        after_created = ""
        after_id = ""
        if cursor_value:
            try:
                after_created, after_id = _decode_cursor(cursor_value, current_android_token() or "", device_ref)
            except ValueError:
                return _error_response(request, "CURSOR_INVALID", "Curseur de pull invalide.", capability,
                                       status=400, fallback_request_id=request_id)
        now = _utc_now(store)
        with store.connect() as connection:
            rows = connection.execute(
                "SELECT proposal_json,state,created_at,proposal_id FROM agent_proposals_v4 "
                "WHERE state IN ('PENDING_ANDROID','RECEIVED','AWAITING_USER','UNCERTAIN') "
                "AND expires_at>? AND (created_at>? OR (created_at=? AND proposal_id>?)) "
                "ORDER BY created_at,proposal_id LIMIT ?",
                (now, after_created, after_created, after_id, body["page_size"] + 1),
            ).fetchall()
        has_more = len(rows) > body["page_size"]
        page = rows[:body["page_size"]]
        proposals = []
        for row in page:
            value = json.loads(row["proposal_json"])
            value["state"] = row["state"]
            proposal = _public_proposal(value)
            if not _validate(_PROPOSAL_ACK_SCHEMA["$defs"]["proposal"], proposal):
                return _error_response(request, "INTERNAL", "La proposition ne respecte pas le schéma V4.",
                                       capability, status=500, fallback_request_id=request_id)
            proposals.append(proposal)
        next_cursor = None
        if has_more and page:
            last = page[-1]
            next_cursor = _encode_cursor(last["created_at"], last["proposal_id"],
                                         current_android_token() or "", device_ref)
        response = {
            "contract_version": 4,
            "schema_version": 4,
            "request_id": request_id,
            "capability": capability,
            "proposals": proposals,
            "next_cursor": next_cursor,
        }
        consent_failure = require_current_consent(
            device_ref, "DAY_PLAN", body["consent_version"], capability, request
        )
        if consent_failure is not None:
            return consent_failure
        projection_failure = require_projection_consent(
            device_ref, "DAY_PLAN", body["consent_version"], capability, request, require_projection=True
        )
        if projection_failure is not None:
            return projection_failure
        return JSONResponse(content=response)

    @router.post("/v4/agent/proposals/{proposal_id}/ack")
    @serialized_consent_route
    async def acknowledge_proposal(proposal_id: str, request: Request) -> JSONResponse:
        capability = "proposal.ack"
        auth = require_android(request, capability)
        if isinstance(auth, JSONResponse):
            return auth
        request_id, device_ref = auth
        body, failure = await read_json_body(request, capability, fallback=request_id)
        if failure is not None:
            return failure
        if not isinstance(body, dict) or not _validate(_PROPOSAL_ACK_SCHEMA, body):
            return _schema_failure(request, capability, fallback=request_id)
        try:
            path_id = str(uuid.UUID(proposal_id))
        except ValueError:
            return _error_response(request, "BAD_REQUEST", "proposal_id doit être un UUID.", capability,
                                   status=400, fallback_request_id=request_id)
        if body["proposal_id"] != path_id:
            return _error_response(request, "PLAN_MISMATCH", "proposal_id du chemin et du reçu diffèrent.",
                                   capability, status=409, fallback_request_id=request_id)
        consent_failure = require_current_consent(
            device_ref, "DAY_PLAN", body["consent_version"], capability, request
        )
        if consent_failure is not None:
            return consent_failure
        projection_failure = require_projection_consent(
            device_ref, "DAY_PLAN", body["consent_version"], capability, request, require_projection=True
        )
        if projection_failure is not None:
            return projection_failure
        with store.connect() as connection:
            proposal_row = connection.execute(
                "SELECT state,expires_at FROM agent_proposals_v4 WHERE proposal_id=?",
                (path_id,),
            ).fetchone()
            existing_ack = connection.execute(
                "SELECT body_hash FROM agent_proposal_events_v4 WHERE ack_id=?",
                (body["ack_id"],),
            ).fetchone()
        if proposal_row is None:
            return _error_response(request, "NOT_FOUND", "Proposition inconnue.", capability,
                                   status=404, fallback_request_id=request_id)
        exact_replay = (
            existing_ack is not None
            and hmac.compare_digest(existing_ack["body_hash"], content_hash(body))
        )
        expired = proposal_row["expires_at"] <= _utc_now(store)
        if expired and not exact_replay and body["state"] not in {"EXPIRED", "RECONCILED"}:
            return _error_response(request, "PROPOSAL_EXPIRED", "La proposition a expiré.", capability,
                                   status=409, fallback_request_id=request_id)
        consent_failure = require_current_consent(
            device_ref, "DAY_PLAN", body["consent_version"], capability, request
        )
        if consent_failure is not None:
            return consent_failure
        projection_failure = require_projection_consent(
            device_ref, "DAY_PLAN", body["consent_version"], capability, request, require_projection=True
        )
        if projection_failure is not None:
            return projection_failure
        try:
            receipt = projections.append_proposal_ack(body)
        except V4ProjectionError as exc:
            code, status, message = _status_from_projection_error(exc)
            return _error_response(request, code, message, capability, status=status,
                                   fallback_request_id=request_id)
        return JSONResponse(content=body, headers={"X-Atlas-Replayed": "true" if receipt.replayed else "false"})

    @router.api_route("/internal/v4/{path:path}", methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"])
    @router.api_route("/v4/{path:path}", methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"])
    async def reject_unknown_v4_route(path: str, request: Request) -> JSONResponse:
        known = {route for route in _SYNC_ROUTES} | {
            "/internal/v4/capabilities",
            "/internal/v4/day-plan",
            "/internal/v4/activity-status/{activity_id}",
            "/internal/v4/plan-revision-proposals",
            "/internal/v4/usage-summary",
            "/internal/v4/goal-progress",
            "/internal/v4/training-plan",
            "/internal/v4/agent/proposals/by-idempotency/{key}",
            "/v4/sync/consent-state",
            "/v4/agent/chat",
            "/v4/agent/proposals/pull",
        }
        request_path = request.url.path
        if request_path.startswith("/internal/v4/activity-status/"):
            is_known = request.method != "GET"
        elif request_path.startswith("/internal/v4/agent/proposals/by-idempotency/"):
            is_known = request.method != "GET"
        elif request_path.startswith("/v4/agent/proposals/") and request_path.endswith("/ack"):
            is_known = request.method != "POST"
        else:
            expected_method = "POST" if request_path in _SYNC_ROUTES or request_path in {
                "/internal/v4/plan-revision-proposals", "/v4/agent/chat", "/v4/agent/proposals/pull",
                "/v4/sync/consent-state"
            } else "GET"
            is_known = request_path in known and request.method != expected_method
        if is_known:
            return _error_response(request, "BAD_REQUEST", "Méthode HTTP non autorisée pour cette route V4.",
                                   status=405)
        return _error_response(request, "NOT_FOUND", "Route V4 inconnue.", status=404)

    router._v4_tool_service = service  # type: ignore[attr-defined]
    return router


def _is_uuid(value: Any) -> bool:
    try:
        uuid.UUID(value)
        return True
    except (ValueError, TypeError, AttributeError):
        return False


def _public_proposal(proposal: dict[str, Any]) -> dict[str, Any]:
    return {
        key: proposal[key]
        for key in _PROPOSAL_ACK_SCHEMA["$defs"]["proposal"]["properties"]
        if key in proposal
    }


def _cursor_key(android_token: str) -> bytes:
    return hashlib.sha256(("atlas-v4-pull:" + android_token).encode("utf-8")).digest()


def _encode_cursor(created_at: str, proposal_id: str, android_token: str, device_ref: str) -> str:
    payload = json.dumps(
        {"created_at": created_at, "proposal_id": proposal_id, "device_ref": device_ref},
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    signature = hmac.new(_cursor_key(android_token), payload, hashlib.sha256).digest()
    return (
        base64.urlsafe_b64encode(payload).decode("ascii").rstrip("=")
        + "."
        + base64.urlsafe_b64encode(signature).decode("ascii").rstrip("=")
    )


def _decode_cursor(cursor: str, android_token: str, device_ref: str) -> tuple[str, str]:
    try:
        encoded_payload, encoded_signature = cursor.split(".", 1)
        payload = base64.urlsafe_b64decode(encoded_payload + "=" * (-len(encoded_payload) % 4))
        signature = base64.urlsafe_b64decode(encoded_signature + "=" * (-len(encoded_signature) % 4))
        expected = hmac.new(_cursor_key(android_token), payload, hashlib.sha256).digest()
        if not hmac.compare_digest(signature, expected):
            raise ValueError
        decoded = json.loads(payload)
        if decoded.get("device_ref") != device_ref:
            raise ValueError
        created_at = decoded.get("created_at")
        proposal_id = decoded.get("proposal_id")
        if not isinstance(created_at, str) or not _is_uuid(proposal_id):
            raise ValueError
        return created_at, proposal_id
    except (ValueError, TypeError, json.JSONDecodeError, UnicodeDecodeError):
        raise ValueError("invalid V4 inbox cursor") from None
