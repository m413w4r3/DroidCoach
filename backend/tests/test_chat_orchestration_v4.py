from __future__ import annotations

import asyncio
import hashlib
import json
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
import pytest
from atlas_backend.main import create_app
from atlas_backend.openclaw_adapter import OpenClawAgentAdapter
from atlas_backend.v4_tool_service import V4ToolError, V4ToolService
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts" / "v4"
ANDROID_TOKEN = "synthetic-android-token"
REQUEST_ID = "00000000-0000-4000-8000-000000000101"
PLAN_ID = "00000000-0000-4000-8000-000000000201"
REVISION_ID = "00000000-0000-4000-8000-000000000202"
ACTIVITY_ID = "00000000-0000-4000-8000-000000000203"

CAPABILITIES = {
    "day_plan.read": True,
    "activity_status.read": True,
    "plan_revision.propose": True,
    "usage_summary.read": True,
    "goal_progress.read": True,
    "training_plan.read": True,
    "coach.chat": True,
}


class ASGITestClient:
    def __init__(self, app):
        self.app = app

    def post(self, url: str, **kwargs):
        async def send():
            transport = httpx.ASGITransport(app=self.app, client=("127.0.0.1", 51400))
            async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
                return await client.post(url, **kwargs)
        return asyncio.run(send())

    def post_timed(self, url: str, **kwargs):
        async def send():
            transport = httpx.ASGITransport(app=self.app, client=("127.0.0.1", 51400))
            async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
                started_at = time.monotonic()
                response = await client.post(url, **kwargs)
                return response, time.monotonic() - started_at
        return asyncio.run(send())


class SlowBodyStream(httpx.SyncByteStream):
    def __init__(self, body: bytes, delay_s: float, phases: list[str]):
        self.body = body
        self.delay_s = delay_s
        self.phases = phases

    def __iter__(self):
        self.phases.append("body")
        time.sleep(self.delay_s)
        yield self.body


def stamp() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def schema(name: str) -> dict:
    return json.loads((CONTRACTS / name).read_text(encoding="utf-8"))


def validate(name: str, value: dict) -> None:
    Draft202012Validator(schema(name), format_checker=FormatChecker()).validate(value)


def chat_body(*, request_id: str = REQUEST_ID, shared_categories: list[str] | None = None) -> dict:
    return {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": request_id,
        "capability": "coach.chat",
        "sent_at": stamp(),
        "conversation_id": "synthetic-conversation",
        "message": "synthetic-secret-phrase: aide-moi à choisir une étape",
        "consent_version": 1,
        "shared_categories": shared_categories or [],
    }


def make_app(tmp_path: Path, monkeypatch, handler, *, capabilities=None, daily_tokens=100_000):
    active_capabilities = dict(CAPABILITIES if capabilities is None else capabilities)
    monkeypatch.setenv("ATLAS_V4_CAPABILITIES_JSON", json.dumps(active_capabilities))
    monkeypatch.setenv("ATLAS_TOOL_TOKEN", "synthetic-tool-token")
    adapter = OpenClawAgentAdapter(
        base_url="http://provider.invalid",
        token="synthetic-provider-token",
        timeout_s=1,
        daily_tokens=daily_tokens,
        transport=httpx.MockTransport(handler),
    )
    return create_app(
        db_path=tmp_path / "atlas.sqlite3",
        token=ANDROID_TOKEN,
        agent=adapter,
    ), adapter


def device_ref() -> str:
    return "android-" + hashlib.sha256(ANDROID_TOKEN.encode()).hexdigest()[:32]


def grant_category(app, category: str, version: int = 1) -> None:
    app.state.v4_tool_service.projections.save_consent_state(
        device_ref(),
        category,
        version,
        "GRANTED",
        idempotency_key=str(uuid.uuid4()),
    )


def response_with_tool_calls(*calls: dict) -> dict:
    return {
        "choices": [{
            "finish_reason": "tool_calls",
            "message": {"role": "assistant", "content": None, "tool_calls": list(calls)},
        }],
    }


def tool_call(call_id: str, name: str, arguments: str) -> dict:
    return {
        "id": call_id,
        "type": "function",
        "function": {"name": name, "arguments": arguments},
    }


def final_response(content: str = "Réponse synthétique.") -> dict:
    return {"choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": content}}]}


def post_chat(client: ASGITestClient, body: dict):
    return client.post(
        "/v4/agent/chat",
        headers={"Authorization": f"Bearer {ANDROID_TOKEN}"},
        json=body,
    )


