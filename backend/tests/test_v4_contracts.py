"""Executable contract checks for the additive ATLAS V4 schemas."""

from __future__ import annotations

import copy
from datetime import datetime, timedelta
import hashlib
import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError, validators

ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts" / "v4"
TOOL_NAMES = (
    "atlas_get_day_plan",
    "atlas_get_activity_status",
    "atlas_propose_plan_revision",
    "atlas_get_usage_summary",
    "atlas_get_goal_progress",
    "atlas_get_training_plan",
)
CAPABILITIES = (
    "day_plan.read",
    "activity_status.read",
    "plan_revision.propose",
    "usage_summary.read",
    "goal_progress.read",
    "training_plan.read",
)
UUID_A = "00000000-0000-4000-8000-000000000101"
UUID_B = "00000000-0000-4000-8000-000000000102"
UUID_C = "00000000-0000-4000-8000-000000000103"
UUID_D = "00000000-0000-4000-8000-000000000104"
STAMP = "2026-10-29T08:02:00Z"
LATER = "2026-10-29T08:02:04Z"
HASH_A = "sha256:" + "a" * 64
HASH_B = "sha256:" + "b" * 64
HASH_C = "sha256:" + "c" * 64
CHECKER = FormatChecker()


@CHECKER.checks("date-time")
def is_rfc3339_datetime(value: object) -> bool:
    if not isinstance(value, str):
        return True
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return False
    return parsed.tzinfo is not None


def validate_atlas_constraints(validator, constraints, instance, schema):
    if not isinstance(instance, dict) or not constraints.get("window_end_after_window_start"):
        return
    if "window_start" not in instance or "window_end" not in instance:
        return
    try:
        start = datetime.fromisoformat(instance["window_start"].replace("Z", "+00:00"))
        end = datetime.fromisoformat(instance["window_end"].replace("Z", "+00:00"))
    except (AttributeError, TypeError, ValueError):
        return  # The standard format checker reports malformed timestamps.
    if end <= start:
        yield ValidationError("window_end must be after window_start")
    maximum_days = constraints.get("maximum_window_days")
    if maximum_days is not None and end - start > timedelta(days=maximum_days):
        yield ValidationError(f"usage window exceeds {maximum_days} days")


ContractValidator = validators.extend(
    Draft202012Validator,
    {"x-atlas-constraints": validate_atlas_constraints},
)

LEGACY_SHA256 = {
    "atlas-remote-v1.schema.json": "a642bd245f037e0c4ea2e9471f226dbc03a8755dbf810353a6db427078ba585a",
    "directive.schema.json": "ce98355e9ce00262fb7d9374bb168a63c03701144e79588216de692ac3e12e7e",
    "error-v1.schema.json": "ebada034888b39a5a687b87486de1d140425f5e1768ab18eb74bb4480d22f3fe",
    "plan-revision-v3.schema.json": "8806634767fde3f2ae63b06caba5e63fdaddc41d7f9c9c0ff14db4fdf8c01638",
    "proposal-v2.schema.json": "96bb303477c83608b16c1b08f2081c550788e759479bf6ff255dd39d90fc884b",
    "sport-plan-v1.schema.json": "7e4d0eef84302e492f1deb58a19651fffd145535f81a5a0408f3774b499d6e13",
    "sync-v1.schema.json": "0f4f34b02f457b99892d7f0c3af49505dfc979ac51f2d08dd6069ecfcd607042",
}


def result_envelope(capability: str, data: dict | None, source: str = "ANDROID_SYNC") -> dict:
    return {
        "contract_version": 4,
        "schema_version": 4,
        "capability": capability,
        "request_id": UUID_A,
        "source": source,
        "observed_at": STAMP,
        "last_synced_at": LATER,
        "is_stale": False,
        "freshness": "FRESH",
        "data": data,
    }


