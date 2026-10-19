#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/android"
SDK_ROOT="${ANDROID_HOME:-$HOME/Android/Sdk}"
SERIAL="${ANDROID_SERIAL:-emulator-5556}"
export ANDROID_SERIAL="$SERIAL"
ADB="$SDK_ROOT/platform-tools/adb"
AAPT="$SDK_ROOT/build-tools/37.0.0/aapt"
APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
RUNNER="dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner"
OUTPUT_DIR="${ATLAS_E2E_OUTPUT_DIR:-$ROOT/docs/v4/logs/H2-$(date -u +%Y%m%dT%H%M%SZ)}"

if [[ ! -x "$ADB" || ! -x "$AAPT" ]]; then
  printf 'BLOCKED: adb/aapt Android 17 toolchain not found under %s\n' "$SDK_ROOT" >&2
  exit 77
fi
if [[ ! -x /tmp/v4/venv/bin/python ]]; then
  printf 'BLOCKED: expected backend venv /tmp/v4/venv is unavailable.\n' >&2
  exit 77
fi

mkdir -p "$OUTPUT_DIR" "$ROOT/docs/v4/captures"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/atlas-backend-e2e.XXXXXX")"
SERVER_PID=""
ATLAS_E2E_TOKEN="$(/tmp/v4/venv/bin/python -c 'import secrets; print(secrets.token_urlsafe(32))')"
ATLAS_E2E_PORT="$(/tmp/v4/venv/bin/python -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
export ATLAS_E2E_TOKEN ATLAS_E2E_PORT
export ATLAS_DB_PATH="$WORK_DIR/atlas.sqlite3"
SERVER_LOG="$OUTPUT_DIR/backend-access.log"

redact_file() {
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
  ATLAS_DB_PATH="$ATLAS_DB_PATH" \
  PYTHONPATH="$ROOT/backend" \
    /tmp/v4/venv/bin/python -m uvicorn atlas_backend.main:app \
      --host 0.0.0.0 --port "$ATLAS_E2E_PORT" --access-log --log-level info \
      >>"$SERVER_LOG" 2>&1 &
  SERVER_PID=$!
  for _ in $(seq 1 50); do
    if /tmp/v4/venv/bin/python -c 'import os,urllib.request; urllib.request.urlopen("http://127.0.0.1:"+os.environ["ATLAS_E2E_PORT"]+"/health", timeout=.3).read()' >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.2
  done
  printf 'FAIL: backend local did not become healthy.\n' >&2
  tail -80 "$SERVER_LOG" >&2
  return 1
}

sync_request_count() {
  local count
  count="$(rg -c '"POST /v1/sync HTTP/1.1"' "$SERVER_LOG" || true)"
  printf '%s' "${count:-0}"
}

backend_api_request_count() {
  local count
  count="$(rg -c '"(GET|POST) /v1/' "$SERVER_LOG" || true)"
  printf '%s' "${count:-0}"
}

run_android_scenario() {
  local scenario="$1"
  local raw="$WORK_DIR/instrument-$scenario.raw.log"
  if ! "$ADB" -s "$SERIAL" shell am instrument -w \
      -e class dev.atlas.coach.sync.RemoteBackendE2eInstrumentationTest \
      -e scenario "$scenario" \
      -e backend_url "http://10.0.2.2:$ATLAS_E2E_PORT" \
      -e backend_token "$ATLAS_E2E_TOKEN" \
      "$RUNNER" >"$raw" 2>&1; then
    redact_file "$raw" > "$OUTPUT_DIR/instrument-$scenario.log"
    tail -120 "$OUTPUT_DIR/instrument-$scenario.log" >&2
    return 1
  fi
  redact_file "$raw" > "$OUTPUT_DIR/instrument-$scenario.log"
  if rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED' "$OUTPUT_DIR/instrument-$scenario.log"; then
    tail -120 "$OUTPUT_DIR/instrument-$scenario.log" >&2
    return 1
  fi
  printf 'PASS: Android backend scenario %s.\n' "$scenario"
}

