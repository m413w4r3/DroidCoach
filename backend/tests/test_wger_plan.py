"""Tests for the pure, bounded sport-plan projection and its JSON contract."""

from __future__ import annotations

import ast
import json
import math
import re
from datetime import date, datetime
from pathlib import Path

from atlas_backend.integrations.wger_plan import (
    empty_sport_plan,
    project_sport_plan,
    wger_error_to_sync_state,
)

ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = ROOT / "contracts" / "sport-plan-v1.schema.json"

CONTRACT_FIXTURE = {
    "schema_version": 1,
    "local_date": "2026-10-26",
    "timezone": "Europe/Paris",
    "source": "FIXTURE",
    "sync_state": "FRESH",
    "routine_id": "12",
    "day_id": "34",
    "day_name": "Séance A",
    "planned": True,
    "exercises": [
        {
            "exercise_id": "56",
            "name": "Squat",
            "sets": 3,
            "reps": 8,
            "weight_kg": 40.0,
            "rest_seconds": 90,
        }
    ],
    "performed": [],
    "generated_at": "2026-10-26T15:00:00Z",
    "limits": {"max_exercises": 50, "truncated": False},
}

_SCHEMA_KEYWORDS = {
    "$schema", "$id", "title", "type", "const", "enum", "minimum", "maximum",
    "minLength", "maxLength", "pattern", "format", "required", "properties",
    "additionalProperties", "items", "minItems", "maxItems",
}


def test_projects_planned_session_with_exercises_and_last_performance():
    plan = project_sport_plan(
        routine={"id": 12, "name": "Routine"},
        day={"id": 34, "name": "Séance A", "routine": 12},
        day_exercises=[
            {
                "exercise": {"id": 56, "name": "Squat"},
                "sets": 3,
                "reps": 8,
                "weight_kg": 40,
                "rest_seconds": 90,
            }
        ],
        workout_logs=[
            {
                "id": "log-1",
                "datetime": "2026-10-22T18:00:00Z",
                "exercise": 56,
                "reps": "8",
                "weight": "40.0",
                "session": "session-1",
            }
        ],
        workout_sessions=[
            {"id": "session-1", "datetime_start": "2026-10-26T18:30:00Z", "duration": 45}
        ],
        local_date="2026-10-26",
        timezone="Europe/Paris",
        generated_at="2026-10-26T15:00:00Z",
        sync_state="FRESH",
    )

    assert plan["planned"] is True
    assert plan["exercises"] == [
        {
            "exercise_id": "56",
            "name": "Squat",
            "sets": 3,
            "reps": 8,
            "weight_kg": 40.0,
            "rest_seconds": 90,
            "last_performance": {
                "performed_at": "2026-10-22T18:00:00+00:00",
                "sets": [{"reps": 8, "weight_kg": 40.0}],
            },
        }
    ]
    assert plan["performed"] == [
        {
            "result_id": "session-1",
            "performed_at": "2026-10-26T18:30:00+00:00",
            "duration_minutes": 45,
            "source": "WGER_API",
            "verification": "API_READ_BACK",
        }
    ]


def test_empty_day_is_not_a_performed_session():
    plan = empty_sport_plan(
        local_date="2026-10-26",
        timezone="Europe/Paris",
        source="FIXTURE",
        sync_state="FRESH",
        generated_at="2026-10-26T15:00:00Z",
    )

    assert plan["planned"] is False
    assert plan["exercises"] == []
    assert plan["performed"] == []


def test_missing_and_malformed_fields_are_omitted_not_fatal():
    plan = project_sport_plan(
        day={"id": True, "name": 42, "routine": {"id": False}},
        day_exercises=[
            {"exercise": True, "sets": "3", "reps": "8"},
            {"exercise": {"id": 5}, "sets": "three", "reps": 8},
            {"exercise": {"id": 6}, "sets": 2},
            {"exercise": 7, "sets": 2, "reps": 8, "weight": "not-a-number"},
        ],
        workout_logs=[{"id": None, "date": "not-a-date", "exercise": 7}],
        workout_sessions=[{"id": [], "date": "2026-10-26"}],
        local_date="not-a-date",
        timezone=None,
        source=[],
        sync_state=[],
        generated_at="no timestamp",
    )

    assert plan["day_id"] is None
    assert plan["routine_id"] is None
    assert plan["day_name"] is None
    assert plan["exercises"] == [{"exercise_id": "7", "sets": 2, "reps": 8}]
    assert plan["performed"] == []
    assert plan["local_date"] is None
    assert plan["timezone"] is None
    assert plan["generated_at"] is None
    assert plan["source"] == "WGER_READ"
    assert plan["sync_state"] == "UNAVAILABLE"


def test_ids_are_strings_even_when_input_is_numeric():
    plan = project_sport_plan(
        routine={"id": 12},
        day={"id": 34},
        day_exercises=[{"exercise": {"id": 56, "name": "Squat"}, "sets": 3, "reps": 8}],
        workout_sessions=[{"id": 78, "datetime_start": "2026-10-26T18:30:00Z"}],
        local_date=date(2026, 10, 26),
        timezone="Europe/Paris",
        generated_at=datetime.fromisoformat("2026-10-26T15:00:00+00:00"),
    )

    assert plan["routine_id"] == "12"
    assert plan["day_id"] == "34"
    assert plan["exercises"][0]["exercise_id"] == "56"
    assert plan["performed"][0]["result_id"] == "78"
    assert all(
        isinstance(identifier, str)
        for identifier in (plan["routine_id"], plan["day_id"], plan["exercises"][0]["exercise_id"])
    )


