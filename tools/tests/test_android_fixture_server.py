import argparse
import errno
import json
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from tools import android_fixture_server as server
from tools.android_fixture_server import throughput_bounds, validate_measurement


def steady_targets(duration: int = 60) -> list[server.ConnectionMetrics]:
    targets = [server.ConnectionMetrics("target", index) for index in range(2)]
    for target in targets:
        for second in range(duration):
            target.received(4_000, second + 0.5)
    return targets


class AndroidFixtureServerTest(unittest.TestCase):
    def test_all_apps_server_publishes_ready_port_and_returns_exact_payload(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            ready = Path(temporary) / "ready"
            process = subprocess.Popen([
                sys.executable,
                str(Path(__file__).resolve().parents[1] / "all_apps_fixture_server.py"),
                "--port", "0", "--ready", str(ready),
            ], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
            try:
                deadline = time.monotonic() + 5
                while not ready.exists() and process.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.01)
                if process.poll() is not None:
                    self.fail(f"Fixture server exited before readiness: {process.communicate()[1]}")
                self.assertTrue(ready.exists(), "Fixture server did not become ready")
                port = int(ready.read_text())
                with socket.create_connection(("127.0.0.1", port), timeout=5) as client:
                    client.sendall(b"\x01")
                    chunks = []
                    while chunk := client.recv(64 * 1024):
                        chunks.append(chunk)
                self.assertEqual(b"Z" * 262_144, b"".join(chunks))
                with socket.create_connection(("127.0.0.1", port), timeout=5) as client:
                    client.sendall(b"\x02")
                    self.assertEqual(b"", client.recv(1))
            finally:
                process.terminate()
                process.communicate(timeout=5)

    def test_exact_si_kbps_bounds_include_payload_floor_five_percent_and_one_mtu(self) -> None:
        self.assertEqual((408_000, 505_500), throughput_bounds(64, 60))
        self.assertEqual((3_264_000, 4_033_500), throughput_bounds(512, 60))
        self.assertEqual((26_112_000, 32_257_500), throughput_bounds(4096, 60))
        self.assertEqual((16_320_000, 20_161_500), throughput_bounds(512, 300))

    def test_target_must_reach_payload_floor_stay_below_cap_and_control_must_bypass(self) -> None:
        result = validate_measurement(64, 60, 480_000, 900_000, steady_targets())
        self.assertTrue(result.passed)
        self.assertEqual((408_000, 505_500), (result.lower_bound_bytes, result.upper_bound_bytes))
        for target_bytes, control_bytes, reason in (
            (407_999, 900_000, "target_below_payload_floor"),
            (505_501, 900_000, "target_above_payload_ceiling"),
            (480_000, 505_500, "control_did_not_bypass"),
        ):
            with self.subTest(reason=reason):
                result = validate_measurement(64, 60, target_bytes, control_bytes, steady_targets())
                self.assertFalse(result.passed)
                self.assertFalse(result.rate_passed)
                self.assertTrue(result.liveness_passed)
                self.assertEqual((reason,), result.failure_reasons)

    def test_each_target_may_have_five_but_not_six_consecutive_zero_buckets(self) -> None:
        for gap, expected in ((5, True), (6, False)):
            with self.subTest(gap=gap):
                targets = steady_targets()
                for second in range(20, 20 + gap):
                    del targets[1].bytes_by_second[second]
                    targets[1].received_bytes -= 4_000
                result = validate_measurement(
                    64, 60, sum(target.received_bytes for target in targets), 900_000, targets,
                )
                self.assertTrue(result.rate_passed)
                self.assertEqual(expected, result.liveness_passed)
                self.assertEqual(expected, result.passed)
                self.assertEqual([0, gap], [
                    target.longest_zero_payload_seconds_after_warmup
                    for target in result.target_liveness
                ])
                self.assertEqual(
                    () if expected else ("target_1_zero_payload_run_exceeded",),
                    result.failure_reasons,
                )

    def test_warmup_and_deadline_tail_do_not_change_complete_bucket_liveness(self) -> None:
        targets = steady_targets()
        # Ten startup zero buckets plus five in-window zeros must not become 15.
        targets[0].bytes_by_second = {second: 4_000 for second in range(15, 60)}
        # Bucket 59 is complete and included; tail buckets 60+ cannot hide the gap.
        targets[1].bytes_by_second = {second: 4_000 for second in range(54)}
        targets[1].received(20_000, 60.01)
        targets[1].received(20_000, 61.01)
        result = validate_measurement(64, 60, 480_000, 900_000, targets)
        self.assertEqual([5, 6], [
            target.longest_zero_payload_seconds_after_warmup for target in result.target_liveness
        ])
        self.assertFalse(result.passed)
        self.assertEqual({
            "warmup_seconds": 10,
            "start_second_inclusive": 10,
            "end_second_exclusive": 60,
            "max_consecutive_zero_payload_seconds": 5,
        }, result.document()["liveness_window"])

    def test_positive_bucket_resets_run_and_empty_target_cannot_pass_via_other_target(self) -> None:
        targets = steady_targets()
        for second in list(range(10, 15)) + list(range(16, 21)):
            targets[0].bytes_by_second[second] = 0
        targets[1].bytes_by_second.clear()
        result = validate_measurement(64, 60, 480_000, 900_000, targets)
        self.assertTrue(result.rate_passed)
        self.assertEqual([5, 50], [
            target.longest_zero_payload_seconds_after_warmup for target in result.target_liveness
        ])
        self.assertFalse(result.liveness_passed)

    def test_five_minute_liveness_checks_last_complete_bucket(self) -> None:
        targets = steady_targets(300)
        for second in range(294, 300):
            del targets[0].bytes_by_second[second]
        result = validate_measurement(64, 300, 2_400_000, 9_000_000, targets)
        self.assertTrue(result.rate_passed)
        self.assertFalse(result.passed)
        self.assertEqual(6, result.target_liveness[0].longest_zero_payload_seconds_after_warmup)
        self.assertEqual(300, result.document()["liveness_window"]["end_second_exclusive"])

    def test_liveness_rejects_missing_duplicate_or_wrong_role_targets(self) -> None:
        for targets in ([], steady_targets()[:1], [steady_targets()[0]] * 2, [
            server.ConnectionMetrics("target", 0), server.ConnectionMetrics("control", 1),
        ]):
            with self.subTest(targets=targets):
                with self.assertRaisesRegex(ValueError, "every distinct target"):
                    validate_measurement(64, 60, 480_000, 900_000, targets)
        with self.assertRaisesRegex(ValueError, "after liveness warmup"):
            validate_measurement(64, 10, 80_000, 90_000, steady_targets(10))

    def test_received_bytes_are_partitioned_by_recv_completion_second(self) -> None:
        connection = Mock()
        connection.recv.side_effect = [b"abc", b"defg", b""]
        counter = server.ByteCounter()
        metrics = server.ConnectionMetrics("target", 0)
        with patch.object(server.time, "monotonic", side_effect=[
            100.0, 100.2, 101.0, 101.5, 101.6, 101.7,
        ]):
            server.receive_until(connection, 160.0, counter, metrics, 100.0)

        self.assertEqual(7, counter.value)
        self.assertEqual(counter.value, metrics.received_bytes)
        self.assertEqual({0: 3, 1: 4}, metrics.bytes_by_second)
        self.assertEqual(counter.value, sum(metrics.bytes_by_second.values()))
        self.assertAlmostEqual(0.2, metrics.first_byte_seconds)
        self.assertAlmostEqual(1.5, metrics.last_byte_seconds)
        self.assertAlmostEqual(1.7, metrics.ended_seconds)
        self.assertEqual("eof", metrics.end_reason)
        self.assertIsNone(metrics.error_errno)

    def test_empty_eof_and_deadline_have_distinct_reasons_without_fabricated_byte_times(self) -> None:
        for reason, reads, clock in (
            ("eof", [b""], [100.0, 100.1]),
            ("deadline", [], [160.0, 160.0]),
            ("deadline", [TimeoutError()], [159.0, 160.0, 160.0]),
        ):
            with self.subTest(reason=reason, reads=reads):
                connection = Mock()
                connection.recv.side_effect = reads
                counter = server.ByteCounter()
                metrics = server.ConnectionMetrics("target", 0)
                with patch.object(server.time, "monotonic", side_effect=clock):
                    server.receive_until(connection, 160.0, counter, metrics, 100.0)
                self.assertEqual(reason, metrics.end_reason)
                self.assertEqual(0, counter.value)
                self.assertEqual(0, metrics.received_bytes)
                self.assertEqual({}, metrics.bytes_by_second)
                self.assertIsNone(metrics.first_byte_seconds)
                self.assertIsNone(metrics.last_byte_seconds)
                self.assertIsNone(metrics.error_errno)

    def test_socket_error_retains_only_errno_without_endpoint_or_exception_text(self) -> None:
        connection = Mock()
        connection.recv.side_effect = OSError(errno.ECONNRESET, "private-endpoint-secret")
        metrics = server.ConnectionMetrics("control", 0)
        with patch.object(server.time, "monotonic", side_effect=[100.0, 100.4]):
            server.receive_until(connection, 160.0, server.ByteCounter(), metrics, 100.0)
        self.assertEqual("error", metrics.end_reason)
        self.assertEqual(errno.ECONNRESET, metrics.error_errno)
        self.assertIsNone(metrics.first_byte_seconds)
        self.assertNotIn("private-endpoint-secret", json.dumps(metrics.document()))

    def test_existing_boundary_recv_tail_remains_counted_in_its_actual_second(self) -> None:
        connection = Mock()
        connection.recv.return_value = b"tail"
        counter = server.ByteCounter()
        metrics = server.ConnectionMetrics("target", 0)
        with patch.object(server.time, "monotonic", side_effect=[
            159.9, 160.2, 160.2, 160.2,
        ]):
            server.receive_until(connection, 160.0, counter, metrics, 100.0)
        self.assertEqual(4, counter.value)
        self.assertEqual({60: 4}, metrics.bytes_by_second)
        self.assertEqual("deadline", metrics.end_reason)
        connection.recv.assert_called_once()

    def test_three_connection_barrier_publishes_consistent_private_metrics(self) -> None:
        pairs = [socket.socketpair() for _ in range(3)]
        clients = [pair[1] for pair in pairs]
        accepted = [pair[0] for pair in pairs]
        payloads = [b"a" * 2_003, b"b" * 5_007, b"c" * 11_009]
        barriers: list[bytes | None] = [None, None, None]

        def send(index: int) -> None:
            client = clients[index]
            client.settimeout(5)
            barriers[index] = client.recv(1)
            if barriers[index] == b"\x01":
                client.sendall(payloads[index])
            client.shutdown(socket.SHUT_WR)

        workers = [threading.Thread(target=send, args=(index,)) for index in range(3)]
        try:
            for worker in workers:
                worker.start()
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                args = argparse.Namespace(
                    target_port=19092, control_port=19093, duration_seconds=60,
                    cap_kbps=64, ready=root / "ready", output=root / "metrics.json",
                )
                with (
                    patch.object(server.socket, "create_server", side_effect=[Mock(), Mock()]),
                    patch.object(server, "accept_connections", side_effect=[accepted[:2], accepted[2:]]),
                ):
                    self.assertFalse(server.run(args))
                document = json.loads(args.output.read_text())
            for worker in workers:
                worker.join(5)
                self.assertFalse(worker.is_alive())
            self.assertEqual([b"\x01"] * 3, barriers)
            self.assertEqual(60, document["duration_seconds"])
            self.assertEqual((408_000, 505_500), (
                document["lower_bound_bytes"], document["upper_bound_bytes"],
            ))
            self.assertEqual(1, document["bytes_bucket_width_seconds"])
            self.assertFalse(document["rate_passed"])
            self.assertFalse(document["liveness_passed"])
            self.assertEqual([50, 50], [
                item["longest_zero_payload_seconds_after_warmup"]
                for item in document["target_liveness"]
            ])
            self.assertEqual(
                [("target", 0), ("target", 1), ("control", 0)],
                [(item["role"], item["index"]) for item in document["connections"]],
            )
            for item, payload in zip(document["connections"], payloads, strict=True):
                self.assertEqual(len(payload), item["received_bytes"])
                self.assertEqual(len(payload), sum(bucket["bytes"] for bucket in item["bytes_by_second"]))
                self.assertEqual("eof", item["end_reason"])
                self.assertIsNone(item["error_errno"])
                self.assertGreaterEqual(item["barrier_sent_seconds"], 0)
                self.assertLess(item["barrier_sent_seconds"], 60)
                self.assertGreaterEqual(item["first_byte_seconds"], 0)
                self.assertLessEqual(item["first_byte_seconds"], item["last_byte_seconds"])
                self.assertLessEqual(item["last_byte_seconds"], item["ended_seconds"])
            for role in ("target", "control"):
                connections = [item for item in document["connections"] if item["role"] == role]
                self.assertEqual(document[f"{role}_connections"], len(connections))
                self.assertEqual(document[f"{role}_bytes"], sum(item["received_bytes"] for item in connections))
            self.assertNotIn("127.0.0.1", json.dumps(document))
            self.assertNotIn("19092", json.dumps(document))
        finally:
            for connection in accepted + clients:
                connection.close()
            for worker in workers:
                if worker.ident is not None:
                    worker.join(5)


if __name__ == "__main__":
    unittest.main()
