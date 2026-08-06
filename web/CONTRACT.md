# Web Protocol v1 contract

The site is static and runs entirely in the browser: no server, network calls, analytics, or key
persistence. The normative cross-language specification lives in `../protocol/v1/`; this document
maps that protocol onto the Web source so a new contributor can find a behavior and its tests.

## Where to start

| Concern | Source | Focused tests |
| --- | --- | --- |
| Configuration types and bounds | `src/lib/particeps/types.ts` | `tests/hostile.spec.ts` |
| RFC 8785 JCS and configuration projection | `src/lib/particeps/canonical.ts` | `tests/canonical.spec.ts` |
| Raw Ed25519/X25519 keys | `src/lib/particeps/crypto.ts` | `tests/crypto.spec.ts` |
| Signed configuration framing | `src/lib/particeps/envelope.ts` | `tests/crypto.spec.ts` |
| Configuration closed-world reader | `src/routes/researcher/parse.ts` | `tests/hostile.spec.ts` |
| Encrypted bundle reader | `src/lib/particeps/bundle.ts` | `tests/bundle.spec.ts` |
| Authoring state and stale-signature rule | `src/routes/researcher/draft.svelte.ts` | `tests/researcher-draft.spec.ts` |
| Downloaded artifacts | `src/routes/researcher/artifacts.ts` | `tests/researcher.spec.ts` |
| Immutable join URI and local QR | `src/lib/particeps/join.ts`, `src/routes/researcher/JoinLinkPanel.svelte` | `tests/join.spec.ts`, shared `join-link-vectors.json` |
| Local deterministic protocol boundary | all of the above | `tests/compat.spec.ts` |

There is no compatibility reader. Former Protocol v1 Tink keysets, protobuf prefixes, PKCS#8,
X.509, padded Base64, floating-point displacement, and variable signature framing are invalid.

## Canonical configuration

`canonicalize(value)` is a generic RFC 8785 JCS primitive. It recursively sorts object member names
by UTF-16 code units, uses ECMAScript JSON primitive serialization, and rejects non-finite numbers,
unsupported values, cycles, sparse arrays, and lone surrogates. `parseCanonicalJson(bytes)` accepts
only fatal UTF-8 whose JCS re-encoding is byte-identical; this also rejects whitespace, duplicate
members, alternate number spellings, and trailing content.

The configuration's typed in-memory model uses `upload: null` when upload is disabled. Protocol v1
has one wire shape, `"upload":{}`. `configurationValue`, `canonicalizeConfiguration`, and
`canonicalConfigurationBytes` own this explicit boundary projection. They also normalize instants
and the set-like network transport list exactly as the Android codec does. Schema rules do not leak
into the generic JCS primitive.

Configuration-specific Protocol v1 changes are:

- `platform` is exactly `"android"`;
- `minimum_client_version` is a positive canonical decimal string;
- `signer.public_key` is a raw 32-byte Ed25519 key;
- `export.hpke_public_key` is a raw 32-byte X25519 key;
- both keys use canonical unpadded base64url;
- `location.v1.minimum_displacement_millimeters` is an integer JSON number.

`parseConfiguration` first requires canonical, closed-world bytes. It builds the typed value
without defaults, re-encodes it to prove Android normalization equality, and applies all schema
validation. For `.partcfg` it then verifies signer identity and the Ed25519 signature. It never
drops an unknown member or repairs an old shape.

## Keys and signed configuration

Both private artifacts are one unpadded base64url string containing exactly 32 raw bytes. Public
halves are always derived locally. Nothing reads an accompanying public key or wrapper metadata.

`PTCCFG01` is exactly:

```text
magic[8] | signer_key_id_length u16 BE | configuration_length u32 BE |
signer_key_id UTF-8 | configuration JCS | Ed25519 signature[64]
```

The signature covers the configuration JCS bytes. There is no signature-length member and the
container must end after byte 64 of the signature.

## Encrypted bundle reader

The browser reader is a bounded convenience reader; large-study analysis belongs in the offline
Python pipeline. `bundle.ts` implements the `PTCEXP01` container, its HPKE layer, and the exact
context bytes that bind both cryptographic layers as
[`../protocol/v1/README.md`](../protocol/v1/README.md) specifies them.

The authenticated document uses exact root objects for producer, signature provenance, and
experiment state. Every sequence, counter, wall time, and monotonic time is a canonical decimal
string, so values above JavaScript's safe-integer limit are never rounded. A successful read has
verified both AEAD layers, JCS bytes, the embedded configuration digest/signature, every repeated
identity, event count, event range, and event ordering. A failure publishes no plaintext object.

## Authoring and artifacts

`draft.svelte.ts` is the single state owner. The editable configuration carries inert IDs; the
document derives experiment, configuration, signer, and export key IDs. A signature is associated
with one canonical string, and any edit immediately retires the signature and envelope.

The four downloads are raw Ed25519 private key, raw X25519 private key, canonical configuration
JSON, and signed `.partcfg`. The two configuration artifacts do not exist until signing succeeds.
Private bytes stay in the tab and are never written to browser storage.

## Immutable join artifact

After an envelope exists, `JoinLinkPanel.svelte` accepts one artifact URL and delegates the exact
Protocol v1 URL / URI bytes to `join.ts`. It renders the resulting QR locally with the bundled
`qrcode` library. There is no QR service, fetch, upload, polling, or browser persistence. The URI
binds the envelope's complete SHA-256 and signing fingerprint; editing the study retires the
envelope and therefore the join artifact.

`join.ts` implements the same deliberately narrow HTTPS profile and uppercase percent encoding as
Kotlin `JoinLink`. It never delegates canonicalization to WHATWG `URL`: that parser is used only as
an equality check after the lexical profile succeeds. Personalized authoring additionally rejects
the assigned participant ID anywhere in the URL and requires a final opaque base64url path token
of at least 22 characters. The normative syntax, bounds, trust flow, and common Kotlin/TypeScript
corpus are in `../protocol/v1/README.md` and `../protocol/v1/join-link-vectors.json`.

## Verification

```sh
pnpm test
pnpm check
pnpm build
```

`tests/compat.spec.ts` consumes the normative shared valid/hostile corpus under `../protocol/v1/`
and also keeps focused RFC 8032 and deterministic local round-trip cases. Browser E2E scripts
remain separate because they require a built static site and Playwright.
