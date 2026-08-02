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
    path="experiment_id configuration_id"
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
    </dl>

    <!-- The escape hatch, and only for the experiment: nobody is asked to type an identifier, which
         is not the same as nobody being allowed to. `configuration_id` has no override at all — it
         is a digest of the document, and pinning it would let two different files claim one name.
         Empty means derived; the suggestion button offers the derived value in one click.

         `Disclosure` treats `open` as an initial value, which is exactly enough: `{#if step ===
         'sign'}` mounts this step fresh, so an issue jump lands here after a mount and the field is
         already expanded when `+page.svelte` goes looking for it. Break that mount and the jump
         silently scrolls to nothing. -->
    <Disclosure
      label={m.control.details}
      icon="document"
      open={draft.issues.some((issue) => issue.path === 'experiment_id')}
    >
      <IdField
        label={m.field.label.experimentId}
        hint={m.field.hint.experimentIdOverride}
        path="experiment_id"
        value={draft.experimentIdPin}
        suggestFrom={draft.configuration.title}
        suggestLabel={m.control.applySuggestion}
        onchange={(value) => draft.pinExperimentId(value)}
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
