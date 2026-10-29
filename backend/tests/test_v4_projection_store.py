from __future__ import annotations

import copy
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator, FormatChecker

from atlas_backend.store import SQLiteStore
from atlas_backend.v4_projection_store import (
    AckConflict,
    AckIdConflict,
    AckValidationError,
    EventIdConflict,
    IdempotencyConflict,
    ProjectionConflict,
    SequenceGap,
    StaleProjectionVersion,
    UnsupportedPayloadVersion,
    V4ProjectionStore,
)
from atlas_backend.v4_tool_service import Consent, V4ToolError, V4ToolService


UUID_PLAN = "00000000-0000-4000-8000-000000000101"
UUID_REVISION = "00000000-0000-4000-8000-000000000102"
UUID_ACTIVITY = "00000000-0000-4000-8000-000000000103"
UUID_CONTINUITY = "00000000-0000-4000-8000-000000000104"
UUID_EVENT = "00000000-0000-4000-8000-000000000105"
UUID_PROPOSAL = "00000000-0000-4000-8000-000000000106"
UUID_KEY = "00000000-0000-4000-8000-000000000107"
UUID_ACK = "00000000-0000-4000-8000-000000000108"
UUID_GOAL = "00000000-0000-4000-8000-000000000109"
NOW = datetime(2026, 10, 29, 8, 2, tzinfo=timezone.utc)
STAMP = "2026-10-29T08:02:00Z"
RECEIVED = "2026-10-29T08:02:05Z"
DEVICE = "paired-device-ref"


class FakeClock:
    def __init__(self, value: datetime = NOW) -> None:
        self.value = value

    def __call__(self) -> datetime:
        return self.value