run_capture() {
  local state="$1"
  local raw="$WORK_DIR/capture-$state.raw.log"
  if ! "$ADB" -s "$SERIAL" shell am instrument -w \
      -e class dev.atlas.coach.sync.SyncSettingsScreenCaptureTest#captureRequestedState \
      -e capture_state "$state" \
      -e backend_url "http://10.0.2.2:$ATLAS_E2E_PORT" \
      -e backend_token "$ATLAS_E2E_TOKEN" \
      "$RUNNER" >"$raw" 2>&1; then
    redact_file "$raw" > "$OUTPUT_DIR/capture-$state.log"
    tail -120 "$OUTPUT_DIR/capture-$state.log" >&2
    return 1
  fi
  redact_file "$raw" > "$OUTPUT_DIR/capture-$state.log"
  if rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED' "$OUTPUT_DIR/capture-$state.log"; then
    tail -120 "$OUTPUT_DIR/capture-$state.log" >&2
    return 1
  fi
  "$ADB" -s "$SERIAL" pull "/sdcard/H2-$state.png" "$ROOT/docs/v4/captures/H2-$state.png" >/dev/null
  printf 'PASS: capture %s saved.\n' "$state"
}

verify_sqlite_count() {
  local expected="$1"
  ATLAS_DB_PATH="$ATLAS_DB_PATH" EXPECTED_EVENTS="$expected" /tmp/v4/venv/bin/python - <<'PY'
import os
import sqlite3

with sqlite3.connect(os.environ["ATLAS_DB_PATH"]) as connection:
    count, distinct_count = connection.execute(
        "SELECT COUNT(*), COUNT(DISTINCT event_id) FROM events"
    ).fetchone()
expected = int(os.environ["EXPECTED_EVENTS"])
assert count == expected, f"SQLite has {count} events; expected {expected}"
assert distinct_count == count, f"SQLite has duplicate event IDs: {count} rows, {distinct_count} IDs"
print(f"PASS: SQLite events={count}, unique_event_ids={distinct_count}.")
PY
}

cd "$ANDROID_DIR"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --offline

if ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
  status="BLOCKED: expected running emulator $SERIAL is unavailable. This script does not start, stop, or recreate an AVD."
  printf '%s\n' "$status" | tee "$OUTPUT_DIR/e2e-status.txt" >&2
  exit 77
fi

PERMISSIONS="$("$AAPT" dump permissions "$APK")"
if ! rg -q 'android.permission.INTERNET' <<<"$PERMISSIONS"; then
  printf 'FAIL: debug APK does not declare INTERNET.\n' >&2
  exit 1
fi
NETWORK_XML="$("$AAPT" dump xmltree "$APK" res/xml/network_security_config.xml)"
if ! rg -q '10\.0\.2\.2' <<<"$NETWORK_XML"; then
  printf 'FAIL: debug network policy does not include the emulator host alias.\n' >&2
  exit 1
fi

"$ADB" -s "$SERIAL" install -r "$APK" > "$OUTPUT_DIR/install.log"
run_capture disabled

start_backend
run_android_scenario online
if [[ "$(sync_request_count)" -ne 2 ]]; then
  printf 'FAIL: expected two HTTP sync posts for original delivery and idempotent replay.\n' >&2
  exit 1
fi
verify_sqlite_count 1

run_capture synchronized
verify_sqlite_count 2

before_unauthorized="$(rg -c '"POST /v1/sync HTTP/1.1" 401' "$SERVER_LOG" || true)"
run_android_scenario unauthorized
after_unauthorized="$(rg -c '"POST /v1/sync HTTP/1.1" 401' "$SERVER_LOG" || true)"
if [[ "${after_unauthorized:-0}" -le "${before_unauthorized:-0}" ]]; then
  printf 'FAIL: server access log does not show the expected invalid-token HTTP 401.\n' >&2
  exit 1
fi

before_disabled="$(backend_api_request_count)"
run_android_scenario disabled
after_disabled="$(backend_api_request_count)"
if [[ "$after_disabled" -ne "$before_disabled" ]]; then
  printf 'FAIL: disabled consent caused an HTTP API request.\n' >&2
  exit 1
fi

stop_backend
run_android_scenario offline
run_capture offline

start_backend
sleep 2
run_android_scenario resume
verify_sqlite_count 3

"$ADB" -s "$SERIAL" logcat -d -t 3000 -s ATLAS_H2:I > "$OUTPUT_DIR/android-filtered.log"
printf 'PASS: E2E artifacts saved in %s.\n' "$OUTPUT_DIR"
