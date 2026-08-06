# Particeps Protocol v1

This directory is the normative, language-neutral contract for Particeps Protocol v1. Kotlin,
TypeScript, Python, and future Swift implementations are conforming implementations; none of
them is the specification.

Protocol v1 is a destructive pre-1.0 replacement, and this document carries its second and final
identity. `schema_version` stays the JSON number `1`. Every identity string is new: the signed
configuration magic is `PTCCFG01`, the encrypted export magic is `PTCEXP01`, the bundle format is
`particeps-research-bundle-v1`, the join scheme is `particeps://join/v1`, the upload media type is
`application/vnd.particeps.research-bundle`, and the routing headers are `X-Particeps-*`.

Two classes of input are therefore invalid rather than old. Artifacts made by the pre-v1
implementation are invalid, as they always were. Artifacts bearing the retired Android Data
Collector identity — `ADCCFG01`, `ADCEXP01`, `research-bundle-v1`, `adc://join/v1`,
`application/vnd.adc.research-bundle`, or any `X-ADC-*` header — are invalid too. Neither class is
an earlier dialect of this protocol. Readers MUST NOT retain a parser, migration path, dual
interpretation, alias, sniffing heuristic, or fallback for either, and MUST fail closed on them
exactly as on random bytes. The hostile corpus in this directory carries executable coverage for
both.

The companion [`collector-catalog.json`](collector-catalog.json) is the closed-world collector and
event schema. [`conformance-vectors.json`](conformance-vectors.json) and
[`join-link-vectors.json`](join-link-vectors.json) are the executable valid and hostile corpora.
Start with these files; platform code must not define a second contract.

The key words MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, and MAY are to be
interpreted as described by RFC 2119 and RFC 8174.

## Common encoding rules

- All binary integers are unsigned, big-endian, and use the exact width stated below.
- JSON is UTF-8 RFC 8785 JSON Canonicalization Scheme (JCS). Duplicate object members,
  noncanonical bytes, malformed UTF-8, non-integral JSON numbers, and trailing bytes are invalid.
- JSON numbers are permitted only where the schema supplies bounded integral minimum and maximum
  values. Sequence numbers, byte counts, Unix times, monotonic times, and client build numbers are
  canonical decimal strings matching `0|[1-9][0-9]*`.
- UUIDs use lowercase RFC 4122 text in JSON and headers, and their 16 network-order bytes in binary
  framing. Producers generate cryptographically random version-4 bundle IDs.
- SHA-256 values use 64 lowercase hexadecimal characters in JSON and in `X-Particeps-*` headers.
- Ed25519 and X25519 keys are raw 32-byte values encoded as unpadded base64url. Signatures are raw
  64-byte Ed25519 signatures encoded the same way in JSON. Tink JSON, protobuf keysets, X.509,
  PKCS#8, padded base64, and standard-base64 wire keys are invalid.
- Every decoder is closed-world. An unknown member, enum, collector, payload type, platform, key
  context, or framing byte fails the whole artifact.
- Implementations MUST reject values before allocating from a claimed length. A complete
  `PTCEXP01` upload body is limited to 33,554,432 bytes (32 MiB).

## Signed configuration (`PTCCFG01`)

The configuration is an Android-targeted, closed-world JCS object with `schema_version` equal to
the JSON number `1`, `platform` equal to `"android"`, and `minimum_client_version` encoded as a
canonical decimal string. Android and future iOS configurations may share `experiment_id`; they
MUST use different `configuration_id` values and signatures. The Android client rejects every
other platform.

All configuration quantities are integral physical units. In particular,
`location.v1.minimum_displacement_millimeters` replaces the former floating-point metre value.
The collector portion of the configuration is defined by
[`collector-catalog.json`](collector-catalog.json).

The envelope is exactly:

```text
offset    size  value
0         8     ASCII "PTCCFG01"
8         2     signer_key_id_length (u16)
10        4     configuration_length (u32)
14        K     signer_key_id UTF-8
14+K      N     configuration_jcs
14+K+N    64    Ed25519 signature
```

`signer_key_id_length` is in `3..64`, and its strict UTF-8 value matches
`[a-z0-9][a-z0-9-]{2,63}`. `configuration_length` is in `2..1,048,576`. The envelope ends after the
signature. The signed message is exactly `configuration_jcs`, without a framing prefix. The key ID
must equal `configuration.signer.key_id`. The verifier obtains the raw Ed25519 public key from
`configuration.signer.public_key`, verifies the fixed 64-byte signature, then applies signer
pinning policy. A valid self-contained signature proves integrity, not publisher identity.

The configuration SHA-256 used everywhere below is SHA-256 over `configuration_jcs`, not over the
envelope.

