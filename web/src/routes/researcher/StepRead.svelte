<script lang="ts">
  /**
   * The last step, and the only one that reads.
   *
   * Everything before this composes a file and hands it out. This takes one back: the `.partexp` a
   * participant exported, opened here, in the tab, with nothing sent anywhere. The site's footer
   * promises exactly that of every other step, and this is the step where the promise is worth the
   * most, because what is on screen is somebody's data rather than the researcher's own draft.
   *
   * Three inputs, the same three `researcher-tools decrypt` takes, and for the same reason: the
   * configuration is not a convenience. Its identifiers are the context the body was sealed under,
   * and none of them is in the file's cleartext, so a personalised study needs *that* participant's
   * configuration or nothing opens. Two of the three can come from this tab when this tab signed
   * the study, and neither is ever required — a researcher opening a bundle next month has files
   * and nothing else, which is the case the file targets exist for.
   *
   * The summary is the bundle's own numbers, in the bundle's own words. What it does *not* do is
   * read the events: `event_type` and `fields` are a source's private vocabulary, `fields`
   * values are strings even when they look like numbers, and a page that started interpreting them
   * would be a second, wrong analysis tool. So: how many, over what span, from which sources — and
   * then the whole document, exactly as the phone wrote it, to read and to take away.
   */
  import Button from '$lib/ui/Button.svelte';
  import BytePane from '$lib/ui/BytePane.svelte';
  import Disclosure from '$lib/ui/Disclosure.svelte';
  import DropTarget from '$lib/ui/DropTarget.svelte';
  import Icon from '$lib/ui/Icon.svelte';
  import LiveRegion from '$lib/ui/LiveRegion.svelte';
  import Note from '$lib/ui/Note.svelte';
  import { groupDigits } from '$lib/ui/format';
  import type { IconRef } from '$lib/ui/icons';
  import { openBundle } from '$lib/particeps/bundle';
  import { MAXIMUM_CONFIGURATION_BYTES, isCollectorId, COLLECTOR_ORDER } from '$lib/particeps/types';
  import type { CollectorId, StudyConfiguration } from '$lib/particeps/types';
  import { download } from './artifacts';
  import { hpkeKeyPairFromPrivate } from './keys';
  import { parseConfiguration } from './parse';
  import type { Draft } from './draft.svelte';
  import type { Messages } from '$lib/i18n/types';

  interface Props {
    draft: Draft;
    m: Messages;
  }

  let { draft, m }: Props = $props();

  /**
   * The three staged inputs. Component state, not draft state, and deliberately: a `File` a
   * researcher picked is not part of the study they are authoring, and the one thing that has to
   * outlive this component — the decrypted document — is the one thing the draft holds.
   */
  let bundleBytes = $state.raw<Uint8Array | null>(null);
  let bundleName = $state('');
  let configuration = $state.raw<StudyConfiguration | null>(null);
  let configurationName = $state('');
  let privateKey = $state.raw<string | null>(null);
  let privateKeyName = $state('');

  let failure = $state('');
  let working = $state(false);

  /**
   * Whether this tab's own configuration and export key are worth offering. Both, or neither: an
   * export key's public half only ever reaches a phone inside a signed configuration, so a key held
   * here with nothing signed is a key no bundle can have been sealed to. `draft.envelope` is null
   * while the document is stale, which is the same statement about a signature that no longer
   * describes what is on screen.
   */
  const session = $derived(draft.envelope !== null && draft.hpke.kind === 'held');

  const ready = $derived(bundleBytes !== null && configuration !== null && privateKey !== null);

  const opened = $derived(draft.bundle);
  const experiment = $derived(opened?.document.experiment ?? null);
  const events = $derived(experiment?.commits.flatMap((commit) => commit.events) ?? []);

  /**
   * Guarded like its two siblings. `DropTarget` calls `onfile` without awaiting, so a rejection
   * here is an unhandled promise and the reader is told nothing — and it is reachable: a file
   * larger than one `ArrayBuffer` makes `arrayBuffer()` reject outright.
   */
  async function takeBundle(file: File) {
    try {
      bundleBytes = new Uint8Array(await file.arrayBuffer());
      bundleName = file.name;
      failure = '';
    } catch {
      bundleBytes = null;
      bundleName = '';
      failure = m.error.bundle.too_large;
    }
  }

  /** `.partcfg`, canonical JSON, or a draft — `parseConfiguration` unwraps the envelope itself. */
  async function takeConfiguration(file: File) {
    try {
      configuration = parseConfiguration(new Uint8Array(await file.arrayBuffer()));
      configurationName = file.name;
      failure = '';
    } catch {
      configuration = null;
      configurationName = '';
      failure = m.error.draft;
    }
  }

  /**
   * The same strict raw-key import the Keys step runs, so malformed or padded base64 fails before
   * any bundle decryption is attempted.
   */
  async function takePrivateKey(file: File) {
    try {
      privateKey = hpkeKeyPairFromPrivate(await file.text()).privateKey;
      privateKeyName = file.name;
      failure = '';
    } catch {
      privateKey = null;
      privateKeyName = '';
      failure = m.error.keyFile;
    }
  }

  function useSessionConfiguration() {
    configuration = draft.document;
    configurationName = m.file.canonical;
    failure = '';
  }

  function useSessionKey() {
    if (draft.hpke.kind !== 'held') return;
    privateKey = draft.hpke.material.privateKey;
    privateKeyName = m.file.exportPrivate;
    failure = '';
  }

  /**
   * The previous result is dropped before the attempt rather than after it. A failed open beside a
   * summary from the last file is a screen that answers a question nobody asked, and on this step
   * the summary is somebody's data — the wrong one on screen is worse than none.
   */
  async function open() {
    if (!bundleBytes || !configuration || !privateKey || working) return;
    working = true;
    draft.holdBundle(null);
    failure = '';
    try {
      const result = await openBundle(bundleBytes, configuration, privateKey);
      if (result.ok) draft.holdBundle(result.bundle);
      else failure = m.error.bundle[result.failure];
    } finally {
      working = false;
    }
  }

  /** Named after the install it came from, which is the only thing that keeps two of them apart. */
  function save() {
    if (!opened || !experiment) return;
    download(
      new TextEncoder().encode(opened.text),
      `${experiment.participant_instance_id}.json`,
      'application/json'
    );
  }

  /** `CollectorGlyphs.kt`'s assignment, as `CollectorCard` draws it. */
  const GLYPHS: Record<CollectorId, IconRef> = {
    'app_lifecycle.v1': 'app',
    'accelerometer.v1': 'motion',
    'battery_state.v1': 'data-volume',
    'temporal_context.v1': 'clock',
    'gyroscope.v1': 'motion',
    'ambient_light.v1': 'app',
    'proximity.v1': 'connection',
    'network_state.v1': 'connection',
    'network_usage.v1': 'data-volume',
    'usage_events.v1': 'screen',
    'location.v1': 'location',
    'keyboard_touch.v1': 'keyboard'
  };

  /**
   * How the events divide, in the codec's collector order so two bundles from one study list their
   * sources the same way. A `source_id` this build does not know keeps its wire name and a
   * neutral mark: the phone owns this vocabulary, and an unrecognised source is a fact about the
   * file rather than a reason to hide a row.
   */
  const sources = $derived.by(() => {
    if (!experiment) return [];
    const tally = new Map<string, number>();
    for (const event of events) {
      tally.set(event.source_id, (tally.get(event.source_id) ?? 0) + 1);
    }
    const rank = (id: string) =>
      isCollectorId(id) ? COLLECTOR_ORDER.indexOf(id) : COLLECTOR_ORDER.length;
    return [...tally]
      .map(([id, count]) => ({
        id,
        count,
        icon: isCollectorId(id) ? GLYPHS[id] : ('sources' as IconRef),
        name: isCollectorId(id) ? m.collector[id].name : id
      }))
      .sort((left, right) => rank(left.id) - rank(right.id) || left.id.localeCompare(right.id));
  });

  /**
   * The widest and narrowest instants in the file, not the first and last rows. Sequence order is
   * the device's own counter and a phone's wall clock can move under it, so the extremes are the
   * honest answer to "what period is this".
   */
  const span = $derived.by(() => {
    if (!experiment || events.length === 0) return null;
    let low: bigint | null = null;
    let high: bigint | null = null;
    for (const event of events) {
      const at = BigInt(event.observed_time.wall_time_utc_millis);
      if (low === null || at < low) low = at;
      if (high === null || at > high) high = at;
    }
    return { from: instant(String(low)), to: instant(String(high)) };
  });

  /**
   * The same shape `issued_at` and `expires_at` take in the configuration, and an identifier rather
   * than prose: a locale-formatted date would read differently in the two languages for a value
   * that is the same instant in both, and this one gets compared against a log.
   */
  function instant(millis: string): string {
    const numeric = Number(millis);
    const at = new Date(numeric);
    return Number.isNaN(at.getTime()) ? String(millis) : `${at.toISOString().slice(0, 19)}Z`;
  }

  /**
   * What the device has counted in its lifetime, which is not always what this file carries: a
   * scheduled upload sends a slice. Shown as a denominator only when the two differ, because
   * `n / total` already means "part of" everywhere else on this page and a denominator equal to the
   * numerator says nothing.
   */
  const lifetime = $derived(experiment ? BigInt(experiment.lifetime_data_event_count) : 0n);
  const partial = $derived(
    experiment !== null && lifetime > BigInt(experiment.event_count)
  );
  const firstEventSequence = $derived(events[0]?.sequence_number ?? null);
  const lastEventSequence = $derived(events.at(-1)?.sequence_number ?? null);
