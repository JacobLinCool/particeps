import { describe, expect, it } from 'vitest';
import demoStudyJson from '../../researcher-tools/examples/demo-study.json?raw';
import { parseInstant } from '$lib/adc/canonical';
import { validate } from '$lib/adc/schema';
import { isUsableHpkePublicKeyset } from '$lib/adc/tink';
import { parseConfiguration } from '../src/routes/researcher/parse';
import type { StudyConfiguration, TinkKeyset } from '$lib/adc/types';

/**
 * A study file the editor did not write.
 *
 * Opening a previous configuration is a first-class flow — the cross-language workflow is exactly
 * that — so every byte in these files is attacker-shaped as far as this page is concerned. The
 * failures below all shared one outcome before they were closed: the page signed the document
 * without complaint and something downstream, a device or a decryption weeks later, refused it.
 */

const encoder = new TextEncoder();
const demo = JSON.parse(demoStudyJson) as Record<string, unknown>;

function load(mutate: (document: Record<string, unknown>) => void): StudyConfiguration {
  const document = JSON.parse(demoStudyJson) as Record<string, unknown>;
  mutate(document);
  return parseConfiguration(encoder.encode(JSON.stringify(document)));
}

function refuses(mutate: (document: Record<string, unknown>) => void): void {
  expect(() => load(mutate)).toThrow();
}

const keyset = demo.export as { tink_hpke_public_keyset: TinkKeyset };
const goodKeyset = keyset.tink_hpke_public_keyset;

function withKeyset(replacement: unknown): StudyConfiguration {
  return load((document) => {
    (document.export as Record<string, unknown>).tink_hpke_public_keyset = replacement;
  });
}

function codes(configuration: StudyConfiguration): string[] {
  return validate(configuration).map((issue) => issue.code);
}

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

describe('the demo study', () => {
  it('is valid, so every failure below is the mutation and not the fixture', () => {
    expect(validate(parseConfiguration(encoder.encode(demoStudyJson)))).toEqual([]);
  });
});

describe('a collector the codec cannot write', () => {
  it('refuses the file rather than encoding the token undefined', () => {
    refuses((document) => {
      document.collectors = [{ id: 'bogus.v1', required: true, config: {} }];
    });
  });

  it('refuses a missing collector config instead of inventing defaults', () => {
    refuses((document) => {
      document.collectors = [{ id: 'accelerometer.v1', required: true }];
    });
  });

  it('survives a collector that is a bare string', () => {
    refuses((document) => {
      document.collectors = ['accelerometer.v1'];
    });
  });

  it('keeps an out-of-range value so validate can report it', () => {
    const configuration = load((document) => {
      document.collectors = [
        { id: 'keyboard_touch.v1', required: false, config: { trajectory_sampling_hz: 9_000 } }
      ];
    });
    expect(codes(configuration)).toEqual(['number_range']);
  });
});

describe('transports', () => {
  it('refuses a name NetworkTransport.valueOf would refuse', () => {
    refuses((document) => {
      document.collectors = [
        { id: 'network_usage.v1', required: false, config: { transports: ['fibre'] } }
      ];
    });
  });

  /** A string has a `length` and an `includes`, so it passed both the check and the encoder. */
  it('does not let a string pass as a one-element set', () => {
    refuses((document) => {
      document.collectors = [
        { id: 'network_usage.v1', required: false, config: { transports: 'wifi' } }
      ];
    });
  });

  it('reports an empty set rather than encoding one', () => {
    const configuration = load((document) => {
      document.collectors = [
        {
          id: 'network_usage.v1',
          required: false,
          config: { transports: [], poll_interval_minutes: 15 }
        }
      ];
    });
    expect(codes(configuration)).toEqual(['transports_empty']);
  });
});

describe('location priority', () => {
  it('refuses a value LocationPriority.valueOf would refuse', () => {
    refuses((document) => {
      document.collectors = [
        { id: 'location.v1', required: false, config: { priority: 'FASTEST' } }
      ];
    });
  });
});

describe('legacy prompts', () => {
  it('rejects the removed prompt shape instead of silently migrating it', () => {
    refuses((document) => {
      delete document.interventions;
      document.prompts = [{ id: 'old-prompt', delay_minutes: 5, message: 'hi' }];
    });
  });
});

