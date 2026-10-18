#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/android"
SDK_ROOT="${ANDROID_HOME:-$HOME/Android/Sdk}"
SERIAL="${ANDROID_SERIAL:-emulator-5556}"
ADB="$SDK_ROOT/platform-tools/adb"
AAPT="$SDK_ROOT/build-tools/37.0.0/aapt"
APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
RUNNER="dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner"

if [[ ! -x "$ADB" || ! -x "$AAPT" ]]; then
  printf 'BLOCKED: adb/aapt Android 17 toolchain not found under %s\n' "$SDK_ROOT" >&2
  exit 77
fi

cd "$ANDROID_DIR"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --offline

if ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
  printf 'BLOCKED: expected already-running emulator %s is unavailable. This script does not start or stop an AVD.\n' "$SERIAL" >&2
  exit 77
fi

PERMISSIONS="$("$AAPT" dump permissions "$APK")"
if ! rg -q 'android.permission.INTERNET' <<<"$PERMISSIONS"; then
  printf 'BLOCKED: debug APK does not declare INTERNET. Apply the manifest integration diff in docs/v4/lots/F.md, rebuild, then rerun.\n' >&2
  exit 77
fi

MANIFEST_TREE="$("$AAPT" dump xmltree "$APK" AndroidManifest.xml)"
if ! rg -q 'usesCleartextTraffic.*(0xffffffff|true)|networkSecurityConfig' <<<"$MANIFEST_TREE"; then
  printf 'BLOCKED: debug APK does not allow the emulator HTTP test endpoint. Apply the debug-only network config diff in docs/v4/lots/F.md, rebuild, then rerun.\n' >&2
  exit 77
fi

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/atlas-backend-e2e.XXXXXX")"
SERVER_PID=""
ATLAS_E2E_TOKEN="$(/tmp/v4/venv/bin/python -c 'import secrets; print(secrets.token_urlsafe(32))')"
ATLAS_E2E_PORT="$(/tmp/v4/venv/bin/python -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
export ATLAS_E2E_TOKEN ATLAS_E2E_PORT

redact_log() {
  /tmp/v4/venv/bin/python -c 'import os,sys; print(sys.stdin.read().replace(os.environ["ATLAS_E2E_TOKEN"], "[redacted]"), end="")' < "$1"
}

stop_backend() {
  if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  SERVER_PID=""
}

cleanup() {
  stop_backend
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

start_backend() {
  ATLAS_TOKEN="$ATLAS_E2E_TOKEN" \
  ATLAS_DB_PATH="$WORK_DIR/atlas.sqlite3" \
  PYTHONPATH="$ROOT/backend" \
    /tmp/v4/venv/bin/python -m uvicorn atlas_backend.main:app \
      --host 0.0.0.0 --port "$ATLAS_E2E_PORT" --no-access-log \
      >"$WORK_DIR/backend.log" 2>&1 &
  SERVER_PID=$!
  for _ in $(seq 1 50); do
    if /tmp/v4/venv/bin/python -c 'import os,urllib.request; urllib.request.urlopen("http://127.0.0.1:"+os.environ["ATLAS_E2E_PORT"]+"/health", timeout=.3).read()' >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.2
  done
  redact_log "$WORK_DIR/backend.log" >&2
  printf 'FAIL: local backend did not become healthy.\n' >&2
  return 1
}

run_android_scenario() {
  local scenario="$1"
  local output="$WORK_DIR/instrument-$scenario.log"
  if ! "$ADB" -s "$SERIAL" shell am instrument -w \
      -e class dev.atlas.coach.sync.RemoteBackendE2eInstrumentationTest \
      -e scenario "$scenario" \
      -e backend_url "http://10.0.2.2:$ATLAS_E2E_PORT" \
      -e backend_token "$ATLAS_E2E_TOKEN" \
      "$RUNNER" >"$output" 2>&1; then
    redact_log "$output" >&2
    return 1
  fi
  if rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED' "$output"; then
    redact_log "$output" >&2
    return 1
  fi
  printf 'PASS: Android scenario %s completed.\n' "$scenario"
}

"$ADB" -s "$SERIAL" install -r "$APK" >/dev/null
start_backend
run_android_scenario online

stop_backend
run_android_scenario offline

start_backend
sleep 2
run_android_scenario resume

/tmp/v4/venv/bin/python - <<'PY'
import json
import os
import urllib.request

request = urllib.request.Request(
    f"http://127.0.0.1:{os.environ['ATLAS_E2E_PORT']}/v1/export",
    headers={"Authorization": f"Bearer {os.environ['ATLAS_E2E_TOKEN']}"},
)
with urllib.request.urlopen(request, timeout=3) as response:
    events = json.load(response)["events"]
ids = [event["event_id"] for event in events]
assert len(events) == 2, f"expected two unique online/offline events, received {len(events)}"
assert len(set(ids)) == len(ids), "backend contains duplicate event UUIDs"
print("PASS: backend export contains two unique acknowledged event UUIDs.")
PY
