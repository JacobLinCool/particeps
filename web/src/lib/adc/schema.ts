/**
 * Every rule the Android decoder enforces, checked here instead of on the phone.
 *
 * `StudyConfiguration`'s `init` blocks and `StudyConfigurationCodec`'s key checks throw on the
 * first failure and refuse the file outright, which is the right behaviour there and useless in a
 * form. `validate` collects all of them so the UI can mark every field at once, and reports each
 * one as a stable `code` that `IssueMessages` in `lib/i18n` turns into a sentence — the codes here
 * and the keys there are one list, split across two files that must not drift.
 */

import {
  canonicalConfigurationBytes,
  formatInstant,
  isCanonicalDecimal,
  parseInstant,
  type Instant
} from './canonical';
import { decodeBase64Url } from './crypto';
import {
  BOUNDS,
  ASSIGNED_PARTICIPANT_ID_PATTERN,
  DEFAULT_MINIMUM_CLIENT_VERSION,
  ID_PATTERN,
  MAXIMUM_INTERVENTION_OCCURRENCES,
  MAXIMUM_CONFIGURATION_BYTES,
  MAXIMUM_LOCAL_BYTES,
  MINIMUM_LOCAL_BYTES,
  PLATFORM,
  SCHEMA_VERSION,
  UPLOAD_MAXIMUM_INTERVAL_MINUTES,
  UPLOAD_MINIMUM_INTERVAL_MINUTES,
  type CollectorConfig,
  type CollectorId,
  type LocalizedText,
  type SurveyQuestion,
  type StudyConfiguration
} from './types';

export type IssueCode =
  | 'required'
  | 'id_format'
  | 'length_range'
  | 'number_range'
  | 'integer'
  | 'instant'
  | 'window_order'
  | 'collectors_empty'
  | 'duplicate_id'
  | 'transports_empty'
  | 'location_interval_order'
  | 'endpoint_scheme'
  | 'endpoint_host'
  | 'document_too_large'
  | 'signer_missing'
  | 'export_key_missing'
  | 'key_invalid'
  | 'language_tag'
  | 'unknown_reference'
  | 'selection_bounds'
  | 'schedule_bounds';

export interface Issue {
  /** Dotted path into the document, `collectors.2.config.interval_millis`. Empty is the document. */
  path: string;
  code: IssueCode;
  /** Present for the two codes whose message states the limits it was measured against. */
  bounds?: { min: number; max: number };
}

type Bounds = readonly [number, number];

const DEFAULT_VALIDITY_DAYS = 90;

/**
 * The quota a study opens on: 1 GiB, which is also a preset chip on the control. Exported so a test
 * can assert against the name rather than a literal. It lives here rather than in `types.ts`
 * because it is an authoring default and not a schema bound — `MINIMUM_LOCAL_BYTES` and
 * `MAXIMUM_LOCAL_BYTES` are the bounds, and they are the app's.
 */
export const DEFAULT_LOCAL_BYTES = 1_024 * 1_024 * 1_024;

/** Conservative UTC-18..UTC+18 local-date reach used by every Protocol v1 implementation. */
export function maximumReachableLocalDates(studyMinutes: number): number {
  return Math.ceil((studyMinutes + 36 * 60) / 1_440) + 1;
}

