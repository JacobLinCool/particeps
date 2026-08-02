/**
 * The two identifiers, derived rather than typed.
 *
 * `experiment_id` names the study across configurations; `configuration_id` names one file of it.
 * Both have to match `ID_PATTERN` — `[a-z0-9][a-z0-9-]{2,63}` — for every title on earth, including
 * the ones that contain no ASCII at all, which is the normal case for a Traditional Chinese study
 * title. So a stem is taken where one exists and a digest stands in where it does not.
 *
 * Pure, and deliberately ignorant of `canonical.ts`: `deriveConfigurationId` takes the canonical
 * string as an argument, so the whole module is testable from plain strings.
 *
 * Six base-36 characters is 30 bits, 1.07×10⁹ values. The de-duplication key downstream is
 * `experiment_id + configuration_id + collector_id + sequence_number` (see `docs/researcher-guide`),
 * so a digest collision would silently merge two arms of a study; at ten configurations the chance
 * of one is ≈4×10⁻⁸, and six characters is the shortest width where that number is negligible.
 *
 * Romanising CJK to make a readable stem was rejected: it needs a transliteration table on a site
 * that ships no external asset by policy, it produces Mandarin pinyin for a Taiwanese researcher's
 * Traditional Chinese, and tone-less pinyin collides on homophones far more often than 30 bits do.
 * `study-8kq2m1` is opaque; a wrong readable ID is worse.
 */

import { sha256 } from '@noble/hashes/sha2.js';

const ENCODER = new TextEncoder();

/** 30 bits of SHA-256 as six base-36 characters. Always `[0-9a-z]{6}`. */
export function tag(text: string): string {
  const bytes = sha256(ENCODER.encode(text));
  const value = ((bytes[0] << 22) | (bytes[1] << 14) | (bytes[2] << 6) | (bytes[3] >>> 2)) >>> 0;
  return value.toString(36).padStart(6, '0');
}

/** `[a-z0-9-]` out of arbitrary text. May be empty — every caller has to handle that. */
export function asciiSlug(text: string): string {
  return text
    .normalize('NFKD')
    // NFKD alone leaves the combining marks behind, and each one would become a hyphen.
    .replace(/\p{M}+/gu, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 64)
    .replace(/-+$/, '');
}

/** What a title that yields no legal stem is called, so the digest still has something to hang on. */
export const FALLBACK_STEM = 'study';

/**
 * The study's name, from the one thing the researcher already wrote. Satisfies `ID_PATTERN` for
 * every string.
 *
 * It is a pure function of the title, so two studies sharing a title share an id. That is right for
 * the cross-configuration group key and wrong for two genuinely different studies — and this page
 * is static, holds nothing across tabs, and has no registry, so it cannot detect the case and does
 * not pretend to. What it does instead: never claim uniqueness, show the value before it is signed,
 * and offer an override.
 */
export function deriveExperimentId(title: string): string {
  const stem = asciiSlug(title);
  if (stem.length >= 3) return stem;
  return `${stem || FALLBACK_STEM}-${tag(title.normalize('NFC'))}`;
}

/**
 * The file's name, from the file's own bytes. `canonicalWithoutId` is `canonicalize(document)` with
 * `configuration_id` blanked — blanking it is what makes this a fixed point, because the hash input
 * then does not depend on the value being produced.
 *
 * There is no override for this one. Pinning it would break the only property it has: it changes
 * exactly when the document changes, which is what the guide used to ask a researcher to do by
 * hand. Two documents sharing a pinned `configuration_id` would merge two arms in every downstream
 * de-duplication, and the digest is what makes that impossible.
 */
export function deriveConfigurationId(experimentId: string, canonicalWithoutId: string): string {
  const suffix = tag(canonicalWithoutId);
  const stem = experimentId.slice(0, 64 - 1 - suffix.length).replace(/-+$/, '') || FALLBACK_STEM;
  return `${stem}-${suffix}`;
}
