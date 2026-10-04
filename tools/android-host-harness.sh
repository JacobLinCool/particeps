#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

skip_build=false
duplex_only=false
duplex_cap_kbps=512
duplex_cap_requested=false
fixed_cap_kbps=0
diagnostic_repetitions=0
diagnostic_duration_seconds=60
diagnostic_duration_requested=false
diagnostics_mode=auto
while (( $# != 0 )); do
  case "$1" in
    --skip-build) skip_build=true; shift ;;
    --duplex-only) duplex_only=true; shift ;;
    --duplex-cap-kbps)
      [[ "$duplex_cap_requested" == false && ( "${2:-}" == 64 || "${2:-}" == 512 ) ]] || { echo "Choose one duplex cap: 64 or 512 kbps" >&2; exit 2; }
      duplex_cap_kbps="$2"; duplex_cap_requested=true; shift 2 ;;
    --fixed-cap-kbps)
      [[ "${2:-}" == 64 || "${2:-}" == 512 || "${2:-}" == 4096 ]] || { echo "Expected 64, 512 or 4096 kbps" >&2; exit 2; }
      fixed_cap_kbps="$2"; shift 2 ;;
    --repetitions)
      [[ "${2:-}" =~ ^[1-5]$ ]] || { echo "Expected 1..5 repetitions" >&2; exit 2; }
      diagnostic_repetitions="$2"; shift 2 ;;
    --duration-seconds)
      [[ "${2:-}" == 60 || "${2:-}" == 300 ]] || { echo "Expected 60 or 300 seconds" >&2; exit 2; }
      diagnostic_duration_seconds="$2"; diagnostic_duration_requested=true; shift 2 ;;
    --capture-throughput-diagnostics|--no-throughput-diagnostics)
      [[ "$diagnostics_mode" == auto ]] || { echo "Choose one diagnostics option" >&2; exit 2; }
      if [[ "$1" == --capture-throughput-diagnostics ]]; then diagnostics_mode=on; else diagnostics_mode=off; fi
      shift ;;
    *) echo "usage: tools/android-host-harness.sh [--skip-build] [--duplex-only [--duplex-cap-kbps 64|512]|--fixed-cap-kbps 64|512|4096 --repetitions 1..5 [--duration-seconds 60|300]] [--capture-throughput-diagnostics|--no-throughput-diagnostics]" >&2; exit 2 ;;
  esac
done
if [[ "$duplex_cap_requested" == true && "$duplex_only" != true ]]; then
  echo "--duplex-cap-kbps requires --duplex-only" >&2
  exit 2
fi
if (( (fixed_cap_kbps == 0) != (diagnostic_repetitions == 0) )); then
  echo "--fixed-cap-kbps and --repetitions are required together" >&2
  exit 2
fi
if [[ "$duplex_only" == true ]] && (( diagnostic_repetitions > 0 )); then
  echo "Duplex-only cannot combine with fixed-cap repetitions" >&2
  exit 2
fi
if [[ "$diagnostic_duration_requested" == true ]] && (( diagnostic_repetitions == 0 )); then
  echo "Diagnostic duration requires --fixed-cap-kbps and --repetitions" >&2
  exit 2
fi
measurement_duration_seconds=60
capture_throughput_diagnostics=false
if (( diagnostic_repetitions > 0 )); then
  measurement_duration_seconds="$diagnostic_duration_seconds"
  [[ "$diagnostics_mode" != off ]] && capture_throughput_diagnostics=true
