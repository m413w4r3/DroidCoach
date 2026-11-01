from __future__ import annotations

from datetime import datetime, timezone

import pytest

from atlas_backend.store import SQLiteStore
from atlas_backend.v4_projection_store import V4ProjectionStore
from atlas_backend.v4_tool_service import Consent, V4ToolError, V4ToolService


DEVICE_REF = "paired-device-v7-06b"
PLAN_ID = "00000000-0000-4000-8000-000000000601"
REVISION_ID = "00000000-0000-4000-8000-000000000602"
ACTIVITY_ID = "00000000-0000-4000-8000-000000000603"
CONTINUITY_ID = "00000000-0000-4000-8000-000000000604"
EVENT_ID = "00000000-0000-4000-8000-000000000605"
IDEMPOTENCY_KEY = "00000000-0000-4000-8000-000000000606"


def stamp(value: datetime) -> str:
    return value.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def make_service(tmp_path) -> tuple[V4ProjectionStore, V4ToolService, datetime]:
    now = datetime.now(timezone.utc).replace(microsecond=0)
    store = SQLiteStore(tmp_path / "atlas.sqlite3", clock=lambda: now)
    projections = V4ProjectionStore(store)
    service = V4ToolService(
        projections,
        clock=lambda: now,
        capabilities={"plan_revision.propose": True},
        consent_lookup=lambda _device, _category: Consent("GRANTED", 3),
    )
    return projections, service, now


def approved_plan(observed_at: str) -> dict:
    local_date = datetime.now(timezone.utc).date().isoformat()
    return {
        "plan_id": PLAN_ID,
        "local_date": local_date,
        "timezone": "Europe/Paris",
        "version": 7,
        "current_revision_id": REVISION_ID,
        "state": "APPROVED",
        "origin": "LOCAL",
        "observed_at": observed_at,
        "activities": [{
            "activity_id": ACTIVITY_ID,
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
            "continuity_id": CONTINUITY_ID,
            "supersedes_activity_id": None,
            "status_provenance": "UNKNOWN",
            "status_decided_at": None,
        }],
    }


def status_event(status: str, observed_at: str) -> dict:
    return {
        "event_id": EVENT_ID,
        "sequence": 1,
        "plan_id": PLAN_ID,
        "activity_id": ACTIVITY_ID,
        "continuity_id": CONTINUITY_ID,
        "from_status": "PLANNED",
        "to_status": status,
        "provenance": "CONFIRMED",
        "source": "USER_UI",
        "decided_at": observed_at,
        "observed_at": observed_at,
        "plan_version": 7,
    }


def proposal_args(operation: str) -> dict:
    change = {"op": operation, "activity_id": ACTIVITY_ID}
    if operation == "reschedule":
        change["target_start_minute"] = 660
    else:
        change["title"] = "Titre modifié"
    return {
        "plan_id": PLAN_ID,
        "base_version": 7,
        "changes": [change],
        "justification": "Réorganiser le créneau prévu.",
        "idempotency_key": IDEMPOTENCY_KEY,
    }


def proposal_count(projections: V4ProjectionStore) -> int:
    with projections.store.connect() as connection:
        return int(connection.execute("SELECT COUNT(*) FROM agent_proposals_v4").fetchone()[0])


@pytest.mark.parametrize("status", ["DONE", "MISSED", "CANCELLED", "POSTPONED"])
@pytest.mark.parametrize("operation", ["reschedule", "edit_activity"])
def test_terminal_activity_event_rejects_plan_revision_without_persisting_proposal(
    tmp_path, status: str, operation: str
) -> None:
    projections, service, now = make_service(tmp_path)
    observed_at = stamp(now)
    projections.save_day_plan_snapshot(
        DEVICE_REF, approved_plan(observed_at), consent_version=3, received_at=observed_at
    )
    receipt = projections.save_activity_status_event(
        DEVICE_REF, status_event(status, observed_at), consent_version=3, received_at=observed_at
    )
    assert receipt.accepted
    assert not receipt.replayed

    with pytest.raises(V4ToolError) as error:
        service.atlas_propose_plan_revision(DEVICE_REF, proposal_args(operation))

    assert error.value.code == "VALIDATION_FAILED"
    assert proposal_count(projections) == 0


def test_nonterminal_activity_event_still_allows_plan_revision(tmp_path) -> None:
    projections, service, now = make_service(tmp_path)
    observed_at = stamp(now)
    projections.save_day_plan_snapshot(
        DEVICE_REF, approved_plan(observed_at), consent_version=3, received_at=observed_at
    )
    receipt = projections.save_activity_status_event(
        DEVICE_REF, status_event("IN_PROGRESS", observed_at), consent_version=3, received_at=observed_at
    )
    assert receipt.accepted

    result = service.atlas_propose_plan_revision(DEVICE_REF, proposal_args("reschedule"))

    assert result["data"]["status"] == "QUEUED"
    assert proposal_count(projections) == 1
