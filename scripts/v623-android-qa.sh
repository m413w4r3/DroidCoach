#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-/home/nill/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
AVD_HOME="/tmp/v623-qa/avd"
LOG_ROOT="/tmp/v623-qa"
GRADLE_HOME="$LOG_ROOT/gradle-home"
GRADLE_CACHE="$LOG_ROOT/project-cache"
ADB_PORT=5037
CONSOLE_PORT=5580
SERIAL="emulator-$CONSOLE_PORT"
RUNNER="dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner"
GPU="${ATLAS_V623_EMULATOR_GPU:-auto}"
COMMAND_TIMEOUT="${ATLAS_V623_ADB_TIMEOUT_SECONDS:-20}"
SERVICE_TIMEOUT="${ATLAS_V623_SERVICE_TIMEOUT_SECONDS:-5}"
BUILD_TIMEOUT="${ATLAS_V623_BUILD_TIMEOUT_SECONDS:-1200}"
TEST_TIMEOUT="${ATLAS_V623_TEST_TIMEOUT_SECONDS:-1800}"

export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK" ANDROID_AVD_HOME="$AVD_HOME"
export ANDROID_ADB_SERVER_PORT="$ADB_PORT" ADB_SERVER_SOCKET="tcp:localhost:$ADB_PORT"
export PATH="$SDK/platform-tools:$SDK/emulator:$PATH"

usage() {
    cat <<'USAGE'
Usage: scripts/v623-android-qa.sh api34|api37|--self-test

Builds in this worktree, then uses one task-owned disposable AVD at a time.
The AVD console/ADB ports are 5580/5581. The shared ADB server remains running;
all target commands use the explicit disposable-emulator serial.
Full run logs are kept outside the repository under /tmp/v623-qa.
USAGE
}

MODE="${1:-}"
case "$MODE" in
    api34|api37|--self-test) ;;
    *) usage >&2; exit 2 ;;
esac

mkdir -p "$LOG_ROOT" "$GRADLE_HOME" "$GRADLE_CACHE" "$AVD_HOME"
RUN_DIR="$LOG_ROOT/$MODE-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir "$RUN_DIR"
exec > >(tee -a "$RUN_DIR/recipe.console.log") 2>&1

SELF_TEST_PASSES=0
SELF_TEST_FAILURES=0
EMULATOR_STARTED=0
EMULATOR_PID=""
LOGCAT_PID=""
AVD_NAME=""
API_LEVEL=""
AVD_DIR=""
HOME_ORIGINAL=""
HOME_CHANGED=0
HOME_ROLE_RESULT="NOT_RUN"
HOME_RETURN_RESULT="NOT_RUN"
HOME_RESTORE_RESULT="NOT_RUN"
HOME_LABEL=""
TEST_APK_INSTALLED=0
SUPPLEMENTAL_FAILURES=0
SUPPLEMENTAL_BLOCKED=0
PERMISSION_INITIAL_STATUS=NOT_RUN
PERMISSION_GRANTED_STATUS=NOT_RUN
PERMISSION_REVOKED_STATUS=NOT_RUN
LOCATION_STATUS=NOT_RUN
MOBILITY_STATUS=NOT_RUN
FONT_SCALE_STATUS=NOT_RUN
OFFLINE_STATUS=NOT_RUN
ROTATION_STATUS=NOT_RUN
FOCUS_STATUS=NOT_RUN
GOALS_STATUS=NOT_RUN
NOTIFICATIONS_STATUS=NOT_RUN
SCREENSHOT_CAPTURE_STATUS=NOT_RUN

self_assert() {
    local name="$1" expected="$2" actual="$3"
    if [[ "$expected" == "$actual" ]]; then
        printf 'SELF_TEST PASS %s expected=%s actual=%s\n' "$name" "$expected" "$actual"
        SELF_TEST_PASSES=$((SELF_TEST_PASSES + 1))
    else
        printf 'SELF_TEST FAIL %s expected=%s actual=%s\n' "$name" "$expected" "$actual"
        SELF_TEST_FAILURES=$((SELF_TEST_FAILURES + 1))
    fi
}

service_kind() {
    local service="$1" response="${2//$'\r'/}"
    case "$response" in
        "Service $service: found") printf 'found' ;;
        "Service $service: not found") printf 'not_found' ;;
        '') printf 'empty' ;;
        *) printf 'unexpected' ;;
    esac
}

status_is_valid() {
    case "$1" in PASS|FAIL|BLOCKED|NOT_RUN) return 0 ;; *) return 1 ;; esac
}

if [[ "$MODE" == --self-test ]]; then
    self_assert service_exact_found found "$(service_kind package 'Service package: found')"
    self_assert service_exact_missing not_found "$(service_kind package 'Service package: not found')"
    self_assert service_empty empty "$(service_kind package '')"
    self_assert service_mismatch unexpected "$(service_kind package 'Service activity: found')"
    for status in PASS FAIL BLOCKED NOT_RUN; do
        if status_is_valid "$status"; then self_assert "status_$status" yes yes; else self_assert "status_$status" yes no; fi
    done
    if status_is_valid SKIPPED; then self_assert forbidden_skipped no yes; else self_assert forbidden_skipped no no; fi
    printf 'SELF_TEST_SUMMARY pass=%s fail=%s adb_called=no emulator_started=no gradle_called=no\n' \
        "$SELF_TEST_PASSES" "$SELF_TEST_FAILURES"
    [[ "$SELF_TEST_FAILURES" == 0 ]]
    exit $?
fi

if [[ ! -x "$ADB" || ! -x "$EMULATOR" || ! -x "$AVDMANAGER" ]]; then
    printf 'BLOCKED missing_android_tool adb=%s emulator=%s avdmanager=%s\n' \
        "$ADB" "$EMULATOR" "$AVDMANAGER" | tee "$RUN_DIR/recipe.result.txt"
    exit 2
fi

case "$MODE" in
    api34)
        API_LEVEL=34
        AVD_NAME="atlas_v623_qa_api34"
        IMAGE="system-images;android-34;google_apis;x86_64"
        ;;
    api37)
        API_LEVEL=37
        AVD_NAME="atlas_v623_qa_api37"
        IMAGE="system-images;android-37.2;google_apis_ps16k;x86_64"
        ;;
esac
AVD_DIR="$AVD_HOME/$AVD_NAME.avd"
APP_APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

printf 'ATLAS V6.2.3 Android QA recipe\nrepo=%s\nmode=%s\napi=%s\navd=%s\nserial=%s\nconsole_port=%s\nadb_port=%s\nadb_server_port=%s\ngpu=%s\nrun_dir=%s\n' \
    "$ROOT" "$MODE" "$API_LEVEL" "$AVD_NAME" "$SERIAL" "$CONSOLE_PORT" "$((CONSOLE_PORT + 1))" "$ADB_PORT" "$GPU" "$RUN_DIR"
printf 'git_branch=%s\ngit_head=%s\njava=%s\npython=%s\nemulator=%s\nadb=%s\n' \
    "$(git -C "$ROOT" branch --show-current)" "$(git -C "$ROOT" rev-parse HEAD)" \
    "$(java -version 2>&1 | head -1)" "$(python3 --version 2>&1)" \
    "$("$EMULATOR" -version 2>&1 | head -1)" "$("$ADB" version 2>&1 | sed -n '2p')"

for apk in "$APP_APK" "$TEST_APK"; do
    if [[ ! -f "$apk" ]]; then
        printf 'NOT_RUN missing_built_apk=%s\n' "$apk" | tee "$RUN_DIR/recipe.result.txt"
        exit 2
    fi
done
sha256sum "$APP_APK" "$TEST_APK" | tee "$RUN_DIR/apk-sha256.txt"

run_host() {
    local label="$1" timeout_seconds="$2"; shift 2
    local status=0
    printf '[%s] command=' "$label"
    printf '%q ' "$@"
    printf '\n'
    if timeout --foreground --signal=TERM --kill-after=5s "${timeout_seconds}s" "$@" \
        >"$RUN_DIR/$label.stdout.log" 2>"$RUN_DIR/$label.stderr.log"; then
        status=0
    else
        status=$?
    fi
    printf '%s\n' "$status" >"$RUN_DIR/$label.exit"
    printf '[%s] exit=%s\n' "$label" "$status"
    return 0
}

run_target() {
    local label="$1"; shift
    local status=0 timeout_seconds="$COMMAND_TIMEOUT"
    case "${1:-} ${2:-} ${3:-}" in
        'shell service '*) timeout_seconds="$SERVICE_TIMEOUT" ;;
        'shell am instrument '*) timeout_seconds="$TEST_TIMEOUT" ;;
        'exec-out screencap '*) timeout_seconds="$SERVICE_TIMEOUT" ;;
        'install '*) timeout_seconds=120 ;;
    esac
    printf '[%s] command=' "$label"
    printf '%q ' "$ADB" -s "$SERIAL" "$@"
    printf '\n'
    if timeout --foreground --signal=TERM --kill-after=3s "${timeout_seconds}s" \
        "$ADB" -s "$SERIAL" "$@" \
        >"$RUN_DIR/$label.stdout.log" 2>"$RUN_DIR/$label.stderr.log"; then
        status=0
    else
        status=$?
    fi
    printf '%s\n' "$status" >"$RUN_DIR/$label.exit"
    printf '[%s] exit=%s\n' "$label" "$status"
    return 0
}

