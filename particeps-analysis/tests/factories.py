from __future__ import annotations

import base64
import copy
import hashlib
from pathlib import Path
from typing import Any

from particeps_analysis.engine import (
    GENESIS_DIGEST,
    EngineCommit,
    EngineCommitParser,
    ResearchTime,
    RuntimeMutation,
    _CanonicalWriter,
    _observation_digest,
    automation_checkpoint_digest,
    calculate_commit_sha256,
    decode_automation_checkpoint,
)
from particeps_analysis.jcs import canonicalize
from particeps_analysis.registry import EventSourceRegistry

CONFIGURATION_SHA256 = "a" * 64


def key_text(byte: int = 1) -> str:
    return base64.urlsafe_b64encode(bytes([byte]) * 32).rstrip(b"=").decode()


def configuration() -> dict:
    return {
        "assigned_participant_id": None,
        "automations": [],
        "collectors": [],
        "configuration_id": "config-one",
        "consent": {"document_version": "one", "summary": "Consent summary"},
        "duration_hours": 1,
        "expires_at": "2026-01-02T00:00:00Z",
        "export": {"hpke_public_key": key_text(2), "researcher_key_id": "researcher-one"},
        "experiment_id": "study-one",
        "interventions": [],
        "issued_at": "2026-01-01T00:00:00Z",
        "minimum_client_version": "1",
        "platform": "android",
        "purpose": "Purpose",
        "researcher": {"contact": "research@example.test", "name": "Researcher"},
        "schema_version": 1,
        "signer": {"key_id": "signer-one", "public_key": key_text(3)},
        "storage": {"maximum_local_bytes": 8 << 20},
        "surveys": [],
        "title": "Study",
        "traffic_shaping": {},
        "upload": {},
    }


def checkpoint_component(
    *,
    evaluated: int = 0,
    lifecycle: str = "READY",
    study_start_utc_millis: int | None = None,
    active_elapsed_nanos: int = 0,
    calendar_elapsed_nanos: int = 0,
    desired_resources: tuple[tuple[str, str, int, str | None], ...] = (),
) -> tuple[str, str]:
    writer = _CanonicalWriter()
    writer.integer(1)
    writer.long(evaluated)
    writer.string(lifecycle)
    writer.nullable(study_start_utc_millis, writer.long)
    writer.long(active_elapsed_nanos)
    writer.long(calendar_elapsed_nanos)
    for _ in range(8):
        writer.integer(0)
    writer.integer(len(desired_resources))
    for kind, source_id, generation, profile_id in desired_resources:
        writer.string(kind)
        writer.string(source_id)
        writer.string(str(generation))
        writer.nullable(profile_id, writer.string)
    for _ in range(3):
        writer.integer(0)
    encoded = "automation-checkpoint-v1:" + base64.urlsafe_b64encode(
        writer.bytes()
    ).rstrip(b"=").decode()
    return encoded, automation_checkpoint_digest(decode_automation_checkpoint(encoded))


def empty_commit_document(sequence: int = 1, previous: str = GENESIS_DIGEST) -> dict:
    component, checkpoint_digest = checkpoint_component()
    projection = {
        "active_condition_epoch": None,
        "clock_checkpoint": None,
        "evaluated_through_commit": str(sequence),
        "lifetime_data_event_count": "0",
        "next_commit_sequence": str(sequence + 1),
        "next_event_sequence": "1",
        "next_observation_sequence": "1",
        "retained_from_commit": "1",
        "revision": str(sequence),
        "source_checkpoints": {},
        "state": "READY",
        "uploaded_through_commit": "0",
    }
    document = {
        "commit_sequence": str(sequence),
        "previous_commit_sha256": previous,
        "input_kind": "LIFECYCLE_COMMAND",
        "consumed_pending_input_sha256": None,
        "source_observations": [],
        "events": [],
        "mutations": [{
            "component_kind": "AUTOMATION_CHECKPOINT",
            "component_id": "main",
            "operation": "UPSERT",
            "canonical_value": component,
        }],
        "committed_at": {
            "wall_time_utc_millis": "1",
            "elapsed_realtime_nanos": "1",
            "boot_session_id": "boot-one",
        },
        "successor_projection": projection,
        "resulting_checkpoint_sha256": checkpoint_digest,
        "commit_sha256": "f" * 64,
    }
    parser = EngineCommitParser(EventSourceRegistry())
    partial: EngineCommit = object.__new__(EngineCommit)
    values = {
        "commit_sequence": sequence,
        "previous_commit_sha256": previous,
        "input_kind": "LIFECYCLE_COMMAND",
        "consumed_pending_input_sha256": None,
        "source_observations": (),
        "events": (),
        "mutations": (RuntimeMutation("AUTOMATION_CHECKPOINT", "main", "UPSERT", component),),
        "committed_at": ResearchTime(1, 1, "boot-one"),
        "successor_projection": parser._projection(projection),
        "resulting_checkpoint_sha256": checkpoint_digest,
        "commit_sha256": "f" * 64,
        "canonical_bytes": b"",
    }
    for name, value in values.items():
        object.__setattr__(partial, name, value)
    document["commit_sha256"] = calculate_commit_sha256(partial)
    return document


