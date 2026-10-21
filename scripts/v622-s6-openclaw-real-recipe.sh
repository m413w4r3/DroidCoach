#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
ADB="$SDK_ROOT/platform-tools/adb"
SERIAL="${ANDROID_SERIAL:-emulator-5580}"
export ADB_SERVER_PORT=5037
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
OUTPUT_DIR="${ATLAS_V622_S6_E2E_OUTPUT_DIR:-/tmp/atlas-v622-s6/openclaw-real-$RUN_ID}"
TOKEN_FILE="${OPENCLAW_GATEWAY_TOKEN_FILE:-$HOME/.config/atlas/openclaw-gateway-token}"
GATEWAY_URL="${OPENCLAW_BASE_URL:-http://127.0.0.1:18789}"
CANDIDATE_DIR="${ATLAS_V622_S6_CANDIDATE_DIR:-/tmp/atlas-v622-s6-s5-s7-p1-01-corrected-test-candidate}"
CANDIDATE_APP_APK="$CANDIDATE_DIR/app-debug.apk"
CANDIDATE_TEST_APK="$CANDIDATE_DIR/app-debug-androidTest.apk"
EXPECTED_APP_SHA256="${ATLAS_V622_CANDIDATE_APP_SHA256:-ce10c968988fb3383e06ee94b3c1eb3723f37efcf6035597601643916a9f298a}"
EXPECTED_TEST_SHA256="${ATLAS_V622_CANDIDATE_TEST_SHA256:-8edb71a818ac01a4c3195cab48def493be117587159f939962f4f15ed053014d}"
WORK_DIR="$(mktemp -d /tmp/atlas-v622-s6/openclaw-work.XXXXXX)"
BACKEND_PID=""
ATLAS_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
ATLAS_PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
BACKEND_LOG="$WORK_DIR/backend.raw.log"
RUNNER="dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner"

umask 077
mkdir -p "$OUTPUT_DIR"
chmod 700 "$OUTPUT_DIR" "$WORK_DIR"
printf '%s\n' "$ATLAS_TOKEN" > "$WORK_DIR/atlas-token.txt"
chmod 600 "$WORK_DIR/atlas-token.txt"
export ADB_SERVER_PORT="${ADB_SERVER_PORT:-5037}"

stop_backend() {
  if [[ -n "$BACKEND_PID" ]] && kill -0 "$BACKEND_PID" 2>/dev/null; then
    kill "$BACKEND_PID" 2>/dev/null || true
    wait "$BACKEND_PID" 2>/dev/null || true
  fi
  BACKEND_PID=""
}

cleanup() {
  stop_backend
  python3 - "$WORK_DIR" <<'PY'
import pathlib
import shutil
import sys

path = pathlib.Path(sys.argv[1])
if path.exists() and path.parent == pathlib.Path("/tmp/atlas-v622-s6"):
    shutil.rmtree(path)
PY
}
trap cleanup EXIT

if [[ ! -x "$ADB" || ! -f "$TOKEN_FILE" ]] || ! command -v timeout >/dev/null 2>&1; then
  printf 'BLOCKED: adb or configured OpenClaw token file is unavailable.\n' >&2
  exit 77
fi
TOKEN_FILE_RESOLVED="$(realpath -m -- "$TOKEN_FILE")"
if [[ "$TOKEN_FILE_RESOLVED" == "$ROOT/"* || "$(stat -c '%a' "$TOKEN_FILE")" != 600 ]]; then
  printf 'BLOCKED: configured OpenClaw token file must have mode 600.\n' >&2
  exit 77
fi
if ! python3 - "$ADB" "$SERIAL" <<'PY'
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
if result.returncode != 0 or len(assigned) != 1 or len(rows) != 1:
    print(f"BLOCKED: ADB inventory targets={len(rows)} assigned_api34={len(assigned)}; identifiers suppressed.")
    raise SystemExit(1)
PY
then
  exit 77
fi
if [[ "$SERIAL" != emulator-5580 ]] || ! timeout 20s "$ADB" -s "$SERIAL" get-state 2>/dev/null | rg -q '^device$'; then
  printf 'BLOCKED: the assigned API 34 emulator is unavailable.\n' >&2
  exit 77
