<script lang="ts">
  /**
   * The study itself, in six blocks separated by ground rather than by cards — the treatment
   * `Disclosure` gets in the app, for the same reason: these are one document, not six objects.
   *
   * What is asked here is what only a person can answer. Nothing on this step names the study:
   * `experiment_id` and `configuration_id` are derived from the title and from the document's own
   * bytes (`lib/particeps/ids.ts`, shown on the sign step), and `minimum_client_version` is pinned, so three
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
  import IdentityField from './IdentityField.svelte';
  import InterventionEditor from './InterventionEditor.svelte';
  import TrafficShapingEditor from './TrafficShapingEditor.svelte';
  import ResourceAutomationEditor from './ResourceAutomationEditor.svelte';
  import ParticipantStudyPreview from './ParticipantStudyPreview.svelte';
  import SyntheticTraceSimulator from './SyntheticTraceSimulator.svelte';
  import QuotaMeter from './QuotaMeter.svelte';
  import WindowStrip from './WindowStrip.svelte';
  import { COLLECTOR_ORDER, type Draft } from './draft.svelte';
  import { scales } from './scales';
  import { BOUNDS } from '$lib/particeps/types';
  import type { Messages } from '$lib/i18n/types';
  import type { Units } from './units';
  import { i18n } from '$lib/ui/i18n.svelte';

  interface Props {
    draft: Draft;
    m: Messages;
    units: Units;
  }

  let { draft, m, units }: Props = $props();

  const configuration = $derived(draft.configuration);
  const section = $derived(m.researcher.study.section);

  /**
   * Every unit decision on this step, in one object. A control is handed the adapter for its own
   * field and nothing else — no bounds, no step, no humaniser — so the unit a researcher edits in
   * is decided in `scales.ts` and is not re-decided at eleven call sites.
   */
  const S = $derived(scales(m, units));

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

  const uid = $props.id();
  const zoneId = `${uid}-zone`;

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
  <Section id="about" title={section.about.title} icon="person">
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

  <!-- How long does this run? One column, asked in the order the question is asked: in this zone,
       from here, until here, for this long — then the picture of what that comes to. The two-column
       band that used to sit here could not be made honest at the width this page actually has: at
       788px it gave each `datetime-local` 212px, and Chrome draws the seconds and drops the AM/PM
       there, so at the site's widest an en-US reader setting 22:39 read back `10:39:14`. -->
  <Section id="validity" title={section.validity.title} icon="clock">
    <div class="validity__window">
      {#if zones.length > 1}
        <!-- The frame, before the two things it frames. A wall time whose zone arrives after it has
             already been read has already been read wrong once. Labelled rather than bare: with no
             section note above it this is the first thing under the heading, and a lone select
             would have to be understood from its own value. `control.timezone` was already its
             accessible name, so promoting it to a visible label costs no string. -->
        <div class="validity__zone">
          <Icon name="globe" size={16} tone="faint" />
          <label class="field__label" for={zoneId}>{m.control.timezone}</label>
          <select
            class="input validity__zone-select"
            id={zoneId}
            value={zone}
            onchange={(event) => (zone = event.currentTarget.value)}
          >
            {#each zones as option (option)}
              <option value={option}>{option}</option>
            {/each}
          </select>
        </div>
      {/if}

      <div class="validity__instants">
        <InstantField
          label={m.field.label.issuedAt}
          path="issued_at"
          value={configuration.issued_at}
          {zone}
          echo={false}
          onchange={(value) => (configuration.issued_at = value)}
        />
        <!-- The hint stays under this one picker: it is a fact about `expires_at` alone — the day
             joining closes, which the label does not say — and under both it would be false. -->
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
    </div>

    <div class="validity__each">
      <!-- No box. One hour to one year crosses hours into days, so no single word names both ends,
           and a box here was a bare `24` beside `1 day` with nothing saying the 24 was hours.
           Ninety days off the ladder rather than `2160` typed into a field. -->
      <RangeField
        label={m.field.label.durationHours}
        hint={m.field.hint.duration}
        path="duration_hours"
        value={configuration.duration_hours}
        unit={S.duration_hours}
        icon="person"
        onchange={(value) => (configuration.duration_hours = value)}
      />
    </div>

    <!-- Under the answers, because it summarises them. It says three things the numbers do not:
         where `now` falls against the window, how one participant's stretch compares with the
         enrolment window, and `issued >= expires` one beat before the field issue fires. -->
    <WindowStrip
      issuedAt={configuration.issued_at}
      expiresAt={configuration.expires_at}
      durationHours={configuration.duration_hours}
      {now}
      {units}
    />

    <Note icon="info" tone="plain" text={m.researcher.study.note.irrevocable} />
  </Section>

  <!-- A root key, so a section of its own rather than a control inside another section's. It sits
       after the window because both are questions about the study itself, and before the data
       because what is collected does not depend on who the file goes to. -->
  <Section
    id="identity"
    path="assigned_participant_id"
    title={section.identity.title}
    icon="participant"
  >
    <IdentityField {draft} {m} />
  </Section>

  <Section id="collectors" path="collectors" title={section.collectors.title} icon="sources">
    <!-- Said once for the section, because it is the same sentence on all twelve cards. Every
         必要 control points at this `id`, so the consequence is still one hop from the decision,
         and it carries the mark an unpressed control carries. -->
    <Note
      id="collectors-required"
      icon="participant"
      tone="plain"
      text={m.researcher.study.note.required}
    />

    <div class="collectors">
      {#each COLLECTOR_ORDER as id (id)}
        <CollectorCard
          {id}
          config={draft.collector(id)}
          path={draft.collectorPath(id)}
          {m}
          locale={i18n.locale}
          scales={S}
          onenable={(which) => draft.enableCollector(which)}
          ondisable={(which) => draft.disableCollector(which)}
          onrequired={(which, required) => draft.setCollectorRequired(which, required)}
          onaddprofile={(which) => draft.addCollectorProfile(which)}
          onrenameprofile={(which, previous, next) => draft.renameCollectorProfile(which, previous, next)}
          onremoveprofile={(which, profileId) => draft.removeCollectorProfile(which, profileId)}
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
      unit={S.maximum_local_bytes}
      onquota={(value) => (configuration.storage.maximum_local_bytes = value)}
    />
  </Section>

  <Section id="consent" title={section.consent.title} icon="seal">
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

  <Section id="traffic-shaping" title={i18n.locale === 'zh-TW' ? 'App 資料傳輸調整' : 'App data-transfer adjustment'} icon="connection">
    <TrafficShapingEditor {draft} locale={i18n.locale} />
  </Section>

  <Section id="interventions" title={section.interventions.title} icon="bell">
    <InterventionEditor {draft} {m} locale={i18n.locale} />
  </Section>

  <Section id="resource-automations" title={i18n.locale === 'zh-TW' ? '條件與資源規則' : 'Conditions and resource rules'} icon="clock">
    <ResourceAutomationEditor {draft} locale={i18n.locale} />
  </Section>

  <Section id="participant-preview" title={i18n.locale === 'zh-TW' ? '參與者預覽' : 'Participant preview'} icon="participant">
    <ParticipantStudyPreview {draft} />
  </Section>

  <Section id="simulator" title={i18n.locale === 'zh-TW' ? '合成事件模擬' : 'Synthetic trace simulator'} icon="motion">
    <SyntheticTraceSimulator {draft} locale={i18n.locale} />
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
        unit={S.upload_interval_minutes}
        icon="clock"
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
  /* Rows, not columns. The two-column band had to choose between the pickers and the presets at a
     788px section column and chose the pickers' segments: at 212px Chrome finishes the seconds and
     drops the AM/PM, so the widest this site ever gets was the width at which 22:39 read as
     10:39:14. A column each gives the pair 374px and the presets one row, and removes the
     breakpoint rather than moving it — 1280, 1024, 900, 768 and 360 are now one layout. */
  .validity__window {
    display: flex;
    flex-direction: column;
    gap: var(--sp-5);
  }

  /* Named, beside its value, on the same left edge as the two labels below it. Stacked it would
     look like a third answer; it is not an answer, it is what the other two are read inside. The
     start padding matches the lead a `.field` head carries, so the three labels line up. */
  .validity__zone {
    display: flex;
    align-items: center;
    gap: var(--sp-5);
    padding-inline-start: calc(var(--sp-5) + var(--line-solid));
  }

  .validity__zone-select {
    inline-size: auto;
  }

  /* Flex for the reason it was flex before: the pair shares a line where both can be read whole and
     takes a line each where they cannot. 16.5rem is measured, not guessed — 14px of shell lead plus
     the 244px at which Chrome finishes drawing the meridiem. */
  .validity__instants {
    display: flex;
    flex-wrap: wrap;
    gap: var(--sp-5);
  }

  .validity__instants :global(.field) {
    flex: 1 1 16.5rem;
    min-inline-size: 0;
  }

  /* The file's clock above, one participant's clock below. One rule doing two jobs: it ends the
     zone's reach, and it says these are not the same clock — which is what the old vertical rule
     said at wide widths and this same horizontal one already said at narrow ones. */
  .validity__each {
    border-block-start: var(--line-hair) solid var(--rule);
    padding-block-start: var(--sp-6);
  }
</style>
