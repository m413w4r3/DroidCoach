#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="/home/nill/Android/Sdk"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
AVD_NAME="atlas_v622_disposable_api37"
AVD_HOME="/tmp/atlas-v622-s4/avd"
AVD_DIR="$AVD_HOME/$AVD_NAME"
IMAGE="system-images;android-37.2;google_apis_ps16k;x86_64"
GPU="${ATLAS_V622_EMULATOR_GPU:-auto}"
SERIAL="emulator-5582"
ADB_SERVER_PORT="5038"
ADB_COMMAND_TIMEOUT_SECONDS="${ATLAS_V622_ADB_TIMEOUT_SECONDS:-20}"
ADB_SERVICE_TIMEOUT_SECONDS="${ATLAS_V622_ADB_SERVICE_TIMEOUT_SECONDS:-5}"
ADB_INSTALL_TIMEOUT_SECONDS="${ATLAS_V622_ADB_INSTALL_TIMEOUT_SECONDS:-60}"
ADB_INSTRUMENT_TIMEOUT_SECONDS="${ATLAS_V622_ADB_INSTRUMENT_TIMEOUT_SECONDS:-180}"
ADB_SCREENCAP_TIMEOUT_SECONDS="${ATLAS_V622_ADB_SCREENCAP_TIMEOUT_SECONDS:-30}"
APP_APK="${ATLAS_V622_APP_APK:-$ROOT/android/app/build/outputs/apk/debug/app-debug.apk}"
TEST_APK="${ATLAS_V622_TEST_APK:-$ROOT/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
EXPECTED_APP_SHA256="${ATLAS_V622_EXPECTED_APP_SHA256:-}"
EXPECTED_TEST_SHA256="${ATLAS_V622_EXPECTED_TEST_SHA256:-}"
LOG_ROOT="${ATLAS_V622_LOG_DIR:-/tmp/atlas-v622-s4}"
RUN_DIR="$LOG_ROOT/run-$(date -u +%Y%m%dT%H%M%S.%N)"
APK_SNAPSHOT_DIR="$RUN_DIR/apk-snapshot"
APP_APK_SNAPSHOT="$APK_SNAPSHOT_DIR/app-debug.apk"
TEST_APK_SNAPSHOT="$APK_SNAPSHOT_DIR/app-debug-androidTest.apk"
RUNNER="dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner"
STARTED_EMULATOR=0
STARTED_ADB_SERVER=0
EMULATOR_PID=""
INSTRUMENTATION_BLOCKED=0
FRAMEWORK_DIAGNOSTICS_CAPTURED=0
CLEANUP_DONE=0
LOGCAT_PID=""
INSTRUMENTATION_PID=""

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_AVD_HOME="$AVD_HOME"
export ANDROID_ADB_SERVER_PORT="$ADB_SERVER_PORT"
export ADB_SERVER_SOCKET="tcp:localhost:$ADB_SERVER_PORT"
export PATH="$SDK/platform-tools:$SDK/emulator:$PATH"

mkdir -p "$LOG_ROOT" "$AVD_HOME"
mkdir "$RUN_DIR"
exec > >(tee -a "$RUN_DIR/recipe.console.log") 2>&1

run_limited_command() {
    local limit_seconds="$1"
    shift
    local status command_text
    printf -v command_text '%q ' "$@"
    if timeout --foreground --signal=TERM --kill-after=5s "${limit_seconds}s" "$@"; then
        return 0
    else
        status=$?
    fi
    if [[ "$status" == 124 || "$status" == 137 ]]; then
        local timeout_record="ADB_TIMEOUT exit=$status limit_s=$limit_seconds command=$command_text"
        printf '%s\n' "$timeout_record" >>"$RUN_DIR/adb-timeouts.log"
        printf '%s\n' "$timeout_record" >&2
    fi
    return "$status"
}

adb_target() {
    local limit_seconds="$ADB_COMMAND_TIMEOUT_SECONDS"
    case "${1:-} ${2:-} ${3:-}" in
        'shell service check '*) limit_seconds="$ADB_SERVICE_TIMEOUT_SECONDS" ;;
        'shell dumpsys location'*) limit_seconds="$ADB_SERVICE_TIMEOUT_SECONDS" ;;
        'shell am instrument '*) limit_seconds="$ADB_INSTRUMENT_TIMEOUT_SECONDS" ;;
    esac
    if [[ "${1:-}" == install ]]; then
        limit_seconds="$ADB_INSTALL_TIMEOUT_SECONDS"
    elif [[ "${1:-} ${2:-}" == 'exec-out screencap' ]]; then
        limit_seconds="$ADB_SCREENCAP_TIMEOUT_SECONDS"
    fi
    run_limited_command "$limit_seconds" "$ADB" -P "$ADB_SERVER_PORT" -s "$SERIAL" "$@"
}

adb_server() {
    run_limited_command "$ADB_COMMAND_TIMEOUT_SECONDS" "$ADB" -P "$ADB_SERVER_PORT" "$@"
}

capture_adb() {
    local label="$1"
    local output_variable="$2"
    shift 2
    local stdout_file="$RUN_DIR/$label.stdout.log"
    local stderr_file="$RUN_DIR/$label.stderr.log"
    local status captured result
    if adb_target "$@" >"$stdout_file" 2>"$stderr_file"; then
        status=0
    else
        status=$?
    fi
    printf '%s\n' "$status" >"$RUN_DIR/$label.exit"
    case "$(command_result_kind "$status")" in
        COMMAND_OK) result=COMMAND_OK ;;
        BLOCKED_TIMEOUT) result=BLOCKED_TIMEOUT ;;
        *) result=FAIL_COMMAND ;;
    esac
    printf '%s exit=%s\n' "$result" "$status" >"$RUN_DIR/$label.result.txt"
    if [[ "$status" == 0 ]]; then
        captured="$(cat "$stdout_file")"
    else
        captured=""
    fi
    printf -v "$output_variable" '%s' "$captured"
    return "$status"
}

command_result_kind() {
    case "$1" in
        0) printf 'COMMAND_OK' ;;
        124|137) printf 'BLOCKED_TIMEOUT' ;;
        *) printf 'FAIL_COMMAND' ;;
    esac
}

service_response_kind() {
    local service="$1"
    local response="${2//$'\r'/}"
    case "$response" in
        "Service $service: found") printf 'found' ;;
        "Service $service: not found") printf 'not_found' ;;
        '') printf 'absent' ;;
        *) printf 'unexpected' ;;
    esac
}

framework_services_all_not_found() {
    [[ "$(service_response_kind package "$1")" == not_found &&
        "$(service_response_kind activity "$2")" == not_found &&
        "$(service_response_kind location "$3")" == not_found &&
        "$(service_response_kind notification "$4")" == not_found &&
        "$(service_response_kind window "$5")" == not_found ]]
}

check_framework_service() {
    local service="$1"
    local output_variable="$2"
    local output="" status response_kind result
    if capture_adb "framework-service-$service" output shell service check "$service"; then
        status=0
    else
        status=$?
    fi
    output="${output//$'\r'/}"
    response_kind="$(service_response_kind "$service" "$output")"
    case "$status" in
        124|137) result=BLOCKED_TIMEOUT ;;
        0)
            case "$response_kind" in
                found) result=PASS ;;
                not_found) result=BLOCKED_SERVICE_NOT_FOUND ;;
                absent) result=BLOCKED_EMPTY_RESPONSE ;;
                *) result=FAIL_UNEXPECTED_RESPONSE ;;
            esac
            ;;
        *) result=FAIL_COMMAND ;;
    esac
    printf '%s service=%s adb_exit=%s response_kind=%s output=%q\n' \
        "$result" "$service" "$status" "$response_kind" "$output" \
        | tee -a "$RUN_DIR/framework-service-probes.log"
    printf -v "$output_variable" '%s' "$output"
    [[ "$result" == PASS ]]
}

instrumentation_result_kind() {
    local expected_method="$1"
    local exit_status="$2"
    local output="$3"
    if [[ "$exit_status" == 124 || "$exit_status" == 137 ]] ||
        [[ "$output" == *ADB_TIMEOUT* || "$output" == *INSTRUMENTATION_ABORTED* || "$output" == *"System has crashed"* || "$output" == *"shortMsg=Process crashed"* ]]; then
        printf 'BLOCKED'
        return 0
    fi
    if [[ "$exit_status" != 0 ]]; then
        printf 'FAIL'
        return 0
    fi
    if [[ "$output" == *'INSTRUMENTATION_STATUS: numtests=1'* &&
        "$output" == *"INSTRUMENTATION_STATUS: test=$expected_method"* &&
        "$output" == *'INSTRUMENTATION_STATUS_CODE: 0'* &&
        "$output" == *'OK (1 test)'* &&
        "$output" == *'INSTRUMENTATION_CODE: -1'* &&
        "$output" != *'INSTRUMENTATION_STATUS_CODE: -2'* &&
        "$output" != *'INSTRUMENTATION_STATUS_CODE: -3'* ]]; then
        printf 'PASS'
        return 0
    fi
    printf 'FAIL'
}

