import importlib.util
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("android_ui_test_device", ROOT / "tools/android_ui_test_device.py")
assert SPEC is not None and SPEC.loader is not None
DEVICE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DEVICE)

POWER = "  mWakefulness=Awake\n  mHalInteractiveModeEnabled=true\n"
POLICY = """    KeyguardServiceDelegate
      showing=false
      inputRestricted=false
      occluded=false
      secure=false
      KeyguardStateMonitor
        mIsShowing=false
"""


class AndroidUiStateTest(unittest.TestCase):
    def test_only_awake_unlocked_unsecured_state_is_ready(self) -> None:
        self.assertEqual([], DEVICE.ui_state_problems(POWER, POLICY, "RUNNING_UNLOCKED\r\n"))
        for power, policy, user in (
            (POWER.replace("Awake", "Asleep"), POLICY, "RUNNING_UNLOCKED"),
            (POWER.replace("true", "false"), POLICY, "RUNNING_UNLOCKED"),
            (POWER, POLICY.replace("showing=false", "showing=true"), "RUNNING_UNLOCKED"),
            (POWER, POLICY.replace("inputRestricted=false", "inputRestricted=true"), "RUNNING_UNLOCKED"),
            (POWER, POLICY.replace("secure=false", "secure=true"), "RUNNING_UNLOCKED"),
            (POWER, POLICY, "RUNNING_LOCKED"),
            ("", POLICY, "RUNNING_UNLOCKED"),
            (POWER, "", "RUNNING_UNLOCKED"),
            (POWER + POWER, POLICY, "RUNNING_UNLOCKED"),
            (POWER, POLICY.replace("showing=false", "showing=false\n      showing=true"), "RUNNING_UNLOCKED"),
        ):
            with self.subTest(power=power, policy=policy, user=user):
                self.assertTrue(DEVICE.ui_state_problems(power, policy, user))

    def run_preparation(self, *, serial="emulator-5584", qemu="1", sdk="34", stays_locked=False, previous_markers=False):
        commands = []

        def run(command, **kwargs):
            if command[1:] == ["get-serialno"]:
                arguments = ("get-serialno",)
            else:
                self.assertEqual(["-s", serial], command[1:3])
                arguments = tuple(command[3:])
            commands.append(arguments)
            replies = {
                ("get-serialno",): serial,
                ("shell", "getprop", "ro.kernel.qemu"): qemu,
                ("shell", "getprop", "ro.build.version.sdk"): sdk,
                ("shell", "dumpsys", "power"): POWER,
                ("shell", "dumpsys", "window", "policy"): (
                    POLICY.replace("showing=false", "showing=true") if stays_locked else POLICY
                ),
                ("shell", "am", "get-started-user-state", "0"): "RUNNING_UNLOCKED",
            }
            return subprocess.CompletedProcess(command, 0, replies.get(arguments, ""), "")

        with tempfile.TemporaryDirectory() as temporary, patch.object(DEVICE.subprocess, "run", side_effect=run):
            evidence = Path(temporary)
            if previous_markers:
                (evidence / "result.txt").write_text("PASS from a previous run\n")
                (evidence / "device-serial.txt").write_text("emulator-5554\n")
                (evidence / "previous-diagnostic.txt").write_text("Preserve diagnostic evidence\n")
            try:
                DEVICE.prepare("adb", evidence, timeout_seconds=0)
                error = None
            except RuntimeError as failure:
                error = str(failure)
            files = {item.name: item.read_text() for item in evidence.iterdir()}
        return commands, error, files

    def test_preparation_uses_normal_power_and_keyguard_commands_and_records_state(self) -> None:
        commands, error, files = self.run_preparation()
        self.assertIsNone(error)
        self.assertIn(("shell", "input", "keyevent", "KEYCODE_WAKEUP"), commands)
        self.assertIn(("shell", "wm", "dismiss-keyguard"), commands)
        self.assertFalse(any(command[:2] == ("shell", "svc") for command in commands))
        self.assertIn("before-window-policy.txt", files)
        self.assertIn("after-window-policy.txt", files)
        self.assertIn("PASS", files["result.txt"])

    def test_refuses_physical_wrong_api_and_unidentified_devices_before_mutating(self) -> None:
        for settings in ({"serial": "physical-phone"}, {"sdk": "37"}, {"qemu": "0"}):
            with self.subTest(settings=settings):
                commands, error, _ = self.run_preparation(**settings)
                self.assertIsNotNone(error)
                self.assertFalse(any(command[:2] in (("shell", "input"), ("shell", "wm"), ("shell", "svc")) for command in commands))

    def test_unresolved_keyguard_remains_a_failure_with_evidence(self) -> None:
        _, error, files = self.run_preparation(stays_locked=True)
        self.assertIn("keyguard showing", error)
        self.assertIn("showing=true", files["after-window-policy.txt"])
        self.assertNotIn("result.txt", files)

    def test_failed_revalidation_clears_only_stale_success_and_device_markers(self) -> None:
        _, error, files = self.run_preparation(serial="physical-phone", previous_markers=True)
        self.assertIsNotNone(error)
        self.assertNotIn("result.txt", files)
        self.assertNotIn("device-serial.txt", files)
        self.assertEqual("Preserve diagnostic evidence\n", files["previous-diagnostic.txt"])


