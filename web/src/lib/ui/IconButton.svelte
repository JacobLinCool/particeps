<script lang="ts">
  /**
   * A control drawn as one mark. `label` is required and has no default, so a caller cannot ship
   * an unnamed button by omission — the accessible name comes from i18n, through the caller.
   */
  import Icon from './Icon.svelte';
  import { cx } from './format';
  import type { IconRef } from './icons';
  import type { Tone } from './types';

  interface Props {
    icon: IconRef;
    /** The accessible name: a verb, short enough to be a tooltip. Never a sentence of prose. */
    label: string;
    size?: number;
    tone?: Tone;
    variant?: 'ghost' | 'danger' | 'float';
    disabled?: boolean;
    pressed?: boolean;
    expanded?: boolean;
    controls?: string;
    haspopup?: 'menu' | 'dialog' | 'true';
    settled?: boolean;
    testid?: string;
    class?: string;
    onclick?: (event: MouseEvent) => void;
  }

  let {
    icon,
    label,
    size = 20,
    tone,
    variant = 'ghost',
    disabled = false,
    pressed,
    expanded,
    controls,
    haspopup,
    settled = false,
    testid,
    class: extra,
    onclick
  }: Props = $props();
</script>

<button
  class={cx(
    'iconbtn',
    variant !== 'ghost' && `iconbtn--${variant}`,
    settled && 'iconbtn--settled',
    extra
  )}
  type="button"
  {disabled}
  {onclick}
  aria-label={label}
  title={label}
  aria-pressed={pressed}
  aria-expanded={expanded}
  aria-controls={controls}
  aria-haspopup={haspopup}
  data-testid={testid}
>
  <Icon name={settled ? 'check' : icon} {size} tone={settled ? 'signal' : tone} />
</button>
