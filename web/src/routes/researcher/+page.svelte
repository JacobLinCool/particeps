<script lang="ts">
  /**
   * Prepare a study: two key pairs, a configuration, a signature, and four files — in this tab,
   * with nothing installed and nothing sent anywhere. Then, weeks later, open what comes back.
   *
   * The rail is the app's, redrawn. On the phone a dot means *where you are in a sequence*, because
   * the participant's five steps are a disclosure gate and are forward-only. Here a dot means
   * *whether that step's output exists and is valid*: authoring is iterative, every step is
   * reachable at any time, and nothing on this page is a disclosure anyone must be made to read.
   *
   * The fifth step is the exception that proves the rule, and `stateLabel` is where it says so: it
   * owns no part of the document and produces no file, so its dot cannot mean what the other four
   * mean. What it means is that a participant's export is decrypted and on screen, in this tab.
   */
  import { tick, untrack } from 'svelte';
  import { base } from '$app/paths';
  import { beforeNavigate, goto } from '$app/navigation';
  import Button from '$lib/ui/Button.svelte';
  import ConfirmDialog from '$lib/ui/ConfirmDialog.svelte';
  import DropTarget from '$lib/ui/DropTarget.svelte';
  import LiveRegion from '$lib/ui/LiveRegion.svelte';
  import Note from '$lib/ui/Note.svelte';
  import Rail from '$lib/ui/Rail.svelte';
  import SiteFooter from '$lib/ui/SiteFooter.svelte';
  import SiteHeader from '$lib/ui/SiteHeader.svelte';
  import StepPanel from '$lib/ui/StepPanel.svelte';
  import { resolveIssueMessage, setFieldSource } from '$lib/ui/field-context';
  import type { StepDef } from '$lib/ui/types';
  import { i18n } from '$lib/ui/i18n.svelte';

  import StepKeys from './StepKeys.svelte';
  import StepStudy from './StepStudy.svelte';
  import StepSign from './StepSign.svelte';
  import StepFiles from './StepFiles.svelte';
  import StepRead from './StepRead.svelte';
  import {
    ARTIFACTS,
    artifactBytes,
    download,
    type ArtifactId,
    type ArtifactNames
  } from './artifacts';
  import { createDraft } from './draft.svelte';
  import { STEPS, stepForPath, type StepId } from './steps';
  import { units } from './units';
  import { keysetJson } from '$lib/adc/canonical';

  const REPOSITORY = 'https://github.com/JacobLinCool/android-data-collector';
  const GUIDES = `${REPOSITORY}/blob/main/docs`;

  const draft = createDraft();

  // Idempotent, and `LocaleMenu` calls it too. Said here as well so the page's own strings resolve
  // against the reader's locale whether or not the header happens to be the thing that mounts first.
  $effect(() => i18n.start());

  const m = $derived(i18n.m);
  const u = $derived(units(i18n.m, i18n.locale));

  let step = $state<StepId>('study');
  let direction = $state<1 | -1>(1);
  let failure = $state('');
  let keyFailure = $state('');
  let loaded = $state('');
  let confirming = $state(false);
  /** Where a cancelled navigation was heading, held until the reader says the key is safe. */
  let departure = $state<string | null>(null);

  /**
   * The Keys step arrives done. A researcher has no basis for choosing between two key pairs they
   * were always going to need, so both are made on arrival rather than on a click, and both names
   * derive from the key material. Through `attempt`, so a browser without the primitives lands in
   * the same `failure` slot it would have landed in on click — with the import path underneath as
   * the working fallback.
   *
   * `untrack` because this is a one-shot: `ensureKeys` reads both key states, and without it the
   * effect would re-run the moment it wrote them.
   */
  $effect(() => untrack(() => attempt(() => draft.ensureKeys(), false)));

  /**
   * Leaving is the same act as `Start over` — the keys are gone either way — so it asks the same
   * question. `Start over` already confirmed; the brand link, the page switcher, the footer, and
   * the tab's own close button did not, and the inconsistency was the tell.
   *
   * Two mechanisms because they cover different exits. `beforeNavigate` catches every link on the
   * page and can be cancelled, so it gets the page's own dialog. A tab close cannot be intercepted
   * with anything but the browser's native prompt, which is what `beforeunload` asks for.
   */
  $effect(() => {
    if (!draft.keysAtRisk) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  });

  let leaving = false;
  beforeNavigate((navigation) => {
    if (leaving || !draft.keysAtRisk || navigation.willUnload) return;
    const to = navigation.to?.url.href;
    if (!to) return;
    navigation.cancel();
    departure = to;
  });

  /** Which arrow key walks the rail. The layout itself is a media query in `surfaces.css`. */
  let wide = $state(true);
  $effect(() => {
    const query = window.matchMedia('(min-width: 900px)');
    const apply = () => (wide = query.matches);
    apply();
    query.addEventListener('change', apply);
    return () => query.removeEventListener('change', apply);
  });

  /**
   * Field issues arrive through context rather than as props, so thirty controls do not thread a
   * validation array between them. The source has already decided visibility: a field marks itself
   * on blur, or everywhere at once the moment a sign is attempted.
   */
  setFieldSource({
    issues: (path) =>
      draft
        .visibleIssues(path)
        .map((issue) => ({ path: issue.path, code: issue.code, params: issue.bounds })),
    touch: (path) => draft.touch(path),
    message: (issue) =>
      resolveIssueMessage(i18n.m.issue as unknown as Record<string, unknown>, issue).text,
    advisory: (path) => advisoryFor(path)
  });

  const advisoryFor = (_path: string): null => null;

  /**
   * Counts are live from the first thing the researcher does, and silent before it. A page opened
   * a second ago has not got nine problems — it has nine fields nobody has reached yet, and a rail
   * that greets a reader with two red badges is a rail they learn to stop reading.
   *
   * The keys step no longer speaks for this: it holds two keys from the second second, so its state
   * says nothing about whether the researcher has done anything. What does say so is an untouched
   * study and no attempt to sign.
   */
  const pristine = $derived(draft.stateOf('study') === 'empty' && !draft.attempted);

  const rail = $derived<StepDef[]>(
    STEPS.map((definition) => ({
      id: definition.id,
      label: m.step[definition.id],
      icon: definition.icon,
      state: draft.stateOf(definition.id),
      count: pristine ? 0 : draft.issuesByStep[definition.id].length
    }))
  );

  /**
   * `id` is threaded through because one state means different things on different steps: `partial`
   * on the keys step is two secrets that are not on disk yet, which is the same sentence `blocked`
   * used to carry there, while `partial` anywhere else is ordinary progress.
   */
  function stateLabel(state: string, count: number, id: string): string {
    if (count > 0 && !pristine) return m.researcher.sign.blocked(count);
    // The read step can never be blocked and has nothing to fix, so `Nothing to fix` on its dot
    // would be an answer to a question it does not host. What its `complete` means is one fact.
    if (id === 'read') return state === 'complete' ? m.researcher.read.opened : m.control.progress;
    if (state === 'blocked') return m.researcher.files.keep;
    if (state === 'partial' && id === 'keys') return m.researcher.files.keep;
    if (state === 'complete') return m.status.clean;
    return m.control.progress;
  }

  const stepIndex = $derived(STEPS.findIndex((definition) => definition.id === step));

  function go(next: string) {
    const order = STEPS.map((definition) => definition.id);
    direction = order.indexOf(next as StepId) >= order.indexOf(step) ? 1 : -1;
    step = next as StepId;
  }

  /** An issue row is a button that goes to the step, scrolls the control in, and focuses it. */
  async function jump(path: string) {
    go(stepForPath(path));
    await tick();
    // Four of the paths `validate` emits belong to no single control — `collectors`,
    // `signer.public_key`, `export.tink_hpke_public_keyset`, and the low thumb of the location
    // dual range. `data-issue-host` is what a section or a card puts up in a field's place, so an
    // issue row never changes the step and then scrolls to nothing.
    const host = document.querySelector<HTMLElement>(
      `[data-testid="field-${path}"], [data-issue-host~="${path}"]`
    );
    host?.scrollIntoView({ block: 'center', behavior: 'smooth' });
    host?.querySelector<HTMLElement>('input, textarea, select, button')?.focus();
  }

  /** A self-check failure and a refused envelope have the same consequence — nothing was
   *  written — and the catalogue says exactly that once. */
  function sign() {
    const outcome = draft.sign();
    failure = outcome === 'signed' ? '' : m.error.signing;
    if (outcome === 'signed') go('files');
  }

  /**
   * A private key file is named after the key inside it, so the file on disk *is* the string
   * `researcher-tools sign --key-id` wants. The other two keep their catalogue names: they are one
   * document, and the document is named inside itself.
   */
  const names = $derived<ArtifactNames>({
    signerKeyId: draft.signerKeyId,
    exportKeyId: draft.exportKeyId
  });

  function save(id: ArtifactId) {
    const bytes = artifactBytes(id, {
      signingPrivate:
        draft.signing.kind === 'held' ? draft.signing.material.privatePkcs8Base64 : null,
      hpkePrivate: draft.hpke.kind === 'held' ? keysetJson(draft.hpke.material.privateKeyset) : null,
      canonical: draft.canonicalBytes,
      envelope: draft.envelope
    });
    if (!bytes) return;
    const definition = ARTIFACTS.find((artifact) => artifact.id === id);
    if (!definition) return;
    download(bytes, definition.filename(i18n.m, names), definition.mime);
    draft.markSent(id);
  }

  /**
   * Both generators and both imports go through here. Every one of them can fail — key generation
   * needs a secure context, and a dropped file is whatever the reader dropped — and the failure has
   * to land on the step that caused it. Silently doing nothing is the one response a page holding
   * irreplaceable keys cannot give, and it was the response until this existed.
   */
  function attempt(act: () => void, onFile: boolean) {
    try {
      act();
      keyFailure = '';
    } catch {
      keyFailure = onFile
        ? m.error.keyFile
        : typeof window !== 'undefined' && !window.isSecureContext
          ? m.error.insecureContext
          : m.error.unsupportedBrowser;
    }
  }

  /** A dropped file that cannot be read says so; it never silently does nothing. */
  async function open(file: File) {
    try {
      draft.load(new Uint8Array(await file.arrayBuffer()));
      loaded = file.name;
      failure = '';
      go('study');
    } catch {
      loaded = '';
      failure = m.error.draft;
      go('sign');
    }
  }
