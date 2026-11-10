from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import stat
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest
import atlas_backend.v4_assertion as v4_assertion_module
import atlas_backend.v4_router as v4_router_module

from atlas_backend.main import create_app
from atlas_backend.store import SQLiteStore
from atlas_backend.v4_assertion import (
    FileReplayNonceCache,
    RelayAssertionError,
    ReplayNonceCache,
    canonical_message,
    load_assertion_keys,
    verify_relay_assertion,
)
from atlas_backend.v4_auth import PrincipalAuthError, V4PrincipalRegistry, legacy_device_ref
from atlas_backend.v4_projection_store import V4ProjectionStore
from atlas_backend.v4_router import _decode_cursor, _encode_cursor


CONSENT_BODY = {
    "contract_version": 4,
    "schema_version": 4,
    "request_id": "00000000-0000-4000-8000-000000000501",
    "capability": "consent.state.write",
    "sent_at": "2026-11-05T12:00:00Z",
    "category": "DAY_PLAN",
    "consent_version": 1,
    "state": "GRANTED",
    "idempotency_key": "00000000-0000-4000-8000-000000000502",
}


class Client:
    def __init__(self, app):
        self.app = app

    def post(self, path: str, **kwargs):
        async def send():
            transport = httpx.ASGITransport(app=self.app, client=("127.0.0.1", 40200))
            async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
                return await client.post(path, **kwargs)
        return asyncio.run(send())

    def get(self, path: str, **kwargs):
        async def send():
            transport = httpx.ASGITransport(app=self.app, client=("127.0.0.1", 40200))
            async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
                return await client.get(path, **kwargs)
        return asyncio.run(send())


def make_app(tmp_path, monkeypatch, *, mode: str = "principal", capability_flags: dict[str, bool] | None = None):
    tmp_path.mkdir(parents=True, exist_ok=True)
    monkeypatch.setenv("ATLAS_V4_AUTH_MODE", mode)
    monkeypatch.delenv("ATLAS_V4_RELAY_ASSERTION_KEYS_FILE", raising=False)
    monkeypatch.setenv("ATLAS_V4_CURSOR_SECRET", "synthetic-cursor-secret-for-tests-only-0001")
    monkeypatch.setenv("ATLAS_TOOL_TOKEN", "synthetic-tool-token-unique")
    monkeypatch.setenv("ATLAS_V4_CAPABILITIES_JSON", json.dumps(capability_flags if capability_flags is not None else {
        "day_plan.read": True,
        "activity_status.read": True,
        "plan_revision.propose": True,
        "usage_summary.read": True,
        "goal_progress.read": True,
        "training_plan.read": True,
        "coach.chat": True,
    }))
    app = create_app(db_path=tmp_path / "atlas.sqlite3", token="synthetic-legacy-token")
    with app.state.store.connect() as connection:
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 5
        assert connection.execute(
            "SELECT name FROM schema_migrations WHERE version=5"
        ).fetchone()[0] == "v4_proposal_expiration_audit"
    return app, V4PrincipalRegistry(app.state.store), Client(app)


def consent_headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}


def grant_consent_capability(registry: V4PrincipalRegistry, principal_id: str) -> None:
    registry.set_capability(principal_id, "consent.state.write", True)


def test_dual_mode_uses_only_explicit_hash_provisioning_and_revocation_is_monotonic(tmp_path, monkeypatch):
    app, registry, client = make_app(tmp_path, monkeypatch, mode="dual")
    principal_id, device_ref = registry.create_principal(principal_id="principal-a", device_ref="stable-device-a")
    token = "synthetic-direct-credential-token-a"
    credential_id = registry.add_credential(principal_id, token, audience="DIRECT")
    grant_consent_capability(registry, principal_id)

    accepted = client.post("/v4/sync/consent-state", headers=consent_headers(token), json=CONSENT_BODY)
    assert accepted.status_code == 200
    assert registry.resolve(token).device_ref == device_ref

    # The configured historical token is not accepted by dual mode without its explicit import.
    unknown_legacy = client.post(
        "/v4/sync/consent-state", headers=consent_headers("synthetic-legacy-token"), json=CONSENT_BODY
    )
    unknown = client.post(
        "/v4/sync/consent-state", headers=consent_headers("synthetic-unknown-token"), json=CONSENT_BODY
    )
    assert unknown_legacy.status_code == 401
    assert unknown.status_code == 401

    with app.state.store.connect() as connection:
        stored_hash = connection.execute(
            "SELECT token_hash FROM credentials_v4 WHERE credential_id=?", (credential_id,)
        ).fetchone()[0]
    assert stored_hash == hashlib.sha256(token.encode("ascii")).hexdigest()
    assert token not in stored_hash

    assert registry.revoke_credential(credential_id)
    assert not registry.revoke_credential(credential_id)
    denied = client.post("/v4/sync/consent-state", headers=consent_headers(token), json=CONSENT_BODY)
    assert denied.status_code == 401
    assert registry.resolve(token) is None


def test_principal_without_grant_is_denied_before_consent_mutation(tmp_path, monkeypatch):
    app, registry, client = make_app(tmp_path, monkeypatch)
    principal_id, _ = registry.create_principal(principal_id="principal-no-grant", device_ref="stable-no-grant")
    token = "synthetic-ungranted-credential-token"
    registry.add_credential(principal_id, token, audience="DIRECT")
    denied = client.post("/v4/sync/consent-state", headers=consent_headers(token), json=CONSENT_BODY)
    assert denied.status_code == 403
    with app.state.store.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM consent_state_events_v4").fetchone()[0] == 0


def test_identity_claims_in_payload_and_headers_never_override_bearer_owner(tmp_path, monkeypatch):
    app, registry, client = make_app(tmp_path, monkeypatch, mode="dual")
    principal_a, device_a = registry.create_principal(principal_id="forged-identity-a", device_ref="forged-device-a")
    principal_b, device_b = registry.create_principal(principal_id="forged-identity-b", device_ref="forged-device-b")
    token_a = "synthetic-forged-identity-credential-a"
    registry.add_credential(principal_a, token_a, audience="DIRECT")
    registry.add_credential(principal_b, "synthetic-forged-identity-credential-b", audience="DIRECT")
    registry.set_capability(principal_a, "consent.state.write", True)
    forged_headers = {
        **consent_headers(token_a),
        "X-Atlas-Principal-Id": principal_b,
        "X-Atlas-Device-Ref": device_b,
    }

    forged_payload = client.post(
        "/v4/sync/consent-state",
        headers=forged_headers,
        json={**CONSENT_BODY, "principal_id": principal_b, "device_ref": device_b},
    )
    assert forged_payload.status_code == 400
    with app.state.store.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM consent_state_events_v4").fetchone()[0] == 0

    accepted = client.post("/v4/sync/consent-state", headers=forged_headers, json=CONSENT_BODY)
    assert accepted.status_code == 200
    with app.state.store.connect() as connection:
        owners = connection.execute("SELECT device_ref FROM consent_states_v4").fetchall()
    assert [row["device_ref"] for row in owners] == [device_a]
    assert device_b not in {row["device_ref"] for row in owners}


def test_new_tool_principal_without_consent_is_denied(tmp_path, monkeypatch):
    _app, registry, client = make_app(tmp_path, monkeypatch, mode="dual")
    principal_id, _ = registry.create_principal(principal_id="new-tool-without-consent", kind="TOOL")
    token = "synthetic-new-tool-without-consent"
    registry.add_credential(principal_id, token, audience="TOOL")
    registry.set_capability(principal_id, "day_plan.read", True)
    monkeypatch.setenv("ATLAS_V4_TOOL_PRINCIPAL_ID", principal_id)
    monkeypatch.setenv("ATLAS_TOOL_TOKEN", token)
    response = client.get(
        "/internal/v4/day-plan?local_date=2026-11-06",
        headers={
            "Authorization": f"Bearer {token}",
            "X-Atlas-Contract-Version": "4",
            "X-Atlas-Schema-Version": "4",
            "X-Atlas-Request-Id": "00000000-0000-4000-8000-00000000058a",
            "X-Atlas-Capability": "day_plan.read",
        },
    )
    assert response.status_code == 403
    assert response.json()["error"]["code"] == "CONSENT_REQUIRED"
    assert "DAY_PLAN" in response.json()["error"]["message"]


