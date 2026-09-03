/** Shared configuration fixture for the web suite only.
 *
 * The cross-language conformance vectors in `protocol/v1/conformance-vectors.json` are built by
 * `tools/generate_protocol_vectors.mjs` from its own configuration, so changing values here cannot
 * move a published vector.
 */

import {
  encodeBase64Url,
  hpkeKeyPairFromPrivate,
  signingKeyPairFromPrivate
} from '../src/lib/particeps/crypto';
import type { StudyConfiguration } from '../src/lib/particeps/types';

const raw = (byte: number) => encodeBase64Url(new Uint8Array(32).fill(byte));

export const SIGNING = signingKeyPairFromPrivate(raw(0x11));
export const HPKE = hpkeKeyPairFromPrivate(raw(0x22));

export function validConfiguration(
  overrides: Partial<StudyConfiguration> = {}
): StudyConfiguration {
  return {
    schema_version: 1,
    platform: 'android',
    experiment_id: 'protocol-study',
    configuration_id: 'protocol-study-000001',
    assigned_participant_id: null,
    issued_at: '2026-01-01T00:00:00Z',
    expires_at: '2027-01-01T00:00:00Z',
    minimum_client_version: '1',
    title: 'Protocol v1 study',
    researcher: { name: 'Protocol Fixture Lab', contact: 'fixture@example.invalid' },
    purpose: 'Protocol conformance',
    duration_hours: 24,
    consent: { document_version: '2026-01', summary: 'Collect lifecycle test events.' },
    collectors: [
      { id: 'app_lifecycle.v1', required: true, profiles: [{ id: 'continuous', config: {} }] },
      {
        id: 'location.v1',
        required: false,
        profiles: [{
          id: 'continuous',
          config: {
            interval_millis: 60_000,
            minimum_interval_millis: 30_000,
            maximum_batch_delay_millis: 300_000,
            minimum_displacement_millimeters: 25_000,
            priority: 'BALANCED'
          }
        }]
      }
    ],
    surveys: [],
    interventions: [],
    automations: [
      {
        type: 'resource_binding', id: 'bind-app-lifecycle',
        resource: { kind: 'collector', id: 'app_lifecycle.v1' },
        cases: [{ condition: { type: 'study_session_active' }, profile_id: 'continuous' }],
        default_profile_id: 'continuous'
      },
      {
        type: 'resource_binding', id: 'bind-location',
        resource: { kind: 'collector', id: 'location.v1' },
        cases: [{ condition: { type: 'study_session_active' }, profile_id: 'continuous' }],
        default_profile_id: null
      }
    ],
    traffic_shaping: {},
    storage: { maximum_local_bytes: 1_073_741_824 },
    signer: { key_id: 'protocol-signer', public_key: SIGNING.publicKey },
    export: { researcher_key_id: 'protocol-export', hpke_public_key: HPKE.publicKey },
    upload: null,
    ...overrides
  };
}