target_stdout() { cat "$RUN_DIR/$1.stdout.log" 2>/dev/null || true; }
target_exit() { cat "$RUN_DIR/$1.exit" 2>/dev/null || printf '999'; }

test_status_for() {
    local target="$1"
    if grep -Fqx "PASS $target" "$RUN_DIR/test-counts.txt"; then printf 'PASS'
    elif grep -Fqx "FAIL $target" "$RUN_DIR/test-counts.txt"; then printf 'FAIL'
    elif grep -Fqx "ERROR $target" "$RUN_DIR/test-counts.txt"; then printf 'FAIL'
    else printf 'NOT_RUN'
    fi
}

class_status_for() {
    python3 - "$RUN_DIR/test-counts.txt" "$1" <<'PY'
import sys
path, prefix = sys.argv[1:]
rows = [line.split(" ", 1) for line in open(path, encoding="utf-8")
        if line.startswith(("PASS ", "FAIL ", "ERROR "))]
matched = [(status, target) for status, target in rows if target.startswith(prefix)]
if not matched:
    print("NOT_RUN")
elif any(status in {"FAIL", "ERROR"} for status, _ in matched):
    print("FAIL")
else:
    print("PASS")
PY
}

combine_statuses() {
    local status saw_not_run=0 saw_blocked=0
    for status in "$@"; do
        case "$status" in
            FAIL) printf 'FAIL'; return ;;
            BLOCKED) saw_blocked=1 ;;
            NOT_RUN) saw_not_run=1 ;;
        esac
    done
    if [[ "${saw_blocked:-0}" == 1 ]]; then printf 'BLOCKED'
    elif [[ "$saw_not_run" == 1 ]]; then printf 'NOT_RUN'
    else printf 'PASS'
    fi
}

record_supplemental() {
    local label="$1" status="$2" classification="$3"
    printf '%s\t%s\t%s\n' "$label" "$status" "$classification" >>"$RUN_DIR/supplemental-results.tsv"
    case "$status" in
        FAIL) SUPPLEMENTAL_FAILURES=$((SUPPLEMENTAL_FAILURES + 1)) ;;
        BLOCKED) SUPPLEMENTAL_BLOCKED=$((SUPPLEMENTAL_BLOCKED + 1)) ;;
    esac
}

instrumentation_result() {
    local label="$1" target="$2" output exit_code method result
    output="$(target_stdout "$label")"
    exit_code="$(target_exit "$label")"
    method="${target##*#}"
    if [[ "$output" == *TEST_SYSTEM_BLOCKED* || "$output" == *INSTRUMENTATION_ABORTED* ||
          "$output" == *"System has crashed"* || "$output" == *"shortMsg=Process crashed"* ||
          "$exit_code" == 124 || "$exit_code" == 137 ]]; then
        result=BLOCKED
    elif [[ "$exit_code" == 0 && "$output" == *'INSTRUMENTATION_STATUS: numtests=1'* &&
            "$output" == *"INSTRUMENTATION_STATUS: test=$method"* &&
            "$output" == *'INSTRUMENTATION_STATUS_CODE: 0'* &&
            "$output" == *'OK (1 test)'* && "$output" == *'INSTRUMENTATION_CODE: -1'* &&
            "$output" != *'INSTRUMENTATION_STATUS_CODE: -2'* &&
            "$output" != *'INSTRUMENTATION_STATUS_CODE: -3'* ]]; then
        result=PASS
    elif [[ "$output" == *'INSTRUMENTATION_STATUS_CODE: -2'* ||
            "$output" == *'INSTRUMENTATION_STATUS_CODE: -3'* ||
            "$output" == *'FAILURES!!!'* ]]; then
        result=FAIL
    else
        result=NOT_RUN
    fi
    printf '%s target=%s exit=%s\n' "$result" "$target" "$exit_code" \
        | tee "$RUN_DIR/$label.result.txt"
    printf '%s\n' "$result"
}

run_single_instrumentation() {
    local label="$1" target="$2"; shift 2
    run_target "$label" shell am instrument -w -r -e class "$target" "$@" "$RUNNER"
    instrumentation_result "$label" "$target" >/dev/null
}

run_synchronized_instrumentation() {
    local label="$1" target="$2" marker="$3"; shift 3
    local instrumentation_pid logcat_pid marker_deadline runner_deadline marker_seen=0
    local device_timestamp geo_status broadcast_status result
    run_target "$label-logcat-clear" shell logcat -c
    if [[ "$(target_exit "$label-logcat-clear")" != 0 ]]; then
        printf 'BLOCKED target=%s reason=logcat_clear_failed\n' "$target" | tee "$RUN_DIR/$label.result.txt"
        return 0
    fi
    timeout --foreground --signal=TERM --kill-after=3s "$TEST_TIMEOUT" \
        "$ADB" -s "$SERIAL" logcat -v threadtime -s ATLAS_V62_LOCATION:V \
        >"$RUN_DIR/$label.logcat.log" 2>"$RUN_DIR/$label.logcat.stderr.log" &
    logcat_pid=$!
    run_target "$label" shell am instrument -w -r -e class "$target" -e location_fix_injection host "$RUNNER" &
    instrumentation_pid=$!
    marker_deadline=$((SECONDS + 30))
    while (( SECONDS < marker_deadline )); do
        if grep -Fq "$marker" "$RUN_DIR/$label.logcat.log"; then marker_seen=1; break; fi
        [[ -f "$RUN_DIR/$label.exit" ]] && break
        sleep 0.2
    done
    printf 'marker=%s\nmarker_seen=%s\n' "$marker" "$marker_seen" >"$RUN_DIR/$label-injection-sync.txt"
    geo_status=NOT_SENT
    broadcast_status=NOT_SENT
    if (( marker_seen )); then
        run_target "$label-device-time-before-injection" shell date +%s%3N
        device_timestamp="$(tr -d '\r[:space:]' <"$RUN_DIR/$label-device-time-before-injection.stdout.log")"
        if [[ "$(target_exit "$label-device-time-before-injection")" == 0 && "$device_timestamp" =~ ^[0-9]{13}$ ]]; then
            run_target "$label-geo-fix" emu geo fix 2.123456 48.123456
            if [[ "$(target_exit "$label-geo-fix")" == 0 && "$(target_stdout "$label-geo-fix")" == *OK* ]]; then
                geo_status=PASS
                run_target "$label-injection-broadcast" shell am broadcast \
                    -a dev.atlas.coach.location.V62_HOST_FIX_INJECTED -p dev.atlas.coach \
                    --el injected_at_utc_ms "$device_timestamp"
                [[ "$(target_exit "$label-injection-broadcast")" == 0 ]] && broadcast_status=PASS || broadcast_status=FAIL
            else
                geo_status=FAIL
            fi
        else
            geo_status=FAIL
        fi
        printf 'device_timestamp_utc_ms=%s\ngeo_fix=%s\nbroadcast=%s\n' \
            "$device_timestamp" "$geo_status" "$broadcast_status" >>"$RUN_DIR/$label-injection-sync.txt"
    else
        printf 'injection=NOT_SENT reason=marker_not_seen_before_30s\n' >>"$RUN_DIR/$label-injection-sync.txt"
    fi
    runner_deadline=$((SECONDS + TEST_TIMEOUT + 10))
    while [[ ! -f "$RUN_DIR/$label.exit" ]] && (( SECONDS < runner_deadline )); do sleep 0.2; done
    if [[ ! -f "$RUN_DIR/$label.exit" ]]; then
        kill -TERM "$instrumentation_pid" 2>/dev/null || true
    fi
    wait "$instrumentation_pid" 2>/dev/null || true
    kill -TERM "$logcat_pid" 2>/dev/null || true
    wait "$logcat_pid" 2>/dev/null || true
    printf 'marker=%s marker_seen=%s geo_fix=%s broadcast=%s\n' \
        "$marker" "$marker_seen" "$geo_status" "$broadcast_status" >>"$RUN_DIR/$label-injection-sync.txt"
    instrumentation_result "$label" "$target" >/dev/null
}

