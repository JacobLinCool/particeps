import hashlib
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.android_host_profile import compare_observations, fixture_configuration, observation_from_query, verified_profile


ROOT = Path(__file__).resolve().parents[2]


class AppliedProfileObservationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.configuration = fixture_configuration(ROOT / "app/src/androidTest/assets/host_harness_study_envelope.txt")

    def observation(self, index=0) -> dict:
        profile = self.configuration["traffic_shaping"]["profiles"][index]
        return {
            "schema_version": 1, "status": "VERIFIED", "state": "RUNNING", "admission_open": True,
            "revision": 4 + index * 2, "active_running_elapsed_millis": index * 30_000,
            "condition_epoch_id": f"00000000-0000-4000-8000-{index + 1:012d}",
            "applied_resource_vector_sha256": str(index + 1) * 64,
            "profile_id": profile["id"], "resource_generation": index + 1,
            "applied_profile_sha256": hashlib.sha256(json.dumps(profile, sort_keys=True, separators=(",", ":")).encode()).hexdigest(),
        }

    def test_only_expected_signed_applied_profile_is_ready(self) -> None:
        self.assertTrue(verified_profile(self.observation(1), self.configuration, "cap-0512"))
        self.assertFalse(verified_profile(self.observation(0), self.configuration, "cap-0512"))
        with self.assertRaisesRegex(ValueError, "advanced beyond"):
            verified_profile(self.observation(2), self.configuration, "cap-0512")

    def test_pending_is_not_success_and_fail_closed_state_is_not_retryable(self) -> None:
        pending = self.observation() | {"status": "PENDING", "admission_open": False, "profile_id": None, "applied_profile_sha256": None, "resource_generation": None}
        self.assertFalse(verified_profile(pending, self.configuration, "cap-0064"))
        for state in ("PAUSED", "ACTIVATING", "WITHDRAWN", None):
            with self.subTest(state=state), self.assertRaises(ValueError):
                verified_profile(pending | {"state": state}, self.configuration, "cap-0064")

    def test_claimed_verified_state_requires_digest_epoch_admission_and_exact_integer_fields(self) -> None:
        for field, value in (
            ("applied_profile_sha256", "0" * 64), ("applied_resource_vector_sha256", "not-a-digest"),
            ("condition_epoch_id", None), ("profile_id", "unsigned-profile"), ("admission_open", False),
            ("resource_generation", "1"), ("resource_generation", 0), ("resource_generation", 2**64),
            ("revision", 0), ("revision", True), ("active_running_elapsed_millis", -1), ("schema_version", True),
        ):
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                verified_profile(self.observation() | {field: value}, self.configuration, "cap-0064")
        self.assertTrue(verified_profile(self.observation() | {"resource_generation": 2**64 - 1}, self.configuration, "cap-0064"))

    def test_fixed_fixture_rejects_an_observation_from_another_speed(self) -> None:
        fixed = fixture_configuration(ROOT / "app/src/androidTest/assets/host_fixed_512_study_envelope.txt")
        with self.assertRaisesRegex(ValueError, "absent from the signed fixture"):
            verified_profile(self.observation(0), fixed, "cap-0512")

    def test_stable_rate_gate_requires_identical_verified_epoch_and_generation(self) -> None:
        before = self.observation(1)
        after = before | {"revision": 100, "active_running_elapsed_millis": 95_000}
        compare_observations(before, after, transition=False)
        for field, changed in (
            ("condition_epoch_id", "00000000-0000-4000-8000-000000000009"),
            ("applied_resource_vector_sha256", "f" * 64), ("profile_id", "cap-4096"),
            ("applied_profile_sha256", "f" * 64), ("resource_generation", 3),
        ):
            with self.subTest(field=field), self.assertRaises(ValueError):
                compare_observations(before, after | {field: changed}, transition=False)

    def test_dynamic_change_requires_new_epoch_and_advancing_revision_and_generation(self) -> None:
        before, after = self.observation(0), self.observation(1)
        compare_observations(before, after, transition=True)
        for field in before:
            if field in ("condition_epoch_id", "applied_resource_vector_sha256", "profile_id", "applied_profile_sha256", "resource_generation", "revision"):
                with self.subTest(field=field), self.assertRaises(ValueError):
                    compare_observations(before, after | {field: before[field]}, transition=True)
        for transition in (False, True):
            with self.subTest(transition=transition), self.assertRaisesRegex(ValueError, "backwards"):
                compare_observations(after, before, transition=transition)

    def test_query_parser_requires_a_single_json_object(self) -> None:
        value = self.observation()
        valid = json.dumps(value)
        self.assertEqual(value, observation_from_query(valid))
        for output in (valid + valid, "RUNNING:5", "null", "[]", '"text"'):
            with self.subTest(output=output), self.assertRaises(ValueError):
                observation_from_query(output)

    def test_envelope_reader_rejects_truncated_or_trailing_payload(self) -> None:
        import base64
        original = base64.b64decode((ROOT / "app/src/androidTest/assets/host_harness_study_envelope.txt").read_text())
        with tempfile.TemporaryDirectory() as temporary:
            asset = Path(temporary) / "asset.txt"
            for malformed in (original[:-1], original + b"x", b"otherfmt" + original[8:]):
                asset.write_bytes(base64.b64encode(malformed))
                with self.assertRaises(ValueError):
                    fixture_configuration(asset)


