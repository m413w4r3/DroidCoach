from __future__ import annotations

import asyncio
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
from jsonschema import Draft202012Validator, FormatChecker

from atlas_backend.main import create_app


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts" / "v4"
UUID_PLAN = "00000000-0000-4000-8000-000000000201"
UUID_REVISION = "00000000-0000-4000-8000-000000000202"
UUID_ACTIVITY = "00000000-0000-4000-8000-000000000203"
UUID_CONTINUITY = "00000000-0000-4000-8000-000000000204"
UUID_REQUEST = "00000000-0000-4000-8000-000000000205"
ANDROID_TOKEN = "android-proposal-secret"
TOOL_TOKEN = "tool-proposal-secret"
CAPABILITIES = {
    "day_plan.read": True,
    "activity_status.read": True,
    "plan_revision.propose": True,
    "usage_summary.read": True,
    "goal_progress.read": True,
    "training_plan.read": True,
    "coach.chat": False,
}


class ASGITestClient:
    """Synchronous test facade over httpx's async ASGI transport."""

    def __init__(self, app, client_address: tuple[str, int]):
        self.app = app
        self.client_address = client_address

    def request(self, method: str, url: str, **kwargs):
        async def send():
            transport = httpx.ASGITransport(app=self.app, client=self.client_address)
            async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
                return await client.request(method, url, **kwargs)

        return asyncio.run(send())

    def get(self, url: str, **kwargs):
        return self.request("GET", url, **kwargs)

    def post(self, url: str, **kwargs):
        return self.request("POST", url, **kwargs)

    def close(self) -> None:
        return None


def stamp(value: datetime | None = None) -> str:
    return (value or datetime.now(timezone.utc)).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def make_client(tmp_path: Path, monkeypatch) -> ASGITestClient:
    monkeypatch.setenv("ATLAS_TOOL_TOKEN", TOOL_TOKEN)
    monkeypatch.setenv("ATLAS_V4_CAPABILITIES_JSON", json.dumps(CAPABILITIES))
    app = create_app(db_path=tmp_path / "atlas.sqlite3", token=ANDROID_TOKEN)
    return ASGITestClient(app, client_address=("127.0.0.1", 50200))


def tool_headers(capability: str) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {TOOL_TOKEN}",
        "X-Atlas-Contract-Version": "4",
        "X-Atlas-Schema-Version": "4",
        "X-Atlas-Request-Id": UUID_REQUEST,
        "X-Atlas-Capability": capability,
    }


def android_headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {ANDROID_TOKEN}"}


def day_plan(*, observed_at: str | None = None) -> dict:
    local_date = datetime.now(timezone.utc).date().isoformat()
    return {
        "plan_id": UUID_PLAN,
        "local_date": local_date,
        "timezone": "Europe/Paris",
        "version": 7,
        "current_revision_id": UUID_REVISION,
        "state": "APPROVED",
        "origin": "LOCAL",
        "observed_at": observed_at or stamp(),
        "activities": [{
            "activity_id": UUID_ACTIVITY,
            "kind": "FOCUS",
            "title": "Bloc de lecture",
            "status": "PLANNED",
            "scheduled_local": {
                "local_date": local_date,
                "start_minute": 600,
                "duration_minutes": 45,
                "timezone": "Europe/Paris",
            },
            "priority": "OPTIONAL",
            "goal_id": None,
            "continuity_id": UUID_CONTINUITY,
            "supersedes_activity_id": None,
            "status_provenance": "UNKNOWN",
            "status_decided_at": None,
        }],
    }


def proposal_args(idempotency_key: str, target: int = 660, *, base_version: int = 7) -> dict:
    return {
        "plan_id": UUID_PLAN,
        "base_version": base_version,
        "changes": [{"op": "reschedule", "activity_id": UUID_ACTIVITY, "target_start_minute": target}],
        "justification": "Déplacer la séance pour préserver le créneau choisi.",
        "idempotency_key": idempotency_key,
    }


def post_plan(client: ASGITestClient, *, observed_at: str | None = None, consent_version: int = 3) -> dict:
    body = {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": UUID_REQUEST,
        "capability": "day_plan.snapshot.write",
        "sent_at": stamp(),
        "consent_version": consent_version,
        "idempotency_key": "00000000-0000-4000-8000-000000000206",
        "data": day_plan(observed_at=observed_at),
    }
    response = client.post("/v4/sync/day-plan-snapshots", headers=android_headers(), json=body)
    assert response.status_code == 200, response.text
    return response.json()


def post_proposal(client: TestClient, key: str, target: int = 660, *, base_version: int = 7):
    return client.post(
        "/internal/v4/plan-revision-proposals",
        headers=tool_headers("plan_revision.propose"),
        json=proposal_args(key, target, base_version=base_version),
    )


