#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_ROOT="/tmp/v623-qa"
RUN_DIR="$LOG_ROOT/android17-differentials-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$RUN_DIR"
RESULTS="$RUN_DIR/experiment-results.tsv"
printf 'variant\tvariable\tapi\timage\tgpu\tram_mb\tstatus\tcause\tmemavailable_kib\tpositive_service_probes\tsystem_server_start_count\trun_dir\n' >"$RESULTS"
exec > >(tee -a "$RUN_DIR/experiments.console.log") 2>&1

variants=(
    api37-baseline
    api37-gpu-swiftshader
    api37-memory-3072
    api37-memory-4096
    api37-image-37-1-playstore
    api37-image-37-2-playstore
    api35-control
)

printf 'V6.2.3 Android 17 differential experiments\nroot=%s\nrun_dir=%s\nlock=/tmp/v623-emulator.lock\n' \
    "$ROOT" "$RUN_DIR"
for variant in "${variants[@]}"; do
    case "$variant" in
        api37-baseline)
            variable=baseline; api=37; image=android-37.2/google_apis_ps16k; gpu=auto; ram=2048 ;;
        api37-gpu-swiftshader)
            variable=gpu; api=37; image=android-37.2/google_apis_ps16k; gpu=swiftshader_indirect; ram=2048 ;;
        api37-memory-3072)
            variable=memory; api=37; image=android-37.2/google_apis_ps16k; gpu=auto; ram=3072 ;;
        api37-memory-4096)
            variable=memory; api=37; image=android-37.2/google_apis_ps16k; gpu=auto; ram=4096 ;;
        api37-image-37-1-playstore)
            variable=image_version; api=37; image=android-37.1/google_apis_playstore_ps16k; gpu=auto; ram=2048 ;;
        api37-image-37-2-playstore)
            variable=image_version; api=37; image=android-37.2/google_apis_playstore_ps16k; gpu=auto; ram=2048 ;;
        api35-control)
            variable=api_control; api=35; image=android-35/google_apis_ps16k; gpu=auto; ram=2048 ;;
    esac
    console="$RUN_DIR/$variant.console.log"
    printf '\n[experiment] variant=%s variable=%s api=%s image=%s gpu=%s ram_mb=%s\n' \
        "$variant" "$variable" "$api" "$image" "$gpu" "$ram"
    set +e
    "$ROOT/scripts/v623-android-qa.sh" "$variant" >"$console" 2>&1
    exit_code=$?
    set -e
    cat "$console"
    variant_run_dir="$(sed -n 's/^run_dir=//p' "$console" | head -n 1)"
    if [[ -z "$variant_run_dir" ]]; then
        status=NOT_RUN
        classification=UNKNOWN
        available=0
        positives=0
        starts=UNKNOWN
    else
        result_file="$variant_run_dir/recipe.result.txt"
        result_line="$(head -n 1 "$result_file" 2>/dev/null || true)"
        if [[ "$result_line" == suite_status=* ]]; then
            status="${result_line#suite_status=}"
            classification="$(sed -n 's/^suite_cause=//p' "$result_file" | head -n 1)"
        else
            status="${result_line%% *}"
            classification=UNKNOWN
        fi
        [[ "$status" =~ ^(PASS|FAIL|BLOCKED|NOT_RUN)$ ]] || status=UNKNOWN
        available="$(sed -n 's/.*memavailable_kib=\([0-9][0-9]*\).*/\1/p' "$variant_run_dir/host-memory-gate.txt" 2>/dev/null | head -n 1)"
        available="${available:-0}"
        positives="$(sed -n 's/^positive_service_responses=//p' "$variant_run_dir/framework-gate-counts.txt" 2>/dev/null | head -n 1 || true)"
        positives="${positives:-0}"
        baseline="$(sed -n 's/^system_server_start_baseline=//p' "$variant_run_dir/framework-gate-counts.txt" 2>/dev/null | head -n 1 || true)"
        final="$(sed -n 's/^system_server_start_final=//p' "$variant_run_dir/framework-gate-counts.txt" 2>/dev/null | head -n 1 || true)"
        starts="${final:-${baseline:-UNKNOWN}}"
        if [[ "$result_line" == *host_memavailable* ]]; then classification=HOST_ENVIRONMENT
        elif [[ "$classification" == NONE && "$status" == PASS ]]; then classification=NONE
        elif [[ "$result_line" == *framework* || "$result_line" == *system_server* ]]; then classification=AVD_ENVIRONMENT_BLOCKED
        elif [[ "$status" == FAIL && "$classification" == UNKNOWN ]]; then classification=UNKNOWN
        elif [[ "$status" == NOT_RUN && "$classification" == UNKNOWN ]]; then classification=UNKNOWN
        fi
    fi
    if (( exit_code != 0 )) && [[ "$status" == PASS ]]; then
        status=FAIL
        classification=TEST_HARNESS_BUG
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$variant" "$variable" "$api" "$image" "$gpu" "$ram" "$status" "$classification" \
        "$available" "$positives" "$starts" "${variant_run_dir:-UNKNOWN}" \
        | tee -a "$RESULTS"
done

printf '\nEXPERIMENT_RESULTS=%s\n' "$RESULTS"
