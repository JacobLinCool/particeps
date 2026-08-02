/**
 * What a study costs on someone else's phone, to one significant figure.
 *
 * The researcher guide is emphatic about minimisation — fewest collectors, lowest usable rate,
 * smallest quota — and a form cannot say anything about cost. Two numbers can: an intensity bar per
 * collector, and a fill line against the quota. Both are advisory. Nothing here ever produces an
 * `Issue`, because none of it is a rule; a study may knowingly exceed its quota and fail closed,
 * which is what the app does at the limit.
 *
 * The constants are order-of-magnitude and live in one place so they can be argued with as a set.
 */

import type { CollectorConfig, StudyConfiguration } from '$lib/adc/types';

export interface Rate {
  events: number;
  bytes: number;
}

export interface Estimate {
  eventsPerHour: number;
  bytesPerHour: number;
  /** `Infinity` when nothing is enabled: a study that writes nothing never fills anything. */
  hoursToQuota: number;
}

/** Bytes per event, after the envelope every record carries. */
const EVENT_BYTES = {
  'app_lifecycle.v1': 180,
  'accelerometer.v1': 120,
  'network_state.v1': 180,
  'network_usage.v1': 180,
  'usage_events.v1': 180,
  'location.v1': 220,
  'keyboard_touch.v1': 140
} as const;

/** A day of foreground changes, screen unlocks, and boots, per poll. */
const USAGE_EVENTS_PER_POLL = 40;

export function collectorRate(collector: CollectorConfig): Rate {
  const bytes = EVENT_BYTES[collector.id];
  switch (collector.id) {
    case 'app_lifecycle.v1':
      return { events: 20, bytes };
    case 'accelerometer.v1':
      return { events: 3.6e9 / Math.max(1, collector.config.sampling_period_us), bytes };
    case 'network_state.v1':
      return { events: 30, bytes };
    case 'network_usage.v1':
      return {
        events:
          (60 / Math.max(1, collector.config.poll_interval_minutes)) *
          collector.config.transports.length,
        bytes
      };
    case 'usage_events.v1':
      return {
        events: (60 / Math.max(1, collector.config.poll_interval_minutes)) * USAGE_EVENTS_PER_POLL,
        bytes
      };
    case 'location.v1':
      return { events: 3.6e6 / Math.max(1, collector.config.interval_millis), bytes };
    case 'keyboard_touch.v1':
      return { events: collector.config.trajectory_sampling_hz * 60, bytes };
  }
}

export function estimate(configuration: StudyConfiguration): Estimate {
  let eventsPerHour = 0;
  let bytesPerHour = 0;
  for (const collector of configuration.collectors) {
    const rate = collectorRate(collector);
    eventsPerHour += rate.events;
    bytesPerHour += rate.events * rate.bytes;
  }
  return {
    eventsPerHour,
    bytesPerHour,
    hoursToQuota:
      bytesPerHour > 0 ? configuration.storage.maximum_local_bytes / bytesPerHour : Infinity
  };
}

export type Intensity = 0 | 1 | 2 | 3 | 4;

/** Four decades, so seven cards side by side say at a glance which one is the expensive one. */
export function intensityOf(eventsPerHour: number): Intensity {
  if (!(eventsPerHour > 0)) return 0;
  if (eventsPerHour < 100) return 1;
  if (eventsPerHour < 1_000) return 2;
  if (eventsPerHour < 10_000) return 3;
  return 4;
}