## Immutable join link (`particeps://join/v1`)

A join link is a transport pointer to one immutable `PTCCFG01` artifact. Its exact ASCII form is:

```text
particeps://join/v1?artifact=<percent-encoded-url>&sha256=<64-lowercase-hex>&signer_fingerprint=<32-uppercase-hex>
```

The query order is fixed. RFC 3986 unreserved bytes are literal; every other artifact-URL byte is
percent encoded with uppercase hexadecimal. Duplicate, missing, reordered, or unknown query
members, lowercase escapes, decoded non-ASCII, and links longer than 4,096 bytes are invalid.
`signer_fingerprint` is the first 16 bytes of SHA-256 over the raw Ed25519 public key. It has no
spaces on the wire.

To prevent Java `URI` and WHATWG `URL` from silently accepting different text for one locator, the
decoded artifact URL uses this deliberately narrow canonical HTTPS profile:

- at most 2,048 ASCII bytes and exactly lowercase `https://`;
- a lowercase DNS-style host of labels in `[a-z0-9-]`, each 1–63 bytes, with at least one ASCII
  letter overall; no user information, IP literal, trailing dot, or internationalized host;
- no port, or a canonical decimal port in `1..65535` other than the redundant default `443`;
- one or more non-empty path segments containing only `[A-Za-z0-9._~-]`; `.` and `..` segments,
  repeated slashes, percent escapes, a trailing slash, query, and fragment are invalid.

This profile is intentionally sufficient for an immutable filename or opaque path token, not a
general browser URL. A personalized artifact MUST use at least 128 bits of random opaque path
material (the authoring tools require a final base64url segment of at least 22 characters), and
MUST NOT put an assigned participant ID in the URL or link.

The Android app rejects a join while any active study or pending deletion exists. It performs one
bounded GET with redirects and implicit retries disabled, stages under no-backup storage, checks
the complete artifact SHA-256, then executes the ordinary Ed25519 verification and fingerprint /
consent flow. The host cannot replace accepted bytes: digest mismatch fails before signature
verification. Staging is cleared on process startup, before each attempt, and after success or
failure. There is no polling, refresh, replacement, background update, or assigned participant ID
in the join URI.

## Encrypted bundle (`PTCEXP01`)

The only cryptographic suite is RFC 9180 base mode (`mode = 0x00`) with:

| Parameter | Value |
| --- | --- |
| KEM | DHKEM(X25519, HKDF-SHA256), `0x0020` |
| KDF | HKDF-SHA256, `0x0001` |
| AEAD | AES-256-GCM, `0x0002` |

Each bundle creates an independent random 32-byte AES-256-GCM content key and a fresh random
12-byte content nonce. HPKE seals the 32-byte content key to the configuration's researcher X25519
public key. With the fixed suite, `enc` is 32 bytes and the sealed content-key ciphertext is 48
bytes including its tag.

The container is exactly; document ciphertext consumes the remainder of the file:

```text
offset     size  value
0          8     ASCII "PTCEXP01"
8          16    bundle_id UUID bytes
24         32    configuration_sha256
56         2     researcher_key_id_length (u16)
58         12    AES-256-GCM content nonce
70         K     researcher_key_id UTF-8
70+K       80    HPKE wrapped content key: enc[32] || sealed_key[48]
150+K      C     encrypted document and 16-byte GCM tag, to end of file
```

`researcher_key_id_length` is in `3..64`; the decoded value matches
`[a-z0-9][a-z0-9-]{2,63}`. `C` is greater than the 16-byte GCM tag. An automatic-upload container is
at most 32 MiB; a manual export has no 32 MiB wire limit and is instead bounded by the signed local
storage quota, so manual-export readers stream it. There is no ciphertext-length field: the file or
HTTP body ends the container, and truncation or appended bytes fail authentication or JCS
validation.

### Cryptographic context

The following exact JCS bytes bind both cryptographic layers (`bundle_id` is lowercase UUID text):

```text
context = UTF8({"bundle_format":"particeps-research-bundle-v1","bundle_id":"<bundle-id>","configuration_sha256":"<lowercase-hex>","researcher_key_id":"<key-id>"})
```

The context is RFC 9180 `info` when sealing the content key; HPKE base-mode `aad` is empty. The
context bytes are separately used as AES-256-GCM associated data for the document. A wrong bundle format, bundle ID,
configuration digest, researcher key ID, HPKE suite, `enc`, sealed key, or content nonce fails
authentication.

