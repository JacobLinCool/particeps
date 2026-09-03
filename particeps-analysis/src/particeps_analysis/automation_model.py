"""Language-neutral value model for the authoritative Protocol v1 automation reducer."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Literal

StudySessionState = Literal[
    "READY", "ACTIVATING", "RUNNING", "PAUSING", "PAUSED", "COMPLETED", "WITHDRAWN"
]


@dataclass(frozen=True, slots=True)
class ResearchTime:
    wall_time_utc_millis: int
    elapsed_realtime_nanos: int
    boot_session_id: str


@dataclass(frozen=True, slots=True)
class ReducerClock:
    now: ResearchTime
    active_elapsed_nanos: int
    calendar_elapsed_nanos: int
    zone_id: str


@dataclass(frozen=True, slots=True)
class AutomationEvent:
    sequence_number: int
    source_id: str
    schema_version: int
    event_type: str
    observed_time: ResearchTime
    primary_source_time: ResearchTime | None
    fields: dict[str, str]


@dataclass(frozen=True, slots=True)
class TimerTarget:
    type: Literal["CALENDAR_UTC", "ACTIVE_ELAPSED", "SAME_BOOT_MONOTONIC"]
    utc_millis: int | None = None
    elapsed_nanos: int | None = None
    boot_session_id: str | None = None
    elapsed_realtime_nanos: int | None = None


@dataclass(frozen=True, slots=True)
class DurableTimer:
    id: str
    automation_id: str
    generation: int
    causal_sequence: int
    producer_key: str
    target: TimerTarget
    logical_deadline_utc_millis: int | None
    expires_at_utc_millis: int | None


@dataclass(frozen=True, slots=True)
class ReducerInput:
    type: Literal[
        "EVENT", "LIFECYCLE", "TIMER_DUE", "TIMER_MATERIALIZED",
        "QUALITY_GAP", "CLOCK_DISCONTINUITY",
    ]
    sequence_number: int
    clock: ReducerClock
    event: AutomationEvent | None = None
    state: StudySessionState | None = None
    timer_id: str | None = None
    automation_id: str | None = None
    generation: int | None = None
    causal_sequence: int | None = None
    target: TimerTarget | None = None
    logical_due: ResearchTime | None = None
    timer: DurableTimer | None = None
    source_id: str | None = None
    restart_resources: tuple[ResourceKey, ...] = ()


@dataclass(frozen=True, slots=True)
class WindowEntry:
    sequence_number: int
    time_nanos: int
    boot_session_id: str
    numeric_value: int


@dataclass(frozen=True, slots=True)
class SequencePartial:
    next_step: int
    first_sequence_number: int
    last_sequence_number: int
    first_time_nanos: int
    boot_session_id: str


@dataclass(frozen=True, slots=True)
class CooldownMark:
    active_elapsed_nanos: int
    calendar_elapsed_nanos: int


@dataclass(frozen=True, slots=True, order=True)
class ResourceKey:
    # Kotlin ResourceKey compares lowercase kind first, then id.
    kind: Literal["ACTUATOR", "COLLECTOR"]
    id: str

    def sort_key(self) -> tuple[str, str]:
        return self.kind.lower(), self.id


@dataclass(frozen=True, slots=True)
class DesiredProfile:
    generation: int
    profile_id: str | None


@dataclass(frozen=True, slots=True)
class MaterializedTimerSummary:
    producer_key: str
    selected_utc_millis: int
    terminal: bool


@dataclass(slots=True)
class AutomationCheckpoint:
    evaluated_through_sequence: int = 0
    lifecycle: StudySessionState = "READY"
    study_start_utc_millis: int | None = None
    last_active_elapsed_nanos: int = 0
    last_calendar_elapsed_nanos: int = 0
    latch_values: dict[str, bool] = field(default_factory=dict)
    presence_keys: dict[str, set[str]] = field(default_factory=dict)
    held_since_nanos: dict[str, int] = field(default_factory=dict)
    prior_condition_values: dict[str, bool] = field(default_factory=dict)
    windows: dict[str, list[WindowEntry]] = field(default_factory=dict)
    sequences: dict[str, list[SequencePartial]] = field(default_factory=dict)
    activation_counts: dict[str, int] = field(default_factory=dict)
    cooldown_marks: dict[str, CooldownMark] = field(default_factory=dict)
    desired_resources: dict[ResourceKey, DesiredProfile] = field(default_factory=dict)
    timers: dict[str, DurableTimer] = field(default_factory=dict)
    timer_generations: dict[str, int] = field(default_factory=dict)
    materialized_timers: dict[str, list[MaterializedTimerSummary]] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class ActionRequest:
    action_id: str
    automation_id: str
    intervention_id: str
    causal_identity: str
    logical_deadline_utc_millis: int | None
    expires_at_utc_millis: int


@dataclass(frozen=True, slots=True)
class AutomationAudit:
    automation_id: str
    matched: bool
    suppression_reason: str | None
    causal_identity: str


@dataclass(frozen=True, slots=True)
class TimerIntent:
    type: Literal["SCHEDULE", "RETIRE"]
    timer: DurableTimer | None = None
    timer_id: str | None = None
    generation: int | None = None


@dataclass(frozen=True, slots=True)
class TimerProductionRequest:
    configuration_sha256: str
    automation: dict[str, Any]
    schedule: dict[str, Any]
    clock: ReducerClock
    study_start_utc_millis: int
    study_deadline_utc_millis: int
    causal_sequence: int
    current_generation: int
    session_state: StudySessionState
    pending_timer: DurableTimer | None
    materialized: tuple[MaterializedTimerSummary, ...]


@dataclass(frozen=True, slots=True)
class ReductionResult:
    checkpoint: AutomationCheckpoint
    action_requests: tuple[ActionRequest, ...]
    timer_intents: tuple[TimerIntent, ...]
    timer_production_requests: tuple[TimerProductionRequest, ...]
    resource_changes: dict[ResourceKey, DesiredProfile]
    audits: tuple[AutomationAudit, ...]


@dataclass(frozen=True, slots=True)
class CompiledAutomationProgram:
    configuration: dict[str, Any]
    configuration_sha256: str
    study_duration_seconds: int
    occurrence_automations: tuple[dict[str, Any], ...]
    resource_bindings: tuple[dict[str, Any], ...]
    contracts: dict[tuple[str, int, str], Any]
