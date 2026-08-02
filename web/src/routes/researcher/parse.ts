/**
 * Reading a study back in: a canonical `.json`, a draft `.json`, or a signed `.adccfg`.
 *
 * `lib/adc` writes these three and never reads them, because the app and the CLI are the readers.
 * The editor is the fourth, and the cross-language workflow depends on it — one signed
 * configuration variant means opening the first one, changing the signed content, and issuing a
 * new `configuration_id` under the same signer.
 *
 * Nothing here judges a *value*. `schema.ts` refuses a document; this only decides whether the bytes
 * were a study configuration at all, and fills the shape so the editor always has something to draw.
 *
 * Shape is the part that has to be exact. `canonicalize` and `validate` are written against the
 * types, so a collector carrying no `config`, or a `transports` that is a string, is not a document
 * with a bad field — it is a `TypeError` out of a `$derived`, and the page cannot even say what was
 * wrong. Everything below therefore either produces a value of the declared type or refuses the
 * file. The three discriminants — collector id, transport, location priority — are refused rather
 * than defaulted, because `decodeCollector` refuses them too: a study re-signed with a source
 * quietly dropped or a priority quietly changed is worse than a study that will not open.
 */

import {
  isCollectorId,
  isLocationPriority,
  isNetworkTransport,
  MAXIMUM_CONFIGURATION_BYTES,
  type CollectorConfig,
  type InterventionConfig,
  type InterventionSchedule,
  type LocalizedText,
  type NetworkTransport,
  type SurveyDefinition,
  type SurveyQuestion,
  type StudyConfiguration
} from '$lib/adc/types';

const MAGIC = 'ADCCFG01';
const HEADER_BYTES = MAGIC.length + 2 + 4 + 2;

export interface Envelope {
  signerKeyId: string;
  configurationBytes: Uint8Array;
  signature: Uint8Array;
}

/** The inverse of `encodeEnvelope`, with the same bounds: a length that lies is a refused file. */
export function decodeEnvelope(bytes: Uint8Array): Envelope {
  if (bytes.length < HEADER_BYTES) throw new Error('envelope_short');
  for (let index = 0; index < MAGIC.length; index += 1) {
    if (bytes[index] !== MAGIC.charCodeAt(index)) throw new Error('envelope_magic');
  }
  const header = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const keyIdLength = header.getUint16(8);
  const configurationLength = header.getInt32(10);
  const signatureLength = header.getUint16(14);
  if (keyIdLength < 3 || keyIdLength > 64) throw new Error('envelope_key_id');
  if (configurationLength < 2 || configurationLength > MAXIMUM_CONFIGURATION_BYTES) {
    throw new Error('envelope_configuration');
  }
  if (signatureLength < 32 || signatureLength > 128) throw new Error('envelope_signature');
  if (bytes.length !== HEADER_BYTES + keyIdLength + configurationLength + signatureLength) {
    throw new Error('envelope_length');
  }
  const keyIdEnd = HEADER_BYTES + keyIdLength;
  const configurationEnd = keyIdEnd + configurationLength;
  return {
    signerKeyId: new TextDecoder().decode(bytes.subarray(HEADER_BYTES, keyIdEnd)),
    configurationBytes: bytes.slice(keyIdEnd, configurationEnd),
    signature: bytes.slice(configurationEnd)
  };
}

export function isEnvelope(bytes: Uint8Array): boolean {
  if (bytes.length < MAGIC.length) return false;
  for (let index = 0; index < MAGIC.length; index += 1) {
    if (bytes[index] !== MAGIC.charCodeAt(index)) return false;
  }
  return true;
}

/**
 * A closed-world structural read, field by field. Unknown, absent, or mistyped fields are refused
 * exactly as the Android codec refuses them. The editor never invents replacement study content.
 *
 * `tink_hpke_public_keyset` is the exception and is kept exactly as parsed, property order
 * included — the canonicaliser re-emits it in the order it was built, so re-ordering it here would
 * change the bytes that get signed.
 */
