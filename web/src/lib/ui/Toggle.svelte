<script lang="ts">
  /**
   * A boolean. One of the few controls that genuinely needs a word: a picture can say *on*, but
   * nothing can draw *include bandwidth estimates*, so the label is the control's name and the
   * track is its state.
   */
  import { cx } from './format';

  interface Props {
    label: string;
    checked: boolean;
    /** One line under the label. Not a hint about how to use it — a consequence of turning it on. */
    description?: string;
    /** Draws the track in --caution when on: nothing is wrong, but this one spends something. */
    caution?: boolean;
    disabled?: boolean;
    describedby?: string;
    testid?: string;
    class?: string;
    onchange: (checked: boolean) => void;
  }

  let {
    label,
    checked,
    description,
    caution = false,
    disabled = false,
    describedby,
    testid,
    class: extra,
    onchange
  }: Props = $props();
</script>

<button
  class={cx('toggle', caution && 'toggle--caution', extra)}
  type="button"
  role="switch"
  aria-checked={checked}
  aria-describedby={describedby}
  {disabled}
  data-testid={testid}
  onclick={() => onchange(!checked)}
>
  <span class="toggle__track"><span class="toggle__knob"></span></span>
  <span class="toggle__text">
    <span>{label}</span>
    {#if description}<span class="micro faint">{description}</span>{/if}
  </span>
</button>
