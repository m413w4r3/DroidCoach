from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import hashlib
import json
import threading
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
import pytest
from jsonschema import Draft202012Validator, FormatChecker

from atlas_backend.main import create_app
from atlas_backend.store import SQLiteStore
from atlas_backend.v4_projection_store import V4ProjectionStore


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


def consent_body(category: str, state: str, version: int, *, key: str | None = None) -> dict:
    category_number = {
        "DAY_PLAN": 1,
        "ACTIVITY_STATUS": 2,
        "USAGE_SUMMARY": 3,
        "GOAL_PROGRESS": 4,
        "TRAINING_PLAN": 5,
    }[category]
    state_number = {"GRANTED": 1, "REVOKED": 2, "UNKNOWN": 3}[state]
    numeric_key = 500 + category_number * 100 + version * 3 + state_number
    return {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": UUID_REQUEST,
        "capability": "consent.state.write",
        "sent_at": stamp(),
        "category": category,
        "consent_version": version,
        "state": state,
        "idempotency_key": key or f"00000000-0000-4000-8000-{numeric_key:012d}",
    }


def post_consent(client: ASGITestClient, category: str, state: str, version: int,
                 *, key: str | None = None):
    response = client.post(
        "/v4/sync/consent-state", headers=android_headers(),
        json=consent_body(category, state, version, key=key),
    )
    assert response.status_code == 200, response.text
    return response


