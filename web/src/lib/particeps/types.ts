/** Exact, closed-world Protocol v1 study configuration types. */

import { EVENT_SOURCE_REGISTRY } from './generated/event-source-registry.ts';

export const SCHEMA_VERSION = 1;
export const PLATFORM = 'android' as const;
export const DEFAULT_MINIMUM_CLIENT_VERSION = '1';
export const MAXIMUM_CONFIGURATION_BYTES = 1_048_576;
export const MAXIMUM_INTERVENTION_OCCURRENCES = 512;

export const ID_PATTERN = /^[a-z0-9][a-z0-9-]{2,63}$/;
export const ASSIGNED_PARTICIPANT_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
export const ANDROID_APPLICATION_ID_PATTERN = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;
export const PARTICEPS_APPLICATION_ID = 'cool.jacoblin.particeps';

export const MINIMUM_LOCAL_BYTES = 8 * 1024 * 1024;
export const MAXIMUM_LOCAL_BYTES = 8 * 1024 * 1024 * 1024;
export const UPLOAD_MINIMUM_INTERVAL_MINUTES = 1;
export const UPLOAD_MAXIMUM_INTERVAL_MINUTES = 10_080;

export type NetworkTransport = 'mobile' | 'wifi';
export type LocationPriority = 'BALANCED' | 'HIGH_ACCURACY';

const selectableCollectors = EVENT_SOURCE_REGISTRY.sources
  .filter((source) => source.source_kind === 'COLLECTOR' && source.selectable)
  .map((source) => source.source_id)
  .sort();

export const COLLECTOR_ORDER = selectableCollectors as readonly CollectorId[];
export type CollectorId =
  | 'accelerometer.v1'
  | 'ambient_light.v1'
  | 'app_lifecycle.v1'
  | 'battery_state.v1'
  | 'gyroscope.v1'
  | 'keyboard_touch.v1'
  | 'location.v1'
  | 'network_state.v1'
  | 'network_usage.v1'
  | 'proximity.v1'
  | 'temporal_context.v1'
  | 'usage_events.v1';

export function isCollectorId(value: unknown): value is CollectorId {
  return typeof value === 'string' && (COLLECTOR_ORDER as readonly string[]).includes(value);
}

export const NETWORK_TRANSPORTS: readonly NetworkTransport[] = ['mobile', 'wifi'];
export const LOCATION_PRIORITIES: readonly LocationPriority[] = ['BALANCED', 'HIGH_ACCURACY'];

export function isNetworkTransport(value: unknown): value is NetworkTransport {
  return typeof value === 'string' && (NETWORK_TRANSPORTS as readonly string[]).includes(value);
}

export function isLocationPriority(value: unknown): value is LocationPriority {
  return typeof value === 'string' && (LOCATION_PRIORITIES as readonly string[]).includes(value);
}

export type SensorProfile = { sampling_period_us: number; maximum_report_latency_us: number };
export type AmbientLightProfile = { sampling_period_us: number; change_threshold_millilux: number };
export type LocationProfile = {
  interval_millis: number; minimum_interval_millis: number; maximum_batch_delay_millis: number;
  minimum_displacement_millimeters: number; priority: LocationPriority;
};
export type CollectorProfileConfiguration = SensorProfile | AmbientLightProfile | Record<string, never>
  | { trajectory_sampling_hz: number } | LocationProfile | { include_bandwidth_estimates: boolean }
  | { poll_interval_seconds: number; transports: NetworkTransport[] }
  | { minimum_event_interval_ms: number; change_threshold_millimeters: number }
  | { poll_interval_seconds: number };

export interface NamedCollectorProfile<C extends CollectorProfileConfiguration = CollectorProfileConfiguration> {
  id: string;
  config: C;
}

