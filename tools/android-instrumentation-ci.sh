#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

adb_binary="${ADB:-adb}"
temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/particeps-instrumentation.XXXXXX")"
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
  "$adb_binary" logcat -b crash -d -v brief >&2 || true
  "$adb_binary" shell df -h /data /data/local/tmp >&2 || true
  return 1
}

run_instrumentation() {
  local name="$1"
  local runner="$2"
  local output="$temporary_directory/$name.txt"
  "$adb_binary" shell am instrument -w -r "$runner" | tr -d '\r' | tee "$output"
  grep -Eq '^OK \([1-9][0-9]* tests?\)$' "$output"
  ! grep -Eq 'FAILURES!!!|INSTRUMENTATION_(ABORTED|FAILED)|shortMsg=' "$output"
}

"$adb_binary" get-state | grep -qx device

"$adb_binary" uninstall cool.jacoblin.particeps.test >/dev/null 2>&1 || true
"$adb_binary" uninstall cool.jacoblin.particeps >/dev/null 2>&1 || true
install_apk app/build/outputs/apk/debug/app-debug.apk false
install_apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk true
run_instrumentation \
  app \
  cool.jacoblin.particeps.test/androidx.test.runner.AndroidJUnitRunner

"$adb_binary" uninstall cool.jacoblin.particeps.core.storage.test >/dev/null 2>&1 || true
install_apk core/storage/build/outputs/apk/androidTest/debug/storage-debug-androidTest.apk true
run_instrumentation \
  storage \
  cool.jacoblin.particeps.core.storage.test/androidx.test.runner.AndroidJUnitRunner
