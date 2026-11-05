from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import time
from datetime import datetime, timezone

import httpx
import pytest

from atlas_backend.main import create_app
from atlas_backend.v4_assertion import canonical_message, load_assertion_keys
from atlas_backend.v4_auth import V4PrincipalRegistry
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


def make_app(tmp_path, monkeypatch, *, mode: str = "principal"):
    monkeypatch.setenv("ATLAS_V4_AUTH_MODE", mode)
    monkeypatch.delenv("ATLAS_V4_RELAY_ASSERTION_KEYS_FILE", raising=False)
    monkeypatch.setenv("ATLAS_V4_CURSOR_SECRET", "synthetic-cursor-secret-for-tests-only-0001")
    monkeypatch.setenv("ATLAS_TOOL_TOKEN", "synthetic-tool-token-unique")
    monkeypatch.setenv("ATLAS_V4_CAPABILITIES_JSON", json.dumps({
        "day_plan.read": True,
        "activity_status.read": True,
        "plan_revision.propose": True,
        "usage_summary.read": True,
        "goal_progress.read": True,
        "training_plan.read": True,
        "coach.chat": True,
    }))
    app = create_app(db_path=tmp_path / "atlas.sqlite3", token="synthetic-legacy-token")
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
                   body: bytes, path: str = "/v4/sync/consent-state") -> dict[str, str]:
    signature = "v1=" + base64.urlsafe_b64encode(
        hmac.new(secret, canonical_message("POST", path, str(timestamp), nonce, relay_device_id, body), hashlib.sha256).digest()
    ).decode("ascii").rstrip("=")
    return {
        **consent_headers(token),
        "X-Atlas-Relay-Device-Id": relay_device_id,
        "X-Atlas-Relay-Key-Id": "test-key-1",
        "X-Atlas-Relay-Time": str(timestamp),
        "X-Atlas-Relay-Nonce": nonce,
        "X-Atlas-Relay-Signature": signature,
    }


def test_relay_assertion_binds_credential_to_active_session_and_rejects_replay(tmp_path, monkeypatch):
    secret = b"synthetic-relay-assertion-key-only-for-tests"
    encoded = base64.urlsafe_b64encode(secret).decode("ascii").rstrip("=")
    monkeypatch.setenv("ATLAS_V4_RELAY_ASSERTION_KEYS_JSON", json.dumps({"test-key-1": encoded}))
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
    body = json.dumps(CONSENT_BODY, separators=(",", ":")).encode("utf-8")
    now = int(time.time() * 1000)
    headers_a = _relay_headers(token_a, secret, "relay-session-a", nonce="nonce-test-a", timestamp=now, body=body)

    accepted = client.post("/v4/sync/consent-state", content=body, headers=headers_a)
    replayed = client.post("/v4/sync/consent-state", content=body, headers=headers_a)
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

    assert accepted.status_code == 200
    assert replayed.status_code == 401
    assert crossed.status_code == 401
    assert no_assertion.status_code == 401
    assert expired.status_code == 401
    resolved = registry.resolve(token_a)
    assert resolved is not None and resolved.device_ref == device_a


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
    with app.state.store.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM agent_proposal_events_v4").fetchone()[0] == 0


def test_historical_null_proposals_are_visible_only_to_legacy_in_dual_mode(tmp_path, monkeypatch):
    app, registry, client = make_app(tmp_path, monkeypatch, mode="dual")
    legacy_token = "synthetic-historical-legacy-token"
    principal_id, device_ref, _credential_id = registry.import_legacy(
        legacy_token, grant_compatibility_defaults=True
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
