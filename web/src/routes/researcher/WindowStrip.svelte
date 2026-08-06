<script lang="ts">
  /**
   * Two bars at two heights, because they are two different quantities and are constantly
   * conflated: the top one is when the *file* is valid, the short one beneath is how long a
   * *participant* runs, counted from their own start. Drawing them at the same height would say
   * they were the same clock.
   *
   * `issued_at >= expires_at` collapses the span and turns it `--danger`, which is the same thing
   * the field-level issue says, one beat earlier and without words.
   */
  import Icon from '$lib/ui/Icon.svelte';
  import { parseInstant } from '$lib/particeps/canonical';
  import type { Units } from './units';

  interface Props {
    issuedAt: string;
    expiresAt: string;
    durationHours: number;
    now: number;
    units: Units;
  }

  let { issuedAt, expiresAt, durationHours, now, units }: Props = $props();

  const issued = $derived(parseInstant(issuedAt)?.second ?? null);
  const expires = $derived(parseInstant(expiresAt)?.second ?? null);
  const invalid = $derived(issued === null || expires === null || issued >= expires);

  /** The axis is the window plus a margin, widened to keep `now` on screen when it falls outside. */
  const axis = $derived.by(() => {
    if (issued === null || expires === null) return null;
    const start = Math.min(issued, expires, now);
    const end = Math.max(issued, expires, now);
    const pad = Math.max(3_600, (end - start) * 0.08);
    return { start: start - pad, span: end - start + 2 * pad };
  });

  const at = (second: number) => (axis ? ((second - axis.start) / axis.span) * 100 : 0);

  const windowHours = $derived(
    issued !== null && expires !== null && expires > issued
      ? Math.round((expires - issued) / 3_600)
      : 0
  );

  /** The participant's clock against the file's: capped, because it may legitimately be longer. */
  const durationFill = $derived(
    windowHours > 0 ? Math.min(100, (durationHours / windowHours) * 100) : 100
  );
</script>

<div class="window" class:window--invalid={invalid} aria-hidden="true">
  <div class="window__bar">
    {#if axis && issued !== null && expires !== null}
      <div
        class="window__span"
        style="inset-inline-start: {at(Math.min(issued, expires))}%; inline-size: {Math.max(
          0.5,
          at(Math.max(issued, expires)) - at(Math.min(issued, expires))
        )}%"
      ></div>
      <div class="window__now" style="inset-inline-start: {at(now)}%"></div>
    {/if}
  </div>

  <div class="window__duration">
    <div class="window__duration-fill" style="inline-size: {durationFill}%"></div>
  </div>

  <!-- One entry, not two. The participant's stretch is printed by the duration readout and by its
       pressed chip within a couple of hundred pixels of here; a third copy of the same number said
       nothing. The window is derived from two instants and appears nowhere else, so it stays. -->
  <div class="window__legend">
    <span><Icon name="clock" size={14} />{units.hours(windowHours)}</span>
  </div>
</div>

<style>
  .window__legend > span {
    display: inline-flex;
    align-items: center;
    gap: var(--sp-3);
  }
</style>
