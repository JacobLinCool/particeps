"""Bundles the production runtime exports in a pilot-shaped week, through to Parquet.

core/study-application's RealRuntimeBundleInteropTest drives the real StudySessionManager and
ExperimentRuntime through the five-day pilot configuration on deterministic platform doubles,
verifies its exports with the Kotlin ResearchBundleVerifier, and, when
PARTICEPS_REAL_RUNTIME_INTEROP_DIR names a directory, writes them there with a test-only researcher
key and expected.json. CI runs that test and then this module with the same variable; the tests
skip when it is unset, and fail when it names a directory the Kotlin test did not write.
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import tempfile
import unittest
from pathlib import Path
from typing import Any

from runtime_fixtures import (
    decrypt_bundle,
    detect_shapes,
    materialize_directory,
    read_partitions,
)

from particeps_analysis.encoding import base64url_decode
from particeps_analysis.registry import EventSourceRegistry
from particeps_analysis.usage_report import usage_report

INTEROP_DIRECTORY_ENV = "PARTICEPS_REAL_RUNTIME_INTEROP_DIR"
INTEROP_FORMAT = "particeps-real-runtime-interop-v1"
COLLECTOR_SOURCES = EventSourceRegistry().collector_source_ids

# The Protocol v1 shapes each scenario must record, named as detect_shapes names them.
EXPECTED_SHAPES = {
    "pilot-deadline.partexp": {
        "setup-checkpoint",
        "barrier-flush-cursor",
        "preparation-bound-coverage",
        "barrier-retires-due-timer-cancelled",
        "lifecycle-command-retirements",
        "intervention-request-epoch",
        "recovery-drops-retrospective-checkpoint",
        "recovery-close-in-new-boot",
        "recovery-close-without-traffic-audit",
        "wall-clock-gap-while-running",
        "wall-clock-gap-while-paused",
        "deadline-stop-retirements",
        "deadline-events-in-closing-epoch",
    },
    "complete-from-running.partexp": {
        "setup-checkpoint",
        "barrier-flush-cursor",
        "preparation-bound-coverage",
        "lifecycle-command-retirements",
        "terminal-request-retires-deadline",
        "deadline-events-in-closing-epoch",
    },
}


class RealRuntimeInteropTest(unittest.TestCase):
    root: Path
    expected: dict[str, Any]
    keys: dict[str, bytes]

    @classmethod
    def setUpClass(cls) -> None:
        directory = os.environ.get(INTEROP_DIRECTORY_ENV)
        if not directory:
            raise unittest.SkipTest("Real-runtime interop bundles were not explicitly requested")
        cls.root = Path(directory)
        cls.expected = json.loads((cls.root / "expected.json").read_text())
        cls.keys = {
            cls.expected["researcher_key_id"]: base64url_decode(
                (cls.root / "researcher-private-key.base64url").read_text(),
                32,
                "real-runtime interop researcher private key",
            )
        }

    def test_manifest_pins_every_bundle(self) -> None:
        self.assertEqual(INTEROP_FORMAT, self.expected["format"])
        listed = {bundle["file"] for bundle in self.expected["bundles"]}
        self.assertEqual(set(EXPECTED_SHAPES), listed)
        self.assertEqual(listed, {path.name for path in self.root.glob("*.partexp")})
        for bundle in self.expected["bundles"]:
            with self.subTest(bundle=bundle["file"]):
                data = (self.root / bundle["file"]).read_bytes()
                self.assertEqual(bundle["byte_count"], len(data))
                self.assertEqual(bundle["sha256"], hashlib.sha256(data).hexdigest())

    def test_bundles_materialize_through_the_sink(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            inbox = work / "inbox"
            inbox.mkdir()
            for bundle in self.expected["bundles"]:
                shutil.copyfile(self.root / bundle["file"], inbox / bundle["file"])
            dataset = materialize_directory(inbox, self._keys_file(work), work)
            manifest = json.loads((dataset / "dataset-manifest.json").read_text())
            self.assertEqual([], manifest["validation_failures"])
            sources = {source["sha256"]: source for source in manifest["source_ciphertexts"]}
            quality = json.loads((dataset / "quality-summary.json").read_text())
            replayed = {
                participant["participant_instance_id"]: participant
                for participant in quality["commit_chain_verification"]["participants"]
            }
            tables = read_partitions(dataset)
            rows: dict[str, int] = {}
            for table in tables.values():
                for participant in table.column("participant_instance_id").to_pylist():
                    rows[participant] = rows.get(participant, 0) + 1
            for bundle in self.expected["bundles"]:
                with self.subTest(bundle=bundle["file"]):
                    source = sources[bundle["sha256"]]
                    participant = bundle["participant_instance_id"]
                    self.assertEqual(participant, source["participant_instance_id"])
                    self.assertEqual(str(bundle["commit_count"]), source["commit_count"])
                    self.assertEqual(str(bundle["event_count"]), source["event_count"])
                    self.assertEqual(str(bundle["event_count"]), replayed[participant]["replayed_event_count"])
                    self.assertEqual(bundle["event_count"], rows[participant])
            self.assertEqual(len(self.expected["bundles"]), len(sources))
            self._assert_columns(tables)

    def test_bundles_record_the_pilot_shapes(self) -> None:
        for bundle in self.expected["bundles"]:
            with self.subTest(bundle=bundle["file"]):
                _configuration, _digest, commits = decrypt_bundle(self.root / bundle["file"], self.keys)
                self.assertEqual(bundle["commit_count"], len(commits))
                self.assertEqual(bundle["state"], commits[-1]["successor_projection"]["state"])
                self.assertLessEqual(EXPECTED_SHAPES[bundle["file"]], detect_shapes(list(commits)))

    def test_pending_usage_recovered_in_new_boot_keeps_its_verified_source_boot(self) -> None:
        path = self.root / "pilot-deadline.partexp"
        _configuration, _digest, commits = decrypt_bundle(path, self.keys)
        activations = {
            event["fields"]["condition_epoch_id"]: json.loads(event["fields"]["boundary_research_time"])
            for commit in commits for event in commit["events"]
            if event["event_type"] == "CONDITION_EPOCH_ACTIVATED"
        }
        recovered = [
            (commit, observation)
            for commit in commits
            if commit["input_kind"] == "RECOVERY" and commit["consumed_pending_input_sha256"] is not None
            for observation in commit["source_observations"]
            if observation["source_id"] == "usage_events.v1"
            and commit["committed_at"]["boot_session_id"]
            != activations[observation["condition_epoch_id"]]["boot_session_id"]
        ]
        self.assertTrue(recovered, "runtime fixture must recover durable pending usage from the prior boot")
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            inbox = work / "inbox"
            inbox.mkdir()
            shutil.copyfile(path, inbox / path.name)
            dataset = materialize_directory(inbox, self._keys_file(work), work)
            quality = json.loads((dataset / "quality-summary.json").read_text())
            participant = quality["commit_chain_verification"]["participants"][0]
            for _commit, observation in recovered:
                epoch_id = observation["condition_epoch_id"]
                source = observation["coverage"]
                intervals = [
                    interval for interval in participant["usage_coverage"]
                    if interval["condition_epoch_id"] == epoch_id
                    and int(interval["start_utc_millis"]) <= int(source["start_inclusive"])
                    and int(interval["end_utc_millis"]) >= int(source["end_exclusive"])
                ]
                self.assertEqual(1, len(intervals))
                self.assertEqual(activations[epoch_id]["boot_session_id"], intervals[0]["boot_session_id"])
            report = usage_report(dataset, work / "usage-report", "Asia/Taipei")
            summary = json.loads((report / "participants.jsonl").read_text())
            self.assertGreater(summary["query_covered_millis"], 0)

    def _assert_columns(self, tables: dict[str, Any]) -> None:
        for key, table in tables.items():
            if key.split("/")[0] in COLLECTOR_SOURCES:
                # A collector event belongs to the epoch that admitted it.
                envelope = table.column("condition_epoch_id").to_pylist()
                self.assertNotIn(None, envelope)
                self.assertEqual(envelope, table.column("source_condition_epoch_id").to_pylist())
        for key in (
            "study_condition.v1/CONDITION_EPOCH_ACTIVATED",
            "study_condition.v1/CONDITION_EPOCH_DEACTIVATED",
            "traffic_shaping.v1/TRAFFIC_SHAPING_PROFILE_APPLIED",
        ):
            table = tables[key]
            self.assertEqual(
                table.column("condition_epoch_id").to_pylist(),
                table.column("payload_condition_epoch_id").to_pylist(),
            )
        gaps = tables["study_runtime.v1/SOURCE_QUALITY_GAP"]
        self.assertEqual(
            ["PROCESS_RECOVERY", "PROCESS_RECOVERY", "WALL_CLOCK_CHANGED", "WALL_CLOCK_CHANGED"],
            sorted(gaps.column("reason").to_pylist()),
        )
        self.assertNotIn(None, gaps.column("payload_source_id").to_pylist())
        for text in tables["study_condition.v1/CONDITION_EPOCH_ACTIVATED"].column("resource_vector_json").to_pylist():
            self.assertEqual(text, json.dumps(json.loads(text), separators=(",", ":"), sort_keys=True))

    def _keys_file(self, directory: Path) -> Path:
        """Write the analyzer's owner-only key file for the test-only researcher key."""

        path = directory / "keys.json"
        encoded = (self.root / "researcher-private-key.base64url").read_text().strip()
        path.write_text(
            json.dumps({
                "format": "particeps-analysis-keys-v1",
                "keys": {self.expected["researcher_key_id"]: encoded},
            })
        )
        os.chmod(path, 0o600)
        return path


if __name__ == "__main__":
    unittest.main()