def parse_empty_commit() -> EngineCommit:
    return EngineCommitParser(EventSourceRegistry()).parse(empty_commit_document())


def inventory_source(path: Path):
    from particeps_analysis.models import InventoryObject

    path.write_bytes(b"ciphertext")
    return InventoryObject(path.as_uri(), "b" * 64, 10, path, None)


def canonical_configuration_sha256(value: dict) -> str:
    return hashlib.sha256(canonicalize(value)).hexdigest()


def research_time(wall: int, monotonic: int, boot: str = "boot-one") -> dict:
    return {
        "boot_session_id": boot,
        "elapsed_realtime_nanos": str(monotonic),
        "wall_time_utc_millis": str(wall),
    }


def embedded_time(wall: int, monotonic: int, boot: str = "boot-one") -> str:
    return canonicalize(
        {
            "boot_session_id": boot,
            "monotonic_time_nanos": str(monotonic),
            "wall_time_utc_millis": str(wall),
        }
    ).decode()


def event_document(
    sequence: int,
    source_id: str,
    event_type: str,
    fields: dict[str, str],
    *,
    epoch_id: str | None,
    wall: int,
    monotonic: int,
) -> dict:
    return {
        "condition_epoch_id": epoch_id,
        "event_type": event_type,
        "fields": fields,
        "observed_time": research_time(wall, monotonic),
        "schema_version": 1,
        "sequence_number": str(sequence),
        "source_id": source_id,
    }


def resource_component(
    *,
    kind: str,
    source_id: str,
    generation: int,
    profile_id: str | None,
    profile_sha256: str | None,
    status: str,
    failure_reason: str | None = None,
) -> str:
    writer = _CanonicalWriter()
    writer.integer(1)
    writer.string(kind)
    writer.string(source_id)
    writer.string(str(generation))
    writer.nullable(profile_id, writer.string)
    writer.nullable(profile_sha256, writer.string)
    writer.string(status)
    writer.nullable(failure_reason, writer.string)
    return "applied-resource-v1:" + base64.urlsafe_b64encode(
        writer.bytes()
    ).rstrip(b"=").decode()


def resource_cleanup_component(
    *,
    kind: str,
    source_id: str,
    generation: int,
    profile_id: str,
    expected_profile_sha256: str,
) -> str:
    writer = _CanonicalWriter()
    writer.integer(1)
    writer.string(kind)
    writer.string(source_id)
    writer.string(str(generation))
    writer.string(profile_id)
    writer.string(expected_profile_sha256)
    return "resource-cleanup-v1:" + base64.urlsafe_b64encode(
        writer.bytes()
    ).rstrip(b"=").decode()


