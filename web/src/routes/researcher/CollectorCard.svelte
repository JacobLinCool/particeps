<script lang="ts">
  /**
   * One collector, drawn with the app's own mark for it.
   *
   * The glyph comes from `CollectorSummary.summarize()`, so the researcher composing a study sees
   * the mark the participant will see beside it on the consent screen. That is the strongest
   * wordless tie between the two surfaces, and it costs nothing to keep.
   *
   * All seven cards are always present, in the codec's emission order, and none of them can be
   * reordered: array order is participant-visible — it is what the data screen lists — so leaving
   * it free would make two otherwise-identical studies non-diffable for no benefit.
   *
   * The whole header row is the enable target. A switch beside a name is two things to hit.
   */
  import Icon from '$lib/ui/Icon.svelte';
  import Note from '$lib/ui/Note.svelte';
  import RangeField from '$lib/ui/RangeField.svelte';
  import DualRangeField from '$lib/ui/DualRangeField.svelte';
  import ToggleField from '$lib/ui/ToggleField.svelte';
  import ChoiceField from '$lib/ui/ChoiceField.svelte';
  import ChipSet from '$lib/ui/ChipSet.svelte';
  import IntensityBar from './IntensityBar.svelte';
  import { PRESETS } from './presets';
  import { collectorRate, intensityOf } from './estimate';
  import { BOUNDS, type CollectorConfig, type CollectorId, type LocationPriority, type NetworkTransport } from '$lib/adc/types';
  import type { IconRef } from '$lib/ui/icons';
  import type { Messages } from '$lib/i18n/types';
  import type { Units } from './units';

  interface Props {
    id: CollectorId;
    config: CollectorConfig | null;
    path: string;
    m: Messages;
    units: Units;
    onenable: (id: CollectorId) => void;
    ondisable: (id: CollectorId) => void;
  }

  let { id, config, path, m, units, onenable, ondisable }: Props = $props();

  /** `CollectorGlyphs.kt`'s assignment, unchanged. */
  const GLYPHS: Record<CollectorId, IconRef> = {
    'app_lifecycle.v1': 'app',
    'accelerometer.v1': 'motion',
    'network_state.v1': 'connection',
    'network_usage.v1': 'data-volume',
    'usage_events.v1': 'screen',
    'location.v1': 'location',
    'keyboard_touch.v1': 'keyboard'
  };

  const copy = $derived(m.collector[id]);
  const on = $derived(config !== null);
  const level = $derived(config ? intensityOf(collectorRate(config).events) : 0);

  const TRANSPORTS: readonly { value: NetworkTransport; label: string; icon: IconRef }[] = $derived([
    { value: 'wifi', label: m.option.transport.wifi, icon: 'wifi' },
    { value: 'mobile', label: m.option.transport.mobile, icon: 'mobile' }
  ]);

  const PRIORITIES: readonly { value: LocationPriority; label: string; icon: IconRef }[] = $derived([
    { value: 'BALANCED', label: m.option.priority.balanced, icon: 'battery' },
    { value: 'HIGH_ACCURACY', label: m.option.priority.highAccuracy, icon: 'target' }
  ]);
</script>

<!-- No aria-disabled while off. The card dims to show the collector is not in the study, but the
     switch inside it is the only way to put it there: announcing the card as unavailable would tell
     a screen-reader user the control they need is not usable. `aria-checked` on the switch already
     carries off and on, and the parameters simply are not rendered until it is on. -->
