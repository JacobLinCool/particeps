"""Commit-level deduplication followed by independent deterministic replay."""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from itertools import groupby
from pathlib import Path
from typing import Any

from .engine import GENESIS_DIGEST, EngineCommit, EngineReplayVerifier
from .errors import ValidationError
from .event_store import DiskEventCollection, EventDatabase
from .jcs import canonicalize
from .models import (
    EventProvenance,
    PartitionedVerifiedEvents,
    VerifiedBundle,
    VerifiedEvent,
)


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
        participant_records: list[dict[str, str]] = []
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
        except Exception:
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
    ) -> tuple[int, dict[str, str]]:
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
        commits: dict[int, tuple[EngineCommit, VerifiedBundle]] = {}
        duplicate_count = 0
        for bundle in bundles:
            for commit in bundle.commits:
                prior = commits.get(commit.commit_sequence)
                if prior is None:
                    commits[commit.commit_sequence] = (commit, bundle)
                elif prior[0].commit_sha256 == commit.commit_sha256:
                    duplicate_count += 1
                    if _bundle_order(bundle) < _bundle_order(prior[1]):
                        commits[commit.commit_sequence] = (commit, bundle)
                else:
                    raise ValidationError("authenticated commit sequence has conflicting variants")
        expected = list(range(1, latest.durable_through_commit + 1))
        if sorted(commits) != expected:
            missing = next((sequence for sequence in expected if sequence not in commits), None)
            raise ValidationError(f"commit chain is incomplete; missing commit {missing}")
        ordered_commits = [commits[sequence][0] for sequence in expected]
        if ordered_commits and ordered_commits[0].previous_commit_sha256 != GENESIS_DIGEST:
            raise ValidationError("complete participant chain does not start at genesis")
        verifier = EngineReplayVerifier(self.registry, first.configuration, first.configuration_sha256)
        replayed = verifier.replay(ordered_commits)
        observation_by_event: dict[int, int] = {}
        commit_by_event: dict[int, tuple[int, VerifiedBundle]] = {}
        for sequence in expected:
            commit, bundle = commits[sequence]
            for observation in commit.source_observations:
                if observation.first_event_sequence is not None and observation.last_event_sequence is not None:
                    for event_sequence in range(observation.first_event_sequence, observation.last_event_sequence + 1):
                        observation_by_event[event_sequence] = observation.observation_sequence
            for event in commit.events:
                commit_by_event[event.sequence_number] = (sequence, bundle)
        for event in replayed:
            commit_sequence, bundle = commit_by_event[event.sequence_number]
            database.add(
                VerifiedEvent.from_recorded(
                    event,
                    experiment_id=identity[0],
                    configuration_id=identity[1],
                    participant_instance_id=identity[2],
                    assigned_participant_id=first.assigned_participant_id,
                    provenance=EventProvenance(
                        bundle.source.sha256,
                        bundle.bundle_id,
                        bundle.configuration_sha256,
                        bundle.source.source_uri,
                        commit_sequence,
                        observation_by_event.get(event.sequence_number),
                    ),
                )
            )
        final = ordered_commits[-1].successor_projection if ordered_commits else None
        if final is None or (
            final["revision"] != latest.durable_through_commit
            or final["state"] != latest.state
            or final["next_commit_sequence"] != latest.next_commit_sequence
            or final["lifetime_data_event_count"] != latest.lifetime_data_event_count
        ):
            raise ValidationError("latest participant snapshot diverges from replayed commit head")
        return duplicate_count, {
            "configuration_id": identity[1],
            "durable_through_commit": str(latest.durable_through_commit),
            "experiment_id": identity[0],
            "participant_instance_id": identity[2],
            "replayed_event_count": str(len(replayed)),
        }


def _bundle_order(bundle: VerifiedBundle) -> tuple:
    return (
        bundle.experiment_id, bundle.configuration_id, bundle.participant_instance_id,
        bundle.first_commit_sequence, bundle.bundle_id, bundle.source.sha256,
        bundle.source.source_uri,
    )


def _bundle_participant(bundle: VerifiedBundle) -> tuple[str, str, str]:
    return bundle.experiment_id, bundle.configuration_id, bundle.participant_instance_id
