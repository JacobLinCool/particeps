"""Commit-level deduplication followed by independent deterministic replay."""

from __future__ import annotations

from collections.abc import Iterable, Iterator
from contextlib import closing
from dataclasses import dataclass
from itertools import groupby
from pathlib import Path
from typing import Any

from .commit_store import PrivateDatabase
from .engine import EngineCommit, EngineCommitParser, EngineReplayVerifier, ResearchTime
from .errors import ValidationError
from .event_store import DiskEventCollection, EventDatabase
from .jcs import canonicalize, parse
from .models import (
    EventProvenance,
    PartitionedVerifiedEvents,
    VerifiedBundle,
    VerifiedEvent,
)
from .usage_coverage import UsageCoverageAccumulator


@dataclass(frozen=True, slots=True)
class ReassemblyResult:
    bundles: tuple[VerifiedBundle, ...]
    events: PartitionedVerifiedEvents
    quality: dict[str, Any]
    has_conflicts: bool


class Reassembler:
    def __init__(self, staging_directory: Path, registry):
        self.staging_directory = staging_directory
        self.registry = registry

    def reassemble(self, bundles: Iterable[VerifiedBundle]) -> ReassemblyResult:
        ordered = tuple(sorted(bundles, key=_bundle_order))
        database = EventDatabase(self.staging_directory)
        collection: DiskEventCollection | None = None
        commit_duplicates = 0
        participant_records: list[dict[str, Any]] = []
        try:
            for identity, group in groupby(ordered, key=_bundle_participant):
                participant_bundles = tuple(group)
                duplicates, record = self._replay_participant(identity, participant_bundles, database)
                commit_duplicates += duplicates
                participant_records.append(record)
            database.finish_candidates()
            # A commit is the authenticated atomic unit. Replayed event identities therefore
            # cannot legitimately conflict; the candidate phase only deduplicates identical
            # ciphertext exports of the same durable commit.
            current_identity = None
            first_row = None
            canonical = None
            for row in database.candidate_rows():
                identity = row[:4]
                row_canonical = bytes(row[14])
                if identity != current_identity:
                    if first_row is not None:
                        database.accept(first_row)
                    current_identity, first_row, canonical = identity, row, row_canonical
                elif row_canonical != canonical:
                    raise ValidationError("replayed event identity has conflicting canonical bytes")
            if first_row is not None:
                database.accept(first_row)
            collection = database.seal()
            quality = {
                "format": "particeps-quality-summary-v1",
                "commit_chain_verification": {
                    "identical_commit_duplicates": str(commit_duplicates),
                    "participants": participant_records,
                },
                "validation_policy": "fail_closed",
            }
            return ReassemblyResult(ordered, collection, quality, False)
        except BaseException:
            if collection is None:
                database.abort()
            else:
                collection.close()
            raise

    def _replay_participant(
        self,
        identity: tuple[str, str, str],
        bundles: tuple[VerifiedBundle, ...],
        database: EventDatabase,
    ) -> tuple[int, dict[str, Any]]:
        first = bundles[0]
        configuration_bytes = canonicalize(first.configuration)
        for bundle in bundles:
            if (
                bundle.configuration_sha256 != first.configuration_sha256
                or bundle.event_source_registry_sha256 != first.event_source_registry_sha256
                or bundle.assigned_participant_id != first.assigned_participant_id
                or canonicalize(bundle.configuration) != configuration_bytes
            ):
                raise ValidationError("participant bundles disagree on signed study identity")
        latest = max(
            bundles,
            key=lambda item: (
                item.durable_through_commit, item.evaluated_through_commit,
                item.exported_at_utc_millis, item.bundle_id,
            ),
        )
        index = PrivateDatabase(self.staging_directory, "particeps-participant-")
        duplicate_count = 0
        replayed_count = 0
        last_commit: EngineCommit | None = None
        usage_coverage = UsageCoverageAccumulator()
        try:
            index.connection.executescript(
                "CREATE TABLE commits (sequence INTEGER PRIMARY KEY, digest TEXT NOT NULL, "
                "payload BLOB NOT NULL, bundle INTEGER NOT NULL);"
                "CREATE TABLE provenance (sequence INTEGER PRIMARY KEY, commit_sequence INTEGER NOT NULL, "
                "bundle INTEGER NOT NULL, observation INTEGER);"
            )
            # Input bundles already have the deterministic provenance order. Keep its first
            # copy, count identical duplicates, and refuse every conflicting variant.
            for bundle_number, bundle in enumerate(bundles):
                for commit in bundle.commits:
                    prior = index.connection.execute(
                        "SELECT digest FROM commits WHERE sequence = ?", (commit.commit_sequence,)
                    ).fetchone()
                    if prior is None:
                        index.connection.execute(
                            "INSERT INTO commits VALUES (?, ?, ?, ?)",
                            (commit.commit_sequence, commit.commit_sha256, commit.canonical_bytes, bundle_number),
                        )
                    elif prior[0] == commit.commit_sha256:
                        duplicate_count += 1
                    else:
                        raise ValidationError("authenticated commit sequence has conflicting variants")
            index.connection.commit()

            def ordered_commits() -> Iterator[EngineCommit]:
                nonlocal last_commit
                parser = EngineCommitParser(self.registry)
                expected = 1
                cursor = index.connection.execute(
                    "SELECT sequence, payload, bundle FROM commits ORDER BY sequence"
                )
                try:
                    for sequence, payload, bundle_number in cursor:
                        if sequence != expected or sequence > latest.durable_through_commit:
                            raise ValidationError(f"commit chain is incomplete; missing commit {expected}")
                        commit = parser.parse(parse(bytes(payload)))
                        if commit.commit_sequence != sequence:
                            raise ValidationError("commit index sequence disagrees with authenticated commit")
                        observation_by_event = {}
                        for observation in commit.source_observations:
                            usage_coverage.add(observation)
                            if observation.first_event_sequence is not None:
                                for event_sequence in range(
                                    observation.first_event_sequence, observation.last_event_sequence + 1
                                ):
                                    observation_by_event[event_sequence] = observation.observation_sequence
                        for event in commit.events:
                            index.connection.execute(
                                "INSERT INTO provenance VALUES (?, ?, ?, ?)",
                                (event.sequence_number, sequence, bundle_number,
                                 observation_by_event.get(event.sequence_number)),
                            )
                        last_commit = commit
                        expected += 1
                        yield commit
                    if expected != latest.durable_through_commit + 1:
                        raise ValidationError(f"commit chain is incomplete; missing commit {expected}")
                finally:
                    cursor.close()

            verifier = EngineReplayVerifier(
                self.registry, first.configuration, first.configuration_sha256,
                evidence_directory=self.staging_directory,
            )
            with closing(ordered_commits()) as commits, closing(verifier.iter_replay(commits)) as replayed:
                for event in replayed:
                    row = index.connection.execute(
                        "SELECT commit_sequence, bundle, observation FROM provenance WHERE sequence = ?",
                        (event.sequence_number,),
                    ).fetchone()
                    if row is None:
                        raise ValidationError("replayed event has no authenticated commit provenance")
                    commit_sequence, bundle_number, observation_sequence = row
                    bundle = bundles[bundle_number]
                    database.add(
                        VerifiedEvent.from_recorded(
                            event,
                            experiment_id=identity[0],
                            configuration_id=identity[1],
                            participant_instance_id=identity[2],
                            assigned_participant_id=first.assigned_participant_id,
                            provenance=EventProvenance(
                                bundle.source.sha256, bundle.bundle_id,
                                bundle.configuration_sha256, bundle.source.source_uri,
                                commit_sequence, observation_sequence,
                            ),
                        )
                    )
                    replayed_count += 1
            final = last_commit.successor_projection if last_commit is not None else None
            if final is None or (
                final["revision"] != latest.durable_through_commit
                or final["state"] != latest.state
                or final["next_commit_sequence"] != latest.next_commit_sequence
                or final["lifetime_data_event_count"] != latest.lifetime_data_event_count
            ):
                raise ValidationError("latest participant snapshot diverges from replayed commit head")
            return duplicate_count, {
                "condition_epochs": _condition_epochs(verifier),
                "configuration_id": identity[1],
                "durable_through_commit": str(latest.durable_through_commit),
                "experiment_id": identity[0],
                "participant_instance_id": identity[2],
                "replayed_event_count": str(replayed_count),
                "latest_committed_at": _time_record(last_commit.committed_at),
                "usage_coverage": usage_coverage.intervals(verifier.known_epochs),
            }
        finally:
            index.close()