Note what this makes of the rename to Particeps. `ADCCFG01` → `PTCCFG01` and `ADCEXP01` →
`PTCEXP01` are length-preserving, so every binary offset in this document is unchanged. The bundle
format is not: `particeps-research-bundle-v1` is ten bytes longer than the name it replaces, and
it is authenticated here. Every deterministic vector, sealed fixture, and assertion on a sealed
bundle's exact byte count was regenerated rather than edited, and a bundle sealed under the
retired context fails authentication rather than decoding into anything.

### Authenticated document

The decrypted bytes are one JCS `particeps-research-bundle-v1` object with exactly these root members:

| Member | Type and rule |
| --- | --- |
| `format` | exactly `"particeps-research-bundle-v1"` |
| `bundle_id` | the outer UUID as lowercase text |
| `bundle_kind` | `"manual_export"` or `"automatic_upload"` |
| `configuration_sha256` | the outer digest |
| `configuration` | the exact signed configuration object; its JCS digest must match |
| `configuration_signature` | exact `{signer_key_id, signature}` object; signature is unpadded base64url raw Ed25519[64] |
| `producer` | exact `{client_version, platform}` object; version is a positive decimal string and platform matches configuration |
| `exported_at_utc_millis` | canonical decimal string |
| `experiment` | exact experiment snapshot object described below |

The `experiment` object has exactly `assigned_participant_id`, `configuration_id`,
`durable_through_sequence`, `event_count`, `events`, `experiment_id`, `first_sequence_number`,
`last_sequence_number`, `next_sequence_number`, `participant_instance_id`,
`retained_from_sequence`, `state`, `transitions`, and `uploaded_through_sequence`. All sequence and
count values are canonical decimal strings. For a non-empty document,
`event_count = last_sequence_number - first_sequence_number + 1`; event sequence numbers are
strictly contiguous and cover that exact range. An automatic upload is never empty. For an empty
manual export, `event_count` is `"0"`, `last_sequence_number = first_sequence_number - 1`, and
`events` is empty.

Each event has exactly `sequence_number`, `collector_id`, `payload_schema_version`,
`observed_time`, `payload_type`, and `fields`. Sequence, `wall_time_utc_millis`, and
`monotonic_time_nanos` are canonical decimal strings; `boot_session_id` is 1–128 UTF-8 bytes. The
event's collector, schema version, payload type, field set, values, and encoded size must
validate against the catalog. Unknown payloads do not become generic rows.

Catalog `int32` payload values remain JSON strings and have one signed decimal spelling:
`0|-?[1-9][0-9]*`. Leading `+`, leading zeroes, and `-0` are invalid; the parsed value must also fit
signed 32-bit range and the field's catalog minimum / maximum.

Catalog `float32` and `float64` payload values are also JSON strings. Their exact decimal grammar
is `[+-]?(?:(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?)`. Empty or whitespace-padded
values, hexadecimal/binary spellings, separators, `NaN`, and infinities are invalid. The parsed
value must be finite, fit its declared precision, and satisfy the catalog bounds.

The reader verifies, in order: framing and bounds; HPKE; content AEAD; JCS bytes; repeated outer
identities; embedded configuration digest and Ed25519 signature; platform/client requirements;
range/count contiguity; and every catalog payload. It publishes no plaintext-derived record before
all checks succeed.

## Automatic upload request

The receiver exposes one endpoint chosen at deployment. The request is `POST`; redirects are
forbidden. It has a fixed `Content-Length`, no `Transfer-Encoding`, and an immutable `PTCEXP01`
body staged before HTTP starts. The exact request headers are:

| Header | Value |
| --- | --- |
| `Content-Type` | `application/vnd.particeps.research-bundle` |
| `Content-Length` | canonical decimal body byte count, at most 33,554,432 |
| `Content-Digest` | RFC 9530 `sha-256=:<padded standard-base64 digest>:` |
| `X-Particeps-Bundle-Format` | `particeps-research-bundle-v1` |
| `X-Particeps-Bundle-Id` | lowercase bundle UUID |
| `X-Particeps-Configuration-SHA256` | 64 lowercase hex characters |
| `X-Particeps-Researcher-Key-Id` | researcher key ID |
| `X-Particeps-Sequence-From` | canonical decimal exact first sequence |
| `X-Particeps-Sequence-To` | canonical decimal exact last sequence |
| `X-Particeps-Event-Count` | canonical decimal count |

This vocabulary — the media type, the bundle format, and the seven routing header names — is the
one part of Protocol v1 with a producer in one language and its only reader in another. It is
therefore also in `conformance-vectors.json` as `valid.upload_request`, and both sides assert
against that fixture rather than against their own constants. Asserting against your own constant
proves only that you are self-consistent, which is exactly what a half-applied rename is.