fi
[[ "$diagnostics_mode" != on ]] || capture_throughput_diagnostics=true

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
diagnostics_pid=""
diagnostics_stop=""
diagnostics_result=""
stop_diagnostics() {
  if [[ -n "$diagnostics_pid" ]]; then
    local result=0
    touch "$diagnostics_stop"
    wait "$diagnostics_pid" || result=$?
    printf '{"monitor_exit_code":%d}\n' "$result" > "$diagnostics_result"
    diagnostics_pid=""
  fi
}
cleanup() {
  stop_diagnostics
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
  stop_diagnostics
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
device_serial="$("$adb_binary" get-serialno | tr -d '\r\n')"
[[ "$device_serial" =~ ^emulator-[0-9]+$ ]]

install_apk() {
  # Fixtures deliberately exercise package replacement with a higher version.
  # Always permit debuggable APK downgrade so a failed or interrupted prior run
  # cannot poison the next run's known version-1 baseline.
  "$adb_binary" install --no-streaming -r -d -t "$1" > "$harness_temporary/install.txt"
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
  local instrumentation_class="${2:-$test_class}"
  if (( $# >= 2 )); then
    shift 2
  else
    shift
  fi
  # Keep each invocation, including a startup ANR/aborted runner, after temporary cleanup.
  local output
  output="$(mktemp "$report_directory/instrumentation-${method}-XXXXXX")"
  "$adb_binary" shell am instrument -w -r \
    -e particepsHostHarness true \
    -e class "$instrumentation_class#$method" \
    "$@" "$test_runner" 2>&1 | tr -d '\r' > "$output"
  grep -Eq '^OK \(1 test\)$' "$output"
  grep -qx 'INSTRUMENTATION_STATUS_CODE: 0' "$output"
  ! grep -Eq 'INSTRUMENTATION_STATUS_CODE: -[1-4]$|FAILURES!!!|INSTRUMENTATION_(ABORTED|FAILED)|shortMsg=' "$output"
}

particeps_pid() {
  "$adb_binary" shell pidof "$particeps_package" | tr -d '\r\n'
}

capture_live_particeps_pid() {
  host_control --timeout-seconds 5 identity
}

host_control() {
  python3 tools/android_host_control.py --adb "$adb_binary" \
    --identity-file "$harness_temporary/particeps-process.json" \
    --evidence-directory "$report_directory/control-operations" "$@"
}

query_live_runtime() {
  local expected_pid="$1" timeout_seconds="${2:-5}" data
  data="$(host_control --expected-pid "$expected_pid" --timeout-seconds "$timeout_seconds" state)"
  [[ "$data" =~ ^(NONE|IMPORTED|CONFIG_VERIFIED|CONSENT_PENDING|ACCESS_SETUP|READY|ACTIVATING|RUNNING|PAUSING|PAUSED|COMPLETED|WITHDRAWN):[0-9]+$ ]]
  printf '%s\n' "$data"
}

query_current_runtime() {
  local data current_pid timeout_seconds="${1:-5}"
  # This query requires an already live process and never starts an Activity.
  current_pid="$(particeps_pid)"
  [[ "$current_pid" =~ ^[0-9]+$ ]]
  data="$(query_live_runtime "$current_pid" "$timeout_seconds")"
  printf '%s|%s\n' "$current_pid" "$data"
}

await_live_state() {
  local expected_state="$1"
  local expected_pid="$2"
  local timeout_seconds="$3"
  local deadline state_and_count state admitted_after_quiescence remaining
  deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS <= deadline )); do
    remaining=$((deadline - SECONDS))
    (( remaining > 0 )) || return 1
    state_and_count="$(query_live_runtime "$expected_pid" "$remaining")" || return 1
    state="${state_and_count%%:*}"
    if [[ "$state" == "$expected_state" ]]; then
      if [[ "$expected_state" == "PAUSED" ]]; then
        sleep 1
        remaining=$((deadline - SECONDS))
        (( remaining > 0 )) || return 1
        admitted_after_quiescence="$(query_live_runtime "$expected_pid" "$remaining")" || return 1
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
  local deadline observation observed_pid state_and_count state admitted_after_quiescence current_pid recovery_pid="" remaining
  permission_revoke_recovery_action=none
  deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS <= deadline )); do
    remaining=$((deadline - SECONDS))
    (( remaining > 0 )) || return 1
    (( remaining <= 90 )) || remaining=90
    current_pid="$(particeps_pid || true)"
    if [[ -n "$recovery_pid" ]]; then
      # A second process loss is a failure, never another cold-start attempt.
      [[ "$current_pid" == "$recovery_pid" ]] || return 1
    elif [[ "$current_pid" != "$initial_pid" ]]; then
      # Android may terminate a process on permission revocation. This scenario explicitly
      # reopens once to test durable PAUSED recovery; it does not claim automatic restart.
      recovery_pid="$(host_control --timeout-seconds "$remaining" prepare)" || return 1
      [[ "$recovery_pid" =~ ^[0-9]+$ && "$recovery_pid" != "$initial_pid" ]] || return 1
      permission_revoke_recovery_action=explicit_app_reopen
    fi
    remaining=$((deadline - SECONDS))
    (( remaining > 0 )) || return 1
    (( remaining <= 90 )) || remaining=90
    if ! observation="$(query_current_runtime "$remaining")"; then
      sleep 0.25
      continue
    fi
    observed_pid="${observation%%|*}"
    state_and_count="${observation#*|}"
    state="${state_and_count%%:*}"
    if [[ "$state" == "PAUSED" ]]; then
      sleep 1
      remaining=$((deadline - SECONDS))
      (( remaining > 0 )) || return 1
      (( remaining <= 90 )) || remaining=90
      admitted_after_quiescence="$(query_live_runtime "$observed_pid" "$remaining")" || return 1
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
    "recovery_action": sys.argv[2],
    "scenario": "api37_local_network_permission_revoke",
}, sort_keys=True, separators=(",", ":")))
' "$process_continuity" "$permission_revoke_recovery_action" >> "$metrics_file"
}

