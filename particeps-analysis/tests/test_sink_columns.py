"""Parquet column contract: payload namespacing, json_string wire text, and typed failures."""

from __future__ import annotations

import tempfile
import unittest
from dataclasses import replace
from pathlib import Path

import pyarrow.parquet as pq

from particeps_analysis.errors import ValidationError
from particeps_analysis.event_store import EventDatabase
from particeps_analysis.jcs import canonicalize
from particeps_analysis.models import EventProvenance, VerifiedEvent
from particeps_analysis.reassembly import ReassemblyResult
from particeps_analysis.registry import EventSourceRegistry
from particeps_analysis.sink import (
    RESERVED_PROVENANCE_COLUMNS,
    ParquetSink,
    _arrow_schema,
    payload_column_name,
)

EPOCH = "fef4f46b-d45e-40b0-9701-06031759791d"
# Strict JSON that is not in JCS form: the column must keep these exact authenticated bytes.
WIRE_TIME = '{ "wall_time_utc_millis": "1000", "monotonic_time_nanos": "10", "boot_session_id": "boot-one" }'


def quality_gap_event(**overrides: object) -> VerifiedEvent:
    registry = EventSourceRegistry()
    wire = {
        "coverage_start_research_time": WIRE_TIME,
        "reason": "PROCESS_RECOVERY",
        "source_id": "usage_events.v1",
    }
    schema = registry.event("study_runtime.v1", 1, "SOURCE_QUALITY_GAP")
    event = VerifiedEvent(
        experiment_id="study-one",
        configuration_id="config-one",
        participant_instance_id="95a484e3-2ba5-4d35-9b2f-03ae394235e7",
        assigned_participant_id=None,
        sequence_number=7,
        source_id="study_runtime.v1",
        schema_version=1,
        event_type="SOURCE_QUALITY_GAP",
        condition_epoch_id=EPOCH,
        source_condition_epoch_id=EPOCH,
        boot_session_id="boot-one",
        monotonic_time_nanos=10,
        wall_time_utc_millis=1_000,
        fields=registry.typed_fields(schema, wire),
        canonical_bytes=canonicalize(
            {
                "condition_epoch_id": EPOCH,
                "event_type": "SOURCE_QUALITY_GAP",
                "fields": wire,
                "observed_time": {
                    "boot_session_id": "boot-one",
                    "elapsed_realtime_nanos": "10",
                    "wall_time_utc_millis": "1000",
                },
                "schema_version": 1,
                "sequence_number": "7",
                "source_id": "study_runtime.v1",
            }
        ),
        provenance=EventProvenance(
            source_ciphertext_sha256="b" * 64,
            source_bundle_id="c3ab3ec1-583b-4cf6-80bf-ff1723cc64e5",
            source_configuration_sha256="a" * 64,
            source_object="file:///bundle.partexp",
            source_commit_sequence=3,
            source_observation_sequence=None,
        ),
    )
    return replace(event, **overrides)


def write_dataset(root: Path, *events: VerifiedEvent) -> Path:
    database = EventDatabase(root / "event-store")
    for event in events:
        database.add(event)
    database.finish_candidates()
    for row in database.candidate_rows():
        database.accept(row)
    collection = database.seal()
    try:
        return ParquetSink(EventSourceRegistry()).write(
            ReassemblyResult(
                bundles=(),
                events=collection,
                quality={"format": "particeps-quality-summary-v1"},
                has_conflicts=False,
            ),
            root / "dataset",
        )
    finally:
        collection.close()


class SinkColumnTest(unittest.TestCase):
    def test_exactly_six_registry_fields_become_payload_columns(self) -> None:
        renamed = set()
        for schema in EventSourceRegistry().event_schemas:
            arrow = _arrow_schema(schema)
            self.assertEqual(len(arrow.names), len(set(arrow.names)))
            for name in schema.fields:
                column = payload_column_name(name)
                field = arrow.field(column)
                self.assertEqual(name.encode(), field.metadata[b"particeps.payload_field"])
                if column != name:
                    self.assertEqual(f"payload_{name}", column)
                    renamed.add((schema.source_id, schema.event_type, name))
        self.assertEqual(
            {
                ("study_condition.v1", "CONDITION_EPOCH_ACTIVATED", "condition_epoch_id"),
                ("study_condition.v1", "CONDITION_EPOCH_DEACTIVATED", "condition_epoch_id"),
                ("traffic_shaping.v1", "TRAFFIC_SHAPING_PROFILE_APPLIED", "condition_epoch_id"),
                ("traffic_shaping.v1", "TRAFFIC_SHAPING_SNAPSHOT", "condition_epoch_id"),
                ("traffic_shaping.v1", "TRAFFIC_SHAPING_PROFILE_REMOVED", "condition_epoch_id"),
                ("study_runtime.v1", "SOURCE_QUALITY_GAP", "source_id"),
            },
            renamed,
        )
        self.assertIn("source_id", RESERVED_PROVENANCE_COLUMNS)
        self.assertIn("parser_version", RESERVED_PROVENANCE_COLUMNS)

    def test_a_prefixed_payload_name_that_still_collides_is_rejected(self) -> None:
        schema = EventSourceRegistry().event("study_runtime.v1", 1, "SOURCE_QUALITY_GAP")
        fields = dict(schema.fields)
        fields["payload_source_id"] = fields["source_id"]
        with self.assertRaisesRegex(ValidationError, "payload field collides with dataset provenance"):
            _arrow_schema(replace(schema, fields=fields))

    def test_json_string_columns_hold_the_authenticated_wire_text(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            dataset = write_dataset(Path(temporary), quality_gap_event())
            (path,) = dataset.rglob("*.parquet")
            physical = pq.read_schema(path)
            table = pq.read_table(path)
        self.assertEqual([WIRE_TIME], table.column("coverage_start_research_time").to_pylist())
        self.assertEqual([None], table.column("coverage_end_research_time").to_pylist())
        self.assertEqual(
            b"json_string", physical.field("coverage_start_research_time").metadata[b"particeps.type"]
        )
        # The payload's source_id stays apart from the Hive partition column a reader adds.
        self.assertNotIn("source_id", physical.names)
        self.assertEqual(["usage_events.v1"], table.column("payload_source_id").to_pylist())
        self.assertEqual(["study_runtime.v1"], table.column("source_id").to_pylist())
        self.assertEqual([EPOCH], table.column("condition_epoch_id").to_pylist())

    def test_a_row_that_does_not_fit_its_arrow_schema_is_a_validation_error(self) -> None:
        event = quality_gap_event()
        malformed = replace(event, fields={**event.fields, "reason": 5})
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaisesRegex(
                ValidationError,
                "does not match its typed dataset schema: study_runtime.v1/SOURCE_QUALITY_GAP",
            ):
                write_dataset(root, malformed)
            self.assertFalse((root / "dataset").exists())


if __name__ == "__main__":
    unittest.main()