<div class="collector" class:collector--on={on} data-testid={`collector-${id}`}>
  <button
    class="collector__head"
    type="button"
    role="switch"
    aria-checked={on}
    onclick={() => (on ? ondisable(id) : onenable(id))}
    data-testid={`collector-enable-${id}`}
  >
    <Icon name={GLYPHS[id]} size={22} class="collector__glyph" />
    <span class="collector__name">{copy.name}</span>
    <IntensityBar {level} />
  </button>

  <!-- What it records is what the choice is between, so it is readable before the choice is made.
       The limits and the parameters stay behind the switch: those are for a collector already on. -->
  {#if !config}
    <p class="collector__records">{copy.records}</p>
  {/if}

  {#if config}
    {@const collector = config}
    <div class="collector__meta">
      <ToggleField
        label={m.field.label.required}
        hint={m.field.hint.required}
        path={`${path}.required`}
        value={collector.required}
        onchange={(value) => (collector.required = value)}
      />
    </div>

    <div class="collector__body">
      <Note icon="info" tone="plain" text={copy.records} />
      <Note icon="alert" tone="plain" text={copy.limit} />

      {#if collector.id === 'accelerometer.v1'}
        {@const cfg = collector.config}
        <RangeField
          label={m.field.label.samplingPeriod}
          hint={m.field.hint.samplingPeriod}
          path={`${path}.config.sampling_period_us`}
          value={cfg.sampling_period_us}
          min={BOUNDS.samplingPeriodUs[0]}
          max={BOUNDS.samplingPeriodUs[1]}
          scale="log"
          invert
          icon="motion"
          format={(value) => units.hertz(Math.max(1, Math.trunc(1_000_000 / Math.max(1, value))))}
          presets={PRESETS.sampling_period_us}
          onchange={(value) => (cfg.sampling_period_us = value)}
        />
        <RangeField
          label={m.field.label.reportLatency}
          path={`${path}.config.maximum_report_latency_us`}
          value={cfg.maximum_report_latency_us}
          min={BOUNDS.maximumReportLatencyUs[0]}
          max={BOUNDS.maximumReportLatencyUs[1]}
          scale="log"
          format={units.micros}
          presets={PRESETS.maximum_report_latency_us}
          onchange={(value) => (cfg.maximum_report_latency_us = value)}
        />
      {:else if collector.id === 'network_state.v1'}
        {@const cfg = collector.config}
        <ToggleField
          label={m.field.label.bandwidthEstimates}
          hint={m.field.hint.bandwidthEstimates}
          path={`${path}.config.include_bandwidth_estimates`}
          value={cfg.include_bandwidth_estimates}
          onchange={(value) => (cfg.include_bandwidth_estimates = value)}
        />
      {:else if collector.id === 'network_usage.v1'}
        {@const cfg = collector.config}
        <ChipSet
          label={m.field.label.transports}
          path={`${path}.config.transports`}
          value={cfg.transports}
          options={TRANSPORTS}
          min={1}
          onchange={(value) => (cfg.transports = value)}
        />
        <RangeField
          label={m.field.label.pollInterval}
          hint={m.field.hint.pollInterval}
          path={`${path}.config.poll_interval_minutes`}
          value={cfg.poll_interval_minutes}
          min={BOUNDS.pollIntervalMinutes[0]}
          max={BOUNDS.pollIntervalMinutes[1]}
          scale="log"
          icon="clock"
          caution={cfg.poll_interval_minutes === 1}
          format={units.minutes}
          presets={PRESETS.poll_interval_minutes}
          onchange={(value) => (cfg.poll_interval_minutes = value)}
        />
      {:else if collector.id === 'usage_events.v1'}
        {@const cfg = collector.config}
        <RangeField
          label={m.field.label.pollInterval}
          hint={m.field.hint.pollInterval}
          path={`${path}.config.poll_interval_minutes`}
          value={cfg.poll_interval_minutes}
          min={BOUNDS.pollIntervalMinutes[0]}
          max={BOUNDS.pollIntervalMinutes[1]}
          scale="log"
          icon="clock"
          caution={cfg.poll_interval_minutes === 1}
          format={units.minutes}
          presets={PRESETS.poll_interval_minutes}
          onchange={(value) => (cfg.poll_interval_minutes = value)}
        />
      {:else if collector.id === 'location.v1'}
        {@const cfg = collector.config}
        <!-- One track, two thumbs: `minimum_interval_millis in 500..interval_millis` becomes
             geometry instead of a message that arrives after the fact. -->
        <DualRangeField
          label={m.field.label.interval}
          hint={m.field.hint.fastestInterval}
          lowLabel={m.field.label.fastestInterval}
          highLabel={m.field.label.interval}
          lowPath={`${path}.config.minimum_interval_millis`}
          highPath={`${path}.config.interval_millis`}
          low={cfg.minimum_interval_millis}
          high={cfg.interval_millis}
          min={BOUNDS.minimumIntervalMillis[0]}
          max={BOUNDS.intervalMillis[1]}
          highMin={BOUNDS.intervalMillis[0]}
          scale="log"
          format={units.millis}
          presets={PRESETS.interval_millis}
          onchange={(low, high) => {
            cfg.minimum_interval_millis = low;
            cfg.interval_millis = high;
          }}
        />
        <RangeField
          label={m.field.label.batchDelay}
          hint={m.field.hint.batchDelay}
          path={`${path}.config.maximum_batch_delay_millis`}
          value={cfg.maximum_batch_delay_millis}
          min={BOUNDS.maximumBatchDelayMillis[0]}
          max={BOUNDS.maximumBatchDelayMillis[1]}
          scale="log"
          icon="battery"
          format={units.millis}
          presets={PRESETS.maximum_batch_delay_millis}
          onchange={(value) => (cfg.maximum_batch_delay_millis = value)}
        />
        <!-- The readout is `formatFloat`, not the typed value: this field is a Kotlin Float, and
             `1234.5678` is written as `1234.5677`. Nobody should meet that at diff time. -->
        <RangeField
          label={m.field.label.displacement}
          path={`${path}.config.minimum_displacement_meters`}
          value={cfg.minimum_displacement_meters}
          min={BOUNDS.minimumDisplacementMeters[0]}
          max={BOUNDS.minimumDisplacementMeters[1]}
          step={0.1}
          scale="log"
          icon="location"
          format={units.metres}
          presets={PRESETS.minimum_displacement_meters}
          onchange={(value) => (cfg.minimum_displacement_meters = value)}
        />
        <ChoiceField
          label={m.field.label.priority}
          hint={m.field.hint.priority}
          path={`${path}.config.priority`}
          value={cfg.priority}
          options={PRIORITIES}
          onchange={(value) => (cfg.priority = value)}
        />
      {:else if collector.id === 'keyboard_touch.v1'}
        {@const cfg = collector.config}
        <RangeField
          label={m.field.label.trajectoryRate}
          path={`${path}.config.trajectory_sampling_hz`}
          value={cfg.trajectory_sampling_hz}
          min={BOUNDS.trajectorySamplingHz[0]}
          max={BOUNDS.trajectorySamplingHz[1]}
          icon="keyboard"
          format={units.hertz}
          presets={PRESETS.trajectory_sampling_hz}
          onchange={(value) => (cfg.trajectory_sampling_hz = value)}
        />
      {/if}
    </div>
  {/if}
</div>
