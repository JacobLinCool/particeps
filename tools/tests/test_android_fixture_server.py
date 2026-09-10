import socket
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

from tools.android_fixture_server import throughput_bounds, validate_measurement


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

    def test_target_must_reach_payload_floor_stay_below_cap_and_control_must_bypass(self) -> None:
        self.assertEqual((True, 408_000, 505_500), validate_measurement(64, 60, 480_000, 900_000))
        self.assertFalse(validate_measurement(64, 60, 407_999, 900_000)[0])
        self.assertFalse(validate_measurement(64, 60, 505_501, 900_000)[0])
        self.assertFalse(validate_measurement(64, 60, 480_000, 505_500)[0])


if __name__ == "__main__":
    unittest.main()
