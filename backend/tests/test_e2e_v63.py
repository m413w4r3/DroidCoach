from __future__ import annotations

import asyncio
import hashlib
import json
import uuid
from datetime import datetime, timezone
from pathlib import Path

import httpx
from atlas_backend.main import create_app
from atlas_backend.openclaw_adapter import OpenClawAgentAdapter


ANDROID_TOKEN = "synthetic-v63-android-token"
TOOL_TOKEN = "synthetic-v63-tool-token"
PROVIDER_TOKEN = "synthetic-v63-provider-token"
PLAN_ID = "00000000-0000-4000-8000-000000000101"
REVISION_ID = "00000000-0000-4000-8000-000000000102"
ACTIVITY_ID = "00000000-0000-4000-8000-000000000103"
CONTINUITY_ID = "00000000-0000-4000-8000-000000000104"
CHAT_ID = "00000000-0000-4000-8000-000000000105"
PLAN_REQUEST_ID = "00000000-0000-4000-8000-000000000106"
PLAN_IDEMPOTENCY_KEY = "00000000-0000-4000-8000-000000000107"
TOOL_CALL_ID = "call-v63-plan-proposal"
CHAT_TOOL_NAMES = [
    "atlas_get_day_plan",
    "atlas_get_activity_status",
    "atlas_propose_plan_revision",
    "atlas_get_usage_summary",
    "atlas_get_goal_progress",
    "atlas_get_training_plan",
]
CAPABILITIES = {
    "day_plan.read": True,
    "activity_status.read": True,
    "plan_revision.propose": True,
    "usage_summary.read": True,
    "goal_progress.read": True,
    "training_plan.read": True,
    "coach.chat": True,
}


class LocalAsgiClient:
    """Synchronous calls to the app through an in-process ASGI transport."""

    def __init__(self, app):
        self.app = app

    def request(self, method: str, path: str, **kwargs):
        async def send():
            transport = httpx.ASGITransport(app=self.app, client=("127.0.0.1", 51400))
            async with httpx.AsyncClient(transport=transport, base_url="http://atlas.test") as client:
                return await client.request(method, path, **kwargs)

        return asyncio.run(send())

    def post(self, path: str, **kwargs):
        return self.request("POST", path, **kwargs)

    def get(self, path: str, **kwargs):
        return self.request("GET", path, **kwargs)


def stamp() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def envelope(capability: str, data: dict, *, request_id: str, idempotency_key: str,
             consent_version: int = 1) -> dict:
    return {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": request_id,
        "capability": capability,
        "sent_at": stamp(),
        "consent_version": consent_version,
        "idempotency_key": idempotency_key,
        "data": data,
    }


def day_plan() -> dict:
    local_date = datetime.now(timezone.utc).date().isoformat()
    return {
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
            "title": "Bloc synthétique",
            "status": "PLANNED",
            "scheduled_local": {
                "local_date": local_date,
                "start_minute": 600,
                "duration_minutes": 45,
                "timezone": "Europe/Paris",
            },
            "priority": "OPTIONAL",
            "goal_id": None,
            "continuity_id": CONTINUITY_ID,
            "supersedes_activity_id": None,
            "status_provenance": "UNKNOWN",
            "status_decided_at": None,
        }],
    }


def consent_body(state: str, version: int) -> dict:
    return {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": str(uuid.uuid4()),
        "capability": "consent.state.write",
        "sent_at": stamp(),
        "category": "DAY_PLAN",
        "consent_version": version,
        "state": state,
        "idempotency_key": str(uuid.uuid4()),
    }


def chat_body(request_id: str = CHAT_ID, *, consent_version: int = 1,
              shared_categories: list[str] | None = None) -> dict:
    return {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": request_id,
        "capability": "coach.chat",
        "sent_at": stamp(),
        "conversation_id": "synthetic-v63-conversation",
        "message": "Ajuste ce bloc synthétique d’une heure.",
        "consent_version": consent_version,
        "shared_categories": shared_categories if shared_categories is not None else ["DAY_PLAN"],
    }


def tool_call(call_id: str, name: str, arguments: dict) -> dict:
    return {
        "id": call_id,
        "type": "function",
        "function": {"name": name, "arguments": json.dumps(arguments)},
    }


def response_with_tool_call(call: dict) -> dict:
    return {"choices": [{
        "finish_reason": "tool_calls",
        "message": {"role": "assistant", "content": None, "tool_calls": [call]},
    }]}


def provider_final(text: str) -> dict:
    return {"choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": text}}]}


def android_headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {ANDROID_TOKEN}"}


