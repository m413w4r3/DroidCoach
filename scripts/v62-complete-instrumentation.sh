#!/usr/bin/env bash
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/android"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/nill/Android/Sdk}}"
ADB="$SDK_ROOT/platform-tools/adb"
EXPECTED_SERIAL="emulator-5580"
EXPECTED_AVD="atlas_v622_s1_api34"
SERIAL="${ANDROID_SERIAL:-$EXPECTED_SERIAL}"
RUNNER="dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner"
APP_APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ANDROID_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
CANDIDATE_DIR="${ATLAS_V622_S6_CANDIDATE_DIR:-/tmp/atlas-v622-s6-s5-s7-p1-01-corrected-test-candidate}"
CANDIDATE_APP_APK="$CANDIDATE_DIR/app-debug.apk"
CANDIDATE_TEST_APK="$CANDIDATE_DIR/app-debug-androidTest.apk"
EXPECTED_APP_SHA256="${ATLAS_V622_CANDIDATE_APP_SHA256:-ce10c968988fb3383e06ee94b3c1eb3723f37efcf6035597601643916a9f298a}"
EXPECTED_TEST_SHA256="${ATLAS_V622_CANDIDATE_TEST_SHA256:-8edb71a818ac01a4c3195cab48def493be117587159f939962f4f15ed053014d}"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
RUN_START="$(date -u -Iseconds)"
LOG_DIR="${ATLAS_V622_S6_LOG_DIR:-/tmp/atlas-v622-s6/full-harness/$RUN_ID}"
GRADLE_STATUS=0
STATE_SNAPSHOT=0

