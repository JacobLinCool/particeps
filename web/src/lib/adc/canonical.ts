/**
 * The canonical encoder: a transcription of `StudyConfigurationCodec.encode`, byte for byte.
 *
 * `decode` on the device re-encodes whatever it parsed and refuses the file unless the bytes come
 * back identical, so this is not "a JSON writer that happens to agree". The signature is computed
 * over exactly these bytes, which means a key in the wrong place, a float in the wrong form, or an
 * escape Gson would not have written produces a file that is correctly signed and rejected by
 * every device.
 *
 * Nothing here validates or throws: the UI encodes as the researcher types, and a draft is
 * half-finished most of the time. `schema.ts` is what refuses a document.
 */

import type {
  CollectorConfig,
  ChoiceOption,
  InterventionConfig,
  LocalizedText,
  NetworkTransport,
  StudyConfiguration,
  SurveyDefinition,
  SurveyQuestion,
  TinkKeyset,
  UploadConfig
} from './types';

/**
 * Gson's default replacement table — the non-HTML-safe one. Everything from `0x20` up is written
 * as itself apart from `"` and `\`, which is why `/`, `<`, `>`, `&`, `=`, `'`, DEL, and all
 * non-ASCII appear raw in the output.
 */
const CONTROL_ESCAPES: readonly string[] = (() => {
  const table: string[] = [];
  for (let code = 0; code < 0x20; code++) table[code] = `\\u${code.toString(16).padStart(4, '0')}`;
  table[0x08] = '\\b';
  table[0x09] = '\\t';
  table[0x0a] = '\\n';
  table[0x0c] = '\\f';
  table[0x0d] = '\\r';
  return table;
})();

/** The escaped body of a JSON string, without the surrounding quotes. */
export function escapeJsonString(value: string): string {
  let escaped = '';
  let plain = 0;
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index);
    let replacement: string;
    if (code < 0x20) replacement = CONTROL_ESCAPES[code];
    else if (code === 0x22) replacement = '\\"';
    else if (code === 0x5c) replacement = '\\\\';
    else if (code === 0x2028) replacement = '\\u2028';
    else if (code === 0x2029) replacement = '\\u2029';
    else continue;
    escaped += value.slice(plain, index) + replacement;
    plain = index + 1;
  }
  return plain === 0 ? value : escaped + value.slice(plain);
}

function quoted(value: string): string {
  return `"${escapeJsonString(value)}"`;
}

/**
 * Java's `Float.toString`, which is what Gson's `JsonWriter.value(Number)` calls for the one
 * `Float` in the schema: the shortest decimal that round-trips to the same float32, and among
 * equally short ones the closest, with at least one digit after the point.
 *
 * `Number.prototype.toString` gives the shortest form for a *double* — for 0.1f that is
 * `0.10000000149011612`, which is a different string and therefore a different signature.
 */
export function formatFloat(value: number): string {
  const float = Math.fround(value);
  if (Number.isNaN(float)) return 'NaN';
  if (float === Infinity) return 'Infinity';
  if (float === -Infinity) return '-Infinity';

  const sign = float < 0 || Object.is(float, -0) ? '-' : '';
  const magnitude = Math.abs(float);
  if (magnitude === 0) return `${sign}0.0`;

  // Nine significant digits always distinguish two float32 values, so the search terminates.
  let rendered = magnitude.toExponential(8);
  for (let precision = 1; precision < 9; precision++) {
    const candidate = magnitude.toExponential(precision - 1);
    if (Math.fround(Number(candidate)) === magnitude) {
      rendered = candidate;
      break;
    }
  }
  const [mantissa, power] = rendered.split('e');
  let digits = mantissa.replace('.', '');
  let exponent = Number(power);

  // One significant digit is the exception to "shortest wins": a two-digit decimal is used when it
  // is strictly closer, which is why Float.MIN_VALUE is 1.4E-45 and not 1.0E-45. A two-digit
  // rendering ending in zero is the one-digit decimal written again, so it never wins.
  if (digits.length === 1) {
    const [refined, refinedPower] = magnitude.toExponential(1).split('e');
    const closer = refined.replace('.', '');
    if (!closer.endsWith('0')) {
      digits = closer;
      exponent = Number(refinedPower);
    }
  }

  // Two decimals of the same length can be equally close to the value. Java takes the one with the
  // even significand; `toExponential` always takes the larger, which renders 4618.53125f as
  // 4618.5313 where the JDK writes 4618.5312.
  if (Number(digits[digits.length - 1]) % 2 === 1 && isHalfway(magnitude, digits, exponent)) {
    digits = String(BigInt(digits) - 1n);
  }

  // Java switches to `1.0E-4` / `1.0E7` outside 10^-3 ..< 10^7. Both ends are outside anything this
  // schema accepts for a displacement, but a file carrying one still has to encode the way the
  // device would re-encode it.
  if (exponent < -3 || exponent >= 7) {
    return `${sign}${digits[0]}.${digits.slice(1) || '0'}E${exponent}`;
  }
  if (exponent < 0) return `${sign}0.${'0'.repeat(-exponent - 1)}${digits}`;
  const padded = digits.padEnd(exponent + 1, '0');
  return `${sign}${padded.slice(0, exponent + 1)}.${padded.slice(exponent + 1) || '0'}`;
}

