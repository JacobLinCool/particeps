"""Independent parser and verifier for authenticated EngineCommit chains."""

from __future__ import annotations

import base64
import binascii
import hashlib
import io
import re
import struct
import uuid
from collections.abc import Iterable, Mapping
from dataclasses import dataclass, replace
from itertools import pairwise
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from .automation_checkpoint import (
    automation_checkpoint_digest as authoritative_checkpoint_digest,
)
from .automation_checkpoint import (
    decode_automation_checkpoint as decode_authoritative_checkpoint,
)
from .automation_compiler import compile_automation_program
from .automation_model import (
    AutomationCheckpoint,
    AutomationEvent,
    DurableTimer,
    ReducerClock,
    ReducerInput,
    ResourceKey,
    TimerTarget,
)
from .automation_model import (
    ResearchTime as AutomationResearchTime,
)
from .automation_reducer import reduce_automation_batch
from .errors import ValidationError
from .jcs import canonicalize, exact_object, parse_embedded_json
from .registry import EventSourceRegistry

GENESIS_DIGEST = "0" * 64
_SHA256 = re.compile(r"[0-9a-f]{64}\Z")
_SOURCE_ID = re.compile(r"[a-z][a-z0-9_.-]{2,63}\Z")
_EVENT_TYPE = re.compile(r"[A-Z][A-Z0-9_]{1,63}\Z")
_COMPONENT_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:@/-]{0,191}\Z")
_BOOT_ID = re.compile(r"[A-Za-z0-9._:-]{1,128}\Z")
_LIFECYCLE_STATES = {
    "IMPORTED", "CONFIG_VERIFIED", "CONSENT_PENDING", "ACCESS_SETUP", "READY",
    "ACTIVATING", "RUNNING", "PAUSING", "PAUSED", "COMPLETED", "WITHDRAWN",
}
_INPUT_KINDS = {
    "SOURCE_OBSERVATION", "LIFECYCLE_COMMAND", "TIMER_WAKE", "RANDOM_SELECTION",
    "ACTION_RESULT", "UPLOAD_ACKNOWLEDGEMENT", "RESOURCE_RESULT", "SAFETY_FAILURE",
    "RECOVERY",
}
_COMPONENT_KINDS = {
    "AUTOMATION_CHECKPOINT", "TIMER", "STUDY_DEADLINE_TIMER", "RESOURCE_AUDIT_TIMER", "ACTION_INVOCATION",
    "UPLOAD_ACKNOWLEDGEMENT", "RESOURCE", "RESOURCE_CLEANUP",
}
_RESOURCE_STATUSES = {"APPLIED", "INACTIVE", "OPTIONAL_FAILED"}
_ACTION_STATES = {"READY", "CLAIMED", "OPENED", "SUCCEEDED", "FAILED"}
_SESSION_STATES = {
    "READY", "ACTIVATING", "RUNNING", "PAUSING", "PAUSED", "COMPLETED", "WITHDRAWN",
}


@dataclass(frozen=True, slots=True)
class ResearchTime:
    wall_time_utc_millis: int
    elapsed_realtime_nanos: int
    boot_session_id: str


@dataclass(frozen=True, slots=True)
class SourceCoverage:
    clock_basis: str
    start_inclusive: str
    end_exclusive: str


@dataclass(frozen=True, slots=True)
class ConditionEpoch:
    id: str
    configuration_sha256: str
    applied_resource_vector_sha256: str
    activated_at: ResearchTime


@dataclass(frozen=True, slots=True)
class AppliedResource:
    kind: str
    id: str
    desired_generation: int
    profile_id: str | None
    applied_profile_sha256: str | None
    status: str
    failure_reason: str | None

    @property
    def key(self) -> tuple[str, str]:
        return self.kind, self.id


@dataclass(frozen=True, slots=True)
class ResourceCleanup:
    kind: str
    id: str
    generation: int
    profile_id: str
    expected_profile_sha256: str

    @property
    def key(self) -> tuple[str, str]:
        return self.kind, self.id


@dataclass(frozen=True, slots=True)
class DurableAction:
    action_id: str
    automation_id: str
    intervention_id: str
    causal_sequence: int
    logical_deadline_utc_millis: int | None
    expires_at_utc_millis: int
    condition_sha256: str
    generation: int
    requested_at: ResearchTime
    opened_at: ResearchTime | None
    state: str
    failure_reason: str | None


@dataclass(frozen=True, slots=True)
class UploadAcknowledgement:
    bundle_id: str
    first_commit: int
    through_commit: int
    bundle_sha256: str
    acknowledged_at: ResearchTime


@dataclass(slots=True)
class TrafficAuditState:
    resource: AppliedResource
    vpn_generation_id: str
    last_counters: tuple[int, int, int, int, int, int] | None = None
    last_snapshot_reason: str | None = None
    removed: bool = False


@dataclass(frozen=True, slots=True)
class RecordedEvent:
    sequence_number: int
    source_id: str
    schema_version: int
    event_type: str
    observed_time: ResearchTime
    condition_epoch_id: str | None
    wire_fields: Mapping[str, str]
    typed_fields: Mapping[str, Any]
    canonical_bytes: bytes
    source_condition_epoch_id: str | None = None

    @property
    def key(self) -> tuple[str, int, str]:
        return self.source_id, self.schema_version, self.event_type


@dataclass(frozen=True, slots=True)
class SourceObservation:
    observation_sequence: int
    source_id: str
    schema_version: int
    resource_generation: int
    admission_kind: str
    producer_ordinal: int
    condition_epoch_id: str
    event_count: int
    first_event_sequence: int | None
    last_event_sequence: int | None
    coverage: SourceCoverage | None
    encoded_sha256: str


@dataclass(frozen=True, slots=True)
class RuntimeMutation:
    component_kind: str
    component_id: str
    operation: str
    canonical_value: str | None


@dataclass(frozen=True, slots=True)
class EngineCommit:
    commit_sequence: int
    previous_commit_sha256: str
    input_kind: str
    consumed_pending_input_sha256: str | None
    source_observations: tuple[SourceObservation, ...]
    events: tuple[RecordedEvent, ...]
    mutations: tuple[RuntimeMutation, ...]
    committed_at: ResearchTime
    successor_projection: Mapping[str, Any]
    resulting_checkpoint_sha256: str
    commit_sha256: str
    canonical_bytes: bytes


class EngineCommitParser:
    def __init__(self, registry: EventSourceRegistry):
        self.registry = registry

    def parse(self, value: Any) -> EngineCommit:
        root = exact_object(value, {
            "commit_sequence", "previous_commit_sha256", "input_kind",
            "consumed_pending_input_sha256", "source_observations", "events",
            "mutations", "committed_at", "successor_projection",
            "resulting_checkpoint_sha256", "commit_sha256",
        }, "engine commit")
        sequence = _long(root["commit_sequence"], "commit sequence", minimum=1)
        previous = _digest(root["previous_commit_sha256"], "previous commit digest", allow_genesis=True)
        input_kind = _enum(root["input_kind"], _INPUT_KINDS, "engine input kind")
        pending = _nullable_digest(root["consumed_pending_input_sha256"], "pending-input digest")
        observations = tuple(self._observation(item) for item in _array(root["source_observations"], "source observations"))
        events = tuple(self._event(item) for item in _array(root["events"], "events"))
        mutations = tuple(self._mutation(item) for item in _array(root["mutations"], "mutations"))
        committed_at = _time(root["committed_at"])
        projection = self._projection(root["successor_projection"])
        checkpoint_digest = _digest(root["resulting_checkpoint_sha256"], "checkpoint digest")
        commit_digest = _digest(root["commit_sha256"], "commit digest")
        commit = EngineCommit(
            sequence, previous, input_kind, pending, observations, events, mutations,
            committed_at, projection, checkpoint_digest, commit_digest,
            canonicalize(root),
        )
        self._validate_local(commit)
        if calculate_commit_sha256(commit) != commit.commit_sha256:
            raise ValidationError("engine commit digest mismatch")
        return commit

    def _event(self, value: Any) -> RecordedEvent:
        root = exact_object(value, {
            "sequence_number", "source_id", "schema_version", "event_type",
            "observed_time", "condition_epoch_id", "fields",
        }, "recorded event")
        sequence = _long(root["sequence_number"], "event sequence", minimum=1)
        source_id = _source_id(root["source_id"])
        schema_version = _int(root["schema_version"], "schema version", minimum=1)
        event_type = _event_type(root["event_type"])
        schema = self.registry.event(source_id, schema_version, event_type)
        epoch = _nullable_uuid4(root["condition_epoch_id"], "condition epoch")
        fields = root["fields"]
        if not isinstance(fields, dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in fields.items()):
            raise ValidationError("event fields must be a string map")
        typed = self.registry.typed_fields(schema, fields)
        self.registry.validate_event_size(root, schema)
        return RecordedEvent(
            sequence, source_id, schema_version, event_type, _time(root["observed_time"]),
            epoch, dict(fields), typed, canonicalize(root),
        )

    def _observation(self, value: Any) -> SourceObservation:
        root = exact_object(value, {
            "observation_sequence", "source_id", "schema_version", "resource_generation",
            "admission_kind", "producer_ordinal", "condition_epoch_id", "event_count",
            "first_event_sequence", "last_event_sequence", "coverage", "encoded_sha256",
        }, "source observation")
        count = _int(root["event_count"], "observation event count", minimum=0, maximum=4096)
        first = _nullable_long(root["first_event_sequence"], "first event sequence", minimum=1)
        last = _nullable_long(root["last_event_sequence"], "last event sequence", minimum=1)
        coverage = None if root["coverage"] is None else _coverage(root["coverage"])
        if count == 0:
            if first is not None or last is not None or coverage is None:
                raise ValidationError("zero-event coverage advance is malformed")
        elif first is None or last is None or last - first + 1 != count:
            raise ValidationError("observation event range is not contiguous")
        source_id = _source_id(root["source_id"])
        schema_version = _int(root["schema_version"], "observation schema", minimum=1)
        if self.registry.source(source_id, schema_version)["source_kind"] != "COLLECTOR":
            raise ValidationError("source observation must belong to a collector")
        return SourceObservation(
            _long(root["observation_sequence"], "observation sequence", minimum=1),
            source_id,
            schema_version,
            _long(root["resource_generation"], "resource generation", minimum=1),
            _enum(root["admission_kind"], {"NORMAL", "BARRIER_FLUSH"}, "admission kind"),
            _long(root["producer_ordinal"], "producer ordinal", minimum=0),
            _uuid4(root["condition_epoch_id"], "observation condition epoch"),
            count, first, last, coverage,
            _digest(root["encoded_sha256"], "observation digest"),
        )

    def _mutation(self, value: Any) -> RuntimeMutation:
        root = exact_object(value, {"component_kind", "component_id", "operation", "canonical_value"}, "runtime mutation")
        kind = _enum(root["component_kind"], _COMPONENT_KINDS, "component kind")
        component_id = _string(root["component_id"], "component id")
        if not _COMPONENT_ID.fullmatch(component_id):
            raise ValidationError("invalid runtime component ID")
        operation = _enum(root["operation"], {"UPSERT", "REMOVE"}, "mutation operation")
        canonical_value = root["canonical_value"]
        if operation == "UPSERT":
            if not isinstance(canonical_value, str) or not canonical_value or len(canonical_value.encode()) > 512 * 1024:
                raise ValidationError("upsert requires a bounded canonical value")
        elif canonical_value is not None:
            raise ValidationError("remove mutation cannot carry a value")
        return RuntimeMutation(kind, component_id, operation, canonical_value)

    def _projection(self, value: Any) -> Mapping[str, Any]:
        root = exact_object(value, {
            "state", "revision", "next_commit_sequence", "next_observation_sequence",
            "next_event_sequence", "source_checkpoints", "clock_checkpoint",
            "active_condition_epoch", "lifetime_data_event_count", "uploaded_through_commit",
            "evaluated_through_commit", "retained_from_commit",
        }, "runtime projection")
        decoded: dict[str, Any] = {
            "state": _enum(root["state"], _LIFECYCLE_STATES, "runtime state"),
            "revision": _long(root["revision"], "projection revision", minimum=0),
            "next_commit_sequence": _long(root["next_commit_sequence"], "next commit", minimum=1),
            "next_observation_sequence": _long(root["next_observation_sequence"], "next observation", minimum=1),
            "next_event_sequence": _long(root["next_event_sequence"], "next event", minimum=1),
            "source_checkpoints": self._source_checkpoints(root["source_checkpoints"]),
            "clock_checkpoint": None if root["clock_checkpoint"] is None else _clock(root["clock_checkpoint"]),
            "active_condition_epoch": None if root["active_condition_epoch"] is None else _epoch(root["active_condition_epoch"]),
            "lifetime_data_event_count": _long(root["lifetime_data_event_count"], "lifetime event count", minimum=0),
            "uploaded_through_commit": _long(root["uploaded_through_commit"], "upload watermark", minimum=0),
            "evaluated_through_commit": _long(root["evaluated_through_commit"], "evaluation watermark", minimum=0),
            "retained_from_commit": _long(root["retained_from_commit"], "retained floor", minimum=1),
        }
        if decoded["next_commit_sequence"] != decoded["revision"] + 1:
            raise ValidationError("projection next commit is inconsistent")
        if not 0 <= decoded["uploaded_through_commit"] <= decoded["revision"]:
            raise ValidationError("invalid upload watermark")
        if not 0 <= decoded["evaluated_through_commit"] <= decoded["revision"]:
            raise ValidationError("invalid evaluation watermark")
        if decoded["retained_from_commit"] > min(decoded["uploaded_through_commit"], decoded["evaluated_through_commit"]) + 1:
            raise ValidationError("retained floor exceeds safe reclaim watermark")
        return decoded

    def _source_checkpoints(self, value: Any) -> Mapping[str, Mapping[str, Any]]:
        if not isinstance(value, dict) or len(value) > 64:
            raise ValidationError("source checkpoints must be an object")
        result = {}
        for source_id in sorted(value):
            _source_id(source_id)
            if self.registry.source(source_id)["source_kind"] != "COLLECTOR":
                raise ValidationError("source checkpoint belongs to a non-collector source")
            root = exact_object(value[source_id], {"source_id", "resource_generation", "next_producer_ordinal", "coverage", "cursor"}, "source checkpoint")
            if root["source_id"] != source_id:
                raise ValidationError("source checkpoint key mismatch")
            cursor = root["cursor"]
            if cursor is not None and (not isinstance(cursor, str) or len(cursor) > 4096):
                raise ValidationError("invalid source cursor")
            result[source_id] = {
                "source_id": source_id,
                "resource_generation": _long(root["resource_generation"], "checkpoint generation", minimum=0),
                "next_producer_ordinal": _long(root["next_producer_ordinal"], "next producer ordinal", minimum=0),
                "coverage": None if root["coverage"] is None else _coverage(root["coverage"]),
                "cursor": cursor,
            }
        return result

    def _validate_local(self, commit: EngineCommit) -> None:
        if commit.successor_projection["revision"] != commit.commit_sequence:
            raise ValidationError("successor projection revision does not equal commit")
        if commit.successor_projection["next_commit_sequence"] != commit.commit_sequence + 1:
            raise ValidationError("successor projection did not advance")
        if any(right.observation_sequence != left.observation_sequence + 1 for left, right in _pairwise(commit.source_observations)):
            raise ValidationError("observation sequences are not contiguous")
        if any(right.sequence_number != left.sequence_number + 1 for left, right in _pairwise(commit.events)):
            raise ValidationError("commit events are not contiguous")
        if len({event.sequence_number for event in commit.events}) != len(commit.events):
            raise ValidationError("commit contains duplicate event sequences")
        if len({item.observation_sequence for item in commit.source_observations}) != len(
            commit.source_observations
        ):
            raise ValidationError("commit contains duplicate observation sequences")
        mutation_keys = [(item.component_kind, item.component_id) for item in commit.mutations]
        if len(mutation_keys) != len(set(mutation_keys)):
            raise ValidationError("commit mutates one component twice")
        if mutation_keys != sorted(mutation_keys, key=lambda item: (_COMPONENT_ORDER[item[0]], item[1])):
            raise ValidationError("runtime mutations are not canonically ordered")


