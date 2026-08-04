/**
 * The page as data.
 *
 * Every section below section 1 is a loop over one of these tables. Keeping the glyph, the message
 * key, and the effect of a control in one row is what makes it possible to check the page against
 * the app by reading a table rather than by reading twelve components — and what makes an omission
 * visible as a missing row.
 */

import type { CollectorId } from '$lib/adc/types';
import type { IconRef } from '$lib/ui/icons';
import type { MessageKey } from './copy';

/** The eleven the app draws, plus the marks this page needs that the app has no screen for. */
export type GlyphName = IconRef;

export interface SourceEntry {
  id: CollectorId;
  glyph: GlyphName;
}

export type Effect = 'continues' | 'stops' | 'drains-then-stops' | 'already-stopped' | 'none';

export interface ControlEntry {
  id: 'export' | 'pause' | 'finish' | 'withdraw' | 'delete';
  glyph: GlyphName;
  collection: Effect;
  sending: Effect;
}

export interface FingerprintCandidate {
  id: string;
  fingerprint: string;
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

/**
 * Export first, because it is the one that is always available; then the three that stop
 * collection, in the order the app's dashboard offers them; then the one that is irreversible.
 * The two effect columns are transcribed from the runtime's own boundary behaviour: a study that
 * uploads keeps delivering what it already holds after the admission gate closes, which is why
 * three of these rows have different values in the two columns.
 */
export const CONTROLS: readonly ControlEntry[] = [
  { id: 'export', glyph: 'export', collection: 'none', sending: 'none' },
  { id: 'pause', glyph: 'pause', collection: 'stops', sending: 'continues' },
  { id: 'finish', glyph: 'finish', collection: 'stops', sending: 'drains-then-stops' },
  { id: 'withdraw', glyph: 'exit', collection: 'stops', sending: 'drains-then-stops' },
  { id: 'delete', glyph: 'trash', collection: 'already-stopped', sending: 'stops' }
];

export const EFFECT_KEY: Record<Effect, MessageKey> = {
  continues: 'controls.effect.continues',
  stops: 'controls.effect.stops',
  'drains-then-stops': 'controls.effect.drainsThenStops',
  'already-stopped': 'controls.effect.alreadyStopped',
  none: 'controls.effect.none'
};

export const GLANCE: readonly { href: string; glyph: GlyphName; labelKey: MessageKey }[] = [
  { href: '#collect', glyph: 'document', labelKey: 'glance.collect' },
  { href: '#where', glyph: 'lock', labelKey: 'glance.where' },
  { href: '#fingerprint', glyph: 'fingerprint', labelKey: 'glance.fingerprint' },
  { href: '#stop', glyph: 'exit', labelKey: 'glance.stop' }
];

export const FLAGS: readonly MessageKey[] = [
  'flags.fingerprint',
  'flags.contents',
  'flags.scope',
  'flags.origin',
  'flags.demo',
  'flags.password'
];

/**
 * A sample, and drawn as one. No fingerprint on this page is authoritative: a participant who
 * learns to get provenance from a web page has learned the habit the check exists to defeat.
 *
 * The impostor differs by a transposition inside group five — `E640` against `E604`. That is
 * precisely the difference an eye skimming the first and last groups misses, so the widget can
 * only be passed by comparing group by group.
 */
export const PUBLISHED_FINGERPRINT = '4B2E 9C07 D1A3 55F8 E640 7B12 8AAC 3D91';

export const CANDIDATES: readonly FingerprintCandidate[] = [
  { id: 'genuine', fingerprint: '4B2E 9C07 D1A3 55F8 E640 7B12 8AAC 3D91' },
  { id: 'impostor', fingerprint: '4B2E 9C07 D1A3 55F8 E604 7B12 8AAC 3D91' }
];

export const SETUP_STEPS: readonly { nameKey: MessageKey; captionKey: MessageKey }[] = [
  { nameKey: 'setup.step.study', captionKey: 'setup.caption.study' },
  { nameKey: 'setup.step.data', captionKey: 'setup.caption.data' },
  { nameKey: 'setup.step.consent', captionKey: 'setup.caption.consent' },
  { nameKey: 'setup.step.access', captionKey: 'setup.caption.access' },
  { nameKey: 'setup.step.start', captionKey: 'setup.caption.start' }
];

export const REPOSITORY = 'https://github.com/JacobLinCool/android-data-collector';

export const PARTICIPANT_GUIDE = `${REPOSITORY}/blob/main/docs/participant-guide.md`;
