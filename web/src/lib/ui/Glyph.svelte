<script lang="ts">
  /** `Icon` under the name both flow specifications use, and accepting a message key as well as
   *  a resolved string. Nothing else differs. */
  import Icon from './Icon.svelte';
  import { lookup } from './i18n.svelte';
  import type { IconRef } from './icons';
  import type { Tone } from './types';

  interface Props {
    name: IconRef;
    size?: number;
    tone?: Tone;
    label?: string;
    labelKey?: string;
    class?: string;
  }

  let { labelKey, label, ...rest }: Props = $props();
</script>

<Icon {...rest} label={label ?? (labelKey ? lookup(labelKey) : undefined)} />
