<script lang="ts">
  /**
   * The app's own five-dot rail, neutral: no step is current, because nobody is anywhere.
   *
   * One divergence from the phone, and only one. The app prints no names on this rail — someone
   * moving through it already knows which panel they are on. Here the reader is learning the shape
   * rather than navigating it, so the names are on and a caption under each dot says what that
   * panel asks. The marker under the fifth is where collection begins, and it is the only thing on
   * this drawing in the accent colour.
   *
   * The caption row repeats `.rail--horizontal`'s flex arithmetic rather than approximating it —
   * five steps and four connectors, each `flex: 1` — so a caption sits under its own dot at every
   * width rather than near it.
   */
  import StepRail from '$lib/ui/StepRail.svelte';
  import Note from '$lib/ui/Note.svelte';
  import { SETUP_STEPS } from './content';
  import { m } from './messages.svelte';
  import { reveal } from './reveal';

  let seen = $state<boolean | undefined>(undefined);
</script>

<div class="setup" data-in={seen} use:reveal={(visible) => (seen = visible)}>
  <div class="setup__inner">
    <div class="setup__rail">
      <StepRail
        count={SETUP_STEPS.length}
        labelNames={SETUP_STEPS.map((step) => m(step.nameKey))}
        orientation="horizontal"
        labels="always"
      />
    </div>

    <div class="setup__captions">
      {#each SETUP_STEPS as step, index (step.captionKey)}
        {#if index > 0}<span class="setup__gap" aria-hidden="true"></span>{/if}
        <p
          class="setup__caption"
          class:setup__caption--start={index === SETUP_STEPS.length - 1}
          style={`--index: ${index}`}
        >
          {#if index === SETUP_STEPS.length - 1}
            <span class="setup__marker" aria-hidden="true"></span>
          {/if}
          <span>{m(step.captionKey)}</span>
        </p>
      {/each}
    </div>
  </div>
</div>

<p class="fine faint">{m('setup.note')}</p>

<Note icon="language" text={m('setup.language')} />

<style>
  .setup {
    padding: var(--sp-7) var(--sp-6);
    background: var(--surface);
    border: var(--line-hair) solid var(--rule);
    border-radius: var(--r-panel);
    overflow-x: auto;
  }

  .setup__inner {
    display: flex;
    flex-direction: column;
    gap: var(--sp-5);
    min-inline-size: 21rem;
  }

  .setup__captions {
    display: flex;
    gap: var(--sp-2);
    align-items: start;
  }

  .setup__caption {
    flex: 1;
    min-inline-size: var(--tap-min);
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--sp-3);
    text-align: center;
    font-size: var(--type-micro);
    line-height: var(--lh-tight);
    color: var(--ink-faint);
  }

  .setup__gap {
    flex: 1;
    min-inline-size: var(--sp-5);
  }

  .setup__caption--start {
    color: var(--accent);
  }

  .setup__marker {
    inline-size: 0;
    block-size: 0;
    border-inline: 5px solid transparent;
    border-block-end: 6px solid var(--accent);
  }

  @media (prefers-reduced-motion: no-preference) {
    .setup[data-in='false'] .setup__rail,
    .setup[data-in='false'] .setup__caption {
      opacity: 0;
    }

    .setup[data-in='true'] .setup__rail {
      animation: rail-in 400ms var(--ease-out) both;
    }

    .setup[data-in='true'] .setup__caption {
      animation: rail-in 400ms var(--ease-out) both;
      animation-delay: calc(120ms + var(--index, 0) * 60ms);
    }

    @keyframes rail-in {
      from {
        opacity: 0;
      }
    }
  }
</style>