class AndroidUiEvidenceTest(unittest.TestCase):
    def test_product_failure_and_precondition_failure_always_retain_evidence(self) -> None:
        for preparation_status, product_status in ((0, 23), (17, 0), (0, 0)):
            with self.subTest(preparation_status=preparation_status, product_status=product_status), tempfile.TemporaryDirectory() as temporary:
                repository = Path(temporary)
                (repository / "tools").mkdir()
                for component in ("app", "core", "collector", "actuator"):
                    (repository / component).mkdir()
                shutil.copy(ROOT / "tools/android-emulator-ci.sh", repository / "tools")
                (repository / "tools/android_ui_test_device.py").write_text(
                    "import runpy, sys\nfrom pathlib import Path\n"
                    "if sys.argv[1] == 'prepare':\n"
                    "    evidence = Path(sys.argv[sys.argv.index('--evidence-directory') + 1])\n"
                    "    evidence.mkdir(parents=True, exist_ok=True)\n"
                    "    (evidence / 'device-serial.txt').write_text('emulator-5584\\n')\n"
                    f"    raise SystemExit({preparation_status})\n"
                    f"runpy.run_path({str(ROOT / 'tools/android_ui_test_device.py')!r}, run_name='__main__')\n"
                )
                for name, body in (
                    ("gradlew", f"echo gradle >> calls.txt\nexit {product_status}\n"),
                    ("tools/android-host-harness.sh", "echo harness >> calls.txt\n"),
                    ("adb", "echo \"$*\" >> adb-calls.txt\necho synthetic-diagnostic\nexit 1\n"),
                ):
                    executable = repository / name
                    executable.write_text("#!/usr/bin/env bash\n" + body)
                    executable.chmod(0o755)
                junit = repository / "app/build/outputs/androidTest-results/connected/TEST-ui.xml"
                junit.parent.mkdir(parents=True)
                junit.write_text('<testsuite failures="1"/>')
                html = repository / "core/storage/build/reports/androidTests/connected/index.html"
                html.parent.mkdir(parents=True)
                html.write_text("test report")
                result = subprocess.run(
                    ["bash", "tools/android-emulator-ci.sh", "--require-16k=false"],
                    cwd=repository, env={**os.environ, "ADB": str(repository / "adb")},
                    capture_output=True, text=True,
                )
                expected_status = preparation_status or product_status
                self.assertEqual(expected_status, result.returncode, result.stderr)
                evidence = repository / "build/reports/android-host-harness/api34-product-lane"
                self.assertEqual(f"{expected_status}\n", (evidence / "exit-status.txt").read_text())
                self.assertEqual(junit.read_text(), (evidence / "connected-reports" / junit.relative_to(repository)).read_text())
                self.assertEqual(html.read_text(), (evidence / "connected-reports" / html.relative_to(repository)).read_text())
                for name in ("logcat.txt", "window.txt", "power.txt", "activities.txt", "screenshot.png"):
                    self.assertTrue((evidence / name).exists(), name)
                calls = (repository / "calls.txt").read_text() if (repository / "calls.txt").exists() else ""
                self.assertEqual("" if preparation_status else ("gradle\n" if product_status else "gradle\nharness\n"), calls)

    def test_device_timeouts_do_not_prevent_report_capture(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            serial = repository / "serial.txt"
            serial.write_text("emulator-5584\n")
            report = repository / "app/build/outputs/androidTest-results/connected/TEST.xml"
            report.parent.mkdir(parents=True)
            report.write_text("failed-test")
            evidence = repository / "evidence"

            def timeout(command, **kwargs):
                self.assertEqual(["adb", "-s", "emulator-5584"], command[:3])
                self.assertEqual(15, kwargs["timeout"])
                raise subprocess.TimeoutExpired(command, 15)

            with patch.object(DEVICE.subprocess, "run", side_effect=timeout) as run:
                DEVICE.capture("adb", evidence, serial, repository)
            self.assertEqual(5, run.call_count)
            self.assertEqual(5, (evidence / "capture-errors.txt").read_text().count("timed out"))
            self.assertEqual(report.read_text(), (evidence / "connected-reports" / report.relative_to(repository)).read_text())


if __name__ == "__main__":
    unittest.main()