class EngineReplayVerifier:
    """Stateful fail-closed verification over one participant's complete commit chain."""

    def __init__(self, registry: EventSourceRegistry, configuration: Mapping[str, Any], configuration_sha256: str):
        self.registry = registry
        self.configuration = configuration
        self.configuration_sha256 = configuration_sha256
        # Bundle verification authenticates the signed configuration digest before replay.
        # Compile its closed-world semantics independently, then bind deterministic reducer IDs
        # to that authenticated envelope digest (which need not be a digest of a test projection).
        self.automation_program = replace(
            compile_automation_program(dict(configuration), registry=registry),
            configuration_sha256=configuration_sha256,
        )
        self.authoritative_checkpoint = AutomationCheckpoint()
        self.components: dict[tuple[str, str], str] = {}
        self.active_epoch: ConditionEpoch | None = None
        self.closed_epochs: dict[str, tuple[ConditionEpoch, ResearchTime]] = {}
        self.known_epochs: dict[str, ConditionEpoch] = {}
        self.automation_ids = {item["id"] for item in configuration["automations"]}
        self.occurrences = {item["id"]: item for item in configuration["automations"] if item["type"] == "occurrence"}
        self.intervention_ids = {item["id"] for item in configuration["interventions"]}
        self.intervention_action_types = {
            item["id"]: item["action"]["type"]
            for item in configuration["interventions"]
        }
        self.match_audits: set[tuple[str, int, int, str]] = set()
        self.requested_actions: dict[str, tuple[str, str, str]] = {}
        self.timers: dict[str, tuple[str, int]] = {}
        self.checkpoint: Mapping[str, Any] | None = None
        self.previous_projection: Mapping[str, Any] | None = None
        self.previous_commit_sha256: str | None = None
        self.expected_commit_sequence: int | None = None
        self.expected_event_sequence: int | None = None
        self.expected_observation_sequence: int | None = None
        self.observation_epoch_by_event: dict[int, str] = {}
        self.observations_by_epoch: dict[str, list[SourceObservation]] = {}
        self.current_checkpoint_digest: str | None = None
        self.pending_components: Mapping[tuple[str, str], str] | None = None
        self.pending_checkpoint: Mapping[str, Any] | None = None
        self.current_due_timers: dict[str, tuple[str, str, str]] = {}
        self.profile_digests, self.required_resources = _signed_resource_profiles(
            configuration
        )
        self.resource_binding_owners = {
            (item["resource"]["kind"], item["resource"]["id"]): item["id"]
            for item in configuration["automations"]
            if item["type"] == "resource_binding"
        }
        self.traffic_audits: dict[str, TrafficAuditState] = {}

    def replay(self, commits: Iterable[EngineCommit]) -> tuple[RecordedEvent, ...]:
        output: list[RecordedEvent] = []
        for commit in commits:
            output.extend(self.accept(commit))
        if self.active_epoch is not None:
            projection = self.previous_projection
            if projection is None or projection["state"] != "RUNNING":
                raise ValidationError("condition epoch remains open outside RUNNING")
        projection = self.previous_projection
        if (
            projection is not None
            and projection["state"] == "RUNNING"
            and self.active_epoch is None
        ):
            raise ValidationError("running study ended replay without a condition epoch")
        if projection is not None and projection["state"] in {
            "PAUSED",
            "COMPLETED",
            "WITHDRAWN",
        }:
            if any(kind == "RESOURCE_CLEANUP" for kind, _ in self.components):
                raise ValidationError(
                    "resource cleanup remains unresolved at the end of replay"
                )
            terminal_resources = [
                _decode_resource_component(value)
                for (kind, _), value in self.components.items()
                if kind == "RESOURCE"
            ]
            if (
                {item.key for item in terminal_resources}
                != set(self.profile_digests)
                or any(item.status != "INACTIVE" for item in terminal_resources)
            ):
                raise ValidationError(
                    "closed study resource vector is not fully inactive"
                )
        self._verify_closed_observation_coverage()
        finalized = []
        for event in output:
            source_epoch = self._source_epoch(event)
            if (
                self.registry.source(event.source_id, event.schema_version)["source_kind"] == "COLLECTOR"
                and source_epoch != event.condition_epoch_id
            ):
                raise ValidationError("source interval crosses or disagrees with condition epoch")
            finalized.append(
                RecordedEvent(
                    event.sequence_number,
                    event.source_id,
                    event.schema_version,
                    event.event_type,
                    event.observed_time,
                    event.condition_epoch_id,
                    event.wire_fields,
                    event.typed_fields,
                    event.canonical_bytes,
                    source_epoch,
                )
            )
        return tuple(finalized)

    def accept(self, commit: EngineCommit) -> tuple[RecordedEvent, ...]:
        if self.expected_commit_sequence is None:
            if commit.commit_sequence != 1 or commit.previous_commit_sha256 != GENESIS_DIGEST:
                raise ValidationError("complete engine replay must begin at genesis")
            self.expected_commit_sequence = 1
        if commit.commit_sequence != self.expected_commit_sequence:
            raise ValidationError("commit chain has a sequence gap")
        if self.previous_commit_sha256 is not None and commit.previous_commit_sha256 != self.previous_commit_sha256:
            raise ValidationError("commit chain digest linkage diverged")
        if self.expected_event_sequence is not None and commit.events and commit.events[0].sequence_number != self.expected_event_sequence:
            raise ValidationError("event sequence has a gap across commits")
        if self.expected_observation_sequence is not None and commit.source_observations and commit.source_observations[0].observation_sequence != self.expected_observation_sequence:
            raise ValidationError("observation sequence has a gap across commits")
        prospective = dict(self.components)
        for mutation in commit.mutations:
            key = (mutation.component_kind, mutation.component_id)
            if mutation.operation == "UPSERT":
                prospective[key] = mutation.canonical_value or ""
            else:
                if key not in prospective:
                    raise ValidationError("runtime mutation removes an unknown component")
                prospective.pop(key)
        checkpoint = self._verify_checkpoint(commit, prospective)
        authoritative = self._verify_authoritative_reduction(commit, prospective)
        self.current_checkpoint_digest = commit.resulting_checkpoint_sha256
        self.pending_components = prospective
        self.pending_checkpoint = checkpoint
        self.current_due_timers = {}
        try:
            self._verify_observations(commit)
            derived_events = tuple(self._accept_event(event) for event in commit.events)
            self._verify_projection(commit, checkpoint)
            self._verify_study_deadline_commit(commit, prospective)
            self._verify_resource_audit_commit(commit, prospective)
            self._verify_component_state(commit, prospective, checkpoint)
        finally:
            self.pending_components = None
            self.pending_checkpoint = None
            self.current_due_timers = {}
        self.components = prospective
        self.checkpoint = checkpoint
        self.authoritative_checkpoint = authoritative
        self.previous_projection = commit.successor_projection
        self.previous_commit_sha256 = commit.commit_sha256
        self.expected_commit_sequence = commit.commit_sequence + 1
        self.expected_event_sequence = commit.successor_projection["next_event_sequence"]
        self.expected_observation_sequence = commit.successor_projection["next_observation_sequence"]
        self.current_checkpoint_digest = None
        return derived_events

    def _verify_observations(self, commit: EngineCommit) -> None:
        self._verify_observation_event_order(commit)
        covered: set[int] = set()
        event_by_sequence = {event.sequence_number: event for event in commit.events}
        expected_checkpoints = {
            key: dict(value)
            for key, value in (
                self.previous_projection["source_checkpoints"].items()
                if self.previous_projection is not None
                else ()
            )
        }
        gap_reasons = {
            event.wire_fields["reason"]
            for event in commit.events
            if event.source_id == "study_runtime.v1"
            and event.event_type == "SOURCE_QUALITY_GAP"
        }
        discard_retrospective = bool(
            gap_reasons & {"WALL_CLOCK_CHANGED", "PROCESS_RECOVERY"}
        )
        process_recovery_gap = "PROCESS_RECOVERY" in gap_reasons
        if process_recovery_gap and getattr(commit, "input_kind", None) != "RECOVERY":
            raise ValidationError("process-recovery quality gap requires RECOVERY input")
        allow_staged_recovery_observations = (
            process_recovery_gap
            and getattr(commit, "consumed_pending_input_sha256", None) is not None
        )
        for observation in commit.source_observations:
            if (
                discard_retrospective
                and observation.source_id in self.registry.retrospective_collector_source_ids
                and not allow_staged_recovery_observations
            ):
                raise ValidationError(
                    "clock-gap commit cannot backfill a retrospective source"
                )
            if self.active_epoch is None or observation.condition_epoch_id != self.active_epoch.id:
                raise ValidationError("source observation is outside its active condition epoch")
            prior = expected_checkpoints.get(observation.source_id)
            expected_ordinal = (
                0
                if prior is None
                or prior["resource_generation"] != observation.resource_generation
                else prior["next_producer_ordinal"]
            )
            if observation.producer_ordinal != expected_ordinal:
                raise ValidationError("source producer ordinal is not contiguous")
            if (
                prior is not None
                and prior["resource_generation"] == observation.resource_generation
                and prior["coverage"] is not None
                and observation.coverage is not None
                and (
                    prior["coverage"].clock_basis != observation.coverage.clock_basis
                    or prior["coverage"].end_exclusive
                    != observation.coverage.start_inclusive
                )
            ):
                raise ValidationError("retrospective source coverage has a gap or overlap")
            events = []
            if observation.event_count:
                assert observation.first_event_sequence is not None
                assert observation.last_event_sequence is not None
                for sequence in range(observation.first_event_sequence, observation.last_event_sequence + 1):
                    event = event_by_sequence.get(sequence)
                    if event is None or sequence in covered:
                        raise ValidationError("observation references a partial or duplicate event range")
                    if (event.source_id, event.schema_version) != (observation.source_id, observation.schema_version):
                        raise ValidationError("observation event source contract mismatch")
                    if event.condition_epoch_id != observation.condition_epoch_id:
                        raise ValidationError("observation event condition epoch mismatch")
                    covered.add(sequence)
                    events.append(event)
                    self.observation_epoch_by_event[sequence] = observation.condition_epoch_id
            if _observation_digest(observation, events) != observation.encoded_sha256:
                raise ValidationError("source observation digest mismatch")
            expected_checkpoints[observation.source_id] = {
                "source_id": observation.source_id,
                "resource_generation": observation.resource_generation,
                "next_producer_ordinal": observation.producer_ordinal + 1,
                "coverage": observation.coverage
                if observation.coverage is not None
                else (prior["coverage"] if prior is not None else None),
                "cursor": prior["cursor"] if prior is not None else None,
            }
            self.observations_by_epoch.setdefault(
                observation.condition_epoch_id, []
            ).append(observation)
        if discard_retrospective:
            expected_checkpoints = {
                source_id: checkpoint
                for source_id, checkpoint in expected_checkpoints.items()
                if source_id not in self.registry.retrospective_collector_source_ids
            }
        for event in commit.events:
            if self.registry.source(event.source_id, event.schema_version)["source_kind"] == "COLLECTOR" and event.sequence_number not in covered:
                raise ValidationError("collector event is not covered by a source observation")
        if commit.successor_projection["source_checkpoints"] != expected_checkpoints:
            raise ValidationError(
                "successor source checkpoints diverge from observation provenance"
            )

    @staticmethod
    def _verify_observation_event_order(commit: EngineCommit) -> None:
        """Restrict manifest/event divergence to the exact resource-barrier rotation."""
        observations = list(commit.source_observations)
        eventful = [observation for observation in observations if observation.event_count]
        if len(eventful) <= 1:
            return
        semantic_order = sorted(
            eventful,
            key=lambda observation: int(observation.first_event_sequence),
        )
        for left, right in pairwise(semantic_order):
            assert left.last_event_sequence is not None
            assert right.first_event_sequence is not None
            if left.last_event_sequence + 1 != right.first_event_sequence:
                raise ValidationError("source observation event ranges are not contiguous")
        if semantic_order == eventful:
            return
        if getattr(commit, "consumed_pending_input_sha256", None) is None:
            raise ValidationError(
                "source observation event ranges diverge outside a pending-consuming barrier"
            )
        causal = observations[0]
        if causal.admission_kind != "NORMAL" or causal.event_count == 0:
            raise ValidationError(
                "pending-consuming barrier does not start with an eventful causal observation"
            )
        flush_started = False
        for observation in observations[1:]:
            if observation.admission_kind == "NORMAL":
                if flush_started:
                    raise ValidationError(
                        "normal pre-drain observation follows a boundary flush"
                    )
            else:
                flush_started = True
        expected_semantic_order = [
            observation for observation in observations[1:] if observation.event_count
        ] + [causal]
        if semantic_order != expected_semantic_order:
            raise ValidationError(
                "pending-consuming barrier event ranges are not pre-drain/flush then causal"
            )

    def _accept_event(self, event: RecordedEvent) -> RecordedEvent:
        source_kind = self.registry.source(event.source_id, event.schema_version)["source_kind"]
        if source_kind == "COLLECTOR":
            if event.condition_epoch_id is None:
                raise ValidationError("collector data event is missing condition epoch")
            epoch = self.known_epochs.get(event.condition_epoch_id)
            if epoch is None and (self.active_epoch is None or self.active_epoch.id != event.condition_epoch_id):
                raise ValidationError("collector data event references an orphan epoch")
            if self.active_epoch is not None and event.condition_epoch_id != self.active_epoch.id:
                raise ValidationError("collector event is admitted outside its active epoch")
        if event.source_id == "study_condition.v1":
            self._condition_event(event)
        elif event.source_id == "automation_runtime.v1":
            self._automation_event(event)
        elif event.source_id == "timer.v1":
            self._timer_event(event)
        elif event.source_id == "traffic_shaping.v1":
            self._traffic_event(event)
        source_epoch = self._source_epoch(event)
        if source_kind == "COLLECTOR" and source_epoch != event.condition_epoch_id:
            raise ValidationError("source interval crosses or disagrees with condition epoch")
        return RecordedEvent(
            event.sequence_number, event.source_id, event.schema_version, event.event_type,
            event.observed_time, event.condition_epoch_id, event.wire_fields, event.typed_fields,
            event.canonical_bytes, source_epoch,
        )

    def _condition_event(self, event: RecordedEvent) -> None:
        fields = event.wire_fields
        epoch_id = _uuid4(fields["condition_epoch_id"], "condition audit epoch")
        configuration = _digest(fields["signed_configuration_sha256"], "condition configuration")
        vector = _digest(fields["applied_resource_vector_sha256"], "resource vector")
        if configuration != self.configuration_sha256:
            raise ValidationError("condition epoch configuration digest divergence")
        vector_json = parse_embedded_json(fields["resource_vector_json"])
        vector_bytes = canonicalize(vector_json)
        if vector_bytes.decode() != fields["resource_vector_json"]:
            raise ValidationError("resource vector JSON is not canonical")
        if hashlib.sha256(vector_bytes).hexdigest() != vector:
            raise ValidationError("applied resource vector digest divergence")
        resources = _resource_vector(vector_json)
        boundary = _embedded_time(fields["boundary_research_time"])
        if event.event_type == "CONDITION_EPOCH_ACTIVATED":
            if self.active_epoch is not None or epoch_id in self.known_epochs:
                raise ValidationError("condition epochs overlap or reuse an ID")
            if event.condition_epoch_id != epoch_id:
                raise ValidationError("activation event envelope has the wrong epoch")
            self._verify_applied_vector(resources, prospective=True)
            epoch = ConditionEpoch(epoch_id, configuration, vector, boundary)
            self.active_epoch = epoch
            self.known_epochs[epoch_id] = epoch
        else:
            if self.active_epoch is None or self.active_epoch.id != epoch_id:
                raise ValidationError("condition epoch deactivation is orphaned or out of order")
            if self.active_epoch.applied_resource_vector_sha256 != vector:
                raise ValidationError("condition epoch deactivation vector mismatch")
            if event.condition_epoch_id != epoch_id:
                raise ValidationError("deactivation event envelope has the wrong epoch")
            traffic_resource = next(
                (
                    item
                    for item in resources
                    if item.key == ("actuator", "traffic-shaping.v1")
                    and item.status == "APPLIED"
                ),
                None,
            )
            if traffic_resource is not None:
                audit = self.traffic_audits.get(epoch_id)
                if audit is None or not audit.removed:
                    raise ValidationError(
                        "traffic profile was not removed before condition deactivation"
                    )
            self._verify_applied_vector(resources, prospective=False)
            if boundary.boot_session_id != self.active_epoch.activated_at.boot_session_id:
                raise ValidationError("condition epoch cannot span a reboot")
            if _time_order(boundary, self.active_epoch.activated_at) < 0:
                raise ValidationError("condition epoch ends before it starts")
            self.closed_epochs[epoch_id] = (self.active_epoch, boundary)
            self.active_epoch = None

    def _verify_applied_vector(
        self, resources: tuple[AppliedResource, ...], *, prospective: bool
    ) -> None:
        components = self.pending_components if prospective else self.components
        if components is None:
            raise ValidationError("resource vector was evaluated outside a commit")
        durable = {
            item.key: item
            for (kind, _), value in components.items()
            if kind == "RESOURCE"
            for item in (_decode_resource_component(value),)
        }
        vector = {item.key: item for item in resources}
        if vector != durable:
            raise ValidationError(
                "condition resource vector differs from durable resource receipts"
            )
        if set(vector) != set(self.profile_digests):
            raise ValidationError("condition resource vector is incomplete")
        checkpoint = self.pending_checkpoint if prospective else self.checkpoint
        for key, item in vector.items():
            profiles = self.profile_digests[key]
            if item.status == "APPLIED":
                if (
                    item.profile_id not in profiles
                    or item.applied_profile_sha256 != profiles[item.profile_id]
                ):
                    raise ValidationError("applied resource profile digest is not signed")
            elif item.status == "OPTIONAL_FAILED":
                if key in self.required_resources or item.profile_id not in profiles:
                    raise ValidationError("invalid optional resource failure")
            elif key in self.required_resources:
                raise ValidationError("required resource is inactive in a condition epoch")
            if checkpoint is not None:
                desired = checkpoint["desired_resources"].get(
                    (key[0].upper(), key[1])
                )
                if desired != (str(item.desired_generation), item.profile_id):
                    raise ValidationError(
                        "applied resource vector differs from reducer desired state"
                    )

    def _automation_event(self, event: RecordedEvent) -> None:
        fields = event.wire_fields
        automation_id = fields["automation_id"]
        if automation_id not in self.automation_ids:
            raise ValidationError("automation audit references unknown automation")
        first = int(fields["causal_first_sequence"])
        last = int(fields["causal_final_sequence"])
        if not 0 < first <= last < event.sequence_number:
            raise ValidationError("automation audit has invalid causal range")
        condition = _digest(fields["condition_sha256"], "automation condition digest")
        if event.event_type in {"AUTOMATION_MATCHED", "AUTOMATION_SUPPRESSED", "ACTION_REQUESTED"} and condition != self.current_checkpoint_digest:
            raise ValidationError("automation audit checkpoint digest divergence")
        if event.event_type in {"AUTOMATION_MATCHED", "AUTOMATION_SUPPRESSED"}:
            self.match_audits.add((automation_id, first, last, condition))
            return
        intervention_id = fields["intervention_id"]
        if intervention_id not in self.intervention_ids:
            raise ValidationError("action references unknown intervention")
        invocation_id = _digest(fields["invocation_id"], "action invocation ID")
        if event.event_type == "ACTION_REQUESTED":
            if (automation_id, first, last, condition) not in self.match_audits:
                raise ValidationError("action request has no causal automation match")
            prior = self.requested_actions.get(invocation_id)
            identity = (automation_id, intervention_id, condition)
            if prior is not None and prior != identity:
                raise ValidationError("action invocation ID was reused")
            self.requested_actions[invocation_id] = identity
            automation = self.occurrences.get(automation_id)
            if automation is None:
                raise ValidationError("action request references non-occurrence automation")
            trigger_kind = automation["trigger"]["type"]
            if trigger_kind == "event_match":
                expected = _deterministic_digest(
                    "particeps-action-v1",
                    self.configuration_sha256,
                    automation_id,
                    intervention_id,
                    "event_match",
                    f"event:{last}",
                    "",
                )
                if invocation_id != expected:
                    raise ValidationError("action deterministic identity mismatch")
            elif trigger_kind == "schedule":
                due = self.current_due_timers.get(automation_id)
                if due is None:
                    raise ValidationError("scheduled action has no causal timer")
                timer_id, clock, logical_wall = due
                expected = _deterministic_digest(
                    "particeps-action-v1",
                    self.configuration_sha256,
                    automation_id,
                    intervention_id,
                    "schedule",
                    f"timer:{timer_id}",
                    logical_wall if clock == "CALENDAR_TIME" else "",
                )
                if invocation_id != expected:
                    raise ValidationError("scheduled action deterministic identity mismatch")
        elif self.requested_actions.get(invocation_id) != (automation_id, intervention_id, condition):
            raise ValidationError("action result has no matching durable request")

    def _timer_event(self, event: RecordedEvent) -> None:
        fields = event.wire_fields
        if fields["producer_key"] == "study-deadline":
            if fields["automation_id"] != "study-duration":
                raise ValidationError("study deadline timer owner mismatch")
            if event.condition_epoch_id is not None:
                raise ValidationError("study deadline timer must not be epoch-scoped")
            return
        automation_id = fields["automation_id"]
        if automation_id not in self.automation_ids:
            raise ValidationError("timer references unknown automation")
        if fields["producer_key"].startswith("resource-audit:"):
            if event.condition_epoch_id is None:
                raise ValidationError("resource audit timer is missing its condition epoch")
            return
        timer_id = _digest(fields["timer_id"], "timer ID")
        expected = _deterministic_digest(
            "particeps-timer-v1", self.configuration_sha256,
            automation_id, fields["producer_key"],
        )
        if timer_id != expected:
            raise ValidationError("timer deterministic identity mismatch")
        generation = int(fields["generation"])
        if event.event_type == "TIMER_SCHEDULED":
            prior = self.timers.get(timer_id)
            if prior is not None and generation <= prior[1]:
                raise ValidationError("timer generation did not advance")
            self.timers[timer_id] = (automation_id, generation)
        else:
            if self.timers.get(timer_id) != (automation_id, generation):
                raise ValidationError("timer due/retired event is stale or orphaned")
            if event.event_type == "TIMER_DUE":
                logical = _embedded_time(fields["logical_due_research_time"])
                self.current_due_timers[automation_id] = (
                    timer_id,
                    fields["clock"],
                    str(logical.wall_time_utc_millis),
                )
            if event.event_type == "TIMER_RETIRED":
                self.timers.pop(timer_id)

    def _traffic_event(self, event: RecordedEvent) -> None:
        fields = event.wire_fields
        field_epoch = _uuid4(fields["condition_epoch_id"], "traffic condition epoch")
        if (
            self.active_epoch is None
            or self.active_epoch.id != field_epoch
            or event.condition_epoch_id != field_epoch
        ):
            raise ValidationError("traffic audit is outside its active condition epoch")

        components = (
            self.pending_components
            if event.event_type == "TRAFFIC_SHAPING_PROFILE_APPLIED"
            else self.components
        )
        if components is None:
            raise ValidationError("traffic audit was evaluated outside a commit")
        encoded = components.get(("RESOURCE", "actuator:traffic-shaping.v1"))
        if encoded is None:
            raise ValidationError("traffic audit has no durable resource evidence")
        resource = _decode_resource_component(encoded)
        if resource.status != "APPLIED":
            raise ValidationError("traffic audit resource is not applied")
        if (
            fields["profile_id"] != resource.profile_id
            or int(fields["resource_generation"]) != resource.desired_generation
        ):
            raise ValidationError("traffic audit resource evidence mismatch")

        if event.event_type == "TRAFFIC_SHAPING_PROFILE_APPLIED":
            if fields["applied_profile_sha256"] != resource.applied_profile_sha256:
                raise ValidationError("traffic applied-profile digest mismatch")
            if fields["signed_configuration_sha256"] != self.configuration_sha256:
                raise ValidationError("traffic signed-configuration digest mismatch")
            traffic = self.configuration["traffic_shaping"]
            if not traffic:
                raise ValidationError("traffic audit appears in a non-shaping study")
            target_digest = hashlib.sha256(
                canonicalize(traffic["target_packages"])
            ).hexdigest()
            if fields["target_package_list_sha256"] != target_digest:
                raise ValidationError("traffic target-package digest mismatch")
            profile = next(
                (item for item in traffic["profiles"] if item["id"] == resource.profile_id),
                None,
            )
            if profile is None:
                raise ValidationError("traffic audit profile is not signed")
            for direction in ("uplink_kbps", "downlink_kbps"):
                expected = profile[direction]
                actual = fields.get(direction)
                if actual != (None if expected is None else str(expected)):
                    raise ValidationError("traffic applied cap differs from signed profile")
            if _embedded_time(fields["activation_research_time"]) != self.active_epoch.activated_at:
                raise ValidationError("traffic activation time differs from condition epoch")
            if _embedded_time(fields["verification_completed_research_time"]) != event.observed_time:
                raise ValidationError("traffic verification time differs from observation")
            if field_epoch in self.traffic_audits:
                raise ValidationError("traffic profile was applied twice in one epoch")
            self.traffic_audits[field_epoch] = TrafficAuditState(
                resource=resource,
                vpn_generation_id=fields["vpn_generation_id"],
            )
            return

        audit = self.traffic_audits.get(field_epoch)
        if audit is None or audit.removed:
            raise ValidationError("traffic counter audit has no active applied profile")
        if audit.resource != resource or fields["vpn_generation_id"] != audit.vpn_generation_id:
            raise ValidationError("traffic counter evidence changed within an epoch")
        counters = tuple(
            int(fields[name])
            for name in (
                "uplink_bytes",
                "uplink_packets",
                "uplink_throttled_nanoseconds",
                "downlink_bytes",
                "downlink_packets",
                "downlink_throttled_nanoseconds",
            )
        )
        if audit.last_counters is not None and any(
            current < prior for current, prior in zip(counters, audit.last_counters, strict=True)
        ):
            raise ValidationError("traffic counters moved backwards within an epoch")

        if event.event_type == "TRAFFIC_SHAPING_SNAPSHOT":
            if _embedded_time(fields["observation_research_time"]) != event.observed_time:
                raise ValidationError("traffic snapshot observation time mismatch")
            logical = _embedded_time(fields["logical_deadline_research_time"])
            if logical.boot_session_id != event.observed_time.boot_session_id:
                raise ValidationError("traffic snapshot deadline crosses a reboot")
            audit.last_counters = counters
            audit.last_snapshot_reason = fields["snapshot_reason"]
            return

        if event.event_type != "TRAFFIC_SHAPING_PROFILE_REMOVED":
            raise ValidationError("unknown traffic audit event")
        if (
            audit.last_snapshot_reason != "EPOCH_BOUNDARY"
            or audit.last_counters != counters
        ):
            raise ValidationError(
                "traffic removal is not paired with its exact boundary snapshot"
            )
        if _embedded_time(fields["boundary_research_time"]) != event.observed_time:
            raise ValidationError("traffic removal boundary time mismatch")
        audit.removed = True

    def _verify_study_deadline_commit(
        self,
        commit: EngineCommit,
        prospective: Mapping[tuple[str, str], str],
    ) -> None:
        previous_components = {
            component_id: value
            for (kind, component_id), value in self.components.items()
            if kind == "STUDY_DEADLINE_TIMER"
        }
        current_components = {
            component_id: value
            for (kind, component_id), value in prospective.items()
            if kind == "STUDY_DEADLINE_TIMER"
        }
        if set(previous_components) - {"study-duration"} or set(current_components) - {"study-duration"}:
            raise ValidationError("study deadline component key mismatch")
        previous = {
            key: _decode_timer_component(value)
            for key, value in previous_components.items()
        }
        current = {
            key: _decode_timer_component(value)
            for key, value in current_components.items()
        }

        expected_id = _deterministic_digest(
            "particeps-study-deadline-timer-v1",
            self.configuration_sha256,
            "study-duration",
            "study-deadline",
        )

        def validate(timer: tuple[Any, ...], *, against_projection: bool) -> None:
            if (
                timer[0] != expected_id
                or timer[1] != "study-duration"
                or timer[4] != "study-deadline"
                or timer[5][0] != "monotonic"
                or timer[6] is None
                or timer[7] is not None
            ):
                raise ValidationError("study deadline timer contract mismatch")
            if not against_projection:
                return
            clock = commit.successor_projection["clock_checkpoint"]
            if clock is None:
                raise ValidationError("study deadline has no authenticated study clock")
            duration_nanos = int(self.configuration["duration_hours"]) * 3_600_000_000_000
            remaining = max(0, duration_nanos - clock["calendar_elapsed_nanos"])
            if remaining == 0:
                return
            target = timer[5]
            if (
                target[1] != clock["anchor"].boot_session_id
                or target[2] != clock["anchor"].elapsed_realtime_nanos + remaining
                or timer[6] != clock["deadline_utc_millis"]
            ):
                raise ValidationError("study deadline timer diverges from the signed duration")

        prior_timer = previous.get("study-duration")
        current_timer = current.get("study-duration")
        if prior_timer is not None:
            validate(prior_timer, against_projection=False)
        if current_timer is not None:
            validate(current_timer, against_projection=True)

        state = commit.successor_projection["state"]
        clock = commit.successor_projection["clock_checkpoint"]
        duration_nanos = int(self.configuration["duration_hours"]) * 3_600_000_000_000
        remaining = None if clock is None else max(
            0,
            duration_nanos - clock["calendar_elapsed_nanos"],
        )
        if state in {"ACTIVATING", "RUNNING", "PAUSING", "PAUSED"} and remaining != 0:
            if current_timer is None:
                raise ValidationError("started study is missing its durable deadline")
        elif state in {"COMPLETED", "WITHDRAWN"} and current_timer is not None:
            raise ValidationError("terminal study retained its deadline timer")
        elif clock is None and current_timer is not None:
            raise ValidationError("unstarted study retained a deadline timer")

        relevant = [
            event
            for event in commit.events
            if event.source_id == "timer.v1"
            and event.wire_fields["producer_key"] == "study-deadline"
        ]
        changed = prior_timer != current_timer
        if not changed and relevant:
            raise ValidationError("stable study deadline emitted a timer transition")
        expected_events: list[tuple[str, tuple[str, str, str | None, str, str, str, str]]] = []
        if prior_timer is not None and changed:
            if any(event.event_type == "TIMER_DUE" for event in relevant):
                expected_events.append((
                    "TIMER_DUE",
                    _timer_output_evidence(_automation_timer(prior_timer), include_cause=True),
                ))
            expected_events.append((
                "TIMER_RETIRED",
                _timer_output_evidence(_automation_timer(prior_timer), include_cause=False),
            ))
        if current_timer is not None and changed:
            expected_events.append((
                "TIMER_SCHEDULED",
                _timer_output_evidence(_automation_timer(current_timer), include_cause=True),
            ))
        actual_events = [
            (event.event_type, _timer_event_evidence(event)) for event in relevant
        ]
        if actual_events != expected_events:
            raise ValidationError("study deadline timer events diverge from durable state")
        for event in relevant:
            if (
                event.event_type == "TIMER_RETIRED"
                and event.wire_fields["retirement_reason"]
                != _expected_timer_retirement_reason(commit)
            ):
                raise ValidationError("study deadline retirement reason mismatch")

    def _verify_resource_audit_commit(
        self,
        commit: EngineCommit,
        prospective: Mapping[tuple[str, str], str],
    ) -> None:
        previous_timers = _component_timers(self.components, "RESOURCE_AUDIT_TIMER")
        current_timers = _component_timers(prospective, "RESOURCE_AUDIT_TIMER")
        previous_epoch = (
            None
            if self.previous_projection is None
            else self.previous_projection["active_condition_epoch"]
        )
        current_epoch = commit.successor_projection["active_condition_epoch"]
        for timer in previous_timers.values():
            self._verify_resource_audit_timer_component(
                timer, self.components, previous_epoch
            )
        for timer in current_timers.values():
            self._verify_resource_audit_timer_component(timer, prospective, current_epoch)

        relevant_timer_events = [
            event
            for event in commit.events
            if event.source_id == "timer.v1"
            and event.wire_fields["producer_key"].startswith("resource-audit:")
        ]
        added = set(current_timers) - set(previous_timers)
        removed = set(previous_timers) - set(current_timers)
        stable = set(previous_timers) & set(current_timers)
        if any(previous_timers[key] != current_timers[key] for key in stable):
            raise ValidationError("resource audit timer changed without a new identity")

        for timer_id in added:
            scheduled = self._timer_events_for(
                relevant_timer_events, "TIMER_SCHEDULED", timer_id
            )
            if len(scheduled) != 1:
                raise ValidationError("resource audit timer upsert lacks one schedule event")
            self._verify_resource_timer_event(scheduled[0], current_timers[timer_id], scheduled=True)
        for timer_id in removed:
            retired = self._timer_events_for(
                relevant_timer_events, "TIMER_RETIRED", timer_id
            )
            if len(retired) != 1:
                raise ValidationError("resource audit timer removal lacks one retirement event")
            self._verify_resource_timer_event(retired[0], previous_timers[timer_id])

        for event in relevant_timer_events:
            timer_id = event.wire_fields["timer_id"]
            if event.event_type == "TIMER_SCHEDULED":
                timer = current_timers.get(timer_id)
                if timer_id not in added or timer is None:
                    raise ValidationError("resource audit schedule has no durable timer upsert")
                self._verify_resource_timer_event(event, timer, scheduled=True)
            else:
                timer = previous_timers.get(timer_id)
                if timer is None:
                    raise ValidationError("resource audit due/retirement is stale or orphaned")
                self._verify_resource_timer_event(event, timer)

        events = list(commit.events)
        positions = {event.sequence_number: index for index, event in enumerate(events)}
        activation = [
            event
            for event in events
            if event.source_id == "study_condition.v1"
            and event.event_type == "CONDITION_EPOCH_ACTIVATED"
        ]
        applied = [
            event
            for event in events
            if event.source_id == "traffic_shaping.v1"
            and event.event_type == "TRAFFIC_SHAPING_PROFILE_APPLIED"
        ]
        for event in applied:
            schedules = [
                candidate
                for candidate in relevant_timer_events
                if candidate.event_type == "TIMER_SCHEDULED"
                and candidate.condition_epoch_id == event.condition_epoch_id
            ]
            starts = [
                candidate
                for candidate in activation
                if candidate.condition_epoch_id == event.condition_epoch_id
            ]
            if len(starts) != 1 or len(schedules) != 1 or not (
                positions[starts[0].sequence_number]
                < positions[event.sequence_number]
                < positions[schedules[0].sequence_number]
            ):
                raise ValidationError("traffic audit activation ordering is invalid")

        due_events = [
            event
            for event in relevant_timer_events
            if event.event_type == "TIMER_DUE"
        ]
        for due in due_events:
            old_timer = previous_timers[due.wire_fields["timer_id"]]
            producer = old_timer[4]
            periodic = [
                event
                for event in events
                if event.source_id == "traffic_shaping.v1"
                and event.event_type == "TRAFFIC_SHAPING_SNAPSHOT"
                and event.wire_fields["snapshot_reason"] == "PERIODIC"
                and event.condition_epoch_id == due.condition_epoch_id
            ]
            retired = [
                event
                for event in relevant_timer_events
                if event.event_type == "TIMER_RETIRED"
                and event.wire_fields["timer_id"] == old_timer[0]
                and event.wire_fields["retirement_reason"] == "FIRED"
            ]
            successor = [
                event
                for event in relevant_timer_events
                if event.event_type == "TIMER_SCHEDULED"
                and event.wire_fields["producer_key"] == producer
            ]
            if len(periodic) != 1 or len(retired) != 1 or len(successor) != 1:
                raise ValidationError("resource audit due cycle is incomplete")
            ordered = (due, periodic[0], retired[0], successor[0])
            if [positions[item.sequence_number] for item in ordered] != sorted(
                positions[item.sequence_number] for item in ordered
            ):
                raise ValidationError("resource audit due cycle ordering is invalid")
            logical_due = _embedded_time(due.wire_fields["logical_due_research_time"])
            snapshot_due = _embedded_time(
                periodic[0].wire_fields["logical_deadline_research_time"]
            )
            if logical_due != snapshot_due:
                raise ValidationError("periodic traffic snapshot has the wrong logical deadline")

        removed_profiles = [
            event
            for event in events
            if event.source_id == "traffic_shaping.v1"
            and event.event_type == "TRAFFIC_SHAPING_PROFILE_REMOVED"
        ]
        for removed_profile in removed_profiles:
            epoch_id = removed_profile.condition_epoch_id
            boundary = [
                event
                for event in events
                if event.source_id == "traffic_shaping.v1"
                and event.event_type == "TRAFFIC_SHAPING_SNAPSHOT"
                and event.wire_fields["snapshot_reason"] == "EPOCH_BOUNDARY"
                and event.condition_epoch_id == epoch_id
            ]
            retired = [
                event
                for event in relevant_timer_events
                if event.event_type == "TIMER_RETIRED"
                and event.condition_epoch_id == epoch_id
            ]
            ended = [
                event
                for event in events
                if event.source_id == "study_condition.v1"
                and event.event_type == "CONDITION_EPOCH_DEACTIVATED"
                and event.condition_epoch_id == epoch_id
            ]
            if len(boundary) != 1 or len(retired) != 1 or len(ended) != 1:
                raise ValidationError("traffic audit boundary cycle is incomplete")
            ordered = (boundary[0], removed_profile, retired[0], ended[0])
            if [positions[item.sequence_number] for item in ordered] != sorted(
                positions[item.sequence_number] for item in ordered
            ):
                raise ValidationError("traffic audit boundary ordering is invalid")

        if commit.input_kind == "RECOVERY" and any(
            event.event_type == "TIMER_SCHEDULED" for event in relevant_timer_events
        ):
            raise ValidationError("recovery must not revive a resource audit timer")

    @staticmethod
    def _timer_events_for(
        events: list[RecordedEvent], event_type: str, timer_id: str
    ) -> list[RecordedEvent]:
        return [
            event
            for event in events
            if event.event_type == event_type
            and event.wire_fields["timer_id"] == timer_id
        ]

    def _verify_resource_audit_timer_component(
        self,
        timer: tuple[Any, ...],
        components: Mapping[tuple[str, str], str],
        epoch: ConditionEpoch | None,
    ) -> None:
        if timer[4] != "resource-audit:actuator:traffic-shaping.v1":
            raise ValidationError("unknown resource audit timer producer")
        if epoch is None:
            raise ValidationError("resource audit timer exists without an active epoch")
        encoded = components.get(("RESOURCE", "actuator:traffic-shaping.v1"))
        if encoded is None:
            raise ValidationError("resource audit timer has no durable resource")
        resource = _decode_resource_component(encoded)
        if (
            resource.status != "APPLIED"
            or resource.profile_id is None
            or resource.applied_profile_sha256 is None
        ):
            raise ValidationError("resource audit timer is not bound to applied evidence")
        owner = self.resource_binding_owners.get(resource.key)
        if owner is None or timer[1] != owner:
            raise ValidationError("resource audit timer has the wrong binding owner")
        if timer[2] != str(resource.desired_generation):
            raise ValidationError("resource audit timer generation mismatch")
        target = timer[5]
        if target[0] != "monotonic" or target[1] != epoch.activated_at.boot_session_id:
            raise ValidationError("resource audit timer has an invalid clock domain")
        if timer[6] is None or timer[7] is not None:
            raise ValidationError("resource audit timer has invalid deadline semantics")
        expected = _deterministic_digest(
            "particeps-resource-audit-timer-v1",
            self.configuration_sha256,
            "traffic_shaping.v1",
            "ACTUATOR",
            "traffic-shaping.v1",
            timer[2],
            resource.profile_id,
            resource.applied_profile_sha256,
            epoch.id,
            str(timer[3]),
            target[1],
            str(target[2]),
        )
        if timer[0] != expected:
            raise ValidationError("resource audit timer deterministic identity mismatch")

    def _verify_resource_timer_event(
        self,
        event: RecordedEvent,
        timer: tuple[Any, ...],
        *,
        scheduled: bool = False,
    ) -> None:
        fields = event.wire_fields
        if (
            fields["timer_id"] != timer[0]
            or fields["automation_id"] != timer[1]
            or fields["generation"] != timer[2]
            or fields["producer_key"] != timer[4]
            or fields["clock"] != "SAME_BOOT_MONOTONIC"
        ):
            raise ValidationError("resource audit timer event evidence mismatch")
        if event.event_type != "TIMER_RETIRED" and fields["causal_sequence"] != str(timer[3]):
            raise ValidationError("resource audit timer event cause mismatch")
        target = timer[5]
        logical = _embedded_time(fields["logical_due_research_time"])
        if (
            target[0] != "monotonic"
            or logical.boot_session_id != target[1]
            or logical.elapsed_realtime_nanos != target[2]
            or event.condition_epoch_id is None
        ):
            raise ValidationError("resource audit timer event has the wrong logical target")
        if scheduled:
            if (
                event.observed_time.boot_session_id != target[1]
                or target[2] - event.observed_time.elapsed_realtime_nanos
                != 60_000_000_000
                or timer[6] - event.observed_time.wall_time_utc_millis != 60_000
            ):
                raise ValidationError("resource audit timer is not a 60-second same-boot timer")
        elif event.event_type == "TIMER_DUE" and (
            event.observed_time.boot_session_id != target[1]
            or event.observed_time.elapsed_realtime_nanos < target[2]
        ):
            raise ValidationError("resource audit timer fired before its monotonic target")

    def _source_epoch(self, event: RecordedEvent) -> str | None:
        observation_epoch = self.observation_epoch_by_event.get(event.sequence_number)
        if observation_epoch is not None:
            return observation_epoch
        schema = self.registry.event(event.source_id, event.schema_version, event.event_type)
        field = schema.primary_source_time_field
        if field is None or event.condition_epoch_id is None:
            return event.condition_epoch_id
        epoch = self.known_epochs.get(event.condition_epoch_id)
        if epoch is None:
            return event.condition_epoch_id
        value = int(event.wire_fields[field])
        if schema.primary_source_basis == "CONTINUOUS_MONOTONIC_SINCE_BOOT":
            if event.observed_time.boot_session_id != epoch.activated_at.boot_session_id:
                raise ValidationError("source clock cannot be assigned across reboot")
            start = epoch.activated_at.elapsed_realtime_nanos
            end_record = self.closed_epochs.get(epoch.id)
            end = end_record[1].elapsed_realtime_nanos if end_record else None
        elif schema.primary_source_basis == "UTC_WALL":
            start = epoch.activated_at.wall_time_utc_millis
            end_record = self.closed_epochs.get(epoch.id)
            end = end_record[1].wall_time_utc_millis if end_record else None
        else:
            return event.condition_epoch_id
        if value < start or (end is not None and value >= end):
            raise ValidationError("source event lies outside its condition epoch")
        return epoch.id

    def _verify_closed_observation_coverage(self) -> None:
        for epoch_id, observations in self.observations_by_epoch.items():
            epoch = self.known_epochs.get(epoch_id)
            if epoch is None:
                raise ValidationError("source observation references an orphan epoch")
            closed = self.closed_epochs.get(epoch_id)
            for observation in observations:
                coverage = observation.coverage
                if coverage is None:
                    continue
                if coverage.clock_basis == "SOURCE_WALL_TIME":
                    start = _coverage_coordinate(coverage.start_inclusive)
                    end = _coverage_coordinate(coverage.end_exclusive)
                    lower = epoch.activated_at.wall_time_utc_millis
                    upper = closed[1].wall_time_utc_millis if closed else None
                elif coverage.clock_basis == "SOURCE_MONOTONIC_TIME":
                    start = _coverage_coordinate(coverage.start_inclusive)
                    end = _coverage_coordinate(coverage.end_exclusive)
                    lower = epoch.activated_at.elapsed_realtime_nanos
                    upper = closed[1].elapsed_realtime_nanos if closed else None
                else:
                    start_time = _embedded_time(coverage.start_inclusive)
                    end_time = _embedded_time(coverage.end_exclusive)
                    if (
                        start_time.boot_session_id != end_time.boot_session_id
                        or start_time.boot_session_id != epoch.activated_at.boot_session_id
                    ):
                        raise ValidationError("coverage cannot be assigned across reboot")
                    start = start_time.elapsed_realtime_nanos
                    end = end_time.elapsed_realtime_nanos
                    lower = epoch.activated_at.elapsed_realtime_nanos
                    upper = closed[1].elapsed_realtime_nanos if closed else None
                if start >= end or start < lower or (upper is not None and end > upper):
                    raise ValidationError(
                        "retrospective coverage crosses a condition epoch boundary"
                    )

    def _verify_projection(
        self, commit: EngineCommit, checkpoint: Mapping[str, Any]
    ) -> None:
        projection = commit.successor_projection
        previous_event = (
            self.previous_projection["next_event_sequence"]
            if self.previous_projection is not None
            else 1
        )
        previous_observation = (
            self.previous_projection["next_observation_sequence"]
            if self.previous_projection is not None
            else 1
        )
        if commit.events and commit.events[0].sequence_number != previous_event:
            raise ValidationError("commit event range does not follow its predecessor")
        if (
            commit.source_observations
            and commit.source_observations[0].observation_sequence
            != previous_observation
        ):
            raise ValidationError("commit observation range does not follow its predecessor")
        event_end = (
            commit.events[-1].sequence_number + 1 if commit.events else previous_event
        )
        observation_end = (
            commit.source_observations[-1].observation_sequence + 1
            if commit.source_observations
            else previous_observation
        )
        if projection["next_event_sequence"] != event_end:
            raise ValidationError("successor event cursor diverged")
        if projection["next_observation_sequence"] != observation_end:
            raise ValidationError("successor observation cursor diverged")
        projected_epoch = projection["active_condition_epoch"]
        if (projected_epoch.id if projected_epoch else None) != (self.active_epoch.id if self.active_epoch else None):
            raise ValidationError("projection active epoch diverged from event replay")
        if projected_epoch and projected_epoch != self.active_epoch:
            raise ValidationError("projection condition epoch fields diverged")
        if projected_epoch and projected_epoch.configuration_sha256 != self.configuration_sha256:
            raise ValidationError("projection condition epoch configuration diverged")
        data_count = sum(
            1
            for event in commit.events
            if self.registry.source(event.source_id, event.schema_version)["source_kind"]
            == "COLLECTOR"
        )
        previous_count = (
            self.previous_projection["lifetime_data_event_count"]
            if self.previous_projection is not None
            else 0
        )
        if projection["lifetime_data_event_count"] != previous_count + data_count:
            raise ValidationError("lifetime collector event count diverged")
        previous_upload = (
            self.previous_projection["uploaded_through_commit"]
            if self.previous_projection is not None
            else 0
        )
        previous_evaluated = (
            self.previous_projection["evaluated_through_commit"]
            if self.previous_projection is not None
            else 0
        )
        previous_retained = (
            self.previous_projection["retained_from_commit"]
            if self.previous_projection is not None
            else 1
        )
        if (
            projection["uploaded_through_commit"] < previous_upload
            or projection["evaluated_through_commit"] < previous_evaluated
            or projection["retained_from_commit"] < previous_retained
        ):
            raise ValidationError("runtime watermark moved backwards")
        expected_lifecycle = (
            projection["state"]
            if projection["state"] in _SESSION_STATES
            else "READY"
        )
        if checkpoint["lifecycle"] != expected_lifecycle:
            raise ValidationError("automation lifecycle diverges from runtime projection")

    def _verify_checkpoint(
        self,
        commit: EngineCommit,
        prospective: Mapping[tuple[str, str], str],
    ) -> Mapping[str, Any]:
        checkpoint = _checkpoint_from_components(prospective)
        _validate_checkpoint(checkpoint, self.automation_ids, self.profile_digests)
        digest = automation_checkpoint_digest(checkpoint)
        if digest != commit.resulting_checkpoint_sha256:
            raise ValidationError("automation checkpoint digest divergence")
        if checkpoint["evaluated_through_sequence"] > commit.successor_projection["next_event_sequence"] - 1:
            raise ValidationError("automation checkpoint evaluated beyond durable events")
        if self.checkpoint is not None:
            delta = (
                checkpoint["evaluated_through_sequence"]
                - self.checkpoint["evaluated_through_sequence"]
            )
            if not 0 <= delta <= len(commit.events):
                raise ValidationError("automation reducer cursor is not causally bounded")
        return checkpoint

    def _verify_authoritative_reduction(
        self,
        commit: EngineCommit,
        prospective: Mapping[tuple[str, str], str],
    ) -> AutomationCheckpoint:
        encoded = _automation_checkpoint_encoding(prospective)
        durable = decode_authoritative_checkpoint(encoded)
        inputs = self._reconstruct_reducer_inputs(commit, prospective)
        if inputs:
            result = reduce_automation_batch(
                self.automation_program, self.authoritative_checkpoint, inputs
            )
            if result.checkpoint != durable:
                raise ValidationError(
                    "durable automation checkpoint diverges from authoritative replay"
                )
            if authoritative_checkpoint_digest(result.checkpoint) != commit.resulting_checkpoint_sha256:
                raise ValidationError("authoritative reducer checkpoint digest divergence")
            self._verify_reduction_outputs(commit, prospective, result)
        elif durable != self.authoritative_checkpoint:
            raise ValidationError("automation checkpoint changed without a reducer input")
        return durable

    def _reconstruct_reducer_inputs(
        self,
        commit: EngineCommit,
        prospective: Mapping[tuple[str, str], str],
    ) -> list[ReducerInput]:
        clock_value = commit.successor_projection["clock_checkpoint"]
        selected: list[tuple[str, Any]] = []
        lifecycle_states: list[tuple[RecordedEvent, str]] = []
        lifecycle_events = {
            "STUDY_STARTED": "ACTIVATING",
            "STUDY_RESUMED": "ACTIVATING",
            "STUDY_RUNNING": "RUNNING",
            "STUDY_PAUSE_REQUESTED": "PAUSING",
            "STUDY_COMPLETE_REQUESTED": "PAUSING",
            "STUDY_WITHDRAW_REQUESTED": "PAUSING",
            "STUDY_SAFETY_PAUSE_REQUESTED": "PAUSING",
            "STUDY_PAUSED": "PAUSED",
            "STUDY_COMPLETED": "COMPLETED",
            "STUDY_WITHDRAWN": "WITHDRAWN",
            "STUDY_SAFETY_PAUSED": "PAUSED",
        }
        for event in commit.events:
            if self.registry.source(event.source_id, event.schema_version)["source_kind"] == "COLLECTOR":
                selected.append(("EVENT", event))
            elif event.source_id == "study_runtime.v1" and event.event_type == "SOURCE_QUALITY_GAP":
                selected.append((
                    "CLOCK_DISCONTINUITY"
                    if event.wire_fields["reason"] == "WALL_CLOCK_CHANGED"
                    else "QUALITY_GAP",
                    event,
                ))
            elif event.source_id == "study_runtime.v1" and event.event_type in lifecycle_events:
                state = lifecycle_events[event.event_type]
                advertised = event.wire_fields["current_state"]
                # A terminal command issued while already PAUSED writes request and result audit
                # events with the same terminal current_state, but it is one reducer transition.
                if advertised in {"COMPLETED", "WITHDRAWN"} and state == "PAUSING":
                    state = advertised
                elif advertised != state:
                    raise ValidationError("lifecycle event does not identify its reducer transition")
                if not lifecycle_states or lifecycle_states[-1][1] != state:
                    lifecycle_states.append((event, state))
            elif (
                event.source_id == "timer.v1"
                and event.event_type == "TIMER_DUE"
                and not event.wire_fields["producer_key"].startswith("resource-audit:")
                and event.wire_fields["producer_key"] != "study-deadline"
            ):
                selected.append(("TIMER_DUE", event))

        # Generated lifecycle audits are not reducer inputs by event count.  Their closed-world
        # transition projection above deliberately collapses the PAUSED -> terminal request/result
        # pair while retaining the two PAUSING -> PAUSED safety transitions.
        selected.extend(("LIFECYCLE", evidence) for evidence, _state in lifecycle_states)
        selected.sort(key=lambda item: item[1].sequence_number if isinstance(item[1], RecordedEvent) else 1 << 63)

        if commit.input_kind == "RANDOM_SELECTION":
            materialized = [
                mutation for mutation in commit.mutations
                if mutation.component_kind == "TIMER" and mutation.operation == "UPSERT"
                and ("TIMER", mutation.component_id) not in self.components
            ]
            if len(materialized) != 1 or selected:
                raise ValidationError("timer materialization commit is not uniquely reconstructable")
            selected.append(("TIMER_MATERIALIZED", materialized[0]))

        self._require_reducer_input_shape(commit, selected)

        if not selected:
            if self.previous_projection is not None:
                prior_clock = self.previous_projection["clock_checkpoint"]
                if (
                    prior_clock is not None
                    and clock_value is not None
                    and prior_clock["zone_id"] != clock_value["zone_id"]
                ):
                    raise ValidationError("clock zone changed without a durable discontinuity input")
            return []
        if clock_value is None:
            raise ValidationError("reducer input commit has no authenticated clock")
        if clock_value["anchor"] != commit.committed_at:
            raise ValidationError("reducer clock anchor differs from commit time")
        prior_clock = (
            None if self.previous_projection is None
            else self.previous_projection["clock_checkpoint"]
        )
        has_discontinuity = any(kind == "CLOCK_DISCONTINUITY" for kind, _ in selected)
        if (
            prior_clock is not None
            and prior_clock["zone_id"] != clock_value["zone_id"]
            and not has_discontinuity
        ):
            raise ValidationError("clock zone changed without a durable discontinuity input")
        clock = _reducer_clock(clock_value)
        sequence = self.authoritative_checkpoint.evaluated_through_sequence
        inputs: list[ReducerInput] = []
        for kind, evidence in selected:
            sequence += 1
            if kind == "EVENT":
                inputs.append(ReducerInput(
                    "EVENT", sequence, clock,
                    event=_automation_event(evidence, sequence, self.registry),
                ))
            elif kind == "LIFECYCLE":
                state = next(
                    state for event, state in lifecycle_states if event is evidence
                )
                inputs.append(ReducerInput("LIFECYCLE", sequence, clock, state=state))
            elif kind == "QUALITY_GAP":
                inputs.append(ReducerInput(
                    "QUALITY_GAP", sequence, clock,
                    source_id=evidence.wire_fields["source_id"],
                ))
            elif kind == "CLOCK_DISCONTINUITY":
                restart_resources = tuple(sorted(
                    (
                        ResourceKey("COLLECTOR", resource.id)
                        for (component_kind, _component_id), value in self.components.items()
                        if component_kind == "RESOURCE"
                        for resource in (_decode_resource_component(value),)
                        if resource.kind == "collector"
                        and resource.status == "APPLIED"
                        and resource.id in self.registry.retrospective_collector_source_ids
                    ),
                    key=ResourceKey.sort_key,
                ))
                inputs.append(ReducerInput(
                    "CLOCK_DISCONTINUITY",
                    sequence,
                    clock,
                    restart_resources=restart_resources,
                ))
            elif kind == "TIMER_DUE":
                timer_id_value = evidence.wire_fields["timer_id"]
                encoded_timer = self.components.get(("TIMER", timer_id_value))
                if encoded_timer is None:
                    raise ValidationError("timer due has no prior durable timer")
                timer = _automation_timer(_decode_timer_component(encoded_timer))
                if _timer_event_evidence(evidence) != _timer_output_evidence(
                    timer, include_cause=True
                ):
                    raise ValidationError(
                        "timer due evidence diverges from its durable target"
                    )
                inputs.append(ReducerInput(
                    "TIMER_DUE", sequence, clock,
                    timer_id=timer.id, automation_id=timer.automation_id,
                    generation=timer.generation, causal_sequence=timer.causal_sequence,
                    target=timer.target,
                    logical_due=_automation_time(_embedded_time(
                        evidence.wire_fields["logical_due_research_time"]
                    )),
                ))
            else:
                mutation = evidence
                encoded_timer = prospective.get(("TIMER", mutation.component_id))
                if encoded_timer is None:
                    raise ValidationError("materialized timer component is absent")
                inputs.append(ReducerInput(
                    "TIMER_MATERIALIZED", sequence, clock,
                    timer=_automation_timer(_decode_timer_component(encoded_timer)),
                ))
        return inputs

    def _require_reducer_input_shape(
        self,
        commit: EngineCommit,
        selected: list[tuple[str, Any]],
    ) -> None:
        """Bind every reducer input to the only EngineInputKind that may durably carry it."""

        kinds = [kind for kind, _ in selected]
        event_count = sum(kind == "EVENT" for kind in kinds)
        tail = kinds[event_count:]
        if kinds[:event_count] != ["EVENT"] * event_count:
            raise ValidationError("collector reducer inputs are not first in their commit")
        if commit.input_kind == "SOURCE_OBSERVATION":
            valid = not tail or (event_count == 0 and tail == ["QUALITY_GAP"])
        elif commit.input_kind == "LIFECYCLE_COMMAND":
            valid = not kinds or tail == ["LIFECYCLE"]
        elif commit.input_kind == "TIMER_WAKE":
            duration_due = any(
                event.source_id == "timer.v1"
                and event.event_type == "TIMER_DUE"
                and event.wire_fields["producer_key"] == "study-deadline"
                for event in commit.events
            )
            valid = (
                not kinds
                or tail in (["TIMER_DUE"], ["CLOCK_DISCONTINUITY"])
                or (
                    duration_due
                    and tail in (
                        ["LIFECYCLE"],
                        ["LIFECYCLE", "LIFECYCLE"],
                        ["CLOCK_DISCONTINUITY", "LIFECYCLE"],
                    )
                )
            )
        elif commit.input_kind == "RANDOM_SELECTION":
            valid = event_count == 0 and tail == ["TIMER_MATERIALIZED"]
        elif commit.input_kind in {"ACTION_RESULT", "UPLOAD_ACKNOWLEDGEMENT"}:
            valid = not kinds
        elif commit.input_kind == "RESOURCE_RESULT":
            valid = event_count == 0 and tail in ([], ["LIFECYCLE"])
        elif commit.input_kind == "SAFETY_FAILURE":
            valid = event_count == 0 and 1 <= len(tail) <= 2 and all(
                kind == "LIFECYCLE" for kind in tail
            )
        elif commit.input_kind == "RECOVERY":
            valid = (
                "QUALITY_GAP" in tail
                and tail.count("QUALITY_GAP") == 1
                and all(kind in {"QUALITY_GAP", "LIFECYCLE"} for kind in tail)
                and tail.index("QUALITY_GAP") == 0
            )
        else:  # Parser already rejects unknown input kinds; keep this closed-world here too.
            valid = False
        if not valid:
            raise ValidationError(
                f"{commit.input_kind} commit cannot carry reducer inputs {kinds}"
            )

        observation_events = {
            sequence
            for observation in commit.source_observations
            if observation.event_count
            for sequence in range(
                int(observation.first_event_sequence),  # parser proved non-null for non-empty observations
                int(observation.last_event_sequence) + 1,
            )
        }
        collector_events = {
            event.sequence_number
            for event in commit.events
            if self.registry.source(event.source_id, event.schema_version)["source_kind"]
            == "COLLECTOR"
        }
        if collector_events != observation_events:
            raise ValidationError("reducer collector inputs differ from observation provenance")
        if commit.source_observations and commit.input_kind not in {
            "SOURCE_OBSERVATION", "LIFECYCLE_COMMAND", "TIMER_WAKE", "RECOVERY",
        }:
            raise ValidationError("engine input kind cannot carry source observations")

    def _verify_reduction_outputs(
        self,
        commit: EngineCommit,
        prospective: Mapping[tuple[str, str], str],
        result: Any,
    ) -> None:
        checkpoint_digest = authoritative_checkpoint_digest(result.checkpoint)
        causal_sequence = max(result.checkpoint.evaluated_through_sequence, 1)
        clock_value = commit.successor_projection["clock_checkpoint"]
        if clock_value is None:
            raise ValidationError("reducer output commit has no authenticated clock")
        observed = clock_value["anchor"]
        action_events = [
            event for event in commit.events
            if event.source_id == "automation_runtime.v1"
            and event.event_type == "ACTION_REQUESTED"
        ]
        expected_actions = [
            (
                item.action_id,
                item.automation_id,
                item.intervention_id,
                str(_causal_range(item.causal_identity, causal_sequence)[0]),
                str(_causal_range(item.causal_identity, causal_sequence)[1]),
                checkpoint_digest,
                _embedded_time_text(
                    ResearchTime(
                        item.logical_deadline_utc_millis,
                        observed.elapsed_realtime_nanos,
                        observed.boot_session_id,
                    )
                    if item.logical_deadline_utc_millis is not None
                    else observed
                ),
                _embedded_time_text(observed),
                observed,
            )
            for item in result.action_requests
        ]
        actual_actions = [
            (
                event.wire_fields["invocation_id"],
                event.wire_fields["automation_id"],
                event.wire_fields["intervention_id"],
                event.wire_fields["causal_first_sequence"],
                event.wire_fields["causal_final_sequence"],
                event.wire_fields["condition_sha256"],
                event.wire_fields["logical_time"],
                event.wire_fields["observed_time"],
                event.observed_time,
            )
            for event in action_events
            if event.wire_fields["generation"] == "1"
        ]
        if actual_actions != expected_actions or len(actual_actions) != len(action_events):
            raise ValidationError("durable action requests diverge from reducer output")

        action_components = {
            component_id: _decode_action_component(value)
            for (kind, component_id), value in prospective.items()
            if kind == "ACTION_INVOCATION" and component_id in {
                item.action_id for item in result.action_requests
            }
        }
        expected_action_components = {
            item.action_id: DurableAction(
                item.action_id,
                item.automation_id,
                item.intervention_id,
                causal_sequence,
                item.logical_deadline_utc_millis,
                item.expires_at_utc_millis,
                checkpoint_digest,
                1,
                observed,
                None,
                "READY",
                None,
            )
            for item in result.action_requests
        }
        if action_components != expected_action_components:
            raise ValidationError("durable action outbox differs from reducer output")

        audit_events = [
            event for event in commit.events
            if event.source_id == "automation_runtime.v1"
            and event.event_type in {"AUTOMATION_MATCHED", "AUTOMATION_SUPPRESSED"}
        ]
        expected_audits = [
            (
                item.automation_id,
                "AUTOMATION_MATCHED" if item.suppression_reason is None else "AUTOMATION_SUPPRESSED",
                _registry_suppression(item.suppression_reason),
                str(_causal_range(item.causal_identity, causal_sequence)[0]),
                str(_causal_range(item.causal_identity, causal_sequence)[1]),
                checkpoint_digest,
                _embedded_time_text(observed),
                observed,
            )
            for item in result.audits
            if item.matched or item.suppression_reason is not None
        ]
        actual_audits = [
            (
                event.wire_fields["automation_id"], event.event_type,
                event.wire_fields.get("suppression_reason"),
                event.wire_fields["causal_first_sequence"],
                event.wire_fields["causal_final_sequence"],
                event.wire_fields["condition_sha256"],
                event.wire_fields["logical_time"],
                event.observed_time,
            )
            for event in audit_events
            if event.wire_fields["generation"] == "1"
            and event.wire_fields["observed_time"] == _embedded_time_text(event.observed_time)
        ]
        if actual_audits != expected_audits or len(actual_audits) != len(audit_events):
            raise ValidationError("automation audit events diverge from reducer output")

        timer_events = [
            event for event in commit.events
            if event.source_id == "timer.v1"
            and event.event_type in {"TIMER_SCHEDULED", "TIMER_RETIRED"}
            and not event.wire_fields["producer_key"].startswith("resource-audit:")
            and event.wire_fields["producer_key"] != "study-deadline"
        ]
        expected_timers = [
            (
                item.type,
                _timer_output_evidence(
                    item.timer
                    if item.timer is not None
                    else self.authoritative_checkpoint.timers.get(item.timer_id or ""),
                    include_cause=item.type == "SCHEDULE",
                ),
            )
            for item in result.timer_intents
            if item.type == "SCHEDULE"
            or item.timer_id in self.authoritative_checkpoint.timers
        ]
        actual_timers = [
            (
                "SCHEDULE" if event.event_type == "TIMER_SCHEDULED" else "RETIRE",
                _timer_event_evidence(event),
            )
            for event in timer_events
        ]
        if (
            actual_timers != expected_timers
            or any(event.observed_time != observed for event in timer_events)
            or any(
                event.event_type == "TIMER_RETIRED"
                and event.wire_fields["retirement_reason"]
                != _expected_timer_retirement_reason(commit)
                for event in timer_events
            )
        ):
            raise ValidationError("timer audit events diverge from reducer intents")

        before = self.authoritative_checkpoint.desired_resources
        after = result.checkpoint.desired_resources
        expected_changes = {
            key: value for key, value in after.items() if before.get(key) != value
        }
        if result.resource_changes != expected_changes:
            raise ValidationError("resource changes are not the checkpoint delta")

    def _verify_component_state(
        self,
        commit: EngineCommit,
        prospective: Mapping[tuple[str, str], str],
        checkpoint: Mapping[str, Any],
    ) -> None:
        component_timers: dict[str, tuple[Any, ...]] = {}
        resource_audit_timers: dict[str, tuple[Any, ...]] = {}
        upload_acknowledgements: dict[str, UploadAcknowledgement] = {}
        resources: dict[tuple[str, str], AppliedResource] = {}
        cleanups: dict[tuple[str, str], ResourceCleanup] = {}
        for (kind, component_id), value in prospective.items():
            if kind == "TIMER":
                timer = _decode_timer_component(value)
                if timer[0] != component_id:
                    raise ValidationError("timer component key mismatch")
                if timer[1] not in self.automation_ids:
                    raise ValidationError("timer component references unknown automation")
                if timer[0] != _deterministic_digest(
                    "particeps-timer-v1",
                    self.configuration_sha256,
                    timer[1],
                    timer[4],
                ):
                    raise ValidationError("timer component deterministic identity mismatch")
                component_timers[component_id] = timer
            elif kind == "RESOURCE_AUDIT_TIMER":
                timer = _decode_timer_component(value)
                if timer[0] != component_id:
                    raise ValidationError("resource audit timer component key mismatch")
                resource_audit_timers[component_id] = timer
            elif kind == "RESOURCE":
                resource = _decode_resource_component(value)
                if component_id != f"{resource.kind}:{resource.id}":
                    raise ValidationError("resource component key mismatch")
                if resource.key not in self.profile_digests:
                    raise ValidationError("resource component was not signed into the study")
                resources[resource.key] = resource
            elif kind == "RESOURCE_CLEANUP":
                cleanup = _decode_resource_cleanup_component(value)
                if component_id != f"{cleanup.kind}:{cleanup.id}":
                    raise ValidationError("resource cleanup component key mismatch")
                profiles = self.profile_digests.get(cleanup.key)
                if (
                    profiles is None
                    or profiles.get(cleanup.profile_id)
                    != cleanup.expected_profile_sha256
                ):
                    raise ValidationError(
                        "resource cleanup attempt is not a signed profile"
                    )
                cleanups[cleanup.key] = cleanup
            elif kind == "ACTION_INVOCATION":
                action = _decode_action_component(value)
                if action.action_id != component_id:
                    raise ValidationError("action component key mismatch")
                if (
                    action.automation_id not in self.occurrences
                    or action.intervention_id not in self.intervention_ids
                ):
                    raise ValidationError("action component has an unknown reference")
                identity = self.requested_actions.get(action.action_id)
                if identity != (
                    action.automation_id,
                    action.intervention_id,
                    action.condition_sha256,
                ):
                    raise ValidationError("action component has no durable causal request")
            elif kind == "UPLOAD_ACKNOWLEDGEMENT":
                if component_id != "latest":
                    raise ValidationError("upload acknowledgement component key mismatch")
                upload_acknowledgements[component_id] = (
                    _decode_upload_acknowledgement(value)
                )

        self._verify_resource_cleanup_state(
            commit,
            prospective,
            checkpoint,
            resources,
            cleanups,
        )

        checkpoint_timers = checkpoint["timers"]
        if component_timers != checkpoint_timers:
            raise ValidationError("timer components diverge from automation checkpoint")
        checkpoint_timer_ids = {
            timer_id: (timer[1], int(timer[2]))
            for timer_id, timer in checkpoint_timers.items()
        }
        if self.timers != checkpoint_timer_ids:
            raise ValidationError("timer audit lifecycle diverges from durable timer state")

        applied_traffic = any(
            kind == "RESOURCE"
            and _decode_resource_component(value).key
            == ("actuator", "traffic-shaping.v1")
            and _decode_resource_component(value).status == "APPLIED"
            for (kind, _), value in prospective.items()
        )
        audit_required = (
            applied_traffic
            and commit.successor_projection["active_condition_epoch"] is not None
        )
        if len(resource_audit_timers) != (1 if audit_required else 0):
            raise ValidationError(
                "durable traffic resource and resource audit timer diverge"
            )

        if len(upload_acknowledgements) > 1:
            raise ValidationError("multiple upload acknowledgement components exist")
        acknowledgement = upload_acknowledgements.get("latest")
        prior_acknowledgement = (
            _decode_upload_acknowledgement(
                self.components[("UPLOAD_ACKNOWLEDGEMENT", "latest")]
            )
            if ("UPLOAD_ACKNOWLEDGEMENT", "latest") in self.components
            else None
        )
        previous_upload = (
            0
            if self.previous_projection is None
            else self.previous_projection["uploaded_through_commit"]
        )
        current_upload = commit.successor_projection["uploaded_through_commit"]
        if current_upload > 0 and (
            acknowledgement is None
            or acknowledgement.through_commit != current_upload
        ):
            raise ValidationError("upload watermark lacks its durable acknowledgement")
        if commit.input_kind == "UPLOAD_ACKNOWLEDGEMENT":
            if (
                acknowledgement is None
                or acknowledgement == prior_acknowledgement
                or acknowledgement.first_commit != previous_upload + 1
                or acknowledgement.through_commit != current_upload
                or acknowledgement.through_commit > commit.commit_sequence - 1
                or _time_order(acknowledgement.acknowledged_at, commit.committed_at) > 0
            ):
                raise ValidationError("upload acknowledgement is not causally valid")
        elif (
            current_upload != previous_upload
            or acknowledgement != prior_acknowledgement
        ):
            raise ValidationError(
                "upload acknowledgement changed outside an acknowledgement input"
            )

        previous_actions = {
            component_id: _decode_action_component(value)
            for (kind, component_id), value in self.components.items()
            if kind == "ACTION_INVOCATION"
        }
        current_actions = {
            component_id: _decode_action_component(value)
            for (kind, component_id), value in prospective.items()
            if kind == "ACTION_INVOCATION"
        }
        if not set(previous_actions) <= set(current_actions):
            raise ValidationError("durable action invocation was removed")
        for action_id, action in current_actions.items():
            prior = previous_actions.get(action_id)
            if prior is None:
                if action.state != "READY" or action.generation != 1:
                    raise ValidationError("new durable action is not a generation-one request")
                continue
            stable = (
                prior.action_id,
                prior.automation_id,
                prior.intervention_id,
                prior.causal_sequence,
                prior.logical_deadline_utc_millis,
                prior.expires_at_utc_millis,
                prior.condition_sha256,
                prior.generation,
                prior.requested_at,
            )
            candidate = (
                action.action_id,
                action.automation_id,
                action.intervention_id,
                action.causal_sequence,
                action.logical_deadline_utc_millis,
                action.expires_at_utc_millis,
                action.condition_sha256,
                action.generation,
                action.requested_at,
            )
            if stable != candidate:
                raise ValidationError("durable action identity changed during retry")
            if prior.opened_at is not None and action.opened_at != prior.opened_at:
                raise ValidationError("durable action open time changed")
            allowed = {
                "READY": {"READY", "CLAIMED", "SUCCEEDED", "FAILED"},
                "CLAIMED": {"CLAIMED", "OPENED", "SUCCEEDED", "FAILED"},
                "OPENED": {"OPENED", "SUCCEEDED", "FAILED"},
                "SUCCEEDED": {"SUCCEEDED"},
                "FAILED": {"FAILED"},
            }
            if action.state not in allowed[prior.state]:
                raise ValidationError("durable action state moved backwards")

        action_events = [
            event
            for event in commit.events
            if event.source_id == "automation_runtime.v1"
            and event.event_type
            in {"ACTION_REQUESTED", "ACTION_SUCCEEDED", "ACTION_FAILED"}
        ]
        for event in action_events:
            action = current_actions.get(event.wire_fields["invocation_id"])
            if action is None:
                raise ValidationError("action audit has no durable outbox component")
            expected_state = {
                "ACTION_REQUESTED": "READY",
                "ACTION_SUCCEEDED": "SUCCEEDED",
                "ACTION_FAILED": "FAILED",
            }[event.event_type]
            if action.state != expected_state:
                raise ValidationError("action audit diverges from durable outbox state")
        self._verify_survey_expiry_causality(commit, current_actions)

    def _verify_survey_expiry_causality(
        self,
        commit: EngineCommit,
        current_actions: Mapping[str, DurableAction],
    ) -> None:
        """Bind survey expiry UI state to its exact terminal outbox result."""
        expired_failures: dict[str, tuple[int, RecordedEvent]] = {}
        survey_expiries: dict[str, tuple[int, RecordedEvent]] = {}
        for index, event in enumerate(commit.events):
            if (
                event.source_id == "automation_runtime.v1"
                and event.event_type == "ACTION_FAILED"
                and event.wire_fields["failure_reason"] == "EXPIRED"
            ):
                action_id = event.wire_fields["invocation_id"]
                if action_id in expired_failures:
                    raise ValidationError("duplicate expired action audit")
                expired_failures[action_id] = index, event
            elif (
                event.source_id == "interventions.v1"
                and event.event_type == "SURVEY_EXPIRED"
            ):
                action_id = event.wire_fields["occurrence_id"]
                if action_id in survey_expiries:
                    raise ValidationError("duplicate survey expiry audit")
                survey_expiries[action_id] = index, event

        for action_id, (survey_index, survey_event) in survey_expiries.items():
            action = current_actions.get(action_id)
            if action is None:
                raise ValidationError("orphan survey expiry has no durable action")
            if self.intervention_action_types.get(action.intervention_id) != "survey":
                raise ValidationError("non-survey action emitted SURVEY_EXPIRED")
            failure_entry = expired_failures.get(action_id)
            if failure_entry is None:
                raise ValidationError("orphan survey expiry has no expired action result")
            failure_index, failure_event = failure_entry
            if survey_index >= failure_index:
                raise ValidationError("SURVEY_EXPIRED must precede its ACTION_FAILED result")
            expected_scheduled = (
                action.logical_deadline_utc_millis
                if action.logical_deadline_utc_millis is not None
                else action.requested_at.wall_time_utc_millis
            )
            if (
                survey_event.wire_fields["intervention_id"] != action.intervention_id
                or survey_event.wire_fields["trigger_id"] != action.automation_id
                or survey_event.wire_fields["scheduled_for_utc_millis"]
                != str(expected_scheduled)
                or survey_event.observed_time != failure_event.observed_time
                or survey_event.condition_epoch_id
                != failure_event.condition_epoch_id
            ):
                raise ValidationError("survey expiry identity diverges from its durable action")

        for action_id, (_failure_index, failure_event) in expired_failures.items():
            action = current_actions.get(action_id)
            if action is None:
                raise ValidationError("expired action audit has no durable action")
            expected_logical_time = failure_event.observed_time
            if action.logical_deadline_utc_millis is not None:
                expected_logical_time = ResearchTime(
                    action.logical_deadline_utc_millis,
                    failure_event.observed_time.elapsed_realtime_nanos,
                    failure_event.observed_time.boot_session_id,
                )
            if (
                action.state != "FAILED"
                or action.failure_reason != "EXPIRED"
                or failure_event.wire_fields["automation_id"] != action.automation_id
                or failure_event.wire_fields["intervention_id"] != action.intervention_id
                or failure_event.wire_fields["condition_sha256"] != action.condition_sha256
                or failure_event.wire_fields["generation"] != str(action.generation)
                or failure_event.wire_fields["causal_first_sequence"]
                != str(action.causal_sequence)
                or failure_event.wire_fields["causal_final_sequence"]
                != str(action.causal_sequence)
                or failure_event.wire_fields["logical_time"]
                != _embedded_time_text(expected_logical_time)
                or failure_event.wire_fields["observed_time"]
                != _embedded_time_text(failure_event.observed_time)
            ):
                raise ValidationError("expired action audit diverges from its durable action")
            action_type = self.intervention_action_types.get(action.intervention_id)
            if action_type is None:
                raise ValidationError("expired action references an unknown intervention")
            if action_type == "survey" and action_id not in survey_expiries:
                raise ValidationError("expired survey action is missing SURVEY_EXPIRED")
            if action_type != "survey" and action_id in survey_expiries:
                raise ValidationError("notification expiry cannot emit SURVEY_EXPIRED")

    def _verify_resource_cleanup_state(
        self,
        commit: EngineCommit,
        prospective: Mapping[tuple[str, str], str],
        checkpoint: Mapping[str, Any],
        resources: Mapping[tuple[str, str], AppliedResource],
        cleanups: Mapping[tuple[str, str], ResourceCleanup],
    ) -> None:
        previous_resources = {
            resource.key: resource
            for (kind, _component_id), value in self.components.items()
            if kind == "RESOURCE"
            for resource in (_decode_resource_component(value),)
        }
        previous_cleanups = {
            cleanup.key: cleanup
            for (kind, _component_id), value in self.components.items()
            if kind == "RESOURCE_CLEANUP"
            for cleanup in (_decode_resource_cleanup_component(value),)
        }
        state = commit.successor_projection["state"]
        epoch = commit.successor_projection["active_condition_epoch"]
        signed_keys = set(self.profile_digests)

        if cleanups:
            if state != "PAUSED" or epoch is not None:
                raise ValidationError(
                    "resource cleanup is allowed only in closed PAUSED containment"
                )
            if set(resources) != signed_keys:
                raise ValidationError(
                    "resource cleanup containment has an incomplete trusted vector"
                )
            if previous_cleanups and cleanups != previous_cleanups:
                raise ValidationError(
                    "pending resource cleanup changed before atomic finalization"
                )
            if not previous_cleanups and commit.input_kind not in {
                "SAFETY_FAILURE",
                "RECOVERY",
            }:
                raise ValidationError(
                    "resource cleanup was created outside a containment input"
                )
            for key in cleanups:
                current_resource = resources.get(key)
                if current_resource is None:
                    raise ValidationError(
                        "resource cleanup has no last-trusted resource"
                    )
                component_id = f"{key[0]}:{key[1]}"
                prior_encoded = self.components.get(("RESOURCE", component_id))
                current_encoded = prospective.get(("RESOURCE", component_id))
                if prior_encoded is not None and current_encoded != prior_encoded:
                    raise ValidationError(
                        "resource cleanup rewrote its last-trusted resource"
                    )
                if prior_encoded is None and current_resource.status != "INACTIVE":
                    raise ValidationError(
                        "initial containment must materialize an inactive trusted resource"
                    )

        removed_cleanup_keys = set(previous_cleanups) - set(cleanups)
        previous_was_paused = (
            self.previous_projection is not None
            and self.previous_projection["state"] == "PAUSED"
        )
        previous_unconverged = previous_was_paused and (
            bool(previous_cleanups)
            or any(item.status != "INACTIVE" for item in previous_resources.values())
        )
        if removed_cleanup_keys or (
            previous_unconverged
            and resources
            and all(item.status == "INACTIVE" for item in resources.values())
        ):
            if (
                commit.input_kind != "RESOURCE_RESULT"
                or state != "PAUSED"
                or epoch is not None
                or cleanups
                or set(resources) != signed_keys
                or any(item.status != "INACTIVE" for item in resources.values())
            ):
                raise ValidationError(
                    "resource cleanup finalization is not an atomic all-inactive result"
                )
            cleanup_mutations = {
                mutation.component_id: mutation.operation
                for mutation in commit.mutations
                if mutation.component_kind == "RESOURCE_CLEANUP"
            }
            expected_cleanup_ids = {
                f"{kind}:{source_id}" for kind, source_id in previous_cleanups
            }
            if cleanup_mutations != {
                component_id: "REMOVE" for component_id in expected_cleanup_ids
            }:
                raise ValidationError(
                    "resource cleanup finalization did not remove the complete ledger"
                )
            resource_mutations = {
                mutation.component_id: mutation
                for mutation in commit.mutations
                if mutation.component_kind == "RESOURCE"
            }
            expected_resource_ids = {
                f"{kind}:{source_id}" for kind, source_id in signed_keys
            }
            if set(resource_mutations) != expected_resource_ids or any(
                mutation.operation != "UPSERT"
                for mutation in resource_mutations.values()
            ):
                raise ValidationError(
                    "resource cleanup finalization did not rewrite every signed resource"
                )
            for key, resource in resources.items():
                desired = checkpoint["desired_resources"].get(
                    (key[0].upper(), key[1])
                )
                if desired != (str(resource.desired_generation), None):
                    raise ValidationError(
                        "inactive cleanup receipt diverges from reducer desired generation"
                    )


