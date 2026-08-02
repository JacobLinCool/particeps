/**
 * The study configuration schema, mirrored from `core/study-definition`.
 *
 * This file is the contract every other module in `lib/adc` codes against. It exists because the
 * configuration this site produces is only useful if the Android app accepts it, and the app's
 * codec is a closed world: a root key too many, a number in the wrong form, or a bound off by one
 * and the file is refused outright with no fallback reader and no migration path.
 *
 * Bounds are transcribed from `StudyConfiguration.kt` and `StudyConfigurationCodec.kt`. Keep them
 * in step: `tests/compat.spec.ts` proves the encoder byte-matches the Kotlin one, but nothing
 * proves these numbers still match, so they are worth re-reading against the source when the
 * schema moves.
 */

export const SCHEMA_VERSION = 1;
export const MAXIMUM_INTERVENTION_OCCURRENCES = 512;

/**
 * Pinned. The lowest `versionCode` the schema allows, and the only one this page authors — there is
 * no control for it, because a floor a researcher has no way to measure is a floor they cannot set
 * honestly. `BOUNDS.minimumAppVersion` stays: `validate` still has to judge documents written
 * elsewhere, and `researcher-tools` can raise the floor deliberately.
 */
export const DEFAULT_MINIMUM_APP_VERSION = 1;

/** `[a-z0-9][a-z0-9-]{2,63}` — stable schema IDs. */
export const ID_PATTERN = /^[a-z0-9][a-z0-9-]{2,63}$/;
export const ASSIGNED_PARTICIPANT_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

export const MINIMUM_LOCAL_BYTES = 8 * 1024 * 1024;
export const MAXIMUM_LOCAL_BYTES = 8 * 1024 * 1024 * 1024;

export const UPLOAD_MINIMUM_INTERVAL_MINUTES = 1;
export const UPLOAD_MAXIMUM_INTERVAL_MINUTES = 10_080;

export type NetworkTransport = 'mobile' | 'wifi';
export type LocationPriority = 'BALANCED' | 'HIGH_ACCURACY';

export type CollectorId =
  | 'app_lifecycle.v1'
  | 'accelerometer.v1'
  | 'network_state.v1'
  | 'network_usage.v1'
  | 'usage_events.v1'
  | 'location.v1'
  | 'keyboard_touch.v1';

/**
 * The codec's emission order, which is also the docs' table order and the demo file's. It is a
 * value as well as a type because two things need it at runtime: the editor keeps `collectors` in
 * this order, and `parse.ts` has to decide whether an id read out of a file is one of these at all.
 */
export const COLLECTOR_ORDER: readonly CollectorId[] = [
  'app_lifecycle.v1',
  'accelerometer.v1',
  'network_state.v1',
  'network_usage.v1',
  'usage_events.v1',
  'location.v1',
  'keyboard_touch.v1'
];

export const NETWORK_TRANSPORTS: readonly NetworkTransport[] = ['mobile', 'wifi'];
export const LOCATION_PRIORITIES: readonly LocationPriority[] = ['BALANCED', 'HIGH_ACCURACY'];

export function isCollectorId(value: unknown): value is CollectorId {
  return typeof value === 'string' && (COLLECTOR_ORDER as readonly string[]).includes(value);
}

export function isNetworkTransport(value: unknown): value is NetworkTransport {
  return typeof value === 'string' && (NETWORK_TRANSPORTS as readonly string[]).includes(value);
}

export function isLocationPriority(value: unknown): value is LocationPriority {
  return typeof value === 'string' && (LOCATION_PRIORITIES as readonly string[]).includes(value);
}

/** The order the app's codec emits collectors in is the order of this array in the document. */
export type CollectorConfig =
  | { id: 'app_lifecycle.v1'; required: boolean; config: Record<string, never> }
  | {
      id: 'accelerometer.v1';
      required: boolean;
      config: { sampling_period_us: number; maximum_report_latency_us: number };
    }
  | {
      id: 'network_state.v1';
      required: boolean;
      config: { include_bandwidth_estimates: boolean };
    }
  | {
      id: 'network_usage.v1';
      required: boolean;
      config: { transports: NetworkTransport[]; poll_interval_minutes: number };
    }
  | { id: 'usage_events.v1'; required: boolean; config: { poll_interval_minutes: number } }
  | {
      id: 'location.v1';
      required: boolean;
      config: {
        interval_millis: number;
        minimum_interval_millis: number;
        maximum_batch_delay_millis: number;
        /** A Kotlin Float. See `formatFloat` in canonical.ts — this does not round-trip as a JS number. */
        minimum_displacement_meters: number;
        priority: LocationPriority;
      };
    }
  | { id: 'keyboard_touch.v1'; required: boolean; config: { trajectory_sampling_hz: number } };

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

