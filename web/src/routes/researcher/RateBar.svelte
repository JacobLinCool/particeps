<script lang="ts">
  /**
   * How much this collector writes, as a length in one colour.
   *
   * Four lit segments is four decades of events per hour, computed from the parameters on the card
   * itself. Across seven cards it is the only cross-collector comparison on the page.
   *
   * It used to warm from green through amber to red, which is the battery-meter idiom exactly: a
   * ramp encodes danger, and there is no measurement behind danger here. Length encodes magnitude,
   * and magnitude is the whole of what the number is — so the ramp is gone and one ink is left.
   *
   * `aria-hidden`: it summarises parameters that are reachable a few controls below, and an
   * estimate announced as a value reads as a measurement.
   */
  import type { Volume } from './estimate';

  interface Props {
    level: Volume;
  }

  let { level }: Props = $props();
</script>

<span class="rate" aria-hidden="true">
  {#each [1, 2, 3, 4] as segment (segment)}
    <span class="rate__seg" data-on={segment <= level}></span>
  {/each}
</span>
