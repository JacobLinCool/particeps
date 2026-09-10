<script lang="ts">
  import type { LocalizedText } from '$lib/particeps/types';
  import TextField from '$lib/ui/TextField.svelte';
  import Button from '$lib/ui/Button.svelte';
  import IconButton from '$lib/ui/IconButton.svelte';
  let { value, path, label, locale, multiline = false }: { value: LocalizedText; path: string; label: string; locale: 'en' | 'zh-TW'; multiline?: boolean } = $props();
  let language = $state('');
  let error = $state('');
  function add() {
    const tag = language.trim();
    if (!/^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/.test(tag) || Object.keys(value.translations).some((key) => key.toLowerCase() === tag.toLowerCase()) || Object.keys(value.translations).length >= 32) {
      error = locale === 'zh-TW' ? '請輸入尚未加入的語言代碼，例如 en 或 zh-TW。' : 'Enter a new language tag, such as en or zh-TW.'; return;
    }
    value.translations[tag] = ''; language = ''; error = '';
  }
</script>
<div class="localized" data-issue-host={path}>
  <TextField {label} path={`${path}.default`} value={value.default} max={2_000} {multiline} onchange={(text) => value.default = text} />
  <details>
    <summary>{locale === 'zh-TW' ? '翻譯' : 'Translations'} ({Object.keys(value.translations).length})</summary>
    <div class="stack" data-issue-host={`${path}.translations`}>
      {#each Object.entries(value.translations) as [tag, text] (tag)}
        <div class="translation">
          <TextField label={`${label} · ${tag}`} path={`${path}.translations.${tag}`} value={text} max={2_000} {multiline} onchange={(next) => value.translations[tag] = next} />
          <IconButton icon="trash" label={locale === 'zh-TW' ? `移除 ${tag} 翻譯` : `Remove ${tag} translation`} variant="danger" onclick={() => delete value.translations[tag]} />
        </div>
      {/each}
      <div class="translation">
        <TextField label={locale === 'zh-TW' ? '語言代碼' : 'Language tag'} value={language} max={35} placeholder="zh-TW" onchange={(next) => language = next} />
        <Button label={locale === 'zh-TW' ? '新增翻譯' : 'Add translation'} icon="plus" variant="ghost" onclick={add} />
      </div>
      {#if error}<p class="fine" role="alert">{error}</p>{/if}
    </div>
  </details>
</div>
<style>
  .localized { display: grid; gap: var(--sp-2); min-inline-size: 0; }
  summary { cursor: pointer; color: var(--ink-faint); padding-block: var(--sp-2); }
  .translation { display: flex; align-items: end; gap: var(--sp-3); }
  .translation > :global(.field) { flex: 1; min-inline-size: 0; }
  @media (max-width: 34rem) { .translation { flex-wrap: wrap; } }
</style>
