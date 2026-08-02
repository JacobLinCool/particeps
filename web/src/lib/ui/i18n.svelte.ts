/**
 * The locale, as one reactive value the whole tree reads.
 *
 * Two things follow the browser by default and are overridable in the page: which catalogue the
 * components read, and which typography `:lang()` selects. Both are driven from here, and the
 * choice persists to `localStorage` under the same key `app.html` reads before first paint — the
 * inline script and this module must agree or the page reflows one frame in.
 *
 * `<html lang>` is written as `zh-Hant-TW`, not `zh-TW`. CSS language matching is prefix-based:
 * `:lang(zh-Hant)` matches `zh-Hant-TW` and does not match `zh-TW`, and the whole CJK type block
 * hangs off that selector.
 */

import { en, zhTW, type Messages } from '$lib/i18n/messages';

export type Locale = 'en' | 'zh-TW';

export const LOCALES: readonly Locale[] = ['en', 'zh-TW'];

export const messages: Record<Locale, Messages> = { en, 'zh-TW': zhTW };

const HTML_LANG: Record<Locale, string> = { en: 'en', 'zh-TW': 'zh-Hant-TW' };

const STORAGE_KEY = 'adc.locale';

function isLocale(value: unknown): value is Locale {
  return value === 'en' || value === 'zh-TW';
}

/** `navigator.languages`, falling back to English. Simplified Chinese is not this catalogue. */
export function detectLocale(): Locale {
  if (typeof navigator === 'undefined') return 'en';
  const tags = navigator.languages?.length ? navigator.languages : [navigator.language ?? 'en'];
  for (const tag of tags) {
    if (/^zh\b/i.test(tag) && !/\b(Hans|CN|SG)\b/i.test(tag)) return 'zh-TW';
    if (/^en\b/i.test(tag)) return 'en';
  }
  return 'en';
}

function stored(): Locale | null {
  if (typeof localStorage === 'undefined') return null;
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    return isLocale(value) ? value : null;
  } catch {
    return null;
  }
}

/** `null` means "whatever the browser says", which is a choice a reader can go back to. */
let chosen = $state<Locale | null>(null);
let detected = $state<Locale>('en');
let started = false;

const active = $derived(chosen ?? detected);

export const i18n = {
  /** The locale in force. */
  get locale(): Locale {
    return active;
  },
  /** What the reader picked, or `null` if they are following the browser. */
  get chosen(): Locale | null {
    return chosen;
  },
  /** The catalogue for the locale in force. */
  get m(): Messages {
    return messages[active];
  },
  /** `null` returns to the browser default rather than pinning English. */
  choose(next: Locale | null): void {
    chosen = next;
    try {
      if (next) localStorage.setItem(STORAGE_KEY, next);
      else localStorage.removeItem(STORAGE_KEY);
    } catch {
      /* storage is a convenience here; the choice still holds for this tab */
    }
    applyLang();
  },
  /** Idempotent. Called by `LanguageControl` on mount so a route cannot forget it. */
  start(): void {
    if (started) return;
    started = true;
    detected = detectLocale();
    chosen = stored();
    applyLang();
  }
};

function applyLang(): void {
  if (typeof document === 'undefined') return;
  document.documentElement.lang = HTML_LANG[chosen ?? detected];
}

/**
 * Resolves a dotted path against the catalogue. Components take resolved strings for anything
 * they render; this exists for the few places a caller holds a key rather than a string, and it
 * returns the key itself when nothing matches — a visible wrong word beats a silent empty label.
 */
export function lookup(path: string): string {
  let node: unknown = i18n.m;
  for (const part of path.split('.')) {
    if (node && typeof node === 'object' && part in node) node = (node as Record<string, unknown>)[part];
    else return path;
  }
  return typeof node === 'string' ? node : path;
}
