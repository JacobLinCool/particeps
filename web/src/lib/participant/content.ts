/**
 * The page as data.
 *
 * The repeated parts of the participant page are defined by these tables. Keeping each glyph and
 * message key beside the item it describes makes it possible to check the page against the app by
 * reading data rather than tracing repeated component markup.
 */

import type { CollectorId } from '$lib/particeps/types';
import type { IconRef } from '$lib/ui/icons';
import type { MessageKey } from './copy';

/** The eleven the app draws, plus the marks this page needs that the app has no screen for. */
export type GlyphName = IconRef;

export interface SourceEntry {
  id: CollectorId;
  glyph: GlyphName;
}

/** The order the app's data step lists them in, which is the order of the schema's collector IDs. */
export const SOURCES: readonly SourceEntry[] = [
  { id: 'app_lifecycle.v1', glyph: 'app' },
  { id: 'accelerometer.v1', glyph: 'motion' },
  { id: 'battery_state.v1', glyph: 'dataVolume' },
  { id: 'temporal_context.v1', glyph: 'clock' },
  { id: 'gyroscope.v1', glyph: 'motion' },
  { id: 'ambient_light.v1', glyph: 'app' },
  { id: 'proximity.v1', glyph: 'connection' },
  { id: 'network_state.v1', glyph: 'connection' },
  { id: 'network_usage.v1', glyph: 'dataVolume' },
  { id: 'usage_events.v1', glyph: 'screen' },
  { id: 'location.v1', glyph: 'location' },
  { id: 'keyboard_touch.v1', glyph: 'keyboard' }
];

/** A collector ID carries a dot and a version; a message key cannot. */
const SOURCE_SLUG: Record<CollectorId, string> = {
  'app_lifecycle.v1': 'appLifecycle',
  'accelerometer.v1': 'accelerometer',
  'battery_state.v1': 'batteryState',
  'temporal_context.v1': 'temporalContext',
  'gyroscope.v1': 'gyroscope',
  'ambient_light.v1': 'ambientLight',
  'proximity.v1': 'proximity',
  'network_state.v1': 'networkState',
  'network_usage.v1': 'networkUsage',
  'usage_events.v1': 'usageEvents',
  'location.v1': 'location',
  'keyboard_touch.v1': 'keyboardTouch'
};

export function sourceName(id: CollectorId): MessageKey {
  return `sources.name.${SOURCE_SLUG[id]}` as MessageKey;
}

export function sourceDetail(id: CollectorId): MessageKey {
  return `sources.detail.${SOURCE_SLUG[id]}` as MessageKey;
}

export const GLANCE: readonly { href: string; glyph: GlyphName; labelKey: MessageKey }[] = [
  { href: '#collect', glyph: 'document', labelKey: 'glance.collect' },
  { href: '#where', glyph: 'lock', labelKey: 'glance.where' }
];

export const FLAGS: readonly MessageKey[] = [
  'flags.fingerprint',
  'flags.contents',
  'flags.scope',
  'flags.origin',
  'flags.demo',
  'flags.password'
];

export const SETUP_STEPS: readonly { nameKey: MessageKey; captionKey: MessageKey }[] = [
  { nameKey: 'setup.step.study', captionKey: 'setup.caption.study' },
  { nameKey: 'setup.step.data', captionKey: 'setup.caption.data' },
  { nameKey: 'setup.step.consent', captionKey: 'setup.caption.consent' },
  { nameKey: 'setup.step.access', captionKey: 'setup.caption.access' },
  { nameKey: 'setup.step.start', captionKey: 'setup.caption.start' }
];

export const REPOSITORY = 'https://github.com/JacobLinCool/particeps';

export const ANDROID_RELEASE_VERSION = 'v1.0.0-rc.8';

export const ANDROID_APK_URL = `${REPOSITORY}/releases/download/${ANDROID_RELEASE_VERSION}/particeps-${ANDROID_RELEASE_VERSION}.apk`;

export const PARTICIPANT_GUIDE = `${REPOSITORY}/blob/main/docs/participant-guide.md`;
