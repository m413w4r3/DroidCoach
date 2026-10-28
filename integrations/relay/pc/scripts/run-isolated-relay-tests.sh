#!/usr/bin/env bash
# Runs the full connector test suite, including the real-relay integration test,
# against a pinned local relay container. Loopback only; never publishes a port.
#
# The image is verified by immutable image ID, not by a mutable tag. Override
# ATLAS_RELAY_IMAGE_ID only after independently rebuilding and reviewing the
# pinned upstream artifact; a mismatch is BLOCKED, not a silent fallback.
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../../../.." && pwd)
RELAY_PORT="${ATLAS_TEST_RELAY_PORT:-4402}"
IMAGE="${ATLAS_RELAY_IMAGE:-atlas-paseo-relay:r3a}"
EXPECTED_IMAGE_ID="${ATLAS_RELAY_IMAGE_ID:-sha256:75d68fd8bdb9d60a26203cd7c49c07c035f1f400c47740770056814e76846889}"
# Unique per invocation: no other process can own this name, and cleanup only
# targets the container ID captured below.
CONTAINER="atlas-r3c-test-relay-$(date +%s)-$$"
CREATED_ID=""

cleanup() {
  if [ -n "$CREATED_ID" ]; then
    docker rm -f "$CREATED_ID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

if ! command -v docker >/dev/null 2>&1; then
  echo "BLOCKED: docker is required for the isolated real-relay test" >&2
  exit 2
fi
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "BLOCKED: pinned relay image $IMAGE is not present locally" >&2
  exit 2
fi
ACTUAL_IMAGE_ID=$(docker image inspect --format '{{.Id}}' "$IMAGE")
if [ "$ACTUAL_IMAGE_ID" != "$EXPECTED_IMAGE_ID" ]; then
  echo "BLOCKED: relay image ID mismatch (expected $EXPECTED_IMAGE_ID, got $ACTUAL_IMAGE_ID)" >&2
  exit 2
fi
if docker inspect "$CONTAINER" >/dev/null 2>&1; then
  echo "BLOCKED: unique container name $CONTAINER already exists" >&2
  exit 2
fi

# The pinned start script owns port validation and readiness.
if ! ATLAS_RELAY_IMAGE="$IMAGE" ATLAS_RELAY_CONTAINER="$CONTAINER" ATLAS_RELAY_PORT="$RELAY_PORT" \
  "$REPO_ROOT/scripts/r3a/start-relay.sh"; then
  CREATED_ID=$(docker inspect --format '{{.Id}}' "$CONTAINER" 2>/dev/null || true)
  exit 1
fi

CREATED_ID=$(docker inspect --format '{{.Id}}' "$CONTAINER")
RUNNING_IMAGE_ID=$(docker inspect --format '{{.Image}}' "$CREATED_ID")
if [ "$RUNNING_IMAGE_ID" != "$EXPECTED_IMAGE_ID" ]; then
  echo "BLOCKED: running relay image ID mismatch (expected $EXPECTED_IMAGE_ID, got $RUNNING_IMAGE_ID)" >&2
  exit 2
fi

cd "$SCRIPT_DIR/.."
ATLAS_TEST_RELAY_URL="ws://127.0.0.1:${RELAY_PORT}" npm test
