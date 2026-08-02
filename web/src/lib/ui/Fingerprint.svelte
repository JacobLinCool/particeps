<script lang="ts">
  /**
   * Eight groups of four, drawn as eight groups.
   *
   * Each group is its own element with `white-space: nowrap`, so a line break never falls inside
   * one and a screen reader reads groups rather than a thirty-two character run. The whole point
   * of the value is eye-comparison against something printed, and a group split across two lines
   * is a group nobody can compare.
   *
   * `plaque` makes the entire value the copy target: a small copy icon beside a large number is a
   * small target beside the thing the reader is already pointing at.
   */
  import { cx, fingerprintGroups } from './format';

  interface Props {
    value: string;
    size?: 'inline' | 'plaque';
    copyable?: boolean;
    /** `control.copyFingerprint`. Describes the button; it never replaces the value inside it. */
    copyLabel?: string;
    copiedLabel?: string;
    /** The published value to check against. Enables per-group comparison. */
    compareTo?: string;
    /** How many groups the sweep has resolved. The caller advances it. */
    revealed?: number;
    class?: string;
    testid?: string;
    oncopy?: () => void;
  }

  let {
    value,
    size = 'inline',
    copyable = false,
    copyLabel,
    copiedLabel,
    compareTo,
    revealed,
    class: extra,
    testid = 'fingerprint',
    oncopy
  }: Props = $props();

  const uid = $props.id();
  const groups = $derived(fingerprintGroups(value));
  const against = $derived(compareTo ? fingerprintGroups(compareTo) : null);

  function stateOf(index: number): string | undefined {
    if (!against) return undefined;
    if (revealed !== undefined && index >= revealed) return 'pending';
    return groups[index] === against[index] ? 'match' : 'mismatch';
  }

  let copied = $state(false);
  let announced = $state('');
  let timer: ReturnType<typeof setTimeout> | undefined;

  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      copied = true;
      announced = copiedLabel ?? '';
      oncopy?.();
    } catch {
      copied = false;
    }
    clearTimeout(timer);
    timer = setTimeout(() => {
      copied = false;
      announced = '';
    }, 1400);
  }

  $effect(() => () => clearTimeout(timer));

  const klass = $derived(
    cx('fingerprint', `fingerprint--${size}`, copied && 'fingerprint--copied', extra)
  );
</script>

{#snippet body()}
  {#each groups as group, index (index)}
    <span class="fingerprint__group" data-state={stateOf(index)}>{group}</span>
  {/each}
{/snippet}

{#if copyable}
  <!-- `aria-label` here would override the contents, and the contents are the eight groups the
       reader has to be able to read out and publish. The act goes on `title` and a described-by
       line instead, so the button announces its value and then what pressing it does. -->
  <button
    class={klass}
    type="button"
    title={copyLabel}
    aria-describedby={copyLabel ? `${uid}-act` : undefined}
    onclick={copy}
    data-testid={testid}
  >
    {@render body()}
  </button>
  {#if copyLabel}<span class="sr" id={`${uid}-act`}>{copyLabel}</span>{/if}
  <span class="sr" aria-live="polite">{announced}</span>
{:else}
  <span class={klass} data-testid={testid}>{@render body()}</span>
{/if}
