#!/usr/bin/env bash
set -euo pipefail

if (( $# != 2 )); then
  echo "usage: tools/android-api37-surfaceflinger-guard.sh <emulator-serial> <ready-file>" >&2
  exit 2
fi

emulator_serial="$1"
ready_file="$2"
failure_file="${ready_file}.failed"
adb_binary="${ADB:-adb}"
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
overlay_apk="${PARTICEPS_API37_SNAPSHOT_OVERLAY_APK:-$repository_root/test-fixtures/api37-snapshot-overlay/build/outputs/apk/debug/api37-snapshot-overlay-debug.apk}"
overlay_device_path="/product/overlay/ParticepsDisableTaskSnapshots.apk"
guard_timeout_seconds="${PARTICEPS_SURFACEFLINGER_GUARD_TIMEOUT_SECONDS:-300}"
stability_seconds="${PARTICEPS_SURFACEFLINGER_STABILITY_SECONDS:-10}"

publish_failure() {
  local exit_status="$?"
  if (( exit_status != 0 )); then
    printf 'failed\n' > "$failure_file"
  fi
}
trap publish_failure EXIT

service_is_available() {
  local service_name="$1"
  local service_status
  service_status="$(
    "$adb_binary" -s "$emulator_serial" shell service check "$service_name" 2>/dev/null |
      tr -d '\r' || true
  )"
  [[ "$service_status" == "Service ${service_name}: found" ]]
}

if [[ ! "$guard_timeout_seconds" =~ ^[1-9][0-9]*$ ]] ||
    [[ ! "$stability_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "SurfaceFlinger guard timeouts must be positive integers" >&2
  exit 2
fi
if [[ ! -s "$overlay_apk" ]]; then
  echo "API 37 task-snapshot RRO was not built: $overlay_apk" >&2
  exit 1
fi

guard_deadline_epoch=$(( $(date +%s) + guard_timeout_seconds ))
while (( $(date +%s) < guard_deadline_epoch )); do
  if "$adb_binary" -s "$emulator_serial" shell \
      setprop debug.sf.luma_sampling 0 >/dev/null 2>&1; then
    sampling_state="$(
      "$adb_binary" -s "$emulator_serial" shell \
        getprop debug.sf.luma_sampling 2>/dev/null | tr -d '\r'
    )"
    if [[ "$sampling_state" == "0" ]]; then
      break
    fi
  fi
  sleep 1
done

if [[ "${sampling_state:-}" != "0" ]]; then
  echo "Timed out setting the API 37 SurfaceFlinger guard property on $emulator_serial" >&2
  exit 1
fi

# The API 37 ps16k revision 5 image is userdebug. Root is required only to stage a test-only
# framework RRO on its writable product partition; production App behavior remains unprivileged.
"$adb_binary" -s "$emulator_serial" root >/dev/null
"$adb_binary" -s "$emulator_serial" wait-for-device
if [[ "$("$adb_binary" -s "$emulator_serial" shell id -u | tr -d '\r')" != "0" ]]; then
  echo "API 37 emulator adbd did not restart as root" >&2
  exit 1
fi
"$adb_binary" -s "$emulator_serial" shell setprop debug.sf.luma_sampling 0
"$adb_binary" -s "$emulator_serial" shell setprop sys.boot_completed 0

while (( $(date +%s) < guard_deadline_epoch )); do
  if service_is_available activity && service_is_available package &&
      service_is_available overlay; then
    initial_framework_ready=true
    break
  fi
  sleep 1
done

if [[ "${initial_framework_ready:-false}" != true ]]; then
  echo "API 37 framework and overlay service were not ready for graphics stabilization" >&2
  exit 1
fi

# SystemUI registers the nav-bar luma listener that drives RegionSamplingThread. Task snapshots
# are a second CPU-readback caller in system_server. Both hit the same broken ranchu non-DMA path.
# Stage the immutable RRO and fully reboot so WindowManager reads it while constructing its
# snapshot controllers; a late dynamic overlay cannot change those construction-time values.
"$adb_binary" -s "$emulator_serial" shell \
  pm disable-user --user 0 com.android.systemui >/dev/null
"$adb_binary" -s "$emulator_serial" remount >/dev/null
"$adb_binary" -s "$emulator_serial" push "$overlay_apk" "$overlay_device_path" >/dev/null
"$adb_binary" -s "$emulator_serial" shell chmod 0644 "$overlay_device_path"
"$adb_binary" -s "$emulator_serial" shell restorecon "$overlay_device_path"
"$adb_binary" -s "$emulator_serial" shell test -s "$overlay_device_path"
"$adb_binary" -s "$emulator_serial" shell sync

"$adb_binary" -s "$emulator_serial" shell setprop sys.boot_completed 0
"$adb_binary" -s "$emulator_serial" reboot
"$adb_binary" -s "$emulator_serial" wait-for-device
"$adb_binary" -s "$emulator_serial" root >/dev/null
"$adb_binary" -s "$emulator_serial" wait-for-device
if [[ "$("$adb_binary" -s "$emulator_serial" shell id -u | tr -d '\r')" != "0" ]]; then
  echo "API 37 emulator adbd did not return as root after reboot" >&2
  exit 1
fi
"$adb_binary" -s "$emulator_serial" shell setprop debug.sf.luma_sampling 0
"$adb_binary" -s "$emulator_serial" shell setprop sys.boot_completed 0

while (( $(date +%s) < guard_deadline_epoch )); do
  system_server_pid="$(
    "$adb_binary" -s "$emulator_serial" shell pidof system_server 2>/dev/null |
      tr -d '\r' || true
  )"
  if [[ -n "$system_server_pid" ]] && service_is_available package; then
    "$adb_binary" -s "$emulator_serial" shell \
      pm disable-user --user 0 com.android.systemui >/dev/null
    post_reboot_package_ready=true
    break
  fi
  sleep 1
done

if [[ "${post_reboot_package_ready:-false}" != true ]]; then
  echo "API 37 package service did not recover after graphics stabilization reboot" >&2
  exit 1
fi

while (( $(date +%s) < guard_deadline_epoch )); do
  boot_completed="$(
    "$adb_binary" -s "$emulator_serial" shell getprop sys.boot_completed 2>/dev/null |
      tr -d '\r' || true
  )"
  system_server_pid="$(
    "$adb_binary" -s "$emulator_serial" shell pidof system_server 2>/dev/null |
      tr -d '\r' || true
  )"
  if [[ "$boot_completed" == "1" ]] && [[ -n "$system_server_pid" ]] &&
      service_is_available activity && service_is_available package &&
      service_is_available window; then
    restarted_framework_ready=true
    break
  fi
  sleep 1
