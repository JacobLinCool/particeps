<script lang="ts">
  /**
   * Four segments, warming as the rate climbs. Across seven cards this is an instant read of which
   * collector is the expensive one, which is the only thing a form cannot say about cost.
   *
   * `aria-hidden`: it summarises parameters that are already reachable a few controls below, and
   * an estimate announced as a value would read as a measurement.
   */
  import type { Intensity } from './estimate';

  interface Props {
    level: Intensity;
  }

  let { level }: Props = $props();
</script>

<span class="intensity" aria-hidden="true">
  {#each [1, 2, 3, 4] as segment (segment)}
    <span class="intensity__seg" data-on={segment <= level} data-level={segment}></span>
  {/each}
</span>
