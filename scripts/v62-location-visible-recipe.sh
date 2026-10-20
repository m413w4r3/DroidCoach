#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/android"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
ADB="$SDK_ROOT/platform-tools/adb"
SERIAL="${ANDROID_SERIAL:-emulator-5560}"
RUNNER="dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
LOG_DIR="$ROOT/docs/v6-2/logs/recette-native/$RUN_ID"
APP_APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ANDROID_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
mkdir -p "$LOG_DIR"

if [[ ! -x "$ADB" ]]; then
  printf 'BLOCKED: adb unavailable at %s\n' "$ADB" >&2
  exit 77
fi
if [[ ! "$SERIAL" =~ ^emulator-[0-9]+$ ]] || ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
  printf 'BLOCKED: %s must be a running disposable emulator; physical devices are refused.\n' "$SERIAL" >&2
  exit 77
fi
AVD_NAME="$("$ADB" -s "$SERIAL" shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r')"
API_LEVEL="$("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$AVD_NAME" != atlas_v62_disposable_api34 || "$API_LEVEL" != 34 ]]; then
  printf 'BLOCKED: expected atlas_v62_disposable_api34/API 34; observed AVD=%s API=%s.\n' "$AVD_NAME" "$API_LEVEL" >&2
  exit 77
fi

{
  printf 'ATLAS V6.2 visible coarse-location recipe · %s UTC\n' "$RUN_ID"
  printf 'AVD=%s serial=%s API=%s\n' "$AVD_NAME" "$SERIAL" "$API_LEVEL"
  "$SDK_ROOT/emulator/emulator" -version | sed -n '1p'
  java -version 2>&1 | sed -n '1p'
  git -C "$ROOT" rev-parse --short HEAD
} > "$LOG_DIR/environment.txt"

cd "$ANDROID_DIR"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain > "$LOG_DIR/gradle-assemble.log" 2>&1
test -s "$APP_APK" && test -s "$TEST_APK"
"$ADB" -s "$SERIAL" shell pm clear dev.atlas.coach > "$LOG_DIR/clear-app-data.log"
"$ADB" -s "$SERIAL" shell pm clear dev.atlas.coach.test > "$LOG_DIR/clear-test-data.log"
"$ADB" -s "$SERIAL" install -r "$APP_APK" > "$LOG_DIR/install-app.log"
"$ADB" -s "$SERIAL" install -r -t "$TEST_APK" > "$LOG_DIR/install-test-apk.log"
"$ADB" -s "$SERIAL" shell pm revoke dev.atlas.coach android.permission.ACCESS_FINE_LOCATION > "$LOG_DIR/revoke-fine-location.log"
"$ADB" -s "$SERIAL" shell pm grant dev.atlas.coach android.permission.ACCESS_COARSE_LOCATION > "$LOG_DIR/grant-coarse-location.log"
"$ADB" -s "$SERIAL" shell pm grant dev.atlas.coach android.permission.POST_NOTIFICATIONS > "$LOG_DIR/grant-notifications.log"
"$ADB" -s "$SERIAL" shell cmd location set-location-enabled true > "$LOG_DIR/location-enabled.log"