capture_diagnostics() {
    local reason="$1"
    printf '%s\n' "$reason" >"$RUN_DIR/avd-blocked-reason.txt"
    run_target diagnostics-build-fingerprint shell getprop ro.build.fingerprint
    run_target diagnostics-version shell getprop ro.build.version.release
    run_target diagnostics-sdk shell getprop ro.build.version.sdk
    run_target diagnostics-sdk-full shell getprop ro.build.version.sdk_full
    run_target diagnostics-boot shell getprop sys.boot_completed
    run_target diagnostics-service-list shell service list
    run_target diagnostics-crash-buffer shell logcat -b crash -d -v threadtime
    run_target diagnostics-system-errors shell logcat -b system -d -v threadtime -s SystemServer:E AndroidRuntime:E Watchdog:E
    run_target diagnostics-native-errors shell logcat -b main -d -v threadtime -s surfaceflinger:F android.hardware.uwb-service:F ot-daemon:F lmkd:I
    run_target diagnostics-events shell logcat -b events -d -v threadtime
    run_target diagnostics-logcat-all shell logcat -b all -d -v threadtime
    run_target diagnostics-memory shell cat /proc/meminfo
    run_target diagnostics-memory-pressure shell cat /proc/pressure/memory
    run_target diagnostics-process-list shell ps -A -o PID,PPID,RSS,STAT,NAME
}

capture_screenshot() {
    local label="$1" signature="" size=0
    printf '[%s] command=' "$label"
    printf '%q ' "$ADB" -s "$SERIAL" exec-out screencap -p
    printf '\n'
    if timeout --foreground --signal=TERM --kill-after=3s "${SERVICE_TIMEOUT}s" \
        "$ADB" -s "$SERIAL" exec-out screencap -p \
        >"$RUN_DIR/$label.png" 2>"$RUN_DIR/$label.stderr.log"; then
        signature="$(od -An -tx1 -N8 "$RUN_DIR/$label.png" | tr -d ' \n')"
        size="$(wc -c <"$RUN_DIR/$label.png" | tr -d ' ')"
        if [[ "$signature" == 89504e470d0a1a0a ]]; then
            printf 'PASS bytes=%s png_signature=%s\n' "$size" "$signature" | tee "$RUN_DIR/$label.result.txt"
        else
            printf 'BLOCKED bytes=%s invalid_signature=%s\n' "$size" "$signature" | tee "$RUN_DIR/$label.result.txt"
            head -c 512 "$RUN_DIR/$label.png" | strings >"$RUN_DIR/$label.invalid-output.txt" || true
        fi
    else
        printf 'BLOCKED adb_exit=%s\n' "$?" | tee "$RUN_DIR/$label.result.txt"
    fi
    printf 'Screenshot result is independent; it is not a prerequisite for instrumentation.\n'
}

probe_service() {
    local service="$1" output status result
    if timeout --foreground --signal=TERM --kill-after=2s "${SERVICE_TIMEOUT}s" \
        "$ADB" -s "$SERIAL" shell service check "$service" \
        >"$RUN_DIR/service-$service.stdout.log" 2>"$RUN_DIR/service-$service.stderr.log"; then
        status=0
    else
        status=$?
    fi
    printf '%s\n' "$status" >"$RUN_DIR/service-$service.exit"
    status="$(target_exit "service-$service")"
    output="$(target_stdout "service-$service")"
    output="${output//$'\r'/}"
    case "$status:$(service_kind "$service" "$output")" in
        0:found) result=PASS ;;
        124:*|137:*) result=BLOCKED_TIMEOUT ;;
        0:not_found) result=BLOCKED_SERVICE_NOT_FOUND ;;
        0:empty) result=BLOCKED_EMPTY_RESPONSE ;;
        *) result=FAIL_COMMAND ;;
    esac
    printf 'command=%q -s %q shell service check %q\n' "$ADB" "$SERIAL" "$service" >>"$RUN_DIR/framework-service-probes.log"
    printf '%s service=%s adb_exit=%s response=%q\n' "$result" "$service" "$status" "$output" \
        >>"$RUN_DIR/framework-service-probes.log"
    [[ "$result" == PASS ]]
}

write_blocked_matrix() {
    local reason="$1"
    local file="$RUN_DIR/scenario-matrix.tsv"
    printf 'scenario\tstatus\tcause\tevidence\n' >"$file"
    for scenario in installation_launch home_role previous_launcher focus goals notifications location_one_shot mobility_visible runtime_permissions revocation rotation font_scale_2x offline; do
        printf '%s\tBLOCKED\tAVD_ENVIRONMENT_BLOCKED\t%s (%s)\n' "$scenario" "$reason" "$RUN_DIR" >>"$file"
    done
    printf 'BLOCKED framework_gate=%s matrix=%s\n' "$reason" "$file" | tee "$RUN_DIR/scenario-matrix.result.txt"
}

