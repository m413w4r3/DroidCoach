from __future__ import annotations

from test_v4_routes import (
    UUID_ACTIVITY,
    UUID_PLAN,
    activity_status_data,
    android_headers,
    make_client,
    post_consent,
    post_plan,
    sync_envelope,
    tool_headers,
)


def test_terminal_activity_validation_error_returns_http_422_without_proposal(
    tmp_path, monkeypatch
) -> None:
    client = make_client(tmp_path, monkeypatch)
    post_plan(client)
    post_consent(client, "ACTIVITY_STATUS", "GRANTED", 3)

    event = activity_status_data("00000000-0000-4000-8000-000000000107")
    event["to_status"] = "DONE"
    event_response = client.post(
        "/v4/sync/activity-status-events",
        headers=android_headers(),
        json=sync_envelope("activity_status.write", event),
    )
    assert event_response.status_code == 200, event_response.text

    response = client.post(
        "/internal/v4/plan-revision-proposals",
        headers=tool_headers("plan_revision.propose"),
        json={
            "plan_id": UUID_PLAN,
            "base_version": 7,
            "changes": [{
                "op": "reschedule",
                "activity_id": UUID_ACTIVITY,
                "target_start_minute": 660,
            }],
            "justification": "Déplacer le créneau prévu.",
            "idempotency_key": "00000000-0000-4000-8000-000000000108",
        },
    )

    payload = response.json()
    assert response.status_code == 422, response.text
    assert payload["error"]["code"] == "VALIDATION_FAILED"
    assert "proposal_id" not in payload
    with client.app.state.store.connect() as connection:
        count = connection.execute("SELECT COUNT(*) FROM agent_proposals_v4").fetchone()[0]
    assert count == 0