def make_harness(tmp_path: Path, monkeypatch, provider_handler):
    monkeypatch.setenv("ATLAS_TOOL_TOKEN", TOOL_TOKEN)
    monkeypatch.setenv("ATLAS_V4_CAPABILITIES_JSON", json.dumps(CAPABILITIES))
    agent = OpenClawAgentAdapter(
        base_url="http://provider.invalid",
        token=PROVIDER_TOKEN,
        timeout_s=2,
        transport=httpx.MockTransport(provider_handler),
    )
    app = create_app(db_path=tmp_path / "atlas-v63-e2e.sqlite3", token=ANDROID_TOKEN, agent=agent)
    return app, LocalAsgiClient(app)


def assert_typed_error(response: httpx.Response, expected_code: str) -> None:
    assert response.status_code >= 400
    payload = response.json()
    assert payload["error"]["code"] == expected_code
    assert payload["contract_version"] == 4
    assert payload["schema_version"] == 4


def grant_day_plan(client: LocalAsgiClient, state: str = "GRANTED", version: int = 1) -> httpx.Response:
    return client.post(
        "/v4/sync/consent-state",
        headers=android_headers(),
        json=consent_body(state, version),
    )


def tool_headers(capability: str, request_id: str) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {TOOL_TOKEN}",
        "X-Atlas-Contract-Version": "4",
        "X-Atlas-Schema-Version": "4",
        "X-Atlas-Request-Id": request_id,
        "X-Atlas-Capability": capability,
    }


