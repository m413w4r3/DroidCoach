from __future__ import annotations

import json
from types import SimpleNamespace

import httpx
import pytest

from atlas_backend.integrations.wger import (
    ApiTokenWgerAuth,
    WgerClient,
    WgerError,
    WgerLimits,
    WgerResourceProbe,
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


def test_probe_checks_all_resources_with_limit_one():
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(200, json=page([]))

    client, http = make_client(handler)
    try:
        probe = client.probe()
    finally:
        http.close()

    assert [request.url.path for request in requests] == [
        "/api/v2/routine/",
        "/api/v2/exercise/",
        "/api/v2/workoutlog/",
        "/api/v2/workoutsession/",
    ]
    assert [request.url.params["limit"] for request in requests] == ["1"] * 4
    assert all(resource.status_http == 200 for resource in vars(probe).values())


def test_workout_logs_and_sessions_parse_real_shapes_across_pages():
    entries = {
        "/api/v2/workoutlog/": [
            {
                "id": "log-opaque-a",
                "date": "2026-10-26",
                "exercise": 12,
                "routine": 3,
                "session": "session-opaque-a",
                "slot_entry": 4,
                "repetitions": "8",
                "weight": "42.5",
            },
            {
                "id": 17,
                "date": "2026-10-27",
                "exercise": 13,
                "routine": None,
                "session": None,
                "slot_entry": 5,
                "repetitions": "9",
                "weight": "45",
            },
        ],
        "/api/v2/workoutsession/": [
            {
                "id": "session-opaque-a",
                "datetime_start": "2026-10-26T08:30:00Z",
                "datetime_end": "2026-10-26T09:15:00Z",
                "day": 2,
                "routine": 3,
                "impression": "Good",
                "notes": "Synthetic note",
            },
            {
                "id": "session-opaque-b",
                "datetime_start": "2026-10-27T08:30:00+02:00",
                "datetime_end": "2026-10-27T09:15:00+02:00",
                "day": None,
                "routine": None,
                "impression": "",
                "notes": "",
            },
        ],
    }
    requests = []

    def handler(request):
        requests.append(request)
        assert request.method == "GET"
        records = entries[request.url.path]
        offset = int(request.url.params["offset"])
        next_url = (
            f"{BASE}{request.url.path}?limit=1&offset=1" if offset == 0 else None
        )
        previous_url = (
            f"{BASE}{request.url.path}?limit=1&offset=0" if offset == 1 else None
        )
        return httpx.Response(
            200,
            json=page(
                records[offset : offset + 1],
                count=len(records),
                next_url=next_url,
                previous_url=previous_url,
            ),
        )

    client, http = make_client(
        handler, limits=WgerLimits(page_size=1, max_pages=3, max_items=10)
    )
    try:
        logs = client.list_workout_logs()
        sessions = client.list_workout_sessions()
    finally:
        http.close()

    assert [(item.id, item.exercise_id, item.routine_id, item.session_id) for item in logs] == [
        ("log-opaque-a", 12, 3, "session-opaque-a"),
        ("17", 13, None, None),
    ]
    assert [
        (item.id, item.datetime_start, item.datetime_end, item.day_id, item.routine_id)
        for item in sessions
    ] == [
        ("session-opaque-a", "2026-10-26T08:30:00Z", "2026-10-26T09:15:00Z", 2, 3),
        (
            "session-opaque-b",
            "2026-10-27T08:30:00+02:00",
            "2026-10-27T09:15:00+02:00",
            None,
            None,
        ),
    ]
    assert [request.url.path for request in requests] == [
        "/api/v2/workoutlog/",
        "/api/v2/workoutlog/",
        "/api/v2/workoutsession/",
        "/api/v2/workoutsession/",
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


def test_oversized_response_is_rejected_before_json_decode(monkeypatch):
    from atlas_backend.integrations import wger as wger_module

    monkeypatch.setattr(wger_module, "MAX_RESPONSE_BYTES", 8)

    class OversizedResponse:
        status_code = 200
        content = b"x" * 9
        headers = {"Content-Length": "9"}

        def json(self):
            raise AssertionError("oversized response must not be decoded")

    class FakeHttp:
        def get(self, *args, **kwargs):
            return OversizedResponse()

    client = WgerClient(BASE, ApiTokenWgerAuth("unit-test-value"), FakeHttp())
    with pytest.raises(WgerError) as captured:
        client.list_routines()
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


@pytest.mark.parametrize(
    ("endpoint", "entry"),
    [
        (
            "workoutlog",
            {"id": "bad\u0000id", "date": "2026-10-26", "exercise": 1},
        ),
        (
            "workoutlog",
            {"id": "x" * 65, "date": "2026-10-26", "exercise": 1},
        ),
        (
            "workoutsession",
            {
                "id": "session-a",
                "datetime_start": "not-a-date",
                "datetime_end": "2026-10-26T09:15:00Z",
            },
        ),
        (
            "workoutsession",
            {
                "id": "session-a",
                "datetime_start": "2026-10-26T08:30:00Z",
                "datetime_end": "2026-10-26",
            },
        ),
    ],
)
def test_workout_log_and_session_invalid_shapes_are_schema_errors(endpoint, entry):
    def handler(request):
        return httpx.Response(200, json=page([entry]))

    client, http = make_client(handler, limits=WgerLimits(page_size=1))
    try:
        with pytest.raises(WgerError) as captured:
            if endpoint == "workoutlog":
                client.list_workout_logs()
            else:
                client.list_workout_sessions()
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


def test_diagnostic_uses_configured_base_url_and_disables_redirects(monkeypatch, capsys):
    from scripts import wger_diagnostic as diagnostic

    base_url = "https://custom-wger.example"
    observed = {}
    monkeypatch.setenv("WGER_AUTH_MODE", "api_token")
    monkeypatch.setenv("WGER_API_TOKEN", "diagnostic-token-canary")
    monkeypatch.setenv("WGER_BASE_URL", base_url)

    class FakeHttp:
        def __init__(self, **kwargs):
            observed["http_options"] = kwargs

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

    class FakeWgerClient:
        def __init__(self, configured_base_url, auth, http, limits):
            observed["base_url"] = configured_base_url

        def probe(self):
            result = WgerResourceProbe(200, None, 1, False)
            return SimpleNamespace(
                routines=result,
                exercises=result,
                workout_logs=result,
                workout_sessions=result,
            )

    monkeypatch.setattr(diagnostic.httpx, "Client", FakeHttp)
    monkeypatch.setattr(diagnostic, "WgerClient", FakeWgerClient)
    result = diagnostic.main(["--mode", "api_token", "--read-only"])
    output = capsys.readouterr().out

    assert result == 0
    assert observed["base_url"] == base_url
    assert observed["http_options"] == {"follow_redirects": False}
    assert base_url not in output
    assert "diagnostic-token-canary" not in output
    assert len(output.splitlines()) == 4
    for line in output.splitlines():
        assert set(json.loads(line)) == {
            "mode",
            "status_http",
            "error_category",
            "element_count",
            "pagination_present",
        }


def test_diagnostic_rejects_non_https_base_url_without_echoing_it(monkeypatch, capsys):
    from scripts import wger_diagnostic as diagnostic

    base_url = "http://wger.invalid/private-path"
    options = []
    monkeypatch.setenv("WGER_AUTH_MODE", "api_token")
    monkeypatch.setenv("WGER_API_TOKEN", "diagnostic-token-canary")
    monkeypatch.setenv("WGER_BASE_URL", base_url)

    class FakeHttp:
        def __init__(self, **kwargs):
            options.append(kwargs)

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

    monkeypatch.setattr(diagnostic.httpx, "Client", FakeHttp)
    result = diagnostic.main(["--mode", "api_token", "--read-only"])
    output = capsys.readouterr().out

    assert result == 2
    assert options == [{"follow_redirects": False}]
    assert '"error_category":"CONFIG_REQUIRED"' in output
    assert base_url not in output
    assert "diagnostic-token-canary" not in output


def test_diagnostic_dotenv_and_argument_errors_are_redacted(monkeypatch, capsys):
    from scripts import wger_diagnostic as diagnostic

    path_canary = "/tmp/private-env-path-canary"
    monkeypatch.setattr(
        diagnostic,
        "load_dotenv_if_enabled",
        lambda _path: (_ for _ in ()).throw(WgerError("CONFIG_REQUIRED")),
    )
    result = diagnostic.main(
        ["--mode", "api_token", "--read-only", "--env-file", path_canary]
    )
    output = capsys.readouterr().out
    assert result == 2
    assert path_canary not in output
    assert "Traceback" not in output

    with pytest.raises(SystemExit) as invalid_argument:
        diagnostic.main(["--mode", "argument-canary", "--read-only"])
    errors = capsys.readouterr().err
    assert invalid_argument.value.code == 2
    assert "argument-canary" not in errors
    assert "Traceback" not in errors