export function parseConfiguration(bytes: Uint8Array): StudyConfiguration {
  const source = isEnvelope(bytes) ? decodeEnvelope(bytes).configurationBytes : bytes;
  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder().decode(source));
  } catch {
    throw new Error('parse_json');
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('parse_shape');
  const raw = parsed as Record<string, unknown>;
  if (!('experiment_id' in raw) && !('collectors' in raw)) throw new Error('parse_shape');
  requireExactKeys(raw, ROOT_KEYS);

  const researcher = object(raw.researcher);
  const consent = object(raw.consent);
  const storage = object(raw.storage);
  const signer = object(raw.signer);
  const exported = object(raw.export);
  const upload = object(raw.upload);

  requireExactKeys(researcher, ['name', 'contact']);
  requireExactKeys(consent, ['document_version', 'summary']);
  requireExactKeys(storage, ['maximum_local_bytes']);
  requireExactKeys(signer, ['key_id', 'public_key']);
  requireExactKeys(exported, ['researcher_key_id', 'tink_hpke_public_keyset']);
  if (Object.keys(upload).length > 0) requireExactKeys(upload, ['endpoint', 'interval_minutes', 'allow_metered']);

  return {
    schema_version: numeric(raw.schema_version),
    experiment_id: string(raw.experiment_id),
    configuration_id: string(raw.configuration_id),
    assigned_participant_id: nullableString(raw.assigned_participant_id),
    issued_at: string(raw.issued_at),
    expires_at: string(raw.expires_at),
    minimum_app_version: appVersion(raw.minimum_app_version),
    title: string(raw.title),
    researcher: {
      name: string(researcher.name),
      contact: string(researcher.contact)
    },
    purpose: string(raw.purpose),
    duration_hours: numeric(raw.duration_hours),
    consent: {
      document_version: string(consent.document_version),
      summary: string(consent.summary)
    },
    collectors: array(raw.collectors).map(collector),
    surveys: array(raw.surveys).map(survey),
    interventions: array(raw.interventions).map(intervention),
    storage: {
      maximum_local_bytes: numeric(storage.maximum_local_bytes)
    },
    signer: {
      key_id: string(signer.key_id),
      public_key: string(signer.public_key)
    },
    export: {
      researcher_key_id: string(exported.researcher_key_id),
      tink_hpke_public_keyset: isKeysetShaped(exported.tink_hpke_public_keyset)
        ? (exported.tink_hpke_public_keyset as StudyConfiguration['export']['tink_hpke_public_keyset'])
        : fail('parse_keyset')
    },
    // `{}` is how an absent upload block is written, so an empty object is "no", not "malformed".
    upload:
      Object.keys(upload).length > 0
        ? {
            endpoint: string(upload.endpoint),
            interval_minutes: numeric(upload.interval_minutes),
            allow_metered: boolean(upload.allow_metered)
          }
        : null
  };
}

const ROOT_KEYS = [
  'schema_version', 'experiment_id', 'configuration_id', 'assigned_participant_id', 'issued_at',
  'expires_at', 'minimum_app_version', 'title', 'researcher', 'purpose', 'duration_hours', 'consent',
  'collectors', 'surveys', 'interventions', 'storage', 'signer', 'export', 'upload'
] as const;

function requireExactKeys(value: Record<string, unknown>, expected: readonly string[]): void {
  const actual = Object.keys(value);
  if (actual.length !== expected.length || expected.some((key) => !(key in value))) {
    throw new Error('parse_keys');
  }
}

const object = (value: unknown): Record<string, unknown> =>
  value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : fail('parse_object');

const string = (value: unknown): string => typeof value === 'string' ? value : fail('parse_string');
const nullableString = (value: unknown): string | null =>
  value === null || typeof value === 'string' ? value : fail('parse_string');
const boolean = (value: unknown): boolean => typeof value === 'boolean' ? value : fail('parse_boolean');
const array = (value: unknown): unknown[] => Array.isArray(value) ? value : fail('parse_array');

const numeric = (value: unknown): number =>
  typeof value === 'number' && Number.isFinite(value) ? value : fail('parse_number');

function fail(message: string): never { throw new Error(message); }

/**
 * Kept as a named seam because this field has no editor control. Its value is never clamped:
 * `validate` reports an illegal floor, and a legal value round-trips byte for byte.
 */
const appVersion = (value: unknown): number => numeric(value);

