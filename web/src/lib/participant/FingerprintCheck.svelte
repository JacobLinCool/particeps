<script lang="ts">
  /**
   * The one interactive thing on the page, and the only task it teaches.
   *
   * Left, fixed: what the research team published, through the channel that already reached the
   * reader. Right: two faithful replicas of the app's *Configuration signature* block. One of them
   * matches. Which one is decided on mount, so it differs between visits and cannot be learned
   * from the prerendered HTML — and the sweep resolves group by group, stopping at the first
   * difference, because a transposition in the middle of the value is exactly what an eye skimming
   * the first and last groups misses. Anything less than a group-by-group comparison fails here.
   *
   * Everything is Frost and neutral until a verdict exists. The app refuses to paint an unpinned
   * signer red, since an unpinned signer is the ordinary case; a page that rendered the same block
   * as an alarm would teach a reader to skip it.
   */
  import Glyph from '$lib/ui/Glyph.svelte';
  import Mark from '$lib/ui/Mark.svelte';
  import FingerprintValue from '$lib/ui/FingerprintValue.svelte';
  import LiveRegion from '$lib/ui/LiveRegion.svelte';
  import { fingerprintGroups } from '$lib/ui/format';
  import { m } from './messages.svelte';
  import type { FingerprintCandidate } from './content';
  import { onMount } from 'svelte';

  interface Props {
    published: string;
    candidates: readonly FingerprintCandidate[];
  }

  let { published, candidates }: Props = $props();

  const STEP = 70;

  let order = $state<readonly FingerprintCandidate[]>([]);
  let picked = $state<string | null>(null);
  let revealed = $state(0);
  let timer: ReturnType<typeof setInterval> | undefined;

  const shown = $derived(order.length ? order : candidates);
  const groups = $derived(fingerprintGroups(published));

  onMount(() => {
    order = Math.random() < 0.5 ? candidates : [...candidates].reverse();
  });

  /** Where the sweep stops: the first differing group, or the end. */
  function breakAt(value: string): number {
    const other = fingerprintGroups(value);
    const at = groups.findIndex((group, index) => group !== other[index]);
    return at === -1 ? groups.length : at + 1;
  }

  const verdict = $derived.by(() => {
    const candidate = shown.find((entry) => entry.id === picked);
    if (!candidate || revealed < breakAt(candidate.fingerprint)) return null;
    return candidate.fingerprint === published ? 'match' : 'mismatch';
  });

  function pick(candidate: FingerprintCandidate) {
    clearInterval(timer);
    picked = candidate.id;

    const stop = breakAt(candidate.fingerprint);
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) {
      revealed = stop;
      return;
    }

    revealed = 0;
    timer = setInterval(() => {
      revealed += 1;
      if (revealed >= stop) clearInterval(timer);
    }, STEP);
  }

  $effect(() => () => clearInterval(timer));
</script>

<p class="micro faint">{m('fingerprint.sample')}</p>

<div class="check">
  <div class="check__published">
    <p class="check__head">
      <Glyph name="document" size={18} tone="accent" />
      <span>{m('fingerprint.publishedTitle')}</span>
    </p>
    <FingerprintValue value={published} size="plaque" />
    <p class="micro faint">{m('fingerprint.publishedNote')}</p>
  </div>

  <div class="check__side">
    <p class="fine soft">{m('fingerprint.pick')}</p>

    <div class="check__candidates">
      {#each shown as candidate (candidate.id)}
        <button
          class="signature"
          type="button"
          aria-pressed={picked === candidate.id}
          data-verdict={picked === candidate.id && verdict ? verdict : 'none'}
          onclick={() => pick(candidate)}
        >
          <span class="check__head">
            <Mark kind={picked === candidate.id && verdict ? 'check' : 'pending'} tone="accent" />
            <span>{m('fingerprint.cardTitle')}</span>
          </span>

          <FingerprintValue
            value={candidate.fingerprint}
            size="plaque"
            compareTo={picked === candidate.id ? published : undefined}
            revealed={picked === candidate.id ? revealed : undefined}
          />

          <span class="signature__compare">{m('fingerprint.compare')}</span>
          <span class="signature__caveat">{m('fingerprint.unverified')}</span>
        </button>
      {/each}
    </div>

    <p class="verdict" data-verdict={verdict ?? 'none'}>
      {#if verdict === 'match'}
        <Mark kind="check" tone="signal" />
        <span>{m('fingerprint.match')}</span>
      {:else if verdict === 'mismatch'}
        <Mark kind="blocking" tone="danger" />
        <span>{m('fingerprint.mismatch')}</span>
      {/if}
    </p>

    <LiveRegion
      text={verdict ? m(verdict === 'match' ? 'fingerprint.match' : 'fingerprint.mismatch') : ''}
    />

    <p class="micro faint">{m('fingerprint.normal')}</p>
  </div>
</div>

<style>
  .check {
    display: grid;
    gap: var(--sp-6);
    align-items: start;
  }

  @media (min-width: 56rem) {
    .check {
      grid-template-columns: minmax(0, 19rem) minmax(0, 1fr);
    }
  }

  .check__published {
    display: flex;
    flex-direction: column;
    gap: var(--sp-5);
    padding: var(--sp-6);
    background: var(--surface-sunk);
    border-radius: var(--r-panel);
  }

  .check__side {
    display: flex;
    flex-direction: column;
    gap: var(--sp-6);
  }

  .check__candidates {
    display: grid;
    gap: var(--sp-5);
  }

  /* The same dashed edge the sample values in section 4 carry. No fingerprint on this page is
     authoritative, and a participant who learns to get provenance from a web page has learned the
     habit the check exists to defeat. */
  .check :global(.fingerprint--plaque) {
    border: var(--line-hair) dashed var(--rule);
  }

  .check__head {
    display: flex;
    align-items: center;
    gap: var(--sp-4);
    font-size: var(--type-fine);
    font-weight: var(--w-medium);
  }

  .signature {
    display: flex;
    flex-direction: column;
    gap: var(--sp-4);
    inline-size: 100%;
    padding: var(--sp-6);
    text-align: start;
    background: var(--surface);
    border: var(--line-hair) solid var(--rule);
    border-radius: var(--r-panel);
    transition:
      border-color var(--motion-state),
      box-shadow var(--motion-state);
  }

  .signature:hover {
    border-color: var(--accent);
    box-shadow: var(--shadow-lift);
  }

  .signature[data-verdict='match'] {
    border-color: var(--signal-ink);
  }

  .signature[data-verdict='mismatch'] {
    border-color: var(--danger-ink);
  }

  .signature__compare {
    font-size: var(--type-fine);
  }

  .signature__caveat {
    font-size: var(--type-micro);
    color: var(--ink-faint);
  }

  .verdict {
    display: flex;
    align-items: start;
    gap: var(--sp-4);
    min-block-size: 1.4em;
    font-size: var(--type-fine);
  }

  .verdict[data-verdict='match'] {
    color: var(--signal-ink);
  }

  /* One of exactly two places on this page in the error colour. */
  .verdict[data-verdict='mismatch'] {
    color: var(--danger-ink);
  }
</style>
