import { describe, expect, it } from 'vitest';
import { bundleContext, openBundle, type ResearchDocument } from '../src/lib/particeps/bundle';
import { canonicalize } from '../src/lib/particeps/canonical';
import { generateHpkeKeyPair } from '../src/lib/particeps/crypto';
import { HPKE, SIGNING, validConfiguration } from './fixture';
import { sealBundle } from './seal';

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value));

describe('PTCEXP01 Protocol v1 reader', () => {
  it('opens the fixed RFC 9180 framing and preserves decimal 64-bit values', async () => {
    const configuration = validConfiguration();
    const bytes = await sealBundle(configuration, SIGNING.privateKey);
    expect(new TextDecoder().decode(bytes.subarray(0, 8))).toBe('PTCEXP01');
    expect(new DataView(bytes.buffer).getUint16(56)).toBe(
      new TextEncoder().encode(configuration.export.researcher_key_id).length
    );

    const result = await openBundle(bytes, configuration, HPKE.privateKey);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.bundle.document.bundle_id).toBe('00112233-4455-4677-8899-aabbccddeeff');
    expect(result.bundle.document.experiment.events[0].observed_time.monotonic_time_nanos).toBe(
      '9007199254740993'
    );
    expect(result.bundle.document.experiment.event_count).toBe('2');
    expect(canonicalize(JSON.parse(result.bundle.text))).toBe(result.bundle.text);
  });

  it('uses one JCS context for HPKE info and content AAD', () => {
    expect(
      new TextDecoder().decode(
        bundleContext(
          '00112233-4455-4677-8899-aabbccddeeff',
          '00'.repeat(32),
          'protocol-export'
        )
      )
    ).toBe(
      '{"bundle_format":"particeps-research-bundle-v1","bundle_id":"00112233-4455-4677-8899-aabbccddeeff","configuration_sha256":"' +
        '00'.repeat(32) +
        '","researcher_key_id":"protocol-export"}'
    );
  });

  it.each(['', ' ', '0x10', '0b10', 'NaN', 'Infinity'])(
    'rejects a non-decimal sensor float spelling: %j',
    async (hostile) => {
      const base = validConfiguration();
      const configuration = validConfiguration({
        collectors: [
          ...base.collectors,
          {
            id: 'gyroscope.v1',
            required: false,
            config: { maximum_report_latency_us: 1_000_000, sampling_period_us: 20_000 }
          }
        ]
      });
      const bytes = await sealBundle(configuration, SIGNING.privateKey, {
        document: (value) => {
          const changed = clone(value);
          changed.experiment.events[0] = {
            sequence_number: '1',
            collector_id: 'gyroscope.v1',
            payload_schema_version: 1,
            observed_time: changed.experiment.events[0].observed_time,
            payload_type: 'GYROSCOPE_SAMPLE',
            fields: {
              accuracy: '3',
              source_elapsed_realtime_nanos: '1000000000',
              x_radians_per_second: hostile,
              y_radians_per_second: '0.2',
              z_radians_per_second: '0.3'
            }
          };
          return changed;
        }
      });

      expect(await openBundle(bytes, configuration, HPKE.privateKey)).toEqual({
        ok: false,
        failure: 'unreadable'
      });
    }
  );

  it('distinguishes wrong configuration, wrong key, HPKE corruption, and body corruption', async () => {
    const configuration = validConfiguration();
    const valid = await sealBundle(configuration, SIGNING.privateKey);

    expect(await openBundle(valid, { ...configuration, configuration_id: 'other-config' }, HPKE.privateKey))
      .toEqual({ ok: false, failure: 'wrong_study' });
    expect(await openBundle(valid, configuration, generateHpkeKeyPair().privateKey)).toEqual({
      ok: false,
      failure: 'wrong_key'
    });

    const wrapped = valid.slice();
    const wrappedAt = 70 + new DataView(valid.buffer).getUint16(56);
    wrapped[wrappedAt + 40] ^= 1;
    expect(await openBundle(wrapped, configuration, HPKE.privateKey)).toEqual({
      ok: false,
      failure: 'unwrap_failed'
    });

    const body = valid.slice();
    body[body.length - 1] ^= 1;
    expect(await openBundle(body, configuration, HPKE.privateKey)).toEqual({
      ok: false,
      failure: 'tag_failed'
    });
  });

  it.each([
    {
      name: 'unknown root member',
      mutate: (value: ResearchDocument) => ({ ...value, future: true })
    },
    {
      name: 'numeric sequence instead of decimal string',
      mutate: (value: ResearchDocument) => {
        const changed = clone(value) as unknown as { experiment: { events: Array<{ sequence_number: unknown }> } };
        changed.experiment.events[0].sequence_number = 1;
        return changed;
      }
    },
    {
      name: 'conflicting event count',
      mutate: (value: ResearchDocument) => {
        const changed = clone(value);
        changed.experiment.event_count = '3';
        return changed;
      }
    },
    {
      name: 'wrong actual range',
      mutate: (value: ResearchDocument) => {
        const changed = clone(value);
        changed.experiment.first_sequence_number = '0';
        return changed;
      }
    },
    {
      name: 'non-contiguous event range',
      mutate: (value: ResearchDocument) => {
        const changed = clone(value);
        changed.experiment.events[1].sequence_number = '3';
        changed.experiment.last_sequence_number = '3';
        return changed;
      }
    },
    {
      name: 'empty automatic upload',
      mutate: (value: ResearchDocument) => {
        const changed = clone(value);
        changed.bundle_kind = 'automatic_upload';
        changed.experiment.events = [];
        changed.experiment.event_count = '0';
        changed.experiment.first_sequence_number = '3';
        changed.experiment.last_sequence_number = '2';
        return changed;
      }
    },
    {
      name: 'old padded signature encoding',
      mutate: (value: ResearchDocument) => {
        const changed = clone(value);
        changed.configuration_signature.signature += '==';
        return changed;
      }
    }
  ])('fails closed on $name', async ({ mutate }) => {
    const configuration = validConfiguration();
    const bytes = await sealBundle(configuration, SIGNING.privateKey, { document: mutate });
    expect(await openBundle(bytes, configuration, HPKE.privateKey)).toEqual({
      ok: false,
      failure: 'unreadable'
    });
  });

  it('rejects a truncated PTCEXP01 header and the retired ADCEXP01 magic on an otherwise valid bundle', async () => {
    const configuration = validConfiguration();
    const truncated = new Uint8Array(64);
    truncated.set(new TextEncoder().encode('PTCEXP01'));
    expect(await openBundle(truncated, configuration, HPKE.privateKey)).toEqual({
      ok: false,
      failure: 'not_a_bundle'
    });

    const valid = await sealBundle(configuration, SIGNING.privateKey);
    expect(await openBundle(valid, configuration, HPKE.privateKey)).toMatchObject({ ok: true });
    const retired = valid.slice();
    // Retired-identity rejection fixture. `ADCEXP01` is the old export magic and must stay spelled
    // out here: a rename sweep that "fixes" it would leave this test proving nothing.
    retired.set(new TextEncoder().encode('ADCEXP01'));
    expect(await openBundle(retired, configuration, HPKE.privateKey)).toEqual({
      ok: false,
      failure: 'not_a_bundle'
    });
  });

  it('rejects a non-random bundle UUID and a noncanonical key ID before decryption', async () => {
    const configuration = validConfiguration();
    const invalidUuid = await sealBundle(configuration, SIGNING.privateKey, {
      bundleId: '00112233-4455-0677-8899-aabbccddeeff'
    });
    expect(await openBundle(invalidUuid, configuration, HPKE.privateKey)).toEqual({
      ok: false,
      failure: 'not_a_bundle'
    });
    const invalidKeyId = await sealBundle(configuration, SIGNING.privateKey, { keyId: 'Bad' });
    expect(await openBundle(invalidKeyId, configuration, HPKE.privateKey)).toEqual({
      ok: false,
      failure: 'not_a_bundle'
    });
  });
});
