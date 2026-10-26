from __future__ import annotations

import json
import os
import stat
import subprocess
import threading
from datetime import datetime, timedelta, timezone

import httpx
import pytest

from atlas_backend.config import load_dotenv_if_enabled, resolve_wger_auth_mode
from atlas_backend.integrations.wger import (
    ApiTokenWgerAuth,
    RotatingJwtWgerAuth,
    WgerError,
    WgerTokenRecord,
    WgerTokenStore,
)

NOW = datetime(2026, 10, 26, 9, 0, tzinfo=timezone.utc)


def seeded_store(tmp_path):
    store = WgerTokenStore(tmp_path / "private" / "wger.json")
    store.save(
        WgerTokenRecord(
            generation=1,
            access_token="access-value-1",
            refresh_token="refresh-value-1",
            obtained_at=NOW - timedelta(minutes=1),
            expires_at=NOW + timedelta(minutes=4),
        )
    )
    return store


def test_api_token_headers_are_exact_and_repr_is_redacted():
    auth = ApiTokenWgerAuth("api-token-canary")
    assert auth.headers() == {"Authorization": "Token api-token-canary"}
    assert "api-token-canary" not in repr(auth)


def test_jwt_refresh_is_explicit_rotates_and_persists(tmp_path):
    store = seeded_store(tmp_path)
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(
            200,
            json={"access": "access-value-2", "refresh": "refresh-value-2", "expires_in": 600},
        )

    http = httpx.Client(transport=httpx.MockTransport(handler))
    auth = RotatingJwtWgerAuth(
        store,
        http,
        now=lambda: NOW,
        token_url="https://wger.example/api/v2/token/refresh/",
    )
    try:
        assert auth.headers() == {"Authorization": "Bearer access-value-1"}
        assert requests == []
        assert auth.refresh() == {"Authorization": "Bearer access-value-2"}
        persisted = store.load()
    finally:
        http.close()

    assert len(requests) == 1
    assert requests[0].method == "POST"
    assert json.loads(requests[0].read()) == {"refresh": "refresh-value-1"}
    assert persisted is not None
    assert persisted.generation == 2
    assert persisted.access_token == "access-value-2"
    assert persisted.refresh_token == "refresh-value-2"
    assert persisted.state == "ACTIVE"
    assert "access-value-2" not in repr(persisted)
    assert "refresh-value-2" not in repr(persisted)


def test_two_concurrent_refreshes_send_only_one_request(tmp_path):
    store = seeded_store(tmp_path)
    started = threading.Event()
    release = threading.Event()
    request_count = 0
    count_lock = threading.Lock()

    def handler(request):
        nonlocal request_count
        with count_lock:
            request_count += 1
        started.set()
        assert release.wait(timeout=3)
        return httpx.Response(
            200,
            json={"access": "access-value-2", "refresh": "refresh-value-2", "expires_in": 600},
        )

    http = httpx.Client(transport=httpx.MockTransport(handler))
    first = RotatingJwtWgerAuth(
        store, http, now=lambda: NOW, token_url="https://wger.example/token/"
    )
    second = RotatingJwtWgerAuth(
        store, http, now=lambda: NOW, token_url="https://wger.example/token/"
    )
    results = []

    def rotate(auth):
        try:
            results.append(auth.refresh())
        except WgerError as error:
            results.append(error.code)

    first_thread = threading.Thread(target=rotate, args=(first,))
    second_thread = threading.Thread(target=rotate, args=(second,))
    try:
        first_thread.start()
        assert started.wait(timeout=3)
        second_thread.start()
        second_thread.join(timeout=3)
        release.set()
        first_thread.join(timeout=3)
    finally:
        release.set()
        http.close()

    assert not first_thread.is_alive()
    assert not second_thread.is_alive()
    assert request_count == 1
    assert results.count("UNAVAILABLE") == 1
    assert results.count({"Authorization": "Bearer access-value-2"}) == 1
    assert store.load().generation == 2