def calculate_commit_sha256(commit: EngineCommit) -> str:
    writer = _CanonicalWriter()
    writer.string("particeps-engine-commit-v1")
    writer.long(commit.commit_sequence)
    writer.string(commit.previous_commit_sha256)
    writer.string(commit.input_kind)
    writer.nullable(commit.consumed_pending_input_sha256, writer.string)
    writer.list(commit.source_observations, lambda item: _write_observation(writer, item))
    writer.list(commit.events, lambda item: _write_event(writer, item))
    writer.list(commit.mutations, lambda item: _write_mutation(writer, item))
    _write_time(writer, commit.committed_at)
    _write_projection(writer, commit.successor_projection)
    writer.string(commit.resulting_checkpoint_sha256)
    return hashlib.sha256(writer.bytes()).hexdigest()


class _CanonicalWriter:
    def __init__(self) -> None:
        self.stream = io.BytesIO()

    def bytes(self) -> bytes:
        return self.stream.getvalue()

    def integer(self, value: int) -> None:
        self.stream.write(struct.pack(">i", value))

    def long(self, value: int) -> None:
        self.stream.write(struct.pack(">q", value))

    def boolean(self, value: bool) -> None:
        self.stream.write(b"\x01" if value else b"\x00")

    def string(self, value: str) -> None:
        data = value.encode()
        self.integer(len(data))
        self.stream.write(data)

    def nullable(self, value: Any, encode) -> None:
        self.boolean(value is not None)
        if value is not None:
            encode(value)

    def list(self, values: Iterable[Any], encode) -> None:
        values = tuple(values)
        self.integer(len(values))
        for value in values:
            encode(value)


