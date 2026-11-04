from __future__ import annotations

import asyncio
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

import httpx
from atlas_backend.main import create_app
from atlas_backend.openclaw_adapter import OpenClawAgentAdapter
from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts" / "v4"
ANDROID_TOKEN = "synthetic-v7-08-a0-android-token"
TOOL_TOKEN = "synthetic-v7-08-a0-tool-token"
REQUEST_APPLY = "00000000-0000-4000-8000-000000000801"
REQUEST_REJECT = "00000000-0000-4000-8000-000000000802"
REQUEST_NO_CONSENT = "00000000-0000-4000-8000-000000000803"
REQUEST_AFTER_CONSENT = "00000000-0000-4000-8000-000000000804"
PLAN_ID = "00000000-0000-4000-8000-000000000811"
REVISION_INITIAL = "00000000-0000-4000-8000-000000000812"
REVISION_APPLIED = "00000000-0000-4000-8000-000000000813"
ACTIVITY_ID = "00000000-0000-4000-8000-000000000814"
UNKNOWN_PROPOSAL_ID = "00000000-0000-4000-8000-000000000815"
FIXED_NOW = datetime(2030, 6, 1, 8, 0, tzinfo=timezone.utc)
SYNTHETIC_TITLE = "Test ATLAS — séance Focus"
SYNTHETIC_START_MINUTE = 780

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
    """Synchronous test facade over the real FastAPI routes."""

    def __init__(self, app):
        self.app = app

    def request(self, method: str, url: str, **kwargs):
        async def send():
            transport = httpx.ASGITransport(app=self.app, client=("127.0.0.1", 50808))
            async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
                return await client.request(method, url, **kwargs)

        return asyncio.run(send())

    def get(self, url: str, **kwargs):
        return self.request("GET", url, **kwargs)

    def post(self, url: str, **kwargs):
        return self.request("POST", url, **kwargs)


def stamp(value: datetime = FIXED_NOW) -> str:
    return value.isoformat().replace("+00:00", "Z")


def schema(name: str) -> dict:
    return json.loads((CONTRACTS / name).read_text(encoding="utf-8"))


def validate(name: str, value: dict, *, definition: str | None = None) -> None:
    contract = schema(name)
    if definition is not None:
        contract = contract["$defs"][definition]
    Draft202012Validator(contract, format_checker=FormatChecker()).validate(value)


def set_fixed_clock(app) -> None:
    app.state.store.clock = lambda: FIXED_NOW
    app.state.v4_tool_service.clock = lambda: FIXED_NOW


def make_app(tmp_path: Path, monkeypatch, provider) -> tuple[object, OpenClawAgentAdapter]:
    monkeypatch.setenv("ATLAS_TOOL_TOKEN", TOOL_TOKEN)
    monkeypatch.setenv("ATLAS_V4_CAPABILITIES_JSON", json.dumps(CAPABILITIES))
    adapter = OpenClawAgentAdapter(
        base_url="http://provider.invalid",
        token="synthetic-provider-token",
        timeout_s=1,
        daily_tokens=1_000_000,
        transport=httpx.MockTransport(provider),
    )
    app = create_app(db_path=tmp_path / "atlas.sqlite3", token=ANDROID_TOKEN, agent=adapter)
    set_fixed_clock(app)
    return app, adapter


def restart_app(tmp_path: Path, adapter: OpenClawAgentAdapter):
    """Re-open the same SQLite database to exercise persisted protocol state."""
    app = create_app(db_path=tmp_path / "atlas.sqlite3", token=ANDROID_TOKEN, agent=adapter)
    set_fixed_clock(app)
    return app


def android_headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {ANDROID_TOKEN}"}


def tool_headers(capability: str) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {TOOL_TOKEN}",
        "X-Atlas-Contract-Version": "4",
        "X-Atlas-Schema-Version": "4",
        "X-Atlas-Request-Id": "00000000-0000-4000-8000-000000000816",
        "X-Atlas-Capability": capability,
    }


def device_ref() -> str:
    return "android-" + hashlib.sha256(ANDROID_TOKEN.encode()).hexdigest()[:32]


