import json
import io
import os
import tempfile
from pathlib import Path
import subprocess
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from tools.android_host_control import ControlFailure, HostControl, main, receipt_from_broadcast

PROCESS = "10000000-0000-4000-8000-000000000001"
OTHER = "10000000-0000-4000-8000-000000000002"
OPERATION = "20000000-0000-4000-8000-000000000001"


class FakeAdb:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []
        self.timeouts = []
        self.now = 0.0
        self.pid = "42"

    def clock(self):
        return self.now

    def sleep(self, duration):
        self.now += duration

    def run(self, command, **kwargs):
        self.calls.append(command)
        self.timeouts.append(kwargs["timeout"])
        if "pidof" in command:
            return SimpleNamespace(returncode=0, stdout=self.pid)
        if "start" in command:
            return SimpleNamespace(returncode=0, stdout="Status: ok")
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        if callable(response):
            response = response(self)
        value = {"schema_version": 1, "process_id": PROCESS, **response}
        return SimpleNamespace(returncode=0, stdout=f'Broadcast completed: result=-1, data="{json.dumps(value)}"\n')

    def client(self):
        return HostControl(["adb"], run=self.run, clock=self.clock, sleep=self.sleep)

    def broadcasts(self):
        return [call for call in self.calls if "broadcast" in call]


