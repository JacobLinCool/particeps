#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

if (( $# != 0 )); then
  echo "usage: tools/android-api37-emulator-runner.sh" >&2
  exit 2
fi

report_directory="$repository_root/build/reports/android-host-harness"
mkdir -p "$report_directory"
emulator_log="$report_directory/api37-emulator-logcat.txt"
runner_log="$report_directory/api37-emulator-runner.txt"
: > "$emulator_log"
: > "$runner_log"

find_sdk_tool() {
  local tool_name="$1"
  local candidate
  for candidate in \
    "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/$tool_name" \
    "$ANDROID_SDK_ROOT"/cmdline-tools/*/bin/"$tool_name" \
    "$ANDROID_SDK_ROOT/tools/bin/$tool_name"; do
    if [[ -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  echo "Unable to find $tool_name under ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT" >&2
  return 1
}

sdkmanager="$(find_sdk_tool sdkmanager)"
avdmanager="$(find_sdk_tool avdmanager)"
adb_binary="$ANDROID_SDK_ROOT/platform-tools/adb"
emulator_binary="$ANDROID_SDK_ROOT/emulator/emulator"

system_image_package="system-images;android-37.0;google_apis_ps16k;x86_64"
printf 'y\n' | "$sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" \
  "emulator" \
  "platform-tools" \
  "platforms;android-37.0" \
  "$system_image_package"

for executable in "$adb_binary" "$emulator_binary"; do
  if [[ ! -x "$executable" ]]; then
    echo "Required Android SDK executable is missing after SDK installation: $executable" >&2
    exit 1
  fi
done

python3 -c 'import os, pathlib, xml.etree.ElementTree as ET; p=pathlib.Path(os.environ["ANDROID_SDK_ROOT"]) / "system-images/android-37.0/google_apis_ps16k/x86_64/package.xml"; r=ET.parse(p).getroot().find(".//revision/major"); assert r is not None and int(r.text) >= 5, "API 37 ps16k image revision must be at least 5"'

tools/android-emulator-prebuild.sh --require-16k=true

avd_parent="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/particeps-api37-avd"
mkdir -p "$avd_parent"
export ANDROID_AVD_HOME="$avd_parent"
avd_name="particeps_api37_gate"
printf 'no\n' | "$avdmanager" create avd \
  --force \
  --name "$avd_name" \
  --package "$system_image_package" \
  --device pixel_7_pro

emulator_pid=""
cleanup() {
  if [[ -n "$emulator_pid" ]]; then
    "$adb_binary" -s emulator-5554 emu kill >/dev/null 2>&1 || true
    kill "$emulator_pid" >/dev/null 2>&1 || true
    wait "$emulator_pid" >/dev/null 2>&1 || true
  fi
  "$avdmanager" delete avd --name "$avd_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

"$emulator_binary" \
  -avd "$avd_name" \
  -port 5554 \
  -accel on \
  -memory 4096 \
  -partition-size 12288 \
  -no-window \
  -gpu off \
  -no-snapshot \
  -noaudio \
  -camera-back none \
  -logcat '*:W' \
  -logcat-output "$emulator_log" \
  >>"$runner_log" 2>&1 &
emulator_pid=$!

# sys.boot_completed is not a sufficient readiness signal on the revision 5 preview image: it can
# become 1 before package, activity, or input has registered. Wait for the services required by the
# blocking compatibility gate. This observes the stock image and does not patch Android framework or
# SystemUI state.
deadline=$((SECONDS + 600))
ready=false
while (( SECONDS <= deadline )); do
  if ! kill -0 "$emulator_pid" >/dev/null 2>&1; then
    echo "API 37 emulator exited before required Android services became ready" >&2
    sed -n '1,240p' "$runner_log" >&2
    exit 1
  fi

  state="$($adb_binary -s emulator-5554 get-state 2>/dev/null || true)"
  boot_completed="$($adb_binary -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [[ "$state" == device && "$boot_completed" == 1 ]]; then
    package_service="$($adb_binary -s emulator-5554 shell service check package 2>/dev/null | tr -d '\r' || true)"
    activity_service="$($adb_binary -s emulator-5554 shell service check activity 2>/dev/null | tr -d '\r' || true)"
    input_service="$($adb_binary -s emulator-5554 shell service check input 2>/dev/null | tr -d '\r' || true)"
    if [[ "$package_service" == *found* && "$activity_service" == *found* && "$input_service" == *found* ]]; then
      ready=true
      break
    fi
  fi
  sleep 2
done

if [[ "$ready" != true ]]; then
  echo "API 37 emulator did not expose required Android services within 600 seconds" >&2
  exit 1
fi

export ADB="$adb_binary"
export ANDROID_SERIAL="emulator-5554"
tools/android-emulator-ci.sh --require-16k=true
