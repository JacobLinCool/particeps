"""Small immutable values shared by pipeline stages."""

from __future__ import annotations

from collections.abc import Iterator, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol


class VerifiedEvents(Protocol):
    def __iter__(self) -> Iterator[VerifiedEvent]: ...

    def __len__(self) -> int: ...

    def close(self) -> None: ...


class PartitionedVerifiedEvents(VerifiedEvents, Protocol):
    def iter_partitioned(self) -> Iterator[VerifiedEvent]: ...

    def iter_boot_sessions(self) -> Iterator[BootSession]: ...

    def iter_sampling_groups(
        self,
        source_clock_fields: Mapping[tuple[str, int, str], str],
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
    collector_id: str
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
    payload_type: str
    event_count: int


@dataclass(frozen=True, slots=True)
class VerifiedEvent:
    experiment_id: str
    configuration_id: str
    participant_instance_id: str
    assigned_participant_id: str | None
    sequence_number: int
    collector_id: str
    payload_schema_version: int
    payload_type: str
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


@dataclass(frozen=True, slots=True)
class VerifiedBundle:
    bundle_id: str
    bundle_kind: str
    configuration_sha256: str
    experiment_id: str
    configuration_id: str
    participant_instance_id: str
    exported_at_utc_millis: int
    first_sequence_number: int
    last_sequence_number: int
    event_count: int
    retained_from_sequence: int
    uploaded_through_sequence: int
    durable_through_sequence: int
    next_sequence_number: int
    events: VerifiedEvents
    source: InventoryObject