class HostControlTest(unittest.TestCase):
    @patch("tools.android_host_control.uuid.uuid4", return_value=OPERATION)
    def test_cold_setup_waits_readiness_but_admits_mutation_exactly_once(self, _):
        adb = FakeAdb([
            {"status": "INITIALIZING"}, {"status": "READY"},
            {"status": "RUNNING", "operation_id": OPERATION},
            {"status": "RUNNING", "operation_id": OPERATION},
            {"status": "SUCCEEDED", "operation_id": OPERATION, "result": "RESET"},
        ])
        client = adb.client()
        client.ready(start=True)
        self.assertEqual("RESET", client.execute("reset"))
        self.assertEqual(1, sum("start" in call for call in adb.calls))
        self.assertEqual(1, sum(any("HOST_HARNESS_RESET" in arg for arg in call) for call in adb.calls))
        self.assertEqual(2, sum("operation_status" in call for call in adb.calls))
        self.assertGreater(adb.now, 0)

    @patch("tools.android_host_control.uuid.uuid4", return_value=OPERATION)
    def test_profile_and_native_queries_never_start_or_restart_activity(self, _):
        for operation in ("profile", "native"):
            with self.subTest(operation=operation):
                adb = FakeAdb([{"status": "READY"}, {"status": "SUCCEEDED", "operation_id": OPERATION, "result": "{}"}])
                client = adb.client()
                client.ready(start=False)
                self.assertEqual("{}", client.execute(operation))
                self.assertFalse(any("start" in call or "force-stop" in call for call in adb.calls))
                self.assertTrue(all(PROCESS in call for call in adb.broadcasts()[1:]))

    @patch("tools.android_host_control.uuid.uuid4", return_value=OPERATION)
    def test_pid_or_process_token_change_never_reissues_the_command(self, _):
        def replace_pid(adb):
            adb.pid = "43"
            return {"status": "RUNNING", "operation_id": OPERATION}
        for response in (replace_pid, {"status": "RUNNING", "operation_id": OPERATION, "process_id": OTHER}):
            with self.subTest(response=response):
                adb = FakeAdb([{"status": "READY"}, response])
                client = adb.client()
                client.ready(start=False)
                with self.assertRaisesRegex(ControlFailure, "changed"):
                    client.execute("reset")
                self.assertEqual(2, len(adb.broadcasts()))

    @patch("tools.android_host_control.uuid.uuid4", return_value=OPERATION)
    def test_unknown_busy_failed_and_mismatched_receipts_are_terminal(self, _):
        for response in (
            {"status": "UNKNOWN_OPERATION", "operation_id": OPERATION},
            {"status": "BUSY", "operation_id": OPERATION},
            {"status": "CAPACITY_EXHAUSTED", "operation_id": OPERATION},
            {"status": "FAILED", "operation_id": OPERATION, "result": "IOException"},
            {"status": "SUCCEEDED", "operation_id": OTHER, "result": "RESET"},
        ):
            with self.subTest(response=response):
                adb = FakeAdb([{"status": "READY"}, response])
                client = adb.client()
                client.ready(start=False)
                with self.assertRaises(ControlFailure):
                    client.execute("reset")
                self.assertEqual(2, len(adb.broadcasts()))

    @patch("tools.android_host_control.uuid.uuid4", return_value=OPERATION)
    def test_lost_admission_response_is_unknown_outcome_without_retry(self, _):
        adb = FakeAdb([{"status": "READY"}, subprocess.TimeoutExpired("adb", 5)])
        client = adb.client()
        client.ready(start=False)
        with self.assertRaises(subprocess.TimeoutExpired):
            client.execute("provision", envelope="test-fixture")
        self.assertEqual(2, len(adb.broadcasts()))

    @patch("tools.android_host_control.uuid.uuid4", return_value=OPERATION)
    def test_deadline_limits_polling_without_resubmission(self, _):
        adb = FakeAdb([{"status": "READY"}] + [{"status": "RUNNING", "operation_id": OPERATION}] * 10)
        client = adb.client()
        client.ready(start=False)
        with self.assertRaisesRegex(ControlFailure, "deadline"):
            client.execute("reset", timeout=0.2)
        self.assertEqual(1, sum(any("HOST_HARNESS_RESET" in arg for arg in call) for call in adb.calls))
        self.assertLessEqual(adb.now, 0.2)

    def test_state_is_immediate_and_uninitialized_queries_do_not_wait_or_start(self):
        adb = FakeAdb([{"status": "READY"}, {"status": "SUCCEEDED", "result": "RUNNING:5"}])
        client = adb.client()
        client.ready(start=False)
        self.assertEqual("RUNNING:5", client.execute("state"))
        self.assertEqual(0, adb.now)
        self.assertFalse(any("start" in call for call in adb.calls))
        adb = FakeAdb([{"status": "INITIALIZING"}])
        with self.assertRaisesRegex(ControlFailure, "not initialized"):
            adb.client().ready(start=False)
        self.assertEqual(1, len(adb.broadcasts()))

    def test_control_wire_rejects_missing_duplicate_and_wrong_schema_receipts(self):
        valid = f'Broadcast completed: result=-1, data="{json.dumps(dict(schema_version=1, process_id=PROCESS, status="READY"))}"\n'
        self.assertEqual("READY", receipt_from_broadcast(valid)["status"])
        for output in (valid.replace("result=-1", "result=0"), valid + valid, "", valid.replace('"schema_version": 1', '"schema_version": true')):
            with self.subTest(output=output), self.assertRaises(ControlFailure):
                receipt_from_broadcast(output)


