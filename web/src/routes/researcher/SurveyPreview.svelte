<script lang="ts">
  import type { LocalizedText, SurveyDefinition } from '$lib/particeps/types';
  let { survey, locale }: { survey: SurveyDefinition; locale: 'en' | 'zh-TW' } = $props();
  const zh = $derived(locale === 'zh-TW');
  function text(value: LocalizedText) { return value.translations[locale] ?? value.default; }
</script>
<section class="survey-preview">
  <h4>{text(survey.title)}</h4>
  <p>{text(survey.description)}</p>
  <ol>
    {#each survey.questions as question (question.id)}
      <li>
        <p><strong>{text(question.prompt)}</strong> <span class="fine faint">{question.required ? (zh ? '必填' : 'Required') : (zh ? '選填' : 'Optional')}</span></p>
        {#if question.type === 'short_text'}
          <p class="fine faint">{zh ? `簡答，最多 ${question.maximum_length} 字元` : `Short answer, up to ${question.maximum_length} characters`}</p>
        {:else if question.type === 'scale'}
          <p>{question.minimum} · {text(question.minimum_label)} — {question.maximum} · {text(question.maximum_label)}</p>
        {:else}
          <p class="fine faint">{question.type === 'single_choice' ? (zh ? '選擇一項' : 'Choose one') : (zh ? `選擇 ${question.minimum_selections}–${question.maximum_selections} 項` : `Choose ${question.minimum_selections}–${question.maximum_selections}`)}</p>
          <ul>{#each question.options as option (option.id)}<li>{text(option.label)}</li>{/each}</ul>
        {/if}
      </li>
    {/each}
  </ol>
</section>
<style>
  .survey-preview { display: grid; gap: var(--sp-3); }
  h4, p { margin: 0; overflow-wrap: anywhere; white-space: pre-wrap; }
  ol { display: grid; gap: var(--sp-5); padding-inline-start: var(--sp-6); }
  ul { padding-inline-start: var(--sp-5); }
</style>
