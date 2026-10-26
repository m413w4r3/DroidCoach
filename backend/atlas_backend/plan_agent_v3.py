"""Proposal-only V3 daily-plan revision flow.

Model output is treated as untrusted data. The backend adds its own envelope,
performs the narrow V3 pre-validation, and persists the response before
returning it. Android remains responsible for full validation and execution.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from typing import Literal
from uuid import uuid4
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StrictBool,
    StrictInt,
    StrictStr,
    ValidationError,
    field_validator,
    model_validator,
)

from .models_v2 import MandateSummary
from .plan_v3 import (
    PlanProposalEnvelopeV3,
    PlanRevisionCandidateV3,
    validate_plan_revision,
)
from .store import PlanRevisionIdempotencyConflict, SQLiteStore

UUID_PATTERN = r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
DATE_PATTERN = r"^\d{4}-\d{2}-\d{2}$"


class StrictV3Model(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, str_strip_whitespace=True)


def _plain_text(value: str) -> str:
    if any(ord(character) < 0x20 for character in value):
        raise ValueError("control characters are not allowed")
    return value


class PlanRevisionActivityRequestV3(StrictV3Model):
    activity_id: StrictStr = Field(pattern=UUID_PATTERN)
    kind: Literal["FOCUS", "TRAINING", "APPOINTMENT", "ADMIN", "MEAL", "REST", "BUFFER", "OTHER"]
    title: StrictStr = Field(min_length=1, max_length=80)
    start_minute: StrictInt = Field(ge=0, le=1439)
    duration_minutes: StrictInt = Field(ge=1, le=1440)
    priority: Literal["REQUIRED", "IMPORTANT", "OPTIONAL"]
    goal_id: StrictStr | None = Field(pattern=UUID_PATTERN)

    @field_validator("title")
    @classmethod
    def title_is_plain(cls, value: str) -> str:
        return _plain_text(value)


class PlanRevisionPlanRequestV3(StrictV3Model):
    plan_id: StrictStr = Field(pattern=UUID_PATTERN)
    local_date: StrictStr = Field(pattern=DATE_PATTERN)
    timezone: StrictStr = Field(min_length=1, max_length=64)
    version: StrictInt = Field(ge=1)
    state: Literal["DRAFT", "APPROVED"]
    activities: list[PlanRevisionActivityRequestV3] = Field(max_length=32)

    @field_validator("local_date")
    @classmethod
    def local_date_is_valid(cls, value: str) -> str:
        try:
            date.fromisoformat(value)
        except ValueError as exc:
            raise ValueError("local_date must be an ISO-8601 date") from exc
        return value

    @field_validator("timezone")
    @classmethod
    def timezone_is_known(cls, value: str) -> str:
        try:
            ZoneInfo(value)
        except ZoneInfoNotFoundError as exc:
            raise ValueError("timezone must be an IANA timezone") from exc
        return value

    @model_validator(mode="after")
    def activity_ids_are_unique(self) -> PlanRevisionPlanRequestV3:
        ids = [activity.activity_id.lower() for activity in self.activities]
        if len(ids) != len(set(ids)):
            raise ValueError("activity_id must be unique")
        return self


class PlanRevisionRequestV3(StrictV3Model):
    schema_version: Literal[3]
    request_id: StrictStr = Field(pattern=UUID_PATTERN)
    conversation_id: StrictStr | None
    message: StrictStr = Field(min_length=1, max_length=2000)
    plan_context_consented: StrictBool
    mandate_summary: MandateSummary
    plan: PlanRevisionPlanRequestV3


class PlanRevisionModelOutputV3(StrictV3Model):
    reply: StrictStr
    proposal: PlanRevisionCandidateV3 | None = None


class PlanRevisionResponseV3(StrictV3Model):
    schema_version: Literal[3] = 3
    reply: StrictStr
    agent_status: Literal["ok", "unavailable", "invalid_output", "quota_limited"]
    proposal: PlanProposalEnvelopeV3 | None
    overlaps: list[tuple[StrictStr, StrictStr]]


class PlanRevisionAdapterUnavailable(RuntimeError):
    """The proposal adapter could not complete a request."""


class PlanRevisionQuotaLimited(RuntimeError):
    """The adapter rejected a request because its quota is exhausted."""


class PlanRevisionInvalidOutput(ValueError):
    """The adapter returned a malformed or schema-invalid model response."""


@dataclass(frozen=True)
class PlanRevisionResult:
    status_code: int
    response: dict


INVALID_OUTPUT_REPLY = "La proposition du planificateur est invalide et a été rejetée. Aucune action n'a été exécutée."
UNAVAILABLE_REPLY = "Le service de planification est indisponible. Aucune proposition n'a été enregistrée."
QUOTA_REPLY = "La limite de requêtes du planificateur est atteinte. Réessaie plus tard."
IN_PROGRESS_REPLY = "Une requête identique est en cours ou a été interrompue. Aucune seconde proposition ne sera générée."


def _response(
    *,
    reply: str,
    agent_status: Literal["ok", "unavailable", "invalid_output", "quota_limited"],
    proposal: PlanProposalEnvelopeV3 | None = None,
    overlaps: list[tuple[str, str]] | None = None,
) -> dict:
    return PlanRevisionResponseV3(
        reply=reply,
        agent_status=agent_status,
        proposal=proposal,
        overlaps=overlaps or [],
    ).model_dump(mode="json")


def _canonical_body_hash(body: PlanRevisionRequestV3) -> str:
    canonical = json.dumps(
        body.model_dump(mode="json"),
        sort_keys=True,
        ensure_ascii=False,
        separators=(",", ":"),
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _model_output(value: object) -> PlanRevisionModelOutputV3:
    try:
        if isinstance(value, PlanRevisionModelOutputV3):
            return value
        if isinstance(value, str):
            return PlanRevisionModelOutputV3.model_validate_json(value)
        return PlanRevisionModelOutputV3.model_validate(value)
    except (ValidationError, ValueError, TypeError) as exc:
        raise PlanRevisionInvalidOutput("invalid plan revision output") from exc


def _finish_result(
    store: SQLiteStore,
    request_id: str,
    body_hash: str,
    status_code: int,
    response: dict,
) -> PlanRevisionResult:
    stored = store.complete_plan_revision_request(
        request_id,
        body_hash,
        status_code,
        response,
    )
    return PlanRevisionResult(
        status_code=stored["status_code"],
        response=stored["response"],
    )


def propose_plan_revision(
    body: PlanRevisionRequestV3,
    *,
    adapter: object,
    store: SQLiteStore,
    ttl: timedelta = timedelta(hours=24),
    now: datetime | None = None,
) -> PlanRevisionResult:
    """Call one proposal adapter at most once for this durable request id."""
    if not body.plan_context_consented:
        raise PermissionError("plan context consent is required")

    request_id = body.request_id.lower()
    body_hash = _canonical_body_hash(body)
    current_time = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    claim, inserted = store.claim_plan_revision_request(
        request_id,
        body_hash,
        current_time.isoformat().replace("+00:00", "Z"),
    )
    if not inserted:
        if claim["state"] == "complete":
            return PlanRevisionResult(
                status_code=claim["status_code"],
                response=claim["response"],
            )
        # A durable in-flight marker means a prior call may already have
        # reached the model. Fail closed instead of creating another proposal.
        return PlanRevisionResult(
            status_code=503,
            response=_response(reply=IN_PROGRESS_REPLY, agent_status="unavailable"),
        )

    method = getattr(adapter, "propose_plan_revision", None)
    if not callable(method):
        return _finish_result(
            store,
            request_id,
            body_hash,
            503,
            _response(reply=UNAVAILABLE_REPLY, agent_status="unavailable"),
        )

    conversation = body.conversation_id
    if conversation is not None:
        conversation = "conv_" + hashlib.sha256(conversation.encode("utf-8")).hexdigest()[:32]
    try:
        raw_output = method(
            conversation_id=conversation,
            message=body.message,
            plan=body.plan.model_dump(mode="json"),
            mandate=body.mandate_summary,
        )
        output = _model_output(raw_output)
    except PlanRevisionQuotaLimited:
        return _finish_result(
            store,
            request_id,
            body_hash,
            200,
            _response(reply=QUOTA_REPLY, agent_status="quota_limited"),
        )
    except PlanRevisionInvalidOutput:
        return _finish_result(
            store,
            request_id,
            body_hash,
            200,
            _response(reply=INVALID_OUTPUT_REPLY, agent_status="invalid_output"),
        )
    except Exception:  # noqa: BLE001 - fail closed without adapter details
        return _finish_result(
            store,
            request_id,
            body_hash,
            503,
            _response(reply=UNAVAILABLE_REPLY, agent_status="unavailable"),
        )

    if output.proposal is None:
        return _finish_result(
            store,
            request_id,
            body_hash,
            200,
            _response(reply=output.reply, agent_status="ok"),
        )

    created_at = current_time
    expires_at = created_at + ttl
    idempotency_key = "v3plan-" + hashlib.sha256(
        f"{request_id}:{body_hash}".encode("utf-8")
    ).hexdigest()
    try:
        envelope = PlanProposalEnvelopeV3(
            proposal_id=str(uuid4()),
            idempotency_key=idempotency_key,
            conversation_id=conversation,
            mandate_id=body.mandate_summary.id,
            mandate_version=body.mandate_summary.version,
            created_at=created_at,
            expires_at=expires_at,
            proposal=output.proposal,
        )
    except ValidationError:
        return _finish_result(
            store,
            request_id,
            body_hash,
            200,
            _response(reply=INVALID_OUTPUT_REPLY, agent_status="invalid_output"),
        )

    decision = validate_plan_revision(
        envelope,
        plan_id=body.plan.plan_id,
        current_version=body.plan.version,
        known_activity_ids={activity.activity_id for activity in body.plan.activities},
        now=current_time,
    )
    if not decision.accepted:
        return _finish_result(
            store,
            request_id,
            body_hash,
            200,
            _response(
                reply=f"La proposition a été rejetée : {decision.reason}.",
                agent_status="invalid_output",
            ),
        )

    overlaps = envelope.proposal.parameters.overlaps()
    return _finish_result(
        store,
        request_id,
        body_hash,
        200,
        _response(
            reply=output.reply,
            agent_status="ok",
            proposal=envelope,
            overlaps=overlaps,
        ),
    )
