from __future__ import annotations

import json as jsonlib
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from fastapi import HTTPException
from fastapi.routing import APIRoute
from pydantic import ValidationError
from starlette.responses import JSONResponse

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from atlas_backend.agent import AgentStatus, MockAgentAdapter
from atlas_backend.main import create_app
from atlas_backend.models_v2 import MandateSummary
from atlas_backend.openclaw_adapter import (
    PLAN_REVISION_SYSTEM_PROMPT,
    OpenClawAgentAdapter,
    build_plan_revision_payload,
)
from atlas_backend.plan_agent_v3 import (
    PlanRevisionAdapterUnavailable,
    PlanRevisionRequestV3,
    PlanRevisionQuotaLimited,
    _canonical_body_hash,
)
from atlas_backend.plan_v3 import (
    PlanProposalEnvelopeV3,
    PlanRevisionCandidateV3,
    validate_plan_revision,
)

PLAN_ID = "8f9a9146-38ca-4bf6-9822-e1e57c5c2f70"
ACTIVITY_ID = "2672ef77-1b16-4f39-97e4-389cafaf7080"
UNKNOWN_ACTIVITY_ID = "71465518-01ad-4871-9f24-d60560d1733e"
REQUEST_ID = "b06bb87c-f47f-40fd-9637-04609b9bb22b"


def revision_output(*, base_version: int = 4, activity_id: str | None = ACTIVITY_ID, extra=None):
    activity = {
        "client_ref": "focus-main",
        "activity_id": activity_id,
        "kind": "FOCUS",
        "title": "Réviser le chapitre",
        "local_date": "2026-10-26",
        "start_minute": 540,
        "duration_minutes": 50,
        "timezone": "Europe/Paris",
        "priority": "IMPORTANT",
        "goal_id": None,
        "sub_goal_id": None,
        "focus": None,
        "training_ref": None,
        "notes": None,
    }
    parameters = {
        "plan_id": PLAN_ID,
        "base_version": base_version,
        "local_date": "2026-10-26",
        "timezone": "Europe/Paris",
        "summary": "Replacer le bloc de révision.",
        "activities": [activity],
    }
    if extra:
        parameters.update(extra)
    return {
        "reply": "Je propose de déplacer le bloc de révision.",
        "proposal": {
            "action_type": "plan.revise_day",
            "reason": "Le créneau proposé correspond à la demande.",
            "parameters": parameters,
        },
    }


class PlanAgent(MockAgentAdapter):
    def __init__(self, output=None, error: Exception | None = None):
        self.output = revision_output() if output is None else output
        self.error = error
        self.calls = 0
        self.last_args = None

    def propose_plan_revision(self, **kwargs):
        self.calls += 1
        self.last_args = kwargs
        if self.error is not None:
            raise self.error
        return self.output

    def status(self) -> AgentStatus:
        return AgentStatus(
            adapter="mock",
            connected=True,
            breaker="closed",
            quota={"calls_last_minute": 0, "daily_tokens": 0, "limited": False},
        )


class FakeHTTPResponse:
    def __init__(self, status_code: int, body: dict):
        self.status_code = status_code
        self._body = body
        self.is_success = 200 <= status_code < 300

    def json(self) -> dict:
        return self._body


class FakeHTTPClient:
    def __init__(self, response: FakeHTTPResponse):
        self.response = response
        self.calls = 0
        self.payload = None

    def post(self, _url, *, headers, content):
        self.calls += 1
        self.payload = jsonlib.loads(content)
        assert headers["Authorization"] == "Bearer fixture-only-token"
        return self.response


class AdapterBackedPlanAgent(PlanAgent):
    def __init__(self, adapter: OpenClawAgentAdapter):
        super().__init__()
        self.adapter = adapter

    def propose_plan_revision(self, **kwargs):
        self.calls += 1
        self.last_args = kwargs
        return self.adapter.propose_plan_revision(**kwargs)


def make_fake_http_adapter(response: FakeHTTPResponse):
    adapter = OpenClawAgentAdapter(
        base_url="http://gateway.invalid",
        token="fixture-only-token",
        daily_tokens=100_000,
    )
    adapter._client.close()
    client = FakeHTTPClient(response)
    adapter._client = client
    return adapter, client


class DirectResponse:
    def __init__(self, status_code: int, body: dict):
        self.status_code = status_code
        self._body = body

    def json(self) -> dict:
        return self._body


