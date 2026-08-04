import { sha256 } from '@noble/hashes/sha2.js';

export interface JoinLink {
  artifactUrl: string;
  artifactSha256: string;
  signerFingerprint: string;
}

const PREFIX = 'adc://join/v1?';
const SHA256 = /^[0-9a-f]{64}$/;
const FINGERPRINT = /^[0-9A-F]{32}$/;
const OPAQUE_PATH_TOKEN = /^[A-Za-z0-9_-]{22,}$/;
const QUERY_KEYS = ['artifact', 'sha256', 'signer_fingerprint'] as const;
const MAXIMUM_ARTIFACT_URL_BYTES = 2_048;
const MAXIMUM_JOIN_LINK_BYTES = 4_096;
const UTF8 = new TextEncoder();
const UNRESERVED = /^[A-Za-z0-9._~-]$/;
const ARTIFACT_URL = /^https:\/\/([^/:?#]+)(?::([0-9]+))?(\/[A-Za-z0-9._~\/-]+)$/;
const HOST_LABEL = /^(?:[a-z0-9]|[a-z0-9][a-z0-9-]{0,61}[a-z0-9])$/;
const CANONICAL_PORT = /^[1-9][0-9]{0,4}$/;

/** Build the exact immutable URI represented by an envelope digest and signer fingerprint. */
export function createJoinLink(
  artifactUrl: string,
  artifact: Uint8Array,
  fingerprint: string,
  assignedParticipantId: string | null = null
): string {
  const canonicalUrl = validateArtifactUrl(artifactUrl, assignedParticipantId);
  const normalizedFingerprint = fingerprint.replaceAll(' ', '');
  const value: JoinLink = {
    artifactUrl: canonicalUrl,
    artifactSha256: hex(sha256(artifact)),
    signerFingerprint: normalizedFingerprint
  };
  return encodeJoinLink(value);
}

export function encodeJoinLink(value: JoinLink): string {
  const artifact = validateArtifactUrl(value.artifactUrl, null);
  if (!SHA256.test(value.artifactSha256)) throw new Error('join_sha256_invalid');
  if (!FINGERPRINT.test(value.signerFingerprint)) throw new Error('join_fingerprint_invalid');
  const encoded =
    `${PREFIX}artifact=${percentEncode(artifact)}` +
    `&sha256=${value.artifactSha256}` +
    `&signer_fingerprint=${value.signerFingerprint}`;
  if (encoded.length > MAXIMUM_JOIN_LINK_BYTES) throw new Error('join_link_too_long');
  return encoded;
}

export function parseJoinLink(encoded: string): JoinLink {
  if (encoded.length > MAXIMUM_JOIN_LINK_BYTES || !encoded.startsWith(PREFIX)) {
    throw new Error('join_link_invalid');
  }
  const parts = encoded.slice(PREFIX.length).split('&');
  if (parts.length !== QUERY_KEYS.length) throw new Error('join_query_invalid');
  const values = parts.map((part, index) => {
    const separator = part.indexOf('=');
    if (separator <= 0 || part.indexOf('=', separator + 1) >= 0) throw new Error('join_query_invalid');
    if (part.slice(0, separator) !== QUERY_KEYS[index]) throw new Error('join_query_invalid');
    return percentDecode(part.slice(separator + 1));
  });
  const value: JoinLink = {
    artifactUrl: values[0],
    artifactSha256: values[1],
    signerFingerprint: values[2]
  };
  if (encodeJoinLink(value) !== encoded) throw new Error('join_link_noncanonical');
  return value;
}

function validateArtifactUrl(value: string, assignedParticipantId: string | null): string {
  if (value.length > MAXIMUM_ARTIFACT_URL_BYTES) {
    throw new Error('join_artifact_url_invalid');
  }
  const match = ARTIFACT_URL.exec(value);
  if (match === null) throw new Error('join_artifact_url_invalid');
  const [, host, port = '', path] = match;
  if (
    host.length > 253 ||
    !/[a-z]/.test(host) ||
    !host.split('.').every((label) => HOST_LABEL.test(label)) ||
    (port !== '' &&
      (!CANONICAL_PORT.test(port) || Number(port) > 65_535 || port === '443')) ||
    !path.slice(1).split('/').every((segment) => segment !== '' && segment !== '.' && segment !== '..')
  ) throw new Error('join_artifact_url_invalid');
  try {
    if (new URL(value).href !== value) throw new Error('join_artifact_url_invalid');
  } catch {
    throw new Error('join_artifact_url_invalid');
  }
  if (assignedParticipantId !== null) {
    if (value.includes(assignedParticipantId)) throw new Error('join_url_exposes_participant_id');
    const lastSegment = path.split('/').at(-1) ?? '';
    if (!OPAQUE_PATH_TOKEN.test(lastSegment)) throw new Error('join_url_requires_opaque_path');
  }
  return value;
}

function percentEncode(value: string): string {
  let encoded = '';
  for (const byte of UTF8.encode(value)) {
    const character = String.fromCharCode(byte);
    encoded += UNRESERVED.test(character)
      ? character
      : `%${byte.toString(16).toUpperCase().padStart(2, '0')}`;
  }
  return encoded;
}

function percentDecode(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    throw new Error('join_escape_invalid');
  }
}

function hex(value: Uint8Array): string {
  return Array.from(value, (byte) => byte.toString(16).padStart(2, '0')).join('');
}
