#!/usr/bin/env bash

android17_env_init() {
  local sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  local adb_bin="$sdk_root/platform-tools/adb"
  local connected_state
  local api_level
  local sdk_full
  local avd_name
  local expected_avd="${ANDROID17_EXPECTED_AVD:-Pixel_9_Pro_XL_2}"

  export ANDROID_HOME="$sdk_root"
  export ANDROID_SDK_ROOT="$sdk_root"
  export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5556}"

  if [[ ! -x "$adb_bin" ]]; then
    adb_bin="$(command -v adb || true)"
  fi
  if [[ -z "$adb_bin" || ! -x "$adb_bin" ]]; then
    echo "adb introuvable (ANDROID_HOME=$ANDROID_HOME)." >&2
    return 1
  fi
  export PATH="$(dirname "$adb_bin"):$PATH"

  if [[ ! -f "$ANDROID_HOME/platforms/android-37.2/android.jar" ]]; then
    echo "La plateforme SDK $ANDROID_HOME/platforms/android-37.2 est absente." >&2
    return 1
  fi

  if ! connected_state="$(adb -s "$ANDROID_SERIAL" get-state 2>/dev/null)" || [[ "$connected_state" != "device" ]]; then
    echo "L'émulateur demandé n'est pas prêt : $ANDROID_SERIAL (état: ${connected_state:-indisponible})." >&2
    return 1
  fi

  api_level="$(adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
  sdk_full="$(adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk_full | tr -d '\r')"
  avd_name="$(adb -s "$ANDROID_SERIAL" shell getprop ro.boot.qemu.avd_name | tr -d '\r')"

  if [[ "$api_level" != "37" || "$sdk_full" != "37.2" ]]; then
    echo "Émulateur attendu API 37 / sdk_full 37.2, obtenu API ${api_level:-?} / sdk_full ${sdk_full:-?}." >&2
    return 1
  fi
  if [[ -n "$expected_avd" && "$avd_name" != "$expected_avd" ]]; then
    echo "AVD attendu '$expected_avd', obtenu '${avd_name:-inconnu}'. Définir ANDROID17_EXPECTED_AVD pour autoriser un autre nom." >&2
    return 1
  fi

  printf 'SDK Android : %s\n' "$ANDROID_HOME"
  printf 'ADB : %s\n' "$adb_bin"
  printf 'Émulateur : %s (%s), API %s / sdk_full %s\n' "$ANDROID_SERIAL" "$avd_name" "$api_level" "$sdk_full"
}

if ! android17_env_init; then
  if [[ "${BASH_SOURCE[0]}" != "$0" ]]; then
    return 1
  fi
  exit 1
fi
