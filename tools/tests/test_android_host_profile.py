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
        ):
            with self.subTest(arguments=arguments):
                result = subprocess.run(["bash", "-c", script, "harness", *arguments], capture_output=True, text=True)
                if expected is None:
                    self.assertEqual(2, result.returncode, result.stderr)
                else:
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
        with tempfile.TemporaryDirectory() as temporary:
            script = "set -euo pipefail\n" + self.function("case_duplex_fixed_512") + """
provision_running_study() { echo provision >> "$calls"; }
capture_live_particeps_pid() { echo 42; }
await_applied_profile() { echo proof >> "$calls"; }
case_cleanup() { :; }
run_duplex_measurement() {
  echo measure >> "$calls"
  false
  echo accidentally-ignored-errexit >> "$calls"
}
python3() { echo compare >> "$calls"; }
reset_study() { echo reset >> "$calls"; }
case_duplex_fixed_512
"""
            calls = Path(temporary) / "calls.txt"
            result = subprocess.run(["bash", "-s"], input=script, text=True, capture_output=True,
                env={**os.environ, "report_directory": temporary, "calls": str(calls)})
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(["provision", "proof", "measure", "proof", "compare"], calls.read_text().splitlines())


if __name__ == "__main__":
    unittest.main()
