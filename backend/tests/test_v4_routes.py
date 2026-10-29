from __future__ import annotations

import asyncio
import json
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
import pytest
from jsonschema import Draft202012Validator, FormatChecker

from atlas_backend.main import MAX_REQUEST_BYTES, create_app


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts" / "v4"
UUID_PLAN = "00000000-0000-4000-8000-000000000101"
UUID_REVISION = "00000000-0000-4000-8000-000000000102"
UUID_ACTIVITY = "00000000-0000-4000-8000-000000000103"
UUID_CONTINUITY = "00000000-0000-4000-8000-000000000104"
UUID_REQUEST = "00000000-0000-4000-8000-000000000105"
UUID_IDEMPOTENCY = "00000000-0000-4000-8000-000000000106"
ANDROID_TOKEN = "android-test-secret"
TOOL_TOKEN = "tool-test-secret"
READ_CAPABILITIES = {
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


def make_client(tmp_path: Path, monkeypatch, *, capabilities: dict[str, bool] | None = None,
                android_token: str = ANDROID_TOKEN, tool_token: str = TOOL_TOKEN,
                client_address: tuple[str, int] = ("127.0.0.1", 50100)) -> ASGITestClient:
    monkeypatch.setenv("ATLAS_TOOL_TOKEN", tool_token)
    monkeypatch.setenv("ATLAS_V4_CAPABILITIES_JSON", json.dumps(READ_CAPABILITIES if capabilities is None else capabilities))
    return ASGITestClient(create_app(db_path=tmp_path / "atlas.sqlite3", token=android_token),
                          client_address)


def tool_headers(capability: str, token: str = TOOL_TOKEN) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {token}",
        "X-Atlas-Contract-Version": "4",
        "X-Atlas-Schema-Version": "4",
        "X-Atlas-Request-Id": UUID_REQUEST,
        "X-Atlas-Capability": capability,
    }


def android_headers(token: str = ANDROID_TOKEN) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def day_plan(*, observed_at: str | None = None, version: int = 7) -> dict:
    local_date = datetime.now(timezone.utc).date().isoformat()
    return {
        "plan_id": UUID_PLAN,
        "local_date": local_date,
        "timezone": "Europe/Paris",
        "version": version,
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


def sync_envelope(capability: str, data: dict, *, consent_version: int = 3,
                  request_id: str = UUID_REQUEST, idempotency_key: str = UUID_IDEMPOTENCY) -> dict:
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


def consent_envelope(category: str, state: str, version: int, *, key: str | None = None) -> dict:
    category_number = {
        "DAY_PLAN": 1,
        "ACTIVITY_STATUS": 2,
        "USAGE_SUMMARY": 3,
        "GOAL_PROGRESS": 4,
        "TRAINING_PLAN": 5,
    }[category]
    state_number = {"GRANTED": 1, "REVOKED": 2, "UNKNOWN": 3}[state]
    numeric_key = 300 + category_number * 100 + version * 3 + state_number
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
                 *, key: str | None = None, token: str = ANDROID_TOKEN) -> dict:
    response = client.post(
        "/v4/sync/consent-state",
        headers=android_headers(token),
        json=consent_envelope(category, state, version, key=key),
    )
    assert response.status_code == 200, response.text
    schema = json.loads((CONTRACTS / "sync.schema.json").read_text(encoding="utf-8"))["$defs"]["consent_state_receipt"]
    Draft202012Validator(schema, format_checker=FormatChecker()).validate(response.json())
    return response.json()


def post_plan(client: ASGITestClient, *, observed_at: str | None = None, consent_version: int = 3,
              token: str = ANDROID_TOKEN, consent_key: str | None = None) -> dict:
    post_consent(client, "DAY_PLAN", "GRANTED", consent_version, key=consent_key, token=token)
    response = client.post(
        "/v4/sync/day-plan-snapshots",
        headers=android_headers(token),
        json=sync_envelope("day_plan.snapshot.write", day_plan(observed_at=observed_at),
                           consent_version=consent_version),
    )
    assert response.status_code == 200, response.text
    return response.json()


def activity_status_data(event_id: str, *, sequence: int = 1) -> dict:
    return {
        "event_id": event_id,
        "sequence": sequence,
        "plan_id": UUID_PLAN,
        "activity_id": UUID_ACTIVITY,
        "continuity_id": UUID_CONTINUITY,
        "plan_version": 7,
        "from_status": "PLANNED",
        "to_status": "IN_PROGRESS",
        "provenance": "CONFIRMED",
        "source": "USER_UI",
        "decided_at": stamp(),
        "observed_at": stamp(),
        "focus_session_id": None,
    }


