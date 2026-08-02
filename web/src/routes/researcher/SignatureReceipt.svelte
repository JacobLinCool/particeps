<script lang="ts">
  /**
   * `check-config`, run locally on what was just produced: decode the envelope, verify the
   * signature against the public key inside it, check the clock is in the window.
   *
   * The third mark is a non-blocking `PendingMark`, not an error. An unpinned signer is the
   * deployment model — the configuration certifies itself and the fingerprint is what closes the
   * gap — and painting the normal case red teaches a reader to skip red.
   */
  import Fingerprint from '$lib/ui/Fingerprint.svelte';
  import Mark from '$lib/ui/Mark.svelte';
  import type { Messages } from '$lib/i18n/types';
  import type { StudyConfiguration } from '$lib/adc/types';

  interface Props {
    configuration: StudyConfiguration;
    fingerprint: string;
    /** The signature verified against `signer.public_key`, here, on these bytes. */
    verified: boolean;
    /** The clock is inside `issued_at .. expires_at`. */
    current: boolean;
    m: Messages;
  }

  let { configuration, fingerprint, verified, current, m }: Props = $props();
</script>

<div class="receipt" data-testid="signature-receipt">
  <p class="receipt__row">
    <Mark kind={verified && current ? 'check' : 'blocking'} tone={verified && current ? 'signal' : 'danger'} size={18} />
    <span class="receipt__label">{m.status.verified}</span>
    <span class="receipt__value">{configuration.experiment_id} {configuration.configuration_id}</span>
  </p>

  <p class="receipt__row">
    <Mark kind={verified ? 'check' : 'blocking'} tone={verified ? 'signal' : 'danger'} size={18} />
    <span class="receipt__label">{m.field.label.signerKeyId}</span>
    <span class="receipt__value">{configuration.signer.key_id}</span>
  </p>

  <p class="receipt__row">
    <Mark kind="pending" tone="faint" size={18} />
    <span class="receipt__label">{m.field.label.fingerprint}</span>
    <span class="receipt__value"><Fingerprint value={fingerprint} size="inline" testid="receipt-fingerprint" /></span>
  </p>
</div>
