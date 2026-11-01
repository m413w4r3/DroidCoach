#!/usr/bin/env python3
"""Read-only oracle for the isolated V7-06A VM-1 Core database."""

from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
import sys
from pathlib import Path


EXPECTED_CONSENTS = {
    "DAY_PLAN": [(2, "GRANTED"), (3, "REVOKED")],
    "ACTIVITY_STATUS": [(2, "GRANTED")],
}


def fail(message: str) -> None:
    raise SystemExit(f"FAIL: {message}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, default=Path("/tmp/atlas-v7-06a-vm/core.sqlite3"))
    parser.add_argument("--android-token-file", type=Path, default=Path("/tmp/atlas-v7-06a-vm/core-token"))
    args = parser.parse_args()

    if not args.db.is_file() or not args.android_token_file.is_file():
        fail("isolated database or synthetic Core token file is missing")

    token = args.android_token_file.read_text(encoding="utf-8").strip()
    if not token:
        fail("synthetic Core token file is empty")
    expected_device_ref = "android-" + hashlib.sha256(token.encode("utf-8")).hexdigest()[:32]
    del token

    uri = f"file:{args.db.resolve()}?mode=ro"
    try:
        connection = sqlite3.connect(uri, uri=True)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA query_only=ON")
    except sqlite3.Error as error:
        fail(f"could not open isolated Core database read-only: {error.__class__.__name__}")

    try:
        states = connection.execute(
            "SELECT category, consent_version, state, source "
            "FROM consent_states_v4 WHERE device_ref=? ORDER BY category",
            (expected_device_ref,),
        ).fetchall()
        state_by_category = {row["category"]: row for row in states}
        if set(state_by_category) != set(EXPECTED_CONSENTS):
            fail("consent state categories differ from the two VM-1 categories")

        for category, expected_events in EXPECTED_CONSENTS.items():
            state = state_by_category[category]
            expected_version, expected_state = expected_events[-1]
            if (state["consent_version"], state["state"], state["source"]) != (
                expected_version,
                expected_state,
                "ANDROID_SYNC",
            ):
                fail(f"final {category} state/version/source does not match VM-1 oracle")

            events = connection.execute(
                "SELECT consent_version, state, source FROM consent_state_events_v4 "
                "WHERE device_ref=? AND category=? ORDER BY consent_version",
                (expected_device_ref, category),
            ).fetchall()
            actual = [(row["consent_version"], row["state"]) for row in events]
            if actual != expected_events:
                fail(f"{category} consent event sequence contains a missing or duplicate effect")
            if any(row["source"] != "ANDROID_SYNC" for row in events):
                fail(f"{category} consent event provenance is not ANDROID_SYNC")

        event_count = connection.execute(
            "SELECT COUNT(*) FROM consent_state_events_v4 WHERE device_ref=? AND category IN (?,?)",
            (expected_device_ref, "DAY_PLAN", "ACTIVITY_STATUS"),
        ).fetchone()[0]
        if event_count != 3:
            fail("consent event count is not exactly three")

        snapshots = connection.execute(
            "SELECT local_date, timezone, plan_version, revision_id, schema_version, consent_version, projection_json "
            "FROM day_plan_snapshots_v4 WHERE device_ref=?",
            (expected_device_ref,),
        ).fetchall()
        if len(snapshots) != 1:
            fail("expected exactly one approved DayPlan snapshot for the synthetic device")
        snapshot = snapshots[0]
        projection = json.loads(snapshot["projection_json"])
        if (
            snapshot["plan_version"] != 2
            or snapshot["consent_version"] != 2
            or projection.get("version") != 2
            or projection.get("state") != "APPROVED"
            or projection.get("origin") != "LOCAL"
            or projection.get("local_date") != snapshot["local_date"]
            or projection.get("timezone") != snapshot["timezone"]
            or projection.get("current_revision_id") != snapshot["revision_id"]
        ):
            fail("DayPlan snapshot version, consent version, or provenance fields are inconsistent")
        activities = projection.get("activities")
        if not isinstance(activities, list) or len(activities) != 1 or activities[0].get("kind") != "FOCUS":
            fail("DayPlan snapshot does not contain exactly one synthetic focus activity")

        duplicate_key_groups = connection.execute(
            "SELECT COUNT(*) FROM (SELECT idempotency_key FROM consent_state_events_v4 "
            "WHERE device_ref=? AND category IN (?,?) GROUP BY idempotency_key HAVING COUNT(*) > 1)",
            (expected_device_ref, "DAY_PLAN", "ACTIVITY_STATUS"),
        ).fetchone()[0]
        if duplicate_key_groups != 0:
            fail("duplicate consent idempotency keys were found")

        quarantine_count = connection.execute(
            "SELECT COUNT(*) FROM v4_quarantine WHERE device_ref=?", (expected_device_ref,)
        ).fetchone()[0]
        if quarantine_count != 0:
            fail("Core quarantined one or more VM-1 records")

        print(
            json.dumps(
                {
                    "result": "PASS",
                    "database_mode": "read_only",
                    "device_ref": "synthetic_android_token_derived",
                    "consent_states": {
                        category: {
                            "state": state_by_category[category]["state"],
                            "version": state_by_category[category]["consent_version"],
                            "source": state_by_category[category]["source"],
                            "event_sequence": [
                                {"version": version, "state": state}
                                for version, state in EXPECTED_CONSENTS[category]
                            ],
                        }
                        for category in sorted(EXPECTED_CONSENTS)
                    },
                    "consent_event_count": event_count,
                    "duplicate_idempotency_groups": duplicate_key_groups,
                    "day_plan_snapshot_count": len(snapshots),
                    "day_plan_version": snapshot["plan_version"],
                    "day_plan_consent_version": snapshot["consent_version"],
                    "consent_provenance_source": "ANDROID_SYNC",
                    "quarantine_count": quarantine_count,
                },
                sort_keys=True,
            )
        )
        return 0
    except (sqlite3.Error, json.JSONDecodeError) as error:
        fail(f"read-only Core oracle failed: {error.__class__.__name__}")
    finally:
        connection.close()


if __name__ == "__main__":
    sys.exit(main())