source_target_declared() {
    local target="$1"
    local class_name="${target%%#*}"
    local method_name="${target##*#}"
    local package_name="${class_name%.*}"
    local simple_class="${class_name##*.}"
    local source="$ROOT/android/app/src/androidTest/java/${class_name//.//}.kt"
    [[ "$target" == *#* && "$class_name" != "$target" && -f "$source" ]] || return 1
    grep -Fxq "package $package_name" "$source" || return 1
    grep -Eq "^[[:space:]]*class[[:space:]]+$simple_class([[:space:](]|$)" "$source" || return 1
    grep -Eq "^[[:space:]]*fun[[:space:]]+$method_name[[:space:]]*\\(" "$source"
}

validate_instrument_source_targets() {
    local target
    local declared_target
    local failures=0
    local invocation_count=0
    local targets=(
        'dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#prepareSharingConsentBeforeHostInjectsFreshEmulatorFix'
        'dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#injectedCoarseFixIsMinimizedAndStoredOnlyAsAZoneSummary'
        'dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#deniedPermissionStateReturnsFunctionalErrorWithoutCrashing'
        'dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#api26Through29UseListenerAndReturnTimeoutOrUnavailableCodes'
        'dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#permissionRevocationDuringAcquisitionIsRecheckedBeforeReturningFix'
        'dev.atlas.coach.AtlasLaunchTest#directiveFocusHabitSimulatorAndLibraryJourneysWorkOffline'
        'dev.atlas.coach.app.ProactiveCoachNotificationInstrumentationTest#tappingAfterMandateRevocationShowsNeutralMessageAndDoesNotNavigateOrDispatch'
        'dev.atlas.coach.app.LauncherPresentationTest#emergencyAndLauncherExitActionsStayReachableAtFontScaleTwo'
        'dev.atlas.coach.app.LauncherDrawerLazyInstrumentationTest#drawerScrollsToLastSyntheticAppAndKeepsFooterClickableInLandscapeAtFontScaleTwo'
        'dev.atlas.coach.app.GoalFocusStartInstrumentationTest#focusGoalSelectorOffersNoneOneAndManyAndCreditsOnlyFinishedLinkedWork'
        'dev.atlas.coach.app.GoalFocusStartInstrumentationTest#singleGoalCanChooseNoGoalAndChoiceStaysFreeWhenEligibilityChanges'
        'dev.atlas.coach.app.GoalFocusStartInstrumentationTest#localGoalLinkSurvivesPauseResumeFinishUnrelatedWriteAndRealControllerRecreation'
        'dev.atlas.coach.app.GoalFocusStartInstrumentationTest#agentStartCommitsTheLocalGoalChoiceWithItsIdempotencyKeyOnce'
        'dev.atlas.coach.app.GoalFocusStartInstrumentationTest#failedInitialCommitHasNoSessionGoalAlarmStartedEventOrRecreatedState'
        'dev.atlas.coach.app.GoalFocusStartInstrumentationTest#controllerRecreationAfterElapsedFocusPersistsOneCompletedCycleAndProgress'
        'dev.atlas.coach.app.GoalFocusStartInstrumentationTest#legacyV621HistoryIsPreservedWithoutInventedGoalLinks'
        'dev.atlas.coach.app.ProactiveControllerPublicationAuthorizationInstrumentationTest#latePlanResponseAfterCategoryRevocationAndSameClockReoptInHasNoLocalEffects'
        'dev.atlas.coach.app.ProactiveControllerPublicationAuthorizationInstrumentationTest#revocationAfterTransportReturnButBeforeResponseMergeHasNoLocalEffects'
        'dev.atlas.coach.app.ProactiveCoachNotificationPublisherInstrumentationTest#revocationRemovesOnlyProactiveChannelIncludingLegacyUntaggedNotice'
        'dev.atlas.coach.app.ProactiveCoachNotificationPublisherInstrumentationTest#finalAuthorizationGateRejectsAndConcurrentRevocationSerializesTargetedRemoval'
        'dev.atlas.coach.location.V62MobilityServiceInstrumentedTest#prepareSharingConsentBeforeHostInjectsFreshMobilityFix'
        'dev.atlas.coach.location.V62MobilityServiceInstrumentedTest#visibleStartShowsStopNotificationAndServiceStopsWithoutRawTrail'
    )
    : >"$RUN_DIR/instrument-target-source-check.log"
    : >"$RUN_DIR/instrument-target-invocation-audit.log"
    for target in "${targets[@]}"; do
        if source_target_declared "$target"; then
            printf 'PASS source_declared target=%s\n' "$target" | tee -a "$RUN_DIR/instrument-target-source-check.log"
        else
            printf 'FAIL source_missing target=%s\n' "$target" | tee -a "$RUN_DIR/instrument-target-source-check.log"
            failures=$((failures + 1))
        fi
    done
    while IFS= read -r target; do
        [[ -n "$target" ]] || continue
        invocation_count=$((invocation_count + 1))
        local matched=0
        for declared_target in "${targets[@]}"; do
            if [[ "$target" == "$declared_target" ]]; then
                matched=1
                break
            fi
        done
        if (( matched == 0 )); then
            printf 'FAIL invocation_not_in_source_audit target=%s\n' "$target" \
                | tee -a "$RUN_DIR/instrument-target-invocation-audit.log"
            failures=$((failures + 1))
        elif source_target_declared "$target"; then
            printf 'PASS invocation_fqcn_and_method_declared target=%s\n' "$target" \
                | tee -a "$RUN_DIR/instrument-target-invocation-audit.log"
        else
            printf 'FAIL invocation_fqcn_or_method_mismatch target=%s\n' "$target" \
                | tee -a "$RUN_DIR/instrument-target-invocation-audit.log"
            failures=$((failures + 1))
        fi
    done < <(awk '
        /^[[:space:]]*(run_instrumented|run_synchronized_instrumented)[[:space:]]+/ && /\\$/ { waiting=1; next }
        waiting && /^[[:space:]]*dev\.atlas\./ {
            gsub(/^[[:space:]]+|[[:space:]]+$/, "")
            sub(/\\$/, "")
            gsub(/[[:space:]]+$/, "")
            print
            waiting=0
            next
        }
        waiting && NF { print "<malformed instrumentation invocation>"; waiting=0 }
    ' "$ROOT/scripts/v622-android17-recipe.sh")
    if (( invocation_count == 0 )); then
        printf 'FAIL no run_instrumented targets discovered\n' \
            | tee -a "$RUN_DIR/instrument-target-invocation-audit.log"
        failures=$((failures + 1))
    fi
    if (( invocation_count != ${#targets[@]} )); then
        printf 'FAIL invocation_inventory_count expected=%s actual=%s\n' "${#targets[@]}" "$invocation_count" \
            | tee -a "$RUN_DIR/instrument-target-invocation-audit.log"
        failures=$((failures + 1))
    fi
    for target in "${targets[@]}"; do
        local target_invocation_count
        target_invocation_count="$(grep -Fxc "PASS invocation_fqcn_and_method_declared target=$target" "$RUN_DIR/instrument-target-invocation-audit.log" || true)"
        if [[ "$target_invocation_count" != 1 ]]; then
            printf 'FAIL invocation_coverage target=%s count=%s expected=1\n' "$target" "$target_invocation_count" \
                | tee -a "$RUN_DIR/instrument-target-invocation-audit.log"
            failures=$((failures + 1))
        fi
    done
    printf 'invocation_targets=%s\n' "$invocation_count" \
        >"$RUN_DIR/instrument-target-invocation-audit.result.txt"
    if (( failures == 0 )); then
        printf 'PASS source_targets=%s invoked_targets=%s\n' \
            "${#targets[@]}" "$invocation_count" >"$RUN_DIR/instrument-target-source-check.result.txt"
        return 0
    fi
    printf 'FAIL missing=%s\n' "$failures" >"$RUN_DIR/instrument-target-source-check.result.txt"
    return 1
}

snapshot_apk() {
    local label="$1"
    local source="$2"
    local expected="$3"
    local destination="$4"
    local before after copied
    local result_file="$RUN_DIR/$label-snapshot.result.txt"
    local log_file="$RUN_DIR/$label-snapshot.log"
    if [[ ! -f "$source" || ! "$expected" =~ ^[[:xdigit:]]{64}$ ]]; then
        printf 'FAIL invalid_source_or_expected_hash\n' | tee "$result_file" "$log_file"
        return 1
    fi
    before="$(sha256sum "$source" | awk '{print $1}')"
    mkdir -p "$(dirname "$destination")"
    if ! cp --reflink=never -- "$source" "$destination"; then
        printf 'FAIL copy_failed source=%s\n' "$source" | tee "$result_file" "$log_file"
        return 1
    fi
    after="$(sha256sum "$source" | awk '{print $1}')"
    copied="$(sha256sum "$destination" | awk '{print $1}')"
    {
        printf 'source=%s\nsource_sha256_before=%s\nsource_sha256_after=%s\nsnapshot=%s\nsnapshot_sha256=%s\nexpected_sha256=%s\n' \
            "$source" "$before" "$after" "$destination" "$copied" "$expected"
    } >"$log_file"
    if [[ "$before" != "$after" ]]; then
        printf 'BLOCKED source_changed_during_snapshot\n' >"$result_file"
        cat "$result_file" "$log_file"
        return 1
    fi
    if [[ "$copied" != "$expected" ]]; then
        printf 'FAIL snapshot_hash_mismatch\n' >"$result_file"
        cat "$result_file" "$log_file"
        return 1
    fi
    chmod 0444 "$destination"
    printf 'PASS read_only_snapshot_sha256=%s\n' "$copied" >"$result_file"
    cat "$result_file" "$log_file"
}

run_step() {
    local label="$1"
    shift
    local output="$RUN_DIR/$label.log"
    local status
    printf '\n[%s] command:' "$label"
    printf ' %q' "$@"
    printf '\n'
    if "$@" >"$output" 2>&1; then
        status=0
    else
        status=$?
    fi
    printf '%s\n' "$status" >"$RUN_DIR/$label.exit"
    case "$(command_result_kind "$status")" in
        COMMAND_OK) printf 'COMMAND_OK exit=%s\n' "$status" >"$RUN_DIR/$label.result.txt" ;;
        BLOCKED_TIMEOUT) printf 'BLOCKED timeout exit=%s\n' "$status" >"$RUN_DIR/$label.result.txt" ;;
        *) printf 'FAIL command_exit=%s\n' "$status" >"$RUN_DIR/$label.result.txt" ;;
    esac
    cat "$output"
    printf '[%s] exit=%s\n' "$label" "$status"
    return 0
}

capture_framework_diagnostics() {
    local label="$1"
    [[ "$FRAMEWORK_DIAGNOSTICS_CAPTURED" == 0 ]] || return 0
    FRAMEWORK_DIAGNOSTICS_CAPTURED=1
    run_step "$label-framework-services" adb_target shell service list
    run_step "$label-framework-crash-buffer" adb_target logcat -b crash -d -v threadtime
    run_step "$label-framework-system-errors" adb_target logcat -b system -d -v threadtime -s SystemServer:E AndroidRuntime:E Watchdog:E
    run_step "$label-framework-main-errors" adb_target logcat -b main -d -v threadtime -s system_server:E ActivityManager:E ot-daemon:F lowmemorykiller:I
    run_step "$label-framework-events-buffer" adb_target logcat -b events -d -v threadtime
}

record_instrumentation_result() {
    local label="$1"
    local target="$2"
    local exit_status="$3"
    local output="$4"
    local method="${target##*#}"
    local result
    result="$(instrumentation_result_kind "$method" "$exit_status" "$output")"
    printf '%s target=%s exit=%s\n' "$result" "$target" "$exit_status" | tee "$RUN_DIR/$label.result.txt"
    if [[ "$result" == BLOCKED ]] && {
        [[ "$output" == *INSTRUMENTATION_ABORTED* || "$output" == *"System has crashed"* ||
            "$output" == *"shortMsg=Process crashed"* || "$exit_status" == 124 || "$exit_status" == 137 ]];
    }; then
        INSTRUMENTATION_BLOCKED=1
        capture_framework_diagnostics "$label"
    fi
    printf '%s\n' "$result"
}

run_instrumented() {
    local label="$1"
    local target="$2"
    shift 2
    if [[ "$INSTRUMENTATION_BLOCKED" == 1 ]]; then
        printf 'NOT_RUN target=%s reason=prior_instrumentation_framework_block\n' "$target" | tee "$RUN_DIR/$label.result.txt"
        return 0
    fi
    run_step "$label" adb_target shell am instrument -w -r "$@" -e class "$target" "$RUNNER"
    record_instrumentation_result "$label" "$target" "$(cat "$RUN_DIR/$label.exit")" "$(cat "$RUN_DIR/$label.log")" >/dev/null
}

run_synchronized_instrumented() {
    local label="$1"
    local target="$2"
    local marker="$3"
    shift 3
    local marker_deadline runner_deadline device_timestamp injection_output
    local inject_status=0 broadcast_status=0 marker_seen=0 runner_exit result
    local logcat_file="$RUN_DIR/$label.logcat.log"
    local logcat_stderr="$RUN_DIR/$label.logcat.stderr.log"
    if [[ "$INSTRUMENTATION_BLOCKED" == 1 ]]; then
        printf 'NOT_RUN target=%s reason=prior_instrumentation_framework_block\n' "$target" | tee "$RUN_DIR/$label.result.txt"
        return 0
    fi
    run_step "$label-logcat-clear" adb_target logcat -c
    if [[ "$(cat "$RUN_DIR/$label-logcat-clear.exit")" != 0 ]]; then
        printf 'BLOCKED target=%s reason=logcat_clear_failed\n' "$target" | tee "$RUN_DIR/$label.result.txt"
        return 0
    fi
    run_limited_command "$ADB_INSTRUMENT_TIMEOUT_SECONDS" "$ADB" -P "$ADB_SERVER_PORT" -s "$SERIAL" \
        logcat -v threadtime -s ATLAS_V62_LOCATION:I >"$logcat_file" 2>"$logcat_stderr" &
    LOGCAT_PID=$!
    run_step "$label" adb_target shell am instrument -w -r "$@" -e class "$target" "$RUNNER" &
    INSTRUMENTATION_PID=$!

    marker_deadline=$((SECONDS + 30))
    while (( SECONDS < marker_deadline )); do
        if grep -Fq "$marker" "$logcat_file"; then
            marker_seen=1
            break
        fi
        [[ -f "$RUN_DIR/$label.exit" ]] && break
        sleep 0.2
    done
    printf 'marker=%s\nmarker_seen=%s\n' "$marker" "$marker_seen" >"$RUN_DIR/$label-injection.sync.txt"
    if (( marker_seen )); then
        if capture_adb "$label-device-time-before-injection" device_timestamp shell date +%s%3N &&
            [[ "$device_timestamp" =~ ^[0-9]{13}$ ]]; then
            run_step "$label-geo-fix" adb_target emu geo fix 2.123456 48.123456
            inject_status="$(cat "$RUN_DIR/$label-geo-fix.exit")"
            injection_output="$(cat "$RUN_DIR/$label-geo-fix.log")"
            if [[ "$inject_status" == 0 && "$injection_output" == *OK* ]]; then
                run_step "$label-injection-broadcast" adb_target shell am broadcast \
                    -a dev.atlas.coach.location.V62_HOST_FIX_INJECTED -p dev.atlas.coach \
                    --el injected_at_utc_ms "$device_timestamp"
                broadcast_status="$(cat "$RUN_DIR/$label-injection-broadcast.exit")"
                printf 'device_timestamp_utc_ms=%s\ngeo_fix_exit=%s\nbroadcast_exit=%s\n' \
                    "$device_timestamp" "$inject_status" "$broadcast_status" >>"$RUN_DIR/$label-injection.sync.txt"
            else
                printf 'geo_fix_result=FAIL_OR_NO_OK\ngeo_fix_exit=%s\n' "$inject_status" >>"$RUN_DIR/$label-injection.sync.txt"
            fi
        else
            printf 'device_timestamp_result=FAIL_OR_INVALID\n' >>"$RUN_DIR/$label-injection.sync.txt"
        fi
    else
        printf 'injection=NOT_SENT reason=marker_not_observed_before_deadline\n' >>"$RUN_DIR/$label-injection.sync.txt"
    fi

    runner_deadline=$((SECONDS + ADB_INSTRUMENT_TIMEOUT_SECONDS + 10))
    while [[ ! -f "$RUN_DIR/$label.exit" ]] && (( SECONDS < runner_deadline )); do sleep 0.2; done
    if [[ ! -f "$RUN_DIR/$label.exit" ]] && [[ -n "$INSTRUMENTATION_PID" ]]; then
        kill -TERM "$INSTRUMENTATION_PID" 2>/dev/null || true
    fi
    if [[ -n "$INSTRUMENTATION_PID" ]]; then wait "$INSTRUMENTATION_PID" 2>/dev/null || true; fi
    INSTRUMENTATION_PID=""
    if [[ -n "$LOGCAT_PID" ]]; then
        kill -TERM "$LOGCAT_PID" 2>/dev/null || true
        wait "$LOGCAT_PID" 2>/dev/null || true
    fi
    LOGCAT_PID=""
    if [[ ! -f "$RUN_DIR/$label.exit" ]]; then
        runner_exit=124
        printf '%s\n' "$runner_exit" >"$RUN_DIR/$label.exit"
        printf 'ADB_TIMEOUT exit=124 limit_s=%s command=instrumented-runner-wrapper\n' \
            "$ADB_INSTRUMENT_TIMEOUT_SECONDS" >>"$RUN_DIR/adb-timeouts.log"
        printf 'INSTRUMENTATION_ABORTED: bounded runner wait expired\n' >"$RUN_DIR/$label.log"
    else
        runner_exit="$(cat "$RUN_DIR/$label.exit")"
    fi
    if [[ "$target" == *'#visibleStartShowsStopNotificationAndServiceStopsWithoutRawTrail' ]]; then
        run_step "$label-system-logcat" adb_target logcat -d -v threadtime -s ATLAS_V62_LOCATION:I
        if [[ "$runner_exit" == 0 &&
            "$(cat "$RUN_DIR/$label.log")" == *'There was 1 failure'* &&
            "$(cat "$RUN_DIR/$label.log")" == *'TEST_SYSTEM_BLOCKED: Android supplied no fresh coarse fix after synchronized injection'* &&
            "$(cat "$RUN_DIR/$label-system-logcat.log")" == *'MOBILITY_SHARING_REVOCATION_STOPPED active=false notification_present=false'* ]]; then
            result=BLOCKED
            printf 'PASS target=%s evidence=visible_start_and_stop_revocation_assertions_completed\n' "$target" \
                | tee "$RUN_DIR/$label-a2-start-stop.result.txt"
        else
            result="$(instrumentation_result_kind "${target##*#}" "$runner_exit" "$(cat "$RUN_DIR/$label.log")")"
            if [[ "$result" == BLOCKED ]]; then
                INSTRUMENTATION_BLOCKED=1
                capture_framework_diagnostics "$label"
            fi
        fi
        printf '%s target=%s exit=%s reason=system_did_not_supply_fresh_coarse_fix_after_injection\n' \
            "$result" "$target" "$runner_exit" | tee "$RUN_DIR/$label.result.txt"
    else
        record_instrumentation_result "$label" "$target" "$runner_exit" "$(cat "$RUN_DIR/$label.log")" >/dev/null
    fi
    printf 'synchronized_target=%s marker_seen=%s geo_fix_exit=%s broadcast_exit=%s\n' \
        "$target" "$marker_seen" "$inject_status" "$broadcast_status" >>"$RUN_DIR/$label-injection.sync.txt"
}

home_picker_bounds_center() {
    local xml_file="$1"
    local label="$2"
    python3 - "$xml_file" "$label" <<'PY'
import re
import sys
from html import unescape

xml = open(sys.argv[1], encoding="utf-8").read()
label = sys.argv[2]
for line in re.findall(r'<node\b[^>]*>', xml):
    text = re.search(r'\btext="([^"]*)"', line)
    bounds = re.search(r'\bbounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', line)
    if text and bounds and unescape(text.group(1)) == label:
        x1, y1, x2, y2 = map(int, bounds.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        raise SystemExit(0)
raise SystemExit(1)
PY
}

home_settings_dump() {
    local label="$1"
    run_step "$label-open-settings" adb_target shell am start -W -a android.settings.HOME_SETTINGS
    sleep 1
    run_step "$label-dump-ui" adb_target shell uiautomator dump /sdcard/atlas-v622-home-picker.xml
    if [[ "$(cat "$RUN_DIR/$label-dump-ui.exit")" != 0 ]]; then return 1; fi
    if ! adb_target exec-out cat /sdcard/atlas-v622-home-picker.xml >"$RUN_DIR/$label-ui.xml" 2>"$RUN_DIR/$label-ui.stderr.log"; then
        return 1
    fi
}

run_home_role_roundtrip() {
    local original_holder original_label atlas_point restore_point current_holder
    original_holder="$(tr -d '\r[:space:]' <"$RUN_DIR/initial-home-role.log")"
    original_label=""
    case "$original_holder" in
        com.google.android.apps.nexuslauncher) original_label="Pixel Launcher" ;;
    esac
    if [[ -z "$original_holder" || -z "$original_label" || "$original_holder" == dev.atlas.coach ]]; then
        printf 'NOT_RUN reason=original_home_holder_unrecognized_or_already_atlas holder=%s\n' "$original_holder" \
            | tee "$RUN_DIR/a2-03-home-selection.result.txt" "$RUN_DIR/a2-04-home-restore.result.txt"
        return 0
    fi
    if ! home_settings_dump home-before-selection; then
        printf 'BLOCKED reason=home_settings_ui_unavailable\n' | tee "$RUN_DIR/a2-03-home-selection.result.txt"
        printf 'NOT_RUN reason=home_role_not_changed\n' | tee "$RUN_DIR/a2-04-home-restore.result.txt"
        return 0
    fi
    if ! atlas_point="$(home_picker_bounds_center "$RUN_DIR/home-before-selection-ui.xml" ATLAS)" ||
        ! restore_point="$(home_picker_bounds_center "$RUN_DIR/home-before-selection-ui.xml" "$original_label")"; then
        printf 'NOT_RUN reason=picker_did_not_expose_both_ATLAS_and_original_launcher;original_holder=%s\n' "$original_holder" \
            | tee "$RUN_DIR/a2-03-home-selection.result.txt"
        printf 'NOT_RUN reason=home_role_not_changed\n' | tee "$RUN_DIR/a2-04-home-restore.result.txt"
        return 0
    fi
    read -r atlas_x atlas_y <<<"$atlas_point"
    run_step select-atlas-home-via-settings-ui adb_target shell input tap "$atlas_x" "$atlas_y"
    sleep 2
    run_step verify-atlas-home-role adb_target shell cmd role get-role-holders android.app.role.HOME
    current_holder="$(tr -d '\r[:space:]' <"$RUN_DIR/verify-atlas-home-role.log")"
    if [[ "$current_holder" != dev.atlas.coach ]]; then
        printf 'BLOCKED reason=settings_ui_did_not_assign_atlas holder=%s\n' "$current_holder" \
            | tee "$RUN_DIR/a2-03-home-selection.result.txt"
        printf 'NOT_RUN reason=home_role_remained_original\n' | tee "$RUN_DIR/a2-04-home-restore.result.txt"
        return 0
    fi
    run_step press-home-with-atlas-selected adb_target shell input keyevent KEYCODE_HOME
    sleep 2
    run_step verify-atlas-home-after-home-key adb_target shell dumpsys activity activities
    if [[ "$(cat "$RUN_DIR/verify-atlas-home-after-home-key.log")" == *dev.atlas.coach* ]]; then
        printf 'PASS selected_voluntarily_in_android_settings_ui_and_home_key_opened_atlas\n' \
            | tee "$RUN_DIR/a2-03-home-selection.result.txt"
    else
        printf 'FAIL role_selected_but_home_key_did_not_open_atlas\n' | tee "$RUN_DIR/a2-03-home-selection.result.txt"
    fi
    if home_settings_dump home-restore; then
        restore_point="$(home_picker_bounds_center "$RUN_DIR/home-restore-ui.xml" "$original_label" || true)"
        if [[ -n "$restore_point" ]]; then
            read -r restore_x restore_y <<<"$restore_point"
            run_step restore-original-home-via-settings-ui adb_target shell input tap "$restore_x" "$restore_y"
            sleep 2
        fi
    fi
    run_step verify-restored-home-role adb_target shell cmd role get-role-holders android.app.role.HOME
    current_holder="$(tr -d '\r[:space:]' <"$RUN_DIR/verify-restored-home-role.log")"
    if [[ "$current_holder" == "$original_holder" ]]; then
        printf 'PASS restored_original_holder=%s through_android_settings_ui\n' "$original_holder" \
            | tee "$RUN_DIR/a2-04-home-restore.result.txt"
    else
        printf 'FAIL restore_holder=%s expected=%s; retrying bounded Settings UI restoration\n' "$current_holder" "$original_holder" \
            | tee "$RUN_DIR/a2-04-home-restore.result.txt"
        if home_settings_dump home-restore-retry; then
            restore_point="$(home_picker_bounds_center "$RUN_DIR/home-restore-retry-ui.xml" "$original_label" || true)"
            if [[ -n "$restore_point" ]]; then
                read -r restore_x restore_y <<<"$restore_point"
                run_step retry-restore-original-home-via-settings-ui adb_target shell input tap "$restore_x" "$restore_y"
                sleep 2
            fi
        fi
        run_step verify-restored-home-role-retry adb_target shell cmd role get-role-holders android.app.role.HOME
        current_holder="$(tr -d '\r[:space:]' <"$RUN_DIR/verify-restored-home-role-retry.log")"
        if [[ "$current_holder" == "$original_holder" ]]; then
            printf 'PASS restored_original_holder=%s through_android_settings_ui_after_retry\n' "$original_holder" \
                | tee "$RUN_DIR/a2-04-home-restore.result.txt"
        else
            printf 'FAIL unable_to_restore_original_holder=%s current=%s\n' "$original_holder" "$current_holder" \
                | tee "$RUN_DIR/a2-04-home-restore.result.txt"
        fi
    fi
}

self_test_assert_equal() {
    local label="$1"
    local expected="$2"
    local actual="$3"
    if [[ "$actual" == "$expected" ]]; then
        printf 'SELF_TEST PASS %s expected=%s actual=%s\n' "$label" "$expected" "$actual"
        SELF_TEST_PASSES=$((SELF_TEST_PASSES + 1))
    else
        printf 'SELF_TEST FAIL %s expected=%s actual=%s\n' "$label" "$expected" "$actual"
        SELF_TEST_FAILURES=$((SELF_TEST_FAILURES + 1))
    fi
}

run_self_tests() {
    SELF_TEST_PASSES=0
    SELF_TEST_FAILURES=0
    local valid_output zero_tests_output wrong_method_output failed_test_output abort_output
    local fixture_dir fixture_source fixture_snapshot fixture_sha wrong_sha ui_xml
    local timeout_status=0
    local service_output service_result
    local package_found activity_found location_found notification_found window_found

    self_test_assert_equal service_found found "$(service_response_kind package 'Service package: found')"
    self_test_assert_equal service_not_found not_found "$(service_response_kind package 'Service package: not found')"
    self_test_assert_equal service_absent absent "$(service_response_kind package '')"
    self_test_assert_equal service_wrong_name unexpected "$(service_response_kind package 'Service activity: found')"
    self_test_assert_equal service_trailing_text unexpected "$(service_response_kind package 'Service package: found extra')"
    self_test_assert_equal isolated_adb_port_export 5038 "$ANDROID_ADB_SERVER_PORT"
    package_found='Service package: found'
    activity_found='Service activity: found'
    location_found='Service location: found'
    notification_found='Service notification: found'
    window_found='Service window: found'
    if framework_services_all_not_found "$package_found" "$activity_found" "$location_found" "$notification_found" "$window_found"; then
        self_test_assert_equal framework_mixed_positive_not_all_missing REJECT ACCEPT
    else
        self_test_assert_equal framework_mixed_positive_not_all_missing REJECT REJECT
    fi
    package_found='Service package: not found'
    activity_found='Service activity: not found'
    location_found='Service location: not found'
    notification_found='Service notification: not found'
    window_found='Service window: not found'
    if framework_services_all_not_found "$package_found" "$activity_found" "$location_found" "$notification_found" "$window_found"; then
        self_test_assert_equal framework_all_five_missing_detected DETECT DETECT
    else
        self_test_assert_equal framework_all_five_missing_detected DETECT MISSED
    fi
    location_found='Service location: found'
    if framework_services_all_not_found "$package_found" "$activity_found" "$location_found" "$notification_found" "$window_found"; then
        self_test_assert_equal framework_partial_recovery_not_all_missing REJECT ACCEPT
    else
        self_test_assert_equal framework_partial_recovery_not_all_missing REJECT REJECT
    fi

    SELF_TEST_SERVICE_OUTPUT=''
    SELF_TEST_SERVICE_EXIT=0
    adb_target() {
        printf '%s' "$SELF_TEST_SERVICE_OUTPUT"
        return "$SELF_TEST_SERVICE_EXIT"
    }
    : >"$RUN_DIR/framework-service-probes.log"
    SELF_TEST_SERVICE_OUTPUT='Service package: found'
    if check_framework_service package service_output; then service_result=PASS; else service_result=FAIL; fi
    self_test_assert_equal framework_probe_exact_found PASS "$service_result"
    self_test_assert_equal framework_probe_found_output 'Service package: found' "$service_output"
    SELF_TEST_SERVICE_OUTPUT='Service package: not found'
    if check_framework_service package service_output; then service_result=PASS; else service_result=BLOCKED; fi
    self_test_assert_equal framework_probe_not_found BLOCKED "$service_result"
    SELF_TEST_SERVICE_OUTPUT=''
    if check_framework_service package service_output; then service_result=PASS; else service_result=BLOCKED; fi
    self_test_assert_equal framework_probe_absent BLOCKED "$service_result"
    SELF_TEST_SERVICE_OUTPUT='Service package: found'
    SELF_TEST_SERVICE_EXIT=124
    if check_framework_service package service_output; then service_result=PASS; else service_result=BLOCKED; fi
    self_test_assert_equal framework_probe_timeout BLOCKED "$service_result"
    service_result="$(awk 'END { print $1 }' "$RUN_DIR/framework-service-probes.log")"
    self_test_assert_equal framework_probe_results_logged BLOCKED_TIMEOUT "$service_result"
    unset -f adb_target
    self_test_assert_equal step_success_is_not_test_pass COMMAND_OK "$(command_result_kind 0)"
    self_test_assert_equal step_timeout_124 BLOCKED_TIMEOUT "$(command_result_kind 124)"
    self_test_assert_equal step_timeout_137 BLOCKED_TIMEOUT "$(command_result_kind 137)"
    self_test_assert_equal step_failure FAIL_COMMAND "$(command_result_kind 1)"
    if run_limited_command 1 sleep 2; then
        timeout_status=0
    else
        timeout_status=$?
    fi
    self_test_assert_equal timeout_command_nonzero 124 "$timeout_status"
    if grep -q '^ADB_TIMEOUT exit=124 limit_s=1 command=sleep 2' "$RUN_DIR/adb-timeouts.log"; then
        self_test_assert_equal timeout_command_logged BLOCKED BLOCKED
    else
        self_test_assert_equal timeout_command_logged BLOCKED MISSING
    fi

    valid_output=$'INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: test=sampleMethod\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1'
    zero_tests_output=$'INSTRUMENTATION_STATUS: numtests=0\nINSTRUMENTATION_RESULT: stream=OK (0 tests)\nINSTRUMENTATION_CODE: -1'
    wrong_method_output=$'INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: test=otherMethod\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1'
    failed_test_output=$'INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: test=sampleMethod\nINSTRUMENTATION_STATUS_CODE: -2\nINSTRUMENTATION_CODE: 0'
    abort_output=$'INSTRUMENTATION_ABORTED: System has crashed.'
    self_test_assert_equal instrumentation_one_exact_test PASS "$(instrumentation_result_kind sampleMethod 0 "$valid_output")"
    self_test_assert_equal instrumentation_zero_tests FAIL "$(instrumentation_result_kind sampleMethod 0 "$zero_tests_output")"
    self_test_assert_equal instrumentation_wrong_method FAIL "$(instrumentation_result_kind sampleMethod 0 "$wrong_method_output")"
    self_test_assert_equal instrumentation_assertion_failure FAIL "$(instrumentation_result_kind sampleMethod 0 "$failed_test_output")"
    self_test_assert_equal instrumentation_abort BLOCKED "$(instrumentation_result_kind sampleMethod 0 "$abort_output")"
    self_test_assert_equal instrumentation_timeout BLOCKED "$(instrumentation_result_kind sampleMethod 124 '')"
    if source_target_declared 'dev.atlas.coach.app.AtlasLaunchTest#directiveFocusHabitSimulatorAndLibraryJourneysWorkOffline'; then
        self_test_assert_equal atlas_launch_wrong_package_rejected REJECT ACCEPT
    else
        self_test_assert_equal atlas_launch_wrong_package_rejected REJECT REJECT
    fi

    ui_xml="$RUN_DIR/self-test/home-picker.xml"
    mkdir -p "$(dirname "$ui_xml")"
    cat >"$ui_xml" <<'XML'
<hierarchy><node text="ATLAS" bounds="[10,20][110,70]"/><node text="Pixel Launcher" bounds="[10,80][210,140]"/></hierarchy>
XML
    self_test_assert_equal home_picker_atlas_bounds '60 45' "$(home_picker_bounds_center "$ui_xml" ATLAS)"
    self_test_assert_equal home_picker_original_bounds '110 110' "$(home_picker_bounds_center "$ui_xml" 'Pixel Launcher')"
    if home_picker_bounds_center "$ui_xml" 'Missing Launcher' >/dev/null 2>&1; then
        self_test_assert_equal home_picker_missing_label_rejected REJECT ACCEPT
    else
        self_test_assert_equal home_picker_missing_label_rejected REJECT REJECT
    fi
    write_not_run_a2_matrix self_test_framework_gate
    self_test_assert_equal a2_not_run_matrix_rows 17 "$(awk 'END { print NR - 1 }' "$RUN_DIR/a2-matrix-final-candidate.tsv")"
    self_test_assert_equal a2_not_run_matrix_statuses 17 \
        "$(awk -F '\t' 'NR > 1 && $2 == "NOT_RUN" { count++ } END { print count + 0 }' "$RUN_DIR/a2-matrix-final-candidate.tsv")"

    if validate_instrument_source_targets; then
        self_test_assert_equal instrument_targets_declared PASS PASS
    else
        self_test_assert_equal instrument_targets_declared PASS FAIL
    fi

    fixture_dir="$RUN_DIR/self-test"
    fixture_source="$fixture_dir/input-app.apk"
    fixture_snapshot="$fixture_dir/snapshot-app.apk"
    mkdir -p "$fixture_dir"
    printf 'synthetic APK snapshot fixture\n' >"$fixture_source"
    fixture_sha="$(sha256sum "$fixture_source" | awk '{print $1}')"
    if snapshot_apk self-test-copy "$fixture_source" "$fixture_sha" "$fixture_snapshot"; then
        self_test_assert_equal apk_snapshot_hash_match "$fixture_sha" "$(sha256sum "$fixture_snapshot" | awk '{print $1}')"
        self_test_assert_equal apk_snapshot_read_only 444 "$(stat -c '%a' "$fixture_snapshot")"
    else
        self_test_assert_equal apk_snapshot_hash_match PASS FAIL
    fi
    wrong_sha="$(printf '%064d' 0)"
    if snapshot_apk self-test-mismatch "$fixture_source" "$wrong_sha" "$fixture_dir/mismatched-copy.apk"; then
        self_test_assert_equal apk_snapshot_hash_mismatch_rejected FAIL PASS
    else
        self_test_assert_equal apk_snapshot_hash_mismatch_rejected FAIL FAIL
    fi

    printf 'SELF_TEST_SUMMARY pass=%s fail=%s avd_started=no adb_binary_called=no gradle_called=no\n' \
        "$SELF_TEST_PASSES" "$SELF_TEST_FAILURES"
    [[ "$SELF_TEST_FAILURES" == 0 ]]
}

check_screencap() {
    local label="$1"
    local capture="$RUN_DIR/$label.png"
    local magic
    local bytes
    if adb_target exec-out screencap -p >"$capture" 2>"$RUN_DIR/$label.stderr.log"; then
        magic="$(od -An -tx1 -N8 "$capture" | tr -d ' \n')"
        bytes="$(wc -c <"$capture" | tr -d ' ')"
        if [[ "$magic" == 89504e470d0a1a0a ]]; then
            printf '%s=PASS bytes=%s png_signature=%s\n' "$label" "$bytes" "$magic"
            printf 'PASS bytes=%s png_signature=%s\n' "$bytes" "$magic" >"$RUN_DIR/$label.result.txt"
        else
            printf '%s=BLOCKED bytes=%s invalid_signature=%s\n' "$label" "$bytes" "$magic"
            printf 'BLOCKED bytes=%s invalid_signature=%s\n' "$bytes" "$magic" >"$RUN_DIR/$label.result.txt"
            head -c 512 "$capture" | strings >"$RUN_DIR/$label.invalid-output.txt" || true
            cat "$RUN_DIR/$label.invalid-output.txt"
        fi
    else
        if grep -q 'ADB_TIMEOUT' "$RUN_DIR/$label.stderr.log"; then
            printf '%s=BLOCKED timeout\n' "$label"
            printf 'BLOCKED timeout\n' >"$RUN_DIR/$label.result.txt"
        else
            printf '%s=BLOCKED adb_exec_out_failed\n' "$label"
            printf 'BLOCKED adb_exec_out_failed\n' >"$RUN_DIR/$label.result.txt"
        fi
    fi
}

write_not_run_a2_matrix() {
    local reason="$1"
    local matrix="$RUN_DIR/a2-matrix-final-candidate.tsv"
    local entry number scenario evidence
    local entries=(
        '01|clean-install|install-clean-app.result.txt'
        '02|launch-without-home-role|launch-without-home-role.result.txt'
        '03|voluntary-home-selection|a2-03-home-selection.result.txt'
        '04|restore-original-home|a2-04-home-restore.result.txt'
        '05|approximate-location-grant|location-coarse-synchronized.result.txt'
        '06|location-denied|location-denied.result.txt'
        '07|location-revocation|location-revocation-recheck.result.txt'
        '08|system-provider-off|provider-off.result.txt'
        '09|capture-timeout|location-timeout-and-provider-logic.result.txt'
        '10|mobility-start|mobility-start-stop-notification.result.txt'
        '11|mobility-notification-stop|mobility-start-stop-notification-a2-start-stop.result.txt'
        '12|force-stop-no-restart|a2-12-force-stop.result.txt'
        '13|rotation|launcher-250-apps-landscape-font-2x.result.txt'
        '14|font-scale-two|android-home-emergency-pause-2x-instrumented.result.txt'
        '15|offline-focus|focus-pause-resume-recreation-offline.result.txt'
        '16|proactive-revocation|coach-revocation.result.txt'
        '17|home-emergency-pause-offline|android-home-emergency-pause-2x-instrumented.result.txt'
    )
    printf 'scenario\tstatus\tevidence\treason\n' >"$matrix"
    for entry in "${entries[@]}"; do
        IFS='|' read -r number scenario evidence <<<"$entry"
        printf '%s:%s\tNOT_RUN\t%s/%s\t%s\n' "$number" "$scenario" "$RUN_DIR" "$evidence" "$reason" >>"$matrix"
    done
    printf 'NOT_RUN rows=%s reason=%s path=%s\n' "${#entries[@]}" "$reason" "$matrix" \
        | tee "$RUN_DIR/a2-matrix-final-candidate.result.txt"
}

cleanup() {
    [[ "$CLEANUP_DONE" == 0 ]] || return 0
    CLEANUP_DONE=1
    if [[ -n "$INSTRUMENTATION_PID" ]]; then
        kill -TERM "$INSTRUMENTATION_PID" 2>/dev/null || true
        wait "$INSTRUMENTATION_PID" 2>/dev/null || true
        INSTRUMENTATION_PID=""
    fi
    if [[ -n "$LOGCAT_PID" ]]; then
        kill -TERM "$LOGCAT_PID" 2>/dev/null || true
        wait "$LOGCAT_PID" 2>/dev/null || true
        LOGCAT_PID=""
    fi
    if [[ "$STARTED_EMULATOR" == 1 ]]; then
        local running_avd
        if capture_adb cleanup-avd-identity running_avd shell getprop ro.boot.qemu.avd_name; then
            running_avd="${running_avd//$'\r'/}"
        else
            running_avd=""
        fi
        if [[ "$running_avd" == "$AVD_NAME" ]]; then
            if [[ -f "$RUN_DIR/initial-home-role.log" ]]; then
                local current_home cleanup_home_label cleanup_home_point cleanup_home_x cleanup_home_y
                current_home="$(adb_target shell cmd role get-role-holders android.app.role.HOME 2>/dev/null | tr -d '\r[:space:]' || true)"
                if [[ "$current_home" == dev.atlas.coach ]]; then
                    cleanup_home_label=""
                    case "$(tr -d '\r[:space:]' <"$RUN_DIR/initial-home-role.log")" in
                        com.google.android.apps.nexuslauncher) cleanup_home_label="Pixel Launcher" ;;
                    esac
                    if [[ -n "$cleanup_home_label" ]] && home_settings_dump cleanup-home-restore; then
                        cleanup_home_point="$(home_picker_bounds_center "$RUN_DIR/cleanup-home-restore-ui.xml" "$cleanup_home_label" || true)"
                        if [[ -n "$cleanup_home_point" ]]; then
                            read -r cleanup_home_x cleanup_home_y <<<"$cleanup_home_point"
                            run_step cleanup-restore-original-home-via-settings-ui adb_target shell input tap "$cleanup_home_x" "$cleanup_home_y"
                            sleep 2
                            run_step cleanup-verify-original-home adb_target shell cmd role get-role-holders android.app.role.HOME
                        fi
                    fi
                fi
            fi
            run_step emulator-stop-adb adb_target emu kill
        fi
        if [[ -n "$EMULATOR_PID" ]] && kill -0 "$EMULATOR_PID" 2>/dev/null; then
            kill -TERM "$EMULATOR_PID" 2>/dev/null || true
            wait "$EMULATOR_PID" 2>/dev/null || true
        fi
    fi
    if [[ "$STARTED_ADB_SERVER" == 1 ]]; then
        run_step adb-server-stop adb_server kill-server
    fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for timeout_value in \
    "$ADB_COMMAND_TIMEOUT_SECONDS" \
    "$ADB_SERVICE_TIMEOUT_SECONDS" \
    "$ADB_INSTALL_TIMEOUT_SECONDS" \
    "$ADB_INSTRUMENT_TIMEOUT_SECONDS" \
    "$ADB_SCREENCAP_TIMEOUT_SECONDS"; do
    if [[ ! "$timeout_value" =~ ^[1-9][0-9]*$ ]]; then
        printf 'ADB timeout settings must be positive integer seconds.\n' >&2
        exit 2
    fi
done
if ! command -v timeout >/dev/null 2>&1; then
    printf 'GNU timeout is required to bound ADB commands.\n' >&2
    exit 2
fi

if [[ "${1:-}" == --self-test ]]; then
    run_self_tests
    exit $?
fi

printf 'ATLAS Android 17 V6.2.2 direct-ADB recipe\n'
printf 'repo=%s\nrun_dir=%s\navd=%s\nserial=%s\nconsole_port=5582\nadb_port=5583\nadb_server_port=%s\ngpu=%s\n' \
    "$ROOT" "$RUN_DIR" "$AVD_NAME" "$SERIAL" "$ADB_SERVER_PORT" "$GPU"
printf 'No Gradle command is used. All target ADB calls include -s %s and have bounded timeouts.\n' "$SERIAL"
printf 'git_branch=%s\n' "$(git -C "$ROOT" branch --show-current)"
printf 'git_head=%s\n' "$(git -C "$ROOT" rev-parse HEAD)"
printf 'emulator_version=%s\n' "$("$EMULATOR" -version 2>&1 | head -1)"
adb_version="$(run_limited_command "$ADB_COMMAND_TIMEOUT_SECONDS" "$ADB" version | sed -n '2p')"
printf 'adb_version=%s\n' "$adb_version"
printf 'app_apk=%s\ntest_apk=%s\n' "$APP_APK" "$TEST_APK"

if [[ ! -f "$APP_APK" || ! -f "$TEST_APK" ]]; then
    printf 'Missing APK input. Provide existing APK paths; this recipe does not build.\n' >&2
    exit 2
fi
if [[ -z "$EXPECTED_APP_SHA256" || -z "$EXPECTED_TEST_SHA256" ]]; then
    printf 'Set both ATLAS_V622_EXPECTED_APP_SHA256 and ATLAS_V622_EXPECTED_TEST_SHA256 to pin this APK pair.\n' >&2
    exit 2
fi
if ! snapshot_apk app "$APP_APK" "$EXPECTED_APP_SHA256" "$APP_APK_SNAPSHOT"; then
    printf 'App APK snapshot is not safe to use.\n' >&2
    exit 2
fi
if ! snapshot_apk test "$TEST_APK" "$EXPECTED_TEST_SHA256" "$TEST_APK_SNAPSHOT"; then
    printf 'Instrumentation APK snapshot is not safe to use.\n' >&2
    exit 2
fi
APP_SHA256="$(sha256sum "$APP_APK_SNAPSHOT" | awk '{print $1}')"
TEST_SHA256="$(sha256sum "$TEST_APK_SNAPSHOT" | awk '{print $1}')"
printf 'app_snapshot=%s\napp_sha256=%s\ntest_snapshot=%s\ntest_sha256=%s\n' \
    "$APP_APK_SNAPSHOT" "$APP_SHA256" "$TEST_APK_SNAPSHOT" "$TEST_SHA256"
if ! validate_instrument_source_targets; then
    printf 'Instrument target source validation failed; refusing to create or use an AVD.\n' >&2
    exit 2
fi
if [[ ! -d "$SDK/system-images/android-37.2/google_apis_ps16k/x86_64" ]]; then
    printf 'Required installed API 37 image is absent: %s\n' "$IMAGE" >&2
    exit 2
fi

if [[ ! -f "$AVD_HOME/$AVD_NAME.ini" ]]; then
    if [[ -e "$AVD_DIR" ]]; then
        printf 'AVD directory exists without its registry file; refusing to overwrite it.\n' >&2
        exit 2
    fi
    run_step avd-create "$AVDMANAGER" create avd --name "$AVD_NAME" --package "$IMAGE" --device pixel_5 --path "$AVD_DIR"
    if [[ ! -f "$AVD_HOME/$AVD_NAME.ini" ]]; then
        printf 'AVD creation did not produce the expected AVD registry file.\n' >&2
        exit 2
    fi
fi
if ! grep -Eq '^image\.sysdir\.1[[:space:]]*=[[:space:]]*system-images/android-37\.2/google_apis_ps16k/x86_64/' "$AVD_DIR/config.ini"; then
    printf 'Existing task AVD does not reference the expected API 37 image; refusing to use it.\n' >&2
    exit 2
fi

if ss -ltn 2>/dev/null | grep -Eq '[:.]5038[[:space:]]'; then
    printf 'ADB server port %s is already occupied; refusing to share or stop another server.\n' "$ADB_SERVER_PORT" >&2
    exit 2
fi
run_step adb-server-start adb_server start-server
if [[ "$(cat "$RUN_DIR/adb-server-start.exit")" != 0 ]]; then
    printf 'Could not start the isolated ADB server on port %s.\n' "$ADB_SERVER_PORT" >&2
    exit 1
fi
STARTED_ADB_SERVER=1
if capture_adb initial-adb-device-state state get-state; then
    state="${state//$'\r'/}"
else
    state=""
fi
if [[ "$state" != device ]]; then
    if ss -ltn 2>/dev/null | grep -Eq '127\.0\.0\.1:5582[[:space:]]'; then
        printf 'Port 5582 is occupied while the explicit target is not online; refusing a second emulator.\n' >&2
        exit 2
    fi
    "$EMULATOR" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim -no-snapshot-load -no-snapshot-save \
        -port 5582 -gpu "$GPU" -cores 2 -memory 2048 -partition-size 4096 -wipe-data -netdelay none -netspeed full \
        >"$RUN_DIR/emulator.log" 2>&1 &
    EMULATOR_PID=$!
    STARTED_EMULATOR=1
    printf 'emulator_pid=%s\n' "$EMULATOR_PID"
fi

boot_deadline=$((SECONDS + 240))
booted=""
while (( SECONDS < boot_deadline )); do
    if capture_adb boot-completed-probe booted shell getprop sys.boot_completed; then
        booted="${booted//$'\r'/}"
    else
        booted=""
    fi
    if [[ "$booted" == 1 ]]; then
        break
    fi
    sleep 2
done
if [[ "$booted" != 1 ]]; then
    if grep -q 'ADB_TIMEOUT' "$RUN_DIR/adb-timeouts.log" 2>/dev/null; then
        printf 'BLOCKED: Android boot probes timed out before boot completion; inspect adb-timeouts.log and emulator.log.\n' >&2
    else
        printf 'FAIL: Android boot did not complete within 240 seconds; inspect emulator.log.\n' >&2
    fi
    exit 1
fi

deadline=$((SECONDS + 240))
state=""
while (( SECONDS < deadline )); do
    if capture_adb adb-device-state-probe state get-state; then
        state="${state//$'\r'/}"
    else
        state=""
    fi
    if [[ "$state" == device ]]; then
        break
    fi
    sleep 2
done
if [[ "$state" != device ]]; then
    if grep -q 'ADB_TIMEOUT' "$RUN_DIR/adb-timeouts.log" 2>/dev/null; then
        printf 'BLOCKED: ADB state probes timed out before device state; inspect adb-timeouts.log and emulator.log.\n' >&2
    else
        printf 'FAIL: AVD did not reach ADB device state within 240 seconds; inspect emulator.log.\n' >&2
    fi
    exit 1
fi

capture_adb verify-avd-name avd_name shell getprop ro.boot.qemu.avd_name || exit 1
capture_adb verify-api-level api shell getprop ro.build.version.sdk || exit 1
capture_adb verify-api-full api_full shell getprop ro.build.version.sdk_full || exit 1
capture_adb verify-boot-complete booted shell getprop sys.boot_completed || exit 1
avd_name="${avd_name//$'\r'/}"
api="${api//$'\r'/}"
api_full="${api_full//$'\r'/}"
booted="${booted//$'\r'/}"
printf 'device_state=%s\nro.boot.qemu.avd_name=%s\nro.build.version.sdk=%s\nro.build.version.sdk_full=%s\nsys.boot_completed=%s\n' \
    "$state" "$avd_name" "$api" "$api_full" "$booted"
if [[ "$avd_name" != "$AVD_NAME" || "$api" != 37 || "$booted" != 1 ]]; then
    printf 'Target identity or boot check failed; no APK or test command will run.\n' >&2
    exit 2
fi
run_step api37-build-fingerprint adb_target shell getprop ro.build.fingerprint
run_step api37-data-partition adb_target shell df -h /data

framework_deadline=$((SECONDS + 240))
framework_ready=0
framework_services_ever_available=0
framework_stable_since=""
: >"$RUN_DIR/framework-service-probes.log"
while (( SECONDS < framework_deadline )); do
    package_ready=0
    activity_ready=0
    location_ready=0
    notification_ready=0
    window_ready=0
    check_framework_service package package_service && package_ready=1 || true
    check_framework_service activity activity_service && activity_ready=1 || true
    check_framework_service location location_service && location_ready=1 || true
    check_framework_service notification notification_service && notification_ready=1 || true
    check_framework_service window window_service && window_ready=1 || true
    if (( package_ready && activity_ready && location_ready && notification_ready && window_ready )); then
        framework_services_ever_available=1
        if [[ -z "$framework_stable_since" ]]; then
            framework_stable_since="$SECONDS"
        fi
        if (( SECONDS - framework_stable_since >= 15 )); then
            framework_ready=1
            break
        fi
    else
        framework_stable_since=""
        if (( framework_services_ever_available )) &&
            framework_services_all_not_found "$package_service" "$activity_service" "$location_service" "$notification_service" "$window_service"; then
            printf 'BLOCKED framework_services_disappeared_after_positive_probes; collecting diagnostics and stopping this AVD run.\n' \
                | tee "$RUN_DIR/framework-runtime-gate.result.txt"
            capture_framework_diagnostics framework-services-disappeared
            write_not_run_a2_matrix framework_services_disappeared_before_install
            exit 1
        fi
    fi
    sleep 2
done
printf 'framework_services_ready=%s\npackage=%s\nactivity=%s\nlocation=%s\nnotification=%s\nwindow=%s\n' \
    "$framework_ready" "$package_service" "$activity_service" "$location_service" "$notification_service" "$window_service"
if [[ "$framework_ready" != 1 ]]; then
    printf 'Android framework services did not become available within 240 seconds; no install or instrumentation command will run.\n' >&2
    write_not_run_a2_matrix framework_services_not_stable_before_install
    exit 1
fi

for package in dev.atlas.coach.test dev.atlas.coach; do
    if capture_adb "existing-package-list-${package//./-}" existing_package_list shell pm list packages "$package"; then
        if grep -Fxq "package:$package" "$RUN_DIR/existing-package-list-${package//./-}.stdout.log"; then
            run_step "remove-existing-$package" adb_target uninstall "$package"
            if [[ "$(cat "$RUN_DIR/remove-existing-$package.exit")" != 0 ]]; then
                printf 'FAIL existing package removal failed for %s; refusing an ambiguous install.\n' "$package" >&2
                exit 1
            fi
        else
            printf 'PASS package_absent_before_install package=%s\n' "$package" \
                | tee "$RUN_DIR/package-absent-before-install-${package//./-}.result.txt"
        fi
    else
        printf 'BLOCKED/FAIL: could not establish installed-package inventory for %s; refusing an ambiguous install.\n' "$package" >&2
        exit 1
    fi
done
run_step preinstall-atlas-package-state adb_target shell pm path dev.atlas.coach
run_step install-clean-app adb_target install "$APP_APK_SNAPSHOT"
if [[ "$(cat "$RUN_DIR/install-clean-app.exit")" == 0 && "$(cat "$RUN_DIR/install-clean-app.log")" == *Success* ]]; then
    printf 'PASS fresh_app_install\n' | tee "$RUN_DIR/a2-01-clean-install.result.txt"
else
    printf 'FAIL fresh_app_install\n' | tee "$RUN_DIR/a2-01-clean-install.result.txt"
    INSTRUMENTATION_BLOCKED=1
fi
run_step installed-app-path adb_target shell pm path dev.atlas.coach
run_step install-instrumentation-apk adb_target install -t "$TEST_APK_SNAPSHOT"
if [[ "$(cat "$RUN_DIR/install-instrumentation-apk.exit")" == 0 && "$(cat "$RUN_DIR/install-instrumentation-apk.log")" == *Success* ]]; then
    printf 'PASS instrumentation_apk_install\n' | tee "$RUN_DIR/install-instrumentation-apk.verified.txt"
else
    printf 'FAIL instrumentation_apk_install\n' | tee "$RUN_DIR/install-instrumentation-apk.verified.txt"
    INSTRUMENTATION_BLOCKED=1
fi
run_step installed-test-path adb_target shell pm path dev.atlas.coach.test
run_step installed-instrumentation adb_target shell pm list instrumentation
run_step resolve-main-activity adb_target shell cmd package resolve-activity --brief -n dev.atlas.coach/.app.MainActivity
run_step initial-home-role adb_target shell cmd role get-role-holders android.app.role.HOME
run_step launch-without-home-role adb_target shell am start -W -n dev.atlas.coach/dev.atlas.coach.app.MainActivity
run_step launch-resumed-activity adb_target shell dumpsys activity activities
if [[ "$(cat "$RUN_DIR/launch-without-home-role.exit")" == 0 &&
    "$(cat "$RUN_DIR/launch-without-home-role.log")" == *'Status: ok'* &&
    "$(cat "$RUN_DIR/launch-resumed-activity.log")" == *'dev.atlas.coach/.app.MainActivity'* ]]; then
    printf 'PASS launch_without_home_role\n' | tee "$RUN_DIR/a2-02-launch-without-home-role.result.txt"
else
    printf 'FAIL launch_without_home_role\n' | tee "$RUN_DIR/a2-02-launch-without-home-role.result.txt"
fi

run_step initial-runtime-permissions adb_target shell dumpsys package dev.atlas.coach
run_step revoke-coarse-permission adb_target shell pm revoke dev.atlas.coach android.permission.ACCESS_COARSE_LOCATION
run_instrumented location-denied \
    dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#deniedPermissionStateReturnsFunctionalErrorWithoutCrashing
run_instrumented location-timeout-and-provider-logic \
    dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#api26Through29UseListenerAndReturnTimeoutOrUnavailableCodes
run_instrumented location-revocation-recheck \
    dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#permissionRevocationDuringAcquisitionIsRecheckedBeforeReturningFix
run_step grant-coarse-permission adb_target shell pm grant dev.atlas.coach android.permission.ACCESS_COARSE_LOCATION
run_step grant-notification-permission adb_target shell pm grant dev.atlas.coach android.permission.POST_NOTIFICATIONS
run_step foreground-coarse-app-op adb_target shell cmd appops get dev.atlas.coach COARSE_LOCATION
run_step permissions-after-grant adb_target shell dumpsys package dev.atlas.coach
run_step ensure-location-provider-enabled adb_target shell cmd location set-location-enabled true
run_step verify-location-provider-enabled adb_target shell cmd location is-location-enabled
run_instrumented location-sharing-consent-setup \
    dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#prepareSharingConsentBeforeHostInjectsFreshEmulatorFix \
    -e location_fix_injection host
run_synchronized_instrumented location-coarse-synchronized \
    dev.atlas.coach.location.V62LocationCaptureInstrumentedTest#injectedCoarseFixIsMinimizedAndStoredOnlyAsAZoneSummary \
    COARSE_CAPTURE_MANAGER_CALL -e location_fix_injection host
run_step revoke-coarse-after-grant adb_target shell pm revoke dev.atlas.coach android.permission.ACCESS_COARSE_LOCATION
run_step permissions-after-os-revocation adb_target shell dumpsys package dev.atlas.coach
run_step restore-coarse-for-mobility adb_target shell pm grant dev.atlas.coach android.permission.ACCESS_COARSE_LOCATION
run_instrumented mobility-sharing-consent-setup \
    dev.atlas.coach.location.V62MobilityServiceInstrumentedTest#prepareSharingConsentBeforeHostInjectsFreshMobilityFix \
    -e location_fix_injection host
run_synchronized_instrumented mobility-start-stop-notification \
    dev.atlas.coach.location.V62MobilityServiceInstrumentedTest#visibleStartShowsStopNotificationAndServiceStopsWithoutRawTrail \
    MOBILITY_SERVICE_START_REQUEST -e location_fix_injection host

# Provider-off is observed on this AVD, but no Android-backed product assertion currently targets that system state.
run_step disable-system-location-provider adb_target shell cmd location set-location-enabled false
run_step verify-system-location-provider-disabled adb_target shell cmd location is-location-enabled
provider_state="$(tr -d '\r[:space:]' <"$RUN_DIR/verify-system-location-provider-disabled.log")"
if [[ "$(cat "$RUN_DIR/disable-system-location-provider.exit")" == 0 && "$provider_state" == false ]]; then
    printf 'BLOCKED provider_state=confirmed_false reason=no_system_backed_product_assertion;fake_API26_29_test_is_not_a_system_provider_test\n' \
        | tee "$RUN_DIR/provider-off.result.txt"
else
    printf 'BLOCKED provider_state=unconfirmed reason=provider_off_precondition_not_verified\n' \
        | tee "$RUN_DIR/provider-off.result.txt"
fi
run_step restore-system-location-provider adb_target shell cmd location set-location-enabled true
run_step verify-system-location-provider-restored adb_target shell cmd location is-location-enabled

run_step set-airplane-mode adb_target shell settings put global airplane_mode_on 1
run_step broadcast-airplane-mode adb_target shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true
run_step disable-wifi adb_target shell svc wifi disable
run_step disable-mobile-data adb_target shell svc data disable
run_step offline-connectivity-state adb_target shell dumpsys connectivity
run_instrumented focus-pause-resume-recreation-offline \
    dev.atlas.coach.AtlasLaunchTest#directiveFocusHabitSimulatorAndLibraryJourneysWorkOffline
run_instrumented coach-revocation \
    dev.atlas.coach.app.ProactiveCoachNotificationInstrumentationTest#tappingAfterMandateRevocationShowsNeutralMessageAndDoesNotNavigateOrDispatch

run_instrumented goal-focus-01 \
    dev.atlas.coach.app.GoalFocusStartInstrumentationTest#focusGoalSelectorOffersNoneOneAndManyAndCreditsOnlyFinishedLinkedWork
run_instrumented goal-focus-02 \
    dev.atlas.coach.app.GoalFocusStartInstrumentationTest#singleGoalCanChooseNoGoalAndChoiceStaysFreeWhenEligibilityChanges
run_instrumented goal-focus-03 \
    dev.atlas.coach.app.GoalFocusStartInstrumentationTest#localGoalLinkSurvivesPauseResumeFinishUnrelatedWriteAndRealControllerRecreation
run_instrumented goal-focus-04 \
    dev.atlas.coach.app.GoalFocusStartInstrumentationTest#agentStartCommitsTheLocalGoalChoiceWithItsIdempotencyKeyOnce
run_instrumented goal-focus-05 \
    dev.atlas.coach.app.GoalFocusStartInstrumentationTest#failedInitialCommitHasNoSessionGoalAlarmStartedEventOrRecreatedState
run_instrumented goal-focus-06 \
    dev.atlas.coach.app.GoalFocusStartInstrumentationTest#controllerRecreationAfterElapsedFocusPersistsOneCompletedCycleAndProgress
run_instrumented goal-focus-07 \
    dev.atlas.coach.app.GoalFocusStartInstrumentationTest#legacyV621HistoryIsPreservedWithoutInventedGoalLinks
run_instrumented proactive-controller-revocation-01 \
    dev.atlas.coach.app.ProactiveControllerPublicationAuthorizationInstrumentationTest#latePlanResponseAfterCategoryRevocationAndSameClockReoptInHasNoLocalEffects
run_instrumented proactive-controller-revocation-02 \
    dev.atlas.coach.app.ProactiveControllerPublicationAuthorizationInstrumentationTest#revocationAfterTransportReturnButBeforeResponseMergeHasNoLocalEffects
run_instrumented proactive-publisher-revocation-01 \
    dev.atlas.coach.app.ProactiveCoachNotificationPublisherInstrumentationTest#revocationRemovesOnlyProactiveChannelIncludingLegacyUntaggedNotice
run_instrumented proactive-publisher-revocation-02 \
    dev.atlas.coach.app.ProactiveCoachNotificationPublisherInstrumentationTest#finalAuthorizationGateRejectsAndConcurrentRevocationSerializesTargetedRemoval

run_step set-font-scale-2x adb_target shell settings put system font_scale 2.0
run_step lock-landscape adb_target shell settings put system accelerometer_rotation 0
run_step rotate-landscape adb_target shell settings put system user_rotation 1
run_step landscape-display-state adb_target shell dumpsys window displays
run_instrumented android-home-emergency-pause-2x-instrumented \
    dev.atlas.coach.app.LauncherPresentationTest#emergencyAndLauncherExitActionsStayReachableAtFontScaleTwo
run_instrumented launcher-250-apps-landscape-font-2x \
    dev.atlas.coach.app.LauncherDrawerLazyInstrumentationTest#drawerScrollsToLastSyntheticAppAndKeepsFooterClickableInLandscapeAtFontScaleTwo
run_step restore-font-scale adb_target shell settings put system font_scale 1.0
run_step restore-auto-rotation adb_target shell settings put system accelerometer_rotation 1
run_step restore-portrait-rotation adb_target shell settings put system user_rotation 0

run_home_role_roundtrip

run_step force-stop-app adb_target shell am force-stop dev.atlas.coach
sleep 10
if adb_target shell pidof dev.atlas.coach >"$RUN_DIR/force-stop-package-process.log" 2>&1; then
    process_state="$(cat "$RUN_DIR/force-stop-package-process.log")"
    printf 'FAIL process_still_present pid=%s\n' "$process_state" | tee "$RUN_DIR/a2-12-force-stop.result.txt"
else
    process_status=$?
    printf '%s\n' "$process_status" >"$RUN_DIR/force-stop-package-process.exit"
    process_state="$(cat "$RUN_DIR/force-stop-package-process.log")"
    run_step force-stop-mobility-service adb_target shell dumpsys activity services dev.atlas.coach.location.MobilitySessionService
    if [[ "$process_status" == 1 && -z "$process_state" &&
        "$(cat "$RUN_DIR/force-stop-mobility-service.exit")" == 0 &&
        "$(cat "$RUN_DIR/force-stop-mobility-service.log")" != *dev.atlas.coach.location.MobilitySessionService* ]]; then
        printf 'PASS no_app_pid_or_mobility_service_after_force_stop\n' | tee "$RUN_DIR/a2-12-force-stop.result.txt"
    else
        printf 'BLOCKED process_query_exit=%s service_state_unconfirmed\n' "$process_status" | tee "$RUN_DIR/a2-12-force-stop.result.txt"
    fi
fi

check_screencap screencap-after-tests
run_step screenshot-capability-getprop adb_target shell getprop ro.build.version.sdk

printf '\nHOME role interaction is confined to this disposable AVD and must use Android Settings UI.\n'
printf 'Previous holder is recorded in initial-home-role.log. If selecting ATLAS, restore that exact holder through the same UI picker.\n'
printf 'Full command logs: %s\n' "$RUN_DIR"
printf 'The installed APKs are read-only snapshots identified by the requested S5 hashes.\n'
printf 'Runtime results apply only to those candidate artifacts on this API 37 AVD.\n'
