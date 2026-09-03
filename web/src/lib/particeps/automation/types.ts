import type {
  AutomationDefinition,
  OccurrenceAutomation,
  ResourceBindingAutomation,
  StudyConfiguration
} from '../types';
import type { RegistryEventContract } from '../generated/event-source-registry';

export type StudySessionState =
  | 'READY' | 'ACTIVATING' | 'RUNNING' | 'PAUSING' | 'PAUSED' | 'COMPLETED' | 'WITHDRAWN';

export interface ResearchTime {
  wall_time_utc_millis: number;
  elapsed_realtime_nanos: bigint;
  boot_session_id: string;
}

export interface ReducerClock {
  now: ResearchTime;
  active_elapsed_nanos: bigint;
  calendar_elapsed_nanos: bigint;
  zone_id: string;
}

export interface AutomationEvent {
  sequence_number: number;
  source_id: string;
  schema_version: number;
  event_type: string;
  observed_time: ResearchTime;
  primary_source_time: ResearchTime | null;
  fields: Record<string, string>;
}

export type TimerTarget =
  | { type: 'CALENDAR_UTC'; utc_millis: number }
  | { type: 'ACTIVE_ELAPSED'; elapsed_nanos: bigint }
  | { type: 'SAME_BOOT_MONOTONIC'; boot_session_id: string; elapsed_realtime_nanos: bigint };

export interface DurableTimer {
  id: string;
  automation_id: string;
  generation: bigint;
  causal_sequence: number;
  producer_key: string;
  target: TimerTarget;
  logical_deadline_utc_millis: number | null;
  expires_at_utc_millis: number | null;
}

export type ReducerInput =
  | { type: 'EVENT'; sequence_number: number; clock: ReducerClock; event: AutomationEvent }
  | { type: 'LIFECYCLE'; sequence_number: number; clock: ReducerClock; state: StudySessionState }
  | {
      type: 'TIMER_DUE'; sequence_number: number; clock: ReducerClock; timer_id: string;
      automation_id: string; generation: bigint; causal_sequence: number; target: TimerTarget;
      logical_due: ResearchTime;
    }
  | { type: 'TIMER_MATERIALIZED'; sequence_number: number; clock: ReducerClock; timer: DurableTimer }
  | { type: 'QUALITY_GAP'; sequence_number: number; clock: ReducerClock; source_id: string }
  | {
      type: 'CLOCK_DISCONTINUITY'; sequence_number: number; clock: ReducerClock;
      restart_resources: ResourceKey[];
    };

export interface WindowEntry {
  sequence_number: number;
  time_nanos: bigint;
  boot_session_id: string;
  numeric_value: bigint;
}

export interface SequencePartial {
  next_step: number;
  first_sequence_number: number;
  last_sequence_number: number;
  first_time_nanos: bigint;
  boot_session_id: string;
}

export interface CooldownMark {
  active_elapsed_nanos: bigint;
  calendar_elapsed_nanos: bigint;
}

export interface ResourceKey { kind: 'COLLECTOR' | 'ACTUATOR'; id: string }
export interface DesiredProfile { generation: bigint; profile_id: string | null }
export interface MaterializedTimerSummary { producer_key: string; selected_utc_millis: number; terminal: boolean }

export interface AutomationCheckpoint {
  evaluated_through_sequence: number;
  lifecycle: StudySessionState;
  study_start_utc_millis: number | null;
  last_active_elapsed_nanos: bigint;
  last_calendar_elapsed_nanos: bigint;
  latch_values: Map<string, boolean>;
  presence_keys: Map<string, Set<string>>;
  held_since_nanos: Map<string, bigint>;
  prior_condition_values: Map<string, boolean>;
  windows: Map<string, WindowEntry[]>;
  sequences: Map<string, SequencePartial[]>;
  activation_counts: Map<string, number>;
  cooldown_marks: Map<string, CooldownMark>;
  desired_resources: Map<string, { key: ResourceKey; desired: DesiredProfile }>;
  timers: Map<string, DurableTimer>;
  timer_generations: Map<string, bigint>;
  materialized_timers: Map<string, MaterializedTimerSummary[]>;
}

export interface ActionRequest {
  action_id: string;
  automation_id: string;
  intervention_id: string;
  causal_identity: string;
  logical_deadline_utc_millis: number | null;
  expires_at_utc_millis: number;
}

export type SuppressionReason = 'GUARD_FALSE' | 'COOLDOWN' | 'MAXIMUM_ACTIVATIONS' | 'EXPIRED' | 'STALE_TIMER';
export interface AutomationAudit {
  automation_id: string;
  matched: boolean;
  suppression_reason: SuppressionReason | null;
  causal_identity: string;
}

export type TimerIntent =
  | { type: 'SCHEDULE'; timer: DurableTimer }
  | { type: 'RETIRE'; timer_id: string; generation: bigint };

export interface TimerProductionRequest {
  configuration_sha256: string;
  automation: OccurrenceAutomation;
  schedule: Extract<OccurrenceAutomation['trigger'], { type: 'schedule' }>['schedule'];
  clock: ReducerClock;
  study_start_utc_millis: number;
  study_deadline_utc_millis: number;
  causal_sequence: number;
  current_generation: bigint;
  session_state: StudySessionState;
  pending_timer: DurableTimer | null;
  materialized: MaterializedTimerSummary[];
}

export interface ReductionResult {
  checkpoint: AutomationCheckpoint;
  action_requests: ActionRequest[];
  timer_intents: TimerIntent[];
  timer_production_requests: TimerProductionRequest[];
  resource_changes: Map<string, { key: ResourceKey; desired: DesiredProfile }>;
  audits: AutomationAudit[];
}

export interface CompiledAutomationProgram {
  configuration: StudyConfiguration;
  configuration_sha256: string;
  study_duration_seconds: number;
  occurrence_automations: OccurrenceAutomation[];
  resource_bindings: ResourceBindingAutomation[];
  contracts: Map<string, RegistryEventContract>;
  automations: AutomationDefinition[];
}

export const resourceKeyString = (key: ResourceKey): string => `${key.kind}:${key.id}`;

export function emptyAutomationCheckpoint(): AutomationCheckpoint {
  return {
    evaluated_through_sequence: 0,
    lifecycle: 'READY',
    study_start_utc_millis: null,
    last_active_elapsed_nanos: 0n,
    last_calendar_elapsed_nanos: 0n,
    latch_values: new Map(),
    presence_keys: new Map(),
    held_since_nanos: new Map(),
    prior_condition_values: new Map(),
    windows: new Map(),
    sequences: new Map(),
    activation_counts: new Map(),
    cooldown_marks: new Map(),
    desired_resources: new Map(),
    timers: new Map(),
    timer_generations: new Map(),
    materialized_timers: new Map()
  };
}