def test_principal_mode_requires_cursor_secret_and_unknown_mode_fails_startup(tmp_path, monkeypatch):
    monkeypatch.setenv("ATLAS_V4_AUTH_MODE", "principal")
    monkeypatch.delenv("ATLAS_V4_CURSOR_SECRET", raising=False)
    with pytest.raises(ValueError, match="ATLAS_V4_CURSOR_SECRET"):
        create_app(db_path=tmp_path / "missing-cursor-secret.sqlite3", token="synthetic-legacy-token")

    monkeypatch.setenv("ATLAS_V4_AUTH_MODE", "fallback")
    monkeypatch.setenv("ATLAS_V4_CURSOR_SECRET", "synthetic-cursor-secret-for-tests-only-0001")
    with pytest.raises(ValueError, match="ATLAS_V4_AUTH_MODE"):
        create_app(db_path=tmp_path / "unknown-mode.sqlite3", token="synthetic-legacy-token")


def test_internal_tool_routes_require_explicit_tool_principal(tmp_path, monkeypatch):
    monkeypatch.delenv("ATLAS_V4_TOOL_PRINCIPAL_ID", raising=False)
    _app, _registry, client = make_app(tmp_path, monkeypatch)
    headers = {
        "Authorization": "Bearer synthetic-tool-token-unique",
        "X-Atlas-Contract-Version": "4",
        "X-Atlas-Schema-Version": "4",
        "X-Atlas-Request-Id": "00000000-0000-4000-8000-000000000571",
        "X-Atlas-Capability": "day_plan.read",
    }
    response = client.get("/internal/v4/day-plan", headers=headers)
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "CONFIG_REQUIRED"


@pytest.mark.parametrize("mode", ["dual", "principal"])
def test_tool_catalog_is_authenticated_principal_metadata_without_a_grant(tmp_path, monkeypatch, mode):
    app, registry, client = make_app(tmp_path / mode, monkeypatch, mode=mode)
    principal_id, _ = registry.create_principal(principal_id=f"catalog-{mode}", kind="TOOL")
    token = f"synthetic-catalog-tool-credential-{mode}"
    credential_id = registry.add_credential(principal_id, token, audience="TOOL")
    wrong_audience_token = f"synthetic-catalog-wrong-audience-{mode}"
    registry.add_credential(principal_id, wrong_audience_token, audience="DIRECT")
    monkeypatch.setenv("ATLAS_V4_TOOL_PRINCIPAL_ID", principal_id)
    monkeypatch.setenv("ATLAS_TOOL_TOKEN", token)
    headers = {
        "Authorization": f"Bearer {token}",
        "X-Atlas-Contract-Version": "4",
        "X-Atlas-Schema-Version": "4",
        "X-Atlas-Request-Id": "00000000-0000-4000-8000-000000000590",
        "X-Atlas-Capability": "tool_catalog.read",
    }

    catalog = client.get("/internal/v4/capabilities", headers=headers)
    assert catalog.status_code == 200, catalog.text

    wrong_version = client.get(
        "/internal/v4/capabilities",
        headers={**headers, "X-Atlas-Contract-Version": "3"},
    )
    assert wrong_version.status_code == 400
    missing_capability = client.get(
        "/internal/v4/capabilities",
        headers={key: value for key, value in headers.items() if key.lower() != "x-atlas-capability"},
    )
    assert missing_capability.status_code == 400

    monkeypatch.setenv("ATLAS_V4_TOOL_PRINCIPAL_ID", "different-configured-tool")
    mismatched_principal = client.get("/internal/v4/capabilities", headers=headers)
    assert mismatched_principal.status_code == 401
    monkeypatch.setenv("ATLAS_V4_TOOL_PRINCIPAL_ID", principal_id)

    unknown = client.get(
        "/internal/v4/capabilities",
        headers={**headers, "Authorization": "Bearer synthetic-unknown-tool-credential"},
    )
    assert unknown.status_code == 401

    monkeypatch.setenv("ATLAS_TOOL_TOKEN", wrong_audience_token)
    wrong_audience = client.get(
        "/internal/v4/capabilities",
        headers={**headers, "Authorization": f"Bearer {wrong_audience_token}"},
    )
    assert wrong_audience.status_code == 401

    monkeypatch.setenv("ATLAS_TOOL_TOKEN", token)
    assert registry.revoke_credential(credential_id)
    revoked = client.get("/internal/v4/capabilities", headers=headers)
    assert revoked.status_code == 401


def test_global_mutation_flags_intersect_principal_grants_and_legacy_defaults(tmp_path, monkeypatch):
    app, registry, client = make_app(
        tmp_path / "consent-kill-switch",
        monkeypatch,
        mode="dual",
        capability_flags={"consent.state.write": False},
    )
    principal_id, _ = registry.create_principal(principal_id="global-consent-kill-switch")
    token = "synthetic-global-consent-kill-switch-token"
    registry.add_credential(principal_id, token, audience="DIRECT")
    registry.set_capability(principal_id, "consent.state.write", True)
    denied_consent = client.post("/v4/sync/consent-state", headers=consent_headers(token), json=CONSENT_BODY)
    assert denied_consent.status_code == 403
    with app.state.store.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM consent_state_events_v4").fetchone()[0] == 0

    write_app, write_registry, write_client = make_app(
        tmp_path / "projection-kill-switch",
        monkeypatch,
        mode="dual",
        capability_flags={"day_plan.snapshot.write": False},
    )
    write_principal, _ = write_registry.create_principal(principal_id="global-projection-kill-switch")
    write_token = "synthetic-global-projection-kill-switch-token"
    write_registry.add_credential(write_principal, write_token, audience="DIRECT")
    write_registry.set_capability(write_principal, "day_plan.snapshot.write", True)
    denied_write = write_client.post(
        "/v4/sync/day-plan-snapshots",
        headers=consent_headers(write_token),
        json={"capability": "day_plan.snapshot.write"},
    )
    assert denied_write.status_code == 403
    with write_app.state.store.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM day_plan_snapshots_v4").fetchone()[0] == 0

    _legacy_app, _legacy_registry, legacy_client = make_app(
        tmp_path / "legacy-mutation-default",
        monkeypatch,
        mode="legacy",
        capability_flags={},
    )
    allowed_legacy_consent = legacy_client.post(
        "/v4/sync/consent-state",
        headers=consent_headers("synthetic-legacy-token"),
        json=CONSENT_BODY,
    )
    assert allowed_legacy_consent.status_code == 200

    _legacy_killed_app, _legacy_killed_registry, legacy_killed_client = make_app(
        tmp_path / "legacy-mutation-kill-switch",
        monkeypatch,
        mode="legacy",
        capability_flags={"consent.state.write": False},
    )
    denied_legacy_consent = legacy_killed_client.post(
        "/v4/sync/consent-state",
        headers=consent_headers("synthetic-legacy-token"),
        json=CONSENT_BODY,
    )
    assert denied_legacy_consent.status_code == 403