def _condition_epochs(verifier: EngineReplayVerifier) -> list[dict[str, Any]]:
    """Each epoch's verified source interval, in activation order.

    Retrospective coverage, and so collector rows, may begin at the preparation bound: the later of
    the preceding deactivation and the latest entry into ACTIVATING, before any condition resource
    was confirmed. Rows whose source time precedes `activated_at` fall in that preparation slice.
    """

    records = []
    for epoch_id, epoch in verifier.known_epochs.items():
        closed = verifier.closed_epochs.get(epoch_id)
        records.append({
            "activated_at": _time_record(epoch.activated_at),
            "condition_epoch_id": epoch_id,
            "deactivated_at": None if closed is None else _time_record(closed[1]),
            "preparation_bound": _time_record(verifier.epoch_preparation_bounds[epoch_id]),
        })
    return records


def _bundle_order(bundle: VerifiedBundle) -> tuple:
    return (
        bundle.experiment_id, bundle.configuration_id, bundle.participant_instance_id,
        bundle.first_commit_sequence, bundle.bundle_id, bundle.source.sha256,
        bundle.source.source_uri,
    )


def _bundle_participant(bundle: VerifiedBundle) -> tuple[str, str, str]:
    return bundle.experiment_id, bundle.configuration_id, bundle.participant_instance_id


def _time_record(value: ResearchTime) -> dict[str, str]:
    return {
        "boot_session_id": value.boot_session_id,
        "elapsed_realtime_nanos": str(value.elapsed_realtime_nanos),
        "wall_time_utc_millis": str(value.wall_time_utc_millis),
    }
