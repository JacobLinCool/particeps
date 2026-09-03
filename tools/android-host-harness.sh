#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

skip_build=false
if [[ "${1:-}" == "--skip-build" ]]; then
  skip_build=true
  shift
fi
if (( $# != 0 )); then
  echo "usage: tools/android-host-harness.sh [--skip-build]" >&2
  exit 2
fi

adb_binary="${ADB:-adb}"
report_directory="${PARTICEPS_HOST_REPORT_DIR:-$repository_root/build/reports/android-host-harness}"
mkdir -p "$report_directory"
metrics_file="$report_directory/fixture-metrics.ndjson"
junit_file="$report_directory/android-host-harness.xml"
: > "$metrics_file"
printf '%s\n' \
  '<?xml version="1.0" encoding="UTF-8"?>' \
  '<testsuite name="android-host-harness" tests="1" failures="1"><testcase classname="particeps.android.host" name="harness_setup"><failure message="Harness did not reach final report commit." /></testcase></testsuite>' \
  > "$junit_file"

harness_temporary="$(mktemp -d "${TMPDIR:-/tmp}/particeps-host-harness.XXXXXX")"
server_pid=""
cleanup() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" >/dev/null 2>&1 || true
    wait "$server_pid" >/dev/null 2>&1 || true
  fi
  "$adb_binary" shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
  "$adb_binary" shell am force-stop cool.jacoblin.particeps.fixture.competingvpn >/dev/null 2>&1 || true
  find "$harness_temporary" -type f -delete >/dev/null 2>&1 || true
  rmdir "$harness_temporary" >/dev/null 2>&1 || true
}
trap cleanup EXIT

case_cleanup() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" >/dev/null 2>&1 || true
    wait "$server_pid" >/dev/null 2>&1 || true
    server_pid=""
  fi
  stop_traffic_fixtures
}

particeps_package="cool.jacoblin.particeps"
test_runner="cool.jacoblin.particeps.test/androidx.test.runner.AndroidJUnitRunner"
test_class="cool.jacoblin.particeps.HostHarnessStudyControlTest"
target_a_package="cool.jacoblin.particeps.fixture.targeta"
target_b_package="cool.jacoblin.particeps.fixture.targetb"
control_package="cool.jacoblin.particeps.fixture.control"
shared_target_package="cool.jacoblin.particeps.fixture.sharedtarget"
shared_peer_package="cool.jacoblin.particeps.fixture.sharedpeer"
competing_vpn_package="cool.jacoblin.particeps.fixture.competingvpn"
competing_vpn_activity="cool.jacoblin.particeps.fixtures.competingvpn.CompetingVpnActivity"
traffic_activity="cool.jacoblin.particeps.fixtures.traffic.TrafficFixtureActivity"
host_query_action="cool.jacoblin.particeps.HOST_HARNESS_QUERY"
host_provision_action="cool.jacoblin.particeps.HOST_HARNESS_PROVISION"
host_reset_action="cool.jacoblin.particeps.HOST_HARNESS_RESET"
host_envelope_asset="app/src/androidTest/assets/host_harness_study_envelope.txt"

stop_traffic_fixtures() {
  for package_name in "$target_a_package" "$target_b_package" "$control_package"; do
    "$adb_binary" shell am force-stop "$package_name" >/dev/null 2>&1 || true
  done
}

app_apk="app/build/outputs/apk/debug/app-debug.apk"
test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
target_a_base_apk="test-fixtures/traffic-target-a/build/outputs/apk/base/debug/traffic-target-a-base-debug.apk"
target_a_replacement_apk="test-fixtures/traffic-target-a/build/outputs/apk/replacement/debug/traffic-target-a-replacement-debug.apk"
target_b_apk="test-fixtures/traffic-target-b/build/outputs/apk/debug/traffic-target-b-debug.apk"
control_apk="test-fixtures/traffic-control/build/outputs/apk/debug/traffic-control-debug.apk"
shared_target_apk="test-fixtures/shared-uid-target/build/outputs/apk/debug/shared-uid-target-debug.apk"
shared_peer_apk="test-fixtures/shared-uid-peer/build/outputs/apk/debug/shared-uid-peer-debug.apk"
competing_vpn_apk="test-fixtures/competing-vpn/build/outputs/apk/debug/competing-vpn-debug.apk"

