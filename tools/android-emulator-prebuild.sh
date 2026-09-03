#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

if (( $# != 1 )); then
  echo "usage: tools/android-emulator-prebuild.sh --require-16k=true|false" >&2
  exit 2
fi

case "$1" in
  --require-16k=true|--require-16k=false) ;;
  *)
    echo "usage: tools/android-emulator-prebuild.sh --require-16k=true|false" >&2
    exit 2
    ;;
esac

# Build the blocking compatibility APK and the complete host-harness inputs before starting either
# emulator. API 37 runs the compatibility APK first; these additional artifacts are used only by its
# quarantined full host harness and cannot affect that blocking result.
gradle_tasks=(
  :app:assembleDebug
  :app:assembleDebugAndroidTest
  :core:storage:assembleDebug
  :core:storage:assembleDebugAndroidTest
  :test-fixtures:competing-vpn:assembleDebug
  :test-fixtures:shared-uid-peer:assembleDebug
  :test-fixtures:shared-uid-target:assembleDebug
  :test-fixtures:traffic-control:assembleDebug
  :test-fixtures:traffic-target-a:assembleBaseDebug
  :test-fixtures:traffic-target-a:assembleReplacementDebug
  :test-fixtures:traffic-target-b:assembleDebug
)
./gradlew --no-daemon --max-workers=1 \
  -PinstrumentedTestAbi=x86_64 \
  "${gradle_tasks[@]}"
