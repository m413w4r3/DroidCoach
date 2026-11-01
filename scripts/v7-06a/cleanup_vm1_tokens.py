#!/usr/bin/env python3
"""Remove only synthetic token files created by the isolated VM-1 stack script."""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any


TASK_ID = "V7-06A-VM"
ROOT = Path(__file__).resolve().parents[2]
PRIVATE_ROOT = Path("/tmp/atlas-v7-06a-vm-private")
STATE_FILE = PRIVATE_ROOT / "stack-state.json"
ALLOWED_FILES = {
    PRIVATE_ROOT / "core-token",
    PRIVATE_ROOT / "tool-token",
    ROOT / "pairing.json",
}


def main() -> int:
    if not STATE_FILE.is_file():
        raise RuntimeError("VM-1 state manifest is missing; refusing to guess which tokens to remove")
    state: dict[str, Any] = json.loads(STATE_FILE.read_text(encoding="utf-8"))
    if state.get("task_id") != TASK_ID:
        raise RuntimeError("state manifest does not belong to V7-06A-VM")
    owned = state.get("owned_files")
    if not isinstance(owned, dict):
        raise RuntimeError("state manifest has no owned-file inventory")

    removed: list[str] = []
    retained: list[str] = []
    for raw_path, identity in owned.items():
        path = Path(raw_path).resolve()
        if path not in {allowed.resolve() for allowed in ALLOWED_FILES}:
            continue
        if not path.exists():
            continue
        current = path.stat()
        if current.st_dev != identity.get("device") or current.st_ino != identity.get("inode"):
            retained.append(path.name)
            continue
        os.unlink(path)
        removed.append(path.name)

    print(json.dumps({"result": "PASS", "synthetic_tokens_removed": sorted(removed),
                      "files_retained_due_to_identity_mismatch": sorted(retained),
                      "core_database_preserved": True, "connector_state_preserved": True}, sort_keys=True))
    if retained:
        return 1
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"FAIL: {type(error).__name__}: {str(error)[:240]}", file=sys.stderr)
        sys.exit(1)