LOG_DIR="$(realpath -m -- "$LOG_DIR")"
case "$LOG_DIR" in
  /tmp/atlas-v622-s6/*) ;;
  *) printf 'BLOCKED: instrumentation evidence must stay under /tmp/atlas-v622-s6.\n' >&2; exit 77 ;;
esac
CAPTURE_DIR="$LOG_DIR/captures"
umask 077
mkdir -p "$LOG_DIR" "$CAPTURE_DIR"
chmod 700 "$LOG_DIR" "$CAPTURE_DIR"
export ADB_SERVER_PORT=5037

if [[ ! -x "$ADB" || ! -x "$ANDROID_DIR/gradlew" ]] || ! command -v timeout >/dev/null 2>&1; then
  printf 'BLOCKED: adb, Gradle wrapper or timeout unavailable. SDK=%s\n' "$SDK_ROOT" | tee "$LOG_DIR/preflight.txt" >&2
  exit 77
fi
if [[ "$SERIAL" != "$EXPECTED_SERIAL" ]] || ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
  printf 'BLOCKED: expected a running disposable AVD at %s.\n' "$SERIAL" | tee "$LOG_DIR/preflight.txt" >&2
  exit 77
fi
AVD_NAME="$("$ADB" -s "$SERIAL" shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r')"
API_LEVEL="$("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
MODEL="$("$ADB" -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')"
if ! DEVICE_SUMMARY="$(python3 - "$ADB" "$SERIAL" <<'PY'
import os
import subprocess
import sys

try:
    result = subprocess.run(
        [sys.argv[1], "devices"], text=True, stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL, check=False, timeout=20,
        env={**os.environ, "ADB_SERVER_PORT": "5037"},
    )
except (OSError, subprocess.TimeoutExpired):
    raise SystemExit(1)
rows = [line.split() for line in result.stdout.splitlines()[1:] if line.strip()]
assigned = [row for row in rows if row[0] == sys.argv[2] and len(row) >= 2 and row[1] == "device"]
other_count = len(rows) - len(assigned)
assigned_state = "device" if len(assigned) == 1 else "missing_or_ambiguous"
print(f"targets={len(rows)} assigned={assigned_state} other_targets={other_count}")
raise SystemExit(result.returncode)
PY
)"; then
  printf 'BLOCKED: ADB target inventory failed or timed out.\n' | tee "$LOG_DIR/preflight.txt" >&2
  exit 77
fi
if [[ "$AVD_NAME" != "$EXPECTED_AVD" || "$API_LEVEL" != 34 || "$DEVICE_SUMMARY" != "targets=1 assigned=device other_targets=0" ]]; then
  printf 'BLOCKED: expected only %s / API 34; observed AVD=%s API=%s inventory=%s\n' \
    "$EXPECTED_AVD" "$AVD_NAME" "$API_LEVEL" "$DEVICE_SUMMARY" | tee "$LOG_DIR/preflight.txt" >&2
  exit 77
fi

if [[ ! -s "$CANDIDATE_APP_APK" || ! -s "$CANDIDATE_TEST_APK" ]]; then
  printf 'BLOCKED: frozen candidate APK pair is unavailable.\n' | tee "$LOG_DIR/preflight.txt" >&2
  exit 77
fi
CANDIDATE_APP_ACTUAL="$(sha256sum "$CANDIDATE_APP_APK" | awk '{print $1}')"
CANDIDATE_TEST_ACTUAL="$(sha256sum "$CANDIDATE_TEST_APK" | awk '{print $1}')"
if [[ "$CANDIDATE_APP_ACTUAL" != "$EXPECTED_APP_SHA256" || "$CANDIDATE_TEST_ACTUAL" != "$EXPECTED_TEST_SHA256" ]]; then
  printf 'BLOCKED: frozen candidate APK hashes differ from the declared pair.\n' | tee "$LOG_DIR/preflight.txt" >&2
  exit 77
fi
{
  printf 'candidate_app_sha256=%s\ncandidate_test_sha256=%s\n' "$CANDIDATE_APP_ACTUAL" "$CANDIDATE_TEST_ACTUAL"
} > "$LOG_DIR/candidate-identity.txt"

run_instrument() {
  local status=0
  if timeout --signal=TERM --kill-after=10s 90s "$ADB" -s "$SERIAL" shell am instrument "$@"; then
    status=0
  else
    status=$?
  fi
  if [[ "$status" -eq 124 || "$status" -eq 137 ]]; then
    printf 'FAIL: instrumentation exceeded 90 seconds; stopping both test packages.\n' >&2
    "$ADB" -s "$SERIAL" shell am force-stop dev.atlas.coach.test >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" shell am force-stop dev.atlas.coach >/dev/null 2>&1 || true
  fi
  return "$status"
}

runtime_permission_state() {
  local permission="$1" line
  line="$("$ADB" -s "$SERIAL" shell dumpsys package dev.atlas.coach | rg -F -m1 "$permission:" | tr -d '\r' || true)"
  if [[ "$line" == *"granted=true"* ]]; then
    printf 'granted'
  elif [[ "$line" == *"granted=false"* ]]; then
    printf 'denied'
  else
    printf 'unknown'
  fi
}

appops_mode() {
  local scope="$1" output mode
  if [[ "$scope" == uid ]]; then
    output="$("$ADB" -s "$SERIAL" shell appops get --uid dev.atlas.coach GET_USAGE_STATS 2>&1 | tr -d '\r')"
  else
    output="$("$ADB" -s "$SERIAL" shell appops get dev.atlas.coach GET_USAGE_STATS 2>&1 | tr -d '\r')"
  fi
  mode="$(printf '%s\n' "$output" | sed -nE 's/^.*GET_USAGE_STATS:[[:space:]]*([[:alpha:]_]+).*$/\1/p' | tail -n 1)"
  if [[ -z "$mode" ]]; then
    mode="$(printf '%s\n' "$output" | sed -nE 's/^Default mode:[[:space:]]*([[:alpha:]_]+).*$/\1/p' | tail -n 1)"
  fi
  printf '%s' "${mode:-default}"
}

INITIAL_COARSE="$(runtime_permission_state android.permission.ACCESS_COARSE_LOCATION)"
INITIAL_FINE="$(runtime_permission_state android.permission.ACCESS_FINE_LOCATION)"
INITIAL_NOTIFICATIONS="$(runtime_permission_state android.permission.POST_NOTIFICATIONS)"
INITIAL_UID_USAGE_OP="$(appops_mode uid)"
INITIAL_PACKAGE_USAGE_OP="$(appops_mode package)"
INITIAL_LOCATION_ENABLED="$("$ADB" -s "$SERIAL" shell cmd location is-location-enabled 2>/dev/null | tr -d '\r')"
INITIAL_WM_SIZE="$("$ADB" -s "$SERIAL" shell wm size | sed -n 's/^Override size: //p' | tr -d '\r' | tail -n 1)"
INITIAL_WM_DENSITY="$("$ADB" -s "$SERIAL" shell wm density | sed -n 's/^Override density: //p' | tr -d '\r' | tail -n 1)"
FIXTURE_WAS_INSTALLED=0
if "$ADB" -s "$SERIAL" shell pm list packages dev.atlas.fixture.distraction | rg -Fqx 'package:dev.atlas.fixture.distraction'; then
  FIXTURE_WAS_INSTALLED=1
fi
if [[ "$INITIAL_COARSE" == unknown || "$INITIAL_FINE" == unknown || "$INITIAL_NOTIFICATIONS" == unknown ]]; then
  printf 'BLOCKED: could not read the initial runtime permission state from the disposable AVD.\n' | tee "$LOG_DIR/preflight.txt" >&2
  exit 77
fi
STATE_SNAPSHOT=1

restore_avd_state() {
  [[ "$STATE_SNAPSHOT" == 1 ]] || return 0
  local status_file="$LOG_DIR/state-restored.txt"
  : > "$status_file"
  for pair in \
    "ACCESS_COARSE_LOCATION:$INITIAL_COARSE" \
    "ACCESS_FINE_LOCATION:$INITIAL_FINE" \
    "POST_NOTIFICATIONS:$INITIAL_NOTIFICATIONS"; do
    local name="${pair%%:*}" state="${pair#*:}" permission="android.permission.${pair%%:*}"
    if [[ "$state" == granted ]]; then
      "$ADB" -s "$SERIAL" shell pm grant dev.atlas.coach "$permission" >> "$status_file" 2>&1 || true
    else
      "$ADB" -s "$SERIAL" shell pm revoke dev.atlas.coach "$permission" >> "$status_file" 2>&1 || true
    fi
    printf '%s=%s\n' "$permission" "$state" >> "$status_file"
  done
  "$ADB" -s "$SERIAL" shell appops set --uid dev.atlas.coach GET_USAGE_STATS "$INITIAL_UID_USAGE_OP" >> "$status_file" 2>&1 || true
  "$ADB" -s "$SERIAL" shell appops set dev.atlas.coach GET_USAGE_STATS "$INITIAL_PACKAGE_USAGE_OP" >> "$status_file" 2>&1 || true
  printf 'appops_uid_GET_USAGE_STATS=%s\nappops_package_GET_USAGE_STATS=%s\n' \
    "$INITIAL_UID_USAGE_OP" "$INITIAL_PACKAGE_USAGE_OP" >> "$status_file"
  if [[ "$INITIAL_LOCATION_ENABLED" == true || "$INITIAL_LOCATION_ENABLED" == false ]]; then
    "$ADB" -s "$SERIAL" shell cmd location set-location-enabled "$INITIAL_LOCATION_ENABLED" >> "$status_file" 2>&1 || true
    printf 'location_enabled=%s\n' "$INITIAL_LOCATION_ENABLED" >> "$status_file"
  fi
  if [[ -n "$INITIAL_WM_SIZE" ]]; then
    "$ADB" -s "$SERIAL" shell wm size "$INITIAL_WM_SIZE" >> "$status_file" 2>&1 || true
    printf 'wm_size=%s\n' "$INITIAL_WM_SIZE" >> "$status_file"
  else
    "$ADB" -s "$SERIAL" shell wm size reset >> "$status_file" 2>&1 || true
    printf 'wm_size=reset\n' >> "$status_file"
  fi
  if [[ -n "$INITIAL_WM_DENSITY" ]]; then
    "$ADB" -s "$SERIAL" shell wm density "$INITIAL_WM_DENSITY" >> "$status_file" 2>&1 || true
    printf 'wm_density=%s\n' "$INITIAL_WM_DENSITY" >> "$status_file"
  else
    "$ADB" -s "$SERIAL" shell wm density reset >> "$status_file" 2>&1 || true
    printf 'wm_density=reset\n' >> "$status_file"
  fi
  if [[ "$FIXTURE_WAS_INSTALLED" == 0 ]] && \
      "$ADB" -s "$SERIAL" shell pm list packages dev.atlas.fixture.distraction | rg -Fqx 'package:dev.atlas.fixture.distraction'; then
    "$ADB" -s "$SERIAL" uninstall dev.atlas.fixture.distraction >> "$status_file" 2>&1 || true
    printf 'fixture_package=uninstalled_after_run\n' >> "$status_file"
  fi
}
trap restore_avd_state EXIT

export ANDROID_SERIAL="$SERIAL"
{
  printf 'ATLAS V6.2 complete instrumentation recipe\nrun_id=%s\nAVD=%s\nserial=%s\nAPI=%s\nmodel=%s\n' \
    "$RUN_ID" "$AVD_NAME" "$SERIAL" "$API_LEVEL" "$MODEL"
  printf 'host_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)"
  java -version 2>&1 | sed -n '1p'
  "$SDK_ROOT/emulator/emulator" -version 2>&1 | sed -n '1p'
  git -C "$ROOT" rev-parse --short HEAD
  printf 'initial_coarse_location=%s\ninitial_fine_location=%s\ninitial_notifications=%s\n' \
    "$INITIAL_COARSE" "$INITIAL_FINE" "$INITIAL_NOTIFICATIONS"
  printf 'initial_usage_appop_uid=%s\ninitial_usage_appop_package=%s\ninitial_location_enabled=%s\n' \
    "$INITIAL_UID_USAGE_OP" "$INITIAL_PACKAGE_USAGE_OP" "$INITIAL_LOCATION_ENABLED"
  printf 'initial_wm_size=%s\ninitial_wm_density=%s\nfixture_installed=%s\n' \
    "${INITIAL_WM_SIZE:-physical-default}" "${INITIAL_WM_DENSITY:-physical-default}" "$FIXTURE_WAS_INSTALLED"
} > "$LOG_DIR/environment.txt"

cd "$ANDROID_DIR"
if ! ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain > "$LOG_DIR/assemble.log" 2>&1; then
  printf 'BLOCKED: app/androidTest assembly failed; see assemble.log\n' | tee "$LOG_DIR/status.txt"
  exit 1
fi

BUILT_APP_SHA256="$(sha256sum "$APP_APK" | awk '{print $1}')"
BUILT_TEST_SHA256="$(sha256sum "$TEST_APK" | awk '{print $1}')"
if [[ "$BUILT_APP_SHA256" != "$EXPECTED_APP_SHA256" || "$BUILT_TEST_SHA256" != "$EXPECTED_TEST_SHA256" ]]; then
  printf 'BLOCKED: assembled APK pair does not match the frozen candidate; nothing installed.\n' | tee "$LOG_DIR/candidate-build-mismatch.txt" >&2
  printf 'candidate_app_sha256=%s\nbuilt_app_sha256=%s\ncandidate_test_sha256=%s\nbuilt_test_sha256=%s\n' \
    "$EXPECTED_APP_SHA256" "$BUILT_APP_SHA256" "$EXPECTED_TEST_SHA256" "$BUILT_TEST_SHA256" >> "$LOG_DIR/candidate-build-mismatch.txt"
  exit 77
fi
printf 'assembled_app_sha256=%s\nassembled_test_sha256=%s\n' "$BUILT_APP_SHA256" "$BUILT_TEST_SHA256" \
  >> "$LOG_DIR/candidate-identity.txt"

"$ADB" -s "$SERIAL" install -r "$APP_APK" > "$LOG_DIR/install-app.log" 2>&1 || exit 1
"$ADB" -s "$SERIAL" install -r -t "$TEST_APK" > "$LOG_DIR/install-test-apk.log" 2>&1 || exit 1
# This recipe owns only the dedicated AVD's app and instrumentation package data.
"$ADB" -s "$SERIAL" shell pm clear dev.atlas.coach > "$LOG_DIR/clear-app-data.log" 2>&1 || exit 1
"$ADB" -s "$SERIAL" shell pm clear dev.atlas.coach.test > "$LOG_DIR/clear-test-data.log" 2>&1 || exit 1
"$ADB" -s "$SERIAL" shell pm revoke dev.atlas.coach android.permission.ACCESS_FINE_LOCATION > "$LOG_DIR/revoke-fine-location.log" 2>&1 || exit 1
# The app does not declare ACCESS_BACKGROUND_LOCATION; the instrumentation asserts it stays denied.
printf 'background_location=not_requested_and_denied_by_default\n' > "$LOG_DIR/revoke-background-location.log"
"$ADB" -s "$SERIAL" shell pm grant dev.atlas.coach android.permission.ACCESS_COARSE_LOCATION > "$LOG_DIR/grant-coarse-location.log" 2>&1 || exit 1
"$ADB" -s "$SERIAL" shell pm grant dev.atlas.coach android.permission.POST_NOTIFICATIONS > "$LOG_DIR/grant-notifications.log" 2>&1 || exit 1
"$ADB" -s "$SERIAL" shell cmd location set-location-enabled true > "$LOG_DIR/enable-location.log" 2>&1 || exit 1
"$ADB" -s "$SERIAL" shell wm size 1080x2400 > "$LOG_DIR/display-360dp.log" 2>&1 || exit 1
"$ADB" -s "$SERIAL" shell wm density 480 >> "$LOG_DIR/display-360dp.log" 2>&1 || exit 1

"$ADB" -s "$SERIAL" logcat -c

set +e
PRE_RUN_APP_SHA256="$(sha256sum "$APP_APK" | awk '{print $1}')"
PRE_RUN_TEST_SHA256="$(sha256sum "$TEST_APK" | awk '{print $1}')"
if [[ "$PRE_RUN_APP_SHA256" != "$EXPECTED_APP_SHA256" || "$PRE_RUN_TEST_SHA256" != "$EXPECTED_TEST_SHA256" ]]; then
  printf 'BLOCKED: APK pair changed before connected instrumentation; runner not started.\n' | tee "$LOG_DIR/candidate-pre-run-mismatch.txt" >&2
  printf 'app_sha256=%s\ntest_sha256=%s\n' "$PRE_RUN_APP_SHA256" "$PRE_RUN_TEST_SHA256" >> "$LOG_DIR/candidate-pre-run-mismatch.txt"
  exit 77
fi
./gradlew :app:connectedDebugAndroidTest -x :app:assembleDebug -x :app:assembleDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=dev.atlas.coach.app.V62RealGatewayContextBridgeTest \
  -Pandroid.testInstrumentationRunnerArguments.expected_width_dp=360 \
  --console=plain > "$LOG_DIR/connected-debug-android-test.log" 2>&1
GRADLE_STATUS=$?
set -e

mkdir -p "$LOG_DIR/android-test-results"
RESULT_ROOT="$ANDROID_DIR/app/build/outputs/androidTest-results/connected"
if [[ -d "$RESULT_ROOT" ]]; then
  while IFS= read -r -d '' result_xml; do
    relative_xml="${result_xml#"$RESULT_ROOT"/}"
    mkdir -p "$LOG_DIR/android-test-results/$(dirname "$relative_xml")"
    cp "$result_xml" "$LOG_DIR/android-test-results/$relative_xml"
  done < <(find "$RESULT_ROOT" -type f -name 'TEST-*.xml' -newermt "$RUN_START" -print0 2>/dev/null)
fi
"$ADB" -s "$SERIAL" logcat -d -s ATLAS_TEST_FILTER:I > "$LOG_DIR/backend-filter.log" 2>&1
"$ADB" -s "$SERIAL" logcat -c

# AppOps is changed only by the host ADB shell on the disposable AVD. The
# instrumentation methods assert the supplied precondition and never set AppOps.
set +e
"$ADB" -s "$SERIAL" install -r "$APP_APK" > "$LOG_DIR/reinstall-app-for-host-tests.log" 2>&1
external_app_install_status=$?
"$ADB" -s "$SERIAL" install -r -t "$TEST_APK" > "$LOG_DIR/reinstall-test-apk-for-host-tests.log" 2>&1
external_test_install_status=$?
if [[ "$external_app_install_status" -eq 0 && "$external_test_install_status" -eq 0 ]]; then
  "$ADB" -s "$SERIAL" shell pm revoke dev.atlas.coach android.permission.ACCESS_FINE_LOCATION > "$LOG_DIR/external-revoke-fine-location.log" 2>&1
  external_fine_status=$?
  "$ADB" -s "$SERIAL" shell pm grant dev.atlas.coach android.permission.ACCESS_COARSE_LOCATION > "$LOG_DIR/external-grant-coarse-location.log" 2>&1
  external_coarse_status=$?
  "$ADB" -s "$SERIAL" shell pm grant dev.atlas.coach android.permission.POST_NOTIFICATIONS > "$LOG_DIR/external-grant-notifications.log" 2>&1
  external_notification_status=$?
  "$ADB" -s "$SERIAL" shell cmd location set-location-enabled true > "$LOG_DIR/external-enable-location.log" 2>&1
  external_location_status=$?
else
  external_fine_status=1
  external_coarse_status=1
  external_notification_status=1
  external_location_status=1
fi
external_packages_ready=0
if [[ "$external_app_install_status" -eq 0 && "$external_test_install_status" -eq 0 &&
      "$external_fine_status" -eq 0 && "$external_coarse_status" -eq 0 &&
      "$external_notification_status" -eq 0 && "$external_location_status" -eq 0 ]]; then
  external_packages_ready=1
fi
printf 'app_install=%s\ntest_apk_install=%s\nfine_permission=%s\ncoarse_permission=%s\nnotification_permission=%s\nlocation_enabled=%s\n' \
  "$external_app_install_status" "$external_test_install_status" "$external_fine_status" \
  "$external_coarse_status" "$external_notification_status" "$external_location_status" \
  > "$LOG_DIR/host-test-precondition-status.txt"
"$ADB" -s "$SERIAL" shell appops set --uid dev.atlas.coach GET_USAGE_STATS ignore > "$LOG_DIR/appops-denial-prepare.log" 2>&1
uid_ignore_status=$?
"$ADB" -s "$SERIAL" shell appops set dev.atlas.coach GET_USAGE_STATS ignore >> "$LOG_DIR/appops-denial-prepare.log" 2>&1
package_ignore_status=$?
if [[ "$external_packages_ready" == 1 && "$uid_ignore_status" -eq 0 && "$package_ignore_status" -eq 0 ]]; then
  run_instrument -w -r -e usage_appops_denied "$RUN_ID" -e class \
    'dev.atlas.coach.usage.UsageFixtureInstrumentationTest#actualAppOpsDenialStopsBeforeQueryingUsageStats' "$RUNNER" \
    > "$LOG_DIR/usage-actual-appops-denial.log" 2>&1
  USAGE_DENIAL_STATUS=$?
else
  USAGE_DENIAL_STATUS=77
fi
"$ADB" -s "$SERIAL" shell appops set --uid dev.atlas.coach GET_USAGE_STATS "$INITIAL_UID_USAGE_OP" \
  > "$LOG_DIR/appops-denial-restore.log" 2>&1
"$ADB" -s "$SERIAL" shell appops set dev.atlas.coach GET_USAGE_STATS "$INITIAL_PACKAGE_USAGE_OP" \
  >> "$LOG_DIR/appops-denial-restore.log" 2>&1

FIXTURE_BUILD_STATUS=0
FIXTURE_INSTALL_STATUS=0
FIXTURE_TEST_STATUS=77
bash "$ROOT/android/fixtures/build-distraction-app.sh" --console=plain > "$LOG_DIR/fixture-build.log" 2>&1
FIXTURE_BUILD_STATUS=$?
FIXTURE_APK="$ROOT/android/fixtures/distraction-app/app/build/outputs/apk/debug/app-debug.apk"
if [[ "$FIXTURE_BUILD_STATUS" -eq 0 && -f "$FIXTURE_APK" ]]; then
  "$ADB" -s "$SERIAL" install -r "$FIXTURE_APK" > "$LOG_DIR/fixture-install.log" 2>&1
  FIXTURE_INSTALL_STATUS=$?
  if [[ "$FIXTURE_INSTALL_STATUS" -eq 0 ]]; then
    "$ADB" -s "$SERIAL" shell appops set --uid dev.atlas.coach GET_USAGE_STATS allow \
      > "$LOG_DIR/appops-fixture-prepare.log" 2>&1
    uid_allow_status=$?
    "$ADB" -s "$SERIAL" shell appops set dev.atlas.coach GET_USAGE_STATS allow \
      >> "$LOG_DIR/appops-fixture-prepare.log" 2>&1
    package_allow_status=$?
    "$ADB" -s "$SERIAL" shell pm grant dev.atlas.coach android.permission.POST_NOTIFICATIONS \
      >> "$LOG_DIR/appops-fixture-prepare.log" 2>&1
    notification_grant_status=$?
    if [[ "$uid_allow_status" -eq 0 && "$package_allow_status" -eq 0 && "$notification_grant_status" -eq 0 ]]; then
      run_instrument -w -r -e usage_fixture_ready "$RUN_ID" -e class \
        'dev.atlas.coach.usage.UsageFixtureInstrumentationTest#fourRealFixtureOpensReachTheRuleAfterReturningToAtlas' "$RUNNER" \
        > "$LOG_DIR/usage-four-fixture-opens.log" 2>&1
      FIXTURE_TEST_STATUS=$?
    fi
  fi
fi
"$ADB" -s "$SERIAL" shell appops set --uid dev.atlas.coach GET_USAGE_STATS "$INITIAL_UID_USAGE_OP" \
  > "$LOG_DIR/appops-fixture-restore.log" 2>&1
"$ADB" -s "$SERIAL" shell appops set dev.atlas.coach GET_USAGE_STATS "$INITIAL_PACKAGE_USAGE_OP" \
  >> "$LOG_DIR/appops-fixture-restore.log" 2>&1
set -e

# The coarse-location method stays filtered in this recipe: the synchronized
# ADB injection was exercised in S11 (one fresh fix, then a real timeout). A
# separate harness invocation can pass -e location_fix_injection; this run does
# not synthesize a result or spend another provider attempt.
printf 'UsageFixtureInstrumentationTest#actualAppOpsDenialStopsBeforeQueryingUsageStats_exit=%s\n' \
  "$USAGE_DENIAL_STATUS" > "$LOG_DIR/external-test-status.txt"
printf 'UsageFixtureInstrumentationTest#fourRealFixtureOpensReachTheRuleAfterReturningToAtlas_exit=%s\n' \
  "$FIXTURE_TEST_STATUS" >> "$LOG_DIR/external-test-status.txt"
printf 'fixture_build_exit=%s\nfixture_install_exit=%s\n' \
  "$FIXTURE_BUILD_STATUS" "$FIXTURE_INSTALL_STATUS" >> "$LOG_DIR/external-test-status.txt"
printf 'V62LocationCaptureInstrumentedTest#prepareSharingConsentBeforeHostInjectsFreshEmulatorFix=filtered(no location_fix_injection harness key)\n' \
  > "$LOG_DIR/external-preconditions.txt"
printf 'V62LocationCaptureInstrumentedTest#injectedCoarseFixIsMinimizedAndStoredOnlyAsAZoneSummary=filtered(no location_fix_injection harness key; S11 proof retained)\n' \
  >> "$LOG_DIR/external-preconditions.txt"
printf 'V61LandscapeCaptureTest#capturesHomeWithDirectiveCtaAboveFixedFooter=filtered(no landscape_display_ready host display setup in the portrait full-suite run; executed separately under AVD landscape geometry)\n' \
  >> "$LOG_DIR/external-preconditions.txt"

# Start independent follow-ups from a clean disposable app state. Runtime grants remain
# coarse-only; no fine or background permission is introduced here.
"$ADB" -s "$SERIAL" shell pm clear dev.atlas.coach > "$LOG_DIR/follow-up-clear-app-data.log" 2>&1 || exit 1
"$ADB" -s "$SERIAL" shell pm clear dev.atlas.coach.test > "$LOG_DIR/follow-up-clear-test-data.log" 2>&1 || exit 1
"$ADB" -s "$SERIAL" shell pm revoke dev.atlas.coach android.permission.ACCESS_FINE_LOCATION > "$LOG_DIR/follow-up-revoke-fine-location.log" 2>&1 || exit 1
"$ADB" -s "$SERIAL" shell pm grant dev.atlas.coach android.permission.ACCESS_COARSE_LOCATION > "$LOG_DIR/follow-up-grant-coarse-location.log" 2>&1 || exit 1
"$ADB" -s "$SERIAL" shell pm grant dev.atlas.coach android.permission.POST_NOTIFICATIONS > "$LOG_DIR/follow-up-grant-notifications.log" 2>&1 || exit 1
"$ADB" -s "$SERIAL" shell cmd location set-location-enabled true > "$LOG_DIR/follow-up-enable-location.log" 2>&1 || exit 1

run_targeted() {
  local label="$1" class_method="$2" expected_width="$3"
  local args=("-e" "expected_width_dp" "$expected_width" "-e" "class" "$class_method")
  run_instrument -w -r "${args[@]}" "$RUNNER" > "$LOG_DIR/$label.log" 2>&1
  return $?
}

run_targeted_with_argument() {
  local label="$1" class_method="$2" expected_width="$3" key="$4" value="$5"
  run_instrument -w -r \
    -e "$key" "$value" -e expected_width_dp "$expected_width" -e class "$class_method" "$RUNNER" \
    > "$LOG_DIR/$label.log" 2>&1
  return $?
}

set +e
"$ADB" -s "$SERIAL" install -r "$APP_APK" > "$LOG_DIR/reinstall-app-for-captures.log" 2>&1
"$ADB" -s "$SERIAL" install -r -t "$TEST_APK" > "$LOG_DIR/reinstall-test-apk-for-captures.log" 2>&1
"$ADB" -s "$SERIAL" shell pm grant dev.atlas.coach android.permission.ACCESS_COARSE_LOCATION > "$LOG_DIR/ensure-coarse-before-ui.log" 2>&1
"$ADB" -s "$SERIAL" shell pm grant dev.atlas.coach android.permission.POST_NOTIFICATIONS > "$LOG_DIR/ensure-notifications-before-ui.log" 2>&1

has_connected_case() {
  [[ -f "$LOG_DIR/android-test-results/connected-results.xml" ]] &&
    rg -q "testcase name=\"$1\"" "$LOG_DIR/android-test-results/connected-results.xml"
}
for method in \
  api26Through29UseListenerAndReturnTimeoutOrUnavailableCodes \
  permissionRevocationDuringAcquisitionIsRecheckedBeforeReturningFix \
  deniedPermissionStateReturnsFunctionalErrorWithoutCrashing; do
  if ! has_connected_case "$method"; then
    set +e
    run_targeted "follow-up-$method" "dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#$method" 360
    follow_up_status=$?
    set -e
    printf '%s_exit=%s\n' "$method" "$follow_up_status" >> "$LOG_DIR/follow-up-status.txt"
  fi
done
# A failed class can stop AndroidJUnitRunner before later packages are discovered.
# Re-run only classes absent from both result XML and the runner's explicit filter log.
python3 - "$ROOT/android/app/src/androidTest/java" "$LOG_DIR/android-test-results" "$LOG_DIR/backend-filter.log" \
  > "$LOG_DIR/unseen-instrumentation-classes.txt" <<'PY'
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

source_root = pathlib.Path(sys.argv[1])
result_root = pathlib.Path(sys.argv[2])
filter_log = pathlib.Path(sys.argv[3])
executed = set()
for result_xml in result_root.rglob("TEST-*.xml"):
    try:
        for node in ET.parse(result_xml).iter("testcase"):
            executed.add(node.attrib.get("classname", ""))
    except ET.ParseError:
        continue
filtered_classes = set()
for line in filter_log.read_text(errors="replace").splitlines():
    match = re.search(r"Filtered .*?\(([\w.]+)\):", line)
    if match:
        filtered_classes.add(match.group(1))
for path in sorted(source_root.rglob("*.kt")):
    source = path.read_text(errors="replace")
    package = re.search(r"^package\s+([\w.]+)", source, re.M)
    klass = re.search(r"^class\s+(\w+)", source, re.M)
    if not package or not klass or "@RunWith" not in source or "@Test" not in source:
        continue
    name = f"{package.group(1)}.{klass.group(1)}"
    if name in executed or name in filtered_classes or name.endswith(".V62RealGatewayContextBridgeTest"):
        continue
    print(name)
PY
while IFS= read -r class_name; do
  [[ -z "$class_name" ]] && continue
  case "$class_name" in
    dev.atlas.coach.location.V62LocationCaptureInstrumentedTest|dev.atlas.coach.app.V62OpenClawClientInstrumentationTest)
      # Fake location methods are handled once above. Real location injection and
      # backend calls remain filtered without their external harness.
      continue
      ;;
    dev.atlas.coach.location.V62MobilityServiceInstrumentedTest)
      set +e
      run_instrument -w -r -e class "$class_name" "$RUNNER" \
        > "$LOG_DIR/follow-up-class-${class_name##*.}.log" 2>&1
      class_status=$?
      set -e
      printf '%s_exit=%s\n' "$class_name" "$class_status" >> "$LOG_DIR/follow-up-status.txt"
      continue
      ;;
    dev.atlas.coach.usage.UsageFixtureInstrumentationTest)
      set +e
      run_instrument -w -r -e class \
        "$class_name#notificationPresenterReportsMissingManifestPermissionInsteadOfPosting" "$RUNNER" \
        > "$LOG_DIR/follow-up-usage-notification-permission.log" 2>&1
      method_status=$?
      set -e
      printf '%s#notificationPresenterReportsMissingManifestPermissionInsteadOfPosting_exit=%s\n' \
        "$class_name" "$method_status" >> "$LOG_DIR/follow-up-status.txt"
      continue
      ;;
    dev.atlas.coach.sync.SyncSettingsScreenCaptureTest)
      set +e
      run_instrument -w -r -e capture_state disabled -e class \
        "$class_name" "$RUNNER" > "$LOG_DIR/follow-up-sync-settings-disabled.log" 2>&1
      method_status=$?
      set -e
      printf '%s#capture_state=disabled_exit=%s\n' "$class_name" "$method_status" >> "$LOG_DIR/follow-up-status.txt"
      continue
      ;;
  esac
  set +e
  run_instrument -w -r -e class "$class_name" "$RUNNER" \
    > "$LOG_DIR/follow-up-class-${class_name##*.}.log" 2>&1
  class_status=$?
  set -e
  printf '%s_exit=%s\n' "$class_name" "$class_status" >> "$LOG_DIR/follow-up-status.txt"
done < "$LOG_DIR/unseen-instrumentation-classes.txt"

"$ADB" -s "$SERIAL" shell wm size 1080x2400 > "$LOG_DIR/display-360dp.log" 2>&1
"$ADB" -s "$SERIAL" shell wm density 480 >> "$LOG_DIR/display-360dp.log" 2>&1
set +e
run_targeted native-home-360dp \
  'dev.atlas.coach.app.V62NativeUiCaptureTest#nativeHomeMatrixAtRequestedWidth' 360
HOME_360_STATUS=$?
run_targeted native-coach-360dp \
  'dev.atlas.coach.app.V62NativeUiCaptureTest#coachScreenShowsNamedLocalFixtureWithoutRawProvenanceHash' 360
COACH_360_STATUS=$?
"$ADB" -s "$SERIAL" shell wm size 960x2400 > "$LOG_DIR/display-320dp.log" 2>&1
"$ADB" -s "$SERIAL" shell wm density 480 >> "$LOG_DIR/display-320dp.log" 2>&1
run_targeted native-home-320dp \
  'dev.atlas.coach.app.V62NativeUiCaptureTest#nativeHomeMatrixAtRequestedWidth' 320
HOME_320_STATUS=$?
run_targeted native-coach-320dp \
  'dev.atlas.coach.app.V62NativeUiCaptureTest#coachScreenShowsNamedLocalFixtureWithoutRawProvenanceHash' 320
COACH_320_STATUS=$?
set -e

# V61 captures a landscape-only contract. AndroidJUnitRunner cannot change the
# emulator's display geometry through an Activity orientation request, so run
# the real case under a temporary host display override, then restore portrait.
set +e
"$ADB" -s "$SERIAL" shell wm size 2400x1080 > "$LOG_DIR/display-landscape-v61.log" 2>&1
landscape_display_status=$?
"$ADB" -s "$SERIAL" shell wm density 480 >> "$LOG_DIR/display-landscape-v61.log" 2>&1
landscape_density_status=$?
if [[ "$landscape_display_status" -eq 0 && "$landscape_density_status" -eq 0 ]]; then
  run_targeted_with_argument follow-up-v61-landscape \
    'dev.atlas.coach.app.V61LandscapeCaptureTest#capturesHomeWithDirectiveCtaAboveFixedFooter' 360 landscape_display_ready "$RUN_ID"
  V61_LANDSCAPE_STATUS=$?
else
  V61_LANDSCAPE_STATUS=1
fi
"$ADB" -s "$SERIAL" shell wm size 1080x2400 > "$LOG_DIR/restore-display-after-v61.log" 2>&1
"$ADB" -s "$SERIAL" shell wm density 480 >> "$LOG_DIR/restore-display-after-v61.log" 2>&1
set -e
printf 'V61LandscapeCaptureTest#capturesHomeWithDirectiveCtaAboveFixedFooter_exit=%s\n' \
  "$V61_LANDSCAPE_STATUS" >> "$LOG_DIR/external-test-status.txt"

if [[ "$V61_LANDSCAPE_STATUS" -eq 0 ]]; then
  if "$ADB" -s "$SERIAL" pull /sdcard/16-home-landscape.png "$CAPTURE_DIR/16-home-landscape.png" \
      > "$LOG_DIR/pull-v61-landscape.log" 2>&1 && \
      python3 - "$CAPTURE_DIR/16-home-landscape.png" <<'PY'
import pathlib
import sys
p = pathlib.Path(sys.argv[1])
if not p.is_file() or p.stat().st_size < 64 or p.read_bytes()[:8] != b"\x89PNG\r\n\x1a\n":
    p.unlink(missing_ok=True)
    raise SystemExit(1)
PY
  then
    printf 'PNG_OK 16-home-landscape.png bytes=%s\n' "$(wc -c < "$CAPTURE_DIR/16-home-landscape.png" | tr -d ' ')" >> "$LOG_DIR/capture-validation.txt"
  else
    printf 'INVALID_OR_MISSING_PNG 16-home-landscape.png\n' >> "$LOG_DIR/capture-validation.txt"
  fi
else
  printf 'NOT_CAPTURED 16-home-landscape.png because V61 test exit=%s\n' "$V61_LANDSCAPE_STATUS" >> "$LOG_DIR/capture-validation.txt"
fi

pull_capture() {
  local name="$1"
  local destination="$CAPTURE_DIR/$name"
  if "$ADB" -s "$SERIAL" exec-out run-as dev.atlas.coach cat "files/v62-captures/$name" > "$destination" 2>/dev/null; then
    if ! python3 - "$destination" <<'PY'
import pathlib
import sys
p = pathlib.Path(sys.argv[1])
png = b"\x89PNG\r\n\x1a\n"
if not p.is_file() or p.stat().st_size < 64 or p.read_bytes()[:8] != png:
    p.unlink(missing_ok=True)
    raise SystemExit(1)
PY
    then
      printf 'INVALID_OR_MISSING_PNG %s\n' "$name" >> "$LOG_DIR/capture-validation.txt"
    else
      printf 'PNG_OK %s bytes=%s\n' "$name" "$(wc -c < "$destination" | tr -d ' ')" >> "$LOG_DIR/capture-validation.txt"
    fi
  else
    rm -f "$destination"
    printf 'MISSING %s\n' "$name" >> "$LOG_DIR/capture-validation.txt"
  fi
}
for name in \
  v62-home-dark-focus-360dp.png v62-home-s4-accent-360dp.png \
  v62-home-amoled-focus-360dp.png v62-home-no-remote-directive-360dp.png \
  v62-home-five-favorites-360dp.png v62-app-drawer-360dp.png v62-app-search-360dp.png \
  v62-home-font-1_5-360dp.png v62-home-font-2_0-360dp.png \
  v62-coach-local-fixture-360dp.png \
  v62-home-dark-focus-320dp.png v62-home-s4-accent-320dp.png \
  v62-home-amoled-focus-320dp.png v62-home-no-remote-directive-320dp.png \
  v62-home-five-favorites-320dp.png v62-app-drawer-320dp.png v62-app-search-320dp.png \
  v62-home-font-1_5-320dp.png v62-home-font-2_0-320dp.png v62-coach-local-fixture-320dp.png; do
  pull_capture "$name"
done
"$ADB" -s "$SERIAL" shell wm size 1080x2400 > "$LOG_DIR/restore-display-360dp.log" 2>&1
"$ADB" -s "$SERIAL" shell wm density 480 >> "$LOG_DIR/restore-display-360dp.log" 2>&1
"$ADB" -s "$SERIAL" logcat -d -s ATLAS_TEST_FILTER:I > "$LOG_DIR/follow-up-filter.log" 2>&1

printf 'native_home_360dp_exit=%s\nnative_coach_360dp_exit=%s\nnative_home_320dp_exit=%s\nnative_coach_320dp_exit=%s\n' \
  "$HOME_360_STATUS" "$COACH_360_STATUS" "$HOME_320_STATUS" "$COACH_320_STATUS" \
  > "$LOG_DIR/native-test-status.txt"
if ! python3 "$ROOT/scripts/v62-instrumentation-summary.py" \
  --root "$LOG_DIR" \
  --main-exit "$GRADLE_STATUS" \
  --native-home-360dp "$HOME_360_STATUS" \
  --native-coach-360dp "$COACH_360_STATUS" \
  --native-home-320dp "$HOME_320_STATUS" \
  --native-coach-320dp "$COACH_320_STATUS"; then
  printf 'summary_generator=FAIL\nrecipe=FAIL\n' > "$LOG_DIR/recipe-result.txt"
  cp "$LOG_DIR/recipe-result.txt" "$LOG_DIR/counts.txt"
fi

python3 - "$ANDROID_DIR/app/build/reports/androidTests/connected/debug" "$LOG_DIR/failure-summary.txt" <<'PY'
import html.parser
import pathlib
import re
import sys

class Failures(html.parser.HTMLParser):
    def __init__(self):
        super().__init__()
        self.current = None
        self.in_failure_heading = False
        self.in_pre = False
        self.text = []
        self.rows = []
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "h3" and "failures" in attrs.get("class", ""):
            self.in_failure_heading = True
            self.current = ["", ""]
        elif tag == "pre" and self.current is not None:
            self.in_pre = True
            self.text = []
    def handle_endtag(self, tag):
        if tag == "h3" and self.in_failure_heading:
            self.in_failure_heading = False
        elif tag == "pre" and self.in_pre:
            self.current[1] = "".join(self.text)
            self.in_pre = False
            self.rows.append(self.current)
            self.current = None
    def handle_data(self, data):
        if self.current is not None:
            if self.in_failure_heading:
                self.current[0] += data
            elif self.in_pre:
                self.text.append(data)

report_dir = pathlib.Path(sys.argv[1])
output = pathlib.Path(sys.argv[2])
rows = []
for report in sorted(report_dir.glob("*.html")):
    parser = Failures()
    parser.feed(report.read_text(errors="replace"))
    for name, details in parser.rows:
        cleaned = re.sub(r"-?\d{1,3}\.\d{4,}", "[coordinate]", details)
        cleaned = re.sub(r"(?i)(bearer\s+)[^\s]+", r"\1[redacted]", cleaned)
        lines = [line.strip() for line in cleaned.splitlines() if line.strip()]
        rows.append((report.name, name, " | ".join(lines[:5])))
output.write_text("\n".join(f"{report} :: {name}\n  {detail}" for report, name, detail in rows) + ("\n" if rows else "No Gradle HTML failure reports were found.\n"))
PY

if rg -q '^recipe=PASS$' "$LOG_DIR/recipe-result.txt"; then
  cp "$LOG_DIR/recipe-result.txt" "$LOG_DIR/status.txt"
  printf 'complete instrumentation recipe finished successfully\nlogs=%s\ncaptures=%s\n' "$LOG_DIR" "$CAPTURE_DIR"
  exit 0
fi
cp "$LOG_DIR/recipe-result.txt" "$LOG_DIR/status.txt"
printf 'complete instrumentation recipe failed; review counts, failure summary and follow-up logs\nlogs=%s\ncaptures=%s\n' \
  "$LOG_DIR" "$CAPTURE_DIR" >&2
exit 1
