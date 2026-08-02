<script lang="ts">
  /** `LocaleMenu` under the name both flow specifications use. */
  import LocaleMenu from './LocaleMenu.svelte';
  import type { ComponentProps } from 'svelte';

  let props: ComponentProps<typeof LocaleMenu> = $props();
</script>

<LocaleMenu {...props} />
