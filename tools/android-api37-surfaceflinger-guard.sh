#!/usr/bin/env bash
set -euo pipefail

if (( $# != 1 )); then
  echo "usage: tools/android-api37-surfaceflinger-guard.sh <emulator-serial>" >&2
  exit 2
fi

emulator_serial="$1"
adb_binary="${ADB:-adb}"
guard_timeout_seconds="${PARTICEPS_SURFACEFLINGER_GUARD_TIMEOUT_SECONDS:-180}"

if [[ ! "$guard_timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "PARTICEPS_SURFACEFLINGER_GUARD_TIMEOUT_SECONDS must be a positive integer" >&2
  exit 2
fi

guard_deadline_epoch=$(( $(date +%s) + guard_timeout_seconds ))
while (( $(date +%s) < guard_deadline_epoch )); do
  if "$adb_binary" -s "$emulator_serial" shell \
      setprop debug.sf.luma_sampling 0 >/dev/null 2>&1; then
    sampling_state="$(
      "$adb_binary" -s "$emulator_serial" shell \
        getprop debug.sf.luma_sampling 2>/dev/null | tr -d '\r'
    )"
    if [[ "$sampling_state" == "0" ]]; then
      echo "API 37 SurfaceFlinger region sampling disabled on $emulator_serial"
      exit 0
    fi
  fi
  sleep 1
done

echo "Timed out disabling API 37 SurfaceFlinger region sampling on $emulator_serial" >&2
exit 1
