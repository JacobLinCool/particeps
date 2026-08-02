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
   * The header row carries two decisions: whether the collector is in the study, and whether a
   * participant may decline it. They are siblings rather than one inside the other — the enable
   * target is a `<button>` and interactive content inside a button is invalid HTML, which is the
   * hard answer to "a switch inside a switch". Everything else on the card is a parameter.
   */
  import Icon from '$lib/ui/Icon.svelte';
  import RangeField from '$lib/ui/RangeField.svelte';
  import DualRangeField from '$lib/ui/DualRangeField.svelte';
  import ToggleField from '$lib/ui/ToggleField.svelte';
  import ChoiceField from '$lib/ui/ChoiceField.svelte';
  import ChipSet from '$lib/ui/ChipSet.svelte';
  import RateBar from './RateBar.svelte';
  import { collectorRate, volumeOf } from './estimate';
  import { BOUNDS, type CollectorConfig, type CollectorId, type LocationPriority, type NetworkTransport } from '$lib/adc/types';
  import type { IconRef } from '$lib/ui/icons';
  import type { Messages } from '$lib/i18n/types';
  import type { Scale, ScaleKey } from './scales';

  interface Props {
    id: CollectorId;
    config: CollectorConfig | null;
    path: string;
    m: Messages;
    /** Unit, bounds and chips per field. Built once on the page and handed down. */
    scales: Record<ScaleKey, Scale>;
    onenable: (id: CollectorId) => void;
    ondisable: (id: CollectorId) => void;
  }

  let { id, config, path, m, scales: S, onenable, ondisable }: Props = $props();

  const uid = $props.id();

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
  const level = $derived(config ? volumeOf(collectorRate(config).events) : 0);

  const TRANSPORTS: readonly { value: NetworkTransport; label: string; icon: IconRef }[] = $derived([
    { value: 'wifi', label: m.option.transport.wifi, icon: 'wifi' },
    { value: 'mobile', label: m.option.transport.mobile, icon: 'mobile' }
  ]);

  /**
   * A plain pin against a target: less precise beside more precise, which is the only property
   * `LocationPriority` names. `BALANCED` carried a battery, and a battery beside a word is a power
   * claim however the word reads — the enum name itself stays, because the app's own summary shows
   * the participant the same one.
   */
  const PRIORITIES: readonly { value: LocationPriority; label: string; icon: IconRef }[] = $derived([
    { value: 'BALANCED', label: m.option.priority.balanced, icon: 'location' },
    { value: 'HIGH_ACCURACY', label: m.option.priority.highAccuracy, icon: 'target' }
  ]);
</script>

<!-- No aria-disabled while off. The card dims to show the collector is not in the study, but the
     switch inside it is the only way to put it there: announcing the card as unavailable would tell
     a screen-reader user the control they need is not usable. `aria-checked` on the switch already
     carries off and on, and the parameters simply are not rendered until it is on. -->
<div class="collector" class:collector--on={on} data-testid={`collector-${id}`}>
  <!-- Two controls, one row, and they cannot be nested: the enable target is a <button>, and
       interactive content inside a button is invalid. The group is named by the collector, so both
       controls announce under the name they are about. -->
  <div class="collector__head" role="group" aria-labelledby={`${uid}-name`}>
    <button
      class="collector__enable"
      type="button"
      role="switch"
      aria-checked={on}
      onclick={() => (on ? ondisable(id) : onenable(id))}
      data-testid={`collector-enable-${id}`}
    >
      <Icon name={GLYPHS[id]} size={22} class="collector__glyph" />
      <span class="collector__name" id={`${uid}-name`}>{copy.name}</span>
      <RateBar {level} />
    </button>

    {#if config}
      {@const collector = config}
      <!-- Not a second switch, and four things say so: `aria-pressed` rather than `aria-checked`
           (the grammar `.chip` and `.range__preset` already use here), a tick only when it is set,
           `--caution` where the card wears `--accent`, and its own hover surface beside the row's.
           It only exists on a card that is already on, which is where a wrong guess costs least.
           The sentence it used to carry is said once above the grid; `aria-describedby` and the
           pointer title keep it one hop from the control. -->
      <button
        class="collector__required"
        type="button"
        aria-pressed={collector.required}
        aria-describedby="collectors-required"
        title={m.field.hint.required}
        onclick={() => (collector.required = !collector.required)}
        data-testid={`required-${path}.required`}
      >
        <Icon name={collector.required ? 'check' : 'participant'} size={14} />
        <span>{m.field.label.required}</span>
      </button>
    {/if}
  </div>

  <!-- What it records is what the choice is between, so it is readable before the choice is made.
       The limits and the parameters stay behind the switch: those are for a collector already on. -->
  {#if !config}
    <p class="collector__records">{copy.records}</p>
  {/if}

  {#if config}
    {@const collector = config}
    <div class="collector__body">
      <!-- Two disclosures, one block. Both sentences stay and both marks stay; what goes is the
           second paragraph's own padding and the body gap that stood between them. -->
      <div class="collector__disclosure">
        <Icon name="info" size={16} tone="faint" />
        <span>{copy.records}</span>
        <Icon name="alert" size={16} tone="faint" />
        <span>{copy.limit}</span>
      </div>

      {#if collector.id === 'accelerometer.v1'}
        {@const cfg = collector.config}
        <!-- Hertz in the control, microseconds in the file. The box says `50` and `Hz`; the period
             it stores is `scales.ts`'s business and appears nowhere on screen. -->
        <RangeField
          label={m.field.label.samplingPeriod}
          hint={m.field.hint.samplingPeriod}
          path={`${path}.config.sampling_period_us`}
          value={cfg.sampling_period_us}
          unit={S.sampling_period_us}
          icon="motion"
          onchange={(value) => (cfg.sampling_period_us = value)}
        />
        <RangeField
          label={m.field.label.reportLatency}
          path={`${path}.config.maximum_report_latency_us`}
          value={cfg.maximum_report_latency_us}
          unit={S.maximum_report_latency_us}
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
          unit={S.poll_interval_minutes}
          icon="clock"
          caution={cfg.poll_interval_minutes === 1}
          onchange={(value) => (cfg.poll_interval_minutes = value)}
        />
      {:else if collector.id === 'usage_events.v1'}
        {@const cfg = collector.config}
        <RangeField
          label={m.field.label.pollInterval}
          hint={m.field.hint.pollInterval}
          path={`${path}.config.poll_interval_minutes`}
          value={cfg.poll_interval_minutes}
          unit={S.poll_interval_minutes}
          icon="clock"
          caution={cfg.poll_interval_minutes === 1}
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
          unit={S.interval_millis}
          highMin={BOUNDS.intervalMillis[0]}
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
          unit={S.maximum_batch_delay_millis}
          icon="package"
          onchange={(value) => (cfg.maximum_batch_delay_millis = value)}
        />
        <!-- The box shows `formatFloat`, not the typed value: this field is a Kotlin Float, and
             `1234.5678` is written as `1234.5677`. Nobody should meet that at diff time. -->
        <RangeField
          label={m.field.label.displacement}
          path={`${path}.config.minimum_displacement_meters`}
          value={cfg.minimum_displacement_meters}
          unit={S.minimum_displacement_meters}
          icon="location"
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
          unit={S.trajectory_sampling_hz}
          icon="keyboard"
          onchange={(value) => (cfg.trajectory_sampling_hz = value)}
        />
      {/if}
    </div>
  {/if}
</div>
