#!/usr/bin/env python3
"""Offline dry-run/apply tool for V4 proposal expiration reconciliation."""

from __future__ import annotations

import argparse
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import stat
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from atlas_backend.store import _V4_MIGRATION_V5_STATEMENTS
from atlas_backend.v4_projection_store import expire_due_proposals


EXPECTED_VERSION = 5
MIGRATION_NAME = "v4_proposal_expiration_audit"
ACTIVE_STATES = ("PENDING_ANDROID", "RECEIVED", "AWAITING_USER")
AGE_BUCKETS = ("future", "under_1_day", "1_to_7_days", "over_7_days", "invalid")


class ReconcileError(ValueError):
    pass


def _timestamp(value: str | None) -> str:
    if value is None:
        parsed = datetime.now(timezone.utc)
    else:
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            raise ReconcileError("--now doit être un horodatage ISO-8601") from None
        if parsed.tzinfo is None:
            raise ReconcileError("--now doit inclure un fuseau horaire")
    return parsed.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _checked_db_path(value: str) -> Path:
    path = Path(value)
    if not path.is_absolute() or path.is_symlink() or not path.is_file():
        raise ReconcileError("--db doit désigner une base SQLite locale existante par chemin absolu")
    info = path.stat()
    if not stat.S_ISREG(info.st_mode):
        raise ReconcileError("--db doit désigner un fichier régulier")
    return path


def _connect_readonly(path: Path) -> sqlite3.Connection:
    connection = sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys=ON")
    connection.execute("PRAGMA query_only=ON")
    return connection


def _connect_writable(path: Path) -> sqlite3.Connection:
    connection = sqlite3.connect(path, timeout=5)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA busy_timeout=5000")
    connection.execute("PRAGMA foreign_keys=ON")
    return connection


def _require_v5(connection: sqlite3.Connection) -> None:
    version = int(connection.execute("PRAGMA user_version").fetchone()[0])
    migration = connection.execute(
        "SELECT name,checksum FROM schema_migrations WHERE version=?", (EXPECTED_VERSION,)
    ).fetchone()
    checksum = hashlib.sha256(
        (MIGRATION_NAME + "\n" + "\n".join(s.strip() for s in _V4_MIGRATION_V5_STATEMENTS)).encode("utf-8")
    ).hexdigest()
    if version != EXPECTED_VERSION or migration is None or migration["name"] != MIGRATION_NAME or migration["checksum"] != checksum:
        raise ReconcileError("la base doit avoir la migration V4 version 5 avec checksum conforme")
    tables = {
        row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")
    }
    if not {"agent_proposals_v4", "agent_proposal_events_v4", "agent_proposal_expiration_events_v4"} <= tables:
        raise ReconcileError("les tables de propositions V4 sont absentes")


def _integrity(connection: sqlite3.Connection) -> tuple[str, list[tuple]]:
    result = str(connection.execute("PRAGMA integrity_check").fetchone()[0])
    foreign_keys = [tuple(row) for row in connection.execute("PRAGMA foreign_key_check").fetchall()]
    if result != "ok" or foreign_keys:
        raise ReconcileError("les contrôles d’intégrité SQLite ont échoué")
    return result, foreign_keys


def _parse_utc(value: str) -> datetime | None:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.astimezone(timezone.utc)