class InProcessRouteClient:
    """Invoke the registered synchronous endpoint without TestClient threads."""

    def __init__(self, app):
        self.app = app

    def __enter__(self):
        return self

    def __exit__(self, *_exc):
        return None

    def post(self, path: str, *, headers: dict, json: dict) -> DirectResponse:
        if path != "/v3/agent/plan-revision":
            return DirectResponse(404, {})
        if headers.get("Authorization") != "Bearer local-test":
            return DirectResponse(401, {})
        route = next(
            route
            for route in self.app.routes
            if isinstance(route, APIRoute) and route.path == path and "POST" in route.methods
        )
        try:
            request = PlanRevisionRequestV3.model_validate(json)
        except ValidationError:
            return DirectResponse(422, {})
        try:
            response = route.endpoint(request)
        except HTTPException as error:
            return DirectResponse(error.status_code, {"detail": error.detail})
        if isinstance(response, JSONResponse):
            return DirectResponse(response.status_code, jsonlib.loads(response.body))
        return DirectResponse(200, response)


def request_body(*, request_id: str = REQUEST_ID) -> dict:
    return {
        "schema_version": 3,
        "request_id": request_id,
        "conversation_id": "conversation-fixture-001",
        "message": "Peux-tu déplacer mon bloc de révision ?",
        "plan_context_consented": True,
        "mandate_summary": {
            "id": "mandate-fixture",
            "version": 2,
            "allowed_actions": ["focus.propose"],
        },
        "plan": {
            "plan_id": PLAN_ID,
            "local_date": "2026-10-26",
            "timezone": "Europe/Paris",
            "version": 4,
            "state": "DRAFT",
            "activities": [
                {
                    "activity_id": ACTIVITY_ID,
                    "kind": "FOCUS",
                    "title": "Réviser les notes",
                    "start_minute": 480,
                    "duration_minutes": 50,
                    "priority": "IMPORTANT",
                    "goal_id": None,
                }
            ],
        },
    }


def create_test_client(tmp_path, agent, *, plan_revision_enabled: bool | None = True):
    db_path = tmp_path / "plan-v3.sqlite3"
    capabilities = (
        None if plan_revision_enabled is None else {"plan_revision": plan_revision_enabled}
    )
    app = create_app(db_path=db_path, token="local-test", agent=agent, capabilities=capabilities)
    return app, InProcessRouteClient(app), db_path


def post_revision(client: InProcessRouteClient, body: dict | None = None):
    return client.post(
        "/v3/agent/plan-revision",
        headers={"Authorization": "Bearer local-test"},
        json=request_body() if body is None else body,
    )


def test_valid_request_returns_server_envelope_and_24_hour_expiry(tmp_path):
    agent = PlanAgent()
    app, client, _db = create_test_client(tmp_path, agent)
    with client:
        response = post_revision(client)

    assert response.status_code == 200
    result = response.json()
    assert result["schema_version"] == 3
    assert result["agent_status"] == "ok"
    assert result["reply"] == "Je propose de déplacer le bloc de révision."
    assert result["overlaps"] == []
    envelope = result["proposal"]
    assert envelope["schema_version"] == 3
    assert envelope["proposal_id"] != REQUEST_ID
    assert envelope["idempotency_key"].startswith("v3plan-")
    assert envelope["mandate_id"] == "mandate-fixture"
    assert envelope["mandate_version"] == 2
    created_at = datetime.fromisoformat(envelope["created_at"].replace("Z", "+00:00"))
    expires_at = datetime.fromisoformat(envelope["expires_at"].replace("Z", "+00:00"))
    assert expires_at - created_at == timedelta(hours=24)
    assert envelope["proposal"]["action_type"] == "plan.revise_day"
    assert app.state.store.proposal(envelope["proposal_id"]) is None
    assert agent.calls == 1
    assert agent.last_args["plan"]["plan_id"] == PLAN_ID
    assert agent.last_args["conversation_id"].startswith("conv_")


def test_false_plan_context_consent_returns_403_without_adapter_call(tmp_path):
    agent = PlanAgent()
    _app, client, _db = create_test_client(tmp_path, agent)
    body = request_body()
    body["plan_context_consented"] = False

    with client:
        response = post_revision(client, body)

    assert response.status_code == 403
    assert response.json()["error"]["code"] == "CONSENT_REQUIRED"
    assert agent.calls == 0


@pytest.mark.parametrize("enabled", [None, False])
def test_disabled_plan_revision_returns_capability_error_without_adapter_call(
    tmp_path, monkeypatch, enabled
):
    monkeypatch.delenv("ATLAS_CAPABILITIES_JSON", raising=False)
    agent = PlanAgent()
    _app, client, _db = create_test_client(
        tmp_path,
        agent,
        plan_revision_enabled=enabled,
    )

    with client:
        response = post_revision(client)

    assert response.status_code == 403
    assert response.json()["error"]["code"] == "CAPABILITY_DISABLED"
    assert agent.calls == 0


