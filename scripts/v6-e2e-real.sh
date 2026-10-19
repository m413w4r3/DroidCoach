#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/android"
SDK_ROOT="${ANDROID_HOME:-$HOME/Android/Sdk}"
SERIAL="${ANDROID_SERIAL:-emulator-5554}"
export ANDROID_SERIAL="$SERIAL"
ADB="$SDK_ROOT/platform-tools/adb"
AAPT="$SDK_ROOT/build-tools/37.0.0/aapt"
APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ANDROID_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
RUNNER="dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner"
TOKEN_FILE="${OPENCLAW_GATEWAY_TOKEN_FILE:-$HOME/.config/atlas/openclaw-gateway-token}"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
OUTPUT_DIR="${ATLAS_V6_E2E_OUTPUT_DIR:-$ROOT/docs/v6/logs/v6-e2e-real-$RUN_ID}"
CAPTURE_DIR="$ROOT/docs/v6/captures"
INSTALL_MARKER="$HOME/.cache/atlas/v6-e2e-owned-install"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/atlas-v6-e2e.XXXXXX")"
BACKEND_PID=""
HOME_ROLE_CHANGED=0
ORIGINAL_HOME_HOLDERS=""
ATLAS_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
ATLAS_PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
BACKEND_LOG="$WORK_DIR/backend.raw.log"
printf '%s\n' "$ATLAS_TOKEN" > "$WORK_DIR/atlas-token.txt"
chmod 600 "$WORK_DIR/atlas-token.txt"

mkdir -p "$OUTPUT_DIR" "$CAPTURE_DIR"

app_installed() {
  "$ADB" -s "$SERIAL" shell pm path dev.atlas.coach 2>/dev/null | rg -q '^package:'
}

redact_file() {
  ATLAS_E2E_TOKEN="$ATLAS_TOKEN" python3 -c 'import os,sys; print(sys.stdin.read().replace(os.environ["ATLAS_E2E_TOKEN"], "[redacted]"), end="")' < "$1" > "$2"
}

verify_no_secret_in_repo() {
  local secret_file="$1"
  local label="$2"
  local result
  set +e
  python3 - "$ROOT" "$secret_file" <<'PY'
import pathlib
import subprocess
import sys

root = pathlib.Path(sys.argv[1])
secret_file = pathlib.Path(sys.argv[2])
listed = subprocess.run(
    ["git", "-C", str(root), "ls-files", "-co", "--exclude-standard", "-z"],
    check=True,
    stdout=subprocess.PIPE,
)
files = [str(root / item.decode()) for item in listed.stdout.split(b"\0") if item]
if not files:
    raise SystemExit(2)
result = subprocess.run(["grep", "-a", "-F", "-l", "-f", str(secret_file), *files], stdout=subprocess.DEVNULL)
raise SystemExit(result.returncode)
PY
  result=$?
  set -e
  if [[ "$result" -eq 0 ]]; then
    printf 'FAIL: %s found in repository files.\n' "$label" >&2
    return 1
  fi
  if [[ "$result" -gt 1 ]]; then
    printf 'FAIL: grep could not verify %s absence.\n' "$label" >&2
    return 1
  fi
  printf 'PASS: grep -F confirmed %s is absent from repository files.\n' "$label"
}

stop_backend() {
  if [[ -n "$BACKEND_PID" ]] && kill -0 "$BACKEND_PID" 2>/dev/null; then
    kill "$BACKEND_PID" 2>/dev/null || true
    wait "$BACKEND_PID" 2>/dev/null || true
  fi
  BACKEND_PID=""
}

restore_home_role() {
  if [[ "$HOME_ROLE_CHANGED" == "1" ]]; then
    "$ADB" -s "$SERIAL" shell cmd role remove-role-holder --user 0 android.app.role.HOME dev.atlas.coach >/dev/null 2>&1 || true
    while IFS= read -r package_name; do
      package_name="${package_name//$'\r'/}"
      if [[ -n "$package_name" ]]; then
        "$ADB" -s "$SERIAL" shell cmd role add-role-holder --user 0 android.app.role.HOME "$package_name" >/dev/null 2>&1 || true
      fi
    done <<< "$ORIGINAL_HOME_HOLDERS"
    HOME_ROLE_CHANGED=0
  fi
}

cleanup() {
  stop_backend
  restore_home_role
  if [[ -n "$BACKEND_LOG" && -f "$BACKEND_LOG" ]]; then
    redact_file "$BACKEND_LOG" "$OUTPUT_DIR/backend.log"
  fi
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

if [[ ! -x "$ADB" || ! -x "$AAPT" ]]; then
  printf 'BLOCKED: Android adb/aapt unavailable under %s.\n' "$SDK_ROOT" >&2
  exit 77
fi
if ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
  printf 'BLOCKED: expected running emulator %s is unavailable. This script never starts, stops, or recreates an AVD.\n' "$SERIAL" >&2
  exit 77
fi

if [[ ! -f "$TOKEN_FILE" ]]; then
  python3 - "$HOME/.openclaw/openclaw.json" "$TOKEN_FILE" <<'PY'
import json
import os
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
config = json.loads(source.read_text(encoding="utf-8"))
token = config["gateway"]["auth"]["token"]
if not isinstance(token, str) or not token.strip():
    raise SystemExit("Gateway credential is absent or invalid")
destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "w", encoding="utf-8") as stream:
    stream.write(token.strip())
    stream.write("\n")