const FLOAT_BITS = new DataView(new ArrayBuffer(4));

/**
 * Whether the value sits exactly between `digits` and the decimal one unit below it. Both sides of
 * the comparison are integers: the float is `significand × 2^exponent` exactly, and the midpoint of
 * two adjacent decimals of the same length is `(2 × digits - 1) × 10^power / 2`.
 */
function isHalfway(magnitude: number, digits: string, exponent: number): boolean {
  FLOAT_BITS.setFloat32(0, magnitude);
  const bits = FLOAT_BITS.getUint32(0);
  const biased = (bits >>> 23) & 0xff;
  let left = BigInt(biased === 0 ? bits & 0x7fffff : (bits & 0x7fffff) | 0x800000);
  let right = 2n * BigInt(digits) - 1n;
  const power2 = (biased === 0 ? -126 : biased - 127) - 23 + 1;
  if (power2 >= 0) left <<= BigInt(power2);
  else right <<= BigInt(-power2);
  const power10 = exponent - digits.length + 1;
  if (power10 >= 0) right *= 10n ** BigInt(power10);
  else left *= 10n ** BigInt(-power10);
  return left === right;
}

/** A `java.time.Instant`, decomposed. */
export interface Instant {
  second: number;
  nano: number;
}

// `DateTimeFormatter.ISO_INSTANT` appends an offset written `+HH:MM[:ss]`, so the colon is not
// optional and the minutes are not: `+08`, `+0800`, and `+080000` are all spellings `Instant.parse`
// refuses. Accepting them here would take a hand-written file the CLI would not.
const INSTANT =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2}(?::\d{2})?)$/;

const MINIMUM_INSTANT_SECOND = -62_167_219_200; // 0000-01-01T00:00:00Z
const MAXIMUM_INSTANT_SECOND = 253_402_300_799; // 9999-12-31T23:59:59Z

/**
 * `Instant.parse`, narrowed to the years `Instant.toString` renders as four plain digits. Anything
 * outside that would need Java's `+10000-…` form, and a study configuration has no business
 * carrying one.
 */
export function parseInstant(text: string): Instant | null {
  const match = INSTANT.exec(text);
  if (!match) return null;
  const [year, month, day, hour, minute, second] = match.slice(1, 7).map(Number);
  if (month < 1 || month > 12 || day < 1 || day > monthLength(year, month)) return null;
  if (hour > 23 || minute > 59 || second > 59) return null;
  const offset = offsetSeconds(match[8]);
  if (offset === null) return null;
  const epochSecond =
    epochDay(year, month, day) * 86_400 + hour * 3_600 + minute * 60 + second - offset;
  if (epochSecond < MINIMUM_INSTANT_SECOND || epochSecond > MAXIMUM_INSTANT_SECOND) return null;
  return { second: epochSecond, nano: match[7] ? Number(match[7].padEnd(9, '0')) : 0 };
}

/** `Instant.toString`: always seconds, fractions in whole groups of three, and never an offset. */
export function formatInstant(instant: Instant): string {
  const day = Math.floor(instant.second / 86_400);
  const time = instant.second - day * 86_400;
  const [year, month, dayOfMonth] = civilFromEpochDay(day);
  const date = `${pad(year, 4)}-${pad(month, 2)}-${pad(dayOfMonth, 2)}`;
  const clock = `${pad(Math.floor(time / 3_600), 2)}:${pad(Math.floor(time / 60) % 60, 2)}:${pad(time % 60, 2)}`;
  return `${date}T${clock}${fraction(instant.nano)}Z`;
}

