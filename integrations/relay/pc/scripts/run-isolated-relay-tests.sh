#!/usr/bin/env bash
# Runs the full connector test suite, including the real-relay integration test,
# against a pinned local relay container. Loopback only; never publishes a port.
#
# Requirements: Docker image atlas-paseo-relay:r3a (pinned in scripts/r3a/start-relay.sh)
# and a free loopback port (default 4402).
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../../../.." && pwd)
RELAY_PORT="${ATLAS_TEST_RELAY_PORT:-4402}"
CONTAINER="${ATLAS_TEST_RELAY_CONTAINER:-atlas-r3c-test-relay}"
IMAGE="${ATLAS_RELAY_IMAGE:-atlas-paseo-relay:r3a}"

if ! command -v docker >/dev/null 2>&1; then
  echo "BLOCKED: docker is required for the isolated real-relay test" >&2
  exit 2
fi
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "BLOCKED: pinned relay image $IMAGE is not present locally" >&2
  exit 2
fi
if docker inspect "$CONTAINER" >/dev/null 2>&1; then
  echo "BLOCKED: container $CONTAINER already exists; remove it explicitly first" >&2
  exit 2
fi

cleanup() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

# The pinned start script owns image/port validation and readiness.
ATLAS_RELAY_IMAGE="$IMAGE" ATLAS_RELAY_CONTAINER="$CONTAINER" ATLAS_RELAY_PORT="$RELAY_PORT" \
  "$REPO_ROOT/scripts/r3a/start-relay.sh"

cd "$SCRIPT_DIR/.."
ATLAS_TEST_RELAY_URL="ws://127.0.0.1:${RELAY_PORT}" npm test