def fixtures() -> dict[str, list[dict]]:
    scheduled = {
        "local_date": "2026-10-29",
        "start_minute": 600,
        "duration_minutes": 45,
        "timezone": "Europe/Paris",
    }
    activity = {
        "activity_id": UUID_C,
        "kind": "FOCUS",
        "title": "Bloc de lecture exemple",
        "status": "PLANNED",
        "scheduled_local": scheduled,
        "priority": "OPTIONAL",
        "goal_id": None,
        "continuity_id": None,
        "supersedes_activity_id": None,
        "status_provenance": "UNKNOWN",
        "status_decided_at": None,
        "status_observed_at": None,
        "status_sync_state": "ACKED",
    }
    response_data = [
        {
            "state": "READY",
            "plan": {
                "plan_id": UUID_A,
                "local_date": "2026-10-29",
                "timezone": "Europe/Paris",
                "version": 7,
                "current_revision_id": UUID_B,
                "origin": "LOCAL",
                "state": "APPROVED",
                "activities": [activity],
            },
            "next_cursor": None,
            "truncated": False,
        },
        {
            "activity_id": UUID_C,
            "plan_id": UUID_A,
            "version": 7,
            "status": "PLANNED",
            "status_provenance": "UNKNOWN",
            "status_source": "UNKNOWN",
            "status_decided_at": None,
            "status_observed_at": STAMP,
            "last_synced_at": LATER,
            "sync_result": "ACKED",
            "freshness": "FRESH",
            "details": None,
        },
        {
            "proposal_id": UUID_D,
            "status": "QUEUED",
            "plan_id": UUID_A,
            "base_version": 7,
            "observed_version": 7,
            "diff_hash": HASH_A,
            "diff": [],
            "conflicts": [],
            "confirmation_mode": "A0_USER_REVIEW",
            "auto_application": "NONE_AT_CORE",
            "created_at": STAMP,
            "expires_at": LATER,
        },
        {
            "window_start": STAMP,
            "window_end": LATER,
            "timezone": "Europe/Paris",
            "total_foreground_ms": 120000,
            "apps": [
                {
                    "app_ref": "synthetic-app-01",
                    "display_name": None,
                    "foreground_ms": 120000,
                    "launch_count": 2,
                    "last_used_at": STAMP,
                    "reliability": "MEDIUM",
                }
            ],
            "coverage": {
                "observed_ms": 180000,
                "blind_ms": 0,
                "events_seen": 4,
                "truncated": False,
            },
            "consent_state": "GRANTED",
            "consent_version": 3,
            "generated_at": LATER,
            "freshness": "FRESH",
        },
        {
            "goals_version": 2,
            "goals": [
                {
                    "goal_id": UUID_B,
                    "title": "Objectif synthétique",
                    "status": "ACTIVE",
                    "progress": None,
                    "progress_source": None,
                    "observed_at": STAMP,
                    "subgoals": [],
                    "confirmed_commitments": [],
                    "missing_data": ["NO_MEASURE"],
                }
            ],
            "next_cursor": None,
            "truncated": False,
        },
        {
            "state": "READY",
            "provider": "wger",
            "source": "WGER_API",
            "sync_state": "FRESH",
            "source_updated_at": STAMP,
            "observed_at": STAMP,
            "last_synced_at": LATER,
            "routines": [{"routine_id": 5, "day_id": None, "slot_id": None}],
            "sessions": [
                {
                    "session_id": "synthetic-session-01",
                    "routine_id": 5,
                    "scheduled_date": "2026-10-29",
                    "title": "Séance exemple",
                    "source": "WGER_API",
                }
            ],
            "results": [
                {
                    "result_id": UUID_D,
                    "performed_at": STAMP,
                    "duration_minutes": 30,
                    "source": "USER_DECLARED",
                    "verification": "USER_CONFIRMED",
                }
            ],
            "freshness": "FRESH",
            "next_cursor": None,
            "truncated": False,
        },
    ]
    result_fixtures = []
    for index, (name, capability, data) in enumerate(zip(TOOL_NAMES, CAPABILITIES, response_data)):
        item = result_envelope(capability, data, "CORE" if index == 2 else ("WGER_API" if index == 5 else "ANDROID_SYNC"))
        if index in (0, 1, 2):
            item["plan_id"] = UUID_A
            item["version"] = 7
        result_fixtures.append(item)

    tool_args = [
        {"local_date": "2026-10-29", "page_size": 32},
        {"activity_id": UUID_C, "plan_id": UUID_A, "include_details": False},
        {
            "plan_id": UUID_A,
            "base_version": 7,
            "changes": [{"op": "reschedule", "activity_id": UUID_C, "target_start_minute": 660}],
            "justification": "Déplacement synthétique dans la même journée.",
            "idempotency_key": UUID_D,
        },
        {"window_start": STAMP, "window_end": LATER, "include_app_labels": False, "app_limit": 10},
        {"page_size": 20, "include_subgoals": True},
        {"local_date": "2026-10-29", "include_results": True, "page_size": 20},
    ]
    request_fixtures = [{"tool_name": n, "arguments": a} for n, a in zip(TOOL_NAMES, tool_args)]

    sync_base = {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": UUID_A,
        "sent_at": LATER,
        "consent_version": 3,
    }
    sync_data = [
        {
            "plan_id": UUID_A,
            "local_date": "2026-10-29",
            "timezone": "Europe/Paris",
            "version": 7,
            "current_revision_id": UUID_B,
            "state": "APPROVED",
            "origin": "LOCAL",
            "observed_at": STAMP,
            "activities": [
                {
                    "activity_id": UUID_C,
                    "kind": "FOCUS",
                    "title": "Bloc de lecture exemple",
                    "status": "PLANNED",
                    "scheduled_local": scheduled,
                    "priority": "OPTIONAL",
                    "goal_id": None,
                    "continuity_id": None,
                    "supersedes_activity_id": None,
                    "status_provenance": "UNKNOWN",
                    "status_decided_at": None,
                }
            ],
        },
        {
            "event_id": UUID_D,
            "sequence": 2,
            "plan_id": UUID_A,
            "activity_id": UUID_C,
            "continuity_id": None,
            "plan_version": 7,
            "from_status": "IN_PROGRESS",
            "to_status": "DONE",
            "provenance": "CONFIRMED",
            "source": "USER_UI",
            "decided_at": STAMP,
            "observed_at": STAMP,
            "focus_session_id": UUID_B,
        },
        {
            "window_start": STAMP,
            "window_end": LATER,
            "timezone": "Europe/Paris",
            "total_foreground_ms": 120000,
            "apps": [],
            "coverage": {"observed_ms": 120000, "blind_ms": 0, "events_seen": 2, "truncated": False},
            "generated_at": LATER,
            "consent_version": 3,
        },
        {
            "goals_version": 2,
            "observed_at": STAMP,
            "goals": [
                {
                    "goal_id": UUID_B,
                    "title": "Objectif synthétique",
                    "status": "ACTIVE",
                    "progress_value": None,
                    "unit": None,
                }
            ],
        },
        {
            "provider": "wger",
            "source": "WGER_API",
            "sync_state": "FRESH",
            "observed_at": STAMP,
            "source_updated_at": None,
            "routines": [{"routine_id": 5, "day_id": None}],
            "sessions": [{"session_id": "synthetic-session-01", "scheduled_date": None, "title": "Séance exemple"}],
            "results": [
                {
                    "result_id": UUID_D,
                    "performed_at": STAMP,
                    "source": "USER_DECLARED",
                    "verification": "USER_CONFIRMED",
                }
            ],
        },
    ]
    sync_caps = ["day_plan.snapshot.write", "activity_status.write", "usage_summary.write", "goal_progress.write", "training_plan.write"]
    sync_fixtures = []
    for capability, data in zip(sync_caps, sync_data):
        item = {**sync_base, "capability": capability, "data": data, "idempotency_key": UUID_D}
        sync_fixtures.append(item)

    proposal = {
        "contract_version": 4,
        "schema_version": 4,
        "proposal_id": UUID_D,
        "capability": "plan_revision.propose",
        "idempotency_key": UUID_A,
        "proposal_hash": HASH_A,
        "created_at": STAMP,
        "expires_at": "2026-10-30T08:02:00Z",
        "state": "PENDING_ANDROID",
        "plan_id": UUID_B,
        "base_version": 7,
        "origin": "AGENT",
        "justification": "Proposition synthétique de déplacement.",
        "changes": [{"op": "reschedule", "activity_id": UUID_C, "target_start_minute": 660}],
        "diff": [
            {
                "op": "reschedule",
                "activity_id": UUID_C,
                "client_ref": None,
                "before": {"start_minute": 600, "duration_minutes": 45},
                "after": {"start_minute": 660, "duration_minutes": 45},
            }
        ],
        "conflicts": [],
        "confirmation_mode": "A0_USER_REVIEW",
        "auto_application": "NONE_AT_CORE",
    }
    transition = {
        "contract_version": 4,
        "schema_version": 4,
        "proposal_id": UUID_D,
        "sequence": 1,
        "from_state": "PENDING_ANDROID",
        "to_state": "RECEIVED",
        "occurred_at": STAMP,
        "reason_code": None,
    }
    ack = {
        "contract_version": 4,
        "schema_version": 4,
        "ack_id": UUID_A,
        "capability": "proposal.ack",
        "proposal_id": UUID_D,
        "sequence": 3,
        "state": "APPLIED",
        "result_code": "APPLIED",
        "occurred_at": LATER,
        "plan_id": UUID_B,
        "applied_plan_version": 8,
        "revision_id": UUID_C,
        "scope_hash": HASH_C,
        "effect_ids": [f"PLAN_APPLY:{UUID_C}"],
        "consent_version": 3,
    }
    error = {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": UUID_A,
        "error": {
            "code": "STALE_VERSION",
            "message": "La projection de plan doit être réconciliée.",
            "retryable": False,
            "capability": "plan_revision.propose",
            "retry_after_seconds": None,
        },
    }
    chat_request = {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": UUID_A,
        "capability": "coach.chat",
        "sent_at": LATER,
        "conversation_id": "demo-conversation-01",
        "message": "Puis-je déplacer cette activité exemple ?",
        "consent_version": 3,
        "shared_categories": ["DAY_PLAN", "ACTIVITY_STATUS"],
    }
    chat_response = {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": UUID_A,
        "reply": "Une proposition est prête à être examinée.",
        "proposal_ids": [UUID_D],
        "agent_status": "mock",
        "tool_state": "completed",
        "tool_call_count": 2,
    }
    negotiation = {
        "contract_version": 4,
        "schema_version": 4,
        "service": "atlas-core",
        "supported_contract_versions": [2, 3, 4],
        "capabilities": {name: index % 2 == 0 for index, name in enumerate((*CAPABILITIES, "coach.chat"))},
        "limits": {
            "max_tool_calls_per_turn": 6,
            "max_proposals_per_turn": 1,
            "max_page_size": 32,
            "max_usage_window_days": 14,
            "max_payload_bytes": 65536,
            "max_response_bytes": 262144,
            "chat_timeout_seconds": 20,
        },
    }
    return {
        "tool-catalog.schema.json": [{
            "contract_version": 4,
            "schema_version": 4,
            "tools": {
                name: {
                    "capability": capability,
                    "idempotence": {
                        "class": "KEYED_REQUEST" if name == TOOL_NAMES[2] else "PURE_READ",
                        "description": "Même clé et même corps canonique pour rejouer une proposition."
                        if name == TOOL_NAMES[2]
                        else "Lecture pure pour la projection observée.",
                        "key_required": name == TOOL_NAMES[2],
                    },
                    "freshness_max_age_seconds": age,
                    "errors": errors,
                }
                for name, capability, age, errors in zip(
                    TOOL_NAMES,
                    CAPABILITIES,
                    [300, 300, 300, 1800, 3600, 86400],
                    [
                        ["BAD_REQUEST", "CAPABILITY_UNAVAILABLE", "CONSENT_REQUIRED", "DATA_UNAVAILABLE", "CURSOR_INVALID", "TIMEOUT"],
                        ["BAD_REQUEST", "ACTIVITY_NOT_FOUND", "PLAN_MISMATCH", "CONSENT_REQUIRED", "PERMISSION_REQUIRED", "DATA_UNAVAILABLE", "CAPABILITY_UNAVAILABLE", "TIMEOUT"],
                        ["BAD_REQUEST", "VALIDATION_FAILED", "PLAN_NOT_FOUND", "STALE_VERSION", "CONFLICT", "CONSENT_REQUIRED", "CAPABILITY_UNAVAILABLE", "IDEMPOTENCY_CONFLICT", "REPLAY_PENDING", "RESULT_UNCERTAIN", "QUOTA_EXCEEDED", "TIMEOUT"],
                        ["BAD_REQUEST", "CONSENT_REQUIRED", "PERMISSION_REQUIRED", "CAPABILITY_UNAVAILABLE", "PERIOD_TOO_LARGE", "DATA_UNAVAILABLE", "TIMEOUT"],
                        ["CAPABILITY_UNAVAILABLE", "CONSENT_REQUIRED", "DATA_UNAVAILABLE", "CURSOR_INVALID", "TIMEOUT"],
                        ["CAPABILITY_UNAVAILABLE", "CONSENT_REQUIRED", "CONFIG_REQUIRED", "AUTH_REQUIRED", "DATA_UNAVAILABLE", "CURSOR_INVALID", "TIMEOUT", "UPSTREAM_UNAVAILABLE"],
                    ],
                )
            },
        }],
        "tool-requests.schema.json": request_fixtures,
        "tool-results.schema.json": result_fixtures,
        "sync.schema.json": sync_fixtures,
        "sync-receipts.schema.json": [
            {
                "contract_version": 4,
                "schema_version": 4,
                "request_id": UUID_A,
                "capability": "day_plan.snapshot.write",
                "accepted": True,
                "replayed": False,
                "stored_version": 7,
                "snapshot_hash": HASH_A,
                "last_synced_at": LATER,
                "result_code": "ACCEPTED",
            },
            {
                "contract_version": 4,
                "schema_version": 4,
                "request_id": UUID_A,
                "capability": "activity_status.write",
                "items": [{"event_id": UUID_D, "result_code": "ACCEPTED", "last_synced_at": LATER}],
            },
        ],
        "proposal-inbox-ack.schema.json": [proposal, transition, ack],
        "error.schema.json": [error],
        "chat-request.schema.json": [chat_request],
        "chat-response.schema.json": [chat_response],
        "capability-negotiation.schema.json": [negotiation],
    }