def consent_body(state: str, version: int, key: str) -> dict:
    return {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": "00000000-0000-4000-8000-000000000817",
        "capability": "consent.state.write",
        "sent_at": stamp(),
        "category": "DAY_PLAN",
        "consent_version": version,
        "state": state,
        "idempotency_key": key,
    }


def day_plan(*, version: int = 1, revision_id: str = REVISION_INITIAL, activities: list | None = None) -> dict:
    # The fixture is pinned to Europe/Paris and independent of host time zone.
    local_date = "2030-06-01"
    return {
        "plan_id": PLAN_ID,
        "local_date": local_date,
        "timezone": "Europe/Paris",
        "version": version,
        "current_revision_id": revision_id,
        "state": "APPROVED",
        "origin": "LOCAL",
        "observed_at": stamp(),
        "activities": [activity()] if activities is None else activities,
    }


def activity(*, start_minute: int = SYNTHETIC_START_MINUTE) -> dict:
    return {
        "activity_id": ACTIVITY_ID,
        "kind": "FOCUS",
        "title": SYNTHETIC_TITLE,
        "status": "PLANNED",
        "scheduled_local": {
            "local_date": "2030-06-01",
            "start_minute": start_minute,
            "duration_minutes": 45,
            "timezone": "Europe/Paris",
        },
        "priority": "IMPORTANT",
        "goal_id": None,
        "continuity_id": "00000000-0000-4000-8000-000000000818",
        "supersedes_activity_id": None,
        "status_provenance": "UNKNOWN",
        "status_decided_at": None,
    }


def sync_plan(client: ASGITestClient, plan: dict, *, key: str, request_id: str) -> httpx.Response:
    body = {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": request_id,
        "capability": "day_plan.snapshot.write",
        "sent_at": stamp(),
        "consent_version": 3,
        "idempotency_key": key,
        "data": plan,
    }
    validate("sync.schema.json", body, definition="day_plan_snapshot")
    return client.post("/v4/sync/day-plan-snapshots", headers=android_headers(), json=body)


def seed_day_plan(client: ASGITestClient) -> None:
    grant = consent_body(
        "GRANTED", 3, "00000000-0000-4000-8000-000000000819",
    )
    validate("sync.schema.json", grant, definition="consent_state_update")
    response = client.post("/v4/sync/consent-state", headers=android_headers(), json=grant)
    assert response.status_code == 200, response.text
    validate("sync.schema.json", response.json(), definition="consent_state_receipt")

    response = sync_plan(
        client,
        day_plan(),
        key="00000000-0000-4000-8000-000000000820",
        request_id="00000000-0000-4000-8000-000000000821",
    )
    assert response.status_code == 200, response.text
    validate("sync-receipts.schema.json", response.json())


def tool_call(call_id: str, name: str, arguments: dict) -> dict:
    return {
        "id": call_id,
        "type": "function",
        "function": {"name": name, "arguments": json.dumps(arguments, ensure_ascii=False)},
    }


def tool_call_response(call: dict) -> dict:
    return {
        "choices": [{
            "finish_reason": "tool_calls",
            "message": {"role": "assistant", "content": None, "tool_calls": [call]},
        }],
    }


def final_response(reply: str) -> dict:
    return {"choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": reply}}]}


def chat_body(request_id: str, conversation_id: str, *, message: str) -> dict:
    return {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": request_id,
        "capability": "coach.chat",
        "sent_at": stamp(),
        "conversation_id": conversation_id,
        "message": message,
        "consent_version": 3,
        "shared_categories": ["DAY_PLAN"],
    }


def chat_responses(changes: list[dict], prefix: str, *, base_version: int = 1) -> list[dict]:
    return [
        tool_call_response(tool_call(f"{prefix}-read-plan", "atlas_get_day_plan", {})),
        tool_call_response(tool_call(
            f"{prefix}-propose", "atlas_propose_plan_revision", {
                "plan_id": PLAN_ID,
                "base_version": base_version,
                "changes": changes,
                "justification": "Essai A0 synthétique, en attente de revue utilisateur.",
            },
        )),
        final_response("Une proposition attend votre décision explicite."),
    ]


def send_chat(client: ASGITestClient, body: dict) -> httpx.Response:
    validate("chat-request.schema.json", body)
    return client.post("/v4/agent/chat", headers=android_headers(), json=body)


