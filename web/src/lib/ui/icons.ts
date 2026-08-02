/**
 * The icon vocabulary, drawn rather than depended on.
 *
 * Every mark is a few strokes on a 24-unit square with a 2-unit round-capped stroke, which is the
 * geometry `CollectorGlyphs.kt` uses on the phone (a unit square with `line = 0.09 * s`). The
 * eleven marks the app already draws are transcribed coordinate for coordinate, so a researcher
 * composing a study sees the same mark the participant will see beside it on the consent screen.
 * If `CollectorGlyphs.kt` moves, these move with it.
 *
 * Everything else is drawn in the same hand: single object, one internal mark, no fills except
 * where the app itself fills (the location dot, the connection dot, the keyboard keys).
 */

/** The eleven the app draws, then the site's own. */
export type IconName =
  // transcribed from CollectorGlyphs.kt
  | 'motion'
  | 'location'
  | 'connection'
  | 'data-volume'
  | 'screen'
  | 'app'
  | 'keyboard'
  | 'person'
  | 'contact'
  | 'clock'
  | 'language'
  // keys and the things they do
  | 'key'
  | 'key-sign'
  | 'key-open'
  | 'seal'
  | 'lock'
  | 'unlock'
  | 'fingerprint'
  | 'recover'
  | 'no-recover'
  // acts
  | 'download'
  | 'upload'
  | 'copy'
  | 'check'
  | 'cross'
  | 'plus'
  | 'minus'
  | 'chevron'
  | 'chevron-down'
  | 'arrow-right'
  | 'print'
  | 'link-out'
  | 'import'
  | 'export'
  | 'eye'
  | 'eye-off'
  | 'trash'
  // states and objects
  | 'alert'
  | 'info'
  | 'document'
  | 'json'
  | 'package'
  | 'archive'
  | 'storage'
  | 'sources'
  | 'bell'
  | 'send'
  | 'send-auto'
  | 'pause'
  | 'finish'
  | 'exit'
  | 'wifi'
  | 'mobile'
  | 'target'
  | 'researcher'
  | 'participant'
  | 'phone';

/** A fan of arcs over a point: reach, not a named network. Shared by `connection` and `wifi`. */
const FAN = `<path d="M7.49 17.56a4.8 4.8 0 0 1 9.02 0"/>
<path d="M4.22 16.37a8.28 8.28 0 0 1 15.56 0"/>
<path d="M0.95 15.18a11.76 11.76 0 0 1 22.1 0"/>
<circle cx="12" cy="19.2" r="1.8" fill="currentColor" stroke="none"/>`;

/** Head and shoulders. Shared by `person` and `participant`. */
const PERSON = `<circle cx="12" cy="7.44" r="4.08"/>
<path d="M4.78 18.1a7.68 7.44 0 0 1 14.44 0"/>`;

/** A phone. Shared by `screen` and `phone` — they are the same object. */
const SCREEN = `<rect x="5.76" y="2.4" width="12.48" height="19.2" rx="2.88"/>
<path d="M10.08 18.24h3.84"/>`;

/** The shaft and teeth both keys share, so `key-sign` and `key-open` read as siblings. */
const KEY_SHAFT = `<path d="m10.43 13.57 9.17-9.17M16.6 7.4l2.6 2.6M13.9 10.1l2.2 2.2"/>`;

