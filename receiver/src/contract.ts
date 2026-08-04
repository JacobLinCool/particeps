export const BUNDLE_FORMAT = "research-bundle-v1";
export const BUNDLE_MEDIA_TYPE = "application/vnd.adc.research-bundle";
export const MAXIMUM_BODY_BYTES = 32 * 1024 * 1024;
const MINIMUM_BODY_BYTES = 170;

const MAGIC = new TextEncoder().encode("ADCEXP01");
const MAXIMUM_SIGNED_64 = 9_223_372_036_854_775_807n;
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const LOWER_HEX_256 = /^[0-9a-f]{64}$/;
const KEY_ID = /^[a-z0-9][a-z0-9-]{2,63}$/;
const DEPLOYMENT_PATH = /^\/(?:[A-Za-z0-9._~-]+\/)*[A-Za-z0-9._~-]+$/;
const CANONICAL_DECIMAL = /^(?:0|[1-9][0-9]*)$/;
const BASE64_SHA256 = /^[A-Za-z0-9+/]{43}=$/;

const ADC_HEADERS = new Set([
  "x-adc-bundle-format",
  "x-adc-bundle-id",
  "x-adc-configuration-sha256",
  "x-adc-researcher-key-id",
  "x-adc-sequence-from",
  "x-adc-sequence-to",
  "x-adc-event-count",
]);
const CONTENT_HEADERS = new Set(["content-type", "content-length", "content-digest"]);
// These are transport headers supplied by OkHttp or documented Cloudflare edge transforms. They
// carry no receiver semantics. Everything else under client control is rejected rather than
// accidentally becoming a second authentication, routing, or representation mechanism.
const INFRASTRUCTURE_HEADERS = new Set([
  "accept-encoding",
  "cdn-loop",
  "cf-connecting-ip",
  "cf-connecting-ipv6",
  "cf-connecting-o2o",
  "cf-ew-via",
  "cf-ipcountry",
  "cf-pseudo-ipv4",
  "cf-ray",
  "cf-visitor",
  "cf-worker",
  "connection",
  "host",
  // Wrangler/Miniflare adds this local transport header before invoking the Worker.
  "mf-original-hostname",
  "true-client-ip",
  "user-agent",
  "x-forwarded-for",
  "x-forwarded-proto",
  "x-real-ip",
]);

export const CUSTOM_METADATA_KEYS = [
  "sha256",
  "byte_count",
  "configuration_sha256",
  "researcher_key_id",
  "first_sequence_number",
  "last_sequence_number",
  "event_count",
  "received_at_utc",
] as const;

export interface ReceiverEnv {
  BUNDLES: R2Bucket;
  UPLOAD_PATH: string;
  ALLOWED_CONFIGURATION_SHA256: string;
  ALLOWED_RESEARCHER_KEY_ID: string;
}

export interface Deployment {
  uploadPath: string;
  configurationSha256: string;
  researcherKeyId: string;
}

export interface UploadClaims {
  bundleId: string;
  byteCount: number;
  byteCountText: string;
  configurationSha256: string;
  researcherKeyId: string;
  firstSequenceNumber: string;
  lastSequenceNumber: string;
  eventCount: string;
  sha256: string;
  sha256Bytes: Uint8Array;
}

export class RequestViolation extends Error {
  constructor(message: string) {
    super(message);
    this.name = "RequestViolation";
  }
}

export class PayloadTooLargeViolation extends RequestViolation {
  constructor() {
    super("Content-Length exceeds the Protocol v1 limit");
    this.name = "PayloadTooLargeViolation";
  }
}

export class DeploymentViolation extends Error {
  constructor(message: string) {
    super(message);
    this.name = "DeploymentViolation";
  }
}

export function parseDeployment(env: ReceiverEnv): Deployment {
  if (!DEPLOYMENT_PATH.test(env.UPLOAD_PATH)) {
    throw new DeploymentViolation("UPLOAD_PATH is invalid");
  }
  if (!LOWER_HEX_256.test(env.ALLOWED_CONFIGURATION_SHA256)) {
    throw new DeploymentViolation("ALLOWED_CONFIGURATION_SHA256 is invalid");
  }
  if (!KEY_ID.test(env.ALLOWED_RESEARCHER_KEY_ID)) {
    throw new DeploymentViolation("ALLOWED_RESEARCHER_KEY_ID is invalid");
  }
  return {
    uploadPath: env.UPLOAD_PATH,
    configurationSha256: env.ALLOWED_CONFIGURATION_SHA256,
    researcherKeyId: env.ALLOWED_RESEARCHER_KEY_ID,
  };
}

