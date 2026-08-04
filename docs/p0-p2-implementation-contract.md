# P0–P2 implementation contract

This document is the implementation contract for roadmap issues #7–#19. It records the
cross-module decisions that must stay stable while the work is delivered in phases. The issue
bodies remain the product requirements; this document says where each responsibility belongs and
which shortcuts are forbidden.

## Scope

This delivery includes:

- P0: Protocol v1 finalization, a replay-safe Android upload outbox, the collector/event catalog,
  and the Collector capability policy;
- P1: the R2-only ciphertext receiver, offline Python verification/reassembly, typed Parquet, and
  immutable join links;
- P2: battery state, temporal context, gyroscope, ambient light, proximity, and randomized local
  EMA windows.

It excludes iOS/P3, remote configuration, remote triggers, participant/device attestation,
receiver administration, receiver-side decryption, databases other than R2 ciphertext storage,
and analysis sinks other than Parquet. Existing issue #5 is the sole mutable-hosted-content
exception; it does not permit changing an accepted app configuration.

## Non-negotiable invariants

1. Protocol names remain `schema_version: 1`, `ADCCFG01`, `ADCEXP01`, and
   `research-bundle-v1`. This is a destructive pre-1.0 replacement. No old-v1 reader, migration,
   dual parser, compatibility flag, Tink wire keyset, or fallback is retained.
2. Protocol input is closed-world and fail-closed. Unknown members, malformed UTF-8, duplicate
   JSON members, noncanonical JSON, invalid key encodings, unsupported collectors, wrong platform,
   wrong cryptographic context, malformed framing, and trailing bytes are rejected.
3. Android and future iOS configurations may share an experiment ID but never a configuration ID
   or signature. This build accepts only an Android-targeted configuration.
4. Upload bytes are fully encrypted and durably staged before HTTP begins. One bundle ID always
   denotes one immutable byte string. The watermark advances only after an exact matching durable
   receiver receipt.
5. Receiver routing metadata is untrusted. Receiver success proves only that bounded ciphertext
   bytes were stored in R2; it does not prove participant, device, configuration, or plaintext
   authenticity.
6. Offline analysis verifies the entire bundle before publishing plaintext-derived records. An
   invalid bundle produces no partial rows. Event identity is
   `(experiment_id, configuration_id, participant_instance_id, sequence_number)` only after
   authenticated decryption.
7. Collector implementations remain compiled, closed-world modules. The catalog describes their
   contracts; it does not load code or turn unknown payloads into a generic runtime plugin.

## Responsibility map

| Concern | Authoritative location | Implementations/consumers |
| --- | --- | --- |
| Normative Protocol v1, catalog, vectors | `protocol/v1/` | Kotlin, TypeScript, Python |
| Typed study configuration | `:core:study-definition` | Android, researcher tooling |
| Signed configuration envelope/trust | `:core:protocol` | Android, researcher tooling |
| Raw-key Ed25519 verification and RFC 9180 HPKE primitives | `:core:crypto` | `:core:protocol`, `:core:export`, researcher tooling |
| Encrypted bundle framing/document | `:core:export` | Android export/outbox, Python analysis |
| Upload planning/watermark | `:core:study-application` | Android session/runtime |
| Durable body staging and HTTP | `:app` platform adapters | WorkManager upload worker |
| Ciphertext ingress | `receiver/` | Cloudflare Worker and R2 only |
| Offline validation and datasets | `adc-analysis/` | Local/R2 source, Parquet sink |
| Collector implementations | `:collector:*` | Android composition root |
| Collector capability policy | `assurance/collector-policy.json` | CI |

No generic service/repository/controller hierarchy is introduced across these concerns.

## Protocol v1 decisions

- Signed configuration JSON uses RFC 8785 JCS. The schema permits only bounded integral JSON
  numbers; semantic 64-bit counters, times, monotonic values, and byte counts use canonical decimal
  strings.
