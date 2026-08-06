from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import pyarrow.parquet as pq

from particeps_analysis.catalog import CollectorCatalog
from particeps_analysis.event_store import EventDatabase
from particeps_analysis.models import (
    BootSession,
    EventProvenance,
    SamplingGroup,
    SurveyLifecycleCount,
    VerifiedEvent,
)
from particeps_analysis.reassembly import ReassemblyResult
from particeps_analysis.sink import ParquetSink, _observations

REPOSITORY = Path(__file__).resolve().parents[2]
CATALOG = REPOSITORY / "protocol" / "v1" / "collector-catalog.json"
PARTICIPANT = "00000000-0000-0000-0000-000000000001"


def _event(
    sequence: int,
    *,
    collector_id: str = "app_lifecycle.v1",
    payload_type: str = "ACTIVITY_CREATED",
    boot_session_id: str = "boot",
    fields: dict[str, object] | None = None,
    monotonic_time_nanos: int | None = None,
) -> VerifiedEvent:
    if fields is None:
        fields = (
            {
                "change_reason": "TIMEZONE_CHANGED",
                "daylight_saving_time": False,
                "timezone_id": "Asia/Taipei",
                "utc_offset_seconds": 28_800,
            }
            if collector_id == "temporal_context.v1"
            else {"activity_class": "Activity"}
        )
    return VerifiedEvent(
        "study-id",
        "config-id",
        PARTICIPANT,
        None,
        sequence,
        collector_id,
        1,
        payload_type,
        boot_session_id,
        sequence * 10 if monotonic_time_nanos is None else monotonic_time_nanos,
        sequence * 20,
        fields,
        f"event-{sequence}".encode(),
        EventProvenance("a" * 64, "bundle", "b" * 64, "memory:bundle"),
    )


class _Events:
    def __init__(
        self,
        events: list[VerifiedEvent],
        *,
        boot_sessions: list[BootSession] | None = None,
        sampling_groups: list[SamplingGroup] | None = None,
        survey_counts: list[SurveyLifecycleCount] | None = None,
    ):
        self.events = events
        self.boot_sessions = boot_sessions or []
        self.sampling_groups = sampling_groups or []
        self.survey_counts = survey_counts or []

    def __iter__(self):
        return iter(self.events)

    def __len__(self) -> int:
        return len(self.events)

    def close(self) -> None:
        pass

    def iter_partitioned(self):
        return iter(
            sorted(
                self.events,
                key=lambda event: (
                    event.experiment_id,
                    event.configuration_id,
                    event.collector_id,
                    event.payload_schema_version,
                    event.payload_type,
                    event.participant_instance_id,
                    event.sequence_number,
                ),
            )
        )

    def iter_boot_sessions(self):
        return iter(self.boot_sessions)

    def iter_sampling_groups(self, source_clock_fields):
        return iter(self.sampling_groups)

    def iter_survey_lifecycle_counts(self):
        return iter(self.survey_counts)