def test_persistence_failure_keeps_store_reconciliation_required(tmp_path, monkeypatch):
    """Simulates save failure; it does not inject a real fsync/replace fault."""
    store = seeded_store(tmp_path)
    posts = 0

    def handler(request):
        nonlocal posts
        posts += 1
        return httpx.Response(
            200,
            json={"access": "access-value-2", "refresh": "refresh-value-2", "expires_in": 600},
        )

    original_save = store._save_unlocked
    failed = False

    def fail_new_active(record):
        nonlocal failed
        if record.state == "ACTIVE" and record.generation == 2 and not failed:
            failed = True
            raise WgerError("UNAVAILABLE")
        original_save(record)

    monkeypatch.setattr(store, "_save_unlocked", fail_new_active)
    http = httpx.Client(transport=httpx.MockTransport(handler))
    auth = RotatingJwtWgerAuth(
        store, http, now=lambda: NOW, token_url="https://wger.example/token/"
    )
    try:
        with pytest.raises(WgerError) as captured:
            auth.refresh()
        assert captured.value.code == "UNAVAILABLE"
        assert store.load().state == "RECONCILIATION_REQUIRED"
        with pytest.raises(WgerError) as second_attempt:
            auth.refresh()
        assert second_attempt.value.code == "RECONCILIATION_REQUIRED"
    finally:
        http.close()
    assert posts == 1


def test_expired_jwt_does_not_consume_refresh_token_automatically(tmp_path):
    store = WgerTokenStore(tmp_path / "tokens.json")
    store.save(
        WgerTokenRecord(
            generation=1,
            access_token="expired-access-value",
            refresh_token="refresh-value",
            obtained_at=NOW - timedelta(minutes=5),
            expires_at=NOW,
        )
    )
    posts = []
    http = httpx.Client(transport=httpx.MockTransport(lambda request: posts.append(request)))
    auth = RotatingJwtWgerAuth(
        store, http, now=lambda: NOW, token_url="https://wger.example/token/"
    )
    try:
        with pytest.raises(WgerError) as captured:
            auth.headers()
    finally:
        http.close()
    assert captured.value.code == "AUTH_INVALID"
    assert posts == []


def test_failed_rotation_response_leaves_reconciliation_marker(tmp_path):
    store = seeded_store(tmp_path)
    http = httpx.Client(
        transport=httpx.MockTransport(lambda request: httpx.Response(503, text="body-canary"))
    )
    auth = RotatingJwtWgerAuth(
        store, http, now=lambda: NOW, token_url="https://wger.example/token/"
    )
    try:
        with pytest.raises(WgerError) as captured:
            auth.refresh()
    finally:
        http.close()
    assert captured.value.code == "SERVER_ERROR"
    assert store.load().state == "RECONCILIATION_REQUIRED"
    assert "body-canary" not in str(captured.value)


def test_auth_mode_does_not_fall_back_or_read_refresh_token():
    assert resolve_wger_auth_mode({"WGER_API_TOKEN": "api-token-value"}) == "api_token"
    assert resolve_wger_auth_mode(
        {"WGER_AUTH_MODE": "jwt_refresh", "WGER_API_TOKEN": "api-token-value"}
    ) == "jwt_refresh"
    with pytest.raises(WgerError) as missing:
        resolve_wger_auth_mode({"WGER_REFRESH_TOKEN": "refresh-value"})
    assert missing.value.code == "CONFIG_REQUIRED"
    with pytest.raises(WgerError) as blank_token:
        resolve_wger_auth_mode({"WGER_API_TOKEN": " "})
    assert blank_token.value.code == "CONFIG_REQUIRED"
    with pytest.raises(WgerError) as explicit_api_missing:
        resolve_wger_auth_mode({"WGER_AUTH_MODE": "api_token"})
    assert explicit_api_missing.value.code == "CONFIG_REQUIRED"
    assert resolve_wger_auth_mode(
        {
            "WGER_AUTH_MODE": "jwt_refresh",
            "WGER_API_TOKEN": "api-token-value",
            "WGER_REFRESH_TOKEN": "refresh-value",
        }
    ) == "jwt_refresh"
    with pytest.raises(WgerError) as empty_mode:
        resolve_wger_auth_mode({"WGER_AUTH_MODE": "", "WGER_API_TOKEN": "api-token-value"})
    assert empty_mode.value.code == "CONFIG_REQUIRED"


def test_dotenv_is_not_loaded_without_opt_in(tmp_path):
    env_path = tmp_path / ".env"
    env_path.write_text("WGER_API_TOKEN=env-file-value\n", encoding="utf-8")
    environ = {"ATLAS_ENV_FILE": str(env_path)}
    assert load_dotenv_if_enabled(environ=environ, repo_root=tmp_path) is False
    assert "WGER_API_TOKEN" not in environ