export function validate(configuration: StudyConfiguration): Issue[] {
  const issues: Issue[] = [];

  // A version other than the current one is not a field to correct; it is a different format.
  if (configuration.schema_version !== SCHEMA_VERSION) {
    issues.push(range('schema_version', [SCHEMA_VERSION, SCHEMA_VERSION]));
  }
  if (configuration.platform !== PLATFORM) {
    issues.push({ path: 'platform', code: 'required' });
  }
  identifier(issues, 'experiment_id', configuration.experiment_id);
  identifier(issues, 'configuration_id', configuration.configuration_id);
  if (configuration.assigned_participant_id !== null &&
      !ASSIGNED_PARTICIPANT_ID_PATTERN.test(configuration.assigned_participant_id)) {
    issues.push({ path: 'assigned_participant_id', code: 'id_format' });
  }

  const issued = instant(issues, 'issued_at', configuration.issued_at);
  const expires = instant(issues, 'expires_at', configuration.expires_at);
  if (issued && expires && !before(issued, expires)) {
    issues.push({ path: 'expires_at', code: 'window_order' });
  }

  decimal(
    issues,
    'minimum_client_version',
    configuration.minimum_client_version,
    BOUNDS.minimumClientVersion
  );
  text(issues, 'title', configuration.title, BOUNDS.title);
  text(issues, 'researcher.name', configuration.researcher.name, BOUNDS.researcherName);
  text(issues, 'researcher.contact', configuration.researcher.contact, BOUNDS.researcherContact);
  text(issues, 'purpose', configuration.purpose, BOUNDS.purpose);
  integer(issues, 'duration_hours', configuration.duration_hours, BOUNDS.durationHours);
  text(
    issues,
    'consent.document_version',
    configuration.consent.document_version,
    BOUNDS.consentDocumentVersion
  );
  text(issues, 'consent.summary', configuration.consent.summary, BOUNDS.consentSummary);

  if (configuration.collectors.length === 0) {
    issues.push({ path: 'collectors', code: 'collectors_empty' });
  }
  const collectorIds = new Set<string>();
  configuration.collectors.forEach((collector, index) => {
    const path = `collectors.${index}`;
    if (collectorIds.has(collector.id)) issues.push({ path: `${path}.id`, code: 'duplicate_id' });
    collectorIds.add(collector.id);
    collectorConfig(issues, `${path}.config`, collector);
  });

  const surveyIds = new Set<string>();
  configuration.surveys.forEach((survey, index) => {
    const path = `surveys.${index}`;
    identifier(issues, `${path}.id`, survey.id);
    if (surveyIds.has(survey.id)) issues.push({ path: `${path}.id`, code: 'duplicate_id' });
    surveyIds.add(survey.id);
    localized(issues, `${path}.title`, survey.title);
    localized(issues, `${path}.description`, survey.description);
    if (survey.questions.length < 1 || survey.questions.length > 100) {
      issues.push(range(`${path}.questions`, [1, 100]));
    }
    const questionIds = new Set<string>();
    survey.questions.forEach((question, questionIndex) => {
      const questionPath = `${path}.questions.${questionIndex}`;
      identifier(issues, `${questionPath}.id`, question.id);
      if (questionIds.has(question.id)) issues.push({ path: `${questionPath}.id`, code: 'duplicate_id' });
      questionIds.add(question.id);
      surveyQuestion(issues, questionPath, question);
    });
  });

  const interventionIds = new Set<string>();
  const triggerIds = new Set<string>();
  let maximumOccurrences = 0;
  configuration.interventions.forEach((intervention, index) => {
    const path = `interventions.${index}`;
    identifier(issues, `${path}.id`, intervention.id);
    if (interventionIds.has(intervention.id)) issues.push({ path: `${path}.id`, code: 'duplicate_id' });
    interventionIds.add(intervention.id);
    text(issues, `${path}.action.notification_title`, intervention.action.notification_title, BOUNDS.notificationTitle);
    text(issues, `${path}.action.notification_message`, intervention.action.notification_message, BOUNDS.notificationMessage);
    if (intervention.action.type === 'survey' && !surveyIds.has(intervention.action.survey_id)) {
      issues.push({ path: `${path}.action.survey_id`, code: 'unknown_reference' });
    }
    if (intervention.triggers.length === 0) issues.push({ path: `${path}.triggers`, code: 'required' });
    intervention.triggers.forEach((trigger, triggerIndex) => {
      const triggerPath = `${path}.triggers.${triggerIndex}`;
      identifier(issues, `${triggerPath}.id`, trigger.id);
      if (triggerIds.has(trigger.id)) issues.push({ path: `${triggerPath}.id`, code: 'duplicate_id' });
      triggerIds.add(trigger.id);
      integer(issues, `${triggerPath}.availability_minutes`, trigger.availability_minutes, BOUNDS.availabilityMinutes);
      validateSchedule(issues, `${triggerPath}.schedule`, trigger.schedule, configuration.duration_hours * 60);
      maximumOccurrences += occurrenceCount(trigger.schedule, configuration.duration_hours * 60);
    });
  });
  if (maximumOccurrences > MAXIMUM_INTERVENTION_OCCURRENCES) {
    issues.push({ path: 'interventions', code: 'schedule_bounds' });
  }

  integer(issues, 'storage.maximum_local_bytes', configuration.storage.maximum_local_bytes, [
    MINIMUM_LOCAL_BYTES,
    MAXIMUM_LOCAL_BYTES
  ]);

  identifier(issues, 'signer.key_id', configuration.signer.key_id);
  rawPublicKey(issues, 'signer.public_key', configuration.signer.public_key, 'signer_missing');

  identifier(issues, 'export.researcher_key_id', configuration.export.researcher_key_id);
  rawPublicKey(
    issues,
    'export.hpke_public_key',
    configuration.export.hpke_public_key,
    'export_key_missing'
  );

  upload(issues, configuration);

  const bytes = canonicalConfigurationBytes(configuration).length;
  if (bytes > MAXIMUM_CONFIGURATION_BYTES) {
    issues.push({
      path: '',
      code: 'document_too_large',
      bounds: { min: 2, max: MAXIMUM_CONFIGURATION_BYTES }
    });
  }

  return issues;
}