def _write_observation(writer: _CanonicalWriter, value: SourceObservation) -> None:
    writer.long(value.observation_sequence); writer.string(value.source_id)
    writer.integer(value.schema_version); writer.long(value.resource_generation)
    writer.string(value.admission_kind); writer.long(value.producer_ordinal)
    writer.string(value.condition_epoch_id); writer.integer(value.event_count)
    writer.nullable(value.first_event_sequence, writer.long)
    writer.nullable(value.last_event_sequence, writer.long)
    writer.nullable(value.coverage, lambda item: _write_coverage(writer, item))
    writer.string(value.encoded_sha256)


def _write_event(writer: _CanonicalWriter, value: RecordedEvent) -> None:
    writer.long(value.sequence_number); writer.string(value.source_id)
    writer.integer(value.schema_version); writer.string(value.event_type)
    _write_time(writer, value.observed_time)
    writer.nullable(value.condition_epoch_id, writer.string)
    items = sorted(value.wire_fields.items())
    writer.integer(len(items))
    for key, field_value in items:
        writer.string(key); writer.string(field_value)


def _write_mutation(writer: _CanonicalWriter, value: RuntimeMutation) -> None:
    writer.string(value.component_kind); writer.string(value.component_id)
    writer.string(value.operation); writer.nullable(value.canonical_value, writer.string)


