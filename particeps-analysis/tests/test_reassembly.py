from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from particeps_analysis.catalog import CollectorCatalog
from particeps_analysis.errors import ConflictError
from particeps_analysis.models import (
    EventProvenance,
    InventoryObject,
    VerifiedBundle,
    VerifiedEvent,
)
from particeps_analysis.reassembly import Reassembler
from particeps_analysis.sink import ParquetSink

REPOSITORY = Path(__file__).resolve().parents[2]


class _MemoryEvents:
    def __init__(self, events: tuple[VerifiedEvent, ...]):
        self.events = events

    def __iter__(self):
        return iter(self.events)

    def __len__(self) -> int:
        return len(self.events)

    def close(self) -> None:
        pass


def _event(
    sequence: int, content: bytes = b"same", bundle_id: str = "bundle-a"
) -> VerifiedEvent:
    provenance = EventProvenance("a" * 64, bundle_id, "b" * 64, f"memory:{bundle_id}")
    return VerifiedEvent(
        "study-id",
        "config-id",
        "00000000-0000-0000-0000-000000000001",
        None,
        sequence,
        "app_lifecycle.v1",
        1,
        "ACTIVITY_CREATED",
        "boot",
        sequence * 10,
        sequence * 20,
        {"activity_class": "Activity"},
        content,
        provenance,
    )


def _bundle(
    bundle_id: str,
    events: tuple[VerifiedEvent, ...],
    *,
    first: int | None = None,
    last: int | None = None,
    retained: int = 1,
    durable: int | None = None,
    sha: str = "a" * 64,
) -> VerifiedBundle:
    first = first if first is not None else (events[0].sequence_number if events else 1)
    last = (
        last
        if last is not None
        else (events[-1].sequence_number if events else first - 1)
    )
    durable = durable if durable is not None else max(last, 0)
    source = InventoryObject(
        f"memory:{bundle_id}:{sha}", sha, 100, Path("/tmp/unused"), None
    )
    return VerifiedBundle(
        bundle_id,
        "manual_export",
        "b" * 64,
        "study-id",
        "config-id",
        "00000000-0000-0000-0000-000000000001",
        durable,
        first,
        last,
        len(events),
        retained,
        max(0, retained - 1),
        durable,
        durable + 1,
        _MemoryEvents(events),
        source,
    )


class ReassemblyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.reassembly_index = 0

    def _reassemble(self, bundles):
        self.reassembly_index += 1
        result = Reassembler(
            Path(self.temporary.name) / f"reassembly-{self.reassembly_index}"
        ).reassemble(bundles)
        self.addCleanup(result.events.close)
        return result

    def test_duplicates_overlaps_gaps_and_reclaimed_prefix_are_distinct(self) -> None:
        first = _event(3, bundle_id="bundle-a")
        duplicate = _event(3, bundle_id="bundle-b")
        fourth = _event(4, b"four", bundle_id="bundle-b")
        result = self._reassemble(
            [
                _bundle("bundle-a", (first,), retained=3, durable=6),
                _bundle("bundle-b", (duplicate, fourth), retained=3, durable=6),
            ]
        )
        self.assertFalse(result.has_conflicts)
        self.assertEqual([3, 4], [event.sequence_number for event in result.events])
        self.assertEqual("1", result.quality["identical_event_duplicates"]["count"])
        self.assertEqual("1", result.quality["range_overlaps"]["count"])
        coverage = result.quality["participant_coverage"]["examples"][0]
        self.assertEqual(
            [{"first": "1", "last": "2"}],
            coverage["reclaimed_prefix"]["examples"],
        )
        self.assertEqual([], coverage["interior_gaps"]["examples"])
        self.assertEqual(
            [{"first": "5", "last": "6"}],
            coverage["not_yet_delivered"]["examples"],
        )

    def test_content_conflict_has_no_winner(self) -> None:
        result = self._reassemble(
            [
                _bundle("bundle-a", (_event(1, b"one", "bundle-a"),)),
                _bundle(
                    "bundle-b", (_event(1, b"different", "bundle-b"),), sha="c" * 64
                ),
            ]
        )
        self.assertTrue(result.has_conflicts)
        self.assertEqual([], list(result.events))
        self.assertEqual("1", result.quality["event_conflicts"]["count"])
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "dataset"
            with self.assertRaises(ConflictError):
                ParquetSink(
                    CollectorCatalog(
                        REPOSITORY / "protocol" / "v1" / "collector-catalog.json"
                    )
                ).write(result, destination)
            self.assertFalse(destination.exists())

    def test_interior_gap_is_not_a_not_yet_delivered_suffix(self) -> None:
        result = self._reassemble(
            [
                _bundle("bundle-a", (_event(1),), durable=5),
                _bundle(
                    "bundle-b",
                    (_event(3, b"three", "bundle-b"),),
                    durable=5,
                    sha="c" * 64,
                ),
            ]
        )
        coverage = result.quality["participant_coverage"]["examples"][0]
        self.assertEqual(
            [{"first": "2", "last": "2"}],
            coverage["interior_gaps"]["examples"],
        )
        self.assertEqual(
            [{"first": "4", "last": "5"}],
            coverage["not_yet_delivered"]["examples"],
        )

    def test_reassembled_event_collection_is_repeatable(self) -> None:
        bundles = [
            _bundle("bundle-a", (_event(1, bundle_id="bundle-a"),), durable=3),
            _bundle(
                "bundle-b",
                (
                    _event(1, bundle_id="bundle-b"),
                    _event(2, b"two", "bundle-b"),
                ),
                durable=3,
                sha="c" * 64,
            ),
        ]
        result = self._reassemble(bundles)
        first = [event.canonical_bytes for event in result.events]
        second = [event.canonical_bytes for event in result.events]
        self.assertEqual(first, second)
        self.assertEqual([b"same", b"two"], first)



if __name__ == "__main__":
    unittest.main()
