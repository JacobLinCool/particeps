import copy
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from tools.android_host_control import ControlFailure, HostControl, PACKAGE
from tools.android_reboot_proof import BOOT_ID_COMMAND, EVENTS_COMMAND, process_events, qualify, safety_pause_proof

OLD_BOOT = "10000000-0000-4000-8000-000000000001"
NEW_BOOT = "10000000-0000-4000-8000-000000000002"
PROCESS = "20000000-0000-4000-8000-000000000001"
OTHER_PROCESS = "20000000-0000-4000-8000-000000000002"
OPERATION = "30000000-0000-4000-8000-000000000001"


def proof():
    first = {"initialized": True, "state": "PAUSED", "lifetime_data_event_count": 7,
             "elapsed_realtime_millis": 8000, "notification_tag": "particeps-recovery",
             "notification_body": "收集已暫停", "expected_notification_body": "收集已暫停"}
    return {"schema_version": 1, "status": "VERIFIED_SAFETY_PAUSED", "observations": [
        first, {**first, "elapsed_realtime_millis": 9000},
    ]}


def event(tag="am_proc_start", pid="42", process=PACKAGE):
    # Payload copied from stock API37 failure-logcat-all.txt line856; epoch header
    # is the explicitly selected AOSP threadtime+epoch rendering of that event.
    fields = f"0,{pid},10229,{process},next-top-activity,{{{PACKAGE}/{PACKAGE}.MainActivity}}"
    if tag != "am_proc_start":
        fields = f"0,{pid},{process},0,reason,which may contain commas"
    return f"1791176654.461   645   674 I {tag}: [{fields}]\n"


class FakeAdb:
    def __init__(self):
        self.now = 0.0
        self.pid = "42"
        self.boot_ids = [NEW_BOOT, NEW_BOOT]
        self.events = "--------- beginning of events\n" + event()
        self.calls = []
        self.ready_delay = 0.0
        self.events_delay = 0.0
        self.fail_events = False
        self.pid_after_events = None
        self.proof = proof()
        self.responses = [
            {"status": "READY"},
            {"status": "RUNNING", "operation_id": OPERATION},
            {"status": "SUCCEEDED", "operation_id": OPERATION},
        ]

    def clock(self):
        return self.now

    def sleep(self, duration):
        self.now += duration

    def run(self, command, **kwargs):
        self.calls.append((command, kwargs["timeout"]))
        args = command[1:]
        if args == BOOT_ID_COMMAND:
            return SimpleNamespace(returncode=0, stdout=self.boot_ids.pop(0) + "\n")
        if args == EVENTS_COMMAND:
            self.now += self.events_delay
            if self.pid_after_events is not None:
                self.pid = self.pid_after_events
            if self.fail_events:
                raise subprocess.TimeoutExpired(command, kwargs["timeout"])
            return SimpleNamespace(returncode=0, stdout=self.events)
        if "pidof" in args:
            return SimpleNamespace(returncode=0, stdout=self.pid)
        if "start" in args:
            return SimpleNamespace(returncode=0, stdout="Status: ok\n")
        response = self.responses.pop(0)
        if callable(response):
            response = response(self)
        if response["status"] == "READY":
            self.now += self.ready_delay
        if response["status"] == "SUCCEEDED":
            response = {**response, "result": json.dumps(self.proof)}
        value = {"schema_version": 1, "process_id": PROCESS, **response}
        return SimpleNamespace(returncode=0, stdout=f'Broadcast completed: result=-1, data="{json.dumps(value)}"\n')

    def control(self):
        return HostControl(["adb"], run=self.run, clock=self.clock, sleep=self.sleep)


