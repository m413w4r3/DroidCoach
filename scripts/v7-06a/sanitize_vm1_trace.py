#!/usr/bin/env python3
"""Extract only bounded Android V4 and connector diagnostics from raw logs."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


ANDROID_FIELDS = (
    "seq",
    "elapsedMs",
    "workerOrdinal",
    "executionGeneration",
    "workKind",
    "runAttempt",
    "category",
    "consentVersion",
    "state",
    "stage",
    "result",
    "ackResult",
    "durationMs",
    "sessionGeneration",
    "correlationTag",
)
CONNECTOR_FIELDS = (
    "timestamp",
    "sessionOrdinal",
    "correlationTag",
    "operation",
    "stage",
    "result",
    "httpStatus",
    "resultCode",
    "durationMs",
)
ANDROID_TAG = "ATLAS_V4_DIAG"


def read_lines(path: Path | None) -> list[str]:
    if path is None:
        return []
    if not path.is_file():
        raise SystemExit(f"FAIL: input log not found: {path}")
    return path.read_text(encoding="utf-8", errors="replace").splitlines()


def sanitize_android(lines: list[str]) -> list[dict[str, Any]]:
    output: list[dict[str, Any]] = []
    for line in lines:
        marker = line.find(ANDROID_TAG)
        if marker < 0:
            continue
        payload = line.find("seq=", marker)
        if payload < 0:
            continue
        fields: dict[str, Any] = {}
        for part in line[payload:].split():
            key, separator, value = part.partition("=")
            if separator and key in ANDROID_FIELDS:
                fields[key] = value
        if all(name in fields for name in ("seq", "category", "stage", "result")):
            output.append(fields)
    return output


def sanitize_connector(lines: list[str]) -> list[dict[str, Any]]:
    output: list[dict[str, Any]] = []
    for line in lines:
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not isinstance(event, dict) or not str(event.get("operation", "")).startswith("v4."):
            continue
        output.append({key: event[key] for key in CONNECTOR_FIELDS if key in event})
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-log", type=Path)
    parser.add_argument("--connector-stderr", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    android = sanitize_android(read_lines(args.android_log))
    connector = sanitize_connector(read_lines(args.connector_stderr))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as output:
        output.write("# Sanitized VM-1 diagnostics; source lines and payloads omitted.\n")
        output.write(f"android_events={len(android)} connector_events={len(connector)}\n")
        for event in android:
            output.write("android " + json.dumps(event, sort_keys=True, separators=(",", ":")) + "\n")
        for event in connector:
            output.write("connector " + json.dumps(event, sort_keys=True, separators=(",", ":")) + "\n")
    print(f"PASS: android_events={len(android)} connector_events={len(connector)} sanitized_output={args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
