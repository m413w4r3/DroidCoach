#!/usr/bin/env python3
"""Stop only the Core, connector, and relay recorded by start_vm1_stack.py."""

from __future__ import annotations

import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


TASK_ID = "V7-06A-VM"
PRIVATE_ROOT = Path("/tmp/atlas-v7-06a-vm-private")
STATE_FILE = PRIVATE_ROOT / "stack-state.json"
EXPECTED_RELAY_IMAGE_ID = "sha256:75d68fd8bdb9d60a26203cd7c49c07c035f1f400c47740770056814e76846889"


def owned_process(pid: int, marker: str) -> bool:
    try:
        command = Path(f"/proc/{pid}/cmdline").read_bytes().replace(b"\x00", b" ").decode("utf-8", "replace")
    except FileNotFoundError:
        return False
    return marker in command


def stop_process(pid: int | None, marker: str, label: str) -> str:
    if pid is None:
        return f"{label}=not_started"
    if not owned_process(pid, marker):
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return f"{label}=already_stopped"
        raise RuntimeError(f"refusing to stop PID {pid}: process identity does not match {label}")
    os.kill(pid, signal.SIGINT)
    deadline = time.monotonic() + 8.0
    while time.monotonic() < deadline:
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return f"{label}=stopped_sigint"
        time.sleep(0.2)
    if not owned_process(pid, marker):
        return f"{label}=stopped"
    os.kill(pid, signal.SIGTERM)
    deadline = time.monotonic() + 5.0
    while time.monotonic() < deadline:
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return f"{label}=stopped_sigterm"
        time.sleep(0.2)
    raise RuntimeError(f"owned {label} process {pid} did not stop after SIGTERM")


def checked(command: list[str]) -> str:
    result = subprocess.run(command, text=True, capture_output=True, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"{Path(command[0]).name} exited with status {result.returncode}")
    return result.stdout.strip()


def stop_relay(name: str | None) -> str:
    if name is None:
        return "relay=not_started"
    if not name.startswith("atlas-v7-06a-relay-"):
        raise RuntimeError("refusing to stop a container outside the VM-1 name prefix")
    inspection = checked([
        "docker", "inspect", "--format", "{{.Config.Image}}|{{.Image}}", name,
    ])
    image_name, image_id = inspection.split("|", maxsplit=1)
    if image_name != "atlas-paseo-relay:r3a" or image_id != EXPECTED_RELAY_IMAGE_ID:
        raise RuntimeError("refusing to stop a container whose image does not match the VM-1 pin")
    checked(["docker", "stop", name])
    checked(["docker", "rm", name])
    return "relay=stopped_removed"


def main() -> int:
    if not STATE_FILE.is_file():
        raise RuntimeError("VM-1 stack state file is missing; no process will be inferred or stopped")
    state: dict[str, Any] = json.loads(STATE_FILE.read_text(encoding="utf-8"))
    if state.get("task_id") != TASK_ID:
        raise RuntimeError("stack state does not belong to V7-06A-VM")

    outcomes: list[str] = []
    failures: list[str] = []
    for label, action in (
        ("connector", lambda: stop_process(state.get("connector_pid"), "dist/main.js start", "connector")),
        ("relay", lambda: stop_relay(state.get("relay_container"))),
        ("core", lambda: stop_process(state.get("core_pid"), "atlas_backend.main:app", "core")),
    ):
        try:
            outcomes.append(action())
        except Exception as error:
            failures.append(f"{label}={type(error).__name__}: {str(error)[:160]}")
    if failures:
        print("FAIL: " + "; ".join(failures), file=sys.stderr)
        print("completed: " + " ".join(outcomes), file=sys.stderr)
        return 1
    print("PASS: " + " ".join(outcomes) + "; database and local state preserved")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"FAIL: {type(error).__name__}: {str(error)[:240]}", file=sys.stderr)
        sys.exit(1)
