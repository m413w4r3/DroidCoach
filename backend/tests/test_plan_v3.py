"""Tests for the inactive V6.3 planning contract."""

from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timedelta, timezone

import pytest
from pydantic import ValidationError

from atlas_backend.plan_v3 import (
    PlanActivityV3,
    PlanProposalEnvelopeV3,
    PlanRevisionParametersV3,
    validate_plan_revision,
)

PLAN_ID = "11111111-1111-4111-8111-111111111111"
ACTIVITY_ID = "22222222-2222-4222-8222-222222222222"
TZ = "Europe/Paris"
NOW = datetime(2026, 10, 26, 8, 0, tzinfo=timezone.utc)


def activity(**overrides):
    value = {
        "client_ref": "slot-1",
        "activity_id": None,
        "kind": "FOCUS",
        "title": "Préparer le plan",
        "local_date": "2026-10-26",
        "start_minute": 9 * 60,
        "duration_minutes": 50,
        "timezone": TZ,
        "priority": "IMPORTANT",
    }
    value.update(overrides)
    return value


def parameters(**overrides):
    value = {
        "plan_id": PLAN_ID,
        "base_version": 3,
        "local_date": "2026-10-26",
        "timezone": TZ,
        "summary": "Révision de la matinée",
        "activities": [activity()],
    }
    value.update(overrides)
    return value


def envelope(**overrides):
    value = {
        "schema_version": 3,
        "proposal_id": "33333333-3333-4333-8333-333333333333",
        "idempotency_key": "plan-revision-2026-10-26-3",
        "conversation_id": "conv-1",
        "mandate_id": "agent-local-v3",
        "mandate_version": 2,
        "created_at": NOW,
        "expires_at": NOW + timedelta(hours=12),
        "required_level": "confirm",
        "state": "proposed",
        "proposal": {
            "action_type": "plan.revise_day",
            "reason": "Le rendez-vous de 10 h libère la matinée.",
            "parameters": parameters(),
        },
    }
    value.update(overrides)
    return value


def validate(proposal, **overrides):
    values = {
        "plan_id": PLAN_ID,
        "current_version": 3,
        "known_activity_ids": {ACTIVITY_ID},
        "now": NOW,
    }
    values.update(overrides)
    return validate_plan_revision(proposal, **values)


def test_valid_revision_is_accepted():
    proposal = PlanProposalEnvelopeV3.model_validate(envelope())
    decision = validate(proposal)
    assert decision.accepted, decision.reason


def test_existing_activity_reference_can_be_kept():
    proposal = PlanProposalEnvelopeV3.model_validate(
        envelope(proposal={
            "action_type": "plan.revise_day",
            "reason": "Conserver la séance et déplacer le reste.",
            "parameters": parameters(
                activities=[activity(activity_id=ACTIVITY_ID, client_ref="existing")],
            ),
        })
    )
    assert validate(proposal).accepted


def test_unknown_field_is_rejected():
    with pytest.raises(ValidationError):
        PlanProposalEnvelopeV3.model_validate(envelope(unexpected=True))


def test_unknown_activity_field_is_rejected():
    with pytest.raises(ValidationError):
        PlanActivityV3.model_validate(activity(screen_content="secret"))


def test_duplicate_client_ref_is_rejected():
    with pytest.raises(ValidationError):
        PlanRevisionParametersV3.model_validate(
            parameters(activities=[activity(), activity(start_minute=700)])
        )


def test_duplicate_activity_id_is_rejected():
    with pytest.raises(ValidationError):
        PlanRevisionParametersV3.model_validate(
            parameters(
                activities=[
                    activity(client_ref="a", activity_id=ACTIVITY_ID),
                    activity(client_ref="b", activity_id=ACTIVITY_ID, start_minute=700),
                ]
            )
        )


def test_activity_on_another_date_is_rejected():
    with pytest.raises(ValidationError):
        PlanRevisionParametersV3.model_validate(
            parameters(activities=[activity(local_date="2026-10-27")])
        )


def test_activity_with_another_timezone_is_rejected():
    with pytest.raises(ValidationError):
        PlanRevisionParametersV3.model_validate(
            parameters(activities=[activity(timezone="UTC")])
        )


