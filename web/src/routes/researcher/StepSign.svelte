<script lang="ts">
  /**
   * The gate: what the study is called, everything wrong, the exact bytes, and the one action.
   *
   * The two identifiers are read out here rather than typed anywhere, because this is the step
   * where they become real and this is the question the step answers — what am I about to sign.
   * `SignatureReceipt` already showed them after signing; showing them before it is what lets a
   * researcher who reused a study title notice that they reused a study title.
   *
   * The issue list is above the action rather than beside it, because a disabled button with no
   * explanation is a dead end. The count rides on the button itself and is announced politely, so
   * a researcher fixing fields hears the number fall.
   *
   * The byte pane is what makes this page trustworthy to someone who already knows the CLI: they
   * can diff it against `researcher-tools canonicalize` and see the same thing.
   */
  import Button from '$lib/ui/Button.svelte';
  import BytePane from '$lib/ui/BytePane.svelte';
  import CopyButton from '$lib/ui/CopyButton.svelte';
  import Disclosure from '$lib/ui/Disclosure.svelte';
  import IdField from '$lib/ui/IdField.svelte';
  import IssueList from '$lib/ui/IssueList.svelte';
  import LiveRegion from '$lib/ui/LiveRegion.svelte';
  import Note from '$lib/ui/Note.svelte';
  import Section from '$lib/ui/Section.svelte';
  import SignatureReceipt from './SignatureReceipt.svelte';
  import { fieldLabel } from './labels';
  import { STEPS, stepForPath } from './steps';
  import { parseInstant } from '$lib/adc/canonical';
  import { verify } from '$lib/adc/crypto';
  import { decodeEnvelope } from './parse';
  import type { Draft } from './draft.svelte';
  import type { Messages } from '$lib/i18n/types';
  import type { UiIssue } from '$lib/ui/types';

  interface Props {
    draft: Draft;
    m: Messages;
    /** The one failure that must interrupt: a signature that will fail on every device. */
    failure: string;
    onjump: (path: string) => void;
    onsign: () => void;
  }

  let { draft, m, failure, onjump, onsign }: Props = $props();

  const uid = $props.id();
  const countId = `${uid}-count`;

  const issues = $derived<UiIssue[]>(
    draft.issues.map((issue) => ({ path: issue.path, code: issue.code, params: issue.bounds }))
  );

  const icons = new Map(STEPS.map((step) => [step.id, step.icon]));

  /** The three paths whose only control is inside the disclosure, so an issue on one opens it. */
  const OVERRIDDEN = new Set(['experiment_id', 'signer.key_id', 'export.researcher_key_id']);

  function groupOf(issue: UiIssue) {
    const owner = stepForPath(issue.path);
    return { id: owner, label: m.step[owner], icon: icons.get(owner) };
  }

  /**
   * `check-config`, reproduced on what was just produced rather than trusted from the act that
   * produced it: decode the container, verify the signature over the configuration bytes it
   * carries, and check the clock is inside the window.
   */
  const receipt = $derived.by(() => {
    const envelope = draft.envelope;
    if (!envelope) return null;
    try {
      const decoded = decodeEnvelope(envelope);
      const verified = verify(
        decoded.configurationBytes,
        decoded.signature,
        draft.document.signer.public_key
      );
      const now = Math.floor(Date.now() / 1_000);
      const issued = parseInstant(draft.document.issued_at)?.second ?? null;
      const expires = parseInstant(draft.document.expires_at)?.second ?? null;
      const current = issued !== null && expires !== null && issued <= now && now < expires;
      return { verified, current };
    } catch {
      return { verified: false, current: false };
    }
  });
</script>