def plan_snapshot(*, version: int = 7, observed_at: str = STAMP) -> dict:
    return {
        "plan_id": UUID_PLAN,
        "local_date": "2026-10-29",
        "timezone": "Europe/Paris",
        "version": version,
        "current_revision_id": UUID_REVISION,
        "schema_version": 4,
        "state": "APPROVED",
        "origin": "LOCAL",
        "observed_at": observed_at,
        "activities": [
            {
                "activity_id": UUID_ACTIVITY,
                "kind": "FOCUS",
                "title": "Lecture exemple",
                "status": "PLANNED",
                "scheduled_local": {
                    "local_date": "2026-10-29",
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
                "status_observed_at": None,
                "status_sync_state": "ACKED",
                "private_note": "must not escape the projection tool",
            }
        ],
    }


def status_event(*, event_id: str = UUID_EVENT, sequence: int = 1, to_status: str = "DONE") -> dict:
    return {
        "event_id": event_id,
        "sequence": sequence,
        "plan_id": UUID_PLAN,
        "activity_id": UUID_ACTIVITY,
        "continuity_id": UUID_CONTINUITY,
        "from_status": "IN_PROGRESS",
        "to_status": to_status,
        "provenance": "CONFIRMED",
        "source": "USER_UI",
        "decided_at": STAMP,
        "observed_at": STAMP,
        "plan_version": 7,
    }


def usage_summary() -> dict:
    return {
        "window_start": "2026-10-29T08:00:00Z",
        "window_end": STAMP,
        "timezone": "Europe/Paris",
        "total_foreground_ms": 120000,
        "apps": [
            {
                "app_ref": "app-pseudonym-01",
                "display_name": "Reader",
                "foreground_ms": 120000,
                "launch_count": 2,
                "last_used_at": STAMP,
                "reliability": "HIGH",
            }
        ],
        "coverage": {"observed_ms": 120000, "blind_ms": 0, "events_seen": 2, "truncated": False},
        "generated_at": STAMP,
    }


def goals_snapshot() -> dict:
    return {
        "goals_version": 2,
        "observed_at": STAMP,
        "goals": [{
            "goal_id": UUID_GOAL,
            "title": "Lire chaque semaine",
            "status": "ACTIVE",
            "progress_value": 7.5,
            "unit": "heures",
        }],
    }


def training_snapshot(*, observed_at: str = STAMP) -> dict:
    return {
        "provider": "wger",
        "observed_at": observed_at,
        "sync_state": "FRESH",
        "source_version": "wger-revision-9",
        "state": "READY",
        "source": "WGER_API",
        "source_updated_at": STAMP,
        "routines": [],
        "sessions": [],
        "results": [],
    }


def make_store(tmp_path, clock: FakeClock | None = None):
    active_clock = clock or FakeClock()
    sqlite_store = SQLiteStore(tmp_path / "atlas.sqlite3", clock=active_clock)
    return sqlite_store, V4ProjectionStore(sqlite_store), active_clock


def test_plan_snapshot_versioning_idempotence_conflict_regression_and_future_time(tmp_path):
    sqlite_store, store, _clock = make_store(tmp_path)
    snapshot = plan_snapshot()
    first = store.save_day_plan_snapshot(DEVICE, snapshot, consent_version=3, received_at=RECEIVED)
    replay = store.save_day_plan_snapshot(DEVICE, snapshot, consent_version=3, received_at=RECEIVED)
    assert first.accepted and not first.replayed
    assert replay.accepted and replay.replayed
    assert first.record_hash.startswith("sha256:")

    changed = copy.deepcopy(snapshot)
    changed["activities"][0]["title"] = "autre titre"
    with pytest.raises(ProjectionConflict):
        store.save_day_plan_snapshot(DEVICE, changed, consent_version=3, received_at=RECEIVED)
    with pytest.raises(StaleProjectionVersion):
        store.save_day_plan_snapshot(DEVICE, plan_snapshot(version=6), consent_version=3, received_at=RECEIVED)

    future = plan_snapshot(version=8, observed_at="2026-10-29T08:10:00Z")
    store.save_day_plan_snapshot(DEVICE, future, consent_version=3, received_at=RECEIVED)
    newest = store.latest_day_plan_snapshot(DEVICE, plan_id=UUID_PLAN)
    assert newest["plan_version"] == 8
    assert newest["snapshot_hash"] != first.record_hash
    assert "private_note" not in newest["projection"]["activities"][0]
    with sqlite_store.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM day_plan_snapshots_v4").fetchone()[0] == 2


def test_plan_and_goals_refresh_with_renewed_consent_and_remain_fresh(tmp_path):
    _sqlite_store, projections, clock = make_store(tmp_path)
    plan = plan_snapshot()
    goals = goals_snapshot()
    projections.save_day_plan_snapshot(DEVICE, plan, consent_version=3, received_at=RECEIVED)
    projections.save_goal_progress_snapshot(DEVICE, goals, consent_version=3, received_at=RECEIVED)

    assert projections.save_day_plan_snapshot(
        DEVICE, plan, consent_version=4, received_at="2026-10-29T08:03:05Z"
    ).replayed
    assert projections.save_goal_progress_snapshot(
        DEVICE, goals, consent_version=4, received_at="2026-10-29T08:03:05Z"
    ).replayed

    service = make_service(projections, clock, consent_version=4)
    plan_result = service.atlas_get_day_plan(DEVICE, {})
    goal_result = service.atlas_get_goal_progress(DEVICE, {})
    assert plan_result["freshness"] == "FRESH" and plan_result["data"] is not None
    assert goal_result["freshness"] == "FRESH" and goal_result["data"] is not None
    with projections.store.connect() as connection:
        assert connection.execute(
            "SELECT consent_version FROM day_plan_snapshots_v4"
        ).fetchone()[0] == 4
        assert connection.execute(
            "SELECT consent_version FROM goal_progress_snapshots_v4"
        ).fetchone()[0] == 4


def test_status_events_are_append_only_idempotent_and_sequence_gaps_quarantine(tmp_path):
    _sqlite_store, store, _clock = make_store(tmp_path)
    store.save_day_plan_snapshot(DEVICE, plan_snapshot(), consent_version=3, received_at=RECEIVED)
    event = status_event()
    accepted = store.save_activity_status_event(DEVICE, event, consent_version=3, received_at=RECEIVED)
    replay = store.save_activity_status_event(DEVICE, event, consent_version=3, received_at=RECEIVED)
    assert accepted.accepted and not accepted.replayed
    assert replay.replayed

    conflict = status_event(to_status="MISSED")
    with pytest.raises(EventIdConflict):
        store.save_activity_status_event(DEVICE, conflict, consent_version=3, received_at=RECEIVED)

    with pytest.raises(SequenceGap):
        store.save_activity_status_event(
            DEVICE, status_event(event_id=UUID_ACK, sequence=3), consent_version=3, received_at=RECEIVED
        )
    with store.store.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM activity_status_events_v4").fetchone()[0] == 1
        quarantine = connection.execute(
            "SELECT error_code,payload,payload_hash FROM v4_quarantine WHERE kind='ACTIVITY_STATUS'"
        ).fetchone()
        assert quarantine["error_code"] == "SEQUENCE_GAP"
        assert json.loads(quarantine["payload"])["sequence"] == 3
        assert hashlib.sha256(quarantine["payload"]).hexdigest() in quarantine["payload_hash"]
        with pytest.raises(Exception, match="append-only"):
            connection.execute("DELETE FROM activity_status_events_v4 WHERE event_id=?", (UUID_EVENT,))
    service = make_service(store, _clock)
    result = service.atlas_get_activity_status(DEVICE, {"activity_id": UUID_ACTIVITY, "plan_id": UUID_PLAN})
    assert result["freshness"] == "UNKNOWN" and result["data"] is None


def test_usage_goal_and_training_snapshots_are_idempotent_and_consent_versioned(tmp_path):
    _sqlite_store, store, _clock = make_store(tmp_path)
    usage = usage_summary()
    assert not store.save_usage_summary(DEVICE, usage, consent_version=3, received_at=RECEIVED).replayed
    assert store.save_usage_summary(DEVICE, usage, consent_version=3, received_at=RECEIVED).replayed
    assert not store.save_usage_summary(DEVICE, usage, consent_version=4, received_at=RECEIVED).replayed
    goals = goals_snapshot()
    assert not store.save_goal_progress_snapshot(DEVICE, goals, consent_version=3, received_at=RECEIVED).replayed
    assert store.save_goal_progress_snapshot(DEVICE, goals, consent_version=3, received_at=RECEIVED).replayed
    training = training_snapshot()
    assert not store.save_training_snapshot(DEVICE, training, consent_version=3, received_at=RECEIVED).replayed
    assert store.save_training_snapshot(DEVICE, training, consent_version=3, received_at=RECEIVED).replayed
    changed = copy.deepcopy(training)
    changed["sessions"] = [{"session_id": "new"}]
    with pytest.raises(ProjectionConflict):
        store.save_training_snapshot(DEVICE, changed, consent_version=3, received_at=RECEIVED)
    with store.store.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM usage_summaries_v4").fetchone()[0] == 2
        assert connection.execute("SELECT COUNT(*) FROM goal_progress_snapshots_v4").fetchone()[0] == 1
        assert connection.execute("SELECT COUNT(*) FROM training_snapshots_v4").fetchone()[0] == 1


def test_unknown_payload_version_is_quarantined_byte_for_byte(tmp_path):
    _sqlite_store, store, _clock = make_store(tmp_path)
    raw = b'{"schema_version":99,"private":"preserve exact bytes"}'
    with pytest.raises(UnsupportedPayloadVersion):
        store.save_day_plan_snapshot(DEVICE, json.loads(raw), consent_version=3, received_at=RECEIVED, schema_version=99)
    with store.store.connect() as connection:
        row = connection.execute(
            "SELECT payload,payload_hash,schema_version,error_code FROM v4_quarantine WHERE kind='DAY_PLAN'"
        ).fetchone()
        assert row["payload"] == json.dumps(json.loads(raw), ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
        assert row["schema_version"] == 99
        assert row["error_code"] == "UNSUPPORTED_VERSION"
        assert row["payload_hash"] == "sha256:" + hashlib.sha256(row["payload"]).hexdigest()


def test_proposal_idempotency_lookup_and_ack_replay_conflicts(tmp_path):
    sqlite_store, projections, clock = make_store(tmp_path)
    projections.save_day_plan_snapshot(DEVICE, plan_snapshot(), consent_version=3, received_at=RECEIVED)
    service = make_service(projections, clock)
    args = proposal_args()
    response = service.atlas_propose_plan_revision(DEVICE, args)
    proposal = projections.proposal_by_idempotency_key(UUID_KEY)
    assert proposal is not None
    assert proposal["proposal_id"] == response["data"]["proposal_id"]
    replay = service.atlas_propose_plan_revision(DEVICE, args)
    assert replay["data"]["proposal_id"] == response["data"]["proposal_id"]

    other_body = dict(args, justification="Autre raison")
    with pytest.raises(V4ToolError) as conflict:
        service.atlas_propose_plan_revision(DEVICE, other_body)
    assert conflict.value.code == "IDEMPOTENCY_CONFLICT"

    ack = {
        "ack_id": UUID_ACK,
        "capability": "proposal.ack",
        "proposal_id": proposal["proposal_id"],
        "sequence": 3,
        "state": "APPLIED",
        "result_code": "APPLIED",
        "occurred_at": STAMP,
        "plan_id": UUID_PLAN,
        "applied_plan_version": 8,
        "revision_id": UUID_PROPOSAL,
        "scope_hash": "sha256:" + "c" * 64,
        "effect_ids": ["PLAN_APPLY:" + UUID_PROPOSAL],
        "consent_version": 3,
    }
    assert not projections.append_proposal_ack(ack, received_at=RECEIVED).replayed
    assert projections.append_proposal_ack(ack, received_at=RECEIVED).replayed
    with sqlite_store.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM agent_proposal_events_v4").fetchone()[0] == 1
        assert connection.execute("SELECT state FROM agent_proposals_v4").fetchone()[0] == "APPLIED"

    changed_ack = dict(ack, result_code="STALE_VERSION")
    with pytest.raises(AckIdConflict):
        projections.append_proposal_ack(changed_ack, received_at=RECEIVED)
    new_ack_same_sequence = dict(ack, ack_id=UUID_EVENT)
    with pytest.raises(AckConflict):
        projections.append_proposal_ack(new_ack_same_sequence, received_at=RECEIVED)


def test_proposal_ack_requires_complete_application_proof_and_rejects_proof_without_application(tmp_path):
    _sqlite_store, projections, _clock = make_store(tmp_path)
    valid_applied = {
        "ack_id": UUID_ACK,
        "capability": "proposal.ack",
        "proposal_id": UUID_PROPOSAL,
        "sequence": 1,
        "state": "APPLIED",
        "result_code": "APPLIED",
        "occurred_at": STAMP,
        "plan_id": UUID_PLAN,
        "applied_plan_version": 8,
        "revision_id": UUID_PROPOSAL,
        "scope_hash": "sha256:" + "c" * 64,
        "effect_ids": ["PLAN_APPLY:" + UUID_PROPOSAL],
        "consent_version": 3,
    }
    missing_proof = dict(valid_applied)
    missing_proof.pop("applied_plan_version")
    null_proof = dict(valid_applied, applied_plan_version=None)
    rejected_with_proof = dict(
        valid_applied,
        state="REJECTED",
        result_code="USER_REJECTED",
        ack_id=UUID_EVENT,
    )
    uncertain_with_proof = dict(
        valid_applied,
        state="UNCERTAIN",
        result_code="RESULT_UNCERTAIN",
        ack_id=UUID_CONTINUITY,
    )
    reconciled_without_proof = dict(
        valid_applied,
        state="RECONCILED",
        result_code="RECONCILED_APPLIED",
        ack_id=UUID_GOAL,
    )
    for field in ("plan_id", "applied_plan_version", "revision_id", "scope_hash", "effect_ids"):
        reconciled_without_proof.pop(field)
    for invalid_ack in (
        missing_proof,
        null_proof,
        rejected_with_proof,
        uncertain_with_proof,
        reconciled_without_proof,
    ):
        with pytest.raises(AckValidationError):
            projections.append_proposal_ack(invalid_ack, received_at=RECEIVED)


def make_service(projections, clock, *, consent_state: str = "GRANTED", consent_version: int | None = 3,
                 capabilities: dict[str, bool] | None = None) -> V4ToolService:
    enabled = {capability: True for capability in (
        "day_plan.read", "activity_status.read", "plan_revision.propose", "usage_summary.read",
        "goal_progress.read", "training_plan.read",
    )}
    if capabilities is not None:
        enabled.update(capabilities)
    return V4ToolService(
        projections,
        clock=clock,
        capabilities=enabled,
        consent_lookup=lambda _device, category: (
            Consent("UNKNOWN", None) if category == "USAGE_APP_LABELS"
            else Consent(consent_state, consent_version)
        ),
        request_id_factory=lambda: UUID_PROPOSAL,
        cursor_secret=b"unit-test-v4-cursor-key",
    )


def proposal_args() -> dict:
    return {
        "plan_id": UUID_PLAN,
        "base_version": 7,
        "changes": [{"op": "reschedule", "activity_id": UUID_ACTIVITY, "target_start_minute": 660}],
        "justification": "Un conflit avec un autre créneau.",
        "idempotency_key": UUID_KEY,
    }


def test_all_six_tools_match_contracts_and_do_not_leak_notes_or_apply_proposal(tmp_path):
    _sqlite_store, projections, clock = make_store(tmp_path)
    projections.save_day_plan_snapshot(DEVICE, plan_snapshot(), consent_version=3, received_at=RECEIVED)
    projections.save_activity_status_event(DEVICE, status_event(), consent_version=3, received_at=RECEIVED)
    projections.save_usage_summary(DEVICE, usage_summary(), consent_version=3, received_at=RECEIVED)
    projections.save_goal_progress_snapshot(DEVICE, goals_snapshot(), consent_version=3, received_at=RECEIVED)
    projections.save_training_snapshot(DEVICE, training_snapshot(), consent_version=3, received_at=RECEIVED)
    service = make_service(projections, clock)

    results = {
        "atlas_get_day_plan_response": service.atlas_get_day_plan(DEVICE, {}),
        "atlas_get_activity_status_response": service.atlas_get_activity_status(
            DEVICE, {"activity_id": UUID_ACTIVITY, "plan_id": UUID_PLAN}
        ),
        "atlas_get_usage_summary_response": service.atlas_get_usage_summary(
            DEVICE, {"window_start": "2026-10-29T08:00:00Z", "window_end": STAMP, "include_app_labels": True}
        ),
        "atlas_get_goal_progress_response": service.atlas_get_goal_progress(DEVICE, {}),
        "atlas_get_training_plan_response": service.atlas_get_training_plan(DEVICE, {}),
        "atlas_propose_plan_revision_response": service.atlas_propose_plan_revision(DEVICE, proposal_args()),
    }
    schema = json.loads((Path(__file__).resolve().parents[2] / "contracts/v4/tool-results.schema.json").read_text())
    for definition, result in results.items():
        Draft202012Validator(schema["$defs"][definition], format_checker=FormatChecker()).validate(result)

    plan_result = results["atlas_get_day_plan_response"]
    assert plan_result["source"] == "ANDROID_SYNC"
    assert "private_note" not in plan_result["data"]["plan"]["activities"][0]
    assert results["atlas_get_activity_status_response"]["data"]["status"] == "DONE"
    assert results["atlas_get_usage_summary_response"]["data"]["apps"][0]["display_name"] is None
    assert results["atlas_get_goal_progress_response"]["data"]["goals"][0]["progress"] == {
        "value": 7.5,
        "unit": "heures",
        "target_value": None,
    }
    assert results["atlas_propose_plan_revision_response"]["data"]["status"] == "QUEUED"
    with projections.store.connect() as connection:
        assert connection.execute("SELECT state FROM agent_proposals_v4").fetchone()[0] == "PENDING_ANDROID"
        assert connection.execute("SELECT COUNT(*) FROM agent_proposal_events_v4").fetchone()[0] == 0


def test_goal_progress_response_maps_sync_values_and_satisfies_schema(tmp_path):
    _sqlite_store, projections, clock = make_store(tmp_path)
    projections.save_goal_progress_snapshot(DEVICE, goals_snapshot(), consent_version=3, received_at=RECEIVED)
    result = make_service(projections, clock).atlas_get_goal_progress(DEVICE, {})
    schema = json.loads((Path(__file__).resolve().parents[2] / "contracts/v4/tool-results.schema.json").read_text())
    Draft202012Validator(
        schema["$defs"]["atlas_get_goal_progress_response"],
        format_checker=FormatChecker(),
    ).validate(result)
    assert result["data"]["goals"][0] == {
        "goal_id": UUID_GOAL,
        "title": "Lire chaque semaine",
        "status": "ACTIVE",
        "progress": {"value": 7.5, "unit": "heures", "target_value": None},
        "progress_source": None,
        "observed_at": None,
        "subgoals": [],
        "confirmed_commitments": [],
        "missing_data": [],
    }


def test_reads_return_unavailable_when_absent_and_capability_errors_when_disabled(tmp_path):
    _sqlite_store, projections, clock = make_store(tmp_path)
    service = make_service(projections, clock)
    outputs = [
        service.atlas_get_day_plan(DEVICE, {}),
        service.atlas_get_activity_status(DEVICE, {"activity_id": UUID_ACTIVITY}),
        service.atlas_get_usage_summary(DEVICE, {"window_start": "2026-10-29T08:00:00Z", "window_end": STAMP}),
        service.atlas_get_goal_progress(DEVICE, {}),
        service.atlas_get_training_plan(DEVICE, {}),
    ]
    assert all(output["data"] is None and output["freshness"] == "UNAVAILABLE" for output in outputs)

    disabled = make_service(projections, clock, capabilities={"day_plan.read": False})
    with pytest.raises(V4ToolError) as unavailable:
        disabled.atlas_get_day_plan(DEVICE, {})
    assert unavailable.value.code == "CAPABILITY_UNAVAILABLE"
    assert unavailable.value.envelope(UUID_PROPOSAL)["error"]["code"] == "CAPABILITY_UNAVAILABLE"


def test_stale_future_unknown_and_revoked_consent_never_claim_fresh_android_state(tmp_path):
    _sqlite_store, projections, clock = make_store(tmp_path)
    projections.save_day_plan_snapshot(DEVICE, plan_snapshot(), consent_version=3, received_at=RECEIVED)
    service = make_service(projections, clock)
    clock.value = datetime(2026, 10, 29, 8, 10, tzinfo=timezone.utc)
    stale = service.atlas_get_day_plan(DEVICE, {})
    assert stale["freshness"] == "STALE" and stale["is_stale"] is True
    assert stale["data"] is not None

    future_service = make_service(projections, FakeClock(NOW), consent_state="GRANTED", consent_version=3)
    projections.save_day_plan_snapshot(
        DEVICE, plan_snapshot(version=8, observed_at="2026-10-29T08:10:00Z"),
        consent_version=3, received_at=RECEIVED,
    )
    future = future_service.atlas_get_day_plan(DEVICE, {})
    assert future["freshness"] == "UNKNOWN" and future["data"] is None

    unknown = make_service(projections, FakeClock(NOW), consent_state="UNKNOWN", consent_version=None)
    revoked = make_service(projections, FakeClock(NOW), consent_state="REVOKED", consent_version=3)
    changed_version = make_service(projections, FakeClock(NOW), consent_state="GRANTED", consent_version=4)
    for result in (unknown.atlas_get_day_plan(DEVICE, {}), revoked.atlas_get_day_plan(DEVICE, {}),
                   changed_version.atlas_get_day_plan(DEVICE, {})):
        assert result["freshness"] == "UNKNOWN"
        assert result["data"] is None


def test_cursor_is_bound_to_device_capability_and_snapshot(tmp_path):
    _sqlite_store, projections, clock = make_store(tmp_path)
    plan = plan_snapshot()
    plan["activities"].append(copy.deepcopy(plan["activities"][0]))
    plan["activities"][1]["activity_id"] = UUID_ACK
    plan["activities"][1]["scheduled_local"]["start_minute"] = 700
    projections.save_day_plan_snapshot(DEVICE, plan, consent_version=3, received_at=RECEIVED)
    service = make_service(projections, clock)
    first_page = service.atlas_get_day_plan(DEVICE, {"page_size": 1})
    cursor = first_page["data"]["next_cursor"]
    second_page = service.atlas_get_day_plan(DEVICE, {"page_size": 1, "cursor": cursor})
    assert first_page["data"]["truncated"] is True
    assert second_page["data"]["plan"]["activities"][0]["activity_id"] == UUID_ACK
    projections.save_day_plan_snapshot("different-device", plan_snapshot(), consent_version=3, received_at=RECEIVED)
    with pytest.raises(V4ToolError) as wrong_device:
        service.atlas_get_day_plan("different-device", {"page_size": 1, "cursor": cursor})
    assert wrong_device.value.code == "CURSOR_INVALID"
