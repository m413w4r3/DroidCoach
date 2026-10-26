"""Pure, bounded projection of already-read wger workout data."""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from datetime import date, datetime
from typing import Any

SCHEMA_VERSION = 1
MAX_EXERCISES = 50
MAX_SETS = 10
MAX_NAME_LENGTH = 120
MAX_ID_LENGTH = 128
MAX_TIMESTAMP_LENGTH = 40
MAX_SOURCE_ROWS = 500

_SOURCES = {"WGER_READ", "FIXTURE"}
_SYNC_STATES = {"FRESH", "STALE", "UNAVAILABLE", "MISMATCH"}
_UNAVAILABLE_CODES = {"CONFIG_REQUIRED", "UNAVAILABLE", "FORBIDDEN"}
_MISMATCH_CODES = {"SCHEMA_ERROR"}
_STALE_CODES = {"RATE_LIMITED", "SERVER_ERROR", "TIMEOUT"}


def project_sport_plan(
    routine: Mapping[str, Any] | None = None,
    day: Mapping[str, Any] | None = None,
    day_exercises: Sequence[Mapping[str, Any]] = (),
    workout_logs: Sequence[Mapping[str, Any]] = (),
    workout_sessions: Sequence[Mapping[str, Any]] = (),
    local_date: str | date | None = None,
    timezone: str | None = None,
    source: str = "WGER_READ",
    sync_state: str = "UNAVAILABLE",
    generated_at: str | datetime | None = None,
) -> dict[str, Any]:
    """Project synthetic or already-read mappings without performing I/O.

    Malformed source rows are skipped independently. IDs are treated as opaque
    values and only converted to strings; this function never invents a result
    from a prescription.
    """
    routine_map = _mapping(routine)
    day_map = _mapping(day)
    logs, logs_truncated = _bounded_rows(workout_logs)
    sessions, sessions_truncated = _bounded_rows(workout_sessions)
    exercise_rows, exercise_rows_truncated = _bounded_rows(day_exercises, MAX_EXERCISES + 1)

    log_rows: list[dict[str, Any]] = []
    for log in logs:
        row = _mapping(log)
        if row is not None:
            log_rows.append(row)

    last_by_exercise: dict[str, dict[str, Any]] = {}
    for exercise_id in {_exercise_id(row) for row in exercise_rows if _mapping(row) is not None}:
        if exercise_id is None:
            continue
        performance, was_truncated = _last_performance(exercise_id, log_rows)
        if performance is not None:
            last_by_exercise[exercise_id] = performance
        logs_truncated = logs_truncated or was_truncated

    exercises: list[dict[str, Any]] = []
    truncated = logs_truncated or sessions_truncated or exercise_rows_truncated
    for raw_row in exercise_rows:
        row = _mapping(raw_row)
        if row is None:
            continue
        exercise_value = row.get("exercise")
        exercise_map = _mapping(exercise_value)
        exercise_id = _identifier(
            exercise_map.get("id") if exercise_map is not None else exercise_value
        )
        sets = _integer(row.get("sets"), minimum=1, maximum=10_000)
        reps = _integer(row.get("reps"), minimum=0, maximum=1_000)
        if exercise_id is None or sets is None or reps is None:
            continue

        projected_sets = min(sets, MAX_SETS)
        truncated = truncated or sets > MAX_SETS
        exercise: dict[str, Any] = {
            "exercise_id": exercise_id,
            "sets": projected_sets,
            "reps": reps,
        }
        name = _bounded_name(
            exercise_map.get("name") if exercise_map is not None else row.get("name")
        )
        if name is not None:
            exercise["name"] = name
            original_name = exercise_map.get("name") if exercise_map is not None else row.get("name")
            truncated = truncated or len(original_name) > MAX_NAME_LENGTH

        weight_value = row.get("weight_kg", row.get("weight"))
        weight = _weight(weight_value)
        if weight is not None:
            exercise["weight_kg"] = weight
        rest = _integer(row.get("rest_seconds"), minimum=0, maximum=86_400)
        if rest is not None:
            exercise["rest_seconds"] = rest
        last_performance = last_by_exercise.get(exercise_id)
        if last_performance is not None:
            exercise["last_performance"] = last_performance
        exercises.append(exercise)

    if len(exercises) > MAX_EXERCISES:
        exercises = exercises[:MAX_EXERCISES]
        truncated = True

    performed: list[dict[str, Any]] = []
    session_ids: set[str] = set()
    for raw_session in sessions:
        session = _mapping(raw_session)
        if session is None:
            continue
        result_id = _identifier(session.get("id"))
        performed_at = _aware_timestamp(
            session.get("datetime_start", session.get("datetime", session.get("date")))
        )
        if result_id is None or performed_at is None:
            continue
        session_ids.add(result_id)
        result: dict[str, Any] = {
            "result_id": result_id,
            "performed_at": performed_at,
            "duration_minutes": _duration(
                session.get("duration_minutes", session.get("duration"))
            ),
            "source": "WGER_API",
            "verification": "API_READ_BACK",
        }
        performed.append(result)

    # workoutlog rows can stand alone when wger has no corresponding session.
    # Date-only logs are intentionally omitted: they do not contain a timezone.
    for row in log_rows:
        session_id = _identifier(row.get("session"))
        if session_id is not None and session_id in session_ids:
            continue
        result_id = _identifier(row.get("id"))
        performed_at = _aware_timestamp(row.get("datetime", row.get("date")))
        if result_id is None or performed_at is None:
            continue
        performed.append({
            "result_id": result_id,
            "performed_at": performed_at,
            "duration_minutes": None,
            "source": "WGER_API",
            "verification": "API_READ_BACK",
        })

    if len(performed) > MAX_EXERCISES:
        performed = performed[:MAX_EXERCISES]
        truncated = True

    routine_id = _identifier(routine_map.get("id")) if routine_map is not None else None
    day_id = _identifier(day_map.get("id")) if day_map is not None else None
    day_name = _bounded_name(day_map.get("name")) if day_map is not None else None
    if day_map is not None and isinstance(day_map.get("name"), str):
        truncated = truncated or len(day_map["name"]) > MAX_NAME_LENGTH
    if routine_id is None and day_map is not None:
        day_routine = day_map.get("routine")
        day_routine_map = _mapping(day_routine)
        routine_id = _identifier(
            day_routine_map.get("id") if day_routine_map is not None else day_routine
        )

    return {
        "schema_version": SCHEMA_VERSION,
        "local_date": _local_date(local_date),
        "timezone": _bounded_timezone(timezone),
        "source": source if isinstance(source, str) and source in _SOURCES else "WGER_READ",
        "sync_state": sync_state if isinstance(sync_state, str) and sync_state in _SYNC_STATES else "UNAVAILABLE",
        "routine_id": routine_id,
        "day_id": day_id,
        "day_name": day_name,
        "planned": bool(exercises) or day_id is not None or day_name is not None,
        "exercises": exercises,
        "performed": performed,
        "generated_at": _aware_timestamp(generated_at),
        "limits": {"max_exercises": MAX_EXERCISES, "truncated": truncated},
    }