export type RelativeClock = 'CALENDAR_TIME' | 'ACTIVE_RUNNING_TIME';
export type InterventionSchedule =
  | { type: 'one_time'; offset_minutes: number; clock: RelativeClock }
  | { type: 'interval'; start_offset_minutes: number; interval_minutes: number; clock: RelativeClock }
  | { type: 'daily_local'; local_time: string };

export interface InterventionTrigger {
  id: string;
  schedule: InterventionSchedule;
  availability_minutes: number;
}

export type InterventionAction =
  | { type: 'notification'; notification_title: string; notification_message: string }
  | { type: 'survey'; notification_title: string; notification_message: string; survey_id: string };

export interface InterventionConfig {
  id: string;
  action: InterventionAction;
  triggers: InterventionTrigger[];
}

export interface UploadConfig {
  endpoint: string;
  interval_minutes: number;
  allow_metered: boolean;
}

/** A Tink keyset document, kept as parsed JSON so it can be re-emitted in its original key order. */
export interface TinkKeyset {
  primaryKeyId: number;
  key: Array<{
    keyData: { typeUrl: string; value: string; keyMaterialType: string };
    status: string;
    keyId: number;
    outputPrefixType: string;
  }>;
}

export interface StudyConfiguration {
  schema_version: number;
  experiment_id: string;
  configuration_id: string;
  assigned_participant_id: string | null;
  /** ISO-8601 instant, exactly as `Instant.toString()` renders it. */
  issued_at: string;
  expires_at: string;
  minimum_app_version: number;
  title: string;
  researcher: { name: string; contact: string };
  purpose: string;
  duration_hours: number;
  consent: { document_version: string; summary: string };
  collectors: CollectorConfig[];
  surveys: SurveyDefinition[];
  interventions: InterventionConfig[];
  storage: { maximum_local_bytes: number };
  signer: { key_id: string; public_key: string };
  export: { researcher_key_id: string; tink_hpke_public_keyset: TinkKeyset };
  /** `null` means the study does not upload; the encoder writes `"upload":{}` for it. */
  upload: UploadConfig | null;
}

/** Field bounds, in the same units the schema uses. Inclusive at both ends. */
export const BOUNDS = {
  title: [1, 120],
  researcherName: [1, 120],
  researcherContact: [3, 240],
  purpose: [1, 2_000],
  durationHours: [1, 8_760],
  consentDocumentVersion: [1, 64],
  consentSummary: [1, 8_000],
  // `require(minimumAppVersion > 0)` on a Kotlin `Int`, so the ceiling is the Int's, not "any
  // positive number": `requireInt` throws above it and the file is refused before the bound is read.
  minimumAppVersion: [1, 2_147_483_647],
  notificationTitle: [1, 120],
  notificationMessage: [1, 500],
  availabilityMinutes: [1, 525_600],
  surveyText: [1, 2_000],
  shortTextMaximumLength: [1, 4_000],
  signerPublicKey: [32, 1_024],
  uploadEndpoint: [8, 2_048],
  samplingPeriodUs: [5_000, 1_000_000],
  maximumReportLatencyUs: [0, 60_000_000],
  pollIntervalMinutes: [1, 1_440],
  intervalMillis: [1_000, 3_600_000],
  minimumIntervalMillis: [500, 3_600_000],
  maximumBatchDelayMillis: [0, 86_400_000],
  minimumDisplacementMeters: [0, 10_000],
  trajectorySamplingHz: [1, 120]
} as const satisfies Record<string, readonly [number, number]>;

/** Whole configuration document, in bytes. */
export const MAXIMUM_CONFIGURATION_BYTES = 1_048_576;