def test_v4_chat_sends_only_six_tools_and_returns_exact_tool_call_id(tmp_path, monkeypatch, caplog):
    provider_requests = []
    replies = [
        response_with_tool_calls(tool_call("call-exact-id", "atlas_get_day_plan", "{}")),
        final_response("Aucun plan n’est synchronisé."),
    ]

    def provider(request: httpx.Request) -> httpx.Response:
        provider_requests.append(json.loads(request.content))
        return httpx.Response(200, json=replies.pop(0))

    app, _adapter = make_app(tmp_path, monkeypatch, provider)
    grant_category(app, "DAY_PLAN")
    response = post_chat(ASGITestClient(app), chat_body(shared_categories=["DAY_PLAN"]))

    assert response.status_code == 200
    result = response.json()
    validate("chat-request.schema.json", chat_body(shared_categories=["DAY_PLAN"]))
    validate("chat-response.schema.json", result)
    assert result["tool_state"] == "completed"
    assert result["tool_call_count"] == 1
    assert len(provider_requests) == 2
    first = provider_requests[0]
    assert [item["function"]["name"] for item in first["tools"]] == [
        "atlas_get_day_plan",
        "atlas_get_activity_status",
        "atlas_propose_plan_revision",
        "atlas_get_usage_summary",
        "atlas_get_goal_progress",
        "atlas_get_training_plan",
    ]
    assert first["tool_choice"] == "auto"
    assert first["parallel_tool_calls"] is False
    followup_messages = provider_requests[1]["messages"]
    assistant_message = next(item for item in followup_messages if item["role"] == "assistant")
    tool_message = next(item for item in followup_messages if item["role"] == "tool")
    assert assistant_message["tool_calls"][0]["id"] == "call-exact-id"
    assert tool_message["tool_call_id"] == "call-exact-id"
    assert json.loads(tool_message["content"])["data"]["state"] == "NO_PLAN"
    assert "synthetic-secret-phrase" not in caplog.text
    assert "synthetic-provider-token" not in caplog.text


@pytest.mark.parametrize(
    "calls",
    [
        [{"type": "function", "function": {"name": "atlas_get_day_plan", "arguments": "{}"}}],
        [tool_call("call-non-printable\n", "atlas_get_day_plan", "{}")],
        [tool_call("same-id", "atlas_get_day_plan", "{}"), tool_call("same-id", "atlas_get_goal_progress", "{}")],
        [{"id": "call-wrong-type", "type": "custom", "function": {"name": "atlas_get_day_plan", "arguments": "{}"}}],
        {"id": "call-function-not-object", "type": "function", "function": []},
        [{"id": "call-name-not-string", "type": "function", "function": {"name": None, "arguments": "{}"}}],
        [{"id": "call-arguments-not-string", "type": "function", "function": {"name": "atlas_get_day_plan", "arguments": {}}}],
    ],
    ids=["id-absent", "id-non-printable", "id-duplicate", "type-invalid", "function-invalid", "name-not-string", "arguments-not-string"],
)
def test_invalid_provider_tool_calls_are_rejected_before_dispatch(tmp_path, monkeypatch, calls):
    provider_count = 0

    def provider(_request: httpx.Request) -> httpx.Response:
        nonlocal provider_count
        provider_count += 1
        return httpx.Response(200, json=response_with_tool_calls(*calls))

    app, _adapter = make_app(tmp_path, monkeypatch, provider)
    # The request has no shared categories, and no tool can run before validation.
    response = post_chat(ASGITestClient(app), chat_body())
    assert response.status_code == 503
    validate("chat-response.schema.json", response.json())
    assert response.json()["agent_status"] == "unavailable"
    assert response.json()["tool_call_count"] == len(calls)
    assert provider_count == 1


@pytest.mark.parametrize("calls", [None, "not-a-list", []], ids=["null", "not-a-list", "empty"])
def test_invalid_provider_tool_call_lot_is_rejected(tmp_path, monkeypatch, calls):
    def provider(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"choices": [{
            "finish_reason": "stop",
            "message": {"role": "assistant", "content": "Ne pas accepter cette réponse.", "tool_calls": calls},
        }]})

    app, _adapter = make_app(tmp_path, monkeypatch, provider)
    response = post_chat(ASGITestClient(app), chat_body())
    assert response.status_code == 503
    validate("error.schema.json", response.json())
    assert response.json()["error"]["code"] == "UPSTREAM_UNAVAILABLE"


