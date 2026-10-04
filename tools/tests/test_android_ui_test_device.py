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
PROCESS_HEADER = "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n  All known processes:\n"


def process(name=DEVICE.STOCK_HOME_PACKAGE, pid=1109, error="", user=0):
    return f"  *APP* UID 10168 ProcessRecord{{abcd {pid}:{name}/u{user}a168}}\n    user #{user} uid=10168 gids={{}}\n" + error


ANR = "     mCrashing=false null mNotResponding=true null bad=false\n"
PROCESSES = PROCESS_HEADER + process()


def displays(title=DEVICE.STOCK_HOME):
    focus = "null" if title is None else f"Window{{abcd u0 {title}}}"
    return f"WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n  Display: mDisplayId=0 (organized)\n  mCurrentFocus={focus}\n"


class AndroidHomeStateTest(unittest.TestCase):
    def test_pending_anr_is_recognized_before_dialog_exists(self):
        self.assertEqual((1109, False), DEVICE.home_process_state(PROCESSES))
        self.assertEqual((1109, True), DEVICE.home_process_state(PROCESS_HEADER + process(error=ANR)))
        self.assertEqual("home", DEVICE.home_focus(displays()))
        self.assertEqual("stock_anr", DEVICE.home_focus(displays("Application Not Responding: " + DEVICE.STOCK_HOME_PACKAGE)))

    def test_unknown_other_application_and_ambiguous_process_state_fail_closed(self):
        for value in (
            "", PROCESS_HEADER, PROCESSES + process(), PROCESSES.replace("    user #0", "    user #10"),
            PROCESS_HEADER + process(pid=0),
            PROCESSES + process(name="cool.jacoblin.particeps", pid=2000, error=ANR),
            PROCESS_HEADER + process(error=ANR.replace("mCrashing=false", "mCrashing=true")),
            PROCESS_HEADER + process(error=ANR.replace("bad=false", "bad=true")),
            PROCESS_HEADER + process(error="     mNotResponding=true\n"),
            PROCESS_HEADER + process(error=ANR + ANR),
            PROCESS_HEADER + process(error=ANR.replace("mNotResponding=true", "mNotResponding=false")),
            PROCESSES.replace("ProcessRecord{", "UnknownRecord{"),
        ):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                DEVICE.home_process_state(value)

    def test_live_focus_rejects_other_dialogs_and_historical_or_duplicate_dump(self):
        self.assertEqual("none", DEVICE.home_focus(displays(None)))
        for value in (
            "", displays("Application Not Responding: cool.jacoblin.particeps"),
            displays("Unexpected dialog"), displays() + displays(),
            displays().replace("mDisplayId=0", "mDisplayId=1"),
            "WINDOW MANAGER LAST ANR\n" + displays(),
        ):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                DEVICE.home_focus(value)


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

    def run_preparation(self, *, serial="emulator-5584", qemu="1", sdk="34", stays_locked=False, previous_markers=False,
                        states=None, home=None, homes=None, timeout_seconds=30, launch_result="Status: ok", command_delay=0.01):
        commands = []
        clock = [0.0]
        snapshot = [0]
        resolver = [0]
        states = states or [{}]
        homes = homes or [home or DEVICE.STOCK_HOME]

        def run(command, **kwargs):
            self.assertGreater(kwargs["timeout"], 0)
            self.assertLessEqual(kwargs["timeout"], min(15, timeout_seconds - clock[0]))
            clock[0] += command_delay
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
                ("shell", "dumpsys", "window", "displays"): states[min(snapshot[0], len(states)-1)].get("displays", displays()),
                ("shell", "dumpsys", "activity", "-a", "processes"): states[min(snapshot[0], len(states)-1)].get("processes", PROCESSES),
                ("shell", "cmd", "package", "resolve-activity", "--components", "--user", "0", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"): homes[min(resolver[0], len(homes)-1)],
                ("shell", "am", "start", "-W", "--user", "0", "-n", DEVICE.STOCK_HOME, "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"): launch_result,
            }
            if arguments == ("shell", "dumpsys", "activity", "-a", "processes"):
                snapshot[0] += 1
            if "resolve-activity" in arguments:
                resolver[0] += 1
            return subprocess.CompletedProcess(command, 0, replies.get(arguments, ""), "")

        def sleep(seconds):
            clock[0] += seconds

        with tempfile.TemporaryDirectory() as temporary, patch.object(DEVICE.subprocess, "run", side_effect=run), \
                patch.object(DEVICE.time, "monotonic", side_effect=lambda: clock[0]), patch.object(DEVICE.time, "sleep", side_effect=sleep):
            evidence = Path(temporary)
            if previous_markers:
                (evidence / "result.txt").write_text("PASS from a previous run\n")
                (evidence / "device-serial.txt").write_text("emulator-5554\n")
                (evidence / "previous-diagnostic.txt").write_text("Preserve diagnostic evidence\n")
            try:
                DEVICE.prepare("adb", evidence, timeout_seconds=timeout_seconds)
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
        self.assertIn("time budget", error)
        self.assertIn("keyguard showing", files["pending-problems.txt"])
        self.assertIn("showing=true", files["state-000-window-policy.txt"])
        self.assertNotIn("result.txt", files)

    def test_failed_revalidation_clears_only_stale_success_and_device_markers(self) -> None:
        _, error, files = self.run_preparation(serial="physical-phone", previous_markers=True)
        self.assertIsNotNone(error)
        self.assertNotIn("result.txt", files)
        self.assertNotIn("device-serial.txt", files)
        self.assertEqual("Preserve diagnostic evidence\n", files["previous-diagnostic.txt"])

    def test_only_matching_stock_anr_allows_one_recovery_before_success(self):
        for focus in (DEVICE.STOCK_HOME, "Application Not Responding: " + DEVICE.STOCK_HOME_PACKAGE):
            bad = {"processes": PROCESS_HEADER + process(error=ANR), "displays": displays(focus)}
            healthy = {"processes": PROCESS_HEADER + process(pid=2200)}
            commands, error, files = self.run_preparation(states=[bad, bad, healthy])
            self.assertIsNone(error)
            self.assertEqual(1, commands.count(("shell", "am", "force-stop", "--user", "0", DEVICE.STOCK_HOME_PACKAGE)))
            self.assertIn("mNotResponding=true", files["state-000-processes.txt"])
            self.assertIn("2200:", files["after-processes.txt"])
            self.assertIn("pid=1109", files["stock-home-recovery.txt"])

    def test_resolver_requests_components_only_and_rejects_unexpected_metadata(self):
        component = DEVICE.STOCK_HOME_PACKAGE + "/.NexusLauncherActivity"
        commands, error, files = self.run_preparation(home=component)
        self.assertIsNone(error)
        resolver = [command for command in commands if "resolve-activity" in command]
        self.assertEqual(1, len(resolver))
        self.assertIn("--components", resolver[0])
        self.assertNotIn("--brief", resolver[0])
        self.assertEqual(component + "\n", files["resolved-home.txt"])
        # Actual Android 14 --brief output must not be accepted by splitting off an arbitrary tail.
        for unexpected in (
            "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n" + component,
            component + "\n" + component,
            "No activity found",
        ):
            with self.subTest(unexpected=unexpected):
                _, error, files = self.run_preparation(home=unexpected)
                self.assertIn("Unexpected default HOME", error)
                self.assertNotIn("result.txt", files)

    def test_unhealthy_unidentified_or_repeated_state_is_never_dismissed(self):
        stock_anr = {"processes": PROCESS_HEADER + process(error=ANR)}
        cases = (
            ({"states": [stock_anr]}, 1),
            ({"states": [stock_anr, stock_anr, {}]}, 1),  # same PID after force-stop
            ({"states": [{"processes": PROCESSES + process(name="cool.jacoblin.particeps", pid=2000, error=ANR)}]}, 0),
            ({"states": [{"displays": displays("Unexpected dialog")}]}, 0),
            ({"states": [{"displays": displays("Application Not Responding: " + DEVICE.STOCK_HOME_PACKAGE)}]}, 0),
            ({"home": "com.other/.Home"}, 0),
            ({"launch_result": "Status: timeout"}, 0),
        )
        for settings, mutations in cases:
            with self.subTest(settings=settings):
                commands, error, files = self.run_preparation(**settings)
                self.assertIsNotNone(error)
                self.assertNotIn("result.txt", files)
                self.assertEqual(mutations, commands.count(("shell", "am", "force-stop", "--user", "0", DEVICE.STOCK_HOME_PACKAGE)))

    def test_fresh_sdk_setup_is_observed_until_nexus_without_mutating_setup(self):
        fallback = {"displays": displays(DEVICE.FALLBACK_HOME_WINDOW)}
        commands, error, files = self.run_preparation(
            homes=[DEVICE.STOCK_SETUP_HOME, DEVICE.STOCK_SETUP_HOME, DEVICE.STOCK_HOME],
            states=[fallback, fallback, fallback, fallback, {}, {}],
        )
        self.assertIsNone(error)
        self.assertEqual(DEVICE.STOCK_SETUP_HOME + "\n", files["resolved-home-000.txt"])
        self.assertEqual(DEVICE.STOCK_SETUP_HOME + "\n", files["resolved-home-001.txt"])
        self.assertEqual(DEVICE.STOCK_HOME + "\n", files["resolved-home-002.txt"])
        self.assertIn(DEVICE.FALLBACK_HOME_WINDOW, files["setup-001-window-displays.txt"])
        self.assertIn("setup-001-processes.txt", files)
        self.assertIn("PASS", files["result.txt"])
        mutations = [command for command in commands if command[:3] in (
            ("shell", "am", "start"), ("shell", "am", "force-stop"),
        )]
        self.assertEqual(1, len(mutations))
        last_resolve = max(index for index, command in enumerate(commands) if "resolve-activity" in command)
        self.assertGreater(commands.index(mutations[0]), last_resolve)
        self.assertFalse(any(command[:2] in (("shell", "settings"), ("shell", "pm")) for command in commands))

    def test_sdk_setup_timeout_and_unknown_default_fail_without_home_launch(self):
        for homes, expected in (
            ([DEVICE.STOCK_SETUP_HOME], "time budget"),
            ([DEVICE.STOCK_SETUP_HOME, "com.other/.Home"], "Unexpected default HOME"),
            ([DEVICE.STOCK_SETUP_HOME, "com.google.android.googlesdksetup/com.google.android.googlesdksetup.DefaultActivity"], "Unexpected default HOME"),
        ):
            with self.subTest(homes=homes):
                commands, error, files = self.run_preparation(homes=homes, timeout_seconds=0.65)
                self.assertIn(expected, error)
                self.assertNotIn("result.txt", files)
                self.assertIn("setup-000-processes.txt", files)
                self.assertFalse(any(command[:3] in (("shell", "am", "start"), ("shell", "am", "force-stop")) for command in commands))

    def test_sdk_setup_does_not_accept_other_windows_or_process_errors(self):
        for pending in (
            {"displays": displays("Unexpected dialog")},
            {"processes": PROCESS_HEADER + process(error=ANR)},
            {"processes": PROCESSES + process(name="com.google.android.googlesdksetup", pid=2000, error=ANR)},
        ):
            with self.subTest(pending=pending):
                commands, error, files = self.run_preparation(homes=[DEVICE.STOCK_SETUP_HOME], states=[{}, pending])
                self.assertIsNotNone(error)
                self.assertNotIn("result.txt", files)
                self.assertFalse(any(command[:3] in (("shell", "am", "start"), ("shell", "am", "force-stop")) for command in commands))
        # FallbackHome is not accepted outside the explicitly observed SDK setup sequence.
        _, error, _ = self.run_preparation(states=[{"displays": displays(DEVICE.FALLBACK_HOME_WINDOW)}])
        self.assertIn("Unexpected foreground window", error)

    def test_all_commands_and_success_readback_share_one_deadline(self):
        commands, error, files = self.run_preparation(timeout_seconds=0.135)
        self.assertIn("time budget", error)
        self.assertNotIn("result.txt", files)
        self.assertEqual(14, len(commands))


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