/** A blank document to open the form on: valid where the schema leaves no choice, empty elsewhere. */
export function emptyConfiguration(): StudyConfiguration {
  const now = Math.floor(Date.now() / 1_000);
  return {
    schema_version: SCHEMA_VERSION,
    platform: PLATFORM,
    // Inert placeholders, and so are the two key IDs below. The editor derives all four —
    // `lib/adc/ids.ts` — and the document it signs carries the derived values, never these.
    // `validate` still checks them, because it also judges documents this editor did not build
    // (`tests/hostile.spec.ts`), and because `''` is what both key IDs are until a key exists.
    experiment_id: '',
    configuration_id: '',
    // Verification needs the current time inside this window, and an issued file cannot be
    // revoked, so the default window is short enough to be a mistake worth noticing.
    issued_at: formatInstant({ second: now, nano: 0 }),
    expires_at: formatInstant({ second: now + DEFAULT_VALIDITY_DAYS * 86_400, nano: 0 }),
    minimum_client_version: DEFAULT_MINIMUM_CLIENT_VERSION,
    title: '',
    researcher: { name: '', contact: '' },
    purpose: '',
    duration_hours: 24,
    consent: { document_version: '', summary: '' },
    collectors: [],
    assigned_participant_id: null,
    surveys: [],
    interventions: [],
    storage: { maximum_local_bytes: DEFAULT_LOCAL_BYTES },
    signer: { key_id: '', public_key: '' },
    export: { researcher_key_id: '', hpke_public_key: '' },
    upload: null
  };
}

/**
 * Defaults for a collector the researcher has just switched on. Every one is the least burdensome
 * setting that is still useful, and none is `required`: a study that cannot run without a
 * permission has to say so deliberately.
 */
export function defaultCollector(id: CollectorId): CollectorConfig {
  switch (id) {
    case 'app_lifecycle.v1':
      return { id, required: false, config: {} };
    case 'accelerometer.v1':
      return {
        id,
        required: false,
        config: { sampling_period_us: 100_000, maximum_report_latency_us: 1_000_000 }
      };
    case 'battery_state.v1':
    case 'temporal_context.v1':
      return { id, required: false, config: {} };
    case 'gyroscope.v1':
      return {
        id,
        required: false,
        config: { sampling_period_us: 100_000, maximum_report_latency_us: 1_000_000 }
      };
    case 'ambient_light.v1':
      return {
        id,
        required: false,
        config: { sampling_period_us: 1_000_000, change_threshold_millilux: 1_000 }
      };
    case 'proximity.v1':
      return {
        id,
        required: false,
        config: { minimum_event_interval_ms: 1_000, change_threshold_millimeters: 0 }
      };
    case 'network_state.v1':
      return { id, required: false, config: { include_bandwidth_estimates: false } };
    case 'network_usage.v1':
      return {
        id,
        required: false,
        config: { transports: ['mobile', 'wifi'], poll_interval_minutes: 15 }
      };
    case 'usage_events.v1':
      return { id, required: false, config: { poll_interval_minutes: 15 } };
    case 'location.v1':
      return {
        id,
        required: false,
        config: {
          interval_millis: 60_000,
          minimum_interval_millis: 30_000,
          maximum_batch_delay_millis: 300_000,
          minimum_displacement_millimeters: 25_000,
          priority: 'BALANCED'
        }
      };
    case 'keyboard_touch.v1':
      return { id, required: false, config: { trajectory_sampling_hz: 60 } };
  }
}