def activity_status_row_count(client: ASGITestClient, event_id: str | None = None) -> int:
    with client.app.state.store.connect() as connection:
        if event_id is None:
            row = connection.execute("SELECT COUNT(*) AS count FROM activity_status_events_v4").fetchone()
        else:
            row = connection.execute(
                "SELECT COUNT(*) AS count FROM activity_status_events_v4 WHERE event_id=?", (event_id,)
            ).fetchone()
    return int(row["count"])


def validate(schema_name: str, result: dict) -> None:
    schema = json.loads((CONTRACTS / schema_name).read_text(encoding="utf-8"))
    Draft202012Validator(schema, format_checker=FormatChecker()).validate(result)


def validate_error(response, expected_code: str | None = None) -> dict:
    payload = response.json()
    validate("error.schema.json", payload)
    if expected_code:
        assert payload["error"]["code"] == expected_code
    return payload


def test_sync_snapshots_and_six_fixed_tool_routes_match_contracts(tmp_path, monkeypatch):
    client = make_client(tmp_path, monkeypatch)
    post_consent(client, "DAY_PLAN", "GRANTED", 3)
    plan_body = sync_envelope("day_plan.snapshot.write", day_plan())
    plan_response = client.post("/v4/sync/day-plan-snapshots", headers=android_headers(), json=plan_body)
    assert plan_response.status_code == 200, plan_response.text
    plan_receipt = plan_response.json()
    validate("sync-receipts.schema.json", plan_receipt)
    assert plan_receipt["result_code"] == "ACCEPTED"
    plan_replay = client.post("/v4/sync/day-plan-snapshots", headers=android_headers(), json=plan_body)
    assert plan_replay.status_code == 200, plan_replay.text
    assert plan_replay.json()["replayed"] is True
    conflicting_plan = json.loads(json.dumps(plan_body))
    conflicting_plan["data"]["activities"][0]["title"] = "Titre modifié"
    plan_conflict = client.post("/v4/sync/day-plan-snapshots", headers=android_headers(), json=conflicting_plan)
    assert plan_conflict.status_code == 409
    validate_error(plan_conflict, "CONFLICT")

    now = datetime.now(timezone.utc).replace(microsecond=0)
    start = stamp(now - timedelta(hours=1))
    end = stamp(now - timedelta(minutes=1))
    usage_data = {
        "window_start": start,
        "window_end": end,
        "timezone": "Europe/Paris",
        "total_foreground_ms": 120_000,
        "apps": [{
            "app_ref": "synthetic-app",
            "display_name": "Example Reader",
            "foreground_ms": 120_000,
            "launch_count": 2,
            "last_used_at": end,
            "reliability": "HIGH",
        }],
        "coverage": {"observed_ms": 120_000, "blind_ms": 0, "events_seen": 2, "truncated": False},
        "generated_at": end,
        "consent_version": 3,
    }
    goals_data = {"goals_version": 2, "observed_at": stamp(), "goals": []}
    training_data = {
        "provider": "wger",
        "source": "WGER_API",
        "sync_state": "FRESH",
        "observed_at": stamp(),
        "source_updated_at": stamp(),
        "routines": [],
        "sessions": [],
        "results": [],
    }
    sync_payloads = []
    for category in ("ACTIVITY_STATUS", "USAGE_SUMMARY", "GOAL_PROGRESS", "TRAINING_PLAN"):
        post_consent(client, category, "GRANTED", 3)
    for route, capability, data in (
        ("/v4/sync/usage-summaries", "usage_summary.write", usage_data),
        ("/v4/sync/goal-progress-snapshots", "goal_progress.write", goals_data),
        ("/v4/sync/training-snapshots", "training_plan.write", training_data),
    ):
        envelope = sync_envelope(capability, data)
        response = client.post(route, headers=android_headers(), json=envelope)
        assert response.status_code == 200, response.text
        validate("sync-receipts.schema.json", response.json())
        replay = client.post(route, headers=android_headers(), json=envelope)
        assert replay.status_code == 200, replay.text
        assert replay.json()["replayed"] is True
        sync_payloads.append((route, capability, envelope))

    for route, capability, envelope in sync_payloads:
        changed = json.loads(json.dumps(envelope))
        if capability == "usage_summary.write":
            changed["data"]["total_foreground_ms"] += 1
        elif capability == "goal_progress.write":
            changed["data"]["observed_at"] = stamp(datetime.now(timezone.utc) + timedelta(seconds=2))
        else:
            changed["data"]["sync_state"] = "UNKNOWN"
        conflict = client.post(route, headers=android_headers(), json=changed)
        assert conflict.status_code == 409
        validate_error(conflict, "CONFLICT")

    status_data = {
        "event_id": "00000000-0000-4000-8000-000000000107",
        "sequence": 1,
        "plan_id": UUID_PLAN,
        "activity_id": UUID_ACTIVITY,
        "continuity_id": UUID_CONTINUITY,
        "plan_version": 7,
        "from_status": "PLANNED",
        "to_status": "IN_PROGRESS",
        "provenance": "CONFIRMED",
        "source": "USER_UI",
        "decided_at": stamp(),
        "observed_at": stamp(),
        "focus_session_id": None,
    }
    status_envelope = sync_envelope("activity_status.write", status_data)
    response = client.post("/v4/sync/activity-status-events", headers=android_headers(), json=status_envelope)
    assert response.status_code == 200, response.text
    validate("sync-receipts.schema.json", response.json())
    status_replay = client.post("/v4/sync/activity-status-events", headers=android_headers(), json=status_envelope)
    assert status_replay.status_code == 200, status_replay.text
    assert status_replay.json()["items"][0]["result_code"] == "DUPLICATE"
    conflicting_status = json.loads(json.dumps(status_envelope))
    conflicting_status["data"]["to_status"] = "DONE"
    status_conflict = client.post("/v4/sync/activity-status-events", headers=android_headers(), json=conflicting_status)
    assert status_conflict.status_code == 409
    validate_error(status_conflict, "EVENT_ID_CONFLICT")

    headers = tool_headers("tool_catalog.read")
    response = client.get("/internal/v4/capabilities", headers=headers)
    assert response.status_code == 200, response.text
    validate("capability-negotiation.schema.json", response.json())
    assert response.json()["capabilities"]["day_plan.read"] is True

    tool_calls = [
        ("day_plan.read", "/internal/v4/day-plan?local_date=" + day_plan()["local_date"]),
        ("activity_status.read", f"/internal/v4/activity-status/{UUID_ACTIVITY}?plan_id={UUID_PLAN}"),
        ("usage_summary.read", f"/internal/v4/usage-summary?window_start={start}&window_end={end}"),
        ("goal_progress.read", "/internal/v4/goal-progress"),
        ("training_plan.read", f"/internal/v4/training-plan?local_date={day_plan()['local_date']}"),
    ]
    for capability, path in tool_calls:
        response = client.get(path, headers=tool_headers(capability))
        assert response.status_code == 200, response.text
        validate("tool-results.schema.json", response.json())
    client.close()


