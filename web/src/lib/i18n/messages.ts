/**
 * The catalogues, under the path `lib/ui/i18n.svelte.ts` imports them from.
 *
 * `lib/ui` owns the reactive half of the locale — it is what `LocaleMenu` writes to, what
 * `app.html` resolves before first paint under `particeps.locale`, and what stamps `zh-Hant-TW` onto
 * `<html>` so the CJK block in `type.css` selects. This file is the seam between that module and
 * the catalogue files, and holds nothing of its own.
 */

export type { Messages } from './types';
export { en } from './en';
export { zhTW } from './zh-TW';
