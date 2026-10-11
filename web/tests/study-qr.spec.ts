import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { studyJoinLink } from '$lib/participant/study-qr';
import { parseJoinLink } from '$lib/particeps/join';

const bytes = readFileSync(new URL('../static/studies/phone-usage-five-day/study.partcfg', import.meta.url));
const url = 'https://jacoblincool.github.io/particeps/studies/phone-usage-five-day/study.partcfg';
describe('participant study QR', () => {
  it('encodes a valid App join link bound to the current artifact and trusted signer', () => {
    expect(parseJoinLink(studyJoinLink(url, bytes))).toEqual({
      artifactUrl: url,
      artifactSha256: createHash('sha256').update(bytes).digest('hex'),
      signerFingerprint: '3E17CD8A4AD2198D87B781EA9EBD3201'
    });
  });
  it('rejects a modified study instead of displaying an untrusted QR', () => {
    const changed = Uint8Array.from(bytes);
    changed[changed.length - 1] ^= 1;
    expect(() => studyJoinLink(url, changed)).toThrow();
  });
});
