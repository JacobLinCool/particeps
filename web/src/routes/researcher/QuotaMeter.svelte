<script lang="ts">
  /**
   * The quota, beside the thing that fills it.
   *
   * `storage.maximum_local_bytes` is a study-level field, and it is drawn here rather than with the
   * rest of the prose because the decision is only meaningful next to the collectors that fill it:
   * a quota control beside an estimated fill rate is a decision, the same control on a page of
   * prose is a number.
   *
   * Over quota is `--caution`, never an issue. The app pauses collection at the limit rather than
   * dropping events, which is a real behaviour a researcher may knowingly accept.
   */
  import RangeField from '$lib/ui/RangeField.svelte';
  import Icon from '$lib/ui/Icon.svelte';
  import { fraction } from '$lib/ui/format';
  import { MAXIMUM_LOCAL_BYTES, MINIMUM_LOCAL_BYTES } from '$lib/adc/types';
  import { PRESETS } from './presets';
  import type { Units } from './units';

  interface Props {
    quotaBytes: number;
    bytesPerHour: number;
    durationHours: number;
    label: string;
    hint: string;
    units: Units;
    onquota: (value: number) => void;
  }

  let { quotaBytes, bytesPerHour, durationHours, label, hint, units, onquota }: Props = $props();

  const projected = $derived(bytesPerHour * durationHours);
  const over = $derived(projected > quotaBytes);
  const hoursToQuota = $derived(bytesPerHour > 0 ? quotaBytes / bytesPerHour : Infinity);
</script>

<div class="quota" data-testid="quota-meter">
  <RangeField
    {label}
    {hint}
    path="storage.maximum_local_bytes"
    value={quotaBytes}
    min={MINIMUM_LOCAL_BYTES}
    max={MAXIMUM_LOCAL_BYTES}
    scale="log"
    icon="storage"
    format={units.bytes}
    numericInput={false}
    presets={PRESETS.maximum_local_bytes}
    onchange={onquota}
  />

  <div class="quota__bar" aria-hidden="true">
    <div
      class="quota__fill"
      data-level={over ? 'over' : undefined}
      style="transform: scaleX({fraction(projected, quotaBytes)})"
    ></div>
  </div>

  <p class="quota__read">
    <Icon name={over ? 'alert' : 'storage'} size={14} tone={over ? 'caution' : 'faint'} />
    <span class="mono">{units.about(units.bytes(projected))}</span>
    {#if Number.isFinite(hoursToQuota)}
      <span class="mono">
        <Icon name="clock" size={14} />
        {units.hours(Math.max(1, Math.round(hoursToQuota)))}
      </span>
    {/if}
  </p>
</div>

<style>
  /* Ten digits. This is the one control whose value gets there — 1 073 741 824 is the default and
     8 589 934 592 the ceiling — and at the shared width the last digit was cut off the end of the
     box, which reads as a different number rather than as a clipped one. */
  .quota :global(.input--num) {
    inline-size: 8.5rem;
  }

  .quota__read > span {
    display: inline-flex;
    align-items: center;
    gap: var(--sp-3);
  }
</style>