def validate(schema_name: str, value: dict) -> None:
    schema = json.loads((CONTRACTS / schema_name).read_text(encoding="utf-8"))
    Draft202012Validator(schema, format_checker=FormatChecker()).validate(value)


def error_code(response) -> str:
    body = response.json()
    validate("error.schema.json", body)
    return body["error"]["code"]


def ack_body(proposal_id: str, ack_id: str, state: str, result_code: str, *, sequence: int = 1,
             consent_version: int = 3) -> dict:
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
            "plan_id": UUID_PLAN,
            "applied_plan_version": 8,
            "revision_id": "00000000-0000-4000-8000-000000000207",
            "scope_hash": "sha256:" + "a" * 64,
            "effect_ids": [
                f"PLAN_ACTIVITY_UPDATE:{UUID_ACTIVITY}",
                "PLAN_APPLY:00000000-0000-4000-8000-000000000207",
            ],
        })
    return body


def test_proposal_validation_idempotency_lookup_and_plan_is_not_applied(tmp_path, monkeypatch):
    client = make_client(tmp_path, monkeypatch)
    post_plan(client)

    first = post_proposal(client, "00000000-0000-4000-8000-000000000211")
    assert first.status_code == 200, first.text
    validate("tool-results.schema.json", first.json())
    assert first.json()["data"]["status"] == "QUEUED"
    proposal_id = first.json()["data"]["proposal_id"]

    replay = post_proposal(client, "00000000-0000-4000-8000-000000000211")
    assert replay.status_code == 200, replay.text
    assert replay.json()["data"]["proposal_id"] == proposal_id

    conflict = post_proposal(client, "00000000-0000-4000-8000-000000000211", target=700)
    assert conflict.status_code == 409
    assert error_code(conflict) == "IDEMPOTENCY_CONFLICT"

    lookup = client.get(
        "/internal/v4/agent/proposals/by-idempotency/00000000-0000-4000-8000-000000000211",
        headers=tool_headers("plan_revision.propose"),
    )
    assert lookup.status_code == 200, lookup.text
    validate("proposal-inbox-ack.schema.json", lookup.json())
    assert lookup.json()["proposal_id"] == proposal_id
    assert "body_hash" not in lookup.json()

    plan = client.get("/internal/v4/day-plan", headers=tool_headers("day_plan.read"))
    assert plan.status_code == 200, plan.text
    assert plan.json()["data"]["plan"]["version"] == 7
    assert plan.json()["data"]["plan"]["activities"][0]["scheduled_local"]["start_minute"] == 600

    stale = post_proposal(client, "00000000-0000-4000-8000-000000000212", base_version=6)
    assert stale.status_code == 200, stale.text
    validate("tool-results.schema.json", stale.json())
    assert stale.json()["data"]["status"] == "STALE_VERSION"
    client.close()


