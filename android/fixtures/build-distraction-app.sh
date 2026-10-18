#!/usr/bin/env bash
set -euo pipefail

FIXTURE_DIR="$(cd "$(dirname "$0")/distraction-app" && pwd)"
ANDROID_DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec "$ANDROID_DIR/gradlew" -p "$FIXTURE_DIR" :app:assembleDebug "$@"