PY
fi
TOKEN_MODE="$(stat -c '%a' "$TOKEN_FILE")"
if [[ "$TOKEN_MODE" != "600" ]]; then
  printf 'BLOCKED: Gateway token file must have mode 600; observed mode %s.\n' "$TOKEN_MODE" >&2
  exit 77
fi

if app_installed && [[ ! -f "$INSTALL_MARKER" ]]; then
  printf 'BLOCKED: dev.atlas.coach is installed without this recipe marker; refusing to alter possibly existing app data.\n' >&2
  exit 77
fi

cd "$ANDROID_DIR"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
PERMISSIONS="$("$AAPT" dump permissions "$APK")"
if ! rg -q 'android.permission.INTERNET' <<< "$PERMISSIONS"; then
  printf 'FAIL: debug APK does not declare INTERNET.\n' >&2
  exit 1
fi
NETWORK_XML="$("$AAPT" dump xmltree "$APK" res/xml/network_security_config.xml)"
if ! rg -q '10\.0\.2\.2' <<< "$NETWORK_XML"; then
  printf 'FAIL: debug policy has no 10.0.2.2 cleartext exception.\n' >&2
  exit 1
fi

if ! app_installed; then
  "$ADB" -s "$SERIAL" install "$APK" > "$OUTPUT_DIR/install.log"
  mkdir -p "$(dirname "$INSTALL_MARKER")"
  chmod 700 "$(dirname "$INSTALL_MARKER")"
  : > "$INSTALL_MARKER"
fi
"$ADB" -s "$SERIAL" install -r "$APK" > "$OUTPUT_DIR/update-app.log"
"$ADB" -s "$SERIAL" install -r -t "$TEST_APK" > "$OUTPUT_DIR/install-test-apk.log"

ATLAS_AGENT_ADAPTER=openclaw \
ATLAS_TOKEN="$ATLAS_TOKEN" \
ATLAS_DB_PATH="$WORK_DIR/atlas.sqlite3" \
OPENCLAW_BASE_URL="${OPENCLAW_BASE_URL:-http://127.0.0.1:18789}" \
OPENCLAW_AGENT_ID="${OPENCLAW_AGENT_ID:-atlas}" \
OPENCLAW_TIMEOUT_S="${OPENCLAW_TIMEOUT_S:-60}" \
OPENCLAW_GATEWAY_TOKEN_FILE="$TOKEN_FILE" \
ATLAS_AGENT_RPM=30 \
PYTHONPATH="$ROOT/backend${PYTHONPATH:+:$PYTHONPATH}" \
  python3 -m uvicorn atlas_backend.main:app --host 0.0.0.0 --port "$ATLAS_PORT" --access-log --log-level info \
  > "$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!
export ATLAS_TOKEN ATLAS_PORT
for _ in $(seq 1 80); do
  if python3 -c 'import os,urllib.request; urllib.request.urlopen("http://127.0.0.1:"+os.environ["ATLAS_PORT"]+"/health", timeout=.5).read()' >/dev/null 2>&1; then
    break
  fi
  sleep 0.25
done
if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
  redact_file "$BACKEND_LOG" "$OUTPUT_DIR/backend.log"
  tail -80 "$OUTPUT_DIR/backend.log" >&2
  printf 'FAIL: OpenClaw adapter backend did not start.\n' >&2
  exit 1
fi
ATLAS_TOKEN="$ATLAS_TOKEN" ATLAS_PORT="$ATLAS_PORT" python3 - <<'PY'
import json
import os
import urllib.request

request = urllib.request.Request(
    f"http://127.0.0.1:{os.environ['ATLAS_PORT']}/v2/agent/status",
    headers={"Authorization": f"Bearer {os.environ['ATLAS_TOKEN']}"},
)
with urllib.request.urlopen(request, timeout=2) as response:
    status = json.load(response)
assert status["adapter"] == "openclaw", "backend is not configured for OpenClaw"
print("PASS: local FastAPI status reports adapter=openclaw.")
PY

