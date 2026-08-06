/**
 * The four identifiers, derived rather than typed.
 *
 * `experiment_id` names the study across configurations; `configuration_id` names one file of it.
 * Both have to match `ID_PATTERN` — `[a-z0-9][a-z0-9-]{2,63}` — for every title on earth, including
 * the ones that contain no ASCII at all, which is the normal case for a Traditional Chinese study
 * title. So a stem is taken where one exists and a digest stands in where it does not.
 *
 * `signer.key_id` and `export.researcher_key_id` are the other two. They name a *key*, so they are
 * a pure function of the public half of the key they name — computed by the document rather than
 * typed into it. A researcher has no basis for choosing either, and the property that matters falls
 * out for free: the second configuration under the same signer gets the same `signer.key_id` by
 * construction, whether the key was generated here, imported, or read back out of a configuration
 * file. The canonical raw 32-byte public key is the hash input.
 *
 * Pure, and deliberately ignorant of `canonical.ts`: `deriveConfigurationId` takes the canonical
 * string as an argument, so the whole module is testable from plain strings.
 *
 * Six base-36 characters is 30 bits, 1.07×10⁹ values. Analysis partitions by experiment and
 * configuration before event-level `(participant_instance_id, sequence_number)` de-duplication,
 * so a configuration digest collision could merge two arms of a study; at ten configurations the chance
 * of one is ≈4×10⁻⁸, and six characters is the shortest width where that number is negligible.
 *
 * A key namespace is not a ten-configuration namespace, which is why `keyTag` is a separate
 * primitive rather than a second caller of `tag`. Sixty-four bits, thirteen base-36 characters:
 * the rendering is a bijection from the 2⁶⁴ truncated digests onto its 13-character range, so two
 * IDs collide iff the two digests do. Modelling truncation as uniform, `1 − exp(−n(n−1)/2⁶⁵)` is
 * 2.7×10⁻¹⁴ at a thousand keys and 2.7×10⁻⁸ at a million — against populations that are a lab's own
 * keys, or the keys named in one institution's `trustedSigningKeys` map, so tens to hundreds.
 * `tag`'s thirty bits reach 4.7×10⁻⁴ at a thousand and are certain at a million. Adversarially, a
 * chosen ID is a 64-bit preimage; an untargeted pair costs ~2³² and yields two attacker keys
 * colliding with each other, which buys nothing. `ConfigurationVerifier` never trusts the ID
 * anyway — unpinned it verifies with the public key inside the signed bytes, pinned it additionally
 * requires the pinned key's encoding to equal the declared one. The ID is a name, and 64 bits is
 * sized against accidental confusion in a file listing.
 *
 * The two key IDs are deliberately *not* related to `fingerprint()`, and must not come to look like
 * it. The fingerprint exists so a participant can compare a whole 128-bit value out of band against
 * published recruitment material; publishing a second string that shares its leading characters
 * would teach prefix comparison, which is exactly what the fingerprint has to resist — grinding
 * keypairs until SHA-256 of the SPKI opens with a chosen 32 bits is hours of one ordinary CPU. So:
 * a domain-separated hash input,
 * a different alphabet and case (lowercase base-36 versus uppercase hex), and a different shape
 * (one unbroken 13-character run behind a word stem, versus eight groups of four). The tag is never
 * grouped into quads; the visual difference is load-bearing. What a researcher actually needs from
 * the relation — *this ID names this key* — is delivered by derivation and co-location instead:
 * both strings are functions of the same public key, and `deriveSignerKeyId(signer.public_key)`
 * recomputes the ID from the file, which is a check a person can run and a shared prefix is not.
 *
 * Romanising CJK to make a readable stem was rejected: it needs a transliteration table on a site
 * that ships no external asset by policy, it produces Mandarin pinyin for a Taiwanese researcher's
 * Traditional Chinese, and tone-less pinyin collides on homophones far more often than 30 bits do.
 * `study-8kq2m1` is opaque; a wrong readable ID is worse.
 */

import { sha256 } from '@noble/hashes/sha2.js';
import { decodeBase64Url } from './crypto';

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

/* ---- the two key names ---------------------------------------------------------------------
 *
 * Domain separation, so the same key never derives one string under two roles. Twenty-seven ASCII
 * bytes each, prefixed to the key before hashing. */
const SIGNER_DOMAIN = 'particeps:signer-key-id:v1:';
const EXPORT_DOMAIN = 'particeps:export-key-id:v1:';

const KEY_BYTES = 32;
const TAG_CHARACTERS = 13;

/**
 * 64 bits of SHA-256 over a domain-separated public key, as 13 base-36 characters.
 *
 * Always `[0-9a-z]{13}`: the value is below 2⁶⁴ and 2⁶⁴ < 36¹³, so `toString(36)` yields at most
 * thirteen characters and `padStart` makes it exactly thirteen with a character that is itself in
 * the alphabet. Leading zeros are expected and kept — a uniform 64-bit value lands below 36¹² about
 * 26% of the time, and the padding is what makes the width constant. Twelve would not do:
 * 36¹² < 2⁶⁴, so thirteen is the minimum width for 64 bits.
 *
 * Not exported: the two callers below are the whole interface.
 */
function keyTag(domain: string, publicKey: Uint8Array): string {
  const label = ENCODER.encode(domain);
  const input = new Uint8Array(label.length + publicKey.length);
  input.set(label);
  input.set(publicKey, label.length);
  const digest = sha256(input);
  let value = 0n;
  for (let index = 0; index < 8; index += 1) value = (value << 8n) | BigInt(digest[index]);
  return value.toString(36).padStart(TAG_CHARACTERS, '0');
}

/**
 * `signer.key_id`. `''` when the argument is not a canonical raw Ed25519 public key.
 *
 * Total, and the two outcomes are the only two: a legal ID exactly 20 characters long, or the empty
 * string a fresh document already carries — so `validate` reports the same `required` issue on the
 * same path it always did and nothing downstream changes shape.
 */
export function deriveSignerKeyId(publicKey: string): string {
  let raw: Uint8Array;
  try {
    raw = decodeBase64Url(publicKey, KEY_BYTES);
  } catch {
    return '';
  }
  return `signer-${keyTag(SIGNER_DOMAIN, raw)}`;
}

/**
 * `export.researcher_key_id`. `''` when the argument is not a canonical raw X25519 public key.
 */
export function deriveExportKeyId(publicKey: string): string {
  let raw: Uint8Array;
  try {
    raw = decodeBase64Url(publicKey, KEY_BYTES);
  } catch {
    return '';
  }
  return `export-${keyTag(EXPORT_DOMAIN, raw)}`;
}
