# ADC ciphertext receiver

This directory contains the complete server-side surface for automatic uploads. It is one
Cloudflare Worker, one deployment-fixed `POST` path, and one R2 binding. The normative request,
bundle, and receipt contract is [`../protocol/v1/README.md`](../protocol/v1/README.md); the phase
boundaries are in
[`../docs/p0-p2-implementation-contract.md`](../docs/p0-p2-implementation-contract.md).

The Worker accepts a bounded `ADCEXP01` ciphertext stream and stores it under its bundle UUID. It
checks the Protocol v1 content headers, untrusted routing claims, visible outer bundle identities,
actual byte count, and SHA-256. It never buffers a complete bundle. A create-only R2 write returns
`201`; an exact replay returns the same canonical receipt with `200`; reuse of a bundle UUID with
different bytes or metadata returns `409`. No success response is produced before the R2 operation
has completed. The verifier feeds R2 through a `FixedLengthStream`, preserving backpressure while
meeting R2's requirement that streamed uploads have a known length.

The application-header vocabulary is closed-world. Apart from the ten Protocol v1 headers, the
Worker ignores only the ordinary OkHttp transport headers and headers in Cloudflare's
[edge HTTP header reference](https://developers.cloudflare.com/fundamentals/reference/http-headers/).
Local Wrangler/Miniflare's `MF-Original-Hostname` transport header is also ignored.
Every other client-controlled header is rejected. In particular, credentials, cookies, alternate
content encodings, and extra routing headers cannot silently acquire meaning.

This is deliberately not an application server. It has no participant or device authentication,
private key, decryption, listing, download, deletion, administration, dashboard, remote control,
runtime configuration endpoint, D1, Queue, KV, or Durable Object. R2 metadata and request headers
are untrusted routing data, not proof of bundle origin or plaintext contents.

## Fixed deployment inputs

Copy `wrangler.example.jsonc` to the ignored `wrangler.jsonc` and replace every placeholder:

- `routes[0].pattern`: the controlled receiver hostname;
- `r2_buckets[0].bucket_name`: the private ciphertext bucket;
- `UPLOAD_PATH`: one absolute path such as `/v1/upload`; query strings are rejected;
- `ALLOWED_CONFIGURATION_SHA256`: one lowercase SHA-256 of canonical configuration bytes;
- `ALLOWED_RESEARCHER_KEY_ID`: the researcher key ID bound into that configuration.

The checked-in template intentionally cannot accept uploads unchanged. These values are fixed in
a Worker deployment; the Worker exposes no API that changes them. Deploy a separate Worker (and
normally a separate bucket) for a different configuration.

The R2 object key is exactly the lowercase bundle UUID. Custom metadata has exactly these names:

```text
sha256
byte_count
configuration_sha256
researcher_key_id
first_sequence_number
last_sequence_number
event_count
received_at_utc
```

All eight values are untrusted. `received_at_utc` is assigned by the Worker on the first successful
write and remains unchanged on replay. The seven-field receipt omits receive time and researcher
key ID, as required by Protocol v1.

## Code map

| Location | Responsibility |
| --- | --- |
| `src/contract.ts` | Closed-world request/outer-header parsing, R2 metadata identity, receipt bytes |
| `src/verified-body.ts` | Backpressured length, prefix, and streaming SHA-256 verification |
| `src/index.ts` | Single-route HTTP flow and create-only R2 transaction |
| `tests/receiver.test.ts` | Fake-R2 replay, race, conflict, bound, and failure-path coverage |
| `tests/receiver.workerd.test.ts` | Real workerd/R2 upload and replay against the shared Protocol corpus |
| `wrangler.example.jsonc` | The complete production binding and deployment template |

## Develop and verify

Requires Node.js 22+ and pnpm 10:

```sh
cd receiver
pnpm install --frozen-lockfile
pnpm typecheck
pnpm test
pnpm build
```

The fast tests call the same request handler as production with an in-memory, conditionally written
fake R2 bucket. A separate integration suite runs the production export, native `DigestStream`, and
real local R2 implementation inside workerd, using the shared Protocol v1 valid bundle and receipt
vectors. Together they cover streaming validation, exact replay, concurrent create-only writes,
conditional-write races, conflicts, and storage failures without adding a test-only HTTP route.

For a local Worker after creating `wrangler.jsonc`:

```sh
pnpm dev
```

When manually posting a fixture with `curl`, add `--header 'Accept:'` to suppress curl's default
`Accept: */*`; it is not one of the exact Protocol v1 request headers and is intentionally rejected.

## Deploy and operate

1. Create a private R2 bucket and bind it as `BUNDLES`. Do not enable `r2.dev` public access.
2. Put a retention rule on the bucket before accepting uploads. Cloudflare documents both the
   dashboard flow and `wrangler r2 bucket lifecycle` commands in its
   [R2 object lifecycle guide](https://developers.cloudflare.com/r2/buckets/object-lifecycles/).
3. Route the Worker through a controlled hostname. Configure a
   [WAF rate-limit rule](https://developers.cloudflare.com/waf/rate-limiting-rules/) for the exact
   upload path; the ingress is intentionally public and can receive bounded bogus ciphertext.
4. Run `pnpm deploy`. Verify wrong paths and methods fail, then submit a Protocol v1 fixture and
   confirm a `201` followed by an identical-receipt `200` replay.
5. Give analysts a separate least-privilege S3-compatible read credential for the bucket. The
   Worker itself exposes no retrieval endpoint.

Application logs contain no request headers, bundle IDs, participant identifiers, or bodies. Use
Cloudflare aggregate request/R2 metrics and WAF controls for operations; consent and governance
documents must still name the endpoint operator, jurisdiction, retention, and authorized analysts.

The implementation relies only on documented Cloudflare behavior: R2 `put()` accepts streams,
custom metadata, SHA-256 verification, and conditional writes; a failed condition returns `null`;
successful writes are strongly consistent once the promise resolves. See the
[R2 Workers API reference](https://developers.cloudflare.com/r2/api/workers/workers-api-reference/)
and [R2 consistency model](https://developers.cloudflare.com/r2/reference/consistency/).