def empty_sport_plan(
    local_date: str | date | None = None,
    timezone: str | None = None,
    source: str = "WGER_READ",
    sync_state: str = "UNAVAILABLE",
    generated_at: str | datetime | None = None,
) -> dict[str, Any]:
    """Return a no-session projection; it never reports a completed workout."""
    plan = project_sport_plan(
        local_date=local_date,
        timezone=timezone,
        source=source,
        sync_state=sync_state,
        generated_at=generated_at,
    )
    plan["planned"] = False
    plan["performed"] = []
    return plan


def wger_error_to_sync_state(code: str) -> str:
    """Map stable wger error categories to a non-fresh synchronization state."""
    if not isinstance(code, str):
        return "UNAVAILABLE"
    if code in _UNAVAILABLE_CODES:
        return "UNAVAILABLE"
    if code in _MISMATCH_CODES:
        return "MISMATCH"
    if code in _STALE_CODES:
        return "STALE"
    return "UNAVAILABLE"


def _mapping(value: Any) -> Mapping[str, Any] | None:
    return value if isinstance(value, Mapping) else None


def _bounded_rows(value: Sequence[Mapping[str, Any]], limit: int = MAX_SOURCE_ROWS) -> tuple[list[Any], bool]:
    if isinstance(value, (str, bytes, bytearray)) or not isinstance(value, Sequence):
        return [], False
    try:
        rows = list(value[: limit + 1])
    except (IndexError, TypeError, ValueError):
        return [], False
    return rows[:limit], len(rows) > limit