FIXTURES = fixtures()
SCHEMAS = {path.name: json.loads(path.read_text(encoding="utf-8")) for path in CONTRACTS.glob("*.schema.json")}


def validator(name: str) -> Draft202012Validator:
    return ContractValidator(SCHEMAS[name], format_checker=CHECKER)


def assert_invalid(name: str, instance: dict) -> None:
    assert list(validator(name).iter_errors(instance)), (name, instance)


def unknown_property_variants(value: object):
    if isinstance(value, dict):
        changed = copy.deepcopy(value)
        changed["__unexpected_v4_field__"] = True
        yield changed
        for key, child in value.items():
            for variant in unknown_property_variants(child):
                updated = copy.deepcopy(value)
                updated[key] = variant
                yield updated
    elif isinstance(value, list):
        for index, child in enumerate(value):
            for variant in unknown_property_variants(child):
                updated = copy.deepcopy(value)
                updated[index] = variant
                yield updated


def without_path(instance: dict, path: tuple[str | int, ...]) -> dict:
    changed = copy.deepcopy(instance)
    parent = changed
    for part in path[:-1]:
        parent = parent[part]
    del parent[path[-1]]
    return changed


def test_every_v4_schema_is_draft_2020_12_and_has_unique_id_and_strict_objects():
    ids = set()
    for name, schema in SCHEMAS.items():
        Draft202012Validator.check_schema(schema)
        assert schema["$schema"] == "https://json-schema.org/draft/2020-12/schema"
        assert schema["$id"] not in ids
        ids.add(schema["$id"])

        def visit(node):
            if isinstance(node, dict):
                if node.get("type") == "object":
                    assert node.get("additionalProperties") is False, (name, node)
                for child in node.values():
                    visit(child)
            elif isinstance(node, list):
                for child in node:
                    visit(child)

        visit(schema)