def _summary(connection: sqlite3.Connection, now: str) -> dict[str, object]:
    now_dt = _parse_utc(now)
    assert now_dt is not None
    rows = connection.execute("SELECT proposal_id,state,expires_at FROM agent_proposals_v4").fetchall()
    state_counts: dict[str, int] = {}
    age_counts: dict[str, dict[str, int]] = {}
    candidates_by_state: dict[str, int] = {}
    due_ids: list[str] = []
    invalid_expiry = 0
    for row in rows:
        state = str(row["state"])
        state_counts[state] = state_counts.get(state, 0) + 1
        buckets = age_counts.setdefault(state, {bucket: 0 for bucket in AGE_BUCKETS})
        expires = _parse_utc(str(row["expires_at"]))
        if expires is None:
            buckets["invalid"] += 1
            invalid_expiry += 1
            continue
        delta = now_dt - expires
        if delta.total_seconds() < 0:
            bucket = "future"
        elif delta.total_seconds() < 86_400:
            bucket = "under_1_day"
        elif delta.total_seconds() < 7 * 86_400:
            bucket = "1_to_7_days"
        else:
            bucket = "over_7_days"
        buckets[bucket] += 1
        if state in ACTIVE_STATES and expires <= now_dt:
            candidates_by_state[state] = candidates_by_state.get(state, 0) + 1
            due_ids.append(str(row["proposal_id"]))

    ack_present = 0
    if due_ids:
        placeholders = ",".join("?" for _ in due_ids)
        ack_present = int(connection.execute(
            f"SELECT COUNT(DISTINCT proposal_id) FROM agent_proposal_events_v4 WHERE proposal_id IN ({placeholders})",
            due_ids,
        ).fetchone()[0])

    expired_without_audit = int(connection.execute(
        "SELECT COUNT(*) FROM agent_proposals_v4 p LEFT JOIN agent_proposal_expiration_events_v4 e "
        "ON e.proposal_id=p.proposal_id WHERE p.state='EXPIRED' AND e.proposal_id IS NULL"
    ).fetchone()[0])
    audit_without_expired = int(connection.execute(
        "SELECT COUNT(*) FROM agent_proposal_expiration_events_v4 e JOIN agent_proposals_v4 p "
        "ON p.proposal_id=e.proposal_id WHERE p.state!='EXPIRED' OR e.to_state!='EXPIRED'"
    ).fetchone()[0])
    duplicate_audits = int(connection.execute(
        "SELECT COUNT(*) FROM (SELECT proposal_id FROM agent_proposal_expiration_events_v4 "
        "GROUP BY proposal_id HAVING COUNT(*)>1)"
    ).fetchone()[0])
    orphan_audits = int(connection.execute(
        "SELECT COUNT(*) FROM agent_proposal_expiration_events_v4 e LEFT JOIN agent_proposals_v4 p "
        "ON p.proposal_id=e.proposal_id WHERE p.proposal_id IS NULL"
    ).fetchone()[0])
    due_active_with_terminal_ack = int(connection.execute(
        "SELECT COUNT(DISTINCT p.proposal_id) FROM agent_proposals_v4 p JOIN agent_proposal_events_v4 a "
        "ON a.proposal_id=p.proposal_id WHERE p.state IN ('PENDING_ANDROID','RECEIVED','AWAITING_USER') "
        "AND p.expires_at<=? AND a.state IN ('APPLIED','REJECTED')", (now,),
    ).fetchone()[0])

    return {
        "proposal_count": len(rows),
        "by_state": dict(sorted(state_counts.items())),
        "by_state_and_expiration_age": {key: age_counts[key] for key in sorted(age_counts)},
        "candidate_count": len(due_ids),
        "candidates_by_state": dict(sorted(candidates_by_state.items())),
        "candidates_with_ack": ack_present,
        "anomalies": {
            "invalid_expires_at": invalid_expiry,
            "expired_without_server_audit": expired_without_audit,
            "audit_without_expired_state": audit_without_expired,
            "duplicate_audit_proposals": duplicate_audits,
            "orphan_audit_rows": orphan_audits,
            "due_active_with_applied_or_rejected_ack": due_active_with_terminal_ack,
        },
    }