class ControlDeadlineIdentityTest(unittest.TestCase):
    def invoke(self, adb, arguments):
        with patch("tools.android_host_control.HostControl", return_value=adb.client()), patch(
            "sys.argv", ["control", "--adb", "adb", *arguments],
        ), patch("sys.stdout", new_callable=io.StringIO), patch("sys.stderr", new_callable=io.StringIO):
            main()

    @patch("tools.android_host_control.uuid.uuid4", return_value=OPERATION)
    def test_query_readiness_and_execution_share_one_budget(self, _):
        def slow_readiness(adb):
            adb.now += 2
            return {"status": "READY"}
        adb = FakeAdb([slow_readiness, {"status": "SUCCEEDED", "operation_id": OPERATION, "result": "{}"}])
        self.invoke(adb, ["--timeout-seconds", "3", "profile"])
        self.assertTrue(all(timeout <= 1 for timeout in adb.timeouts[3:]))

    def test_prepare_honors_callers_remaining_budget_instead_of_startup_105(self):
        def initialization_too_slow(adb):
            adb.now += 2
            return {"status": "INITIALIZING"}
        adb = FakeAdb([initialization_too_slow])
        with self.assertRaises(SystemExit) as failure:
            self.invoke(adb, ["--timeout-seconds", "2", "prepare"])
        self.assertEqual(1, failure.exception.code)
        self.assertTrue(all(timeout <= 2 for timeout in adb.timeouts))

    def test_pinned_process_uuid_rejects_pid_reuse_across_invocations(self):
        with tempfile.TemporaryDirectory() as temporary:
            identity = Path(temporary) / "identity.json"
            original = json.dumps({"pid": "42", "process_id": PROCESS})
            identity.write_text(original)
            adb = FakeAdb([{"status": "READY", "process_id": OTHER}])
            with self.assertRaises(SystemExit):
                self.invoke(adb, ["--expected-pid", "42", "--identity-file", str(identity), "state"])
            self.assertEqual(original, identity.read_text())
            self.assertEqual(1, len(adb.broadcasts()))

    def test_only_explicit_cold_prepare_updates_persisted_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            identity = Path(temporary) / "identity.json"
            identity.write_text(json.dumps({"pid": "41", "process_id": OTHER}))
            adb = FakeAdb([{"status": "READY"}])
            self.invoke(adb, ["--identity-file", str(identity), "prepare"])
            self.assertEqual({"pid": "42", "process_id": PROCESS}, json.loads(identity.read_text()))
            self.assertEqual(1, sum("start" in call for call in adb.calls))


class PermissionRecoveryControlTest(unittest.TestCase):
    def run_recovery(self, initial_pid, die_again=False):
        root = Path(__file__).resolve().parents[2]
        harness = (root / "tools/android-host-harness.sh").read_text()
        declaration = "await_permission_revoke_pause() {"
        body = harness.split(declaration, 1)[1].split("\n}", 1)[0]
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "pid").write_text(initial_pid)
            script = "set -euo pipefail\n" + declaration + body + "\n}\n" + r'''
particeps_pid() { cat "$fixture/pid"; }
sleep() { :; }
host_control() {
  [[ "$1" == --timeout-seconds && "$2" -gt 0 && "$2" -le 3 && "$3" == prepare ]]
  echo prepare >> "$fixture/calls"
  echo 43 > "$fixture/pid"
  echo 43
}
query_current_runtime() {
  local pid
  pid="$(particeps_pid)"
  if [[ "$die_again" == true ]]; then
    : > "$fixture/pid"
    echo "$pid|RUNNING:5"
  else
    echo "$pid|PAUSED:5"
  fi
}
query_live_runtime() { echo PAUSED:5; }
await_permission_revoke_pause 42 3
printf '%s|%s' "$permission_revoke_process_continuity" "$permission_revoke_recovery_action"
'''
            result = subprocess.run(["bash", "-s"], input=script, text=True, capture_output=True,
                env={**os.environ, "fixture": temporary, "die_again": str(die_again).lower()})
            calls = (directory / "calls").read_text().splitlines() if (directory / "calls").exists() else []
            return result, calls

    def test_original_process_is_never_prepared_or_reopened(self):
        result, calls = self.run_recovery("42")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("true|none", result.stdout)
        self.assertEqual([], calls)

    def test_lost_or_replaced_process_is_explicitly_reopened_only_once(self):
        for pid in ("", "43"):
            with self.subTest(pid=pid):
                result, calls = self.run_recovery(pid)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("false|explicit_app_reopen", result.stdout)
                self.assertEqual(["prepare"], calls)

    def test_second_process_loss_fails_without_another_reopen(self):
        result, calls = self.run_recovery("", die_again=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(["prepare"], calls)


if __name__ == "__main__":
    unittest.main()