function range(path: string, [min, max]: Bounds): Issue {
  return { path, code: 'number_range', bounds: { min, max } };
}

function identifier(issues: Issue[], path: string, value: string): void {
  if (typeof value !== 'string' || value.length === 0) issues.push({ path, code: 'required' });
  else if (!ID_PATTERN.test(value)) issues.push({ path, code: 'id_format' });
}

/** Kotlin measures `String.length` in UTF-16 code units, which is what JavaScript counts too. */
function text(issues: Issue[], path: string, value: string, [min, max]: Bounds): void {
  if (typeof value !== 'string' || value.length === 0) {
    if (min > 0) issues.push({ path, code: 'required' });
  } else if (value.length < min || value.length > max) {
    issues.push({ path, code: 'length_range', bounds: { min, max } });
  }
}

const BCP47 = /^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/;

function localized(issues: Issue[], path: string, value: LocalizedText): void {
  text(issues, `${path}.default`, value.default, BOUNDS.surveyText);
  const languages = Object.keys(value.translations);
  if (languages.length > 32) issues.push(range(`${path}.translations`, [0, 32]));
  languages.forEach((language) => {
    if (!BCP47.test(language)) issues.push({ path: `${path}.translations.${language}`, code: 'language_tag' });
    text(issues, `${path}.translations.${language}`, value.translations[language], BOUNDS.surveyText);
  });
  if (new Set(languages.map((language) => language.toLowerCase())).size !== languages.length) {
    issues.push({ path: `${path}.translations`, code: 'duplicate_id' });
  }
}

function surveyQuestion(issues: Issue[], path: string, question: SurveyQuestion): void {
  localized(issues, `${path}.prompt`, question.prompt);
  if (question.type === 'short_text') {
    integer(issues, `${path}.maximum_length`, question.maximum_length, BOUNDS.shortTextMaximumLength);
    return;
  }
  if (question.type === 'scale') {
    integer(issues, `${path}.minimum`, question.minimum, [-1_000, 1_000]);
    integer(issues, `${path}.maximum`, question.maximum, [-1_000, 1_000]);
    if (question.minimum >= question.maximum) issues.push({ path: `${path}.maximum`, code: 'window_order' });
    localized(issues, `${path}.minimum_label`, question.minimum_label);
    localized(issues, `${path}.maximum_label`, question.maximum_label);
    return;
  }
  if (question.options.length < 2 || question.options.length > 50) {
    issues.push(range(`${path}.options`, [2, 50]));
  }
  const optionIds = new Set<string>();
  question.options.forEach((option, index) => {
    identifier(issues, `${path}.options.${index}.id`, option.id);
    if (optionIds.has(option.id)) issues.push({ path: `${path}.options.${index}.id`, code: 'duplicate_id' });
    optionIds.add(option.id);
    localized(issues, `${path}.options.${index}.label`, option.label);
  });
  if (question.type === 'multiple_choice') {
    integer(issues, `${path}.minimum_selections`, question.minimum_selections, [0, question.options.length]);
    integer(issues, `${path}.maximum_selections`, question.maximum_selections, [1, question.options.length]);
    if (question.minimum_selections > question.maximum_selections ||
        (question.required && question.minimum_selections === 0)) {
      issues.push({ path: `${path}.maximum_selections`, code: 'selection_bounds' });
    }
  }
}

