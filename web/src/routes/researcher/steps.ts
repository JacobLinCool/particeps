/**
 * The five steps, and which part of the document each one owns.
 *
 * The app's rail is a disclosure gate: forward-only, and a dot means *where you are*. Authoring is
 * iterative — set a collector, look at the quota, go back, change the consent text — so here every
 * step is reachable at any time and a dot means *whether that step's output exists and is valid*.
 * Same drawing, different rules.
 *
 * The step names are the catalogue's (`step.study/keys/sign/files/read`), not a set invented here.
 * A step with no name in `lib/i18n` would be a step with no name in one of the two languages.
 *
 * Four of the five own part of the study document. `read` owns none of it and never will: it
 * consumes a file rather than composing one, which is why its `paths` are empty and its dot is
 * about something else entirely.
 */

import type { StepState } from '$lib/ui/types';
import type { IconRef } from '$lib/ui/icons';

export type StepId = 'keys' | 'study' | 'sign' | 'files' | 'read';

export interface StepDefinition {
  id: StepId;
  icon: IconRef;
  /** Root keys of the document this step owns. Empty means it owns no schema path. */
  paths: readonly string[];
}

/**
 * Order is the order of this array, and nothing else reads a step's position: the rail, the
 * back/next pair and the direction of the panel transition all derive from it.
 *
 * The study comes first because it is the work. Keys arrive already made, so opening on them met a
 * researcher with two secrets to file away before they had written a word — a chore standing where
 * the task should be. They are still needed before signing, which is where they now sit.
 */
export const STEPS: readonly StepDefinition[] = [
  {
    id: 'study',
    icon: 'document',
    // Three root keys the study step no longer owns: both identifiers are derived and shown on the
    // sign step, while platform and `minimum_client_version` are pinned with no control anywhere.
    // `?? 'sign'` fallback routes them to the step that now holds them.
    paths: [
      'schema_version',
      'issued_at',
      'expires_at',
      'title',
      'researcher',
      'purpose',
      'duration_hours',
      'consent',
      'collectors',
      'assigned_participant_id',
      'surveys',
      'interventions',
      'storage',
      'upload'
    ]
  },
  { id: 'keys', icon: 'key', paths: ['signer', 'export'] },
  { id: 'sign', icon: 'seal', paths: [] },
  { id: 'files', icon: 'send', paths: [] },
  // Last, because it is the only step that happens weeks after the other four — and `unlock`
  // against `sign`'s `seal`, which is the pair of acts it closes.
  { id: 'read', icon: 'unlock', paths: [] }
];

const OWNER = new Map<string, StepId>(
  STEPS.flatMap((step) => step.paths.map((path) => [path, step.id] as const))
);

/**
 * The two paths whose first segment lies about which step hosts them. Both key IDs are derived, and
 * the one control that can override either is the disclosure on the sign step — so an issue on
 * `signer.key_id` has to land there, while `signer.public_key` keeps landing on the Keys step where
 * the file that carries it is. Without this table an issue jump would change to the Keys step and
 * then find no `data-issue-host` to scroll to.
 */
const EXACT = new Map<string, StepId>([
  ['signer.key_id', 'sign'],
  ['export.researcher_key_id', 'sign']
]);

/**
 * Longest-prefix match on segment boundaries. `validate` emits `collectors.2.config.interval_millis`
 * and `interventions.0.id`, so the first segment decides. A path nothing claims — including the empty
 * path `validate` uses for the whole document — lands on `sign`, where the issue list is: an issue
 * with no home must still be visible somewhere.
 */
export function stepForPath(path: string): StepId {
  return EXACT.get(path) ?? OWNER.get(path.split('.')[0]) ?? 'sign';
}

export type { StepState };
