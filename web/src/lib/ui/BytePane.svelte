<script lang="ts">
  /**
   * The canonical bytes, shown as bytes.
   *
   * This is what makes the site trustworthy to a researcher who already knows the CLI: they can
   * diff what is on screen against `researcher-tools canonicalize` and see the same thing. So the
   * pane has no radius — a rounded corner would imply the bytes had been prettified — and the copy
   * control copies the text verbatim.
   *
   * The count and the bar move on the same duration, so the two can never disagree about how full
   * the document is. The denominator is `MAXIMUM_CONFIGURATION_BYTES`, literally.
   */
  import CopyButton from './CopyButton.svelte';
  import { tokenize } from './bytes';
  import { byteRatio, fillLevel, fraction } from './format';
  import { MAXIMUM_CONFIGURATION_BYTES } from '$lib/adc/types';

  interface Props {
    text: string;
    /** Byte length of the canonical encoding, which is not the same as `text.length`. */
    bytes: number;
    max?: number;
    copyLabel: string;
    copiedLabel: string;
    testid?: string;
  }

  let {
    text,
    bytes,
    max = MAXIMUM_CONFIGURATION_BYTES,
    copyLabel,
    copiedLabel,
    testid = 'canonical-preview'
  }: Props = $props();

  const spans = $derived(tokenize(text));
  const level = $derived(fillLevel(bytes, max));
</script>

<div class="bytes" data-testid={testid}>
  <CopyButton text={() => text} label={copyLabel} {copiedLabel} float />

  <pre class="bytes__body"><code
      >{#each spans as span, index (index)}{#if span.role === 'plain'}{span.text}{:else}<span
            class={`tok-${span.role === 'blob' ? 'blob' : span.role}`}>{span.text}</span
          >{/if}{/each}</code
    ></pre>

  <div class="bytes__foot">
    <span class="bytes__count" data-level={level === 'over' ? 'over' : undefined}>
      {byteRatio(bytes, max)}
    </span>
    <span class="bytes__gauge" aria-hidden="true">
      <span
        class="bytes__fill"
        data-level={level === 'under' ? undefined : level}
        style="transform: scaleX({fraction(bytes, max)})"
      ></span>
    </span>
  </div>
</div>