home_picker_point() {
    local xml_file="$1" needle="$2"
    python3 - "$xml_file" "$needle" <<'PY'
import re
import sys
from html import unescape

xml = open(sys.argv[1], encoding="utf-8").read()
needle = sys.argv[2].casefold()
for node in re.findall(r"<node\b[^>]*>", xml):
    text = re.search(r'\btext="([^"]*)"', node)
    desc = re.search(r'\bcontent-desc="([^"]*)"', node)
    bounds = re.search(r'\bbounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    label = " ".join(unescape(match.group(1)) for match in (text, desc) if match)
    if bounds and needle in label.casefold():
        x1, y1, x2, y2 = map(int, bounds.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        raise SystemExit(0)
raise SystemExit(1)
PY
}

activity_is_resumed() {
    local label="$1" package="$2"
    python3 - "$RUN_DIR/$label.stdout.log" "$package" <<'PY'
import re
import sys

text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
package = sys.argv[2]
markers = [line for line in text.splitlines()
           if re.search(r"topResumedActivity|mResumedActivity|ResumedActivity", line, re.I)]
open(sys.argv[1].removesuffix(".stdout.log") + ".resumed-lines.txt", "w", encoding="utf-8").write(
    "\n".join(markers) + ("\n" if markers else ""))
raise SystemExit(0 if any(package in line for line in markers) else 1)
PY
}

home_picker_dump() {
    local label="$1"
    run_target "$label-open-settings" shell am start -W -a android.settings.HOME_SETTINGS
    sleep 2
    run_target "$label-dump-ui" shell uiautomator dump /sdcard/v623-home-picker.xml
    [[ "$(target_exit "$label-dump-ui")" == 0 ]] || return 1
    timeout --foreground --signal=TERM --kill-after=3s "${COMMAND_TIMEOUT}s" \
        "$ADB" -s "$SERIAL" exec-out cat /sdcard/v623-home-picker.xml \
        >"$RUN_DIR/$label-ui.xml" 2>"$RUN_DIR/$label-ui.stderr.log"
}

restore_home_role() {
    local label="$1" point x y current
    [[ "$HOME_CHANGED" == 1 && -n "$HOME_ORIGINAL" ]] || return 0
    if home_picker_dump "$label" && point="$(home_picker_point "$RUN_DIR/$label-ui.xml" "$HOME_LABEL" || true)" && [[ -n "$point" ]]; then
        read -r x y <<<"$point"
        run_target "$label-select-original" shell input tap "$x" "$y"
        sleep 2
    fi
    run_target "$label-verify-original" shell cmd role get-role-holders android.app.role.HOME
    current="$(tr -d '\r[:space:]' <"$RUN_DIR/$label-verify-original.stdout.log")"
    if [[ "$current" == "$HOME_ORIGINAL" ]]; then
        HOME_CHANGED=0
        HOME_RESTORE_RESULT=PASS
        printf 'PASS original_home_restored=%s\n' "$HOME_ORIGINAL" | tee "$RUN_DIR/home-restore.result.txt"
        return 0
    fi
    HOME_RESTORE_RESULT=FAIL
    printf 'FAIL original_home_not_restored expected=%s actual=%s\n' "$HOME_ORIGINAL" "$current" \
        | tee "$RUN_DIR/home-restore.result.txt"
    return 1
}

run_home_roundtrip() {
    local atlas_point restore_point current plus_point return_point x y
    run_target initial-home-role shell cmd role get-role-holders android.app.role.HOME
    HOME_ORIGINAL="$(tr -d '\r[:space:]' <"$RUN_DIR/initial-home-role.stdout.log")"
    case "$HOME_ORIGINAL" in
        com.google.android.apps.nexuslauncher) HOME_LABEL="Pixel Launcher" ;;
        com.android.launcher3) HOME_LABEL="Launcher" ;;
        *)
            HOME_ROLE_RESULT=NOT_RUN
            HOME_RETURN_RESULT=NOT_RUN
            printf 'NOT_RUN reason=unrecognized_original_home_holder holder=%s\n' "$HOME_ORIGINAL" \
                | tee "$RUN_DIR/home-roundtrip.result.txt" "$RUN_DIR/home-return.result.txt" "$RUN_DIR/home-restore.result.txt"
            return 0
            ;;
    esac

    # Let LauncherActivity record the current default HOME component before role selection.
    run_target seed-saved-previous-launcher shell am start -W -n dev.atlas.coach/dev.atlas.coach.app.LauncherActivity
    sleep 2
    if ! home_picker_dump home-before-selection; then
        HOME_ROLE_RESULT=NOT_RUN
        HOME_RETURN_RESULT=NOT_RUN
        printf 'NOT_RUN reason=home_settings_ui_unavailable\n' | tee "$RUN_DIR/home-roundtrip.result.txt"
        printf 'NOT_RUN reason=home_role_not_changed\n' | tee "$RUN_DIR/home-return.result.txt"
        printf 'NOT_RUN reason=role_not_changed\n' | tee "$RUN_DIR/home-restore.result.txt"
        return 0
    fi
    atlas_point="$(home_picker_point "$RUN_DIR/home-before-selection-ui.xml" ATLAS || true)"
    restore_point="$(home_picker_point "$RUN_DIR/home-before-selection-ui.xml" "$HOME_LABEL" || true)"
    if [[ -z "$atlas_point" || -z "$restore_point" ]]; then
        HOME_ROLE_RESULT=NOT_RUN
        HOME_RETURN_RESULT=NOT_RUN
        printf 'NOT_RUN reason=picker_did_not_show_ATLAS_and_original holder=%s label=%s\n' "$HOME_ORIGINAL" "$HOME_LABEL" \
            | tee "$RUN_DIR/home-roundtrip.result.txt"
        printf 'NOT_RUN reason=home_role_not_changed\n' | tee "$RUN_DIR/home-return.result.txt"
        printf 'NOT_RUN reason=role_not_changed\n' | tee "$RUN_DIR/home-restore.result.txt"
        return 0
    fi
    read -r x y <<<"$atlas_point"
    run_target select-atlas-home-via-settings-ui shell input tap "$x" "$y"
    sleep 2
    run_target verify-atlas-home-role shell cmd role get-role-holders android.app.role.HOME
    current="$(tr -d '\r[:space:]' <"$RUN_DIR/verify-atlas-home-role.stdout.log")"
    if [[ "$current" != dev.atlas.coach ]]; then
        HOME_ROLE_RESULT=NOT_RUN
        HOME_RETURN_RESULT=NOT_RUN
        printf 'NOT_RUN reason=settings_ui_did_not_assign_ATLAS current=%s\n' "$current" \
            | tee "$RUN_DIR/home-roundtrip.result.txt"
        printf 'NOT_RUN reason=home_role_not_changed\n' | tee "$RUN_DIR/home-return.result.txt"
        printf 'NOT_RUN reason=role_not_changed\n' | tee "$RUN_DIR/home-restore.result.txt"
        return 0
    fi
    HOME_CHANGED=1
    run_target press-home-with-atlas-selected shell input keyevent KEYCODE_HOME
    sleep 2
    run_target verify-atlas-home-launch shell dumpsys activity activities
    if activity_is_resumed verify-atlas-home-launch dev.atlas.coach; then
        HOME_ROLE_RESULT=PASS
        printf 'PASS role_selected_in_settings_and_HOME_opened_ATLAS\n' | tee "$RUN_DIR/home-roundtrip.result.txt"
    else
        HOME_ROLE_RESULT=FAIL
        printf 'FAIL HOME_key_did_not_open_ATLAS\n' | tee "$RUN_DIR/home-roundtrip.result.txt"
    fi

    if [[ "$HOME_ROLE_RESULT" == PASS ]]; then
        run_target atlas-home-ui-dump shell uiautomator dump /sdcard/v623-home-ui.xml
        if [[ "$(target_exit atlas-home-ui-dump)" == 0 ]] &&
            timeout --foreground --signal=TERM --kill-after=3s "${COMMAND_TIMEOUT}s" \
                "$ADB" -s "$SERIAL" exec-out cat /sdcard/v623-home-ui.xml \
                >"$RUN_DIR/atlas-home-ui.xml" 2>"$RUN_DIR/atlas-home-ui.stderr.log"; then
            plus_point="$(home_picker_point "$RUN_DIR/atlas-home-ui.xml" Plus || true)"
        else
            plus_point=""
        fi
        if [[ -n "$plus_point" ]]; then
            read -r x y <<<"$plus_point"
            run_target open-atlas-home-menu shell input tap "$x" "$y"
            sleep 1
            run_target atlas-home-menu-dump shell uiautomator dump /sdcard/v623-home-menu.xml
            if [[ "$(target_exit atlas-home-menu-dump)" == 0 ]] &&
                timeout --foreground --signal=TERM --kill-after=3s "${COMMAND_TIMEOUT}s" \
                    "$ADB" -s "$SERIAL" exec-out cat /sdcard/v623-home-menu.xml \
                    >"$RUN_DIR/atlas-home-menu.xml" 2>"$RUN_DIR/atlas-home-menu.stderr.log"; then
                return_point="$(home_picker_point "$RUN_DIR/atlas-home-menu.xml" 'Launcher précédent' || true)"
            else
                return_point=""
            fi
        else
            return_point=""
        fi
        if [[ -n "$return_point" ]]; then
            read -r x y <<<"$return_point"
            run_target choose-previous-launcher shell input tap "$x" "$y"
            sleep 3
            run_target verify-previous-launcher-return shell dumpsys activity activities
            if activity_is_resumed verify-previous-launcher-return "$HOME_ORIGINAL"; then
                HOME_RETURN_RESULT=PASS
                printf 'PASS previous_launcher_opened=%s via_ATLAS_menu\n' "$HOME_ORIGINAL" \
                    | tee "$RUN_DIR/home-return.result.txt"
            else
                HOME_RETURN_RESULT=FAIL
                printf 'FAIL previous_launcher_not_resumed expected=%s\n' "$HOME_ORIGINAL" \
                    | tee "$RUN_DIR/home-return.result.txt"
            fi
        else
            HOME_RETURN_RESULT=NOT_RUN
            printf 'NOT_RUN reason=ATLAS_launcher_menu_item_unavailable\n' | tee "$RUN_DIR/home-return.result.txt"
        fi
    else
        HOME_RETURN_RESULT=NOT_RUN
        printf 'NOT_RUN reason=ATLAS_HOME_not_opened\n' | tee "$RUN_DIR/home-return.result.txt"
    fi
    restore_home_role home-restore || true
}