def test_v4_global_capability_configuration_rejects_unknown_names(tmp_path, monkeypatch):
    monkeypatch.setenv("ATLAS_V4_AUTH_MODE", "dual")
    monkeypatch.setenv("ATLAS_V4_CURSOR_SECRET", "synthetic-capability-config-cursor-secret")
    monkeypatch.setenv("ATLAS_V4_CAPABILITIES_JSON", json.dumps({"tool_catalog.read": True}))
    with pytest.raises(ValueError, match="capabilities inconnues"):
        create_app(db_path=tmp_path / "unknown-global-capability.sqlite3", token="synthetic-legacy-token")


def test_proposal_cursor_is_scoped_to_principal_credential_and_rotatable_secret():
    cursor = _encode_cursor(
        "2026-11-05T12:00:00Z", "00000000-0000-4000-8000-000000000572", "ignored",
        "stable-device-a", server_secret=b"synthetic-cursor-secret-v7-10-0001",
        principal_id="principal-a", credential_id="credential-a",
    )
    assert _decode_cursor(
        cursor, "ignored", "stable-device-a", server_secret=b"synthetic-cursor-secret-v7-10-0001",
        principal_id="principal-a", credential_id="credential-a",
    ) == ("2026-11-05T12:00:00Z", "00000000-0000-4000-8000-000000000572")
    for context in (
        ("principal-b", "credential-b", "stable-device-a", b"synthetic-cursor-secret-v7-10-0001"),
        ("principal-a", "credential-b", "stable-device-a", b"synthetic-cursor-secret-v7-10-0001"),
        ("principal-a", "credential-a", "stable-device-a", b"synthetic-cursor-secret-v7-10-0002"),
    ):
        principal_id, credential_id, device_ref, secret = context
        with pytest.raises(ValueError):
            _decode_cursor(
                cursor, "ignored", device_ref, server_secret=secret,
                principal_id=principal_id, credential_id=credential_id,
            )


def _relay_headers(token: str, secret: bytes, relay_device_id: str, *, nonce: str, timestamp: int,
                   body: bytes, path: str = "/v4/sync/consent-state", key_id: str = "test-key-1",
                   signed_method: str = "POST") -> dict[str, str]:
    signature = "v1=" + base64.urlsafe_b64encode(
        hmac.new(secret, canonical_message(signed_method, path, str(timestamp), nonce, relay_device_id, body), hashlib.sha256).digest()
    ).decode("ascii").rstrip("=")
    return {
        **consent_headers(token),
        "X-Atlas-Relay-Device-Id": relay_device_id,
        "X-Atlas-Relay-Key-Id": key_id,
        "X-Atlas-Relay-Time": str(timestamp),
        "X-Atlas-Relay-Nonce": nonce,
        "X-Atlas-Relay-Signature": signature,
    }


def test_relay_assertion_binds_credential_to_active_session_and_rejects_replay(tmp_path, monkeypatch):
    secret = b"synthetic-relay-assertion-key-only-for-tests"
    rotated_secret = b"synthetic-rotated-relay-assertion-key-for-tests"
    encoded = base64.urlsafe_b64encode(secret).decode("ascii").rstrip("=")
    rotated_encoded = base64.urlsafe_b64encode(rotated_secret).decode("ascii").rstrip("=")
    monkeypatch.setenv("ATLAS_V4_RELAY_ASSERTION_KEYS_JSON", json.dumps({
        "test-key-1": encoded, "test-key-2": rotated_encoded,
    }))
    app, registry, client = make_app(tmp_path, monkeypatch)

    principal_a, device_a = registry.create_principal(principal_id="principal-a", device_ref="stable-a")
    principal_b, _device_b = registry.create_principal(principal_id="principal-b", device_ref="stable-b")
    token_a = "synthetic-relay-credential-token-a"
    token_b = "synthetic-relay-credential-token-b"
    registry.add_credential(principal_a, token_a, audience="RELAY")
    registry.add_credential(principal_b, token_b, audience="RELAY")
    grant_consent_capability(registry, principal_a)
    grant_consent_capability(registry, principal_b)
    registry.add_binding("relay-session-a", principal_a)
    registry.add_binding("relay-session-b", principal_b)
    body = json.dumps(CONSENT_BODY, separators=(",", ":")).encode("utf-8")
    body_b = json.dumps({
        **CONSENT_BODY,
        "request_id": "00000000-0000-4000-8000-0000000005a1",
        "idempotency_key": "00000000-0000-4000-8000-0000000005a2",
    }, separators=(",", ":")).encode("utf-8")
    now = int(time.time() * 1000)
    headers_a = _relay_headers(token_a, secret, "relay-session-a", nonce="nonce-test-a", timestamp=now, body=body)

    accepted = client.post("/v4/sync/consent-state", content=body, headers=headers_a)
    replayed = client.post("/v4/sync/consent-state", content=body, headers=headers_a)
    matched_b = client.post(
        "/v4/sync/consent-state",
        content=body_b,
        headers=_relay_headers(token_b, secret, "relay-session-b", nonce="nonce-test-b", timestamp=now, body=body_b),
    )
    crossed = client.post(
        "/v4/sync/consent-state",
        content=body,
        headers=_relay_headers(token_b, secret, "relay-session-a", nonce="nonce-test-b", timestamp=now, body=body),
    )
    no_assertion = client.post("/v4/sync/consent-state", content=body, headers=consent_headers(token_a))
    expired = client.post(
        "/v4/sync/consent-state",
        content=body,
        headers=_relay_headers(token_a, secret, "relay-session-a", nonce="nonce-test-old", timestamp=now - 121_000, body=body),
    )
    unknown_relay_identity = client.post(
        "/v4/sync/consent-state",
        content=body,
        headers=_relay_headers(token_a, secret, "relay-session-unknown", nonce="nonce-test-unknown-id", timestamp=now, body=body),
    )
    altered_body = client.post(
        "/v4/sync/consent-state",
        content=body + b" ",
        headers=_relay_headers(token_a, secret, "relay-session-a", nonce="nonce-test-body", timestamp=now, body=body),
    )
    altered_timestamp_headers = _relay_headers(
        token_a, secret, "relay-session-a", nonce="nonce-test-time", timestamp=now, body=body,
    )
    altered_timestamp_headers["X-Atlas-Relay-Time"] = str(now + 1)
    altered_timestamp = client.post("/v4/sync/consent-state", content=body, headers=altered_timestamp_headers)
    altered_nonce_headers = _relay_headers(
        token_a, secret, "relay-session-a", nonce="nonce-test-original", timestamp=now, body=body,
    )
    altered_nonce_headers["X-Atlas-Relay-Nonce"] = "nonce-test-altered"
    altered_nonce = client.post("/v4/sync/consent-state", content=body, headers=altered_nonce_headers)
    altered_path = client.post(
        "/v4/sync/day-plan-snapshots",
        content=body,
        headers=_relay_headers(
            token_a, secret, "relay-session-a", nonce="nonce-test-path", timestamp=now, body=body,
        ),
    )
    with pytest.raises(RelayAssertionError):
        verify_relay_assertion(
            _relay_headers(
                token_a, secret, "relay-session-a", nonce="nonce-test-method", timestamp=now,
                body=body, signed_method="POST",
            ),
            method="PUT",
            path="/v4/sync/consent-state",
            body=body,
            keys={"test-key-1": secret, "test-key-2": rotated_secret},
            replay_cache=ReplayNonceCache(),
            now_ms=now,
        )
    rotated = client.post(
        "/v4/sync/consent-state",
        content=body,
        headers=_relay_headers(
            token_a, rotated_secret, "relay-session-a", nonce="nonce-test-rotated-key", timestamp=now,
            body=body, key_id="test-key-2",
        ),
    )
    unknown_key = client.post(
        "/v4/sync/consent-state",
        content=body,
        headers=_relay_headers(
            token_a, rotated_secret, "relay-session-a", nonce="nonce-test-unknown-key", timestamp=now,
            body=body, key_id="test-key-unknown",
        ),
    )

    assert accepted.status_code == 200
    assert replayed.status_code == 401
    assert matched_b.status_code == 200
    assert crossed.status_code == 401
    assert no_assertion.status_code == 401
    assert expired.status_code == 401
    assert unknown_relay_identity.status_code == 401
    assert altered_body.status_code == 401
    assert altered_timestamp.status_code == 401
    assert altered_nonce.status_code == 401
    assert altered_path.status_code == 401
    assert rotated.status_code == 200
    assert unknown_key.status_code == 401
    resolved = registry.resolve(token_a)
    assert resolved is not None and resolved.device_ref == device_a
    assert registry.revoke_binding("relay-session-a")
    revoked_identity = client.post(
        "/v4/sync/consent-state",
        content=body,
        headers=_relay_headers(
            token_a, secret, "relay-session-a", nonce="nonce-test-revoked-id", timestamp=now, body=body,
        ),
    )
    assert revoked_identity.status_code == 401