def test_activity_crossing_midnight_is_rejected():
    with pytest.raises(ValidationError):
        PlanActivityV3.model_validate(activity(start_minute=23 * 60, duration_minutes=90))


def test_total_scheduled_minutes_are_bounded():
    with pytest.raises(ValidationError):
        PlanRevisionParametersV3.model_validate(
            parameters(
                activities=[
                    activity(client_ref="a", duration_minutes=800),
                    activity(client_ref="b", start_minute=800, duration_minutes=800),
                ]
            )
        )


def test_empty_day_is_a_valid_rest_proposal():
    proposal = PlanProposalEnvelopeV3.model_validate(
        envelope(proposal={
            "action_type": "plan.revise_day",
            "reason": "Journée de repos demandée.",
            "parameters": parameters(activities=[], summary="Repos"),
        })
    )
    assert validate(proposal).accepted


def test_stale_base_version_is_rejected():
    proposal = PlanProposalEnvelopeV3.model_validate(envelope())
    decision = validate(proposal, current_version=4)
    assert not decision.accepted
    assert "périmée" in decision.reason


def test_unknown_activity_id_is_rejected():
    other = "44444444-4444-4444-8444-444444444444"
    proposal = PlanProposalEnvelopeV3.model_validate(
        envelope(proposal={
            "action_type": "plan.revise_day",
            "reason": "Déplacer une activité.",
            "parameters": parameters(
                activities=[activity(activity_id=other, client_ref="other")],
            ),
        })
    )
    decision = validate(proposal)
    assert not decision.accepted
    assert "inconnu" in decision.reason


def test_expired_proposal_is_rejected():
    proposal = PlanProposalEnvelopeV3.model_validate(envelope())
    decision = validate(proposal, now=NOW + timedelta(hours=13))
    assert not decision.accepted
    assert "expirée" in decision.reason


def test_plan_id_mismatch_is_rejected():
    proposal = PlanProposalEnvelopeV3.model_validate(envelope())
    decision = validate(proposal, plan_id="55555555-5555-4555-8555-555555555555")
    assert not decision.accepted


def test_schema_version_two_is_rejected():
    with pytest.raises(ValidationError):
        PlanProposalEnvelopeV3.model_validate(envelope(schema_version=2))


def test_unknown_action_type_is_rejected():
    with pytest.raises(ValidationError):
        PlanProposalEnvelopeV3.model_validate(
            envelope(proposal={
                "action_type": "calendar.delete_all",
                "reason": "interdit",
                "parameters": parameters(),
            })
        )


def test_naive_timestamp_is_rejected():
    with pytest.raises(ValidationError):
        PlanProposalEnvelopeV3.model_validate(
            envelope(created_at=datetime(2026, 10, 26, 8, 0))
        )


def test_expiry_before_creation_is_rejected():
    with pytest.raises(ValidationError):
        PlanProposalEnvelopeV3.model_validate(
            envelope(expires_at=NOW - timedelta(minutes=1))
        )


def test_overlaps_are_reported_not_hidden():
    params = PlanRevisionParametersV3.model_validate(
        parameters(
            activities=[
                activity(client_ref="a", start_minute=540, duration_minutes=60),
                activity(client_ref="b", start_minute=570, duration_minutes=60),
            ]
        )
    )
    assert params.overlaps() == [("a", "b")]


def test_nested_intervals_report_every_overlapping_pair():
    params = PlanRevisionParametersV3.model_validate(
        parameters(
            activities=[
                activity(client_ref="a", start_minute=540, duration_minutes=180),
                activity(client_ref="b", start_minute=600, duration_minutes=15),
                activity(client_ref="c", start_minute=660, duration_minutes=15),
            ]
        )
    )
    assert params.overlaps() == [("a", "b"), ("a", "c")]


def test_control_characters_in_title_are_rejected():
    with pytest.raises(ValidationError):
        PlanActivityV3.model_validate(activity(title="ligne\nsuivante"))


def test_mutation_of_input_does_not_bypass_validation():
    raw = envelope()
    mutated = deepcopy(raw)
    mutated["proposal"]["parameters"]["activities"][0]["start_minute"] = 1500
    with pytest.raises(ValidationError):
        PlanProposalEnvelopeV3.model_validate(mutated)
    assert PlanProposalEnvelopeV3.model_validate(raw) is not None
