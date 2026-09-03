#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

if (( $# != 1 )); then
  echo "usage: tools/android-emulator-ci.sh --require-16k=true|false" >&2
  exit 2
fi

case "$1" in
  --require-16k=true)
    require_16k=true
    ;;
  --require-16k=false)
    require_16k=false
    ;;
  *)
    echo "usage: tools/android-emulator-ci.sh --require-16k=true|false" >&2
    exit 2
    ;;
esac

if [[ "$require_16k" == true ]]; then
  adb_binary="${ADB:-adb}"
  guard_ready_file="build/reports/android-host-harness/api37-surfaceflinger-ready.txt"
  guard_failure_file="${guard_ready_file}.failed"
  guard_wait_deadline=$(( SECONDS + 480 ))
  while [[ ! -s "$guard_ready_file" ]] && [[ ! -s "$guard_failure_file" ]] &&
      (( SECONDS < guard_wait_deadline )); do
    sleep 1
  done
  if [[ ! -s "$guard_ready_file" ]]; then
    echo "API 37 graphics stabilization guard did not complete" >&2
    sed -n '1,240p' build/reports/android-host-harness/api37-surfaceflinger-guard.txt >&2 || true
    exit 1
  fi

  page_size="$($adb_binary shell getconf PAGE_SIZE | tr -d '\r')"
  if [[ "$page_size" != "16384" ]]; then
    echo "API 37 ps16k emulator page size must be 16384, got: $page_size" >&2
    exit 1
  fi
  python3 -c 'import os, pathlib, xml.etree.ElementTree as ET; p=pathlib.Path(os.environ["ANDROID_SDK_ROOT"]) / "system-images/android-37.0/google_apis_ps16k/x86_64/package.xml"; r=ET.parse(p).getroot().find(".//revision/major"); assert r is not None and int(r.text) >= 5, "API 37 ps16k image revision must be at least 5"'

  sampling_state="$($adb_binary shell getprop debug.sf.luma_sampling | tr -d '\r')"
  if [[ "$sampling_state" != "0" ]]; then
    echo "API 37 SurfaceFlinger region sampling guard was not applied" >&2
    exit 1
  fi
  overlay_value="$(
    $adb_binary shell cmd overlay lookup android \
      android:bool/config_disableTaskSnapshots | tr -d '\r'
  )"
  if [[ "$overlay_value" != "true" ]]; then
    echo "API 37 task snapshots are not disabled" >&2
    exit 1
  fi
  if ! $adb_binary shell pm list packages -d --user 0 | tr -d '\r' |
      grep -qx 'package:com.android.systemui'; then
    echo "API 37 SystemUI region-sampling owner is not disabled" >&2
    exit 1
  fi
  snapshot_disabled_count="$(
    $adb_binary shell dumpsys window | grep -c 'mSnapshotEnabled=false' || true
  )"
  if (( snapshot_disabled_count < 2 )); then
    echo "API 37 snapshot controllers are still enabled" >&2
    exit 1
  fi
  surfaceflinger_pid="$($adb_binary shell pidof surfaceflinger | tr -d '\r')"
  system_server_pid="$($adb_binary shell pidof system_server | tr -d '\r')"
  if [[ -z "$surfaceflinger_pid" ]]; then
    echo "API 37 SurfaceFlinger is not running" >&2
    exit 1
  fi
  sleep 10
  stable_surfaceflinger_pid="$($adb_binary shell pidof surfaceflinger | tr -d '\r')"
  stable_system_server_pid="$($adb_binary shell pidof system_server | tr -d '\r')"
  if [[ "$stable_surfaceflinger_pid" != "$surfaceflinger_pid" ]] ||
      [[ "$stable_system_server_pid" != "$system_server_pid" ]]; then
    echo "API 37 graphics or framework process did not remain stable after the guard" >&2
    exit 1
  fi
  $adb_binary shell dumpsys window displays >/dev/null
fi

# One emulator is shared by every Android module in this job. Serial workers avoid overlapping
# package-installer sessions and make teardown between test APKs deterministic.
if [[ "$require_16k" == true ]]; then
  tools/android-instrumentation-ci.sh
else
  ./gradlew --no-daemon --max-workers=1 \
    -PinstrumentedTestAbi=x86_64 \
    connectedDebugAndroidTest
fi
tools/android-host-harness.sh --skip-build