def _backup(path: Path, backup_path: Path) -> dict[str, object]:
    if not backup_path.is_absolute() or backup_path.exists() or backup_path.is_symlink():
        raise ReconcileError("--backup doit être un chemin absolu inexistant")
    try:
        parent = backup_path.parent.lstat()
    except OSError:
        raise ReconcileError("répertoire de sauvegarde indisponible") from None
    if stat.S_ISLNK(parent.st_mode) or not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.getuid():
        raise ReconcileError("répertoire de sauvegarde non sûr")
    flags = os.O_CREAT | os.O_EXCL | os.O_RDWR
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        fd = os.open(backup_path, flags, 0o600)
    except OSError:
        raise ReconcileError("création exclusive de la sauvegarde refusée") from None
    os.close(fd)
    try:
        with closing(_connect_readonly(path)) as source, closing(sqlite3.connect(backup_path)) as destination:
            source.backup(destination)
            destination.execute("PRAGMA foreign_keys=ON")
            result = str(destination.execute("PRAGMA integrity_check").fetchone()[0])
            foreign_keys = [tuple(row) for row in destination.execute("PRAGMA foreign_key_check").fetchall()]
            user_version = int(destination.execute("PRAGMA user_version").fetchone()[0])
            if result != "ok" or foreign_keys or user_version != EXPECTED_VERSION:
                raise ReconcileError("la sauvegarde SQLite ne passe pas les contrôles requis")
        size = backup_path.stat().st_size
        if size <= 0:
            raise ReconcileError("la sauvegarde SQLite est vide")
        with backup_path.open("r+b") as backup_file:
            backup_file.flush()
            os.fsync(backup_file.fileno())
            backup_file.seek(0)
            digest = hashlib.file_digest(backup_file, "sha256").hexdigest()
        return {
            "size_bytes": size,
            "sha256": digest,
            "integrity_check": result,
            "foreign_key_check": foreign_keys,
            "user_version": user_version,
        }
    except Exception:
        backup_path.unlink(missing_ok=True)
        raise


def _run_apply(path: Path, backup_path: Path, now: str, batch_size: int) -> dict[str, object]:
    with closing(_connect_writable(path)) as connection:
        _require_v5(connection)
        _integrity(connection)
        before = _summary(connection, now)
    backup_info = _backup(path, backup_path)
    batches = 0
    while True:
        with closing(_connect_writable(path)) as connection:
            _require_v5(connection)
            connection.execute("BEGIN IMMEDIATE")
            changed = expire_due_proposals(
                connection, now=now, batch_size=batch_size, source="RECONCILIATION"
            )
            connection.commit()
        if changed == 0:
            break
        batches += 1
    with closing(_connect_readonly(path)) as connection:
        _require_v5(connection)
        _integrity(connection)
        after = _summary(connection, now)
    return {"mode": "apply", "now": now, "before": before, "after": after,
            "batches": batches, "backup": backup_info}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", required=True, help="chemin absolu vers une copie SQLite V4 locale")
    parser.add_argument("--now", help="horodatage ISO-8601 pour une analyse reproductible")
    parser.add_argument("--apply", action="store_true", help="appliquer les transitions après sauvegarde vérifiée")
    parser.add_argument("--backup", help="chemin absolu inexistant pour la sauvegarde préalable")
    parser.add_argument("--batch-size", type=int, default=100, help="taille par transaction (1..500)")
    args = parser.parse_args()
    try:
        if not 1 <= args.batch_size <= 500:
            raise ReconcileError("--batch-size doit être compris entre 1 et 500")
        db_path = _checked_db_path(args.db)
        now = _timestamp(args.now)
        if args.apply:
            if not args.backup:
                raise ReconcileError("--backup est obligatoire avec --apply")
            backup_path = Path(args.backup)
            result = _run_apply(db_path, backup_path, now, args.batch_size)
        else:
            if args.backup:
                raise ReconcileError("--backup s’utilise uniquement avec --apply")
            with closing(_connect_readonly(db_path)) as connection:
                _require_v5(connection)
                _integrity(connection)
                result = {"mode": "dry_run", "now": now, "before": _summary(connection, now)}
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 0
    except (OSError, sqlite3.Error, ReconcileError) as exc:
        print(f"expiration reconciliation refused: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