@pytest.mark.parametrize("name", sorted(FIXTURES))
def test_synthetic_fixtures_are_accepted_for_each_contract_schema(name):
    assert name in SCHEMAS
    for fixture in FIXTURES[name]:
        validator(name).validate(fixture)


def test_schemas_publish_the_same_synthetic_examples_as_the_contract_fixtures():
    for name, schema in SCHEMAS.items():
        assert schema.get("examples") == FIXTURES[name], name
        for example in schema["examples"]:
            validator(name).validate(example)


@pytest.mark.parametrize("name", sorted(FIXTURES))
def test_unknown_properties_are_rejected_at_every_object_depth(name):
    for fixture in FIXTURES[name]:
        variants = list(unknown_property_variants(fixture))
        assert variants, name
        for variant in variants:
            assert_invalid(name, variant)


def test_exact_six_tool_names_and_independent_metadata():
    catalog = FIXTURES["tool-catalog.schema.json"][0]["tools"]
    assert tuple(catalog) == TOOL_NAMES
    assert tuple(catalog[name]["capability"] for name in TOOL_NAMES) == CAPABILITIES
    assert [fixture["tool_name"] for fixture in FIXTURES["tool-requests.schema.json"]] == list(TOOL_NAMES)
    assert all(catalog[name]["freshness_max_age_seconds"] > 0 for name in TOOL_NAMES)
    assert catalog[TOOL_NAMES[2]]["idempotence"]["key_required"] is True
    assert all(catalog[name]["idempotence"]["key_required"] is False for name in TOOL_NAMES if name != TOOL_NAMES[2])


