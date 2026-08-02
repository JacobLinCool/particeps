<script lang="ts">
  /**
   * The language control, lifted from the app's `LanguageDialog` for the same reason it exists
   * there: someone who cannot read the language currently on screen must still be able to find
   * theirs. So every locale is written in its own language, the heading is bilingual on one line,
   * and system default is first — returning to the browser's choice is a choice.
   *
   * The chosen locale persists to `localStorage` and drives `<html lang>`, which is what selects
   * the CJK typography in `type.css`.
   */
  import IconButton from './IconButton.svelte';
  import Icon from './Icon.svelte';
  import { i18n, messages, LOCALES, type Locale } from './i18n.svelte';

  interface Props {
    /** Accessible name for the trigger, from `control.language`. */
    label?: string;
  }

  let { label }: Props = $props();

  let open = $state(false);
  let host: HTMLDivElement | undefined = $state();
  let trigger: HTMLElement | undefined = $state();

  const uid = $props.id();

  $effect(() => i18n.start());

  const name = $derived(label ?? i18n.m.control.language);

  /** Both catalogues, always: the heading has to be readable in the language you cannot read. */
  const heading = `${messages.en.language.label} ${messages['zh-TW'].language.label}`;

  const endonym: Record<Locale, string> = {
    en: messages.en.language.en,
    'zh-TW': messages.en.language.zhTW
  };

  function choose(next: Locale | null) {
    i18n.choose(next);
    open = false;
    trigger?.querySelector('button')?.focus();
  }

  $effect(() => {
    if (!open) return;
    const away = (event: MouseEvent) => {
      if (host && !host.contains(event.target as Node)) open = false;
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        open = false;
        trigger?.querySelector('button')?.focus();
      }
    };
    document.addEventListener('pointerdown', away);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', away);
      document.removeEventListener('keydown', escape);
    };
  });
</script>

<div bind:this={host} class="locale-anchor">
  <span bind:this={trigger}>
    <IconButton
      icon="language"
      label={name}
      size={22}
      tone="soft"
      expanded={open}
      controls={uid}
      haspopup="true"
      onclick={() => (open = !open)}
      testid="locale-menu"
    />
  </span>

  {#if open}
    <div class="locale" id={uid} role="radiogroup" aria-label={heading}>
      <p class="locale__head">{heading}</p>

      <button
        class="locale__row"
        type="button"
        role="radio"
        aria-checked={i18n.chosen === null}
        onclick={() => choose(null)}
      >
        <Icon name="check" size={16} />
        <span>{i18n.m.language.system}</span>
      </button>

      {#each LOCALES as option (option)}
        <button
          class="locale__row"
          type="button"
          role="radio"
          aria-checked={i18n.chosen === option}
          lang={option === 'zh-TW' ? 'zh-Hant-TW' : 'en'}
          onclick={() => choose(option)}
        >
          <Icon name="check" size={16} />
          <span>{endonym[option]}</span>
        </button>
      {/each}
    </div>
  {/if}
</div>
