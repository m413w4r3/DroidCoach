#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/android"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
SERIAL="${ANDROID_SERIAL:-emulator-5560}"
ADB="$SDK_ROOT/platform-tools/adb"
EMULATOR="$SDK_ROOT/emulator/emulator"
APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ANDROID_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
RUNNER="dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
LOG_DIR="$ROOT/docs/v6-2/logs/recette-native/$RUN_ID"
CAPTURE_DIR="$ROOT/docs/v6-2/captures/$RUN_ID"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/atlas-v62-recipe.XXXXXX")"
TOKEN_FILE="${OPENCLAW_GATEWAY_TOKEN_FILE:-$HOME/.config/atlas/openclaw-gateway-token}"
BACKEND_PID=""
ATLAS_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
ATLAS_PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
BACKEND_RAW_LOG="$WORK_DIR/backend.raw.log"
RESULTS="$LOG_DIR/results.txt"
LOCAL_OK=1
CORE_OK=1
SAFE_AVD=0

mkdir -p "$LOG_DIR" "$CAPTURE_DIR"
chmod 700 "$WORK_DIR"

redact_secrets() {
  local source="$1"
  local destination="$2"
  ATLAS_REDACT_TOKEN="$ATLAS_TOKEN" \
    python3 - "$source" "$destination" "$TOKEN_FILE" <<'PY'
import os
import pathlib
import sys

data = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
secrets = [os.environ.get("ATLAS_REDACT_TOKEN", "")]
token_file = pathlib.Path(sys.argv[3])
if token_file.is_file():
    secrets.append(token_file.read_text(encoding="utf-8").strip())
for secret in secrets:
    if secret:
        data = data.replace(secret, "[redacted]")
pathlib.Path(sys.argv[2]).write_text(data, encoding="utf-8")
PY
}

stop_backend() {
  if [[ -n "$BACKEND_PID" ]] && kill -0 "$BACKEND_PID" 2>/dev/null; then
    kill "$BACKEND_PID" 2>/dev/null || true
    wait "$BACKEND_PID" 2>/dev/null || true
  fi
  BACKEND_PID=""
}

restore_display() {
  if [[ "$SAFE_AVD" == "1" && -x "$ADB" ]] && "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
    "$ADB" -s "$SERIAL" shell wm size reset >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" shell wm density reset >/dev/null 2>&1 || true
  fi
}

cleanup() {
  stop_backend
  restore_display
  if [[ -f "$BACKEND_RAW_LOG" ]]; then
    redact_secrets "$BACKEND_RAW_LOG" "$LOG_DIR/backend.log"
  fi
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

if [[ ! -x "$ADB" || ! -x "$EMULATOR" ]]; then
  printf 'BLOCKED: adb/emulator unavailable in %s.\n' "$SDK_ROOT" >&2
  exit 77
fi
if [[ ! "$SERIAL" =~ ^emulator-[0-9]+$ ]] || ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
  printf 'BLOCKED: %s must be a running disposable emulator serial; physical devices are refused.\n' "$SERIAL" >&2
  exit 77
fi
AVD_NAME="$("$ADB" -s "$SERIAL" shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r')"
if [[ -z "$AVD_NAME" ]]; then
  AVD_NAME="$("$ADB" -s "$SERIAL" emu avd name 2>/dev/null | sed -n '2s/^OK//p' | tr -d '\r ' )"
fi
API_LEVEL="$("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$AVD_NAME" != atlas_v62_disposable_api34 || "$API_LEVEL" != 34 ]]; then
  printf 'BLOCKED: recipe is restricted to atlas_v62_disposable_api34 (observed AVD=%s API=%s). No other AVD was modified.\n' "$AVD_NAME" "$API_LEVEL" >&2
  exit 77
fi
SAFE_AVD=1

{
  printf 'ATLAS V6.2 native recipe · %s UTC\n' "$RUN_ID"
  printf 'AVD=%s serial=%s API=%s\n' "$AVD_NAME" "$SERIAL" "$API_LEVEL"
  "$EMULATOR" -version | sed -n '1p'
  java -version 2>&1 | sed -n '1p'
  git -C "$ROOT" rev-parse --short HEAD
} > "$LOG_DIR/environment.txt"

cd "$ANDROID_DIR"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest > "$LOG_DIR/gradle-assemble.log" 2>&1
test -s "$APK" && test -s "$TEST_APK"
"$ADB" -s "$SERIAL" shell pm clear dev.atlas.coach > "$LOG_DIR/clear-target-data.log"
"$ADB" -s "$SERIAL" shell pm clear dev.atlas.coach.test > "$LOG_DIR/clear-test-data.log"
"$ADB" -s "$SERIAL" install -r "$APK" > "$LOG_DIR/install-app.log"
"$ADB" -s "$SERIAL" install -r -t "$TEST_APK" > "$LOG_DIR/install-test-apk.log"
"$ADB" -s "$SERIAL" shell pm grant dev.atlas.coach android.permission.ACCESS_COARSE_LOCATION > "$LOG_DIR/grant-coarse-location.log"
"$ADB" -s "$SERIAL" shell pm grant dev.atlas.coach android.permission.POST_NOTIFICATIONS > "$LOG_DIR/grant-notifications.log"
"$ADB" -s "$SERIAL" shell cmd location set-location-enabled true > "$LOG_DIR/location-enabled.log"
"$ADB" -s "$SERIAL" emu geo fix 2.123456 48.123456 > "$LOG_DIR/geo-fix.log"
"$ADB" -s "$SERIAL" shell wm density 480 > "$LOG_DIR/display-density.log"

run_instrument() {
  local name="$1"
  local class_method="$2"
  shift 2
  local raw="$WORK_DIR/$name.raw.log"
  local safe="$LOG_DIR/$name.log"
  local status=0
  if "$ADB" -s "$SERIAL" shell am instrument -w -r \
      -e class "$class_method" "$@" "$RUNNER" > "$raw" 2>&1; then
    status=0
  else
    status=$?
  fi
  redact_secrets "$raw" "$safe"
  if [[ "$status" -eq 0 ]] && ! rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_RESULT: shortMsg' "$safe" && \
      rg -q 'OK \([1-9][0-9]* tests?\)' "$safe"; then
    printf 'PASS %s\n' "$name" | tee -a "$RESULTS"
    return 0
  fi
  printf 'FAIL %s (see %s)\n' "$name" "$safe" | tee -a "$RESULTS"
  LOCAL_OK=0
  case "$name" in
    location-*|mobility-*) ;;
    *) CORE_OK=0 ;;
  esac
  return 1
}

