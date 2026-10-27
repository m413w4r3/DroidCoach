"""The deterministic offline adapter must serve the V3 demo flow without OpenClaw."""

from __future__ import annotations

import uuid

from fastapi.testclient import TestClient

from atlas_backend.main import create_app

PLAN_ID = "11111111-1111-4111-8111-111111111111"
ACTIVITY_ID = "22222222-2222-4222-8222-222222222222"


def request_body(request_id: str | None = None) -> dict:
    return {
        "schema_version": 3,
        "request_id": request_id or str(uuid.uuid4()),
        "conversation_id": "conversation-r3a-mock",
        "message": "Propose un petit décalage du bloc de révision.",
        "plan_context_consented": True,
        "mandate_summary": {
            "id": "mandate-r3a",
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


def test_mock_adapter_returns_a_deterministic_v3_proposal(tmp_path):
    app = create_app(
        db_path=tmp_path / "mock-v3.sqlite3",
        token="local-test",
        capabilities={"plan_revision": True},
    )
    body = request_body()

    with TestClient(app) as client:
        first = client.post(
            "/v3/agent/plan-revision",
            headers={"Authorization": "Bearer local-test"},
            json=body,
        )
        assert first.status_code == 200
        payload = first.json()
        assert payload["schema_version"] == 3
        assert payload["agent_status"] == "ok"
        assert payload["proposal"] is not None
        parameters = payload["proposal"]["proposal"]["parameters"]
        assert parameters["plan_id"] == PLAN_ID
        assert parameters["base_version"] == 4
        assert parameters["activities"][0]["activity_id"] == ACTIVITY_ID
        assert parameters["activities"][0]["start_minute"] == 510

        replay = client.post(
            "/v3/agent/plan-revision",
            headers={"Authorization": "Bearer local-test"},
            json=body,
        )
        assert replay.status_code == 200
        assert replay.json() == payload


def test_mock_adapter_requires_no_network_and_never_executes(tmp_path):
    app = create_app(
        db_path=tmp_path / "mock-v3-2.sqlite3",
        token="local-test",
        capabilities={"plan_revision": True},
    )
    with TestClient(app) as client:
        response = client.post(
            "/v3/agent/plan-revision",
            headers={"Authorization": "Bearer local-test"},
            json=request_body(),
        )
    assert response.status_code == 200
    assert response.json()["proposal"]["proposal"]["action_type"] == "plan.revise_day"