def _write_projection(writer: _CanonicalWriter, value: Mapping[str, Any]) -> None:
    writer.string(value["state"]); writer.long(value["revision"])
    writer.long(value["next_commit_sequence"]); writer.long(value["next_observation_sequence"])
    writer.long(value["next_event_sequence"])
    checkpoints = value["source_checkpoints"]
    writer.integer(len(checkpoints))
    for source_id in sorted(checkpoints):
        checkpoint = checkpoints[source_id]
        writer.string(source_id); writer.string(checkpoint["source_id"])
        writer.long(checkpoint["resource_generation"]); writer.long(checkpoint["next_producer_ordinal"])
        writer.nullable(checkpoint["coverage"], lambda item: _write_coverage(writer, item))
        writer.nullable(checkpoint["cursor"], writer.string)
    writer.nullable(value["clock_checkpoint"], lambda item: _write_clock(writer, item))
    writer.nullable(value["active_condition_epoch"], lambda item: _write_epoch(writer, item))
    writer.long(value["lifetime_data_event_count"]); writer.long(value["uploaded_through_commit"])
    writer.long(value["evaluated_through_commit"]); writer.long(value["retained_from_commit"])


def _write_time(writer: _CanonicalWriter, value: ResearchTime) -> None:
    writer.long(value.wall_time_utc_millis); writer.long(value.elapsed_realtime_nanos)
    writer.string(value.boot_session_id)