def test_inbox_cursor_pagination_ack_states_and_replay_conflicts(tmp_path, monkeypatch):
    client = make_client(tmp_path, monkeypatch)
    post_plan(client)
    keys = [
        "00000000-0000-4000-8000-000000000221",
        "00000000-0000-4000-8000-000000000222",
        "00000000-0000-4000-8000-000000000223",
        "00000000-0000-4000-8000-000000000224",
    ]
    proposals = []
    for index, key in enumerate(keys):
        response = post_proposal(client, key, target=650 + index * 10)
        assert response.status_code == 200, response.text
        proposals.append(response.json()["data"]["proposal_id"])

    pull_body = {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": UUID_REQUEST,
        "capability": "proposal.ack",
        "consent_version": 3,
        "page_size": 1,
        "cursor": None,
    }
    first_page = client.post("/v4/agent/proposals/pull", headers=android_headers(), json=pull_body)
    assert first_page.status_code == 200, first_page.text
    assert len(first_page.json()["proposals"]) == 1
    validate("proposal-inbox-ack.schema.json", first_page.json()["proposals"][0])
    cursor = first_page.json()["next_cursor"]
    assert cursor
    second_body = {**pull_body, "request_id": "00000000-0000-4000-8000-000000000225", "cursor": cursor}
    second_page = client.post("/v4/agent/proposals/pull", headers=android_headers(), json=second_body)
    assert second_page.status_code == 200, second_page.text
    assert len(second_page.json()["proposals"]) == 1
    assert first_page.json()["proposals"][0]["proposal_id"] != second_page.json()["proposals"][0]["proposal_id"]
    bad_cursor = {**second_body, "cursor": cursor[:-1] + ("A" if cursor[-1] != "A" else "B")}
    invalid_cursor = client.post("/v4/agent/proposals/pull", headers=android_headers(), json=bad_cursor)
    assert invalid_cursor.status_code == 400
    assert error_code(invalid_cursor) == "CURSOR_INVALID"

    applied_id = proposals[0]
    applied = ack_body(applied_id, "00000000-0000-4000-8000-000000000231", "APPLIED", "APPLIED")
    response = client.post(f"/v4/agent/proposals/{applied_id}/ack", headers=android_headers(), json=applied)
    assert response.status_code == 200, response.text
    validate("proposal-inbox-ack.schema.json", response.json())
    replay = client.post(f"/v4/agent/proposals/{applied_id}/ack", headers=android_headers(), json=applied)
    assert replay.status_code == 200
    assert replay.headers["x-atlas-replayed"] == "true"
    changed_replay = {**applied, "occurred_at": stamp(datetime.now(timezone.utc) + timedelta(seconds=1))}
    conflict = client.post(f"/v4/agent/proposals/{applied_id}/ack", headers=android_headers(), json=changed_replay)
    assert conflict.status_code == 409
    assert error_code(conflict) == "ACK_ID_CONFLICT"

    rejected_id = proposals[1]
    rejected = ack_body(rejected_id, "00000000-0000-4000-8000-000000000232", "REJECTED", "USER_REJECTED")
    response = client.post(f"/v4/agent/proposals/{rejected_id}/ack", headers=android_headers(), json=rejected)
    assert response.status_code == 200, response.text
    validate("proposal-inbox-ack.schema.json", response.json())

    uncertain_id = proposals[2]
    uncertain = ack_body(uncertain_id, "00000000-0000-4000-8000-000000000233", "UNCERTAIN", "RESULT_UNCERTAIN")
    response = client.post(f"/v4/agent/proposals/{uncertain_id}/ack", headers=android_headers(), json=uncertain)
    assert response.status_code == 200, response.text
    reconciled = ack_body(
        uncertain_id, "00000000-0000-4000-8000-000000000234", "RECONCILED", "RECONCILED_NOT_APPLIED", sequence=2
    )
    response = client.post(f"/v4/agent/proposals/{uncertain_id}/ack", headers=android_headers(), json=reconciled)
    assert response.status_code == 200, response.text
    validate("proposal-inbox-ack.schema.json", response.json())

    expired_id = proposals[3]
    with client.app.state.store.connect() as connection:
        connection.execute(
            "UPDATE agent_proposals_v4 SET expires_at=? WHERE proposal_id=?",
            (stamp(datetime.now(timezone.utc) - timedelta(minutes=1)), expired_id),
        )
    late_applied = ack_body(expired_id, "00000000-0000-4000-8000-000000000237", "APPLIED", "APPLIED")
    response = client.post(f"/v4/agent/proposals/{expired_id}/ack", headers=android_headers(), json=late_applied)
    assert response.status_code == 409
    assert error_code(response) == "PROPOSAL_EXPIRED"
    expired = ack_body(expired_id, "00000000-0000-4000-8000-000000000235", "EXPIRED", "PROPOSAL_EXPIRED")
    response = client.post(f"/v4/agent/proposals/{expired_id}/ack", headers=android_headers(), json=expired)
    assert response.status_code == 200, response.text
    validate("proposal-inbox-ack.schema.json", response.json())
    replay = client.post(f"/v4/agent/proposals/{expired_id}/ack", headers=android_headers(), json=expired)
    assert replay.status_code == 200
    assert replay.headers["x-atlas-replayed"] == "true"

    wrong_consent = ack_body(
        rejected_id, "00000000-0000-4000-8000-000000000236", "REJECTED", "USER_REJECTED", sequence=2,
        consent_version=2,
    )
    response = client.post(f"/v4/agent/proposals/{rejected_id}/ack", headers=android_headers(), json=wrong_consent)
    assert response.status_code == 403
    assert error_code(response) == "CONSENT_REQUIRED"
    client.close()


def test_proposal_requires_fresh_snapshot_and_never_accepts_arbitrary_route_data(tmp_path, monkeypatch):
    client = make_client(tmp_path, monkeypatch)
    old = stamp(datetime.now(timezone.utc) - timedelta(hours=1))
    post_plan(client, observed_at=old)
    stale = post_proposal(client, "00000000-0000-4000-8000-000000000241")
    assert stale.status_code == 409
    assert error_code(stale) == "STALE_DATA"

    arbitrary = client.post(
        "/internal/v4/plan-revision-proposals?url=http://example.invalid",
        headers=tool_headers("plan_revision.propose"), json=proposal_args("00000000-0000-4000-8000-000000000242"),
    )
    assert arbitrary.status_code == 400
    assert error_code(arbitrary) == "BAD_REQUEST"
    client.close()