if [[ "$skip_build" == false ]]; then
  ./gradlew --no-daemon \
    :app:assembleDebug \
    :app:assembleDebugAndroidTest \
    :test-fixtures:competing-vpn:assembleDebug \
    :test-fixtures:shared-uid-peer:assembleDebug \
    :test-fixtures:shared-uid-target:assembleDebug \
    :test-fixtures:traffic-control:assembleDebug \
    :test-fixtures:traffic-target-a:assembleBaseDebug \
    :test-fixtures:traffic-target-a:assembleReplacementDebug \
    :test-fixtures:traffic-target-b:assembleDebug
fi

for artifact in \
  "$app_apk" "$test_apk" "$target_a_base_apk" "$target_a_replacement_apk" \
  "$target_b_apk" "$control_apk" "$shared_target_apk" "$shared_peer_apk" "$competing_vpn_apk"; do
  test -f "$artifact"
done

"$adb_binary" get-state | grep -qx "device"

install_apk() {
  # Fixtures deliberately exercise package replacement with a higher version.
  # Always permit debuggable APK downgrade so a failed or interrupted prior run
  # cannot poison the next run's known version-1 baseline.
  "$adb_binary" install -r -d -t "$1" > "$harness_temporary/install.txt"
  grep -qx "Success" "$harness_temporary/install.txt"
}

install_initial_apks() {
  install_apk "$target_a_base_apk"
  install_apk "$target_b_apk"
  install_apk "$control_apk"
  install_apk "$shared_target_apk"
  install_apk "$shared_peer_apk"
  install_apk "$competing_vpn_apk"
  install_apk "$app_apk"
  install_apk "$test_apk"
}

grant_local_network_if_needed() {
  local sdk
  sdk="$($adb_binary shell getprop ro.build.version.sdk | tr -d '\r')"
  if (( sdk >= 37 )); then
    for package_name in \
      "$particeps_package" "$target_a_package" "$target_b_package" "$control_package" \
      "$competing_vpn_package"; do
      "$adb_binary" shell pm grant "$package_name" android.permission.ACCESS_LOCAL_NETWORK
    done
  fi
}

authorize_vpn() {
  "$adb_binary" shell appops set "$1" ACTIVATE_VPN allow
}

prepare_permissions() {
  "$adb_binary" shell pm grant "$particeps_package" android.permission.POST_NOTIFICATIONS
  for package_name in "$target_a_package" "$target_b_package" "$control_package"; do
    "$adb_binary" shell pm grant "$package_name" android.permission.POST_NOTIFICATIONS
  done
  grant_local_network_if_needed
  authorize_vpn "$particeps_package"
  authorize_vpn "$competing_vpn_package"
}

run_instrumentation() {
  local method="$1"
  local output="$harness_temporary/instrumentation-$method.txt"
  "$adb_binary" shell am instrument -w -r \
    -e particepsHostHarness true \
    -e class "$test_class#$method" \
    "$test_runner" > "$output"
  grep -Eq '^OK \(1 test\)' "$output"
}

particeps_pid() {
  "$adb_binary" shell pidof "$particeps_package" | tr -d '\r\n'
}

capture_live_particeps_pid() {
  local pid
  pid="$(particeps_pid)"
  [[ "$pid" =~ ^[0-9]+$ ]]
  printf '%s\n' "$pid"
}