/**
 * Whatever it turns out to be, it is kept property-for-property: the canonicaliser re-emits this
 * object in the order it was parsed in, so re-ordering it here would change the bytes that get
 * signed. Only "is it an object at all" is decided; `validate` decides whether Tink can use it.
 */
function isKeysetShaped(value: unknown): boolean {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

/**
 * One collector with the codec's exact closed-world shape. Values of the right type are carried
 * through even when out of range so `validate` can point at them; absent or mistyped structure is
 * refused because inventing a signed value would change the study.
 */
function collector(raw: unknown): CollectorConfig {
  const source = object(raw);
  requireExactKeys(source, ['id', 'required', 'config']);
  if (!isCollectorId(source.id)) throw new Error('parse_collector');
  const config = object(source.config);
  const required = boolean(source.required);

  switch (source.id) {
    case 'app_lifecycle.v1':
      requireExactKeys(config, []);
      return { id: source.id, required, config: {} };
    case 'accelerometer.v1':
      requireExactKeys(config, ['sampling_period_us', 'maximum_report_latency_us']);
      return {
        id: source.id,
        required,
        config: {
          sampling_period_us: numeric(config.sampling_period_us),
          maximum_report_latency_us: numeric(config.maximum_report_latency_us)
        }
      };
    case 'network_state.v1':
      requireExactKeys(config, ['include_bandwidth_estimates']);
      return {
        id: source.id,
        required,
        config: { include_bandwidth_estimates: boolean(config.include_bandwidth_estimates) }
      };
    case 'network_usage.v1':
      requireExactKeys(config, ['transports', 'poll_interval_minutes']);
      return {
        id: source.id,
        required,
        config: {
          transports: transports(config.transports),
          poll_interval_minutes: numeric(config.poll_interval_minutes)
        }
      };
    case 'usage_events.v1':
      requireExactKeys(config, ['poll_interval_minutes']);
      return {
        id: source.id,
        required,
        config: { poll_interval_minutes: numeric(config.poll_interval_minutes) }
      };
    case 'location.v1': {
      requireExactKeys(config, [
        'interval_millis', 'minimum_interval_millis', 'maximum_batch_delay_millis',
        'minimum_displacement_meters', 'priority'
      ]);
      const priority = config.priority;
      if (!isLocationPriority(priority)) throw new Error('parse_collector');
      return {
        id: source.id,
        required,
        config: {
          interval_millis: numeric(config.interval_millis),
          minimum_interval_millis: numeric(config.minimum_interval_millis),
          maximum_batch_delay_millis: numeric(config.maximum_batch_delay_millis),
          minimum_displacement_meters: numeric(config.minimum_displacement_meters),
          priority
        }
      };
    }
    case 'keyboard_touch.v1': {
      requireExactKeys(config, ['trajectory_sampling_hz']);
      return {
        id: source.id,
        required,
        config: { trajectory_sampling_hz: numeric(config.trajectory_sampling_hz) }
      };
    }
  }
}

/**
 * `NetworkTransport.valueOf` refuses an unknown name, so an unknown name refuses the file.
 *
 * A non-array refuses it too, which is stricter than the treatment every scalar above gets. The
 * asymmetry is deliberate: this set is participant-visible on the data screen, and its default is
 * *broader* than any of its members, so quietly falling back would widen collection rather than
 * narrow it. `"wifi"` used to survive as `["wifi"]` by accident, which is the worse half of the
 * same problem.
 */
function transports(raw: unknown): NetworkTransport[] {
  if (!Array.isArray(raw)) throw new Error('parse_collector');
  const named: NetworkTransport[] = [];
  for (const entry of raw) {
    if (!isNetworkTransport(entry)) throw new Error('parse_collector');
    named.push(entry);
  }
  return named;
}

function localized(raw: unknown): LocalizedText {
  const source = object(raw);
  requireExactKeys(source, ['default', 'translations']);
  const translations = object(source.translations);
  return {
    default: string(source.default),
    translations: Object.fromEntries(Object.entries(translations).map(([key, value]) => [key, string(value)]))
  };
}

function survey(raw: unknown): SurveyDefinition {
  const source = object(raw);
  requireExactKeys(source, ['id', 'title', 'description', 'questions']);
  return {
    id: string(source.id),
    title: localized(source.title),
    description: localized(source.description),
    questions: array(source.questions).map(question)
  };
}

function question(raw: unknown): SurveyQuestion {
  const source = object(raw);
  const commonKeys = ['type', 'id', 'prompt', 'required'];
  const common = {
    id: string(source.id),
    prompt: localized(source.prompt),
    required: boolean(source.required)
  };
  switch (source.type) {
    case 'short_text':
      requireExactKeys(source, [...commonKeys, 'maximum_length']);
      return { type: 'short_text', ...common, maximum_length: numeric(source.maximum_length) };
    case 'scale': {
      requireExactKeys(source, [...commonKeys, 'minimum', 'maximum', 'minimum_label', 'maximum_label']);
      return {
        type: 'scale', ...common, minimum: numeric(source.minimum), maximum: numeric(source.maximum),
        minimum_label: localized(source.minimum_label), maximum_label: localized(source.maximum_label)
      };
    }
    case 'single_choice':
      requireExactKeys(source, [...commonKeys, 'options']);
      return { type: 'single_choice', ...common, options: choices(source.options) };
    case 'multiple_choice': {
      requireExactKeys(source, [...commonKeys, 'options', 'minimum_selections', 'maximum_selections']);
      return {
        type: 'multiple_choice', ...common, options: choices(source.options),
        minimum_selections: numeric(source.minimum_selections),
        maximum_selections: numeric(source.maximum_selections)
      };
    }
    default: throw new Error('parse_question');
  }
}

function choices(raw: unknown) {
  return array(raw).map((value) => {
    const choice = object(value);
    requireExactKeys(choice, ['id', 'label']);
    return { id: string(choice.id), label: localized(choice.label) };
  });
}

function intervention(raw: unknown): InterventionConfig {
  const source = object(raw);
  requireExactKeys(source, ['id', 'action', 'triggers']);
  const action = object(source.action);
  const actionType = action.type;
  if (actionType !== 'notification' && actionType !== 'survey') throw new Error('parse_action');
  let parsedAction: InterventionConfig['action'];
  if (actionType === 'survey') {
    requireExactKeys(action, ['type', 'notification_title', 'notification_message', 'survey_id']);
    parsedAction = {
        type: 'survey',
        notification_title: string(action.notification_title),
        notification_message: string(action.notification_message),
        survey_id: string(action.survey_id)
    };
  } else {
    requireExactKeys(action, ['type', 'notification_title', 'notification_message']);
    parsedAction = {
      type: 'notification',
      notification_title: string(action.notification_title),
      notification_message: string(action.notification_message)
    };
  }
  return {
    id: string(source.id),
    action: parsedAction,
    triggers: array(source.triggers).map((rawTrigger) => {
      const trigger = object(rawTrigger);
      requireExactKeys(trigger, ['id', 'schedule', 'availability_minutes']);
      const schedule = object(trigger.schedule);
      const type = schedule.type;
      if (type !== 'one_time' && type !== 'interval' && type !== 'daily_local') throw new Error('parse_schedule');
      let parsedSchedule: InterventionSchedule;
      if (type === 'daily_local') {
        requireExactKeys(schedule, ['type', 'local_time']);
        parsedSchedule = { type, local_time: string(schedule.local_time) };
      } else {
        const clock = schedule.clock;
        if (clock !== 'CALENDAR_TIME' && clock !== 'ACTIVE_RUNNING_TIME') throw new Error('parse_schedule');
        if (type === 'one_time') {
          requireExactKeys(schedule, ['type', 'offset_minutes', 'clock']);
          parsedSchedule = { type, offset_minutes: numeric(schedule.offset_minutes), clock };
        } else {
          requireExactKeys(schedule, ['type', 'start_offset_minutes', 'interval_minutes', 'clock']);
          parsedSchedule = {
            type,
            start_offset_minutes: numeric(schedule.start_offset_minutes),
            interval_minutes: numeric(schedule.interval_minutes),
            clock
          };
        }
      }
      return {
        id: string(trigger.id), schedule: parsedSchedule,
        availability_minutes: numeric(trigger.availability_minutes)
      };
    })
  };
}