def action_component(
    *,
    action_id: str,
    automation_id: str,
    intervention_id: str,
    causal_sequence: int,
    condition_sha256: str,
    generation: int = 1,
    state: str = "READY",
    logical_deadline_utc_millis: int | None = None,
    expires_at_utc_millis: int = 10_000,
    requested_wall: int = 1_000,
    requested_monotonic: int = 10,
    opened_wall: int | None = None,
    opened_monotonic: int | None = None,
    failure_reason: str | None = None,
) -> str:
    writer = _CanonicalWriter()
    writer.integer(2)
    writer.string(action_id)
    writer.string(automation_id)
    writer.string(intervention_id)
    writer.long(causal_sequence)
    writer.nullable(logical_deadline_utc_millis, writer.long)
    writer.long(expires_at_utc_millis)
    writer.string(condition_sha256)
    writer.string(str(generation))
    writer.long(requested_wall)
    writer.long(requested_monotonic)
    writer.string("boot-one")
    writer.boolean(opened_wall is not None)
    if opened_wall is not None:
        writer.long(opened_wall)
        writer.long(opened_monotonic if opened_monotonic is not None else requested_monotonic)
        writer.string("boot-one")
    writer.string(state)
    writer.nullable(failure_reason, writer.string)
    return "action-invocation-v1:" + base64.urlsafe_b64encode(
        writer.bytes()
    ).rstrip(b"=").decode()


def timer_component(
    *,
    timer_id: str,
    automation_id: str,
    producer_key: str,
    generation: int = 1,
    causal_sequence: int = 1,
    target_active_nanos: int = 1_000,
    target_boot: str | None = None,
    logical_deadline_utc_millis: int | None = None,
    expires_at_utc_millis: int | None = None,
) -> str:
    writer = _CanonicalWriter()
    writer.integer(1)
    writer.string(timer_id)
    writer.string(automation_id)
    writer.string(str(generation))
    writer.long(causal_sequence)
    writer.string(producer_key)
    if target_boot is None:
        writer.stream.write(b"\x01")
        writer.long(target_active_nanos)
    else:
        writer.stream.write(b"\x02")
        writer.string(target_boot)
        writer.long(target_active_nanos)
    writer.nullable(logical_deadline_utc_millis, writer.long)
    writer.nullable(expires_at_utc_millis, writer.long)
    return "durable-timer-v1:" + base64.urlsafe_b64encode(
        writer.bytes()
    ).rstrip(b"=").decode()


def study_deadline_timer_component(
    *,
    target_monotonic: int,
    logical_deadline_utc_millis: int,
) -> tuple[str, str]:
    timer_id = hashlib.sha256(
        f"particeps-study-deadline-timer-v1\0{CONFIGURATION_SHA256}"
        "\0study-duration\0study-deadline".encode()
    ).hexdigest()
    return timer_id, timer_component(
        timer_id=timer_id,
        automation_id="study-duration",
        producer_key="study-deadline",
        generation=1,
        causal_sequence=1,
        target_active_nanos=target_monotonic,
        target_boot="boot-one",
        logical_deadline_utc_millis=logical_deadline_utc_millis,
    )


def upload_acknowledgement_component(
    *,
    bundle_id: str,
    first_commit: int,
    through_commit: int,
    bundle_sha256: str,
    wall: int,
    monotonic: int,
) -> str:
    writer = _CanonicalWriter()
    writer.integer(1)
    writer.string(bundle_id)
    writer.long(first_commit)
    writer.long(through_commit)
    writer.string(bundle_sha256)
    writer.long(wall)
    writer.long(monotonic)
    writer.string("boot-one")
    return "upload-acknowledgement-v1:" + base64.urlsafe_b64encode(
        writer.bytes()
    ).rstrip(b"=").decode()


def applied_resource_vector(
    *,
    kind: str,
    source_id: str,
    generation: int,
    profile_id: str | None,
    profile_sha256: str | None,
    status: str,
    failure_reason: str | None = None,
) -> tuple[str, str]:
    text = canonicalize(
        {
            "resources": [
                {
                    "applied_profile_sha256": profile_sha256,
                    "desired_generation": str(generation),
                    "failure_reason": failure_reason,
                    "id": source_id,
                    "kind": kind.lower(),
                    "profile_id": profile_id,
                    "status": status,
                }
            ]
        }
    ).decode()
    return text, hashlib.sha256(text.encode()).hexdigest()


def observation_document(
    *,
    sequence: int,
    source_id: str,
    generation: int,
    producer_ordinal: int,
    epoch_id: str,
    events: list[dict],
    coverage: dict | None = None,
) -> dict:
    first = events[0]["sequence_number"] if events else None
    last = events[-1]["sequence_number"] if events else None
    document = {
        "admission_kind": "NORMAL",
        "condition_epoch_id": epoch_id,
        "coverage": coverage,
        "encoded_sha256": "f" * 64,
        "event_count": len(events),
        "first_event_sequence": first,
        "last_event_sequence": last,
        "observation_sequence": str(sequence),
        "producer_ordinal": str(producer_ordinal),
        "resource_generation": str(generation),
        "schema_version": 1,
        "source_id": source_id,
    }
    parser = EngineCommitParser(EventSourceRegistry())
    parsed_observation = parser._observation(document)
    parsed_events = [parser._event(event) for event in events]
    document["encoded_sha256"] = _observation_digest(
        parsed_observation, parsed_events
    )
    return document