type CollectorResource<I extends CollectorId, C extends CollectorProfileConfiguration> = {
  id: I; required: boolean; profiles: NamedCollectorProfile<C>[];
};
export type CollectorConfig =
  | CollectorResource<'accelerometer.v1' | 'gyroscope.v1', SensorProfile>
  | CollectorResource<'ambient_light.v1', AmbientLightProfile>
  | CollectorResource<'app_lifecycle.v1' | 'battery_state.v1' | 'temporal_context.v1', Record<string, never>>
  | CollectorResource<'keyboard_touch.v1', { trajectory_sampling_hz: number }>
  | CollectorResource<'location.v1', LocationProfile>
  | CollectorResource<'network_state.v1', { include_bandwidth_estimates: boolean }>
  | CollectorResource<'network_usage.v1', { poll_interval_seconds: number; transports: NetworkTransport[] }>
  | CollectorResource<'proximity.v1', { minimum_event_interval_ms: number; change_threshold_millimeters: number }>
  | CollectorResource<'usage_events.v1', { poll_interval_seconds: number }>;

export interface LocalizedText {
  default: string;
  translations: Record<string, string>;
}

export interface ChoiceOption {
  id: string;
  label: LocalizedText;
}

export type SurveyQuestion =
  | { type: 'short_text'; id: string; prompt: LocalizedText; required: boolean; maximum_length: number }
  | {
      type: 'scale'; id: string; prompt: LocalizedText; required: boolean;
      minimum: number; maximum: number; minimum_label: LocalizedText; maximum_label: LocalizedText;
    }
  | { type: 'single_choice'; id: string; prompt: LocalizedText; required: boolean; options: ChoiceOption[] }
  | {
      type: 'multiple_choice'; id: string; prompt: LocalizedText; required: boolean;
      options: ChoiceOption[]; minimum_selections: number; maximum_selections: number;
    };

export interface SurveyDefinition {
  id: string;
  title: LocalizedText;
  description: LocalizedText;
  questions: SurveyQuestion[];
}

export type InterventionAction =
  | { type: 'notification'; notification_title: string; notification_message: string }
  | { type: 'survey'; notification_title: string; notification_message: string; survey_id: string };

export interface InterventionConfig {
  id: string;
  required: boolean;
  action: InterventionAction;
}

export type EvaluationClock = 'OBSERVED_RESEARCH_TIME' | 'PRIMARY_SOURCE_TIME';
export type DurationClock = 'ACTIVE_RUNNING_TIME' | 'CALENDAR_TIME';
export type FieldOperator = 'eq' | 'ne' | 'lt' | 'lte' | 'gt' | 'gte' | 'in';
export type ResourceKind = 'collector' | 'actuator';

export interface EventIdentity {
  source_id: string;
  schema_version: number;
  event_type: string;
}

export type FieldPredicate =
  | { field: string; operator: Exclude<FieldOperator, 'in'>; value: string }
  | { field: string; operator: 'in'; values: string[] };

export interface EventMatcher {
  event: EventIdentity;
  predicates: FieldPredicate[];
}

export type Aggregate = { type: 'count' } | { type: 'sum'; field: string };
export interface NumericComparison {
  operator: Exclude<FieldOperator, 'in'>;
  value: string;
}

export type StateCondition =
  | { type: 'study_session_active' }
  | { type: 'event_latch'; set_when: EventMatcher[]; reset_when: EventMatcher[] }
  | { type: 'keyed_presence'; enter_when: EventMatcher[]; exit_when: EventMatcher[]; key_field: string }
  | { type: 'held_for'; condition: StateCondition; duration_seconds: number; clock: DurationClock }
  | { type: 'elapsed_at_least'; duration_seconds: number; clock: DurationClock }
  | {
      type: 'window_threshold'; selector: EventMatcher; window_seconds: number;
      evaluation_clock: EvaluationClock; aggregate: Aggregate; comparison: NumericComparison;
    }
  | { type: 'all' | 'any'; conditions: StateCondition[] }
  | { type: 'not'; condition: StateCondition };

export type AutomationSchedule =
  | { type: 'one_time'; offset_minutes: number; clock: DurationClock }
  | { type: 'interval'; start_offset_minutes: number; interval_minutes: number; clock: DurationClock }
  | { type: 'daily_local'; local_time: string }
  | {
      type: 'random_window';
      local_windows: { start_local_time: string; end_local_time: string }[];
      occurrences_per_window: number;
      maximum_occurrences_per_day: number;
      maximum_occurrences_total: number;
      minimum_separation_minutes: number;
    };

