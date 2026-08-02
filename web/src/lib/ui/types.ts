/** Shared prop vocabulary. Anything more than one component needs lives here. */

import type { IconRef } from './icons';

/** The six colour roles plus the three ink steps, as a prop. */
export type Tone =
  | 'ink'
  | 'soft'
  | 'faint'
  | 'accent'
  | 'signal'
  | 'caution'
  | 'danger'
  | 'binary'
  | 'voice';

/** What a step's output is, never where the reader is. Derived, never stored. */
export type StepState = 'empty' | 'partial' | 'complete' | 'blocked';

export interface StepDef {
  id: string;
  /** Shown beside the mark on wide viewports; the accessible name on narrow ones. */
  label: string;
  icon?: IconRef;
  state?: StepState;
  /** Issues under this step's paths. Drawn as a badge, always live. */
  count?: number;
}

/**
 * Structurally the `Issue` from `lib/adc/schema`, widened by an optional `params` so the
 * parameterised messages in the catalogue (`length_range`, `number_range`, `document_too_large`)
 * can be rendered without the UI knowing the bounds.
 */
export interface UiIssue {
  path: string;
  code: string;
  params?: Record<string, number>;
}

/** Where an artefact is going, which is also the order the hand-off columns run in. */
export type Destination = 'hold' | 'store' | 'send';

/** How far an artefact may travel. `secret` is the only one that draws differently. */
export type Secrecy = 'secret' | 'archive' | 'distribute';

export type Density = 'inline' | 'plaque';

/** Which page owns the header, which decides whether the switcher underline is accent or voice. */
export type PageId = 'researcher' | 'participant';