def test_relay_assertion_pre_restart_is_rejected_but_startup_skew_is_tolerated(tmp_path, monkeypatch):
    secret = b"synthetic-restart-assertion-key-only-for-tests"
    encoded = base64.urlsafe_b64encode(secret).decode("ascii").rstrip("=")
    monkeypatch.setenv("ATLAS_V4_RELAY_ASSERTION_KEYS_JSON", json.dumps({"test-key-1": encoded}))

    now_ms = [1_800_000_000_000]
    controlled_clock = SimpleNamespace(time=lambda: now_ms[0] / 1000)
    monkeypatch.setattr(v4_router_module, "time", controlled_clock)
    monkeypatch.setattr(v4_assertion_module, "time", controlled_clock)

    _first_app, registry, first_client = make_app(tmp_path, monkeypatch)
    principal_id, _device_ref = registry.create_principal(
        principal_id="restart-principal", device_ref="restart-device"
    )
    token = "synthetic-restart-relay-credential-token"
    registry.add_credential(principal_id, token, audience="RELAY")
    grant_consent_capability(registry, principal_id)
    registry.add_binding("restart-relay-session", principal_id)

    def signed_body() -> bytes:
        return json.dumps(
            {
                **CONSENT_BODY,
                "request_id": str(uuid.uuid4()),
                "idempotency_key": str(uuid.uuid4()),
            },
            separators=(",", ":"),
        ).encode("utf-8")

    def relay_post(client, *, timestamp: int, nonce: str, body: bytes):
        return client.post(
            "/v4/sync/consent-state",
            content=body,
            headers=_relay_headers(
                token,
                secret,
                "restart-relay-session",
                nonce=nonce,
                timestamp=timestamp,
                body=body,
            ),
        )

    # The assertion is created after the first app starts and is accepted once.
    assertion_timestamp_ms = now_ms[0] + 10_000
    now_ms[0] = assertion_timestamp_ms
    assertion_body = signed_body()
    accepted_before_restart = relay_post(
        first_client,
        timestamp=assertion_timestamp_ms,
        nonce="restart-replay-nonce",
        body=assertion_body,
    )
    assert accepted_before_restart.status_code == 200

    # Recreating the app simulates a Core restart: the SQLite identity remains,
    # while the router gets a new in-memory replay cache and startup timestamp.
    restart_started_at_ms = 1_800_000_020_000
    now_ms[0] = restart_started_at_ms
    _restarted_app, _restarted_registry, restarted_client = make_app(tmp_path, monkeypatch)
    replay_after_restart = relay_post(
        restarted_client,
        timestamp=assertion_timestamp_ms,
        nonce="restart-replay-nonce",
        body=assertion_body,
    )
    assert replay_after_restart.status_code == 401

    # Fresh assertions at/after startup pass, and a connector clock 3 s behind
    # is within the explicit 5 s startup tolerance.
    after_start_timestamp_ms = restart_started_at_ms + 1_000
    now_ms[0] = after_start_timestamp_ms
    accepted_after_start = relay_post(
        restarted_client,
        timestamp=after_start_timestamp_ms,
        nonce="restart-new-assertion-nonce",
        body=signed_body(),
    )
    assert accepted_after_start.status_code == 200

    tolerated_started_at_ms = restart_started_at_ms + 2_000
    now_ms[0] = tolerated_started_at_ms
    _tolerated_app, _tolerated_registry, tolerated_client = make_app(tmp_path, monkeypatch)
    tolerated_timestamp_ms = tolerated_started_at_ms - 3_000
    accepted_with_clock_skew = relay_post(
        tolerated_client,
        timestamp=tolerated_timestamp_ms,
        nonce="restart-tolerated-skew-nonce",
        body=signed_body(),
    )
    assert accepted_with_clock_skew.status_code == 200


def test_file_replay_nonce_cache_is_shared_private_and_expiring(tmp_path):
    cache_dir = tmp_path / "relay-replay-cache"
    first = FileReplayNonceCache(cache_dir)
    second = FileReplayNonceCache(cache_dir)
    key_id = "synthetic-key-id"
    nonce = "synthetic-nonce-that-must-not-be-stored-in-clear"
    token = "synthetic-bearer-token-that-must-not-be-stored"

    assert stat.S_IMODE(cache_dir.stat().st_mode) == 0o700
    assert first.reserve(key_id, nonce, now=100)
    assert not first.reserve(key_id, nonce, now=100)
    assert not second.reserve(key_id, nonce, now=100)
    assert second.reserve(key_id, "different-synthetic-nonce", now=101)

    with ThreadPoolExecutor(max_workers=4) as workers:
        concurrent = list(
            workers.map(
                lambda index: (first if index % 2 else second).reserve(
                    key_id, "one-concurrent-reservation", now=102
                ),
                range(8),
            )
        )
    assert concurrent.count(True) == 1

    nonce_file = cache_dir / hashlib.sha256(f"{key_id}\0{nonce}".encode()).hexdigest()
    assert stat.S_IMODE(nonce_file.stat().st_mode) == 0o600
    assert nonce_file.read_bytes() == b"400000"
    stored = b"".join(path.name.encode() + path.read_bytes() for path in cache_dir.iterdir())
    assert nonce.encode() not in stored
    assert token.encode() not in stored

    # The first reservation expires at 400 s and can then be atomically renewed.
    assert first.reserve(key_id, nonce, now=400)


def test_file_replay_nonce_cache_rejects_symlinks_and_repository_paths(tmp_path):
    target = tmp_path / "real-replay-cache"
    target.mkdir(mode=0o700)
    alias = tmp_path / "replay-cache-alias"
    alias.symlink_to(target, target_is_directory=True)
    with pytest.raises(OSError):
        FileReplayNonceCache(alias)
    with pytest.raises(ValueError, match="absolute path"):
        FileReplayNonceCache("relative-replay-cache")

    public = tmp_path / "public-replay-cache"
    public.mkdir(mode=0o700)
    public.chmod(0o755)
    with pytest.raises(ValueError, match="mode 0700"):
        FileReplayNonceCache(public)

    repository_cache = Path(__file__).resolve().parents[2] / "backend" / "replay-cache-test-only"
    with pytest.raises(ValueError, match="outside the repository"):
        FileReplayNonceCache(repository_cache)