class SafetyPauseProofTest(unittest.TestCase):
    def test_two_localized_notification_observations_match(self):
        value = proof()
        self.assertEqual(value, safety_pause_proof(json.dumps(value)))

    def test_duplicate_proof_fields_are_not_silently_overwritten(self):
        raw = json.dumps(proof()).replace('"schema_version": 1', '"schema_version": 0, "schema_version": 1')
        with self.assertRaisesRegex(ControlFailure, "Duplicate"):
            safety_pause_proof(raw)

    def test_android_long_maximum_is_exact_and_overflow_is_rejected(self):
        value = proof()
        for item in value["observations"]:
            item["lifetime_data_event_count"] = 2**63 - 1
        value["observations"][0]["elapsed_realtime_millis"] = 2**63 - 1001
        value["observations"][1]["elapsed_realtime_millis"] = 2**63 - 1
        self.assertEqual(value, safety_pause_proof(json.dumps(value)))
        for field in ("lifetime_data_event_count", "elapsed_realtime_millis"):
            invalid = copy.deepcopy(value)
            invalid["observations"][1][field] = 2**63
            with self.subTest(field=field), self.assertRaises(ControlFailure):
                safety_pause_proof(json.dumps(invalid))

    def test_each_missing_or_false_fact_is_rejected(self):
        for name, invalid in (("initialized", False), ("initialized", 1), ("state", "RUNNING"),
                              ("notification_tag", "particeps-foreground"), ("notification_body", "wrong"),
                              ("notification_body", ""), ("lifetime_data_event_count", True),
                              ("lifetime_data_event_count", -1), ("elapsed_realtime_millis", 1.5)):
            for index in (0, 1):
                with self.subTest(name=name, invalid=invalid, index=index):
                    value = proof()
                    value["observations"][index][name] = invalid
                    with self.assertRaises(ControlFailure):
                        safety_pause_proof(json.dumps(value))
        for alter in (
            lambda v: v["observations"].pop(),
            lambda v: v["observations"].append(copy.deepcopy(v["observations"][1])),
            lambda v: v["observations"][1].update(elapsed_realtime_millis=8999),
            lambda v: v["observations"][1].update(lifetime_data_event_count=8),
            lambda v: v.update(schema_version=True),
            lambda v: v.update(status="PAUSED"),
            lambda v: v["observations"][0].pop("initialized"),
        ):
            value = proof()
            alter(value)
            with self.assertRaises(ControlFailure):
                safety_pause_proof(json.dumps(value))


class ProcessEventTest(unittest.TestCase):
    def test_observed_api37_process_prefix_and_unrelated_processes(self):
        events = event(process="cool.jacoblin.particeps.fixture.targeta") + event()
        self.assertEqual(1, len(process_events(events, "42")))

    def test_missing_malformed_or_replaced_main_start_is_not_evidence(self):
        for events in ("", event(pid="41"), event() + event(pid="43"), event() + event(),
                       event().replace("0,42,10229", "0,42,no_uid"),
                       event().replace("1791176654.461", "10-05 13:04:14.461"),
                       event().replace("[0,", "[10,")):
            with self.subTest(events=events), self.assertRaises(ControlFailure):
                process_events(events, "42")

    def test_main_and_subprocess_crash_anr_or_death_always_fail(self):
        for tag in ("am_proc_died", "am_anr", "am_crash"):
            for process in (PACKAGE, PACKAGE + ":worker"):
                with self.subTest(tag=tag, process=process), self.assertRaises(ControlFailure):
                    process_events(event(tag, "41", process) + event(), "42")