fi
AVD_NAME="$(timeout 20s "$ADB" -s "$SERIAL" shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r')"
API_LEVEL="$(timeout 20s "$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
if [[ "$AVD_NAME" != atlas_v622_s1_api34 || "$API_LEVEL" != 34 || "$SERIAL" != emulator-5580 ]]; then
  printf 'BLOCKED: expected the assigned atlas_v622_s1_api34 API 34 emulator.\n' >&2
  exit 77
fi
if ! python3 - "$GATEWAY_URL" <<'PY'
import sys
from urllib.parse import urlparse

parsed = urlparse(sys.argv[1])
if parsed.scheme not in {"http", "https"} or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
    raise SystemExit(1)
PY
then
  printf 'BLOCKED: OpenClaw Gateway must be the configured local loopback endpoint.\n' >&2
  exit 77
fi
if ! curl --silent --fail --max-time 2 "$GATEWAY_URL/health" >/dev/null 2>&1; then
  printf 'BLOCKED: the configured local OpenClaw Gateway is not reachable.\n' >&2
  exit 77
fi
if [[ ! -s "$CANDIDATE_APP_APK" || ! -s "$CANDIDATE_TEST_APK" ]]; then
  printf 'BLOCKED: frozen candidate APK pair is unavailable.\n' >&2
  exit 77
fi
CANDIDATE_APP_ACTUAL="$(sha256sum "$CANDIDATE_APP_APK" | awk '{print $1}')"
CANDIDATE_TEST_ACTUAL="$(sha256sum "$CANDIDATE_TEST_APK" | awk '{print $1}')"
if [[ "$CANDIDATE_APP_ACTUAL" != "$EXPECTED_APP_SHA256" || "$CANDIDATE_TEST_ACTUAL" != "$EXPECTED_TEST_SHA256" ]]; then
  printf 'BLOCKED: frozen candidate APK hashes differ from the declared pair.\n' >&2
  exit 77
fi
{
  printf 'candidate_app_sha256=%s\ncandidate_test_sha256=%s\n' "$CANDIDATE_APP_ACTUAL" "$CANDIDATE_TEST_ACTUAL"
} > "$OUTPUT_DIR/candidate-identity.txt"

timeout 120s "$ADB" -s "$SERIAL" install -r "$CANDIDATE_APP_APK" > "$WORK_DIR/install-app.log"
timeout 120s "$ADB" -s "$SERIAL" install -r -t "$CANDIDATE_TEST_APK" > "$WORK_DIR/install-test.log"

verify_installed_apk() {
  local package="$1" expected="$2" label="$3" paths base_path actual
  paths="$(timeout 20s "$ADB" -s "$SERIAL" shell pm path "$package" | tr -d '\r')"
  base_path="$(printf '%s\n' "$paths" | sed -n 's/^package:\(.*\/base.apk\)$/\1/p')"
  if [[ -z "$base_path" || "$(printf '%s\n' "$base_path" | wc -l | tr -d ' ')" != 1 ]]; then
    printf 'BLOCKED: installed %s base APK could not be identified.\n' "$label" >&2
    return 77
  fi
  if ! timeout 45s "$ADB" -s "$SERIAL" pull "$base_path" "$WORK_DIR/$label-installed.apk" \
      > "$WORK_DIR/$label-pull.log" 2>&1; then
    printf 'BLOCKED: installed %s APK could not be read back.\n' "$label" >&2
    return 77
  fi
  actual="$(sha256sum "$WORK_DIR/$label-installed.apk" | awk '{print $1}')"
  if [[ "$actual" != "$expected" ]]; then
    printf 'FAIL: installed %s APK hash differs from frozen candidate.\n' "$label" >&2
    return 1
  fi
  printf '%s_installed_sha256=%s\n' "$label" "$actual" >> "$OUTPUT_DIR/candidate-identity.txt"
}
verify_installed_apk dev.atlas.coach "$EXPECTED_APP_SHA256" app
verify_installed_apk dev.atlas.coach.test "$EXPECTED_TEST_SHA256" test