export const ICONS: Record<IconName, string> = {
  // A quadratic oscillation: the sensor reports movement, not a movement. The control points sit
  // outside the box on purpose — a quadratic reaches exactly halfway to its control, so the
  // amplitude is what the numbers say.
  motion: `<path d="M1.92 12q5.04-14.4 10.08 0t10.08 0"/>`,

  location: `<path d="M12 21.6c0-6.72 6.96-7.68 6.96-12.48a6.96 6.96 0 0 0-13.92 0c0 4.8 6.96 5.76 6.96 12.48Z"/>
<circle cx="12" cy="9.12" r="2.4" fill="currentColor" stroke="none"/>`,

  connection: FAN,
  wifi: FAN,

  // Two arrows, one each way: this is a volume, not a destination.
  'data-volume': `<path d="M7.68 20.64V3.84M3.84 7.68 7.68 3.84l3.84 3.84M16.32 3.84v16.8M12.48 16.8l3.84 3.84 3.84-3.84"/>`,

  screen: SCREEN,
  phone: SCREEN,

  app: `<rect x="3.36" y="3.36" width="17.28" height="17.28" rx="4.8"/>
<circle cx="12" cy="12" r="2.16" fill="currentColor" stroke="none"/>`,

  // Keys and a space bar, filled rather than stroked so they survive at 18px.
  keyboard: `<rect x="1.92" y="5.76" width="20.16" height="12.48" rx="2.4"/>
<rect x="4.56" y="8.52" width="3.36" height="2.64" rx="0.72" fill="currentColor" stroke="none"/>
<rect x="10.32" y="8.52" width="3.36" height="2.64" rx="0.72" fill="currentColor" stroke="none"/>
<rect x="16.08" y="8.52" width="3.36" height="2.64" rx="0.72" fill="currentColor" stroke="none"/>
<rect x="6.24" y="13.8" width="11.52" height="2.4" rx="1.2" fill="currentColor" stroke="none"/>`,

  person: PERSON,
  participant: PERSON,

  contact: `<rect x="2.4" y="5.28" width="19.2" height="13.44" rx="1.92"/>
<path d="m3.36 6.72 8.64 6.48 8.64-6.48"/>`,

  clock: `<circle cx="12" cy="12" r="9.12"/><path d="M12 6.72v5.76l4.32 2.4"/>`,

  // The meridian: two arcs meeting at the poles read as a globe rather than a target.
  language: `<circle cx="12" cy="12" r="9.12"/><path d="M2.88 12h18.24"/>
<path d="M12 2.88c-6.24 5.28-6.24 12.96 0 18.24 6.24-5.28 6.24-12.96 0-18.24"/>`,

  key: `<circle cx="7.6" cy="16.4" r="4"/>${KEY_SHAFT}`,

  // A signet: the bow carries a stone, because this key marks rather than opens.
  'key-sign': `<circle cx="7.6" cy="16.4" r="4"/>
<circle cx="7.6" cy="16.4" r="1.4" fill="currentColor" stroke="none"/>${KEY_SHAFT}`,

  // The same key with the bow broken open where the shaft leaves it: this one opens things.
  'key-open': `<path d="M11.54 15.71a4 4 0 1 1-1.37-2.37"/>${KEY_SHAFT}`,

  seal: `<circle cx="12" cy="9.2" r="6"/>
<path d="m9.4 9.3 1.9 1.9 3.3-3.7"/>
<path d="m8.7 14.4-1.5 7.2 4.8-2.3 4.8 2.3-1.5-7.2"/>`,

  lock: `<rect x="4" y="10.5" width="16" height="11" rx="2.5"/>
<path d="M7.8 10.5V7.6a4.2 4.2 0 0 1 8.4 0v2.9"/>
<circle cx="12" cy="15.6" r="1.5" fill="currentColor" stroke="none"/>`,

  unlock: `<rect x="4" y="10.5" width="16" height="11" rx="2.5"/>
<path d="M7.8 10.5V7.6a4.2 4.2 0 0 1 8.4 0"/>
<circle cx="12" cy="15.6" r="1.5" fill="currentColor" stroke="none"/>`,

  fingerprint: `<path d="M12 4.6a7.4 7.4 0 0 0-7.4 7.4v3.2"/>
<path d="M12 4.6a7.4 7.4 0 0 1 7.4 7.4c0 1.7-.2 3.4-.7 5"/>
<path d="M12 8.3a3.7 3.7 0 0 0-3.7 3.7v4.4c0 1.1-.2 2.2-.6 3.2"/>
<path d="M12 8.3a3.7 3.7 0 0 1 3.7 3.7c0 2.9-.4 5.8-1.1 8.6"/>
<path d="M12 12v3.6c0 1.6-.2 3.2-.6 4.7"/>`,

  recover: `<path d="M20.4 12a8.4 8.4 0 1 1-2.7-6.2"/><path d="M20.8 3.2v4h-4"/>`,
  'no-recover': `<path d="M20.4 12a8.4 8.4 0 1 1-2.7-6.2"/><path d="M20.8 3.2v4h-4"/>
<path d="M4.6 19.4 19.4 4.6"/>`,

  download: `<path d="M12 3.6v11.4"/><path d="m7.2 10.2 4.8 4.8 4.8-4.8"/><path d="M4.2 19.8h15.6"/>`,
  upload: `<path d="M12 20.4V9"/><path d="m7.2 13.8 4.8-4.8 4.8 4.8"/><path d="M4.2 4.2h15.6"/>`,

  copy: `<rect x="8.6" y="8.6" width="11.8" height="11.8" rx="2.4"/>
<path d="M5.6 15.4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2"/>`,

  check: `<path d="m4.8 12.6 4.8 4.8 9.6-10.8"/>`,
  cross: `<path d="M6 6l12 12M18 6 6 18"/>`,
  plus: `<path d="M12 5v14M5 12h14"/>`,
  minus: `<path d="M5 12h14"/>`,
  chevron: `<path d="m9.5 5 7 7-7 7"/>`,
  'chevron-down': `<path d="m5 9.5 7 7 7-7"/>`,
  'arrow-right': `<path d="M4 12h15"/><path d="m13 6 6 6-6 6"/>`,

  print: `<path d="M6.6 9V3.6h10.8V9"/>
<path d="M6.6 17.4H4.8A1.8 1.8 0 0 1 3 15.6v-4.8A1.8 1.8 0 0 1 4.8 9h14.4a1.8 1.8 0 0 1 1.8 1.8v4.8a1.8 1.8 0 0 1-1.8 1.8h-1.8"/>
<rect x="6.6" y="14.4" width="10.8" height="6" rx="1.2"/>`,

  'link-out': `<path d="M13.8 4.2h6v6"/><path d="M19.8 4.2 11.4 12.6"/>
<path d="M18 14.4v4.2a1.8 1.8 0 0 1-1.8 1.8H5.4a1.8 1.8 0 0 1-1.8-1.8V7.8A1.8 1.8 0 0 1 5.4 6h4.2"/>`,

  import: `<path d="M9.6 20.4H5.4a1.8 1.8 0 0 1-1.8-1.8V5.4a1.8 1.8 0 0 1 1.8-1.8h4.2"/>
<path d="m14.4 15.6 4.2-3.6-4.2-3.6"/><path d="M18.6 12H8.4"/>`,

  export: `<path d="M14.4 3.6H5.4a1.8 1.8 0 0 0-1.8 1.8v13.2a1.8 1.8 0 0 0 1.8 1.8h9"/>
<path d="M20.4 12H9.6"/><path d="m16.8 8.4 3.6 3.6-3.6 3.6"/>`,

  eye: `<path d="M1.8 12s3.6-6.6 10.2-6.6S22.2 12 22.2 12 18.6 18.6 12 18.6 1.8 12 1.8 12Z"/>
<circle cx="12" cy="12" r="3.3"/>`,

  'eye-off': `<path d="M9.6 5.7A9.6 9.6 0 0 1 12 5.4c6.6 0 10.2 6.6 10.2 6.6a17.8 17.8 0 0 1-2.9 3.9"/>
<path d="M6.4 7.6A17.5 17.5 0 0 0 1.8 12s3.6 6.6 10.2 6.6a9.7 9.7 0 0 0 4-.8"/>
<path d="M10 10a2.8 2.8 0 0 0 4 4"/><path d="M3 3l18 18"/>`,

  trash: `<path d="M4.2 6.6h15.6"/>
<path d="M18 6.6v12.6a1.8 1.8 0 0 1-1.8 1.8H7.8A1.8 1.8 0 0 1 6 19.2V6.6"/>
<path d="M8.7 6.6V4.8a1.8 1.8 0 0 1 1.8-1.8h3a1.8 1.8 0 0 1 1.8 1.8v1.8"/>
<path d="M10.5 10.8v6M13.5 10.8v6"/>`,

  alert: `<path d="M12 3.6 22 20.4H2Z"/><path d="M12 9.6v4.6"/>
<circle cx="12" cy="17.4" r="1.1" fill="currentColor" stroke="none"/>`,

  info: `<circle cx="12" cy="12" r="9"/><path d="M12 11v5.6"/>
<circle cx="12" cy="7.7" r="1.1" fill="currentColor" stroke="none"/>`,

  document: `<path d="M13.4 2.8H6.8a2 2 0 0 0-2 2v14.4a2 2 0 0 0 2 2h10.4a2 2 0 0 0 2-2V8.4Z"/>
<path d="M13.4 2.8v5.6h5.8"/><path d="M8.4 13.2h7.2M8.4 16.8h4.8"/>`,

  // Braces: the shape of the thing, not a picture of a file.
  json: `<path d="M9.4 3.6c-2.2 0-3.2 1.1-3.2 3.2v2.4c0 1.6-.8 2.4-2.4 2.8 1.6.4 2.4 1.2 2.4 2.8v2.4c0 2.1 1 3.2 3.2 3.2"/>
<path d="M14.6 3.6c2.2 0 3.2 1.1 3.2 3.2v2.4c0 1.6.8 2.4 2.4 2.8-1.6.4-2.4 1.2-2.4 2.8v2.4c0 2.1-1 3.2-3.2 3.2"/>`,

  package: `<path d="m12 2.9 8.6 4.6v9L12 21.1 3.4 16.5v-9Z"/>
<path d="m3.6 7.6 8.4 4.5 8.4-4.5M12 12.1v9"/>`,

  archive: `<rect x="3" y="4.2" width="18" height="4.6" rx="1.6"/>
<path d="M4.8 8.8v9.4a1.8 1.8 0 0 0 1.8 1.8h10.8a1.8 1.8 0 0 0 1.8-1.8V8.8"/>
<path d="M10 12.6h4"/>`,

  storage: `<ellipse cx="12" cy="6" rx="8" ry="3.2"/>
<path d="M4 6v12c0 1.8 3.6 3.2 8 3.2s8-1.4 8-3.2V6"/>
<path d="M4 12c0 1.8 3.6 3.2 8 3.2s8-1.4 8-3.2"/>`,

  // A 2x3 grid of dots: several things, unnamed.
  sources: `<circle cx="8.4" cy="6" r="1.9" fill="currentColor" stroke="none"/>
<circle cx="15.6" cy="6" r="1.9" fill="currentColor" stroke="none"/>
<circle cx="8.4" cy="12" r="1.9" fill="currentColor" stroke="none"/>
<circle cx="15.6" cy="12" r="1.9" fill="currentColor" stroke="none"/>
<circle cx="8.4" cy="18" r="1.9" fill="currentColor" stroke="none"/>
<circle cx="15.6" cy="18" r="1.9" fill="currentColor" stroke="none"/>`,

  bell: `<path d="M18 9.6a6 6 0 1 0-12 0c0 5.2-2.2 6.8-2.2 6.8h16.4S18 14.8 18 9.6"/>
<path d="M13.9 19.6a2.2 2.2 0 0 1-3.8 0"/>`,

  send: `<path d="M20.8 3.2 3.4 10.4l6.9 3.3 3.3 6.9Z"/><path d="M20.8 3.2 10.3 13.7"/>`,

  // The plane plus the one thing that genuinely repeats.
  'send-auto': `<path d="M20.8 3.2 6.6 9.1l5.6 2.7 2.7 5.6Z"/><path d="M20.8 3.2 12.2 11.8"/>
<path d="M2.6 20.4a3.4 3.4 0 0 0 5.2.5"/><path d="M2.2 17.6v2.8H5"/>`,

  pause: `<path d="M9 5v14M15 5v14"/>`,
  finish: `<path d="M5.4 21V3.6"/><path d="M5.4 5.4h11.4l-2 3.6 2 3.6H5.4"/>`,

  exit: `<path d="M10.2 20.4H5.4a1.8 1.8 0 0 1-1.8-1.8V5.4a1.8 1.8 0 0 1 1.8-1.8h4.8"/>
<path d="M15.6 16.2 19.8 12l-4.2-4.2"/><path d="M19.8 12H9"/>`,

  mobile: `<path d="M4.2 20.4V17M9.4 20.4v-6.6M14.6 20.4V10M19.8 20.4V5.6"/>`,

  // No `battery`. The site has measured no power cost, so it draws none: a battery beside a control
  // is the claim whatever the label says, and an icon set is where that claim comes back from.
  target: `<circle cx="12" cy="12" r="8.4"/><circle cx="12" cy="12" r="3.4"/>
<path d="M12 1.2v3.6M12 19.2v3.6M1.2 12h3.6M19.2 12h3.6"/>`,

  // A signed document: the page, and the one mark that makes it a study.
  researcher: `<path d="M4.8 4.8a2 2 0 0 1 2-2h6.6L19 8.4v10.8a2 2 0 0 1-2 2H6.8a2 2 0 0 1-2-2Z"/>
<path d="M13.4 2.8v5.6H19"/><path d="m9 15.4 2 2 4-4.8"/>`
};

/**
 * The two flow specifications name a handful of these in camelCase, and one page calls the
 * `alert` mark `caution`. Accepting both costs a lookup and saves a route author a wrong guess.
 */
const ALIASES: Record<string, IconName> = {
  dataVolume: 'data-volume',
  keySign: 'key-sign',
  keyOpen: 'key-open',
  noRecover: 'no-recover',
  sendAuto: 'send-auto',
  linkOut: 'link-out',
  chevronDown: 'chevron-down',
  arrowRight: 'arrow-right',
  eyeOff: 'eye-off',
  globe: 'language',
  caution: 'alert',
  warning: 'alert',
  signature: 'seal',
  delete: 'trash',
  withdraw: 'exit',
  add: 'plus',
  remove: 'cross'
};

export type IconRef = IconName | keyof typeof ALIASES | (string & {});

/** Resolves an alias, or `null` for a name nothing draws — the caller renders no mark rather
 *  than a broken one. */
export function resolveIcon(name: IconRef): IconName | null {
  if (name in ICONS) return name as IconName;
  return ALIASES[name] ?? null;
}

export const ICON_NAMES = Object.keys(ICONS) as IconName[];