</script>

<svelte:head>
  <title>{m.researcher.title} · {m.app.name}</title>
  <meta name="description" content={m.researcher.lede} />
</svelte:head>

<div class="page">
  <a class="skip" href="#main">{m.action.skip}</a>

  <SiteHeader
    current="researcher"
    home="{base}/"
    researcherHref="{base}/researcher/"
    participantHref="{base}/participant/"
  >
    <!-- A real button that opens a file picker; drop is an enhancement on top of it. This is what
         makes the cross-language workflow possible: open the first signed configuration, change the
         prose, issue a new configuration ID under the same signer. -->
    {#snippet trailing()}
      <span class="load">
        <DropTarget
          label={m.action.importDraft}
          accept=".json,.adccfg,application/json"
          onfile={open}
          testid="load-configuration"
        />
      </span>
    {/snippet}
  </SiteHeader>

  <main id="main" class="wrap workspace">
    <Rail
      steps={rail}
      current={step}
      orientation={wide ? 'vertical' : 'horizontal'}
      position={m.control.stepPosition}
      label={m.researcher.title}
      {stateLabel}
      onnavigate={go}
    />

    <div class="workspace__panel">
      {#if step === 'keys'}
        <StepPanel id="keys" title={m.step.keys} icon="key" {direction}>
          <p class="lede">{m.researcher.lede}</p>
          <StepKeys {draft} {m} failure={keyFailure} onsave={save} {attempt} />
        </StepPanel>
      {:else if step === 'study'}
        <StepPanel id="study" title={m.step.study} icon="document" {direction}>
          {#if loaded}
            <Note icon="import" tone="plain" text={loaded} />
          {/if}
          <StepStudy {draft} {m} units={u} />
        </StepPanel>
      {:else if step === 'sign'}
        <StepPanel id="sign" title={m.step.sign} icon="seal" {direction}>
          <StepSign {draft} {m} {failure} onjump={jump} onsign={sign} />
        </StepPanel>
      {:else if step === 'files'}
        <StepPanel id="files" title={m.step.files} icon="send" {direction}>
          <StepFiles {draft} {m} onsave={save} onsign={() => go('sign')} />
        </StepPanel>
      {:else}
        <StepPanel id="read" title={m.step.read} icon="unlock" {direction}>
          <StepRead {draft} {m} />
        </StepPanel>
      {/if}

      <!-- A forward affordance. The rail is reachable at any time, but it says where things stand
           rather than what to do next, and a researcher who has just generated two keys was left
           looking at four glyphs with no arrow among them. -->
      <div class="row row--between" data-print="hide">
        <Button
          variant="ghost"
          icon="trash"
          label={m.action.startOver}
          onclick={() => (confirming = true)}
          testid="reset"
        />
        <LiveRegion text={loaded} />
        <div class="row row--tight">
          {#if stepIndex > 0}
            <Button
              variant="quiet"
              label={m.action.back}
              onclick={() => go(STEPS[stepIndex - 1].id)}
              testid="step-back"
            />
          {/if}
          {#if stepIndex < STEPS.length - 1}
            <Button
              variant="quiet"
              iconEnd="arrow-right"
              label={m.action.next}
              onclick={() => go(STEPS[stepIndex + 1].id)}
              testid="step-next"
            />
          {/if}
        </div>
      </div>
    </div>
  </main>

  <SiteFooter
    note={m.researcher.how.local.title}
    links={[
      { href: `${GUIDES}/researcher-guide.md`, label: m.link.researcherGuide, external: true },
      { href: `${GUIDES}/participant-guide.md`, label: m.link.participantGuide, external: true },
      { href: `${GUIDES}/threat-model.md`, label: m.link.threatModel, external: true },
      { href: REPOSITORY, label: m.link.source, external: true }
    ]}
    linksLabel={m.researcher.title}
    aside={{ href: `${base}/participant/`, label: m.app.nav.participant }}
  />
</div>

<ConfirmDialog
  open={confirming}
  title={m.confirm.startOver.title}
  body={m.confirm.startOver.body}
  confirmLabel={m.action.confirm}
  cancelLabel={m.action.cancel}
  onconfirm={() => {
    draft.reset();
    confirming = false;
    loaded = '';
    failure = '';
    go(STEPS[0].id);
  }}
  oncancel={() => (confirming = false)}
/>

<!-- Same question, same words: leaving with a held key is the same loss `Start over` warns about. -->
<ConfirmDialog
  open={departure !== null}
  title={m.confirm.leave.title}
  body={m.confirm.startOver.body}
  confirmLabel={m.action.confirm}
  cancelLabel={m.action.cancel}
  onconfirm={() => {
    const to = departure;
    departure = null;
    leaving = true;
    if (to) goto(to);
  }}
  oncancel={() => (departure = null)}
/>

<style>
  /* This page adds a fourth control to a header bar drawn as one row, and the brand name does not
     wrap. Below 560px the row wraps instead of pushing the language control off the screen, and
     the load target keeps its mark while the word leaves the screen rather than the accessibility
     tree — exactly what `.switcher__label` does at the same width. Both rules are anchored to
     `.page`, so they reach only this route's own header. */
  @media (max-width: 560px) {
    .page :global(.site-header__bar) {
      flex-wrap: wrap;
      padding-block: var(--sp-4);
    }

    .page :global(.site-header__brand) {
      flex: 1 0 100%;
    }

    .load :global(.drop > span:not(.drop__name)) {
      position: absolute;
      inline-size: 1px;
      block-size: 1px;
      overflow: hidden;
      clip-path: inset(50%);
      white-space: nowrap;
    }
  }

  .workspace {
    display: grid;
    gap: var(--sp-7);
    align-items: start;
    padding-block: var(--sp-7) var(--sp-10);
  }

  .workspace__panel {
    display: flex;
    flex-direction: column;
    gap: var(--sp-7);
    min-inline-size: 0;
  }

  @media (min-width: 900px) {
    .workspace {
      grid-template-columns: 220px minmax(0, 1fr);
      gap: var(--sp-9);
    }
  }
</style>
