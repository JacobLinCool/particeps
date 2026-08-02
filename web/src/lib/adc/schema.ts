/**
 * Every rule the Android decoder enforces, checked here instead of on the phone.
 *
 * `StudyConfiguration`'s `init` blocks and `StudyConfigurationCodec`'s key checks throw on the
 * first failure and refuse the file outright, which is the right behaviour there and useless in a
 * form. `validate` collects all of them so the UI can mark every field at once, and reports each
 * one as a stable `code` that `IssueMessages` in `lib/i18n` turns into a sentence — the codes here
 * and the keys there are one list, split across two files that must not drift.
 */

import { canonicalBytes, formatInstant, keysetJson, parseInstant, type Instant } from './canonical';
import { isUsableHpkePublicKeyset } from './tink';
import {
  BOUNDS,
  DEFAULT_MINIMUM_APP_VERSION,
  ID_PATTERN,
  MAXIMUM_CONFIGURATION_BYTES,
  MAXIMUM_LOCAL_BYTES,
  MINIMUM_LOCAL_BYTES,
  SCHEMA_VERSION,
  UPLOAD_MAXIMUM_INTERVAL_MINUTES,
  UPLOAD_MINIMUM_INTERVAL_MINUTES,
  type CollectorConfig,
  type CollectorId,
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
  | 'keyset_unusable';

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

export function validate(configuration: StudyConfiguration): Issue[] {
  const issues: Issue[] = [];

  // A version other than the current one is not a field to correct; it is a different format.
  if (configuration.schema_version !== SCHEMA_VERSION) {
    issues.push(range('schema_version', [SCHEMA_VERSION, SCHEMA_VERSION]));
  }
  identifier(issues, 'experiment_id', configuration.experiment_id);
  identifier(issues, 'configuration_id', configuration.configuration_id);

  const issued = instant(issues, 'issued_at', configuration.issued_at);
  const expires = instant(issues, 'expires_at', configuration.expires_at);
  if (issued && expires && !before(issued, expires)) {
    issues.push({ path: 'expires_at', code: 'window_order' });
  }

  integer(issues, 'minimum_app_version', configuration.minimum_app_version, BOUNDS.minimumAppVersion);
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

  const promptIds = new Set<string>();
  configuration.prompts.forEach((prompt, index) => {
    const path = `prompts.${index}`;
    identifier(issues, `${path}.id`, prompt.id);
    if (promptIds.has(prompt.id)) issues.push({ path: `${path}.id`, code: 'duplicate_id' });
    promptIds.add(prompt.id);
    integer(issues, `${path}.delay_minutes`, prompt.delay_minutes, BOUNDS.promptDelayMinutes);
    text(issues, `${path}.message`, prompt.message, BOUNDS.promptMessage);
  });

  integer(issues, 'storage.maximum_local_bytes', configuration.storage.maximum_local_bytes, [
    MINIMUM_LOCAL_BYTES,
    MAXIMUM_LOCAL_BYTES
  ]);

  identifier(issues, 'signer.key_id', configuration.signer.key_id);
  if (!configuration.signer.public_key) {
    issues.push({ path: 'signer.public_key', code: 'signer_missing' });
  } else {
    text(issues, 'signer.public_key', configuration.signer.public_key, BOUNDS.signerPublicKey);
  }

  identifier(issues, 'export.researcher_key_id', configuration.export.researcher_key_id);
  keyset(issues, configuration);

  upload(issues, configuration);

  const bytes = canonicalBytes(configuration).length;
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
    minimum_app_version: DEFAULT_MINIMUM_APP_VERSION,
    title: '',
    researcher: { name: '', contact: '' },
    purpose: '',
    duration_hours: 24,
    consent: { document_version: '', summary: '' },
    collectors: [],
    prompts: [],
    storage: { maximum_local_bytes: DEFAULT_LOCAL_BYTES },
    signer: { key_id: '', public_key: '' },
    export: { researcher_key_id: '', tink_hpke_public_keyset: { primaryKeyId: 0, key: [] } },
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
          minimum_displacement_meters: 25,
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
      // The one field that is not an integer, so a bare comparison — NaN fails both of these.
      const displacement = config.minimum_displacement_meters;
      const [floor, ceiling] = BOUNDS.minimumDisplacementMeters;
      if (!(displacement >= floor) || !(displacement <= ceiling)) {
        issues.push(range(`${path}.minimum_displacement_meters`, BOUNDS.minimumDisplacementMeters));
      }
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

function keyset(issues: Issue[], configuration: StudyConfiguration): void {
  const path = 'export.tink_hpke_public_keyset';
  const value = configuration.export.tink_hpke_public_keyset;
  if (!value || !Array.isArray(value.key) || value.key.length === 0) {
    issues.push({ path, code: 'export_key_missing' });
    return;
  }
  const length = keysetJson(value).length;
  if (length < 32 || length > 16_384) {
    issues.push({ path, code: 'length_range', bounds: { min: 32, max: 16_384 } });
    return;
  }
  // The length is the only thing `ExportConfiguration` checks, so it is the only thing that would
  // have been caught before a participant's phone. Everything Tink itself would refuse is refused
  // here instead, while the researcher is still in front of the key that produced it.
  if (!isUsableHpkePublicKeyset(value)) issues.push({ path, code: 'keyset_unusable' });
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
