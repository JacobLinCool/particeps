<script lang="ts">
  /** An announcement with nowhere to be seen, or one that is also visible. Assertive is reserved
   *  for the single failure that must interrupt: a signature that does not verify against the
   *  public key already inside the configuration. */
  interface Props {
    text: string;
    politeness?: 'polite' | 'assertive';
    visible?: boolean;
    testid?: string;
  }

  let { text, politeness = 'polite', visible = false, testid }: Props = $props();
</script>

<div
  class={visible ? 'live' : 'sr'}
  aria-live={politeness}
  aria-atomic="true"
  data-testid={testid}
>
  {text}
</div>