def test_environment_capability_override_activates_endpoint(tmp_path, monkeypatch):
    monkeypatch.setenv("ATLAS_CAPABILITIES_JSON", '{"plan_revision": true}')
    agent = PlanAgent()
    _app, client, _db = create_test_client(tmp_path, agent, plan_revision_enabled=None)

    with client:
        response = post_revision(client)

    assert response.status_code == 200
    assert response.json()["agent_status"] == "ok"
    assert agent.calls == 1


def test_stale_base_version_is_rejected_as_invalid_output(tmp_path):
    agent = PlanAgent(output=revision_output(base_version=3))
    _app, client, _db = create_test_client(tmp_path, agent)

    with client:
        response = post_revision(client)

    result = response.json()
    assert response.status_code == 200
    assert result["agent_status"] == "invalid_output"
    assert result["proposal"] is None
    assert "version périmée" in result["reply"]


def test_unknown_activity_id_is_rejected(tmp_path):
    agent = PlanAgent(output=revision_output(activity_id=UNKNOWN_ACTIVITY_ID))
    _app, client, _db = create_test_client(tmp_path, agent)

    with client:
        response = post_revision(client)

    result = response.json()
    assert response.status_code == 200
    assert result["agent_status"] == "invalid_output"
    assert result["proposal"] is None
    assert "activity_id inconnu" in result["reply"]


def test_overlaps_are_reported_and_activities_are_preserved(tmp_path):
    output = revision_output()
    first = output["proposal"]["parameters"]["activities"][0]
    second = dict(first)
    second.update(
        {
            "client_ref": "new-overlap",
            "activity_id": None,
            "title": "Pause proposée",
            "start_minute": 560,
            "duration_minutes": 20,
        }
    )
    output["proposal"]["parameters"]["activities"].append(second)
    agent = PlanAgent(output=output)
    _app, client, _db = create_test_client(tmp_path, agent)

    with client:
        response = post_revision(client)

    result = response.json()
    assert response.status_code == 200
    assert result["overlaps"] == [["focus-main", "new-overlap"]]
    assert len(result["proposal"]["proposal"]["parameters"]["activities"]) == 2


def test_unavailable_adapter_returns_503_and_is_idempotent(tmp_path):
    agent = PlanAgent(error=PlanRevisionAdapterUnavailable("synthetic unavailable"))
    _app, client, _db = create_test_client(tmp_path, agent)

    with client:
        first = post_revision(client)
        replay = post_revision(client)

    assert first.status_code == replay.status_code == 503
    assert first.json() == replay.json()
    assert first.json()["agent_status"] == "unavailable"
    assert first.json()["proposal"] is None
    assert agent.calls == 1


def test_invalid_model_output_returns_200_without_proposal(tmp_path):
    agent = PlanAgent(output='{"reply":"oops","proposal":{"action_type":"unknown"}}')
    _app, client, _db = create_test_client(tmp_path, agent)

    with client:
        response = post_revision(client)

    assert response.status_code == 200
    assert response.json()["agent_status"] == "invalid_output"
    assert response.json()["proposal"] is None
    assert "invalide" in response.json()["reply"]


def test_non_null_focus_is_rejected_without_proposal(tmp_path):
    output = revision_output()
    output["proposal"]["parameters"]["activities"][0]["focus"] = {
        "focus_minutes": 25,
        "break_minutes": 5,
        "cycles": 1,
    }
    agent = PlanAgent(output=output)
    _app, client, _db = create_test_client(tmp_path, agent)

    with client:
        response = post_revision(client)

    assert response.status_code == 200
    assert response.json()["agent_status"] == "invalid_output"
    assert response.json()["proposal"] is None


def test_non_null_training_ref_is_rejected_without_proposal(tmp_path):
    output = revision_output()
    output["proposal"]["parameters"]["activities"][0]["training_ref"] = {
        "provider": "wger",
        "routine_id": 1,
    }
    agent = PlanAgent(output=output)
    _app, client, _db = create_test_client(tmp_path, agent)

    with client:
        response = post_revision(client)

    assert response.status_code == 200
    assert response.json()["agent_status"] == "invalid_output"
    assert response.json()["proposal"] is None


def test_quota_limited_status_is_explicit(tmp_path):
    agent = PlanAgent(error=PlanRevisionQuotaLimited("synthetic quota"))
    _app, client, _db = create_test_client(tmp_path, agent)

    with client:
        response = post_revision(client)

    assert response.status_code == 200
    assert response.json()["agent_status"] == "quota_limited"
    assert response.json()["proposal"] is None