run_with_emulator_fix() {
  local label="$1"
  local class_method="$2"
  local marker="$3"
  local raw="$LOG_DIR/$label-instrumentation.log"
  local ready_log="$LOG_DIR/$label-activity-visible.log"
  local logcat_log="$LOG_DIR/$label-location-logcat.log"
  local result=0

  "$ADB" -s "$SERIAL" logcat -c
  "$ADB" -s "$SERIAL" shell am instrument -w -r \
    -e class "$class_method" "$RUNNER" > "$raw" 2>&1 &
  local instrumentation_pid=$!

  # Read the logcat marker as an event and stop logcat immediately. Waiting for a pipeline
  # timeout here lets an already-finished cached request win the race against geo injection.
  local ready_line=""
  local log_line=""
  coproc ATLAS_LOCATION_LOGCAT { "$ADB" -s "$SERIAL" logcat -s ATLAS_V62_LOCATION:I 2>&1; }
  local logcat_pid="$ATLAS_LOCATION_LOGCAT_PID"
  local logcat_fd="${ATLAS_LOCATION_LOGCAT[0]}"
  local deadline=$((SECONDS + 20))
  while (( SECONDS < deadline )); do
    local remaining=$((deadline - SECONDS))
    if ! IFS= read -r -t "$remaining" -u "$logcat_fd" log_line; then
      break
    fi
    if [[ "$log_line" == *"$marker"* ]]; then
      ready_line="$log_line"
      break
    fi
  done
  kill "$logcat_pid" 2>/dev/null || true
  wait "$logcat_pid" 2>/dev/null || true
  exec {logcat_fd}<&- 2>/dev/null || true
  printf '%s\n' "$ready_line" > "$ready_log"
  if [[ -z "$ready_line" ]]; then
    printf 'FAIL %s: no active-capture signal within 20 s.\n' "$label" | tee -a "$LOG_DIR/results.txt"
    if kill -0 "$instrumentation_pid" 2>/dev/null; then
      kill "$instrumentation_pid" 2>/dev/null || true
      wait "$instrumentation_pid" 2>/dev/null || true
    fi
    "$ADB" -s "$SERIAL" logcat -d -s ATLAS_V62_LOCATION:I > "$logcat_log"
    return 1
  fi

  local injection_started_utc_ms
  injection_started_utc_ms="$(date -u +%s%3N)"
  printf 'host_geo_fix_started_utc_ms=%s\n' "$injection_started_utc_ms" > "$LOG_DIR/$label-geo-fix.log"
  "$ADB" -s "$SERIAL" shell am broadcast \
    -a dev.atlas.coach.location.V62_HOST_FIX_INJECTED \
    -p dev.atlas.coach --el injected_at_utc_ms "$injection_started_utc_ms" >> "$LOG_DIR/$label-geo-fix.log"
  "$ADB" -s "$SERIAL" emu geo fix 2.123456 48.123456 >> "$LOG_DIR/$label-geo-fix.log"

  if wait "$instrumentation_pid"; then result=0; else result=$?; fi
  "$ADB" -s "$SERIAL" logcat -d -s ATLAS_V62_LOCATION:I > "$logcat_log"
  if [[ "$result" -eq 0 ]] && \
      ! rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_RESULT: shortMsg' "$raw" && \
      rg -q 'OK \(1 test\)' "$raw"; then
    printf 'PASS %s\n' "$label" | tee -a "$LOG_DIR/results.txt"
    return 0
  fi
  printf 'FAIL %s (see %s)\n' "$label" "$raw" | tee -a "$LOG_DIR/results.txt"
  return 1
}

run_with_emulator_fix \
  location-visible-main-activity \
  'dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#injectedCoarseFixIsMinimizedAndStoredOnlyAsAZoneSummary' \
  COARSE_LOCATION_LISTENER_ACTIVE || exit 1

# Mobility is attempted only after a successful real coarse capture. The test emits its marker
# from the visible Activity immediately before starting the foreground service.
"$ADB" -s "$SERIAL" shell am instrument -w -r \
  -e class 'dev.atlas.coach.location.V62MobilityServiceInstrumentedTest#prepareSharingConsentBeforeHostInjectsFreshMobilityFix' \
  "$RUNNER" > "$LOG_DIR/mobility-prepare-consent.log" 2>&1
if ! rg -q 'OK \(1 test\)' "$LOG_DIR/mobility-prepare-consent.log"; then
  printf 'FAIL mobility-prepare-consent\n' | tee -a "$LOG_DIR/results.txt"
  exit 1
fi
printf 'PASS mobility-prepare-consent\n' | tee -a "$LOG_DIR/results.txt"

run_with_emulator_fix \
  mobility-visible-service-with-measurement \
  'dev.atlas.coach.location.V62MobilityServiceInstrumentedTest#visibleStartShowsStopNotificationAndServiceStopsWithoutRawTrail' \
  MOBILITY_LOCATION_LISTENER_ACTIVE || exit 1

printf 'PASS targeted coarse capture and measured visible mobility recipe; logs=%s\n' "$LOG_DIR" | tee -a "$LOG_DIR/results.txt"
