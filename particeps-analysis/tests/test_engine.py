from __future__ import annotations

import copy
import hashlib
import unittest
import uuid

from factories import (
    CONFIGURATION_SHA256,
    action_component,
    applied_resource_vector,
    checkpoint_component,
    commit_document,
    configuration,
    embedded_time,
    event_document,
    observation_document,
    parse_empty_commit,
    research_time,
    resign_commit,
    resource_cleanup_component,
    resource_component,
    study_deadline_timer_component,
    timer_component,
    upload_acknowledgement_component,
)

from particeps_analysis.automation import (
    automation_checkpoint_digest as authoritative_checkpoint_digest,
)
from particeps_analysis.automation import (
    decode_automation_checkpoint as decode_authoritative_checkpoint,
)
from particeps_analysis.automation import (
    encode_automation_checkpoint as encode_authoritative_checkpoint,
)
from particeps_analysis.engine import (
    DurableAction,
    EngineCommit,
    EngineCommitParser,
    EngineReplayVerifier,
    RecordedEvent,
    ResearchTime,
    SourceObservation,
)
from particeps_analysis.errors import ValidationError
from particeps_analysis.jcs import canonicalize
from particeps_analysis.registry import EventSourceRegistry

EPOCH_ID = "2f7720d8-e530-45de-868b-15b282abbce2"
VPN_GENERATION_ID = "6745758f-9575-4ea8-bca8-d2e49b7647e1"
TRAFFIC_PROFILE = {"downlink_kbps": 1024, "id": "slow", "uplink_kbps": 256}
TRAFFIC_PROFILE_SHA256 = hashlib.sha256(canonicalize(TRAFFIC_PROFILE)).hexdigest()


def battery_configuration() -> dict:
    value = configuration()
    value["collectors"] = [
        {
            "id": "battery_state.v1",
            "profiles": [{"config": {}, "id": "continuous"}],
            "required": True,
        }
    ]
    value["automations"] = [
        {
            "cases": [
                {
                    "condition": {"type": "study_session_active"},
                    "profile_id": "continuous",
                }
            ],
            "default_profile_id": "continuous",
            "id": "bind-battery",
            "resource": {"id": "battery_state.v1", "kind": "collector"},
            "type": "resource_binding",
        }
    ]
    return value


def scheduled_action_configuration() -> dict:
    value = configuration()
    value["interventions"] = [
        {
            "action": {
                "notification_message": "Message",
                "notification_title": "Title",
                "type": "notification",
            },
            "id": "notify-one",
            "required": True,
        }
    ]
    value["automations"] = [
        {
            "availability_seconds": 300,
            "cooldown": None,
            "guard": None,
            "id": "scheduled-action",
            "intervention_id": "notify-one",
            "maximum_activations": 1,
            "trigger": {
                "schedule": {
                    "clock": "ACTIVE_RUNNING_TIME",
                    "offset_minutes": 0,
                    "type": "one_time",
                },
                "type": "schedule",
            },
            "type": "occurrence",
        }
    ]
    return value


def scheduled_survey_configuration() -> dict:
    value = scheduled_action_configuration()
    value["surveys"] = [
        {
            "description": {"default": "Description", "translations": {}},
            "id": "survey-one",
            "questions": [
                {
                    "id": "question-one",
                    "maximum_length": 100,
                    "prompt": {"default": "Prompt", "translations": {}},
                    "required": True,
                    "type": "short_text",
                }
            ],
            "title": {"default": "Survey", "translations": {}},
        }
    ]
    value["interventions"][0]["action"] = {
        "notification_message": "Please answer",
        "notification_title": "Survey",
        "survey_id": "survey-one",
        "type": "survey",
    }
    return value


def traffic_configuration() -> dict:
    value = configuration()
    value["traffic_shaping"] = {
        "profiles": [TRAFFIC_PROFILE],
        "target_packages": ["com.example.target"],
    }
    value["automations"] = [
        {
            "cases": [
                {
                    "condition": {"type": "study_session_active"},
                    "profile_id": "slow",
                }
            ],
            "default_profile_id": None,
            "id": "bind-traffic",
            "resource": {"id": "traffic-shaping.v1", "kind": "actuator"},
            "type": "resource_binding",
        }
    ]
    return value


def resource_audit_timer_id(*, target: int, causal_sequence: int = 3) -> str:
    return hashlib.sha256(
        "\0".join(
            (
                "particeps-resource-audit-timer-v1",
                CONFIGURATION_SHA256,
                "traffic_shaping.v1",
                "ACTUATOR",
                "traffic-shaping.v1",
                "1",
                "slow",
                TRAFFIC_PROFILE_SHA256,
                EPOCH_ID,
                str(causal_sequence),
                "boot-one",
                str(target),
            )
        ).encode()
    ).hexdigest()


def traffic_activation_commit(*, timer_offset_nanos: int = 60_000_000_000) -> tuple[list[dict], dict, str]:
    started_wall = 1_000
    started_monotonic = 10
    activated_wall = 2_000
    activated_monotonic = 20
    timer_target = activated_monotonic + timer_offset_nanos
    timer_id = resource_audit_timer_id(target=timer_target, causal_sequence=1)
    vector_json, vector_digest = applied_resource_vector(
        kind="ACTUATOR",
        source_id="traffic-shaping.v1",
        generation=1,
        profile_id="slow",
        profile_sha256=TRAFFIC_PROFILE_SHA256,
        status="APPLIED",
    )
    epoch = {
        "activated_at": research_time(activated_wall, activated_monotonic),
        "applied_resource_vector_sha256": vector_digest,
        "configuration_sha256": CONFIGURATION_SHA256,
        "id": EPOCH_ID,
    }
    started = event_document(
        1,
        "study_runtime.v1",
        "STUDY_STARTED",
        {
            "command_id": "b" * 64,
            "current_state": "ACTIVATING",
            "transition_reason": "STUDY_START",
        },
        epoch_id=None,
        wall=started_wall,
        monotonic=started_monotonic,
    )
    first = commit_document(
        sequence=1,
        previous="0" * 64,
        events=[started],
        observations=[],
        state="ACTIVATING",
        next_event_sequence=2,
        next_observation_sequence=1,
        lifetime_data_event_count=0,
        checkpoint_evaluated=1,
        checkpoint_lifecycle="ACTIVATING",
        checkpoint_start=started_wall,
        desired_resources=(("ACTUATOR", "traffic-shaping.v1", 1, "slow"),),
        input_kind="LIFECYCLE_COMMAND",
    )
    events = [
        event_document(
            2,
            "study_condition.v1",
            "CONDITION_EPOCH_ACTIVATED",
            {
                "activation_reason": "INITIAL_START",
                "applied_resource_vector_sha256": vector_digest,
                "boundary_research_time": embedded_time(
                    activated_wall, activated_monotonic
                ),
                "condition_epoch_id": EPOCH_ID,
                "resource_vector_json": vector_json,
                "signed_configuration_sha256": CONFIGURATION_SHA256,
            },
            epoch_id=EPOCH_ID,
            wall=activated_wall,
            monotonic=activated_monotonic,
        ),
        event_document(
            3,
            "traffic_shaping.v1",
            "TRAFFIC_SHAPING_PROFILE_APPLIED",
            {
                "activation_research_time": embedded_time(
                    activated_wall, activated_monotonic
                ),
                "applied_profile_sha256": TRAFFIC_PROFILE_SHA256,
                "condition_epoch_id": EPOCH_ID,
                "downlink_kbps": "1024",
                "profile_id": "slow",
                "resource_generation": "1",
                "signed_configuration_sha256": CONFIGURATION_SHA256,
                "target_package_list_sha256": hashlib.sha256(
                    canonicalize(["com.example.target"])
                ).hexdigest(),
                "uplink_kbps": "256",
                "verification_completed_research_time": embedded_time(
                    activated_wall, activated_monotonic
                ),
                "vpn_generation_id": VPN_GENERATION_ID,
            },
            epoch_id=EPOCH_ID,
            wall=activated_wall,
            monotonic=activated_monotonic,
        ),
        event_document(
            4,
            "timer.v1",
            "TIMER_SCHEDULED",
            {
                "automation_id": "bind-traffic",
                "causal_sequence": "1",
                "clock": "SAME_BOOT_MONOTONIC",
                "generation": "1",
                "logical_due_research_time": embedded_time(
                    activated_wall, timer_target
                ),
                "producer_key": "resource-audit:actuator:traffic-shaping.v1",
                "timer_id": timer_id,
            },
            epoch_id=EPOCH_ID,
            wall=activated_wall,
            monotonic=activated_monotonic,
        ),
        event_document(
            5,
            "study_runtime.v1",
            "STUDY_RUNNING",
            {
                "command_id": "b" * 64,
                "current_state": "RUNNING",
                "previous_state": "ACTIVATING",
                "transition_reason": "ACTIVATION_CONFIRMED",
            },
            epoch_id=EPOCH_ID,
            wall=activated_wall,
            monotonic=activated_monotonic,
        ),
    ]
    timer = timer_component(
        timer_id=timer_id,
        automation_id="bind-traffic",
        producer_key="resource-audit:actuator:traffic-shaping.v1",
        generation=1,
        causal_sequence=1,
        target_active_nanos=timer_target,
        target_boot="boot-one",
        logical_deadline_utc_millis=activated_wall + 60_000,
    )
    second = commit_document(
        sequence=2,
        previous=first["commit_sha256"],
        events=events,
        observations=[],
        state="RUNNING",
        next_event_sequence=6,
        next_observation_sequence=1,
        lifetime_data_event_count=0,
        checkpoint_evaluated=2,
        checkpoint_lifecycle="RUNNING",
        checkpoint_start=started_wall,
        desired_resources=(("ACTUATOR", "traffic-shaping.v1", 1, "slow"),),
        active_epoch=epoch,
        extra_mutations=[
            {
                "canonical_value": timer,
                "component_id": timer_id,
                "component_kind": "RESOURCE_AUDIT_TIMER",
                "operation": "UPSERT",
            },
            {
                "canonical_value": resource_component(
                    kind="ACTUATOR",
                    source_id="traffic-shaping.v1",
                    generation=1,
                    profile_id="slow",
                    profile_sha256=TRAFFIC_PROFILE_SHA256,
                    status="APPLIED",
                ),
                "component_id": "actuator:traffic-shaping.v1",
                "component_kind": "RESOURCE",
                "operation": "UPSERT",
            },
        ],
        input_kind="RESOURCE_RESULT",
    )
    return [first, second], epoch, timer_id


