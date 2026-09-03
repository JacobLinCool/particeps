from __future__ import annotations

import copy
import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path

import pyarrow.parquet as pq
from factories import (
    CONFIGURATION_SHA256,
    configuration,
    empty_commit_document,
    inventory_source,
    parse_empty_commit,
    resign_commit,
)

from particeps_analysis.engine import EngineCommitParser
from particeps_analysis.errors import ValidationError
from particeps_analysis.event_store import EventDatabase
from particeps_analysis.jcs import canonicalize
from particeps_analysis.models import EventProvenance, VerifiedBundle, VerifiedEvent
from particeps_analysis.reassembly import Reassembler, ReassemblyResult
from particeps_analysis.registry import EventSourceRegistry
from particeps_analysis.sink import ParquetSink

PARTICIPANT_ID = "95a484e3-2ba5-4d35-9b2f-03ae394235e7"
CONDITION_EPOCH_ID = "fef4f46b-d45e-40b0-9701-06031759791d"


def verified_bundle(path: Path) -> VerifiedBundle:
    commit = parse_empty_commit()
    registry = EventSourceRegistry()
    return VerifiedBundle(
        bundle_id="c3ab3ec1-583b-4cf6-80bf-ff1723cc64e5",
        bundle_kind="manual_export",
        configuration_sha256=CONFIGURATION_SHA256,
        event_source_registry_sha256=registry.digest,
        configuration=configuration(),
        experiment_id="study-one",
        configuration_id="config-one",
        participant_instance_id=PARTICIPANT_ID,
        assigned_participant_id=None,
        exported_at_utc_millis=1,
        first_commit_sequence=1,
        last_commit_sequence=1,
        commit_count=1,
        event_count=0,
        retained_from_commit=1,
        uploaded_through_commit=0,
        evaluated_through_commit=1,
        durable_through_commit=1,
        next_commit_sequence=2,
        lifetime_data_event_count=0,
        state="READY",
        commits=(commit,),
        source=inventory_source(path),
    )


def verified_battery_event() -> VerifiedEvent:
    fields = {
        "charging_source": "USB",
        "charging_state": "CHARGING",
        "percentage": 50,
        "power_save_enabled": False,
    }
    return VerifiedEvent(
        experiment_id="study-one",
        configuration_id="config-one",
        participant_instance_id=PARTICIPANT_ID,
        assigned_participant_id=None,
        sequence_number=4,
        source_id="battery_state.v1",
        schema_version=1,
        event_type="BATTERY_STATE",
        condition_epoch_id=CONDITION_EPOCH_ID,
        source_condition_epoch_id=CONDITION_EPOCH_ID,
        boot_session_id="boot-one",
        monotonic_time_nanos=10,
        wall_time_utc_millis=1_000,
        fields=fields,
        canonical_bytes=canonicalize(
            {
                "condition_epoch_id": CONDITION_EPOCH_ID,
                "event_type": "BATTERY_STATE",
                "fields": {
                    "charging_source": "USB",
                    "charging_state": "CHARGING",
                    "percentage": "50",
                    "power_save_enabled": "false",
                },
                "observed_time": {
                    "boot_session_id": "boot-one",
                    "elapsed_realtime_nanos": "10",
                    "wall_time_utc_millis": "1000",
                },
                "schema_version": 1,
                "sequence_number": "4",
                "source_id": "battery_state.v1",
            }
        ),
        provenance=EventProvenance(
            source_ciphertext_sha256="b" * 64,
            source_bundle_id="c3ab3ec1-583b-4cf6-80bf-ff1723cc64e5",
            source_configuration_sha256=CONFIGURATION_SHA256,
            source_object="file:///bundle.partexp",
            source_commit_sequence=2,
            source_observation_sequence=1,
        ),
    )


class ReassemblyAndMaterializationTest(unittest.TestCase):
    def test_reassembly_requires_complete_genesis_commit_chain(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = verified_bundle(root / "bundle.partexp")
            result = Reassembler(root / "reassembly", EventSourceRegistry()).reassemble(
                [bundle]
            )
            try:
                self.assertEqual(0, len(result.events))
                self.assertEqual(
                    "1",
                    result.quality["commit_chain_verification"]["participants"][0][
                        "durable_through_commit"
                    ],
                )
            finally:
                result.events.close()

            partial = replace(
                bundle,
                durable_through_commit=2,
                evaluated_through_commit=2,
                next_commit_sequence=3,
            )
            with self.assertRaisesRegex(ValidationError, "missing commit 2"):
                Reassembler(root / "partial", EventSourceRegistry()).reassemble(
                    [partial]
                )

    def test_conflicting_authenticated_commit_variant_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = verified_bundle(root / "first.partexp")
            document = copy.deepcopy(empty_commit_document())
            document["committed_at"]["wall_time_utc_millis"] = "2"
            variant = EngineCommitParser(EventSourceRegistry()).parse(
                resign_commit(document)
            )
            second = replace(
                first,
                bundle_id="fe209002-e69a-4a4d-854f-de8c7d7be610",
                commits=(variant,),
                source=inventory_source(root / "second.partexp"),
            )
            with self.assertRaisesRegex(ValidationError, "conflicting variants"):
                Reassembler(root / "conflict", EventSourceRegistry()).reassemble(
                    [first, second]
                )

    def test_parquet_uses_source_contract_partition_and_epoch_columns(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = EventDatabase(root / "event-store")
            database.add(verified_battery_event())
            database.finish_candidates()
            for row in database.candidate_rows():
                database.accept(row)
            events = database.seal()
            destination = root / "dataset"
            try:
                ParquetSink(EventSourceRegistry()).write(
                    ReassemblyResult(
                        bundles=(),
                        events=events,
                        quality={"format": "particeps-quality-summary-v1"},
                        has_conflicts=False,
                    ),
                    destination,
                )
            finally:
                events.close()

            parquet = (
                destination
                / "experiment_id=study-one"
                / "configuration_id=config-one"
                / "source_id=battery_state.v1"
                / "schema_version=1"
                / "event_type=BATTERY_STATE"
                / "part-00000.parquet"
            )
            self.assertTrue(parquet.is_file())
            table = pq.read_table(parquet)
            self.assertEqual(
                [CONDITION_EPOCH_ID], table.column("condition_epoch_id").to_pylist()
            )
            self.assertEqual(
                [CONDITION_EPOCH_ID],
                table.column("source_condition_epoch_id").to_pylist(),
            )
            self.assertEqual([50], table.column("percentage").to_pylist())
            self.assertFalse(any(destination.rglob("collector_id=*")))
            manifest = json.loads((destination / "dataset-manifest.json").read_text())
            self.assertEqual(EventSourceRegistry().digest, manifest["event_source_registry_sha256"])
            self.assertIn("source_id=battery_state.v1", manifest["partitions"][0]["file"])


if __name__ == "__main__":
    unittest.main()