def test_file_replay_nonce_cache_fails_closed_when_full_or_storage_errors(tmp_path, monkeypatch):
    full_cache = FileReplayNonceCache(tmp_path / "bounded-cache", capacity=1)
    assert full_cache.reserve("kid", "first", now=100)
    assert not full_cache.reserve("kid", "second", now=100)

    unreadable_cache = FileReplayNonceCache(tmp_path / "unreadable-cache")
    with monkeypatch.context() as patch:
        def unreadable(_directory_fd):
            raise PermissionError("synthetic unreadable cache directory")

        patch.setattr(v4_assertion_module.os, "scandir", unreadable)
        assert not unreadable_cache.reserve("kid", "unreadable", now=100)

    write_error_cache = FileReplayNonceCache(tmp_path / "write-error-cache")
    assert write_error_cache.reserve("kid", "warm", now=100)
    with monkeypatch.context() as patch:
        def disk_full(_descriptor, _payload):
            raise OSError(28, "synthetic full cache volume")

        patch.setattr(v4_assertion_module.os, "write", disk_full)
        assert not write_error_cache.reserve("kid", "disk-full", now=101)
    # A partial/empty marker is retained and makes later attempts fail closed.
    assert not write_error_cache.reserve("kid", "disk-full", now=101)


def test_router_file_replay_cache_rejects_replay_after_clock_rollback(tmp_path, monkeypatch):
    secret = b"synthetic-durable-cache-assertion-key-only-for-tests"
    encoded = base64.urlsafe_b64encode(secret).decode("ascii").rstrip("=")
    monkeypatch.setenv("ATLAS_V4_RELAY_ASSERTION_KEYS_JSON", json.dumps({"test-key-1": encoded}))
    monkeypatch.setenv("ATLAS_V4_REPLAY_CACHE_DIR", str(tmp_path / "persistent-replay-cache"))

    now_ms = [1_800_000_000_000]
    controlled_clock = SimpleNamespace(time=lambda: now_ms[0] / 1000)
    monkeypatch.setattr(v4_router_module, "time", controlled_clock)
    monkeypatch.setattr(v4_assertion_module, "time", controlled_clock)

    _first_app, registry, first_client = make_app(tmp_path, monkeypatch)
    principal_id, _device_ref = registry.create_principal(
        principal_id="durable-cache-principal", device_ref="durable-cache-device"
    )
    token = "synthetic-durable-cache-relay-credential-token"
    registry.add_credential(principal_id, token, audience="RELAY")
    grant_consent_capability(registry, principal_id)
    registry.add_binding("durable-cache-relay-session", principal_id)
    body = json.dumps(
        {**CONSENT_BODY, "request_id": str(uuid.uuid4()), "idempotency_key": str(uuid.uuid4())},
        separators=(",", ":"),
    ).encode("utf-8")
    timestamp_ms = now_ms[0]
    headers = _relay_headers(
        token,
        secret,
        "durable-cache-relay-session",
        nonce="durable-cache-clock-rollback-nonce",
        timestamp=timestamp_ms,
        body=body,
    )
    accepted = first_client.post("/v4/sync/consent-state", content=body, headers=headers)
    assert accepted.status_code == 200

    # A 30 s backward Core clock step makes the old timestamp appear 30 s in
    # the future; startup and freshness checks pass, so the durable cache must deny it.
    now_ms[0] = timestamp_ms - 30_000
    _restarted_app, _restarted_registry, restarted_client = make_app(tmp_path, monkeypatch)
    replayed = restarted_client.post("/v4/sync/consent-state", content=body, headers=headers)
    assert replayed.status_code == 401


def test_relay_assertion_key_file_requires_private_regular_file(tmp_path):
    encoded = base64.urlsafe_b64encode(b"synthetic-file-assertion-key-test-only").decode("ascii").rstrip("=")
    key_file = tmp_path / "relay-keys.json"
    key_file.write_text(json.dumps({"file-key": encoded}), encoding="utf-8")
    key_file.chmod(0o600)

    assert load_assertion_keys(raw_json=None, file_path=str(key_file)) == {
        "file-key": b"synthetic-file-assertion-key-test-only"
    }

    key_file.chmod(0o640)
    with pytest.raises(ValueError, match="owner-only mode 0600"):
        load_assertion_keys(raw_json=None, file_path=str(key_file))
    alias = tmp_path / "relay-keys-alias.json"
    alias.symlink_to(key_file)
    with pytest.raises(ValueError, match="regular non-symlink"):
        load_assertion_keys(raw_json=None, file_path=str(alias))
    with pytest.raises(ValueError, match="only in tests"):
        load_assertion_keys(raw_json=json.dumps({"file-key": encoded}), file_path=None)


def test_relay_assertion_keys_reject_duplicate_secrets_and_duplicate_json_keys():
    encoded = base64.urlsafe_b64encode(b"synthetic-duplicate-assertion-key-test").decode("ascii").rstrip("=")
    with pytest.raises(ValueError, match="secret distinct"):
        load_assertion_keys(raw_json=json.dumps({"key-a": encoded, "key-b": encoded}),
                             file_path=None, testing=True)
    duplicate_json = json.dumps({"key-a": encoded}).replace(
        f'"{encoded}"', f'"{encoded}", "key-a": "{encoded}"'
    )
    with pytest.raises(ValueError, match="invalide"):
        load_assertion_keys(raw_json=duplicate_json, file_path=None, testing=True)


