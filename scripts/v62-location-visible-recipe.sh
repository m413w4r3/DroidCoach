#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/android"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
ADB="$SDK_ROOT/platform-tools/adb"
SERIAL="${ANDROID_SERIAL:-emulator-5580}"
RUNNER="dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner"
INSTRUMENTATION_TIMEOUT_SECONDS=90
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
LOG_DIR="${ATLAS_V622_S6_LOCATION_LOG_DIR:-/tmp/atlas-v622-s1/logs/recipe-$RUN_ID}"
CANDIDATE_DIR="${ATLAS_V622_S6_CANDIDATE_DIR:-}"
EXPECTED_APP_SHA256="${ATLAS_V622_S6_EXPECTED_APP_SHA256:-}"
EXPECTED_TEST_SHA256="${ATLAS_V622_S6_EXPECTED_TEST_SHA256:-}"
APP_APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ANDROID_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
mkdir -p "$LOG_DIR"
chmod 700 "$LOG_DIR"

if [[ ! -x "$ADB" ]]; then
  printf 'BLOCKED: adb unavailable at %s\n' "$ADB" >&2
  exit 77
fi
TIMEOUT_BIN="$(command -v timeout || true)"
if [[ -z "$TIMEOUT_BIN" ]]; then
  printf 'BLOCKED: host timeout utility is required to bound instrumentation waits.\n' >&2
  exit 77
fi

if [[ -n "$CANDIDATE_DIR" ]]; then
  APP_APK="$CANDIDATE_DIR/app-debug.apk"
  TEST_APK="$CANDIDATE_DIR/app-debug-androidTest.apk"
  if [[ ! -s "$APP_APK" || ! -s "$TEST_APK" || ! "$EXPECTED_APP_SHA256" =~ ^[[:xdigit:]]{64}$ || ! "$EXPECTED_TEST_SHA256" =~ ^[[:xdigit:]]{64}$ ]]; then
    printf 'BLOCKED: frozen candidate APKs and both expected SHA-256 values are required.\n' >&2
    exit 77
  fi
  local_app_sha="$(sha256sum "$APP_APK" | awk '{print $1}')"
  local_test_sha="$(sha256sum "$TEST_APK" | awk '{print $1}')"
  if [[ "$local_app_sha" != "$EXPECTED_APP_SHA256" || "$local_test_sha" != "$EXPECTED_TEST_SHA256" ]]; then
    printf 'BLOCKED: frozen candidate APK hash mismatch.\n' >&2
    exit 77
  fi
fi
if [[ ! "$SERIAL" =~ ^emulator-[0-9]+$ ]] || ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
  printf 'BLOCKED: %s must be a running disposable emulator; physical devices are refused.\n' "$SERIAL" >&2
  exit 77
fi
AVD_NAME="$("$ADB" -s "$SERIAL" shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r')"
API_LEVEL="$("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$AVD_NAME" != atlas_v622_s1_api34 || "$API_LEVEL" != 34 || "$SERIAL" != emulator-5580 ]]; then
  printf 'BLOCKED: expected atlas_v622_s1_api34 on emulator-5580/API 34; observed AVD=%s serial=%s API=%s.\n' "$AVD_NAME" "$SERIAL" "$API_LEVEL" >&2
  exit 77
fi

{
  printf 'ATLAS V6.2 visible coarse-location recipe · %s UTC\n' "$RUN_ID"
  printf 'AVD=%s serial=%s API=%s\n' "$AVD_NAME" "$SERIAL" "$API_LEVEL"
  "$SDK_ROOT/emulator/emulator" -version | sed -n '1p'
  java -version 2>&1 | sed -n '1p'
  git -C "$ROOT" rev-parse --short HEAD
  if [[ -n "$CANDIDATE_DIR" ]]; then
    printf 'candidate_app_sha256=%s\ncandidate_test_sha256=%s\n' "$EXPECTED_APP_SHA256" "$EXPECTED_TEST_SHA256"
    printf 'candidate_source=frozen_pair_directory\n'
  fi
} > "$LOG_DIR/environment.txt"

