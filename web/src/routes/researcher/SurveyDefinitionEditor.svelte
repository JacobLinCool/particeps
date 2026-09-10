<script lang="ts">
  import type { SurveyDefinition, SurveyQuestion } from '$lib/particeps/types';
  import Button from '$lib/ui/Button.svelte';
  import IconButton from '$lib/ui/IconButton.svelte';
  import IdField from '$lib/ui/IdField.svelte';
  import ToggleField from '$lib/ui/ToggleField.svelte';
  import LocalizedTextEditor from './LocalizedTextEditor.svelte';
  import Select from './EditorSelectField.svelte';
  import NumberField from './AutomationNumberField.svelte';
  import { blankLocalizedText, createSurveyQuestion, freshEditorId, moveEditorItem } from './editor-model';
  let { value, path, locale, onrename }: { value: SurveyDefinition; path: string; locale: 'en' | 'zh-TW'; onrename: (next: string) => void } = $props();
  const zh = $derived(locale === 'zh-TW');
  const types = $derived([
    { value: 'short_text' as const, label: zh ? '簡答' : 'Short text' },
    { value: 'scale' as const, label: zh ? '量表' : 'Scale' },
    { value: 'single_choice' as const, label: zh ? '單選' : 'Single choice' },
    { value: 'multiple_choice' as const, label: zh ? '多選' : 'Multiple choice' }
  ]);
  let newType = $state<SurveyQuestion['type']>('short_text');
  function addQuestion() { value.questions.push(createSurveyQuestion(newType, freshEditorId('question', value.questions.map((item) => item.id)))); }
