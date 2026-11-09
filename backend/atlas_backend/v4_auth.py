"""Device principal and credential registry for V4.

Bearer values are accepted only at the HTTP boundary. SQLite stores SHA-256
digests and stable, server-generated device references.
"""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import hmac
import json
import secrets
import sqlite3
import uuid
from collections.abc import Callable, Iterable
from typing import Any

from .store import SQLiteStore


CAPABILITY_ALLOWLIST = frozenset({
    "day_plan.read",
    "activity_status.read",
    "plan_revision.propose",
    "usage_summary.read",
    "goal_progress.read",
    "training_plan.read",
    "coach.chat",
    "day_plan.snapshot.write",
    "activity_status.write",
    "usage_summary.write",
    "goal_progress.write",
    "training_plan.write",
    "consent.state.write",
    "proposal.ack",
})
AUDIENCES = frozenset({"DIRECT", "RELAY", "TOOL", "LEGACY"})
LEGACY_COMPATIBILITY_CAPABILITIES = CAPABILITY_ALLOWLIST
ANDROID_RELAY_CAPABILITIES = CAPABILITY_ALLOWLIST
TOOL_RELAY_CAPABILITIES = frozenset({
    "day_plan.read",
    "activity_status.read",
    "plan_revision.propose",
    "usage_summary.read",
    "goal_progress.read",
    "training_plan.read",
})


class PrincipalAuthError(ValueError):
    """A provisioning action or credential resolution failed closed."""


@dataclass(frozen=True)
class AuthContext:
    principal_id: str
    device_ref: str
    credential_id: str
    audience: str
    principal_kind: str
    capabilities: frozenset[str]


def bearer_sha256(token: str) -> str:
    if not isinstance(token, str) or len(token) < 32 or len(token) > 4096:
        raise PrincipalAuthError("bearer invalide")
    try:
        token_bytes = token.encode("ascii", errors="strict")
    except UnicodeEncodeError:
        raise PrincipalAuthError("bearer invalide") from None
    return hashlib.sha256(token_bytes).hexdigest()


def legacy_device_ref(token: str) -> str:
    """Return the historic V4 reference for explicit legacy import/rollback."""
    try:
        token_bytes = token.encode("utf-8", errors="strict")
    except UnicodeEncodeError:
        raise PrincipalAuthError("bearer invalide") from None
    return "android-" + hashlib.sha256(token_bytes).hexdigest()[:32]


