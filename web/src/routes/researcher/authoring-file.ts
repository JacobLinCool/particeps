import { canonicalBytes, configurationValue, parseCanonicalJson } from '$lib/particeps/canonical';
import { MAXIMUM_CONFIGURATION_BYTES, type StudyConfiguration } from '$lib/particeps/types';
import { decodeConfigurationValue } from './parse';

export const AUTHORING_FORMAT = 'particeps-authoring-draft-v1';
export interface AuthoringState {
  configuration: StudyConfiguration;
  identifiers: { experiment: string; signer: string; export: string };
}

/** Portable unfinished work, containing public keys only, never a signing secret or bundle. */
export function encodeAuthoringFile(state: AuthoringState): Uint8Array {
  const bytes = canonicalBytes({ format: AUTHORING_FORMAT,
    configuration: configurationValue(state.configuration), identifiers: state.identifiers });
  if (bytes.length > MAXIMUM_CONFIGURATION_BYTES + 1024) throw new Error('draft_too_large');
  return bytes;
}

export function decodeAuthoringFile(bytes: Uint8Array): AuthoringState {
  if (bytes.length > MAXIMUM_CONFIGURATION_BYTES + 1024) throw new Error('draft_too_large');
  const value = parseCanonicalJson(bytes);
  if (!record(value) || Object.keys(value).sort().join(',') !== 'configuration,format,identifiers' ||
    value.format !== AUTHORING_FORMAT || !record(value.identifiers) ||
    Object.keys(value.identifiers).sort().join(',') !== 'experiment,export,signer' ||
    !Object.values(value.identifiers).every(item => typeof item === 'string' && item.length <= 64)) {
    throw new Error('invalid_authoring_file');
  }
  return { configuration: decodeConfigurationValue(value.configuration),
    identifiers: value.identifiers as AuthoringState['identifiers'] };
}

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