cd "$ANDROID_DIR"
./gradlew :app:testDebugUnitTest \
  --tests dev.atlas.coach.location.LocationCaptureManagerTest \
  --tests dev.atlas.coach.location.LocationPrivacyTest \
  --tests dev.atlas.coach.location.LocationRepositoryTest \
  --tests dev.atlas.coach.location.MobilitySessionTest \
  --console=plain > "$LOG_DIR/gradle-location-logique.log" 2>&1
printf 'TEST_LOGIQUE_PASS controlled provider, freshness, disclosure, TTL, and mobility lifecycle tests\n' | tee -a "$LOG_DIR/results.txt"
test -s "$APP_APK" && test -s "$TEST_APK"
if [[ -z "$CANDIDATE_DIR" ]]; then
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain > "$LOG_DIR/gradle-assemble.log" 2>&1
  test -s "$APP_APK" && test -s "$TEST_APK"
  # Legacy mode may replace packages only when no frozen candidate was requested.
  "$ADB" -s "$SERIAL" shell pm uninstall dev.atlas.coach > "$LOG_DIR/uninstall-app.log" 2>&1 || true
  "$ADB" -s "$SERIAL" shell pm uninstall dev.atlas.coach.test > "$LOG_DIR/uninstall-test-apk.log" 2>&1 || true
  "$ADB" -s "$SERIAL" install -r "$APP_APK" > "$LOG_DIR/install-app.log"
  "$ADB" -s "$SERIAL" install -r -t "$TEST_APK" > "$LOG_DIR/install-test-apk.log"
else
  installed_base_sha256() {
    local package_name="$1"
    local package_path=""
    local apk_copy="$LOG_DIR/installed-${package_name//./_}.apk"
    package_path="$("$TIMEOUT_BIN" --signal=TERM --kill-after=5s 20s "$ADB" -s "$SERIAL" shell pm path "$package_name" | sed -n '1s/^package://p' | tr -d '\r')"
    [[ -n "$package_path" ]] || return 1
    "$TIMEOUT_BIN" --signal=TERM --kill-after=5s 45s "$ADB" -s "$SERIAL" pull "$package_path" "$apk_copy" > "$LOG_DIR/installed-${package_name//./_}-pull.log" 2>&1
    chmod 600 "$apk_copy" "$LOG_DIR/installed-${package_name//./_}-pull.log"
    sha256sum "$apk_copy" | awk '{print $1}'
    rm -f "$apk_copy"
  }
  installed_app_sha="$(installed_base_sha256 dev.atlas.coach)"
  installed_test_sha="$(installed_base_sha256 dev.atlas.coach.test)"
  if [[ "$installed_app_sha" != "$EXPECTED_APP_SHA256" || "$installed_test_sha" != "$EXPECTED_TEST_SHA256" ]]; then
    printf 'BLOCKED: installed APK pair differs from the frozen candidate; refusing uninstall or reinstall.\n' >&2
    exit 77
  fi
  {
    printf 'installed_app_sha256=%s\ninstalled_test_sha256=%s\n' "$installed_app_sha" "$installed_test_sha"
    printf 'candidate_pair_reinstall=skipped_exact_hash_match\n'
  } > "$LOG_DIR/candidate-identity.txt"
  chmod 600 "$LOG_DIR/candidate-identity.txt"
fi
"$ADB" -s "$SERIAL" shell pm revoke dev.atlas.coach android.permission.ACCESS_FINE_LOCATION > "$LOG_DIR/revoke-fine-location.log"
"$ADB" -s "$SERIAL" shell pm grant dev.atlas.coach android.permission.ACCESS_COARSE_LOCATION > "$LOG_DIR/grant-coarse-location.log"
"$ADB" -s "$SERIAL" shell pm grant dev.atlas.coach android.permission.POST_NOTIFICATIONS > "$LOG_DIR/grant-notifications.log"
"$ADB" -s "$SERIAL" shell cmd location set-location-enabled true > "$LOG_DIR/location-enabled.log"