cleanup() {
    if [[ -n "$LOGCAT_PID" ]]; then
        kill -TERM "$LOGCAT_PID" 2>/dev/null || true
        wait "$LOGCAT_PID" 2>/dev/null || true
        LOGCAT_PID=""
    fi
    if [[ "$HOME_CHANGED" == 1 ]]; then
        restore_home_role cleanup-home-restore || true
    fi
    if [[ "$EMULATOR_STARTED" == 1 ]]; then
        timeout --foreground --signal=TERM --kill-after=3s 15s \
            "$ADB" -s "$SERIAL" emu kill \
            >"$RUN_DIR/emulator-stop.log" 2>&1 || true
        if [[ -n "$EMULATOR_PID" ]] && kill -0 "$EMULATOR_PID" 2>/dev/null; then
            kill -TERM "$EMULATOR_PID" 2>/dev/null || true
            wait "$EMULATOR_PID" 2>/dev/null || true
        fi
    fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

printf '\n[build] Building debug app and AndroidTest APKs into this worktree.\n'
if ! run_host build "$BUILD_TIMEOUT" env GRADLE_USER_HOME="$GRADLE_HOME" \
    "${ROOT}/android/gradlew" --no-daemon --project-cache-dir "$GRADLE_CACHE" \
    -p "$ROOT/android" :app:assembleDebug :app:assembleDebugAndroidTest; then
    : # run_host records failures in its exit file.
fi
if [[ "$(cat "$RUN_DIR/build.exit")" != 0 ]]; then
    cat "$RUN_DIR/build.stdout.log" "$RUN_DIR/build.stderr.log"
    printf 'NOT_RUN reason=build_failed\n' | tee "$RUN_DIR/recipe.result.txt"
    exit 1
fi
APP_APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
sha256sum "$APP_APK" "$TEST_APK" | tee "$RUN_DIR/apk-sha256.txt"

if ss -ltn 2>/dev/null | grep -Eq ':(5580|5581)([[:space:]]|$)'; then
    printf 'BLOCKED assigned_port_in_use ports=5580,5581; no ADB target or emulator was started.\n' \
        | tee "$RUN_DIR/recipe.result.txt"
    exit 2
fi

if [[ ! -f "$AVD_HOME/$AVD_NAME.ini" ]]; then
    if [[ -e "$AVD_DIR" || -e "$AVD_HOME/$AVD_NAME.ini" ]]; then
        printf 'BLOCKED unregistered_or_unowned_avd_path=%s\n' "$AVD_DIR" | tee "$RUN_DIR/recipe.result.txt"
        exit 2
    fi
    run_host avd-create "$COMMAND_TIMEOUT" "$AVDMANAGER" --silent create avd \
        --name "$AVD_NAME" --package "$IMAGE" --device pixel_5 --path "$AVD_DIR"
    if [[ "$(cat "$RUN_DIR/avd-create.exit")" != 0 || ! -f "$AVD_HOME/$AVD_NAME.ini" ]]; then
        cat "$RUN_DIR/avd-create.stdout.log" "$RUN_DIR/avd-create.stderr.log"
        printf 'BLOCKED avd_creation_failed image=%s\n' "$IMAGE" | tee "$RUN_DIR/recipe.result.txt"
        exit 2
    fi
    printf 'created_by_v623_qa\n' >"$AVD_DIR/.v623-qa-owned"
fi
if [[ ! -f "$AVD_DIR/.v623-qa-owned" ]]; then
    printf 'BLOCKED avd_ownership_marker_missing path=%s\n' "$AVD_DIR" | tee "$RUN_DIR/recipe.result.txt"
    exit 2
fi
if ! grep -Eq "^image\.sysdir\.1[[:space:]]*=[[:space:]]*system-images/android-${API_LEVEL}(\.2)?/" "$AVD_DIR/config.ini"; then
    printf 'BLOCKED avd_image_mismatch path=%s expected=%s\n' "$AVD_DIR/config.ini" "$IMAGE" \
        | tee "$RUN_DIR/recipe.result.txt"
    exit 2
fi

run_host adb-server-start "$COMMAND_TIMEOUT" "$ADB" start-server
if [[ "$(cat "$RUN_DIR/adb-server-start.exit")" != 0 ]]; then
    cat "$RUN_DIR/adb-server-start.stdout.log" "$RUN_DIR/adb-server-start.stderr.log"
    printf 'BLOCKED adb_server_unavailable port=%s\n' "$ADB_PORT" | tee "$RUN_DIR/recipe.result.txt"
    exit 2
fi
run_host initial-adb-devices "$COMMAND_TIMEOUT" "$ADB" devices -l
if awk -v serial="$SERIAL" '$1 == serial && $2 == "device" {found=1} END {exit !found}' \
    "$RUN_DIR/initial-adb-devices.stdout.log"; then
    printf 'BLOCKED assigned_emulator_serial_already_attached serial=%s; refusing to target or stop it.\n' "$SERIAL" \
        | tee "$RUN_DIR/recipe.result.txt"
    exit 2
fi

printf '[emulator-start] command='
printf '%q ' "$EMULATOR" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim -no-snapshot-load -no-snapshot-save \
    -port "$CONSOLE_PORT" -gpu "$GPU" -cores 2 -memory 2048 -partition-size 4096 -wipe-data -netdelay none -netspeed full
printf '\n'
"$EMULATOR" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim -no-snapshot-load -no-snapshot-save \
    -port "$CONSOLE_PORT" -gpu "$GPU" -cores 2 -memory 2048 -partition-size 4096 -wipe-data \
    -netdelay none -netspeed full >"$RUN_DIR/emulator.log" 2>&1 &
EMULATOR_PID=$!
EMULATOR_STARTED=1
printf 'emulator_pid=%s\n' "$EMULATOR_PID" | tee "$RUN_DIR/emulator.pid"

boot_deadline=$((SECONDS + 240))
boot_complete=""
while (( SECONDS < boot_deadline )); do
    if timeout --foreground --signal=TERM --kill-after=2s 10s "$ADB" -s "$SERIAL" \
        shell getprop sys.boot_completed >"$RUN_DIR/boot-probe.log" 2>"$RUN_DIR/boot-probe.stderr.log"; then
        boot_complete="$(tr -d '\r[:space:]' <"$RUN_DIR/boot-probe.log")"
    else
        boot_complete=""
    fi
    [[ "$boot_complete" == 1 ]] && break
    sleep 2
done
if [[ "$boot_complete" != 1 ]]; then
    printf 'BLOCKED api=%s reason=boot_not_completed_before_deadline\n' "$API_LEVEL" \
        | tee "$RUN_DIR/recipe.result.txt"
    write_blocked_matrix boot_not_completed
    exit 1
fi

run_target avd-name shell getprop ro.boot.qemu.avd_name
run_target api-level shell getprop ro.build.version.sdk
run_target api-full shell getprop ro.build.version.sdk_full
run_target build-fingerprint shell getprop ro.build.fingerprint
run_target boot-completed shell getprop sys.boot_completed
run_target data-partition shell df -h /data
printf 'avd=%s api=%s api_full=%s boot=%s\n' \
    "$(target_stdout avd-name | tr -d '\r[:space:]')" \
    "$(target_stdout api-level | tr -d '\r[:space:]')" \
    "$(target_stdout api-full | tr -d '\r[:space:]')" \
    "$(target_stdout boot-completed | tr -d '\r[:space:]')" | tee "$RUN_DIR/device-identity.txt"
if [[ "$(target_stdout avd-name | tr -d '\r[:space:]')" != "$AVD_NAME" ||
      "$(target_stdout api-level | tr -d '\r[:space:]')" != "$API_LEVEL" ||
      "$(target_stdout boot-completed | tr -d '\r[:space:]')" != 1 ]]; then
    printf 'BLOCKED reason=avd_identity_mismatch; no package or test command ran.\n' | tee "$RUN_DIR/recipe.result.txt"
    write_blocked_matrix avd_identity_mismatch
    exit 1
fi

capture_screenshot pre-install-screencap
framework_deadline=$((SECONDS + 240))
framework_ready=0
services_ever_found=0
stable_since=""
: >"$RUN_DIR/framework-service-probes.log"
while (( SECONDS < framework_deadline )); do
    found_count=0
    missing_count=0
    for service in package activity location notification window; do
        if probe_service "$service"; then found_count=$((found_count + 1)); fi
        if [[ "$(service_kind "$service" "$(target_stdout "service-$service")")" == not_found ]]; then
            missing_count=$((missing_count + 1))
        fi
    done
    (( found_count == 5 )) && services_ever_found=1
    if (( found_count == 5 )); then
        [[ -n "$stable_since" ]] || stable_since="$SECONDS"
        if (( SECONDS - stable_since >= 15 )); then framework_ready=1; break; fi
    else
        stable_since=""
        if (( services_ever_found && missing_count == 5 )); then
            printf 'BLOCKED framework_services_disappeared_after_positive_probes\n' \
                | tee "$RUN_DIR/framework-runtime-gate.result.txt"
            capture_diagnostics framework_services_disappeared
            write_blocked_matrix framework_services_disappeared
            exit 1
        fi
    fi
    sleep 2
done
if [[ "$framework_ready" != 1 ]]; then
    printf 'BLOCKED framework_services_not_stable_within_240s\n' | tee "$RUN_DIR/framework-runtime-gate.result.txt"
    capture_diagnostics framework_services_not_stable
    write_blocked_matrix framework_services_not_stable
    exit 1
fi
printf 'PASS framework_services_stable_for_15s\n' | tee "$RUN_DIR/framework-runtime-gate.result.txt"

run_target package-inventory shell pm list packages dev.atlas.coach
if grep -Fxq 'package:dev.atlas.coach' "$RUN_DIR/package-inventory.stdout.log"; then
    printf 'BLOCKED unexpected_existing_atlas_package; refusing uninstall or overwrite.\n' \
        | tee "$RUN_DIR/recipe.result.txt"
    write_blocked_matrix unexpected_existing_atlas_package
    exit 1
fi
run_target install-app install "$APP_APK"
if [[ "$(target_exit install-app)" != 0 || "$(target_stdout install-app)" != *Success* ]]; then
    printf 'FAIL app_install\n' | tee "$RUN_DIR/install-launch.result.txt"
    capture_diagnostics app_install_failed
    exit 1
fi
run_target resolve-main-activity shell cmd package resolve-activity --brief -n dev.atlas.coach/.app.MainActivity
run_target launch-main-activity shell am start -W -n dev.atlas.coach/dev.atlas.coach.app.MainActivity
run_target resumed-activity shell dumpsys activity activities
if [[ "$(target_exit launch-main-activity)" == 0 &&
      "$(target_stdout launch-main-activity)" == *'Status: ok'* ]] &&
      activity_is_resumed resumed-activity dev.atlas.coach; then
    INSTALL_LAUNCH_STATUS=PASS
    printf 'PASS install_and_launch\n' | tee "$RUN_DIR/install-launch.result.txt"
else
    INSTALL_LAUNCH_STATUS=FAIL
    printf 'FAIL installed_app_did_not_launch\n' | tee "$RUN_DIR/install-launch.result.txt"
fi

export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK" ANDROID_SERIAL="$SERIAL"
export ANDROID_ADB_SERVER_PORT="$ADB_PORT" ADB_SERVER_SOCKET="tcp:localhost:$ADB_PORT"
run_target logcat-clear shell logcat -c
timeout --foreground --signal=TERM --kill-after=5s "${TEST_TIMEOUT}s" \
    "$ADB" -s "$SERIAL" logcat -v threadtime \
    >"$RUN_DIR/logcat-live.log" 2>"$RUN_DIR/logcat-live.stderr.log" &
LOGCAT_PID=$!

run_target install-instrumentation-apk install -t "$TEST_APK"
if [[ "$(target_exit install-instrumentation-apk)" != 0 ||
      "$(target_stdout install-instrumentation-apk)" != *Success* ]]; then
    printf 'BLOCKED instrumentation_apk_install_failed\n' | tee "$RUN_DIR/instrumentation-result.txt"
    TEST_EXIT=1
else
    TEST_APK_INSTALLED=1
    : >"$RUN_DIR/supplemental-results.tsv"
    printf 'scenario\tstatus\tclassification\n' >>"$RUN_DIR/supplemental-results.tsv"
    run_target permission-deny-coarse shell pm revoke dev.atlas.coach android.permission.ACCESS_COARSE_LOCATION
    run_target permission-deny-fine shell pm revoke dev.atlas.coach android.permission.ACCESS_FINE_LOCATION
    run_target permission-deny-notifications shell pm revoke dev.atlas.coach android.permission.POST_NOTIFICATIONS
    if [[ "$(target_exit permission-deny-coarse)" == 0 &&
          "$(target_exit permission-deny-fine)" == 0 &&
          "$(target_exit permission-deny-notifications)" == 0 ]]; then
        run_single_instrumentation permission-initial-denied \
            dev.atlas.coach.app.RuntimePermissionInstrumentationTest#systemGrantAndRevocationMatchAppPermissionGate \
            -e permission_state denied
        PERMISSION_INITIAL_STATUS="$(awk 'NR==1 {print $1}' "$RUN_DIR/permission-initial-denied.result.txt")"
        record_supplemental runtime_permission_denied "$PERMISSION_INITIAL_STATUS" \
            "$( [[ "$PERMISSION_INITIAL_STATUS" == PASS ]] && printf NONE || printf UNKNOWN )"
    else
        PERMISSION_INITIAL_STATUS=BLOCKED
        record_supplemental runtime_permission_denied BLOCKED AVD_ENVIRONMENT_BLOCKED
        printf 'BLOCKED reason=permission_revoke_setup_failed\n' >"$RUN_DIR/permission-initial-denied.result.txt"
    fi
    run_target grant-notification-permission shell pm grant dev.atlas.coach android.permission.POST_NOTIFICATIONS
    printf '\n[instrumentation] Running every test available to the generic AndroidJUnitRunner on the explicit AVD serial.\n'
    run_target instrumentation shell am instrument -w -r dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
    TEST_EXIT="$(target_exit instrumentation)"
fi
printf '%s\n' "$TEST_EXIT" >"$RUN_DIR/instrumentation.exit"
run_target test-filter-logcat shell logcat -d -s ATLAS_TEST_FILTER:I
run_target screenshot-capture-logcat shell logcat -d -s ATLAS_UI_CAPTURE:I
capture_ok_count="$( (rg -c 'CAPTURE_OK' "$RUN_DIR/screenshot-capture-logcat.stdout.log" || true) | tail -n 1)"
capture_blocked_count="$( (rg -c 'CAPTURE_BLOCKED' "$RUN_DIR/screenshot-capture-logcat.stdout.log" || true) | tail -n 1)"
capture_ok_count="${capture_ok_count:-0}"
capture_blocked_count="${capture_blocked_count:-0}"
if (( capture_blocked_count > 0 )); then SCREENSHOT_CAPTURE_STATUS=FAIL
elif (( capture_ok_count > 0 )); then SCREENSHOT_CAPTURE_STATUS=PASS
else SCREENSHOT_CAPTURE_STATUS=NOT_RUN
fi
printf 'status=%s\ncapture_ok=%s\ncapture_blocked=%s\n' \
    "$SCREENSHOT_CAPTURE_STATUS" "$capture_ok_count" "$capture_blocked_count" \
    | tee "$RUN_DIR/screenshot-capture.result.txt"
python3 - "$RUN_DIR/instrumentation.stdout.log" "$TEST_EXIT" <<'PY' | tee "$RUN_DIR/test-counts.txt"
import re
import sys

path, exit_code = sys.argv[1], int(sys.argv[2])
text = open(path, encoding="utf-8", errors="replace").read()
last_class = "?"
last_test = "?"
completed = passed = failures = errors = 0
for line in text.splitlines():
    if line.startswith("INSTRUMENTATION_STATUS: class="):
        last_class = line.split("=", 1)[1]
    elif line.startswith("INSTRUMENTATION_STATUS: test="):
        last_test = line.split("=", 1)[1]
    elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
        code = line.split(":", 1)[1].strip()
        if code in {"0", "-2", "-3"}:
            completed += 1
            if code == "0":
                passed += 1
                print(f"PASS {last_class}#{last_test}")
            elif code == "-2":
                failures += 1
                print(f"FAIL {last_class}#{last_test}")
            else:
                errors += 1
                print(f"ERROR {last_class}#{last_test}")
summary = re.findall(r"Tests run:\s*(\d+)\s*,\s*Failures:\s*(\d+)", text)
ok = re.findall(r"OK \((\d+) tests?\)", text)
if summary:
    total = int(summary[-1][0])
    failures = max(failures, int(summary[-1][1]))
elif ok:
    total = int(ok[-1])
else:
    total = completed
complete_marker = "INSTRUMENTATION_CODE: -1" in text
aborted = ("INSTRUMENTATION_ABORTED" in text or "System has crashed" in text or
           "shortMsg=Process crashed" in text)
print(f"TESTS={max(total, completed)} PASSED={passed} FAILURES={failures} ERRORS={errors} "
      f"RUNNER_COMPLETE={str(complete_marker).lower()} ABORTED={str(aborted).lower()} "
      f"ADB_EXIT={exit_code}")
PY
SUMMARY_LINE="$(tail -n 1 "$RUN_DIR/test-counts.txt")"
TEST_COUNT="$(sed -n 's/.*TESTS=\([0-9][0-9]*\).*/\1/p' <<<"$SUMMARY_LINE")"
FAILURE_COUNT="$(sed -n 's/.*FAILURES=\([0-9][0-9]*\).*/\1/p' <<<"$SUMMARY_LINE")"
ERROR_COUNT="$(sed -n 's/.*ERRORS=\([0-9][0-9]*\).*/\1/p' <<<"$SUMMARY_LINE")"
RUNNER_COMPLETE="$(sed -n 's/.*RUNNER_COMPLETE=\([a-z]*\).*/\1/p' <<<"$SUMMARY_LINE")"
RUNNER_ABORTED="$(sed -n 's/.*ABORTED=\([a-z]*\).*/\1/p' <<<"$SUMMARY_LINE")"

run_target post-test-service-list shell service list
service_failures=0
for service in package activity location notification window; do
    if ! probe_service "$service"; then service_failures=$((service_failures + 1)); fi
done
run_target post-test-crash-buffer shell logcat -b crash -d -v threadtime
run_target post-test-system-errors shell logcat -b system -d -v threadtime -s SystemServer:E AndroidRuntime:E Watchdog:E
run_target post-test-native-errors shell logcat -b main -d -v threadtime -s surfaceflinger:F android.hardware.uwb-service:F ot-daemon:F lmkd:I
capture_screenshot post-test-screencap
if (( service_failures == 0 )); then
    run_home_roundtrip
else
    HOME_ROLE_RESULT=BLOCKED
    HOME_RETURN_RESULT=BLOCKED
    HOME_RESTORE_RESULT=BLOCKED
    printf 'BLOCKED reason=framework_service_probe_failure count=%s\n' "$service_failures" \
        | tee "$RUN_DIR/home-roundtrip.result.txt" "$RUN_DIR/home-return.result.txt" "$RUN_DIR/home-restore.result.txt"
fi

FOCUS_STATUS="$(test_status_for dev.atlas.coach.AtlasLaunchTest#directiveFocusHabitSimulatorAndLibraryJourneysWorkOffline)"
GOALS_STATUS="$(class_status_for dev.atlas.coach.app.GoalFocusStartInstrumentationTest#)"
NOTIFICATIONS_STATUS="$(combine_statuses \
    "$(class_status_for dev.atlas.coach.FocusIntegrationInstrumentationTest#)" \
    "$(class_status_for dev.atlas.coach.app.ProactiveCoachNotificationInstrumentationTest#)" \
    "$(class_status_for dev.atlas.coach.app.ProactiveCoachNotificationPublisherInstrumentationTest#)" \
    "$(class_status_for dev.atlas.coach.app.ProactiveControllerPublicationAuthorizationInstrumentationTest#)")"
ROTATION_STATUS="$(test_status_for dev.atlas.coach.app.V62NativeUiCaptureTest#landscapeHomeKeepsPauseAndFooterOutsideScrollableContent)"
case "$FOCUS_STATUS" in
    PASS) scenario_cause=NONE ;;
    BLOCKED) scenario_cause=AVD_ENVIRONMENT_BLOCKED ;;
    *) scenario_cause=UNKNOWN ;;