function validateSchedule(
  issues: Issue[],
  path: string,
  schedule: StudyConfiguration['interventions'][number]['triggers'][number]['schedule'],
  studyMinutes: number
): void {
  if (schedule.type === 'random_window') {
    if (schedule.local_windows.length < 1 || schedule.local_windows.length > 8) {
      issues.push(range(`${path}.local_windows`, [1, 8]));
    }
    const parsedWindows = schedule.local_windows.map((window, index) => {
      const windowPath = `${path}.local_windows.${index}`;
      const start = localMinute(issues, `${windowPath}.start_local_time`, window.start_local_time);
      const end = localMinute(issues, `${windowPath}.end_local_time`, window.end_local_time);
      if (start !== null && end !== null && start >= end) {
        issues.push({ path: windowPath, code: 'window_order' });
      }
      return { start, end, path: windowPath };
    });
    parsedWindows.forEach((window, index) => {
      const previous = parsedWindows[index - 1];
      if (previous && previous.end !== null && window.start !== null && previous.end > window.start) {
        issues.push({ path: window.path, code: 'window_order' });
      }
    });
    integer(issues, `${path}.occurrences_per_window`, schedule.occurrences_per_window, [1, 8]);
    integer(issues, `${path}.maximum_occurrences_per_day`, schedule.maximum_occurrences_per_day, [1, 64]);
    integer(issues, `${path}.maximum_occurrences_total`, schedule.maximum_occurrences_total, [1, 512]);
    integer(issues, `${path}.minimum_separation_minutes`, schedule.minimum_separation_minutes, [1, 1_440]);
    if (
      Number.isInteger(schedule.maximum_occurrences_per_day) &&
      schedule.maximum_occurrences_per_day > schedule.local_windows.length * schedule.occurrences_per_window
    ) {
      issues.push({ path: `${path}.maximum_occurrences_per_day`, code: 'schedule_bounds' });
    }
    if (Number.isInteger(schedule.occurrences_per_window) &&
        Number.isInteger(schedule.minimum_separation_minutes)) {
      parsedWindows.forEach((window) => {
        if (window.start !== null && window.end !== null &&
            window.end - window.start < 1 +
              (schedule.occurrences_per_window - 1) * schedule.minimum_separation_minutes) {
          issues.push({ path: window.path, code: 'schedule_bounds' });
        }
      });
      parsedWindows.forEach((window, index) => {
        const next = parsedWindows[(index + 1) % parsedWindows.length];
        if (!next || window.end === null || next.start === null) return;
        const nextStart = next.start + (index === parsedWindows.length - 1 ? 1_440 : 0);
        if (nextStart - (window.end - 1) < schedule.minimum_separation_minutes) {
          issues.push({ path: next.path, code: 'schedule_bounds' });
        }
      });
    }
    return;
  }
  if (schedule.type === 'daily_local') {
    if (!/^(?:[01][0-9]|2[0-3]):[0-5][0-9]$/.test(schedule.local_time)) {
      issues.push({ path: `${path}.local_time`, code: 'instant' });
    }
    return;
  }
  const offset = schedule.type === 'one_time' ? schedule.offset_minutes : schedule.start_offset_minutes;
  integer(issues, `${path}.${schedule.type === 'one_time' ? 'offset_minutes' : 'start_offset_minutes'}`, offset, [0, 525_599]);
  if (offset >= studyMinutes) issues.push({ path, code: 'schedule_bounds' });
  if (schedule.type === 'interval') {
    integer(issues, `${path}.interval_minutes`, schedule.interval_minutes, [1, 525_600]);
    if (schedule.interval_minutes > 0 && Math.ceil((studyMinutes - offset) / schedule.interval_minutes) > 10_000) {
      issues.push({ path, code: 'schedule_bounds' });
    }
  }
}

function occurrenceCount(
  schedule: StudyConfiguration['interventions'][number]['triggers'][number]['schedule'],
  studyMinutes: number
): number {
  if (!Number.isInteger(studyMinutes) || studyMinutes <= 0) return 0;
  if (schedule.type === 'one_time') return 1;
  if (schedule.type === 'daily_local') return maximumReachableLocalDates(studyMinutes);
  if (schedule.type === 'random_window') {
    return Number.isInteger(schedule.maximum_occurrences_total) ? schedule.maximum_occurrences_total : 0;
  }
  if (!Number.isInteger(schedule.start_offset_minutes) || !Number.isInteger(schedule.interval_minutes) ||
      schedule.start_offset_minutes < 0 || schedule.start_offset_minutes >= studyMinutes ||
      schedule.interval_minutes <= 0) return 0;
  return Math.ceil((studyMinutes - schedule.start_offset_minutes) / schedule.interval_minutes);
}

const LOCAL_TIME = /^(?:[01][0-9]|2[0-3]):[0-5][0-9]$/;

function localMinute(issues: Issue[], path: string, value: string): number | null {
  if (!LOCAL_TIME.test(value)) {
    issues.push({ path, code: 'instant' });
    return null;
  }
  return Number(value.slice(0, 2)) * 60 + Number(value.slice(3));
}