def test_chat_requires_sent_at_and_capability():
    request = FIXTURES["chat-request.schema.json"][0]
    assert_invalid("chat-request.schema.json", {k: v for k, v in request.items() if k != "sent_at"})
    assert_invalid("chat-request.schema.json", {k: v for k, v in request.items() if k != "capability"})


@pytest.mark.parametrize(
    ("name", "fixture_index", "path"),
    [
        ("tool-catalog.schema.json", 0, ("contract_version",)),
        ("tool-catalog.schema.json", 0, ("tools", TOOL_NAMES[0], "capability")),
        ("tool-requests.schema.json", 0, ("arguments",)),
        ("tool-requests.schema.json", 1, ("arguments", "activity_id")),
        ("tool-requests.schema.json", 2, ("arguments", "idempotency_key")),
        ("tool-requests.schema.json", 3, ("arguments", "window_start")),
        ("tool-results.schema.json", 0, ("request_id",)),
        ("tool-results.schema.json", 0, ("data", "plan", "plan_id")),
        ("sync.schema.json", 0, ("request_id",)),
        ("sync.schema.json", 0, ("data", "plan_id")),
        ("sync.schema.json", 1, ("data", "event_id")),
        ("sync.schema.json", 1, ("idempotency_key",)),
        ("sync-receipts.schema.json", 0, ("result_code",)),
        ("sync-receipts.schema.json", 1, ("items", 0, "event_id")),
        ("proposal-inbox-ack.schema.json", 0, ("proposal_id",)),
        ("proposal-inbox-ack.schema.json", 1, ("from_state",)),
        ("proposal-inbox-ack.schema.json", 2, ("sequence",)),
        ("error.schema.json", 0, ("error", "code")),
        ("chat-request.schema.json", 0, ("sent_at",)),
        ("chat-response.schema.json", 0, ("reply",)),
        ("capability-negotiation.schema.json", 0, ("supported_contract_versions",)),
        ("capability-negotiation.schema.json", 0, ("limits", "max_page_size")),
    ],
)
def test_required_fields_cannot_be_omitted(name, fixture_index, path):
    fixture = FIXTURES[name][fixture_index]
    assert_invalid(name, without_path(fixture, path))


