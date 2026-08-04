import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { openBundle } from '../../../web/src/lib/adc/bundle';
import { parseCanonicalJson } from '../../../web/src/lib/adc/canonical';
import { verify } from '../../../web/src/lib/adc/crypto';
import { decodeEnvelope } from '../../../web/src/lib/adc/envelope';
import { encodeJoinLink, parseJoinLink } from '../../../web/src/lib/adc/join';
import { parseConfiguration } from '../../../web/src/routes/researcher/parse';

type Vector = {
  category: string;
  entrypoint: 'canonical_json' | 'configuration_jcs' | 'signed_configuration' | 'bundle' | 'bundle_unwrap_context' | 'receipt';
  expected_failure: string;
  id: string;
  input_hex: string;
};

const corpus = JSON.parse(
  readFileSync(new URL('../../../protocol/v1/conformance-vectors.json', import.meta.url), 'utf8')
) as {
  hostile: Vector[];
  valid: {
    bundle: Record<string, string>;
    canonical_json: { canonical_jcs_utf8_hex: string };
    signed_configuration: Record<string, string>;
    upload_receipt: { canonical_jcs_utf8_hex: string; value: Record<string, string> };
  };
};
const joinCorpus = JSON.parse(
  readFileSync(new URL('../../../protocol/v1/join-link-vectors.json', import.meta.url), 'utf8')
) as {
  hostile: Array<{ encoded: string; id: string }>;
  valid: {
    artifact_sha256: string;
    artifact_url: string;
    encoded: string;
    signer_fingerprint: string;
  };
};
const bytes = (hex: string) => Uint8Array.from(Buffer.from(hex, 'hex'));

describe('shared Protocol v1 conformance corpus', () => {
  const signed = corpus.valid.signed_configuration;
  const configurationBytes = bytes(signed.canonical_jcs_utf8_hex);
  const configuration = parseConfiguration(configurationBytes);
  const bundle = corpus.valid.bundle;
  const container = bytes(bundle.container_hex);

  it('is consumed by the actual configuration and bundle readers', async () => {
    expect(parseCanonicalJson(bytes(corpus.valid.canonical_json.canonical_jcs_utf8_hex)))
      .toBeTypeOf('object');
    const envelope = decodeEnvelope(bytes(signed.envelope_hex));
    expect(envelope.signerKeyId).toBe(signed.signer_key_id);
    expect(envelope.configurationBytes).toEqual(configurationBytes);
    expect(verify(configurationBytes, envelope.signature, signed.signer_public_key_base64url)).toBe(true);

    const opened = await openBundle(container, configuration, bundle.researcher_private_key_base64url);
    expect(opened.ok).toBe(true);
    if (!opened.ok) return;
    expect(new TextEncoder().encode(opened.bundle.text)).toEqual(bytes(bundle.document_jcs_utf8_hex));
  });

  for (const vector of corpus.hostile) {
    if (vector.entrypoint === 'receipt') continue; // The TypeScript receiver owns this entrypoint.
    it(`rejects ${vector.id}`, async () => {
      const input = bytes(vector.input_hex);
      if (vector.entrypoint === 'canonical_json') {
        expect(() => parseCanonicalJson(input)).toThrow();
      } else if (vector.entrypoint === 'configuration_jcs') {
        expect(() => parseConfiguration(input)).toThrow();
      } else if (vector.entrypoint === 'signed_configuration') {
        expect(() => {
          const envelope = decodeEnvelope(input);
          parseConfiguration(envelope.configurationBytes);
          if (!verify(envelope.configurationBytes, envelope.signature, signed.signer_public_key_base64url)) {
            throw new Error('configuration_signature_invalid');
          }
        }).toThrow();
      } else if (vector.entrypoint === 'bundle') {
        await expect(openBundle(input, configuration, bundle.researcher_private_key_base64url))
          .resolves.toMatchObject({ ok: false });
      } else {
        const wrongContext = parseCanonicalJson(input) as { bundle_id: string };
        const mutated = container.slice();
        mutated.set(bytes(wrongContext.bundle_id.replaceAll('-', '')), 8);
        await expect(openBundle(mutated, configuration, bundle.researcher_private_key_base64url))
          .resolves.toMatchObject({ ok: false, failure: 'unwrap_failed' });
      }
    });
  }
});

describe('shared Protocol v1 join-link corpus', () => {
  it('accepts the canonical join link byte-for-byte', () => {
    const parsed = parseJoinLink(joinCorpus.valid.encoded);
    expect(parsed).toEqual({
      artifactUrl: joinCorpus.valid.artifact_url,
      artifactSha256: joinCorpus.valid.artifact_sha256,
      signerFingerprint: joinCorpus.valid.signer_fingerprint
    });
    expect(encodeJoinLink(parsed)).toBe(joinCorpus.valid.encoded);
  });

  for (const vector of joinCorpus.hostile) {
    it(`rejects ${vector.id}`, () => {
      expect(() => parseJoinLink(vector.encoded)).toThrow();
    });
  }
});
