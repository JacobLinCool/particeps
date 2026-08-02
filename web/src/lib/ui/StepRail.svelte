<script lang="ts">
  /** `Rail` under the name both flow specifications use, and accepting the participant page's
   *  shorter form — a count and a list of names, with no state to derive. */
  import Rail from './Rail.svelte';
  import type { ComponentProps } from 'svelte';
  import type { StepDef } from './types';

  type RailProps = ComponentProps<typeof Rail>;

  interface Props extends Partial<Omit<RailProps, 'steps'>> {
    steps?: readonly StepDef[];
    /** Participant form: n unnamed dots, optionally named by `labels`. */
    count?: number;
    labelNames?: readonly string[];
  }

  let { steps, count, labelNames, ...rest }: Props = $props();

  const resolved = $derived<readonly StepDef[]>(
    steps ??
      Array.from({ length: count ?? 0 }, (_, index) => ({
        id: String(index),
        label: labelNames?.[index] ?? String(index + 1)
      }))
  );
</script>

<Rail {...rest} steps={resolved} />
