/**
 * The four steps, and which part of the document each one owns.
 *
 * The app's rail is a disclosure gate: forward-only, and a dot means *where you are*. Authoring is
 * iterative — set a collector, look at the quota, go back, change the consent text — so here every
 * step is reachable at any time and a dot means *whether that step's output exists and is valid*.
 * Same drawing, different rules.
 *
 * The step names are the catalogue's (`step.keys/study/sign/files`), not a set invented here. A
 * fifth step with no name in `lib/i18n` would be a step with no name in one of the two languages.
 */

import type { StepState } from '$lib/ui/types';
import type { IconRef } from '$lib/ui/icons';

export type StepId = 'keys' | 'study' | 'sign' | 'files';

export interface StepDefinition {
  id: StepId;
  icon: IconRef;
  /** Root keys of the document this step owns. Empty means it owns no schema path. */
  paths: readonly string[];
}

export const STEPS: readonly StepDefinition[] = [
  { id: 'keys', icon: 'key', paths: ['signer', 'export'] },
  {
    id: 'study',
    icon: 'document',
    paths: [
      'schema_version',
      'experiment_id',
      'configuration_id',
      'issued_at',
      'expires_at',
      'minimum_app_version',
      'title',
      'researcher',
      'purpose',
      'duration_hours',
      'consent',
      'collectors',
      'prompts',
      'storage',
      'upload'
    ]
  },
  { id: 'sign', icon: 'seal', paths: [] },
  { id: 'files', icon: 'send', paths: [] }
];

const OWNER = new Map<string, StepId>(
  STEPS.flatMap((step) => step.paths.map((path) => [path, step.id] as const))
);

/**
 * Longest-prefix match on segment boundaries. `validate` emits `collectors.2.config.interval_millis`
 * and `prompts.0.id`, so the first segment decides. A path nothing claims — including the empty
 * path `validate` uses for the whole document — lands on `sign`, where the issue list is: an issue
 * with no home must still be visible somewhere.
 */
export function stepForPath(path: string): StepId {
  return OWNER.get(path.split('.')[0]) ?? 'sign';
}

export type { StepState };
