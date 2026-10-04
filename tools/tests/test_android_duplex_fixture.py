import argparse
import json
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import Mock, patch

from tools import android_duplex_fixture as duplex

ROOT = Path(__file__).resolve().parents[2]
IDENTITY = "6e45c574-6f83-4f13-9f89-9c087d54c9ca"


def observation(cap_kbps=512):
    def connection(role, amount):
        return {"role": role, "index": 0, "received_bytes": amount * 60,
            "end_reason": "deadline", "error_errno": None,
            "bytes_by_second": [{"second": second, "bytes": amount} for second in range(60)]}
    shared = {"schema_version": 1, "measurement_id": IDENTITY, "duration_seconds": 60}
    host = {**shared, "completed": True, "download_barrier_ack_seconds": 0.2,
        "upload": connection("target", cap_kbps * 1000 // 8), "control": connection("control", 100_000),
        "download_sender": {"socket_accepted_bytes": 9_000_000, "end_reason": "error",
            "error_errno": 32, "ended_host_seconds": 60.2}}
    download = {**shared, **connection("target", cap_kbps * 1000 // 8), "direction": "download",
        "payload_valid": True, "error_type": None, "fixture_role": "target_b",
        "timing_basis": "android_elapsed_realtime_since_validated_barrier",
        "started_elapsed_realtime_nanos": 1_000_000_000,
        "ended_elapsed_realtime_nanos": 61_000_000_000}
    return host, download


class AndroidDuplexFixtureTest(unittest.TestCase):
    def test_only_receiver_payload_counts_decide_both_direction_bounds(self):
        host, download = observation()
        result = duplex.validate_duplex(host, download, IDENTITY, 512)
        self.assertTrue(result["passed"])
        self.assertEqual((3_264_000, 4_033_500), (result["lower_bound_bytes"], result["upper_bound_bytes"]))
        host["download_sender"]["socket_accepted_bytes"] = 0
        self.assertEqual(result, duplex.validate_duplex(host, download, IDENTITY, 512))
        for direction in ("upload", "download"):
            for amount in (50_000, 70_000):
                host, download = observation()
                target = host["upload"] if direction == "upload" else download
                for bucket in target["bytes_by_second"]:
                    bucket["bytes"] = amount
                target["received_bytes"] = amount * 60
                result = duplex.validate_duplex(host, download, IDENTITY, 512)
                self.assertFalse(result["passed"])
                self.assertEqual([f"{direction}_outside_payload_bounds"], result["failure_reasons"])

    def test_each_direction_requires_progress_and_control_requires_bypass(self):
        for cap in (64, 512):
            for direction in ("upload", "download"):
                for gap, expected in ((5, True), (6, False)):
                    with self.subTest(cap=cap, direction=direction, gap=gap):
                        host, download = observation(cap)
                        target = host["upload"] if direction == "upload" else download
                        for second in range(20, 20 + gap):
                            target["bytes_by_second"][second]["bytes"] = 0
                            target["received_bytes"] -= cap * 1000 // 8
                        result = duplex.validate_duplex(host, download, IDENTITY, cap)
                        self.assertTrue(result["directions"][direction]["rate_passed"])
                        self.assertEqual(expected, result["passed"])
                        self.assertEqual(gap, result["directions"][direction]["longest_zero_payload_seconds_after_warmup"])
        host, download = observation()
        for bucket in host["control"]["bytes_by_second"]:
            bucket["bytes"] = 64_000
        host["control"]["received_bytes"] = 3_840_000
        self.assertEqual(["control_did_not_bypass"], duplex.validate_duplex(host, download, IDENTITY, 512)["failure_reasons"])

    def test_explicit_caps_use_same_exact_bounds_for_both_directions(self):
        for cap, lower, upper in ((64, 408_000, 505_500), (512, 3_264_000, 4_033_500)):
            for direction in ("upload", "download"):
                for total, passed in ((lower - 1, False), (lower, True), (upper, True), (upper + 1, False)):
                    with self.subTest(cap=cap, direction=direction, total=total):
                        host, download = observation(cap)
                        target = host["upload"] if direction == "upload" else download
                        amount, remainder = divmod(total, 60)
                        target["bytes_by_second"] = [
                            {"second": second, "bytes": amount + (remainder if second == 59 else 0)}
                            for second in range(60)
                        ]
                        target["received_bytes"] = total
                        result = duplex.validate_duplex(host, download, IDENTITY, cap)
                        self.assertEqual((lower, upper), (result["lower_bound_bytes"], result["upper_bound_bytes"]))
                        self.assertEqual(cap, result["cap_kbps"])
                        self.assertEqual(passed, result["passed"])
            host, download = observation(cap)
            amount, remainder = divmod(upper, 60)
            host["control"]["received_bytes"] = upper
            host["control"]["bytes_by_second"] = [
                {"second": second, "bytes": amount + (remainder if second == 59 else 0)} for second in range(60)
            ]
            self.assertEqual(["control_did_not_bypass"], duplex.validate_duplex(host, download, IDENTITY, cap)["failure_reasons"])
        for invalid in (0, 65, 4096, True, 64.0):
            with self.subTest(invalid=invalid), self.assertRaisesRegex(ValueError, "Duplex cap"):
                duplex.validate_duplex(*observation(), IDENTITY, invalid)

    def test_validator_cli_requires_explicit_supported_cap(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            host, download = observation(64)
            (directory / "host.json").write_text(json.dumps(host))
            (directory / "download.json").write_text(json.dumps(download))
            command = [sys.executable, "-m", "tools.android_duplex_fixture", "validate",
                       "--measurement-id", IDENTITY, "--host", str(directory / "host.json"),
                       "--download", str(directory / "download.json"), "--output", str(directory / "result.json")]
            for arguments, status in (([], 2), (["--cap-kbps", "4096"], 2), (["--cap-kbps", "64"], 0)):
                result = subprocess.run(command + arguments, cwd=ROOT, capture_output=True, text=True)
                self.assertEqual(status, result.returncode, result.stderr)
            result = json.loads((directory / "result.json").read_text())
            self.assertEqual(64, result["cap_kbps"])
            self.assertTrue(result["passed"])

    def test_host_deadline_tail_cannot_satisfy_upload_floor_or_control_bypass(self):
        for role, total, reason in (
            ("upload", 3_264_000 - 1, "upload_outside_payload_bounds"),
            ("control", 4_033_500, "control_did_not_bypass"),
        ):
            host, download = observation()
            amount, remainder = divmod(total, 60)
            host[role]["bytes_by_second"] = [
                {"second": second, "bytes": amount + (remainder if second == 59 else 0)}
                for second in range(60)
            ] + [{"second": 60, "bytes": 1_500}]
            host[role]["received_bytes"] = total + 1_500
            with self.assertRaisesRegex(ValueError, "Invalid bounded receive buckets"):
                duplex.validate_duplex(host, download, IDENTITY, 512)

    def test_stale_malformed_incomplete_or_corrupt_receiver_evidence_is_rejected(self):
        mutations = (
            lambda h, d: d.update(measurement_id=str(uuid.uuid4())),
            lambda h, d: d.update(duration_seconds=59),
            lambda h, d: d.update(fixture_role="control"),
            lambda h, d: d.update(payload_valid=False),
            lambda h, d: d.update(end_reason="eof"),
            lambda h, d: d.update(end_reason="error", error_type="SocketException"),
            lambda h, d: d.update(ended_elapsed_realtime_nanos=60_999_999_999),
            lambda h, d: d.update(started_elapsed_realtime_nanos=None),
            lambda h, d: d.update(received_bytes=d["received_bytes"] + 1),
            lambda h, d: d["bytes_by_second"].pop(),
            lambda h, d: d["bytes_by_second"][59].update(second=60),
            lambda h, d: d["bytes_by_second"][59].update(second=58),
            lambda h, d: d["bytes_by_second"][59].update(bytes=True),
            lambda h, d: h.update(completed=False),
            lambda h, d: h.update(download_barrier_ack_seconds=1.00001),
            lambda h, d: h.update(download_barrier_ack_seconds=float("nan")),
            lambda h, d: h["download_sender"].update(ended_host_seconds=59.99),
            lambda h, d: h["upload"].update(end_reason="eof"),
            lambda h, d: h["control"].update(end_reason="error"),
        )
        for mutate in mutations:
            host, download = observation()
            mutate(host, download)
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                duplex.validate_duplex(host, download, IDENTITY, 512)

    def test_protocol_frame_requires_exact_identity_and_nonempty_complete_read(self):
        reader = Mock()
        reader.recv.side_effect = [b"ab", b"c"]
        self.assertEqual(b"abc", duplex.receive_exact(reader, 3, time.monotonic() + 1))
        reader.recv.side_effect = [b"a", b""]
        with self.assertRaises(EOFError):
            duplex.receive_exact(reader, 3, time.monotonic() + 1)
        with self.assertRaises(TimeoutError):
            duplex.receive_exact(reader, 3, time.monotonic() - 1)

    def test_real_java_receiver_validates_content_and_excludes_deadline_tail(self):
        source = ROOT / "test-fixtures/traffic-common/src/main/java/cool/jacoblin/particeps/fixtures/traffic/DownloadMeasurement.java"
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            probe = directory / "Probe.java"
            probe.write_text('''import java.util.Arrays;
import cool.jacoblin.particeps.fixtures.traffic.DownloadMeasurement;
public class Probe { public static void main(String[] args) {
  String id = "6e45c574-6f83-4f13-9f89-9c087d54c9ca";
  byte[] bytes = new byte[100]; Arrays.fill(bytes, (byte) 'Z');
  DownloadMeasurement p = new DownloadMeasurement(id, "target_b"); p.start(100);
  p.received(bytes, 100, 100); p.received(bytes, 20, 59000000100L);
  p.received(bytes, 100, 60000000100L); // Boundary tail is not delivered in-window.
  p.finish("deadline", null, 60000000100L); System.out.print(p.json());
  DownloadMeasurement bad = new DownloadMeasurement(id, "target_b"); bad.start(100); bytes[99] = 'X';
  try { bad.received(bytes, 100, 200); throw new AssertionError(); }
  catch (IllegalStateException expected) { bad.finish("error", "IllegalStateException", 300); }
  System.out.print(bad.json());
  DownloadMeasurement eof = new DownloadMeasurement(id, "target_b"); eof.start(100);
  eof.finish("eof", null, 200); System.out.print(eof.json());
  DownloadMeasurement early = new DownloadMeasurement(id, "target_b"); early.start(100);
  try { early.finish("deadline", null, 200); throw new AssertionError(); }
  catch (IllegalStateException expected) { }
}}
''')
            subprocess.run(["javac", "-d", temporary, str(source), str(probe)], check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", temporary, "Probe"], check=True, capture_output=True, text=True)
        good, bad, eof = map(json.loads, result.stdout.splitlines())
        self.assertEqual(120, good["received_bytes"])
        self.assertEqual(60, len(good["bytes_by_second"]))
        self.assertEqual(120, sum(bucket["bytes"] for bucket in good["bytes_by_second"]))
        self.assertEqual(100, good["bytes_by_second"][0]["bytes"])
        self.assertEqual(20, good["bytes_by_second"][59]["bytes"])
        self.assertTrue(good["payload_valid"])
        self.assertEqual("deadline", good["end_reason"])
        self.assertFalse(bad["payload_valid"])
        self.assertEqual(0, bad["received_bytes"])
        self.assertEqual("error", bad["end_reason"])
        self.assertEqual("eof", eof["end_reason"])

    def test_three_socket_barrier_transfers_real_payload_and_retains_private_roles(self):
        pairs = [socket.socketpair() for _ in range(3)]
        accepted = [pair[0] for pair in pairs]
        for connection in accepted:
            connection.settimeout(duplex.IO_POLL_SECONDS)
        clients = [pair[1] for pair in pairs]
        done = threading.Event()
        errors = []
        downloaded = []
        identity = uuid.UUID(IDENTITY).bytes

        def client(index):
            try:
                connection = clients[index]
                connection.settimeout(2)
                if index == 1:
                    connection.sendall(identity)
                    self.assertEqual(b"\x01" + identity, duplex.receive_exact(connection, 17, time.monotonic() + 1))
                    connection.sendall(b"\x02" + identity)
                    deadline = time.monotonic() + 0.15
                    while time.monotonic() < deadline:
                        data = connection.recv(64 * 1024)
                        self.assertTrue(data and data == b"Z" * len(data))
                        downloaded.append(len(data))
                else:
                    self.assertEqual(b"\x01", connection.recv(1))
                    connection.sendall(b"A" * (1_000 + index))
                    done.wait(2)
            except BaseException as error:
                errors.append(error)
            finally:
                connection.close()

        threads = [threading.Thread(target=client, args=(index,)) for index in range(3)]
        try:
            for thread in threads:
                thread.start()
            with tempfile.TemporaryDirectory() as temporary:
                args = argparse.Namespace(measurement_id=IDENTITY, upload_port=19001, download_port=19002,
                    control_port=19003, ready=Path(temporary) / "ready", output=Path(temporary) / "host.json")
                with patch.object(duplex.socket, "create_server", side_effect=[Mock(), Mock(), Mock()]), patch.object(
                    duplex, "accept_connections", side_effect=[[item] for item in accepted],
                ), patch.object(duplex, "DURATION_SECONDS", 0.1), patch.object(duplex, "SENDER_LIMIT_SECONDS", 0.3):
                    self.assertTrue(duplex.run_server(args), args.output.read_text())
                result = json.loads(args.output.read_text())
            self.assertEqual(1_000, result["upload"]["received_bytes"])
            self.assertEqual(1_002, result["control"]["received_bytes"])
            self.assertLessEqual(result["download_barrier_ack_seconds"], 1)
            self.assertGreater(sum(downloaded), 0)
            self.assertNotIn("19001", json.dumps(result))
        finally:
            done.set()
            for connection in accepted + clients:
                connection.close()
            for thread in threads:
                thread.join(2)
        self.assertEqual([], errors)


if __name__ == "__main__":
    unittest.main()