def test_dotenv_load_requires_ignored_untracked_file_and_never_overwrites(tmp_path):
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    (tmp_path / ".gitignore").write_text(".env\n", encoding="utf-8")
    env_path = tmp_path / ".env"
    env_path.write_text(
        "WGER_API_TOKEN=file-value\nATLAS_SAMPLE=loaded\n", encoding="utf-8"
    )
    os.chmod(env_path, 0o600)
    environ = {"WGER_API_TOKEN": "process-value"}
    assert load_dotenv_if_enabled(env_path, environ=environ, repo_root=tmp_path) is True
    assert environ["WGER_API_TOKEN"] == "process-value"
    assert environ["ATLAS_SAMPLE"] == "loaded"

    tracked_path = tmp_path / ".env.tracked"
    tracked_path.write_text("ATLAS_TRACKED=should-not-load\n", encoding="utf-8")
    subprocess.run(["git", "-C", str(tmp_path), "add", ".env.tracked"], check=True)
    with pytest.raises(WgerError) as captured:
        load_dotenv_if_enabled(tracked_path, environ={}, repo_root=tmp_path)
    assert captured.value.code == "CONFIG_REQUIRED"


def test_dotenv_requires_private_posix_mode_before_loading(tmp_path):
    if os.name != "posix":
        pytest.skip("POSIX mode-bit check is not available")
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    (tmp_path / ".gitignore").write_text(".env\n", encoding="utf-8")
    env_path = tmp_path / ".env"
    env_path.write_text("ATLAS_SAMPLE=loaded\n", encoding="utf-8")
    os.chmod(env_path, 0o644)

    environ = {}
    with pytest.raises(WgerError) as captured:
        load_dotenv_if_enabled(env_path, environ=environ, repo_root=tmp_path)
    assert captured.value.code == "ENV_PERMISSIONS"
    assert str(captured.value) == "environment file permissions are too open"
    assert environ == {}

    os.chmod(env_path, 0o600)
    assert load_dotenv_if_enabled(env_path, environ=environ, repo_root=tmp_path) is True
    assert environ == {"ATLAS_SAMPLE": "loaded"}


def test_dotenv_load_refuses_file_not_ignored_by_git(tmp_path):
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    env_path = tmp_path / "local.env"
    env_path.write_text("ATLAS_SAMPLE=must-not-load\n", encoding="utf-8")
    with pytest.raises(WgerError) as captured:
        load_dotenv_if_enabled(env_path, environ={}, repo_root=tmp_path)
    assert captured.value.code == "CONFIG_REQUIRED"


def test_token_store_file_and_lock_are_mode_0600_and_values_are_not_in_repr(tmp_path):
    store = seeded_store(tmp_path)
    token_file = store.path
    lock_file = token_file.with_name(token_file.name + ".lock")
    assert stat.S_IMODE(token_file.stat().st_mode) == 0o600
    assert stat.S_IMODE(lock_file.stat().st_mode) == 0o600
    assert "access-value-1" not in repr(store.load())
    assert "refresh-value-1" not in repr(store.load())
    assert "access-value-1" not in repr(store)


def test_diagnostic_jwt_mode_stays_inactive_and_output_is_redacted(monkeypatch, capsys):
    from scripts.wger_diagnostic import main

    monkeypatch.setenv("WGER_AUTH_MODE", "jwt_refresh")
    monkeypatch.setenv("WGER_API_TOKEN", "api-token-canary")
    monkeypatch.setenv("WGER_REFRESH_TOKEN", "refresh-token-canary")
    result = main(["--mode", "jwt_refresh", "--read-only"])
    output = capsys.readouterr().out
    assert result == 2
    assert "api-token-canary" not in output
    assert "refresh-token-canary" not in output
    assert '"error_category":"CONFIG_REQUIRED"' in output


def test_diagnostic_refuses_mode_override_instead_of_falling_back(monkeypatch, capsys):
    from scripts.wger_diagnostic import main

    monkeypatch.setenv("WGER_AUTH_MODE", "jwt_refresh")
    monkeypatch.setenv("WGER_API_TOKEN", "api-token-canary")
    result = main(["--mode", "api_token", "--read-only"])
    output = capsys.readouterr().out
    assert result == 2
    assert '"mode":"api_token"' in output
    assert '"error_category":"CONFIG_REQUIRED"' in output
    assert "api-token-canary" not in output


def test_auth_and_store_errors_never_reach_caplog(caplog, tmp_path):
    marker = "secret-canary-value"
    store = WgerTokenStore(tmp_path / "tokens.json")
    with pytest.raises(WgerError):
        store.save(
            WgerTokenRecord(
                generation=0,
                access_token=marker,
                refresh_token="other-test-value",
                obtained_at=NOW,
                expires_at=NOW + timedelta(minutes=1),
            )
        )
    assert marker not in caplog.text
