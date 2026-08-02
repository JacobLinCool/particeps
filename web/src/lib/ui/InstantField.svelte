<script lang="ts">
  /**
   * A wall-clock picker that emits an instant, and shows the instant it emitted.
   *
   * `datetime-local` yields local wall time with no zone, and the codec is unforgiving here: it
   * writes `Instant.toString()` and `decode` refuses bytes that do not round-trip. So this control
   * emits `YYYY-MM-DDTHH:MM:SSZ` and nothing else — always seconds, never sub-second, never an
   * offset form. `2026-01-01T00:00Z` parses in Java and re-emits with seconds, which makes the
   * file non-canonical and gets it refused.
   *
   * The literal string sits under the picker in monospace, because it is the byte-level truth and
   * a researcher may need to match it against a file the CLI produced.
   */
  import Field from './Field.svelte';
  import { fieldSource } from './field-context';

  interface Props {
    label: string;
    /** An ISO instant, exactly as it will be written. */
    value: string;
    path?: string;
    hint?: string;
    /** IANA zone. Defaults to the browser's. */
    zone?: string;
    /** `control.timezone`. The name of the control, never one of its values. */
    zoneLabel?: string;
    onchange: (value: string) => void;
  }

  let { label, value, path, hint, zone, zoneLabel, onchange }: Props = $props();

  const source = fieldSource();
  const browserZone =
    typeof Intl === 'undefined' ? 'UTC' : (Intl.DateTimeFormat().resolvedOptions().timeZone ?? 'UTC');

  let picked = $state(zone ?? browserZone);
  const zones = $derived(browserZone === 'UTC' ? ['UTC'] : [browserZone, 'UTC']);

  function partsIn(date: Date, timeZone: string): number[] {
    const parts = new Intl.DateTimeFormat('en-CA', {
      timeZone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit'
    }).formatToParts(date);
    const at = (type: string) => Number(parts.find((part) => part.type === type)?.value ?? 0);
    return [at('year'), at('month') - 1, at('day'), at('hour'), at('minute'), at('second')];
  }

  function offsetMs(date: Date, timeZone: string): number {
    const [y, mo, d, h, mi, s] = partsIn(date, timeZone);
    return Date.UTC(y, mo, d, h, mi, s) - Math.floor(date.getTime() / 1000) * 1000;
  }

  /** The wall time this instant reads as, in the chosen zone, for the picker. */
  const local = $derived.by(() => {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '';
    const [y, mo, d, h, mi, s] = partsIn(date, picked);
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${y}-${pad(mo + 1)}-${pad(d)}T${pad(h)}:${pad(mi)}:${pad(s)}`;
  });

  /** Two passes, because the offset at the guessed instant can differ from the offset at the
   *  real one across a DST boundary. */
  function toInstant(wall: string): string | null {
    const guess = Date.parse(`${wall.length === 16 ? `${wall}:00` : wall}Z`);
    if (Number.isNaN(guess)) return null;
    let ts = guess - offsetMs(new Date(guess), picked);
    ts = guess - offsetMs(new Date(ts), picked);
    return `${new Date(Math.floor(ts / 1000) * 1000).toISOString().replace(/\.\d{3}Z$/, 'Z')}`;
  }

  function edit(wall: string) {
    const instant = toInstant(wall);
    if (instant) onchange(instant);
  }
</script>

<Field {label} {path} {hint}>
  {#snippet children({ id, describedby, invalid })}
    <div class="row row--tight">
      <input
        class="input input--mono grow"
        type="datetime-local"
        step="1"
        {id}
        aria-describedby={describedby}
        aria-invalid={invalid || undefined}
        value={local}
        oninput={(event) => edit(event.currentTarget.value)}
        onblur={() => path && source.touch?.(path)}
      />
      {#if zones.length > 1}
        <select
          class="input"
          style="inline-size: auto"
          aria-label={zoneLabel}
          value={picked}
          onchange={(event) => {
            picked = event.currentTarget.value;
          }}
        >
          {#each zones as option (option)}
            <option value={option}>{option}</option>
          {/each}
        </select>
      {/if}
    </div>
    <p class="mono micro faint">{value}</p>
  {/snippet}
</Field>