class RebootQualificationTest(unittest.TestCase):
    def invoke(self, adb, output, timeout=105.0):
        with patch("tools.android_host_control.uuid.uuid4", return_value=OPERATION):
            return qualify(adb.control(), OLD_BOOT, output, timeout)

    def test_single_ordinary_launch_and_single_admission_with_success_evidence(self):
        adb = FakeAdb()
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "proof"
            result = self.invoke(adb, output)
            self.assertEqual("VERIFIED_SAFETY_PAUSED", result["status"])
            self.assertEqual(PROCESS, result["process_id"])
            self.assertEqual(adb.events, (output / "events.txt").read_text())
            self.assertEqual(result, json.loads((output / "result.json").read_text()))
        calls = [command for command, _ in adb.calls]
        self.assertEqual(1, sum("start" in c for c in calls))
        self.assertEqual(1, sum("include_safety_pause_proof" in c for c in calls))
        self.assertEqual(1, sum("operation_status" in c for c in calls))
        self.assertFalse(any(token in c for c in calls for token in ("instrument", "force-stop", "reset", "reboot")))
        self.assertTrue(all(0 < timeout <= 30 for _, timeout in adb.calls))

    def test_crash_before_first_receipt_then_restart_cannot_become_pass(self):
        adb = FakeAdb()
        adb.events = event(pid="41") + event("am_crash", "41") + event()
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "proof"
            with self.assertRaisesRegex(ControlFailure, "am_crash"):
                self.invoke(adb, output)
            record = json.loads((output / "result.json").read_text())
            self.assertEqual("FAILED", record["status"])
            self.assertTrue((output / "safety-pause-proof.json").exists())
            self.assertTrue((output / "failure-events.txt").exists())
        self.assertEqual(1, sum("start" in c for c, _ in adb.calls))

    def test_same_boot_and_second_reboot_are_rejected(self):
        for boots in ([OLD_BOOT], [NEW_BOOT, OTHER_PROCESS]):
            adb = FakeAdb()
            adb.boot_ids = boots
            with self.subTest(boots=boots), tempfile.TemporaryDirectory() as temporary:
                with self.assertRaises(ControlFailure):
                    self.invoke(adb, Path(temporary) / "proof")

    def test_uuid_change_and_pid_change_reject_without_restart(self):
        def change_pid(adb):
            adb.pid = "43"
            return {"status": "RUNNING", "operation_id": OPERATION}
        for change in (change_pid, {"status": "RUNNING", "operation_id": OPERATION, "process_id": OTHER_PROCESS}):
            adb = FakeAdb()
            adb.responses[1] = change
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temporary:
                with self.assertRaisesRegex(ControlFailure, "changed"):
                    self.invoke(adb, Path(temporary) / "proof")
            self.assertEqual(1, sum("start" in c for c, _ in adb.calls))

    def test_last_process_check_rejects_death_after_proof_and_events(self):
        for pid in ("", "43"):
            adb = FakeAdb()
            adb.pid_after_events = pid
            with self.subTest(pid=pid), tempfile.TemporaryDirectory() as temporary:
                with self.assertRaises(ControlFailure):
                    self.invoke(adb, Path(temporary) / "proof")

    def test_readiness_and_evidence_share_deadline_no_late_pass(self):
        adb = FakeAdb()
        adb.ready_delay, adb.events_delay = 2, 1
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "proof"
            with self.assertRaisesRegex(ControlFailure, "deadline"):
                self.invoke(adb, output, timeout=3)
            self.assertEqual("FAILED", json.loads((output / "result.json").read_text())["status"])
        first_events = next(timeout for command, timeout in adb.calls if command[1:] == EVENTS_COMMAND)
        self.assertLess(first_events, 1)

    def test_capture_failure_is_not_pass_and_does_not_mask_original_failure(self):
        adb = FakeAdb()
        adb.fail_events = True
        adb.proof["observations"][1]["lifetime_data_event_count"] = 8
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "proof"
            with self.assertRaisesRegex(ControlFailure, "admission advanced"):
                self.invoke(adb, output)
            record = json.loads((output / "result.json").read_text())
            self.assertEqual("FAILED", record["status"])
            self.assertEqual("TimeoutExpired", record["diagnostic_capture_failure"])
        adb = FakeAdb()
        adb.fail_events = True
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(subprocess.TimeoutExpired):
                self.invoke(adb, Path(temporary) / "proof")

    def test_result_write_error_preserves_original_failure(self):
        original_write = Path.write_text

        def fail_result(path, *args, **kwargs):
            if path.name == "result.json":
                raise OSError("disk unavailable")
            return original_write(path, *args, **kwargs)

        adb = FakeAdb()
        adb.proof["observations"][1]["lifetime_data_event_count"] = 8
        with tempfile.TemporaryDirectory() as temporary, patch.object(Path, "write_text", fail_result), patch("sys.stderr", new_callable=io.StringIO):
            with self.assertRaisesRegex(ControlFailure, "admission advanced"):
                self.invoke(adb, Path(temporary) / "proof")
        with tempfile.TemporaryDirectory() as temporary, patch.object(Path, "write_text", fail_result):
            with self.assertRaises(OSError):
                self.invoke(FakeAdb(), Path(temporary) / "proof")

    def test_deadline_cannot_be_extended_or_nonfinite(self):
        for timeout in (0, -1, 106, float("nan"), float("inf")):
            with self.subTest(timeout=timeout), tempfile.TemporaryDirectory() as temporary:
                with self.assertRaises(ValueError):
                    self.invoke(FakeAdb(), Path(temporary) / "proof", timeout)


if __name__ == "__main__":
    unittest.main()
