#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk_root" ]]; then
  echo "ANDROID_SDK_ROOT must identify the pinned Android SDK" >&2
  exit 1
fi

build_tools="$sdk_root/build-tools/37.0.0"
platform_jar="$sdk_root/platforms/android-37.0/android.jar"
resource_root="$repository_root/test-fixtures/api37-snapshot-overlay/src/main/res"
manifest="$repository_root/test-fixtures/api37-snapshot-overlay/src/main/AndroidManifest.xml"
output_directory="$repository_root/test-fixtures/api37-snapshot-overlay/build/outputs/apk/debug"
output_apk="$output_directory/api37-snapshot-overlay-debug.apk"

for required_file in \
  "$build_tools/aapt2" \
  "$build_tools/apksigner" \
  "$build_tools/zipalign" \
  "$platform_jar" \
  "$manifest"; do
  if [[ ! -f "$required_file" ]]; then
    echo "Required API 37 RRO build input is missing: $required_file" >&2
    exit 1
  fi
done

temporary_directory="$(mktemp -d)"
cleanup() {
  rm -rf -- "$temporary_directory"
}
trap cleanup EXIT

if [[ -n "${PARTICEPS_DEBUG_KEYSTORE:-}" ]]; then
  debug_keystore="$PARTICEPS_DEBUG_KEYSTORE"
  if [[ ! -f "$debug_keystore" ]]; then
    echo "Configured API 37 RRO signing keystore is missing: $debug_keystore" >&2
    exit 1
  fi
else
  debug_keystore="$temporary_directory/test-only.keystore"
  if ! command -v keytool >/dev/null 2>&1; then
    echo "JDK keytool is required to sign the test-only API 37 RRO" >&2
    exit 1
  fi
  keytool -genkeypair \
    -alias androiddebugkey \
    -keyalg RSA \
    -keysize 2048 \
    -validity 1 \
    -dname "CN=Particeps API 37 Test RRO" \
    -keystore "$debug_keystore" \
    -storepass android \
    -keypass android \
    -noprompt >/dev/null 2>&1
fi

mkdir -p "$output_directory"
"$build_tools/aapt2" compile \
  --dir "$resource_root" \
  -o "$temporary_directory/resources.zip"
"$build_tools/aapt2" link \
  --manifest "$manifest" \
  --auto-add-overlay \
  -I "$platform_jar" \
  -o "$temporary_directory/overlay-unsigned.apk" \
  "$temporary_directory/resources.zip"
"$build_tools/zipalign" -f 4 \
  "$temporary_directory/overlay-unsigned.apk" \
  "$temporary_directory/overlay-aligned.apk"
"$build_tools/apksigner" sign \
  --ks "$debug_keystore" \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$output_apk" \
  "$temporary_directory/overlay-aligned.apk"
"$build_tools/apksigner" verify --verbose "$output_apk" >/dev/null

if unzip -Z1 "$output_apk" | grep -Eq '^classes[0-9]*[.]dex$'; then
  echo "Test-only API 37 RRO must not contain executable code" >&2
  exit 1
fi

echo "Built test-only API 37 snapshot RRO: $output_apk"
