"""Optional, local-first ATLAS Core API in deterministic mock mode."""

from __future__ import annotations

import hashlib
import hmac
import json
import os
from datetime import datetime, timedelta, timezone
from datetime import time as datetime_time
from pathlib import Path
from typing import Annotated, Literal
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo

import uvicorn
from fastapi import Depends, FastAPI, HTTPException, Query, Request
from fastapi import Path as ApiPath
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StrictStr,
    TypeAdapter,
    field_validator,
    model_validator,
)
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.types import ASGIApp, Receive, Scope, Send

from .agent import AgentAdapter, MockAgentAdapter, ProposedAction
from .content import DEMO_CONTENT
from .models_v2 import (
    AckRequest,
    AgentChatRequest,
    ProjectMemory,
    ProposalV2,
)
from .openclaw_adapter import OpenClawAgentAdapter, stable_conversation_id
from .plan_agent_v3 import PlanRevisionRequestV3, propose_plan_revision
from .policy import MANDATE, PolicyEngine, configured_capabilities
from .store import (
    EventIdConflict,
    InvalidProposalTransition,
    SQLiteStore,
    PlanRevisionIdempotencyConflict,
    UnknownProposal,
    UnknownProposalCursor,
)

MAX_REQUEST_BYTES = 65_536
API_CONTRACT_VERSION = 1
BASE_DIR = Path(__file__).resolve().parents[1]
V2_PROPOSAL_ADAPTER = TypeAdapter(ProposalV2)
DEMO_DIRECTIVE_ID = "demo-focus-001"
DIRECTIVE_CHOICES = ("start", "discuss", "alternative", "postpone", "pause")
CHOICE_ACTIONS = {
    "start": "start_focus_session",
    "discuss": "discuss",
    "alternative": "suggest_alternative",
    "postpone": "postpone",
    "pause": "pause",
}
class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, str_strip_whitespace=True)


class DirectiveOut(StrictModel):
    schema_version: Literal[1] = 1
    directive_id: StrictStr = Field(min_length=1, max_length=80)
    type: Literal["start_focus_session", "habit_checkin", "read_content", "project_action"]
    title: StrictStr = Field(min_length=1, max_length=160)
    reason: StrictStr = Field(max_length=400)
    project_id: StrictStr | None = None
    estimated_minutes: int | None = Field(default=10, ge=1, le=180)
    choices: list[Literal["start", "discuss", "alternative", "postpone", "pause"]] = Field(
        min_length=4, max_length=5
    )
    created_at: datetime
    expires_at: datetime
    requires_explicit_confirmation: bool = False

    @field_validator("created_at", "expires_at")
    @classmethod
    def timestamps_are_utc(cls, value: datetime) -> datetime:
        if value.tzinfo is None:
            raise ValueError("directive timestamps must include timezone")
        return value.astimezone(timezone.utc)

    @model_validator(mode="after")
    def valid_expiry(self) -> DirectiveOut:
        if self.created_at.tzinfo is None or self.expires_at.tzinfo is None:
            raise ValueError("directive timestamps must include timezone")
        if self.expires_at <= self.created_at:
            raise ValueError("expires_at must be after created_at")
        return self


class RespondRequest(StrictModel):
    choice: Literal["start", "discuss", "alternative", "postpone", "pause"]


class EventRequest(StrictModel):
    event_id: UUID = Field(strict=False)
    event_type: Literal[
        "focus_started",
        "focus_paused",
        "focus_completed",
        "habit_checkin",
        "directive_responded",
        "content_opened",
    ]
    occurred_at: datetime = Field(strict=False)
    payload: dict[StrictStr, StrictStr | int | float | bool | None] = Field(
        default_factory=dict, max_length=20
    )

    @field_validator("occurred_at")
    @classmethod
    def timestamp_is_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None:
            raise ValueError("occurred_at must include timezone")
        return value.astimezone(timezone.utc)

    @field_validator("payload")
    @classmethod
    def payload_is_bounded_scalar_data(cls, value):
        for key, item in value.items():
            if not key or len(key) > 80 or any(ord(char) < 32 for char in key):
                raise ValueError("payload keys must be plain text up to 80 characters")
            if isinstance(item, str) and len(item) > 1000:
                raise ValueError("payload strings are limited to 1000 characters")
        return value