def test_truncates_at_bounds_and_sets_flag():
    rows = [
        {"exercise": {"id": index, "name": "x" * 140}, "sets": 12, "reps": 8}
        for index in range(55)
    ]
    plan = project_sport_plan(
        day_exercises=rows,
        local_date="2026-10-26",
        timezone="Europe/Paris",
        generated_at="2026-10-26T15:00:00Z",
    )

    assert len(plan["exercises"]) == 50
    assert all(exercise["sets"] == 10 for exercise in plan["exercises"])
    assert all(len(exercise["name"]) == 120 for exercise in plan["exercises"])
    assert plan["limits"] == {"max_exercises": 50, "truncated": True}


def test_error_mapping_never_claims_fresh():
    expected = {
        "CONFIG_REQUIRED": "UNAVAILABLE",
        "UNAVAILABLE": "UNAVAILABLE",
        "FORBIDDEN": "UNAVAILABLE",
        "SCHEMA_ERROR": "MISMATCH",
        "RATE_LIMITED": "STALE",
        "SERVER_ERROR": "STALE",
        "TIMEOUT": "STALE",
    }
    for code, state in expected.items():
        assert wger_error_to_sync_state(code) == state
    assert wger_error_to_sync_state("UNKNOWN") != "FRESH"


def test_output_validates_against_contract():
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    assert schema["$schema"] == "https://json-schema.org/draft/2020-12/schema"
    _assert_supported_schema(schema)
    _validate_schema(CONTRACT_FIXTURE, schema)
    projected = project_sport_plan(
        day={"id": 34, "name": "Séance A", "routine": 12},
        day_exercises=[{"exercise": {"id": 56, "name": "Squat"}, "sets": 3, "reps": 8}],
        local_date="2026-10-26",
        timezone="Europe/Paris",
        source="FIXTURE",
        sync_state="FRESH",
        generated_at="2026-10-26T15:00:00Z",
    )
    _validate_schema(projected, schema)


def test_projection_has_no_io_and_no_write_fields():
    source = Path(__import__("atlas_backend.integrations.wger_plan", fromlist=["__file__"]).__file__).read_text(
        encoding="utf-8"
    )
    tree = ast.parse(source)
    imported = {
        alias.name.split(".")[0]
        for node in ast.walk(tree)
        if isinstance(node, ast.Import)
        for alias in node.names
    } | {
        node.module.split(".")[0]
        for node in ast.walk(tree)
        if isinstance(node, ast.ImportFrom) and node.module
    }
    assert not imported.intersection({"httpx", "pathlib", "os", "sqlite3", "urllib"})
    assert not any(
        isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in {"open", "print"}
        for node in ast.walk(tree)
    )

    plan = empty_sport_plan("2026-10-26", "Europe/Paris", generated_at="2026-10-26T15:00:00Z")
    assert set(plan) == {
        "schema_version", "local_date", "timezone", "source", "sync_state", "routine_id", "day_id",
        "day_name", "planned", "exercises", "performed", "generated_at", "limits",
    }
    assert not {"write", "create", "update", "delete", "refresh_token"}.intersection(plan)


def _assert_supported_schema(schema: dict) -> None:
    unknown = set(schema) - _SCHEMA_KEYWORDS
    assert not unknown
    for child in schema.get("properties", {}).values():
        _assert_supported_schema(child)
    if isinstance(schema.get("items"), dict):
        _assert_supported_schema(schema["items"])


def _validate_schema(value, schema: dict, path: str = "$:") -> None:
    if "const" in schema:
        assert value == schema["const"], path
    if "enum" in schema:
        assert value in schema["enum"], path

    expected = schema.get("type")
    if expected is not None:
        types = expected if isinstance(expected, list) else [expected]
        if value is None and "null" in types:
            return
        type_matches = {
            "object": isinstance(value, dict),
            "array": isinstance(value, list),
            "string": isinstance(value, str),
            "integer": isinstance(value, int) and not isinstance(value, bool),
            "number": isinstance(value, (int, float)) and not isinstance(value, bool),
            "boolean": isinstance(value, bool),
            "null": value is None,
        }
        assert any(type_matches.get(kind, False) for kind in types), f"{path} type"

    if isinstance(value, dict):
        assert set(schema.get("required", ())) <= set(value), path
        if schema.get("additionalProperties") is False:
            assert set(value) <= set(schema.get("properties", {})), path
        for key, child in schema.get("properties", {}).items():
            if key in value:
                _validate_schema(value[key], child, f"{path}{key}.")
    if isinstance(value, list):
        assert len(value) >= schema.get("minItems", 0), path
        assert len(value) <= schema.get("maxItems", math.inf), path
        for index, item in enumerate(value):
            _validate_schema(item, schema["items"], f"{path}{index}.")
    if isinstance(value, str):
        assert len(value) >= schema.get("minLength", 0), path
        assert len(value) <= schema.get("maxLength", math.inf), path
        if "pattern" in schema:
            assert re.search(schema["pattern"], value), path
        if schema.get("format") == "date":
            assert date.fromisoformat(value).isoformat() == value, path
        if schema.get("format") == "date-time":
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
            assert parsed.tzinfo is not None and parsed.utcoffset() is not None, path
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        assert value >= schema.get("minimum", -math.inf), path
        assert value <= schema.get("maximum", math.inf), path