def traffic_due_commit(previous: dict, epoch: dict, old_timer_id: str) -> dict:
    due_wall = 62_000
    due_monotonic = 60_000_000_020
    successor_target = due_monotonic + 60_000_000_000
    successor_id = resource_audit_timer_id(target=successor_target, causal_sequence=1)
    common = {
        "condition_epoch_id": EPOCH_ID,
        "downlink_bytes": "2000",
        "downlink_packets": "20",
        "downlink_throttled_nanoseconds": "200",
        "profile_id": "slow",
        "resource_generation": "1",
        "uplink_bytes": "1000",
        "uplink_packets": "10",
        "uplink_throttled_nanoseconds": "100",
        "vpn_generation_id": VPN_GENERATION_ID,
    }
    events = [
        event_document(
            6,
            "timer.v1",
            "TIMER_DUE",
            {
                "automation_id": "bind-traffic",
                "causal_sequence": "1",
                "clock": "SAME_BOOT_MONOTONIC",
                "generation": "1",
                "logical_due_research_time": embedded_time(due_wall, due_monotonic),
                "producer_key": "resource-audit:actuator:traffic-shaping.v1",
                "timer_id": old_timer_id,
            },
            epoch_id=EPOCH_ID,
            wall=due_wall,
            monotonic=due_monotonic,
        ),
        event_document(
            7,
            "traffic_shaping.v1",
            "TRAFFIC_SHAPING_SNAPSHOT",
            common
            | {
                "logical_deadline_research_time": embedded_time(
                    due_wall, due_monotonic
                ),
                "observation_research_time": embedded_time(
                    due_wall, due_monotonic
                ),
                "snapshot_reason": "PERIODIC",
            },
            epoch_id=EPOCH_ID,
            wall=due_wall,
            monotonic=due_monotonic,
        ),
        event_document(
            8,
            "timer.v1",
            "TIMER_RETIRED",
            {
                "automation_id": "bind-traffic",
                "clock": "SAME_BOOT_MONOTONIC",
                "generation": "1",
                "logical_due_research_time": embedded_time(due_wall, due_monotonic),
                "producer_key": "resource-audit:actuator:traffic-shaping.v1",
                "retirement_reason": "FIRED",
                "timer_id": old_timer_id,
            },
            epoch_id=EPOCH_ID,
            wall=due_wall,
            monotonic=due_monotonic,
        ),
        event_document(
            9,
            "timer.v1",
            "TIMER_SCHEDULED",
            {
                "automation_id": "bind-traffic",
                "causal_sequence": "1",
                "clock": "SAME_BOOT_MONOTONIC",
                "generation": "1",
                "logical_due_research_time": embedded_time(
                    due_wall, successor_target
                ),
                "producer_key": "resource-audit:actuator:traffic-shaping.v1",
                "timer_id": successor_id,
            },
            epoch_id=EPOCH_ID,
            wall=due_wall,
            monotonic=due_monotonic,
        ),
    ]
    mutations = sorted(
        [
            {
                "canonical_value": None,
                "component_id": old_timer_id,
                "component_kind": "RESOURCE_AUDIT_TIMER",
                "operation": "REMOVE",
            },
            {
                "canonical_value": timer_component(
                    timer_id=successor_id,
                    automation_id="bind-traffic",
                    producer_key="resource-audit:actuator:traffic-shaping.v1",
                    generation=1,
                    causal_sequence=1,
                    target_active_nanos=successor_target,
                    target_boot="boot-one",
                    logical_deadline_utc_millis=due_wall + 60_000,
                ),
                "component_id": successor_id,
                "component_kind": "RESOURCE_AUDIT_TIMER",
                "operation": "UPSERT",
            },
        ],
        key=lambda item: item["component_id"],
    )
    return commit_document(
        sequence=3,
        previous=previous["commit_sha256"],
        events=events,
        observations=[],
        state="RUNNING",
        next_event_sequence=10,
        next_observation_sequence=1,
        lifetime_data_event_count=0,
        checkpoint_evaluated=2,
        checkpoint_lifecycle="RUNNING",
        checkpoint_start=1_000,
        desired_resources=(("ACTUATOR", "traffic-shaping.v1", 1, "slow"),),
        active_epoch=epoch,
        extra_mutations=mutations,
        input_kind="TIMER_WAKE",
        committed_wall=due_wall,
        committed_monotonic=due_monotonic,
        clock_active_elapsed_nanos=due_monotonic,
        clock_calendar_elapsed_nanos=due_monotonic,
    )