def test_v63_offline_chat_to_proposal_pull_ack_and_revocation(tmp_path, monkeypatch):
    provider_requests: list[dict] = []
    replies = [
        response_with_tool_call(tool_call(TOOL_CALL_ID, "atlas_propose_plan_revision", {
            "plan_id": PLAN_ID,
            "base_version": 7,
            "changes": [{
                "op": "reschedule",
                "activity_id": ACTIVITY_ID,
                "target_start_minute": 660,
            }],
            "justification": "Proposition synthétique à revoir par la personne.",
        })),
        provider_final("Une proposition attend votre revue."),
    ]

    def fake_provider(request: httpx.Request) -> httpx.Response:
        assert request.url.host == "provider.invalid"
        assert request.headers.get("Authorization") == f"Bearer {PROVIDER_TOKEN}"
        payload = json.loads(request.content)
        provider_requests.append(payload)
        if replies:
            return httpx.Response(200, json=replies.pop(0))
        return httpx.Response(503, json={"error": "synthetic provider unavailable"})

    app, client = make_harness(tmp_path, monkeypatch, fake_provider)

    refused = client.post("/v4/agent/chat", headers=android_headers(), json=chat_body())
    assert_typed_error(refused, "CONSENT_REQUIRED")
    assert provider_requests == []

    consent = grant_day_plan(client)
    assert consent.status_code == 200
    plan_data = day_plan()
    plan_push = envelope(
        "day_plan.snapshot.write",
        plan_data,
        request_id=PLAN_REQUEST_ID,
        idempotency_key=PLAN_IDEMPOTENCY_KEY,
    )
    pushed = client.post("/v4/sync/day-plan-snapshots", headers=android_headers(), json=plan_push)
    assert pushed.status_code == 200, pushed.text
    assert pushed.json()["result_code"] == "ACCEPTED"
    replay = client.post("/v4/sync/day-plan-snapshots", headers=android_headers(), json=plan_push)
    assert replay.status_code == 200
    assert replay.json()["replayed"] is True
    changed_push = json.loads(json.dumps(plan_push))
    changed_push["data"]["activities"][0]["title"] = "Autre fixture synthétique"
    changed = client.post("/v4/sync/day-plan-snapshots", headers=android_headers(), json=changed_push)
    assert_typed_error(changed, "CONFLICT")

    body = chat_body()
    chat = client.post("/v4/agent/chat", headers=android_headers(), json=body)
    assert chat.status_code == 200, chat.text
    result = chat.json()
    assert result["tool_state"] == "completed"
    assert result["tool_call_count"] == 1
    assert result["agent_status"] == "ok"
    assert len(provider_requests) == 2
    assert [item["function"]["name"] for item in provider_requests[0]["tools"]] == CHAT_TOOL_NAMES
    assert len(provider_requests[0]["tools"]) == 6
    followup = provider_requests[1]["messages"]
    assistant = next(message for message in followup if message["role"] == "assistant")
    tool = next(message for message in followup if message["role"] == "tool")
    assert assistant["tool_calls"][0]["id"] == TOOL_CALL_ID
    assert tool["tool_call_id"] == TOOL_CALL_ID
    tool_result = json.loads(tool["content"])
    assert tool_result["data"]["confirmation_mode"] == "A0_USER_REVIEW"
    assert tool_result["data"]["auto_application"] == "NONE_AT_CORE"
    proposal_id = result["proposal_ids"][0]

    # Chat replay must return the stored result without repeating provider calls or tools.
    chat_replay = client.post("/v4/agent/chat", headers=android_headers(), json=body)
    assert chat_replay.status_code == 200
    assert chat_replay.json() == result
    assert len(provider_requests) == 2

    day_plan_route = f"/internal/v4/day-plan?local_date={plan_data['local_date']}"
    unchanged = client.get(
        day_plan_route,
        headers=tool_headers("day_plan.read", str(uuid.uuid4())),
    )
    assert unchanged.status_code == 200
    assert unchanged.json()["data"]["plan"]["version"] == 7
    assert unchanged.json()["data"]["plan"]["activities"][0]["scheduled_local"]["start_minute"] == 600

    pull = client.post("/v4/agent/proposals/pull", headers=android_headers(), json={
        "contract_version": 4,
        "schema_version": 4,
        "request_id": str(uuid.uuid4()),
        "capability": "proposal.ack",
        "consent_version": 1,
        "page_size": 8,
        "cursor": None,
    })
    assert pull.status_code == 200, pull.text
    proposals = pull.json()["proposals"]
    assert len(proposals) == 1
    assert proposals[0]["proposal_id"] == proposal_id
    assert proposals[0]["confirmation_mode"] == "A0_USER_REVIEW"
    assert proposals[0]["auto_application"] == "NONE_AT_CORE"

    ack = {
        "contract_version": 4,
        "schema_version": 4,
        "ack_id": "00000000-0000-4000-8000-000000000108",
        "capability": "proposal.ack",
        "proposal_id": proposal_id,
        "sequence": 1,
        "state": "REJECTED",
        "result_code": "USER_REJECTED",
        "occurred_at": stamp(),
        "consent_version": 1,
    }
    ack_route = f"/v4/agent/proposals/{proposal_id}/ack"
    rejected = client.post(ack_route, headers=android_headers(), json=ack)
    assert rejected.status_code == 200, rejected.text
    assert rejected.json() == ack
    ack_replay = client.post(ack_route, headers=android_headers(), json=ack)
    assert ack_replay.status_code == 200
    assert ack_replay.headers["X-Atlas-Replayed"] == "true"

    revoked = grant_day_plan(client, "REVOKED", 2)
    assert revoked.status_code == 200
    stale_chat = client.post(
        "/v4/agent/chat",
        headers=android_headers(),
        json=chat_body("00000000-0000-4000-8000-000000000109", consent_version=1),
    )
    assert_typed_error(stale_chat, "CONSENT_REQUIRED")
    regranted = grant_day_plan(client, "GRANTED", 3)
    assert regranted.status_code == 200
    stale_chat_after_regrant = client.post(
        "/v4/agent/chat",
        headers=android_headers(),
        json=chat_body("00000000-0000-4000-8000-000000000113", consent_version=1),
    )
    assert_typed_error(stale_chat_after_regrant, "STALE_VERSION")
    stale_push_body = envelope(
        "day_plan.snapshot.write",
        plan_data,
        request_id="00000000-0000-4000-8000-000000000110",
        idempotency_key="00000000-0000-4000-8000-000000000111",
        consent_version=1,
    )
    stale_push = client.post("/v4/sync/day-plan-snapshots", headers=android_headers(), json=stale_push_body)
    assert_typed_error(stale_push, "STALE_VERSION")

    upstream = client.post(
        "/v4/agent/chat",
        headers=android_headers(),
        json=chat_body("00000000-0000-4000-8000-000000000112", shared_categories=[]),
    )
    assert_typed_error(upstream, "UPSTREAM_UNAVAILABLE")
    assert len(provider_requests) == 3
    with app.state.store.connect() as connection:
        stored_plan = connection.execute(
            "SELECT plan_version FROM day_plan_snapshots_v4 WHERE device_ref=? AND plan_id=?",
            ("android-" + hashlib.sha256(ANDROID_TOKEN.encode()).hexdigest()[:32], PLAN_ID),
        ).fetchone()
        proposal_count = connection.execute("SELECT COUNT(*) FROM agent_proposals_v4").fetchone()[0]
    assert stored_plan is not None and stored_plan["plan_version"] == 7
    assert proposal_count == 1
