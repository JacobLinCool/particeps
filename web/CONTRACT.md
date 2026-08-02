# Module contract

The site is static and runs entirely in the browser: no server, no network calls, no analytics. Keys
are generated in the tab and never leave it. Everything below is the shape each module must expose so
the parts can be written independently.

Read `src/lib/adc/types.ts` first — it is the schema contract, transcribed from
`core/study-definition`.

## Non-negotiable encoding facts

These were measured against the shipped `researcher-tools` CLI on a real build. They are not
guesses, and code that contradicts them produces a file the Android app rejects.

1. **String escaping** is Gson `JsonWriter`'s default table, *not* HTML-safe. Escape `"` → `\"`,
   `\` → `\\`, `\b` `\f` `\n` `\r` `\t` to their short forms, every other code point below `0x20`
   as `\u00xx` with lowercase hex, and U+2028 / U+2029 as ` ` / ` `. Leave everything
   else alone: `/`, `<`, `>`, `&`, `=`, `'`, DEL (0x7F), and all non-ASCII — CJK and emoji are
   emitted raw as UTF-8.
2. **`minimum_displacement_meters` is a Kotlin `Float`** and is written by Java's
   `Float.toString()`. Round the value to float32, then emit the shortest decimal that round-trips
   to that float32, always with at least one digit after the point. Observed: `0` → `0.0`,
   `5` → `5.0`, `100` → `100.0`, `0.25` → `0.25`, `0.1` → `0.1`, `0.3` → `0.3`,
   `9999.999` → `9999.999`, `1234.5678` → `1234.5677`. JavaScript's `Number.prototype.toString`
   gives the shortest form for a *double* and is wrong here.
3. **Every other number** is an integer literal matching `-?(0|[1-9][0-9]*)`. No exponents, no
   leading zeros, no trailing `.0`.
4. **`tink_hpke_public_keyset`** is re-emitted from Gson's `JsonObject.toString()`: compact, no
   whitespace, keys in the order they appeared. Emit it in the same order the keyset was built in.
5. **Root key order is fixed** by `StudyConfigurationCodec.encode` and is not alphabetical. The v1
   shape includes `assigned_participant_id`, `surveys`, and `interventions`; the former prompt shape
   is invalid and has no compatibility path. Take the complete order from that function.
6. **`upload: null`** encodes as `"upload":{}`.

## `src/lib/adc/canonical.ts`

```ts
export function formatFloat(value: number): string;
export function escapeJsonString(value: string): string;
export function canonicalize(configuration: StudyConfiguration): string;
export function canonicalBytes(configuration: StudyConfiguration): Uint8Array;
```

`canonicalize` must produce the exact bytes `researcher-tools canonicalize` would. This is the one
module with a byte-level test against the real CLI.

## `src/lib/adc/schema.ts`

```ts
export interface Issue { path: string; code: string }
export function validate(configuration: StudyConfiguration): Issue[];
export function emptyConfiguration(): StudyConfiguration;
export function defaultCollector(id: CollectorId): CollectorConfig;
```

`validate` returns every problem rather than throwing on the first, because the UI marks fields.
`code` is a stable identifier the i18n layer maps to a message; never a sentence.

## `src/lib/adc/crypto.ts`

```ts
export interface SigningKeyPair { privatePkcs8Base64: string; publicX509Base64: string }
export function generateSigningKeyPair(): SigningKeyPair;
export function sign(configurationBytes: Uint8Array, privatePkcs8Base64: string): Uint8Array;
export function verify(configurationBytes: Uint8Array, signature: Uint8Array, publicX509Base64: string): boolean;
export function fingerprint(publicX509Base64: string): string;
```

Ed25519 via `@noble/ed25519`. The private key is PKCS#8 DER, the public key X.509
SubjectPublicKeyInfo, both Base64 — byte-identical to what `KeyPairGenerator.getInstance("Ed25519")`
emits, because the CLI reads these files. The fingerprint is SHA-256 over the *decoded* public key,
first 16 bytes, as eight uppercase groups of four hex characters separated by single spaces.

