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

adb_binary="${ADB:-adb}"
report_directory="build/reports/android-host-harness"
mkdir -p "$report_directory"

run_api37_quarantined_host_harness() {
  local quarantine_directory="$report_directory/api37-full-host-harness-quarantine"
  local harness_output="$quarantine_directory/host-harness-output.txt"
  local crash_log="$quarantine_directory/crash-buffer.txt"
  local classification="$quarantine_directory/classification.txt"
  local crash_log_pid
  local harness_status

  mkdir -p "$quarantine_directory"
  "$adb_binary" logcat -b crash -c >/dev/null 2>&1 || true
  "$adb_binary" logcat -b crash -v threadtime >"$crash_log" 2>&1 &
  crash_log_pid=$!

  set +e
  PARTICEPS_HOST_REPORT_DIR="$quarantine_directory/harness" \
    tools/android-host-harness.sh --skip-build >"$harness_output" 2>&1
  harness_status=$?
  set -e

  kill "$crash_log_pid" >/dev/null 2>&1 || true
  wait "$crash_log_pid" >/dev/null 2>&1 || true
  "$adb_binary" logcat -b crash -d -v threadtime >>"$crash_log" 2>&1 || true

  if (( harness_status == 0 )); then
    printf '%s\n' "API 37 full host harness passed; quarantine was not used." | tee "$classification"
    return 0
  fi

  if python3 tools/classify_api37_emulator_failure.py \
      "$harness_output" \
      "$crash_log" \
      "$quarantine_directory/harness/android-host-harness.xml" | tee "$classification"; then
    echo "::warning::API 37 full host harness quarantined for the known revision 5 SurfaceFlinger defect."
    return 0
  fi

  echo "API 37 full host harness failed without the exact quarantined platform signature" >&2
  sed -n '1,240p' "$harness_output" >&2
  sed -n '1,240p' "$crash_log" >&2
  return "$harness_status"
}

if [[ "$require_16k" == true ]]; then
  page_size="$($adb_binary shell getconf PAGE_SIZE | tr -d '\r')"
  if [[ "$page_size" != "16384" ]]; then
    echo "API 37 ps16k emulator page size must be 16384, got: $page_size" >&2
    exit 1
  fi
  python3 -c 'import os, pathlib, xml.etree.ElementTree as ET; p=pathlib.Path(os.environ["ANDROID_SDK_ROOT"]) / "system-images/android-37.0/google_apis_ps16k/x86_64/package.xml"; r=ET.parse(p).getroot().find(".//revision/major"); assert r is not None and int(r.text) >= 5, "API 37 ps16k image revision must be at least 5"'

  # This blocking suite never launches participant UI or asks the system to capture task snapshots.
  # It verifies installability, manifest contracts, native loading, and non-snapshot instrumentation on
  # an unmodified API 37 16 KiB image.
  tools/android-instrumentation-ci.sh --suite=api37-compatibility
  printf '%s\n' \
    "PASS: API 37 installation, 16 KiB page size, manifest contracts, and native loading checks passed." \
    > "$report_directory/api37-blocking-compatibility.txt"
  run_api37_quarantined_host_harness
else
  # API 34 remains the complete blocking product-behaviour lane.
  ./gradlew --no-daemon --max-workers=1 \
    -PinstrumentedTestAbi=x86_64 \
    connectedDebugAndroidTest
  tools/android-host-harness.sh --skip-build
fi