def traffic_boundary_commit(previous: dict, old_timer_id: str) -> dict:
    boundary_wall = 20_000
    boundary_monotonic = 20_000_000_000
    vector_json, vector_digest = applied_resource_vector(
        kind="ACTUATOR",
        source_id="traffic-shaping.v1",
        generation=1,
        profile_id="slow",
        profile_sha256=TRAFFIC_PROFILE_SHA256,
        status="APPLIED",
    )
    common = {
        "condition_epoch_id": EPOCH_ID,
        "downlink_bytes": "2000",
        "downlink_packets": "20",
        "downlink_throttled_nanoseconds": "200",
        "profile_id": "slow",
        "resource_generation": "1",
        "uplink_bytes": "1000",
        "uplink_packets": "10",
        "uplink_throttled_nanoseconds": "100",
        "vpn_generation_id": VPN_GENERATION_ID,
    }
    events = [
        event_document(
            6,
            "study_runtime.v1",
            "STUDY_SAFETY_PAUSE_REQUESTED",
            {
                "command_id": "c" * 64,
                "current_state": "PAUSING",
                "previous_state": "RUNNING",
                "transition_reason": "REQUIRED_RESOURCE_FAILURE",
            },
            epoch_id=EPOCH_ID,
            wall=boundary_wall,
            monotonic=boundary_monotonic,
        ),
        event_document(
            7,
            "traffic_shaping.v1",
            "TRAFFIC_SHAPING_SNAPSHOT",
            common
            | {
                "logical_deadline_research_time": embedded_time(
                    boundary_wall, boundary_monotonic
                ),
                "observation_research_time": embedded_time(
                    boundary_wall, boundary_monotonic
                ),
                "snapshot_reason": "EPOCH_BOUNDARY",
            },
            epoch_id=EPOCH_ID,
            wall=boundary_wall,
            monotonic=boundary_monotonic,
        ),
        event_document(
            8,
            "traffic_shaping.v1",
            "TRAFFIC_SHAPING_PROFILE_REMOVED",
            common
            | {
                "boundary_research_time": embedded_time(
                    boundary_wall, boundary_monotonic
                ),
                "removal_reason": "SYSTEM_SAFETY_PAUSE",
            },
            epoch_id=EPOCH_ID,
            wall=boundary_wall,
            monotonic=boundary_monotonic,
        ),
        event_document(
            9,
            "timer.v1",
            "TIMER_RETIRED",
            {
                "automation_id": "bind-traffic",
                "clock": "SAME_BOOT_MONOTONIC",
                "generation": "1",
                "logical_due_research_time": embedded_time(
                    boundary_wall, 60_000_000_020
                ),
                "producer_key": "resource-audit:actuator:traffic-shaping.v1",
                "retirement_reason": "LIFECYCLE_ENDED",
                "timer_id": old_timer_id,
            },
            epoch_id=EPOCH_ID,
            wall=boundary_wall,
            monotonic=boundary_monotonic,
        ),
        event_document(
            10,
            "study_condition.v1",
            "CONDITION_EPOCH_DEACTIVATED",
            {
                "applied_resource_vector_sha256": vector_digest,
                "boundary_research_time": embedded_time(
                    boundary_wall, boundary_monotonic
                ),
                "condition_epoch_id": EPOCH_ID,
                "deactivation_reason": "SAFETY_PAUSED",
                "resource_vector_json": vector_json,
                "signed_configuration_sha256": CONFIGURATION_SHA256,
            },
            epoch_id=EPOCH_ID,
            wall=boundary_wall,
            monotonic=boundary_monotonic,
        ),
        event_document(
            11,
            "study_runtime.v1",
            "STUDY_SAFETY_PAUSED",
            {
                "command_id": "c" * 64,
                "current_state": "PAUSED",
                "previous_state": "PAUSING",
                "transition_reason": "REQUIRED_RESOURCE_FAILURE",
            },
            epoch_id=EPOCH_ID,
            wall=boundary_wall,
            monotonic=boundary_monotonic,
        ),
    ]
    return commit_document(
        sequence=3,
        previous=previous["commit_sha256"],
        events=events,
        observations=[],
        state="PAUSED",
        next_event_sequence=12,
        next_observation_sequence=1,
        lifetime_data_event_count=0,
        checkpoint_evaluated=4,
        checkpoint_lifecycle="PAUSED",
        checkpoint_start=1_000,
        desired_resources=(("ACTUATOR", "traffic-shaping.v1", 2, None),),
        active_epoch=None,
        extra_mutations=[
            {
                "canonical_value": None,
                "component_id": old_timer_id,
                "component_kind": "RESOURCE_AUDIT_TIMER",
                "operation": "REMOVE",
            },
            {
                "canonical_value": resource_component(
                    kind="ACTUATOR",
                    source_id="traffic-shaping.v1",
                    generation=2,
                    profile_id=None,
                    profile_sha256=None,
                    status="INACTIVE",
                ),
                "component_id": "actuator:traffic-shaping.v1",
                "component_kind": "RESOURCE",
                "operation": "UPSERT",
            },
        ],
        input_kind="SAFETY_FAILURE",
        committed_wall=boundary_wall,
        committed_monotonic=boundary_monotonic,
        clock_active_elapsed_nanos=boundary_monotonic,
        clock_calendar_elapsed_nanos=boundary_monotonic,
        checkpoint_active_elapsed_nanos=boundary_monotonic,
        checkpoint_calendar_elapsed_nanos=boundary_monotonic,
    )


def active_chain() -> tuple[list[dict], dict]:
    profile_digest = hashlib.sha256(canonicalize({})).hexdigest()
    vector_json, vector_digest = applied_resource_vector(
        kind="COLLECTOR",
        source_id="battery_state.v1",
        generation=1,
        profile_id="continuous",
        profile_sha256=profile_digest,
        status="APPLIED",
    )
    epoch = {
        "activated_at": research_time(2_000, 20),
        "applied_resource_vector_sha256": vector_digest,
        "configuration_sha256": CONFIGURATION_SHA256,
        "id": EPOCH_ID,
    }
    started = event_document(
        1,
        "study_runtime.v1",
        "STUDY_STARTED",
        {
            "command_id": "b" * 64,
            "current_state": "ACTIVATING",
            "transition_reason": "STUDY_START",
        },
        epoch_id=None,
        wall=1_000,
        monotonic=10,
    )
    first = commit_document(
        sequence=1,
        previous="0" * 64,
        events=[started],
        observations=[],
        state="ACTIVATING",
        next_event_sequence=2,
        next_observation_sequence=1,
        lifetime_data_event_count=0,
        checkpoint_evaluated=1,
        checkpoint_lifecycle="ACTIVATING",
        checkpoint_start=1_000,
        desired_resources=(("COLLECTOR", "battery_state.v1", 1, "continuous"),),
        active_epoch=None,
        input_kind="LIFECYCLE_COMMAND",
    )
    activation = event_document(
        2,
        "study_condition.v1",
        "CONDITION_EPOCH_ACTIVATED",
        {
            "activation_reason": "INITIAL_START",
            "applied_resource_vector_sha256": vector_digest,
            "boundary_research_time": embedded_time(2_000, 20),
            "condition_epoch_id": EPOCH_ID,
            "resource_vector_json": vector_json,
            "signed_configuration_sha256": CONFIGURATION_SHA256,
        },
        epoch_id=EPOCH_ID,
        wall=2_000,
        monotonic=20,
    )
    running = event_document(
        3,
        "study_runtime.v1",
        "STUDY_RUNNING",
        {
            "command_id": "b" * 64,
            "current_state": "RUNNING",
            "previous_state": "ACTIVATING",
            "transition_reason": "ACTIVATION_CONFIRMED",
        },
        epoch_id=EPOCH_ID,
        wall=2_000,
        monotonic=20,
    )
    second = commit_document(
        sequence=2,
        previous=first["commit_sha256"],
        events=[activation, running],
        observations=[],
        state="RUNNING",
        next_event_sequence=4,
        next_observation_sequence=1,
        lifetime_data_event_count=0,
        checkpoint_evaluated=2,
        checkpoint_lifecycle="RUNNING",
        checkpoint_start=1_000,
        desired_resources=(("COLLECTOR", "battery_state.v1", 1, "continuous"),),
        active_epoch=epoch,
        extra_mutations=[
            {
                "canonical_value": resource_component(
                    kind="COLLECTOR",
                    source_id="battery_state.v1",
                    generation=1,
                    profile_id="continuous",
                    profile_sha256=profile_digest,
                    status="APPLIED",
                ),
                "component_id": "collector:battery_state.v1",
                "component_kind": "RESOURCE",
                "operation": "UPSERT",
            }
        ],
        input_kind="RESOURCE_RESULT",
    )
    battery = event_document(
        4,
        "battery_state.v1",
        "BATTERY_STATE",
        {
            "charging_source": "USB",
            "charging_state": "CHARGING",
            "percentage": "50",
            "power_save_enabled": "false",
        },
        epoch_id=EPOCH_ID,
        wall=2000,
        monotonic=20,
    )
    observation = observation_document(
        sequence=1,
        source_id="battery_state.v1",
        generation=1,
        producer_ordinal=0,
        epoch_id=EPOCH_ID,
        events=[battery],
    )
    third = commit_document(
        sequence=3,
        previous=second["commit_sha256"],
        events=[battery],
        observations=[observation],
        state="RUNNING",
        next_event_sequence=5,
        next_observation_sequence=2,
        lifetime_data_event_count=1,
        checkpoint_evaluated=3,
        checkpoint_lifecycle="RUNNING",
        checkpoint_start=1000,
        desired_resources=(("COLLECTOR", "battery_state.v1", 1, "continuous"),),
        active_epoch=epoch,
        source_checkpoints={
            "battery_state.v1": {
                "coverage": None,
                "cursor": None,
                "next_producer_ordinal": "1",
                "resource_generation": "1",
                "source_id": "battery_state.v1",
            }
        },
    )
    return [first, second, third], epoch