def test_status_event_reconciles_before_stale_consent_and_reuses_stored_receipt(tmp_path, monkeypatch):
    client = make_client(tmp_path, monkeypatch)
    now = [datetime(2026, 10, 29, 8, 0, tzinfo=timezone.utc)]
    client.app.state.store.clock = lambda: now[0]
    post_plan(client)
    post_consent(client, "ACTIVITY_STATUS", "GRANTED", 3)
    event_id = "00000000-0000-4000-8000-000000000501"
    envelope = sync_envelope("activity_status.write", activity_status_data(event_id))

    accepted = client.post("/v4/sync/activity-status-events", headers=android_headers(), json=envelope)
    assert accepted.status_code == 200, accepted.text
    validate("sync-receipts.schema.json", accepted.json())
    accepted_item = accepted.json()["items"][0]
    assert accepted_item["result_code"] == "ACCEPTED"

    now[0] += timedelta(minutes=1)
    post_consent(client, "ACTIVITY_STATUS", "REVOKED", 4)
    now[0] += timedelta(minutes=1)
    post_consent(client, "ACTIVITY_STATUS", "GRANTED", 5)
    now[0] += timedelta(minutes=3)

    changed_body = json.loads(json.dumps(envelope))
    changed_body["data"]["to_status"] = "DONE"
    conflict = client.post(
        "/v4/sync/activity-status-events", headers=android_headers(), json=changed_body
    )
    assert conflict.status_code == 409
    validate_error(conflict, "EVENT_ID_CONFLICT")
    assert activity_status_row_count(client, event_id) == 1

    replay = client.post("/v4/sync/activity-status-events", headers=android_headers(), json=envelope)
    assert replay.status_code == 200, replay.text
    validate("sync-receipts.schema.json", replay.json())
    replay_item = replay.json()["items"][0]
    assert replay_item["event_id"] == accepted_item["event_id"]
    assert replay_item["result_code"] == "DUPLICATE"
    assert replay_item["last_synced_at"] == accepted_item["last_synced_at"]
    assert replay_item["last_synced_at"] != now[0].isoformat().replace("+00:00", "Z")
    assert activity_status_row_count(client, event_id) == 1
    client.close()


