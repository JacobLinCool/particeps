/**
 * How many events an hour a study records, and how many bytes that is.
 *
 * Both numbers are arithmetic over values in the document being written — sampling periods, poll
 * intervals, a location interval, a trajectory rate, a count of transports — and nothing here says
 * anything about power, battery, or hardware, none of which this site has measured. What it does
 * say is volume: a rate per collector, and a fill line against the quota, which is the one cost the
 * app actually enforces (collection stops at the limit rather than dropping events).
 *
 * Everything is advisory. Nothing here ever produces an `Issue`, because none of it is a rule: a
 * study may knowingly exceed its quota and fail closed.
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
  'battery_state.v1': 140,
  'temporal_context.v1': 180,
  'gyroscope.v1': 120,
  'ambient_light.v1': 120,
  'proximity.v1': 140,
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
    case 'battery_state.v1':
      return { events: 4, bytes };
    case 'temporal_context.v1':
      return { events: 1, bytes };
    case 'gyroscope.v1':
      return { events: 3.6e9 / Math.max(1, collector.config.sampling_period_us), bytes };
    case 'ambient_light.v1':
      return { events: 3.6e9 / Math.max(1, collector.config.sampling_period_us), bytes };
    case 'proximity.v1':
      return { events: 3.6e6 / Math.max(1, collector.config.minimum_event_interval_ms), bytes };
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

export type Volume = 0 | 1 | 2 | 3 | 4;

/**
 * Which decade of events per hour this collector is in. Four steps rather than a continuous
 * position, because the constants above are order-of-magnitude and a smooth bar would claim a
 * precision they do not have. Twelve cards side by side then say which one writes the most.
 */
export function volumeOf(eventsPerHour: number): Volume {
  if (!(eventsPerHour > 0)) return 0;
  if (eventsPerHour < 100) return 1;
  if (eventsPerHour < 1_000) return 2;
  if (eventsPerHour < 10_000) return 3;
  return 4;
}