class SyncRequest(StrictModel):
    contract_version: Literal[1] = 1
    events: list[EventRequest] = Field(default_factory=list, max_length=100)


class CoachMessageRequest(StrictModel):
    message: StrictStr = Field(min_length=1, max_length=1000)


class RequestSizeLimitMiddleware:
    """Reject oversized bodies, including chunked requests without Content-Length."""

    def __init__(self, app: ASGIApp, max_bytes: int = MAX_REQUEST_BYTES) -> None:
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        headers = dict(scope.get("headers", []))
        length = headers.get(b"content-length")
        if length is not None:
            try:
                if int(length) > self.max_bytes:
                    await JSONResponse(
                        status_code=413,
                        content={
                            "contract_version": API_CONTRACT_VERSION,
                            "detail": "requête trop volumineuse",
                            "error": {"code": "request_too_large", "message": "requête trop volumineuse"},
                        },
                    )(scope, receive, send)
                    return
            except ValueError:
                await JSONResponse(
                    status_code=400,
                    content={
                        "contract_version": API_CONTRACT_VERSION,
                        "detail": "Content-Length invalide",
                        "error": {"code": "invalid_content_length", "message": "Content-Length invalide"},
                    },
                )(scope, receive, send)
                return

        chunks: list[bytes] = []
        size = 0
        more_body = True
        while more_body:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            if message["type"] != "http.request":
                continue
            chunk = message.get("body", b"")
            size += len(chunk)
            if size > self.max_bytes:
                await JSONResponse(
                    status_code=413,
                    content={
                        "contract_version": API_CONTRACT_VERSION,
                        "detail": "requête trop volumineuse",
                        "error": {"code": "request_too_large", "message": "requête trop volumineuse"},
                    },
                )(scope, receive, send)
                return
            chunks.append(chunk)
            more_body = message.get("more_body", False)

        body = b"".join(chunks)
        delivered = False

        async def replay_body() -> dict:
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": body, "more_body": False}
            return await receive()

        await self.app(scope, replay_body, send)


def _directive_now() -> DirectiveOut:
    now = datetime.now(timezone.utc).replace(microsecond=0)
    return DirectiveOut(
        schema_version=1,
        directive_id=DEMO_DIRECTIVE_ID,
        type="start_focus_session",
        title="Avancer 10 minutes sur une tâche",
        reason="Une courte session est une prochaine étape simple.",
        project_id=None,
        estimated_minutes=10,
        choices=list(DIRECTIVE_CHOICES),
        created_at=now,
        expires_at=now + timedelta(days=1),
        requires_explicit_confirmation=False,
    )