def test_freshness_and_error_codes_are_closed():
    result = copy.deepcopy(FIXTURES["tool-results.schema.json"][0])
    result["freshness"] = "CURRENT"
    assert_invalid("tool-results.schema.json", result)
    error = copy.deepcopy(FIXTURES["error.schema.json"][0])
    error["error"]["code"] = "MADE_UP"
    assert_invalid("error.schema.json", error)


def test_uncertain_ack_does_not_claim_applied_effects():
    ack = copy.deepcopy(FIXTURES["proposal-inbox-ack.schema.json"][2])
    for field in ("plan_id", "applied_plan_version", "revision_id", "scope_hash", "effect_ids"):
        ack.pop(field)
    ack["state"] = "UNCERTAIN"
    ack["result_code"] = "RESULT_UNCERTAIN"
    validator("proposal-inbox-ack.schema.json").validate(ack)
    ack["result_code"] = "APPLIED"
    assert_invalid("proposal-inbox-ack.schema.json", ack)
    ack["result_code"] = "RESULT_UNCERTAIN"
    ack["revision_id"] = UUID_C
    assert_invalid("proposal-inbox-ack.schema.json", ack)


def test_reconciled_applied_ack_requires_complete_application_proof():
    ack = copy.deepcopy(FIXTURES["proposal-inbox-ack.schema.json"][2])
    ack["state"] = "RECONCILED"
    ack["result_code"] = "RECONCILED_APPLIED"
    validator("proposal-inbox-ack.schema.json").validate(ack)

    for field in ("applied_plan_version", "revision_id", "scope_hash", "effect_ids"):
        incomplete = copy.deepcopy(ack)
        incomplete.pop(field)
        assert_invalid("proposal-inbox-ack.schema.json", incomplete)

    rejected = copy.deepcopy(ack)
    rejected["result_code"] = "USER_REJECTED"
    assert_invalid("proposal-inbox-ack.schema.json", rejected)


