<script lang="ts">
  /**
   * Irreversible acts confirm, as they do in the app. Native `<dialog>`, so focus trapping, the
   * backdrop, and Escape are the platform's job rather than ours.
   */
  import Button from './Button.svelte';
  import Icon from './Icon.svelte';
  import type { IconRef } from './icons';

  interface Props {
    open: boolean;
    title: string;
    body: string;
    confirmLabel: string;
    cancelLabel: string;
    icon?: IconRef;
    danger?: boolean;
    onconfirm: () => void;
    oncancel: () => void;
  }

  let {
    open,
    title,
    body,
    confirmLabel,
    cancelLabel,
    icon = 'alert',
    danger = true,
    onconfirm,
    oncancel
  }: Props = $props();

  let dialog: HTMLDialogElement | undefined = $state();

  $effect(() => {
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  });
</script>

<dialog bind:this={dialog} class="dialog" onclose={oncancel} oncancel={oncancel}>
  <div class="dialog__panel">
    <div class="row row--tight">
      <Icon name={icon} size={22} tone={danger ? 'danger' : 'accent'} />
      <h2 class="panel__title">{title}</h2>
    </div>
    <p>{body}</p>
    <div class="dialog__actions">
      <Button variant="ghost" label={cancelLabel} onclick={oncancel} />
      <Button
        variant={danger ? 'danger' : 'primary'}
        label={confirmLabel}
        onclick={onconfirm}
      />
    </div>
  </div>
</dialog>
