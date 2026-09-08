import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { SOURCES } from '$lib/participant/content';
import { canonicalize } from '$lib/particeps/canonical';
import { eventContract } from '$lib/particeps/generated/event-source-registry';
import { COLLECTOR_SOURCES, RESEARCHER_EVENTS } from '$lib/particeps/registry';
import { isCollectorId } from '$lib/particeps/types';
import { parseConfiguration, UnavailableCollectorError } from '../src/routes/researcher/parse';

const encode = (value: unknown) => new TextEncoder().encode(canonicalize(value));
const example = (name: string) => JSON.parse(readFileSync(
  new URL(`../../researcher-tools/examples/${name}.json`, import.meta.url), 'utf8'
));

describe('cross-app notification collection is unavailable', () => {
  it('retains the published event contract without offering collection or automation triggers', () => {
    expect(eventContract('notification_events.v1', 1, 'NOTIFICATION_POSTED')).toBeDefined();
    expect(isCollectorId('notification_events.v1')).toBe(false);
    expect(COLLECTOR_SOURCES.map((source) => source.source_id)).not.toContain('notification_events.v1');
    expect(RESEARCHER_EVENTS.map(({ source }) => source.source_id)).not.toContain('notification_events.v1');
    expect(SOURCES.map((source) => source.id)).not.toContain('notification_events.v1');
  });

  it('rejects an older configuration explicitly instead of dropping its required source', () => {
    const raw = example('five-day-speed-study');
    raw.collectors.push({
      id: 'notification_events.v1', required: true,
      profiles: [{ id: 'continuous', config: {} }]
    });
    expect(() => parseConfiguration(encode(raw))).toThrow(UnavailableCollectorError);
    expect(() => parseConfiguration(encode(raw))).toThrow('notification_events.v1 is unavailable');
  });

  it.each(['five-day-speed-study', 'five-day-windowed-gyro-study'])(
    'keeps seven valid collectors and the activity-survey notification in %s', (name) => {
      const raw = example(name);
      const configuration = parseConfiguration(encode(raw));
      expect(configuration.collectors).toHaveLength(7);
      expect(configuration.collectors.every((collector) => collector.required)).toBe(true);
      expect(configuration.interventions.some((intervention) =>
        intervention.action.type === 'survey' && intervention.action.notification_title.length > 0
      )).toBe(true);
      expect(raw.consent.summary).not.toContain('通知來源');
      expect(raw.automations.some((automation: { resource?: { id: string } }) =>
        automation.resource?.id === 'notification_events.v1'
      )).toBe(false);
    }
  );
});
