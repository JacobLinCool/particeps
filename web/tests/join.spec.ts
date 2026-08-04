import { describe, expect, it } from 'vitest';
import { createJoinLink, encodeJoinLink, parseJoinLink } from '../src/lib/adc/join';

describe('immutable join links', () => {
  it('round-trips the exact artifact digest and signer fingerprint', () => {
    const link = createJoinLink(
      'https://artifacts.example.invalid/join/dGhpcy1pcy1hLTEyOC1iaXQtdG9rZW4',
      new Uint8Array([1, 2, 3]),
      '0123 4567 89AB CDEF FEDC BA98 7654 3210'
    );

    expect(parseJoinLink(link)).toEqual({
      artifactUrl:
        'https://artifacts.example.invalid/join/dGhpcy1pcy1hLTEyOC1iaXQtdG9rZW4',
      artifactSha256: '039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81',
      signerFingerprint: '0123456789ABCDEFFEDCBA9876543210'
    });
  });

  it('rejects URLs that WHATWG and URI implementations normalize differently', () => {
    for (const artifactUrl of [
      'https://EXAMPLE.invalid:443/a/../config.adccfg',
      'https://artifacts.example.invalid/config.adccfg?download=1',
      'https://artifacts.example.invalid/a//config.adccfg',
      'https://artifacts.example.invalid/a/%63onfig.adccfg',
      'https://127.0.0.1/config.adccfg'
    ]) {
      expect(() =>
        encodeJoinLink({
          artifactUrl,
          artifactSha256: '0'.repeat(64),
          signerFingerprint: 'A'.repeat(32)
        })
      ).toThrow('join_artifact_url_invalid');
    }
  });

  it('requires opaque personalized paths and never carries the roster code', () => {
    expect(() =>
      createJoinLink(
        'https://artifacts.example.invalid/alice-001',
        new Uint8Array([1]),
        'A'.repeat(32),
        'alice-001'
      )
    ).toThrow('join_url_exposes_participant_id');
    expect(() =>
      createJoinLink(
        'https://artifacts.example.invalid/config.adccfg',
        new Uint8Array([1]),
        'A'.repeat(32),
        'roster-code'
      )
    ).toThrow('join_url_requires_opaque_path');
    expect(
      createJoinLink(
        'https://artifacts.example.invalid/MDEyMzQ1Njc4OWFiY2RlZg',
        new Uint8Array([1]),
        'A'.repeat(32),
        'roster-code'
      )
    ).not.toContain('roster-code');
  });

  it('rejects ambiguous, mutable, or noncanonical encodings', () => {
    const valid = encodeJoinLink({
      artifactUrl: 'https://artifacts.example.invalid/config.adccfg',
      artifactSha256: '0'.repeat(64),
      signerFingerprint: 'A'.repeat(32)
    });
    const artifact = 'https%3A%2F%2Fartifacts.example.invalid%2Fconfig.adccfg';
    for (const hostile of [
      valid.replace('adc://', 'https://'),
      valid.replace('artifact=', 'unknown=x&artifact='),
      valid.replace('&sha256=', `&sha256=${'0'.repeat(64)}&sha256=`),
      valid.replace('https%3A', 'http%3A'),
      valid.replace(artifact, 'https%3A%2F%2Fuser%40artifacts.example.invalid%2Fconfig.adccfg'),
      valid.replace(artifact, `${artifact}%23mutable`),
      valid.replace('%2F', '%2f'),
      `${valid}&extra=1`
    ]) {
      expect(() => parseJoinLink(hostile), hostile).toThrow();
    }
  });
});