def _write_coverage(writer: _CanonicalWriter, value: SourceCoverage) -> None:
    writer.string(value.clock_basis); writer.string(value.start_inclusive); writer.string(value.end_exclusive)


def _write_clock(writer: _CanonicalWriter, value: Mapping[str, Any]) -> None:
    writer.long(value["calendar_elapsed_nanos"]); writer.long(value["active_running_elapsed_nanos"])
    _write_time(writer, value["anchor"]); writer.long(value["deadline_utc_millis"])
    writer.boolean(value["deadline_utc_trusted"])
    writer.string(value["zone_id"])


def _write_epoch(writer: _CanonicalWriter, value: ConditionEpoch) -> None:
    writer.string(value.id); writer.string(value.configuration_sha256)
    writer.string(value.applied_resource_vector_sha256); _write_time(writer, value.activated_at)


def _observation_digest(observation: SourceObservation, events: list[RecordedEvent]) -> str:
    writer = _CanonicalWriter()
    writer.string("particeps-source-observation-v1")
    writer.string(observation.source_id)
    writer.integer(observation.schema_version)
    writer.long(observation.resource_generation)
    writer.long(observation.producer_ordinal)
    writer.string(observation.condition_epoch_id)
    writer.boolean(observation.coverage is not None)
    if observation.coverage is not None:
        writer.string(observation.coverage.clock_basis)
        writer.string(observation.coverage.start_inclusive)
        writer.string(observation.coverage.end_exclusive)
    writer.integer(len(events))
    for event in events:
        writer.string(event.event_type)
        writer.long(event.observed_time.wall_time_utc_millis)
        writer.long(event.observed_time.elapsed_realtime_nanos)
        writer.string(event.observed_time.boot_session_id)
        fields = sorted(event.wire_fields.items())
        writer.integer(len(fields))
        for key, value in fields:
            writer.string(key)
            writer.string(value)
    return hashlib.sha256(writer.bytes()).hexdigest()


def _checkpoint_from_components(
    components: Mapping[tuple[str, str], str],
) -> Mapping[str, Any]:
    chunks = {
        component_id: value
        for (kind, component_id), value in components.items()
        if kind == "AUTOMATION_CHECKPOINT"
    }
    if "main" not in chunks:
        raise ValidationError("runtime has no durable automation checkpoint")
    ordered = ["main"]
    suffixes = sorted(key for key in chunks if key != "main")
    expected = [f"main/{index:04d}" for index in range(1, len(suffixes) + 1)]
    if suffixes != expected:
        raise ValidationError("automation checkpoint chunks are not contiguous")
    ordered.extend(suffixes)
    return decode_automation_checkpoint("".join(chunks[key] for key in ordered))


def _automation_checkpoint_encoding(
    components: Mapping[tuple[str, str], str],
) -> str:
    chunks = {
        component_id: value
        for (kind, component_id), value in components.items()
        if kind == "AUTOMATION_CHECKPOINT"
    }
    if "main" not in chunks:
        raise ValidationError("runtime has no durable automation checkpoint")
    suffixes = sorted(key for key in chunks if key != "main")
    if suffixes != [f"main/{index:04d}" for index in range(1, len(suffixes) + 1)]:
        raise ValidationError("automation checkpoint chunks are not contiguous")
    return "".join(chunks[key] for key in ["main", *suffixes])


def _reducer_clock(value: Mapping[str, Any]) -> ReducerClock:
    return ReducerClock(
        _automation_time(value["anchor"]),
        value["active_running_elapsed_nanos"],
        value["calendar_elapsed_nanos"],
        value["zone_id"],
    )


def _automation_time(value: ResearchTime) -> AutomationResearchTime:
    return AutomationResearchTime(
        value.wall_time_utc_millis,
        value.elapsed_realtime_nanos,
        value.boot_session_id,
    )


