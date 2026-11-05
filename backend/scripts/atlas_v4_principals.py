#!/usr/bin/env python3
"""Offline administration for the V4 principal registry.

This command never contacts Core, Relay, Android, or an external service. Bearers
are accepted only from stdin or written once to an exclusive mode-0600 file.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import sqlite3
import stat
import sys
from typing import Sequence

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from atlas_backend.store import SQLiteStore
from atlas_backend.v4_auth import (
    AUDIENCES,
    CAPABILITY_ALLOWLIST,
    PrincipalAuthError,
    V4PrincipalRegistry,
)


def _read_token_stdin() -> str:
    raw = sys.stdin.buffer.readline(8194)
    if len(raw) > 8192 or (raw and not raw.endswith(b"\n") and len(raw) == 8194):
        raise PrincipalAuthError("entrée credential trop longue")
    if raw.endswith(b"\n"):
        raw = raw[:-1]
        if raw.endswith(b"\r"):
            raw = raw[:-1]
    try:
        token = raw.decode("ascii", errors="strict")
    except UnicodeDecodeError:
        raise PrincipalAuthError("credential invalide") from None
    if not token:
        raise PrincipalAuthError("credential vide")
    return token


def _write_private_exclusive(path_value: str, content: str) -> None:
    path = Path(path_value)
    if not path.is_absolute():
        raise PrincipalAuthError("le fichier credential doit utiliser un chemin absolu")
    try:
        parent = path.parent.lstat()
    except OSError:
        raise PrincipalAuthError("répertoire credential indisponible") from None
    if stat.S_ISLNK(parent.st_mode) or not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.getuid():
        raise PrincipalAuthError("répertoire credential non sûr")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        fd = os.open(path, flags, 0o600)
    except OSError:
        raise PrincipalAuthError("création exclusive du fichier credential refusée") from None
    try:
        info = os.fstat(fd)
        if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600 or not stat.S_ISREG(info.st_mode):
            raise PrincipalAuthError("fichier credential non sûr")
        with os.fdopen(fd, "w", encoding="ascii", closefd=True) as output:
            fd = -1
            output.write(content)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
    finally:
        if fd >= 0:
            os.close(fd)


def _secret_for(args: argparse.Namespace) -> str:
    if args.generate:
        if not args.output:
            raise PrincipalAuthError("--output requis avec --generate")
        token = "atlas_" + secrets.token_urlsafe(32)
        _write_private_exclusive(args.output, token)
        return token
    return _read_token_stdin()


def _readonly_connection(path: Path) -> sqlite3.Connection:
    if not path.is_absolute() or not path.is_file() or path.is_symlink():
        raise PrincipalAuthError("base SQLite locale absente ou chemin non sûr")
    uri = f"file:{path.as_posix()}?mode=ro"
    connection = sqlite3.connect(uri, uri=True)
    connection.row_factory = sqlite3.Row
    return connection


def _preview_migration(db_path: Path) -> dict[str, object]:
    with _readonly_connection(db_path) as connection:
        version = int(connection.execute("PRAGMA user_version").fetchone()[0])
        has_ledger = connection.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='schema_migrations'"
        ).fetchone() is not None
        migration = connection.execute(
            "SELECT name,checksum FROM schema_migrations WHERE version=4"
        ).fetchone() if has_ledger else None
        tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        historical = None
        if "agent_proposals_v4" in tables:
            columns = {row[1] for row in connection.execute("PRAGMA table_info(agent_proposals_v4)")}
            if "device_ref" in columns:
                historical = int(connection.execute(
                    "SELECT COUNT(*) FROM agent_proposals_v4 WHERE device_ref IS NULL"
                ).fetchone()[0])
    return {
        "user_version": version,
        "v4_principal_migration_applied": migration is not None,
        "migration_name": migration["name"] if migration is not None else None,
        "historical_proposals_without_owner": historical,
        "mode": "preview_only",
    }


def _active_legacy_principal(db_path: Path, principal_id: str | None) -> tuple[str, str]:
    with _readonly_connection(db_path) as connection:
        if principal_id:
            row = connection.execute(
                "SELECT principal_id,device_ref FROM principals_v4 "
                "WHERE principal_id=? AND kind='LEGACY' AND state='ACTIVE'", (principal_id,)
            ).fetchone()
            if row is None:
                raise PrincipalAuthError("principal legacy actif introuvable")
            return str(row["principal_id"]), str(row["device_ref"])
        rows = connection.execute(
            "SELECT principal_id,device_ref FROM principals_v4 WHERE kind='LEGACY' AND state='ACTIVE' "
            "ORDER BY created_at,principal_id"
        ).fetchall()
    if len(rows) != 1:
        raise PrincipalAuthError("un principal legacy cible explicite est requis")
    return str(rows[0]["principal_id"]), str(rows[0]["device_ref"])


def _verified_backup(db_path: Path, backup_value: str) -> str:
    backup_path = Path(backup_value)
    if not backup_path.is_absolute() or backup_path.exists() or backup_path.is_symlink():
        raise PrincipalAuthError("--backup doit être un chemin absolu inexistant")
    try:
        parent = backup_path.parent.lstat()
    except OSError:
        raise PrincipalAuthError("répertoire de sauvegarde indisponible") from None
    if stat.S_ISLNK(parent.st_mode) or not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.getuid():
        raise PrincipalAuthError("répertoire de sauvegarde non sûr")
    flags = os.O_CREAT | os.O_EXCL | os.O_RDWR
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        fd = os.open(backup_path, flags, 0o600)
    except OSError:
        raise PrincipalAuthError("création exclusive de la sauvegarde refusée") from None
    os.close(fd)
    try:
        with sqlite3.connect(db_path) as source, sqlite3.connect(backup_path) as destination:
            source.backup(destination)
            check = destination.execute("PRAGMA integrity_check").fetchone()[0]
            foreign_check = destination.execute("PRAGMA foreign_key_check").fetchall()
            version = int(destination.execute("PRAGMA user_version").fetchone()[0])
            count = int(destination.execute("SELECT COUNT(*) FROM agent_proposals_v4 WHERE device_ref IS NULL").fetchone()[0])
        info = backup_path.lstat()
        if (
            check != "ok" or foreign_check or info.st_size <= 0
            or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600
        ):
            raise PrincipalAuthError("vérification de sauvegarde échouée")
        with _readonly_connection(db_path) as current:
            current_version = int(current.execute("PRAGMA user_version").fetchone()[0])
            current_count = int(current.execute(
                "SELECT COUNT(*) FROM agent_proposals_v4 WHERE device_ref IS NULL"
            ).fetchone()[0])
        if version != current_version or count != current_count:
            raise PrincipalAuthError("sauvegarde incohérente avec la base source")
        digest = hashlib.sha256(backup_path.read_bytes()).hexdigest()
        return digest
    except BaseException:
        try:
            backup_path.unlink()
        except OSError:
            pass
        raise


def _backfill(db_path: Path, *, apply: bool, backup: str | None, principal_id: str | None) -> dict[str, object]:
    target_id, device_ref = _active_legacy_principal(db_path, principal_id)
    with _readonly_connection(db_path) as connection:
        before = int(connection.execute(
            "SELECT COUNT(*) FROM agent_proposals_v4 WHERE device_ref IS NULL"
        ).fetchone()[0])
    result: dict[str, object] = {"unowned_proposals": before, "target_principal_id": target_id, "mode": "dry_run"}
    if not apply:
        return result
    if not backup:
        raise PrincipalAuthError("--backup requis avec --apply")
    backup_hash = _verified_backup(db_path, backup)
    store = SQLiteStore(db_path)
    with store.transaction() as connection:
        changed = connection.execute(
            "UPDATE agent_proposals_v4 SET device_ref=? WHERE device_ref IS NULL", (device_ref,)
        ).rowcount
        after = int(connection.execute(
            "SELECT COUNT(*) FROM agent_proposals_v4 WHERE device_ref IS NULL"
        ).fetchone()[0])
        if after != 0 or changed != before:
            raise PrincipalAuthError("backfill proposals incomplet")
    store._verify_v4_integrity()
    result.update({"mode": "applied", "updated_proposals": before, "backup_sha256": backup_hash})
    return result


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", required=True, help="chemin absolu de la base SQLite locale")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("preview-migration")

    legacy = sub.add_parser("legacy-import")
    legacy.add_argument(
        "--grant-defaults", action="store_true", required=True,
        help="accorder explicitement les capabilities de compatibilité APK V4",
    )

    principal_group = sub.add_parser("principal")
    principal_sub = principal_group.add_subparsers(dest="action", required=True)
    principal = principal_sub.add_parser("add")
    principal.add_argument("--principal-id")
    principal.add_argument("--kind", choices=("ANDROID", "TOOL", "LEGACY"), default="ANDROID")
    principal.add_argument("--device-ref")
    principal_revoke = principal_sub.add_parser("revoke")
    principal_revoke.add_argument("--principal-id", required=True)

    credential_group = sub.add_parser("credential")
    credential_sub = credential_group.add_subparsers(dest="action", required=True)
    credential = credential_sub.add_parser("add")
    credential.add_argument("--principal-id", required=True)
    credential.add_argument("--audience", required=True, choices=sorted(AUDIENCES))
    mode = credential.add_mutually_exclusive_group(required=True)
    mode.add_argument("--token-stdin", action="store_true")
    mode.add_argument("--generate", action="store_true")
    credential.add_argument("--output")

    rotate = credential_sub.add_parser("rotate")
    rotate.add_argument("--credential-id", required=True)
    mode = rotate.add_mutually_exclusive_group(required=True)
    mode.add_argument("--token-stdin", action="store_true")
    mode.add_argument("--generate", action="store_true")
    rotate.add_argument("--output")

    revoke_credential = credential_sub.add_parser("revoke")
    revoke_credential.add_argument("--credential-id", required=True)

    binding_group = sub.add_parser("binding")
    binding_sub = binding_group.add_subparsers(dest="action", required=True)
    binding = binding_sub.add_parser("add")
    binding.add_argument("--relay-device-id", required=True)
    binding.add_argument("--principal-id", required=True)
    binding_revoke = binding_sub.add_parser("revoke")
    binding_revoke.add_argument("--relay-device-id", required=True)

    capability_group = sub.add_parser("capability")
    capability_sub = capability_group.add_subparsers(dest="action", required=True)
    capability = capability_sub.add_parser("set")
    capability.add_argument("--principal-id", required=True)
    capability.add_argument("--capability", required=True, choices=sorted(CAPABILITY_ALLOWLIST))
    capability.add_argument("--grant", action="store_true")

    sub.add_parser("list")

    verify = sub.add_parser("verify-token")

    backfill = sub.add_parser("backfill-proposals")
    backfill.add_argument("--apply", action="store_true", help="appliquer après sauvegarde vérifiée")
    backfill.add_argument("--backup", help="chemin absolu inexistant de sauvegarde à créer et vérifier")
    backfill.add_argument("--legacy-principal-id")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    db_path = Path(args.db)
    if args.command == "preview-migration":
        print(json.dumps(_preview_migration(db_path), sort_keys=True))
        return 0
    if args.command == "backfill-proposals":
        result = _backfill(db_path, apply=args.apply, backup=args.backup,
                           principal_id=args.legacy_principal_id)
        print(json.dumps(result, sort_keys=True))
        return 0

    store = SQLiteStore(db_path)
    registry = V4PrincipalRegistry(store)
    if args.command == "legacy-import":
        token = _read_token_stdin()
        principal_id, device_ref, credential_id = registry.import_legacy(
            token, grant_compatibility_defaults=args.grant_defaults
        )
        print(json.dumps({"principal_id": principal_id, "device_ref": device_ref,
                          "credential_id": credential_id, "audience": "LEGACY", "status": "ACTIVE"}, sort_keys=True))
        return 0
    if args.command == "principal" and args.action == "add":
        principal_id, device_ref = registry.create_principal(
            principal_id=args.principal_id, kind=args.kind, device_ref=args.device_ref
        )
        print(json.dumps({"principal_id": principal_id, "device_ref": device_ref, "kind": args.kind}, sort_keys=True))
        return 0
    if args.command == "credential" and args.action in {"add", "rotate"}:
        token = _secret_for(args)
        if args.action == "add":
            credential_id = registry.add_credential(args.principal_id, token, audience=args.audience)
            principal_id, audience = args.principal_id, args.audience
        else:
            credential_id = registry.rotate_credential(args.credential_id, token)
            with store.connect() as connection:
                row = connection.execute(
                    "SELECT principal_id,audience FROM credentials_v4 WHERE credential_id=?", (credential_id,)
                ).fetchone()
            principal_id, audience = str(row["principal_id"]), str(row["audience"])
        print(json.dumps({"principal_id": principal_id, "credential_id": credential_id,
                          "audience": audience, "status": "ACTIVE"}, sort_keys=True))
        return 0
    if args.command == "credential" and args.action == "revoke":
        print(json.dumps({"revoked": registry.revoke_credential(args.credential_id)}, sort_keys=True))
        return 0
    if args.command == "principal" and args.action == "revoke":
        print(json.dumps({"revoked": registry.revoke_principal(args.principal_id)}, sort_keys=True))
        return 0
    if args.command == "binding" and args.action == "add":
        registry.add_binding(args.relay_device_id, args.principal_id)
        print(json.dumps({"relay_device_id": args.relay_device_id, "principal_id": args.principal_id,
                          "state": "ACTIVE"}, sort_keys=True))
        return 0
    if args.command == "binding" and args.action == "revoke":
        print(json.dumps({"revoked": registry.revoke_binding(args.relay_device_id)}, sort_keys=True))
        return 0
    if args.command == "capability" and args.action == "set":
        registry.set_capability(args.principal_id, args.capability, args.grant)
        print(json.dumps({"principal_id": args.principal_id, "capability": args.capability,
                          "granted": args.grant}, sort_keys=True))
        return 0
    if args.command == "list":
        print(json.dumps({"principals": registry.list_redacted()}, sort_keys=True))
        return 0
    if args.command == "verify-token":
        context = registry.resolve(_read_token_stdin())
        if context is None:
            print(json.dumps({"valid": False}, sort_keys=True))
            return 1
        print(json.dumps({"valid": True, "principal_id": context.principal_id,
                          "device_ref": context.device_ref, "credential_id": context.credential_id,
                          "audience": context.audience, "capabilities": sorted(context.capabilities)}, sort_keys=True))
        return 0
    raise PrincipalAuthError("commande inconnue")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except PrincipalAuthError as exc:
        print(json.dumps({"error": str(exc)}, sort_keys=True), file=sys.stderr)
        raise SystemExit(2)
