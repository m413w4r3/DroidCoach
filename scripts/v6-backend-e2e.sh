#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="${PYTHON:-python3}"
export PYTHONPATH="$ROOT/backend${PYTHONPATH:+:$PYTHONPATH}"

if [[ "${ATLAS_REAL_GATEWAY:-0}" == "1" ]]; then
  TOKEN_FILE="${OPENCLAW_GATEWAY_TOKEN_FILE:-$HOME/.config/atlas/openclaw-gateway-token}"
  if [[ ! -f "$TOKEN_FILE" ]]; then
    printf 'BLOCKED: Gateway token file is missing; provide a mode-600 file outside the repository.\n' >&2
    exit 77
  fi
  MODE="$(stat -c '%a' "$TOKEN_FILE")"
  if [[ "$MODE" != "600" ]]; then
    printf 'BLOCKED: Gateway token file permissions must be 600.\n' >&2
    exit 77
  fi
  export OPENCLAW_GATEWAY_TOKEN_FILE="$TOKEN_FILE"
  "$PYTHON_BIN" -m pytest -c "$ROOT/backend/pytest.ini" "$ROOT/backend/tests" -q -m real_gateway
else
  "$PYTHON_BIN" -m pytest -c "$ROOT/backend/pytest.ini" "$ROOT/backend/tests" -q -m 'not real_gateway'
fi
