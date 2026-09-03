#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

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