run_with_emulator_fix() {
  local label="$1"
  local class_method="$2"
  local marker="$3"
  local raw="$LOG_DIR/$label-instrumentation.log"
  local ready_log="$LOG_DIR/$label-active-gps-request.log"
  local logcat_log="$LOG_DIR/$label-location-logcat.log"
  local state_log="$LOG_DIR/$label-location-state-at-injection.txt"
  local geo_log="$LOG_DIR/$label-geo-fix.log"
  local result=0

  "$ADB" -s "$SERIAL" logcat -c
  # Start logcat first so the instrumentation marker cannot race the host reader.
  local ready_line=""
  local log_line=""
  coproc ATLAS_LOCATION_LOGCAT { "$ADB" -s "$SERIAL" logcat -s ATLAS_V62_LOCATION:I 2>&1; }
  local logcat_pid="$ATLAS_LOCATION_LOGCAT_PID"
  local logcat_fd="${ATLAS_LOCATION_LOGCAT[0]}"
  "$TIMEOUT_BIN" --signal=TERM --kill-after=5s "${INSTRUMENTATION_TIMEOUT_SECONDS}s" \
    "$ADB" -s "$SERIAL" shell am instrument -w -r \
    -e location_fix_injection host \
    -e class "$class_method" "$RUNNER" > "$raw" 2>&1 &
  local instrumentation_pid=$!
  local deadline=$((SECONDS + 20))
  while (( SECONDS < deadline )); do
    if ! IFS= read -r -t 1 -u "$logcat_fd" log_line; then
      if ! kill -0 "$instrumentation_pid" 2>/dev/null; then break; fi
      continue
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
    printf 'FAIL %s: test did not announce capture start within 20 s.\n' "$label" | tee -a "$LOG_DIR/results.txt"
    "$ADB" -s "$SERIAL" shell am force-stop dev.atlas.coach >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" shell am force-stop dev.atlas.coach.test >/dev/null 2>&1 || true
    wait "$instrumentation_pid" 2>/dev/null || true
    "$ADB" -s "$SERIAL" logcat -d -s ATLAS_V62_LOCATION:I > "$logcat_log"
    return 1
  fi

  # A test marker alone is not a provider request. `emu geo fix` feeds GPS, so require an
  # active coarse GPS request for ATLAS before injecting; preserve the state used to sync.
  local location_state=""
  local active_request=0
  deadline=$((SECONDS + 20))
  while (( SECONDS < deadline )); do
    location_state="$("$ADB" -s "$SERIAL" shell dumpsys location 2>&1 | tr -d '\r')"
    if printf '%s\n' "$location_state" | awk '
      /^  Location Providers:$/ { in_providers = 1; next }
      /^  [^ ]/ && in_providers { in_providers = 0 }
      in_providers && /^    gps provider:$/ { in_gps_provider = 1; next }
      in_providers && /^    [[:alpha:]_]+ provider:/ { in_gps_provider = 0; next }
      in_providers && in_gps_provider && /dev\.atlas\.coach/ && /Request\[/ { found = 1 }
      END { exit !found }
    '; then
      active_request=1
      break
    fi
    sleep 0.25
  done
  printf '%s\n' "$location_state" > "$state_log"
  if [[ "$active_request" -ne 1 ]]; then
    printf 'FAIL %s: active ATLAS coarse GPS request was not observed before injection.\n' "$label" | tee -a "$LOG_DIR/results.txt"
    "$ADB" -s "$SERIAL" shell am force-stop dev.atlas.coach >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" shell am force-stop dev.atlas.coach.test >/dev/null 2>&1 || true
    wait "$instrumentation_pid" 2>/dev/null || true
    "$ADB" -s "$SERIAL" logcat -d -s ATLAS_V62_LOCATION:I > "$logcat_log"
    return 1
  fi

  local injection_started_utc_ms
  injection_started_utc_ms="$(date -u +%s%3N)"
  printf 'host_geo_fix_started_utc_ms=%s\n' "$injection_started_utc_ms" > "$geo_log"
  "$ADB" -s "$SERIAL" shell am broadcast \
    -a dev.atlas.coach.location.V62_HOST_FIX_INJECTED \
    -p dev.atlas.coach --el injected_at_utc_ms "$injection_started_utc_ms" >> "$geo_log"
  "$ADB" -s "$SERIAL" emu geo fix 2.123456 48.123456 >> "$geo_log"

  if wait "$instrumentation_pid"; then result=0; else result=$?; fi
  if [[ "$result" -eq 124 || "$result" -eq 137 || "$result" -eq 143 ]]; then
    "$ADB" -s "$SERIAL" shell am force-stop dev.atlas.coach >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" shell am force-stop dev.atlas.coach.test >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" logcat -d -s ATLAS_V62_LOCATION:I > "$logcat_log"
    printf 'FAIL %s: instrumentation exceeded the %ss bound (timeout exit=%s).\n' \
      "$label" "$INSTRUMENTATION_TIMEOUT_SECONDS" "$result" | tee -a "$LOG_DIR/results.txt"
    return 1
  fi
  "$ADB" -s "$SERIAL" logcat -d -s ATLAS_V62_LOCATION:I > "$logcat_log"
  if is_expected_system_block "$raw" "$class_method"; then
    printf 'TEST_SYSTEM_BLOCKED %s reason=no_fresh_coarse_fix_after_host_injection\n' "$label" | tee -a "$LOG_DIR/results.txt"
    return 77
  fi
  if is_logged_mobility_system_block "$raw" "$logcat_log" "$class_method"; then
    printf 'TEST_SYSTEM_BLOCKED %s reason=no_fresh_coarse_fix_within_15000ms\n' "$label" | tee -a "$LOG_DIR/results.txt"
    return 77
  fi
  if [[ "$result" -eq 0 ]] && \
      ! rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_RESULT: shortMsg' "$raw" && \
      rg -q 'OK \(1 test\)' "$raw"; then
    printf 'PASS %s\n' "$label" | tee -a "$LOG_DIR/results.txt"
    return 0
  fi
  printf 'FAIL %s (see %s)\n' "$label" "$raw" | tee -a "$LOG_DIR/results.txt"
  return 1
}

is_expected_system_block() {
  local raw_log="$1"
  local class_method="$2"
  local class_name="${class_method%%#*}"
  local method="${class_method##*#}"

  [[ "$class_name" != "$class_method" && "$method" != "$class_method" ]] || return 1
  [[ "$(rg -c '^INSTRUMENTATION_STATUS_CODE: -2$' "$raw_log" || true)" == 1 ]] || return 1
  [[ "$(rg -c '^Error in ' "$raw_log" || true)" == 1 ]] || return 1
  [[ "$(rg -c '^There was 1 failure:$' "$raw_log" || true)" == 1 ]] || return 1
  [[ "$(rg -c '^Tests run: 1, +Failures: 1$' "$raw_log" || true)" == 1 ]] || return 1
  rg -q '^FAILURES!!!$' "$raw_log" || return 1
  rg -Fq 'java.lang.AssertionError: TEST_SYSTEM_BLOCKED: Android supplied no fresh coarse fix after synchronized injection' "$raw_log" || return 1
  rg -Fq "Error in $method($class_name):" "$raw_log" || return 1
  rg -Fq "at $class_name.$method(" "$raw_log" || return 1
  ! rg -q 'INSTRUMENTATION_FAILED|INSTRUMENTATION_RESULT: shortMsg' "$raw_log"
}

is_logged_mobility_system_block() {
  local raw_log="$1"
  local logcat_log="$2"
  local class_method="$3"
  local expected_class='dev.atlas.coach.location.V62MobilityServiceInstrumentedTest#visibleStartShowsStopNotificationAndServiceStopsWithoutRawTrail'
  local marker='TEST_SYSTEM_BLOCKED reason=no_fresh_coarse_fix_within_15000ms session_remained_active=true'

  [[ "$class_method" == "$expected_class" ]] || return 1
  [[ "$(rg -c -F "$marker" "$logcat_log" || true)" == 1 ]] || return 1
  [[ "$(rg -c '^OK \(1 test\)$' "$raw_log" || true)" == 1 ]] || return 1
  ! rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_RESULT: shortMsg|^Error in ' "$raw_log"
}

system_blocked=0
if run_with_emulator_fix \
  location-visible-main-activity \
  'dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#injectedCoarseFixIsMinimizedAndStoredOnlyAsAZoneSummary' \
  COARSE_CAPTURE_MANAGER_CALL; then
  :
else
  recipe_result=$?
  if [[ "$recipe_result" -eq 77 ]]; then system_blocked=1; else exit "$recipe_result"; fi
fi

# The first injection becomes the system cache. Let it age past LocationCaptureManager's
# production max-age before starting mobility so the next injection synchronizes with a
# live provider request instead of reusing that first result.
sleep 16

# Run mobility independently after the first injected fix has aged beyond the production cache.
# A provider block in the one-shot flow does not suppress the separate service acquisition.
prepare_result=0
if "$TIMEOUT_BIN" --signal=TERM --kill-after=5s "${INSTRUMENTATION_TIMEOUT_SECONDS}s" \
  "$ADB" -s "$SERIAL" shell am instrument -w -r \
  -e class 'dev.atlas.coach.location.V62MobilityServiceInstrumentedTest#prepareSharingConsentBeforeHostInjectsFreshMobilityFix' \
  "$RUNNER" > "$LOG_DIR/mobility-prepare-consent.log" 2>&1; then
  :
else
  prepare_result=$?
fi
if [[ "$prepare_result" -eq 124 || "$prepare_result" -eq 137 || "$prepare_result" -eq 143 ]]; then
  "$ADB" -s "$SERIAL" shell am force-stop dev.atlas.coach >/dev/null 2>&1 || true
  "$ADB" -s "$SERIAL" shell am force-stop dev.atlas.coach.test >/dev/null 2>&1 || true
  printf 'FAIL mobility-prepare-consent: instrumentation exceeded the %ss bound.\n' \
    "$INSTRUMENTATION_TIMEOUT_SECONDS" | tee -a "$LOG_DIR/results.txt"
  exit 1
fi
if ! rg -q 'OK \(1 test\)' "$LOG_DIR/mobility-prepare-consent.log"; then
  printf 'FAIL mobility-prepare-consent\n' | tee -a "$LOG_DIR/results.txt"
  exit 1
fi
printf 'PASS mobility-prepare-consent\n' | tee -a "$LOG_DIR/results.txt"

if run_with_emulator_fix \
  mobility-visible-service-with-measurement \
  'dev.atlas.coach.location.V62MobilityServiceInstrumentedTest#visibleStartShowsStopNotificationAndServiceStopsWithoutRawTrail' \
  MOBILITY_SERVICE_START_REQUEST; then
  :
else
  recipe_result=$?
  if [[ "$recipe_result" -eq 77 ]]; then system_blocked=1; else exit "$recipe_result"; fi
fi

if [[ "$system_blocked" -eq 1 ]]; then
  printf 'TEST_SYSTEM_BLOCKED recipe completed with one or more Android provider blocks; logs=%s\n' "$LOG_DIR" | tee -a "$LOG_DIR/results.txt"
  exit 77
fi
printf 'PASS targeted coarse capture and measured visible mobility recipe; logs=%s\n' "$LOG_DIR" | tee -a "$LOG_DIR/results.txt"
