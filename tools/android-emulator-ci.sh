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

if [[ "$require_16k" == true ]]; then
  adb_binary="${ADB:-adb}"
  page_size="$($adb_binary shell getconf PAGE_SIZE | tr -d '\r')"
  if [[ "$page_size" != "16384" ]]; then
    echo "API 37 ps16k emulator page size must be 16384, got: $page_size" >&2
    exit 1
  fi
  python3 -c 'import os, pathlib, xml.etree.ElementTree as ET; p=pathlib.Path(os.environ["ANDROID_SDK_ROOT"]) / "system-images/android-37.0/google_apis_ps16k/x86_64/package.xml"; r=ET.parse(p).getroot().find(".//revision/major"); assert r is not None and int(r.text) >= 5, "API 37 ps16k image revision must be at least 5"'

  sampling_state="$($adb_binary shell getprop debug.sf.luma_sampling | tr -d '\r')"
  if [[ "$sampling_state" != "0" ]]; then
    echo "API 37 SurfaceFlinger region sampling guard was not applied" >&2
    exit 1
  fi
  surfaceflinger_pid="$($adb_binary shell pidof surfaceflinger | tr -d '\r')"
  if [[ -z "$surfaceflinger_pid" ]]; then
    echo "API 37 SurfaceFlinger is not running" >&2
    exit 1
  fi
  sleep 10
  stable_surfaceflinger_pid="$($adb_binary shell pidof surfaceflinger | tr -d '\r')"
  if [[ "$stable_surfaceflinger_pid" != "$surfaceflinger_pid" ]]; then
    echo "API 37 SurfaceFlinger did not remain stable after the region sampling guard" >&2
    exit 1
  fi
  $adb_binary shell dumpsys window displays >/dev/null
fi

# One emulator is shared by every Android module in this job. Serial workers avoid overlapping
# package-installer sessions and make teardown between test APKs deterministic.
if [[ "$require_16k" == true ]]; then
  tools/android-instrumentation-ci.sh
else
  ./gradlew --no-daemon --max-workers=1 \
    -PinstrumentedTestAbi=x86_64 \
    connectedDebugAndroidTest
fi
tools/android-host-harness.sh --skip-build
