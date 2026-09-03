#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

if (( $# != 1 )); then
  echo "usage: tools/android-emulator-prebuild.sh --require-16k=true|false" >&2
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
    echo "usage: tools/android-emulator-prebuild.sh --require-16k=true|false" >&2
    exit 2
    ;;
esac

# Build every APK used by connected tests and the host harness before starting the emulator.
# This keeps the preview x86_64 image out of the host's build pressure and prevents a build from
# replacing the harness report directory after the emulator is live.
./gradlew --no-daemon --max-workers=1 \
  -PinstrumentedTestAbi=x86_64 \
  :app:assembleDebug \
  :app:assembleDebugAndroidTest \
  :core:storage:assembleDebug \
  :core:storage:assembleDebugAndroidTest \
  :test-fixtures:competing-vpn:assembleDebug \
  :test-fixtures:shared-uid-peer:assembleDebug \
  :test-fixtures:shared-uid-target:assembleDebug \
  :test-fixtures:traffic-control:assembleDebug \
  :test-fixtures:traffic-target-a:assembleBaseDebug \
  :test-fixtures:traffic-target-a:assembleReplacementDebug \
  :test-fixtures:traffic-target-b:assembleDebug

if [[ "$require_16k" == true ]]; then
  tools/build-api37-snapshot-overlay.sh
  # API 37 ps16k revision 5 advertises a host readback path that its ranchu mapper
  # rejects when emulator UI services lock a buffer. The background guard removes
  # those two UI-only callers and publishes a marker that the test launcher awaits.
  guard_report_directory="build/reports/android-host-harness"
  mkdir -p "$guard_report_directory"
  guard_ready_file="$repository_root/$guard_report_directory/api37-surfaceflinger-ready.txt"
  guard_failure_file="${guard_ready_file}.failed"
  [[ ! -e "$guard_ready_file" ]] || unlink "$guard_ready_file"
  [[ ! -e "$guard_failure_file" ]] || unlink "$guard_failure_file"
  nohup tools/android-api37-surfaceflinger-guard.sh \
    "${PARTICEPS_EMULATOR_SERIAL:-emulator-5554}" \
    "$guard_ready_file" \
    >"$guard_report_directory/api37-surfaceflinger-guard.txt" 2>&1 </dev/null &
fi
