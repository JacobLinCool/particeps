#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

suite="full"
if (( $# == 1 )) && [[ "$1" == --suite=* ]]; then
  suite="${1#--suite=}"
elif (( $# != 0 )); then
  echo "usage: tools/android-instrumentation-ci.sh [--suite=full|api37-compatibility]" >&2
  exit 2
fi
if [[ "$suite" != "full" && "$suite" != "api37-compatibility" ]]; then
  echo "unknown instrumentation suite: $suite" >&2
  exit 2
fi

adb_binary="${ADB:-adb}"
temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/particeps-instrumentation.XXXXXX")"
report_directory="build/reports/android-host-harness/instrumentation"
mkdir -p "$report_directory"
cleanup() {
  find "$temporary_directory" -type f -delete >/dev/null 2>&1 || true
  rmdir "$temporary_directory" >/dev/null 2>&1 || true
}
trap cleanup EXIT

install_apk() {
  local apk="$1"
  local test_only="$2"
  local output
  local -a options=(--no-streaming)
  if [[ "$test_only" == true ]]; then
    options+=(-t)
  fi
  if output="$("$adb_binary" install "${options[@]}" "$apk" 2>&1 | tr -d '\r')"; then
    if grep -qx 'Success' <<< "$output"; then
      return 0
    fi
  fi
  printf 'Failed to install %s:\n%s\n' "$apk" "$output" >&2
  local report_name
  report_name="$(basename "$apk" .apk)"
  printf '%s\n' "$output" > "$report_directory/$report_name-install.txt"
  "$adb_binary" logcat -b crash -d -v brief \
    > "$report_directory/$report_name-install-crash-log.txt" 2>&1 || true
  cat "$report_directory/$report_name-install-crash-log.txt" >&2
  "$adb_binary" shell df -h /data /data/local/tmp >&2 || true
  return 1
}

run_instrumentation() {
  local name="$1"
  local runner="$2"
  local success_pattern='^OK \([1-9][0-9]* tests?\)$'
  if (( $# >= 3 )); then
    success_pattern="$3"
    shift 3
  else
    shift 2
  fi
  local output="$temporary_directory/$name.txt"
  if ! "$adb_binary" shell am instrument -w -r "$@" "$runner" \
    | tr -d '\r' \
    | tee "$output"; then
    cp "$output" "$report_directory/$name.txt"
    "$adb_binary" logcat -b crash -d -v brief > "$report_directory/$name-crash-log.txt" 2>&1 || true
    "$adb_binary" shell dumpsys activity processes > "$report_directory/$name-processes.txt" 2>&1 || true
    return 1
  fi
  cp "$output" "$report_directory/$name.txt"
  if grep -Eq "$success_pattern" "$output" \
    && ! grep -Eq 'FAILURES!!!|INSTRUMENTATION_(ABORTED|FAILED)|shortMsg=' "$output"; then
    return 0
  fi
  "$adb_binary" logcat -b crash -d -v brief > "$report_directory/$name-crash-log.txt" 2>&1 || true
  "$adb_binary" shell dumpsys activity processes > "$report_directory/$name-processes.txt" 2>&1 || true
  return 1
}

"$adb_binary" get-state | grep -qx device

"$adb_binary" uninstall cool.jacoblin.particeps.test >/dev/null 2>&1 || true
"$adb_binary" uninstall cool.jacoblin.particeps >/dev/null 2>&1 || true
install_apk app/build/outputs/apk/debug/app-debug.apk false
if [[ "$suite" == "api37-compatibility" ]]; then
  run_instrumentation \
    api37-compatibility \
    cool.jacoblin.particeps/cool.jacoblin.particeps.Api37CompatibilityInstrumentation \
    '^INSTRUMENTATION_CODE: -1$'
  exit 0
fi

install_apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk true
run_instrumentation \
  app \
  cool.jacoblin.particeps.test/androidx.test.runner.AndroidJUnitRunner

"$adb_binary" uninstall cool.jacoblin.particeps.core.storage.test >/dev/null 2>&1 || true
install_apk core/storage/build/outputs/apk/androidTest/debug/storage-debug-androidTest.apk true
run_instrumentation \
  storage \
  cool.jacoblin.particeps.core.storage.test/androidx.test.runner.AndroidJUnitRunner
