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

# Build every APK used by the blocking compatibility checks and host harness before starting the
# emulator. API 37 uses the stock image without root, remount, overlays, or system-service changes.
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
