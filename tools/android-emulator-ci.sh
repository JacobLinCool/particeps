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

await_api37_services() {
  local timeout_seconds="$1"
  local deadline state boot_completed package_service activity_service input_service
  deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS <= deadline )); do
    state="$($adb_binary get-state 2>/dev/null || true)"
    boot_completed="$($adb_binary shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [[ "$state" == device && "$boot_completed" == 1 ]]; then
      package_service="$($adb_binary shell service check package 2>/dev/null | tr -d '\r' || true)"
      activity_service="$($adb_binary shell service check activity 2>/dev/null | tr -d '\r' || true)"
      input_service="$($adb_binary shell service check input 2>/dev/null | tr -d '\r' || true)"
      if [[ "$package_service" == *found* && "$activity_service" == *found* && "$input_service" == *found* ]]; then
        return 0
      fi
    fi
    sleep 2
  done
  echo "API 37 emulator services did not recover within $timeout_seconds seconds" >&2
  return 1
}

run_api37_blocking_compatibility() {
  local maximum_attempts=3
  local attempt attempt_output attempt_report_directory crash_log classification instrumentation_file compatibility_status
  local -a evidence_files

  for attempt in $(seq 1 "$maximum_attempts"); do
    await_api37_services 180
    attempt_output="$report_directory/api37-compatibility-attempt-$attempt.txt"
    attempt_report_directory="$report_directory/instrumentation/api37-attempt-$attempt"
    crash_log="$report_directory/api37-compatibility-attempt-$attempt-crash.txt"
    classification="$report_directory/api37-compatibility-attempt-$attempt-classification.txt"
    "$adb_binary" logcat -b crash -c >/dev/null 2>&1 || true

    set +e
    PARTICEPS_INSTRUMENTATION_REPORT_DIR="$attempt_report_directory" \
      tools/android-instrumentation-ci.sh --suite=api37-compatibility \
      >"$attempt_output" 2>&1
    compatibility_status=$?
    set -e
    if (( compatibility_status == 0 )); then
      cat "$attempt_output"
      return 0
    fi

    "$adb_binary" logcat -b crash -d -v threadtime >"$crash_log" 2>&1 || true
    evidence_files=("$attempt_output" "$crash_log")
    while IFS= read -r instrumentation_file; do
      evidence_files+=("$instrumentation_file")
    done < <(find "$attempt_report_directory" -maxdepth 1 -type f -print 2>/dev/null | sort)

    if ! python3 tools/classify_api37_emulator_failure.py \
        --result-label RETRYABLE \
        "${evidence_files[@]}" | tee "$classification"; then
      cat "$attempt_output" >&2
      return "$compatibility_status"
    fi
    if (( attempt == maximum_attempts )); then
      echo "API 37 blocking compatibility did not pass after $maximum_attempts exact-platform-failure attempts" >&2
      return "$compatibility_status"
    fi
    echo "::warning::Retrying the blocking API 37 compatibility gate after the exact revision 5 SurfaceFlinger service restart."
  done
}

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
  run_api37_blocking_compatibility
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
