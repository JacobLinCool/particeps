"""Immutable values crossing analysis pipeline stages."""

from __future__ import annotations

from collections.abc import Iterator, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

from .engine import EngineCommit, RecordedEvent


class VerifiedEvents(Protocol):
    def __iter__(self) -> Iterator[VerifiedEvent]: ...
    def __len__(self) -> int: ...
    def close(self) -> None: ...


class PartitionedVerifiedEvents(VerifiedEvents, Protocol):
    def iter_partitioned(self) -> Iterator[VerifiedEvent]: ...
    def iter_boot_sessions(self) -> Iterator[BootSession]: ...
    def iter_sampling_groups(
        self, source_clock_fields: Mapping[tuple[str, int, str], str]
    ) -> Iterator[SamplingGroup]: ...
    def iter_survey_lifecycle_counts(self) -> Iterator[SurveyLifecycleCount]: ...


@dataclass(frozen=True, slots=True)
class SourceObject:
    source_uri: str
    size: int
    metadata: Mapping[str, str] | None
    opener: Any
    source_kind: str = "local"

    def open(self):
        return self.opener()


@dataclass(frozen=True, slots=True)
class InventoryObject:
    source_uri: str
    sha256: str
    byte_count: int
    cache_path: Path
    metadata: Mapping[str, str] | None
    source_kind: str = "local"


@dataclass(frozen=True, slots=True)
class EventProvenance:
    source_ciphertext_sha256: str
    source_bundle_id: str
    source_configuration_sha256: str
    source_object: str
    source_commit_sequence: int
    source_observation_sequence: int | None


@dataclass(frozen=True, slots=True)
class BootSession:
    experiment_id: str
    configuration_id: str
    participant_instance_id: str
    boot_session_id: str


@dataclass(frozen=True, slots=True)
class SamplingGroup:
    experiment_id: str
    configuration_id: str
    participant_instance_id: str
    source_id: str
    boot_session_id: str
    source_clock_field: str
    first_monotonic_time_nanos: int
    last_monotonic_time_nanos: int
    event_count: int


@dataclass(frozen=True, slots=True)
class SurveyLifecycleCount:
    experiment_id: str
    configuration_id: str
    participant_instance_id: str
    event_type: str
    event_count: int


@dataclass(frozen=True, slots=True)
class VerifiedEvent:
    experiment_id: str
    configuration_id: str
    participant_instance_id: str
    assigned_participant_id: str | None
    sequence_number: int
    source_id: str
    schema_version: int
    event_type: str
    condition_epoch_id: str | None
    source_condition_epoch_id: str | None
    boot_session_id: str
    monotonic_time_nanos: int
    wall_time_utc_millis: int
    fields: Mapping[str, Any]
    canonical_bytes: bytes
    provenance: EventProvenance

    @property
    def identity(self) -> tuple[str, str, str, int]:
        return (
            self.experiment_id,
            self.configuration_id,
            self.participant_instance_id,
            self.sequence_number,
        )

    @classmethod
    def from_recorded(
        cls,
        event: RecordedEvent,
        *,
        experiment_id: str,
        configuration_id: str,
        participant_instance_id: str,
        assigned_participant_id: str | None,
        provenance: EventProvenance,
    ) -> VerifiedEvent:
        return cls(
            experiment_id,
            configuration_id,
            participant_instance_id,
            assigned_participant_id,
            event.sequence_number,
            event.source_id,
            event.schema_version,
            event.event_type,
            event.condition_epoch_id,
            event.source_condition_epoch_id,
            event.observed_time.boot_session_id,
            event.observed_time.elapsed_realtime_nanos,
            event.observed_time.wall_time_utc_millis,
            event.typed_fields,
            event.canonical_bytes,
            provenance,
        )


@dataclass(frozen=True, slots=True)
class VerifiedBundle:
    bundle_id: str
    bundle_kind: str
    configuration_sha256: str
    event_source_registry_sha256: str
    configuration: Mapping[str, Any]
    experiment_id: str
    configuration_id: str
    participant_instance_id: str
    assigned_participant_id: str | None
    exported_at_utc_millis: int
    first_commit_sequence: int
    last_commit_sequence: int
    commit_count: int
    event_count: int
    retained_from_commit: int
    uploaded_through_commit: int
    evaluated_through_commit: int
    durable_through_commit: int
    next_commit_sequence: int
    lifetime_data_event_count: int
    state: str
    commits: tuple[EngineCommit, ...]
    source: InventoryObject