def post_plan(client: ASGITestClient, *, observed_at: str | None = None, consent_version: int = 3,
              idempotency_key: str = "00000000-0000-4000-8000-000000000206") -> dict:
    post_consent(client, "DAY_PLAN", "GRANTED", consent_version)
    body = {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": UUID_REQUEST,
        "capability": "day_plan.snapshot.write",
        "sent_at": stamp(),
        "consent_version": consent_version,
        "idempotency_key": idempotency_key,
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
    changed_cursor_char = "A" if cursor[1] != "A" else "B"
    bad_cursor = {**second_body, "cursor": cursor[:1] + changed_cursor_char + cursor[2:]}
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
    assert response.status_code == 409
    assert error_code(response) == "STALE_VERSION"
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


def test_consent_revocation_tombstone_blocks_reads_proposals_pull_and_ack_until_regrant(tmp_path, monkeypatch):
    client = make_client(tmp_path, monkeypatch)
    snapshot_observed_at = stamp()
    post_plan(client, consent_version=3, observed_at=snapshot_observed_at)

    before_revoke = client.get("/internal/v4/day-plan", headers=tool_headers("day_plan.read"))
    assert before_revoke.status_code == 200, before_revoke.text
    validate("tool-results.schema.json", before_revoke.json())
    original = post_proposal(client, "00000000-0000-4000-8000-000000000251")
    assert original.status_code == 200, original.text
    proposal_id = original.json()["data"]["proposal_id"]

    revoked = post_consent(client, "DAY_PLAN", "REVOKED", 4)
    assert revoked.json()["state"] == "REVOKED"
    old_projection = client.app.state.store
    with old_projection.connect() as connection:
        projection_count_before = connection.execute(
            "SELECT COUNT(*) FROM day_plan_snapshots_v4"
        ).fetchone()[0]
    assert projection_count_before == 1

    denied_read = client.get("/internal/v4/day-plan", headers=tool_headers("day_plan.read"))
    assert denied_read.status_code == 403
    assert error_code(denied_read) == "CONSENT_REQUIRED"
    denied_proposal = post_proposal(client, "00000000-0000-4000-8000-000000000252")
    assert denied_proposal.status_code == 403
    assert error_code(denied_proposal) == "CONSENT_REQUIRED"
    denied_lookup = client.get(
        "/internal/v4/agent/proposals/by-idempotency/00000000-0000-4000-8000-000000000251",
        headers=tool_headers("plan_revision.propose"),
    )
    assert denied_lookup.status_code == 403
    assert error_code(denied_lookup) == "CONSENT_REQUIRED"

    pull_body = {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": UUID_REQUEST,
        "capability": "proposal.ack",
        "consent_version": 4,
        "page_size": 10,
        "cursor": None,
    }
    denied_pull = client.post("/v4/agent/proposals/pull", headers=android_headers(), json=pull_body)
    assert denied_pull.status_code == 403
    assert error_code(denied_pull) == "CONSENT_REQUIRED"
    denied_ack = client.post(
        f"/v4/agent/proposals/{proposal_id}/ack", headers=android_headers(),
        json=ack_body(proposal_id, "00000000-0000-4000-8000-000000000253", "REJECTED", "USER_REJECTED",
                      consent_version=4),
    )
    assert denied_ack.status_code == 403
    assert error_code(denied_ack) == "CONSENT_REQUIRED"

    device_ref = "android-" + hashlib.sha256(ANDROID_TOKEN.encode("utf-8")).hexdigest()[:32]
    reopened = V4ProjectionStore(SQLiteStore(tmp_path / "atlas.sqlite3"))
    persisted = reopened.consent_state(device_ref, "DAY_PLAN")
    assert persisted is not None
    assert persisted["device_ref"] == device_ref
    assert persisted["category"] == "DAY_PLAN"
    assert persisted["consent_version"] == 4
    assert persisted["state"] == "REVOKED"
    assert persisted["source"] == "ANDROID_SYNC"
    with reopened.store.connect() as connection:
        tombstone_count = connection.execute(
            "SELECT COUNT(*) FROM consent_state_events_v4 WHERE device_ref=? AND category='DAY_PLAN' "
            "AND consent_version=4 AND state='REVOKED'",
            (device_ref,),
        ).fetchone()[0]
        projection_count_after = connection.execute(
            "SELECT COUNT(*) FROM day_plan_snapshots_v4"
        ).fetchone()[0]
    assert tombstone_count == 1
    assert projection_count_after == projection_count_before

    regressive = client.post(
        "/v4/sync/consent-state", headers=android_headers(),
        json=consent_body("DAY_PLAN", "GRANTED", 3),
    )
    assert regressive.status_code == 409
    assert error_code(regressive) == "STALE_VERSION"

    post_consent(client, "DAY_PLAN", "GRANTED", 5)
    stale_regrant_read = client.get("/internal/v4/day-plan", headers=tool_headers("day_plan.read"))
    assert stale_regrant_read.status_code == 409
    assert error_code(stale_regrant_read) == "STALE_DATA"
    stale_regrant_proposal = post_proposal(client, "00000000-0000-4000-8000-000000000255")
    assert stale_regrant_proposal.status_code == 409
    assert error_code(stale_regrant_proposal) == "STALE_DATA"
    stale_regrant_lookup = client.get(
        "/internal/v4/agent/proposals/by-idempotency/00000000-0000-4000-8000-000000000251",
        headers=tool_headers("plan_revision.propose"),
    )
    assert stale_regrant_lookup.status_code == 409
    assert error_code(stale_regrant_lookup) == "STALE_DATA"
    stale_regrant_pull = client.post(
        "/v4/agent/proposals/pull", headers=android_headers(),
        json={**pull_body, "consent_version": 5},
    )
    assert stale_regrant_pull.status_code == 409
    assert error_code(stale_regrant_pull) == "STALE_DATA"
    stale_regrant_ack = client.post(
        f"/v4/agent/proposals/{proposal_id}/ack", headers=android_headers(),
        json=ack_body(proposal_id, "00000000-0000-4000-8000-000000000257", "REJECTED", "USER_REJECTED",
                      consent_version=5),
    )
    assert stale_regrant_ack.status_code == 409
    assert error_code(stale_regrant_ack) == "STALE_DATA"

    post_plan(
        client, consent_version=5, observed_at=snapshot_observed_at,
        idempotency_key="00000000-0000-4000-8000-000000000260",
    )
    after_fresh_snapshot = client.get("/internal/v4/day-plan", headers=tool_headers("day_plan.read"))
    assert after_fresh_snapshot.status_code == 200, after_fresh_snapshot.text
    validate("tool-results.schema.json", after_fresh_snapshot.json())
    regranted_proposal = post_proposal(client, "00000000-0000-4000-8000-000000000258")
    assert regranted_proposal.status_code == 200, regranted_proposal.text
    regranted_lookup = client.get(
        "/internal/v4/agent/proposals/by-idempotency/00000000-0000-4000-8000-000000000258",
        headers=tool_headers("plan_revision.propose"),
    )
    assert regranted_lookup.status_code == 200, regranted_lookup.text
    validate("proposal-inbox-ack.schema.json", regranted_lookup.json())
    regranted_pull = client.post(
        "/v4/agent/proposals/pull", headers=android_headers(),
        json={**pull_body, "consent_version": 5},
    )
    assert regranted_pull.status_code == 200, regranted_pull.text
    validate("proposal-inbox-ack.schema.json", regranted_pull.json()["proposals"][0])

    post_consent(client, "DAY_PLAN", "UNKNOWN", 6)
    unknown_read = client.get("/internal/v4/day-plan", headers=tool_headers("day_plan.read"))
    assert unknown_read.status_code == 403
    assert error_code(unknown_read) == "CONSENT_REQUIRED"
    unknown_proposal = post_proposal(client, "00000000-0000-4000-8000-000000000256")
    assert unknown_proposal.status_code == 403
    assert error_code(unknown_proposal) == "CONSENT_REQUIRED"
    unknown_pull = client.post(
        "/v4/agent/proposals/pull", headers=android_headers(),
        json={**pull_body, "consent_version": 6},
    )
    assert unknown_pull.status_code == 403
    assert error_code(unknown_pull) == "CONSENT_REQUIRED"
    unknown_ack = client.post(
        f"/v4/agent/proposals/{proposal_id}/ack", headers=android_headers(),
        json=ack_body(proposal_id, "00000000-0000-4000-8000-000000000259", "REJECTED", "USER_REJECTED",
                      consent_version=6),
    )
    assert unknown_ack.status_code == 403
    assert error_code(unknown_ack) == "CONSENT_REQUIRED"
    client.close()


@pytest.mark.parametrize("route", ["lookup", "pull"])
def test_revocation_wins_before_proposal_inbox_read(tmp_path, monkeypatch, route: str):
    client = make_client(tmp_path, monkeypatch)
    post_plan(client, consent_version=5)
    idempotency_key = "00000000-0000-4000-8000-000000000271"
    proposal_response = post_proposal(client, idempotency_key)
    assert proposal_response.status_code == 200, proposal_response.text
    role = threading.local()
    original_transaction = SQLiteStore.transaction
    original_consent_write = V4ProjectionStore.save_consent_state
    revocation_written = threading.Event()
    release_revocation = threading.Event()
    protected_transaction_attempt = threading.Event()

    @contextmanager
    def observed_transaction(store, **kwargs):
        if getattr(role, "name", None) == "protected":
            protected_transaction_attempt.set()
        with original_transaction(store, **kwargs) as connection:
            yield connection

    def pause_after_revocation_write(store, device_ref, category, version, state, **kwargs):
        receipt = original_consent_write(store, device_ref, category, version, state, **kwargs)
        if getattr(role, "name", None) == "revoker" and category == "DAY_PLAN" and state == "REVOKED":
            revocation_written.set()
            if not release_revocation.wait(timeout=5):
                raise TimeoutError("revocation test barrier was not released")
        return receipt

    monkeypatch.setattr(SQLiteStore, "transaction", observed_transaction)
    monkeypatch.setattr(V4ProjectionStore, "save_consent_state", pause_after_revocation_write)

    def revoke():
        role.name = "revoker"
        try:
            return client.post(
                "/v4/sync/consent-state",
                headers=android_headers(),
                json=consent_body("DAY_PLAN", "REVOKED", 6,
                                  key="00000000-0000-4000-8000-000000000272"),
            )
        finally:
            role.name = None

    def read_proposals():
        role.name = "protected"
        try:
            if route == "lookup":
                return client.get(
                    f"/internal/v4/agent/proposals/by-idempotency/{idempotency_key}",
                    headers=tool_headers("plan_revision.propose"),
                )
            return client.post(
                "/v4/agent/proposals/pull",
                headers=android_headers(),
                json={
                    "contract_version": 4,
                    "schema_version": 4,
                    "request_id": UUID_REQUEST,
                    "capability": "proposal.ack",
                    "consent_version": 5,
                    "page_size": 10,
                    "cursor": None,
                },
            )
        finally:
            role.name = None

    with ThreadPoolExecutor(max_workers=2, thread_name_prefix="v4-inbox-race") as pool:
        revoke_future = pool.submit(revoke)
        try:
            assert revocation_written.wait(timeout=3)
            read_future = pool.submit(read_proposals)
            assert protected_transaction_attempt.wait(timeout=3)
            assert not read_future.done()
        finally:
            release_revocation.set()
        revoke_response = revoke_future.result(timeout=5)
        response = read_future.result(timeout=5)

    assert revoke_response.status_code == 200, revoke_response.text
    assert response.status_code == 403, response.text
    assert error_code(response) == "CONSENT_REQUIRED"
    assert "proposal_id" not in response.json()
    assert "proposals" not in response.json()
    client.close()


def test_concurrent_revocation_waits_for_read_transaction_after_projection_read(tmp_path, monkeypatch):
    client = make_client(tmp_path, monkeypatch)
    observed_at = stamp()
    post_plan(client, consent_version=3, observed_at=observed_at)
    role = threading.local()
    projection_read = threading.Event()
    release_read = threading.Event()
    revoke_transaction_attempt = threading.Event()
    original_transaction = SQLiteStore.transaction
    original_snapshot_reader = V4ProjectionStore.latest_day_plan_snapshot

    @contextmanager
    def observed_transaction(store, **kwargs):
        if getattr(role, "name", None) == "revoker":
            revoke_transaction_attempt.set()
        with original_transaction(store, **kwargs) as connection:
            yield connection

    def pause_after_snapshot_read(store, device_ref: str, **kwargs):
        snapshot = original_snapshot_reader(store, device_ref, **kwargs)
        if getattr(role, "name", None) == "reader":
            projection_read.set()
            if not release_read.wait(timeout=5):
                raise TimeoutError("read test barrier was not released")
        return snapshot

    monkeypatch.setattr(SQLiteStore, "transaction", observed_transaction)
    monkeypatch.setattr(V4ProjectionStore, "latest_day_plan_snapshot", pause_after_snapshot_read)

    def read_plan():
        role.name = "reader"
        try:
            return client.get("/internal/v4/day-plan", headers=tool_headers("day_plan.read"))
        finally:
            role.name = None

    def revoke():
        role.name = "revoker"
        try:
            return client.post(
                "/v4/sync/consent-state",
                headers=android_headers(),
                json=consent_body("DAY_PLAN", "REVOKED", 4,
                                  key="00000000-0000-4000-8000-000000000281"),
            )
        finally:
            role.name = None

    with ThreadPoolExecutor(max_workers=2, thread_name_prefix="v4-read-race") as pool:
        read_future = pool.submit(read_plan)
        assert projection_read.wait(timeout=3)
        revoke_future = pool.submit(revoke)
        assert revoke_transaction_attempt.wait(timeout=3)
        assert not revoke_future.done()
        release_read.set()
        read_response = read_future.result(timeout=5)
        revoke_response = revoke_future.result(timeout=5)

    assert read_response.status_code == 200, read_response.text
    validate("tool-results.schema.json", read_response.json())
    assert revoke_response.status_code == 200, revoke_response.text
    client.close()


@pytest.mark.parametrize("effect", ["proposal", "sync", "ack"])
def test_revocation_transaction_wins_before_v4_effect(tmp_path, monkeypatch, effect: str):
    client = make_client(tmp_path, monkeypatch)
    observed_at = stamp()
    post_plan(client, consent_version=3, observed_at=observed_at)
    initial_proposal = post_proposal(client, "00000000-0000-4000-8000-000000000282")
    assert initial_proposal.status_code == 200, initial_proposal.text
    proposal_id = initial_proposal.json()["data"]["proposal_id"]

    effect_method = {
        "proposal": "create_agent_proposal",
        "sync": "save_day_plan_snapshot",
        "ack": "append_proposal_ack",
    }[effect]
    original_effect = getattr(V4ProjectionStore, effect_method)
    original_consent_write = V4ProjectionStore.save_consent_state
    original_transaction = SQLiteStore.transaction
    role = threading.local()
    revocation_written = threading.Event()
    release_revocation = threading.Event()
    effect_transaction_attempt = threading.Event()
    effect_started = threading.Event()

    @contextmanager
    def observed_transaction(store, **kwargs):
        if getattr(role, "name", None) == "effect":
            effect_transaction_attempt.set()
        with original_transaction(store, **kwargs) as connection:
            yield connection

    def pause_after_revocation_write(store, device_ref, category, version, state, **kwargs):
        receipt = original_consent_write(store, device_ref, category, version, state, **kwargs)
        if getattr(role, "name", None) == "revoker" and category == "DAY_PLAN" and state == "REVOKED":
            revocation_written.set()
            if not release_revocation.wait(timeout=5):
                raise TimeoutError("revocation test barrier was not released")
        return receipt

    def observe_effect(store, *args, **kwargs):
        if getattr(role, "name", None) == "effect":
            effect_started.set()
        return original_effect(store, *args, **kwargs)

    monkeypatch.setattr(SQLiteStore, "transaction", observed_transaction)
    monkeypatch.setattr(V4ProjectionStore, "save_consent_state", pause_after_revocation_write)
    monkeypatch.setattr(V4ProjectionStore, effect_method, observe_effect)

    def revoke():
        role.name = "revoker"
        try:
            return client.post(
                "/v4/sync/consent-state",
                headers=android_headers(),
                json=consent_body("DAY_PLAN", "REVOKED", 4,
                                  key="00000000-0000-4000-8000-000000000283"),
            )
        finally:
            role.name = None

    def run_effect():
        role.name = "effect"
        try:
            if effect == "proposal":
                return post_proposal(client, "00000000-0000-4000-8000-000000000284")
            if effect == "sync":
                snapshot = day_plan(observed_at=observed_at)
                snapshot["version"] = 8
                snapshot["current_revision_id"] = "00000000-0000-4000-8000-000000000285"
                return client.post(
                    "/v4/sync/day-plan-snapshots",
                    headers=android_headers(),
                    json={
                        "contract_version": 4,
                        "schema_version": 4,
                        "request_id": UUID_REQUEST,
                        "capability": "day_plan.snapshot.write",
                        "sent_at": stamp(),
                        "consent_version": 3,
                        "idempotency_key": "00000000-0000-4000-8000-000000000286",
                        "data": snapshot,
                    },
                )
            ack_id = "00000000-0000-4000-8000-000000000287"
            return client.post(
                f"/v4/agent/proposals/{proposal_id}/ack",
                headers=android_headers(),
                json=ack_body(proposal_id, ack_id, "REJECTED", "USER_REJECTED", consent_version=3),
            )
        finally:
            role.name = None

    with ThreadPoolExecutor(max_workers=2, thread_name_prefix="v4-effect-race") as pool:
        revoke_future = pool.submit(revoke)
        try:
            assert revocation_written.wait(timeout=3)
            effect_future = pool.submit(run_effect)
            assert effect_transaction_attempt.wait(timeout=3)
            assert not effect_future.done()
        finally:
            release_revocation.set()
        revoke_response = revoke_future.result(timeout=5)
        effect_response = effect_future.result(timeout=5)

    assert revoke_response.status_code == 200, revoke_response.text
    assert effect_response.status_code == 403, effect_response.text
    assert error_code(effect_response) == "CONSENT_REQUIRED"
    assert not effect_started.is_set()
    with client.app.state.store.connect() as connection:
        if effect == "proposal":
            residual_count = connection.execute(
                "SELECT COUNT(*) FROM agent_proposals_v4 WHERE idempotency_key=?",
                ("00000000-0000-4000-8000-000000000284",),
            ).fetchone()[0]
        elif effect == "sync":
            residual_count = connection.execute(
                "SELECT COUNT(*) FROM day_plan_snapshots_v4 WHERE plan_version=8"
            ).fetchone()[0]
        else:
            residual_count = connection.execute(
                "SELECT COUNT(*) FROM agent_proposal_events_v4 WHERE ack_id=?",
                ("00000000-0000-4000-8000-000000000287",),
            ).fetchone()[0]
    assert residual_count == 0
    client.close()


def test_ack_insert_is_serialized_before_waiting_revocation(tmp_path, monkeypatch):
    client = make_client(tmp_path, monkeypatch)
    post_plan(client, consent_version=3)
    proposal_response = post_proposal(client, "00000000-0000-4000-8000-000000000288")
    assert proposal_response.status_code == 200, proposal_response.text
    proposal_id = proposal_response.json()["data"]["proposal_id"]
    ack_id = "00000000-0000-4000-8000-000000000289"
    role = threading.local()
    original_transaction = SQLiteStore.transaction
    original_append_ack = V4ProjectionStore.append_proposal_ack
    ack_before_insert = threading.Event()
    release_ack = threading.Event()
    revocation_transaction_attempt = threading.Event()

    @contextmanager
    def observed_transaction(store, **kwargs):
        if getattr(role, "name", None) == "revoker":
            revocation_transaction_attempt.set()
        with original_transaction(store, **kwargs) as connection:
            yield connection

    def pause_before_ack_insert(store, ack, **kwargs):
        if getattr(role, "name", None) == "ack":
            ack_before_insert.set()
            if not release_ack.wait(timeout=5):
                raise TimeoutError("ACK test barrier was not released")
        return original_append_ack(store, ack, **kwargs)

    monkeypatch.setattr(SQLiteStore, "transaction", observed_transaction)
    monkeypatch.setattr(V4ProjectionStore, "append_proposal_ack", pause_before_ack_insert)

    def send_ack():
        role.name = "ack"
        try:
            return client.post(
                f"/v4/agent/proposals/{proposal_id}/ack",
                headers=android_headers(),
                json=ack_body(proposal_id, ack_id, "REJECTED", "USER_REJECTED", consent_version=3),
            )
        finally:
            role.name = None

    def revoke():
        role.name = "revoker"
        try:
            return client.post(
                "/v4/sync/consent-state",
                headers=android_headers(),
                json=consent_body("DAY_PLAN", "REVOKED", 4,
                                  key="00000000-0000-4000-8000-000000000290"),
            )
        finally:
            role.name = None

    with ThreadPoolExecutor(max_workers=2, thread_name_prefix="v4-ack-race") as pool:
        ack_future = pool.submit(send_ack)
        assert ack_before_insert.wait(timeout=3)
        revoke_future = pool.submit(revoke)
        assert revocation_transaction_attempt.wait(timeout=3)
        assert not revoke_future.done()
        release_ack.set()
        ack_response = ack_future.result(timeout=5)
        revoke_response = revoke_future.result(timeout=5)

    assert ack_response.status_code == 200, ack_response.text
    assert revoke_response.status_code == 200, revoke_response.text
    with client.app.state.store.connect() as connection:
        ack_count = connection.execute(
            "SELECT COUNT(*) FROM agent_proposal_events_v4 WHERE ack_id=?", (ack_id,)
        ).fetchone()[0]
    assert ack_count == 1
    current_consent = V4ProjectionStore(client.app.state.store).consent_state(
        "android-" + hashlib.sha256(ANDROID_TOKEN.encode("utf-8")).hexdigest()[:32], "DAY_PLAN"
    )
    assert current_consent is not None
    assert current_consent["state"] == "REVOKED"
    assert current_consent["consent_version"] == 4
    client.close()