def commit_document(
    *,
    sequence: int,
    previous: str,
    events: list[dict],
    observations: list[dict],
    state: str,
    next_event_sequence: int,
    next_observation_sequence: int,
    lifetime_data_event_count: int,
    checkpoint_evaluated: int,
    checkpoint_lifecycle: str,
    checkpoint_start: int | None,
    desired_resources: tuple[tuple[str, str, int, str | None], ...] = (),
    active_epoch: dict | None = None,
    source_checkpoints: dict[str, dict] | None = None,
    extra_mutations: list[dict[str, Any]] | None = None,
    input_kind: str = "SOURCE_OBSERVATION",
    uploaded_through_commit: int = 0,
    zone_id: str = "UTC",
    committed_wall: int | None = None,
    committed_monotonic: int | None = None,
    clock_active_elapsed_nanos: int | None = None,
    clock_calendar_elapsed_nanos: int | None = None,
    checkpoint_active_elapsed_nanos: int | None = None,
    checkpoint_calendar_elapsed_nanos: int | None = None,
) -> dict:
    events = copy.deepcopy(events)
    observations = copy.deepcopy(observations)
    wall = sequence * 1000 if committed_wall is None else committed_wall
    monotonic = sequence * 10 if committed_monotonic is None else committed_monotonic
    active_clock = monotonic if clock_active_elapsed_nanos is None else clock_active_elapsed_nanos
    calendar_clock = monotonic if clock_calendar_elapsed_nanos is None else clock_calendar_elapsed_nanos
    checkpoint_active = (
        checkpoint_evaluated * 10
        if checkpoint_active_elapsed_nanos is None
        else checkpoint_active_elapsed_nanos
    )
    checkpoint_calendar = (
        checkpoint_evaluated * 10
        if checkpoint_calendar_elapsed_nanos is None
        else checkpoint_calendar_elapsed_nanos
    )
    started = state in {"ACTIVATING", "RUNNING", "PAUSING", "PAUSED"}
    deadline_wall = (checkpoint_start or 0) + 3_600_000
    deadline_target = monotonic + max(0, 3_600_000_000_000 - calendar_clock)
    if started and sequence > 1:
        for event in events:
            event["sequence_number"] = str(int(event["sequence_number"]) + 1)
        for observation in observations:
            if observation["first_event_sequence"] is not None:
                observation["first_event_sequence"] = str(
                    int(observation["first_event_sequence"]) + 1
                )
                observation["last_event_sequence"] = str(
                    int(observation["last_event_sequence"]) + 1
                )
        next_event_sequence += 1
        parser = EngineCommitParser(EventSourceRegistry())
        parsed_events = {int(event["sequence_number"]): parser._event(event) for event in events}
        for observation in observations:
            parsed = parser._observation(observation)
            covered = [
                parsed_events[number]
                for number in range(
                    int(observation["first_event_sequence"]),
                    int(observation["last_event_sequence"]) + 1,
                )
            ] if observation["first_event_sequence"] is not None else []
            observation["encoded_sha256"] = _observation_digest(parsed, covered)
    component, checkpoint_digest = checkpoint_component(
        evaluated=checkpoint_evaluated,
        lifecycle=checkpoint_lifecycle,
        study_start_utc_millis=checkpoint_start,
        active_elapsed_nanos=max(0, checkpoint_active),
        calendar_elapsed_nanos=max(0, checkpoint_calendar),
        desired_resources=desired_resources,
    )
    mutations = [
        {
            "canonical_value": component,
            "component_id": "main",
            "component_kind": "AUTOMATION_CHECKPOINT",
            "operation": "UPSERT",
        },
        *(
            [{
                "canonical_value": study_deadline_timer_component(
                    target_monotonic=deadline_target,
                    logical_deadline_utc_millis=deadline_wall,
                )[1],
                "component_id": "study-duration",
                "component_kind": "STUDY_DEADLINE_TIMER",
                "operation": "UPSERT",
            }]
            if started
            else []
        ),
        *(extra_mutations or []),
    ]
    if started and sequence == 1:
        timer_id, _encoded = study_deadline_timer_component(
            target_monotonic=deadline_target,
            logical_deadline_utc_millis=deadline_wall,
        )
        events.append(event_document(
            next_event_sequence,
            "timer.v1",
            "TIMER_SCHEDULED",
            {
                "automation_id": "study-duration",
                "causal_sequence": "1",
                "clock": "SAME_BOOT_MONOTONIC",
                "generation": "1",
                "logical_due_research_time": embedded_time(
                    deadline_wall,
                    deadline_target,
                ),
                "producer_key": "study-deadline",
                "timer_id": timer_id,
            },
            epoch_id=None,
            wall=wall,
            monotonic=monotonic,
        ))
        next_event_sequence += 1
    component_order = {
        "AUTOMATION_CHECKPOINT": 0,
        "TIMER": 1,
        "STUDY_DEADLINE_TIMER": 2,
        "RESOURCE_AUDIT_TIMER": 3,
        "ACTION_INVOCATION": 4,
        "UPLOAD_ACKNOWLEDGEMENT": 5,
        "RESOURCE": 6,
        "RESOURCE_CLEANUP": 7,
    }
    mutations.sort(
        key=lambda item: (component_order[item["component_kind"]], item["component_id"])
    )
    projection = {
        "active_condition_epoch": active_epoch,
        "clock_checkpoint": {
            "active_running_elapsed_nanos": str(active_clock),
            "anchor": research_time(wall, monotonic),
            "calendar_elapsed_nanos": str(calendar_clock),
            "deadline_utc_millis": str(deadline_wall),
            "deadline_utc_trusted": True,
            "zone_id": zone_id,
        },
        "evaluated_through_commit": str(sequence),
        "lifetime_data_event_count": str(lifetime_data_event_count),
        "next_commit_sequence": str(sequence + 1),
        "next_event_sequence": str(next_event_sequence),
        "next_observation_sequence": str(next_observation_sequence),
        "retained_from_commit": "1",
        "revision": str(sequence),
        "source_checkpoints": source_checkpoints or {},
        "state": state,
        "uploaded_through_commit": str(uploaded_through_commit),
    }
    document = {
        "commit_sequence": str(sequence),
        "previous_commit_sha256": previous,
        "input_kind": input_kind,
        "consumed_pending_input_sha256": None,
        "source_observations": observations,
        "events": events,
        "mutations": mutations,
        "committed_at": research_time(wall, monotonic),
        "successor_projection": projection,
        "resulting_checkpoint_sha256": checkpoint_digest,
        "commit_sha256": "f" * 64,
    }
    return resign_commit(document)


