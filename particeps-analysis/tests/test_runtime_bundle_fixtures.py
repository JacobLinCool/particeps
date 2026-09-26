"""Real runtime exports, from the RC13 pilot build and this branch, through to Parquet.

Each fixture is a byte-for-byte copy of a `.partexp` the runtime wrote (see
fixtures/runtime-bundles/PROVENANCE.md). The tests run the public CLI, inventory then
materialize, exactly as a researcher does, and check the published dataset.
"""

from __future__ import annotations

import base64
import contextlib
import hashlib
import io
import json
import shutil
import tempfile
import unittest
from pathlib import Path
from typing import Any

from runtime_fixtures import (
    DEMO_HPKE_PRIVATE_KEY,
    FIXTURE_ENTRIES,
    FIXTURES,
    MANIFEST,
    decrypted_chain,
    detect_shapes,
    materialize_directory,
    private_keys_file,
    read_partitions,
)

from particeps_analysis import engine
from particeps_analysis.cli import main
from particeps_analysis.engine import EngineCommitParser, EngineReplayVerifier
from particeps_analysis.errors import ValidationError
from particeps_analysis.pipeline import AnalysisPipeline, load_private_keys
from particeps_analysis.registry import EventSourceRegistry
from particeps_analysis.sink import ParquetSink

FIXTURE_BUDGET_BYTES = 5_000_000
COLLECTOR_SOURCES = EventSourceRegistry().collector_source_ids


def materialize(names: list[str], root: Path) -> Path:
    """Run the CLI inventory and materialize commands over the named fixtures."""

    inbox = root / "inbox"
    inbox.mkdir()
    for name in names:
        shutil.copyfile(FIXTURES / name, inbox / name)
    return materialize_directory(inbox, private_keys_file(root), root)


class _RenderingSpy(EngineReplayVerifier):
    """Record which timer rendering each multi-intent reduction wrote."""

    def __init__(self, *arguments: Any) -> None:
        super().__init__(*arguments)
        self.renderings: list[tuple[str, str]] = []

    def _verify_reduction_outputs(self, commit, prospective, result) -> None:
        complete, net = engine._timer_intent_renderings(
            result.timer_intents,
            self.authoritative_checkpoint.timers,
            result.checkpoint.timers,
        )
        if complete != net:
            actual = [
                (
                    "SCHEDULE" if event.event_type == "TIMER_SCHEDULED" else "RETIRE",
                    engine._timer_event_evidence(event),
                )
                for event in commit.events
                if event.source_id == "timer.v1"
                and event.event_type in {"TIMER_SCHEDULED", "TIMER_RETIRED"}
                and engine._is_automation_timer_event(event)
            ]
            rendering = "complete" if actual == complete else "net" if actual == net else "none"
            self.renderings.append((commit.input_kind, rendering))
        super()._verify_reduction_outputs(commit, prospective, result)


