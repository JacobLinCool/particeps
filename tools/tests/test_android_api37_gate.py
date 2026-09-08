import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PLATFORM_FAILURE = (
    "surfaceflinger /vendor/lib64/hw/mapper.ranchu.so\n"
    "Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma\n"
    "transport error: package service unavailable\n"
)
JAVA_FAILURE = (
    "E AndroidRuntime: FATAL EXCEPTION: main\n"
    "E AndroidRuntime: Process: cool.jacoblin.particeps, PID: 456\n"
    "java.lang.IllegalStateException: SharedPreferences in credential encrypted storage "
    "are not available until after user is unlocked\n"
)


class Api37GateTest(unittest.TestCase):
    def run_gate(
        self,
        *,
        boot_log: str = "Stock emulator started.\n",
        compatibility_log: str = "",
        compatibility_status: int = 0,
        compatibility_output: str | None = None,
        stale_attempt_output: str = "",
        retry_once: bool = False,
        host_log: str = "",
        host_status: int = 0,
        unlocked: tuple[bool, ...] = (True,),
    ) -> tuple[subprocess.CompletedProcess[str], list[str], dict[str, str]]:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            tools = root / "tools"
            tools.mkdir()
            for filename in ("android-emulator-ci.sh", "classify_api37_emulator_failure.py"):
                shutil.copy2(ROOT / "tools" / filename, tools / filename)
            fake_bin = root / "bin"
            fake_bin.mkdir()
            sleep = fake_bin / "sleep"
            sleep.write_text("#!/usr/bin/env bash\nexit 0\n")
            sleep.chmod(0o755)
            sdk = root / "sdk"
            image = sdk / "system-images/android-37.0/google_apis_ps16k/x86_64"
            image.mkdir(parents=True)
            (image / "package.xml").write_text("<package><revision><major>6</major></revision></package>")
            emulator_log = root / "emulator.log"
            emulator_log.write_text(boot_log)
            events = root / "events.log"
            adb = fake_bin / "adb"
            adb.write_text(
                """#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
command = ' '.join(args)
with open(os.environ['GATE_EVENTS'], 'a') as log:
    log.write(command + '\\n')
if command == 'get-state':
    print('device')
elif command == 'shell getconf PAGE_SIZE':
    print('16384')
elif command == 'shell getprop sys.boot_completed':
    print('1')
elif command.startswith('shell service check '):
    print('Service found')
elif command == 'shell cmd package path android':
    print('package:/system/framework/framework-res.apk')
elif command == 'shell am get-started-user-state 0':
    state = pathlib.Path(os.environ['GATE_STATE'])
    count = int(state.read_text()) if state.exists() else 0
    sequence = json.loads(os.environ['GATE_UNLOCKED'])
    value = sequence[min(count, len(sequence) - 1)]
    state.write_text(str(count + 1))
    print('RUNNING_UNLOCKED' if value else 'RUNNING_LOCKED')
elif args and args[0] == 'logcat':
    if '-c' in args:
        raise SystemExit('Crash evidence must never be cleared')
    # Deliberately empty: the full emulator log must catch crashes outside the buffer.
else:
    raise SystemExit('Unexpected ADB command: ' + command)
""",
            )
            adb.chmod(0o755)
            compatibility = tools / "android-instrumentation-ci.sh"
            compatibility.write_text(
                """#!/usr/bin/env python3
import os, pathlib
with open(os.environ['GATE_EVENTS'], 'a') as log:
    log.write('compatibility-start\\n')
report = pathlib.Path(os.environ['PARTICEPS_INSTRUMENTATION_REPORT_DIR'])
report.mkdir(parents=True)
state = pathlib.Path(os.environ['GATE_ATTEMPTS'])
count = int(state.read_text()) if state.exists() else 0
state.write_text(str(count + 1))
if os.environ['GATE_RETRY_ONCE'] == '1' and count > 0:
    evidence, status = '', 0
else:
    evidence = os.environ['GATE_COMPATIBILITY_LOG']
    status = int(os.environ['GATE_COMPATIBILITY_STATUS'])
with open(os.environ['PARTICEPS_API37_EMULATOR_LOG'], 'a') as log:
    log.write(evidence)
output = 'transport error: device offline' if status else 'API 37 compatibility checks passed.'
if os.environ['GATE_COMPATIBILITY_OUTPUT']:
    output = os.environ['GATE_COMPATIBILITY_OUTPUT']
output += os.environ['GATE_STALE_ATTEMPT_OUTPUT']
(report / 'api37-compatibility.txt').write_text(output)
print(output)
raise SystemExit(status)
""",
            )
            compatibility.chmod(0o755)
            host = tools / "android-host-harness.sh"
            host.write_text(
                """#!/usr/bin/env python3
import os, pathlib
with open(os.environ['GATE_EVENTS'], 'a') as log:
    log.write('host-start\\n')
report = pathlib.Path(os.environ['PARTICEPS_HOST_REPORT_DIR'])
report.mkdir(parents=True)
(report / 'android-host-harness.xml').write_text('<testsuite/>')
with open(os.environ['PARTICEPS_API37_EMULATOR_LOG'], 'a') as log:
    log.write(os.environ['GATE_HOST_LOG'])
status = int(os.environ['GATE_HOST_STATUS'])
print('transport error: device offline' if status else 'Host harness passed.')
raise SystemExit(status)
""",
            )
            host.chmod(0o755)
            environment = os.environ | {
                "PATH": str(fake_bin) + os.pathsep + os.environ["PATH"],
                "ADB": str(adb),
                "ANDROID_SDK_ROOT": str(sdk),
                "PARTICEPS_API37_EMULATOR_LOG": str(emulator_log),
                "GATE_EVENTS": str(events),
                "GATE_STATE": str(root / "unlocked-count"),
                "GATE_ATTEMPTS": str(root / "attempt-count"),
                "GATE_UNLOCKED": json.dumps(unlocked),
                "GATE_COMPATIBILITY_LOG": compatibility_log,
                "GATE_COMPATIBILITY_STATUS": str(compatibility_status),
                "GATE_COMPATIBILITY_OUTPUT": compatibility_output or "",
                "GATE_STALE_ATTEMPT_OUTPUT": stale_attempt_output,
                "GATE_RETRY_ONCE": "1" if retry_once else "0",
                "GATE_HOST_LOG": host_log,
                "GATE_HOST_STATUS": str(host_status),
            }
            result = subprocess.run(
                [str(tools / "android-emulator-ci.sh"), "--require-16k=true"],
                cwd=root,
                env=environment,
                capture_output=True,
                text=True,
                timeout=20,
            )
            reports = {
                str(path.relative_to(root)): path.read_text()
                for path in (root / "build/reports").rglob("*.txt")
            }
            return result, events.read_text().splitlines(), reports

    def test_compatibility_waits_for_three_consecutive_unlocked_observations(self) -> None:
        result, events, _ = self.run_gate(unlocked=(False, True, False, True, True, True))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        before_start = events[:events.index("compatibility-start")]
        self.assertEqual(6, before_start.count("shell am get-started-user-state 0"))
        self.assertFalse(any("logcat" in event and " -c" in event for event in events))

    def test_compatibility_pass_does_not_hide_credential_storage_app_crash(self) -> None:
        result, events, _ = self.run_gate(compatibility_log=JAVA_FAILURE)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("blocking", result.stdout)
        self.assertEqual(1, events.count("compatibility-start"))
        self.assertNotIn("host-start", events)

    def test_app_failure_before_compatibility_remains_blocking(self) -> None:
        result, events, _ = self.run_gate(boot_log=PLATFORM_FAILURE + JAVA_FAILURE)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("host-start", events)

    def test_native_app_crash_blocks_pass_and_platform_retry(self) -> None:
        for identity in (
            "F DEBUG: Cmdline: cool.jacoblin.particeps\n",
            "F DEBUG: pid: 123, tid: 456 >>> cool.jacoblin.particeps:worker <<<\n",
            "F libc: Fatal signal 11 (SIGSEGV), code 1, pid 1234 (lin.particeps)\n",
        ):
            for status in (0, 1):
                with self.subTest(identity=identity, status=status):
                    result, events, _ = self.run_gate(
                        compatibility_log=PLATFORM_FAILURE + identity,
                        compatibility_status=status,
                    )
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual(1, events.count("compatibility-start"))
                    self.assertNotIn("host-start", events)

    def test_known_platform_restart_can_retry_after_unlocked_readiness(self) -> None:
        result, events, _ = self.run_gate(
            compatibility_log=PLATFORM_FAILURE,
            compatibility_status=1,
            retry_once=True,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        starts = [index for index, event in enumerate(events) if event == "compatibility-start"]
        self.assertEqual(2, len(starts))
        self.assertEqual(3, events[starts[0]:starts[1]].count("shell am get-started-user-state 0"))

    def test_stale_platform_crash_cannot_quarantine_later_unknown_failure(self) -> None:
        result, _, _ = self.run_gate(boot_log=PLATFORM_FAILURE, host_status=1)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("missing:", result.stdout)
        self.assertNotIn("QUARANTINED:", result.stdout)

    def test_stale_crash_buffer_in_attempt_output_cannot_authorize_retry(self) -> None:
        result, events, _ = self.run_gate(
            boot_log=PLATFORM_FAILURE,
            compatibility_status=1,
            stale_attempt_output=PLATFORM_FAILURE,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, events.count("compatibility-start"))
        self.assertIn("missing:", result.stdout)
        self.assertNotIn("RETRYABLE:", result.stdout)

    def test_current_exact_platform_crash_can_quarantine_host_failure(self) -> None:
        result, _, _ = self.run_gate(host_log=PLATFORM_FAILURE, host_status=1)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("QUARANTINED:", result.stdout)

    def test_surfacecomposer_broken_pipe_is_not_an_adb_transport_failure(self) -> None:
        graphics_error = (
            "09-08 15:47:29.749 123 456 E SurfaceComposerClient: "
            "SurfaceComposerClient::createSurface error Broken pipe\n"
            "E/SurfaceComposerClient( 123): error Broken pipe\n"
        )
        signature = PLATFORM_FAILURE.replace("transport error: package service unavailable\n", "")
        result, events, _ = self.run_gate(
            compatibility_log=signature + graphics_error,
            compatibility_status=1,
            compatibility_output="Failure [INSTALL_FAILED_INVALID_APK]\n" + graphics_error,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, events.count("compatibility-start"))
        self.assertIn("current command did not report", result.stdout)
        self.assertNotIn("RETRYABLE:", result.stdout)

    def test_host_success_and_quarantine_both_keep_app_crashes_blocking(self) -> None:
        for status in (0, 1):
            with self.subTest(status=status):
                result, _, _ = self.run_gate(host_log=PLATFORM_FAILURE + JAVA_FAILURE, host_status=status)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("blocking", result.stdout)
                self.assertNotIn("QUARANTINED:", result.stdout)


if __name__ == "__main__":
    unittest.main()