export type AutomationTrigger =
  | { type: 'event_match'; selector: EventMatcher; evaluation_clock: EvaluationClock }
  | { type: 'sequence'; steps: EventMatcher[]; within_seconds: number; evaluation_clock: EvaluationClock }
  | {
      type: 'window_threshold'; selector: EventMatcher; window_seconds: number;
      evaluation_clock: EvaluationClock; aggregate: Aggregate; comparison: NumericComparison;
    }
  | { type: 'condition_rising_edge'; condition: StateCondition }
  | { type: 'schedule'; schedule: AutomationSchedule };

export interface OccurrenceAutomation {
  type: 'occurrence';
  id: string;
  trigger: AutomationTrigger;
  guard: StateCondition | null;
  intervention_id: string;
  availability_seconds: number;
  cooldown: { duration_seconds: number; clock: DurationClock } | null;
  maximum_activations: number;
}

export interface ResourceBindingAutomation {
  type: 'resource_binding';
  id: string;
  resource: { kind: ResourceKind; id: string };
  cases: { condition: StateCondition; profile_id: string | null }[];
  default_profile_id: string | null;
}

export type AutomationDefinition = OccurrenceAutomation | ResourceBindingAutomation;

export interface TrafficShapingProfile {
  id: string;
  uplink_kbps: number | null;
  downlink_kbps: number | null;
}

export type TrafficShapingConfiguration =
  | Record<string, never>
  | { target_packages: string[]; profiles: TrafficShapingProfile[] };

export function trafficShapingEnabled(
  value: TrafficShapingConfiguration
): value is Extract<TrafficShapingConfiguration, { target_packages: string[] }> {
  return Object.hasOwn(value, 'target_packages');
}

export interface UploadConfig {
  endpoint: string;
  interval_minutes: number;
  allow_metered: boolean;
}

export interface StudyConfiguration {
  schema_version: number;
  platform: typeof PLATFORM;
  experiment_id: string;
  configuration_id: string;
  assigned_participant_id: string | null;
  issued_at: string;
  expires_at: string;
  minimum_client_version: string;
  title: string;
  researcher: { name: string; contact: string };
  purpose: string;
  duration_hours: number;
  consent: { document_version: string; summary: string };
  collectors: CollectorConfig[];
  surveys: SurveyDefinition[];
  interventions: InterventionConfig[];
  automations: AutomationDefinition[];
  traffic_shaping: TrafficShapingConfiguration;
  storage: { maximum_local_bytes: number };
  signer: { key_id: string; public_key: string };
  export: { researcher_key_id: string; hpke_public_key: string };
  upload: UploadConfig | null;
}

export const BOUNDS = {
  title: [1, 120], researcherName: [1, 120], researcherContact: [3, 240], purpose: [1, 2_000],
  durationHours: [1, 8_760], consentDocumentVersion: [1, 64], consentSummary: [1, 8_000],
  minimumClientVersion: [1, 2_147_483_647], notificationTitle: [1, 120], notificationMessage: [1, 500],
  availabilitySeconds: [1, 31_536_000], surveyText: [1, 2_000], shortTextMaximumLength: [1, 4_000],
  rawPublicKey: [43, 43], uploadEndpoint: [8, 2_048], samplingPeriodUs: [5_000, 1_000_000],
  ambientLightSamplingPeriodUs: [200_000, 10_000_000], maximumReportLatencyUs: [0, 60_000_000],
  changeThresholdMillilux: [0, 100_000_000], minimumEventIntervalMs: [100, 60_000],
  changeThresholdMillimeters: [0, 10_000], pollIntervalSeconds: [15, 86_400],
  intervalMillis: [1_000, 3_600_000], minimumIntervalMillis: [500, 3_600_000],
  maximumBatchDelayMillis: [0, 86_400_000], minimumDisplacementMillimeters: [0, 10_000_000],
  trajectorySamplingHz: [1, 120], trafficKbps: [1, 1_000_000]
} as const satisfies Record<string, readonly [number, number]>;
