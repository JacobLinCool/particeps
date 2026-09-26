import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  PARTICIPANT_VPN_DISCLOSURE,
  participantStudyUiModel
} from '$lib/particeps/participant-projection';
import { validConfiguration } from './fixture';

describe('participant-safe study projection', () => {
  it('exposes only high-level study copy, data categories, and the VPN disclosure flag', () => {
    const configuration = validConfiguration();
    configuration.traffic_shaping = {
      target_packages: ['com.secret.treatment'],
      profiles: [{ id: 'secret-cap', uplink_kbps: 64, downlink_kbps: 512 }]
    };
    configuration.automations.push({
      type: 'resource_binding', id: 'secret-trigger',
      resource: { kind: 'actuator', id: 'traffic-shaping.v1' },
      cases: [{ condition: { type: 'elapsed_at_least', duration_seconds: 180, clock: 'ACTIVE_RUNNING_TIME' }, profile_id: 'secret-cap' }],
      default_profile_id: 'secret-cap'
    });

    const model = participantStudyUiModel(configuration);
    expect(Object.keys(model)).toEqual([
      'title', 'purpose', 'data_category_ids', 'shows_traffic_disclosure', 'upload_destination_host'
    ]);
    expect(model.shows_traffic_disclosure).toBe(true);
    expect(JSON.stringify(model)).not.toMatch(/secret|64|512|180|automation|profile|epoch|digest|owner/i);
  });

  it('shows only the upload host, as the App does', () => {
    const configuration = validConfiguration();
    expect(participantStudyUiModel(configuration).upload_destination_host).toBeNull();
    configuration.upload = {
      endpoint: 'https://upload.example.org/secret-path?arm=treatment',
      interval_minutes: 360,
      allow_metered: false
    };
    const model = participantStudyUiModel(configuration);
    expect(model.upload_destination_host).toBe('upload.example.org');
    expect(JSON.stringify(model)).not.toMatch(/secret|arm=|treatment/);
    configuration.upload.endpoint = 'not yet a url';
    expect(participantStudyUiModel(configuration).upload_destination_host).toBeNull();
  });

  it('keeps the fixed bilingual VPN disclosures identical to the App copy', () => {
    expect(PARTICIPANT_VPN_DISCLOSURE.en).toBe(appString('values', 'traffic_shaping_disclosure'));
    expect(PARTICIPANT_VPN_DISCLOSURE['zh-TW']).toBe(appString('values-zh-rTW', 'traffic_shaping_disclosure'));
  });
});

/** One plain `<string>` from the App's resources, with Android's escapes resolved. */
function appString(directory: string, name: string): string {
  const resources = readFileSync(
    new URL(`../../app/src/main/res/${directory}/strings.xml`, import.meta.url),
    'utf8'
  );
  const match = resources.match(new RegExp(`<string name="${name}">([^<]*)</string>`));
  if (!match) throw new Error(`${directory}/strings.xml has no plain string ${name}`);
  return match[1]
    .replace(/\\(['"])/g, '$1')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, '&');
}
