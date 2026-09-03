#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

# Build the only two instrumentation APK pairs before starting the emulator. This keeps the
# preview x86_64 system image out of the host's peak Kotlin/R8/native build pressure.
./gradlew --no-daemon --max-workers=1 \
  :app:assembleDebug \
  :app:assembleDebugAndroidTest \
  :core:storage:assembleDebug \
  :core:storage:assembleDebugAndroidTest