def paused_cleanup_chain() -> list[dict]:
    documents, _ = active_chain()
    prior = documents[-1]
    profile_digest = hashlib.sha256(canonicalize({})).hexdigest()
    vector_json, vector_digest = applied_resource_vector(
        kind="COLLECTOR",
        source_id="battery_state.v1",
        generation=1,
        profile_id="continuous",
        profile_sha256=profile_digest,
        status="APPLIED",
    )
    command_id = "c" * 64
    boundary_wall = 4_000
    boundary_monotonic = 40
    requested = event_document(
        5,
        "study_runtime.v1",
        "STUDY_SAFETY_PAUSE_REQUESTED",
        {
            "command_id": command_id,
            "current_state": "PAUSING",
            "previous_state": "RUNNING",
            "transition_reason": "TRAFFIC_CONDITION_LOST",
        },
        epoch_id=EPOCH_ID,
        wall=boundary_wall,
        monotonic=boundary_monotonic,
    )
    deactivated = event_document(
        6,
        "study_condition.v1",
        "CONDITION_EPOCH_DEACTIVATED",
        {
            "applied_resource_vector_sha256": vector_digest,
            "boundary_research_time": embedded_time(
                boundary_wall, boundary_monotonic
            ),
            "condition_epoch_id": EPOCH_ID,
            "deactivation_reason": "SAFETY_PAUSED",
            "resource_vector_json": vector_json,
            "signed_configuration_sha256": CONFIGURATION_SHA256,
        },
        epoch_id=EPOCH_ID,
        wall=boundary_wall,
        monotonic=boundary_monotonic,
    )
    paused = event_document(
        7,
        "study_runtime.v1",
        "STUDY_SAFETY_PAUSED",
        {
            "command_id": command_id,
            "current_state": "PAUSED",
            "previous_state": "PAUSING",
            "transition_reason": "TRAFFIC_CONDITION_LOST",
        },
        epoch_id=EPOCH_ID,
        wall=boundary_wall,
        monotonic=boundary_monotonic,
    )
    trusted_resource = resource_component(
        kind="COLLECTOR",
        source_id="battery_state.v1",
        generation=1,
        profile_id="continuous",
        profile_sha256=profile_digest,
        status="APPLIED",
    )
    containment = commit_document(
        sequence=4,
        previous=prior["commit_sha256"],
        events=[requested, deactivated, paused],
        observations=[],
        state="PAUSED",
        next_event_sequence=8,
        next_observation_sequence=2,
        lifetime_data_event_count=1,
        checkpoint_evaluated=5,
        checkpoint_lifecycle="PAUSED",
        checkpoint_start=1_000,
        desired_resources=(("COLLECTOR", "battery_state.v1", 2, None),),
        active_epoch=None,
        source_checkpoints=prior["successor_projection"]["source_checkpoints"],
        extra_mutations=[
            {
                "canonical_value": trusted_resource,
                "component_id": "collector:battery_state.v1",
                "component_kind": "RESOURCE",
                "operation": "UPSERT",
            },
            {
                "canonical_value": resource_cleanup_component(
                    kind="COLLECTOR",
                    source_id="battery_state.v1",
                    generation=2,
                    profile_id="continuous",
                    expected_profile_sha256=profile_digest,
                ),
                "component_id": "collector:battery_state.v1",
                "component_kind": "RESOURCE_CLEANUP",
                "operation": "UPSERT",
            },
        ],
        input_kind="SAFETY_FAILURE",
        committed_wall=boundary_wall,
        committed_monotonic=boundary_monotonic,
        clock_active_elapsed_nanos=boundary_monotonic,
        clock_calendar_elapsed_nanos=boundary_monotonic,
        checkpoint_active_elapsed_nanos=boundary_monotonic,
        checkpoint_calendar_elapsed_nanos=boundary_monotonic,
    )
    final = commit_document(
        sequence=5,
        previous=containment["commit_sha256"],
        events=[],
        observations=[],
        state="PAUSED",
        next_event_sequence=8,
        next_observation_sequence=2,
        lifetime_data_event_count=1,
        checkpoint_evaluated=5,
        checkpoint_lifecycle="PAUSED",
        checkpoint_start=1_000,
        desired_resources=(("COLLECTOR", "battery_state.v1", 2, None),),
        active_epoch=None,
        source_checkpoints=prior["successor_projection"]["source_checkpoints"],
        extra_mutations=[
            {
                "canonical_value": resource_component(
                    kind="COLLECTOR",
                    source_id="battery_state.v1",
                    generation=2,
                    profile_id=None,
                    profile_sha256=None,
                    status="INACTIVE",
                ),
                "component_id": "collector:battery_state.v1",
                "component_kind": "RESOURCE",
                "operation": "UPSERT",
            },
            {
                "canonical_value": None,
                "component_id": "collector:battery_state.v1",
                "component_kind": "RESOURCE_CLEANUP",
                "operation": "REMOVE",
            },
        ],
        input_kind="RESOURCE_RESULT",
        committed_wall=5_000,
        committed_monotonic=50,
        clock_active_elapsed_nanos=boundary_monotonic,
        clock_calendar_elapsed_nanos=50,
        checkpoint_active_elapsed_nanos=boundary_monotonic,
        checkpoint_calendar_elapsed_nanos=boundary_monotonic,
    )
    return [*documents, containment, final]