class RuntimeBundleFixtureTest(unittest.TestCase):
    def test_manifest_pins_every_fixture_byte_within_budget(self) -> None:
        listed = {entry["file"] for entry in MANIFEST["fixtures"]}
        self.assertEqual(listed, {path.name for path in FIXTURES.glob("*.partexp")})
        total = 0
        for entry in MANIFEST["fixtures"]:
            data = (FIXTURES / entry["file"]).read_bytes()
            total += len(data)
            with self.subTest(fixture=entry["file"]):
                self.assertEqual(entry["byte_count"], len(data))
                self.assertEqual(entry["sha256"], hashlib.sha256(data).hexdigest())
                self.assertEqual(entry["runtime"], entry["file"].split("-")[0])
        self.assertLess(total, FIXTURE_BUDGET_BYTES)
        self.assertEqual(
            {"rc13", "branch"}, {entry["runtime"] for entry in MANIFEST["fixtures"]}
        )

    def test_gitignore_re_includes_exactly_the_listed_fixtures(self) -> None:
        # The repository ignores *.partexp everywhere; naming each fixture here keeps an export
        # copied into this directory ignored instead of committing it with the fixtures.
        included = {
            line.removeprefix("!/")
            for line in (FIXTURES / ".gitignore").read_text().splitlines()
            if line.startswith("!")
        }
        self.assertEqual({entry["file"] for entry in MANIFEST["fixtures"]}, included)

    def test_keys_file_is_the_published_insecure_demo_key(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            keys = load_private_keys(private_keys_file(Path(temporary)))
        demo = DEMO_HPKE_PRIVATE_KEY.read_text().strip()
        self.assertEqual(
            {MANIFEST["researcher_key_id"]: base64.urlsafe_b64decode(demo + "=")}, keys
        )

    def test_each_fixture_materializes_to_the_expected_parquet_dataset(self) -> None:
        for name, entry in FIXTURE_ENTRIES.items():
            with self.subTest(fixture=name), tempfile.TemporaryDirectory() as temporary:
                dataset = materialize([name], Path(temporary))
                manifest = json.loads((dataset / "dataset-manifest.json").read_text())
                self.assertEqual([], manifest["validation_failures"])
                (source,) = manifest["source_ciphertexts"]
                self.assertEqual(entry["sha256"], source["sha256"])
                self.assertEqual(entry["bundle_id"], source["bundle_id"])
                self.assertEqual(entry["participant_instance_id"], source["participant_instance_id"])
                self.assertEqual(str(entry["commit_count"]), source["commit_count"])
                self.assertEqual(str(entry["event_count"]), source["event_count"])
                quality = json.loads((dataset / "quality-summary.json").read_text())
                (participant,) = quality["commit_chain_verification"]["participants"]
                self.assertEqual(str(entry["event_count"]), participant["replayed_event_count"])
                self._assert_published_epoch_intervals(entry, participant["condition_epochs"])

                tables = read_partitions(dataset)
                self.assertEqual(
                    entry["partition_rows"],
                    {key: table.num_rows for key, table in tables.items()},
                )
                self.assertEqual(entry["event_count"], sum(entry["partition_rows"].values()))
                self._assert_epoch_columns(entry, tables)
                self._assert_wire_text_and_payload_columns(tables)

    def _assert_published_epoch_intervals(
        self, entry: dict[str, Any], epochs: list[dict[str, Any]]
    ) -> None:
        # Each epoch's verified source interval is published, so analysts can see and exclude the
        # preparation slice between the preparation bound and the activation.
        self.assertEqual(entry["condition_epoch_count"], len(epochs))
        for epoch in epochs:
            bound, activated = epoch["preparation_bound"], epoch["activated_at"]
            self.assertLessEqual(
                int(bound["wall_time_utc_millis"]), int(activated["wall_time_utc_millis"])
            )
            if epoch["deactivated_at"] is not None:
                self.assertLessEqual(
                    int(activated["wall_time_utc_millis"]),
                    int(epoch["deactivated_at"]["wall_time_utc_millis"]),
                )
        if "preparation-bound-coverage" in entry["covers"]:
            self.assertTrue(any(
                int(epoch["preparation_bound"]["wall_time_utc_millis"])
                < int(epoch["activated_at"]["wall_time_utc_millis"])
                for epoch in epochs
            ))

    def _assert_epoch_columns(self, entry: dict[str, Any], tables: dict[str, Any]) -> None:
        envelope_epochs = set()
        requests: dict[str, str] = {}
        for key, table in tables.items():
            source_id = key.split("/")[0]
            envelope = table.column("condition_epoch_id").to_pylist()
            attributed = table.column("source_condition_epoch_id").to_pylist()
            envelope_epochs.update(epoch for epoch in envelope if epoch is not None)
            if source_id in COLLECTOR_SOURCES:
                # A collector event belongs to the epoch that admitted it.
                self.assertNotIn(None, envelope)
                self.assertEqual(envelope, attributed)
            if key == "automation_runtime.v1/ACTION_REQUESTED":
                self.assertNotIn(None, envelope)
                requests.update(zip(table.column("invocation_id").to_pylist(), envelope, strict=True))
        self.assertEqual(entry["condition_epoch_count"], len(envelope_epochs))
        activated = tables["study_condition.v1/CONDITION_EPOCH_ACTIVATED"]
        self.assertEqual(
            entry["condition_epoch_count"],
            len(set(activated.column("payload_condition_epoch_id").to_pylist())),
        )
        for key, table in tables.items():
            if key.startswith("interventions.v1/"):
                # An intervention event belongs to the epoch its request was recorded in.
                expected = [
                    requests[occurrence]
                    for occurrence in table.column("occurrence_id").to_pylist()
                ]
                self.assertEqual(expected, table.column("source_condition_epoch_id").to_pylist())

    def _assert_wire_text_and_payload_columns(self, tables: dict[str, Any]) -> None:
        for key in (
            "study_condition.v1/CONDITION_EPOCH_ACTIVATED",
            "study_condition.v1/CONDITION_EPOCH_DEACTIVATED",
        ):
            table = tables[key]
            self.assertEqual(
                table.column("condition_epoch_id").to_pylist(),
                table.column("payload_condition_epoch_id").to_pylist(),
            )
            field = table.schema.field("payload_condition_epoch_id")
            self.assertEqual(b"condition_epoch_id", field.metadata[b"particeps.payload_field"])
            for text in table.column("resource_vector_json").to_pylist():
                self.assertIsInstance(text, str)
                self.assertEqual(text, json.dumps(json.loads(text), separators=(",", ":"), sort_keys=True))
        for key, table in tables.items():
            if key.startswith("traffic_shaping.v1/"):
                self.assertEqual(
                    table.column("condition_epoch_id").to_pylist(),
                    table.column("payload_condition_epoch_id").to_pylist(),
                )
            if key == "study_runtime.v1/SOURCE_QUALITY_GAP":
                self.assertNotIn(None, table.column("payload_source_id").to_pylist())
            if key.startswith("timer.v1/"):
                for text in table.column("logical_due_research_time").to_pylist():
                    self.assertEqual(
                        {"boot_session_id", "monotonic_time_nanos", "wall_time_utc_millis"},
                        set(json.loads(text)),
                    )

    def test_rc13_and_branch_record_one_deterministic_timer_rendering_each(self) -> None:
        registry = EventSourceRegistry()
        for name, entry in FIXTURE_ENTRIES.items():
            with self.subTest(fixture=name):
                configuration, digest, commits = decrypted_chain(name)
                parser = EngineCommitParser(registry)
                verifier = _RenderingSpy(registry, configuration, digest)
                events = verifier.replay([parser.parse(commit) for commit in commits])
                self.assertEqual(entry["event_count"], len(events))
                self.assertEqual(entry["state"], verifier.previous_projection["state"])
                expected = entry["recovery_timer_rendering"]
                self.assertEqual(
                    [] if expected is None else [("RECOVERY", expected)],
                    verifier.renderings,
                )

    def test_rc13_and_branch_forks_of_one_participant_are_never_combined(self) -> None:
        names = ["rc13-jvm-pilot-process-restart.partexp", "branch-jvm-pilot-process-restart.partexp"]
        entries = [FIXTURE_ENTRIES[name] for name in names]
        self.assertEqual(1, len({entry["participant_instance_id"] for entry in entries}))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inbox = root / "inbox"
            inbox.mkdir()
            for name in names:
                shutil.copyfile(FIXTURES / name, inbox / name)
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(
                    0,
                    main(["inventory", "--workspace", str(root / "workspace"), "--local", str(inbox)]),
                )
            registry = EventSourceRegistry()
            pipeline = AnalysisPipeline(
                root / "workspace",
                registry,
                load_private_keys(private_keys_file(root)),
                ParquetSink(registry),
            )
            with self.assertRaisesRegex(ValidationError, "conflicting variants"):
                pipeline.materialize(root / "dataset")
            self.assertFalse((root / "dataset").exists())

    def test_manifest_coverage_labels_are_present_in_each_fixture(self) -> None:
        required = {
            "setup-checkpoint",
            "barrier-flush-cursor",
            "barrier-retires-due-timer-cancelled",
            "due-timer-retires-fired",
            "deadline-stop-retirements",
            "terminal-request-retires-deadline",
            "lifecycle-command-retirements",
            "deadline-events-in-closing-epoch",
            "recovery-close-without-traffic-audit",
            "recovery-close-in-new-boot",
            "recovery-drops-retrospective-checkpoint",
            "complete-timer-rendering",
            "preparation-bound-coverage",
            "intervention-request-epoch",
            "barrier-flush-events",
            "empty-barrier-flush",
            "safety-close-without-traffic-audit",
            "safety-pause-while-pausing",
            "recovery-while-pausing",
            "deadline-rearmed-after-terminal-request",
            "recovery-close-without-trusted-clock",
        }
        rc13_covered: set[str] = set()
        for name, entry in FIXTURE_ENTRIES.items():
            with self.subTest(fixture=name):
                _configuration, _digest, commits = decrypted_chain(name)
                shapes = detect_shapes(commits)
                if entry["recovery_timer_rendering"] is not None:
                    shapes.add(f"{entry['recovery_timer_rendering']}-timer-rendering")
                self.assertLessEqual(set(entry["covers"]), shapes)
                if entry["runtime"] == "rc13":
                    rc13_covered.update(entry["covers"])
        self.assertLessEqual(required, rc13_covered)


if __name__ == "__main__":
    unittest.main()
