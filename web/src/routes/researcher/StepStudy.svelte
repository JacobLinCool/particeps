<script lang="ts">
  /**
   * The study itself, in eight blocks separated by ground rather than by cards — the treatment
   * `Disclosure` gets in the app, for the same reason: these are one document, not eight objects.
   *
   * Two placements are deliberate. `storage.maximum_local_bytes` sits with the collectors even
   * though it is a study-level field, because the decision is only meaningful next to the things
   * that fill it. And delivery sits last, next to a chip pointing back at the consent text, because
   * a study that transmits has to say so in words the participant reads before agreeing.
   */
  import Chip from '$lib/ui/Chip.svelte';
  import Icon from '$lib/ui/Icon.svelte';
  import IdField from '$lib/ui/IdField.svelte';
  import InstantField from '$lib/ui/InstantField.svelte';
  import Note from '$lib/ui/Note.svelte';
  import RangeField from '$lib/ui/RangeField.svelte';
  import Section from '$lib/ui/Section.svelte';
  import TextField from '$lib/ui/TextField.svelte';
  import ToggleField from '$lib/ui/ToggleField.svelte';
  import CollectorCard from './CollectorCard.svelte';
  import IntegerField from './IntegerField.svelte';
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

  const durationPresets = [1, 8, 24, 72, 168, 720];

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
  <Section id="identity" title={section.identity.title} lead={section.identity.note} icon="document">
    <IdField
      label={m.field.label.experimentId}
      hint={m.field.hint.id}
      path="experiment_id"
      value={configuration.experiment_id}
      suggestFrom={configuration.title}
      suggestLabel={m.control.applySuggestion}
      onchange={(value) => (configuration.experiment_id = value)}
    />
    <IdField
      label={m.field.label.configurationId}
      hint={m.field.hint.id}
      path="configuration_id"
      value={configuration.configuration_id}
      onchange={(value) => (configuration.configuration_id = value)}
    />
  </Section>

  <Section id="validity" title={section.validity.title} lead={section.validity.note} icon="clock">
    <WindowStrip
      issuedAt={configuration.issued_at}
      expiresAt={configuration.expires_at}
      durationHours={configuration.duration_hours}
      {now}
      {units}
    />
    <InstantField
      label={m.field.label.issuedAt}
      zoneLabel={m.control.timezone}
      path="issued_at"
      value={configuration.issued_at}
      onchange={(value) => (configuration.issued_at = value)}
    />
    <InstantField
      label={m.field.label.expiresAt}
      zoneLabel={m.control.timezone}
      path="expires_at"
      value={configuration.expires_at}
      onchange={(value) => (configuration.expires_at = value)}
    />
    <RangeField
      label={m.field.label.durationHours}
      hint={m.field.hint.duration}
      path="duration_hours"
      value={configuration.duration_hours}
      min={BOUNDS.durationHours[0]}
      max={BOUNDS.durationHours[1]}
      scale="log"
      icon="person"
      format={units.hours}
      presets={durationPresets}
      onchange={(value) => (configuration.duration_hours = value)}
    />
    <IntegerField
      label={m.field.label.minimumAppVersion}
      hint={m.field.hint.minimumAppVersion}
      path="minimum_app_version"
      value={configuration.minimum_app_version}
      min={BOUNDS.minimumAppVersion[0]}
      icon="phone"
      onchange={(value) => (configuration.minimum_app_version = value)}
    />
  </Section>

  <Section id="about" title={section.about.title} lead={section.about.note} icon="person">
    <TextField
      label={m.field.label.title}
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
    <Note icon="info" tone="plain" text={m.researcher.how.disclosure.body} />
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
  </Section>

  <Section id="storage" title={section.storage.title} lead={section.storage.note} icon="storage">
    <QuotaMeter
      quotaBytes={configuration.storage.maximum_local_bytes}
      bytesPerHour={draft.estimate.bytesPerHour}
      durationHours={configuration.duration_hours}
      label={m.field.label.storageQuota}
      hint={section.storage.note}
      {units}
      onquota={(value) => (configuration.storage.maximum_local_bytes = value)}
    />
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
</style>
