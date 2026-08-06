/**
 * How a field finds out it is wrong.
 *
 * `Field` reads the issues for its own path out of context rather than taking them as a prop, so
 * a route can put thirty controls on a page without threading a validation array through every
 * one. The researcher page provides a source backed by its draft store; the participant page
 * provides nothing, and the default below answers "no issues" for every path — which is correct,
 * because that page has no form.
 */

import { getContext, setContext } from 'svelte';
import type { UiIssue } from './types';

export interface FieldSource {
  /** Already filtered for visibility: touched, or a sign has been attempted. */
  issues(path: string): readonly UiIssue[];
  /** A soft advisory is not an issue: nothing is wrong, but something is worth knowing. */
  advisory?(path: string): string | null;
  /** Called on blur. */
  touch?(path: string): void;
  /** Turns a stable code into a sentence in the reader's language. */
  message(issue: UiIssue): string;
}

const KEY = Symbol('particeps.field-source');

const NONE: FieldSource = {
  issues: () => [],
  message: (issue) => issue.code
};

export function setFieldSource(source: FieldSource): void {
  setContext(KEY, source);
}

export function fieldSource(): FieldSource {
  return getContext<FieldSource>(KEY) ?? NONE;
}

/**
 * The default code-to-message resolver, over the catalogue's `issue` table. An unmapped code
 * comes back as the raw code so `Field` can render it in a monospace chip: never a bare code with
 * no explanation, and never nothing at all.
 */
export function resolveIssueMessage(
  table: Record<string, unknown>,
  issue: UiIssue
): { text: string; mapped: boolean } {
  const entry = table[issue.code];
  if (typeof entry === 'string') return { text: entry, mapped: true };
  if (typeof entry === 'function') {
    return { text: String((entry as (p: unknown) => string)(issue.params ?? {})), mapped: true };
  }
  return { text: issue.code, mapped: false };
}