provision_running_study() {
  local envelope_asset="${1:-$host_envelope_asset}" data
  "$adb_binary" shell am force-stop "$competing_vpn_package"
  authorize_vpn "$particeps_package"
  data="$(host_control provision --envelope "$envelope_asset")"
  if [[ "$data" != "RUNNING" ]]; then
    printf 'Host provisioning failed: %s\n' "${data:-NO_RESULT}" >&2
    return 1
  fi
}

await_applied_profile() {
  local expected_profile="$1" expected_pid="$2" envelope_asset="$3" proof_file="$4"
  local deadline current_pid query_output result remaining
  deadline=$((SECONDS + 90))
  query_output="$harness_temporary/applied-profile-query.txt"
  while (( SECONDS <= deadline )); do
    current_pid="$(particeps_pid)"
    [[ "$current_pid" == "$expected_pid" ]]
    remaining=$((deadline - SECONDS))
    (( remaining > 0 )) || return 1
    host_control --expected-pid "$expected_pid" --timeout-seconds "$remaining" profile > "$query_output"
    current_pid="$(particeps_pid)"
    [[ "$current_pid" == "$expected_pid" ]]
    result=0
    python3 tools/android_host_profile.py query --observation "$query_output" \
      --asset "$envelope_asset" --profile "$expected_profile" --output "$proof_file" || result=$?
    if (( result == 0 )); then
      return 0
    fi
    # Only an explicit PENDING/earlier verified profile permits a state-driven wait.
    (( result == 3 )) || return "$result"
    sleep 0.25
  done
  printf 'Verified profile %s did not arrive within 90 seconds\n' "$expected_profile" >&2
  return 1
}

reset_study() {
  local data
  # One process-bound operation UUID; polling never repeats deletion after an unknown outcome.
  data="$(host_control reset)"
  [[ "$data" == "RESET" ]]
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
  for package_name in "$target_a_package" "$target_b_package" "$control_package"; do
    "$adb_binary" shell run-as "$package_name" rm -f files/saturation-progress.json
  done
  if [[ "$capture_throughput_diagnostics" == true ]]; then
    mkdir -p "$report_directory/throughput-diagnostics"
    diagnostics_stop="$harness_temporary/diagnostics-$sequence.stop"
    diagnostics_result="$report_directory/throughput-diagnostics/measurement-$sequence-result.json"
    rm -f "$diagnostics_stop"
    python3 -m tools.android_host_diagnostics --adb "$adb_binary" --serial "$device_serial" monitor \
      --identity-file "$harness_temporary/particeps-process.json" \
      --expected "$report_directory/applied-profiles/fixed-$cap_kbps-before.json" \
      --output "$report_directory/throughput-diagnostics/measurement-$sequence.ndjson" \
      --maximum-seconds "$((measurement_duration_seconds + 60))" \
      --stop-file "$diagnostics_stop" &
    diagnostics_pid="$!"
  fi

  python3 tools/android_fixture_server.py \
    --cap-kbps "$cap_kbps" \
    --control-port "$control_port" \
    --duration-seconds "$measurement_duration_seconds" \
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
  stop_diagnostics
  if (( result != 0 )); then
    python3 -m tools.android_host_diagnostics --adb "$adb_binary" --serial "$device_serial" capture \
      --output "$report_directory/throughput-diagnostics/measurement-$sequence-failure"
  fi
  stop_traffic_fixtures
  (( result == 0 ))
}