esac
record_supplemental focus "$FOCUS_STATUS" "$scenario_cause"
record_supplemental goals "$GOALS_STATUS" \
    "$( [[ "$GOALS_STATUS" == PASS ]] && printf NONE || [[ "$GOALS_STATUS" == BLOCKED ]] && printf AVD_ENVIRONMENT_BLOCKED || printf UNKNOWN )"
record_supplemental notifications "$NOTIFICATIONS_STATUS" \
    "$( [[ "$NOTIFICATIONS_STATUS" == PASS ]] && printf NONE || [[ "$NOTIFICATIONS_STATUS" == BLOCKED ]] && printf AVD_ENVIRONMENT_BLOCKED || printf UNKNOWN )"
record_supplemental rotation "$ROTATION_STATUS" \
    "$( [[ "$ROTATION_STATUS" == PASS ]] && printf NONE || [[ "$ROTATION_STATUS" == BLOCKED ]] && printf AVD_ENVIRONMENT_BLOCKED || printf UNKNOWN )"

if [[ "$TEST_APK_INSTALLED" == 1 && "$service_failures" == 0 ]]; then
    run_target set-system-font-scale-2x shell settings put system font_scale 2.0
    run_target verify-system-font-scale-2x shell settings get system font_scale
    if [[ "$(target_exit set-system-font-scale-2x)" == 0 &&
          "$(tr -d '\r[:space:]' <"$RUN_DIR/verify-system-font-scale-2x.stdout.log")" == 2.0 ]]; then
        run_single_instrumentation system-font-scale-2x \
            dev.atlas.coach.app.V62NativeUiCaptureTest#systemFontScaleTwoKeepsEmergencyControlsAccessible \
            -e expected_system_font_scale 2.0
        FONT_SCALE_STATUS="$(awk 'NR==1 {print $1}' "$RUN_DIR/system-font-scale-2x.result.txt")"
    else
        FONT_SCALE_STATUS=BLOCKED
        printf 'BLOCKED reason=system_font_scale_setting_not_applied\n' >"$RUN_DIR/system-font-scale-2x.result.txt"
    fi
    record_supplemental font_scale_2x "$FONT_SCALE_STATUS" \
        "$( [[ "$FONT_SCALE_STATUS" == PASS ]] && printf NONE || [[ "$FONT_SCALE_STATUS" == BLOCKED ]] && printf AVD_ENVIRONMENT_BLOCKED || printf UNKNOWN )"
    run_target restore-system-font-scale shell settings put system font_scale 1.0
    run_target verify-restored-font-scale shell settings get system font_scale
    if [[ "$(target_exit restore-system-font-scale)" != 0 ||
          "$(tr -d '\r[:space:]' <"$RUN_DIR/verify-restored-font-scale.stdout.log")" != 1.0 ]]; then
        record_supplemental font_scale_restore FAIL UNKNOWN
    fi

    run_target offline-airplane-mode-enable shell settings put global airplane_mode_on 1
    run_target offline-airplane-mode-broadcast shell am broadcast \
        -a android.intent.action.AIRPLANE_MODE --ez state true
    run_target offline-wifi-disable shell svc wifi disable
    run_target offline-data-disable shell svc data disable
    run_target offline-connectivity-snapshot shell dumpsys connectivity
    run_target offline-airplane-mode-verify shell settings get global airplane_mode_on
    if [[ "$(target_exit offline-airplane-mode-enable)" == 0 &&
          "$(target_exit offline-airplane-mode-broadcast)" == 0 &&
          "$(target_exit offline-wifi-disable)" == 0 &&
          "$(target_exit offline-data-disable)" == 0 &&
          "$(tr -d '\r[:space:]' <"$RUN_DIR/offline-airplane-mode-verify.stdout.log")" == 1 ]]; then
        run_single_instrumentation offline-local-journey \
            dev.atlas.coach.AtlasLaunchTest#directiveFocusHabitSimulatorAndLibraryJourneysWorkOffline
        OFFLINE_STATUS="$(awk 'NR==1 {print $1}' "$RUN_DIR/offline-local-journey.result.txt")"
    else
        OFFLINE_STATUS=BLOCKED
        printf 'BLOCKED reason=airplane_mode_setup_or_verification_failed\n' >"$RUN_DIR/offline-local-journey.result.txt"
    fi
    record_supplemental offline "$OFFLINE_STATUS" \
        "$( [[ "$OFFLINE_STATUS" == PASS ]] && printf NONE || [[ "$OFFLINE_STATUS" == BLOCKED ]] && printf AVD_ENVIRONMENT_BLOCKED || printf UNKNOWN )"
    run_target offline-airplane-mode-disable shell settings put global airplane_mode_on 0
    run_target offline-airplane-mode-restore-broadcast shell am broadcast \
        -a android.intent.action.AIRPLANE_MODE --ez state false
    run_target offline-wifi-restore shell svc wifi enable
    run_target offline-data-restore shell svc data enable

    run_target permission-grant-coarse shell pm grant dev.atlas.coach android.permission.ACCESS_COARSE_LOCATION
    run_target permission-ensure-fine-denied shell pm revoke dev.atlas.coach android.permission.ACCESS_FINE_LOCATION
    run_target permission-grant-notifications shell pm grant dev.atlas.coach android.permission.POST_NOTIFICATIONS
    run_target enable-location-provider shell cmd location set-location-enabled true
    run_target granted-permission-state-dump shell dumpsys package dev.atlas.coach
    run_target granted-location-state-dump shell dumpsys location
    if [[ "$(target_exit permission-grant-coarse)" == 0 &&
          "$(target_exit permission-ensure-fine-denied)" == 0 &&
          "$(target_exit permission-grant-notifications)" == 0 &&
          "$(target_exit enable-location-provider)" == 0 ]]; then
        run_single_instrumentation permission-granted \
            dev.atlas.coach.app.RuntimePermissionInstrumentationTest#systemGrantAndRevocationMatchAppPermissionGate \
            -e permission_state granted
        PERMISSION_GRANTED_STATUS="$(awk 'NR==1 {print $1}' "$RUN_DIR/permission-granted.result.txt")"
    else
        PERMISSION_GRANTED_STATUS=BLOCKED
        printf 'BLOCKED reason=permission_grant_or_location_setup_failed\n' >"$RUN_DIR/permission-granted.result.txt"
    fi
    record_supplemental runtime_permission_granted "$PERMISSION_GRANTED_STATUS" \
        "$( [[ "$PERMISSION_GRANTED_STATUS" == PASS ]] && printf NONE || [[ "$PERMISSION_GRANTED_STATUS" == BLOCKED ]] && printf AVD_ENVIRONMENT_BLOCKED || printf UNKNOWN )"

    run_single_instrumentation location-consent-prepare \
        dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#prepareSharingConsentBeforeHostInjectsFreshEmulatorFix \
        -e location_fix_injection host
    location_prepare_status="$(awk 'NR==1 {print $1}' "$RUN_DIR/location-consent-prepare.result.txt")"
    if [[ "$location_prepare_status" == PASS ]]; then
        run_synchronized_instrumentation location-one-shot-capture \
            dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#injectedCoarseFixIsMinimizedAndStoredOnlyAsAZoneSummary \
            COARSE_CAPTURE_MANAGER_CALL
        LOCATION_STATUS="$(awk 'NR==1 {print $1}' "$RUN_DIR/location-one-shot-capture.result.txt")"
    else
        LOCATION_STATUS="$location_prepare_status"
        printf '%s reason=location_consent_prepare_%s\n' "$LOCATION_STATUS" "$location_prepare_status" \
            >"$RUN_DIR/location-one-shot-capture.result.txt"
    fi
    record_supplemental location_one_shot "$LOCATION_STATUS" \
        "$( [[ "$LOCATION_STATUS" == PASS ]] && printf NONE || [[ "$LOCATION_STATUS" == BLOCKED ]] && printf AVD_ENVIRONMENT_BLOCKED || printf UNKNOWN )"

    run_single_instrumentation mobility-consent-prepare \
        dev.atlas.coach.location.V62MobilityServiceInstrumentedTest#prepareSharingConsentBeforeHostInjectsFreshMobilityFix \
        -e location_fix_injection host
    mobility_prepare_status="$(awk 'NR==1 {print $1}' "$RUN_DIR/mobility-consent-prepare.result.txt")"
    if [[ "$mobility_prepare_status" == PASS ]]; then
        run_synchronized_instrumentation mobility-visible-session \
            dev.atlas.coach.location.V62MobilityServiceInstrumentedTest#visibleStartShowsStopNotificationAndServiceStopsWithoutRawTrail \
            MOBILITY_SERVICE_START_REQUEST
        MOBILITY_STATUS="$(awk 'NR==1 {print $1}' "$RUN_DIR/mobility-visible-session.result.txt")"
    else
        MOBILITY_STATUS="$mobility_prepare_status"
        printf '%s reason=mobility_consent_prepare_%s\n' "$MOBILITY_STATUS" "$mobility_prepare_status" \
            >"$RUN_DIR/mobility-visible-session.result.txt"
    fi
    record_supplemental mobility_visible "$MOBILITY_STATUS" \
        "$( [[ "$MOBILITY_STATUS" == PASS ]] && printf NONE || [[ "$MOBILITY_STATUS" == BLOCKED ]] && printf AVD_ENVIRONMENT_BLOCKED || printf UNKNOWN )"

    run_target permission-revoke-coarse shell pm revoke dev.atlas.coach android.permission.ACCESS_COARSE_LOCATION
    run_target permission-revoke-fine shell pm revoke dev.atlas.coach android.permission.ACCESS_FINE_LOCATION
    run_target permission-revoke-notifications shell pm revoke dev.atlas.coach android.permission.POST_NOTIFICATIONS
    run_target revoked-permission-state-dump shell dumpsys package dev.atlas.coach
    if [[ "$(target_exit permission-revoke-coarse)" == 0 &&
          "$(target_exit permission-revoke-fine)" == 0 &&
          "$(target_exit permission-revoke-notifications)" == 0 ]]; then
        run_single_instrumentation permission-revoked \
            dev.atlas.coach.app.RuntimePermissionInstrumentationTest#systemGrantAndRevocationMatchAppPermissionGate \
            -e permission_state revoked
        PERMISSION_REVOKED_STATUS="$(awk 'NR==1 {print $1}' "$RUN_DIR/permission-revoked.result.txt")"
    else
        PERMISSION_REVOKED_STATUS=BLOCKED
        printf 'BLOCKED reason=permission_revoke_setup_failed\n' >"$RUN_DIR/permission-revoked.result.txt"
    fi
    record_supplemental runtime_permission_revoked "$PERMISSION_REVOKED_STATUS" \
        "$( [[ "$PERMISSION_REVOKED_STATUS" == PASS ]] && printf NONE || [[ "$PERMISSION_REVOKED_STATUS" == BLOCKED ]] && printf AVD_ENVIRONMENT_BLOCKED || printf UNKNOWN )"