def pull_body(request_id: str, consent_version: int = 3) -> dict:
    # v4_router.pull_proposals is the current closed-key validator; the repository
    # publishes proposal fragments but no pull-envelope schema.
    return {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": request_id,
        "capability": "proposal.ack",
        "consent_version": consent_version,
        "page_size": 32,
        "cursor": None,
    }


def pull(client: ASGITestClient, request_id: str, *, consent_version: int = 3) -> dict:
    response = client.post(
        "/v4/agent/proposals/pull",
        headers=android_headers(),
        json=pull_body(request_id, consent_version),
    )
    assert response.status_code == 200, response.text
    result = response.json()
    assert set(result) == {
        "contract_version", "schema_version", "request_id", "capability", "proposals", "next_cursor",
    }
    assert result["contract_version"] == result["schema_version"] == 4
    assert result["capability"] == "proposal.ack"
    for proposal in result["proposals"]:
        validate("proposal-inbox-ack.schema.json", proposal, definition="proposal")
    return result


def ack_body(
    proposal_id: str,
    ack_id: str,
    state: str,
    result_code: str,
    *,
    consent_version: int = 3,
    sequence: int = 1,
) -> dict:
    body = {
        "contract_version": 4,
        "schema_version": 4,
        "ack_id": ack_id,
        "capability": "proposal.ack",
        "proposal_id": proposal_id,
        "sequence": sequence,
        "state": state,
        "result_code": result_code,
        "occurred_at": stamp(),
        "consent_version": consent_version,
    }
    if state == "APPLIED":
        body.update({
            "plan_id": PLAN_ID,
            "applied_plan_version": 2,
            "revision_id": REVISION_APPLIED,
            "scope_hash": "sha256:" + hashlib.sha256(b"synthetic local application scope").hexdigest(),
            "effect_ids": [
                f"PLAN_ACTIVITY_UPDATE:{ACTIVITY_ID}",
                f"PLAN_APPLY:{REVISION_APPLIED}",
            ],
        })
    return body


def post_ack(client: ASGITestClient, ack: dict) -> httpx.Response:
    validate("proposal-inbox-ack.schema.json", ack, definition="ack")
    return client.post(
        f"/v4/agent/proposals/{ack['proposal_id']}/ack",
        headers=android_headers(),
        json=ack,
    )


def current_day_plan(client: ASGITestClient) -> dict:
    response = client.get("/internal/v4/day-plan", headers=tool_headers("day_plan.read"))
    assert response.status_code == 200, response.text
    validate("tool-results.schema.json", response.json())
    return response.json()["data"]["plan"]


def assert_only_synthetic_activity(plan: dict, *, start_minute: int = SYNTHETIC_START_MINUTE) -> None:
    activities = plan["activities"]
    assert len(activities) == 1
    actual = activities[0]
    expected = activity(start_minute=start_minute)
    for field in (
        "activity_id", "kind", "title", "status", "scheduled_local", "priority", "goal_id",
        "continuity_id", "supersedes_activity_id", "status_provenance", "status_decided_at",
    ):
        assert actual[field] == expected[field]


def error_code(response, expected: str, status: int) -> None:
    assert response.status_code == status, response.text
    validate("error.schema.json", response.json())
    assert response.json()["error"]["code"] == expected