def test_proposal_pull_and_ack_are_scoped_to_authenticated_device(tmp_path, monkeypatch):
    app, registry, client = make_app(tmp_path, monkeypatch)
    projections = V4ProjectionStore(app.state.store)
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    principals: dict[str, tuple[str, str]] = {}
    for letter in ("a", "b"):
        principal_id, device_ref = registry.create_principal(
            principal_id=f"proposal-{letter}", device_ref=f"stable-proposal-{letter}"
        )
        token = f"synthetic-proposal-credential-token-{letter}"
        registry.add_credential(principal_id, token, audience="DIRECT")
        for capability in (
            "consent.state.write", "day_plan.snapshot.write", "plan_revision.propose", "proposal.ack",
        ):
            registry.set_capability(principal_id, capability, True)
        projections.save_consent_state(
            device_ref, "DAY_PLAN", 3, "GRANTED",
            idempotency_key=f"00000000-0000-4000-8000-00000000057{letter}",
        )
        snapshot = {
            "plan_id": f"00000000-0000-4000-8000-00000000051{letter}",
            "local_date": "2026-11-05",
            "timezone": "Europe/Paris",
            "version": 7,
            "current_revision_id": f"00000000-0000-4000-8000-00000000052{letter}",
            "state": "APPROVED",
            "origin": "LOCAL",
            "observed_at": now,
            "activities": [{
                "activity_id": f"00000000-0000-4000-8000-00000000053{letter}",
                "kind": "FOCUS",
                "title": "Bloc de lecture",
                "status": "PLANNED",
                "scheduled_local": {
                    "local_date": "2026-11-05", "start_minute": 600,
                    "duration_minutes": 45, "timezone": "Europe/Paris",
                },
                "priority": "OPTIONAL",
                "goal_id": None,
                "continuity_id": f"00000000-0000-4000-8000-00000000054{letter}",
                "supersedes_activity_id": None,
                "status_provenance": "UNKNOWN",
                "status_decided_at": None,
            }],
        }
        projections.save_day_plan_snapshot(device_ref, snapshot, consent_version=3, received_at=now)
        principals[letter] = principal_id, device_ref

    args_by_letter = {}
    for letter in ("a", "b"):
        _principal_id, device_ref = principals[letter]
        args_by_letter[letter] = {
            "plan_id": f"00000000-0000-4000-8000-00000000051{letter}",
            "base_version": 7,
            "changes": [{
                "op": "reschedule",
                "activity_id": f"00000000-0000-4000-8000-00000000053{letter}",
                "target_start_minute": 660,
            }],
            "justification": f"synthetic reason {letter}",
            "idempotency_key": f"00000000-0000-4000-8000-00000000055{letter}",
        }
        app.state.v4_tool_service.atlas_propose_plan_revision(device_ref, args_by_letter[letter])

    with app.state.store.connect() as connection:
        ids = {
            letter: connection.execute(
                "SELECT proposal_id FROM agent_proposals_v4 WHERE idempotency_key=?",
                (args_by_letter[letter]["idempotency_key"],),
            ).fetchone()[0]
            for letter in ("a", "b")
        }
        assert connection.execute(
            "SELECT device_ref FROM agent_proposals_v4 WHERE proposal_id=?", (ids["a"],)
        ).fetchone()[0] == principals["a"][1]

    pull_body = {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": "00000000-0000-4000-8000-000000000561",
        "capability": "proposal.ack",
        "consent_version": 3,
        "page_size": 32,
        "cursor": None,
    }
    pulls = {}
    for letter in ("a", "b"):
        token = f"synthetic-proposal-credential-token-{letter}"
        pulls[letter] = client.post(
            "/v4/agent/proposals/pull",
            headers=consent_headers(token),
            json=pull_body,
        )
        assert pulls[letter].status_code == 200
        assert [item["proposal_id"] for item in pulls[letter].json()["proposals"]] == [ids[letter]]

    ack = {
        "contract_version": 4,
        "schema_version": 4,
        "ack_id": "00000000-0000-4000-8000-000000000562",
        "capability": "proposal.ack",
        "proposal_id": ids["b"],
        "sequence": 1,
        "state": "REJECTED",
        "result_code": "USER_REJECTED",
        "occurred_at": now,
        "consent_version": 3,
    }
    crossed_ack = client.post(
        f"/v4/agent/proposals/{ids['b']}/ack",
        headers=consent_headers("synthetic-proposal-credential-token-a"),
        json=ack,
    )
    assert crossed_ack.status_code == 404

    expired_at = (datetime.now(timezone.utc) - timedelta(seconds=1)).replace(
        microsecond=0
    ).isoformat().replace("+00:00", "Z")
    with app.state.store.connect() as connection:
        connection.execute(
            "UPDATE agent_proposals_v4 SET expires_at=? WHERE proposal_id=?", (expired_at, ids["b"])
        )
    after_expiration = client.post(
        "/v4/agent/proposals/pull",
        headers=consent_headers("synthetic-proposal-credential-token-a"),
        json={**pull_body, "request_id": "00000000-0000-4000-8000-000000000563"},
    )
    assert after_expiration.status_code == 200
    assert [item["proposal_id"] for item in after_expiration.json()["proposals"]] == [ids["a"]]
    crossed_expired_ack = client.post(
        f"/v4/agent/proposals/{ids['b']}/ack",
        headers=consent_headers("synthetic-proposal-credential-token-a"),
        json=ack,
    )
    assert crossed_expired_ack.status_code == 404
    with app.state.store.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM agent_proposal_events_v4").fetchone()[0] == 0
        owner_row = connection.execute(
            "SELECT device_ref,state FROM agent_proposals_v4 WHERE proposal_id=?", (ids["b"],)
        ).fetchone()
        assert owner_row["device_ref"] == principals["b"][1]
        assert owner_row["state"] == "EXPIRED"
        expiration_audit = connection.execute(
            "SELECT source FROM agent_proposal_expiration_events_v4 WHERE proposal_id=?", (ids["b"],)
        ).fetchone()
        assert expiration_audit["source"] == "PULL"


