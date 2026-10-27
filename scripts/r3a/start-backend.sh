#!/usr/bin/env bash
# Starts the ATLAS FastAPI backend on loopback with a synthetic service token.
# The token is read from a 0600 file outside Git; it is never printed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SECRET_DIR="${ATLAS_SECRET_DIR:-$HOME/.local/share/atlas-v63-r3a/secrets}"
TOKEN_FILE="$SECRET_DIR/backend-service-token"
PORT="${ATLAS_BACKEND_PORT:-8100}"

if [ ! -f "$TOKEN_FILE" ]; then
  mkdir -p "$SECRET_DIR"
  umask 077
  openssl rand -hex 32 > "$TOKEN_FILE"
  chmod 600 "$TOKEN_FILE"
fi

if [ "$(stat -c '%a' "$TOKEN_FILE")" != "600" ]; then
  echo "token file must be 0600: $TOKEN_FILE" >&2
  exit 1
fi

export ATLAS_TOKEN="$(cat "$TOKEN_FILE")"
export ATLAS_CAPABILITIES_JSON="${ATLAS_CAPABILITIES_JSON:-{\"plan_revision\":true}}"

cd "$ROOT/backend"
exec "$ROOT/backend/.venv/bin/python" -m uvicorn atlas_backend.main:app \
  --host 127.0.0.1 --port "$PORT" --log-level info