</script>

<div class="stack stack--loose">
  <Note icon="info" tone="plain" text={m.researcher.read.lede} />

  <div class="inputs">
    <div class="inputs__row">
      <!-- No `filename` hint, because there is nothing honest to put there: a bundle is named by
           the phone that wrote it, and this is the one input whose name nobody here chose. -->
      <div class="row row--tight">
        <DropTarget
          label={m.researcher.read.bundle}
          accept=".partexp"
          icon="package"
          onfile={takeBundle}
          testid="read-bundle"
        />
      </div>
      {#if bundleName}<Note icon="import" tone="plain" text={bundleName} />{/if}
    </div>

    <!-- Both borrow the label of the step that made the file, so a researcher looking for the thing
         they downloaded three weeks ago is looking for the same words they downloaded it under. -->
    <div class="inputs__row">
      <div class="row row--tight">
        <DropTarget
          label={m.researcher.sign.canonical}
          filename={m.file.canonical}
          accept=".json,.partcfg,application/json"
          icon="json"
          onfile={takeConfiguration}
          testid="read-configuration"
        />
        {#if session}
          <Button
            variant="ghost"
            icon="document"
            label={m.researcher.read.session}
            onclick={useSessionConfiguration}
            testid="read-configuration-session"
          />
        {/if}
      </div>
      {#if configurationName}<Note icon="import" tone="plain" text={configurationName} />{/if}
    </div>

    <div class="inputs__row">
      <div class="row row--tight">
        <DropTarget
          label={m.researcher.keys.export.title}
          filename={m.file.exportPrivate}
          accept=".key,text/plain"
          icon="key-open"
          onfile={takePrivateKey}
          testid="read-key"
        />
        {#if session}
          <Button
            variant="ghost"
            icon="key"
            label={m.researcher.read.session}
            onclick={useSessionKey}
            testid="read-key-session"
          />
        {/if}
      </div>
      {#if privateKeyName}<Note icon="import" tone="plain" text={privateKeyName} />{/if}
    </div>
  </div>

  <div class="row">
    <Button
      variant="primary"
      icon="unlock"
      label={m.action.open}
      disabled={!ready || working}
      onclick={open}
      testid="read-open"
    />
  </div>

  <!-- Every one of these names which of the three files to change. Assertive, because a researcher
       who pressed Open and got nothing has no other way to find out why. -->
  {#if failure}
    <div role="alert" aria-live="assertive">
      <Note icon="alert" tone="danger" text={failure} />
    </div>
  {/if}

  {#if opened && experiment}
    <!-- No heading of its own: the step panel above already says `Read`, and the figures name
         themselves. A second `Read` here would be one thing titled twice. -->
    <div class="stack stack--loose" data-testid="read-summary">
      <dl class="figures" data-testid="read-figures">
        <div class="figure">
          <dt>{m.researcher.read.events}</dt>
          <dd class="figure__value">{groupDigits(experiment.event_count)}</dd>
        </div>
        <!-- The separator before the lifetime total is a non-breaking space, not a literal one:
             Svelte strips whitespace at the start of an element's children, which rendered
             `501–505/ 900`. An export carrying no events has no range to state — the device writes
             first 1 and last 0 for that, and drawing it gave a backwards `1–0`. -->
        <div class="figure">
          <dt>{m.researcher.read.window}</dt>
          <dd class="figure__value">
            {#if firstEventSequence === null || lastEventSequence === null}
              —
            {:else}{groupDigits(firstEventSequence)}–{groupDigits(
                lastEventSequence
              )}{#if partial}<span class="figure__of">&nbsp;/ {groupDigits(lifetime)}</span>{/if}{/if}
          </dd>
        </div>
        <div class="figure">
          <dt>{m.researcher.read.commits}</dt>
          <dd class="figure__value">{groupDigits(experiment.commit_count)}</dd>
        </div>
        <div class="figure">
          <dt>{m.researcher.read.span}</dt>
          <dd class="figure__stack">
            {#if span}
              <span>{span.from}</span>
              <span>{span.to}</span>
            {:else}
              <span>—</span>
            {/if}
          </dd>
        </div>
      </dl>

      <dl class="identity" data-testid="read-identity">
        <dt>{m.field.label.experimentId}</dt>
        <dd>{experiment.experiment_id}</dd>
        <dt>{m.field.label.configurationId}</dt>
        <dd>{experiment.configuration_id}</dd>
        <dt>{m.researcher.read.instance}</dt>
        <dd>{experiment.participant_instance_id}</dd>
        <!-- Absent in an anonymous study, and absent here too: an empty row would read as a code
             that failed to load rather than as a study that never assigned one. -->
        {#if experiment.assigned_participant_id}
          <dt>{m.intervention.assignedId}</dt>
          <dd>{experiment.assigned_participant_id}</dd>
        {/if}
        <dt>{m.researcher.read.state}</dt>
        <dd>{experiment.state}</dd>
        <dt>{m.researcher.read.exported}</dt>
        <dd>{instant(opened.document.exported_at_utc_millis)}</dd>
      </dl>

      {#if sources.length > 0}
        <ul class="sources" data-testid="read-sources">
          {#each sources as source (source.id)}
            <li class="source">
              <Icon name={source.icon} size={22} tone="accent" />
              <span class="source__name">{source.name}</span>
              <span class="source__count">{groupDigits(source.count)}</span>
            </li>
          {/each}
        </ul>
      {:else}
        <Note icon="info" tone="plain" text={m.researcher.read.none} />
      {/if}

      <!-- The pane tokenises whatever it is handed, character by character, and draws a gauge
           against a megabyte. Past that the gauge means nothing and the tokeniser is the slowest
           thing on the page, so the document is offered as a file instead. It is open either way. -->
      {#if opened.bytes <= MAXIMUM_CONFIGURATION_BYTES}
        <Disclosure label={m.researcher.read.json} icon="json" testid="read-json">
          <BytePane
            text={opened.text}
            bytes={opened.bytes}
            copyLabel={m.action.copy}
            copiedLabel={m.status.copied}
            testid="read-preview"
          />
        </Disclosure>
      {:else}
        <Note icon="info" tone="plain" text={m.researcher.read.large} />
      {/if}

      <div class="row">
        <Button
          variant="quiet"
          icon="download"
          label={m.action.download}
          onclick={save}
          testid="read-download"
        />
        <LiveRegion text={m.researcher.read.opened} />
      </div>
    </div>
  {/if}
</div>

<style>
  .inputs {
    display: flex;
    flex-direction: column;
    gap: var(--sp-5);
    max-inline-size: var(--measure);
  }

  .inputs__row {
    display: flex;
    flex-direction: column;
    gap: var(--sp-3);
  }

  /* The headline. Four numbers a researcher reads before anything else, sized so they are read as
     numbers rather than as fields — the labels under them are the fine print, which is the inverse
     of every other plaque on this page and is the point. */
  .figures {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr));
    gap: var(--sp-5);
    padding: var(--sp-6);
    background: var(--surface-sunk);
    border-radius: var(--r-panel);
  }

  .figure {
    display: flex;
    flex-direction: column;
    gap: var(--sp-2);
    min-inline-size: 0;
  }

  .figure dt {
    font-size: var(--type-fine);
    color: var(--ink-faint);
  }

  .figure__value {
    font-family: var(--font-mono);
    font-size: var(--type-title);
    font-variant-numeric: tabular-nums;
  }

  /* The window's denominator, when the device counted more than this file carries. Fine and faint
     because it is the same `n / total` the byte pane and the hand-off columns already draw. */
  .figure__of {
    font-size: var(--type-fine);
    color: var(--ink-faint);
  }

  .figure__stack {
    display: flex;
    flex-direction: column;
    font-family: var(--font-mono);
    font-size: var(--type-fine);
    font-variant-numeric: tabular-nums;
  }

  /* The sign step's plaque, exactly: what it is, and what it is called. */
  .identity {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr);
    gap: var(--sp-4) var(--sp-6);
    align-items: baseline;
    padding: var(--sp-6);
    background: var(--surface-sunk);
    border-radius: var(--r-panel);
  }

  .identity dt {
    font-size: var(--type-fine);
    color: var(--ink-faint);
  }

  .identity dd {
    font-family: var(--font-mono);
    font-size: var(--type-fine);
    overflow-wrap: anywhere;
  }

  .sources {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(15rem, 1fr));
    gap: var(--sp-4);
    list-style: none;
    padding: 0;
    margin: 0;
  }

  .source {
    display: flex;
    align-items: center;
    gap: var(--sp-4);
    padding: var(--sp-4) var(--sp-5);
    background: var(--surface-sunk);
    border-radius: var(--r-field);
    min-inline-size: 0;
  }

  .source__name {
    flex: 1 1 auto;
    min-inline-size: 0;
  }

  .source__count {
    font-family: var(--font-mono);
    font-variant-numeric: tabular-nums;
    color: var(--ink-soft);
  }
</style>