The routing headers are untrusted claims. The receiver checks their syntax, internal range/count
arithmetic, body length/digest, and equality to the parseable outer bundle ID, configuration
digest, and researcher key ID. It cannot authenticate the encrypted participant or sequence claims
and MUST NOT describe them as authenticated.

The body and every header are fixed for all attempts of one staged bundle. Clients disable
automatic redirects and transport-library request replay. Only I/O failure, 408, 425, 429, and 5xx
are retryable. `202 Accepted`, redirects, every other 4xx, malformed receipts, and receipt mismatch
are terminal delivery failures; they do not stop collection.

## Receiver write and receipt

The receiver streams the bounded body directly into a new R2 object whose key is the lowercase
bundle UUID. It verifies SHA-256 during the write, uses a create-only conditional write, and returns
success only after R2 durability is confirmed.

- New immutable object: `201 Created`.
- Existing object with identical byte count, content digest, configuration digest, key ID, and
  claimed range/count metadata: `200 OK`, with the original receipt bytes.
- Existing bundle ID with any mismatch: `409 Conflict`; it is never overwritten.

A success body is JCS JSON with `Content-Type: application/json` and exactly the following members
(shown expanded for readability; response bytes are compact JCS):

```json
{
  "bundle_id": "550e8400-e29b-41d4-a716-446655440000",
  "byte_count": "1234",
  "configuration_sha256": "64 lowercase hex characters",
  "event_count": "1",
  "first_sequence_number": "1",
  "last_sequence_number": "1",
  "sha256": "64 lowercase hex characters"
}
```

Both `201 Created` and exact-replay `200 OK` return this same canonical seven-member receipt. Before
advancing its watermark, the client requires every receipt value to match its durable outbox
manifest exactly. Receive time, researcher key ID, and claimed range may additionally be retained
as untrusted R2 custom metadata; they are not added to the receipt JSON.

The receiver has no list, download, delete, administration, decryption, private-key, D1, Queue, KV,
Durable Object, dashboard, or runtime-configuration path. Deployment-time allowlists, WAF/rate
limits, R2 lifecycle rules, and minimal S3 read credentials are operational controls, not protocol
extensions.

## Conformance

Every implementation must consume the shared valid and hostile corpus in this directory. The
corpus must cover Unicode JCS ordering, integral bounds, raw-key encodings, signature input, HPKE
labels and wrong contexts, malformed lengths, wrong outer/inner identities, body tampering,
range/count mismatch, rejection of the pre-v1 encodings, rejection of the retired Android Data
Collector identity (the `ADCCFG01` and `ADCEXP01` magics, the `research-bundle-v1` bundle format,
and the `adc://join/v1` scheme), unknown fields and payloads, non-finite sensor values, and
trailing bytes. The two legacy classes are named separately because an implementation can reject
one while accepting the other. Absence of a vector is not permission to accept an unspecified
encoding.

The join-link corpus is consumed by Kotlin and TypeScript, the two implementations that create or
open join links. Python analysis has no join-link entrypoint and never parses a join link.
`tools/validate_protocol_vectors.py` does read the corpus, but only to check the fixtures
themselves: closed-world shape, corpus identity, digest and fingerprint spelling, ASCII, and the
4,096-byte limit. That is not an implementation of the join-link grammar, and it proves nothing
about the profile rules above.

Validate the checked-in sources with:

```sh
python3 tools/catalog.py check
python3 tools/validate_protocol_vectors.py
python3 tools/retired_identity_audit.py
```

The last of those is what keeps the retirement above from decaying into a convention. It searches
every tracked file for the retired spellings and fails on any that is not in its reviewed
allow-list, so a hostile fixture cannot quietly become a live constant and a new one cannot be
added without saying in writing why it must carry an old name. It also pins the Android
`applicationId`, because that single value is what makes a pre-rename install a different
application rather than something Android would offer to upgrade.

After deliberately changing a wire rule, regenerate the deterministic corpus with
`node tools/generate_protocol_vectors.mjs` and make every language consumer pass the new bytes in
the same change.

## Implementation map

For the join path, Web authoring is in `web/src/lib/particeps/join.ts` and
`web/src/routes/researcher/JoinLinkPanel.svelte`; the shared parser is
`core/protocol/.../JoinLink.kt`; Android staging is
`app/.../platform/JoinArtifactDownloader.kt`; the `particeps://join/v1` intent enters through
`app/.../MainActivity.kt`; and digest → signature → fingerprint binding is enforced by
`core/study-application/.../StudyApplication.kt`. The adjacent tests and shared
`join-link-vectors.json` are the executable map. Automatic upload instead follows the outbox and
HTTP adapter named in the repository README; receiver and offline analysis each have their own
README code map.