function fraction(nano: number): string {
  if (nano === 0) return '';
  if (nano % 1_000_000 === 0) return `.${pad(nano / 1_000_000, 3)}`;
  if (nano % 1_000 === 0) return `.${pad(nano / 1_000, 6)}`;
  return `.${pad(nano, 9)}`;
}

function pad(value: number, width: number): string {
  return String(value).padStart(width, '0');
}

function monthLength(year: number, month: number): number {
  if (month === 2) return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0 ? 29 : 28;
  return month === 4 || month === 6 || month === 9 || month === 11 ? 30 : 31;
}

const MAXIMUM_OFFSET_SECONDS = 18 * 3_600;

function offsetSeconds(zone: string): number | null {
  if (zone === 'Z') return 0;
  const [hour, minute, second = 0] = zone.slice(1).split(':').map(Number);
  if (minute > 59 || second > 59) return null;
  // `ZoneOffset` bounds the whole offset at ±18:00 rather than the hour field, so -18:00:01 and
  // -18:16 are both out even though neither has an hour above 18.
  const magnitude = hour * 3_600 + minute * 60 + second;
  if (magnitude > MAXIMUM_OFFSET_SECONDS) return null;
  return zone[0] === '-' ? -magnitude : magnitude;
}

// Howard Hinnant's civil-calendar conversions, with March as the start of the year so the leap day
// lands at the end. Exact for every year this module accepts.
function epochDay(year: number, month: number, day: number): number {
  const shifted = year - (month <= 2 ? 1 : 0);
  const era = Math.floor(shifted / 400);
  const yearOfEra = shifted - era * 400;
  const dayOfYear = Math.trunc((153 * (month > 2 ? month - 3 : month + 9) + 2) / 5) + day - 1;
  const dayOfEra =
    yearOfEra * 365 + Math.trunc(yearOfEra / 4) - Math.trunc(yearOfEra / 100) + dayOfYear;
  return era * 146_097 + dayOfEra - 719_468;
}

function civilFromEpochDay(days: number): [number, number, number] {
  const shifted = days + 719_468;
  const era = Math.floor(shifted / 146_097);
  const dayOfEra = shifted - era * 146_097;
  const yearOfEra = Math.trunc(
    (dayOfEra -
      Math.trunc(dayOfEra / 1_460) +
      Math.trunc(dayOfEra / 36_524) -
      Math.trunc(dayOfEra / 146_096)) /
      365
  );
  const dayOfYear =
    dayOfEra - (365 * yearOfEra + Math.trunc(yearOfEra / 4) - Math.trunc(yearOfEra / 100));
  const monthIndex = Math.trunc((5 * dayOfYear + 2) / 153);
  const day = dayOfYear - Math.trunc((153 * monthIndex + 2) / 5) + 1;
  const month = monthIndex < 10 ? monthIndex + 3 : monthIndex - 9;
  return [yearOfEra + era * 400 + (month <= 2 ? 1 : 0), month, day];
}

/**
 * Kotlin holds a parsed `Instant` and writes `toString()`, so a valid instant spelled some other
 * way — `Date.toISOString()`'s trailing `.000`, an offset that is not `Z` — is re-spelled here
 * rather than carried through. A value that is not an instant at all is passed on untouched, for
 * the preview; `validate` is what refuses it.
 */
function instantText(text: string): string {
  const instant = parseInstant(text);
  return quoted(instant ? formatInstant(instant) : text);
}

/**
 * Every number in the schema apart from the one displacement is an `Int` or a `Long`, written by
 * `JsonWriter.value(long)` as a plain decimal literal. A draft can hold something that is not a
 * whole number yet, so this renders what it was given rather than pretending.
 */
function integer(value: number): string {
  if (!Number.isFinite(value)) return '0';
  return Number.isInteger(value) ? BigInt(value).toString() : String(value);
}

/** Same defence, for a draft that reached a boolean field carrying something else. */
function boolean(value: boolean): string {
  return value === true ? 'true' : 'false';
}

