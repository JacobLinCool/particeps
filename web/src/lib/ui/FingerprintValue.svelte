<script lang="ts">
  /** `Fingerprint` under the name the participant flow specification uses. */
  import Fingerprint from './Fingerprint.svelte';
  import type { ComponentProps } from 'svelte';

  let props: ComponentProps<typeof Fingerprint> = $props();
</script>

<Fingerprint {...props} />
