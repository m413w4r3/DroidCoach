#!/usr/bin/env bash
# Starts the pinned upstream Paseo Relay locally on loopback only.
# This script never publishes a public port and never touches another container.
set -euo pipefail

IMAGE="${ATLAS_RELAY_IMAGE:-atlas-paseo-relay:r3a}"
NAME="${ATLAS_RELAY_CONTAINER:-atlas-r3a-relay}"
PORT="${ATLAS_RELAY_PORT:-4400}"

if docker inspect "$NAME" >/dev/null 2>&1; then
  state="$(docker inspect -f '{{.State.Running}}' "$NAME")"
  if [ "$state" = "true" ]; then
    echo "relay already running: $NAME on 127.0.0.1:$PORT"
    exit 0
  fi
  docker rm -f "$NAME" >/dev/null
fi

docker run -d --rm --name "$NAME" \
  -p "127.0.0.1:${PORT}:4000" \
  -e PASEO_RELAY_HOST=0.0.0.0 \
  -e PASEO_RELAY_PORT=4000 \
  "$IMAGE" >/dev/null

for _ in $(seq 1 40); do
  if curl -fsS "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1; then
    echo "relay ready: 127.0.0.1:${PORT}"
    exit 0
  fi
  sleep 0.25
done

echo "relay did not become ready" >&2
docker logs "$NAME" 2>&1 | tail -20 >&2 || true
exit 1