ATLAS_AGENT_ADAPTER=openclaw \
ATLAS_TOKEN="$ATLAS_TOKEN" \
ATLAS_DB_PATH="$WORK_DIR/atlas.sqlite3" \
OPENCLAW_BASE_URL="$GATEWAY_URL" \
OPENCLAW_AGENT_ID="${OPENCLAW_AGENT_ID:-atlas}" \
OPENCLAW_TIMEOUT_S="${OPENCLAW_TIMEOUT_S:-60}" \
OPENCLAW_GATEWAY_TOKEN_FILE="$TOKEN_FILE" \
ATLAS_AGENT_RPM=30 \
PYTHONPATH="$ROOT/backend${PYTHONPATH:+:$PYTHONPATH}" \
  python3 -m uvicorn atlas_backend.main:app --host 127.0.0.1 --port "$ATLAS_PORT" \
  --no-access-log --log-level warning > "$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!
export ATLAS_TOKEN ATLAS_PORT

for _ in $(seq 1 80); do
  if curl --silent --fail --max-time 1 "http://127.0.0.1:$ATLAS_PORT/health" >/dev/null 2>&1; then
    break
  fi
  sleep 0.25
done
if ! kill -0 "$BACKEND_PID" 2>/dev/null || ! curl --silent --fail --max-time 1 "http://127.0.0.1:$ATLAS_PORT/health" >/dev/null 2>&1; then
  printf 'FAIL: local backend did not become healthy.\n' >&2
  exit 1
fi
ATLAS_TOKEN="$ATLAS_TOKEN" ATLAS_PORT="$ATLAS_PORT" python3 - <<'PY' > "$OUTPUT_DIR/backend-adapter-status.txt"
import json
import os
import urllib.request

request = urllib.request.Request(
    f"http://127.0.0.1:{os.environ['ATLAS_PORT']}/v2/agent/status",
    headers={"Authorization": f"Bearer {os.environ['ATLAS_TOKEN']}"},
)
with urllib.request.urlopen(request, timeout=3) as response:
    status = json.load(response)
assert status.get("adapter") == "openclaw"
print("adapter=openclaw")
print(f"connected_before_first_request={str(bool(status.get('connected'))).lower()}")
PY

parse_runner_result() {
  local method="$1"
  local raw="$2"
  local result_file="$OUTPUT_DIR/$method-result.txt"
  if ! python3 - "$raw" "$result_file" <<'PY'
import re
import sys
from pathlib import Path

raw = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
counts = re.search(
    r"Tests run:\s*(\d+),\s*Failures:\s*(\d+)(?:,\s*Errors:\s*(\d+))?(?:,\s*Ignored:\s*(\d+))?",
    raw,
)
if counts:
    tests, failures, errors, ignored = (int(value or 0) for value in counts.groups())
else:
    ok = re.search(r"OK \((\d+) tests?\)", raw)
    if not ok:
        raise SystemExit("runner_summary=missing")
    tests, failures, errors, ignored = int(ok.group(1)), 0, 0, 0
if tests != 1 or failures or errors or ignored:
    raise SystemExit(
        f"runner_summary=FAIL tests={tests} failures={failures} errors={errors} ignored={ignored}"
    )
Path(sys.argv[2]).write_text(
    f"tests={tests}\nfailures={failures}\nerrors={errors}\nignored={ignored}\n",
    encoding="utf-8",
)
PY
  then
    return 1
  fi
  printf 'tests=1 failures=0 errors=0 ignored=0\n' >> "$OUTPUT_DIR/$method-result.txt"
}