def create_app(
    db_path: str | Path | None = None,
    token: str | None = None,
    agent: AgentAdapter | None = None,
    capabilities: dict[str, bool] | None = None,
) -> FastAPI:
    database_path = Path(db_path or os.getenv("ATLAS_DB_PATH", BASE_DIR / "data" / "atlas.sqlite3"))
    store = SQLiteStore(database_path)
    policy = PolicyEngine(capabilities if capabilities is not None else configured_capabilities())
    if agent is not None:
        active_agent = agent
    else:
        adapter_setting = os.getenv("ATLAS_AGENT_ADAPTER", "mock").strip().lower()
        if adapter_setting not in {"mock", "openclaw"}:
            raise ValueError("ATLAS_AGENT_ADAPTER must be mock or openclaw")
        active_agent = OpenClawAgentAdapter() if adapter_setting == "openclaw" else MockAgentAdapter()
    legacy_agent = active_agent if active_agent.status().adapter == "mock" else MockAgentAdapter()
    bearer = HTTPBearer(scheme_name="bearerAuth", auto_error=False)

    app = FastAPI(
        title="ATLAS Core",
        version="2.0.0",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        description=(
            "Backend optionnel de démonstration. Les agents produisent des propositions; "
            "les règles de policy les valident et aucune action Android n'est exécutée ici."
        ),
    )
    app.state.store = store
    app.state.policy = policy
    app.state.agent = active_agent

    @app.exception_handler(StarletteHTTPException)
    async def http_error(_request: Request, exc: StarletteHTTPException) -> JSONResponse:
        code_by_status = {
            401: "authentication_required",
            403: "policy_denied",
            404: "not_found",
            409: "idempotency_conflict",
        }
        message = str(exc.detail)
        code = code_by_status.get(exc.status_code, "request_rejected")
        return JSONResponse(
            status_code=exc.status_code,
            content={
                "contract_version": API_CONTRACT_VERSION,
                "detail": exc.detail,
                "error": {"code": code, "message": message},
            },
            headers=exc.headers,
        )

    @app.exception_handler(RequestValidationError)
    async def contract_error(_request: Request, _exc: RequestValidationError) -> JSONResponse:
        message = "La requête ne respecte pas le contrat ATLAS v1."
        return JSONResponse(
            status_code=422,
            content={
                "contract_version": API_CONTRACT_VERSION,
                "detail": message,
                "error": {"code": "contract_validation_failed", "message": message},
            },
        )

    async def require_local_token(
        credentials: HTTPAuthorizationCredentials | None = Depends(bearer),  # noqa: B008
    ) -> None:
        expected = token if token is not None else os.getenv("ATLAS_TOKEN")
        if not expected:
            raise HTTPException(
                status_code=401,
                detail="ATLAS_TOKEN n'est pas configuré",
                headers={"WWW-Authenticate": "Bearer"},
            )
        if credentials is None or not hmac.compare_digest(credentials.credentials, expected):
            raise HTTPException(
                status_code=401,
                detail="jeton local manquant ou incorrect",
                headers={"WWW-Authenticate": "Bearer"},
            )

    auth = [Depends(require_local_token)]

    app.add_middleware(RequestSizeLimitMiddleware, max_bytes=MAX_REQUEST_BYTES)

    @app.get("/health", tags=["system"])
    def health() -> dict[str, str]:
        return {"status": "ok", "mode": "mock"}

    @app.get("/v1/directives/current", response_model=DirectiveOut, dependencies=auth, tags=["directives"])
    def current_directive() -> DirectiveOut:
        return _directive_now()

    @app.post("/v1/directives/{directive_id}/respond", dependencies=auth, tags=["directives"])
    def respond(
        body: RespondRequest,
        directive_id: Annotated[str, ApiPath(min_length=1, max_length=80)],
    ) -> dict:
        if directive_id != DEMO_DIRECTIVE_ID:
            raise HTTPException(status_code=404, detail="directive inconnue")
        action = CHOICE_ACTIONS[body.choice]
        parameters_by_choice = {
            "start": {"duration_minutes": 10},
            "discuss": {},
            "alternative": {"duration_minutes": 5},
            "postpone": {"delay_minutes": 60},
            "pause": {"duration_minutes": 30},
        }
        proposal = ProposedAction(
            action=action,
            summary=f"Réponse choisie : {body.choice}",
            parameters=parameters_by_choice[body.choice],
        )
        decision = policy.validate(proposal)
        if not decision.accepted:
            raise HTTPException(status_code=403, detail=decision.reason)
        now = datetime.now(timezone.utc).replace(microsecond=0)
        message_by_choice = {
            "start": "Demande de focus prête à être prise en charge par le client.",
            "discuss": "La directive est ouverte à la discussion.",
            "alternative": "Une alternative courte est proposée : 5 minutes.",
            "postpone": "Une demande de report de 60 minutes est proposée au client.",
            "pause": "Une demande de pause de 30 minutes est proposée au client.",
        }
        result = {
            "directive_id": directive_id,
            "choice": body.choice,
            "state": "accepted",
            "message": message_by_choice[body.choice],
            "proposal": proposal.model_dump(mode="json"),
            "policy": {"accepted": True, "reason": decision.reason},
            "action_executed": False,
            "created_at": now.isoformat(),
        }
        return store.save_response(directive_id, body.choice, result)

    def record_event(event: EventRequest) -> tuple[dict, bool]:
        normalized = event.model_dump(mode="json")
        event_id = str(event.event_id)
        normalized["event_id"] = event_id
        result = {
            "event_id": event_id,
            "status": "accepted",
            "recorded_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        }
        try:
            return store.insert_event(normalized, result)
        except EventIdConflict as error:
            raise HTTPException(status_code=409, detail=str(error)) from error

    @app.post("/v1/events", dependencies=auth, tags=["events"])
    def create_event(event: EventRequest) -> dict:
        policy.require_capability("event_ingest")
        result, _inserted = record_event(event)
        return result

    @app.get("/v1/plan/today", dependencies=auth, tags=["plan"])
    def plan_today() -> dict:
        policy.require_capability("focus_sessions")
        directive = _directive_now()
        return {
            "date": datetime.now(timezone.utc).date().isoformat(),
            "items": [
                {
                    "directive_id": directive.directive_id,
                    "title": directive.title,
                    "estimated_minutes": directive.estimated_minutes,
                    "status": "suggested",
                }
            ],
            "source": "mock",
        }

    @app.get("/v1/policy/effective", dependencies=auth, tags=["policy"])
    def effective_policy() -> dict:
        return policy.as_dict()

    @app.get("/openapi.json", dependencies=auth, include_in_schema=False)
    def openapi_document():
        return JSONResponse(app.openapi())

    @app.get("/v1/content/recommendations", dependencies=auth, tags=["content"])
    def recommendations() -> list[dict[str, str]]:
        policy.require_capability("content_recommendations")
        return [item.model_dump() for item in DEMO_CONTENT[:3]]

    @app.post("/v1/coach/message", dependencies=auth, tags=["coach"])
    def coach_message(body: CoachMessageRequest) -> dict:
        policy.require_capability("coach_messages")
        proposal = legacy_agent.propose(
            context=body.message,
            mandate=MANDATE,
            capabilities=policy.capabilities,
        )
        decision = policy.validate(proposal)
        return {
            "mode": "mock",
            "reply": "Je peux proposer une petite étape de concentration.",
            "proposal": proposal.model_dump(mode="json"),
            "policy": {"accepted": decision.accepted, "reason": decision.reason},
            "action_executed": False,
            "openclaw": {"status": "unsupported", "detail": "adaptateur non implémenté"},
        }

    def _utc_now() -> datetime:
        return datetime.now(timezone.utc).replace(microsecond=0)

    def _proposal_ttl() -> timedelta:
        try:
            hours = int(os.getenv("ATLAS_PROPOSAL_TTL_HOURS", "24"))
        except ValueError:
            hours = 24
        return timedelta(hours=max(1, min(24, hours)))

    def _context_version(body: AgentChatRequest) -> str:
        minimized = body.shared_context.minimized()
        encoded = json.dumps(minimized, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
        return "sha256-" + hashlib.sha256(encoded.encode("utf-8")).hexdigest()[:32]

    def _cache_key(route: str, body: AgentChatRequest, conversation: str) -> str:
        material = {
            "route": route,
            "conversation_id": conversation,
            "message": body.message,
            "shared_context": body.shared_context.minimized(),
            "mandate_summary": body.mandate_summary.model_dump(mode="json"),
        }
        encoded = json.dumps(material, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
        return hashlib.sha256(encoded.encode("utf-8")).hexdigest()

    def _run_v2_agent(route: str, body: AgentChatRequest) -> dict:
        policy.require_capability("coach_messages")
        conversation = stable_conversation_id(body.conversation_id)
        now = _utc_now()
        key = _cache_key(route, body, conversation)
        cached = store.get_agent_chat(key, now.isoformat())
        if cached is not None:
            for proposal in cached.get("proposals", []):
                current_state = store.proposal_state(proposal["proposal_id"])
                if current_state is not None:
                    proposal["state"] = current_state
            return cached

        output = active_agent.chat_v2(
            conversation_id=conversation,
            message=body.message,
            shared_context=body.shared_context,
            mandate=body.mandate_summary,
            intent=route,
        )
        status = active_agent.status()
        agent_status = "mock" if status.adapter == "mock" else ("connected" if status.connected else "unavailable")
        proposals: list[dict] = []
        if agent_status != "unavailable":
            context_version = _context_version(body)
            daily_plan_count = 0
            focus_window = body.mandate_summary.focus_window
            if focus_window is not None:
                local_now = now.astimezone(ZoneInfo(focus_window.timezone))
                day_start = datetime.combine(local_now.date(), datetime_time.min, ZoneInfo(focus_window.timezone))
                day_end = datetime.combine(
                    local_now.date() + timedelta(days=1),
                    datetime_time.min,
                    ZoneInfo(focus_window.timezone),
                )
                daily_plan_count = store.focus_plan_count(
                    day_start.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
                    day_end.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
                )
            accepted_focus_refs = store.accepted_focus_refs()
            for candidate in output.proposals:
                decision = policy.validate_v2(
                    candidate,
                    body.mandate_summary,
                    now=now,
                    daily_plan_count=daily_plan_count,
                    accepted_focus_refs=accepted_focus_refs,
                )
                if not decision.accepted:
                    continue
                created_at = now
                expires_at = created_at + _proposal_ttl()
                candidate_material = {
                    "conversation_id": conversation,
                    "mandate_id": body.mandate_summary.id,
                    "mandate_version": body.mandate_summary.version,
                    "action_type": candidate.action_type,
                    "parameters": candidate.parameters.model_dump(mode="json", exclude_none=True),
                    "reason": candidate.reason,
                }
                idem_json = json.dumps(candidate_material, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
                idempotency_key = hashlib.sha256(idem_json.encode("utf-8")).hexdigest()
                proposal = {
                    "schema_version": 2,
                    "proposal_id": str(uuid4()),
                    "idempotency_key": idempotency_key,
                    "conversation_id": conversation,
                    "mandate_id": body.mandate_summary.id,
                    "mandate_version": body.mandate_summary.version,
                    "context_version": context_version,
                    "created_at": created_at.isoformat().replace("+00:00", "Z"),
                    "expires_at": expires_at.isoformat().replace("+00:00", "Z"),
                    "action_type": candidate.action_type,
                    "parameters": candidate.parameters.model_dump(mode="json", exclude_none=True),
                    "reason": candidate.reason,
                    "required_level": policy.required_level(candidate, body.mandate_summary),
                    "state": "proposed",
                }
                validated = V2_PROPOSAL_ADAPTER.validate_json(
                    json.dumps(proposal, ensure_ascii=False, separators=(",", ":"))
                )
                saved, _inserted = store.save_proposal(validated.model_dump(mode="json", exclude_none=True))
                proposals.append(saved)
                if candidate.action_type == "focus.plan":
                    daily_plan_count += 1
        result = {
            "reply": output.reply if agent_status != "unavailable" else "Le service de propositions est indisponible. Aucune action n'a été proposée.",
            "proposals": proposals,
            "agent_status": agent_status,
        }
        if agent_status != "unavailable":
            store.save_agent_chat(key, result, (now + _proposal_ttl()).isoformat().replace("+00:00", "Z"))
        return result

    @app.post("/v2/agent/chat", dependencies=auth, tags=["agent-v2"])
    def agent_chat(body: AgentChatRequest) -> dict:
        return _run_v2_agent("chat", body)

    @app.post("/v2/agent/plan", dependencies=auth, tags=["agent-v2"])
    def agent_plan(body: AgentChatRequest) -> dict:
        return _run_v2_agent("plan", body)

    @app.post("/v3/agent/plan-revision", dependencies=auth, tags=["agent-v3"])
    def agent_plan_revision(body: PlanRevisionRequestV3) -> JSONResponse:
        if not body.plan_context_consented:
            return JSONResponse(
                status_code=403,
                content={
                    "detail": "CONSENT_REQUIRED",
                    "error": {
                        "code": "CONSENT_REQUIRED",
                        "message": "Le contexte du plan n'a pas été consenti.",
                    },
                },
            )
        if not policy.capabilities.get("plan_revision", False):
            return JSONResponse(
                status_code=403,
                content={
                    "detail": "CAPABILITY_DISABLED",
                    "error": {
                        "code": "CAPABILITY_DISABLED",
                        "message": "La capacité plan_revision est désactivée.",
                    },
                },
            )
        try:
            result = propose_plan_revision(
                body,
                adapter=active_agent,
                store=store,
                ttl=_proposal_ttl(),
                now=_utc_now(),
            )
        except PlanRevisionIdempotencyConflict as error:
            raise HTTPException(status_code=409, detail="request_id reused with a different body") from error
        return JSONResponse(status_code=result.status_code, content=result.response)

    @app.get("/v2/agent/inbox", dependencies=auth, tags=["agent-v2"])
    def agent_inbox(after: Annotated[str | None, Query(max_length=80)] = None) -> dict:
        now = _utc_now().isoformat().replace("+00:00", "Z")
        try:
            proposals, cursor = store.inbox(after=after, now=now)
        except UnknownProposalCursor as error:
            raise HTTPException(status_code=400, detail=str(error)) from error
        return {"proposals": proposals, "next_cursor": cursor}

    @app.post("/v2/agent/proposals/{proposal_id}/ack", dependencies=auth, tags=["agent-v2"])
    def acknowledge_proposal(
        body: AckRequest,
        proposal_id: Annotated[str, ApiPath(min_length=36, max_length=36)],
    ) -> dict:
        now = _utc_now().isoformat().replace("+00:00", "Z")
        try:
            result, _inserted = store.acknowledge_proposal(
                proposal_id,
                state=body.state,
                effect=body.effect,
                acknowledged_at=body.at.isoformat().replace("+00:00", "Z"),
                now=now,
            )
        except UnknownProposal as error:
            raise HTTPException(status_code=404, detail=str(error)) from error
        except InvalidProposalTransition as error:
            raise HTTPException(status_code=409, detail=str(error)) from error
        return result

    @app.get("/v2/agent/status", dependencies=auth, tags=["agent-v2"])
    def agent_status() -> dict:
        status = active_agent.status()
        return {
            "adapter": status.adapter,
            "connected": status.connected,
            "breaker": status.breaker,
            "quota": status.quota,
        }

    @app.get("/v2/agent/memory", dependencies=auth, tags=["agent-v2-memory"])
    def get_project_memory() -> dict:
        return {"schema_version": 2, **store.get_project_memory()}

    @app.put("/v2/agent/memory", dependencies=auth, tags=["agent-v2-memory"])
    def put_project_memory(body: ProjectMemory) -> dict:
        value = body.model_dump(mode="json")
        store.set_project_memory(value, _utc_now().isoformat().replace("+00:00", "Z"))
        return {"schema_version": 2, **value}

    @app.delete("/v2/agent/memory/{category}/{entry_id}", dependencies=auth, tags=["agent-v2-memory"])
    def delete_project_memory_entry(
        category: Annotated[Literal["goals", "projects", "availability", "preferences"], ApiPath()],
        entry_id: Annotated[str, ApiPath(min_length=1, max_length=80)],
    ) -> dict:
        deleted = store.delete_memory_entry(category, entry_id, _utc_now().isoformat().replace("+00:00", "Z"))
        if not deleted:
            raise HTTPException(status_code=404, detail="entrée mémoire inconnue")
        return {"deleted": True, "category": category, "id": entry_id}

    @app.delete("/v2/agent/memory", dependencies=auth, tags=["agent-v2-memory"])
    def delete_project_memory() -> dict:
        store.delete_project_memory()
        return {"deleted": True}

    @app.post("/v1/sync", dependencies=auth, tags=["sync"])
    def sync(body: SyncRequest) -> dict:
        policy.require_capability("sync")
        results = []
        inserted_count = 0
        for event in body.events:
            result, inserted = record_event(event)
            results.append(result)
            inserted_count += int(inserted)
        return {
            "contract_version": body.contract_version,
            "status": "ok",
            "received": len(body.events),
            "inserted": inserted_count,
            "duplicates": len(body.events) - inserted_count,
            "results": results,
        }

    @app.get("/v1/export", dependencies=auth, tags=["export"])
    def export_data() -> dict:
        policy.require_capability("export")
        exported = store.export_data()
        return {
            "schema_version": 1,
            "exported_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
            **exported,
        }

    return app


app = create_app()


if __name__ == "__main__":
    uvicorn.run(
        "atlas_backend.main:app",
        host="127.0.0.1",
        port=int(os.getenv("ATLAS_PORT", "8000")),
    )