/**
 * The keyset is re-emitted from Gson's `JsonObject.toString()`: compact, and in the order the keys
 * were parsed in. Object property order carries that here, and every number in a Tink keyset is a
 * key ID.
 */
export function keysetJson(keyset: TinkKeyset): string {
  return compact(keyset);
}

function compact(value: unknown): string {
  if (value === null || value === undefined) return 'null';
  if (typeof value === 'string') return quoted(value);
  if (typeof value === 'boolean') return String(value);
  if (typeof value === 'number') return integer(value);
  if (Array.isArray(value)) return `[${value.map(compact).join(',')}]`;
  return `{${Object.entries(value as object)
    .map(([key, entry]) => `${quoted(key)}:${compact(entry)}`)
    .join(',')}}`;
}

/** `sortedBy { it.name }` over a `Set<NetworkTransport>`: MOBILE before WIFI, and no duplicates. */
const TRANSPORTS_BY_ENUM_NAME: readonly NetworkTransport[] = ['mobile', 'wifi'];

function encodeCollector(collector: CollectorConfig): string {
  return `{"id":${quoted(collector.id)},"required":${boolean(collector.required)},"config":{${collectorConfig(collector)}}}`;
}

function collectorConfig(collector: CollectorConfig): string {
  switch (collector.id) {
    case 'app_lifecycle.v1':
      return '';
    case 'accelerometer.v1':
      return (
        `"sampling_period_us":${integer(collector.config.sampling_period_us)}` +
        `,"maximum_report_latency_us":${integer(collector.config.maximum_report_latency_us)}`
      );
    case 'network_state.v1':
      return `"include_bandwidth_estimates":${boolean(collector.config.include_bandwidth_estimates)}`;
    case 'network_usage.v1': {
      const transports = TRANSPORTS_BY_ENUM_NAME.filter((transport) =>
        collector.config.transports.includes(transport)
      );
      return (
        `"transports":[${transports.map(quoted).join(',')}]` +
        `,"poll_interval_minutes":${integer(collector.config.poll_interval_minutes)}`
      );
    }
    case 'usage_events.v1':
      return `"poll_interval_minutes":${integer(collector.config.poll_interval_minutes)}`;
    case 'location.v1':
      return (
        `"interval_millis":${integer(collector.config.interval_millis)}` +
        `,"minimum_interval_millis":${integer(collector.config.minimum_interval_millis)}` +
        `,"maximum_batch_delay_millis":${integer(collector.config.maximum_batch_delay_millis)}` +
        `,"minimum_displacement_meters":${formatFloat(collector.config.minimum_displacement_meters)}` +
        `,"priority":${quoted(collector.config.priority)}`
      );
    case 'keyboard_touch.v1':
      return `"trajectory_sampling_hz":${integer(collector.config.trajectory_sampling_hz)}`;
    // An id outside the union cannot reach here through `parse.ts`, which refuses one exactly as
    // `decodeCollector` does. The arm exists so that if one ever did, the encoder writes an empty
    // object rather than falling off the end and interpolating the token `undefined` — a document
    // that is not JSON at all, signed, and refused by every device with nothing to point at.
    default:
      return '';
  }
}

function localized(text: LocalizedText): string {
  const translations = Object.entries(text.translations).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
  return `{"default":${quoted(text.default)},"translations":{${translations.map(
    ([language, value]) => `${quoted(language)}:${quoted(value)}`
  ).join(',')}}}`;
}

function choices(options: ChoiceOption[]): string {
  return `[${options.map((option) => `{"id":${quoted(option.id)},"label":${localized(option.label)}}`).join(',')}]`;
}

function question(value: SurveyQuestion): string {
  const common = `"type":${quoted(value.type)},"id":${quoted(value.id)},"prompt":${localized(value.prompt)},"required":${boolean(value.required)}`;
  switch (value.type) {
    case 'short_text': return `{${common},"maximum_length":${integer(value.maximum_length)}}`;
    case 'scale': return `{${common},"minimum":${integer(value.minimum)},"maximum":${integer(value.maximum)},"minimum_label":${localized(value.minimum_label)},"maximum_label":${localized(value.maximum_label)}}`;
    case 'single_choice': return `{${common},"options":${choices(value.options)}}`;
    case 'multiple_choice': return `{${common},"options":${choices(value.options)},"minimum_selections":${integer(value.minimum_selections)},"maximum_selections":${integer(value.maximum_selections)}}`;
  }
}