CAPTURE_NAMES=(
  v62-home-dark-focus-360dp.png v62-home-amoled-focus-360dp.png v62-home-no-remote-directive-360dp.png
  v62-home-five-favorites-360dp.png v62-app-drawer-360dp.png v62-app-search-360dp.png
  v62-home-font-1_5-360dp.png v62-home-font-2_0-360dp.png
  v62-home-dark-focus-320dp.png v62-home-amoled-focus-320dp.png v62-home-no-remote-directive-320dp.png
  v62-home-five-favorites-320dp.png v62-app-drawer-320dp.png v62-app-search-320dp.png
  v62-home-font-1_5-320dp.png v62-home-font-2_0-320dp.png
  v62-home-landscape.png v62-home-real-gateway-directive.png v62-focus-active.png v62-coach-real-gateway.png
  v62-agent-mandate.png v62-agent-sharing-focus-goals.png
)
LOCAL_CAPTURE_NAMES=(
  v62-home-dark-focus-360dp.png v62-home-amoled-focus-360dp.png v62-home-no-remote-directive-360dp.png
  v62-home-five-favorites-360dp.png v62-app-drawer-360dp.png v62-app-search-360dp.png
  v62-home-font-1_5-360dp.png v62-home-font-2_0-360dp.png
  v62-home-dark-focus-320dp.png v62-home-amoled-focus-320dp.png v62-home-no-remote-directive-320dp.png
  v62-home-five-favorites-320dp.png v62-app-drawer-320dp.png v62-app-search-320dp.png
  v62-home-font-1_5-320dp.png v62-home-font-2_0-320dp.png
  v62-home-landscape.png v62-focus-active.png
)
GATEWAY_CAPTURE_NAMES=(
  v62-home-real-gateway-directive.png v62-coach-real-gateway.png
  v62-agent-mandate.png v62-agent-sharing-focus-goals.png
)

pull_captures() {
  local capture_name remote
  for capture_name in "$@"; do
    remote="/data/user/0/dev.atlas.coach/files/v62-captures/$capture_name"
    if "$ADB" -s "$SERIAL" shell run-as dev.atlas.coach test -f "$remote" >/dev/null 2>&1; then
      if ! "$ADB" -s "$SERIAL" exec-out run-as dev.atlas.coach cat "$remote" > "$CAPTURE_DIR/$capture_name"; then
        printf 'FAILED_PULL %s\n' "$capture_name" >> "$LOG_DIR/pull-captures.log"
      fi
    else
      printf 'MISSING %s\n' "$capture_name" >> "$LOG_DIR/pull-captures.log"
    fi
  done
}

