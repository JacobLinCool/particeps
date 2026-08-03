<script lang="ts">
  /**
   * `assigned_participant_id`, which is a root key and not a property of any one section.
   *
   * It lived inside the interventions editor, which read as though a notification needed a
   * participant code. What it actually decides is how the study is distributed: null means one
   * signed file goes to everyone and each import mints its own random instance id, while a code
   * means a distinct file and `configuration_id` per participant. That is a decision about the
   * whole study, so it is a section of its own beside the other root keys.
   *
   * The two alternatives are shown at once rather than hidden behind a select, because which of
   * them a study is decides what travels with every event it records.
   */
  import ChoiceField from '$lib/ui/ChoiceField.svelte';
  import IdField from '$lib/ui/IdField.svelte';
  import type { Messages } from '$lib/i18n/types';
  import type { Draft } from './draft.svelte';

  let { draft, m }: { draft: Draft; m: Messages } = $props();

  const configuration = $derived(draft.configuration);
  const copy = $derived(m.intervention);

  /**
   * The code survives a trip through anonymous. The placeholder that used to be written in its
   * place passed `ASSIGNED_PARTICIPANT_ID_PATTERN`, so a study whose participant was literally
   * named `participant-code` signed clean; an empty box raises `id_format` where it can be read.
   */
  let assigned = $state('');
  const personalized = $derived(configuration.assigned_participant_id !== null);
  $effect(() => {
    if (configuration.assigned_participant_id) assigned = configuration.assigned_participant_id;
  });

  const IDENTITY = $derived([
    { value: false, label: copy.anonymous },
    { value: true, label: copy.personalized }
  ]);
</script>

<div class="stack">
  <!-- The group's name is the field it switches on, so the decision is not announced as a menu of
       its own option names. -->
  <ChoiceField
    value={personalized}
    options={IDENTITY}
    groupLabel={copy.assignedId}
    onchange={(value) => (configuration.assigned_participant_id = value ? assigned : null)}
  />
  {#if personalized}
    <IdField
      label={copy.assignedId}
      path="assigned_participant_id"
      value={configuration.assigned_participant_id ?? ''}
      onchange={(value) => {
        assigned = value;
        configuration.assigned_participant_id = value;
      }}
    />
  {/if}
</div>
