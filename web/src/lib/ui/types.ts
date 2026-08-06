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
 * Structurally the `Issue` from `lib/particeps/schema`, widened by an optional `params` so the
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

/**
 * Everything a bounded control needs to say a number in the unit a person uses.
 *
 * The shape lives here and the instances live in `routes/researcher/scales.ts`, because the
 * conversion between a schema's unit and a researcher's is a fact about the schema, while
 * *needing* one is a fact about the control — and a component under `lib/ui` may not reach into a
 * route to find out what its own props are.
 *
 * `box: false` is the shape a control takes when its legal range crosses a unit boundary, so no
 * single word names both ends: 8 MiB to 8 GiB, 1 hour to 1 year. There is nothing honest to put in
 * a number box, so there is none, and `ladder` is the reachable set the slider indexes instead.
 */
export interface Scale {
  /** False is shape B: no box, no affix, and the humanised readout is the value. */
  box: boolean;
  /** The word beside the box, from `m.unit.*`. Unused when `box` is false. */
  affix: string;
  /** Stored → control space. Exact for every value the control can produce. */
  toHuman(stored: number): number;
  /** Control space → the integer unit stored in the signed configuration. */
  toStored(human: number): number;
  /** All in control space. */
  min: number;
  max: number;
  step: number;
  presets: readonly number[];
  /** Shape B only: the reachable set, ascending. The slider indexes it, one rung per arrow press. */
  ladder?: readonly number[];
  scale: 'linear' | 'log';
  /** Humaniser, in control space. Renders the readout and every chip. */
  format(human: number): string;
}
