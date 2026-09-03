import unittest

from tools.android_fixture_server import throughput_bounds, validate_measurement


class AndroidFixtureServerTest(unittest.TestCase):
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
