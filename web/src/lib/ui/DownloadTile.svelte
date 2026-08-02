<script lang="ts">
  /**
   * A `Tile` with the one control it needs.
   *
   * On success the icon becomes a tick in --signal for two seconds and then returns: the state
   * change lands on the control that caused it, not in a notification floating somewhere else.
   * There is no bulk download — `researcher-tools` writes each artefact with
   * `StandardOpenOption.CREATE_NEW` and refuses to overwrite, and two private keys are two
   * decisions.
   */
  import Tile from './Tile.svelte';
  import Button from './Button.svelte';
  import LiveRegion from './LiveRegion.svelte';
  import { groupDigits } from './format';
  import type { IconRef } from './icons';
  import type { Tone } from './types';

  interface Props {
    icon: IconRef;
    filename: string;
    bytes: number;
    /** Everything after the byte count, e.g. `Ed25519 PKCS#8`. Already localised. */
    detail?: string;
    tone?: Tone;
    secret?: boolean;
    /** A download was started. For a secret that is not yet a claim that it reached the disk. */
    sent?: boolean;
    /** On disk. Drives the group's tally and drops the unsaved ring. */
    saved?: boolean;
    /** `action.confirmSaved`. Required when `secret`, because only the reader can set `saved`. */
    keptLabel?: string;
    warning?: string;
    /** The strip's mark and temperature. See `Tile`: `soft` takes no wash. */
    warningIcon?: IconRef;
    warningTone?: Tone;
    /** `a11y.download` plus the filename, from the caller. */
    label: string;
    /** Announced after the file is written. */
    savedLabel?: string;
    disabled?: boolean;
    testid?: string;
    ondownload: () => void;
    /** The reader confirming the file is on their disk. Required when `secret`. */
    onkept?: () => void;
  }

  let {
    icon,
    filename,
    bytes,
    detail,
    tone = 'soft',
    secret = false,
    sent = false,
    saved = false,
    keptLabel,
    warning,
    warningIcon,
    warningTone,
    label,
    savedLabel,
    disabled = false,
    testid,
    ondownload,
    onkept
  }: Props = $props();

  let settled = $state(false);
  let announced = $state('');
  let timer: ReturnType<typeof setTimeout> | undefined;

  function run() {
    ondownload();
    settled = true;
    announced = savedLabel ?? filename;
    clearTimeout(timer);
    timer = setTimeout(() => {
      settled = false;
      announced = '';
    }, 2000);
  }

  $effect(() => () => clearTimeout(timer));
</script>

<Tile
  {icon}
  name={filename}
  {tone}
  {secret}
  {warning}
  {warningIcon}
  {warningTone}
  empty={bytes === 0}
  {testid}
>
  {#snippet meta()}
    <b>{groupDigits(bytes)} B</b>{#if detail}&nbsp;· {detail}{/if}
  {/snippet}

  {#snippet trailing()}
    <span class={!saved && secret ? 'pulse' : undefined} data-loop={!saved && secret ? '' : undefined} data-loop-still="ring">
      <Button
        variant="quiet"
        icon="download"
        settled={settled || saved}
        {disabled}
        onclick={run}
        testid={testid ? `${testid}-button` : undefined}
      >
        <span class="sr">{label}</span>
      </Button>
    </span>
    <!-- A dismissed save sheet writes nothing, and no browser reports that. So the ring stays and
         the tally does not count this file until the one party who can see the disk says so. -->
    {#if secret && sent && !saved && onkept}
      <Button
        variant="quiet"
        icon="check"
        label={keptLabel}
        onclick={onkept}
        testid={testid ? `${testid}-kept` : undefined}
      />
    {/if}
    <LiveRegion text={announced} />
  {/snippet}
</Tile>
