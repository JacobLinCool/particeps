"""Deterministic event reassembly with explicit data-quality states."""

from __future__ import annotations

import heapq
from collections.abc import Iterable
from dataclasses import dataclass
from itertools import groupby, islice
from pathlib import Path
from typing import Any

from .event_store import DiskEventCollection, EventDatabase
from .models import PartitionedVerifiedEvents, VerifiedBundle, VerifiedEvent
from .summary import SUMMARY_EXAMPLE_LIMIT, BoundedExamples


@dataclass(frozen=True, slots=True)
class ReassemblyResult:
    bundles: tuple[VerifiedBundle, ...]
    events: PartitionedVerifiedEvents
    quality: dict[str, Any]
    has_conflicts: bool


class Reassembler:
    def __init__(self, staging_directory: Path):
        self.staging_directory = staging_directory

    def reassemble(self, bundles: Iterable[VerifiedBundle]) -> ReassemblyResult:
        ordered = tuple(
            sorted(
                bundles,
                key=lambda value: (
                    value.experiment_id,
                    value.configuration_id,
                    value.participant_instance_id,
                    value.first_sequence_number,
                    value.bundle_id,
                    value.source.sha256,
                    value.source.source_uri,
                ),
            )
        )
        return self._reassemble(ordered)

    def _reassemble(self, ordered: tuple[VerifiedBundle, ...]) -> ReassemblyResult:
        database = EventDatabase(self.staging_directory)
        collection: DiskEventCollection | None = None
        try:
            for bundle in ordered:
                for event in bundle.events:
                    database.add(event)
            database.finish_candidates()
            identical_duplicates = BoundedExamples[dict[str, Any]]()
            event_conflicts = BoundedExamples[dict[str, Any]]()
            current_identity = None
            first_row = None
            previous_canonical: bytes | None = None
            copies = 0
            content_variants = 0
            content_hash_examples: list[str] = []
            source_bundle_id_examples: list[str] = []
            sampled_source_bundle_ids: set[str] = set()

            def finish_identity() -> None:
                if current_identity is None or first_row is None:
                    return
                common = {
                    "configuration_id": current_identity[1],
                    "experiment_id": current_identity[0],
                    "participant_instance_id": current_identity[2],
                    "sequence_number": str(current_identity[3]),
                    "source_bundle_id_examples": source_bundle_id_examples,
                    "source_bundle_id_examples_truncated": (
                        copies > len(source_bundle_id_examples)
                    ),
                    "source_copy_count": str(copies),
                }
                if content_variants > 1:
                    event_conflicts.add(
                        {
                            **common,
                            "content_sha256_examples": content_hash_examples,
                            "content_sha256_examples_truncated": (
                                content_variants > len(content_hash_examples)
                            ),
                            "content_variant_count": str(content_variants),
                        }
                    )
                    return
                database.accept(first_row)
                if copies > 1:
                    identical_duplicates.add(common)

            for row in database.candidate_rows():
                identity = row[:4]
                if identity != current_identity:
                    finish_identity()
                    current_identity = identity
                    first_row = row
                    previous_canonical = None
                    copies = 0
                    content_variants = 0
                    content_hash_examples = []
                    source_bundle_id_examples = []
                    sampled_source_bundle_ids = set()
                copies += 1
                canonical = bytes(row[12])
                if canonical != previous_canonical:
                    content_variants += 1
                    previous_canonical = canonical
                    if len(content_hash_examples) < SUMMARY_EXAMPLE_LIMIT:
                        content_hash_examples.append(row[17])
                source_bundle_id = row[14]
                if (
                    source_bundle_id not in sampled_source_bundle_ids
                    and len(source_bundle_id_examples) < SUMMARY_EXAMPLE_LIMIT
                ):
                    sampled_source_bundle_ids.add(source_bundle_id)
                    source_bundle_id_examples.append(source_bundle_id)
            finish_identity()
            collection = database.seal()
            bundle_identity_conflicts = _bundle_identity_conflicts(ordered)
            quality = {
                "bundle_identity_conflicts": bundle_identity_conflicts.document(),
                "event_conflicts": event_conflicts.document(),
                "format": "adc-quality-summary-v1",
                "identical_event_duplicates": identical_duplicates.document(),
                "participant_coverage": _coverage_stream(ordered, collection),
                "range_overlaps": _bundle_overlaps(ordered),
            }
            return ReassemblyResult(
                ordered,
                collection,
                quality,
                bool(event_conflicts.count or bundle_identity_conflicts.count),
            )
        except Exception:
            if collection is None:
                database.abort()
            else:
                collection.close()
            raise


def _bundle_identity_conflicts(
    bundles: tuple[VerifiedBundle, ...],
) -> BoundedExamples[dict[str, Any]]:
    summary = BoundedExamples[dict[str, Any]]()
    ordered = sorted(bundles, key=lambda bundle: (bundle.bundle_id, bundle.source.sha256))
    for bundle_id, items in groupby(ordered, key=lambda bundle: bundle.bundle_id):
        variant_count = 0
        previous_digest = None
        digest_examples: list[str] = []
        for bundle in items:
            digest = bundle.source.sha256
            if digest == previous_digest:
                continue
            previous_digest = digest
            variant_count += 1
            if len(digest_examples) < SUMMARY_EXAMPLE_LIMIT:
                digest_examples.append(digest)
        if variant_count > 1:
            summary.add(
                {
                    "bundle_id": bundle_id,
                    "ciphertext_sha256_examples": digest_examples,
                    "ciphertext_sha256_examples_truncated": (
                        variant_count > len(digest_examples)
                    ),
                    "ciphertext_variant_count": str(variant_count),
                }
            )
    return summary


