import { describe, expect, it } from 'vitest';
import {
  deriveConfigurationId,
  deriveExperimentId,
  deriveExportKeyId,
  deriveSignerKeyId,
  tag
} from '../src/lib/particeps/ids';
import { ID_PATTERN } from '../src/lib/particeps/types';
import { generateHpkeKeyPair, generateSigningKeyPair } from '../src/lib/particeps/crypto';
import { hpkeKeyPairFromPrivate, signingKeyPairFromPrivate } from '../src/routes/researcher/keys';

describe('study identifiers', () => {
  it('derives legal stable experiment IDs for ASCII and CJK titles', () => {
    expect(deriveExperimentId('Taiwan Sleep Study')).toBe('taiwan-sleep-study');
    expect(deriveExperimentId('睡眠研究')).toMatch(/^study-[a-z0-9]{6}$/);
    for (const title of ['', 'A', '😀', 'é'.repeat(100)]) {
      expect(deriveExperimentId(title)).toMatch(ID_PATTERN);
    }
  });

  it('derives a fixed-point-safe configuration ID from unnamed canonical bytes', () => {
    const first = deriveConfigurationId('protocol-study', '{"configuration_id":"","x":1}');
    const second = deriveConfigurationId('protocol-study', '{"configuration_id":"","x":2}');
    expect(first).toMatch(ID_PATTERN);
    expect(second).toMatch(ID_PATTERN);
    expect(first).not.toBe(second);
    expect(tag('same')).toBe(tag('same'));
  });
});

describe('raw-key identifiers', () => {
  it('names raw public key material deterministically by role', () => {
    const signing = generateSigningKeyPair();
    const hpke = generateHpkeKeyPair();
    const signerId = deriveSignerKeyId(signing.publicKey);
    const exportId = deriveExportKeyId(hpke.publicKey);
    expect(signerId).toMatch(/^signer-[a-z0-9]{13}$/);
    expect(exportId).toMatch(/^export-[a-z0-9]{13}$/);
    expect(deriveSignerKeyId(signing.publicKey)).toBe(signerId);
    expect(deriveExportKeyId(hpke.publicKey)).toBe(exportId);
  });

  it('returns no identifier for padded, malformed, or wrong-length key encodings', () => {
    for (const value of ['', 'AA==', 'not+a-key', 'AA']) {
      expect(deriveSignerKeyId(value)).toBe('');
      expect(deriveExportKeyId(value)).toBe('');
    }
  });

  it('raw private imports derive the same public halves and identifiers', () => {
    const signing = generateSigningKeyPair();
    const hpke = generateHpkeKeyPair();
    const reopenedSigning = signingKeyPairFromPrivate(`\n${signing.privateKey}\n`);
    const reopenedHpke = hpkeKeyPairFromPrivate(`\n${hpke.privateKey}\n`);
    expect(reopenedSigning).toEqual(signing);
    expect(reopenedHpke).toEqual(hpke);
    expect(deriveSignerKeyId(reopenedSigning.publicKey)).toBe(deriveSignerKeyId(signing.publicKey));
    expect(deriveExportKeyId(reopenedHpke.publicKey)).toBe(deriveExportKeyId(hpke.publicKey));
  });
});