def _automation_event(
    value: RecordedEvent,
    sequence: int,
    registry: EventSourceRegistry,
) -> AutomationEvent:
    schema = registry.event(value.source_id, value.schema_version, value.event_type)
    primary: AutomationResearchTime | None = None
    field = schema.primary_source_time_field
    if field is not None and field in value.wire_fields:
        encoded = int(value.wire_fields[field])
        if schema.primary_source_basis == "UTC_WALL":
            primary = AutomationResearchTime(
                encoded,
                value.observed_time.elapsed_realtime_nanos,
                value.observed_time.boot_session_id,
            )
        elif schema.primary_source_basis in {
            "CONTINUOUS_MONOTONIC_SINCE_BOOT", "BOOT_SESSION_MONOTONIC",
        }:
            primary = AutomationResearchTime(
                value.observed_time.wall_time_utc_millis,
                encoded,
                value.observed_time.boot_session_id,
            )
    return AutomationEvent(
        sequence, value.source_id, value.schema_version, value.event_type,
        _automation_time(value.observed_time), primary, dict(value.wire_fields),
    )


def _automation_timer(value: tuple[Any, ...]) -> DurableTimer:
    target = value[5]
    if target[0] == "calendar":
        converted = TimerTarget("CALENDAR_UTC", utc_millis=target[1])
    elif target[0] == "active":
        converted = TimerTarget("ACTIVE_ELAPSED", elapsed_nanos=target[1])
    else:
        converted = TimerTarget(
            "SAME_BOOT_MONOTONIC",
            boot_session_id=target[1], elapsed_realtime_nanos=target[2],
        )
    return DurableTimer(
        value[0], value[1], int(value[2]), value[3], value[4], converted,
        value[6], value[7],
    )


def _registry_suppression(value: str | None) -> str | None:
    return {
        None: None,
        "GUARD_FALSE": "OPTIONAL_DEPENDENCY_FAILED",
        "COOLDOWN": "COOLDOWN",
        "MAXIMUM_ACTIVATIONS": "MAXIMUM_ACTIVATIONS",
        "EXPIRED": "AVAILABILITY_EXPIRED",
        "STALE_TIMER": "NOT_RUNNING",
    }[value]


def _embedded_time_text(value: ResearchTime) -> str:
    return canonicalize({
        "boot_session_id": value.boot_session_id,
        "monotonic_time_nanos": str(value.elapsed_realtime_nanos),
        "wall_time_utc_millis": str(value.wall_time_utc_millis),
    }).decode()


def _causal_range(identity: str, fallback: int) -> tuple[int, int]:
    parts = identity.split(":")
    if len(parts) == 2 and parts[0] == "event" and parts[1].isdigit():
        result = int(parts[1]), int(parts[1])
    elif (
        len(parts) == 3
        and parts[0] == "range"
        and parts[1].isdigit()
        and parts[2].isdigit()
    ):
        result = int(parts[1]), int(parts[2])
    elif len(parts) == 2 and parts[0] == "timer":
        result = fallback, fallback
    else:
        raise ValidationError("unknown reducer causal identity")
    if not 0 < result[0] <= result[1] <= fallback:
        raise ValidationError("reducer causal identity is outside the committed range")
    return result


def _timer_logical_time(timer: DurableTimer) -> ResearchTime:
    target = timer.target
    if target.type == "CALENDAR_UTC":
        return ResearchTime(int(target.utc_millis), 0, "calendar-time")
    if target.type == "ACTIVE_ELAPSED":
        return ResearchTime(0, int(target.elapsed_nanos), "active-running-time")
    return ResearchTime(
        timer.logical_deadline_utc_millis or 0,
        int(target.elapsed_realtime_nanos),
        str(target.boot_session_id),
    )


def _timer_output_evidence(
    timer: DurableTimer | None,
    *,
    include_cause: bool,
) -> tuple[str, str, str | None, str, str, str, str]:
    if timer is None:
        raise ValidationError("timer retirement has no authoritative prior timer")
    clock = {
        "CALENDAR_UTC": "CALENDAR_TIME",
        "ACTIVE_ELAPSED": "ACTIVE_RUNNING_TIME",
        "SAME_BOOT_MONOTONIC": "SAME_BOOT_MONOTONIC",
    }[timer.target.type]
    return (
        timer.id,
        timer.automation_id,
        str(timer.causal_sequence) if include_cause else None,
        clock,
        str(timer.generation),
        _embedded_time_text(_timer_logical_time(timer)),
        timer.producer_key,
    )


def _timer_event_evidence(
    event: RecordedEvent,
) -> tuple[str, str, str | None, str, str, str, str]:
    return (
        event.wire_fields["timer_id"],
        event.wire_fields["automation_id"],
        event.wire_fields.get("causal_sequence"),
        event.wire_fields["clock"],
        event.wire_fields["generation"],
        event.wire_fields["logical_due_research_time"],
        event.wire_fields["producer_key"],
    )


def _expected_timer_retirement_reason(commit: EngineCommit) -> str:
    quality_gap = any(
        event.source_id == "study_runtime.v1"
        and event.event_type == "SOURCE_QUALITY_GAP"
        for event in commit.events
    )
    if commit.input_kind == "RECOVERY" or quality_gap:
        return "QUALITY_GAP_RESET"
    if commit.input_kind in {"LIFECYCLE_COMMAND", "SAFETY_FAILURE"}:
        return "LIFECYCLE_ENDED"
    if commit.input_kind == "TIMER_WAKE":
        return "FIRED"
    return "CANCELLED"


def _decode_component(encoded: str, prefix: str) -> _ComponentReader:
    if not encoded.startswith(prefix):
        raise ValidationError("unexpected runtime component encoding")
    payload_text = encoded[len(prefix) :]
    if not re.fullmatch(r"[A-Za-z0-9_-]+", payload_text):
        raise ValidationError("invalid runtime component base64url")
    try:
        payload = base64.urlsafe_b64decode(
            payload_text + "=" * ((4 - len(payload_text) % 4) % 4)
        )
    except (ValueError, binascii.Error) as error:
        raise ValidationError("invalid runtime component base64url") from error
    if (
        len(payload) > 512 * 1024
        or base64.urlsafe_b64encode(payload).rstrip(b"=").decode() != payload_text
    ):
        raise ValidationError("runtime component is non-canonical or too large")
    return _ComponentReader(payload)


def _decode_timer_component(encoded: str) -> tuple[Any, ...]:
    reader = _decode_component(encoded, "durable-timer-v1:")
    if reader.integer() != 1:
        raise ValidationError("unsupported durable timer component")
    timer = reader.timer()
    if reader.remaining:
        raise ValidationError("trailing durable timer component bytes")
    _validate_timer(timer)
    return timer


def _component_timers(
    components: Mapping[tuple[str, str], str], kind: str
) -> dict[str, tuple[Any, ...]]:
    timers: dict[str, tuple[Any, ...]] = {}
    for (component_kind, component_id), encoded in components.items():
        if component_kind != kind:
            continue
        timer = _decode_timer_component(encoded)
        if timer[0] != component_id:
            raise ValidationError("timer component key mismatch")
        timers[component_id] = timer
    return timers


def _decode_action_component(encoded: str) -> DurableAction:
    reader = _decode_component(encoded, "action-invocation-v1:")
    if reader.integer() != 2:
        raise ValidationError("unsupported action invocation component")
    action = DurableAction(
        _digest(reader.string(), "action ID"),
        _protocol_component_id(reader.string(), "action automation ID"),
        _protocol_component_id(reader.string(), "action intervention ID"),
        reader.long(),
        reader.nullable(reader.long),
        reader.long(),
        _digest(reader.string(), "action condition digest"),
        _unsigned_component(reader.string(), "action generation", minimum=1),
        reader.research_time(),
        reader.nullable(reader.research_time),
        _enum(reader.string(), _ACTION_STATES, "action state"),
        reader.nullable(reader.string),
    )
    if reader.remaining:
        raise ValidationError("trailing action invocation component bytes")
    if action.causal_sequence <= 0 or action.expires_at_utc_millis < 0:
        raise ValidationError("invalid durable action causal time")
    if (
        action.logical_deadline_utc_millis is not None
        and action.logical_deadline_utc_millis < 0
    ):
        raise ValidationError("invalid durable action logical deadline")
    if (action.state == "FAILED") != (action.failure_reason is not None):
        raise ValidationError("durable action failure reason is inconsistent")
    if action.opened_at is not None and action.state not in {
        "OPENED",
        "SUCCEEDED",
        "FAILED",
    }:
        raise ValidationError("durable action open time is inconsistent")
    if action.failure_reason is not None and not re.fullmatch(
        r"[A-Z][A-Z0-9_]{2,63}", action.failure_reason
    ):
        raise ValidationError("invalid durable action failure reason")
    return action


def _decode_upload_acknowledgement(encoded: str) -> UploadAcknowledgement:
    reader = _decode_component(encoded, "upload-acknowledgement-v1:")
    if reader.integer() != 1:
        raise ValidationError("unsupported upload acknowledgement component")
    acknowledgement = UploadAcknowledgement(
        _uuid4(reader.string(), "upload bundle ID"),
        reader.long(),
        reader.long(),
        _digest(reader.string(), "upload bundle digest"),
        reader.research_time(),
    )
    if reader.remaining:
        raise ValidationError("trailing upload acknowledgement component bytes")
    if (
        acknowledgement.first_commit <= 0
        or acknowledgement.through_commit < acknowledgement.first_commit
    ):
        raise ValidationError("invalid upload acknowledgement commit range")
    return acknowledgement


def _decode_resource_component(encoded: str) -> AppliedResource:
    reader = _decode_component(encoded, "applied-resource-v1:")
    if reader.integer() != 1:
        raise ValidationError("unsupported applied resource component")
    kind = _enum(reader.string(), {"ACTUATOR", "COLLECTOR"}, "resource kind").lower()
    source_id = _source_id(reader.string())
    generation = _unsigned_component(
        reader.string(), "resource generation", minimum=1
    )
    profile_id = reader.nullable(reader.string)
    applied_digest = reader.nullable(reader.string)
    status = _enum(reader.string(), _RESOURCE_STATUSES, "resource status")
    failure_reason = reader.nullable(reader.string)
    if reader.remaining:
        raise ValidationError("trailing applied resource component bytes")
    if profile_id is not None:
        _protocol_component_id(profile_id, "resource profile ID")
    if applied_digest is not None:
        _digest(applied_digest, "applied profile digest")
    if failure_reason is not None and not re.fullmatch(
        r"[A-Z][A-Z0-9_]{2,63}", failure_reason
    ):
        raise ValidationError("invalid resource failure reason")
    if status == "APPLIED" and not (
        profile_id is not None
        and applied_digest is not None
        and failure_reason is None
    ):
        raise ValidationError("applied resource receipt is incomplete")
    if status == "INACTIVE" and any(
        item is not None for item in (profile_id, applied_digest, failure_reason)
    ):
        raise ValidationError("inactive resource retains applied evidence")
    if status == "OPTIONAL_FAILED" and not (
        profile_id is not None
        and applied_digest is None
        and failure_reason is not None
    ):
        raise ValidationError("optional failure receipt is incomplete")
    return AppliedResource(
        kind,
        source_id,
        generation,
        profile_id,
        applied_digest,
        status,
        failure_reason,
    )


def _decode_resource_cleanup_component(encoded: str) -> ResourceCleanup:
    reader = _decode_component(encoded, "resource-cleanup-v1:")
    if reader.integer() != 1:
        raise ValidationError("unsupported resource cleanup component")
    kind = _enum(
        reader.string(), {"ACTUATOR", "COLLECTOR"}, "cleanup resource kind"
    ).lower()
    source_id = _source_id(reader.string())
    generation = _unsigned_component(
        reader.string(), "cleanup resource generation", minimum=1
    )
    profile_id = _protocol_component_id(
        reader.string(), "cleanup resource profile ID"
    )
    expected_digest = _digest(
        reader.string(), "cleanup expected profile digest"
    )
    if reader.remaining:
        raise ValidationError("trailing resource cleanup component bytes")
    return ResourceCleanup(
        kind,
        source_id,
        generation,
        profile_id,
        expected_digest,
    )


def _resource_vector(value: Any) -> tuple[AppliedResource, ...]:
    root = exact_object(value, {"resources"}, "applied resource vector")
    resources = _array(root["resources"], "applied resources")
    if len(resources) > 64:
        raise ValidationError("applied resource vector exceeds 64 entries")
    decoded = []
    for item in resources:
        resource = exact_object(
            item,
            {
                "applied_profile_sha256",
                "desired_generation",
                "failure_reason",
                "id",
                "kind",
                "profile_id",
                "status",
            },
            "applied resource",
        )
        kind = _enum(resource["kind"], {"actuator", "collector"}, "resource kind")
        source_id = _source_id(resource["id"])
        generation = _unsigned_component(
            resource["desired_generation"], "resource generation", minimum=1
        )
        profile_id = resource["profile_id"]
        applied_digest = resource["applied_profile_sha256"]
        failure_reason = resource["failure_reason"]
        if profile_id is not None:
            _protocol_component_id(profile_id, "resource profile ID")
        if applied_digest is not None:
            _digest(applied_digest, "applied profile digest")
        status = _enum(resource["status"], _RESOURCE_STATUSES, "resource status")
        if failure_reason is not None and (
            not isinstance(failure_reason, str)
            or not re.fullmatch(r"[A-Z][A-Z0-9_]{2,63}", failure_reason)
        ):
            raise ValidationError("invalid resource failure reason")
        decoded.append(
            AppliedResource(
                kind,
                source_id,
                generation,
                profile_id,
                applied_digest,
                status,
                failure_reason,
            )
        )
    keys = [item.key for item in decoded]
    if keys != sorted(set(keys)):
        raise ValidationError("applied resource vector is not sorted and unique")
    # Reuse the exact status invariant enforced for durable resource components.
    for item in decoded:
        if item.status == "APPLIED" and not (
            item.profile_id is not None
            and item.applied_profile_sha256 is not None
            and item.failure_reason is None
        ):
            raise ValidationError("applied resource vector receipt is incomplete")
        if item.status == "INACTIVE" and any(
            value is not None
            for value in (
                item.profile_id,
                item.applied_profile_sha256,
                item.failure_reason,
            )
        ):
            raise ValidationError("inactive vector resource retains evidence")
        if item.status == "OPTIONAL_FAILED" and not (
            item.profile_id is not None
            and item.applied_profile_sha256 is None
            and item.failure_reason is not None
        ):
            raise ValidationError("optional vector failure is incomplete")
    return tuple(decoded)


def _signed_resource_profiles(
    configuration: Mapping[str, Any],
) -> tuple[dict[tuple[str, str], dict[str, str]], set[tuple[str, str]]]:
    profiles: dict[tuple[str, str], dict[str, str]] = {}
    required: set[tuple[str, str]] = set()
    for collector in configuration["collectors"]:
        key = ("collector", collector["id"])
        profiles[key] = {
            profile["id"]: hashlib.sha256(
                canonicalize(profile["config"])
            ).hexdigest()
            for profile in collector["profiles"]
        }
        if collector["required"]:
            required.add(key)
    traffic = configuration["traffic_shaping"]
    if traffic:
        key = ("actuator", "traffic-shaping.v1")
        profiles[key] = {
            profile["id"]: hashlib.sha256(canonicalize(profile)).hexdigest()
            for profile in traffic["profiles"]
        }
        required.add(key)
    return profiles, required


def _validate_checkpoint(
    checkpoint: Mapping[str, Any],
    automation_ids: set[str],
    resource_profiles: Mapping[tuple[str, str], Mapping[str, str]],
) -> None:
    if checkpoint["evaluated_through_sequence"] < 0:
        raise ValidationError("negative automation reducer cursor")
    if checkpoint["lifecycle"] not in _SESSION_STATES:
        raise ValidationError("invalid automation lifecycle")
    start = checkpoint["study_start_utc_millis"]
    if start is not None and start < 0:
        raise ValidationError("invalid durable study start")
    if checkpoint["lifecycle"] != "READY" and start is None:
        raise ValidationError("started automation lifecycle has no durable start")
    active = checkpoint["last_active_elapsed_nanos"]
    calendar = checkpoint["last_calendar_elapsed_nanos"]
    if active < 0 or calendar < active:
        raise ValidationError("invalid automation reducer clocks")

    def state_key(value: Any) -> bool:
        return isinstance(value, str) and 1 <= len(value) <= 512 and "\0" not in value

    for name in (
        "latch_values",
        "presence_keys",
        "held_since_nanos",
        "prior_condition_values",
        "windows",
        "sequences",
        "timer_generations",
    ):
        if any(not state_key(key) for key in checkpoint[name]):
            raise ValidationError("invalid automation checkpoint state key")
    for values in checkpoint["presence_keys"].values():
        if len(values) > 256 or values != sorted(set(values)):
            raise ValidationError("invalid keyed-presence checkpoint state")
    if any(value < 0 for value in checkpoint["held_since_nanos"].values()):
        raise ValidationError("invalid held-condition checkpoint state")
    for entries in checkpoint["windows"].values():
        sequences = [entry[0] for entry in entries]
        if (
            len(entries) > 4096
            or sequences != sorted(set(sequences))
            or any(
                entry[0] <= 0
                or entry[1] < 0
                or not entry[2]
                or not re.fullmatch(r"(?:0|-?[1-9][0-9]*)", entry[3])
                for entry in entries
            )
        ):
            raise ValidationError("invalid retained window checkpoint state")
    for partials in checkpoint["sequences"].values():
        if len(partials) > 4096 or any(
            item[0] <= 0
            or item[1] <= 0
            or item[2] < item[1]
            or item[3] < 0
            or not item[4]
            for item in partials
        ):
            raise ValidationError("invalid retained sequence checkpoint state")
    counts = checkpoint["activation_counts"]
    if (
        any(key not in automation_ids or not 0 <= value <= 512 for key, value in counts.items())
        or sum(counts.values()) > 512
    ):
        raise ValidationError("invalid activation-count checkpoint state")
    if any(
        key not in automation_ids or value[0] < 0 or value[1] < value[0]
        for key, value in checkpoint["cooldown_marks"].items()
    ):
        raise ValidationError("invalid cooldown checkpoint state")
    desired = checkpoint["desired_resources"]
    if {(kind.lower(), source_id) for kind, source_id in desired} != set(
        resource_profiles
    ):
        raise ValidationError("automation checkpoint has an incomplete desired resource vector")
    for (kind, source_id), (generation, profile_id) in desired.items():
        if kind not in {"ACTUATOR", "COLLECTOR"}:
            raise ValidationError("invalid desired resource kind")
        _unsigned_component(generation, "desired resource generation", minimum=1)
        if profile_id is not None:
            _protocol_component_id(profile_id, "desired resource profile ID")
            if profile_id not in resource_profiles[(kind.lower(), source_id)]:
                raise ValidationError("desired resource profile was not signed")
    if len(checkpoint["timers"]) > 512:
        raise ValidationError("automation checkpoint exceeds timer bound")
    for key, timer in checkpoint["timers"].items():
        _validate_timer(timer)
        if key != timer[0] or timer[1] not in automation_ids:
            raise ValidationError("invalid durable timer checkpoint entry")
    for value in checkpoint["timer_generations"].values():
        _unsigned_component(value, "timer generation", minimum=1)
    materialized = checkpoint["materialized_timers"]
    if (
        any(key not in automation_ids for key in materialized)
        or sum(map(len, materialized.values())) > 512
    ):
        raise ValidationError("invalid materialized timer checkpoint state")
    for summaries in materialized.values():
        keys = [item[0] for item in summaries]
        if len(keys) != len(set(keys)) or any(
            not 1 <= len(item[0]) <= 160 or item[1] < 0 for item in summaries
        ):
            raise ValidationError("invalid materialized timer summary")