function survey(value: SurveyDefinition): string {
  return `{"id":${quoted(value.id)},"title":${localized(value.title)},"description":${localized(value.description)},"questions":[${value.questions.map(question).join(',')}]}`;
}

function intervention(value: InterventionConfig): string {
  const action = `{"type":${quoted(value.action.type)},"notification_title":${quoted(value.action.notification_title)},"notification_message":${quoted(value.action.notification_message)}${value.action.type === 'survey' ? `,"survey_id":${quoted(value.action.survey_id)}` : ''}}`;
  const triggers = value.triggers.map((trigger) => {
    const schedule = trigger.schedule.type === 'one_time'
      ? `{"type":"one_time","offset_minutes":${integer(trigger.schedule.offset_minutes)},"clock":${quoted(trigger.schedule.clock)}}`
      : trigger.schedule.type === 'interval'
        ? `{"type":"interval","start_offset_minutes":${integer(trigger.schedule.start_offset_minutes)},"interval_minutes":${integer(trigger.schedule.interval_minutes)},"clock":${quoted(trigger.schedule.clock)}}`
        : `{"type":"daily_local","local_time":${quoted(trigger.schedule.local_time)}}`;
    return `{"id":${quoted(trigger.id)},"schedule":${schedule},"availability_minutes":${integer(trigger.availability_minutes)}}`;
  });
  return `{"id":${quoted(value.id)},"action":${action},"triggers":[${triggers.join(',')}]}`;
}

/** The exact string `researcher-tools canonicalize` writes, root key order included. */
export function canonicalize(configuration: StudyConfiguration): string {
  return (
    '{' +
    `"schema_version":${integer(configuration.schema_version)}` +
    `,"experiment_id":${quoted(configuration.experiment_id)}` +
    `,"configuration_id":${quoted(configuration.configuration_id)}` +
    `,"assigned_participant_id":${configuration.assigned_participant_id === null ? 'null' : quoted(configuration.assigned_participant_id)}` +
    `,"issued_at":${instantText(configuration.issued_at)}` +
    `,"expires_at":${instantText(configuration.expires_at)}` +
    `,"minimum_app_version":${integer(configuration.minimum_app_version)}` +
    `,"title":${quoted(configuration.title)}` +
    `,"researcher":{"name":${quoted(configuration.researcher.name)}` +
    `,"contact":${quoted(configuration.researcher.contact)}}` +
    `,"purpose":${quoted(configuration.purpose)}` +
    `,"duration_hours":${integer(configuration.duration_hours)}` +
    `,"consent":{"document_version":${quoted(configuration.consent.document_version)}` +
    `,"summary":${quoted(configuration.consent.summary)}}` +
    `,"collectors":[${configuration.collectors.map(encodeCollector).join(',')}]` +
    `,"surveys":[${configuration.surveys.map(survey).join(',')}]` +
    `,"interventions":[${configuration.interventions.map(intervention).join(',')}]` +
    `,"storage":{"maximum_local_bytes":${integer(configuration.storage.maximum_local_bytes)}}` +
    `,"signer":{"key_id":${quoted(configuration.signer.key_id)}` +
    `,"public_key":${quoted(configuration.signer.public_key)}}` +
    `,"export":{"researcher_key_id":${quoted(configuration.export.researcher_key_id)}` +
    `,"tink_hpke_public_keyset":${keysetJson(configuration.export.tink_hpke_public_keyset)}}` +
    `,"upload":{${encodeUpload(configuration.upload)}}` +
    '}'
  );
}

/** An absent upload block is an empty object, not `null`: the decoder reads emptiness as "no". */
function encodeUpload(upload: UploadConfig | null): string {
  if (!upload) return '';
  return (
    `"endpoint":${quoted(upload.endpoint)}` +
    `,"interval_minutes":${integer(upload.interval_minutes)}` +
    `,"allow_metered":${boolean(upload.allow_metered)}`
  );
}

export function canonicalBytes(configuration: StudyConfiguration): Uint8Array {
  return new TextEncoder().encode(canonicalize(configuration));
}
