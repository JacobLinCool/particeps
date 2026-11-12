import { describe, expect, it } from 'vitest';
import { flushSync } from 'svelte';
import { canonicalBytes, canonicalConfigurationBytes, configurationValue } from '$lib/particeps/canonical';
import { sign } from '$lib/particeps/crypto';
import { encodeEnvelope } from '$lib/particeps/envelope';
import {
  PARTICIPANT_REQUESTED_WARNING,
  PARTICIPANT_TIER_LABELS,
  participantStudyUiModel
} from '$lib/particeps/participant-projection';
import { disclosureMayRevealSchedule, requiresBlindingConfirmation } from '$lib/particeps/researcher-blinding';
import { defaultCollector, emptyConfiguration, upgradeToSchema2, validate } from '$lib/particeps/schema';
import { collectorIsCore, type StudyConfiguration } from '$lib/particeps/types';
import { AUTHORING_DEFINITIONS, validateToolInput } from '$lib/particeps/webmcp-schema';
import { decodeAuthoringFile, encodeAuthoringFile } from '../src/routes/researcher/authoring-file';
import { createDraft } from '../src/routes/researcher/draft.svelte';
import { parseConfiguration } from '../src/routes/researcher/parse';
import { SIGNING, validConfiguration, validConfigurationV2 } from './fixture';
import { readFileSync } from 'node:fs';

/** The configuration as a mutable JSON value, so a test can write what a type would refuse. */
const wire = (configuration: StudyConfiguration) =>
  structuredClone(configurationValue(configuration)) as Record<string, unknown> & {
    collectors: Record<string, unknown>[];
    participant_disclosure?: Record<string, unknown>;
  };
const parseWire = (value: unknown) => parseConfiguration(canonicalBytes(value));

describe('Protocol v1 configuration dialects', () => {
  it('round-trips schema 1 exactly, with no disclosure member', () => {
    const configuration = validConfiguration();
    const bytes = canonicalConfigurationBytes(configuration);
    const parsed = parseConfiguration(bytes);
    expect(parsed).toEqual(configuration);
    expect(Object.hasOwn(parsed, 'participant_disclosure')).toBe(false);
    expect(canonicalConfigurationBytes(parsed)).toEqual(bytes);
    expect(new TextDecoder().decode(bytes)).not.toContain('participant_disclosure');
  });

  it('round-trips schema 2 with tiers, rationale, and disclosure, signed and unsigned', () => {
    const configuration = validConfigurationV2({
      participant_disclosure: { approximate_frequency: true, collection_timeline: false }
    });
    const location = configuration.collectors[1];
    if (location.tier === undefined) throw new Error('expected schema 2');
    location.tier = 'requested';
    location.rationale = 'Where you are helps us interpret phone use. 位置資料 🔬';
    expect(validate(configuration)).toEqual([]);
    const bytes = canonicalConfigurationBytes(configuration);
    const text = new TextDecoder().decode(bytes);
    expect(text).toContain('"participant_disclosure":{"approximate_frequency":true,"collection_timeline":false}');
    expect(text).toContain('"rationale":null');
    expect(text).toContain('"tier":"requested"');
    expect(text).not.toContain('"required"');
    expect(parseConfiguration(bytes)).toEqual(configuration);

    const signature = sign(bytes, SIGNING.privateKey);
    expect(parseConfiguration(encodeEnvelope(configuration.signer.key_id, bytes, signature)))
      .toEqual(configuration);
  });

  it('treats schema 1 required and schema 2 core as the same requiredness', () => {
    const v1 = validConfiguration();
    const v2 = validConfigurationV2();
    expect(v1.collectors.map(collectorIsCore)).toEqual([true, false]);
    expect(v2.collectors.map(collectorIsCore)).toEqual([true, false]);
    for (const tier of ['requested', 'optional'] as const) {
      expect(collectorIsCore({ tier, rationale: null })).toBe(false);
    }
  });

  it('requires an event-trigger source to be core, not merely requested', () => {
    const configuration = validConfigurationV2();
    configuration.interventions = [{
      id: 'prompt', required: false,
      action: { type: 'notification', notification_title: 'T', notification_message: 'M' }
    }];
    configuration.automations.push({
      type: 'occurrence', id: 'on-resume', guard: null, intervention_id: 'prompt',
      availability_seconds: 60, cooldown: null, maximum_activations: 1,
      trigger: {
        type: 'event_match', evaluation_clock: 'OBSERVED_RESEARCH_TIME',
        selector: {
          event: { source_id: 'app_lifecycle.v1', schema_version: 1, event_type: 'ACTIVITY_RESUMED' },
          predicates: []
        }
      }
    });
    configuration.automations.sort((left, right) => left.id.localeCompare(right.id));
    expect(validate(configuration)).toEqual([]);
    const lifecycle = configuration.collectors[0];
    if (lifecycle.tier === undefined) throw new Error('expected schema 2');
    lifecycle.tier = 'requested';
    expect(validate(configuration)).toContainEqual({ path: 'automations', code: 'trigger_source_liveness' });
  });
});

