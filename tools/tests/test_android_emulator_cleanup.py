import importlib.util
import json
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("android_emulator_cleanup", ROOT / "tools/android_emulator_cleanup.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def proc_stat(pid=123, parent=456, start=789, state="S"):
    return f"{pid} (emulator (worker)) " + " ".join([state, str(parent), *(["0"] * 17), str(start)])


class EmulatorCleanupTest(unittest.TestCase):
    def test_identity_parser_retains_parent_and_start_time(self):
        self.assertEqual((123, 456, 789, "S"), MODULE.process_identity(proc_stat()))
        with patch.object(MODULE.Path, "read_text", return_value=proc_stat(start=790)):
            with self.assertRaisesRegex(RuntimeError, "refusing to signal"):
                MODULE.owned_state(123, 456, (123, 456, 789, "S"))
        with patch.object(MODULE.Path, "read_text", return_value=proc_stat(state="Z")):
            self.assertEqual("exited", MODULE.owned_state(123, 456, (123, 456, 789, "S")))
        with patch.object(MODULE.Path, "read_text", side_effect=FileNotFoundError):
            self.assertEqual("gone", MODULE.owned_state(123, 456, (123, 456, 789, "S")))

    def test_pidfd_is_closed_without_signalling_on_identity_mismatch(self):
        with patch.object(MODULE.os, "pidfd_open", return_value=42, create=True), \
                patch.object(MODULE, "owned_state", side_effect=RuntimeError("wrong child")), \
                patch.object(MODULE.signal, "pidfd_send_signal", create=True) as send, \
                patch.object(MODULE.os, "close") as close:
            with self.assertRaisesRegex(RuntimeError, "wrong child"):
                MODULE.signal_owned(123, 456, (123, 456, 789, "S"), signal.SIGTERM)
            send.assert_not_called()
            close.assert_called_once_with(42)

    def run_cleanup(self, *, original_exit=0, pid=123, state="alive", stubborn=False,
                    never_exits=False, command_status="ok", mismatch=False, console_name="only_owned_avd",
                    command_statuses=None, unreaped_command=False):
        current = [state]
        commands, signals = [], []

        def command(argv, evidence, label):
            commands.append(argv)
            if label == "console-identity":
                (evidence / "console-identity.stdout.txt").write_text(console_name + "\nOK\n")
            return {"phase": label, "status": (command_statuses or {}).get(label, command_status),
                    "reap_timed_out": unreaped_command}

        def send(child, parent, identity, sig):
            self.assertEqual((123, 456, (123, 456, 789, "S")), (child, parent, identity))
            signals.append(sig)
            if not never_exits and (sig == signal.SIGKILL or not stubborn):
                current[0] = "exited"

        with tempfile.TemporaryDirectory() as temporary:
            evidence = Path(temporary)
            identity = evidence / "identity"
            identity.write_text(proc_stat())
            with patch.object(MODULE, "bounded_command", side_effect=command), \
                    patch.object(MODULE, "owned_state", side_effect=(RuntimeError("wrong child") if mismatch else lambda *_: current[0])), \
                    patch.object(MODULE, "signal_owned", side_effect=send), \
                    patch.object(MODULE, "TERM_GRACE_SECONDS", 0.02), \
                    patch.object(MODULE, "KILL_GRACE_SECONDS", 0.02):
                started = time.monotonic()
                result = MODULE.cleanup(adb="adb", avdmanager="avdmanager", avd_name="only_owned_avd",
                    emulator_pid=pid, parent_pid=456, identity_file=identity, evidence=evidence,
                    original_exit=original_exit)
                self.assertLess(time.monotonic() - started, 1)
            return result, commands, signals, (evidence / "emulator-ready-to-reap").exists(), json.loads((evidence / "events.json").read_text())

    def test_normal_cleanup_signals_only_owned_child_and_deletes_only_owned_avd(self):
        result, commands, signals, ready, _ = self.run_cleanup()
        self.assertEqual(0, result)
        self.assertEqual([["adb", "-s", "emulator-5554", "emu", "avd", "name"],
                          ["adb", "-s", "emulator-5554", "emu", "kill"],
                          ["avdmanager", "delete", "avd", "--name", "only_owned_avd"]], commands)
        self.assertEqual([signal.SIGTERM], signals)
        self.assertTrue(ready)

    def test_no_owned_live_pid_never_sends_console_kill(self):
        for values in ({"pid": None}, {"state": "gone"}, {"state": "exited"}):
            with self.subTest(values=values):
                result, commands, signals, _, _ = self.run_cleanup(**values)
                self.assertEqual(0, result)
                self.assertEqual([["avdmanager", "delete", "avd", "--name", "only_owned_avd"]], commands)
                self.assertEqual([], signals)

    def test_incomplete_cleanup_fails_success_and_preserves_original_failure(self):
        for original, expected in ((0, 1), (23, 23)):
            for values in ({"never_exits": True}, {"command_status": "timeout"},
                           {"command_status": "command_failed"}, {"unreaped_command": True}):
                with self.subTest(original=original, values=values):
                    result, _, _, _, events = self.run_cleanup(original_exit=original, **values)
                    self.assertEqual(expected, result)
                    self.assertTrue(events[-1]["cleanup_failed"])
        self.assertEqual(23, self.run_cleanup(original_exit=23)[0])

    def test_successful_forced_reclamation_keeps_intermediate_failure_evidence(self):
        for original in (0, 23):
            for values in ({"stubborn": True}, {"command_statuses": {"console-kill": "timeout"}},
                           {"command_statuses": {"console-identity": "command_failed"}}):
                with self.subTest(original=original, values=values):
                    result, _, _, ready, events = self.run_cleanup(original_exit=original, **values)
                    self.assertEqual(original, result)
                    self.assertTrue(ready)
                    self.assertTrue(any(event.get("status") in ("timeout", "command_failed") for event in events))
                    self.assertTrue(events[-1]["emulator_stopped"])
                    self.assertTrue(events[-1]["avd_deleted"])
                    self.assertFalse(events[-1]["cleanup_failed"])

    def test_stubborn_child_escalation_and_unconfirmed_exit_remain_bounded(self):
        result, _, signals, ready, _ = self.run_cleanup(stubborn=True)
        self.assertEqual(0, result)
        self.assertEqual([signal.SIGTERM, signal.SIGKILL], signals)
        self.assertTrue(ready)
        result, commands, _, ready, events = self.run_cleanup(never_exits=True)
        self.assertEqual(1, result)
        self.assertFalse(ready)
        self.assertEqual(2, len(commands))  # Do not delete the running AVD.
        self.assertEqual("skipped", events[-2]["status"])

    def test_avd_delete_timeout_fails_even_after_owned_child_has_stopped(self):
        for original, expected in ((0, 1), (23, 23)):
            with self.subTest(original=original):
                result, _, _, ready, events = self.run_cleanup(
                    original_exit=original, command_statuses={"avd-delete": "timeout"})
                self.assertEqual(expected, result)
                self.assertTrue(ready)
                self.assertEqual("timeout", events[-2]["status"])
                self.assertTrue(events[-1]["emulator_stopped"])
                self.assertFalse(events[-1]["avd_deleted"])
                self.assertTrue(events[-1]["cleanup_failed"])

    def test_reused_pid_is_never_signalled_or_deleted(self):
        result, commands, signals, ready, _ = self.run_cleanup(mismatch=True)
        self.assertEqual(1, result)
        self.assertEqual([], commands)
        self.assertEqual([], signals)
        self.assertFalse(ready)

    def test_another_emulator_on_the_serial_is_not_sent_console_kill(self):
        result, commands, signals, ready, events = self.run_cleanup(console_name="somebody_elses_avd")
        self.assertEqual(0, result)
        self.assertNotIn(["adb", "-s", "emulator-5554", "emu", "kill"], commands)
        self.assertEqual([signal.SIGTERM], signals)  # The verified owned child is still reclaimed.
        self.assertTrue(ready)
        self.assertEqual("identity_mismatch", events[1]["status"])

    def test_hanging_command_is_killed_and_stdout_stderr_are_retained(self):
        with tempfile.TemporaryDirectory() as temporary:
            evidence = Path(temporary)
            with patch.object(MODULE, "COMMAND_TIMEOUT_SECONDS", 0.2):
                started = time.monotonic()
                result = MODULE.bounded_command([sys.executable, "-c",
                    "import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); print('started',flush=True); time.sleep(60)"],
                    evidence, "blocked")
            self.assertLess(time.monotonic() - started, 3)
            self.assertEqual("timeout", result["status"])
            self.assertEqual(-signal.SIGKILL, result["returncode"])
            self.assertEqual("started\n", (evidence / "blocked.stdout.txt").read_text())
            self.assertTrue((evidence / "blocked.stderr.txt").is_file())
            result = MODULE.bounded_command(["/definitely-missing-owned-test-command"], evidence, "missing")
            self.assertEqual("error", result["status"])

    def test_runner_trap_keeps_original_failure_even_if_helper_itself_fails(self):
        source = (ROOT / "tools/android-api37-emulator-runner.sh").read_text()
        cleanup = "cleanup() {" + source.split("cleanup() {", 1)[1].split("\n}", 1)[0] + "\n}"
        with tempfile.TemporaryDirectory() as temporary:
            for original, helper, expected in ((0, 0, 0), (0, 1, 1), (23, 1, 23), (23, 0, 23)):
                script = (f"set -euo pipefail\nreport_directory={temporary!r}\n"
                    "emulator_pid=''\nadb_binary=unused\navdmanager=unused\navd_name=owned\n"
                    f"python3() {{ return {helper}; }}\n{cleanup}\ntrap cleanup EXIT\nexit {original}\n")
                result = subprocess.run(["bash", "-s"], input=script, capture_output=True, text=True, timeout=3)
                self.assertEqual(expected, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