validate_captures() {
  local output_name="$1"
  shift
  python3 - "$CAPTURE_DIR" "$@" > "$LOG_DIR/$output_name" <<'PY'
import pathlib
import struct
import sys

directory = pathlib.Path(sys.argv[1])
expected = sys.argv[2:]
missing = [name for name in expected if not (directory / name).is_file()]
if missing:
    raise SystemExit("Missing requested captures: " + ", ".join(missing))
for path in sorted(directory / name for name in expected):
    data = path.read_bytes()
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"Invalid PNG signature: {path.name}")
    width, height = struct.unpack(">II", data[16:24])
    if width < 1 or height < 1:
        raise SystemExit(f"Invalid PNG dimensions: {path.name} {width}x{height}")
    print(f"PASS {path.name} {width}x{height} {len(data)} bytes")
PY
}

run_display_matrix() {
  local width="$1"
  local px_width="$((width * 3))"
  "$ADB" -s "$SERIAL" shell wm size "${px_width}x2400" > "$LOG_DIR/wm-size-${width}dp.log"
  run_instrument "native-ui-${width}dp" \
    "dev.atlas.coach.app.V62NativeUiCaptureTest#nativeHomeMatrixAtRequestedWidth" \
    -e expected_width_dp "$width" || true
}

run_display_matrix 360
run_display_matrix 320
"$ADB" -s "$SERIAL" shell wm size 1080x2400 > "$LOG_DIR/wm-size-landscape-base.log"
run_instrument "native-landscape" \
  "dev.atlas.coach.app.V62NativeUiCaptureTest#landscapeHomeKeepsPauseAndFooterOutsideScrollableContent" || true
run_instrument "native-focus-screen" \
  "dev.atlas.coach.app.V62NativeUiCaptureTest#focusScreenAndContrastTokensAreVerifiableWithoutGateway" || true
run_instrument "location-prepare-sharing-consent" \
  "dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#prepareSharingConsentBeforeHostInjectsFreshEmulatorFix" || true
"$ADB" -s "$SERIAL" emu geo fix 2.123456 48.123456 > "$LOG_DIR/geo-fix-location-capture.log"
run_instrument "location-injected-coarse" \
  "dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#injectedCoarseFixIsMinimizedAndStoredOnlyAsAZoneSummary" || true
"$ADB" -s "$SERIAL" shell pm revoke dev.atlas.coach android.permission.ACCESS_COARSE_LOCATION > "$LOG_DIR/revoke-coarse-location.log"
run_instrument "location-permission-refused" \
  "dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#refusedOsPermissionReturnsFunctionalErrorWithoutCrashing" || true
"$ADB" -s "$SERIAL" shell pm grant dev.atlas.coach android.permission.ACCESS_COARSE_LOCATION > "$LOG_DIR/regrant-coarse-location.log"
run_instrument "location-api26-timeout-unavailable" \
  "dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#api26Through29UseListenerAndReturnTimeoutOrUnavailableCodes" || true
run_instrument "location-revoke-during-capture" \
  "dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#permissionRevocationDuringAcquisitionIsRecheckedBeforeReturningFix" || true
run_instrument "mobility-prepare-sharing-consent" \
  "dev.atlas.coach.location.V62MobilityServiceInstrumentedTest#prepareSharingConsentBeforeHostInjectsFreshMobilityFix" || true
"$ADB" -s "$SERIAL" emu geo fix 2.123456 48.123456 > "$LOG_DIR/geo-fix-mobility.log"
run_instrument "mobility-visible-service" \
  "dev.atlas.coach.location.V62MobilityServiceInstrumentedTest#visibleStartShowsStopNotificationAndServiceStopsWithoutRawTrail" || true

pull_captures "${LOCAL_CAPTURE_NAMES[@]}"
if ! validate_captures capture-validation-local.log "${LOCAL_CAPTURE_NAMES[@]}"; then
  LOCAL_OK=0
  CORE_OK=0
fi
if [[ "$CORE_OK" -ne 1 ]]; then
  printf 'BLOCKED: Gateway model was not called because a UI/Focus/local-capture Android prerequisite failed.\n' | tee -a "$RESULTS"
  exit 1
fi
if [[ "$LOCAL_OK" -ne 1 ]]; then
  printf 'NOTE: proceeding to independent Focus/Goals Gateway E2E; location/service failures remain recorded above.\n' | tee -a "$RESULTS"
fi

if [[ ! -f "$TOKEN_FILE" ]]; then
  python3 - "$HOME/.openclaw/openclaw.json" "$TOKEN_FILE" <<'PY'
import json
import os
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
token = json.loads(source.read_text(encoding="utf-8"))["gateway"]["auth"]["token"]
if not isinstance(token, str) or not token.strip():
    raise SystemExit("Gateway credential is absent or invalid")
destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "w", encoding="utf-8") as stream:
    stream.write(token.strip() + "\n")
PY
fi
TOKEN_MODE="$(stat -c '%a' "$TOKEN_FILE")"
if [[ "$TOKEN_MODE" != 600 ]]; then
  printf 'BLOCKED: Gateway token file must have mode 600; observed mode %s.\n' "$TOKEN_MODE" >&2
  exit 77
