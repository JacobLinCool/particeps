<script lang="ts">
  /**
   * The study itself, in six blocks separated by ground rather than by cards — the treatment
   * `Disclosure` gets in the app, for the same reason: these are one document, not six objects.
   *
   * What is asked here is what only a person can answer. Nothing on this step names the study:
   * `experiment_id` and `configuration_id` are derived from the title and from the document's own
   * bytes (`lib/adc/ids.ts`, shown on the sign step), and `minimum_app_version` is pinned, so three
   * controls that were arithmetic dressed as questions are gone.
   *
   * Two placements are deliberate. `storage.maximum_local_bytes` sits under the collectors even
   * though it is a study-level field, because the decision is only meaningful next to the things
   * that fill it — and it is now the only cost signal on the page that is measured in anything. And
   * delivery sits last, next to a chip pointing back at the consent text, because a study that
   * transmits has to say so in words the participant reads before agreeing.
   */
  import Chip from '$lib/ui/Chip.svelte';
  import Icon from '$lib/ui/Icon.svelte';
  import InstantField, { zoneOptions } from '$lib/ui/InstantField.svelte';
  import Note from '$lib/ui/Note.svelte';
  import RangeField from '$lib/ui/RangeField.svelte';
  import Section from '$lib/ui/Section.svelte';
  import TextField from '$lib/ui/TextField.svelte';
  import ToggleField from '$lib/ui/ToggleField.svelte';
  import CollectorCard from './CollectorCard.svelte';
  import DurationField from './DurationField.svelte';
  import PromptRow from './PromptRow.svelte';
  import PromptTimeline from './PromptTimeline.svelte';
  import QuotaMeter from './QuotaMeter.svelte';
  import WindowStrip from './WindowStrip.svelte';
  import { COLLECTOR_ORDER, type Draft } from './draft.svelte';
  import { PRESETS } from './presets';
  import {
    BOUNDS,
    UPLOAD_MAXIMUM_INTERVAL_MINUTES,
    UPLOAD_MINIMUM_INTERVAL_MINUTES
  } from '$lib/adc/types';
  import type { Messages } from '$lib/i18n/types';
  import type { Units } from './units';

  interface Props {
    draft: Draft;
    m: Messages;
    units: Units;
  }

  let { draft, m, units }: Props = $props();

  const configuration = $derived(draft.configuration);
  const section = $derived(m.researcher.study.section);

  /** Redrawn once a minute: a `now` tick that never moves is a tick nobody trusts. */
  let now = $state(Math.floor(Date.now() / 1_000));
  $effect(() => {
    const timer = setInterval(() => (now = Math.floor(Date.now() / 1_000)), 60_000);
    return () => clearInterval(timer);
  });

  /**
   * One zone for both instants. Two selectors for one decision was the clutter; deleting them
   * without replacement was the correctness risk, because a wall time with no zone beside it is a
   * time somebody will read as UTC.
   */
  const zones = zoneOptions();
  let zone = $state(zones[0]);

  /** The host the participant's consent screen will render, which is how a typo gets caught. */
  const endpointHost = $derived.by(() => {
    const endpoint = configuration.upload?.endpoint ?? '';
    if (!endpoint.startsWith('https://')) return null;
    const authority = endpoint.slice(8).split(/[/?#]/)[0];
    const named = authority.slice(authority.lastIndexOf('@') + 1);
    return named.replace(/:\d*$/, '') || null;
  });
</script>

<div class="stack">
  <Section id="about" title={section.about.title} lead={section.about.note} icon="person">
    <TextField
      label={m.field.label.title}
      hint={m.field.hint.title}
      path="title"
      value={configuration.title}
      max={BOUNDS.title[1]}
      onchange={(value) => (configuration.title = value)}
    />
    <TextField
      label={m.field.label.researcherName}
      path="researcher.name"
      value={configuration.researcher.name}
      max={BOUNDS.researcherName[1]}
      onchange={(value) => (configuration.researcher.name = value)}
    />
    <TextField
      label={m.field.label.researcherContact}
      hint={m.field.hint.contact}
      path="researcher.contact"
      value={configuration.researcher.contact}
      max={BOUNDS.researcherContact[1]}
      onchange={(value) => (configuration.researcher.contact = value)}
    />
    <TextField
      label={m.field.label.purpose}
      path="purpose"
      value={configuration.purpose}
      max={BOUNDS.purpose[1]}
      multiline
      rows={4}
      onchange={(value) => (configuration.purpose = value)}
    />
  </Section>

  <!-- How long does this run? Three controls describe the window the file is valid in, one
       describes a stretch inside it, and `WindowStrip` draws that relationship at two bar heights —
       which is the sentence that then does not have to be written. -->
  <Section id="validity" title={section.validity.title} lead={section.validity.note} icon="clock">
    <WindowStrip
      issuedAt={configuration.issued_at}
      expiresAt={configuration.expires_at}
      durationHours={configuration.duration_hours}
      {now}
      {units}
    />

    <div class="timeband">
      <div class="timeband__window">
        <div class="timeband__instants">
          <InstantField
            label={m.field.label.issuedAt}
            path="issued_at"
            value={configuration.issued_at}
            {zone}
            echo={false}
            onchange={(value) => (configuration.issued_at = value)}
          />
          <InstantField
            label={m.field.label.expiresAt}
            hint={m.field.hint.expiresAt}
            path="expires_at"
            value={configuration.expires_at}
            {zone}
            echo={false}
            onchange={(value) => (configuration.expires_at = value)}
          />
        </div>

        {#if zones.length > 1}
          <!-- Named, not labelled: the value is the name of a zone and reads as one. It sits on its
               own line under both pickers because it governs both, which a selector tucked beside
               one of them would deny. -->
          <select
            class="input timeband__zone"
            aria-label={m.control.timezone}
            value={zone}
            onchange={(event) => (zone = event.currentTarget.value)}
          >
            {#each zones as option (option)}
              <option value={option}>{option}</option>
            {/each}
          </select>
        {/if}
      </div>

      <div class="timeband__each">
        <DurationField
          label={m.field.label.durationHours}
          hint={m.field.hint.duration}
          path="duration_hours"
          value={configuration.duration_hours}
          min={BOUNDS.durationHours[0]}
          max={BOUNDS.durationHours[1]}
          format={units.hours}
          presets={PRESETS.duration_hours}
          onchange={(value) => (configuration.duration_hours = value)}
        />
      </div>
    </div>

    <Note icon="info" tone="plain" text={m.researcher.study.note.irrevocable} />
  </Section>

  <Section
    id="collectors"
    path="collectors"
    title={section.collectors.title}
    lead={section.collectors.note}
    icon="sources"
  >
    <div class="collectors">
      {#each COLLECTOR_ORDER as id (id)}
        <CollectorCard
          {id}
          config={draft.collector(id)}
          path={draft.collectorPath(id)}
          {m}
          {units}
          onenable={(which) => draft.enableCollector(which)}
          ondisable={(which) => draft.disableCollector(which)}
        />
      {/each}
    </div>

    <QuotaMeter
      quotaBytes={configuration.storage.maximum_local_bytes}
      bytesPerHour={draft.estimate.bytesPerHour}
      durationHours={configuration.duration_hours}
      label={m.field.label.storageQuota}
      hint={m.field.hint.storageQuota}
      {units}
      onquota={(value) => (configuration.storage.maximum_local_bytes = value)}
    />
  </Section>

  <Section id="consent" title={section.consent.title} lead={section.consent.note} icon="seal">
    <TextField
      label={m.field.label.consentDocumentVersion}
      path="consent.document_version"
      value={configuration.consent.document_version}
      max={BOUNDS.consentDocumentVersion[1]}
      onchange={(value) => (configuration.consent.document_version = value)}
    />
    <!-- The hint is the coverage list from the researcher guide. No code can check that a summary
         covers it, so it is stated where it will be read rather than turned into a false green. -->
    <TextField
      label={m.field.label.consentSummary}
      hint={m.field.hint.consentSummary}
      path="consent.summary"
      value={configuration.consent.summary}
      max={BOUNDS.consentSummary[1]}
      multiline
      rows={10}
      onchange={(value) => (configuration.consent.summary = value)}
    />
    <Note icon="info" tone="plain" text={m.researcher.study.note.disclosure} />
  </Section>

  <Section id="prompts" title={section.prompts.title} lead={section.prompts.note} icon="bell">
    {#if configuration.prompts.length === 0}
      <Note icon="info" tone="plain" text={m.empty.prompts} />
    {:else}
      <PromptTimeline
        prompts={configuration.prompts}
        durationHours={configuration.duration_hours}
        label={section.prompts.title}
      />
      <!-- Any prompt makes notification access required for the participant. A state, not a
           warning: --accent, not --caution. A `Chip` is a pill with a 30px floor, so a sentence
           inside one wraps to two lines in a lozenge; `Note` is the component built for sentences. -->
      <Note icon="bell" tone="accent" text={m.field.hint.required} />
      {#each configuration.prompts as prompt, index (index)}
        <PromptRow
          {prompt}
          {index}
          {m}
          {units}
          onremove={() => draft.removePrompt(index)}
        />
      {/each}
    {/if}

    <button
      class="addtile"
      type="button"
      aria-label={m.action.addPrompt}
      onclick={() =>
        draft.addPrompt({
          id: `prompt-${configuration.prompts.length + 1}`,
          delay_minutes: 1_440,
          message: ''
        })}
      data-testid="prompt-add"
    >
      <Icon name="plus" size={24} />
    </button>
  </Section>

  <Section id="delivery" title={section.delivery.title} lead={section.delivery.note} icon="send-auto">
    <ToggleField
      label={m.field.label.upload}
      value={configuration.upload !== null}
      onchange={(on) =>
        (configuration.upload = on
          ? { endpoint: 'https://', interval_minutes: 360, allow_metered: false }
          : null)}
    />

    {#if configuration.upload}
      {@const upload = configuration.upload}
      <!-- The half of this that a participant cannot opt out of, said where it is switched on. -->
      <Note icon="alert" tone="caution" text={m.researcher.study.note.delivery} />
      <TextField
        label={m.field.label.endpoint}
        hint={m.field.hint.endpoint}
        path="upload.endpoint"
        value={upload.endpoint}
        max={BOUNDS.uploadEndpoint[1]}
        mono
        inputmode="url"
        onchange={(value) => (upload.endpoint = value)}
      />
      {#if endpointHost}
        <p class="mono micro faint">{endpointHost}</p>
      {/if}
      <RangeField
        label={m.field.label.uploadInterval}
        path="upload.interval_minutes"
        value={upload.interval_minutes}
        min={UPLOAD_MINIMUM_INTERVAL_MINUTES}
        max={UPLOAD_MAXIMUM_INTERVAL_MINUTES}
        scale="log"
        icon="clock"
        format={units.minutes}
        presets={PRESETS.upload_interval_minutes}
        onchange={(value) => (upload.interval_minutes = value)}
      />
      <ToggleField
        label={m.field.label.allowMetered}
        hint={m.field.hint.allowMetered}
        path="upload.allow_metered"
        value={upload.allow_metered}
        caution={upload.allow_metered}
        onchange={(value) => (upload.allow_metered = value)}
      />
      <!-- Upload must be disclosed. The site cannot check the text; it can put the two things
           next to each other. -->
      <Chip icon="link-out" tone="accent" href="#consent" label={section.consent.title} />
    {/if}
  </Section>
</div>

<style>
  /* A collector card is as tall as its own parameters. Stretching a two-line card to match the
     accelerometer's six controls would put a hundred pixels of nothing under the short one. */
  .collectors {
    align-items: start;
  }

  /* Three controls on one side describe when the file is valid; one on the other describes how long
     a person runs inside it. The rule between them is the whole explanation, and it turns to a
     horizontal one when the two stack. */
  .timeband {
    display: grid;
    gap: var(--sp-6);
  }

  .timeband__window {
    display: flex;
    flex-direction: column;
    gap: var(--sp-5);
  }

  /* Flex rather than `auto-fit` columns: the two pickers share a line where both can be read whole
     and take a line each where they cannot. A grid of `minmax(210px, 1fr)` tracks laid three of
     them across a 668px column and left a `datetime-local` at 199px, which draws its own seconds
     field clipped — the value is wrong on screen and nothing says so. */
  .timeband__instants {
    display: flex;
    flex-wrap: wrap;
    gap: var(--sp-5);
  }

  .timeband__instants :global(.field) {
    flex: 1 1 210px;
    min-inline-size: 0;
  }

  /* Its own line, at its own width, centred under the pair: aligned to either edge it would read as
     belonging to the picker above it, and it governs both. Changing it moves both wall times at
     once, which is the other half of saying so. */
  .timeband__zone {
    align-self: center;
    inline-size: auto;
  }

  .timeband__each {
    border-block-start: var(--line-hair) solid var(--rule);
    padding-block-start: var(--sp-6);
  }

  @media (min-width: 720px) {
    .timeband {
      grid-template-columns: 3fr 2fr;
      align-items: start;
    }

    .timeband__each {
      border-block-start: 0;
      padding-block-start: 0;
      border-inline-start: var(--line-hair) solid var(--rule);
      padding-inline-start: var(--sp-6);
    }
  }
</style>