def test_a0_chat_pull_apply_ack_restart_and_reject_no_mutation(tmp_path, monkeypatch):
    provider_responses = chat_responses([{
        "op": "reschedule",
        "activity_id": ACTIVITY_ID,
        "target_start_minute": 795,
    }], "apply") + chat_responses([{
        "op": "reschedule",
        "activity_id": ACTIVITY_ID,
        "target_start_minute": 810,
    }], "reject", base_version=2)
    provider_requests = []

    def provider(request: httpx.Request) -> httpx.Response:
        provider_requests.append(json.loads(request.content))
        assert provider_responses, "chat unexpectedly dispatched another model request"
        return httpx.Response(200, json=provider_responses.pop(0))

    app, adapter = make_app(tmp_path, monkeypatch, provider)
    client = ASGITestClient(app)
    seed_day_plan(client)

    first_body = chat_body(
        REQUEST_APPLY,
        "synthetic-a0-apply",
        message="Propose de décaler de 15 minutes aujourd’hui Test ATLAS — séance Focus, qui dure 45 minutes. N’applique rien sans mon accord.",
    )
    first = send_chat(client, first_body)
    assert first.status_code == 200, first.text
    validate("chat-response.schema.json", first.json())
    assert first.json()["tool_state"] == "completed"
    assert first.json()["tool_call_count"] == 2
    assert len(first.json()["proposal_ids"]) == 1
    proposal_id = first.json()["proposal_ids"][0]
    assert len(provider_requests) == 3

    tool_reply = [message for message in provider_requests[2]["messages"] if message["role"] == "tool"][-1]
    proposal_result = json.loads(tool_reply["content"])
    validate("tool-results.schema.json", proposal_result)
    proposal_data = proposal_result["data"]
    assert proposal_data["confirmation_mode"] == "A0_USER_REVIEW"
    assert proposal_data["auto_application"] == "NONE_AT_CORE"
    assert proposal_data["status"] == "QUEUED"
    assert proposal_data["diff"][0]["after"]["duration_minutes"] == 45
    assert proposal_data["diff"][0]["after"]["start_minute"] == 795
    assert proposal_data["diff"][0]["before"]["start_minute"] == SYNTHETIC_START_MINUTE

    with app.state.store.connect() as connection:
        stored_rows = connection.execute(
            "SELECT proposal_json,state FROM agent_proposals_v4 WHERE proposal_id=?", (proposal_id,),
        ).fetchall()
    assert len(stored_rows) == 1
    stored_proposal = json.loads(stored_rows[0]["proposal_json"])
    assert stored_proposal["body_hash"].startswith("sha256:")
    public_stored_proposal = {
        key: value for key, value in stored_proposal.items() if key != "body_hash"
    }
    validate("proposal-inbox-ack.schema.json", public_stored_proposal, definition="proposal")
    assert stored_rows[0]["state"] == "PENDING_ANDROID"
    assert stored_proposal["confirmation_mode"] == "A0_USER_REVIEW"
    assert stored_proposal["auto_application"] == "NONE_AT_CORE"
    assert current_day_plan(client)["version"] == 1
    assert_only_synthetic_activity(current_day_plan(client))

    replayed_chat = send_chat(client, first_body)
    assert replayed_chat.status_code == 200
    assert replayed_chat.json() == first.json()
    assert len(provider_requests) == 3
    assert current_day_plan(client)["version"] == 1
    assert_only_synthetic_activity(current_day_plan(client))

    first_pull = pull(client, "00000000-0000-4000-8000-000000000822")
    assert [item["proposal_id"] for item in first_pull["proposals"]] == [proposal_id]
    assert first_pull["proposals"][0]["state"] == "PENDING_ANDROID"
    assert "body_hash" not in first_pull["proposals"][0]

    # A newly constructed app/client represents Android reconnecting to the same Core.
    reconnected_app = restart_app(tmp_path, adapter)
    reconnected = ASGITestClient(reconnected_app)
    second_pull = pull(reconnected, "00000000-0000-4000-8000-000000000823")
    assert second_pull["proposals"] == first_pull["proposals"]

    # This API call stands for the approved Android edit being synchronized. Core has no APPLY route.
    applied_plan = day_plan(version=2, revision_id=REVISION_APPLIED, activities=[activity(start_minute=795)])
    apply_sync = sync_plan(
        reconnected,
        applied_plan,
        key="00000000-0000-4000-8000-000000000824",
        request_id="00000000-0000-4000-8000-000000000825",
    )
    assert apply_sync.status_code == 200, apply_sync.text
    validate("sync-receipts.schema.json", apply_sync.json())
    assert current_day_plan(reconnected)["version"] == 2
    assert_only_synthetic_activity(current_day_plan(reconnected), start_minute=795)

    applied_ack = ack_body(
        proposal_id,
        "00000000-0000-4000-8000-000000000826",
        "APPLIED",
        "APPLIED",
    )
    applied_response = post_ack(reconnected, applied_ack)
    assert applied_response.status_code == 200, applied_response.text
    validate("proposal-inbox-ack.schema.json", applied_response.json(), definition="ack")
    assert applied_response.headers["x-atlas-replayed"] == "false"

    durable_app = restart_app(tmp_path, adapter)
    durable = ASGITestClient(durable_app)
    applied_replay = post_ack(durable, applied_ack)
    assert applied_replay.status_code == 200, applied_replay.text
    assert applied_replay.headers["x-atlas-replayed"] == "true"
    assert applied_replay.json() == applied_ack
    changed_applied = {**applied_ack, "occurred_at": "2030-06-01T08:00:01Z"}
    conflict = post_ack(durable, changed_applied)
    error_code(conflict, "ACK_ID_CONFLICT", 409)
    assert current_day_plan(durable)["version"] == 2
    with durable_app.state.store.connect() as connection:
        first_ack_count = connection.execute(
            "SELECT COUNT(*) FROM agent_proposal_events_v4 WHERE proposal_id=?", (proposal_id,),
        ).fetchone()[0]
        first_state = connection.execute(
            "SELECT state FROM agent_proposals_v4 WHERE proposal_id=?", (proposal_id,),
        ).fetchone()["state"]
    assert first_ack_count == 1
    assert first_state == "APPLIED"

    duplicate_sync = sync_plan(
        durable,
        applied_plan,
        key="00000000-0000-4000-8000-000000000824",
        request_id="00000000-0000-4000-8000-000000000825",
    )
    assert duplicate_sync.status_code == 200, duplicate_sync.text
    validate("sync-receipts.schema.json", duplicate_sync.json())
    assert duplicate_sync.json()["result_code"] == "DUPLICATE"
    assert duplicate_sync.json()["replayed"] is True
    assert current_day_plan(durable)["version"] == 2

    second_body = chat_body(
        REQUEST_REJECT,
        "synthetic-a0-reject",
        message="Propose de décaler cette séance synthétique de 15 minutes. Ne l’applique pas; je vais refuser.",
    )
    second = send_chat(durable, second_body)
    assert second.status_code == 200, second.text
    validate("chat-response.schema.json", second.json())
    assert second.json()["tool_state"] == "completed"
    assert second.json()["tool_call_count"] == 2
    assert len(second.json()["proposal_ids"]) == 1
    rejected_proposal_id = second.json()["proposal_ids"][0]
    assert current_day_plan(durable)["version"] == 2
    assert_only_synthetic_activity(current_day_plan(durable), start_minute=795)

    rejected_pull = pull(durable, "00000000-0000-4000-8000-000000000827")
    assert [item["proposal_id"] for item in rejected_pull["proposals"]] == [rejected_proposal_id]
    rejected_ack = ack_body(
        rejected_proposal_id,
        "00000000-0000-4000-8000-000000000828",
        "REJECTED",
        "USER_REJECTED",
    )
    rejected_response = post_ack(durable, rejected_ack)
    assert rejected_response.status_code == 200, rejected_response.text
    validate("proposal-inbox-ack.schema.json", rejected_response.json(), definition="ack")
    assert rejected_response.json() == rejected_ack
    assert current_day_plan(durable)["version"] == 2
    assert_only_synthetic_activity(current_day_plan(durable), start_minute=795)

    final_app = restart_app(tmp_path, adapter)
    final_client = ASGITestClient(final_app)
    rejected_replay = post_ack(final_client, rejected_ack)
    assert rejected_replay.status_code == 200, rejected_replay.text
    assert rejected_replay.headers["x-atlas-replayed"] == "true"
    assert rejected_replay.json() == rejected_ack
    assert current_day_plan(final_client)["version"] == 2
    assert_only_synthetic_activity(current_day_plan(final_client), start_minute=795)
    with final_app.state.store.connect() as connection:
        states = connection.execute(
            "SELECT p.proposal_id,p.state,COUNT(e.ack_id) AS ack_count "
            "FROM agent_proposals_v4 p LEFT JOIN agent_proposal_events_v4 e USING(proposal_id) "
            "GROUP BY p.proposal_id ORDER BY p.proposal_id"
        ).fetchall()
        snapshot_count = connection.execute("SELECT COUNT(*) FROM day_plan_snapshots_v4").fetchone()[0]
    state_by_id = {row["proposal_id"]: (row["state"], row["ack_count"]) for row in states}
    assert state_by_id[proposal_id] == ("APPLIED", 1)
    assert state_by_id[rejected_proposal_id] == ("REJECTED", 1)
    assert snapshot_count == 2
    assert provider_responses == []
    assert len(provider_requests) == 6