@pytest.mark.parametrize("category", ["ACTIVITY_STATUS", "DAY_PLAN"])
def test_status_event_replay_requires_current_grant_then_recovers_after_regrant(
    tmp_path, monkeypatch, category
):
    client = make_client(tmp_path, monkeypatch)
    post_plan(client)
    post_consent(client, "ACTIVITY_STATUS", "GRANTED", 3)
    event_id = "00000000-0000-4000-8000-000000000505"
    envelope = sync_envelope("activity_status.write", activity_status_data(event_id))

    accepted = client.post("/v4/sync/activity-status-events", headers=android_headers(), json=envelope)
    assert accepted.status_code == 200, accepted.text
    stored_timestamp = accepted.json()["items"][0]["last_synced_at"]
    assert activity_status_row_count(client, event_id) == 1

    post_consent(client, category, "REVOKED", 4)
    denied_replay = client.post(
        "/v4/sync/activity-status-events", headers=android_headers(), json=envelope
    )
    assert denied_replay.status_code == 403
    validate_error(denied_replay, "CONSENT_REQUIRED")
    assert "items" not in denied_replay.json()
    assert activity_status_row_count(client, event_id) == 1

    post_consent(client, category, "GRANTED", 5)
    replay = client.post("/v4/sync/activity-status-events", headers=android_headers(), json=envelope)
    assert replay.status_code == 200, replay.text
    validate("sync-receipts.schema.json", replay.json())
    assert replay.json()["items"][0]["result_code"] == "DUPLICATE"
    assert replay.json()["items"][0]["last_synced_at"] == stored_timestamp
    assert activity_status_row_count(client, event_id) == 1
    client.close()


def test_absent_status_event_with_stale_consent_is_rejected_without_write(tmp_path, monkeypatch):
    client = make_client(tmp_path, monkeypatch)
    post_plan(client)
    post_consent(client, "ACTIVITY_STATUS", "GRANTED", 4)
    event_id = "00000000-0000-4000-8000-000000000502"
    envelope = sync_envelope(
        "activity_status.write", activity_status_data(event_id), consent_version=3
    )

    response = client.post("/v4/sync/activity-status-events", headers=android_headers(), json=envelope)
    assert response.status_code == 409
    validate_error(response, "STALE_VERSION")
    assert activity_status_row_count(client) == 0
    client.close()


def test_status_event_receipt_is_not_visible_to_another_device(tmp_path, monkeypatch):
    other_token = "other-android-test-secret"
    owner = make_client(tmp_path, monkeypatch)
    other_device = make_client(tmp_path, monkeypatch, android_token=other_token)
    post_plan(owner)
    post_consent(owner, "ACTIVITY_STATUS", "GRANTED", 3)
    event_id = "00000000-0000-4000-8000-000000000503"
    envelope = sync_envelope("activity_status.write", activity_status_data(event_id))

    accepted = owner.post("/v4/sync/activity-status-events", headers=android_headers(), json=envelope)
    assert accepted.status_code == 200, accepted.text
    accepted_timestamp = accepted.json()["items"][0]["last_synced_at"]

    post_plan(
        other_device,
        token=other_token,
        consent_key="00000000-0000-4000-8000-000000000601",
    )
    post_consent(
        other_device,
        "ACTIVITY_STATUS",
        "GRANTED",
        3,
        key="00000000-0000-4000-8000-000000000602",
        token=other_token,
    )
    cross_device_replay = other_device.post(
        "/v4/sync/activity-status-events", headers=android_headers(other_token), json=envelope
    )
    assert cross_device_replay.status_code == 409
    validate_error(cross_device_replay, "EVENT_ID_CONFLICT")
    assert "items" not in cross_device_replay.json()
    assert accepted_timestamp not in cross_device_replay.text
    assert activity_status_row_count(owner, event_id) == 1

    owner_replay = owner.post("/v4/sync/activity-status-events", headers=android_headers(), json=envelope)
    assert owner_replay.status_code == 200, owner_replay.text
    assert owner_replay.json()["items"][0]["last_synced_at"] == accepted_timestamp
    owner.close()
    other_device.close()