query_live_runtime() {
  local expected_pid="$1"
  local current_pid output data
  current_pid="$(particeps_pid)"
  [[ "$current_pid" == "$expected_pid" ]]
  output="$harness_temporary/live-state.txt"
  "$adb_binary" shell am broadcast --receiver-foreground \
    -a "$host_query_action" \
    -p "$particeps_package" > "$output"
  grep -q 'Broadcast completed: result=-1' "$output"
  current_pid="$(particeps_pid)"
  [[ "$current_pid" == "$expected_pid" ]]
  data="$(sed -n 's/.*data="\([^"]*\)".*/\1/p' "$output" | tail -n 1)"
  [[ "$data" =~ ^(NONE|IMPORTED|CONFIG_VERIFIED|CONSENT_PENDING|ACCESS_SETUP|READY|ACTIVATING|RUNNING|PAUSING|PAUSED|COMPLETED|WITHDRAWN):[0-9]+$ ]]
  printf '%s\n' "$data"
}

query_current_runtime() {
  local output data current_pid
  output="$harness_temporary/current-state.txt"
  "$adb_binary" shell am broadcast --receiver-foreground \
    -a "$host_query_action" \
    -p "$particeps_package" > "$output"
  grep -q 'Broadcast completed: result=-1' "$output"
  data="$(sed -n 's/.*data="\([^"]*\)".*/\1/p' "$output" | tail -n 1)"
  [[ "$data" =~ ^(NONE|IMPORTED|CONFIG_VERIFIED|CONSENT_PENDING|ACCESS_SETUP|READY|ACTIVATING|RUNNING|PAUSING|PAUSED|COMPLETED|WITHDRAWN):[0-9]+$ ]]
  current_pid="$(capture_live_particeps_pid)"
  printf '%s|%s\n' "$current_pid" "$data"
}

await_live_state() {
  local expected_state="$1"
  local expected_pid="$2"
  local timeout_seconds="$3"
  local deadline state_and_count state admitted_after_quiescence
  deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS <= deadline )); do
    state_and_count="$(query_live_runtime "$expected_pid")" || return 1
    state="${state_and_count%%:*}"
    if [[ "$state" == "$expected_state" ]]; then
      if [[ "$expected_state" == "PAUSED" ]]; then
        sleep 1
        admitted_after_quiescence="$(query_live_runtime "$expected_pid")" || return 1
        [[ "$admitted_after_quiescence" == "$state_and_count" ]]
      fi
      return 0
    fi
    sleep 0.25
  done
  return 1
}

await_permission_revoke_pause() {
  local initial_pid="$1"
  local timeout_seconds="$2"
  local deadline observation observed_pid state_and_count state admitted_after_quiescence
  deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS <= deadline )); do
    if ! observation="$(query_current_runtime)"; then
      sleep 0.25
      continue
    fi
    observed_pid="${observation%%|*}"
    state_and_count="${observation#*|}"
    state="${state_and_count%%:*}"
    if [[ "$state" == "PAUSED" ]]; then
      sleep 1
      admitted_after_quiescence="$(query_live_runtime "$observed_pid")" || return 1
      [[ "$admitted_after_quiescence" == "$state_and_count" ]]
      if [[ "$observed_pid" == "$initial_pid" ]]; then
        permission_revoke_process_continuity=true
      else
        permission_revoke_process_continuity=false
      fi
      return 0
    fi
    sleep 0.25
  done
  return 1
}

record_live_transition_latency() {
  local scenario="$1"
  local expected_state="$2"
  local elapsed_seconds="$3"
  python3 -c '
import json, sys
print(json.dumps({
    "elapsed_seconds": int(sys.argv[3]),
    "expected_state": sys.argv[2],
    "fixture_role": "live_transition_latency",
    "scenario": sys.argv[1],
}, sort_keys=True, separators=(",", ":")))
' "$scenario" "$expected_state" "$elapsed_seconds" >> "$metrics_file"
}

record_not_applicable() {
  local scenario="$1"
  python3 -c '
import json, sys
print(json.dumps({
    "fixture_role": "scenario_applicability",
    "scenario": sys.argv[1],
    "status": "not_applicable_before_api_37",
}, sort_keys=True, separators=(",", ":")))
' "$scenario" >> "$metrics_file"
}

record_permission_revoke_outcome() {
  local process_continuity="$1"
  python3 -c '
import json, sys
print(json.dumps({
    "fixture_role": "permission_revoke_outcome",
    "process_continuity": sys.argv[1] == "true",
    "scenario": "api37_local_network_permission_revoke",
}, sort_keys=True, separators=(",", ":")))
' "$process_continuity" >> "$metrics_file"
}