def test_reconciled_not_applied_and_rejected_ack_cannot_claim_application():
    ack = copy.deepcopy(FIXTURES["proposal-inbox-ack.schema.json"][2])
    for field in ("plan_id", "applied_plan_version", "revision_id", "scope_hash", "effect_ids"):
        ack.pop(field)
    ack["state"] = "RECONCILED"
    ack["result_code"] = "RECONCILED_NOT_APPLIED"
    validator("proposal-inbox-ack.schema.json").validate(ack)

    ack["result_code"] = "USER_REJECTED"
    validator("proposal-inbox-ack.schema.json").validate(ack)

    for field, value in (
        ("applied_plan_version", 8),
        ("revision_id", UUID_C),
        ("scope_hash", HASH_C),
        ("effect_ids", [f"PLAN_APPLY:{UUID_C}"]),
    ):
        contradictory = copy.deepcopy(ack)
        contradictory[field] = value
        assert_invalid("proposal-inbox-ack.schema.json", contradictory)

    rejected = copy.deepcopy(ack)
    rejected["state"] = "REJECTED"
    rejected["result_code"] = "USER_REJECTED"
    rejected["plan_id"] = UUID_B
    assert_invalid("proposal-inbox-ack.schema.json", rejected)

    falsely_applied = copy.deepcopy(ack)
    falsely_applied["result_code"] = "APPLIED"
    assert_invalid("proposal-inbox-ack.schema.json", falsely_applied)



def test_usage_window_over_14_days_requires_atlas_validation():
    request = copy.deepcopy(FIXTURES["tool-requests.schema.json"][3])
    request["arguments"]["window_start"] = "2026-10-01T08:02:00Z"
    request["arguments"]["window_end"] = "2026-10-16T08:02:00Z"

    standard_validator = Draft202012Validator(
        SCHEMAS["tool-requests.schema.json"], format_checker=CHECKER
    )
    assert standard_validator.is_valid(request)  # x-atlas-constraints is a custom keyword.
    assert_invalid("tool-requests.schema.json", request)
    assert "DOIVENT" in SCHEMAS["tool-requests.schema.json"]["$defs"][
        "atlas_get_usage_summary_arguments"
    ]["x-atlas-constraints"]["description"]


