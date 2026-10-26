#!/usr/bin/env bash
set -euo pipefail

readonly EXPECTED_GO="go1.26.3"
readonly EXPECTED_NDK="30.0.14904198"
readonly EXPECTED_NDK_REVISION="30.0.14904198-beta1"
readonly MODULE_PROXY="https://proxy.golang.org"
readonly SUM_DATABASE="sum.golang.org"

if [[ $# -ne 2 || "${1}" != /* || "${1}" != *.aar ]]; then
  echo "usage: $0 /absolute/output.aar all|armeabi-v7a|arm64-v8a|x86|x86_64" >&2
  exit 2
fi
case "$2" in
  all) ABIS=(armeabi-v7a arm64-v8a x86 x86_64); TARGET=android ;;
  armeabi-v7a) ABIS=(armeabi-v7a); TARGET=android/arm ;;
  arm64-v8a) ABIS=(arm64-v8a); TARGET=android/arm64 ;;
  x86) ABIS=(x86); TARGET=android/386 ;;
  x86_64) ABIS=(x86_64); TARGET=android/amd64 ;;
  *) echo "Unsupported native ABI: $2" >&2; exit 2 ;;
esac
readonly ABIS TARGET

MODULE_DIR="$(cd "$(dirname "$0")" && pwd -P)"
readonly MODULE_DIR
readonly OUTPUT="$1"
: "${ANDROID_HOME:?ANDROID_HOME must identify the Android SDK}"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must identify the pinned NDK}"

if [[ "$(go env GOVERSION)" != "$EXPECTED_GO" ]]; then
  echo "Go $EXPECTED_GO is required" >&2
  exit 1
fi
if [[ ! -f "$ANDROID_NDK_HOME/source.properties" ]] ||
   ! grep -Fxq "Pkg.BaseRevision = $EXPECTED_NDK" "$ANDROID_NDK_HOME/source.properties" ||
   ! grep -Fxq "Pkg.Revision = $EXPECTED_NDK_REVISION" "$ANDROID_NDK_HOME/source.properties"; then
  echo "Android NDK $EXPECTED_NDK_REVISION is required" >&2
  if [[ -f "$ANDROID_NDK_HOME/source.properties" ]]; then
    sed -n '/^Pkg\./p' "$ANDROID_NDK_HOME/source.properties" >&2
  fi
  exit 1
fi

MODULE_CACHE="$(go env GOMODCACHE)"
readonly MODULE_CACHE
# Go keys compiled packages by source, compiler and flags. Keep that cache across
# builds, partitioned by the pinned NDK because cgo cannot track external C changes.
BUILD_CACHE="$(go env GOCACHE)/particeps-android-$EXPECTED_NDK_REVISION"
readonly BUILD_CACHE
WORK="$(mktemp -d)"
readonly WORK
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$(dirname "$OUTPUT")" "$WORK/bin" "$WORK/gopath" "$BUILD_CACHE"

export GOPROXY="$MODULE_PROXY"
export GOSUMDB="$SUM_DATABASE"
export GOPRIVATE=""
export GONOPROXY=""
export GONOSUMDB=""
export GOPATH="$WORK/gopath"
export GOMODCACHE="$MODULE_CACHE"
export PATH="$WORK/bin:$PATH"

cd "$MODULE_DIR"
go mod download
go mod verify
go test -run TestPinnedSourceDependenciesMatchSBOMInput -count=1 ./...
go build -trimpath -o "$WORK/bin/gomobile" golang.org/x/mobile/cmd/gomobile
go build -trimpath -o "$WORK/bin/gobind" golang.org/x/mobile/cmd/gobind

export GOCACHE="$BUILD_CACHE"
gomobile bind \
  -target="$TARGET" \
  -androidapi=34 \
  -trimpath \
  -javapkg=cool.jacoblin.particeps.nativebinding \
  -o "$OUTPUT" \
  .

# gomobile packages Go assets but does not know about the repository-level supply-chain record.
# Add the exact checked-in SBOM input and consolidated notices to the AAR so the final APK carries
# the evidence the release verifier audits. These are documentation assets only; no native binary
# is copied into the source tree.
mkdir -p "$WORK/aar-assets/assets/particeps"
cp "$MODULE_DIR/sbom-input.json" "$WORK/aar-assets/assets/particeps/traffic-shaping-sbom.json"
cp "$MODULE_DIR/THIRD_PARTY_NOTICES.md" "$WORK/aar-assets/assets/particeps/THIRD_PARTY_NOTICES.md"
python3 "$MODULE_DIR/package_licenses.py" \
  "$MODULE_DIR/sbom-input.json" \
  "$WORK/aar-assets" >"$WORK/license-assets.txt"
(
  cd "$WORK/aar-assets"
  zip -q -r "$OUTPUT" assets/particeps
)

ENTRIES_FILE="$WORK/aar-entries.txt"
readonly ENTRIES_FILE
unzip -Z1 "$OUTPUT" >"$ENTRIES_FILE"
for abi in "${ABIS[@]}"; do
  if ! grep -Fxq "jni/$abi/libgojni.so" "$ENTRIES_FILE"; then
    echo "AAR is missing ABI $abi" >&2
    exit 1
  fi
done
printf 'jni/%s/libgojni.so\n' "${ABIS[@]}" | sort > "$WORK/expected-native.txt"
grep -E '^jni/[^/]+/libgojni\.so$' "$ENTRIES_FILE" | sort > "$WORK/actual-native.txt"
diff -u "$WORK/expected-native.txt" "$WORK/actual-native.txt"
if ! grep -Fxq "assets/licenses/tun2socks-MIT.txt" "$ENTRIES_FILE"; then
  echo "AAR is missing the tun2socks MIT notice" >&2
  exit 1
fi
for evidence in \
  assets/particeps/traffic-shaping-sbom.json \
  assets/particeps/THIRD_PARTY_NOTICES.md; do
  if ! grep -Fxq "$evidence" "$ENTRIES_FILE"; then
    echo "AAR is missing supply-chain evidence $evidence" >&2
    exit 1
  fi
done
while IFS= read -r evidence; do
  if ! grep -Fxq "$evidence" "$ENTRIES_FILE"; then
    echo "AAR is missing verified module license $evidence" >&2
    exit 1
  fi
done <"$WORK/license-assets.txt"

READELF="$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" -path '*/bin/llvm-readelf' -print -quit)"
readonly READELF
if [[ -z "$READELF" ]]; then
  echo "pinned NDK does not provide llvm-readelf" >&2
  exit 1
fi
for abi in "${ABIS[@]}"; do
  readonly_so="$WORK/$abi-libgojni.so"
  unzip -p "$OUTPUT" "jni/$abi/libgojni.so" >"$readonly_so"
  case "$abi" in
    arm64-v8a|x86_64)
      if ! "$READELF" -lW "$readonly_so" | awk '$1 == "LOAD" && $NF != "0x4000" { exit 1 }'; then
        echo "$abi LOAD segments are not 16 KiB aligned" >&2
        exit 1
      fi
      ;;
  esac
  build_info="$WORK/$abi-build-info.txt"
  go version -m "$readonly_so" >"$build_info"
  if ! grep -Fq $'go1.26.3' "$build_info" ||
     ! grep -Fq $'github.com/xjasonlyu/tun2socks/v2\tv2.7.0\th1:fYEN0Q1sSanuoID8xvUTsMODbKLJJH/J5ywYoMRxIPw=' "$build_info"; then
    echo "$abi AAR build provenance does not match the pinned toolchain and tun2socks source" >&2
    exit 1
  fi
done