class ProfileHarnessControlFlowTest(unittest.TestCase):
    def function(self, name: str) -> str:
        script = (ROOT / "tools/android-host-harness.sh").read_text()
        declaration = f"{name}() {{"
        return declaration + script.split(declaration, 1)[1].split("\n}", 1)[0] + "\n}\n"

    def test_diagnostic_arguments_do_not_change_the_default_release_measurement(self) -> None:
        source = (ROOT / "tools/android-host-harness.sh").read_text()
        parse = source[source.index("skip_build=false"):source.index('adb_binary=')]
        script = "set -euo pipefail\n" + parse + '\nprintf "%s|%s|%s|%s\\n" "$fixed_cap_kbps" "$diagnostic_repetitions" "$measurement_duration_seconds" "$capture_throughput_diagnostics"\n'
        for arguments, expected in (
            ([], "0|0|60|false"),
            (["--capture-throughput-diagnostics"], "0|0|60|true"),
            (["--duplex-only"], "0|0|60|false"),
            (["--duplex-only", "--capture-throughput-diagnostics"], "0|0|60|true"),
            (["--fixed-cap-kbps", "64", "--repetitions", "5"], "64|5|60|true"),
            (["--fixed-cap-kbps", "512", "--repetitions", "1", "--duration-seconds", "300"], "512|1|300|true"),
            (["--no-throughput-diagnostics", "--fixed-cap-kbps", "4096", "--repetitions", "1", "--duration-seconds", "300"], "4096|1|300|false"),
            (["--duplex-only", "--fixed-cap-kbps", "64", "--repetitions", "1"], None),
            (["--duplex-only", "--duration-seconds", "300"], None),
            (["--duration-seconds", "300"], None),
            (["--fixed-cap-kbps", "64"], None),
            (["--repetitions", "1"], None),
            (["--fixed-cap-kbps", "65", "--repetitions", "1"], None),
            (["--fixed-cap-kbps", "64", "--repetitions", "6"], None),
            (["--fixed-cap-kbps", "64", "--repetitions", "1", "--duration-seconds", "600"], None),
            (["--fixed-512-repetitions", "1"], None),
            (["--fixed-512-duration-seconds", "300"], None),
            (["--capture-throughput-diagnostics", "--no-throughput-diagnostics"], None),
            (["--duplex-cap-kbps", "64"], None),
            (["--duplex-cap-kbps", "512"], None),
            (["--duplex-only", "--duplex-cap-kbps", "4096"], None),
            (["--duplex-only", "--duplex-cap-kbps"], None),
            (["--duplex-only", "--duplex-cap-kbps", "64", "--duplex-cap-kbps", "512"], None),
        ):
            with self.subTest(arguments=arguments):
                result = subprocess.run(["bash", "-c", script, "harness", *arguments], capture_output=True, text=True)
                if expected is None:
                    self.assertEqual(2, result.returncode, result.stderr)
                else:
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual(expected, result.stdout.strip())

    def test_explicit_duplex_cap_is_confined_to_focused_lane(self) -> None:
        source = (ROOT / "tools/android-host-harness.sh").read_text()
        parse = source[source.index("skip_build=false"):source.index('adb_binary=')]
        script = "set -euo pipefail\n" + parse + '\nprintf "%s|%s|%s\\n" "$duplex_cap_kbps" "$measurement_duration_seconds" "$capture_throughput_diagnostics"\n'
        for arguments, expected in (
            ([], "512|60|false"),
            (["--duplex-only"], "512|60|false"),
            (["--duplex-only", "--duplex-cap-kbps", "64"], "64|60|false"),
            (["--duplex-only", "--duplex-cap-kbps", "512"], "512|60|false"),
            (["--duplex-only", "--duplex-cap-kbps", "64", "--capture-throughput-diagnostics"], "64|60|true"),
        ):
            result = subprocess.run(["bash", "-c", script, "harness", *arguments], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(expected, result.stdout.strip())

    def test_diagnostic_lane_requires_shared_uid_peer_removal_before_measurement(self) -> None:
        for success in (True, False):
            with self.subTest(success=success), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                adb = directory / "adb"
                calls = directory / "calls.txt"
                adb.write_text('#!/usr/bin/env bash\nprintf "%s\\n" "$*" > "$calls"\necho ' + ("Success" if success else "Failure") + '\n')
                adb.chmod(0o755)
                script = "set -euo pipefail\n" + self.function("prepare_fixed_diagnostic") + '\nprepare_fixed_diagnostic\necho measure >> "$calls"\n'
                result = subprocess.run(["bash", "-s"], input=script, text=True, capture_output=True, env={**os.environ, "adb_binary": str(adb), "harness_temporary": temporary, "shared_peer_package": "fixture.peer", "calls": str(calls)})
                self.assertEqual(success, result.returncode == 0)
                self.assertEqual(["uninstall fixture.peer"] + (["measure"] if success else []), calls.read_text().splitlines())

    def test_fixed_diagnostic_routes_requested_cap_and_attempt_to_signed_profile_measurement(self) -> None:
        for cap in (64, 512, 4096):
            script = "set -euo pipefail\n" + self.function("case_fixed_diagnostic") + """
case_fixed_profile_measurement() { printf '%s|%s' "$1" "$2"; }
case_fixed_diagnostic
"""
            result = subprocess.run(["bash", "-s"], input=script, text=True, capture_output=True,
                env={**os.environ, "fixed_cap_kbps": str(cap), "diagnostic_iteration": "3"})
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(f"{cap}|3", result.stdout)

    def test_diagnostic_monitor_exit_is_retained_without_aborting_cleanup(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            result_file = directory / "result.json"
            script = 'set -euo pipefail\n' + self.function("stop_diagnostics") + '\n(exit 7) &\ndiagnostics_pid=$!\nstop_diagnostics\n'
            result = subprocess.run(["bash", "-s"], input=script, text=True, capture_output=True, env={**os.environ, "diagnostics_result": str(result_file), "diagnostics_stop": str(directory / "stop")})
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual({"monitor_exit_code": 7}, json.loads(result_file.read_text()))

    def test_measurement_end_harvest_runs_once_before_cleanup_and_preserves_failure(self) -> None:
        for server_status, launch_status, capture_status in ((0, 0, 0), (1, 0, 0), (0, 23, 0), (1, 0, 37), (0, 0, 37)):
            with self.subTest(server=server_status, launch=launch_status, capture=capture_status), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                calls = directory / "calls.txt"
                script = "set -euo pipefail\n" + "".join(self.function(name) for name in (
                    "capture_fixture_progress", "case_cleanup", "run_saturation_measurement",
                )) + """
server_pid=""; progress_capture_output=""; progress_capture_mode=""
stop_diagnostics() { :; }
stop_traffic_fixtures() { echo stop >> "$calls"; }
fake_adb() {
  if [[ "$*" == *'am start'* ]]; then
    echo launch >> "$calls"
    return "$launch_status"
  fi
}
python3() {
  if [[ "$1" == tools/android_fixture_server.py ]]; then
    local output="" ready=""
    while (( $# )); do
      case "$1" in --output) output="$2"; shift;; --ready) ready="$2"; shift;; esac
      shift
    done
    echo '{}' > "$output"; touch "$ready"; return "$server_status"
  elif [[ "$*" == *' progress '* ]]; then
    echo progress >> "$calls"
    echo synthetic-capture-error >&2
    return "$capture_status"
  elif [[ "$*" == *' capture '* ]]; then
    echo failure-capture >> "$calls"
  else
    return 99
  fi
}
trap case_cleanup EXIT
run_saturation_measurement 64 1
"""
                result = subprocess.run(["bash", "-s"], input=script, text=True, capture_output=True, timeout=5, env={
                    **os.environ, "calls": str(calls), "server_status": str(server_status), "launch_status": str(launch_status),
                    "capture_status": str(capture_status), "harness_temporary": temporary, "report_directory": temporary,
                    "adb_binary": "fake_adb", "device_serial": "emulator-5584", "target_a_package": "targeta",
                    "target_b_package": "targetb", "control_package": "control", "traffic_activity": "TrafficActivity",
                    "capture_throughput_diagnostics": "false", "measurement_duration_seconds": "60", "metrics_file": str(directory / "metrics"),
                })
                self.assertEqual(launch_status or server_status, result.returncode, result.stderr)
                recorded = calls.read_text().splitlines()
                self.assertEqual(1, recorded.count("progress"))
                self.assertTrue(all(item != "stop" for item in recorded[1:recorded.index("progress")]))
                self.assertEqual("stop", recorded[-1])
                receipt = directory / "throughput-diagnostics/measurement-1-progress.json.capture-result.json"
                self.assertEqual({"capture_exit_code": capture_status}, json.loads(receipt.read_text()))
                self.assertEqual("synthetic-capture-error\n", receipt.with_name("measurement-1-progress.json.stderr").read_text())

    def test_inventory_installs_its_peer_before_uid_checks_and_propagates_install_failure(self) -> None:
        for installed in (True, False):
            with self.subTest(installed=installed), tempfile.TemporaryDirectory() as temporary:
                calls = Path(temporary) / "calls.txt"
                script = "set -euo pipefail\n" + self.function("install_apk") + self.function("case_fixture_inventory_and_protocols") + """
fake_adb() {
  echo "$*" >> "$calls"
  if [[ "$1" == install ]]; then
    echo "$install_result"
    [[ "$install_result" == Success ]] && touch "$harness_temporary/peer-installed"
    return 0
  fi
  echo Success
}
package_uid() {
  test -f "$harness_temporary/peer-installed"
  echo "uid|$1" >> "$calls"
  if [[ "$1" == control ]]; then echo 200; else echo 100; fi
}
run_smoke_fixture() { echo "smoke|$1" >> "$calls"; }
case_fixture_inventory_and_protocols
"""
                result = subprocess.run(["bash", "-s"], input=script, text=True, capture_output=True, env={
                    **os.environ, "calls": str(calls), "install_result": "Success" if installed else "Failure",
                    "harness_temporary": temporary, "adb_binary": "fake_adb", "shared_peer_apk": "peer.apk",
                    "shared_target_package": "sharedtarget", "shared_peer_package": "peer", "control_package": "control",
                    "target_a_package": "targeta", "target_b_package": "targetb",
                })
                self.assertEqual(installed, result.returncode == 0, result.stderr)
                recorded = calls.read_text().splitlines()
                self.assertEqual("install --no-streaming -r -d -t peer.apk", recorded[0])
                self.assertEqual(10 if installed else 1, len(recorded))
                if installed:
                    self.assertEqual("uninstall peer", recorded[-1])

    def test_duplex_end_harvest_does_not_sample_download_target_or_mask_verdict(self) -> None:
        for validation_status in (0, 1):
            with self.subTest(validation=validation_status), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                calls = directory / "calls.txt"
                script = "set -euo pipefail\n" + "".join(self.function(name) for name in (
                    "capture_fixture_progress", "case_cleanup", "run_duplex_measurement",
                )) + """
server_pid=""; progress_capture_output=""; progress_capture_mode=""
stop_diagnostics() { :; }
stop_traffic_fixtures() { echo stop >> "$calls"; }
fake_adb() { echo "$*" >> "$calls"; echo '{}'; }
python3() {
  if [[ "$1" == -c ]]; then echo 00000000-0000-4000-8000-000000000001; return; fi
  local args="$*" output="" ready=""
  while (( $# )); do
    case "$1" in --output) output="$2"; shift;; --ready) ready="$2"; shift;; esac
    shift
  done
  if [[ "$args" == *' serve '* ]]; then
    echo '{}' > "$output"; touch "$ready"
  elif [[ "$args" == *' progress '* ]]; then
    echo "progress|$args" >> "$calls"; return 37
  elif [[ "$args" == *' validate '* ]]; then
    echo '{}' > "$output"; return "$validation_status"
  elif [[ "$args" != *' capture '* ]]; then return 99; fi
}
trap case_cleanup EXIT
run_duplex_measurement 512
"""
                result = subprocess.run(["bash", "-s"], input=script, text=True, capture_output=True, timeout=5, env={
                    **os.environ, "calls": str(calls), "validation_status": str(validation_status),
                    "harness_temporary": temporary, "report_directory": temporary, "adb_binary": "fake_adb",
                    "device_serial": "emulator-5584", "target_a_package": "targeta", "target_b_package": "targetb",
                    "control_package": "control", "traffic_activity": "TrafficActivity", "capture_throughput_diagnostics": "false",
                    "metrics_file": str(directory / "metrics"),
                })
                self.assertEqual(validation_status, result.returncode, result.stderr)
                recorded = calls.read_text().splitlines()
                progress = [item for item in recorded if item.startswith("progress|")]
                self.assertEqual(1, len(progress))
                self.assertIn("--traffic-mode duplex", progress[0])
                self.assertEqual("stop", recorded[-1])
                self.assertNotIn("shell run-as targetb rm -f files/saturation-progress.json", recorded)
                for package in ("targeta", "control"):
                    self.assertIn(f"shell run-as {package} rm -f files/saturation-progress.json", recorded)
                receipt = directory / "duplex/saturation-progress.json.capture-result.json"
                self.assertEqual({"capture_exit_code": 37}, json.loads(receipt.read_text()))

    def test_rate_failure_keeps_after_proof_without_hiding_the_failed_command(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            script = "set -euo pipefail\n" + self.function("case_fixed_profile_measurement") + self.function("case_three_profile_throughput_and_control_bypass") + """
provision_running_study() { echo provision >> "$calls"; }
capture_live_particeps_pid() { echo 42; }
await_applied_profile() { echo proof >> "$calls"; }
case_cleanup() { :; }
run_saturation_measurement() {
  echo measure >> "$calls"
  false
  echo accidentally-ignored-errexit >> "$calls"
}
python3() { echo compare >> "$calls"; }
reset_study() { echo reset >> "$calls"; }
case_three_profile_throughput_and_control_bypass
"""
            calls = Path(temporary) / "calls.txt"
            result = subprocess.run(["bash", "-s"], input=script, text=True, capture_output=True,
                                    env={**os.environ, "report_directory": temporary, "calls": str(calls)})
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(["provision", "proof", "measure", "proof", "compare"], calls.read_text().splitlines())

    def test_applied_profile_query_rejects_process_replacement_before_parsing_proof(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            pid = directory / "pid.txt"
            pid.write_text("42\n")
            adb = directory / "adb"
            adb.write_text('#!/usr/bin/env bash\necho 43 > "$pid_file"\necho completed\n')
            adb.chmod(0o755)
            proof = directory / "proof.json"
            script = "set -euo pipefail\n" + self.function("await_applied_profile") + """
particeps_pid() { cat "$pid_file"; }
host_control() { echo 43 > "$pid_file"; echo completed; }
python3() { echo should-not-parse > "$proof_file"; }
await_applied_profile cap-0064 42 unused "$proof_file"
"""
            result = subprocess.run(["bash", "-s"], input=script, text=True, capture_output=True, env={
                **os.environ, "harness_temporary": temporary, "adb_binary": str(adb),
                "pid_file": str(pid), "proof_file": str(proof), "host_query_action": "fixture", "particeps_package": "fixture",
            })
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(proof.exists())

    def test_duplex_failure_keeps_after_proof_and_does_not_reset_before_evidence(self) -> None:
        for cap in (64, 512):
            with self.subTest(cap=cap), tempfile.TemporaryDirectory() as temporary:
                script = f"set -euo pipefail\nduplex_cap_kbps={cap}\n" + self.function("case_duplex_fixed") + """
provision_running_study() { echo "provision|$1" >> "$calls"; }
capture_live_particeps_pid() { echo 42; }
await_applied_profile() { echo "proof|$1|$2|$3|$4" >> "$calls"; }
case_cleanup() { :; }
run_duplex_measurement() {
  echo "measure|$1" >> "$calls"
  false
  echo accidentally-ignored-errexit >> "$calls"
}
python3() { echo compare >> "$calls"; }
reset_study() { echo reset >> "$calls"; }
case_duplex_fixed
"""
                calls = Path(temporary) / "calls.txt"
                result = subprocess.run(["bash", "-s"], input=script, text=True, capture_output=True,
                    env={**os.environ, "report_directory": temporary, "calls": str(calls)})
                self.assertNotEqual(0, result.returncode)
                asset = f"app/src/androidTest/assets/host_fixed_{cap}_study_envelope.txt"
                proof = f"proof|cap-{cap:04d}|42|{asset}|{temporary}/applied-profiles/duplex-{cap}"
                self.assertEqual([f"provision|{asset}", f"{proof}-before.json", f"measure|{cap}",
                                  f"{proof}-after.json", "compare"], calls.read_text().splitlines())


if __name__ == "__main__":
    unittest.main()