def _bundle_overlaps(bundles: tuple[VerifiedBundle, ...]) -> dict[str, object]:
    summary = BoundedExamples[dict[str, Any]]()
    nonempty = (bundle for bundle in bundles if bundle.event_count)
    for identity, grouped_items in groupby(nonempty, key=_bundle_participant):
        active: list[tuple[int, int, VerifiedBundle]] = []
        for unique_index, current in enumerate(grouped_items):
            while active and active[0][0] < current.first_sequence_number:
                heapq.heappop(active)
            overlap_count = len(active)
            summary.add_count(overlap_count)
            capacity = SUMMARY_EXAMPLE_LIMIT - len(summary.examples)
            for _, _, previous in islice(active, max(0, capacity)):
                summary.add_example(
                    {
                        "bundle_ids": sorted([previous.bundle_id, current.bundle_id]),
                        "configuration_id": identity[1],
                        "experiment_id": identity[0],
                        "first_sequence_number": str(current.first_sequence_number),
                        "last_sequence_number": str(
                            min(
                                previous.last_sequence_number,
                                current.last_sequence_number,
                            )
                        ),
                        "participant_instance_id": identity[2],
                    }
                )
            heapq.heappush(
                active, (current.last_sequence_number, unique_index, current)
            )
    return summary.document()


def _coverage_stream(
    bundles: tuple[VerifiedBundle, ...], events: Iterable[VerifiedEvent]
) -> dict[str, object]:
    event_groups = iter(groupby(events, key=lambda event: event.identity[:3]))
    current_group = next(event_groups, None)
    summary = BoundedExamples[dict[str, Any]]()
    for identity, items in groupby(bundles, key=_bundle_participant):
        latest = max(
            items,
            key=lambda item: (
                item.durable_through_sequence,
                item.retained_from_sequence,
                item.uploaded_through_sequence,
                item.exported_at_utc_millis,
                item.bundle_id,
                item.source.sha256,
            ),
        )
        if current_group is not None and current_group[0] < identity:
            raise RuntimeError("accepted event identity has no verified bundle")
        if current_group is not None and current_group[0] == identity:
            sequences = (event.sequence_number for event in current_group[1])
            summary.add(_streamed_coverage_record(identity, latest, sequences))
            current_group = next(event_groups, None)
        else:
            summary.add(_streamed_coverage_record(identity, latest, ()))
    if current_group is not None:
        raise RuntimeError("accepted event identity has no verified bundle")
    return summary.document()


def _streamed_coverage_record(
    identity: tuple[str, str, str],
    latest: VerifiedBundle,
    sequences: Iterable[int],
) -> dict[str, Any]:
    reclaimed = BoundedExamples[dict[str, str]]()
    gaps = BoundedExamples[dict[str, str]]()
    reclaimed_cursor = 1
    delivered_cursor = latest.retained_from_sequence
    maximum_delivered = 0
    for sequence in sequences:
        maximum_delivered = sequence
        if sequence < latest.retained_from_sequence:
            if sequence > reclaimed_cursor:
                reclaimed.add(
                    {"first": str(reclaimed_cursor), "last": str(sequence - 1)}
                )
            reclaimed_cursor = max(reclaimed_cursor, sequence + 1)
        else:
            if sequence > delivered_cursor:
                gaps.add(
                    {"first": str(delivered_cursor), "last": str(sequence - 1)}
                )
            delivered_cursor = max(delivered_cursor, sequence + 1)
    if reclaimed_cursor < latest.retained_from_sequence:
        reclaimed.add(
            {
                "first": str(reclaimed_cursor),
                "last": str(latest.retained_from_sequence - 1),
            }
        )
    undelivered_start = max(
        maximum_delivered + 1, latest.retained_from_sequence
    )
    not_yet_delivered = BoundedExamples[dict[str, str]]()
    if undelivered_start <= latest.durable_through_sequence:
        not_yet_delivered.add(
            {
                "first": str(undelivered_start),
                "last": str(latest.durable_through_sequence),
            }
        )
    return {
        "configuration_id": identity[1],
        "durable_through_sequence": str(latest.durable_through_sequence),
        "experiment_id": identity[0],
        "interior_gaps": gaps.document(),
        "not_yet_delivered": not_yet_delivered.document(),
        "participant_instance_id": identity[2],
        "reclaimed_prefix": reclaimed.document(),
        "retained_from_sequence": str(latest.retained_from_sequence),
    }


def _bundle_participant(bundle: VerifiedBundle) -> tuple[str, str, str]:
    return (
        bundle.experiment_id,
        bundle.configuration_id,
        bundle.participant_instance_id,
    )
