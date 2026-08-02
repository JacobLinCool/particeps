/**
 * Key in, sentence out.
 *
 * Components on this page hold keys, never text, so a component cannot be the place a claim is
 * quietly reworded and a reviewer reading `copy.ts` has read every claim the page makes. The
 * locale comes from `lib/ui/i18n.svelte`, which is the same reactive value `LocaleMenu` writes
 * and `<html lang>` follows — one choice, honoured everywhere on the page.
 */

import { i18n } from '$lib/ui/i18n.svelte';
import { en, zhTW, type MessageKey, type ParticipantCopy } from './copy';

const CATALOGUE: Record<string, ParticipantCopy> = { en, 'zh-TW': zhTW };

/** Reads the locale on every call, so a template that calls `m` re-renders when it changes. */
export function m(key: MessageKey): string {
  let node: unknown = CATALOGUE[i18n.locale] ?? en;
  for (const part of key.split('.')) {
    node = (node as Record<string, unknown>)[part];
  }
  return node as string;
}

export type { MessageKey };