## `src/lib/adc/tink.ts`

```ts
export interface HpkeKeyset { publicKeyset: TinkKeyset; privateKeyset: TinkKeyset }
export function generateHpkeKeyset(): HpkeKeyset;
```

Tink `DHKEM_X25519_HKDF_SHA256 / HKDF_SHA256 / AES_256_GCM`. The `value` field is a hand-encoded
protobuf, Base64 (standard alphabet, padded). `HpkePublicKey` is `{2: HpkeParams{1:1, 2:1, 3:2},
3: publicKey}`; `HpkePrivateKey` is `{2: HpkePublicKey, 3: privateKey}`; field 1 (version 0) is
omitted in both. `keyId` is a random uint32 that must be non-zero and equal to `primaryKeyId`, and
`outputPrefixType` is `TINK`. Compare against `researcher-tools/examples/INSECURE-demo-hpke-*.json`
— those are real, working keysets and the format must match them exactly.

## `src/lib/adc/envelope.ts`

```ts
export function encodeEnvelope(signerKeyId: string, configurationBytes: Uint8Array, signature: Uint8Array): Uint8Array;
```

`ADCCFG01` (8 ASCII bytes), then big-endian `uint16` key-ID length, `int32` configuration length,
`uint16` signature length, then the key ID as UTF-8, the configuration bytes, and the signature.

## i18n

The catalogues live in `src/lib/i18n` (`types.ts` for the `Messages` shape, `en.ts`, `zh-TW.ts`,
and `messages.ts` as the seam). The reactive half is `src/lib/ui/i18n.svelte.ts`, because runes
compile only in a `.svelte.ts` module and every component that reads a message is already importing
from `$lib/ui`.

```ts
export type Locale = 'en' | 'zh-TW';
export const messages: Record<Locale, Messages>;
```

The chosen locale persists in `localStorage` under `adc.locale`, which the inline script in
`app.html` also reads so the first paint is not in the wrong language. With nothing stored the
browser decides. `<html lang>` is written as `zh-Hant-TW` rather than `zh-TW`, because CSS language
matching is prefix-based and the CJK type block hangs off `:lang(zh-Hant)`. Both locales must have
the same keys, and `tests/i18n.spec.ts` asserts it recursively.

## `src/lib/ui`

Nord palette, both themes. Shared components: the language control, the step rail, the icon set
(drawn as inline SVG, no icon dependency), field controls, and the download tiles. Text is the last
resort here: the interface is meant to be legible without reading, with words added only where a
picture genuinely cannot carry the meaning.

## Tests

`pnpm test` runs the unit suites, including `tests/compat.spec.ts`, which shells out to
`researcher-tools` — rebuilding its distribution before the suite — and asserts byte for byte that this
encoder and the Kotlin one agree, then signs a study here and has `check-config` accept it.

`pnpm e2e` is separate because it needs a build, a static server, and a browser. It drives the
researcher page the way a person would and ends by handing the resulting `.adccfg` to the same CLI.
It exists because the unit suites prove the library and prove nothing about the page: its first run
found a collector card marked `aria-disabled` while switched off, which made the only control that
could switch it on unavailable to assistive technology, and nothing else in the project would have
caught that.

Two more browser runs share that server and gate on the researcher page alone. `pnpm e2e:one-line`
measures every leaf run of interface text and fails if one needs a second line, because the length a
string may be is a constraint and this is the thing that measures it. `pnpm e2e:units` switches on
all seven collectors and fails if a control shows a number in the unit the *file* stores rather than
the unit a person states — `100000` beside `Hz`, `1073741824` where `1 GiB` was meant. Both say in
their own headers what they cannot catch; read that before reading a green run as a proof.

```
pnpm build
pnpm exec vite preview --port 4173 --strictPort   # or any static server over build/
pnpm e2e
pnpm e2e:one-line
pnpm e2e:units
```
