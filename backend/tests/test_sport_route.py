from __future__ import annotations

from types import SimpleNamespace

import pytest
from atlas_backend.integrations.wger import WgerError


class FakeWgerClient:
    endpoints = {
        "routine": [{"id": 7, "name": "Routine privée", "is_active": True}],
        "day": [{"id": 21, "routine": 7, "name": "Jambes", "order": 1}],
        "slot": [{"id": 31, "day": 21, "exercise": 41, "sets": 3, "reps": 8}],
        "exercise": [{"id": 41, "name": "Squat"}],
        "workoutlog": [],
        "workoutsession": [
            {
                "id": 51,
                "datetime_start": "2026-10-26T08:00:00+00:00",
                "datetime_end": "2026-10-26T08:45:00+00:00",
                "day": 21,
                "routine": 7,
            },
        ],
    }

    def __init__(self, base_url, auth, http, limits=None):
        self.base_url = base_url.rstrip("/")
        self.auth = auth
        self.limits = limits or SimpleNamespace(page_size=50, max_pages=5, max_items=100)
        self.http = http
        self.read_endpoints = []

    def _read_page(self, endpoint, url, *, params=None):
        self.read_endpoints.append(endpoint)
        rows = self.endpoints[endpoint]
        return SimpleNamespace(results=rows, next_url=None)


def _get_plan(client, headers):
    return client.get(
        "/v1/sport/plan",
        params={"local_date": "2026-10-26", "timezone": "Europe/Paris"},
        headers=headers,
    )


def test_sport_route_projects_bounded_read_only_plan(client, auth_headers, monkeypatch):
    from atlas_backend import main

    monkeypatch.setenv("WGER_AUTH_MODE", "api_token")
    monkeypatch.setenv("WGER_API_TOKEN", "sport-api-token-canary")
    monkeypatch.setattr(main, "WgerClient", FakeWgerClient)

    response = _get_plan(client, auth_headers)

    assert response.status_code == 200
    body = response.json()
    assert body["schema_version"] == 1
    assert body["local_date"] == "2026-10-26"
    assert body["timezone"] == "Europe/Paris"
    assert body["source"] == "WGER_READ"
    assert body["sync_state"] == "FRESH"
    assert body["planned"] is True
    assert body["routine_id"] == "7"
    assert body["day_id"] == "21"
    assert body["exercises"] == [
        {"exercise_id": "41", "name": "Squat", "sets": 3, "reps": 8},
    ]
    assert body["performed"][0]["result_id"] == "51"
    assert body["performed"][0]["verification"] == "API_READ_BACK"
    assert body["limits"] == {"max_exercises": 50, "truncated": False}


def test_sport_route_without_configuration_returns_unavailable(client, auth_headers, monkeypatch):
    from atlas_backend import main

    monkeypatch.delenv("WGER_AUTH_MODE", raising=False)
    monkeypatch.delenv("WGER_API_TOKEN", raising=False)
    monkeypatch.setattr(
        main,
        "WgerClient",
        lambda *args, **kwargs: pytest.fail("client must not be created without configuration"),
    )

    response = _get_plan(client, auth_headers)

    assert response.status_code == 200
    assert response.json()["sync_state"] == "UNAVAILABLE"
    assert response.json()["planned"] is False
    assert response.json()["performed"] == []
    assert response.json()["generated_at"] is None


@pytest.mark.parametrize(
    ("error_code", "expected_state"),
    [
        ("RATE_LIMITED", "STALE"),
        ("SCHEMA_ERROR", "MISMATCH"),
        ("AUTH_INVALID", "UNAVAILABLE"),
    ],
)
def test_sport_route_maps_wger_errors_without_exposing_error_details(
    client,
    auth_headers,
    monkeypatch,
    error_code,
    expected_state,
):
    from atlas_backend import main

    monkeypatch.setenv("WGER_AUTH_MODE", "api_token")
    monkeypatch.setenv("WGER_API_TOKEN", "sport-api-token-canary")

    class FailingClient(FakeWgerClient):
        def _read_page(self, endpoint, url, *, params=None):
            raise WgerError(error_code)

    monkeypatch.setattr(main, "WgerClient", FailingClient)

    response = _get_plan(client, auth_headers)
    body_text = response.text

    assert response.status_code == 200
    assert response.json()["sync_state"] == expected_state
    assert "sport-api-token-canary" not in body_text
    assert error_code not in body_text


def test_sport_route_refuses_jwt_refresh_without_constructing_a_client(client, auth_headers, monkeypatch):
    from atlas_backend import main

    monkeypatch.setenv("WGER_AUTH_MODE", "jwt_refresh")
    monkeypatch.setenv("WGER_API_TOKEN", "unused-api-token-canary")
    monkeypatch.setenv("WGER_REFRESH_TOKEN", "rotating-refresh-canary")
    monkeypatch.setattr(
        main,
        "WgerClient",
        lambda *args, **kwargs: pytest.fail("jwt_refresh route must not create a wger client"),
    )

    response = _get_plan(client, auth_headers)

    assert response.status_code == 200
    assert response.json()["sync_state"] == "UNAVAILABLE"
    assert "unused-api-token-canary" not in response.text
    assert "rotating-refresh-canary" not in response.text


def test_sport_route_response_never_contains_configured_secrets(client, auth_headers, monkeypatch):
    from atlas_backend import main

    monkeypatch.setenv("WGER_AUTH_MODE", "api_token")
    monkeypatch.setenv("WGER_API_TOKEN", "private-wger-token")
    monkeypatch.setenv("WGER_REFRESH_TOKEN", "private-refresh-token")
    monkeypatch.setattr(main, "WgerClient", FakeWgerClient)

    response = _get_plan(client, auth_headers)

    assert response.status_code == 200
    assert "private-wger-token" not in response.text
    assert "private-refresh-token" not in response.text