- Ed25519 and X25519 keys are raw 32-byte values encoded as unpadded base64url. Private CLI key
  files use the same raw encoding.
- Floating-point configuration is removed. Location displacement becomes integer millimeters.
- The configuration carries an explicit platform target and decimal minimum client build number.
- `ADCCFG01` has fixed Ed25519 signature length and no legacy signature-length field.
- `ADCEXP01` carries a UUID bundle ID, complete configuration SHA-256, fixed-suite RFC 9180
  X25519/HKDF-SHA-256/AES-256-GCM wrapped content key, and an AES-256-GCM encrypted document.
- HPKE context and content AAD bind the bundle format, bundle ID, configuration digest, and
  researcher key ID. The authenticated document repeats and verifies those identities.
- The document includes canonical configuration bytes, configuration signature provenance,
  producer platform/client version, bundle kind, retained/uploaded/durable boundaries, actual
  range/count, and decimal-string research clocks/sequences.

## Android upload transaction

The transaction order is:

1. Recover an existing valid staged bundle or create exactly one in no-backup storage.
2. Send that fixed-length file with its exact digest and metadata; redirects and implicit request
   replay are disabled.
3. Accept only a matching `201 Created` or exact-replay `200 OK` receipt.
4. Persist the watermark through the staged last sequence.
5. Remove the outbox manifest, then its now-harmless body orphan.
6. Reclaim eligible encrypted event segments under the existing quota policy.

A crash before step 4 resends identical bytes. A crash after step 4 clears the already-covered
stage before creating another. I/O, 408, 425, 429, and 5xx are retryable; all other HTTP/protocol
failures are terminal for delivery but never stop collection.

## Receiver and analysis boundaries

The Worker exposes only the Protocol v1 upload POST and performs bounded streaming into an
immutable R2 object. It has no private key, decrypt path, list/download/delete/admin route, D1,
Queue, KV, Durable Object, dashboard, or runtime configuration.

`adc-analysis` uses one directional pipeline:

```text
BundleSource -> immutable ciphertext inventory -> full validation -> deterministic reassembly
             -> typed Parquet sink
```

Local and R2 sources first copy ciphertext into a content-addressed cache. Plaintext is staged with
mode 0600, and validated intermediate data or a complete dataset is published by atomic rename.
Conflicting duplicates remain explicit conflicts; there is no last-write-wins or unknown-schema
fallback.

## P2 design limits

- Each new collector is a separate small Gradle module using the existing collector API and event
  sink. Gyroscope, ambient light, and proximity share only the narrow
  `collector:sensor-common` listener-thread lifecycle helper; configuration, payload mapping,
  rate/change policy, and disclosure stay in their own modules. There is no runtime plugin or
  generic sensor-schema framework.
- Battery and temporal context use runtime-registered, non-exported receivers. Gyroscope, ambient
  light, and proximity use hardware preflight without new Android permissions or components.
- Every new numeric sensor value must be finite; catalog maximum event size and field schema are
  enforced by tests and offline validation.
- Randomized EMA reuses durable `InterventionOccurrence` records. A CSPRNG-selected instant is
  committed before WorkManager is enqueued; committed random occurrences are never rescheduled.
  Only future, unmaterialized local-date windows follow a later time-zone change. Daily and total
  caps consume eligible slots in local-date planning order, then signed window array order, then
  ordinal; the CSPRNG selects the minute within the chosen slot, not which window survives
  truncation.

## Phase review criteria

Each phase is complete only after:

1. targeted and full relevant test/build checks pass;
2. a correctness/security review checks failure paths and stated invariants;
3. a simplicity review removes unnecessary abstractions, duplicate implementations, legacy paths,
   unused code, and avoidable dependencies;
4. scout-rule cleanup leaves touched code clearer than before; and
5. an independent reviewer unfamiliar with the implementation can locate the specification,
   source, tests, and operational documentation from the root documentation.