describe('closed-world v1 shape', () => {
  it('refuses unknown nested fields instead of dropping them before signing', () => {
    refuses((document) => {
      (document.researcher as Record<string, unknown>).legacy_name = 'discard me';
    });
  });

  it('refuses a mistyped scalar instead of replacing it with a default', () => {
    refuses((document) => {
      document.duration_hours = '24';
    });
  });

  it.each(['', 'contains space', 'é', 'a'.repeat(65)])('reports an invalid assigned ID %j', (id) => {
    const configuration = load((document) => { document.assigned_participant_id = id; });
    expect(codes(configuration)).toContain('id_format');
  });

  it('reports a schedule whose durable lifetime occurrence set exceeds the metadata bound', () => {
    const configuration = load((document) => {
      document.interventions = [{
        id: 'too-frequent',
        action: { type: 'notification', notification_title: 'Check-in', notification_message: 'Check in now.' },
        triggers: [{
          id: 'every-minute',
          schedule: { type: 'interval', start_offset_minutes: 0, interval_minutes: 1, clock: 'CALENDAR_TIME' },
          availability_minutes: 5
        }]
      }];
    });
    expect(codes(configuration)).toContain('schedule_bounds');
  });
});

/**
 * `ExportConfiguration` bounds this document's length and nothing else, so before these checks each
 * of the six below signed cleanly here and was refused by real Tink on the phone at export time.
 */
describe('the export keyset', () => {
  it('accepts the one the CLI ships', () => {
    expect(isUsableHpkePublicKeyset(goodKeyset)).toBe(true);
    expect(codes(withKeyset(goodKeyset))).toEqual([]);
  });

  it.each([
    [
      'a primary that names no key',
      (value: TinkKeyset) => {
        value.primaryKeyId = 999;
      }
    ],
    [
      'a disabled key',
      (value: TinkKeyset) => {
        value.key[0].status = 'DISABLED';
      }
    ],
    [
      'a RAW output prefix',
      (value: TinkKeyset) => {
        value.key[0].outputPrefixType = 'RAW';
      }
    ],
    [
      'a symmetric key',
      (value: TinkKeyset) => {
        value.key[0].keyData.typeUrl = 'type.googleapis.com/google.crypto.tink.AesGcmKey';
        value.key[0].keyData.keyMaterialType = 'SYMMETRIC';
      }
    ],
    [
      'a truncated key',
      (value: TinkKeyset) => {
        const raw = atob(value.key[0].keyData.value);
        value.key[0].keyData.value = btoa(raw.slice(0, raw.length - 1));
      }
    ],
    [
      'AES-128-GCM instead of AES-256-GCM',
      (value: TinkKeyset) => {
        const raw = [...atob(value.key[0].keyData.value)].map((c) => c.charCodeAt(0));
        raw[raw.indexOf(0x18) + 1] = 0x01;
        value.key[0].keyData.value = btoa(String.fromCharCode(...raw));
      }
    ]
  ])('refuses %s', (_name, mutate) => {
    const broken = clone(goodKeyset);
    mutate(broken);
    expect(isUsableHpkePublicKeyset(broken)).toBe(false);
    expect(codes(withKeyset(broken))).toContain('keyset_unusable');
  });
});

/**
 * `DateTimeFormatter.ISO_INSTANT` writes an offset as `+HH:MM[:ss]` and `ZoneOffset` caps the whole
 * offset at ±18:00. Accepting a spelling `Instant.parse` refuses means taking a hand-written file
 * the CLI would not.
 */
describe('instant offsets', () => {
  it.each(['+08', '+0800', '+080000', '-18:00:01', '-18:16'])('refuses %s', (offset) => {
    expect(parseInstant(`2026-01-01T00:00:00${offset}`)).toBeNull();
  });

  it.each(['Z', '+08:00', '-08:00', '+18:00', '-18:00', '+05:45'])('accepts %s', (offset) => {
    expect(parseInstant(`2026-01-01T00:00:00${offset}`)).not.toBeNull();
  });
});
