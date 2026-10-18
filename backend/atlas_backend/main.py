"""Optional, local-first ATLAS Core API in deterministic mock mode."""

from __future__ import annotations

import hmac
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Annotated, Literal
from uuid import UUID

import uvicorn
from fastapi import Depends, FastAPI, HTTPException, Path as ApiPath
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, Field, StrictStr, field_validator, model_validator
from starlette.types import ASGIApp, Receive, Scope, Send

from .agent import AgentAdapter, MockAgentAdapter, ProposedAction
from .content import DEMO_CONTENT
from .policy import MANDATE, PolicyEngine, configured_capabilities
from .store import SQLiteStore

MAX_REQUEST_BYTES = 65_536
BASE_DIR = Path(__file__).resolve().parents[1]
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

    @model_validator(mode="after")
    def valid_expiry(self) -> "DirectiveOut":
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
                        status_code=413, content={"detail": "requête trop volumineuse"}
                    )(scope, receive, send)
                    return
            except ValueError:
                await JSONResponse(
                    status_code=400, content={"detail": "Content-Length invalide"}
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
                    status_code=413, content={"detail": "requête trop volumineuse"}
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
    policy = PolicyEngine(capabilities or configured_capabilities())
    active_agent = agent or MockAgentAdapter()
    bearer = HTTPBearer(scheme_name="bearerAuth", auto_error=False)

    app = FastAPI(
        title="ATLAS Core (mock)",
        version="1.0.0",
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

    async def require_local_token(
        credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
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
        return store.insert_event(normalized, result)

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
        proposal = active_agent.propose(
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
        host=os.getenv("ATLAS_HOST", "127.0.0.1"),
        port=int(os.getenv("ATLAS_PORT", "8000")),
    )
