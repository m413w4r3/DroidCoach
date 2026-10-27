#!/usr/bin/env bash
# Read-only status of the R3-A local stack. No secret is printed.
set -euo pipefail

PORT="${ATLAS_RELAY_PORT:-4400}"
BACKEND_PORT="${ATLAS_BACKEND_PORT:-8100}"

echo "relay:"
if curl -fsS "http://127.0.0.1:${PORT}/health" 2>/dev/null; then
  echo
else
  echo "unavailable"
fi

echo "backend:"
if curl -fsS "http://127.0.0.1:${BACKEND_PORT}/health" 2>/dev/null; then
  echo
else
  echo "unavailable"
fi

echo "connector metrics:"
curl -fsS "http://127.0.0.1:4401/metrics" 2>/dev/null || echo "unavailable"