describe('hostile schema 2 configurations', () => {
  const base = () => wire(validConfigurationV2());

  it('rejects an unknown tier', () => {
    const value = base();
    value.collectors[0].tier = 'mandatory';
    expect(() => parseWire(value)).toThrow('parse_collector_tier');
  });

  it('rejects schema 1 `required` in a schema 2 collector, alone or beside a tier', () => {
    const replaced = base();
    delete replaced.collectors[0].tier;
    delete replaced.collectors[0].rationale;
    replaced.collectors[0].required = true;
    expect(() => parseWire(replaced)).toThrow('parse_keys');
    const added = base();
    added.collectors[0].required = true;
    expect(() => parseWire(added)).toThrow('parse_keys');
  });

  it('rejects schema 2 members in a schema 1 configuration', () => {
    const tiered = wire(validConfiguration());
    tiered.collectors[0] = { ...tiered.collectors[0], tier: 'core', rationale: null };
    delete tiered.collectors[0].required;
    expect(() => parseWire(tiered)).toThrow('parse_keys');
    const disclosed = wire(validConfiguration());
    disclosed.participant_disclosure = { approximate_frequency: false, collection_timeline: false };
    expect(() => parseWire(disclosed)).toThrow('parse_keys');
  });

  it('requires the disclosure policy with exactly two boolean switches', () => {
    const missing = base();
    delete missing.participant_disclosure;
    expect(() => parseWire(missing)).toThrow('parse_keys');
    const partial = base();
    delete partial.participant_disclosure!.collection_timeline;
    expect(() => parseWire(partial)).toThrow('parse_keys');
    const extra = base();
    extra.participant_disclosure!.raw_values = false;
    expect(() => parseWire(extra)).toThrow('parse_keys');
    const stringly = base();
    stringly.participant_disclosure!.approximate_frequency = 'true';
    expect(() => parseWire(stringly)).toThrow('parse_boolean');
    const nulled = base();
    nulled.participant_disclosure = null as never;
    expect(() => parseWire(nulled)).toThrow();
  });

  it('rejects a blank, empty, over-long, or non-string rationale', () => {
    for (const rationale of [' ', '\t\n', '　 ', '', 'x'.repeat(601), '😀'.repeat(301)]) {
      const value = base();
      value.collectors[0].rationale = rationale;
      expect(() => parseWire(value), JSON.stringify(rationale)).toThrow('parse_invalid');
    }
    const numeric = base();
    numeric.collectors[0].rationale = 7;
    expect(() => parseWire(numeric)).toThrow('parse_string');
  });

  it('accepts a rationale at the 600 UTF-16 unit bound', () => {
    for (const rationale of ['x'.repeat(600), '😀'.repeat(300), ' a ']) {
      const value = base();
      value.collectors[0].rationale = rationale;
      expect(parseWire(value).collectors[0].rationale).toBe(rationale);
    }
  });

  it('rejects any other schema version', () => {
    for (const version of [0, 3, '2', null]) {
      const value = base();
      value.schema_version = version;
      expect(() => parseWire(value), String(version)).toThrow('parse_schema_version');
    }
  });

  it('reports dialect mismatches from validate for in-memory drafts', () => {
    const v2 = validConfigurationV2();
    delete v2.participant_disclosure;
    expect(validate(v2)).toContainEqual({ path: 'participant_disclosure', code: 'required' });
    const v1 = validConfiguration({ participant_disclosure: { approximate_frequency: false, collection_timeline: false } });
    expect(validate(v1)).toContainEqual({ path: 'participant_disclosure', code: 'schema_dialect' });
    const blank = validConfigurationV2();
    (blank.collectors[0] as { rationale: string | null }).rationale = '   ';
    expect(validate(blank)).toContainEqual({ path: 'collectors.0.rationale', code: 'blank_text' });
  });
});