fi
python3 - "$TOKEN_FILE" <<'PY'
import pathlib
import sys

if not pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").strip():
    raise SystemExit("BLOCKED: Gateway token file is empty.")
PY
unset OPENCLAW_GATEWAY_TOKEN || true
PYTHON_BIN="$ROOT/backend/.venv/bin/python"
if [[ ! -x "$PYTHON_BIN" ]]; then PYTHON_BIN="$(command -v python3)"; fi
printf '%s\n' "$ATLAS_TOKEN" > "$WORK_DIR/atlas-token.txt"
chmod 600 "$WORK_DIR/atlas-token.txt"
ATLAS_TOKEN="$ATLAS_TOKEN" \
ATLAS_DB_PATH="$WORK_DIR/atlas-v62.sqlite3" \
OPENCLAW_BASE_URL="${OPENCLAW_BASE_URL:-http://127.0.0.1:18789}" \
OPENCLAW_AGENT_ID="${OPENCLAW_AGENT_ID:-atlas}" \
OPENCLAW_TIMEOUT_S="${OPENCLAW_TIMEOUT_S:-60}" \
OPENCLAW_GATEWAY_TOKEN_FILE="$TOKEN_FILE" \
ATLAS_AGENT_ADAPTER=openclaw \
ATLAS_AGENT_RPM=30 \
PYTHONPATH="$ROOT/backend${PYTHONPATH:+:$PYTHONPATH}" \
  "$PYTHON_BIN" -m uvicorn atlas_backend.main:app --host 0.0.0.0 --port "$ATLAS_PORT" --log-level info \
  > "$BACKEND_RAW_LOG" 2>&1 &
BACKEND_PID=$!
export ATLAS_TOKEN ATLAS_PORT

backend_ready=0
for attempt in $(seq 1 80); do
  if python3 -c 'import os,urllib.request; urllib.request.urlopen("http://127.0.0.1:"+os.environ["ATLAS_PORT"]+"/health", timeout=.5).read()' >/dev/null 2>&1; then
    backend_ready=1
    break
  fi
  sleep 0.25
done
if [[ "$backend_ready" -ne 1 ]] || ! kill -0 "$BACKEND_PID" 2>/dev/null; then
  redact_secrets "$BACKEND_RAW_LOG" "$LOG_DIR/backend.log"
  printf 'FAIL: FastAPI adapter did not become healthy.\n' | tee -a "$RESULTS"
  exit 1
fi
ATLAS_TOKEN="$ATLAS_TOKEN" ATLAS_PORT="$ATLAS_PORT" python3 - <<'PY' > "$LOG_DIR/backend-status.log"
import json
import os
import urllib.request

request = urllib.request.Request(
    f"http://127.0.0.1:{os.environ['ATLAS_PORT']}/v2/agent/status",
    headers={"Authorization": f"Bearer {os.environ['ATLAS_TOKEN']}"},
)
with urllib.request.urlopen(request, timeout=2) as response:
    status = json.load(response)
assert status["adapter"] == "openclaw", "FastAPI adapter is not connected to OpenClaw"
print("PASS: local FastAPI status reports adapter=openclaw; no model request was issued.")
PY

run_instrument "gateway-real-context-accelerated-focus" \
  "dev.atlas.coach.app.V62RealGatewayContextBridgeTest#realGatewayUsesAccelerated25MinuteFocusContextAndAcceptedProposal" \
  -e backend_url "http://10.0.2.2:$ATLAS_PORT" -e backend_token "$ATLAS_TOKEN" || {
    pull_captures "${GATEWAY_CAPTURE_NAMES[@]}"
    "$ADB" -s "$SERIAL" logcat -d -s ATLAS_V62_REAL:I ATLAS_AGENT:W > "$WORK_DIR/android-real.raw.log"
    redact_secrets "$WORK_DIR/android-real.raw.log" "$LOG_DIR/android-real.log"
    exit 1
  }
stop_backend
redact_secrets "$BACKEND_RAW_LOG" "$LOG_DIR/backend.log"
run_instrument "offline-after-backend-stop" \
  "dev.atlas.coach.app.V62RealGatewayContextBridgeTest#localGoalFocusFavoritesAndEmergencyPauseRemainAvailableAfterBackendStop" \
  -e backend_stopped true || true

pull_captures "${GATEWAY_CAPTURE_NAMES[@]}"
if ! validate_captures capture-validation-all.log "${CAPTURE_NAMES[@]}"; then
  LOCAL_OK=0
fi

if [[ "$LOCAL_OK" -ne 1 ]]; then exit 1; fi
printf 'PASS all requested native checks; see %s and %s\n' "$LOG_DIR" "$CAPTURE_DIR" | tee -a "$RESULTS"