def _validate_timer(timer: tuple[Any, ...]) -> None:
    _digest(timer[0], "timer ID")
    _protocol_component_id(timer[1], "timer automation ID")
    _unsigned_component(timer[2], "timer generation", minimum=1)
    if timer[3] <= 0 or not 1 <= len(timer[4]) <= 160:
        raise ValidationError("invalid durable timer cause or producer key")
    target = timer[5]
    if target[0] == "calendar" and target[1] < 0:
        raise ValidationError("invalid calendar timer target")
    if target[0] == "active" and target[1] < 0:
        raise ValidationError("invalid active timer target")
    if target[0] == "monotonic" and (
        not _BOOT_ID.fullmatch(target[1]) or target[2] < 0
    ):
        raise ValidationError("invalid monotonic timer target")
    if any(value is not None and value < 0 for value in timer[6:8]):
        raise ValidationError("invalid durable timer deadline")


def _protocol_component_id(value: Any, name: str) -> str:
    value = _string(value, name)
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{2,63}", value):
        raise ValidationError(f"invalid {name}")
    return value


def _unsigned_component(value: Any, name: str, *, minimum: int = 0) -> int:
    value = _string(value, name)
    if not re.fullmatch(r"(?:0|[1-9][0-9]*)", value):
        raise ValidationError(f"invalid {name}")
    number = int(value)
    if not minimum <= number < 2**64:
        raise ValidationError(f"{name} is outside uint64")
    return number


def decode_automation_checkpoint(encoded: str) -> dict[str, Any]:
    reader = _decode_component(encoded, "automation-checkpoint-v1:")
    if reader.integer() != 1:
        raise ValidationError("unsupported automation checkpoint version")
    checkpoint: dict[str, Any] = {
        "evaluated_through_sequence": reader.long(), "lifecycle": reader.string(),
        "study_start_utc_millis": reader.nullable(reader.long),
        "last_active_elapsed_nanos": reader.long(), "last_calendar_elapsed_nanos": reader.long(),
        "latch_values": reader.mapping(reader.string, reader.boolean),
        "presence_keys": reader.mapping(reader.string, lambda: reader.list(reader.string)),
        "held_since_nanos": reader.mapping(reader.string, reader.long),
        "prior_condition_values": reader.mapping(reader.string, reader.boolean),
        "windows": reader.mapping(reader.string, lambda: reader.list(lambda: (reader.long(), reader.long(), reader.string(), reader.string()))),
        "sequences": reader.mapping(reader.string, lambda: reader.list(lambda: (reader.integer(), reader.long(), reader.long(), reader.long(), reader.string()))),
        "activation_counts": reader.mapping(reader.string, reader.integer),
        "cooldown_marks": reader.mapping(reader.string, lambda: (reader.long(), reader.long())),
        "desired_resources": reader.mapping(lambda: (reader.string(), reader.string()), lambda: (reader.string(), reader.nullable(reader.string))),
        "timers": reader.mapping(reader.string, reader.timer),
        "timer_generations": reader.mapping(reader.string, reader.string),
        "materialized_timers": reader.mapping(reader.string, lambda: reader.list(lambda: (reader.string(), reader.long(), reader.boolean()))),
    }
    if reader.remaining:
        raise ValidationError("trailing automation checkpoint bytes")
    return checkpoint


class _ComponentReader:
    def __init__(self, data: bytes): self.stream = io.BytesIO(data)
    @property
    def remaining(self) -> bytes: return self.stream.read()
    def _read(self, count: int) -> bytes:
        value = self.stream.read(count)
        if len(value) != count: raise ValidationError("truncated runtime component")
        return value
    def integer(self) -> int: return struct.unpack(">i", self._read(4))[0]
    def long(self) -> int: return struct.unpack(">q", self._read(8))[0]
    def boolean(self) -> bool:
        value=self._read(1)
        if value not in {b"\0",b"\1"}: raise ValidationError("invalid component boolean")
        return value==b"\1"
    def string(self) -> str:
        size=self.integer()
        if not 0<=size<=512*1024: raise ValidationError("invalid component string size")
        try: return self._read(size).decode()
        except UnicodeDecodeError as error: raise ValidationError("invalid component UTF-8") from error
    def nullable(self, read): return read() if self.boolean() else None
    def research_time(self) -> ResearchTime:
        time = ResearchTime(self.long(), self.long(), self.string())
        if (
            time.wall_time_utc_millis < 0
            or time.elapsed_realtime_nanos < 0
            or not _BOOT_ID.fullmatch(time.boot_session_id)
        ):
            raise ValidationError("invalid component research time")
        return time
    def list(self, read):
        size=self.integer()
        if not 0<=size<=4096: raise ValidationError("invalid component collection size")
        return [read() for _ in range(size)]
    def mapping(self, read_key, read_value):
        items=self.list(lambda:(read_key(),read_value()))
        if len({key for key,_ in items})!=len(items): raise ValidationError("duplicate component map key")
        return dict(items)
    def timer(self):
        timer=(self.string(),self.string(),self.string(),self.long(),self.string())
        kind=self._read(1)[0]
        if kind==0: target=("calendar",self.long())
        elif kind==1: target=("active",self.long())
        elif kind==2: target=("monotonic",self.string(),self.long())
        else: raise ValidationError("unknown timer target component")
        return (*timer,target,self.nullable(self.long),self.nullable(self.long))


def automation_checkpoint_digest(checkpoint: Mapping[str, Any]) -> str:
    components = [
        f"evaluated={checkpoint['evaluated_through_sequence']}",
        f"lifecycle={checkpoint['lifecycle']}",
        f"start={checkpoint['study_start_utc_millis'] if checkpoint['study_start_utc_millis'] is not None else ''}",
        f"active={checkpoint['last_active_elapsed_nanos']}",
        f"calendar={checkpoint['last_calendar_elapsed_nanos']}",
    ]
    for key,value in sorted(checkpoint["latch_values"].items()): components.append(f"latch:{_escape(key)}={str(value).lower()}")
    for key,values in sorted(checkpoint["presence_keys"].items()):
        for value in sorted(values): components.append(f"presence:{_escape(key)}:{_escape(value)}")
    for key,value in sorted(checkpoint["held_since_nanos"].items()): components.append(f"held:{_escape(key)}={value}")
    for key,value in sorted(checkpoint["prior_condition_values"].items()): components.append(f"prior:{_escape(key)}={str(value).lower()}")
    for key,values in sorted(checkpoint["windows"].items()):
        for value in values: components.append(f"window:{_escape(key)}:{value[0]}:{value[1]}:{_escape(value[2])}:{value[3]}")
    for key,values in sorted(checkpoint["sequences"].items()):
        for value in values: components.append(f"sequence:{_escape(key)}:{value[0]}:{value[1]}:{value[2]}:{value[3]}:{_escape(value[4])}")
    for key,value in sorted(checkpoint["activation_counts"].items()): components.append(f"activation:{_escape(key)}={value}")
    for key,value in sorted(checkpoint["cooldown_marks"].items()): components.append(f"cooldown:{_escape(key)}:{value[0]}:{value[1]}")
    for key,value in sorted(checkpoint["desired_resources"].items(),key=lambda item:(_RESOURCE_ORDER[item[0][0]],item[0][1])):
        components.append(f"resource:{key[0]}:{_escape(key[1])}:{value[0]}:{_escape(value[1] or '')}")
    for _,timer in sorted(checkpoint["timers"].items()): components.append(_timer_component(timer))
    for key,value in sorted(checkpoint["timer_generations"].items()): components.append(f"timer-generation:{_escape(key)}:{value}")
    for key,values in sorted(checkpoint["materialized_timers"].items()):
        for value in values: components.append(f"materialized:{_escape(key)}:{_escape(value[0])}:{value[1]}:{str(value[2]).lower()}")
    return _deterministic_digest("particeps-automation-checkpoint-v1", *components)


def _timer_component(timer):
    target=timer[5]
    if target[0]=="calendar": rendered=f"calendar:{target[1]}"
    elif target[0]=="active": rendered=f"active:{target[1]}"
    else: rendered=f"monotonic:{_escape(target[1])}:{target[2]}"
    logical = timer[6] if timer[6] is not None else ""
    expiry = timer[7] if timer[7] is not None else ""
    return f"timer:{timer[0]}:{_escape(timer[1])}:{timer[2]}:{timer[3]}:{_escape(timer[4])}:{rendered}:{logical}:{expiry}"


def _escape(value: str) -> str: return value.replace("%","%25").replace("\0","%00").replace(":","%3a").replace("=","%3d")
def _deterministic_digest(domain: str,*parts: str)->str: return hashlib.sha256("\0".join((domain,*parts)).encode()).hexdigest()


def _time(value: Any) -> ResearchTime:
    root=exact_object(value,{"wall_time_utc_millis","elapsed_realtime_nanos","boot_session_id"},"research time")
    boot=_string(root["boot_session_id"],"boot session")
    if not _BOOT_ID.fullmatch(boot): raise ValidationError("invalid boot session ID")
    return ResearchTime(_long(root["wall_time_utc_millis"],"wall time",minimum=0),_long(root["elapsed_realtime_nanos"],"monotonic time",minimum=0),boot)


def _embedded_time(value: str) -> ResearchTime:
    root = exact_object(
        parse_embedded_json(value),
        {"boot_session_id", "monotonic_time_nanos", "wall_time_utc_millis"},
        "embedded research time",
    )
    if canonicalize(root).decode() != value:
        raise ValidationError("embedded research time is not canonical")
    boot = _string(root["boot_session_id"], "embedded boot session")
    if not _BOOT_ID.fullmatch(boot):
        raise ValidationError("invalid embedded boot session ID")
    return ResearchTime(
        _long(root["wall_time_utc_millis"], "embedded wall time", minimum=0),
        _long(root["monotonic_time_nanos"], "embedded monotonic time", minimum=0),
        boot,
    )


def _coverage(value: Any) -> SourceCoverage:
    root=exact_object(value,{"clock_basis","start_inclusive","end_exclusive"},"source coverage")
    start=_string(root["start_inclusive"],"coverage start"); end=_string(root["end_exclusive"],"coverage end")
    if not start or not end or len(start)>160 or len(end)>160: raise ValidationError("invalid coverage coordinates")
    return SourceCoverage(_enum(root["clock_basis"],{"OBSERVED_RESEARCH_TIME","SOURCE_WALL_TIME","SOURCE_MONOTONIC_TIME"},"coverage clock"),start,end)


def _coverage_coordinate(value: str) -> int:
    return _long(value, "coverage coordinate", minimum=0)


def _time_order(left: ResearchTime, right: ResearchTime) -> int:
    if left.boot_session_id == right.boot_session_id:
        return (left.elapsed_realtime_nanos > right.elapsed_realtime_nanos) - (
            left.elapsed_realtime_nanos < right.elapsed_realtime_nanos
        )
    return (left.wall_time_utc_millis > right.wall_time_utc_millis) - (
        left.wall_time_utc_millis < right.wall_time_utc_millis
    )


def _clock(value: Any) -> Mapping[str,Any]:
    root=exact_object(value,{"calendar_elapsed_nanos","active_running_elapsed_nanos","anchor","deadline_utc_millis","deadline_utc_trusted","zone_id"},"clock checkpoint")
    calendar=_long(root["calendar_elapsed_nanos"],"calendar elapsed",minimum=0); active=_long(root["active_running_elapsed_nanos"],"active elapsed",minimum=0)
    if active>calendar: raise ValidationError("active time exceeds calendar time")
    trusted=root["deadline_utc_trusted"]
    if not isinstance(trusted,bool): raise ValidationError("deadline trust must be boolean")
    zone_id = _canonical_zone(root["zone_id"])
    return {"calendar_elapsed_nanos":calendar,"active_running_elapsed_nanos":active,"anchor":_time(root["anchor"]),"deadline_utc_millis":_long(root["deadline_utc_millis"],"deadline",minimum=0),"deadline_utc_trusted":trusted,"zone_id":zone_id}


def _canonical_zone(value: Any) -> str:
    if not isinstance(value, str) or (value != "UTC" and "/" not in value):
        raise ValidationError("clock checkpoint zone is not a canonical IANA ID")
    try:
        zone = ZoneInfo(value)
    except (ZoneInfoNotFoundError, ValueError) as error:
        raise ValidationError("clock checkpoint zone is unknown") from error
    if zone.key != value:
        raise ValidationError("clock checkpoint zone is not canonical")
    return value


def _epoch(value: Any)->ConditionEpoch:
    root=exact_object(value,{"id","configuration_sha256","applied_resource_vector_sha256","activated_at"},"condition epoch")
    return ConditionEpoch(_uuid4(root["id"],"condition epoch"),_digest(root["configuration_sha256"],"configuration digest"),_digest(root["applied_resource_vector_sha256"],"resource vector digest"),_time(root["activated_at"]))


def _long(value:Any,name:str,*,minimum:int=-(2**63),maximum:int=2**63-1)->int:
    if not isinstance(value,str) or not re.fullmatch(r"(?:0|-?[1-9][0-9]*)",value): raise ValidationError(f"{name} must be a canonical decimal string")
    parsed=int(value)
    if not minimum<=parsed<=maximum: raise ValidationError(f"{name} is outside int64 bounds")
    return parsed


def _nullable_long(value:Any,name:str,*,minimum:int=-(2**63))->int|None: return None if value is None else _long(value,name,minimum=minimum)
def _int(value:Any,name:str,*,minimum:int=-(2**31),maximum:int=2**31-1)->int:
    if isinstance(value,bool) or not isinstance(value,int) or not minimum<=value<=maximum: raise ValidationError(f"{name} must be an int32")
    return value
def _string(value:Any,name:str)->str:
    if not isinstance(value,str): raise ValidationError(f"{name} must be a string")
    return value
def _enum(value:Any,allowed:set[str],name:str)->str:
    value=_string(value,name)
    if value not in allowed: raise ValidationError(f"invalid {name}")
    return value
def _digest(value:Any,name:str,allow_genesis:bool=False)->str:
    value=_string(value,name)
    if not _SHA256.fullmatch(value) or (not allow_genesis and value==GENESIS_DIGEST): raise ValidationError(f"invalid {name}")
    return value
def _nullable_digest(value:Any,name:str)->str|None: return None if value is None else _digest(value,name)
def _source_id(value:Any)->str:
    value=_string(value,"source ID")
    if not _SOURCE_ID.fullmatch(value): raise ValidationError("invalid source ID")
    return value
def _event_type(value:Any)->str:
    value=_string(value,"event type")
    if not _EVENT_TYPE.fullmatch(value): raise ValidationError("invalid event type")
    return value
def _uuid4(value:Any,name:str)->str:
    value=_string(value,name)
    try: parsed=uuid.UUID(value)
    except ValueError as error: raise ValidationError(f"invalid {name}") from error
    if str(parsed)!=value or parsed.version!=4 or parsed.variant!=uuid.RFC_4122: raise ValidationError(f"invalid {name}")
    return value
def _nullable_uuid4(value:Any,name:str)->str|None: return None if value is None else _uuid4(value,name)
def _array(value:Any,name:str)->list[Any]:
    if not isinstance(value,list): raise ValidationError(f"{name} must be an array")
    return value
def _pairwise(values): return pairwise(values)


_COMPONENT_ORDER = {
    "AUTOMATION_CHECKPOINT": 0,
    "TIMER": 1,
    "STUDY_DEADLINE_TIMER": 2,
    "RESOURCE_AUDIT_TIMER": 3,
    "ACTION_INVOCATION": 4,
    "UPLOAD_ACKNOWLEDGEMENT": 5,
    "RESOURCE": 6,
    "RESOURCE_CLEANUP": 7,
}
_RESOURCE_ORDER={"ACTUATOR":0,"COLLECTOR":1}
