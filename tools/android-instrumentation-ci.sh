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
  local output
  output="$("$adb_binary" install --no-streaming -r -d -t "$apk" | tr -d '\r')"
  [[ "$output" == "Success" ]]
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
install_apk app/build/outputs/apk/debug/app-debug.apk
install_apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
run_instrumentation \
  app \
  cool.jacoblin.particeps.test/androidx.test.runner.AndroidJUnitRunner

"$adb_binary" uninstall cool.jacoblin.particeps.core.storage.test >/dev/null 2>&1 || true
install_apk core/storage/build/outputs/apk/androidTest/debug/storage-debug-androidTest.apk
run_instrumentation \
  storage \
  cool.jacoblin.particeps.core.storage.test/androidx.test.runner.AndroidJUnitRunner
