import { afterEach, describe, expect, it, vi } from 'vitest';
import { detectLocale, messages } from '$lib/ui/i18n.svelte';
import { en, zhTW } from '$lib/i18n/messages';
import { LOCALES, type Messages } from '$lib/i18n/types';

/**
 * A key present in one catalogue and missing from the other renders nothing at all, and an empty
 * string renders as a gap nobody reviewing a language they cannot read would notice. Both are cheap
 * to prove absent, so they are proved here rather than trusted.
 */

/** Sample arguments for every message that is a template, keyed by its path. */
const ARGUMENTS: Record<string, unknown> = {
  'control.stepPosition': { index: 2, total: 4 },
  'intervention.randomWindowSummary': { minimum: 0, maximum: 14 },
  'issue.length_range': { min: 1, max: 120 },
  'issue.number_range': { min: 5_000, max: 1_000_000 },
  'issue.document_too_large': { max: 1_048_576 },
  'researcher.sign.size': { bytes: 2_048, max: 1_048_576 },
  'researcher.sign.blocked': 3
};

function leaves(value: unknown, path = '', into = new Map<string, unknown>()): Map<string, unknown> {
  if (typeof value === 'object' && value !== null) {
    for (const [key, child] of Object.entries(value)) {
      leaves(child, path ? `${path}.${key}` : key, into);
    }
  } else {
    into.set(path, value);
  }
  return into;
}

function render(path: string, value: unknown): string {
  if (typeof value !== 'function') return String(value);
  expect(ARGUMENTS, path).toHaveProperty(path);
  return (value as (parameters: unknown) => string)(ARGUMENTS[path]);
}

const catalogues: Array<[string, Messages]> = [
  ['en', en],
  ['zh-TW', zhTW]
];

describe('the catalogues', () => {
  it('cover every locale', () => {
    expect(Object.keys(messages).sort()).toEqual([...LOCALES].sort());
    expect(messages.en).toBe(en);
    expect(messages['zh-TW']).toBe(zhTW);
  });

  it('have the same keys, to the leaf', () => {
    expect([...leaves(zhTW).keys()].sort()).toEqual([...leaves(en).keys()].sort());
  });

  it('agree on which messages are templates', () => {
    const shape = (catalogue: Messages) =>
      [...leaves(catalogue)].map(([path, value]) => `${path}: ${typeof value}`).sort();
    expect(shape(zhTW)).toEqual(shape(en));
  });

  it('template exactly the messages the tests supply arguments for', () => {
    const templates = [...leaves(en)]
      .filter(([, value]) => typeof value === 'function')
      .map(([path]) => path);
    expect(templates.sort()).toEqual(Object.keys(ARGUMENTS).sort());
  });

  it.each(catalogues)('say something for every key in %s', (_locale, catalogue) => {
    for (const [path, value] of leaves(catalogue)) {
      expect(typeof value === 'string' || typeof value === 'function', path).toBe(true);
      expect(render(path, value).trim(), path).not.toBe('');
    }
  });
});

describe('detectLocale', () => {
  afterEach(() => vi.unstubAllGlobals());

  it.each([
    [['zh-Hant'], 'zh-TW'],
    [['zh-TW'], 'zh-TW'],
    [['zh-HK'], 'zh-TW'],
    [['zh-Hant-TW', 'en-US'], 'zh-TW'],
    [['ja', 'zh-TW', 'en'], 'zh-TW'],
    [['en-GB', 'zh-TW'], 'en'],
    // A tag that names Simplified has said which Chinese it wants, and it is not the one shipped
    // here, so it falls back rather than being handed the other script. A bare `zh` has only said
    // "Chinese", and the Chinese available is better for that reader than English.
    [['zh-CN'], 'en'],
    [['zh'], 'zh-TW'],
    [['de'], 'en'],
    [[], 'en']
  ])('reads %j as %s', (languages, expected) => {
    vi.stubGlobal('navigator', { languages });
    expect(detectLocale()).toBe(expected);
  });

  it('survives a browser with neither navigator nor storage', () => {
    vi.stubGlobal('navigator', undefined);
    vi.stubGlobal('localStorage', undefined);
    expect(detectLocale()).toBe('en');
  });

  // `detectLocale` reports what the browser asked for and nothing else; a stored choice overrides
  // it a layer up, in the locale store, which is why this asserts the browser's answer is unchanged
  // by storage rather than that storage wins here.
  it('reports the browser regardless of what is stored', () => {
    vi.stubGlobal('navigator', { languages: ['zh-TW'] });
    vi.stubGlobal('localStorage', { getItem: () => 'en' });
    expect(detectLocale()).toBe('zh-TW');
  });

  it('ignores a stored value that is not a locale', () => {
    vi.stubGlobal('navigator', { languages: ['zh-TW'] });
    vi.stubGlobal('localStorage', { getItem: () => 'klingon' });
    expect(detectLocale()).toBe('zh-TW');
  });
});