def test_historical_null_proposals_are_visible_only_to_legacy_in_dual_mode(tmp_path, monkeypatch):
    app, registry, client = make_app(tmp_path, monkeypatch, mode="dual")
    legacy_token = "synthetic-historical-legacy-token"
    principal_id, device_ref, _credential_id = registry.import_legacy(
        legacy_token, grant_compatibility_defaults=True
    )
    second_legacy_token = "synthetic-second-historical-legacy-token"
    second_principal_id, second_device_ref, _ = registry.import_legacy(
        second_legacy_token, grant_compatibility_defaults=True
    )
    reimported_principal, reimported_device, _reimported_credential = registry.import_legacy(
        legacy_token, grant_compatibility_defaults=True
    )
    assert (reimported_principal, reimported_device) == (principal_id, device_ref)
    assert principal_id != "legacy_" + hashlib.sha256(legacy_token.encode("ascii")).hexdigest()[:24]
    projections = V4ProjectionStore(app.state.store)
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    consent = client.post(
        "/v4/sync/consent-state", headers=consent_headers(legacy_token), json=CONSENT_BODY
    )
    assert consent.status_code == 200
    second_consent = projections.save_consent_state(
        second_device_ref, "DAY_PLAN", 1, "GRANTED",
        idempotency_key="00000000-0000-4000-8000-000000000589",
    )
    assert second_consent["state"] == "GRANTED"
    plan_id = "00000000-0000-4000-8000-000000000582"
    activity_id = "00000000-0000-4000-8000-000000000583"
    projections.save_day_plan_snapshot(
        device_ref,
        {
            "plan_id": plan_id,
            "local_date": datetime.now(timezone.utc).date().isoformat(),
            "timezone": "Europe/Paris",
            "version": 7,
            "current_revision_id": "00000000-0000-4000-8000-000000000584",
            "state": "APPROVED",
            "origin": "LOCAL",
            "observed_at": now,
            "activities": [{
                "activity_id": activity_id,
                "kind": "FOCUS",
                "title": "Synthetic focus",
                "status": "PLANNED",
                "scheduled_local": {
                    "local_date": datetime.now(timezone.utc).date().isoformat(), "start_minute": 600,
                    "duration_minutes": 45, "timezone": "Europe/Paris",
                },
                "priority": "OPTIONAL", "goal_id": None,
                "continuity_id": "00000000-0000-4000-8000-000000000585",
                "supersedes_activity_id": None,
                "status_provenance": "UNKNOWN", "status_decided_at": None,
            }],
        },
        consent_version=1,
        received_at=now,
    )
    projections.save_day_plan_snapshot(
        second_device_ref,
        {
            "plan_id": plan_id,
            "local_date": datetime.now(timezone.utc).date().isoformat(),
            "timezone": "Europe/Paris",
            "version": 7,
            "current_revision_id": "00000000-0000-4000-8000-000000000584",
            "state": "APPROVED",
            "origin": "LOCAL",
            "observed_at": now,
            "activities": [{
                "activity_id": activity_id,
                "kind": "FOCUS",
                "title": "Synthetic focus",
                "status": "PLANNED",
                "scheduled_local": {
                    "local_date": datetime.now(timezone.utc).date().isoformat(), "start_minute": 600,
                    "duration_minutes": 45, "timezone": "Europe/Paris",
                },
                "priority": "OPTIONAL", "goal_id": None,
                "continuity_id": "00000000-0000-4000-8000-000000000585",
                "supersedes_activity_id": None,
                "status_provenance": "UNKNOWN", "status_decided_at": None,
            }],
        },
        consent_version=1,
        received_at=now,
    )
    args = {
        "plan_id": plan_id,
        "base_version": 7,
        "changes": [{"op": "reschedule", "activity_id": activity_id, "target_start_minute": 660}],
        "justification": "synthetic historic test proposal",
        "idempotency_key": "00000000-0000-4000-8000-000000000586",
    }
    result = app.state.v4_tool_service.atlas_propose_plan_revision(device_ref, args)
    proposal_id = result["data"]["proposal_id"]
    with app.state.store.connect() as connection:
        connection.execute("DROP TRIGGER agent_proposals_v4_owner_immutable")
        connection.execute("UPDATE agent_proposals_v4 SET device_ref=NULL WHERE proposal_id=?", (proposal_id,))
    monkeypatch.setenv("ATLAS_V4_LEGACY_PRINCIPAL_ID", principal_id)

    pull_body = {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": "00000000-0000-4000-8000-000000000587",
        "capability": "proposal.ack",
        "consent_version": 1,
        "page_size": 32,
        "cursor": None,
    }
    dual_pull = client.post(
        "/v4/agent/proposals/pull", headers=consent_headers(legacy_token), json=pull_body
    )
    assert dual_pull.status_code == 200
    assert [item["proposal_id"] for item in dual_pull.json()["proposals"]] == [proposal_id]

    second_pull = client.post(
        "/v4/agent/proposals/pull",
        headers=consent_headers(second_legacy_token),
        json={**pull_body, "request_id": "00000000-0000-4000-8000-000000000592"},
    )
    assert second_pull.status_code == 200
    assert second_pull.json()["proposals"] == []

    ack = {
        "contract_version": 4,
        "schema_version": 4,
        "ack_id": "00000000-0000-4000-8000-000000000588",
        "capability": "proposal.ack",
        "proposal_id": proposal_id,
        "sequence": 1,
        "state": "REJECTED",
        "result_code": "USER_REJECTED",
        "occurred_at": now,
        "consent_version": 1,
    }
    dual_ack = client.post(
        f"/v4/agent/proposals/{proposal_id}/ack", headers=consent_headers(legacy_token), json=ack
    )
    assert dual_ack.status_code == 200
    second_ack = client.post(
        f"/v4/agent/proposals/{proposal_id}/ack",
        headers=consent_headers(second_legacy_token),
        json={**ack, "ack_id": "00000000-0000-4000-8000-000000000593"},
    )
    assert second_ack.status_code == 404
    with app.state.store.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM agent_proposal_events_v4").fetchone()[0] == 1

    internal_headers = {
        "X-Atlas-Contract-Version": "4",
        "X-Atlas-Schema-Version": "4",
        "X-Atlas-Request-Id": "00000000-0000-4000-8000-000000000594",
        "X-Atlas-Capability": "plan_revision.propose",
    }
    lookup_path = "/internal/v4/agent/proposals/by-idempotency/00000000-0000-4000-8000-000000000586"
    owner_lookup = client.get(
        lookup_path,
        headers={**internal_headers, "Authorization": f"Bearer {legacy_token}"},
    )
    other_lookup = client.get(
        lookup_path,
        headers={**internal_headers, "Authorization": f"Bearer {second_legacy_token}"},
    )
    assert owner_lookup.status_code == 200
    assert owner_lookup.json()["proposal_id"] == proposal_id
    assert other_lookup.status_code == 404

    # Simulate the explicit backfill target: once ownership is assigned, only that
    # device_ref sees the row, independently of the legacy-null compatibility gate.
    second_args = {**args, "idempotency_key": "00000000-0000-4000-8000-000000000595"}
    second_result = app.state.v4_tool_service.atlas_propose_plan_revision(device_ref, second_args)
    backfilled_id = second_result["data"]["proposal_id"]
    with app.state.store.connect() as connection:
        connection.execute("UPDATE agent_proposals_v4 SET device_ref=NULL WHERE proposal_id=?", (backfilled_id,))
        connection.execute(
            "UPDATE agent_proposals_v4 SET device_ref=? WHERE proposal_id=?", (second_device_ref, backfilled_id)
        )
    backfilled_owner_pull = client.post(
        "/v4/agent/proposals/pull",
        headers=consent_headers(second_legacy_token),
        json={**pull_body, "request_id": "00000000-0000-4000-8000-000000000596"},
    )
    backfilled_other_pull = client.post(
        "/v4/agent/proposals/pull",
        headers=consent_headers(legacy_token),
        json={**pull_body, "request_id": "00000000-0000-4000-8000-000000000597"},
    )
    assert [item["proposal_id"] for item in backfilled_owner_pull.json()["proposals"]] == [backfilled_id]
    assert backfilled_other_pull.json()["proposals"] == []

    monkeypatch.delenv("ATLAS_V4_LEGACY_PRINCIPAL_ID", raising=False)
    for token in (legacy_token, second_legacy_token):
        refused = client.post(
            "/v4/agent/proposals/pull",
            headers=consent_headers(token),
            json={**pull_body, "request_id": "00000000-0000-4000-8000-000000000598"},
        )
        assert refused.status_code == 403
    active_android_id, _ = registry.create_principal(principal_id="not-a-legacy-principal", kind="ANDROID")
    inactive_legacy_id, _ = registry.create_principal(principal_id="inactive-legacy-principal", kind="LEGACY")
    registry.revoke_principal(inactive_legacy_id)
    for invalid_id in ("unknown-legacy-principal", active_android_id, inactive_legacy_id):
        monkeypatch.setenv("ATLAS_V4_LEGACY_PRINCIPAL_ID", invalid_id)
        refused = client.post(
            "/v4/agent/proposals/pull",
            headers=consent_headers(legacy_token),
            json={**pull_body, "request_id": "00000000-0000-4000-8000-000000000599"},
        )
        assert refused.status_code == 503

    monkeypatch.setenv("ATLAS_V4_AUTH_MODE", "principal")
    _principal_app, _principal_registry, principal_client = make_app(tmp_path, monkeypatch, mode="principal")
    principal_pull = principal_client.post(
        "/v4/agent/proposals/pull", headers=consent_headers(legacy_token), json=pull_body
    )
    assert principal_pull.status_code == 401


def test_registry_rejects_weak_or_non_ascii_bearers(tmp_path, monkeypatch):
    _app, registry, _client = make_app(tmp_path, monkeypatch)
    principal_id, _device_ref = registry.create_principal(principal_id="weak-token-target")
    for token in ("short-token", "non-ascii-credential-é" * 2):
        with pytest.raises(ValueError, match="bearer invalide"):
            registry.add_credential(principal_id, token, audience="DIRECT")


def test_revoked_legacy_credential_stays_revoked_after_store_restart_and_reimport(tmp_path):
    database = tmp_path / "revocation-restart.sqlite3"
    token = "synthetic-revoked-legacy-credential-after-restart"
    first_registry = V4PrincipalRegistry(SQLiteStore(database))
    principal_id, _device_ref, credential_id = first_registry.import_legacy(
        token, grant_compatibility_defaults=True,
    )
    assert first_registry.revoke_credential(credential_id)

    restarted_registry = V4PrincipalRegistry(SQLiteStore(database))
    assert restarted_registry.resolve(token) is None
    with pytest.raises(PrincipalAuthError, match="révoqué"):
        restarted_registry.import_legacy(token, grant_compatibility_defaults=True)
    with restarted_registry.store.connect() as connection:
        row = connection.execute(
            "SELECT state FROM credentials_v4 WHERE credential_id=?", (credential_id,)
        ).fetchone()
        assert row["state"] == "REVOKED"
        assert connection.execute(
            "SELECT state FROM principals_v4 WHERE principal_id=?", (principal_id,)
        ).fetchone()["state"] == "ACTIVE"


def test_legacy_mode_rejects_explicitly_revoked_imported_bearer(tmp_path, monkeypatch):
    token = "x" * 48
    monkeypatch.setenv("ATLAS_V4_AUTH_MODE", "legacy")
    monkeypatch.setenv("ATLAS_V4_CURSOR_SECRET", "synthetic-cursor-secret-for-tests-only-0001")
    monkeypatch.setenv("ATLAS_TOOL_TOKEN", "synthetic-tool-token-unique")
    monkeypatch.setenv("ATLAS_V4_CAPABILITIES_JSON", "{}")
    app = create_app(db_path=tmp_path / "legacy-revoked-bearer" / "atlas.sqlite3", token=token)
    registry = V4PrincipalRegistry(app.state.store)
    client = Client(app)
    assert registry.credential_state(token) is None
    historical = client.post(
        "/v4/sync/consent-state",
        headers=consent_headers(token),
        json=CONSENT_BODY,
    )
    assert historical.status_code == 200

    _principal_id, _device_ref, credential_id = registry.import_legacy(token)
    assert registry.credential_state(token) == "ACTIVE"
    active = client.post(
        "/v4/sync/consent-state",
        headers=consent_headers(token),
        json=CONSENT_BODY,
    )
    assert active.status_code == 200
    assert registry.revoke_credential(credential_id)
    assert registry.credential_state(token) == "REVOKED"

    response = client.post(
        "/v4/sync/consent-state",
        headers=consent_headers(token),
        json=CONSENT_BODY,
    )

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "AUTH_REQUIRED"