</script>
<div class="stack" data-issue-host={path}>
  <IdField label={zh ? '問卷 ID' : 'Survey ID'} path={`${path}.id`} value={value.id} onchange={onrename} />
  <LocalizedTextEditor value={value.title} path={`${path}.title`} label={zh ? '問卷標題' : 'Survey title'} {locale} />
  <LocalizedTextEditor value={value.description} path={`${path}.description`} label={zh ? '問卷說明' : 'Survey description'} {locale} multiline />
  <div class="questions" data-issue-host={`${path}.questions`}>
    {#each value.questions as question, index (question)}
      {@const questionPath = `${path}.questions.${index}`}
      <details class="question" open={value.questions.length === 1} data-issue-host={questionPath}>
        <summary><span class="num">{index + 1}.</span> {question.prompt.default || (zh ? '尚未填寫題目' : 'Untitled question')} <span class="fine faint">· {types.find((item) => item.value === question.type)?.label}</span></summary>
        <div class="stack">
          <div class="row">
            <IconButton icon="chevron" label={zh ? '題目往上移' : 'Move question up'} disabled={index === 0} onclick={() => moveEditorItem(value.questions, index, -1)} />
            <IconButton icon="chevron-down" label={zh ? '題目往下移' : 'Move question down'} disabled={index === value.questions.length - 1} onclick={() => moveEditorItem(value.questions, index, 1)} />
            <Button label={zh ? '移除題目' : 'Remove question'} icon="trash" variant="ghost" onclick={() => value.questions.splice(index, 1)} />
          </div>
          <div class="columns">
            <IdField label={zh ? '題目 ID' : 'Question ID'} path={`${questionPath}.id`} value={question.id} onchange={(next) => question.id = next} />
            <Select label={zh ? '題型' : 'Question type'} path={`${questionPath}.type`} value={question.type} options={types} hint={zh ? '更換題型會重設該題型專用的欄位；題目文字與必填設定會保留。' : 'Changing type resets its specific fields; prompt and required status are preserved.'} onchange={(type) => value.questions[index] = createSurveyQuestion(type, question.id, question)} />
          </div>
          <LocalizedTextEditor value={question.prompt} path={`${questionPath}.prompt`} label={zh ? '題目' : 'Question prompt'} {locale} multiline />
          <ToggleField label={zh ? '必填' : 'Required answer'} path={`${questionPath}.required`} value={question.required} onchange={(next) => question.required = next} />
          {#if question.type === 'short_text'}
            <NumberField label={zh ? '最多字元數' : 'Maximum characters'} path={`${questionPath}.maximum_length`} value={question.maximum_length} min={1} max={4_000} onchange={(next) => question.maximum_length = next} />
          {:else if question.type === 'scale'}
            <div class="columns">
              <NumberField label={zh ? '量表最小值' : 'Scale minimum'} path={`${questionPath}.minimum`} value={question.minimum} min={-1_000} max={1_000} onchange={(next) => question.minimum = next} />
              <NumberField label={zh ? '量表最大值' : 'Scale maximum'} path={`${questionPath}.maximum`} value={question.maximum} min={-1_000} max={1_000} onchange={(next) => question.maximum = next} />
              <LocalizedTextEditor value={question.minimum_label} path={`${questionPath}.minimum_label`} label={zh ? '最小值標示' : 'Minimum label'} {locale} />
              <LocalizedTextEditor value={question.maximum_label} path={`${questionPath}.maximum_label`} label={zh ? '最大值標示' : 'Maximum label'} {locale} />
            </div>
          {:else}
            <div class="stack" data-issue-host={`${questionPath}.options`}>
              {#each question.options as option, optionIndex (option)}
                {@const optionPath = `${questionPath}.options.${optionIndex}`}
                <div class="option">
                  <div class="row">
                    <strong>{zh ? '選項' : 'Option'} {optionIndex + 1}</strong>
                    <IconButton icon="chevron" label={zh ? '選項往上移' : 'Move option up'} disabled={optionIndex === 0} onclick={() => moveEditorItem(question.options, optionIndex, -1)} />
                    <IconButton icon="chevron-down" label={zh ? '選項往下移' : 'Move option down'} disabled={optionIndex === question.options.length - 1} onclick={() => moveEditorItem(question.options, optionIndex, 1)} />
                    <IconButton icon="trash" label={zh ? '移除選項' : 'Remove option'} variant="danger" onclick={() => question.options.splice(optionIndex, 1)} />
                  </div>
                  <IdField label={zh ? '選項 ID' : 'Option ID'} path={`${optionPath}.id`} value={option.id} onchange={(next) => option.id = next} />
                  <LocalizedTextEditor value={option.label} path={`${optionPath}.label`} label={zh ? '選項文字' : 'Option label'} {locale} />
                </div>
              {/each}
              <Button label={zh ? '新增選項' : 'Add option'} icon="plus" variant="ghost" disabled={question.options.length >= 50} onclick={() => question.options.push({ id: freshEditorId('option', question.options.map((item) => item.id)), label: blankLocalizedText() })} />
            </div>
            {#if question.type === 'multiple_choice'}
              <div class="columns">
                <NumberField label={zh ? '最少選擇數' : 'Minimum selections'} path={`${questionPath}.minimum_selections`} value={question.minimum_selections} min={0} max={question.options.length} onchange={(next) => question.minimum_selections = next} />
                <NumberField label={zh ? '最多選擇數' : 'Maximum selections'} path={`${questionPath}.maximum_selections`} value={question.maximum_selections} min={1} max={question.options.length} onchange={(next) => question.maximum_selections = next} />
              </div>
            {/if}
          {/if}
        </div>
      </details>
    {/each}
  </div>
  <div class="add-question">
    <Select label={zh ? '新增題型' : 'New question type'} path={`${path}.questions.new-type`} value={newType} options={types} onchange={(type) => newType = type} />
    <Button label={zh ? '新增題目' : 'Add question'} icon="plus" disabled={value.questions.length >= 100} onclick={addQuestion} />
  </div>
</div>
<style>
  .questions { min-inline-size: 0; }
  .question { border-block-start: var(--line-hair) solid var(--rule); padding-block: var(--sp-3); }
  summary { cursor: pointer; padding-block: var(--sp-3); overflow-wrap: anywhere; }
  .columns { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 15rem), 1fr)); gap: var(--sp-5); }
  .option { display: grid; gap: var(--sp-3); padding-block: var(--sp-3); border-block-start: var(--line-hair) solid var(--rule); }
  .add-question { display: flex; flex-wrap: wrap; gap: var(--sp-4); align-items: end; }
</style>