class V4PrincipalRegistry:
    def __init__(self, store: SQLiteStore) -> None:
        self.store = store

    def provision_relay_identity(
        self,
        relay_device_id: str,
        relay_token: str,
        tool_token: str,
        *,
        android_capabilities: Iterable[str] = ANDROID_RELAY_CAPABILITIES,
        tool_capabilities: Iterable[str] = TOOL_RELAY_CAPABILITIES,
        before_commit: Callable[[dict[str, Any]], None] | None = None,
    ) -> dict[str, Any]:
        """Atomically provision the Android Relay and dormant Core TOOL identities.

        ``before_commit`` is intended for the offline provisioning CLI to persist
        its private credential/map receipt while the database transaction is
        still open. If that callback fails, SQLite rolls the whole batch back.
        """
        if not isinstance(relay_device_id, str) or not relay_device_id or len(relay_device_id) > 256:
            raise PrincipalAuthError("identité Relay invalide")
        relay_digest = bearer_sha256(relay_token)
        tool_digest = bearer_sha256(tool_token)
        if hmac.compare_digest(relay_digest, tool_digest):
            raise PrincipalAuthError("credentials RELAY et TOOL doivent être distincts")

        android_caps = tuple(android_capabilities)
        tool_caps = tuple(tool_capabilities)
        now = self.store.now_iso()

        with self.store.transaction() as connection:
            counts = {
                "principals": int(connection.execute("SELECT COUNT(*) FROM principals_v4").fetchone()[0]),
                "credentials": int(connection.execute("SELECT COUNT(*) FROM credentials_v4").fetchone()[0]),
                "bindings": int(connection.execute("SELECT COUNT(*) FROM relay_principal_bindings_v4").fetchone()[0]),
            }
            if counts != {"principals": 0, "credentials": 0, "bindings": 0}:
                raise PrincipalAuthError("registre V4 non vide")
            if len(set(android_caps)) != len(android_caps) or set(android_caps) != set(ANDROID_RELAY_CAPABILITIES):
                raise PrincipalAuthError("capabilities Android de provisioning invalides")
            if len(set(tool_caps)) != len(tool_caps) or set(tool_caps) != set(TOOL_RELAY_CAPABILITIES):
                raise PrincipalAuthError("capabilities TOOL de provisioning invalides")
            if any(capability not in CAPABILITY_ALLOWLIST for capability in (*android_caps, *tool_caps)):
                raise PrincipalAuthError("capability inconnue")
            if connection.execute(
                "SELECT 1 FROM relay_principal_bindings_v4 WHERE relay_device_id=? LIMIT 1",
                (relay_device_id,),
            ).fetchone() is not None:
                raise PrincipalAuthError("binding Relay déjà provisionné")
            if connection.execute(
                "SELECT 1 FROM credentials_v4 WHERE token_hash IN (?,?) LIMIT 1",
                (relay_digest, tool_digest),
            ).fetchone() is not None:
                raise PrincipalAuthError("credential déjà provisionné")

            principal_ids = {
                "ANDROID": "prn_" + secrets.token_urlsafe(18),
                "TOOL": "prn_" + secrets.token_urlsafe(18),
            }
            device_refs = {
                "ANDROID": "device-" + secrets.token_urlsafe(24),
                "TOOL": "device-" + secrets.token_urlsafe(24),
            }
            credential_ids = {"RELAY": str(uuid.uuid4()), "TOOL": str(uuid.uuid4())}
            all_ids = [*principal_ids.values(), *device_refs.values(), *credential_ids.values()]
            if len(set(principal_ids.values())) != 2 or len(set(device_refs.values())) != 2:
                raise PrincipalAuthError("identifiants générés en collision")
            if len(set(credential_ids.values())) != 2 or len(set(all_ids)) != len(all_ids):
                raise PrincipalAuthError("identifiants générés en collision")

            for device_ref in device_refs.values():
                if connection.execute(
                    "SELECT 1 FROM consent_states_v4 WHERE device_ref=? LIMIT 1", (device_ref,)
                ).fetchone() is not None:
                    raise PrincipalAuthError("device_ref déjà associé à un consentement")
                if connection.execute(
                    "SELECT 1 FROM agent_proposals_v4 WHERE device_ref=? LIMIT 1", (device_ref,)
                ).fetchone() is not None:
                    raise PrincipalAuthError("device_ref déjà associé à une proposition")

            for kind in ("ANDROID", "TOOL"):
                principal_id = principal_ids[kind]
                connection.execute(
                    "INSERT INTO principals_v4(principal_id,device_ref,kind,state,created_at) "
                    "VALUES(?,?,?,'ACTIVE',?)",
                    (principal_id, device_refs[kind], kind, now),
                )
                self._event(connection, principal_id, None, "PROVISIONED", now, {"kind": kind})

            credentials = (
                ("RELAY", principal_ids["ANDROID"], relay_digest),
                ("TOOL", principal_ids["TOOL"], tool_digest),
            )
            for audience, principal_id, digest in credentials:
                credential_id = credential_ids[audience]
                connection.execute(
                    "INSERT INTO credentials_v4(credential_id,principal_id,token_hash,hash_algorithm,audience,state,created_at) "
                    "VALUES(?,?,?,'SHA-256',?,'ACTIVE',?)",
                    (credential_id, principal_id, digest, audience, now),
                )
                self._event(connection, principal_id, credential_id, "PROVISIONED", now, {"audience": audience})

            binding_id = str(uuid.uuid4())
            connection.execute(
                "INSERT INTO relay_principal_bindings_v4(relay_device_id,principal_id,state,created_at,event_id) "
                "VALUES(?,?,'ACTIVE',?,?)",
                (relay_device_id, principal_ids["ANDROID"], now, binding_id),
            )
            self._event(connection, principal_ids["ANDROID"], None, "RELAY_BOUND", now,
                        {"relay_device_id": relay_device_id, "binding_id": binding_id})

            for kind, capabilities in (("ANDROID", android_caps), ("TOOL", tool_caps)):
                principal_id = principal_ids[kind]
                for capability in sorted(capabilities):
                    connection.execute(
                        "INSERT INTO principal_capabilities_v4(principal_id,capability,granted,updated_at) "
                        "VALUES(?,?,1,?)",
                        (principal_id, capability, now),
                    )
                    self._event(connection, principal_id, None, "CAPABILITY_CHANGED", now,
                                {"capability": capability, "granted": True})

            receipt = {
                "principal_ids": principal_ids,
                "device_refs": device_refs,
                "credential_ids": credential_ids,
                "audiences": {"RELAY": "RELAY", "TOOL": "TOOL"},
                "relay_device_id": relay_device_id,
                "capabilities": {"ANDROID": sorted(android_caps), "TOOL": sorted(tool_caps)},
                "counts": {"principals": 2, "credentials": 2, "bindings": 1, "grants": 20, "events": 25},
            }
            if before_commit is not None:
                before_commit(receipt)
        return receipt

    def credential_state(self, token: str) -> str | None:
        """Return the state of a registered LEGACY bearer without exposing it."""
        try:
            digest = bearer_sha256(token)
        except PrincipalAuthError:
            return None
        try:
            with self.store.connect() as connection:
                row = connection.execute(
                    "SELECT token_hash,state FROM credentials_v4 WHERE token_hash=? AND audience='LEGACY'",
                    (digest,),
                ).fetchone()
        except sqlite3.OperationalError as exc:
            if "no such table" in str(exc).lower():
                return None
            raise
        if row is None or not hmac.compare_digest(digest, str(row["token_hash"])):
            return None
        return str(row["state"])

    def create_principal(
        self,
        *,
        principal_id: str | None = None,
        kind: str = "ANDROID",
        device_ref: str | None = None,
    ) -> tuple[str, str]:
        principal_id = principal_id or "prn_" + secrets.token_urlsafe(18)
        device_ref = device_ref or "device-" + secrets.token_urlsafe(24)
        if not principal_id or len(principal_id) > 128 or kind not in {"ANDROID", "TOOL", "LEGACY"}:
            raise PrincipalAuthError("principal invalide")
        if not device_ref or len(device_ref) > 160:
            raise PrincipalAuthError("device_ref invalide")
        now = self.store.now_iso()
        with self.store.transaction() as connection:
            try:
                connection.execute(
                    "INSERT INTO principals_v4(principal_id,device_ref,kind,state,created_at) VALUES(?,?,?,'ACTIVE',?)",
                    (principal_id, device_ref, kind, now),
                )
            except sqlite3.IntegrityError:
                raise PrincipalAuthError("principal déjà provisionné") from None
            self._event(connection, principal_id, None, "PROVISIONED", now, {"kind": kind})
        return principal_id, device_ref

    def import_legacy(self, token: str, *, grant_compatibility_defaults: bool = False) -> tuple[str, str, str]:
        digest = bearer_sha256(token)
        device_ref = legacy_device_ref(token)
        now = self.store.now_iso()
        with self.store.transaction() as connection:
            existing = connection.execute(
                "SELECT c.credential_id,c.state,p.principal_id,p.device_ref,p.state AS principal_state "
                "FROM credentials_v4 c JOIN principals_v4 p USING(principal_id) WHERE c.token_hash=?",
                (digest,),
            ).fetchone()
            if existing is not None:
                if existing["state"] != "ACTIVE" or existing["principal_state"] != "ACTIVE":
                    raise PrincipalAuthError("credential legacy révoqué")
                return existing["principal_id"], existing["device_ref"], existing["credential_id"]
            # The stable device_ref preserves historical data ownership. The principal ID
            # itself remains independent of the bearer used to import the legacy device.
            principal_id = "legacy_" + secrets.token_urlsafe(18)
            try:
                connection.execute(
                    "INSERT INTO principals_v4(principal_id,device_ref,kind,state,created_at) VALUES(?,?, 'LEGACY','ACTIVE',?)",
                    (principal_id, device_ref, now),
                )
                credential_id = str(uuid.uuid4())
                connection.execute(
                    "INSERT INTO credentials_v4(credential_id,principal_id,token_hash,hash_algorithm,audience,state,created_at) "
                    "VALUES(?,?,?,'SHA-256','LEGACY','ACTIVE',?)",
                    (credential_id, principal_id, digest, now),
                )
            except sqlite3.IntegrityError:
                raise PrincipalAuthError("import legacy conflictuel") from None
            self._event(connection, principal_id, credential_id, "PROVISIONED", now, {"audience": "LEGACY"})
            if grant_compatibility_defaults:
                for capability in sorted(LEGACY_COMPATIBILITY_CAPABILITIES):
                    connection.execute(
                        "INSERT INTO principal_capabilities_v4(principal_id,capability,granted,updated_at) "
                        "VALUES(?,?,1,?)",
                        (principal_id, capability, now),
                    )
                    self._event(connection, principal_id, credential_id, "CAPABILITY_CHANGED", now,
                                {"capability": capability, "source": "explicit_legacy_compatibility_import"})
        return principal_id, device_ref, credential_id

    def add_credential(
        self,
        principal_id: str,
        token: str,
        *,
        audience: str,
        credential_id: str | None = None,
    ) -> str:
        if audience not in AUDIENCES:
            raise PrincipalAuthError("audience invalide")
        digest = bearer_sha256(token)
        credential_id = credential_id or str(uuid.uuid4())
        now = self.store.now_iso()
        with self.store.transaction() as connection:
            principal = connection.execute(
                "SELECT state FROM principals_v4 WHERE principal_id=?", (principal_id,)
            ).fetchone()
            if principal is None or principal["state"] != "ACTIVE":
                raise PrincipalAuthError("principal indisponible")
            try:
                connection.execute(
                    "INSERT INTO credentials_v4(credential_id,principal_id,token_hash,hash_algorithm,audience,state,created_at) "
                    "VALUES(?,?,?,'SHA-256',?,'ACTIVE',?)",
                    (credential_id, principal_id, digest, audience, now),
                )
            except sqlite3.IntegrityError:
                raise PrincipalAuthError("credential conflictuel") from None
            self._event(connection, principal_id, credential_id, "PROVISIONED", now, {"audience": audience})
        return credential_id

    def rotate_credential(self, credential_id: str, token: str) -> str:
        digest = bearer_sha256(token)
        new_credential_id = str(uuid.uuid4())
        now = self.store.now_iso()
        with self.store.transaction() as connection:
            old = connection.execute(
                "SELECT c.principal_id,c.audience,c.state,p.state AS principal_state "
                "FROM credentials_v4 c JOIN principals_v4 p ON p.principal_id=c.principal_id "
                "WHERE c.credential_id=?",
                (credential_id,),
            ).fetchone()
            if old is None or old["state"] != "ACTIVE" or old["principal_state"] != "ACTIVE":
                raise PrincipalAuthError("credential indisponible")
            try:
                connection.execute(
                    "INSERT INTO credentials_v4(credential_id,principal_id,token_hash,hash_algorithm,audience,state,created_at) "
                    "VALUES(?,?,?,'SHA-256',?,'ACTIVE',?)",
                    (new_credential_id, old["principal_id"], digest, old["audience"], now),
                )
            except sqlite3.IntegrityError:
                raise PrincipalAuthError("credential conflictuel") from None
            connection.execute(
                "UPDATE credentials_v4 SET state='REVOKED',revoked_at=?,replaced_by=? WHERE credential_id=?",
                (now, new_credential_id, credential_id),
            )
            self._event(connection, str(old["principal_id"]), credential_id,
                        "CREDENTIAL_REVOKED", now, {"replaced_by": new_credential_id})
            self._event(connection, str(old["principal_id"]), new_credential_id,
                        "ROTATED", now, {"replaces": credential_id, "audience": str(old["audience"])})
        return new_credential_id

    def resolve(self, token: str) -> AuthContext | None:
        try:
            digest = bearer_sha256(token)
        except PrincipalAuthError:
            return None
        with self.store.connect() as connection:
            row = connection.execute(
                "SELECT c.credential_id,c.token_hash,c.principal_id,c.audience,c.state AS credential_state, "
                "p.device_ref,p.kind,p.state AS principal_state "
                "FROM credentials_v4 c JOIN principals_v4 p ON p.principal_id=c.principal_id "
                "WHERE c.token_hash=?",
                (digest,),
            ).fetchone()
            if row is None or not hmac.compare_digest(digest, str(row["token_hash"])):
                return None
            if row["credential_state"] != "ACTIVE" or row["principal_state"] != "ACTIVE":
                return None
            grants = connection.execute(
                "SELECT capability FROM principal_capabilities_v4 WHERE principal_id=? AND granted=1",
                (row["principal_id"],),
            ).fetchall()
        return AuthContext(
            principal_id=str(row["principal_id"]),
            device_ref=str(row["device_ref"]),
            credential_id=str(row["credential_id"]),
            audience=str(row["audience"]),
            principal_kind=str(row["kind"]),
            capabilities=frozenset(str(grant["capability"]) for grant in grants),
        )

    def active_binding(self, relay_device_id: str) -> tuple[str, str] | None:
        with self.store.connect() as connection:
            row = connection.execute(
                "SELECT b.principal_id,p.device_ref FROM relay_principal_bindings_v4 b "
                "JOIN principals_v4 p ON p.principal_id=b.principal_id "
                "WHERE b.relay_device_id=? AND b.state='ACTIVE' AND p.state='ACTIVE'",
                (relay_device_id,),
            ).fetchone()
        return (str(row["principal_id"]), str(row["device_ref"])) if row is not None else None

    def set_capability(self, principal_id: str, capability: str, granted: bool) -> None:
        if capability not in CAPABILITY_ALLOWLIST:
            raise PrincipalAuthError("capability inconnue")
        now = self.store.now_iso()
        with self.store.transaction() as connection:
            row = connection.execute(
                "SELECT state FROM principals_v4 WHERE principal_id=?", (principal_id,)
            ).fetchone()
            if row is None or row["state"] != "ACTIVE":
                raise PrincipalAuthError("principal indisponible")
            prior = connection.execute(
                "SELECT granted FROM principal_capabilities_v4 WHERE principal_id=? AND capability=?",
                (principal_id, capability),
            ).fetchone()
            if prior is not None and bool(prior["granted"]) is bool(granted):
                return
            connection.execute(
                "INSERT INTO principal_capabilities_v4(principal_id,capability,granted,updated_at) VALUES(?,?,?,?) "
                "ON CONFLICT(principal_id,capability) DO UPDATE SET granted=excluded.granted,updated_at=excluded.updated_at",
                (principal_id, capability, int(granted), now),
            )
            self._event(connection, principal_id, None, "CAPABILITY_CHANGED", now,
                        {"capability": capability, "granted": bool(granted)})

    def add_binding(self, relay_device_id: str, principal_id: str) -> None:
        if not relay_device_id or len(relay_device_id) > 256:
            raise PrincipalAuthError("identité Relay invalide")
        now = self.store.now_iso()
        with self.store.transaction() as connection:
            principal = connection.execute(
                "SELECT state FROM principals_v4 WHERE principal_id=?", (principal_id,)
            ).fetchone()
            if principal is None or principal["state"] != "ACTIVE":
                raise PrincipalAuthError("principal indisponible")
            relay_credential = connection.execute(
                "SELECT 1 FROM credentials_v4 WHERE principal_id=? AND audience='RELAY' AND state='ACTIVE' LIMIT 1",
                (principal_id,),
            ).fetchone()
            if relay_credential is None:
                raise PrincipalAuthError("credential Relay actif requis")
            prior = connection.execute(
                "SELECT principal_id,state FROM relay_principal_bindings_v4 WHERE relay_device_id=?",
                (relay_device_id,),
            ).fetchone()
            if prior is not None:
                if prior["principal_id"] == principal_id and prior["state"] == "ACTIVE":
                    return
                raise PrincipalAuthError("binding déjà provisionné")
            binding_id = str(uuid.uuid4())
            connection.execute(
                "INSERT INTO relay_principal_bindings_v4(relay_device_id,principal_id,state,created_at,event_id) "
                "VALUES(?,?,'ACTIVE',?,?)",
                (relay_device_id, principal_id, now, binding_id),
            )
            self._event(connection, principal_id, None, "RELAY_BOUND", now,
                        {"relay_device_id": relay_device_id, "binding_id": binding_id})

    def revoke_binding(self, relay_device_id: str) -> bool:
        now = self.store.now_iso()
        with self.store.transaction() as connection:
            row = connection.execute(
                "SELECT principal_id,state FROM relay_principal_bindings_v4 WHERE relay_device_id=?",
                (relay_device_id,),
            ).fetchone()
            if row is None or row["state"] == "REVOKED":
                return False
            connection.execute(
                "UPDATE relay_principal_bindings_v4 SET state='REVOKED',revoked_at=? WHERE relay_device_id=?",
                (now, relay_device_id),
            )
            self._event(connection, str(row["principal_id"]), None, "RELAY_BOUND", now,
                        {"relay_device_id": relay_device_id, "state": "REVOKED"})
            return True

    def revoke_credential(self, credential_id: str) -> bool:
        now = self.store.now_iso()
        with self.store.transaction() as connection:
            row = connection.execute(
                "SELECT principal_id,state FROM credentials_v4 WHERE credential_id=?", (credential_id,)
            ).fetchone()
            if row is None or row["state"] == "REVOKED":
                return False
            connection.execute(
                "UPDATE credentials_v4 SET state='REVOKED',revoked_at=? WHERE credential_id=?",
                (now, credential_id),
            )
            self._event(connection, str(row["principal_id"]), credential_id, "CREDENTIAL_REVOKED", now, {})
            return True

    def revoke_principal(self, principal_id: str) -> bool:
        now = self.store.now_iso()
        with self.store.transaction() as connection:
            row = connection.execute(
                "SELECT state FROM principals_v4 WHERE principal_id=?", (principal_id,)
            ).fetchone()
            if row is None or row["state"] == "REVOKED":
                return False
            credentials = connection.execute(
                "SELECT credential_id FROM credentials_v4 WHERE principal_id=? AND state='ACTIVE'",
                (principal_id,),
            ).fetchall()
            bindings = connection.execute(
                "SELECT relay_device_id FROM relay_principal_bindings_v4 "
                "WHERE principal_id=? AND state='ACTIVE'",
                (principal_id,),
            ).fetchall()
            connection.execute(
                "UPDATE credentials_v4 SET state='REVOKED',revoked_at=? WHERE principal_id=? AND state='ACTIVE'",
                (now, principal_id),
            )
            for credential in credentials:
                self._event(connection, principal_id, str(credential["credential_id"]),
                            "CREDENTIAL_REVOKED", now, {"reason": "principal_revoked"})
            for binding in bindings:
                self._event(connection, principal_id, None, "RELAY_BOUND", now,
                            {"relay_device_id": str(binding["relay_device_id"]), "state": "REVOKED"})
            connection.execute(
                "UPDATE relay_principal_bindings_v4 SET state='REVOKED',revoked_at=? WHERE principal_id=? AND state='ACTIVE'",
                (now, principal_id),
            )
            connection.execute(
                "UPDATE principals_v4 SET state='REVOKED',revoked_at=? WHERE principal_id=?",
                (now, principal_id),
            )
            self._event(connection, principal_id, None, "PRINCIPAL_REVOKED", now, {})
            return True

    def list_redacted(self) -> list[dict[str, Any]]:
        with self.store.connect() as connection:
            principals = connection.execute(
                "SELECT principal_id,device_ref,kind,state,created_at,revoked_at FROM principals_v4 ORDER BY principal_id"
            ).fetchall()
            result: list[dict[str, Any]] = []
            for principal in principals:
                credentials = connection.execute(
                    "SELECT credential_id,audience,state,created_at,revoked_at FROM credentials_v4 "
                    "WHERE principal_id=? ORDER BY created_at,credential_id", (principal["principal_id"],)
                ).fetchall()
                grants = connection.execute(
                    "SELECT capability,granted FROM principal_capabilities_v4 WHERE principal_id=? ORDER BY capability",
                    (principal["principal_id"],),
                ).fetchall()
                bindings = connection.execute(
                    "SELECT relay_device_id,state FROM relay_principal_bindings_v4 WHERE principal_id=? ORDER BY relay_device_id",
                    (principal["principal_id"],),
                ).fetchall()
                result.append({
                    **dict(principal),
                    "credentials": [dict(row) for row in credentials],
                    "capabilities": [dict(row) for row in grants],
                    "relay_bindings": [dict(row) for row in bindings],
                })
        return result

    @staticmethod
    def _event(
        connection: sqlite3.Connection,
        principal_id: str,
        credential_id: str | None,
        event_type: str,
        occurred_at: str,
        metadata: dict[str, Any],
    ) -> None:
        connection.execute(
            "INSERT INTO principal_events_v4(event_id,principal_id,credential_id,event_type,occurred_at,metadata_json) "
            "VALUES(?,?,?,?,?,?)",
            (str(uuid.uuid4()), principal_id, credential_id, event_type, occurred_at,
             json.dumps(metadata, sort_keys=True, separators=(",", ":"))),
        )