def resign_commit(document: dict) -> dict:
    parser = EngineCommitParser(EventSourceRegistry())
    parsed: EngineCommit = object.__new__(EngineCommit)
    values = {
        "commit_sequence": int(document["commit_sequence"]),
        "previous_commit_sha256": document["previous_commit_sha256"],
        "input_kind": document["input_kind"],
        "consumed_pending_input_sha256": document[
            "consumed_pending_input_sha256"
        ],
        "source_observations": tuple(
            parser._observation(item) for item in document["source_observations"]
        ),
        "events": tuple(parser._event(item) for item in document["events"]),
        "mutations": tuple(parser._mutation(item) for item in document["mutations"]),
        "committed_at": ResearchTime(0, 0, "boot-one"),
        "successor_projection": parser._projection(document["successor_projection"]),
        "resulting_checkpoint_sha256": document["resulting_checkpoint_sha256"],
        "commit_sha256": "f" * 64,
        "canonical_bytes": b"",
    }
    time = document["committed_at"]
    values["committed_at"] = ResearchTime(
        int(time["wall_time_utc_millis"]),
        int(time["elapsed_realtime_nanos"]),
        time["boot_session_id"],
    )
    for name, value in values.items():
        object.__setattr__(parsed, name, value)
    document["commit_sha256"] = calculate_commit_sha256(parsed)
    return document