else
    for scenario in font_scale_2x offline runtime_permission_granted location_one_shot mobility_visible runtime_permission_revoked; do
        record_supplemental "$scenario" BLOCKED AVD_ENVIRONMENT_BLOCKED
    done
    FONT_SCALE_STATUS=BLOCKED
    OFFLINE_STATUS=BLOCKED
    PERMISSION_GRANTED_STATUS=BLOCKED
    LOCATION_STATUS=BLOCKED
    MOBILITY_STATUS=BLOCKED
    PERMISSION_REVOKED_STATUS=BLOCKED
fi

PERMISSION_STATUS="$(combine_statuses "$PERMISSION_INITIAL_STATUS" "$PERMISSION_GRANTED_STATUS" "$PERMISSION_REVOKED_STATUS")"
REVOCATION_STATUS="$(combine_statuses "$PERMISSION_REVOKED_STATUS" "$MOBILITY_STATUS")"
printf 'records=%s\nfailure_count=%s\nblocked_count=%s\n' \
    "$(tail -n +2 "$RUN_DIR/supplemental-results.tsv" 2>/dev/null | wc -l | tr -d ' ')" \
    "$SUPPLEMENTAL_FAILURES" "$SUPPLEMENTAL_BLOCKED" >"$RUN_DIR/supplemental-counts.txt"