export function parseUploadRequest(request: Request, deployment: Deployment): UploadClaims {
  rejectUnknownProtocolHeaders(request.headers);
  if (request.headers.get("content-type") !== BUNDLE_MEDIA_TYPE) {
    throw new RequestViolation("Content-Type is invalid");
  }

  const byteCountText = requiredHeader(request.headers, "content-length");
  const byteCount = parseBoundedNumber(
    "Content-Length",
    byteCountText,
    MINIMUM_BODY_BYTES,
    MAXIMUM_BODY_BYTES,
  );
  const digest = parseContentDigest(requiredHeader(request.headers, "content-digest"));
  const bundleFormat = requiredHeader(request.headers, "x-adc-bundle-format");
  const bundleId = requiredHeader(request.headers, "x-adc-bundle-id");
  const configurationSha256 = requiredHeader(request.headers, "x-adc-configuration-sha256");
  const researcherKeyId = requiredHeader(request.headers, "x-adc-researcher-key-id");
  const firstSequenceNumber = requiredHeader(request.headers, "x-adc-sequence-from");
  const lastSequenceNumber = requiredHeader(request.headers, "x-adc-sequence-to");
  const eventCount = requiredHeader(request.headers, "x-adc-event-count");

  if (bundleFormat !== BUNDLE_FORMAT) throw new RequestViolation("Bundle format is invalid");
  if (!UUID_V4.test(bundleId)) throw new RequestViolation("Bundle ID is invalid");
  if (!LOWER_HEX_256.test(configurationSha256)) {
    throw new RequestViolation("Configuration digest is invalid");
  }
  if (!KEY_ID.test(researcherKeyId)) throw new RequestViolation("Researcher key ID is invalid");

  const first = parsePositiveInt64("X-ADC-Sequence-From", firstSequenceNumber);
  const last = parsePositiveInt64("X-ADC-Sequence-To", lastSequenceNumber);
  const count = parsePositiveInt64("X-ADC-Event-Count", eventCount);
  if (last < first || last - first + 1n !== count) {
    throw new RequestViolation("Sequence range and event count do not agree");
  }
  if (configurationSha256 !== deployment.configurationSha256) {
    throw new RequestViolation("Configuration digest is not allowed");
  }
  if (researcherKeyId !== deployment.researcherKeyId) {
    throw new RequestViolation("Researcher key ID is not allowed");
  }

  return {
    bundleId,
    byteCount,
    byteCountText,
    configurationSha256,
    researcherKeyId,
    firstSequenceNumber,
    lastSequenceNumber,
    eventCount,
    sha256: digest.hex,
    sha256Bytes: digest.bytes,
  };
}

function rejectUnknownProtocolHeaders(headers: Headers): void {
  for (const [rawName] of headers) {
    const name = rawName.toLowerCase();
    if (ADC_HEADERS.has(name) || CONTENT_HEADERS.has(name)) continue;
    if (INFRASTRUCTURE_HEADERS.has(name)) continue;
    throw new RequestViolation("Unknown request header");
  }
}

function requiredHeader(headers: Headers, name: string): string {
  const value = headers.get(name);
  if (value === null || value.length === 0) throw new RequestViolation(`Missing ${name}`);
  return value;
}

function parseBoundedNumber(name: string, raw: string, minimum: number, maximum: number): number {
  if (!CANONICAL_DECIMAL.test(raw)) throw new RequestViolation(`${name} is not canonical`);
  if (raw.length > String(maximum).length) throw new PayloadTooLargeViolation();
  const value = BigInt(raw);
  if (value > BigInt(maximum)) throw new PayloadTooLargeViolation();
  if (value < BigInt(minimum)) throw new RequestViolation(`${name} is out of range`);
  return Number(value);
}

function parsePositiveInt64(name: string, raw: string): bigint {
  if (!CANONICAL_DECIMAL.test(raw)) throw new RequestViolation(`${name} is not canonical`);
  if (raw.length > 19) throw new RequestViolation(`${name} is out of range`);
  const value = BigInt(raw);
  if (value < 1n || value > MAXIMUM_SIGNED_64) {
    throw new RequestViolation(`${name} is out of range`);
  }
  return value;
}

function parseContentDigest(raw: string): { bytes: Uint8Array; hex: string } {
  const prefix = "sha-256=:";
  if (!raw.startsWith(prefix) || !raw.endsWith(":")) {
    throw new RequestViolation("Content-Digest is invalid");
  }
  const encoded = raw.slice(prefix.length, -1);
  if (!BASE64_SHA256.test(encoded)) throw new RequestViolation("Content-Digest is invalid");
  let decoded: Uint8Array;
  try {
    decoded = Uint8Array.from(atob(encoded), (character) => character.charCodeAt(0));
  } catch {
    throw new RequestViolation("Content-Digest is invalid");
  }
  if (decoded.length !== 32 || encodeBase64(decoded) !== encoded) {
    throw new RequestViolation("Content-Digest is not canonical");
  }
  return { bytes: decoded, hex: bytesToHex(decoded) };
}

function encodeBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

export function requiredOuterPrefixLength(prefix: Uint8Array): number {
  if (prefix.length < 58) throw new RequestViolation("Bundle outer header is truncated");
  const keyIdLength = (prefix[56]! << 8) | prefix[57]!;
  if (keyIdLength < 3 || keyIdLength > 64) {
    throw new RequestViolation("Bundle researcher key ID length is invalid");
  }
  return 70 + keyIdLength;
}

export function verifyOuterPrefix(prefix: Uint8Array, claims: UploadClaims): void {
  const requiredLength = requiredOuterPrefixLength(prefix);
  if (prefix.length < requiredLength) throw new RequestViolation("Bundle outer header is truncated");
  if (!equalBytes(prefix.subarray(0, 8), MAGIC)) throw new RequestViolation("Bundle magic is invalid");

  const outerBundleId = uuidFromBytes(prefix.subarray(8, 24));
  const outerConfigurationSha256 = bytesToHex(prefix.subarray(24, 56));
  const keyIdBytes = prefix.subarray(70, requiredLength);
  let outerResearcherKeyId: string;
  try {
    outerResearcherKeyId = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(keyIdBytes);
  } catch {
    throw new RequestViolation("Bundle researcher key ID is not UTF-8");
  }
  if (!KEY_ID.test(outerResearcherKeyId)) {
    throw new RequestViolation("Bundle researcher key ID is invalid");
  }
  if (outerBundleId !== claims.bundleId) throw new RequestViolation("Outer bundle ID mismatch");
  if (outerConfigurationSha256 !== claims.configurationSha256) {
    throw new RequestViolation("Outer configuration digest mismatch");
  }
  if (outerResearcherKeyId !== claims.researcherKeyId) {
    throw new RequestViolation("Outer researcher key ID mismatch");
  }
  if (claims.byteCount <= 150 + keyIdBytes.length + 16) {
    throw new RequestViolation("Bundle ciphertext is truncated");
  }
}

function uuidFromBytes(bytes: Uint8Array): string {
  const hex = bytesToHex(bytes);
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function bytesToHex(bytes: Uint8Array): string {
  let output = "";
  for (const byte of bytes) output += byte.toString(16).padStart(2, "0");
  return output;
}

function equalBytes(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  let difference = 0;
  for (let index = 0; index < left.length; index += 1) {
    difference |= left[index]! ^ right[index]!;
  }
  return difference === 0;
}

export function objectMetadata(claims: UploadClaims, receivedAtUtc: string): Record<string, string> {
  return {
    sha256: claims.sha256,
    byte_count: claims.byteCountText,
    configuration_sha256: claims.configurationSha256,
    researcher_key_id: claims.researcherKeyId,
    first_sequence_number: claims.firstSequenceNumber,
    last_sequence_number: claims.lastSequenceNumber,
    event_count: claims.eventCount,
    received_at_utc: receivedAtUtc,
  };
}

export function isExactObject(object: R2Object, claims: UploadClaims): boolean {
  const metadata = object.customMetadata;
  const httpMetadata = object.httpMetadata;
  if (metadata === undefined || httpMetadata === undefined) return false;
  if (Object.keys(metadata).sort().join("\n") !== [...CUSTOM_METADATA_KEYS].sort().join("\n")) {
    return false;
  }
  const storedChecksum = object.checksums.sha256;
  return object.size === claims.byteCount
    && httpMetadata.contentType === BUNDLE_MEDIA_TYPE
    && storedChecksum !== undefined
    && bytesToHex(new Uint8Array(storedChecksum)) === claims.sha256
    && metadata.sha256 === claims.sha256
    && metadata.byte_count === claims.byteCountText
    && metadata.configuration_sha256 === claims.configurationSha256
    && metadata.researcher_key_id === claims.researcherKeyId
    && metadata.first_sequence_number === claims.firstSequenceNumber
    && metadata.last_sequence_number === claims.lastSequenceNumber
    && metadata.event_count === claims.eventCount
    && isCanonicalReceiveTime(metadata.received_at_utc);
}

function isCanonicalReceiveTime(value: string | undefined): boolean {
  if (value === undefined || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value)) return false;
  const parsed = new Date(value);
  return !Number.isNaN(parsed.valueOf()) && parsed.toISOString() === value;
}

export function encodeReceipt(claims: UploadClaims): string {
  return `{"bundle_id":"${claims.bundleId}","byte_count":"${claims.byteCountText}",`
    + `"configuration_sha256":"${claims.configurationSha256}","event_count":"${claims.eventCount}",`
    + `"first_sequence_number":"${claims.firstSequenceNumber}",`
    + `"last_sequence_number":"${claims.lastSequenceNumber}","sha256":"${claims.sha256}"}`;
}