def test_unknown_tool_returns_bounded_tool_error_and_model_can_correct(tmp_path, monkeypatch):
    requests = []
    replies = [
        response_with_tool_calls(tool_call("call-unknown", "atlas_run_shell", '{"private":"secret"}')),
        response_with_tool_calls(tool_call("call-corrected", "atlas_get_day_plan", "{}")),
        final_response("Aucune action n’a été exécutée."),
    ]
    dispatched = []
    original_get_day_plan = V4ToolService.atlas_get_day_plan

    def observed_get_day_plan(service, device: str, arguments: dict) -> dict:
        dispatched.append(("atlas_get_day_plan", arguments))
        return original_get_day_plan(service, device, arguments)

    monkeypatch.setattr(V4ToolService, "atlas_get_day_plan", observed_get_day_plan)

    def provider(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return httpx.Response(200, json=replies.pop(0))

    app, _adapter = make_app(tmp_path, monkeypatch, provider)
    grant_category(app, "DAY_PLAN")
    response = post_chat(ASGITestClient(app), chat_body(shared_categories=["DAY_PLAN"]))

    assert response.status_code == 200
    assert response.json()["tool_call_count"] == 2
    assert response.json()["tool_state"] == "completed"
    assert len(requests) == 3
    messages = requests[2]["messages"]
    assistant_calls = [call for message in messages if message["role"] == "assistant" for call in message["tool_calls"]]
    tool_messages = [message for message in messages if message["role"] == "tool"]
    assert [call["id"] for call in assistant_calls] == ["call-unknown", "call-corrected"]
    assert assistant_calls[0] == tool_call("call-unknown", "atlas_run_shell", '{"private":"secret"}')
    assert [message["tool_call_id"] for message in tool_messages] == ["call-unknown", "call-corrected"]
    unknown_error = json.loads(tool_messages[0]["content"])
    validate("error.schema.json", unknown_error)
    assert unknown_error["error"]["code"] == "BAD_REQUEST"
    assert unknown_error["error"]["capability"] is None
    assert "secret" not in tool_messages[0]["content"]
    assert json.loads(tool_messages[1]["content"])["data"]["state"] == "NO_PLAN"
    assert [item[0] for item in dispatched] == ["atlas_get_day_plan"]


@pytest.mark.parametrize(
    "malformed_arguments",
    ["{bad json", '{"page_size":1,"page_size":2}', "NaN"],
    ids=["syntax", "duplicate-key", "non-standard-number"],
)
def test_malformed_tool_arguments_return_error_then_allow_correction(
    tmp_path, monkeypatch, malformed_arguments
):
    requests = []
    replies = [
        response_with_tool_calls(tool_call("call-malformed", "atlas_get_day_plan", malformed_arguments)),
        response_with_tool_calls(tool_call("call-corrected", "atlas_get_day_plan", "{}")),
        final_response(),
    ]
    dispatched = []
    original_get_day_plan = V4ToolService.atlas_get_day_plan

    def observed_get_day_plan(service, device: str, arguments: dict) -> dict:
        dispatched.append(arguments)
        return original_get_day_plan(service, device, arguments)

    monkeypatch.setattr(V4ToolService, "atlas_get_day_plan", observed_get_day_plan)

    def provider(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return httpx.Response(200, json=replies.pop(0))

    app, _adapter = make_app(tmp_path, monkeypatch, provider)
    grant_category(app, "DAY_PLAN")
    response = post_chat(ASGITestClient(app), chat_body(shared_categories=["DAY_PLAN"]))

    assert response.status_code == 200
    assert response.json()["tool_call_count"] == 2
    messages = requests[2]["messages"]
    assistant_calls = [call for message in messages if message["role"] == "assistant" for call in message["tool_calls"]]
    tool_messages = [message for message in messages if message["role"] == "tool"]
    assert assistant_calls[0]["function"]["arguments"] == malformed_arguments
    assert [message["tool_call_id"] for message in tool_messages] == ["call-malformed", "call-corrected"]
    malformed_error = json.loads(tool_messages[0]["content"])
    validate("error.schema.json", malformed_error)
    assert malformed_error["error"]["code"] == "BAD_REQUEST"
    assert malformed_error["error"]["capability"] == "day_plan.read"
    assert len(dispatched) == 1


def test_schema_invalid_tool_arguments_return_error_then_allow_correction(tmp_path, monkeypatch):
    requests = []
    replies = [
        response_with_tool_calls(tool_call("call-schema-invalid", "atlas_get_activity_status", "{}")),
        response_with_tool_calls(tool_call(
            "call-corrected", "atlas_get_activity_status", json.dumps({"activity_id": ACTIVITY_ID})
        )),
        final_response(),
    ]
    dispatched = []
    original_get_activity_status = V4ToolService.atlas_get_activity_status

    def observed_get_activity_status(service, device: str, arguments: dict) -> dict:
        dispatched.append(arguments)
        return original_get_activity_status(service, device, arguments)

    monkeypatch.setattr(V4ToolService, "atlas_get_activity_status", observed_get_activity_status)

    def provider(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return httpx.Response(200, json=replies.pop(0))

    app, _adapter = make_app(tmp_path, monkeypatch, provider)
    seed_day_plan(app)
    grant_category(app, "ACTIVITY_STATUS")
    response = post_chat(ASGITestClient(app), chat_body(shared_categories=["ACTIVITY_STATUS", "DAY_PLAN"]))

    assert response.status_code == 200
    assert response.json()["tool_call_count"] == 2
    messages = requests[2]["messages"]
    tool_messages = [message for message in messages if message["role"] == "tool"]
    schema_error = json.loads(tool_messages[0]["content"])
    validate("error.schema.json", schema_error)
    assert schema_error["error"]["code"] == "BAD_REQUEST"
    assert schema_error["error"]["capability"] == "activity_status.read"
    assert [message["tool_call_id"] for message in tool_messages] == ["call-schema-invalid", "call-corrected"]
    assert len(dispatched) == 1


def test_mixed_valid_and_invalid_tool_calls_preserve_ids_order_and_arguments(tmp_path, monkeypatch):
    requests = []
    calls = [
        tool_call("call-valid", "atlas_get_day_plan", '{"page_size":2}'),
        tool_call("call-invalid", "atlas_unknown", '{"raw":"private"}'),
    ]
    replies = [response_with_tool_calls(*calls), final_response()]

    def provider(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return httpx.Response(200, json=replies.pop(0))

    app, _adapter = make_app(tmp_path, monkeypatch, provider)
    grant_category(app, "DAY_PLAN")
    response = post_chat(ASGITestClient(app), chat_body(shared_categories=["DAY_PLAN"]))

    assert response.status_code == 200
    assert response.json()["tool_call_count"] == 2
    messages = requests[1]["messages"]
    assistant_message = next(message for message in messages if message["role"] == "assistant")
    tool_messages = [message for message in messages if message["role"] == "tool"]
    assert assistant_message["tool_calls"] == calls
    assert [message["tool_call_id"] for message in tool_messages] == ["call-valid", "call-invalid"]
    assert json.loads(tool_messages[0]["content"])["data"]["state"] == "NO_PLAN"
    invalid_error = json.loads(tool_messages[1]["content"])
    validate("error.schema.json", invalid_error)
    assert invalid_error["error"]["code"] == "BAD_REQUEST"
    assert invalid_error["error"]["capability"] is None
    assert "private" not in tool_messages[1]["content"]


def test_invalid_proposal_does_not_consume_proposal_quota_or_create_proposal(tmp_path, monkeypatch):
    requests = []
    valid_proposal = {
        "plan_id": PLAN_ID,
        "base_version": 7,
        "changes": [{"op": "reschedule", "activity_id": ACTIVITY_ID, "target_start_minute": 660}],
        "justification": "Ajustement synthétique.",
    }
    replies = [
        response_with_tool_calls(tool_call("proposal-invalid", "atlas_propose_plan_revision", "{}")),
        response_with_tool_calls(tool_call("proposal-valid", "atlas_propose_plan_revision", json.dumps(valid_proposal))),
        final_response("Une proposition attend ta revue."),
    ]

    def provider(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return httpx.Response(200, json=replies.pop(0))

    app, _adapter = make_app(tmp_path, monkeypatch, provider)
    seed_day_plan(app)
    response = post_chat(ASGITestClient(app), chat_body(shared_categories=["DAY_PLAN"]))

    assert response.status_code == 200
    assert response.json()["tool_call_count"] == 2
    assert len(response.json()["proposal_ids"]) == 1
    messages = requests[2]["messages"]
    tool_messages = [message for message in messages if message["role"] == "tool"]
    invalid_error = json.loads(tool_messages[0]["content"])
    validate("error.schema.json", invalid_error)
    assert invalid_error["error"]["code"] == "BAD_REQUEST"
    assert invalid_error["error"]["capability"] == "plan_revision.propose"
    assert [message["tool_call_id"] for message in tool_messages] == ["proposal-invalid", "proposal-valid"]
    with app.state.store.connect() as connection:
        rows = connection.execute("SELECT proposal_json FROM agent_proposals_v4").fetchall()
    assert len(rows) == 1
    assert json.loads(rows[0]["proposal_json"])["proposal_id"] == response.json()["proposal_ids"][0]


def test_six_invalid_tool_calls_exhaust_quota_without_inventing_a_final_reply(tmp_path, monkeypatch):
    requests = []
    replies = [
        response_with_tool_calls(tool_call(f"invalid-{index}", "atlas_unknown", "{}"))
        for index in range(1, 7)
    ]
    replies.append(response_with_tool_calls(tool_call("over-limit", "atlas_unknown", "{}")))

    def provider(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return httpx.Response(200, json=replies.pop(0))

    app, _adapter = make_app(tmp_path, monkeypatch, provider)
    response = post_chat(ASGITestClient(app), chat_body())

    assert response.status_code == 429
    validate("chat-response.schema.json", response.json())
    assert response.json()["agent_status"] == "unavailable"
    assert response.json()["tool_call_count"] == 6
    assert len(requests) == 7
    prior_messages = requests[-1]["messages"]
    assistant_calls = [call for message in prior_messages if message["role"] == "assistant" for call in message["tool_calls"]]
    tool_messages = [message for message in prior_messages if message["role"] == "tool"]
    assert [call["id"] for call in assistant_calls] == [f"invalid-{index}" for index in range(1, 7)]
    assert [message["tool_call_id"] for message in tool_messages] == [f"invalid-{index}" for index in range(1, 7)]
    assert all(json.loads(message["content"])["error"]["code"] == "BAD_REQUEST" for message in tool_messages)


def test_chat_request_rejects_extra_fields_and_provider_invalid_response(tmp_path, monkeypatch):
    provider_count = 0

    def provider(_request: httpx.Request) -> httpx.Response:
        nonlocal provider_count
        provider_count += 1
        return httpx.Response(200, json={"choices": []})

    app, _adapter = make_app(tmp_path, monkeypatch, provider)
    client = ASGITestClient(app)
    invalid_request = chat_body()
    invalid_request["extra"] = True
    invalid = post_chat(client, invalid_request)
    assert invalid.status_code == 400
    validate("error.schema.json", invalid.json())
    assert provider_count == 0

    invalid_provider = post_chat(client, chat_body(request_id="00000000-0000-4000-8000-000000000102"))
    assert invalid_provider.status_code == 503
    validate("error.schema.json", invalid_provider.json())
    assert invalid_provider.json()["error"]["code"] == "UPSTREAM_UNAVAILABLE"
    assert provider_count == 1


def test_request_limit_is_65536_bytes(tmp_path, monkeypatch):
    def provider(_request: httpx.Request) -> httpx.Response:
        raise AssertionError("oversized request must be rejected before contacting the provider")

    app, _adapter = make_app(tmp_path, monkeypatch, provider)
    response = ASGITestClient(app).post(
        "/v4/agent/chat",
        headers={"Authorization": f"Bearer {ANDROID_TOKEN}"},
        content=b"x" * 65_537,
    )
    assert response.status_code == 413
    validate("error.schema.json", response.json())
    assert response.json()["error"]["code"] == "PAYLOAD_TOO_LARGE"


def test_six_tool_and_one_proposal_quotas_are_enforced_before_dispatch(tmp_path, monkeypatch):
    provider_count = 0

    # A seventh tool call is rejected as a whole before any call can run.
    seven_calls = [tool_call(f"call-{index}", "atlas_get_day_plan", "{}") for index in range(7)]

    def seven_tool_provider(_request: httpx.Request) -> httpx.Response:
        nonlocal provider_count
        provider_count += 1
        return httpx.Response(200, json=response_with_tool_calls(*seven_calls))

    app, _adapter = make_app(tmp_path / "calls", monkeypatch, seven_tool_provider)
    too_many_calls = post_chat(ASGITestClient(app), chat_body())
    assert too_many_calls.status_code == 429
    validate("chat-response.schema.json", too_many_calls.json())
    assert too_many_calls.json()["agent_status"] == "unavailable"
    assert too_many_calls.json()["tool_call_count"] == 6
    assert provider_count == 1

    proposal_args = json.dumps({
        "plan_id": PLAN_ID,
        "base_version": 7,
        "changes": [{"op": "reschedule", "activity_id": ACTIVITY_ID, "target_start_minute": 660}],
        "justification": "Ajustement synthétique.",
    })
    two_proposals = [
        tool_call("proposal-1", "atlas_propose_plan_revision", proposal_args),
        tool_call("proposal-2", "atlas_propose_plan_revision", proposal_args),
    ]

    def proposal_provider(_request: httpx.Request) -> httpx.Response:
        nonlocal provider_count
        provider_count += 1
        return httpx.Response(200, json=response_with_tool_calls(*two_proposals))

    app, _adapter = make_app(tmp_path / "proposals", monkeypatch, proposal_provider)
    too_many_proposals = post_chat(ASGITestClient(app), chat_body())
    assert too_many_proposals.status_code == 429
    validate("chat-response.schema.json", too_many_proposals.json())
    assert too_many_proposals.json()["agent_status"] == "unavailable"
    assert too_many_proposals.json()["tool_call_count"] == 2
    assert provider_count == 2


def test_mock_chat_is_deterministic_and_offline(tmp_path, monkeypatch):
    monkeypatch.setenv("ATLAS_V4_CAPABILITIES_JSON", json.dumps(CAPABILITIES))
    monkeypatch.setenv("ATLAS_TOOL_TOKEN", "synthetic-tool-token")
    app = create_app(db_path=tmp_path / "atlas.sqlite3", token=ANDROID_TOKEN)
    body = chat_body()
    client = ASGITestClient(app)
    first = post_chat(client, body)
    replay = post_chat(client, body)
    assert first.status_code == replay.status_code == 200
    validate("chat-response.schema.json", first.json())
    assert first.json() == replay.json()
    assert first.json()["agent_status"] == "mock"
    assert first.json()["tool_state"] == "none"
    assert first.json()["tool_call_count"] == 0


@pytest.mark.parametrize(
    ("handler_kind", "daily_tokens", "expected_status", "expected_code"),
    [
        ("timeout", 100_000, 504, "TIMEOUT"),
        ("quota", 0, 429, "QUOTA_EXCEEDED"),
        ("oversized", 100_000, 413, "PAYLOAD_TOO_LARGE"),
    ],
)
def test_provider_timeout_quota_and_response_size_are_bounded(
    tmp_path, monkeypatch, handler_kind, daily_tokens, expected_status, expected_code
):
    def provider(request: httpx.Request) -> httpx.Response:
        if handler_kind == "timeout":
            raise httpx.ReadTimeout("synthetic timeout", request=request)
        if handler_kind == "oversized":
            return httpx.Response(200, content=b"x" * (262_144 + 1))
        return httpx.Response(200, json=final_response())

    app, _adapter = make_app(tmp_path, monkeypatch, provider, daily_tokens=daily_tokens)
    response = post_chat(ASGITestClient(app), chat_body())
    assert response.status_code == expected_status
    validate("error.schema.json", response.json())
    assert response.json()["error"]["code"] == expected_code


def test_wall_deadline_covers_slow_provider_phases_and_blocks_late_tools(
    tmp_path, monkeypatch
):
    from atlas_backend import v4_router

    monkeypatch.setattr(v4_router, "CHAT_V4_DEADLINE_SECONDS", 0.4)
    phases = []
    tool_dispatches = []
    original_get_day_plan = V4ToolService.atlas_get_day_plan

    def observed_get_day_plan(service, device: str, arguments: dict) -> dict:
        tool_dispatches.append("atlas_get_day_plan")
        return original_get_day_plan(service, device, arguments)

    monkeypatch.setattr(V4ToolService, "atlas_get_day_plan", observed_get_day_plan)

    def provider(_request: httpx.Request) -> httpx.Response:
        phases.append("connect")
        time.sleep(0.12)
        phases.append("headers")
        time.sleep(0.12)
        body = json.dumps(response_with_tool_calls(
            tool_call("call-after-deadline", "atlas_get_day_plan", "{}")
        ), separators=(",", ":")).encode()
        return httpx.Response(
            200,
            headers={"content-type": "application/json"},
            stream=SlowBodyStream(body, 0.6, phases),
        )

    app, _adapter = make_app(tmp_path, monkeypatch, provider)
    seed_day_plan(app)
    response, elapsed = ASGITestClient(app).post_timed(
        "/v4/agent/chat",
        headers={"Authorization": f"Bearer {ANDROID_TOKEN}"},
        json=chat_body(shared_categories=["DAY_PLAN"]),
    )

    assert response.status_code == 504
    validate("error.schema.json", response.json())
    assert response.json()["error"]["code"] == "TIMEOUT"
    assert elapsed < 0.58
    assert phases == ["connect", "headers", "body"]
    assert tool_dispatches == []


def test_chat_capability_and_requested_consent_are_required_before_provider(tmp_path, monkeypatch):
    calls = 0

    def provider(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(200, json=final_response())

    caps = dict(CAPABILITIES)
    caps["coach.chat"] = False
    app, _adapter = make_app(tmp_path, monkeypatch, provider, capabilities=caps)
    disabled = post_chat(ASGITestClient(app), chat_body())
    assert disabled.status_code == 403
    validate("error.schema.json", disabled.json())
    assert disabled.json()["error"]["code"] == "CAPABILITY_UNAVAILABLE"
    assert calls == 0

    caps["coach.chat"] = True
    app, _adapter = make_app(tmp_path, monkeypatch, provider, capabilities=caps)
    missing_consent = post_chat(ASGITestClient(app), chat_body(shared_categories=["DAY_PLAN"]))
    assert missing_consent.status_code == 403
    validate("error.schema.json", missing_consent.json())
    assert missing_consent.json()["error"]["code"] == "CONSENT_REQUIRED"
    assert calls == 0


def test_tool_capability_is_checked_before_dispatch(tmp_path, monkeypatch):
    calls = 0

    def provider(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(200, json=response_with_tool_calls(
            tool_call("call-denied", "atlas_get_day_plan", "{}")
        ))

    caps = dict(CAPABILITIES)
    caps["day_plan.read"] = False
    app, _adapter = make_app(tmp_path, monkeypatch, provider, capabilities=caps)
    grant_category(app, "DAY_PLAN")
    response = post_chat(ASGITestClient(app), chat_body(shared_categories=["DAY_PLAN"]))
    assert response.status_code == 403
    validate("chat-response.schema.json", response.json())
    assert response.json()["agent_status"] == "unavailable"
    assert response.json()["tool_call_count"] == 1
    assert calls == 1


def seed_day_plan(app) -> None:
    projections = app.state.v4_tool_service.projections
    projections.save_consent_state(
        device_ref(), "DAY_PLAN", 1, "GRANTED", idempotency_key=str(uuid.uuid4())
    )
    local_date = datetime.now(timezone.utc).date().isoformat()
    projections.save_day_plan_snapshot(
        device_ref(),
        {
            "schema_version": 4,
            "plan_id": PLAN_ID,
            "local_date": local_date,
            "timezone": "Europe/Paris",
            "version": 7,
            "current_revision_id": REVISION_ID,
            "state": "APPROVED",
            "origin": "LOCAL",
            "observed_at": stamp(),
            "activities": [{
                "activity_id": ACTIVITY_ID,
                "kind": "FOCUS",
                "title": "Bloc de test",
                "status": "PLANNED",
                "scheduled_local": {
                    "local_date": local_date,
                    "start_minute": 600,
                    "duration_minutes": 45,
                    "timezone": "Europe/Paris",
                },
                "priority": "OPTIONAL",
                "goal_id": None,
                "continuity_id": "00000000-0000-4000-8000-000000000204",
                "supersedes_activity_id": None,
                "status_provenance": "UNKNOWN",
                "status_decided_at": None,
            }],
        },
        consent_version=1,
    )


def test_proposal_is_deduplicated_and_remains_a0_user_review(tmp_path, monkeypatch):
    requests = []
    proposal_args = json.dumps({
        "plan_id": PLAN_ID,
        "base_version": 7,
        "changes": [{
            "op": "reschedule",
            "activity_id": ACTIVITY_ID,
            "target_start_minute": 660,
        }],
        "justification": "Ajustement synthétique demandé par l’utilisateur.",
    })
    replies = [
        response_with_tool_calls(tool_call("call-proposal-exact", "atlas_propose_plan_revision", proposal_args)),
        final_response("Une proposition attend ta revue."),
    ]

    def provider(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return httpx.Response(200, json=replies.pop(0))

    app, _adapter = make_app(tmp_path, monkeypatch, provider)
    seed_day_plan(app)
    client = ASGITestClient(app)
    body = chat_body(shared_categories=["DAY_PLAN"])
    first = post_chat(client, body)
    replay = post_chat(client, body)

    assert first.status_code == 200
    assert replay.status_code == 200
    validate("chat-request.schema.json", body)
    validate("chat-response.schema.json", first.json())
    assert replay.json() == first.json()
    assert len(requests) == 2
    tool_message = next(item for item in requests[1]["messages"] if item["role"] == "tool")
    assert tool_message["tool_call_id"] == "call-proposal-exact"
    proposal_result = json.loads(tool_message["content"])
    assert proposal_result["data"]["confirmation_mode"] == "A0_USER_REVIEW"
    assert proposal_result["data"]["auto_application"] == "NONE_AT_CORE"
    assert first.json()["proposal_ids"] == [proposal_result["data"]["proposal_id"]]
    with app.state.store.connect() as connection:
        rows = connection.execute("SELECT proposal_json FROM agent_proposals_v4").fetchall()
    assert len(rows) == 1
    saved = json.loads(rows[0]["proposal_json"])
    assert saved["confirmation_mode"] == "A0_USER_REVIEW"
    assert saved["auto_application"] == "NONE_AT_CORE"


def test_failed_tool_after_a_success_reports_partial_effect(tmp_path, monkeypatch):
    def fail_activity_status(_service, _device_ref: str, _arguments: dict) -> dict:
        raise V4ToolError("DATA_UNAVAILABLE", "synthetic failure")

    monkeypatch.setattr(V4ToolService, "atlas_get_activity_status", fail_activity_status)

    def provider(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=response_with_tool_calls(
            tool_call("call-read-ok", "atlas_get_day_plan", "{}"),
            tool_call("call-read-failed", "atlas_get_activity_status", json.dumps({"activity_id": ACTIVITY_ID})),
        ))

    app, _adapter = make_app(tmp_path, monkeypatch, provider)
    grant_category(app, "DAY_PLAN")
    grant_category(app, "ACTIVITY_STATUS")
    response = post_chat(
        ASGITestClient(app),
        chat_body(shared_categories=["DAY_PLAN", "ACTIVITY_STATUS"]),
    )

    assert response.status_code == 503
    validate("chat-response.schema.json", response.json())
    assert response.json()["tool_state"] == "partial"
    assert response.json()["tool_call_count"] == 2


def test_proposal_error_after_persistence_reports_uncertain_effect(tmp_path, monkeypatch):
    original = V4ToolService.atlas_propose_plan_revision

    def persist_then_fail(service, device: str, arguments: dict) -> dict:
        original(service, device, arguments)
        raise RuntimeError("synthetic post-commit failure")

    monkeypatch.setattr(V4ToolService, "atlas_propose_plan_revision", persist_then_fail)
    proposal_args = json.dumps({
        "plan_id": PLAN_ID,
        "base_version": 7,
        "changes": [{"op": "reschedule", "activity_id": ACTIVITY_ID, "target_start_minute": 660}],
        "justification": "Ajustement synthétique.",
    })

    def provider(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=response_with_tool_calls(
            tool_call("call-uncertain-proposal", "atlas_propose_plan_revision", proposal_args)
        ))

    app, _adapter = make_app(tmp_path, monkeypatch, provider)
    seed_day_plan(app)
    response = post_chat(ASGITestClient(app), chat_body(shared_categories=["DAY_PLAN"]))

    assert response.status_code == 500
    validate("chat-response.schema.json", response.json())
    assert response.json()["tool_state"] == "uncertain"
    assert response.json()["tool_call_count"] == 1
    with app.state.store.connect() as connection:
        rows = connection.execute("SELECT proposal_json FROM agent_proposals_v4").fetchall()
    assert len(rows) == 1
    stored = json.loads(rows[0]["proposal_json"])
    assert stored["confirmation_mode"] == "A0_USER_REVIEW"
    assert stored["auto_application"] == "NONE_AT_CORE"


def test_timeout_after_proposal_reports_completed_effect_and_replays_without_retry(tmp_path, monkeypatch):
    provider_count = 0
    proposal_args = json.dumps({
        "plan_id": PLAN_ID,
        "base_version": 7,
        "changes": [{"op": "reschedule", "activity_id": ACTIVITY_ID, "target_start_minute": 660}],
        "justification": "Ajustement synthétique.",
    })

    def provider(request: httpx.Request) -> httpx.Response:
        nonlocal provider_count
        provider_count += 1
        if provider_count == 1:
            return httpx.Response(200, json=response_with_tool_calls(
                tool_call("call-before-timeout", "atlas_propose_plan_revision", proposal_args)
            ))
        raise httpx.ReadTimeout("synthetic continuation timeout", request=request)

    app, _adapter = make_app(tmp_path, monkeypatch, provider)
    seed_day_plan(app)
    client = ASGITestClient(app)
    body = chat_body(shared_categories=["DAY_PLAN"])
    first = post_chat(client, body)
    replay = post_chat(client, body)

    assert first.status_code == replay.status_code == 504
    validate("chat-response.schema.json", first.json())
    assert replay.json() == first.json()
    assert first.json()["agent_status"] == "unavailable"
    assert first.json()["tool_state"] == "completed"
    assert first.json()["tool_call_count"] == 1
    assert len(first.json()["proposal_ids"]) == 1
    assert provider_count == 2


def test_request_id_cannot_be_reused_with_a_different_canonical_body(tmp_path, monkeypatch):
    calls = 0

    def provider(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(200, json=final_response())

    app, _adapter = make_app(tmp_path, monkeypatch, provider)
    client = ASGITestClient(app)
    first = chat_body()
    assert post_chat(client, first).status_code == 200
    different = dict(first)
    different["message"] = "Different synthetic message."
    conflict = post_chat(client, different)
    assert conflict.status_code == 409
    validate("error.schema.json", conflict.json())
    assert conflict.json()["error"]["code"] == "IDEMPOTENCY_CONFLICT"
    assert calls == 1


def test_cache_finalization_failure_keeps_request_pending_without_redispatch(tmp_path, monkeypatch, caplog):
    provider_count = 0

    def provider(_request: httpx.Request) -> httpx.Response:
        nonlocal provider_count
        provider_count += 1
        return httpx.Response(200, json=final_response())

    app, _adapter = make_app(tmp_path, monkeypatch, provider)
    with app.state.store.connect() as connection:
        connection.execute(
            "CREATE TRIGGER reject_chat_cache_update BEFORE UPDATE ON agent_chat_cache "
            "BEGIN SELECT RAISE(FAIL, 'synthetic cache failure'); END"
        )

    client = ASGITestClient(app)
    replay_body = chat_body()
    first = post_chat(client, replay_body)
    replay = post_chat(client, replay_body)

    assert first.status_code == 200
    validate("chat-response.schema.json", first.json())
    assert replay.status_code == 409
    validate("error.schema.json", replay.json())
    assert replay.json()["error"]["code"] == "REPLAY_PENDING"
    assert provider_count == 1
    assert "synthetic-secret-phrase" not in caplog.text
    assert "synthetic-provider-token" not in caplog.text
    assert "synthetic cache failure" not in caplog.text


def test_chat_cache_is_stable_for_24_hours_then_expires_and_cleans_old_rows(tmp_path, monkeypatch):
    provider_count = 0

    def provider(_request: httpx.Request) -> httpx.Response:
        nonlocal provider_count
        provider_count += 1
        return httpx.Response(200, json=final_response())

    app, _adapter = make_app(tmp_path, monkeypatch, provider)
    base_time = datetime(2030, 1, 1, tzinfo=timezone.utc)
    current_time = [base_time]
    app.state.store.clock = lambda: current_time[0]
    body = chat_body(request_id="00000000-0000-4000-8000-000000000111")
    client = ASGITestClient(app)
    cache_key = "v4-chat:" + device_ref() + ":" + body["request_id"]

    first = post_chat(client, body)
    with app.state.store.connect() as connection:
        original_expiry = connection.execute(
            "SELECT expires_at FROM agent_chat_cache WHERE cache_key=?", (cache_key,)
        ).fetchone()["expires_at"]
    assert original_expiry == "2030-01-02T00:00:00Z"
    current_time[0] = base_time + timedelta(hours=23, minutes=59, seconds=59)
    replay = post_chat(client, body)
    assert first.status_code == replay.status_code == 200
    assert replay.json() == first.json()
    assert provider_count == 1

    with app.state.store.connect() as connection:
        connection.execute(
            "INSERT INTO agent_chat_cache(cache_key,response_json,expires_at) VALUES(?,?,?)",
            ("v4-chat:expired-synthetic", json.dumps({"state": "complete"}), "2029-12-31T23:59:59Z"),
        )
    current_time[0] = base_time + timedelta(days=1, seconds=1)
    expired_replay = post_chat(client, body)
    assert expired_replay.status_code == 200, f"provider_count={provider_count}"
    assert expired_replay.json() == first.json()
    assert provider_count == 2
    with app.state.store.connect() as connection:
        rows = connection.execute(
            "SELECT cache_key,expires_at FROM agent_chat_cache WHERE cache_key LIKE 'v4-chat:%'"
        ).fetchall()
    assert len(rows) == 1
    assert rows[0]["cache_key"] == cache_key
    assert rows[0]["expires_at"] == "2030-01-03T00:00:01Z"
