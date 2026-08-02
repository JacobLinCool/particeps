<script lang="ts">
  /**
   * The gate: everything wrong, the exact bytes, and the one action.
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
  import Disclosure from '$lib/ui/Disclosure.svelte';
  import IssueList from '$lib/ui/IssueList.svelte';
  import LiveRegion from '$lib/ui/LiveRegion.svelte';
  import Note from '$lib/ui/Note.svelte';
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
        draft.configuration.signer.public_key
      );
      const now = Math.floor(Date.now() / 1_000);
      const issued = parseInstant(draft.configuration.issued_at)?.second ?? null;
      const expires = parseInstant(draft.configuration.expires_at)?.second ?? null;
      const current = issued !== null && expires !== null && issued <= now && now < expires;
      return { verified, current };
    } catch {
      return { verified: false, current: false };
    }
  });
</script>

<div class="stack stack--loose">
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
      configuration={draft.configuration}
      fingerprint={draft.fingerprint}
      verified={receipt.verified}
      current={receipt.current}
      {m}
    />
  {/if}
</div>