describe('schema 2 authoring', () => {
  it('starts new drafts and collectors in schema 2 with an optional tier', () => {
    const configuration = emptyConfiguration();
    expect(configuration.schema_version).toBe(2);
    expect(configuration.participant_disclosure).toEqual({ approximate_frequency: false, collection_timeline: false });
    expect(defaultCollector('battery_state.v1')).toMatchObject({ tier: 'optional', rationale: null });
  });

  it('converts schema 1 to schema 2 without changing requiredness', () => {
    const upgraded = upgradeToSchema2(validConfiguration());
    expect(upgraded.schema_version).toBe(2);
    expect(upgraded.participant_disclosure).toEqual({ approximate_frequency: false, collection_timeline: false });
    expect(upgraded.collectors.map((collector) => [collector.tier, collector.rationale, Object.hasOwn(collector, 'required')]))
      .toEqual([['core', null, false], ['optional', null, false]]);
    expect(validate(upgraded)).toEqual([]);
    const already = validConfigurationV2();
    expect(upgradeToSchema2(already)).toBe(already);
  });

  it('opens a signed schema 1 file as a new schema 2 draft that must be signed again', () => {
    const draft = createDraft();
    draft.importSigning(SIGNING.privateKey);
    const original = validConfiguration();
    const bytes = canonicalConfigurationBytes(original);
    draft.load(encodeEnvelope(original.signer.key_id, bytes, sign(bytes, SIGNING.privateKey)));
    flushSync();
    expect(draft.convertedFromSchema1).toBe(true);
    expect(draft.document.schema_version).toBe(2);
    expect(draft.document.collectors.map((collector) => collector.tier)).toEqual(['core', 'optional']);
    expect(draft.canonicalBytes).not.toEqual(bytes);
    expect(draft.configurationId).not.toBe(original.configuration_id);
    expect(draft.signature).toBeNull();

    const reopened = createDraft();
    reopened.loadAuthoring(draft.authoringBytes);
    expect(reopened.convertedFromSchema1).toBe(false);
    expect(reopened.configuration.schema_version).toBe(2);
    draft.reset();
    expect(draft.convertedFromSchema1).toBe(false);
  });

  it('sets tiers, rationale, and disclosure through the draft', () => {
    const draft = createDraft();
    draft.enableCollector('location.v1');
    draft.setCollectorTier('location.v1', 'requested');
    draft.setCollectorRationale('location.v1', 'Places help interpret phone use.');
    draft.setParticipantDisclosure('collection_timeline', true);
    flushSync();
    expect(draft.collector('location.v1')).toMatchObject({ tier: 'requested', rationale: 'Places help interpret phone use.' });
    expect(draft.document.participant_disclosure).toEqual({ approximate_frequency: false, collection_timeline: true });
    draft.setCollectorRationale('location.v1', '');
    expect(draft.collector('location.v1')?.rationale).toBeNull();
    for (const tier of ['core', 'optional', 'requested'] as const) {
      draft.setCollectorTier('location.v1', tier);
      expect(draft.collector('location.v1')?.tier).toBe(tier);
    }
  });

  it('round-trips both dialects through the portable authoring file', () => {
    for (const configuration of [validConfiguration(), validConfigurationV2()]) {
      const state = { configuration, identifiers: { experiment: '', signer: '', export: '' } };
      expect(decodeAuthoringFile(encodeAuthoringFile(state))).toEqual(state);
    }
  });

  it('advertises the schema 2 collector shape to agents', () => {
    const fixture = validConfigurationV2();
    for (const collector of fixture.collectors) {
      expect(validateToolInput(collector, AUTHORING_DEFINITIONS.collector)).toEqual([]);
    }
    expect(validateToolInput(validConfiguration().collectors[0], AUTHORING_DEFINITIONS.collector)).not.toEqual([]);
    expect(validateToolInput({ ...fixture.collectors[0], tier: 'mandatory' }, AUTHORING_DEFINITIONS.collector)).not.toEqual([]);
    expect(validateToolInput({ ...fixture.collectors[0], rationale: 'x'.repeat(601) }, AUTHORING_DEFINITIONS.collector)).not.toEqual([]);
    expect(validateToolInput(
      { approximate_frequency: true, collection_timeline: false }, AUTHORING_DEFINITIONS.participant_disclosure
    )).toEqual([]);
  });
});