def test_concurrent_status_event_replays_keep_one_stored_receipt(tmp_path, monkeypatch):
    client = make_client(tmp_path, monkeypatch)
    post_plan(client)
    post_consent(client, "ACTIVITY_STATUS", "GRANTED", 3)
    event_id = "00000000-0000-4000-8000-000000000504"
    envelope = sync_envelope("activity_status.write", activity_status_data(event_id))
    accepted = client.post("/v4/sync/activity-status-events", headers=android_headers(), json=envelope)
    assert accepted.status_code == 200, accepted.text
    stored_timestamp = accepted.json()["items"][0]["last_synced_at"]

    with ThreadPoolExecutor(max_workers=2, thread_name_prefix="v4-status-replay") as pool:
        responses = list(pool.map(
            lambda _index: client.post(
                "/v4/sync/activity-status-events", headers=android_headers(), json=envelope
            ),
            range(2),
        ))

    assert all(response.status_code == 200 for response in responses)
    for response in responses:
        validate("sync-receipts.schema.json", response.json())
        assert response.json()["items"][0]["result_code"] == "DUPLICATE"
        assert response.json()["items"][0]["last_synced_at"] == stored_timestamp
    assert activity_status_row_count(client, event_id) == 1
    client.close()


def test_tool_auth_is_separate_loopback_only_and_headers_are_strict(tmp_path, monkeypatch):
    client = make_client(tmp_path, monkeypatch)
    android_as_tool = client.get("/internal/v4/day-plan", headers=tool_headers("day_plan.read", ANDROID_TOKEN))
    assert android_as_tool.status_code == 401
    validate_error(android_as_tool, "AUTH_REQUIRED")

    tool_as_android = client.post(
        "/v4/sync/day-plan-snapshots", headers=android_headers(TOOL_TOKEN),
        json=sync_envelope("day_plan.snapshot.write", day_plan()),
    )
    assert tool_as_android.status_code == 401
    validate_error(tool_as_android, "AUTH_REQUIRED")

    non_loopback = make_client(tmp_path / "remote", monkeypatch, client_address=("192.0.2.20", 50101))
    response = non_loopback.get("/internal/v4/day-plan", headers=tool_headers("day_plan.read"))
    assert response.status_code == 401
    validate_error(response, "AUTH_REQUIRED")

    wrong_capability = client.get("/internal/v4/day-plan", headers=tool_headers("usage_summary.read"))
    assert wrong_capability.status_code == 400
    validate_error(wrong_capability, "BAD_REQUEST")
    missing_version = tool_headers("day_plan.read")
    del missing_version["X-Atlas-Schema-Version"]
    response = client.get("/internal/v4/day-plan", headers=missing_version)
    assert response.status_code == 400
    validate_error(response, "UNSUPPORTED_VERSION")
    client.close()
    non_loopback.close()


def test_consent_state_sync_is_android_only_typed_and_idempotent(tmp_path, monkeypatch):
    client = make_client(tmp_path, monkeypatch)
    body = consent_envelope("DAY_PLAN", "GRANTED", 3)
    granted = client.post("/v4/sync/consent-state", headers=android_headers(), json=body)
    assert granted.status_code == 200, granted.text
    receipt_schema = json.loads((CONTRACTS / "sync.schema.json").read_text(encoding="utf-8"))["$defs"]["consent_state_receipt"]
    Draft202012Validator(receipt_schema, format_checker=FormatChecker()).validate(granted.json())
    assert granted.json()["state"] == "GRANTED"
    assert granted.json()["result_code"] == "ACCEPTED"

    replay = client.post("/v4/sync/consent-state", headers=android_headers(), json=body)
    assert replay.status_code == 200, replay.text
    assert replay.json()["replayed"] is True
    assert replay.json()["result_code"] == "DUPLICATE"

    conflict_body = {**body, "state": "REVOKED"}
    conflict = client.post("/v4/sync/consent-state", headers=android_headers(), json=conflict_body)
    assert conflict.status_code == 409
    validate_error(conflict, "IDEMPOTENCY_CONFLICT")

    tool_auth = client.post("/v4/sync/consent-state", headers=android_headers(TOOL_TOKEN), json=body)
    assert tool_auth.status_code == 401
    validate_error(tool_auth, "AUTH_REQUIRED")

    invalid = client.post(
        "/v4/sync/consent-state", headers=android_headers(),
        json={**consent_envelope("DAY_PLAN", "GRANTED", 4), "state": "DISABLED"},
    )
    assert invalid.status_code == 400
    validate_error(invalid, "BAD_REQUEST")

    wrong_method = client.get("/v4/sync/consent-state", headers=android_headers())
    assert wrong_method.status_code == 405
    validate_error(wrong_method, "BAD_REQUEST")
    client.close()