run_duplex_measurement() {
  local cap_kbps="$1"
  local directory="$report_directory/duplex" identity ready result=0 server_result=0
  mkdir -p "$directory"
  identity="$(python3 -c 'import uuid; print(uuid.uuid4())')"
  ready="$harness_temporary/duplex.ready"
  stop_traffic_fixtures
  "$adb_binary" shell run-as "$target_b_package" rm -f files/duplex-download.json
  if [[ "$capture_throughput_diagnostics" == true ]]; then
    for package_name in "$target_a_package" "$control_package"; do
      "$adb_binary" shell run-as "$package_name" rm -f files/saturation-progress.json
    done
    diagnostics_stop="$harness_temporary/duplex-diagnostics.stop"
    diagnostics_result="$directory/monitor-result.json"
    rm -f "$diagnostics_stop"
    python3 -m tools.android_host_diagnostics --adb "$adb_binary" --serial "$device_serial" monitor \
      --identity-file "$harness_temporary/particeps-process.json" \
      --traffic-mode duplex --maximum-seconds 120 \
      --expected "$report_directory/applied-profiles/duplex-$cap_kbps-before.json" \
      --output "$directory/diagnostics.ndjson" --stop-file "$diagnostics_stop" &
    diagnostics_pid="$!"
  fi
  python3 -m tools.android_duplex_fixture serve \
    --measurement-id "$identity" --upload-port 19120 --download-port 19121 --control-port 19122 \
    --ready "$ready" --output "$directory/host.json" &
  server_pid="$!"
  for _ in $(seq 1 50); do
    [[ -f "$ready" ]] && break
    kill -0 "$server_pid"
    sleep 0.1
  done
  test -f "$ready"
  "$adb_binary" shell am start -W -n "$target_a_package/$traffic_activity" \
    --es mode saturate --ei port 19120 > "$harness_temporary/duplex-a.txt"
  "$adb_binary" shell am start -W -n "$target_b_package/$traffic_activity" \
    --es mode duplex-download --es measurement_id "$identity" --ei port 19121 > "$harness_temporary/duplex-b.txt"
  "$adb_binary" shell am start -W -n "$control_package/$traffic_activity" \
    --es mode saturate --ei port 19122 > "$harness_temporary/duplex-control.txt"
  wait "$server_pid" || server_result=$?
  server_pid=""
  stop_diagnostics
  # One post-window adb call waits for AtomicFile publication and reads it once.
  # The default duplex gate has no periodic queries; explicit diagnostics opt in.
  "$adb_binary" shell run-as "$target_b_package" sh -c \
    "'for n in 1 2 3 4 5 6 7 8 9 10; do if [ -f files/duplex-download.json ]; then cat files/duplex-download.json; exit 0; fi; sleep 0.2; done; exit 1'" \
    > "$directory/android-download.json"
  python3 -m tools.android_duplex_fixture validate \
    --measurement-id "$identity" --cap-kbps "$cap_kbps" --host "$directory/host.json" \
    --download "$directory/android-download.json" --output "$directory/result.json" || result=$?
  cat "$directory/result.json" >> "$metrics_file"
  if (( result != 0 || server_result != 0 )); then
    python3 -m tools.android_host_diagnostics --adb "$adb_binary" --serial "$device_serial" capture \
      --output "$directory/failure"
  fi
  stop_traffic_fixtures
  (( result == 0 && server_result == 0 ))
}

wait_for_boot() {
  local boot_completed user_state ready_samples=0
  "$adb_binary" wait-for-device
  for _ in $(seq 1 90); do
    boot_completed="$($adb_binary shell getprop sys.boot_completed | tr -d '\r')"
    # ADB and getprop are available before ActivityManager registers its service.
    # Query its user state only after Android has published boot completion.
    user_state="NOT_QUERIED"
    if [[ "$boot_completed" == "1" ]]; then
      user_state="$($adb_binary shell am get-started-user-state 0 | tr -d '\r')"
    fi
    printf 'boot_completed=%s user_0=%s\n' "$boot_completed" "$user_state" >> "$report_directory/reboot-readiness.txt"
    if [[ "$boot_completed" == "1" && "$user_state" == "RUNNING_UNLOCKED" ]]; then
      ready_samples=$((ready_samples + 1))
      if (( ready_samples == 2 )); then return 0; fi
    else
      ready_samples=0
    fi
    sleep 2
  done
  return 1
}