provision_running_study() {
  local encoded output data
  "$adb_binary" shell am force-stop "$competing_vpn_package"
  authorize_vpn "$particeps_package"
  encoded="$(tr -d '\r\n' < "$host_envelope_asset")"
  output="$harness_temporary/provision.txt"
  "$adb_binary" shell am broadcast --receiver-foreground \
    -a "$host_provision_action" \
    -p "$particeps_package" \
    --es signed_envelope_base64 "$encoded" > "$output"
  grep -q 'Broadcast completed: result=-1' "$output"
  data="$(sed -n 's/.*data="\([^"]*\)".*/\1/p' "$output" | tail -n 1)"
  [[ "$data" == "RUNNING" ]]
}

reset_study() {
  local output data
  output="$harness_temporary/reset.txt"
  # Connectivity callbacks can briefly overlap teardown after a handover.
  # RESET is idempotent, so retry the debug-only command until the serialized
  # runtime has completed any in-flight transition instead of failing cleanup.
  for _ in $(seq 1 40); do
    if "$adb_binary" shell am broadcast --receiver-foreground \
      -a "$host_reset_action" \
      -p "$particeps_package" > "$output" \
      && grep -q 'Broadcast completed: result=-1' "$output"; then
      data="$(sed -n 's/.*data="\([^"]*\)".*/\1/p' "$output" | tail -n 1)"
      [[ "$data" == "RESET" ]] && return 0
    fi
    sleep 0.25
  done
  return 1
}

package_uid() {
  local package_name="$1"
  "$adb_binary" shell cmd package list packages -U "$package_name" \
    | tr -d '\r' \
    | awk -v expected="package:$package_name" \
      '$1 == expected && $2 ~ /^uid:[0-9]+$/ {sub(/^uid:/, "", $2); print $2; exit}'
}

package_version_code() {
  "$adb_binary" shell dumpsys package "$1" \
    | tr -d '\r' \
    | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' \
    | head -n 1
}

read_fixture_file() {
  "$adb_binary" shell run-as "$1" cat "files/$2" | tr -d '\r'
}

run_smoke_fixture() {
  local package_name="$1"
  local expected_role="$2"
  "$adb_binary" shell am force-stop "$package_name"
  "$adb_binary" shell run-as "$package_name" rm -f files/fixture-metrics.json
  "$adb_binary" shell am start -W -n "$package_name/$traffic_activity" > "$harness_temporary/activity.txt"
  local value=""
  for _ in $(seq 1 60); do
    value="$(read_fixture_file "$package_name" fixture-metrics.json 2>/dev/null || true)"
    [[ -n "$value" ]] && break
    sleep 0.5
  done
  [[ -n "$value" ]]
  python3 -c '
import json, sys
value = json.loads(sys.argv[1])
assert set(value) == {"attempted_bytes", "failed_operations", "fixture_role", "succeeded_operations", "total_attempts", "version_code"}
assert value["fixture_role"] == sys.argv[2]
assert value["total_attempts"] == 10
assert value["succeeded_operations"] + value["failed_operations"] == 10
assert value["attempted_bytes"] == 1110
' "$value" "$expected_role"
  printf '%s\n' "$value" >> "$metrics_file"
}

run_saturation_measurement() {
  local cap_kbps="$1"
  local sequence="$2"
  local target_port=$((19090 + sequence * 2))
  local control_port=$((target_port + 1))
  local ready="$harness_temporary/server-$sequence.ready"
  local output="$harness_temporary/server-$sequence.json"

  # Android may retain a stopped foreground-service instance briefly. Start
  # each measurement from fresh fixture processes so no prior startId or
  # cached-app freezer state can suppress the next finite workload.
  stop_traffic_fixtures

  python3 tools/android_fixture_server.py \
    --cap-kbps "$cap_kbps" \
    --control-port "$control_port" \
    --duration-seconds 60 \
    --output "$output" \
    --ready "$ready" \
    --target-port "$target_port" &
  server_pid="$!"
  for _ in $(seq 1 50); do
    [[ -f "$ready" ]] && break
    sleep 0.1
  done
  [[ -f "$ready" ]]

  "$adb_binary" shell am start -W -n "$target_a_package/$traffic_activity" \
    --es mode saturate --ei port "$target_port" > "$harness_temporary/saturate-a.txt"
  "$adb_binary" shell am start -W -n "$target_b_package/$traffic_activity" \
    --es mode saturate --ei port "$target_port" > "$harness_temporary/saturate-b.txt"
  "$adb_binary" shell am start -W -n "$control_package/$traffic_activity" \
    --es mode saturate --ei port "$control_port" > "$harness_temporary/saturate-control.txt"

  local result=0
  wait "$server_pid" || result="$?"
  server_pid=""
  test -f "$output"
  cat "$output" >> "$metrics_file"
  stop_traffic_fixtures
  (( result == 0 ))
}

wait_for_boot() {
  "$adb_binary" wait-for-device
  for _ in $(seq 1 90); do
    if [[ "$($adb_binary shell getprop sys.boot_completed | tr -d '\r')" == "1" ]]; then
      return 0
    fi
    sleep 2
  done
  return 1
}

case_fixture_inventory_and_protocols() {
  local shared_target_uid shared_peer_uid control_uid
  shared_target_uid="$(package_uid "$shared_target_package")"
  shared_peer_uid="$(package_uid "$shared_peer_package")"
  control_uid="$(package_uid "$control_package")"
  [[ -n "$shared_target_uid" ]]
  [[ "$shared_target_uid" == "$shared_peer_uid" ]]
  [[ "$shared_target_uid" != "$control_uid" ]]
  run_smoke_fixture "$target_a_package" target_a
  run_smoke_fixture "$target_b_package" target_b
  run_smoke_fixture "$control_package" control
  run_smoke_fixture "$shared_target_package" shared_uid_target
  run_smoke_fixture "$shared_peer_package" shared_uid_peer
  "$adb_binary" uninstall "$shared_peer_package" > "$harness_temporary/uninstall.txt"
  grep -qx "Success" "$harness_temporary/uninstall.txt"
}

case_protocol_matrix_through_verified_vpn() {
  local live_pid
  provision_running_study
  live_pid="$(capture_live_particeps_pid)"
  run_smoke_fixture "$target_a_package" target_a
  run_smoke_fixture "$target_b_package" target_b
  run_smoke_fixture "$shared_target_package" shared_uid_target
  run_smoke_fixture "$control_package" control
  await_live_state RUNNING "$live_pid" 15
  reset_study
}

case_three_profile_throughput_and_control_bypass() {
  provision_running_study
  run_saturation_measurement 64 1
  # The signed resource binding changes at 75 active seconds; stay clear of the barrier.
  sleep 18
  run_saturation_measurement 512 2
  # The next signed binding boundary is 150 active seconds.
  sleep 18
  run_saturation_measurement 4096 3
  reset_study
}

case_process_kill_recovery() {
  provision_running_study
  "$adb_binary" shell am force-stop "$particeps_package"
  run_instrumentation assertSafetyPaused
  reset_study
}

case_reboot_recovery() {
  provision_running_study
  "$adb_binary" reboot
  wait_for_boot
  prepare_permissions
  run_instrumentation assertSafetyPaused
  reset_study
}

case_target_replace() {
  local live_pid transition_started
  provision_running_study
  live_pid="$(capture_live_particeps_pid)"
  install_apk "$target_a_replacement_apk"
  [[ "$(package_version_code "$target_a_package")" == "2" ]]
  transition_started="$(date +%s)"
  await_live_state PAUSED "$live_pid" 30
  record_live_transition_latency target_replace PAUSED "$(( $(date +%s) - transition_started ))"
  "$adb_binary" shell am force-stop "$particeps_package"
  run_instrumentation assertDurablySafetyPaused
  reset_study
  "$adb_binary" uninstall "$target_a_package" > "$harness_temporary/uninstall.txt"
  grep -qx "Success" "$harness_temporary/uninstall.txt"
  install_apk "$target_a_base_apk"
  [[ "$(package_version_code "$target_a_package")" == "1" ]]
  grant_local_network_if_needed
}

case_target_uninstall() {
  local live_pid transition_started
  provision_running_study
  live_pid="$(capture_live_particeps_pid)"
  "$adb_binary" uninstall "$target_b_package" > "$harness_temporary/uninstall.txt"
  grep -qx "Success" "$harness_temporary/uninstall.txt"
  transition_started="$(date +%s)"
  await_live_state PAUSED "$live_pid" 30
  record_live_transition_latency target_uninstall PAUSED "$(( $(date +%s) - transition_started ))"
  "$adb_binary" shell am force-stop "$particeps_package"
  run_instrumentation assertDurablySafetyPaused
  reset_study
  install_apk "$target_b_apk"
  grant_local_network_if_needed
}

case_shared_uid_peer_install() {
  local live_pid transition_started
  provision_running_study
  live_pid="$(capture_live_particeps_pid)"
  install_apk "$shared_peer_apk"
  # Any package mutation re-captures signed targets and same-UID peers immediately.
  transition_started="$(date +%s)"
  await_live_state PAUSED "$live_pid" 30
  record_live_transition_latency shared_uid_peer_install PAUSED "$(( $(date +%s) - transition_started ))"
  "$adb_binary" shell am force-stop "$particeps_package"
  run_instrumentation assertDurablySafetyPaused
  reset_study
  "$adb_binary" uninstall "$shared_peer_package" > "$harness_temporary/uninstall.txt"
  grep -qx "Success" "$harness_temporary/uninstall.txt"
}

case_competing_vpn_revoke() {
  local live_pid transition_started
  provision_running_study
  live_pid="$(capture_live_particeps_pid)"
  authorize_vpn "$competing_vpn_package"
  "$adb_binary" shell run-as "$competing_vpn_package" rm -f files/vpn-fixture-state.json
  "$adb_binary" shell am start -W \
    -n "$competing_vpn_package/$competing_vpn_activity" > "$harness_temporary/competing-vpn.txt"
  local value=""
  for _ in $(seq 1 40); do
    value="$(read_fixture_file "$competing_vpn_package" vpn-fixture-state.json 2>/dev/null || true)"
    [[ -n "$value" ]] && break
    sleep 0.25
  done
  [[ "$value" == '{"established":true,"fixture_role":"competing_vpn"}' ]]
  printf '%s\n' "$value" >> "$metrics_file"
  transition_started="$(date +%s)"
  await_live_state PAUSED "$live_pid" 30
  record_live_transition_latency competing_vpn_revoke PAUSED "$(( $(date +%s) - transition_started ))"
  "$adb_binary" shell am force-stop "$particeps_package"
  run_instrumentation assertDurablySafetyPaused
  reset_study
  "$adb_binary" shell am force-stop "$competing_vpn_package"
}

case_underlying_network_handover() {
  local live_pid transition_started
  provision_running_study
  live_pid="$(capture_live_particeps_pid)"
  "$adb_binary" shell cmd connectivity airplane-mode enable
  sleep 3
  "$adb_binary" shell cmd connectivity airplane-mode disable
  sleep 10
  transition_started="$(date +%s)"
  await_live_state RUNNING "$live_pid" 15
  record_live_transition_latency underlying_network_handover RUNNING "$(( $(date +%s) - transition_started ))"
  reset_study
}

case_api37_local_network_permission_revoke() {
  local sdk live_pid transition_started
  sdk="$($adb_binary shell getprop ro.build.version.sdk | tr -d '\r')"
  if (( sdk < 37 )); then
    record_not_applicable api37_local_network_permission_revoke
    return 77
  fi
  provision_running_study
  live_pid="$(capture_live_particeps_pid)"
  "$adb_binary" shell pm revoke "$particeps_package" android.permission.ACCESS_LOCAL_NETWORK
  transition_started="$(date +%s)"
  permission_revoke_process_continuity=""
  await_permission_revoke_pause "$live_pid" 105
  record_live_transition_latency local_network_permission_revoke PAUSED \
    "$(( $(date +%s) - transition_started ))"
  [[ "$permission_revoke_process_continuity" =~ ^(true|false)$ ]]
  record_permission_revoke_outcome "$permission_revoke_process_continuity"
  "$adb_binary" shell am force-stop "$particeps_package"
  run_instrumentation assertDurablySafetyPaused
  reset_study
  "$adb_binary" shell pm grant "$particeps_package" android.permission.ACCESS_LOCAL_NETWORK
}

install_initial_apks
prepare_permissions

declare -a case_names=()
declare -a case_results=()
declare -a case_durations=()
failure_count=0
skipped_count=0

run_case() {
  local name="$1"
  local function_name="$2"
  local started ended result
  started="$(date +%s)"
  local case_exit
  # A function invoked from an `if` condition inherits Bash's ignored-errexit context, even when
  # the subshell executes `set -e` again. Run it as an ordinary command while the parent briefly
  # permits a non-zero status, then classify the captured result. Otherwise an early failed
  # assertion can be hidden by a later successful cleanup command in the same case.
  set +e
  (trap case_cleanup EXIT; set -euo pipefail; "$function_name")
  case_exit=$?
  set -e
  if (( case_exit == 0 )); then
    result="passed"
  else
    if (( case_exit == 77 )); then
      result="not_applicable"
      skipped_count=$((skipped_count + 1))
    else
      result="failed"
      failure_count=$((failure_count + 1))
      "$adb_binary" shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
      "$adb_binary" shell am force-stop "$competing_vpn_package" >/dev/null 2>&1 || true
      "$adb_binary" install -r -d -t "$target_a_base_apk" > "$harness_temporary/restore.txt" 2>&1 || true
      "$adb_binary" install -r -t "$target_b_apk" > "$harness_temporary/restore.txt" 2>&1 || true
      "$adb_binary" install -r -t "$shared_target_apk" > "$harness_temporary/restore.txt" 2>&1 || true
      "$adb_binary" uninstall "$shared_peer_package" > "$harness_temporary/restore.txt" 2>&1 || true
      prepare_permissions >/dev/null 2>&1 || true
      reset_study >/dev/null 2>&1 || true
    fi
  fi
  ended="$(date +%s)"
  case_names+=("$name")
  case_results+=("$result")
  case_durations+=("$((ended - started))")
}

run_case "fixture_inventory_tcp_udp_dns_ipv4_ipv6_and_shared_uid" case_fixture_inventory_and_protocols
run_case "tcp_udp_dns_ipv4_ipv6_through_verified_vpn" case_protocol_matrix_through_verified_vpn
run_case "aggregate_64_512_4096_kbps_and_control_bypass" case_three_profile_throughput_and_control_bypass
run_case "process_kill_recovers_safety_paused" case_process_kill_recovery
run_case "reboot_recovers_safety_paused" case_reboot_recovery
run_case "target_replace_safety_pauses" case_target_replace
run_case "target_uninstall_safety_pauses" case_target_uninstall
run_case "unselected_shared_uid_peer_install_safety_pauses" case_shared_uid_peer_install
run_case "competing_vpn_revokes_and_safety_pauses" case_competing_vpn_revoke
run_case "underlying_network_handover_remains_running" case_underlying_network_handover
run_case "api37_local_network_permission_revoke_safety_pauses" case_api37_local_network_permission_revoke

junit_temporary="$junit_file.tmp"
{
  printf '<?xml version="1.0" encoding="UTF-8"?>\n'
  printf '<testsuite name="android-host-harness" tests="%d" failures="%d" skipped="%d">\n' \
    "${#case_names[@]}" "$failure_count" "$skipped_count"
  for index in "${!case_names[@]}"; do
    printf '  <testcase classname="particeps.android.host" name="%s" time="%s">' \
      "${case_names[$index]}" "${case_durations[$index]}"
    if [[ "${case_results[$index]}" == "failed" ]]; then
      printf '<failure message="Host scenario failed; raw device output intentionally not retained." />'
    elif [[ "${case_results[$index]}" == "not_applicable" ]]; then
      printf '<skipped message="Requires the Android 17 local-network runtime permission (API 37)." />'
    fi
    printf '</testcase>\n'
  done
  printf '</testsuite>\n'
} > "$junit_temporary"
mv "$junit_temporary" "$junit_file"

(( failure_count == 0 ))