done

if [[ "${restarted_framework_ready:-false}" != true ]]; then
  echo "API 37 framework did not recover after graphics stabilization reboot" >&2
  exit 1
fi

# Reassert the emulator-only SystemUI state after boot before checking process stability.
"$adb_binary" -s "$emulator_serial" shell \
  pm disable-user --user 0 com.android.systemui >/dev/null
if ! "$adb_binary" -s "$emulator_serial" shell pm list packages -d --user 0 |
    tr -d '\r' | grep -qx 'package:com.android.systemui'; then
  echo "API 37 SystemUI did not remain disabled" >&2
  exit 1
fi
overlay_value="$(
  "$adb_binary" -s "$emulator_serial" shell cmd overlay lookup \
    android android:bool/config_disableTaskSnapshots | tr -d '\r'
)"
if [[ "$overlay_value" != "true" ]]; then
  echo "API 37 task-snapshot RRO was not active after reboot" >&2
  exit 1
fi
snapshot_disabled_count="$(
  "$adb_binary" -s "$emulator_serial" shell dumpsys window |
    grep -c 'mSnapshotEnabled=false' || true
)"
if (( snapshot_disabled_count < 2 )); then
  echo "API 37 task and activity snapshots did not initialize disabled" >&2
  exit 1
fi

surfaceflinger_pid="$(
  "$adb_binary" -s "$emulator_serial" shell pidof surfaceflinger | tr -d '\r'
)"
sleep "$stability_seconds"
stable_surfaceflinger_pid="$(
  "$adb_binary" -s "$emulator_serial" shell pidof surfaceflinger | tr -d '\r'
)"
stable_system_server_pid="$(
  "$adb_binary" -s "$emulator_serial" shell pidof system_server | tr -d '\r'
)"
if [[ -z "$surfaceflinger_pid" ]] || [[ "$stable_surfaceflinger_pid" != "$surfaceflinger_pid" ]] ||
    [[ "$stable_system_server_pid" != "$system_server_pid" ]]; then
  echo "API 37 graphics or framework process did not remain stable" >&2
  exit 1
fi

printf 'ready\n' > "$ready_file"
trap - EXIT
echo "API 37 emulator graphics stabilized on $emulator_serial"
