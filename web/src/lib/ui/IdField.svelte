<script lang="ts">
  /**
   * An identifier, which is a different kind of string from prose: it is typed into a terminal,
   * it becomes a filename, and it has to match `ID_PATTERN` exactly.
   *
   * A slug derived from the title is offered beside the field and applied on click. It is never
   * applied while typing — a control that rewrites what is being typed is a control that cannot
   * be typed into.
   */
  import Field from './Field.svelte';
  import Icon from './Icon.svelte';
  import { fieldSource } from './field-context';
  import { ID_PATTERN } from '$lib/adc/types';

  interface Props {
    label: string;
    value: string;
    path?: string;
    hint?: string;
    /** Usually the study title. A slug is offered only when it is legal and differs. */
    suggestFrom?: string;
    /** `control.applySuggestion`. The name is the act; the slug is the button's contents. */
    suggestLabel?: string;
    onchange: (value: string) => void;
  }

  let { label, value, path, hint, suggestFrom, suggestLabel, onchange }: Props = $props();

  const source = fieldSource();
  const uid = $props.id();

  function slug(from: string): string | null {
    const candidate = from
      .toLowerCase()
      .normalize('NFKD')
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 64)
      .replace(/-+$/, '');
    return ID_PATTERN.test(candidate) ? candidate : null;
  }

  const suggestion = $derived.by(() => {
    if (!suggestFrom) return null;
    const candidate = slug(suggestFrom);
    return candidate && candidate !== value ? candidate : null;
  });
</script>

<Field {label} {path} {hint}>
  {#snippet children({ id, describedby, invalid })}
    <div class="row row--tight">
      <input
        class="input input--mono grow"
        type="text"
        {id}
        spellcheck="false"
        autocapitalize="off"
        autocorrect="off"
        maxlength={64}
        aria-describedby={describedby}
        aria-invalid={invalid || undefined}
        {value}
        oninput={(event) => onchange(event.currentTarget.value)}
        onblur={() => path && source.touch?.(path)}
      />
      {#if suggestion}
        <button
          class="suggest"
          type="button"
          title={suggestLabel}
          aria-describedby={suggestLabel ? `${uid}-act` : undefined}
          onclick={() => onchange(suggestion)}
        >
          <Icon name="arrow-right" size={14} />
          {suggestion}
        </button>
        {#if suggestLabel}<span class="sr" id={`${uid}-act`}>{suggestLabel}</span>{/if}
      {/if}
    </div>
  {/snippet}
</Field>