class SinkTest(unittest.TestCase):
    def test_all_p2_collectors_materialize_typed_parquet_fixtures(self) -> None:
        fixtures = [
            (
                "battery_state.v1",
                "BATTERY_STATE",
                {
                    "charging_source": "USB",
                    "charging_state": "CHARGING",
                    "percentage": 73,
                    "power_save_enabled": True,
                },
            ),
            (
                "temporal_context.v1",
                "TEMPORAL_CONTEXT",
                {
                    "change_reason": "TIMEZONE_CHANGED",
                    "daylight_saving_time": False,
                    "timezone_id": "Asia/Taipei",
                    "utc_offset_seconds": 28_800,
                },
            ),
            (
                "gyroscope.v1",
                "GYROSCOPE_SAMPLE",
                {
                    "accuracy": 3,
                    "source_elapsed_realtime_nanos": 9_223_372_036,
                    "x_radians_per_second": 1.25,
                    "y_radians_per_second": -2.5,
                    "z_radians_per_second": 0.5,
                },
            ),
            (
                "ambient_light.v1",
                "AMBIENT_LIGHT_SAMPLE",
                {
                    "accuracy": 2,
                    "illuminance_lux": 321.5,
                    "source_elapsed_realtime_nanos": 9_223_372_037,
                },
            ),
            (
                "proximity.v1",
                "PROXIMITY_SAMPLE",
                {
                    "distance_centimeters": 1.5,
                    "maximum_range_centimeters": 5.0,
                    "near": True,
                    "source_elapsed_realtime_nanos": 9_223_372_038,
                },
            ),
        ]
        events = [
            _event(
                index,
                collector_id=collector_id,
                payload_type=payload_type,
                fields=fields,
            )
            for index, (collector_id, payload_type, fields) in enumerate(fixtures, start=1)
        ]
        result = ReassemblyResult((), _Events(events), {"format": "particeps-quality-summary-v1"}, False)

        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "dataset"
            ParquetSink(CollectorCatalog(CATALOG)).write(result, destination)
            for collector_id, payload_type, expected in fixtures:
                path = next(
                    (
                        destination
                        / "experiment_id=study-id"
                        / "configuration_id=config-id"
                        / f"collector_id={collector_id}"
                        / "payload_schema_version=1"
                        / f"payload_type={payload_type}"
                    ).glob("*.parquet")
                )
                table = pq.ParquetFile(path).read()
                self.assertEqual(1, table.num_rows)
                row = table.to_pylist()[0]
                for name, value in expected.items():
                    if isinstance(value, float):
                        self.assertAlmostEqual(value, row[name], places=5)
                    else:
                        self.assertEqual(value, row[name])
                self.assertEqual("a" * 64, row["source_ciphertext_sha256"])
                self.assertEqual("bundle", row["source_bundle_id"])
                self.assertEqual("b" * 64, row["source_configuration_sha256"])
                self.assertEqual("memory:bundle", row["source_object"])
                self.assertEqual("int64", str(table.schema.field("sequence_number").type))
                for name, descriptor in self.catalog_fields(collector_id, payload_type).items():
                    self.assertEqual(descriptor, str(table.schema.field(name).type))

    def test_quality_observations_are_partitioned_and_bounded(self) -> None:
        events = [
            _event(
                sequence,
                collector_id="temporal_context.v1",
                payload_type="TEMPORAL_CONTEXT",
                boot_session_id=f"boot-{sequence:03d}",
            )
            for sequence in range(1, 151)
        ]
        collection = _Events(
            events,
            boot_sessions=[
                BootSession("study-id", "config-id", PARTICIPANT, f"boot-{index:03d}")
                for index in range(1, 151)
            ],
            sampling_groups=[
                SamplingGroup(
                    "study-id",
                    "config-id",
                    PARTICIPANT,
                    "gyroscope.v1",
                    f"boot-{index:03d}",
                    "source_elapsed_realtime_nanos",
                    index * 10,
                    index * 10,
                    1,
                )
                for index in range(1, 151)
            ],
            survey_counts=[
                SurveyLifecycleCount(
                    "study-id",
                    "config-id",
                    f"participant-{index:03d}",
                    "SURVEY_OPENED",
                    index,
                )
                for index in range(1, 151)
            ],
        )

        observations = _observations(
            collection,
            CollectorCatalog(CATALOG).sampling_clock_fields,
        )

        achieved = observations["achieved_sampling_observations"]
        self.assertEqual("150", achieved["count"])
        self.assertEqual(100, len(achieved["examples"]))
        self.assertTrue(achieved["examples_truncated"])
        boot_sessions = observations["boot_sessions"]
        self.assertEqual("1", boot_sessions["count"])
        session_ids = boot_sessions["examples"][0]["boot_session_ids"]
        self.assertEqual("150", session_ids["count"])
        self.assertEqual(100, len(session_ids["examples"]))
        survey = observations["survey_lifecycle_counts"]
        self.assertEqual("150", survey["count"])
        self.assertEqual("study-id", survey["examples"][0]["experiment_id"])
        self.assertEqual("config-id", survey["examples"][0]["configuration_id"])
        temporal = observations["temporal_context_events"]
        self.assertEqual("150", temporal["count"])
        self.assertEqual("study-id", temporal["examples"][0]["experiment_id"])
        self.assertEqual("config-id", temporal["examples"][0]["configuration_id"])

    def test_parquet_batches_obey_the_estimated_byte_cap(self) -> None:
        events = [_event(sequence) for sequence in range(1, 4)]
        collection = _Events(
            events,
            boot_sessions=[BootSession("study-id", "config-id", PARTICIPANT, "boot")],
            sampling_groups=[
                SamplingGroup(
                    "study-id",
                    "config-id",
                    PARTICIPANT,
                    "app_lifecycle.v1",
                    "boot",
                    "source_elapsed_realtime_nanos",
                    10,
                    30,
                    3,
                )
            ],
        )
        result = ReassemblyResult(
            (),
            collection,
            {"format": "particeps-quality-summary-v1"},
            False,
        )
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "dataset"
            with patch(
                "particeps_analysis.sink.PARQUET_BATCH_MAX_ESTIMATED_BYTES", 1
            ):
                ParquetSink(CollectorCatalog(CATALOG)).write(result, destination)
            parquet = pq.ParquetFile(next(destination.rglob("*.parquet")))
            self.assertEqual(3, parquet.metadata.num_row_groups)

    def test_batched_gyroscope_rate_uses_hardware_source_timestamps(self) -> None:
        catalog = CollectorCatalog(CATALOG)
        with tempfile.TemporaryDirectory() as temporary:
            database = EventDatabase(Path(temporary))
            for sequence in range(1, 52):
                database.add(
                    _event(
                        sequence,
                        collector_id="gyroscope.v1",
                        payload_type="GYROSCOPE_SAMPLE",
                        monotonic_time_nanos=9_000_000_000,
                        fields={
                            "accuracy": 3,
                            "source_elapsed_realtime_nanos": (sequence - 1)
                            * 20_000_000,
                            "x_radians_per_second": 0.1,
                            "y_radians_per_second": 0.2,
                            "z_radians_per_second": 0.3,
                        },
                    )
                )
            database.finish_candidates()
            for row in database.candidate_rows():
                database.accept(row)
            events = database.seal()
            self.addCleanup(events.close)

            observations = _observations(events, catalog.sampling_clock_fields)

        achieved = observations["achieved_sampling_observations"]["examples"]
        self.assertEqual(1, len(achieved))
        self.assertEqual("1000000000", achieved[0]["duration_monotonic_nanos"])
        self.assertEqual("50", achieved[0]["sampling_interval_count"])
        self.assertEqual("50000", achieved[0]["mean_sampling_rate_millihertz"])
        self.assertEqual(
            "source_elapsed_realtime_nanos",
            achieved[0]["source_clock_field"],
        )

    @staticmethod
    def catalog_fields(collector_id: str, payload_type: str) -> dict[str, str]:
        schema = CollectorCatalog(CATALOG).payload(collector_id, 1, payload_type)
        arrow_types = {
            "boolean": "bool",
            "decimal_string": "int64",
            "enum": "string",
            "float32": "float",
            "float64": "double",
            "int32": "int32",
            "json_string": "string",
            "string": "string",
        }
        return {name: arrow_types[str(field["type"])] for name, field in schema.fields.items()}


if __name__ == "__main__":
    unittest.main()
