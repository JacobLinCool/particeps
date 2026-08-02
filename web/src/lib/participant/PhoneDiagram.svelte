<script lang="ts">
  /**
   * One drawing, three configurations.
   *
   * The phone, the store inside it, and the lock never move between them; only the arrows differ.
   * That is the whole point: the hero teaches the picture, and section 4 then shows the two kinds
   * of study as two states of a picture the reader has already read. Hiding the second behind a
   * toggle would make the difference a thing you have to go and find.
   *
   * The glyphs are the app's own marks, taken from the same table the rest of the site draws from,
   * so a source in this drawing is the same mark the participant will meet on the data step.
   */
  import { ICONS, resolveIcon } from '$lib/ui/icons';
  import { m, type MessageKey } from './messages.svelte';
  import type { GlyphName } from './content';

  interface Props {
    inbound: GlyphName[];
    outbound: 'none' | 'manual' | 'automatic' | 'both';
    sealed?: boolean;
    /** The dash march on the automatic arrow. Only that arrow, and only where it repeats. */
    animate?: boolean;
    /** The sentence a sighted reader gets from the drawing, not a second copy of the prose. */
    captionKey: MessageKey;
  }

  let { inbound, outbound, sealed = true, animate = false, captionKey }: Props = $props();

  const MIDDLE = 88;
  const SOURCE_X = 14;
  const PHONE_LEFT = 130;
  const PHONE_RIGHT = 210;
  const OUT_X = 300;

  /** Evenly through the phone's own height, so three sources bracket the store and the lock. */
  const inboundRows = $derived(
    inbound.map((glyph, index) => ({
      glyph,
      y: inbound.length === 1 ? MIDDLE : 44 + (index * 88) / (inbound.length - 1)
    }))
  );

  const manual = $derived(outbound === 'manual' || outbound === 'both');
  const automatic = $derived(outbound === 'automatic' || outbound === 'both');
  const split = $derived(outbound === 'both');

  /** A flat S from a source to the phone's edge: a route, not a wire. */
  function flow(x1: number, y1: number, x2: number, y2: number): string {
    const bend = (x2 - x1) * 0.55;
    return `M${x1} ${y1} C${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}`;
  }

  function head(x: number, y: number): string {
    return `M${x - 7} ${y - 5} L${x} ${y} L${x - 7} ${y + 5}`;
  }

  function draw(name: GlyphName): string {
    const resolved = resolveIcon(name);
    return resolved ? ICONS[resolved] : '';
  }
</script>

<figure class="diagram">
  <svg
    class="diagram__svg"
    viewBox="0 0 340 176"
    fill="none"
    stroke="currentColor"
    stroke-width="2"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
  >
    {#each inboundRows as row (row.glyph)}
      <path class="diagram__flow" d={flow(46, row.y, PHONE_LEFT - 8, MIDDLE)} />
      <path class="diagram__flow" d={head(PHONE_LEFT - 8, MIDDLE)} />
      <g class="diagram__source" transform={`translate(${SOURCE_X} ${row.y - 12})`}>
        <!-- eslint-disable-next-line svelte/no-at-html-tags -- constant markup from icons.ts -->
        {@html draw(row.glyph)}
      </g>
    {/each}

    <rect
      class="diagram__phone"
      x={PHONE_LEFT}
      y="26"
      width={PHONE_RIGHT - PHONE_LEFT}
      height="124"
      rx="18"
    />
    <path class="diagram__phone" d="M158 132h24" />

    <g class="diagram__store" transform="translate(154 46) scale(1.35)">
      <!-- eslint-disable-next-line svelte/no-at-html-tags -- constant markup from icons.ts -->
      {@html draw('storage')}
    </g>

    {#if sealed}
      <g class="diagram__lock" transform="translate(155 92) scale(1.25)">
        <!-- eslint-disable-next-line svelte/no-at-html-tags -- constant markup from icons.ts -->
        {@html draw('lock')}
      </g>
    {/if}

    {#if manual}
      {@const y = split ? 62 : MIDDLE}
      <path class="diagram__out" d={flow(PHONE_RIGHT + 6, MIDDLE, OUT_X - 10, y)} />
      <path class="diagram__out" d={head(OUT_X - 10, y)} />
      <g class="diagram__out" transform={`translate(${OUT_X} ${y - 12})`}>
        <!-- eslint-disable-next-line svelte/no-at-html-tags -- constant markup from icons.ts -->
        {@html draw('export')}
      </g>
    {/if}

    {#if automatic}
      {@const y = split ? 114 : MIDDLE}
      <path
        class="diagram__out"
        class:diagram__march={animate}
        d={flow(PHONE_RIGHT + 6, MIDDLE, OUT_X - 10, y)}
        stroke-dasharray={animate ? undefined : '5 5'}
        data-loop={animate ? '' : undefined}
      />
      <path class="diagram__out" d={head(OUT_X - 10, y)} />
      <g class="diagram__out" transform={`translate(${OUT_X} ${y - 12})`}>
        <!-- eslint-disable-next-line svelte/no-at-html-tags -- constant markup from icons.ts -->
        {@html draw('sendAuto')}
      </g>
    {/if}
  </svg>

  <figcaption class="sr">{m(captionKey)}</figcaption>
</figure>

<style>
  .diagram {
    inline-size: 100%;
  }

  .diagram__svg {
    inline-size: 100%;
    block-size: auto;
  }

  .diagram__phone {
    stroke: var(--ink-soft);
  }

  .diagram__store {
    color: var(--ink-faint);
  }

  .diagram__lock {
    color: var(--seal);
  }

  .diagram__source {
    color: var(--ink-faint);
  }

  .diagram__flow {
    stroke: var(--rule);
  }

  .diagram__out {
    stroke: var(--accent);
    color: var(--accent);
  }

  /* The one thing on the page that genuinely repeats. base.css stops `[data-loop]` under
     `prefers-reduced-motion: reduce`, and the dash pattern stays, so the arrow reads as
     scheduled rather than drawn by hand either way. */
  .diagram__march {
    stroke-dasharray: 5 5;
    animation: march 3s linear infinite;
  }

  @keyframes march {
    to {
      stroke-dashoffset: -20;
    }
  }
</style>
