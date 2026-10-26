import base64
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.android_host_profile import fixture_configuration
from tools.generate_android_host_studies import configurations


ROOT = Path(__file__).resolve().parents[2]

class AndroidHostHarnessContractTest(unittest.TestCase):
    def run_host_instrumentation_result(
        self, output: str,
    ) -> tuple[subprocess.CompletedProcess[str], str]:
        harness = (ROOT / "tools/android-host-harness.sh").read_text()
        declaration = "run_instrumentation() {"
        function_body = harness.split(declaration, 1)[1].split("\n}", 1)[0]
        script = (
            "set -euo pipefail\n"
            f"{declaration}{function_body}\n}}\n"
            "run_instrumentation transfer AllAppsClass -e traffic_test_endpoint 10.0.2.2:18766\n"
        )
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            adb = directory / "adb"
            result_file = directory / "result.txt"
            log = directory / "adb.log"
            result_file.write_text(output)
            adb.write_text(
                '#!/usr/bin/env bash\n'
                'printf "%s\\n" "$*" > "$FAKE_ADB_LOG"\n'
                'cat "$FAKE_ADB_RESULT"\n'
            )
            adb.chmod(0o755)
            environment = os.environ.copy()
            environment.update({
                "adb_binary": str(adb),
                "harness_temporary": temporary,
                "report_directory": temporary,
                "test_class": "UnusedDefaultClass",
                "test_runner": "fixture/Runner",
                "FAKE_ADB_LOG": str(log),
                "FAKE_ADB_RESULT": str(result_file),
            })
            result = subprocess.run(
                ["bash", "-s"],
                input=script,
                env=environment,
                capture_output=True,
                text=True,
            )
            retained = list(directory.glob("instrumentation-transfer-*"))
            self.assertEqual(1, len(retained))
            self.assertEqual(output.replace("\r", ""), retained[0].read_text())
            return result, log.read_text()

    def test_host_gate_requires_a_completed_successful_instrumentation_test(self) -> None:
        result, commands = self.run_host_instrumentation_result(
            "INSTRUMENTATION_STATUS_CODE: 1\r\n"
            "INSTRUMENTATION_STATUS_CODE: 0\r\n"
            "OK (1 test)\r\n"
            "INSTRUMENTATION_CODE: -1\r\n",
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("-e class AllAppsClass#transfer", commands)
        self.assertIn("-e traffic_test_endpoint 10.0.2.2:18766", commands)

    def test_host_gate_rejects_skips_failures_and_summary_only_results(self) -> None:
        for output in (
            "INSTRUMENTATION_STATUS_CODE: -3\nOK (1 test)\n",
            "INSTRUMENTATION_STATUS_CODE: -4\nOK (1 test)\n",
            "INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_STATUS_CODE: -2\nOK (1 test)\n",
            "INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_FAILED: test process died\n",
            "OK (1 test)\n",
        ):
            with self.subTest(output=output):
                result, _ = self.run_host_instrumentation_result(output)
                self.assertNotEqual(0, result.returncode)

    def test_reboot_requires_two_consecutive_completed_unlocked_samples(self) -> None:
        harness = (ROOT / "tools/android-host-harness.sh").read_text()
        declaration = "wait_for_boot() {"
        body = harness.split(declaration, 1)[1].split("\n}", 1)[0]
        for states, expected in (
            ([('0', 'SERVICE_NOT_READY'), ('1', 'RUNNING_UNLOCKED'), ('1', 'RUNNING_UNLOCKED')], 0),
            ([('1', 'RUNNING_LOCKED'), ('1', 'RUNNING_UNLOCKED'), ('1', 'RUNNING_UNLOCKED')], 0),
            ([('1', 'RUNNING_UNLOCKED'), ('0', 'RUNNING_UNLOCKED'), ('1', 'RUNNING_UNLOCKED')], 1),
            ([('1', 'RUNNING_LOCKED')] * 3, 1),
            ([('1', 'unknown')] * 3, 1),
        ):
            with self.subTest(states=states), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                fixture = directory / 'states.json'
                fixture.write_text(json.dumps(states))
                adb = directory / 'adb'
                adb.write_text(
                    '#!/usr/bin/env python3\nimport json, os, pathlib, sys\n'
                    'p=pathlib.Path(os.environ["FAKE_STATES"]); states=json.loads(p.read_text())\n'
                    'i=p.with_suffix(".index"); n=int(i.read_text()) if i.exists() else 0\n'
                    'if sys.argv[1:]==["wait-for-device"]: pass\n'
                    'elif sys.argv[1:]==["shell","getprop","sys.boot_completed"]:\n'
                    ' print(states[n][0]); i.write_text(str(n+1))\n'
                    'elif sys.argv[1:]==["shell","am","get-started-user-state","0"]:\n'
                    ' if states[n-1][0]!="1": raise SystemExit("activity service is not registered")\n'
                    ' print(states[n-1][1])\n'
                    ' with p.with_suffix(".queries").open("a") as out: out.write(str(n-1)+"\\n")\n'
                    'else: raise SystemExit(2)\n'
                )
                adb.chmod(0o755)
                script = (
                    'set -euo pipefail\nsleep() { :; }\nseq() { printf "1\\n2\\n3\\n"; }\n'
                    f'{declaration}{body}\n}}\nwait_for_boot\n'
                )
                result = subprocess.run(['bash', '-s'], input=script, text=True, capture_output=True,
                    env={**os.environ, 'adb_binary': str(adb), 'report_directory': temporary, 'FAKE_STATES': str(fixture)})
                self.assertEqual(expected, result.returncode, result.stderr)
                self.assertEqual(3, len((directory / 'reboot-readiness.txt').read_text().splitlines()))
                self.assertEqual([str(i) for i, state in enumerate(states) if state[0] == '1'],
                                 fixture.with_suffix('.queries').read_text().splitlines())

    def test_reboot_does_not_swallow_activity_errors_after_boot_completed(self) -> None:
        harness = (ROOT / "tools/android-host-harness.sh").read_text()
        declaration = "wait_for_boot() {"
        body = harness.split(declaration, 1)[1].split("\n}", 1)[0]
        with tempfile.TemporaryDirectory() as temporary:
            adb = Path(temporary) / 'adb'
            adb.write_text(
                '#!/usr/bin/env bash\n'
                'case "$*" in\n'
                ' wait-for-device) exit 0;;\n'
                ' "shell getprop sys.boot_completed") echo 1;;\n'
                ' "shell am get-started-user-state 0") echo "unexpected activity failure" >&2; exit 23;;\n'
                ' *) exit 2;;\nesac\n'
            )
            adb.chmod(0o755)
            result = subprocess.run(['bash', '-s'], input=f'set -euo pipefail\n{declaration}{body}\n}}\nwait_for_boot\n',
                text=True, capture_output=True, env={**os.environ, 'adb_binary': str(adb), 'report_directory': temporary})
            self.assertEqual(23, result.returncode)
            self.assertIn('unexpected activity failure', result.stderr)
            self.assertFalse((Path(temporary) / 'reboot-readiness.txt').exists())

    def run_api37_classifier(
        self,
        evidence: str,
        result_label: str | None = None,
    ) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as temporary:
            evidence_file = Path(temporary) / "evidence.txt"
            evidence_file.write_text(evidence)
            command = [
                "python3",
                str(ROOT / "tools/classify_api37_emulator_failure.py"),
                "--platform-evidence",
                str(evidence_file),
                "--transport-evidence",
                str(evidence_file),
            ]
            if result_label is not None:
                command.extend(("--result-label", result_label))
            command.append(str(evidence_file))
            return subprocess.run(
                command,
                cwd=ROOT,
                check=False,
                capture_output=True,
                text=True,
            )

    def test_api_37_classifier_accepts_only_exact_platform_signature(self) -> None:
        result = self.run_api37_classifier(
            "surfaceflinger /vendor/lib64/hw/mapper.ranchu.so\n"
            "Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma\n"
            "error: device offline\n",
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("QUARANTINED", result.stdout)

    def test_api_37_classifier_requires_explicit_current_phase_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            evidence = Path(temporary) / "evidence.txt"
            evidence.write_text(
                "surfaceflinger /vendor/lib64/hw/mapper.ranchu.so\n"
                "Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma\n"
                "transport error: device offline\n",
            )
            result = subprocess.run(
                ["python3", str(ROOT / "tools/classify_api37_emulator_failure.py"), str(evidence)],
                capture_output=True,
                text=True,
            )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("requires --platform-evidence", result.stderr)

    def test_api_37_classifier_keeps_product_and_test_failures_blocking(self) -> None:
        result = self.run_api37_classifier(
            "surfaceflinger /vendor/lib64/hw/mapper.ranchu.so\n"
            "Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma\n"
            "error: device offline\n"
            "java.lang.AssertionError: Particeps result did not match\n",
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("blocking", result.stdout)

    def test_api_37_classifier_recognizes_exact_service_restart_fallout(self) -> None:
        result = self.run_api37_classifier(
            "surfaceflinger /vendor/lib64/hw/mapper.ranchu.so\n"
            "Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma\n"
            "cmd: Can't find service: package\n",
            result_label="RETRYABLE",
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RETRYABLE", result.stdout)
        self.assertNotIn("QUARANTINED", result.stdout)

    def run_instrumentation_launcher(
        self,
        fail_install: bool = False,
        fail_instrumentation: bool = False,
        suite: str | None = None,
    ) -> tuple[subprocess.CompletedProcess[str], str]:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            adb = directory / "adb"
            log = directory / "adb.log"
            adb.write_text(
                """#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$*" >> "$FAKE_ADB_LOG"
case "$1" in
  get-state)
    printf 'device\\n'
    ;;
  uninstall)
    exit 1
    ;;
  install)
    if [[ "${FAKE_ADB_FAIL_INSTALL:-0}" == 1 ]]; then
      printf 'Failure [INSTALL_FAILED_TEST]\\n'
      exit 1
    fi
    printf 'fixture.apk: 1 file pushed, 0 skipped.\\nPerforming Push Install\\nSuccess\\n'
    ;;
  logcat)
    printf 'synthetic crash buffer\\n'
    ;;
  shell)
    if [[ "${FAKE_ADB_FAIL_INSTRUMENT:-0}" == 1 ]]; then
      printf 'FAILURES!!!\\nTests run: 1, Failures: 1\\n'
    elif [[ "$*" == *Api37CompatibilityInstrumentation* ]]; then
      printf 'INSTRUMENTATION_RESULT: stream=API 37 compatibility checks passed.\\nINSTRUMENTATION_CODE: -1\\n'
    else
      printf 'OK (1 test)\\n'
    fi
    ;;
esac
"""
            )
            adb.chmod(0o755)
            environment = os.environ.copy()
            environment["ADB"] = str(adb)
            environment["FAKE_ADB_LOG"] = str(log)
            if fail_install:
                environment["FAKE_ADB_FAIL_INSTALL"] = "1"
            if fail_instrumentation:
                environment["FAKE_ADB_FAIL_INSTRUMENT"] = "1"
            command = [str(ROOT / "tools/android-instrumentation-ci.sh")]
            if suite is not None:
                command.append(f"--suite={suite}")
            result = subprocess.run(
                command,
                cwd=ROOT,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )
            return result, log.read_text()

    def test_api_37_instrumentation_launcher_uses_non_streaming_installs(self) -> None:
        result, commands = self.run_instrumentation_launcher()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(3, commands.count("install --no-streaming"))
        self.assertEqual(2, commands.count("shell am instrument -w -r"))

    def test_api_37_compatibility_uses_one_self_targeted_product_apk(self) -> None:
        result, commands = self.run_instrumentation_launcher(suite="api37-compatibility")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, commands.count("install --no-streaming"))
        self.assertEqual(1, commands.count("shell am instrument -w -r"))
        self.assertIn("Api37CompatibilityInstrumentation", commands)

    def test_api_37_instrumentation_launcher_reports_install_failures(self) -> None:
        result, commands = self.run_instrumentation_launcher(fail_install=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Failure [INSTALL_FAILED_TEST]", result.stderr)
        self.assertIn("logcat -b crash -d -v brief", commands)

    def test_api_37_instrumentation_launcher_rejects_test_failures(self) -> None:
        result, _ = self.run_instrumentation_launcher(fail_instrumentation=True)
        self.assertNotEqual(0, result.returncode)

    def test_signed_study_uses_three_profiles_and_event_driven_active_time_binding(self) -> None:
        configuration = json.loads((ROOT / "test-fixtures/host-study.json").read_text())
        shaping = configuration["traffic_shaping"]
        self.assertEqual(
            [
                "cool.jacoblin.particeps.fixture.sharedtarget",
                "cool.jacoblin.particeps.fixture.targeta",
                "cool.jacoblin.particeps.fixture.targetb",
            ],
            shaping["target_packages"],
        )
        self.assertEqual(
            [("cap-0064", 64), ("cap-0512", 512), ("cap-4096", 4096)],
            [(profile["id"], profile["uplink_kbps"]) for profile in shaping["profiles"]],
        )
        binding = next(item for item in configuration["automations"] if item["id"] == "bind-traffic-shaping")
        self.assertEqual("cap-0064", binding["default_profile_id"])
        self.assertEqual([60, 30], [case["condition"]["duration_seconds"] for case in binding["cases"]])
        self.assertTrue(all(case["condition"]["clock"] == "ACTIVE_RUNNING_TIME" for case in binding["cases"]))

        envelope = base64.b64decode(
            (ROOT / "app/src/androidTest/assets/host_harness_study_envelope.txt").read_text().strip(),
            validate=True,
        )
        self.assertEqual(b"PTCCFG01", envelope[:8])

    def test_signed_rate_studies_cannot_change_profile_during_measurement(self) -> None:
        base = json.loads((ROOT / "test-fixtures/host-study.json").read_text())
        for name, expected in configurations(base):
            with self.subTest(asset=name):
                actual = fixture_configuration(ROOT / "app/src/androidTest/assets" / name)
                self.assertEqual(expected, actual)
                if name == "host_harness_study_envelope.txt":
                    continue
                profiles = actual["traffic_shaping"]["profiles"]
                self.assertEqual(1, len(profiles))
                profile = profiles[0]
                binding = next(item for item in actual["automations"] if item["id"] == "bind-traffic-shaping")
                self.assertEqual(profile["id"], binding["default_profile_id"])
                self.assertEqual([{"condition": {"type": "study_session_active"}, "profile_id": profile["id"]}], binding["cases"])
                self.assertEqual(profile["uplink_kbps"], profile["downlink_kbps"])

if __name__ == "__main__":
    unittest.main()
