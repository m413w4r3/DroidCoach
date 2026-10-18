#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd -- "$SCRIPT_DIR/../android" && pwd)"

cd "$ANDROID_DIR"
./gradlew :app:assembleDebug
printf 'APK: %s\n' "$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
