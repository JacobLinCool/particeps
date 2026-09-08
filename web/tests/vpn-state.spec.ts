import { describe, expect, it } from 'vitest';
import { sourceDetail, sourceName, SOURCES } from '$lib/participant/content';
import { en, zhTW } from '$lib/participant/copy';
import { eventContract } from '$lib/particeps/generated/event-source-registry';
import { participantStudyUiModel } from '$lib/particeps/participant-projection';
import { continuousBinding, defaultCollector, validate } from '$lib/particeps/schema';
import { collectorRate } from '../src/routes/researcher/estimate';
import { validConfiguration } from './fixture';

describe('independent VPN state collection', () => {
  it('authors an empty profile and discloses observation without enabling VPN traffic shaping', () => {
    const configuration = validConfiguration();
    const collector = defaultCollector('vpn_state.v1');
    configuration.collectors.push(collector);
    configuration.collectors.sort((left, right) => left.id.localeCompare(right.id));
    configuration.automations.push(continuousBinding(collector));
    expect(collector.profiles).toEqual([{ id: 'continuous', config: {} }]);
    expect(validate(configuration)).toEqual([]);
    const model = participantStudyUiModel(configuration);
    expect(model.data_category_ids).toContain('vpn_state.v1');
    expect(model.shows_traffic_disclosure).toBe(false);
    expect(collectorRate(collector).events).toBeGreaterThan(0);
    expect(collectorRate(collector).bytes).toBeGreaterThan(0);
  });

  it('assigns VPN_STATUS only to the new source contract', () => {
    expect(eventContract('vpn_state.v1', 1, 'VPN_STATUS')).toBeDefined();
    expect(eventContract('network_state.v1', 1, 'VPN_STATUS')).toBeUndefined();
  });

  it('provides separate participant category copy in both languages', () => {
    expect(SOURCES.some((source) => source.id === 'vpn_state.v1')).toBe(true);
    expect(sourceName('vpn_state.v1')).toBe('sources.name.vpnState');
    expect(sourceDetail('vpn_state.v1')).toBe('sources.detail.vpnState');
    expect(en.sources.name.vpnState).toBe('VPN connection state');
    expect(zhTW.sources.name.vpnState).toBe('VPN 連線狀態');
    expect(en.sources.detail.vpnState).toContain('no provider or traffic content');
    expect(zhTW.sources.detail.vpnState).toContain('不含提供者或流量內容');
  });
});