def test_legacy_mode_accepts_historical_bearer_when_registry_table_is_absent(tmp_path, monkeypatch):
    token = "y" * 48
    monkeypatch.setenv("ATLAS_V4_AUTH_MODE", "legacy")
    monkeypatch.setenv("ATLAS_V4_CURSOR_SECRET", "synthetic-cursor-secret-for-tests-only-0001")
    monkeypatch.setenv("ATLAS_TOOL_TOKEN", "synthetic-tool-token-unique")
    monkeypatch.setenv("ATLAS_V4_CAPABILITIES_JSON", "{}")
    app = create_app(db_path=tmp_path / "legacy-without-registry" / "atlas.sqlite3", token=token)
    client = Client(app)
    with app.state.store.connect() as connection:
        connection.execute("DROP TABLE credentials_v4")

    response = client.post(
        "/v4/sync/consent-state",
        headers=consent_headers(token),
        json=CONSENT_BODY,
    )

    assert response.status_code == 200


def test_legacy_mode_does_not_treat_revoked_nonlegacy_credential_as_tombstone(tmp_path, monkeypatch):
    token = "z" * 48
    monkeypatch.setenv("ATLAS_V4_AUTH_MODE", "legacy")
    monkeypatch.setenv("ATLAS_V4_CURSOR_SECRET", "synthetic-cursor-secret-for-tests-only-0001")
    monkeypatch.setenv("ATLAS_TOOL_TOKEN", "synthetic-tool-token-unique")
    monkeypatch.setenv("ATLAS_V4_CAPABILITIES_JSON", "{}")
    app = create_app(db_path=tmp_path / "legacy-with-revoked-direct" / "atlas.sqlite3", token=token)
    registry = V4PrincipalRegistry(app.state.store)
    client = Client(app)
    principal_id, _device_ref = registry.create_principal(principal_id="revoked-direct-credential")
    credential_id = registry.add_credential(principal_id, token, audience="DIRECT")
    assert registry.revoke_credential(credential_id)

    response = client.post(
        "/v4/sync/consent-state",
        headers=consent_headers(token),
        json=CONSENT_BODY,
    )

    assert response.status_code == 200


def test_legacy_dual_rollback_preserves_proposals_and_ack_events(tmp_path, monkeypatch):
    database = tmp_path / "legacy-dual-rollback.sqlite3"
    token = "synthetic-legacy-dual-rollback-token"
    device_ref = legacy_device_ref(token)
    monkeypatch.setenv("ATLAS_V4_CAPABILITIES_JSON", "{}")
    monkeypatch.setenv("ATLAS_V4_AUTH_MODE", "legacy")
    legacy_app = create_app(db_path=database, token=token)
    legacy_client = Client(legacy_app)
    consent = legacy_client.post("/v4/sync/consent-state", headers=consent_headers(token), json=CONSENT_BODY)
    assert consent.status_code == 200

    projections = V4ProjectionStore(legacy_app.state.store)
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    plan_id = "00000000-0000-4000-8000-00000000058b"
    activity_id = "00000000-0000-4000-8000-00000000058c"
    projections.save_day_plan_snapshot(
        device_ref,
        {
            "plan_id": plan_id,
            "local_date": datetime.now(timezone.utc).date().isoformat(),
            "timezone": "Europe/Paris",
            "version": 7,
            "current_revision_id": "00000000-0000-4000-8000-00000000058d",
            "state": "APPROVED",
            "origin": "LOCAL",
            "observed_at": now,
            "activities": [{
                "activity_id": activity_id,
                "kind": "FOCUS",
                "title": "Rollback test focus",
                "status": "PLANNED",
                "scheduled_local": {
                    "local_date": datetime.now(timezone.utc).date().isoformat(),
                    "start_minute": 600,
                    "duration_minutes": 45,
                    "timezone": "Europe/Paris",
                },
                "priority": "OPTIONAL",
                "goal_id": None,
                "continuity_id": "00000000-0000-4000-8000-00000000058e",
                "supersedes_activity_id": None,
                "status_provenance": "UNKNOWN",
                "status_decided_at": None,
            }],
        },
        consent_version=1,
        received_at=now,
    )
    proposal_result = legacy_app.state.v4_tool_service.atlas_propose_plan_revision(device_ref, {
        "plan_id": plan_id,
        "base_version": 7,
        "changes": [{"op": "reschedule", "activity_id": activity_id, "target_start_minute": 660}],
        "justification": "synthetic rollback test",
        "idempotency_key": "00000000-0000-4000-8000-00000000058f",
    })
    proposal_id = proposal_result["data"]["proposal_id"]
    pull_body = {
        "contract_version": 4,
        "schema_version": 4,
        "request_id": "00000000-0000-4000-8000-000000000590",
        "capability": "proposal.ack",
        "consent_version": 1,
        "page_size": 32,
        "cursor": None,
    }
    legacy_pull = legacy_client.post(
        "/v4/agent/proposals/pull", headers=consent_headers(token), json=pull_body,
    )
    assert legacy_pull.status_code == 200
    assert [item["proposal_id"] for item in legacy_pull.json()["proposals"]] == [proposal_id]

    monkeypatch.setenv("ATLAS_V4_AUTH_MODE", "dual")
    monkeypatch.setenv("ATLAS_V4_CURSOR_SECRET", "synthetic-rollback-cursor-secret-long-enough")
    dual_app = create_app(db_path=database, token=token)
    registry = V4PrincipalRegistry(dual_app.state.store)
    principal_id, dual_device_ref, _credential_id = registry.import_legacy(
        token, grant_compatibility_defaults=True,
    )
    assert dual_device_ref == device_ref
    monkeypatch.setenv("ATLAS_V4_LEGACY_PRINCIPAL_ID", principal_id)
    dual_client = Client(dual_app)
    dual_pull = dual_client.post(
        "/v4/agent/proposals/pull", headers=consent_headers(token), json=pull_body,
    )
    assert dual_pull.status_code == 200
    assert [item["proposal_id"] for item in dual_pull.json()["proposals"]] == [proposal_id]
    ack = {
        "contract_version": 4,
        "schema_version": 4,
        "ack_id": "00000000-0000-4000-8000-000000000591",
        "capability": "proposal.ack",
        "proposal_id": proposal_id,
        "sequence": 1,
        "state": "REJECTED",
        "result_code": "USER_REJECTED",
        "occurred_at": now,
        "consent_version": 1,
    }
    dual_ack = dual_client.post(
        f"/v4/agent/proposals/{proposal_id}/ack", headers=consent_headers(token), json=ack,
    )
    assert dual_ack.status_code == 200

    monkeypatch.setenv("ATLAS_V4_AUTH_MODE", "legacy")
    rollback_app = create_app(db_path=database, token=token)
    rollback_client = Client(rollback_app)
    replayed_ack = rollback_client.post(
        f"/v4/agent/proposals/{proposal_id}/ack", headers=consent_headers(token), json=ack,
    )
    assert replayed_ack.status_code == 200
    assert replayed_ack.headers["x-atlas-replayed"] == "true"
    with rollback_app.state.store.connect() as connection:
        assert connection.execute(
            "SELECT state FROM agent_proposals_v4 WHERE proposal_id=?", (proposal_id,)
        ).fetchone()["state"] == "REJECTED"
        assert connection.execute(
            "SELECT COUNT(*) FROM agent_proposal_events_v4 WHERE proposal_id=?", (proposal_id,)
        ).fetchone()[0] == 1