run_android_test() {
  local class_method="$1"
  shift
  local method="${class_method##*#}"
  local run_label="${E2E_PASS_LABEL:-single}"
  local result_key="$run_label-$method"
  local raw="$WORK_DIR/$result_key.raw.log"
  local result=0
  if timeout --signal=TERM --kill-after=5s 240s \
    "$ADB" -s "$SERIAL" shell am instrument -w -r "$@" \
    -e class "$class_method" "$RUNNER" > "$raw" 2>&1; then
    result=0
  else
    result=$?
  fi
  if [[ "$result" -ne 0 ]]; then
    printf 'FAIL method=%s runner_exit=%s\n' "$method" "$result" >&2
    return 1
  fi
  if ! parse_runner_result "$result_key" "$raw"; then
    python3 - "$method" "$raw" "$OUTPUT_DIR/$result_key-failure.txt" <<'PY'
import sys
from pathlib import Path

method, raw_path, destination = sys.argv[1:]
raw = Path(raw_path).read_text(encoding="utf-8", errors="replace")
classifiers = {
    "The executed proposal ACK was not accepted by the backend": "executed_ack_pending",
    "The real Gateway reply did not use the completed Focus minutes": "reply_missing_completed_focus_minutes",
    "Android must report the real OpenClaw adapter as connected": "real_adapter_not_connected",
    "OpenClaw returned no actionable home.set_directive proposal": "real_directive_missing",
    "Revoked goal data remained in the next Android payload": "revoked_goal_present_in_fresh_payload",
}
failure = next((name for marker, name in classifiers.items() if marker in raw), "unclassified_runner_failure")
exception = next((line.strip().split(":", 1)[0] for line in raw.splitlines() if line.strip().startswith(("java.", "kotlin."))), "unknown")
line_match = __import__("re").search(r"V62RealGatewayContextBridgeTest\.kt:(\d+)", raw)
source_line = line_match.group(1) if line_match else "unknown"
Path(destination).write_text(
    f"method={method}\nfailure={failure}\nexception={exception}\nsource_line={source_line}\n",
    encoding="utf-8",
)
print(f"method={method} failure={failure} exception={exception} source_line={source_line}")
PY
    if [[ -f "$WORK_DIR/atlas.sqlite3" ]]; then
      python3 - "$WORK_DIR/atlas.sqlite3" <<'PY'
import sqlite3
import sys

try:
    connection = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)
    try:
        row = connection.execute(
            "SELECT COUNT(*) FROM agent_proposal_acks WHERE state='executed' AND effect IS NOT NULL AND length(effect)>0"
        ).fetchone()
        print(f"backend_executed_ack_with_effect_count={int(row[0])}")
    finally:
        connection.close()
except sqlite3.Error:
    print("backend_executed_ack_with_effect_count=unavailable")
PY
    fi
    printf 'FAIL method=%s runner_summary=not_passing\n' "$method" >&2
    return 1
  fi
  printf 'PASS e2e_pass=%s method=%s tests=1 failures=0 errors=0 ignored=0\n' "$run_label" "$method" | tee -a "$OUTPUT_DIR/e2e-results.txt"
}

force_stop_and_verify_process() {
  local stage="$1"
  "$ADB" -s "$SERIAL" shell am start -W -n dev.atlas.coach/.app.LauncherActivity \
    > "$WORK_DIR/$stage-launch.log" 2>&1
  local before_pid
  before_pid="$("$ADB" -s "$SERIAL" shell pidof dev.atlas.coach 2>/dev/null | tr -d '\r' || true)"
  if [[ -z "$before_pid" ]]; then
    printf 'FAIL process_stage=%s running_pid_not_observed\n' "$stage" >&2
    return 1
  fi
  "$ADB" -s "$SERIAL" shell am force-stop dev.atlas.coach > "$WORK_DIR/$stage-force-stop.log" 2>&1
  local after_pid
  after_pid="$("$ADB" -s "$SERIAL" shell pidof dev.atlas.coach 2>/dev/null | tr -d '\r' || true)"
  if [[ -n "$after_pid" ]]; then
    printf 'FAIL process_stage=%s pid_still_running\n' "$stage" >&2
    return 1
  fi
  printf 'process_stage=%s running_before=1 stopped_after=1\n' "$stage" | tee -a "$OUTPUT_DIR/e2e-process-results.txt"
}

if [[ "${ATLAS_V622_S6_E2E_CONTEXT_ONLY:-0}" != 1 ]]; then
  run_android_test \
    dev.atlas.coach.app.V6RealAgentE2eInstrumentationTest#realCoachResponseCreatesDirectiveAndStartsFocus \
    -e backend_url "http://10.0.2.2:$ATLAS_PORT" -e backend_token "$ATLAS_TOKEN"
  force_stop_and_verify_process "real-agent-restart"
  run_android_test \
    dev.atlas.coach.app.V6RealAgentE2eInstrumentationTest#restoredFocusCanPauseResumeAndCancel \
    -e backend_url "http://10.0.2.2:$ATLAS_PORT" -e backend_token "$ATLAS_TOKEN"
  run_android_test \
    dev.atlas.coach.app.V6RealAgentE2eInstrumentationTest#limitedMandatePreauthorizesFocusPlanOnAndroid