case_all_apps_tcp_round_trip() {
  local ready="$harness_temporary/all-apps-server.ready"
  local port
  reset_study
  python3 tools/all_apps_fixture_server.py --port 0 --ready "$ready" &
  server_pid="$!"
  for _ in $(seq 1 50); do
    [[ -f "$ready" ]] && break
    kill -0 "$server_pid"
    sleep 0.1
  done
  test -f "$ready"
  port="$(cat "$ready")"
  [[ "$port" =~ ^[0-9]+$ ]]
  run_instrumentation \
    allAppsIncludesTheResearchAppAndForwardsThroughTheCappedVpn \
    cool.jacoblin.particeps.AllAppsTrafficShapingAndroidTest \
    -e traffic_test_endpoint "10.0.2.2:$port"
  authorize_vpn "$particeps_package"
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

case_fixed_profile_measurement() {
  local cap="$1" sequence="$2" profile envelope_asset live_pid before after measurement_status
  mkdir -p "$report_directory/applied-profiles"
  printf -v profile 'cap-%04d' "$cap"
  envelope_asset="app/src/androidTest/assets/host_fixed_${cap}_study_envelope.txt"
  provision_running_study "$envelope_asset"
  live_pid="$(capture_live_particeps_pid)"
  before="$report_directory/applied-profiles/fixed-$cap-before.json"
  after="$report_directory/applied-profiles/fixed-$cap-after.json"
  await_applied_profile "$profile" "$live_pid" "$envelope_asset" "$before"
  # Record the applied condition at both ends even when the byte-count gate fails. Keep errexit
  # inside the measurement, so a setup failure cannot be hidden by its later cleanup.
  set +e
  (trap case_cleanup EXIT; set -euo pipefail; run_saturation_measurement "$cap" "$sequence")
  measurement_status=$?
  set -e
  await_applied_profile "$profile" "$live_pid" "$envelope_asset" "$after"
  python3 tools/android_host_profile.py compare --before "$before" --after "$after"
  (( measurement_status == 0 ))
  reset_study
}

case_three_profile_throughput_and_control_bypass() {
  local sequence=0 cap
  for cap in 64 512 4096; do
    sequence=$((sequence + 1))
    case_fixed_profile_measurement "$cap" "$sequence"
  done
}

case_duplex_fixed() {
  local live_pid before after measurement_status profile_id cap_kbps="$duplex_cap_kbps"
  local envelope_asset="app/src/androidTest/assets/host_fixed_${cap_kbps}_study_envelope.txt"
  printf -v profile_id 'cap-%04d' "$cap_kbps"
  mkdir -p "$report_directory/applied-profiles"
  provision_running_study "$envelope_asset"
  live_pid="$(capture_live_particeps_pid)"
  before="$report_directory/applied-profiles/duplex-$cap_kbps-before.json"
  after="$report_directory/applied-profiles/duplex-$cap_kbps-after.json"
  await_applied_profile "$profile_id" "$live_pid" "$envelope_asset" "$before"
  set +e
  (trap case_cleanup EXIT; set -euo pipefail; run_duplex_measurement "$cap_kbps")
  measurement_status=$?
  set -e
  await_applied_profile "$profile_id" "$live_pid" "$envelope_asset" "$after"
  python3 tools/android_host_profile.py compare --before "$before" --after "$after"
  (( measurement_status == 0 ))
  reset_study
}

case_fixed_diagnostic() {
  case_fixed_profile_measurement "$fixed_cap_kbps" "$diagnostic_iteration"
}

prepare_fixed_diagnostic() {
  # The full suite removes this intentionally unselected shared-UID peer in its inventory case.
  # The focused lane must establish the same signed-target precondition before the first attempt.
  "$adb_binary" uninstall "$shared_peer_package" > "$harness_temporary/uninstall-diagnostic-peer.txt"
  grep -qx "Success" "$harness_temporary/uninstall-diagnostic-peer.txt"
}

case_dynamic_verified_profile_transitions() {
  local live_pid profile previous="" proof
  mkdir -p "$report_directory/applied-profiles"
  provision_running_study
  live_pid="$(capture_live_particeps_pid)"
  for profile in cap-0064 cap-0512 cap-4096; do
    proof="$report_directory/applied-profiles/dynamic-$profile.json"
    await_applied_profile "$profile" "$live_pid" "$host_envelope_asset" "$proof"
    if [[ -n "$previous" ]]; then
      python3 tools/android_host_profile.py compare --before "$previous" --after "$proof" --transition
    fi
    previous="$proof"
  done
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
      printf 'Host scenario failed: %s\n' "$name" >&2
      python3 -m tools.android_host_diagnostics --adb "$adb_binary" --serial "$device_serial" capture \
        --output "$report_directory/failure-$name" || true
      "$adb_binary" shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
      "$adb_binary" shell am force-stop "$competing_vpn_package" >/dev/null 2>&1 || true
      "$adb_binary" install --no-streaming -r -d -t "$target_a_base_apk" > "$harness_temporary/restore.txt" 2>&1 || true
      "$adb_binary" install --no-streaming -r -t "$target_b_apk" > "$harness_temporary/restore.txt" 2>&1 || true
      "$adb_binary" install --no-streaming -r -t "$shared_target_apk" > "$harness_temporary/restore.txt" 2>&1 || true
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

if [[ "$duplex_only" == true ]]; then
  prepare_fixed_diagnostic
  run_case "simultaneous_${duplex_cap_kbps}_upload_download_and_control_bypass" case_duplex_fixed
elif (( diagnostic_repetitions > 0 )); then
  prepare_fixed_diagnostic
  for diagnostic_iteration in $(seq 1 "$diagnostic_repetitions"); do
    run_case "fixed_${fixed_cap_kbps}_diagnostic_$diagnostic_iteration" case_fixed_diagnostic
    # A separate study and proof history for every bounded attempt, including failures.
    mv "$report_directory/applied-profiles" "$report_directory/applied-profiles-$diagnostic_iteration"
  done
else
run_case "all_apps_capped_tcp_round_trip" case_all_apps_tcp_round_trip
run_case "fixture_inventory_protocol_attempts_and_shared_uid" case_fixture_inventory_and_protocols
run_case "protocol_attempts_preserve_verified_vpn" case_protocol_matrix_through_verified_vpn
run_case "aggregate_64_512_4096_kbps_and_control_bypass" case_three_profile_throughput_and_control_bypass
run_case "simultaneous_512_upload_download_and_control_bypass" case_duplex_fixed
run_case "dynamic_profiles_advance_verified_epochs_without_process_restart" case_dynamic_verified_profile_transitions
run_case "process_kill_recovers_safety_paused" case_process_kill_recovery
run_case "reboot_recovers_safety_paused" case_reboot_recovery
run_case "target_replace_safety_pauses" case_target_replace
run_case "target_uninstall_safety_pauses" case_target_uninstall
run_case "unselected_shared_uid_peer_install_safety_pauses" case_shared_uid_peer_install
run_case "competing_vpn_revokes_and_safety_pauses" case_competing_vpn_revoke
run_case "underlying_network_handover_remains_running" case_underlying_network_handover
run_case "api37_local_network_permission_revoke_safety_pauses" case_api37_local_network_permission_revoke
fi

junit_temporary="$junit_file.tmp"
{
  printf '<?xml version="1.0" encoding="UTF-8"?>\n'
  printf '<testsuite name="android-host-harness" tests="%d" failures="%d" skipped="%d">\n' \
    "${#case_names[@]}" "$failure_count" "$skipped_count"
  for index in "${!case_names[@]}"; do
    printf '  <testcase classname="particeps.android.host" name="%s" time="%s">' \
      "${case_names[$index]}" "${case_durations[$index]}"
    if [[ "${case_results[$index]}" == "failed" ]]; then
      printf '<failure message="Host scenario failed; see bounded synthetic-device diagnostics." />'
    elif [[ "${case_results[$index]}" == "not_applicable" ]]; then
      printf '<skipped message="Requires the Android 17 local-network runtime permission (API 37)." />'
    fi
    printf '</testcase>\n'
  done
  printf '</testsuite>\n'
} > "$junit_temporary"
mv "$junit_temporary" "$junit_file"

(( failure_count == 0 ))
