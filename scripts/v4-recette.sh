#!/usr/bin/env bash
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/android17-env.sh" || exit 1

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
LOG_ROOT="$REPO_ROOT/docs/v4/logs"
RUN_DIR="$LOG_ROOT/recette-$RUN_ID"
mkdir -p "$RUN_DIR"
LOG_FILE="$RUN_DIR/run.log"
exec > >(tee -a "$LOG_FILE") 2>&1

FAILURES=0

run_step() {
  local label="$1"
  shift
  printf '\n===== %s =====\n' "$label"
  if "$@"; then
    printf 'PASS: %s\n' "$label"
  else
    local status=$?
    printf 'FAIL (exit %s): %s\n' "$status" "$label"
    FAILURES=$((FAILURES + 1))
  fi
}

report_versions() {
  printf 'Horodatage UTC : %s\n' "$RUN_ID"
  printf 'Commit : %s\n' "$(git -C "$REPO_ROOT" rev-parse HEAD)"
  printf 'ANDROID_HOME=%s\nANDROID_SERIAL=%s\n' "$ANDROID_HOME" "$ANDROID_SERIAL"
  printf 'SDK platform : %s\n' "$(sed -n 's/^AndroidVersion.ApiLevel=//p' "$ANDROID_HOME/platforms/android-37.2/source.properties")"
  printf 'Build tools installés :\n'
  ls -1 "$ANDROID_HOME/build-tools"
  printf 'Plateformes Android installées :\n'
  ls -1 "$ANDROID_HOME/platforms"
  printf 'Émulateur release/API/sdk_full/ABI/AVD : %s / %s / %s / %s / %s\n' \
    "$(adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.release | tr -d '\r')" \
    "$(adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')" \
    "$(adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk_full | tr -d '\r')" \
    "$(adb -s "$ANDROID_SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')" \
    "$(adb -s "$ANDROID_SERIAL" shell getprop ro.boot.qemu.avd_name | tr -d '\r')"
  java -version 2>&1
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --version
  (cd "$REPO_ROOT/android" && ./gradlew --version)
  printf 'AGP/Kotlin :\n'
  sed -n '1,8p' "$REPO_ROOT/android/build.gradle.kts"
  printf 'compileSdk/targetSdk :\n'
  rg -n -A5 'compileSdk|targetSdk' "$REPO_ROOT/android/app/build.gradle.kts"
}

gradle() {
  (cd "$REPO_ROOT/android" && ./gradlew "$@")
}

run_pytest() {
  local python_bin="${PYTHON_BIN:-/tmp/v4/venv/bin/python}"
  if [[ ! -x "$python_bin" ]]; then
    echo "Python du venv introuvable : $python_bin" >&2
    return 1
  fi
  (cd "$REPO_ROOT/backend" && "$python_bin" -m pytest -q --junitxml="$RUN_DIR/pytest.xml")
}

hash_apk() {
  local apk="$REPO_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
  if [[ ! -f "$apk" ]]; then
    echo "APK debug absente : $apk" >&2
    return 1
  fi
  sha256sum "$apk" | tee "$RUN_DIR/apk-sha256.txt"
}

collect_xml() {
  python - "$REPO_ROOT" "$RUN_DIR" <<'PY'
from pathlib import Path
import shutil
import sys

repo = Path(sys.argv[1])
run_dir = Path(sys.argv[2])
build_dir = repo / "android" / "app" / "build"
destination = run_dir / "xml" / "android"
copied = 0
for report in build_dir.rglob("*.xml"):
    relative = report.relative_to(build_dir)
    if "test-results" not in relative.parts and "androidTest-results" not in relative.parts:
        continue
    target = destination / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(report, target)
    copied += 1
print(f"Rapports XML Android copiés : {copied}")
if copied == 0:
    raise SystemExit("Aucun rapport XML Gradle trouvé")

pytest_report = run_dir / "pytest.xml"
print(f"Rapport XML pytest : {pytest_report if pytest_report.exists() else 'absent'}")
if not pytest_report.exists():
    raise SystemExit("Rapport XML pytest absent")
PY
}

run_step "adb devices" adb devices -l
run_step "versions Android/Gradle/Kotlin" report_versions
run_step "tests JVM + assembleDebug + lintDebug" gradle :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
run_step "connectedDebugAndroidTest (AVD existant)" gradle :app:connectedDebugAndroidTest
run_step "pytest backend (venv)" run_pytest
run_step "SHA-256 APK debug" hash_apk
run_step "collecte des rapports XML" collect_xml

printf '\nRapport de recette : %s\n' "$RUN_DIR"
if [[ "$FAILURES" -eq 0 ]]; then
  printf 'Résultat global : PASS\n'
  exit 0
fi
printf 'Résultat global : FAIL (%s étape(s))\n' "$FAILURES"
exit 1
