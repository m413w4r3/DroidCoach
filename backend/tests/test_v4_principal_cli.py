from __future__ import annotations

import json
import os
from pathlib import Path
import stat
import subprocess
import sys

from atlas_backend.store import SQLiteStore
from atlas_backend.v4_auth import V4PrincipalRegistry


BACKEND = Path(__file__).resolve().parents[1]
CLI = BACKEND / "scripts" / "atlas_v4_principals.py"


def run_cli(db_path: Path, *args: str, input_text: str | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(CLI), "--db", str(db_path), *args],
        cwd=BACKEND,
        input=input_text,
        text=True,
        capture_output=True,
        check=False,
    )


def test_legacy_import_stores_hash_only_and_never_prints_bearer(tmp_path):
    db_path = tmp_path / "atlas.sqlite3"
    token = "synthetic-cli-legacy-credential-token"
    result = run_cli(db_path, "legacy-import", "--grant-defaults", input_text=token + "\n")
    assert result.returncode == 0, result.stderr
    assert token not in result.stdout
    assert token not in result.stderr
    row = json.loads(result.stdout)
    store = SQLiteStore(db_path)
    context = V4PrincipalRegistry(store).resolve(token)
    assert context is not None
    assert context.principal_id == row["principal_id"]
    assert context.capabilities
    with store.connect() as connection:
        saved = connection.execute("SELECT token_hash FROM credentials_v4").fetchone()[0]
    assert token not in saved


def test_credential_rotation_keeps_device_ref_and_revokes_old_credential(tmp_path):
    store = SQLiteStore(tmp_path / "rotate.sqlite3")
    registry = V4PrincipalRegistry(store)
    principal_id, device_ref = registry.create_principal(principal_id="rotate-target", device_ref="stable-rotate")
    old_token = "synthetic-old-credential-token-for-rotation"
    new_token = "synthetic-new-credential-token-for-rotation"
    old_id = registry.add_credential(principal_id, old_token, audience="DIRECT")
    new_id = registry.rotate_credential(old_id, new_token)
    assert registry.resolve(old_token) is None
    current = registry.resolve(new_token)
    assert current is not None
    assert current.device_ref == device_ref
    assert current.credential_id == new_id
    assert current.principal_id == principal_id


def test_legacy_import_requires_explicit_compatibility_grants_and_rejects_weak_bearers(tmp_path):
    db_path = tmp_path / "weak.sqlite3"
    weak = run_cli(db_path, "legacy-import", "--grant-defaults", input_text="weak\n")
    assert weak.returncode == 2
    assert "weak" not in weak.stdout
    assert "weak" not in weak.stderr

    missing_grant_flag = run_cli(
        tmp_path / "without-grants.sqlite3", "legacy-import", input_text="synthetic-valid-legacy-credential\n"
    )
    assert missing_grant_flag.returncode == 2
    assert "synthetic-valid-legacy-credential" not in missing_grant_flag.stdout + missing_grant_flag.stderr


def test_proposal_backfill_is_dry_run_by_default_and_apply_requires_verified_backup(tmp_path):
    db_path = tmp_path / "backfill.sqlite3"
    store = SQLiteStore(db_path)
    registry = V4PrincipalRegistry(store)
    principal_id, device_ref = registry.create_principal(
        principal_id="legacy-target", kind="LEGACY", device_ref="historic-device-ref"
    )
    with store.connect() as connection:
        connection.execute("DROP TRIGGER agent_proposals_v4_require_owner")
        connection.execute(
            "INSERT INTO agent_proposals_v4(proposal_id,idempotency_key,body_hash,plan_id,base_version,capability,created_at,expires_at,state,proposal_json,device_ref) "
            "VALUES(?,?,?,?,?,?,?,?,?,?,NULL)",
            (
                "00000000-0000-4000-8000-000000000601",
                "00000000-0000-4000-8000-000000000602",
                "sha256:" + "a" * 64,
                "00000000-0000-4000-8000-000000000603",
                1,
                "plan_revision.propose",
                "2026-11-05T00:00:00Z",
                "2026-11-06T00:00:00Z",
                "PENDING_ANDROID",
                "{}",
            ),
        )
    preview = run_cli(db_path, "backfill-proposals")
    assert preview.returncode == 0, preview.stderr
    assert json.loads(preview.stdout)["unowned_proposals"] == 1
    with store.connect() as connection:
        assert connection.execute("SELECT device_ref FROM agent_proposals_v4").fetchone()[0] is None

    missing_backup = run_cli(db_path, "backfill-proposals", "--apply")
    assert missing_backup.returncode == 2

    backup = tmp_path / "verified-before-apply.sqlite3"
    applied = run_cli(db_path, "backfill-proposals", "--apply", "--backup", str(backup),
                      "--legacy-principal-id", principal_id)
    assert applied.returncode == 0, applied.stderr
    result = json.loads(applied.stdout)
    assert result["updated_proposals"] == 1
    assert result["mode"] == "applied"
    assert len(result["backup_sha256"]) == 64
    assert backup.is_file()
    assert stat.S_IMODE(backup.stat().st_mode) == 0o600
    with store.connect() as connection:
        assert connection.execute("SELECT device_ref FROM agent_proposals_v4").fetchone()[0] == device_ref