describe('participant disclosure and blinding', () => {
  const occurrence = (configuration: StudyConfiguration) => {
    configuration.interventions = [{
      id: 'check-in', required: false,
      action: { type: 'notification', notification_title: 'T', notification_message: 'M' }
    }];
    configuration.automations.push({
      type: 'occurrence', id: 'daily-check-in', guard: null, intervention_id: 'check-in',
      availability_seconds: 3_600, cooldown: null, maximum_activations: 1,
      trigger: { type: 'schedule', schedule: { type: 'daily_local', local_time: '09:00' } }
    });
  };

  it('needs no confirmation for disclosure of continuous collection', () => {
    const configuration = validConfigurationV2({
      participant_disclosure: { approximate_frequency: true, collection_timeline: true }
    });
    expect(disclosureMayRevealSchedule(configuration)).toBe(false);
    expect(requiresBlindingConfirmation(configuration)).toBe(false);
  });

  it('requires confirmation when a switch is on and a collector is not always active', () => {
    for (const key of ['approximate_frequency', 'collection_timeline'] as const) {
      const configuration = validConfigurationV2();
      configuration.participant_disclosure![key] = true;
      const binding = configuration.automations[0];
      if (binding.type !== 'resource_binding') throw new Error('expected binding');
      binding.cases = [{
        condition: { type: 'study_local_window', first_day: 1, last_day: 366, start_local_time: '12:00', end_local_time: '17:00' },
        profile_id: 'continuous'
      }];
      binding.default_profile_id = null;
      expect(disclosureMayRevealSchedule(configuration), key).toBe(true);
      expect(requiresBlindingConfirmation(configuration), key).toBe(true);
    }
  });

  it('requires confirmation when a switch is on and the study has an occurrence automation', () => {
    const configuration = validConfigurationV2({
      participant_disclosure: { approximate_frequency: false, collection_timeline: true }
    });
    occurrence(configuration);
    expect(disclosureMayRevealSchedule(configuration)).toBe(true);
    configuration.participant_disclosure!.collection_timeline = false;
    expect(disclosureMayRevealSchedule(configuration)).toBe(false);
    // The occurrence alone already needs confirmation, as before.
    expect(requiresBlindingConfirmation(configuration)).toBe(true);
  });

  it('projects tiers and disclosure for the participant preview', () => {
    const configuration = validConfigurationV2({
      participant_disclosure: { approximate_frequency: true, collection_timeline: false }
    });
    (configuration.collectors[1] as { tier: string }).tier = 'requested';
    const model = participantStudyUiModel(configuration);
    expect(model.data_category_tiers).toEqual(['core', 'requested']);
    expect(model.disclosure).toEqual({ approximate_frequency: true, collection_timeline: false });
    expect(participantStudyUiModel(validConfiguration()).disclosure)
      .toEqual({ approximate_frequency: false, collection_timeline: false });
  });

  it('keeps the fixed tier labels and requested warning identical to the App copy', () => {
    for (const [locale, directory] of [['en', 'values'], ['zh-TW', 'values-zh-rTW']] as const) {
      expect(PARTICIPANT_TIER_LABELS[locale].core).toBe(appString(directory, 'data_required'));
      expect(PARTICIPANT_TIER_LABELS[locale].requested).toBe(appString(directory, 'data_requested'));
      expect(PARTICIPANT_TIER_LABELS[locale].optional).toBe(appString(directory, 'data_optional'));
      expect(PARTICIPANT_REQUESTED_WARNING[locale]).toBe(appString(directory, 'data_requested_warning'));
    }
  });
});

function appString(directory: string, name: string): string {
  const resources = readFileSync(
    new URL(`../../app/src/main/res/${directory}/strings.xml`, import.meta.url),
    'utf8'
  );
  const match = resources.match(new RegExp(`<string name="${name}">([^<]*)</string>`));
  if (!match) throw new Error(`${directory}/strings.xml has no plain string ${name}`);
  return match[1].replace(/\\'/g, "'").replace(/\\"/g, '"');
}
