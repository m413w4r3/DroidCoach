#!/usr/bin/env python3
"""Wait up to five minutes for the one authorized V7-06A AVD to finish booting."""

from __future__ import annotations

import subprocess
import sys
import time


SERIAL = "emulator-5580"
TIMEOUT_SECONDS = 300
COMMAND_TIMEOUT_SECONDS = 10
POLL_INTERVAL_SECONDS = 5
CHECK = (
    "printf 'boot='; getprop sys.boot_completed; "
    "service check activity; service check package"
)


def main() -> int:
    deadline = time.monotonic() + TIMEOUT_SECONDS
    attempts = 0
    latest = "adb_unavailable"
    while time.monotonic() < deadline:
        attempts += 1
        try:
            result = subprocess.run(
                ["adb", "-s", SERIAL, "shell", "sh", "-c", CHECK],
                text=True,
                capture_output=True,
                timeout=COMMAND_TIMEOUT_SECONDS,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired):
            latest = "adb_unavailable_or_timed_out"
        else:
            lines = result.stdout.splitlines()
            latest = ";".join(line.strip() for line in lines if line.strip())[:240]
            if (
                result.returncode == 0
                and "boot=1" in lines
                and any("Service activity: found" in line for line in lines)
                and any("Service package: found" in line for line in lines)
            ):
                elapsed = int(TIMEOUT_SECONDS - max(0, deadline - time.monotonic()))
                print(f"PASS: boot_completed=1 activity=found package=found attempts={attempts} elapsed_s={elapsed}")
                return 0
        if attempts % 6 == 0:
            print(f"WAIT: elapsed_s={int(time.monotonic() - (deadline - TIMEOUT_SECONDS))} latest={latest}", flush=True)
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(POLL_INTERVAL_SECONDS, remaining))

    print(f"BLOCKED_ENVIRONMENT: AVD boot incomplete after {TIMEOUT_SECONDS}s; attempts={attempts}; latest={latest}")
    return 2


if __name__ == "__main__":
    sys.exit(main())