fi
for E2E_PASS_LABEL in 1 2; do
  run_android_test \
    dev.atlas.coach.app.V62RealGatewayContextBridgeTest#realGatewayUsesAccelerated25MinuteFocusContextAndAcceptedProposal \
    -e backend_url "http://10.0.2.2:$ATLAS_PORT" -e backend_token "$ATLAS_TOKEN"
  if [[ "$E2E_PASS_LABEL" == 1 ]]; then
    force_stop_and_verify_process "between-complete-e2e-passes"
  fi
done
unset E2E_PASS_LABEL

ATLAS_TOKEN="$ATLAS_TOKEN" ATLAS_PORT="$ATLAS_PORT" python3 - <<'PY' > "$OUTPUT_DIR/backend-adapter-after-call.txt"
import json
import os
import urllib.request

request = urllib.request.Request(
    f"http://127.0.0.1:{os.environ['ATLAS_PORT']}/v2/agent/status",
    headers={"Authorization": f"Bearer {os.environ['ATLAS_TOKEN']}"},
)
with urllib.request.urlopen(request, timeout=3) as response:
    status = json.load(response)
assert status.get("adapter") == "openclaw"
assert status.get("connected") is True
print("adapter=openclaw")
print("connected_after_real_request=true")
PY

python3 - "$WORK_DIR/atlas.sqlite3" "$OUTPUT_DIR/backend-ack-summary.txt" <<'PY'
import sqlite3
import sys
from pathlib import Path

connection = sqlite3.connect(sys.argv[1])
try:
    rows = connection.execute(
        "SELECT COUNT(*) FROM agent_proposal_acks WHERE state='executed' AND effect IS NOT NULL AND length(effect)>0"
    ).fetchone()
finally:
    connection.close()
count = int(rows[0])
if count < 2:
    raise SystemExit(f"executed_ack_count_below_two:{count}")
Path(sys.argv[2]).write_text(f"executed_ack_with_effect_count={count}\n", encoding="utf-8")
PY

stop_backend
if curl --silent --fail --max-time 1 "http://127.0.0.1:$ATLAS_PORT/health" >/dev/null 2>&1; then
  printf 'FAIL offline_backend=still_reachable\n' >&2
  exit 1
fi
printf 'offline_backend=stopped\n' | tee -a "$OUTPUT_DIR/e2e-results.txt"
force_stop_and_verify_process "offline-restart"
run_android_test \
  dev.atlas.coach.app.V62RealGatewayContextBridgeTest#localGoalFocusFavoritesAndEmergencyPauseRemainAvailableAfterBackendStop \
  -e backend_stopped true
timeout 20s "$ADB" -s "$SERIAL" shell am force-stop dev.atlas.coach > "$WORK_DIR/offline-post-test-force-stop.log" 2>&1
if [[ -n "$("$ADB" -s "$SERIAL" shell pidof dev.atlas.coach 2>/dev/null | tr -d '\r' || true)" ]]; then
  printf 'FAIL process_stage=offline-relaunch app_process_survived_force_stop\n' >&2
  exit 1
fi
timeout 30s "$ADB" -s "$SERIAL" shell am start -W -n dev.atlas.coach/.app.LauncherActivity \
  > "$WORK_DIR/offline-relaunch.log" 2>&1
OFFLINE_PID="$("$ADB" -s "$SERIAL" shell pidof dev.atlas.coach 2>/dev/null | tr -d '\r' || true)"
if [[ -z "$OFFLINE_PID" ]]; then
  printf 'FAIL process_stage=offline-relaunch app_process_not_created_by_activity_manager\n' >&2
  exit 1
fi
printf 'process_stage=offline-relaunch backend_stopped=1 force_stopped=1 app_process_created_by_activity_manager=1\n' \
  | tee -a "$OUTPUT_DIR/e2e-process-results.txt"

printf 'PASS e2e real OpenClaw recipe; sanitized proof directory=%s\n' "$OUTPUT_DIR"
