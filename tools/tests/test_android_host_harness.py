import base64
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULES = (
    "competing-vpn",
    "shared-uid-peer",
    "shared-uid-target",
    "traffic-control",
    "traffic-target-a",
    "traffic-target-b",
)


class AndroidHostHarnessContractTest(unittest.TestCase):
    def test_fixtures_are_debug_only_and_not_app_dependencies(self) -> None:
        settings = (ROOT / "settings.gradle.kts").read_text()
        app_build = (ROOT / "app/build.gradle.kts").read_text()
        for module in MODULES:
            self.assertIn(f'":test-fixtures:{module}"', settings)
            build = (ROOT / f"test-fixtures/{module}/build.gradle.kts").read_text()
            self.assertIn('selector().withBuildType("release")', build)
            self.assertIn("it.enable = false", build)
            self.assertNotIn(f'project(":test-fixtures:{module}")', app_build)

    def test_signed_study_uses_three_profiles_and_event_driven_active_time_binding(self) -> None:
        configuration = json.loads((ROOT / "test-fixtures/host-study.json").read_text())
        shaping = configuration["traffic_shaping"]
        self.assertEqual(
            [
                "cool.jacoblin.particeps.fixture.sharedtarget",
                "cool.jacoblin.particeps.fixture.targeta",
                "cool.jacoblin.particeps.fixture.targetb",
            ],
            shaping["target_packages"],
        )
        self.assertEqual(
            [("cap-0064", 64), ("cap-0512", 512), ("cap-4096", 4096)],
            [(profile["id"], profile["uplink_kbps"]) for profile in shaping["profiles"]],
        )
        binding = next(item for item in configuration["automations"] if item["id"] == "bind-traffic-shaping")
        self.assertEqual("cap-0064", binding["default_profile_id"])
        self.assertEqual([150, 75], [case["condition"]["duration_seconds"] for case in binding["cases"]])
        self.assertTrue(all(case["condition"]["clock"] == "ACTIVE_RUNNING_TIME" for case in binding["cases"]))

        envelope = base64.b64decode(
            (ROOT / "app/src/androidTest/assets/host_harness_study_envelope.txt").read_text().strip(),
            validate=True,
        )
        self.assertEqual(b"PTCCFG01", envelope[:8])

    def test_harness_is_blocking_and_covers_every_device_stage(self) -> None:
        harness = (ROOT / "tools/android-host-harness.sh").read_text()
        required_cases = (
            "aggregate_64_512_4096_kbps_and_control_bypass",
            "tcp_udp_dns_ipv4_ipv6_through_verified_vpn",
            "process_kill_recovers_safety_paused",
            "reboot_recovers_safety_paused",
            "target_replace_safety_pauses",
            "target_uninstall_safety_pauses",
            "unselected_shared_uid_peer_install_safety_pauses",
            "competing_vpn_revokes_and_safety_pauses",
            "underlying_network_handover_remains_running",
            "api37_local_network_permission_revoke_safety_pauses",
        )
        for name in required_cases:
            self.assertIn(name, harness)
        self.assertEqual(3, harness.count("run_saturation_measurement "))
        self.assertIn("--duration-seconds 60", harness)
        self.assertIn("run_instrumentation assertSafetyPaused", harness)
        self.assertEqual(2, harness.count("run_instrumentation assertSafetyPaused"))
        self.assertIn("await_live_state PAUSED", harness)
        self.assertIn("await_live_state RUNNING", harness)
        self.assertIn("capture_live_particeps_pid", harness)
        self.assertIn('[[ "$current_pid" == "$expected_pid" ]]', harness)
        self.assertIn("record_live_transition_latency shared_uid_peer_install PAUSED", harness)
        self.assertIn("cmd package list packages -U", harness)
        self.assertIn('$1 == expected && $2 ~ /^uid:[0-9]+$/', harness)
        self.assertIn("competing_vpn_activity", harness)
        self.assertIn("stop_traffic_fixtures", harness)
        self.assertIn('am force-stop "$package_name"', harness)
        self.assertEqual(5, harness.count("run_instrumentation assertDurablySafetyPaused"))
        self.assertIn("pm revoke \"$particeps_package\" android.permission.ACCESS_LOCAL_NETWORK", harness)
        self.assertIn("await_permission_revoke_pause \"$live_pid\" 105", harness)
        self.assertIn("record_permission_revoke_outcome", harness)
        self.assertIn('"process_continuity": sys.argv[1] == "true"', harness)
        self.assertIn("record_not_applicable api37_local_network_permission_revoke", harness)
        self.assertIn('result="not_applicable"', harness)
        self.assertIn("<skipped message=", harness)
        self.assertIn("-e particepsHostHarness true", harness)
        self.assertIn("HOST_HARNESS_PROVISION", harness)
        self.assertIn("HOST_HARNESS_RESET", harness)
        self.assertIn("RESET is idempotent", harness)
        self.assertNotIn("run_instrumentation provisionRunningStudy", harness)
        self.assertNotIn("run_instrumentation resetStudy", harness)
        self.assertIn("set +e\n  (trap case_cleanup EXIT; set -euo pipefail;", harness)
        self.assertIn("case_exit=$?\n  set -e", harness)
        self.assertNotIn(
            'if (trap case_cleanup EXIT; set -euo pipefail; "$function_name"); then',
            harness,
        )

    def test_live_control_seam_is_debug_only_and_shell_protected(self) -> None:
        debug_manifest = (ROOT / "app/src/debug/AndroidManifest.xml").read_text()
        main_manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        receiver = (
            ROOT
            / "app/src/debug/kotlin/cool/jacoblin/particeps/HostHarnessStateReceiver.kt"
        ).read_text()
        self.assertIn(".HostHarnessStateReceiver", debug_manifest)
        self.assertIn('android:permission="android.permission.DUMP"', debug_manifest)
        self.assertNotIn("HostHarnessStateReceiver", main_manifest)
        self.assertIn("lifetimeDataEventCount", receiver)
        self.assertIn("FLAG_DEBUGGABLE", receiver)
        self.assertIn("HOST_HARNESS_PROVISION", receiver)
        self.assertIn("HOST_HARNESS_RESET", receiver)
        self.assertIn("session.deleteLocalData()", receiver)
        self.assertIn("session.resetAfterRecoveryFailure()", receiver)
        self.assertIn("session.start()", receiver)

    def test_both_blocking_emulator_lanes_run_harness_and_upload_only_sanitized_reports(self) -> None:
        for workflow_name in ("ci.yml", "release.yml"):
            workflow = (ROOT / f".github/workflows/{workflow_name}").read_text()
            self.assertIn("tools/android-host-harness.sh", workflow)
            self.assertNotIn("tools/android-host-harness.sh --skip-build", workflow)
            self.assertIn("build/reports/android-host-harness", workflow)
            self.assertIn("google_apis_ps16k", workflow)
            self.assertIn("revision must be at least 5", workflow)
            self.assertIn("system_image_api: 34", workflow)

    def test_api_37_traffic_apps_declare_local_network_permission(self) -> None:
        permission = "android.permission.ACCESS_LOCAL_NETWORK"
        for module in ("traffic-target-a", "traffic-target-b", "traffic-control", "competing-vpn"):
            manifest = (ROOT / f"test-fixtures/{module}/src/main/AndroidManifest.xml").read_text()
            self.assertIn(permission, manifest)

    def test_dns_fixture_is_a_deterministic_datagram_not_a_cached_resolver_lookup(self) -> None:
        source = (
            ROOT
            / "test-fixtures/traffic-common/src/main/java/cool/jacoblin/particeps/fixtures/traffic/TrafficFixtureActivity.java"
        ).read_text()
        self.assertIn("DNS_QUERY", source)
        self.assertIn("sendDnsQuery()", source)
        self.assertIn("DNS_QUERY.length, address, 53", source)
        self.assertNotIn("getAllByName", source)

    def test_saturation_fixture_is_independent_of_activity_destruction(self) -> None:
        activity = (
            ROOT
            / "test-fixtures/traffic-common/src/main/java/cool/jacoblin/particeps/fixtures/traffic/TrafficFixtureActivity.java"
        ).read_text()
        service = (
            ROOT
            / "test-fixtures/traffic-common/src/main/java/cool/jacoblin/particeps/fixtures/traffic/TrafficFixtureService.java"
        ).read_text()
        self.assertIn("startForegroundService(service)", activity)
        self.assertIn("startForeground(NOTIFICATION_ID", service)
        for module in ("traffic-target-a", "traffic-target-b", "traffic-control"):
            manifest = (ROOT / f"test-fixtures/{module}/src/main/AndroidManifest.xml").read_text()
            self.assertIn("FOREGROUND_SERVICE_DATA_SYNC", manifest)
            self.assertIn("TrafficFixtureService", manifest)
        self.assertIn("The host closes the socket at the exact measurement boundary", service)


if __name__ == "__main__":
    unittest.main()