def test_absence_staleness_schema_size_unknown_method_and_no_generic_proxy(tmp_path, monkeypatch):
    client = make_client(tmp_path, monkeypatch)
    for category in ("DAY_PLAN", "ACTIVITY_STATUS", "USAGE_SUMMARY", "GOAL_PROGRESS", "TRAINING_PLAN"):
        post_consent(client, category, "GRANTED", 3)
    missing = client.get("/internal/v4/day-plan", headers=tool_headers("day_plan.read"))
    assert missing.status_code == 200
    validate("tool-results.schema.json", missing.json())
    assert missing.json()["freshness"] == "UNAVAILABLE"
    assert missing.json()["data"]["state"] == "NO_PLAN"

    unavailable_calls = (
        ("activity_status.read", f"/internal/v4/activity-status/{UUID_ACTIVITY}?plan_id={UUID_PLAN}"),
        ("usage_summary.read", "/internal/v4/usage-summary?window_start=2026-10-29T10:00:00Z&window_end=2026-10-29T11:00:00Z"),
        ("goal_progress.read", "/internal/v4/goal-progress"),
        ("training_plan.read", "/internal/v4/training-plan?local_date=2026-10-29"),
    )
    for capability, path in unavailable_calls:
        response = client.get(path, headers=tool_headers(capability))
        assert response.status_code == 503
        validate_error(response, "DATA_UNAVAILABLE")

    old_stamp = stamp(datetime.now(timezone.utc) - timedelta(hours=1))
    post_plan(client, observed_at=old_stamp)
    stale = client.get("/internal/v4/day-plan", headers=tool_headers("day_plan.read"))
    assert stale.status_code == 200
    validate("tool-results.schema.json", stale.json())
    assert stale.json()["freshness"] == "STALE"
    assert stale.json()["is_stale"] is True

    bad_query = client.get("/internal/v4/day-plan?url=http://example.invalid", headers=tool_headers("day_plan.read"))
    assert bad_query.status_code == 400
    validate_error(bad_query, "UNKNOWN_FIELD")

    unknown = client.get("/internal/v4/proxy?url=http://example.invalid", headers=tool_headers("day_plan.read"))
    assert unknown.status_code == 404
    validate_error(unknown, "NOT_FOUND")
    wrong_method = client.post("/internal/v4/day-plan", headers=tool_headers("day_plan.read"))
    assert wrong_method.status_code == 405
    validate_error(wrong_method, "BAD_REQUEST")

    invalid = client.post("/v4/sync/day-plan-snapshots", headers=android_headers(), json={"schema_version": 3})
    assert invalid.status_code == 400
    validate_error(invalid, "BAD_REQUEST")
    oversized = client.post(
        "/v4/sync/day-plan-snapshots", headers=android_headers(),
        content=b"{" + b" " * MAX_REQUEST_BYTES,
    )
    assert oversized.status_code == 413
    validate_error(oversized, "PAYLOAD_TOO_LARGE")
    client.close()


def test_capability_flags_and_consent_version_gate_reads_and_sync(tmp_path, monkeypatch):
    capabilities = dict(READ_CAPABILITIES)
    capabilities["day_plan.read"] = False
    client = make_client(tmp_path, monkeypatch, capabilities=capabilities)
    response = client.get("/internal/v4/day-plan", headers=tool_headers("day_plan.read"))
    assert response.status_code == 403
    validate_error(response, "CAPABILITY_UNAVAILABLE")

    receipt = post_plan(client, consent_version=4)
    assert receipt["stored_version"] == 7
    stale_consent = client.post(
        "/v4/sync/day-plan-snapshots", headers=android_headers(),
        json=sync_envelope("day_plan.snapshot.write", day_plan(), consent_version=3),
    )
    assert stale_consent.status_code == 409
    validate_error(stale_consent, "STALE_VERSION")
    client.close()