run_android_test() {
  local method="$1"
  local raw="$WORK_DIR/instrument-$method.raw.log"
  local safe="$OUTPUT_DIR/instrument-$method.log"
  if ! "$ADB" -s "$SERIAL" shell am instrument -w \
      -e class "dev.atlas.coach.app.V6RealAgentE2eInstrumentationTest#$method" \
      -e backend_url "http://10.0.2.2:$ATLAS_PORT" \
      -e backend_token "$ATLAS_TOKEN" \
      "$RUNNER" > "$raw" 2>&1; then
    redact_file "$raw" "$safe"
    "$ADB" -s "$SERIAL" logcat -d -s ATLAS_AGENT:W ATLAS_V6_REAL:I > "$WORK_DIR/android-debug-$method.raw.log"
    redact_file "$WORK_DIR/android-debug-$method.raw.log" "$OUTPUT_DIR/android-debug-$method.log"
    tail -100 "$safe" >&2
    tail -80 "$OUTPUT_DIR/android-debug-$method.log" >&2
    return 1
  fi
  redact_file "$raw" "$safe"
  if rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_RESULT: shortMsg' "$safe"; then
    "$ADB" -s "$SERIAL" logcat -d -s ATLAS_AGENT:W ATLAS_V6_REAL:I > "$WORK_DIR/android-debug-$method.raw.log"
    redact_file "$WORK_DIR/android-debug-$method.raw.log" "$OUTPUT_DIR/android-debug-$method.log"
    tail -100 "$safe" >&2
    tail -80 "$OUTPUT_DIR/android-debug-$method.log" >&2
    return 1
  fi
  if ! rg -q 'INSTRUMENTATION_CODE: 0|OK \([1-9][0-9]* tests?\)' "$safe"; then
    tail -100 "$safe" >&2
    printf 'FAIL: instrumentation method %s did not report successful completion.\n' "$method" >&2
    return 1
  fi
  printf 'PASS: Android instrumentation method %s.\n' "$method"
}

run_android_test realCoachResponseCreatesDirectiveAndStartsFocus
stop_backend
redact_file "$BACKEND_LOG" "$OUTPUT_DIR/backend.log"

ORIGINAL_HOME_HOLDERS="$("$ADB" -s "$SERIAL" shell cmd role get-role-holders --user 0 android.app.role.HOME | tr -d '\r')"
if ! "$ADB" -s "$SERIAL" shell cmd role add-role-holder --user 0 android.app.role.HOME dev.atlas.coach >/dev/null; then
  printf 'FAIL: could not temporarily select ATLAS for the HOME screenshot.\n' >&2
  exit 1
fi
HOME_ROLE_CHANGED=1
if ! "$ADB" -s "$SERIAL" shell cmd role get-role-holders --user 0 android.app.role.HOME | tr -d '\r' | rg -q '(^|[[:space:]])dev\.atlas\.coach($|[[:space:]])'; then
  printf 'FAIL: ATLAS did not receive the temporary HOME role.\n' >&2
  exit 1
fi
"$ADB" -s "$SERIAL" shell am force-stop dev.atlas.coach
"$ADB" -s "$SERIAL" shell am start -n dev.atlas.coach/.app.LauncherActivity > "$OUTPUT_DIR/launch-home.log"
sleep 3
"$ADB" -s "$SERIAL" shell uiautomator dump /sdcard/v6-real-home.xml >/dev/null
"$ADB" -s "$SERIAL" pull /sdcard/v6-real-home.xml "$CAPTURE_DIR/v6-openclaw-home.xml" >/dev/null
"$ADB" -s "$SERIAL" exec-out screencap -p > "$CAPTURE_DIR/v6-openclaw-home.png"
"$ADB" -s "$SERIAL" shell rm -f /sdcard/v6-real-home.xml

# Validate the generated directive title from the instrumented response against HOME UI hierarchy.
"$ADB" -s "$SERIAL" logcat -d -s ATLAS_V6_REAL:I > "$WORK_DIR/android-proof.raw.log"
redact_file "$WORK_DIR/android-proof.raw.log" "$OUTPUT_DIR/android-proof.log"
HOME_TITLE="$(python3 - "$OUTPUT_DIR/android-proof.log" <<'PY'
import re
import sys

lines = open(sys.argv[1], encoding="utf-8").read().splitlines()
for line in reversed(lines):
    match = re.search(r"home_title=(.*?) focus_minutes=", line)
    if match:
        print(match.group(1).strip())
        break
else:
    raise SystemExit("FAIL: Android test emitted no real HOME title")
PY
)"
EXPECTED_HOME_TITLE="$HOME_TITLE" python3 - "$CAPTURE_DIR/v6-openclaw-home.xml" <<'PY'
import os
import sys
import xml.etree.ElementTree as ET

expected = os.environ["EXPECTED_HOME_TITLE"]
root = ET.parse(sys.argv[1]).getroot()
visible = "\n".join(
    value
    for node in root.iter("node")
    for value in (node.attrib.get("text", ""), node.attrib.get("content-desc", ""))
)
if expected not in visible:
    raise SystemExit("FAIL: real directive title is absent from the LauncherActivity UI dump")
print(f"PASS: LauncherActivity HOME displays the real directive: {expected}")
PY

run_android_test restoredFocusCanPauseResumeAndCancel
run_android_test limitedMandatePreauthorizesFocusPlanOnAndroid
restore_home_role

redact_file "$BACKEND_LOG" "$OUTPUT_DIR/backend.log"
verify_no_secret_in_repo "$TOKEN_FILE" "OpenClaw Gateway token"
verify_no_secret_in_repo "$WORK_DIR/atlas-token.txt" "random ATLAS bearer token"
printf 'PASS: Android 17 E2E artifacts saved in %s and %s.\n' "$OUTPUT_DIR" "$CAPTURE_DIR"