def test_same_request_and_body_returns_durable_response_after_restart(tmp_path):
    first_agent = PlanAgent()
    app, client, db_path = create_test_client(tmp_path, first_agent)
    with client:
        first = post_revision(client)

    next_agent = PlanAgent()
    restarted_app = create_app(
        db_path=db_path,
        token="local-test",
        agent=next_agent,
        capabilities={"plan_revision": True},
    )
    restarted_client = InProcessRouteClient(restarted_app)
    with restarted_client:
        replay = post_revision(restarted_client)

    assert first.status_code == replay.status_code == 200
    assert first.json() == replay.json()
    assert first_agent.calls == 1
    assert next_agent.calls == 0
    assert app.state.store.path == db_path


def test_pending_idempotency_reservation_after_crash_never_calls_adapter(tmp_path):
    agent = PlanAgent()
    app, client, _db = create_test_client(tmp_path, agent)
    request = PlanRevisionRequestV3.model_validate(request_body())
    app.state.store.claim_plan_revision_request(
        request.request_id.lower(),
        _canonical_body_hash(request),
        "2026-10-26T12:00:00Z",
    )

    with client:
        first = post_revision(client)
        replay = post_revision(client)

    assert first.status_code == replay.status_code == 503
    assert first.json() == replay.json()
    assert first.json()["agent_status"] == "unavailable"
    assert "Aucune seconde proposition" in first.json()["reply"]
    assert agent.calls == 0


def test_same_request_id_with_different_body_returns_409(tmp_path):
    agent = PlanAgent()
    _app, client, _db = create_test_client(tmp_path, agent)
    changed = request_body()
    changed["message"] = "Autre demande pour le même identifiant"

    with client:
        first = post_revision(client)
        conflict = post_revision(client, changed)

    assert first.status_code == 200
    assert conflict.status_code == 409
    assert agent.calls == 1


def test_unknown_request_fields_are_rejected_with_422(tmp_path):
    agent = PlanAgent()
    _app, client, _db = create_test_client(tmp_path, agent)
    body = request_body()
    body["unexpected"] = "field"

    with client:
        response = post_revision(client, body)

    assert response.status_code == 422
    assert agent.calls == 0


def test_openclaw_plan_revision_payload_has_no_tools_field():
    body = request_body()
    payload = build_plan_revision_payload(
        agent_id="atlas",
        conversation_id="conv_fixture",
        message=body["message"],
        plan=body["plan"],
        mandate=MandateSummary.model_validate(body["mandate_summary"]),
        max_tokens=1200,
    )

    assert "tools" not in payload
    assert payload["messages"][0]["content"] != ""
    assert "plan.revise_day" in payload["messages"][0]["content"]
    assert jsonlib.loads(payload["messages"][1]["content"])["plan"]["plan_id"] == PLAN_ID


def test_plan_revision_prompt_disallows_focus_and_training_refs():
    instruction = (
        "Dans ce lot, focus et training_ref doivent toujours être null ; ne les renseigne pas."
    )
    assert instruction in PLAN_REVISION_SYSTEM_PROMPT


@pytest.mark.parametrize(
    ("fake_response", "expected_http_status", "expected_agent_status"),
    [
        (FakeHTTPResponse(429, {}), 200, "quota_limited"),
        (FakeHTTPResponse(500, {}), 503, "unavailable"),
        (
            FakeHTTPResponse(
                200,
                {"choices": [{"message": {"content": "not-json"}}]},
            ),
            200,
            "invalid_output",
        ),
    ],
    ids=["quota-429", "server-500", "invalid-json"],
)
def test_fake_openclaw_http_responses_map_to_agent_status(
    tmp_path, fake_response, expected_http_status, expected_agent_status
):
    adapter, fake_client = make_fake_http_adapter(fake_response)
    agent = AdapterBackedPlanAgent(adapter)
    _app, client, _db = create_test_client(tmp_path, agent)

    with client:
        response = post_revision(client)

    assert response.status_code == expected_http_status
    assert response.json()["agent_status"] == expected_agent_status
    assert response.json()["proposal"] is None
    assert fake_client.calls == 1
    assert "tools" not in fake_client.payload


def test_expired_plan_revision_is_rejected():
    now = datetime(2026, 10, 26, 12, 0, tzinfo=timezone.utc)
    candidate = PlanRevisionCandidateV3.model_validate(revision_output()["proposal"])
    envelope = PlanProposalEnvelopeV3(
        proposal_id="f8d56ea4-d6e7-40e8-b5a4-12535b218ef1",
        idempotency_key="expired-fixture",
        conversation_id=None,
        mandate_id="mandate-fixture",
        mandate_version=2,
        created_at=now - timedelta(days=2),
        expires_at=now - timedelta(days=1),
        proposal=candidate,
    )

    decision = validate_plan_revision(
        envelope,
        plan_id=PLAN_ID,
        current_version=4,
        known_activity_ids={ACTIVITY_ID},
        now=now,
    )
    assert not decision.accepted
    assert decision.reason == "proposal expirée"