class EngineTest(unittest.TestCase):
    def test_observation_event_order_allows_only_the_exact_pending_barrier_rotation(self) -> None:
        def observation(sequence: int, ordinal: int, event_sequence: int, kind: str = "NORMAL") -> SourceObservation:
            return SourceObservation(
                observation_sequence=sequence,
                source_id="usage_events.v1",
                schema_version=1,
                resource_generation=1,
                admission_kind=kind,
                producer_ordinal=ordinal,
                condition_epoch_id=EPOCH_ID,
                event_count=1,
                first_event_sequence=event_sequence,
                last_event_sequence=event_sequence,
                coverage=None,
                encoded_sha256="0" * 64,
            )

        def commit(observations: tuple[SourceObservation, ...], consumed: bool) -> EngineCommit:
            value = object.__new__(EngineCommit)
            object.__setattr__(value, "source_observations", observations)
            object.__setattr__(
                value,
                "consumed_pending_input_sha256",
                "f" * 64 if consumed else None,
            )
            return value

        EngineReplayVerifier._verify_observation_event_order(
            commit((observation(1, 0, 5), observation(2, 1, 6)), consumed=False)
        )
        EngineReplayVerifier._verify_observation_event_order(
            commit(
                (
                    observation(1, 0, 6),
                    observation(2, 1, 5, "BARRIER_FLUSH"),
                ),
                consumed=True,
            )
        )
        with self.assertRaisesRegex(ValidationError, "pre-drain/flush then causal"):
            EngineReplayVerifier._verify_observation_event_order(
                commit(
                    (
                        observation(1, 0, 7),
                        observation(2, 1, 6),
                        observation(3, 2, 5, "BARRIER_FLUSH"),
                    ),
                    consumed=True,
                )
            )

    def setUp(self) -> None:
        self.registry = EventSourceRegistry()
        self.parser = EngineCommitParser(self.registry)

    def test_study_deadline_component_must_match_signed_duration_exactly(self) -> None:
        documents, _ = active_chain()
        forged = copy.deepcopy(documents[0])
        deadline = next(
            mutation
            for mutation in forged["mutations"]
            if mutation["component_kind"] == "STUDY_DEADLINE_TIMER"
        )
        deadline["canonical_value"] = study_deadline_timer_component(
            target_monotonic=3_600_000_000_001,
            logical_deadline_utc_millis=3_601_000,
        )[1]
        forged = resign_commit(forged)

        with self.assertRaisesRegex(ValidationError, "signed duration"):
            EngineReplayVerifier(
                self.registry,
                battery_configuration(),
                CONFIGURATION_SHA256,
            ).accept(self.parser.parse(forged))

    def test_clock_gap_discards_retrospective_checkpoint_and_rejects_backfill(self) -> None:
        documents, _ = active_chain()
        verifier = EngineReplayVerifier(
            self.registry,
            battery_configuration(),
            CONFIGURATION_SHA256,
        )
        for document in documents:
            verifier.accept(self.parser.parse(document))

        prior = dict(verifier.previous_projection or {})
        checkpoints = dict(prior["source_checkpoints"])
        checkpoints["usage_events.v1"] = {
            "source_id": "usage_events.v1",
            "resource_generation": 1,
            "next_producer_ordinal": 1,
            "coverage": None,
            "cursor": "100",
        }
        verifier.previous_projection = {**prior, "source_checkpoints": checkpoints}
        gap = self.parser._event(event_document(
            5,
            "study_runtime.v1",
            "SOURCE_QUALITY_GAP",
            {"reason": "WALL_CLOCK_CHANGED", "source_id": "timer.v1"},
            epoch_id=EPOCH_ID,
            wall=4_000,
            monotonic=40,
        ))
        clean_commit = object.__new__(EngineCommit)
        object.__setattr__(clean_commit, "events", (gap,))
        object.__setattr__(clean_commit, "source_observations", ())
        object.__setattr__(
            clean_commit,
            "successor_projection",
            {**prior, "source_checkpoints": prior["source_checkpoints"]},
        )
        verifier._verify_observations(clean_commit)

        backlog = self.parser._observation(observation_document(
            sequence=2,
            source_id="usage_events.v1",
            generation=1,
            producer_ordinal=1,
            epoch_id=EPOCH_ID,
            events=[],
            coverage={
                "clock_basis": "SOURCE_WALL_TIME",
                "end_exclusive": "200",
                "start_inclusive": "100",
            },
        ))
        hostile_commit = object.__new__(EngineCommit)
        object.__setattr__(hostile_commit, "events", (gap,))
        object.__setattr__(hostile_commit, "source_observations", (backlog,))
        object.__setattr__(
            hostile_commit,
            "successor_projection",
            {**prior, "source_checkpoints": prior["source_checkpoints"]},
        )
        with self.assertRaisesRegex(ValidationError, "cannot backfill"):
            verifier._verify_observations(hostile_commit)

        process_gap = self.parser._event(event_document(
            5,
            "study_runtime.v1",
            "SOURCE_QUALITY_GAP",
            {"reason": "PROCESS_RECOVERY", "source_id": "usage_events.v1"},
            epoch_id=EPOCH_ID,
            wall=4_000,
            monotonic=40,
        ))
        staged_recovery = object.__new__(EngineCommit)
        object.__setattr__(staged_recovery, "events", (process_gap,))
        object.__setattr__(staged_recovery, "source_observations", (backlog,))
        object.__setattr__(staged_recovery, "input_kind", "RECOVERY")
        object.__setattr__(
            staged_recovery,
            "consumed_pending_input_sha256",
            "f" * 64,
        )
        object.__setattr__(
            staged_recovery,
            "successor_projection",
            {**prior, "source_checkpoints": prior["source_checkpoints"]},
        )
        verifier._verify_observations(staged_recovery)

        forged_recovery = object.__new__(EngineCommit)
        object.__setattr__(forged_recovery, "events", (process_gap,))
        object.__setattr__(forged_recovery, "source_observations", (backlog,))
        object.__setattr__(forged_recovery, "input_kind", "RECOVERY")
        object.__setattr__(forged_recovery, "consumed_pending_input_sha256", None)
        object.__setattr__(
            forged_recovery,
            "successor_projection",
            {**prior, "source_checkpoints": prior["source_checkpoints"]},
        )
        with self.assertRaisesRegex(ValidationError, "cannot backfill"):
            verifier._verify_observations(forged_recovery)

    def test_commit_digest_and_canonical_decimal_are_fail_closed(self) -> None:
        document = copy.deepcopy(commit_document(
            sequence=1,
            previous="0" * 64,
            events=[],
            observations=[],
            state="READY",
            next_event_sequence=1,
            next_observation_sequence=1,
            lifetime_data_event_count=0,
            checkpoint_evaluated=0,
            checkpoint_lifecycle="READY",
            checkpoint_start=None,
        ))
        self.parser.parse(document)
        document["commit_sha256"] = "1" * 64
        with self.assertRaisesRegex(ValidationError, "digest mismatch"):
            self.parser.parse(document)
        numeric = copy.deepcopy(document)
        numeric["commit_sha256"] = resign_commit(numeric)["commit_sha256"]
        numeric["commit_sequence"] = 1
        with self.assertRaisesRegex(ValidationError, "decimal string"):
            self.parser.parse(numeric)

    def test_random_selection_is_an_input_kind_not_a_component_kind(self) -> None:
        document = copy.deepcopy(commit_document(
            sequence=1,
            previous="0" * 64,
            events=[],
            observations=[],
            state="READY",
            next_event_sequence=1,
            next_observation_sequence=1,
            lifetime_data_event_count=0,
            checkpoint_evaluated=0,
            checkpoint_lifecycle="READY",
            checkpoint_start=None,
            input_kind="RANDOM_SELECTION",
        ))
        document["mutations"][0]["component_kind"] = "RANDOM_SELECTION"

        with self.assertRaisesRegex(ValidationError, "invalid component kind"):
            self.parser.parse(document)

    def test_clock_zone_is_exact_canonical_and_authenticated(self) -> None:
        valid = commit_document(
            sequence=1,
            previous="0" * 64,
            events=[],
            observations=[],
            state="READY",
            next_event_sequence=1,
            next_observation_sequence=1,
            lifetime_data_event_count=0,
            checkpoint_evaluated=0,
            checkpoint_lifecycle="READY",
            checkpoint_start=None,
        )
        self.parser.parse(valid)

        missing = copy.deepcopy(valid)
        del missing["successor_projection"]["clock_checkpoint"]["zone_id"]
        with self.assertRaisesRegex(ValidationError, "clock checkpoint keys mismatch"):
            self.parser.parse(missing)

        invalid = copy.deepcopy(valid)
        invalid["successor_projection"]["clock_checkpoint"]["zone_id"] = "GMT+08:00"
        with self.assertRaisesRegex(ValidationError, "canonical IANA"):
            self.parser.parse(invalid)

        forged = copy.deepcopy(valid)
        forged["successor_projection"]["clock_checkpoint"]["zone_id"] = "Asia/Taipei"
        with self.assertRaisesRegex(ValidationError, "commit digest mismatch"):
            self.parser.parse(forged)

    def test_replay_verifies_manifest_epoch_resource_and_checkpoint(self) -> None:
        documents, _ = active_chain()
        commits = [self.parser.parse(document) for document in documents]
        events = EngineReplayVerifier(
            self.registry, battery_configuration(), CONFIGURATION_SHA256
        ).replay(commits)
        self.assertEqual(5, len(events))
        self.assertEqual(EPOCH_ID, events[-1].condition_epoch_id)
        self.assertEqual(EPOCH_ID, events[-1].source_condition_epoch_id)
        self.assertEqual(50, events[-1].typed_fields["percentage"])

    def test_resource_cleanup_containment_finalizes_atomically(self) -> None:
        documents = paused_cleanup_chain()
        events = EngineReplayVerifier(
            self.registry, battery_configuration(), CONFIGURATION_SHA256
        ).replay(self.parser.parse(document) for document in documents)

        self.assertEqual("STUDY_SAFETY_PAUSED", events[-1].event_type)
        self.assertEqual(8, events[-1].sequence_number)

    def test_unresolved_resource_cleanup_cannot_be_published(self) -> None:
        documents = paused_cleanup_chain()[:-1]
        with self.assertRaisesRegex(ValidationError, "cleanup remains unresolved"):
            EngineReplayVerifier(
                self.registry, battery_configuration(), CONFIGURATION_SHA256
            ).replay(self.parser.parse(document) for document in documents)

    def test_resource_cleanup_is_rejected_outside_closed_paused_containment(
        self,
    ) -> None:
        documents, _ = active_chain()
        forged = copy.deepcopy(documents[1])
        profile_digest = hashlib.sha256(canonicalize({})).hexdigest()
        forged["mutations"].append(
            {
                "canonical_value": resource_cleanup_component(
                    kind="COLLECTOR",
                    source_id="battery_state.v1",
                    generation=1,
                    profile_id="continuous",
                    expected_profile_sha256=profile_digest,
                ),
                "component_id": "collector:battery_state.v1",
                "component_kind": "RESOURCE_CLEANUP",
                "operation": "UPSERT",
            }
        )
        forged = resign_commit(forged)
        verifier = EngineReplayVerifier(
            self.registry, battery_configuration(), CONFIGURATION_SHA256
        )
        verifier.accept(self.parser.parse(documents[0]))
        with self.assertRaisesRegex(ValidationError, "closed PAUSED containment"):
            verifier.accept(self.parser.parse(forged))

    def test_resource_cleanup_attempt_is_signed_and_cannot_rewrite_trusted_state(
        self,
    ) -> None:
        documents = paused_cleanup_chain()
        forged_digest = copy.deepcopy(documents[3])
        cleanup = next(
            mutation
            for mutation in forged_digest["mutations"]
            if mutation["component_kind"] == "RESOURCE_CLEANUP"
        )
        cleanup["canonical_value"] = resource_cleanup_component(
            kind="COLLECTOR",
            source_id="battery_state.v1",
            generation=2,
            profile_id="continuous",
            expected_profile_sha256="f" * 64,
        )
        forged_digest = resign_commit(forged_digest)
        verifier = EngineReplayVerifier(
            self.registry, battery_configuration(), CONFIGURATION_SHA256
        )
        for document in documents[:3]:
            verifier.accept(self.parser.parse(document))
        with self.assertRaisesRegex(ValidationError, "not a signed profile"):
            verifier.accept(self.parser.parse(forged_digest))

        rewritten = copy.deepcopy(documents[3])
        resource = next(
            mutation
            for mutation in rewritten["mutations"]
            if mutation["component_kind"] == "RESOURCE"
        )
        profile_digest = hashlib.sha256(canonicalize({})).hexdigest()
        resource["canonical_value"] = resource_component(
            kind="COLLECTOR",
            source_id="battery_state.v1",
            generation=2,
            profile_id="continuous",
            profile_sha256=profile_digest,
            status="APPLIED",
        )
        rewritten = resign_commit(rewritten)
        verifier = EngineReplayVerifier(
            self.registry, battery_configuration(), CONFIGURATION_SHA256
        )
        for document in documents[:3]:
            verifier.accept(self.parser.parse(document))
        with self.assertRaisesRegex(ValidationError, "last-trusted resource"):
            verifier.accept(self.parser.parse(rewritten))

    def test_resource_cleanup_finalization_requires_complete_inactive_vector(
        self,
    ) -> None:
        documents = paused_cleanup_chain()
        forged = copy.deepcopy(documents[-1])
        resource = next(
            mutation
            for mutation in forged["mutations"]
            if mutation["component_kind"] == "RESOURCE"
        )
        profile_digest = hashlib.sha256(canonicalize({})).hexdigest()
        resource["canonical_value"] = resource_component(
            kind="COLLECTOR",
            source_id="battery_state.v1",
            generation=1,
            profile_id="continuous",
            profile_sha256=profile_digest,
            status="APPLIED",
        )
        forged = resign_commit(forged)
        verifier = EngineReplayVerifier(
            self.registry, battery_configuration(), CONFIGURATION_SHA256
        )
        for document in documents[:-1]:
            verifier.accept(self.parser.parse(document))
        with self.assertRaisesRegex(ValidationError, "all-inactive result"):
            verifier.accept(self.parser.parse(forged))

    def test_partial_observation_and_producer_ordinal_divergence_are_rejected(self) -> None:
        documents, _ = active_chain()
        first, second = documents[:2], documents[2]
        partial = copy.deepcopy(second)
        partial["source_observations"][0]["last_event_sequence"] = "3"
        with self.assertRaises(ValidationError):
            self.parser.parse(resign_commit(partial))
        ordinal = copy.deepcopy(second)
        ordinal["source_observations"][0]["producer_ordinal"] = "1"
        ordinal["source_observations"][0]["encoded_sha256"] = "1" * 64
        parsed = self.parser.parse(resign_commit(ordinal))
        verifier = EngineReplayVerifier(
            self.registry, battery_configuration(), CONFIGURATION_SHA256
        )
        for document in first:
            verifier.accept(self.parser.parse(document))
        with self.assertRaisesRegex(ValidationError, "ordinal"):
            verifier.accept(parsed)

    def test_missing_or_orphan_condition_epoch_is_rejected(self) -> None:
        documents, _ = active_chain()
        first, second = documents[:2], documents[2]
        missing = copy.deepcopy(second)
        missing["events"][0]["condition_epoch_id"] = None
        missing["source_observations"][0]["encoded_sha256"] = "1" * 64
        verifier = EngineReplayVerifier(
            self.registry, battery_configuration(), CONFIGURATION_SHA256
        )
        for document in first:
            verifier.accept(self.parser.parse(document))
        with self.assertRaises(ValidationError):
            verifier.accept(self.parser.parse(resign_commit(missing)))

        orphan = copy.deepcopy(second)
        unknown = str(uuid.uuid4())
        orphan["events"][0]["condition_epoch_id"] = unknown
        orphan["source_observations"][0]["condition_epoch_id"] = unknown
        orphan["source_observations"][0]["encoded_sha256"] = "1" * 64
        verifier = EngineReplayVerifier(
            self.registry, battery_configuration(), CONFIGURATION_SHA256
        )
        for document in first:
            verifier.accept(self.parser.parse(document))
        with self.assertRaisesRegex(ValidationError, "active condition epoch"):
            verifier.accept(self.parser.parse(resign_commit(orphan)))

    def test_checkpoint_digest_divergence_is_rejected(self) -> None:
        documents, _ = active_chain()
        documents[0]["resulting_checkpoint_sha256"] = "1" * 64
        parsed = self.parser.parse(resign_commit(documents[0]))
        with self.assertRaisesRegex(ValidationError, "checkpoint digest"):
            EngineReplayVerifier(
                self.registry, battery_configuration(), CONFIGURATION_SHA256
            ).accept(parsed)

    def test_forged_self_consistent_checkpoint_fails_authoritative_replay(self) -> None:
        documents, _ = active_chain()
        forged = copy.deepcopy(documents[-1])
        checkpoint_mutation = next(
            mutation
            for mutation in forged["mutations"]
            if mutation["component_kind"] == "AUTOMATION_CHECKPOINT"
        )
        checkpoint = decode_authoritative_checkpoint(checkpoint_mutation["canonical_value"])
        checkpoint.latch_values["forged-state"] = True
        checkpoint_mutation["canonical_value"] = encode_authoritative_checkpoint(checkpoint)
        forged["resulting_checkpoint_sha256"] = authoritative_checkpoint_digest(checkpoint)
        forged = resign_commit(forged)

        verifier = EngineReplayVerifier(
            self.registry, battery_configuration(), CONFIGURATION_SHA256
        )
        for document in documents[:-1]:
            verifier.accept(self.parser.parse(document))
        with self.assertRaisesRegex(ValidationError, "authoritative replay"):
            verifier.accept(self.parser.parse(forged))

    def test_input_kind_cannot_relabel_a_source_observation(self) -> None:
        documents, _ = active_chain()
        forged = copy.deepcopy(documents[-1])
        forged["input_kind"] = "LIFECYCLE_COMMAND"
        forged = resign_commit(forged)

        verifier = EngineReplayVerifier(
            self.registry, battery_configuration(), CONFIGURATION_SHA256
        )
        for document in documents[:-1]:
            verifier.accept(self.parser.parse(document))
        with self.assertRaisesRegex(
            ValidationError, "LIFECYCLE_COMMAND commit cannot carry reducer inputs"
        ):
            verifier.accept(self.parser.parse(forged))

    def test_timer_identity_and_durable_checkpoint_parity_are_fail_closed(self) -> None:
        timer = event_document(
            1,
            "timer.v1",
            "TIMER_SCHEDULED",
            {
                "automation_id": "scheduled-action",
                "causal_sequence": "1",
                "clock": "ACTIVE_RUNNING_TIME",
                "generation": "1",
                "logical_due_research_time": embedded_time(1000, 10),
                "producer_key": "one-time:0",
                "timer_id": "1" * 64,
            },
            epoch_id=None,
            wall=1000,
            monotonic=10,
        )
        document = commit_document(
            sequence=1,
            previous="0" * 64,
            events=[timer],
            observations=[],
            state="READY",
            next_event_sequence=2,
            next_observation_sequence=1,
            lifetime_data_event_count=0,
            checkpoint_evaluated=1,
            checkpoint_lifecycle="READY",
            checkpoint_start=None,
            input_kind="TIMER_WAKE",
        )
        with self.assertRaisesRegex(ValidationError, "checkpoint changed without a reducer input"):
            EngineReplayVerifier(
                self.registry, scheduled_action_configuration(), CONFIGURATION_SHA256
            ).accept(self.parser.parse(document))

        timer_id = hashlib.sha256(
            (
                f"particeps-timer-v1\0{CONFIGURATION_SHA256}"
                "\0scheduled-action\0one-time:0"
            ).encode()
        ).hexdigest()
        component = timer_component(
            timer_id=timer_id,
            automation_id="scheduled-action",
            producer_key="one-time:0",
        )
        document = commit_document(
            sequence=1,
            previous="0" * 64,
            events=[],
            observations=[],
            state="READY",
            next_event_sequence=1,
            next_observation_sequence=1,
            lifetime_data_event_count=0,
            checkpoint_evaluated=0,
            checkpoint_lifecycle="READY",
            checkpoint_start=None,
            extra_mutations=[
                {
                    "canonical_value": component,
                    "component_id": timer_id,
                    "component_kind": "TIMER",
                    "operation": "UPSERT",
                }
            ],
            input_kind="TIMER_WAKE",
        )
        with self.assertRaisesRegex(ValidationError, "timer components diverge"):
            EngineReplayVerifier(
                self.registry, scheduled_action_configuration(), CONFIGURATION_SHA256
            ).accept(self.parser.parse(document))

    def test_action_request_requires_match_and_durable_outbox_provenance(self) -> None:
        _, condition_sha256 = checkpoint_component(
            evaluated=2,
            active_elapsed_nanos=10,
            calendar_elapsed_nanos=10,
        )
        lifecycle = event_document(
            1,
            "study_runtime.v1",
            "STUDY_STARTED",
            {
                "command_id": "b" * 64,
                "current_state": "ACTIVATING",
                "transition_reason": "STUDY_START",
            },
            epoch_id=None,
            wall=1000,
            monotonic=10,
        )
        request = event_document(
            2,
            "automation_runtime.v1",
            "ACTION_REQUESTED",
            {
                "automation_id": "scheduled-action",
                "causal_final_sequence": "1",
                "causal_first_sequence": "1",
                "condition_sha256": condition_sha256,
                "generation": "1",
                "intervention_id": "notify-one",
                "invocation_id": "c" * 64,
                "logical_time": embedded_time(1000, 10),
                "observed_time": embedded_time(1000, 10),
            },
            epoch_id=None,
            wall=1000,
            monotonic=10,
        )
        document = commit_document(
            sequence=1,
            previous="0" * 64,
            events=[lifecycle, request],
            observations=[],
            state="READY",
            next_event_sequence=3,
            next_observation_sequence=1,
            lifetime_data_event_count=0,
            checkpoint_evaluated=2,
            checkpoint_lifecycle="READY",
            checkpoint_start=None,
            input_kind="ACTION_RESULT",
        )
        with self.assertRaisesRegex(ValidationError, "ACTION_RESULT commit cannot carry"):
            EngineReplayVerifier(
                self.registry, scheduled_action_configuration(), CONFIGURATION_SHA256
            ).accept(self.parser.parse(document))

        _, empty_condition = checkpoint_component(
            active_elapsed_nanos=10,
            calendar_elapsed_nanos=10,
        )
        action_id = "d" * 64
        component = action_component(
            action_id=action_id,
            automation_id="scheduled-action",
            intervention_id="notify-one",
            causal_sequence=1,
            condition_sha256=empty_condition,
        )
        document = commit_document(
            sequence=1,
            previous="0" * 64,
            events=[],
            observations=[],
            state="READY",
            next_event_sequence=1,
            next_observation_sequence=1,
            lifetime_data_event_count=0,
            checkpoint_evaluated=0,
            checkpoint_lifecycle="READY",
            checkpoint_start=None,
            extra_mutations=[
                {
                    "canonical_value": component,
                    "component_id": action_id,
                    "component_kind": "ACTION_INVOCATION",
                    "operation": "UPSERT",
                }
            ],
            input_kind="ACTION_RESULT",
        )
        with self.assertRaisesRegex(ValidationError, "no durable causal request"):
            EngineReplayVerifier(
                self.registry, scheduled_action_configuration(), CONFIGURATION_SHA256
            ).accept(self.parser.parse(document))

    def test_survey_expiry_requires_exact_preceding_correlated_failure(self) -> None:
        action_id = "d" * 64
        condition_sha256 = "e" * 64
        observed = ResearchTime(11_000, 110, "boot-one")
        action = DurableAction(
            action_id=action_id,
            automation_id="scheduled-action",
            intervention_id="notify-one",
            causal_sequence=3,
            logical_deadline_utc_millis=None,
            expires_at_utc_millis=10_000,
            condition_sha256=condition_sha256,
            generation=1,
            requested_at=ResearchTime(1_000, 10, "boot-one"),
            opened_at=None,
            state="FAILED",
            failure_reason="EXPIRED",
        )

        def survey(sequence: int, **overrides: str) -> RecordedEvent:
            fields = {
                "intervention_id": "notify-one",
                "occurrence_id": action_id,
                "scheduled_for_utc_millis": "1000",
                "trigger_id": "scheduled-action",
                **overrides,
            }
            return self.parser._event(event_document(
                sequence,
                "interventions.v1",
                "SURVEY_EXPIRED",
                fields,
                epoch_id=EPOCH_ID,
                wall=observed.wall_time_utc_millis,
                monotonic=observed.elapsed_realtime_nanos,
            ))

        def failed(sequence: int, **overrides: str) -> RecordedEvent:
            fields = {
                "automation_id": "scheduled-action",
                "causal_final_sequence": "3",
                "causal_first_sequence": "3",
                "condition_sha256": condition_sha256,
                "failure_reason": "EXPIRED",
                "generation": "1",
                "intervention_id": "notify-one",
                "invocation_id": action_id,
                "logical_time": embedded_time(
                    observed.wall_time_utc_millis,
                    observed.elapsed_realtime_nanos,
                ),
                "observed_time": embedded_time(
                    observed.wall_time_utc_millis,
                    observed.elapsed_realtime_nanos,
                ),
                **overrides,
            }
            return self.parser._event(event_document(
                sequence,
                "automation_runtime.v1",
                "ACTION_FAILED",
                fields,
                epoch_id=EPOCH_ID,
                wall=observed.wall_time_utc_millis,
                monotonic=observed.elapsed_realtime_nanos,
            ))

        def commit(*events: RecordedEvent) -> EngineCommit:
            value = object.__new__(EngineCommit)
            object.__setattr__(value, "events", tuple(events))
            return value

        survey_verifier = EngineReplayVerifier(
            self.registry,
            scheduled_survey_configuration(),
            CONFIGURATION_SHA256,
        )
        actions = {action_id: action}

        survey_verifier._verify_survey_expiry_causality(
            commit(survey(1), failed(2)),
            actions,
        )
        with self.assertRaisesRegex(ValidationError, "missing SURVEY_EXPIRED"):
            survey_verifier._verify_survey_expiry_causality(commit(failed(1)), actions)
        with self.assertRaisesRegex(ValidationError, "orphan survey expiry"):
            survey_verifier._verify_survey_expiry_causality(commit(survey(1)), actions)
        with self.assertRaisesRegex(ValidationError, "duplicate survey expiry"):
            survey_verifier._verify_survey_expiry_causality(
                commit(survey(1), survey(2), failed(3)),
                actions,
            )
        with self.assertRaisesRegex(ValidationError, "must precede"):
            survey_verifier._verify_survey_expiry_causality(
                commit(failed(1), survey(2)),
                actions,
            )
        with self.assertRaisesRegex(ValidationError, "identity diverges"):
            survey_verifier._verify_survey_expiry_causality(
                commit(survey(1, trigger_id="wrong-trigger"), failed(2)),
                actions,
            )
        with self.assertRaisesRegex(ValidationError, "duplicate expired action"):
            survey_verifier._verify_survey_expiry_causality(
                commit(survey(1), failed(2), failed(3)),
                actions,
            )

        notification_verifier = EngineReplayVerifier(
            self.registry,
            scheduled_action_configuration(),
            CONFIGURATION_SHA256,
        )
        notification_verifier._verify_survey_expiry_causality(
            commit(failed(1)),
            actions,
        )
        with self.assertRaisesRegex(ValidationError, "non-survey action"):
            notification_verifier._verify_survey_expiry_causality(
                commit(survey(1), failed(2)),
                actions,
            )

    def test_resource_audit_timer_and_periodic_traffic_cycle_replay(self) -> None:
        activation, epoch, timer_id = traffic_activation_commit()
        due = traffic_due_commit(activation[-1], epoch, timer_id)
        events = EngineReplayVerifier(
            self.registry, traffic_configuration(), CONFIGURATION_SHA256
        ).replay([*(self.parser.parse(item) for item in activation), self.parser.parse(due)])
        self.assertEqual(
            [
                "STUDY_STARTED",
                "TIMER_SCHEDULED",
                "CONDITION_EPOCH_ACTIVATED",
                "TRAFFIC_SHAPING_PROFILE_APPLIED",
                "TIMER_SCHEDULED",
                "STUDY_RUNNING",
                "TIMER_DUE",
                "TRAFFIC_SHAPING_SNAPSHOT",
                "TIMER_RETIRED",
                "TIMER_SCHEDULED",
            ],
            [event.event_type for event in events],
        )

    def test_resource_audit_timer_interval_and_due_order_fail_closed(self) -> None:
        short, _, _ = traffic_activation_commit(timer_offset_nanos=59_000_000_000)
        with self.assertRaisesRegex(ValidationError, "60-second"):
            EngineReplayVerifier(
                self.registry, traffic_configuration(), CONFIGURATION_SHA256
            ).replay(self.parser.parse(item) for item in short)

        activation, epoch, timer_id = traffic_activation_commit()
        due = traffic_due_commit(activation[-1], epoch, timer_id)
        first_sequence = due["events"][0]["sequence_number"]
        second_sequence = due["events"][1]["sequence_number"]
        due["events"][0]["sequence_number"] = second_sequence
        due["events"][1]["sequence_number"] = first_sequence
        due["events"][0], due["events"][1] = due["events"][1], due["events"][0]
        malformed = resign_commit(due)
        verifier = EngineReplayVerifier(
            self.registry, traffic_configuration(), CONFIGURATION_SHA256
        )
        for item in activation:
            verifier.accept(self.parser.parse(item))
        with self.assertRaisesRegex(ValidationError, "due cycle ordering"):
            verifier.accept(self.parser.parse(malformed))

    def test_recovery_cannot_revive_a_resource_audit_timer(self) -> None:
        activation, _, _ = traffic_activation_commit()
        activation[-1]["input_kind"] = "RECOVERY"
        with self.assertRaisesRegex(ValidationError, "RECOVERY commit cannot carry"):
            EngineReplayVerifier(
                self.registry, traffic_configuration(), CONFIGURATION_SHA256
            ).replay(self.parser.parse(resign_commit(item)) for item in activation)

    def test_traffic_boundary_audit_precedes_removal_retirement_and_epoch_end(self) -> None:
        activation, _, timer_id = traffic_activation_commit()
        boundary = traffic_boundary_commit(activation[-1], timer_id)
        events = EngineReplayVerifier(
            self.registry, traffic_configuration(), CONFIGURATION_SHA256
        ).replay([*(self.parser.parse(item) for item in activation), self.parser.parse(boundary)])
        removal = next(event for event in events if event.event_type == "TRAFFIC_SHAPING_PROFILE_REMOVED")
        self.assertEqual("SYSTEM_SAFETY_PAUSE", removal.wire_fields["removal_reason"])

        malformed = traffic_boundary_commit(activation[-1], timer_id)
        first_sequence = malformed["events"][1]["sequence_number"]
        second_sequence = malformed["events"][2]["sequence_number"]
        malformed["events"][1]["sequence_number"] = second_sequence
        malformed["events"][2]["sequence_number"] = first_sequence
        malformed["events"][1], malformed["events"][2] = (
            malformed["events"][2],
            malformed["events"][1],
        )
        verifier = EngineReplayVerifier(
            self.registry, traffic_configuration(), CONFIGURATION_SHA256
        )
        for item in activation:
            verifier.accept(self.parser.parse(item))
        with self.assertRaisesRegex(ValidationError, "boundary snapshot"):
            verifier.accept(self.parser.parse(resign_commit(malformed)))

    def test_upload_acknowledgement_advances_only_its_exact_commit_range(self) -> None:
        first = parse_empty_commit()
        acknowledgement = upload_acknowledgement_component(
            bundle_id="c3d5178f-89ca-4d7e-bcf5-73f33a49b59c",
            first_commit=1,
            through_commit=1,
            bundle_sha256="b" * 64,
            wall=2_000,
            monotonic=20,
        )
        second = commit_document(
            sequence=2,
            previous=first.commit_sha256,
            events=[],
            observations=[],
            state="READY",
            next_event_sequence=1,
            next_observation_sequence=1,
            lifetime_data_event_count=0,
            checkpoint_evaluated=0,
            checkpoint_lifecycle="READY",
            checkpoint_start=None,
            extra_mutations=[
                {
                    "canonical_value": acknowledgement,
                    "component_id": "latest",
                    "component_kind": "UPLOAD_ACKNOWLEDGEMENT",
                    "operation": "UPSERT",
                }
            ],
            input_kind="UPLOAD_ACKNOWLEDGEMENT",
            uploaded_through_commit=1,
        )
        self.assertEqual(
            (),
            EngineReplayVerifier(
                self.registry, configuration(), CONFIGURATION_SHA256
            ).replay([first, self.parser.parse(second)]),
        )

        wrong = copy.deepcopy(second)
        wrong["successor_projection"]["uploaded_through_commit"] = "0"
        with self.assertRaisesRegex(ValidationError, "acknowledgement"):
            EngineReplayVerifier(
                self.registry, configuration(), CONFIGURATION_SHA256
            ).replay([first, self.parser.parse(resign_commit(wrong))])

    def test_empty_genesis_fixture_replays(self) -> None:
        self.assertEqual(
            (),
            EngineReplayVerifier(
                self.registry, configuration(), CONFIGURATION_SHA256
            ).replay([parse_empty_commit()]),
        )


if __name__ == "__main__":
    unittest.main()