def test_required_fields_types_enums_formats_and_bounds_are_rejected():
    catalog = copy.deepcopy(FIXTURES["tool-catalog.schema.json"][0])
    assert_invalid("tool-catalog.schema.json", {k: v for k, v in catalog.items() if k != "contract_version"})
    catalog["contract_version"] = "4"
    assert_invalid("tool-catalog.schema.json", catalog)

    request = copy.deepcopy(FIXTURES["tool-requests.schema.json"][0])
    assert_invalid("tool-requests.schema.json", {k: v for k, v in request.items() if k != "arguments"})
    request["tool_name"] = "atlas_delete_everything"
    assert_invalid("tool-requests.schema.json", request)
    request["tool_name"] = TOOL_NAMES[0]
    request["arguments"]["page_size"] = 33
    assert_invalid("tool-requests.schema.json", request)
    request["arguments"]["page_size"] = "32"
    assert_invalid("tool-requests.schema.json", request)
    request = copy.deepcopy(FIXTURES["tool-requests.schema.json"][3])
    request["arguments"]["window_start"] = "not-a-date"
    assert_invalid("tool-requests.schema.json", request)
    request = copy.deepcopy(FIXTURES["tool-requests.schema.json"][3])
    request["arguments"]["window_end"] = "2026-10-29T08:02:00Z"
    assert_invalid("tool-requests.schema.json", request)
    request["arguments"]["window_start"] = "2026-10-01T08:02:00Z"
    request["arguments"]["window_end"] = "2026-10-16T08:02:00Z"
    assert_invalid("tool-requests.schema.json", request)

    result = copy.deepcopy(FIXTURES["tool-results.schema.json"][0])
    result["request_id"] = "not-a-uuid"
    assert_invalid("tool-results.schema.json", result)
    result = copy.deepcopy(FIXTURES["tool-results.schema.json"][0])
    result["freshness"] = "FUTURE"
    assert_invalid("tool-results.schema.json", result)
    result = copy.deepcopy(FIXTURES["tool-results.schema.json"][0])
    result.pop("plan_id")
    assert_invalid("tool-results.schema.json", result)

    sync = copy.deepcopy(FIXTURES["sync.schema.json"][0])
    sync["sent_at"] = "29 October"
    assert_invalid("sync.schema.json", sync)
    sync["sent_at"] = LATER
    sync["data"]["activities"][0]["scheduled_local"]["start_minute"] = 1440
    assert_invalid("sync.schema.json", sync)
    sync = copy.deepcopy(FIXTURES["sync.schema.json"][0])
    sync["data"]["activities"] *= 33
    assert_invalid("sync.schema.json", sync)

    receipt = copy.deepcopy(FIXTURES["sync-receipts.schema.json"][0])
    receipt["accepted"] = "yes"
    assert_invalid("sync-receipts.schema.json", receipt)

    proposal = copy.deepcopy(FIXTURES["proposal-inbox-ack.schema.json"][0])
    proposal["state"] = "PENDING"
    assert_invalid("proposal-inbox-ack.schema.json", proposal)
    proposal = copy.deepcopy(FIXTURES["proposal-inbox-ack.schema.json"][0])
    proposal["changes"] *= 9
    assert_invalid("proposal-inbox-ack.schema.json", proposal)

    error = copy.deepcopy(FIXTURES["error.schema.json"][0])
    error["error"]["retryable"] = "false"
    assert_invalid("error.schema.json", error)

    chat = copy.deepcopy(FIXTURES["chat-request.schema.json"][0])
    chat["message"] = "x" * 4001
    assert_invalid("chat-request.schema.json", chat)
    chat["message"] = "synthetic"
    chat["sent_at"] = "2026-99-99T99:99:99Z"
    assert_invalid("chat-request.schema.json", chat)

    response = copy.deepcopy(FIXTURES["chat-response.schema.json"][0])
    response["tool_call_count"] = 7
    assert_invalid("chat-response.schema.json", response)
    response["tool_call_count"] = 2
    response["agent_status"] = "live-ish"
    assert_invalid("chat-response.schema.json", response)

    negotiation = copy.deepcopy(FIXTURES["capability-negotiation.schema.json"][0])
    negotiation["supported_contract_versions"] = [2, 3]
    assert_invalid("capability-negotiation.schema.json", negotiation)
    negotiation = copy.deepcopy(FIXTURES["capability-negotiation.schema.json"][0])
    negotiation["limits"]["max_page_size"] = 33
    assert_invalid("capability-negotiation.schema.json", negotiation)


def test_legacy_v1_v2_v3_schema_files_are_unchanged_and_keep_their_versions():
    legacy_dir = ROOT / "contracts"
    for filename, expected_hash in LEGACY_SHA256.items():
        raw = (legacy_dir / filename).read_bytes()
        assert hashlib.sha256(raw).hexdigest() == expected_hash, filename
        parsed = json.loads(raw)
        assert isinstance(parsed, dict), filename

    assert json.loads((legacy_dir / "directive.schema.json").read_text())["properties"]["schema_version"]["const"] == 1
    assert json.loads((legacy_dir / "proposal-v2.schema.json").read_text())["properties"]["schema_version"]["const"] == 2
    assert json.loads((legacy_dir / "plan-revision-v3.schema.json").read_text())["properties"]["schema_version"]["const"] == 3
    assert json.loads((legacy_dir / "sport-plan-v1.schema.json").read_text())["properties"]["schema_version"]["const"] == 1
    assert json.loads((legacy_dir / "error-v1.schema.json").read_text())["properties"]["contract_version"]["const"] == 1
    sync_v1 = json.loads((legacy_dir / "sync-v1.schema.json").read_text())
    assert sync_v1["oneOf"][0]["properties"]["contract_version"]["const"] == 1
    assert sync_v1["$defs"]["acknowledgement"]["properties"]["contract_version"]["const"] == 1
    assert json.loads((legacy_dir / "atlas-remote-v1.schema.json").read_text())["$id"].endswith("atlas-remote-v1.schema.json")
