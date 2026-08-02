<script lang="ts">
  /**
   * One of the two kinds of study, drawn as the app's own consent block.
   *
   * Both cards are always on the page. Putting one behind a toggle would let a visitor leave
   * without ever seeing the upload case, and an absence is not a disclosure.
   *
   * The values in the upload card are samples and are drawn as samples. A participant's own
   * endpoint, cadence and network condition are rendered on their consent screen from the signed
   * bytes; nothing here may be mistaken for them.
   */
  import Glyph from '$lib/ui/Glyph.svelte';
  import Mark from '$lib/ui/Mark.svelte';
  import PhoneDiagram from './PhoneDiagram.svelte';
  import { m } from './messages.svelte';

  interface Props {
    mode: 'local' | 'upload';
  }

  let { mode }: Props = $props();
</script>

<div class="card">
  <div class="card__head">
    {#if mode === 'local'}
      <Mark kind="check" tone="signal" size={20} />
      <h3 class="card__title">{m('delivery.local.title')}</h3>
    {:else}
      <Glyph name="sendAuto" size={20} tone="accent" />
      <h3 class="card__title">{m('delivery.upload.title')}</h3>
    {/if}
  </div>

  {#if mode === 'local'}
    <PhoneDiagram inbound={['motion']} outbound="manual" captionKey="delivery.local.caption" />
    <p class="fine">{m('delivery.local.body')}</p>
  {:else}
    <PhoneDiagram
      inbound={['motion']}
      outbound="both"
      animate
      captionKey="delivery.upload.caption"
    />

    <p class="micro faint">{m('a11y.sample')}</p>

    <dl class="spec">
      <dt>{m('delivery.upload.destination')}</dt>
      <dd><span class="sample" title={m('a11y.sample')}>{m('delivery.upload.sampleHost')}</span></dd>
      <dt>{m('delivery.upload.cadence')}</dt>
      <dd>
        <span class="sample" title={m('a11y.sample')}>{m('delivery.upload.sampleCadence')}</span>
      </dd>
      <dt>{m('delivery.upload.network')}</dt>
      <dd>
        <span class="sample" title={m('a11y.sample')}>{m('delivery.upload.sampleNetwork')}</span>
      </dd>
    </dl>

    <p class="fine">{m('delivery.upload.code')}</p>
    <p class="fine">{m('delivery.upload.mandatory')}</p>
    <p class="micro faint">{m('delivery.upload.metadata')}</p>
  {/if}
</div>

<style>
  .card {
    display: flex;
    flex-direction: column;
    gap: var(--sp-5);
    padding: var(--sp-7);
    block-size: 100%;
    background: var(--surface);
    border: var(--line-hair) solid var(--rule);
    border-radius: var(--r-panel);
    box-shadow: var(--shadow-rest);
  }

  .card__head {
    display: flex;
    align-items: start;
    gap: var(--sp-5);
  }

  .card__title {
    font-size: var(--type-body);
    font-weight: var(--w-medium);
  }

  .spec {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: var(--sp-3) var(--sp-5);
    font-size: var(--type-fine);
  }

  .spec dt {
    color: var(--ink-faint);
  }

  /* Dashed and quiet, because nothing on this page may be mistaken for a real study's terms. */
  .sample {
    color: var(--ink-faint);
    font-family: var(--font-mono);
    font-size: 0.95em;
    text-decoration: underline dashed;
    text-decoration-thickness: 1px;
    text-underline-offset: 0.25em;
  }
</style>