function integer(issues: Issue[], path: string, value: number, bounds: Bounds): void {
  if (typeof value !== 'number' || !Number.isInteger(value)) issues.push({ path, code: 'integer' });
  else if (value < bounds[0] || value > bounds[1]) issues.push(range(path, bounds));
}

function inRange(value: number, [min, max]: Bounds): boolean {
  return Number.isInteger(value) && value >= min && value <= max;
}

function instant(issues: Issue[], path: string, value: string): Instant | null {
  if (typeof value !== 'string' || value.length === 0) {
    issues.push({ path, code: 'required' });
    return null;
  }
  const parsed = parseInstant(value);
  if (!parsed) issues.push({ path, code: 'instant' });
  return parsed;
}

/** `issuedAt < expiresAt`, strictly: a window that opens and closes at once is not a window. */
function before(first: Instant, second: Instant): boolean {
  return first.second === second.second ? first.nano < second.nano : first.second < second.second;
}

function collectorConfig(issues: Issue[], path: string, collector: CollectorConfig): void {
  switch (collector.id) {
    case 'app_lifecycle.v1':
      return;
    case 'accelerometer.v1':
    case 'gyroscope.v1':
      integer(
        issues,
        `${path}.sampling_period_us`,
        collector.config.sampling_period_us,
        BOUNDS.samplingPeriodUs
      );
      integer(
        issues,
        `${path}.maximum_report_latency_us`,
        collector.config.maximum_report_latency_us,
        BOUNDS.maximumReportLatencyUs
      );
      return;
    case 'battery_state.v1':
    case 'temporal_context.v1':
      return;
    case 'ambient_light.v1':
      integer(
        issues,
        `${path}.sampling_period_us`,
        collector.config.sampling_period_us,
        BOUNDS.ambientLightSamplingPeriodUs
      );
      integer(
        issues,
        `${path}.change_threshold_millilux`,
        collector.config.change_threshold_millilux,
        BOUNDS.changeThresholdMillilux
      );
      return;
    case 'proximity.v1':
      integer(
        issues,
        `${path}.minimum_event_interval_ms`,
        collector.config.minimum_event_interval_ms,
        BOUNDS.minimumEventIntervalMs
      );
      integer(
        issues,
        `${path}.change_threshold_millimeters`,
        collector.config.change_threshold_millimeters,
        BOUNDS.changeThresholdMillimeters
      );
      return;
    case 'network_state.v1':
      return;
    case 'network_usage.v1':
      // `Array.isArray` rather than `.length`, because the string "x" also has a length of one and
      // the encoder's `includes` filter turns it into `"transports":[]` — valid here, refused by
      // `NetworkUsageConfiguration`'s `require(transports.isNotEmpty())` on the phone.
      if (!Array.isArray(collector.config.transports) || collector.config.transports.length === 0) {
        issues.push({ path: `${path}.transports`, code: 'transports_empty' });
      }
      integer(
        issues,
        `${path}.poll_interval_minutes`,
        collector.config.poll_interval_minutes,
        BOUNDS.pollIntervalMinutes
      );
      return;
    case 'usage_events.v1':
      integer(
        issues,
        `${path}.poll_interval_minutes`,
        collector.config.poll_interval_minutes,
        BOUNDS.pollIntervalMinutes
      );
      return;
    case 'location.v1': {
      const config = collector.config;
      integer(issues, `${path}.interval_millis`, config.interval_millis, BOUNDS.intervalMillis);
      integer(
        issues,
        `${path}.minimum_interval_millis`,
        config.minimum_interval_millis,
        BOUNDS.minimumIntervalMillis
      );
      // The fastest interval is bounded above by the interval itself, not by the schema's ceiling.
      if (
        inRange(config.minimum_interval_millis, BOUNDS.minimumIntervalMillis) &&
        config.minimum_interval_millis > config.interval_millis
      ) {
        issues.push({ path: `${path}.minimum_interval_millis`, code: 'location_interval_order' });
      }
      integer(
        issues,
        `${path}.maximum_batch_delay_millis`,
        config.maximum_batch_delay_millis,
        BOUNDS.maximumBatchDelayMillis
      );
      integer(
        issues,
        `${path}.minimum_displacement_millimeters`,
        config.minimum_displacement_millimeters,
        BOUNDS.minimumDisplacementMillimeters
      );
      return;
    }
    case 'keyboard_touch.v1':
      integer(
        issues,
        `${path}.trajectory_sampling_hz`,
        collector.config.trajectory_sampling_hz,
        BOUNDS.trajectorySamplingHz
      );
      return;
  }
}