def test_day_plan_consent_absent_or_revoked_blocks_chat_pull_and_ack(tmp_path, monkeypatch):
    proposal_changes = [{
        "op": "reschedule",
        "activity_id": ACTIVITY_ID,
        "target_start_minute": 795,
    }]
    provider_responses = chat_responses(proposal_changes, "consent", base_version=1)
    provider_calls = []

    def provider(request: httpx.Request) -> httpx.Response:
        provider_calls.append(request)
        assert provider_responses
        return httpx.Response(200, json=provider_responses.pop(0))

    app, _adapter = make_app(tmp_path, monkeypatch, provider)
    client = ASGITestClient(app)
    no_consent = send_chat(client, chat_body(
        REQUEST_NO_CONSENT,
        "synthetic-no-day-plan-consent",
        message="Propose un changement au plan.",
    ))
    error_code(no_consent, "CONSENT_REQUIRED", 403)
    assert provider_calls == []
    with app.state.store.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM agent_proposals_v4").fetchone()[0] == 0

    grant = consent_body("GRANTED", 3, "00000000-0000-4000-8000-000000000829")
    grant_response = client.post("/v4/sync/consent-state", headers=android_headers(), json=grant)
    assert grant_response.status_code == 200, grant_response.text
    seed_snapshot = sync_plan(
        client,
        day_plan(),
        key="00000000-0000-4000-8000-000000000830",
        request_id="00000000-0000-4000-8000-000000000831",
    )
    assert seed_snapshot.status_code == 200, seed_snapshot.text
    accepted = send_chat(client, chat_body(
        REQUEST_AFTER_CONSENT,
        "synthetic-day-plan-consent-granted",
        message="Propose de décaler Test ATLAS — séance Focus de 15 minutes au plan du jour.",
    ))
    assert accepted.status_code == 200, accepted.text
    validate("chat-response.schema.json", accepted.json())
    proposal_id = accepted.json()["proposal_ids"][0]
    assert len(accepted.json()["proposal_ids"]) == 1

    revoke = consent_body("REVOKED", 4, "00000000-0000-4000-8000-000000000832")
    revoked_response = client.post("/v4/sync/consent-state", headers=android_headers(), json=revoke)
    assert revoked_response.status_code == 200, revoked_response.text
    blocked_pull = client.post(
        "/v4/agent/proposals/pull",
        headers=android_headers(),
        json=pull_body("00000000-0000-4000-8000-000000000833", consent_version=4),
    )
    error_code(blocked_pull, "CONSENT_REQUIRED", 403)
    blocked_ack = post_ack(client, ack_body(
        proposal_id,
        "00000000-0000-4000-8000-000000000834",
        "REJECTED",
        "USER_REJECTED",
        consent_version=4,
    ))
    error_code(blocked_ack, "CONSENT_REQUIRED", 403)
    with app.state.store.connect() as connection:
        state = connection.execute(
            "SELECT state FROM agent_proposals_v4 WHERE proposal_id=?", (proposal_id,),
        ).fetchone()["state"]
        ack_count = connection.execute(
            "SELECT COUNT(*) FROM agent_proposal_events_v4 WHERE proposal_id=?", (proposal_id,),
        ).fetchone()[0]
    assert state == "PENDING_ANDROID"
    assert ack_count == 0
    assert len(provider_calls) == 3


def test_unknown_proposal_ack_is_not_persisted(tmp_path, monkeypatch):
    def provider(_request: httpx.Request) -> httpx.Response:
        raise AssertionError("the unknown ACK test must not call a chat provider")

    app, _adapter = make_app(tmp_path, monkeypatch, provider)
    client = ASGITestClient(app)
    seed_day_plan(client)
    unknown = ack_body(
        UNKNOWN_PROPOSAL_ID,
        "00000000-0000-4000-8000-000000000835",
        "REJECTED",
        "USER_REJECTED",
    )
    response = post_ack(client, unknown)
    error_code(response, "NOT_FOUND", 404)
    with app.state.store.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM agent_proposal_events_v4").fetchone()[0] == 0
        assert connection.execute("SELECT COUNT(*) FROM agent_proposals_v4").fetchone()[0] == 0
