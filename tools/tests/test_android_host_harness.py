import base64
import json
import os
import subprocess
import tempfile
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
    def test_api_37_surfaceflinger_guard_sets_and_verifies_sampling_property(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            adb = directory / "adb"
            log = directory / "adb.log"
            stopped = directory / "stopped"
            service_seen = directory / "service-seen"
            service_ready = directory / "service-ready"
            ready = directory / "ready"
            adb.write_text(
                """#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$*" >> "$FAKE_ADB_LOG"
case "$*" in
  *" root"|*" wait-for-device")
    ;;
  *" shell setprop debug.sf.luma_sampling 0")
    ;;
  *" shell setprop sys.boot_completed 0")
    ;;
  *" shell getprop debug.sf.luma_sampling")
    printf '0\\r\\n'
    ;;
  *" shell id -u")
    printf '0\\n'
    ;;
  *" shell getprop sys.boot_completed")
    printf '1\\n'
    ;;
  *" shell service check activity")
    if [[ -e "$FAKE_ADB_SERVICE_SEEN" ]]; then
      : > "$FAKE_ADB_SERVICE_READY"
      printf 'Service activity: found\\n'
    else
      : > "$FAKE_ADB_SERVICE_SEEN"
      printf 'Service activity: not found\\n'
    fi
    ;;
  *" shell service check package")
    printf 'Service package: found\\n'
    ;;
  *" shell service check window")
    printf 'Service window: found\\n'
    ;;
  *" shell pm disable-user --user 0 com.android.systemui")
    [[ -e "$FAKE_ADB_SERVICE_READY" ]]
    printf 'Package com.android.systemui new state: disabled-user\\n'
    ;;
  *" shell cmd overlay fabricate "*|*" shell cmd overlay enable "*)
    ;;
  *" shell cmd overlay lookup android android:bool/config_disableTaskSnapshots")
    printf 'true\\n'
    ;;
  *" shell stop")
    : > "$FAKE_ADB_STOPPED"
    ;;
  *" shell start")
    [[ ! -e "$FAKE_ADB_STOPPED" ]] || unlink "$FAKE_ADB_STOPPED"
    ;;
  *" shell pidof system_server")
    [[ -e "$FAKE_ADB_STOPPED" ]] || printf '1723\\n'
    ;;
  *" shell pm list packages -d --user 0")
    printf 'package:com.android.systemui\\n'
    ;;
  *" shell dumpsys window")
    printf 'mSnapshotEnabled=false\\nmSnapshotEnabled=false\\n'
    ;;
  *" shell pidof surfaceflinger")
    printf '489\\n'
    ;;
  *)
    exit 1
    ;;
esac
"""
            )
            adb.chmod(0o755)
            environment = os.environ.copy()
            environment["ADB"] = str(adb)
            environment["FAKE_ADB_LOG"] = str(log)
            environment["FAKE_ADB_STOPPED"] = str(stopped)
            environment["FAKE_ADB_SERVICE_SEEN"] = str(service_seen)
            environment["FAKE_ADB_SERVICE_READY"] = str(service_ready)
            environment["PARTICEPS_SURFACEFLINGER_GUARD_TIMEOUT_SECONDS"] = "5"
            environment["PARTICEPS_SURFACEFLINGER_STABILITY_SECONDS"] = "1"
            result = subprocess.run(
                [
                    str(ROOT / "tools/android-api37-surfaceflinger-guard.sh"),
                    "emulator-5554",
                    str(ready),
                ],
                cwd=ROOT,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("emulator graphics stabilized", result.stdout)
            self.assertEqual("ready\n", ready.read_text())
            commands = log.read_text()
            self.assertIn("-s emulator-5554 shell setprop debug.sf.luma_sampling 0", commands)
            self.assertIn("-s emulator-5554 shell getprop debug.sf.luma_sampling", commands)
            self.assertIn("shell pm disable-user --user 0 com.android.systemui", commands)
            self.assertEqual(
                2,
                commands.count("shell pm disable-user --user 0 com.android.systemui"),
            )
            self.assertIn("shell cmd overlay fabricate --target android", commands)
            self.assertIn("android:bool/config_disableTaskSnapshots 0x12 0x1", commands)
            self.assertIn("shell stop", commands)
            self.assertIn("shell start", commands)

    def run_instrumentation_launcher(
        self,
        fail_install: bool = False,
        fail_instrumentation: bool = False,
    ) -> tuple[subprocess.CompletedProcess[str], str]:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            adb = directory / "adb"
            log = directory / "adb.log"
            adb.write_text(
                """#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$*" >> "$FAKE_ADB_LOG"
case "$1" in
  get-state)
    printf 'device\\n'
    ;;
  uninstall)
    exit 1
    ;;
  install)
    if [[ "${FAKE_ADB_FAIL_INSTALL:-0}" == 1 ]]; then
      printf 'Failure [INSTALL_FAILED_TEST]\\n'
      exit 1
    fi
    printf 'fixture.apk: 1 file pushed, 0 skipped.\\nPerforming Push Install\\nSuccess\\n'
    ;;
  logcat)
    printf 'synthetic crash buffer\\n'
    ;;
  shell)
    if [[ "${FAKE_ADB_FAIL_INSTRUMENT:-0}" == 1 ]]; then
      printf 'FAILURES!!!\\nTests run: 1, Failures: 1\\n'
    else
      printf 'OK (1 test)\\n'
    fi
    ;;
esac
"""
            )
            adb.chmod(0o755)
            environment = os.environ.copy()
            environment["ADB"] = str(adb)
            environment["FAKE_ADB_LOG"] = str(log)
            if fail_install:
                environment["FAKE_ADB_FAIL_INSTALL"] = "1"
            if fail_instrumentation:
                environment["FAKE_ADB_FAIL_INSTRUMENT"] = "1"
            result = subprocess.run(
                [str(ROOT / "tools/android-instrumentation-ci.sh")],
                cwd=ROOT,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )
            return result, log.read_text()

    def test_api_37_instrumentation_launcher_uses_non_streaming_installs(self) -> None:
        result, commands = self.run_instrumentation_launcher()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(3, commands.count("install --no-streaming"))
        self.assertEqual(2, commands.count("shell am instrument -w -r"))

    def test_api_37_instrumentation_launcher_reports_install_failures(self) -> None:
        result, commands = self.run_instrumentation_launcher(fail_install=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Failure [INSTALL_FAILED_TEST]", result.stderr)
        self.assertIn("logcat -b crash -d -v brief", commands)

    def test_api_37_instrumentation_launcher_rejects_test_failures(self) -> None:
        result, _ = self.run_instrumentation_launcher(fail_instrumentation=True)
        self.assertNotEqual(0, result.returncode)

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
        harness = (ROOT / "tools/android-host-harness.sh").read_text()
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
        self.assertIn('"FAILED:${failure.stage}:${failure.resultCode}"', receiver)
        self.assertIn("Host provisioning failed:", harness)
        self.assertEqual(
            harness.count("am broadcast --include-stopped-packages --receiver-foreground"),
            4,
        )
        self.assertNotIn("am broadcast --receiver-foreground", harness)

    def test_both_blocking_emulator_lanes_run_harness_and_upload_only_sanitized_reports(self) -> None:
        launcher = (ROOT / "tools/android-emulator-ci.sh").read_text()
        instrumentation = (ROOT / "tools/android-instrumentation-ci.sh").read_text()
        prebuild = (ROOT / "tools/android-emulator-prebuild.sh").read_text()
        for workflow_name in ("ci.yml", "release.yml"):
            workflow = (ROOT / f".github/workflows/{workflow_name}").read_text()
            self.assertIn(
                "script: tools/android-emulator-ci.sh --require-16k=${{ matrix.require_16k }}",
                workflow,
            )
            self.assertNotIn('script: |\n            if [[ "${{ matrix.require_16k }}"', workflow)
            self.assertNotIn("tools/android-host-harness.sh --skip-build", workflow)
            self.assertIn("build/reports/android-host-harness", workflow)
            self.assertIn("google_apis_ps16k", workflow)
            self.assertIn("system_image_api: 34", workflow)
            self.assertIn("ram-size: 4096M", workflow)
            self.assertIn("disk-size: 12G", workflow)
            self.assertIn(
                "-gpu swiftshader -feature -Vulkan -feature -GLDirectMem",
                workflow,
            )
            self.assertNotIn("swiftshader_indirect", workflow)
            self.assertIn(
                "pre-emulator-launch-script: tools/android-emulator-prebuild.sh "
                "--require-16k=${{ matrix.require_16k }}",
                workflow,
            )
        self.assertIn("API 37 ps16k emulator page size must be 16384", launcher)
        self.assertIn("API 37 ps16k image revision must be at least 5", launcher)
        self.assertIn("debug.sf.luma_sampling", launcher)
        self.assertIn("graphics or framework process did not remain stable", launcher)
        self.assertIn('guard_failure_file="${guard_ready_file}.failed"', launcher)
        self.assertIn("config_disableTaskSnapshots", launcher)
        self.assertIn("com.android.systemui", launcher)
        self.assertIn("./gradlew --no-daemon --max-workers=1", launcher)
        self.assertIn("connectedDebugAndroidTest", launcher)
        self.assertIn("tools/android-instrumentation-ci.sh", launcher)
        self.assertIn("tools/android-host-harness.sh --skip-build", launcher)
        self.assertIn("-PinstrumentedTestAbi=x86_64", launcher)
        self.assertIn(":app:assembleDebugAndroidTest", prebuild)
        self.assertIn(":core:storage:assembleDebugAndroidTest", prebuild)
        self.assertIn(":test-fixtures:traffic-target-a:assembleReplacementDebug", prebuild)
        self.assertIn(":test-fixtures:competing-vpn:assembleDebug", prebuild)
        self.assertIn("-PinstrumentedTestAbi=x86_64", prebuild)
        self.assertIn("android-api37-surfaceflinger-guard.sh", prebuild)
        self.assertIn('guard_failure_file="${guard_ready_file}.failed"', prebuild)
        app_build = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn('providers.gradleProperty("instrumentedTestAbi")', app_build)
        self.assertIn("options=(--no-streaming)", instrumentation)
        self.assertIn("cool.jacoblin.particeps.test/androidx.test.runner.AndroidJUnitRunner", instrumentation)
        self.assertIn("cool.jacoblin.particeps.core.storage.test/androidx.test.runner.AndroidJUnitRunner", instrumentation)
        self.assertIn("INSTRUMENTATION_(ABORTED|FAILED)", instrumentation)
        self.assertIn("logcat -b crash -d -v brief", instrumentation)
        harness = (ROOT / "tools/android-host-harness.sh").read_text()
        self.assertIn('install --no-streaming -r -d -t', harness)

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
