import copy
import json
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.android_host_diagnostics import capture, kernel_observation, monitor, native_observation, observed, process_observation, progress_observation

ROOT = Path(__file__).resolve().parents[2]


class AndroidHostDiagnosticsTest(unittest.TestCase):
    def observation(self):
        value = {
            "status": "VERIFIED", "admission_open": True, "revision": 6,
            "active_running_elapsed_millis": 0, "condition_epoch_id": "synthetic-epoch",
            "applied_resource_vector_sha256": "a" * 64, "profile_id": "cap-0512",
            "applied_profile_sha256": "b" * 64, "resource_generation": 1,
        }
        value["native_counters"] = {
            "sample_started_elapsed_realtime_nanos": 5, "sample_completed_elapsed_realtime_nanos": 7,
            "native_generation": 1, "vpn_generation_id": "synthetic-generation", "profile_sha256": "b" * 64,
            "uplink_bytes": 64000, "uplink_packets": 50, "downlink_bytes": 2600,
            "downlink_packets": 50, "uplink_throttled_nanos": 900000000, "downlink_throttled_nanos": 0,
        }
        return value

    def test_native_counter_identity_and_availability_cannot_be_fabricated(self):
        expected = self.observation()
        def decode(value):
            return native_observation(json.dumps(value), expected)
        self.assertEqual(64000, decode(expected)["native_counters"]["uplink_bytes"])
        mutations = (
            lambda v: v.pop("native_counters"),
            lambda v: v["native_counters"].pop("uplink_bytes"),
            lambda v: v["native_counters"].update(uplink_bytes=None),
            lambda v: v["native_counters"].update(uplink_bytes=True),
            lambda v: v["native_counters"].update(profile_sha256="c" * 64),
            lambda v: v["native_counters"].update(sample_completed_elapsed_realtime_nanos=4),
            lambda v: v.update(condition_epoch_id="other-epoch"),
            lambda v: v.update(status="PENDING"),
        )
        for mutate in mutations:
            value = copy.deepcopy(expected)
            mutate(value)
            with self.subTest(value=value), self.assertRaises((ValueError, KeyError)):
                decode(value)

    def test_commands_preserve_failure_timeout_and_parse_failure_without_zero_counts(self):
        for source, expected in (
            ("raise SystemExit(2)", "command_failed"),
            ("import time; time.sleep(2)", "timeout"),
            ("print('not json')", "invalid_observation"),
        ):
            result = observed([sys.executable, "-c", source], json.loads, timeout=0.1)
            self.assertEqual(expected, result["status"])
            self.assertNotIn("value", result)
            self.assertGreaterEqual(result["host_completed_monotonic_ns"], result["host_started_monotonic_ns"])

    def test_kernel_snapshot_preserves_retransmission_and_drop_counters(self):
        value = kernel_observation("Tcp: InSegs OutSegs RetransSegs InErrs OutRsts\nTcp: 10 20 3 0 1\ntun0: 1 2 0 4 0 0 0 0 5 6 0 7 0 0 0 0\n")
        self.assertEqual(3, value["tcp"]["RetransSegs"])
        self.assertEqual(7, value["tun"]["tun0"]["tx_dropped"])
        with self.assertRaises(ValueError):
            kernel_observation("cat: /proc/net/snmp: Permission denied\n")

    def test_process_snapshot_keeps_only_selected_process_state(self):
        value = process_observation("*APP* ProcessRecord{abc 123:cool.jacoblin.particeps/u0a123}\ncurAdj=200 setAdj=200 hasForegroundServices=true cached=false\n*APP* ProcessRecord{abc 124:other/u0a124}\ncached=true\n")
        self.assertEqual({"curAdj": "200", "setAdj": "200", "hasForegroundServices": "true", "cached": "false"}, value)
        with self.assertRaises(ValueError):
            process_observation("No processes found")

    def test_monitor_retains_unavailable_samples_and_stops_after_final_snapshot(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "samples.ndjson"
            stop = Path(temporary) / "stop"
            stop.touch()
            with patch("tools.android_host_diagnostics.signal.signal"), patch(
                "tools.android_host_diagnostics.observed", return_value={"status": "timeout"},
            ) as observe:
                monitor(["adb", "-s", "emulator-5584"], self.observation(), output, stop, identity={"pid": "42", "process_id": "fixed-process"})
            samples = [json.loads(line) for line in output.read_text().splitlines()]
        self.assertEqual(1, len(samples))
        self.assertEqual(6, observe.call_count)
        self.assertEqual("timeout", samples[0]["native"]["status"])
        self.assertEqual([("target", 0), ("target", 1), ("control", 0)], [(v["role"], v["index"]) for v in samples[0]["fixtures"]])
        self.assertTrue(all("value" not in v for v in samples[0]["fixtures"]))

    def test_failure_capture_bounds_dead_device_commands_and_records_all_statuses(self):
        with tempfile.TemporaryDirectory() as temporary:
            with patch("tools.android_host_diagnostics.subprocess.run", side_effect=subprocess.TimeoutExpired("adb", 15)) as run:
                capture(["adb", "-s", "emulator-5584"], Path(temporary))
            results = json.loads((Path(temporary) / "capture-result.json").read_text())
        self.assertEqual(4, len(results))
        self.assertTrue(all(result["status"] == "timeout" for result in results))
        self.assertTrue(all(call.kwargs["timeout"] == 15 for call in run.call_args_list))
        self.assertTrue(all(call.args[0][:3] == ["adb", "-s", "emulator-5584"] for call in run.call_args_list))

    def test_duplex_monitor_never_reads_stale_download_target_upload_progress(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "samples.ndjson"
            stop = Path(temporary) / "stop"
            stop.touch()
            with patch("tools.android_host_diagnostics.signal.signal"), patch(
                "tools.android_host_diagnostics.observed", return_value={"status": "timeout"},
            ) as observe:
                monitor(["adb", "-s", "emulator-5584"], self.observation(), output, stop, traffic_mode="duplex", identity={"pid": "42", "process_id": "fixed-process"})
            sample = json.loads(output.read_text())
        self.assertEqual("duplex", sample["traffic_mode"])
        self.assertEqual(5, observe.call_count)
        self.assertEqual([("target", 0), ("control", 0)], [(v["role"], v["index"]) for v in sample["fixtures"]])
        self.assertNotIn("cool.jacoblin.particeps.fixture.targetb", str(observe.call_args_list))

    def test_monitor_deadline_does_not_launch_more_commands_after_budget_expires(self):
        def consume_budget(command, parser, *, timeout):
            self.assertLessEqual(timeout, 0.03)
            time.sleep(timeout + 0.01)
            return {"status": "timeout"}
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "samples.ndjson"
            with patch("tools.android_host_diagnostics.signal.signal"), patch(
                "tools.android_host_diagnostics.observed", side_effect=consume_budget,
            ) as observe:
                monitor(["adb", "-s", "emulator-5584"], self.observation(), output, Path(temporary) / "stop", maximum_seconds=0.03, identity={"pid": "42", "process_id": "fixed-process"})
            samples = [json.loads(line) for line in output.read_text().splitlines()]
        self.assertEqual(1, observe.call_count)
        self.assertEqual(1, len(samples))
        self.assertTrue(all(item["status"] == "sampling_deadline" for item in samples[0]["fixtures"]))

    def test_real_java_progress_distinguishes_blocked_failed_and_completed_writes(self):
        source = ROOT / "test-fixtures/traffic-common/src/main/java/cool/jacoblin/particeps/fixtures/traffic/SaturationProgress.java"
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            probe = directory / "Probe.java"
            probe.write_text('''import cool.jacoblin.particeps.fixtures.traffic.SaturationProgress;
public class Probe { public static void main(String[] args) {
  SaturationProgress p = new SaturationProgress();
  System.out.print(p.json(100));
  p.awaitingBarrier(); p.beginWrite(1000); System.out.print(p.json(2000));
  p.completeWrite(3000, 65536); System.out.print(p.json(4000));
  p.beginWrite(5000); p.finish("SocketException", 32, 9000); System.out.print(p.json(10000));
}}''')
            subprocess.run(["javac", "-d", temporary, str(source), str(probe)], check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", temporary, "Probe"], check=True, capture_output=True, text=True)
        initial, blocked, completed, failed = map(progress_observation, result.stdout.splitlines())
        self.assertEqual("CONNECTING", initial["stage"])
        self.assertEqual(0, blocked["completed_bytes"])
        self.assertEqual(1000, blocked["current_write_elapsed_nanos"])
        self.assertEqual(65536, completed["completed_bytes"])
        self.assertEqual(2000, completed["completed_write_nanos"])
        self.assertIsNone(completed["current_write_elapsed_nanos"])
        self.assertEqual("FINISHED", failed["stage"])
        self.assertEqual("WRITING", failed["terminal_stage"])
        self.assertEqual(65536, failed["completed_bytes"])
        self.assertEqual(4000, failed["current_write_elapsed_nanos"])
        self.assertEqual("SocketException", failed["error_type"])
        self.assertEqual(32, failed["error_errno"])


if __name__ == "__main__":
    unittest.main()