function decimal(issues: Issue[], path: string, value: string, bounds: Bounds): void {
  if (!isCanonicalDecimal(value)) {
    issues.push({ path, code: 'integer' });
    return;
  }
  const parsed = BigInt(value);
  if (parsed < BigInt(bounds[0]) || parsed > BigInt(bounds[1])) issues.push(range(path, bounds));
}

function rawPublicKey(
  issues: Issue[],
  path: string,
  value: string,
  missing: 'signer_missing' | 'export_key_missing'
): void {
  if (!value) {
    issues.push({ path, code: missing });
    return;
  }
  try {
    decodeBase64Url(value, 32);
  } catch {
    issues.push({ path, code: 'key_invalid' });
  }
}

/**
 * Upload is all or nothing: `null` here is the empty object the encoder writes, and anything else
 * has to carry every field, so a study cannot half-declare delivery and inherit an endpoint or a
 * cadence it never stated.
 */
function upload(issues: Issue[], configuration: StudyConfiguration): void {
  const value = configuration.upload;
  if (!value) return;
  const endpoint = value.endpoint;
  if (typeof endpoint !== 'string' || endpoint.length === 0) {
    issues.push({ path: 'upload.endpoint', code: 'required' });
  } else if (
    endpoint.length < BOUNDS.uploadEndpoint[0] ||
    endpoint.length > BOUNDS.uploadEndpoint[1]
  ) {
    issues.push({
      path: 'upload.endpoint',
      code: 'length_range',
      bounds: { min: BOUNDS.uploadEndpoint[0], max: BOUNDS.uploadEndpoint[1] }
    });
  } else if (!endpoint.startsWith(HTTPS)) {
    issues.push({ path: 'upload.endpoint', code: 'endpoint_scheme' });
  } else if (!hasHost(endpoint)) {
    issues.push({ path: 'upload.endpoint', code: 'endpoint_host' });
  }
  integer(issues, 'upload.interval_minutes', value.interval_minutes, [
    UPLOAD_MINIMUM_INTERVAL_MINUTES,
    UPLOAD_MAXIMUM_INTERVAL_MINUTES
  ]);
}

const HTTPS = 'https://';
const LABEL = /^[a-z\d](?:[a-z\d-]*[a-z\d])?$/i;
const IPV4 = /^\d{1,3}(?:\.\d{1,3}){3}$/;
const ILLEGAL_IN_URI = /[\u0000-\u0020\u007f"<>\\^`{|}]|%(?![\da-f]{2})/i;

/**
 * `java.net.URI(endpoint).host` has to be non-empty, and that parser is stricter than the browser's
 * in every direction that matters here: it refuses the whole URI over a character `URL` would have
 * percent-encoded, it does not collapse `https:///path` into a host, it does not punycode an
 * international name, and it wants a server-based authority, so an underscore leaves `host` null.
 * A multi-label name must also end in a label starting with a letter, unless the host is an IP
 * address. Reading the authority out of the text rather than out of `URL` is what keeps those
 * differences from producing a configuration that signs here and is refused on the phone.
 */
function hasHost(endpoint: string): boolean {
  if (ILLEGAL_IN_URI.test(endpoint)) return false;
  const authority = endpoint.slice(HTTPS.length).split(/[/?#]/)[0];
  const named = authority.slice(authority.lastIndexOf('@') + 1);
  const host = named.startsWith('[')
    ? named.slice(0, named.indexOf(']') + 1)
    : named.replace(/:\d*$/, '');
  if (host.length === 0) return false;
  if (host.startsWith('[')) return host.endsWith(']') && host.length > 2;
  if (IPV4.test(host)) return host.split('.').every((octet) => Number(octet) <= 255);
  const labels = host.replace(/\.$/, '').split('.');
  if (!labels.every((label) => LABEL.test(label))) return false;
  return labels.length === 1 || /^[a-z]/i.test(labels[labels.length - 1]);
}