<div class="stack stack--loose">
  <!-- `path` lands on `data-issue-host` as two space-separated tokens, and the issue-jump matches
       it with `~=`, so both identifier paths resolve to this block. -->
  <Section
    id="identity"
    title={m.researcher.sign.identity.title}
    lead={m.researcher.sign.identity.note}
    icon="document"
    path="experiment_id configuration_id signer.key_id export.researcher_key_id"
  >
    <dl class="identity" data-testid="identity-readout">
      <dt>{m.field.label.experimentId}</dt>
      <dd class="mono">
        <span>{draft.experimentId}</span>
        <CopyButton
          text={draft.experimentId}
          label={m.action.copy}
          copiedLabel={m.status.copied}
          failedLabel={m.error.clipboard}
          testid="copy-experiment-id"
        />
      </dd>
      <dt>{m.field.label.configurationId}</dt>
      <dd class="mono">
        <span>{draft.configurationId}</span>
        <CopyButton
          text={draft.configurationId}
          label={m.action.copy}
          copiedLabel={m.status.copied}
          failedLabel={m.error.clipboard}
          testid="copy-configuration-id"
        />
      </dd>
      <!-- The two key names, which are also what `sign --key-id` wants and what the private key
           files on disk are called. Rendered only when they exist, so the default page state does
           not show two blank rows for keys that failed to generate. -->
      {#if draft.signerKeyId}
        <dt>{m.field.label.signerKeyId}</dt>
        <dd class="mono">
          <span>{draft.signerKeyId}</span>
          <CopyButton
            text={draft.signerKeyId}
            label={m.action.copy}
            copiedLabel={m.status.copied}
            failedLabel={m.error.clipboard}
            testid="copy-signer-key-id"
          />
        </dd>
      {/if}
      {#if draft.exportKeyId}
        <dt>{m.field.label.exportKeyId}</dt>
        <dd class="mono">
          <span>{draft.exportKeyId}</span>
          <CopyButton
            text={draft.exportKeyId}
            label={m.action.copy}
            copiedLabel={m.status.copied}
            failedLabel={m.error.clipboard}
            testid="copy-export-key-id"
          />
        </dd>
      {/if}
    </dl>

    <!-- The escape hatch, for the three identifiers that have one: nobody is asked to type an
         identifier, which is not the same as nobody being allowed to. `configuration_id` has no
         override at all — it is a digest of the document, and pinning it would let two different
         files claim one name. Empty means derived; the suggestion button offers the derived value
         in one click.

         The two key names are here rather than on the Keys step for the same reason they are not
         typed at all: a text field on a key card asking a researcher to name a key is the thing
         being removed. What this is for is a key that already carries a name from
         `sign --key-id lab-signer-2026`, or an export key from a CLI-era study, where being
         silently renamed would put two names on one key across two arms of one study. Neither
         takes a `suggestFrom`: the suggestion machinery derives from the title, which has nothing
         to do with a key.

         `Disclosure` treats `open` as an initial value, which is exactly enough: `{#if step ===
         'sign'}` mounts this step fresh, so an issue jump lands here after a mount and the field is
         already expanded when `+page.svelte` goes looking for it. Break that mount and the jump
         silently scrolls to nothing. -->
    <Disclosure
      label={m.control.details}
      icon="document"
      open={draft.issues.some((issue) => OVERRIDDEN.has(issue.path))}
    >
      <IdField
        label={m.field.label.experimentId}
        hint={m.field.hint.override}
        path="experiment_id"
        value={draft.experimentIdPin}
        suggestFrom={draft.configuration.title}
        suggestLabel={m.control.applySuggestion}
        onchange={(value) => draft.pinExperimentId(value)}
      />
      <IdField
        label={m.field.label.signerKeyId}
        hint={m.field.hint.override}
        path="signer.key_id"
        value={draft.signerKeyIdPin}
        onchange={(value) => draft.pinSignerKeyId(value)}
      />
      <IdField
        label={m.field.label.exportKeyId}
        hint={m.field.hint.override}
        path="export.researcher_key_id"
        value={draft.exportKeyIdPin}
        onchange={(value) => draft.pinExportKeyId(value)}
      />
    </Disclosure>
  </Section>

  <IssueList
    {issues}
    {groupOf}
    fieldLabel={(path) => fieldLabel(m, path)}
    emptyLabel={m.status.clean}
    onjump={(issue) => onjump(issue.path)}
  />

  <Disclosure
    label={m.researcher.sign.canonical}
    icon="json"
    testid="canonical-disclosure"
  >
    <BytePane
      text={draft.canonical}
      bytes={draft.canonicalBytes.length}
      copyLabel={m.action.copy}
      copiedLabel={m.status.copied}
    />
  </Disclosure>

  <div class="row">
    <Button
      variant="primary"
      icon="seal"
      label={m.action.sign}
      count={issues.length}
      disabled={issues.length > 0 || draft.signing.kind !== 'held'}
      describedby={countId}
      onclick={onsign}
      testid="sign"
    />
    <LiveRegion
      text={issues.length > 0 ? m.researcher.sign.blocked(issues.length) : m.status.clean}
      visible
      testid="issue-count"
    />
    <span class="sr" id={countId}>
      {issues.length > 0 ? m.researcher.sign.blocked(issues.length) : m.status.clean}
    </span>
  </div>

  {#if failure}
    <div role="alert" aria-live="assertive">
      <Note icon="alert" tone="danger" text={failure} />
    </div>
  {/if}

  {#if draft.stale}
    <Note icon="alert" tone="caution" text={m.status.stale} />
  {/if}

  {#if draft.envelope && receipt && draft.fingerprint}
    <SignatureReceipt
      configuration={draft.document}
      fingerprint={draft.fingerprint}
      verified={receipt.verified}
      current={receipt.current}
      {m}
    />
  {/if}
</div>

<style>
  /* A two-column plaque: what it is, and what it is called. The value is monospace because it is a
     value — it will be typed into a terminal and it will become part of a filename. */
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
    display: flex;
    align-items: center;
    gap: var(--sp-4);
    font-size: var(--type-fine);
    overflow-wrap: anywhere;
  }
</style>
