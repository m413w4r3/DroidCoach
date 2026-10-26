from __future__ import annotations

import json

import httpx
import pytest

from atlas_backend.integrations.wger import (
    ApiTokenWgerAuth,
    WgerClient,
    WgerError,
    WgerLimits,
)

BASE = "https://wger.example"


def page(results, *, count=None, next_url=None, previous_url=None):
    return {
        "count": len(results) if count is None else count,
        "next": next_url,
        "previous": previous_url,
        "results": results,
    }


def make_client(handler, *, limits=None, auth=None):
    http = httpx.Client(transport=httpx.MockTransport(handler))
    client = WgerClient(
        BASE,
        auth or ApiTokenWgerAuth("unit-test-value"),
        http,
        limits or WgerLimits(),
    )
    return client, http


def test_routines_follow_validated_multiple_pages_and_use_get_only():
    seen = []

    def handler(request):
        seen.append(request)
        assert request.method == "GET"
        assert request.headers["Authorization"] == "Token unit-test-value"
        if request.url.params.get("offset") == "0":
            payload = page(
                [{"id": 1, "name": "Routine A"}, {"id": 2, "name": "Routine B"}],
                count=3,
                next_url=f"{BASE}/api/v2/routine/?limit=2&offset=2",
            )
        else:
            payload = page(
                [{"id": 3, "name": "Routine C"}],
                count=3,
                previous_url=f"{BASE}/api/v2/routine/?limit=2&offset=0",
            )
        return httpx.Response(200, json=payload)

    client, http = make_client(handler, limits=WgerLimits(page_size=2, max_pages=3, max_items=10))
    try:
        routines = client.list_routines()
    finally:
        http.close()

    assert [routine.id for routine in routines] == [1, 2, 3]
    assert [request.url.params.get("offset") for request in seen] == ["0", "2"]


def test_all_list_methods_use_expected_read_only_endpoints():
    paths = []

    def handler(request):
        paths.append(request.url.path)
        if request.url.path.endswith("/routine/"):
            results = [{"id": 1, "name": "Routine"}]
        elif request.url.path.endswith("/exercise/"):
            results = [{"id": 2, "name": "Exercise"}]
        else:
            results = [{"id": 3, "date": "2026-10-26", "exercise": 2}]
        return httpx.Response(200, json=page(results))

    client, http = make_client(handler)
    try:
        assert client.list_routines()[0].name == "Routine"
        assert client.list_exercises()[0].name == "Exercise"
        assert client.list_workout_logs()[0].exercise_id == 2
    finally:
        http.close()
    assert paths == [
        "/api/v2/routine/",
        "/api/v2/exercise/",
        "/api/v2/workoutlog/",
    ]


@pytest.mark.parametrize(
    ("status", "expected"),
    [(401, "AUTH_INVALID"), (403, "FORBIDDEN"), (429, "RATE_LIMITED"), (503, "SERVER_ERROR")],
)
def test_http_errors_have_stable_categories_and_redacted_messages(status, expected, caplog):
    marker = "response-body-canary-value"

    def handler(request):
        return httpx.Response(status, headers={"Retry-After": "3600"}, text=marker)

    client, http = make_client(handler)
    try:
        with pytest.raises(WgerError) as captured:
            client.list_routines()
    finally:
        http.close()

    error = captured.value
    assert error.code == expected
    assert error.status_code == status
    assert marker not in str(error)
    assert marker not in repr(error)
    assert marker not in caplog.text
    if status == 429:
        assert error.retry_after == 60


def test_timeout_is_categorized_without_exposing_transport_message(caplog):
    marker = "transport-canary-value"

    def handler(request):
        raise httpx.ReadTimeout(marker)

    client, http = make_client(handler)
    try:
        with pytest.raises(WgerError) as captured:
            client.list_routines()
    finally:
        http.close()
    assert captured.value.code == "TIMEOUT"
    assert marker not in str(captured.value)
    assert marker not in repr(captured.value)
    assert marker not in caplog.text


def test_invalid_schema_is_redacted():
    def handler(request):
        return httpx.Response(200, json={"count": "not-a-count", "results": [], "next": None, "previous": None})

    client, http = make_client(handler)
    try:
        with pytest.raises(WgerError) as captured:
            client.list_routines()
    finally:
        http.close()
    assert captured.value.code == "SCHEMA_ERROR"


def test_invalid_summary_schema_is_redacted():
    def handler(request):
        return httpx.Response(200, json=page([{"id": 1, "name": {"fr": "texte"}}]))

    client, http = make_client(handler)
    try:
        with pytest.raises(WgerError) as captured:
            client.list_routines()
    finally:
        http.close()
    assert captured.value.code == "SCHEMA_ERROR"


def test_pagination_rejects_cross_origin_links():
    def handler(request):
        return httpx.Response(
            200,
            json=page(
                [{"id": 1, "name": "Routine"}],
                count=2,
                next_url="https://other.example/api/v2/routine/?limit=50&offset=1",
            ),
        )

    client, http = make_client(handler)
    try:
        with pytest.raises(WgerError) as captured:
            client.list_routines()
    finally:
        http.close()
    assert captured.value.code == "SCHEMA_ERROR"


def test_pagination_cycle_and_page_count_are_bounded():
    calls = 0

    def handler(request):
        nonlocal calls
        calls += 1
        return httpx.Response(
            200,
            json=page(
                [{"id": calls, "name": "Routine"}],
                count=10,
                next_url=f"{BASE}/api/v2/routine/?limit=1&offset={calls}",
            ),
        )

    client, http = make_client(handler, limits=WgerLimits(page_size=1, max_pages=2, max_items=10))
    try:
        results = client.list_routines()
    finally:
        http.close()
    assert len(results) == 2
    assert calls == 2


def test_probe_output_contains_only_allowlisted_fields_and_no_names():
    from scripts.wger_diagnostic import _line

    def handler(request):
        return httpx.Response(
            200,
            json=page(
                [{"id": 1, "name": "Private Routine Name"}],
                count=2,
                next_url=f"{BASE}{request.url.path}?limit=50&offset=1",
            ),
        )

    client, http = make_client(handler)
    try:
        probe = client.probe()
    finally:
        http.close()
    output = _line("api_token", probe.routines)
    parsed = json.loads(output)
    assert set(parsed) == {
        "mode",
        "status_http",
        "error_category",
        "element_count",
        "pagination_present",
    }
    assert parsed["pagination_present"] is True
    assert "Private Routine Name" not in output
