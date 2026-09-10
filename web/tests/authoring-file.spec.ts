import { describe, expect, it } from 'vitest';
import { canonicalBytes, canonicalize, configurationValue } from '$lib/particeps/canonical';
import { emptyConfiguration, validate } from '$lib/particeps/schema';
import { MAXIMUM_CONFIGURATION_BYTES } from '$lib/particeps/types';
import { AUTHORING_FORMAT, decodeAuthoringFile, encodeAuthoringFile, type AuthoringState } from '../src/routes/researcher/authoring-file';
import { createDraft } from '../src/routes/researcher/draft.svelte';
import { HPKE, SIGNING, validConfiguration } from './fixture';

function unfinished(): AuthoringState {
  const configuration = emptyConfiguration();
  configuration.title = 'Study in progress';
  configuration.surveys = [{ id: 'draft-survey', title: { default: '', translations: { 'zh-TW': '' } }, description: { default: '', translations: {} }, questions: [] }];
  return { configuration, identifiers: { experiment: 'draft-experiment', signer: '', export: '' } };
}

describe('portable authoring draft', () => {
  it('roundtrips structurally complete but unfinished studies, including empty questions and translations', () => {
    const state = unfinished();
    expect(validate(state.configuration).length).toBeGreaterThan(0);
    expect(decodeAuthoringFile(encodeAuthoringFile(state))).toEqual(state);
  });

  it('restores unfinished finite numeric edits without treating them as valid protocol integers', () => {
    const state = unfinished(); state.configuration.duration_hours = 1.5;
    const decoded = decodeAuthoringFile(encodeAuthoringFile(state));
    expect(decoded.configuration.duration_hours).toBe(1.5);
    expect(validate(decoded.configuration)).toContainEqual({ path: 'duration_hours', code: 'integer' });
  });

  it('saves public configuration and identifiers without private keys or decrypted bundle data', () => {
    const draft = createDraft(); draft.importSigning(SIGNING.privateKey); draft.importHpke(HPKE.privateKey);
    draft.configuration.title = 'Unfinished confidential draft'; draft.pinExperimentId('study-in-progress');
    draft.pinSignerKeyId('lab-signer-2026'); draft.pinExportKeyId('lab-export-2026');
    const serialized = new TextDecoder().decode(draft.authoringBytes);
    expect(serialized).not.toContain(SIGNING.privateKey); expect(serialized).not.toContain(HPKE.privateKey);
    expect(serialized).toContain(SIGNING.publicKey); expect(serialized).toContain(HPKE.publicKey);
    expect(Object.keys(JSON.parse(serialized)).sort()).toEqual(['configuration', 'format', 'identifiers']);
    const restored = createDraft(); restored.loadAuthoring(draft.authoringBytes);
    expect(restored.configuration.title).toBe('Unfinished confidential draft');
    expect(restored.experimentId).toBe('study-in-progress');
    expect(restored.signerKeyId).toBe('lab-signer-2026'); expect(restored.exportKeyId).toBe('lab-export-2026');
    expect(restored.signing.kind).toBe('empty'); expect(restored.hpke.kind).toBe('empty');
    expect(restored.envelope).toBeNull(); expect(restored.unsavedChanges).toBe(false);
  });

  it('rejects unknown outer or inner fields and malformed identifiers atomically', () => {
    const state = unfinished();
    const base = { format: AUTHORING_FORMAT, configuration: configurationValue(state.configuration), identifiers: state.identifiers };
    const foreignValues = [
      { ...base, privateKey: SIGNING.privateKey },
      { ...base, format: 'unknown-draft-format' },
      { ...base, identifiers: { ...state.identifiers, extra: 'unknown' } },
      { ...base, identifiers: { ...state.identifiers, experiment: 123 } },
      { ...base, identifiers: { ...state.identifiers, experiment: 'x'.repeat(65) } },
      { ...base, configuration: { ...(base.configuration as object), secret: SIGNING.privateKey } }
    ];
    const draft = createDraft(); draft.configuration.title = 'Existing work'; const before = canonicalize(draft.configuration);
    for (const foreign of foreignValues) {
      const bytes = canonicalBytes(foreign);
      expect(() => decodeAuthoringFile(bytes)).toThrow();
      expect(() => draft.loadAuthoring(bytes)).toThrow();
      expect(canonicalize(draft.configuration)).toBe(before);
    }
  });

  it('rejects oversized saved files before parsing and oversized output before downloading', () => {
    expect(() => decodeAuthoringFile(new Uint8Array(MAXIMUM_CONFIGURATION_BYTES + 1025))).toThrow('draft_too_large');
    const state = unfinished(); state.configuration.purpose = 'x'.repeat(MAXIMUM_CONFIGURATION_BYTES + 1025);
    expect(() => encodeAuthoringFile(state)).toThrow('draft_too_large');
  });

  it('rejects malformed JSON, noncanonical files, duplicate keys, and missing structure', () => {
    const encode = (text: string) => new TextEncoder().encode(text);
    expect(() => decodeAuthoringFile(encode('{'))).toThrow();
    expect(() => decodeAuthoringFile(encode(JSON.stringify({ format: AUTHORING_FORMAT, configuration: {}, identifiers: {} }, null, 2)))).toThrow();
    expect(() => decodeAuthoringFile(encode('{"format":"a","format":"b"}'))).toThrow();
    expect(() => decodeAuthoringFile(canonicalBytes({ format: AUTHORING_FORMAT, configuration: {}, identifiers: { experiment: '', signer: '', export: '' } }))).toThrow();
  });

  it('keeps signed configuration parser strict for fractional protocol numbers', async () => {
    const { parseConfiguration } = await import('../src/routes/researcher/parse');
    const configuration = validConfiguration({ duration_hours: 1.5 });
    expect(() => parseConfiguration(canonicalBytes(configurationValue(configuration)))).toThrow();
  });
});
