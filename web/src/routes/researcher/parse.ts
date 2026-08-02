/**
 * Reading a study back in: a canonical `.json`, a draft `.json`, or a signed `.adccfg`.
 *
 * `lib/adc` writes these three and never reads them, because the app and the CLI are the readers.
 * The editor is the fourth, and the cross-language workflow depends on it — one signed
 * configuration per language means opening the first one, changing the prose, and issuing a new
 * `configuration_id` under the same signer.
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

import { defaultCollector, emptyConfiguration } from '$lib/adc/schema';
import {
  BOUNDS,
  DEFAULT_MINIMUM_APP_VERSION,
  isCollectorId,
  isLocationPriority,
  isNetworkTransport,
  MAXIMUM_CONFIGURATION_BYTES,
  NETWORK_TRANSPORTS,
  type CollectorConfig,
  type NetworkTransport,
  type PromptConfig,
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
 * A structural read, field by field, defaulting anything absent. Unknown keys are dropped rather
 * than carried: the encoder emits a fixed set in a fixed order, so a key it would not write is a
 * key that cannot survive a round trip anyway.
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

  const base = emptyConfiguration();
  const researcher = object(raw.researcher);
  const consent = object(raw.consent);
  const storage = object(raw.storage);
  const signer = object(raw.signer);
  const exported = object(raw.export);
  const upload = object(raw.upload);

  return {
    schema_version: numeric(raw.schema_version, base.schema_version),
    experiment_id: string(raw.experiment_id, ''),
    configuration_id: string(raw.configuration_id, ''),
    issued_at: string(raw.issued_at, base.issued_at),
    expires_at: string(raw.expires_at, base.expires_at),
    minimum_app_version: appVersion(raw.minimum_app_version),
    title: string(raw.title, ''),
    researcher: {
      name: string(researcher.name, ''),
      contact: string(researcher.contact, '')
    },
    purpose: string(raw.purpose, ''),
    duration_hours: numeric(raw.duration_hours, base.duration_hours),
    consent: {
      document_version: string(consent.document_version, ''),
      summary: string(consent.summary, '')
    },
    collectors: Array.isArray(raw.collectors) ? raw.collectors.map(collector) : [],
    prompts: Array.isArray(raw.prompts) ? raw.prompts.map(prompt) : [],
    storage: {
      maximum_local_bytes: numeric(storage.maximum_local_bytes, base.storage.maximum_local_bytes)
    },
    signer: {
      key_id: string(signer.key_id, ''),
      public_key: string(signer.public_key, '')
    },
    export: {
      researcher_key_id: string(exported.researcher_key_id, ''),
      tink_hpke_public_keyset: isKeysetShaped(exported.tink_hpke_public_keyset)
        ? (exported.tink_hpke_public_keyset as StudyConfiguration['export']['tink_hpke_public_keyset'])
        : base.export.tink_hpke_public_keyset
    },
    // `{}` is how an absent upload block is written, so an empty object is "no", not "malformed".
    upload:
      typeof upload.endpoint === 'string'
        ? {
            endpoint: upload.endpoint,
            interval_minutes: numeric(upload.interval_minutes, 60),
            allow_metered: upload.allow_metered === true
          }
        : null
  };
}

const object = (value: unknown): Record<string, unknown> =>
  value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {};

const string = (value: unknown, fallback: string): string =>
  typeof value === 'string' ? value : fallback;

const numeric = (value: unknown, fallback: number): number =>
  typeof value === 'number' && Number.isFinite(value) ? value : fallback;

/**
 * The one number that is clamped rather than carried through, because the page has no control for
 * it: an out-of-range floor read out of a file would raise an issue on a path with nowhere to land.
 *
 * A legal one survives, deliberately. A file `researcher-tools` wrote with `minimum_app_version: 7`
 * round-trips here unchanged — silently widening a compatibility floor somebody set on purpose
 * would be an edit to a signed document that nobody asked for.
 */
const appVersion = (value: unknown): number =>
  typeof value === 'number' &&
  Number.isInteger(value) &&
  value >= BOUNDS.minimumAppVersion[0] &&
  value <= BOUNDS.minimumAppVersion[1]
    ? value
    : DEFAULT_MINIMUM_APP_VERSION;

/**
 * Whatever it turns out to be, it is kept property-for-property: the canonicaliser re-emits this
 * object in the order it was parsed in, so re-ordering it here would change the bytes that get
 * signed. Only "is it an object at all" is decided; `validate` decides whether Tink can use it.
 */
function isKeysetShaped(value: unknown): boolean {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

/**
 * One collector, with every field of the declared type. The defaults stand in for anything missing
 * or of the wrong type; a value of the right type is carried through even when it is out of range,
 * because a repaired number is a number the researcher never chose and `validate` is what says so.
 */
function collector(raw: unknown): CollectorConfig {
  const source = object(raw);
  if (!isCollectorId(source.id)) throw new Error('parse_collector');
  const base = defaultCollector(source.id);
  const config = object(source.config);
  const required = source.required === undefined ? base.required : source.required === true;

  switch (base.id) {
    case 'app_lifecycle.v1':
      return { ...base, required };
    case 'accelerometer.v1':
      return {
        ...base,
        required,
        config: {
          sampling_period_us: numeric(config.sampling_period_us, base.config.sampling_period_us),
          maximum_report_latency_us: numeric(
            config.maximum_report_latency_us,
            base.config.maximum_report_latency_us
          )
        }
      };
    case 'network_state.v1':
      return {
        ...base,
        required,
        config: { include_bandwidth_estimates: config.include_bandwidth_estimates === true }
      };
    case 'network_usage.v1':
      return {
        ...base,
        required,
        config: {
          transports: transports(config.transports),
          poll_interval_minutes: numeric(
            config.poll_interval_minutes,
            base.config.poll_interval_minutes
          )
        }
      };
    case 'usage_events.v1':
      return {
        ...base,
        required,
        config: {
          poll_interval_minutes: numeric(
            config.poll_interval_minutes,
            base.config.poll_interval_minutes
          )
        }
      };
    case 'location.v1': {
      const priority = config.priority ?? base.config.priority;
      if (!isLocationPriority(priority)) throw new Error('parse_collector');
      return {
        ...base,
        required,
        config: {
          interval_millis: numeric(config.interval_millis, base.config.interval_millis),
          minimum_interval_millis: numeric(
            config.minimum_interval_millis,
            base.config.minimum_interval_millis
          ),
          maximum_batch_delay_millis: numeric(
            config.maximum_batch_delay_millis,
            base.config.maximum_batch_delay_millis
          ),
          minimum_displacement_meters: numeric(
            config.minimum_displacement_meters,
            base.config.minimum_displacement_meters
          ),
          priority
        }
      };
    }
    case 'keyboard_touch.v1':
      return {
        ...base,
        required,
        config: {
          trajectory_sampling_hz: numeric(
            config.trajectory_sampling_hz,
            base.config.trajectory_sampling_hz
          )
        }
      };
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
  if (raw === undefined) return [...NETWORK_TRANSPORTS];
  if (!Array.isArray(raw)) throw new Error('parse_collector');
  const named: NetworkTransport[] = [];
  for (const entry of raw) {
    if (!isNetworkTransport(entry)) throw new Error('parse_collector');
    named.push(entry);
  }
  return named;
}

function prompt(raw: unknown): PromptConfig {
  const source = object(raw);
  return {
    id: string(source.id, ''),
    delay_minutes: numeric(source.delay_minutes, 0),
    message: string(source.message, '')
  };
}
