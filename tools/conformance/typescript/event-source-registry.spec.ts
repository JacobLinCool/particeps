import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import {
  COLLECTOR_SOURCE_IDS,
  EVENT_IDENTITIES,
  EVENT_SOURCE_CONTRACTS,
  EVENT_SOURCE_REGISTRY,
  EVENT_SOURCE_REGISTRY_SHA256,
  SYSTEM_SOURCE_IDS,
  eventContract,
  sourceContract
} from '../../../web/src/lib/particeps/generated/event-source-registry';

const registry = JSON.parse(
  readFileSync(new URL('../../../protocol/v1/event-source-registry.json', import.meta.url), 'utf8')
);
const registryDigest = readFileSync(
  new URL('../../../protocol/v1/generated/event-source-registry.sha256', import.meta.url),
  'utf8'
).trim();
const corpusRoot = new URL('../../../protocol/v1/conformance/event-source-registry/', import.meta.url);
const corpusManifest = JSON.parse(readFileSync(new URL('manifest.json', corpusRoot), 'utf8'));

describe('generated event-source registry projection', () => {
  it('is byte-generation-equivalent to the authoritative registry and digest artifact', () => {
    expect(EVENT_SOURCE_REGISTRY).toEqual(registry);
    expect(EVENT_SOURCE_REGISTRY_SHA256).toBe(registryDigest);
  });

  it('consumes the language-neutral canonical registry corpus', () => {
    const current = corpusManifest.valid_cases.find((item: { id: string }) => item.id === 'current-registry');
    expect(current).toBeDefined();
    const canonical = readFileSync(new URL(current.canonical_jcs, corpusRoot));
    expect(JSON.parse(canonical.toString('utf8'))).toEqual(EVENT_SOURCE_REGISTRY);
    expect(createHash('sha256').update(canonical).digest('hex')).toBe(EVENT_SOURCE_REGISTRY_SHA256);
    expect(current.registry_sha256).toBe(EVENT_SOURCE_REGISTRY_SHA256);
    expect(corpusManifest.invalid_cases.length).toBeGreaterThan(0);
  });

  it('partitions every source exactly once and indexes every typed event identity', () => {
    expect(new Set([...COLLECTOR_SOURCE_IDS, ...SYSTEM_SOURCE_IDS])).toEqual(
      new Set(EVENT_SOURCE_CONTRACTS.map((source) => source.source_id))
    );
    expect(new Set(COLLECTOR_SOURCE_IDS).size).toBe(COLLECTOR_SOURCE_IDS.length);
    expect(new Set(SYSTEM_SOURCE_IDS).size).toBe(SYSTEM_SOURCE_IDS.length);
    expect(new Set(EVENT_IDENTITIES.map((identity) =>
      `${identity.source_id}/${identity.schema_version}/${identity.event_type}`
    )).size).toBe(EVENT_IDENTITIES.length);
    for (const identity of EVENT_IDENTITIES) {
      expect(eventContract(identity.source_id, identity.schema_version, identity.event_type))
        .toBeDefined();
    }
  });

  it('exposes seconds-based retrospective collector profiles and runtime-only system sources', () => {
    for (const sourceId of ['network_usage.v1', 'usage_events.v1']) {
      const contract = sourceContract(sourceId, 1);
      expect(contract?.configuration?.fields.poll_interval_seconds).toMatchObject({
        type: 'integer',
        minimum: 15,
        maximum: 86_400
      });
      expect(contract?.configuration?.fields.poll_interval_minutes).toBeUndefined();
    }
    for (const sourceId of SYSTEM_SOURCE_IDS) {
      expect(sourceContract(sourceId, 1)).toMatchObject({
        source_kind: 'SYSTEM',
        emission_authority: 'RUNTIME_ONLY',
        selectable: false,
        configuration: null
      });
    }
  });

  it('bridges lifecycle outputs as audit-only, never researcher condition triggers', () => {
    for (const eventType of ['STUDY_STARTED', 'STUDY_RESUMED', 'STUDY_RUNNING']) {
      const contract = eventContract('study_runtime.v1', 1, eventType);
      expect(contract?.trigger, eventType).toEqual({
        scope: 'AUDIT_ONLY', condition_kinds: [], presence: null
      });
      expect(contract?.privacy.trigger_exposure, eventType).toBe('NONE');
    }
  });
});