def _identifier(value: Any) -> str | None:
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, int):
        text = str(value)
    elif isinstance(value, str):
        text = value
    else:
        return None
    if not text or len(text) > MAX_ID_LENGTH:
        return None
    return text


def _exercise_id(row: Mapping[str, Any]) -> str | None:
    value = row.get("exercise")
    exercise = _mapping(value)
    return _identifier(exercise.get("id") if exercise is not None else value)


def _bounded_name(value: Any) -> str | None:
    if not isinstance(value, str) or not value:
        return None
    return value[:MAX_NAME_LENGTH]


def _integer(value: Any, *, minimum: int, maximum: int) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        result = value
    elif isinstance(value, float) and math.isfinite(value) and value.is_integer():
        result = int(value)
    elif isinstance(value, str) and 0 < len(value) <= 16:
        try:
            result = int(value, 10)
        except ValueError:
            return None
    else:
        return None
    return result if minimum <= result <= maximum else None


def _weight(value: Any) -> float | None:
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, str):
        if not value or len(value) > 32:
            return None
        try:
            result = float(value)
        except ValueError:
            return None
    elif isinstance(value, (int, float)):
        result = float(value)
    else:
        return None
    if not math.isfinite(result) or not 0 <= result <= 1_000:
        return None
    return result


def _local_date(value: str | date | None) -> str | None:
    if isinstance(value, datetime):
        return None
    if isinstance(value, date):
        return value.isoformat()
    if not isinstance(value, str) or len(value) != 10:
        return None
    try:
        parsed = date.fromisoformat(value)
    except ValueError:
        return None
    return value if parsed.isoformat() == value else None


def _bounded_timezone(value: str | None) -> str | None:
    if not isinstance(value, str) or not value or len(value) > 100:
        return None
    return value


def _aware_timestamp(value: str | datetime | None) -> str | None:
    if isinstance(value, datetime):
        parsed = value
    elif isinstance(value, str) and 0 < len(value) <= MAX_TIMESTAMP_LENGTH:
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return None
    else:
        return None
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        return None
    result = parsed.isoformat()
    return result if len(result) <= MAX_TIMESTAMP_LENGTH else None


def _duration(value: Any) -> int | None:
    return _integer(value, minimum=0, maximum=1_440)


def _last_performance(
    exercise_id: str,
    logs: Sequence[Mapping[str, Any]],
) -> tuple[dict[str, Any] | None, bool]:
    valid: list[tuple[datetime, str, int, float | None]] = []
    for log in logs:
        if _exercise_id(log) != exercise_id:
            continue
        raw_time = log.get("datetime", log.get("date"))
        timestamp = _aware_timestamp(raw_time)
        if timestamp is None:
            continue
        try:
            parsed_time = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
        except ValueError:
            continue
        reps = _integer(log.get("reps", log.get("repetitions")), minimum=0, maximum=1_000)
        if reps is None:
            continue
        valid.append((parsed_time, timestamp, reps, _weight(log.get("weight_kg", log.get("weight")))))
    if not valid:
        return None, False
    latest_time = max(row[0] for row in valid)
    latest = [row for row in valid if row[0] == latest_time]
    was_truncated = len(latest) > MAX_SETS
    sets = []
    for _, _, reps, weight in latest[:MAX_SETS]:
        item: dict[str, Any] = {"reps": reps}
        if weight is not None:
            item["weight_kg"] = weight
        sets.append(item)
    return {"performed_at": latest[0][1], "sets": sets}, was_truncated