scenario_matrix="$RUN_DIR/scenario-matrix.tsv"
printf 'scenario\tstatus\tcause\tevidence\n' >"$scenario_matrix"
matrix_row() {
    local scenario="$1" status="$2" evidence="$3" cause
    case "$status" in
        PASS) cause=NONE ;;
        BLOCKED) cause=AVD_ENVIRONMENT_BLOCKED ;;
        FAIL) cause=UNKNOWN ;;
        NOT_RUN) cause=UNKNOWN ;;
        *) status=NOT_RUN; cause=UNKNOWN ;;
    esac
    printf '%s\t%s\t%s\t%s\n' "$scenario" "$status" "$cause" "$evidence" >>"$scenario_matrix"
}
matrix_row installation_launch "${INSTALL_LAUNCH_STATUS:-NOT_RUN}" "$RUN_DIR/install-launch.result.txt"
matrix_row home_role "$HOME_ROLE_RESULT" "$RUN_DIR/home-roundtrip.result.txt"
matrix_row previous_launcher "$HOME_RETURN_RESULT" "$RUN_DIR/home-return.result.txt"
matrix_row focus "$FOCUS_STATUS" "$RUN_DIR/test-counts.txt"
matrix_row goals "$GOALS_STATUS" "$RUN_DIR/test-counts.txt"
matrix_row notifications "$NOTIFICATIONS_STATUS" "$RUN_DIR/test-counts.txt"
matrix_row location_one_shot "$LOCATION_STATUS" "$RUN_DIR/location-one-shot-capture.result.txt"
matrix_row mobility_visible "$MOBILITY_STATUS" "$RUN_DIR/mobility-visible-session.result.txt"
matrix_row runtime_permissions "$PERMISSION_STATUS" "$RUN_DIR/supplemental-results.tsv"
matrix_row revocation "$REVOCATION_STATUS" "$RUN_DIR/supplemental-results.tsv"
matrix_row rotation "$ROTATION_STATUS" "$RUN_DIR/test-counts.txt"
matrix_row font_scale_2x "$FONT_SCALE_STATUS" "$RUN_DIR/system-font-scale-2x.result.txt"
matrix_row offline "$OFFLINE_STATUS" "$RUN_DIR/offline-local-journey.result.txt"
cp "$scenario_matrix" "$RUN_DIR/scenario-matrix.result.tsv"

if (( service_failures > 0 )) && [[ "${TEST_COUNT:-0}" == 0 ]]; then
    SUITE_STATUS=BLOCKED
    SUITE_CAUSE=AVD_ENVIRONMENT_BLOCKED
    capture_diagnostics framework_services_lost_or_instrumentation_aborted
elif (( service_failures == 5 )); then
    SUITE_STATUS=BLOCKED
    SUITE_CAUSE=AVD_ENVIRONMENT_BLOCKED
    capture_diagnostics framework_services_lost_or_instrumentation_aborted
elif [[ "$RUNNER_ABORTED" == true && "${TEST_COUNT:-0}" != 0 ]]; then
    SUITE_STATUS=FAIL
    SUITE_CAUSE=UNKNOWN
elif [[ "${TEST_COUNT:-0}" == 0 || "$RUNNER_COMPLETE" != true ]]; then
    SUITE_STATUS=NOT_RUN
    SUITE_CAUSE=TEST_HARNESS_BUG
elif [[ "$TEST_EXIT" == 0 && "${FAILURE_COUNT:-0}" == 0 && "${ERROR_COUNT:-0}" == 0 ]]; then
    SUITE_STATUS=PASS
    SUITE_CAUSE=NONE
else
    SUITE_STATUS=FAIL
    SUITE_CAUSE=ASSERTION_OR_RUNNER_FAILURE
fi
if (( SUPPLEMENTAL_FAILURES > 0 )) && [[ "$SUITE_STATUS" != BLOCKED ]]; then
    SUITE_STATUS=FAIL
    [[ "$SUITE_CAUSE" == NONE ]] && SUITE_CAUSE=UNKNOWN
elif (( SUPPLEMENTAL_BLOCKED > 0 )) && [[ "$SUITE_STATUS" == PASS ]]; then
    SUITE_STATUS=BLOCKED
    SUITE_CAUSE=AVD_ENVIRONMENT_BLOCKED
fi
if [[ "$SCREENSHOT_CAPTURE_STATUS" == FAIL && "$SUITE_STATUS" != BLOCKED ]]; then
    SUITE_STATUS=FAIL
    [[ "$SUITE_CAUSE" == NONE ]] && SUITE_CAUSE=UNKNOWN
fi
if [[ "$HOME_ROLE_RESULT" == FAIL || "$HOME_RETURN_RESULT" == FAIL || "$HOME_RESTORE_RESULT" == FAIL ]]; then SUITE_STATUS=FAIL; fi
printf 'suite_status=%s\nsuite_cause=%s\ntests=%s\nfailures=%s\nerrors=%s\ninstall_launch_status=%s\nhome_role_status=%s\nhome_return_status=%s\nhome_restore_status=%s\nfocus_status=%s\ngoals_status=%s\nnotifications_status=%s\nscreenshot_capture_status=%s\nlocation_status=%s\nmobility_status=%s\nruntime_permissions_status=%s\nrevocation_status=%s\nrotation_status=%s\nfont_scale_2x_status=%s\noffline_status=%s\nsupplemental_failures=%s\nsupplemental_blocked=%s\ntest_exit=%s\nrun_dir=%s\n' \
    "$SUITE_STATUS" "$SUITE_CAUSE" "${TEST_COUNT:-0}" "${FAILURE_COUNT:-0}" \
    "${ERROR_COUNT:-0}" "${INSTALL_LAUNCH_STATUS:-NOT_RUN}" "$HOME_ROLE_RESULT" "$HOME_RETURN_RESULT" "$HOME_RESTORE_RESULT" \
    "$FOCUS_STATUS" "$GOALS_STATUS" "$NOTIFICATIONS_STATUS" "$SCREENSHOT_CAPTURE_STATUS" "$LOCATION_STATUS" "$MOBILITY_STATUS" \
    "$PERMISSION_STATUS" "$REVOCATION_STATUS" "$ROTATION_STATUS" "$FONT_SCALE_STATUS" "$OFFLINE_STATUS" \
    "$SUPPLEMENTAL_FAILURES" "$SUPPLEMENTAL_BLOCKED" "$TEST_EXIT" "$RUN_DIR" | tee "$RUN_DIR/recipe.result.txt"
[[ "$SUITE_STATUS" == PASS ]] || exit 1
exit 0
