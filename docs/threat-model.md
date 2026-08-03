# Threat model

A reference description of the protections in the current release, the limitations that come with them, and a few checks anyone can run. It is written to be attached to an ethics submission or read by a security reviewer, and it describes the system as implemented in this repository. Where a protection is weaker than it looks, that is stated here.

## What is being protected

| Asset | Primary concern |
| --- | --- |
| Study events on the device | Confidentiality while the phone is out of the participant's hands |
| The exported or uploaded bundle | Confidentiality in transit and at the destination |
| The study configuration | Integrity — a participant gets exactly the study they consented to |
| Assigned and random participant codes | Confidentiality and controlled linkability to a research roster or import |
| Survey answers and intervention history | Atomicity, immutability, and truthful lifecycle interpretation |
| The participant's control | That start, pause, withdrawal, and deletion mean what they say |
| The scope of collection | That a study cannot collect beyond what it declared |

## Trust assumptions

The design assumes all of the following. If one is false, the protections below weaken accordingly.

- The device's OS is intact — not rooted, not running a modified build, Keystore behaving as documented.
- The participant controls physical access to their unlocked device.
- The research team handles its own private keys competently and honours what its consent document says. For a study that uploads, that includes operating the endpoint it named and keeping the HPKE private key off it.
- The device's system certificate trust store contains only certificate authorities the participant would accept. The app pins no certificate.
- Android's Keystore, Storage Access Framework, permission model, and `NetworkStatsManager` / `UsageStatsManager` behave as their documentation states.
- The APK the participant installed is the one built from this source. Nothing in the app proves this; it rests on the release signing identity and on the distribution channel.
- The participant received the study configuration from the research team that recruited them, through that team's own channel. Nothing in the app establishes this either; the signer fingerprint on the consent screen is what makes it checkable by hand.

## Current protections

### Local study data at rest

Events and metadata are encrypted with AES-256-GCM under a per-study Android Keystore key marked non-exportable, with a fresh provider-generated 96-bit IV per record and a 128-bit tag. Each frame's AAD binds the format tag, an opaque per-study locator derived by SHA-256, and the record's sequence number, so a frame cannot be moved between studies, reordered, or replayed without failing authentication.

One detail matters for review: the app does **not** request StrongBox and does **not** verify that the key landed in secure hardware, so this document does not claim the key is hardware-backed. On most current devices an `AndroidKeyStore` AES key is TEE-backed, but the app neither requires nor checks it. The key also carries no user-authentication requirement, so it is usable whenever the app process runs. The protection therefore covers data at rest on a powered-off or locked device, backed by Android's file-based encryption and the Keystore — not an attacker who can run code as the app.

### Study configuration integrity

A configuration is Ed25519-signed inside an `ADCCFG01` envelope. The signing public key travels inside the signed bytes, in a mandatory `signer` block, so a configuration certifies itself and one published app can verify any researcher's study without a rebuild.

**What a signature proves is that the configuration is unchanged since it was signed.** It does not prove who wrote it. A verified configuration establishes that identity mode, collectors, localized surveys, intervention actions and triggers, duration, consent, export key, and `upload` block are exactly the bytes the signer produced. It establishes nothing about the identity behind that key unless the build pins that signer.

On import the app checks envelope framing and length bounds, decodes the configuration strictly, requires the declared `signer.key_id` to equal the envelope's signer key ID, verifies the signature over the canonical configuration bytes, and checks the validity window and minimum app version. Nothing decoded is acted on until the signature verifies. Canonicality is enforced by re-encoding the decoded configuration and requiring a byte-identical match, so reordered keys, altered whitespace, duplicate keys, and reformatted numbers are rejected. Every object has an exact required key set — unknown *and* missing keys both fail — and an unknown collector ID fails even if the collector is marked optional. The current shape deliberately remains schema v1; prompt-shaped older v1 documents fail instead of entering a compatibility branch.

A build may additionally pin signers, as `CollectorApplication.TRUSTED_SIGNING_KEYS`. That map is empty in the shipped build, which therefore accepts any correctly signed configuration and reports the publisher as unverified to the participant. A non-empty map is strictly exclusive: only listed signers are accepted, and the pinned key overrides the one the configuration declares and must equal it, so a configuration cannot claim a pinned key ID while carrying a different key. `ConfigurationVerifier` returns both the configuration and whether its signer was pinned, and the consent screen renders that distinction.

Failures are fail-closed: a failed import does not activate a study, and a failed recovery at boot lands the app in the no-study state, which collects nothing. The `signerKeyId` in the envelope sits outside the signature and asserts nothing: it must match the key ID inside the signed bytes, so a forged one fails that check, fails to resolve against a pinned map, or fails signature verification.

### Scope of collection

Collectors are selected by ID from a registry of modules compiled into the APK: no plugin download, no scripting layer, no dynamic class loading. Every collector parameter has a validated range in the schema, study duration is bounded, and the local quota a study may claim is bounded to 8 MiB-8 GiB. The module graph enforces the boundary structurally — a `collector:*` module depends only on `core:collector-api` and `core:study-definition`, so a collector cannot write files, change study state, start activities, or request permissions, because the code that would is not on its classpath.

**What the participant is told about that scope is the app's text, not the researcher's.** Setup is five steps with one panel each — study, data, consent, access, start — and the data step, which comes before the consent text, lists every collector the signed configuration enables. Each entry is described from a template compiled into the app and filled in from that study's own signed parameters: the accelerometer's rate, the poll interval of each polling collector, the location interval and minimum displacement. No configuration field changes any of it, so a researcher cannot understate what a collector captures on the screen a participant reads immediately before consenting. This is a small integrity property rather than a large one, and its limits are worth stating exactly: it constrains how each enabled source is described, not the honesty of the consent summary beside it, which remains the researcher's own prose — and it is entirely positive. The screen says what each source records; it makes no claim about what a source cannot see. A participant who wants that has the documentation and their research team, not this screen.

One template hedges on purpose. The accelerometer entry reads "about N times per second **or more**", because Android treats a sampling period as a hint rather than a contract and a device is free to deliver faster than the study asked for — observed on a current emulator image at over ten times the requested rate. Stating the configured rate alone would understate what is recorded.

Every participant-facing app string lives in resources and ships in English and Traditional Chinese. The interface follows Android's per-app language. Ordinary researcher prose — title, purpose, researcher name, contact, and consent summary — renders exactly as signed. Survey content is the explicit exception: every localized value and its signed default are inside the signed configuration, and selection never creates unsigned text.

### Identity and survey integrity

Every import mints a new random UUID, even when the same configuration is imported twice. A personalized configuration may also contain one opaque assigned code restricted to a small ASCII grammar. The consent screen distinguishes the two modes and shows the assigned code for comparison. Both codes live in encrypted metadata and exports; only the random per-import UUID is allowed onto the clear upload-routing surface.

An intervention occurrence is keyed by a deterministic SHA-256 identity over its signed logical schedule position. Its durable state distinguishes scheduled, notification posted, opened, submitted, and expired; recovery and timezone reconciliation use that identity instead of generating a new occurrence. Survey submission validates stable question and option IDs, then uses an encrypted transaction journal to commit one event and the corresponding terminal metadata together. There is no draft store and no update path after submission. These controls prevent duplicate commits and partial durable answers; they do not prove that a participant saw a notification or personally supplied an answer.

The configuration admits at most 512 lifetime occurrences. This is a security and reliability bound, not an authoring suggestion: retaining every terminal identity is what prevents an old logical firing from reappearing after recovery, and the bound keeps that set under the authenticated metadata limit instead of silently weakening idempotency.

### Data leaving the device

Study data leaves the device two ways, and both carry the same encrypted bundle.

The first is export: the participant picks a Storage Access Framework destination and the app writes the bundle there. The second is upload, which exists only if the signed configuration carries a populated `upload` block naming an `https://` endpoint, an interval, and whether metered networks are allowed. The app then posts bundles to that endpoint and to no other. A study whose `upload` block is empty transmits nothing. Which of the two applies is fixed by the configuration the participant consented to, and the endpoint host, the cadence, and the network condition are rendered into the consent step from the signed bytes rather than from the researcher's free-text summary — as a block the app asserts itself, directly below that summary and on the same panel as it. The manifest declares `android.permission.INTERNET`, so a build's permission list no longer distinguishes an uploading study from a non-uploading one. The signed configuration is what a reviewer should read, and the checks at the end of this document cover both.

The bundle itself is encrypted with a fresh AES-256 key per bundle, wrapped with Tink HPKE (`DHKEM_X25519_HKDF_SHA256` / `HKDF_SHA256` / `AES_256_GCM`) to the researcher public key carried in the signed configuration. The keyset is validated at configuration import — exactly one enabled primary key, with KEM, KDF, AEAD, and variant each checked — so a study with a malformed or downgraded export key is rejected before any data is collected. The app never holds the researcher's private key, and a bundle streams from the store through AES-GCM to its destination without a plaintext or full-ciphertext temporary file.

Decryption on the researcher's machine streams too, because a bundle is bounded by the study's storage quota rather than by a fixed size. One detail is deliberate and worth a reviewer's attention: `ResearchExport.decrypt` drives the AES-GCM cipher directly rather than reading through `CipherInputStream`, which reports an authentication failure as an ordinary end of stream. Through `CipherInputStream` a tampered bundle would decrypt into a silently truncated file that looks like a short study; driving the cipher directly keeps a bad tag an exception. The trade-off is that plaintext reaches the caller before the tag has been verified, so `researcher-tools decrypt` writes to a temporary file in the destination directory and moves it into place only after verification succeeds. Anything else consuming this API inherits the same obligation: do not publish the output until the call returns normally.

What an upload endpoint therefore sees:

| The endpoint learns | The endpoint does not learn |
| --- | --- |
| That this install is participating, and when each delivery arrives | Any event content; the body is ciphertext only the researcher's HPKE private key opens |
| How much data was collected, from the body size and the declared sequence range | Anything derived from the payload without that private key |
| The `experiment_id`, `configuration_id`, and random participant instance ID, sent in request headers | The assigned participant ID, survey content, event content, name, account, device identifier, or advertising ID |

The participant instance ID is a random UUID minted for every import and kept in that study's metadata. An uploading study needs it because encrypted chunks otherwise arrive indistinguishable. It is pseudonymous and disclosed on the consent screen. A researcher-assigned ID, when present, stays inside HPKE ciphertext and must not be copied into endpoint headers or logs.

Transport is TLS: the endpoint must be `https://`, validated when the configuration is decoded, and `usesCleartextTraffic="false"` remains set, so a plaintext HTTP endpoint cannot be configured or reached. There is **no certificate pinning**. The connection trusts the device's system trust store, so an attacker holding a certificate that store accepts — an enterprise or otherwise installed CA, for example — can see the delivery metadata above and can substitute their own endpoint. They still cannot read a bundle.

Delivery is durable rather than best-effort, and this is deliberate: `StudyMetadata.uploadedThroughSequence` records the highest sequence an endpoint confirmed, advances only after a successful response, and never moves backwards. It advances to what the bundle's receipt says was actually written, not to what the run set out to send, so a delivery that stopped early at its size budget leaves the remainder marked undelivered. A study that fails to upload keeps collecting, and the participant can still export by hand.

A failed delivery is reported to the participant as a fixed reason code — `UPLOAD_TIMEOUT`, `UPLOAD_TLS_FAILED`, `UPLOAD_HTTP_<status>`, `UPLOAD_IO_FAILED` and a few others. `StudyUploadException` carries the code instead of a message and validates it against the same `[A-Z][A-Z0-9_]{2,63}` pattern a collector's health reason uses, so a string that reaches the screen or the log cannot carry study data, an endpoint's response body, or a URL. The underlying transport exception is written to the Android log, where it is a network error rather than research data. Collector health failures surface their reason code the same way.

Confirmed delivery is also what makes local data reclaimable. Once a study's storage passes 80% of its configured quota, a successful upload lets the device release whole leading segments — down to 60% — provided every event in them was confirmed by the endpoint and they are not the segment still being written. `StudyMetadata.retainedFromSequence` records the lowest sequence still present, and the participant's dashboard states how many earlier events were delivered and removed. Below that threshold a study keeps everything, and an endpoint that never confirms anything reclaims nothing.

### State boundaries and failure behaviour

Entering `RUNNING` mints an admission epoch token that collectors must present, and events carry their original observation time. On pause, completion, or withdrawal the runtime takes a monotonic boundary, persists the transition first, then drains: only events from the same epoch observed strictly before the boundary are still admitted, after which the epoch closes permanently. A delayed callback cannot smuggle post-pause data into the dataset.

A storage write failure or exhausted quota force-closes the admission gate, records an incident code, and fail-closes the study to `PAUSED`. There is no ring buffer and no silent dropping of events, and reclaiming space is a different thing from either: it can only release events an endpoint has already confirmed receiving, so a quota that fills with nothing delivered stops the study rather than making room, and what was released is recorded in `retainedFromSequence`, declared in every bundle's `first_sequence_number`, and stated on the participant's dashboard. Nothing that has not reached the research team is ever discarded to free space.

Occurrence lifecycle events and survey submissions have a stronger two-record boundary: the encrypted `ADCTXN01` journal makes the event append and updated metadata recoverable as one idempotent commit. Recovery completes the exact pending transaction or recognizes it as already complete. No unverified fallback reconstructs a response from UI state.

A corrupt segment, index gap, AEAD failure, or missing key is a hard failure, and only an incomplete trailing frame in the final segment may be recovered. Event segments missing *below* the retained floor are a hard failure too, because a prefix that disappeared without being reclaimed is indistinguishable from one that was tampered away. Metadata claiming more events than are durable is rejected in favour of the durable count, and an export that cannot read its whole window to the boundary fails rather than producing a partial file. Missing required access keeps a study from reaching `READY`, and a foreground service that fails to start rolls the runtime back instead of collecting. A dataset is therefore either complete over the window it declares or absent, rather than quietly partial.

**When an event payload is authenticated, and when it is not.** Opening a study decrypts no events. The sequence number is stored unencrypted at the front of each frame, so the framing, the segment index, and the contiguity of the sequence are checked from the plaintext headers, and the metadata — which holds each collector's last event — is verified by its own AES-GCM tag. This is what makes a large quota workable: the cost of opening a study is linear in the number of frames rather than in the bytes decrypted. The trade-off is direct. **An event payload's authentication tag is verified when that event is read, not when the study is opened.** Corruption or tampering inside an event body surfaces on export or upload, as a hard failure at that point, rather than at startup. Nothing is accepted unverified — a tampered event still cannot reach a bundle — but the detection is deferred, so a device holding a damaged log can look healthy until its data is next read.

### Deletion

Local deletion removes metadata, event segments, and the study's Keystore alias. This is crypto-shredding: the ciphertext may physically persist in flash until overwritten, but the key to it is gone.

## Limitations

These are known limitations rather than bugs. Reports about them are handled as documentation issues — see [SECURITY.md](../SECURITY.md).

**A rooted or compromised device.** There is no root detection, attestation, or integrity checking. Code running as the app can ask the Keystore to decrypt, which yields the data even though the key itself cannot be extracted. Because no user-authentication requirement is set on the key, screen lock is not an obstacle to such an attacker.

**An unlocked device in someone else's hands.** There is no in-app authentication, PIN, or biometric gate. Anyone holding the unlocked phone can open the app, export the bundle to any destination, or delete the local data. At-rest encryption covers the seized-and-powered-off case, not this one.

**A malicious or careless researcher.** The researcher writes the configuration, chooses the collectors and their rates, writes the consent text, and holds the private key that decrypts everything participants send. The software constrains what is technically possible — only the compiled-in collectors, within validated parameter ranges, after the participant grants each Android permission — but it cannot verify that the consent text honestly describes any of it. A participant who consents to a study is trusting that research team, not this software. Ethics review is the control here, and the platform supports it: the configuration is human-readable, signed, and reproduced verbatim inside every export.

One narrow part of this is closed by the data step described under *Scope of collection*. Which collectors are enabled, at what rate, and what each cannot see are stated by the app from the signed parameters, so those particular claims cannot be softened in the telling. Everything around them — why the data is collected, who reaches it, how long it is kept, what withdrawal means on the research side — is still the researcher's prose, and nothing in the app checks it.

**Publisher impersonation.** The researcher name and contact shown on the consent screen come from the signed configuration, which makes them text the signer chose. Anyone can generate an Ed25519 key, write a configuration naming any research team, sign it, and produce a file that verifies on a build with no pinned signers. The signature is genuine; what it certifies is the file, not its author.

Three things narrow this. The consent step shows the key fingerprint — SHA-256 over the encoded public key, first 16 bytes, as eight uppercase groups of four hex characters — under the heading *Configuration signature*, and, when the signer is not pinned, asks the participant to check that fingerprint against the one their research team published, noting underneath that a signature shows a file is unaltered rather than who wrote it. Deliberately none of it is in the error colour. An unpinned signer is the deployment model, not a failure, and rendering the ordinary case as an alarm trains a reader to skip the block — the mitigation here depends on the participant actually performing a comparison, so the text is an instruction rather than a warning. That wording is in the app's string resources rather than in Kotlin, so it is translated with the rest of the interface and no configuration can alter it. A team that publishes its fingerprint through the channel that recruited its participants gives them a check that copied prose does not defeat. And a configuration is not an anonymous download: it reaches a participant through a relationship that already exists, so an impersonator has to get their file in front of someone through that channel.

A build that pins its signers removes this exposure for the studies it accepts, and accepts nothing else. See the [researcher guide](researcher-guide.md) for both sides of that choice.

**What happens to a bundle after it leaves.** Once the participant picks a destination, or the app posts a bundle to the study's endpoint, the bytes are beyond the app's reach. Confidentiality holds — only the researcher's HPKE private key opens them — with three caveats:

- A bundle is not authenticated as to origin. It is encrypted *to* the researcher, not signed *by* the device. Anyone with the researcher's public keyset, which is inside every copy of the signed configuration, can fabricate a syntactically valid bundle that decrypts cleanly. A decryptable bundle is not proof of who produced it, and an endpoint receiving one has no cryptographic evidence that a real participant device sent it.
- The bundle header exposes the researcher key ID in cleartext, and the suggested export filename contains the study ID and an export timestamp. Anyone handling the file can tell that this person participated in that study.
- Size discloses roughly how much data was collected. The same is true on the device: event segment file sizes and modification times leak collection volume and timing to anyone with filesystem read access, without any decryption.

**A compromised or hostile upload endpoint.** An endpoint that is taken over, misconfigured, or logging more than the study intended still cannot read a bundle without the researcher's HPKE private key, which does not belong on a collection server. What it does get is the metadata above for every delivery: which install, which study, how many events, and when. Whoever operates the endpoint can therefore build a participation timeline per instance ID even while the payloads stay closed. It can also refuse deliveries indefinitely; the effect is that the device keeps the data and the researcher does not receive it, not that collection stops. The converse matters more: an endpoint that answers 2xx without durably storing the body advances the watermark, and under storage pressure the device may then release those events. A success response is a claim to have stored the bundle, and an endpoint that cannot honour it should answer 408, 429, or 5xx instead. Treat the endpoint as part of the study's data governance, keep the decryption key off it, and state its operator in the consent material.

**Inference from the data itself.** That location traces, keyboard touch dynamics, and app usage patterns can identify a person is a property of the data, not a defect in the software. Minimisation and consent are the controls. The keyboard collector cannot see text, but its timing and within-key position data are behaviourally distinctive; the [data dictionary](data-dictionary.md) states this per collector.

**Assigned IDs and survey responses are direct governance responsibilities.** An opaque assigned code can still be identifying to the team that holds its roster, and free-text survey answers can contain names or other sensitive details. Bulk personalization keeps codes out of filenames and logs, and transport keeps them out of headers, but decryption intentionally reveals them to the private-key holder. Ethics review should minimize free text, document the roster join and retention policy, and state that closing an unfinished survey stores no answer while submission is final.

**Notification timing is not participation evidence.** WorkManager is inexact, devices can delay work, and `NOTIFICATION_POSTED` only records that Android accepted the post. An occurrence ID prevents duplicate logical delivery across recovery; it cannot prove visibility, attention, or who tapped. Analyses must keep scheduled, posted, opened, submitted, and expired as separate outcomes.

**Configuration replay and clock manipulation.** The signed envelope has no nonce and no device binding, so the same configuration can be imported on any number of devices until it expires. Validity is checked against the device wall clock, so a participant who moves their clock backwards can revive an expired configuration. Keep validity windows short; a multi-year window makes both worse.

**No signer revocation.** There is no revocation list, rotation protocol, or kill switch at any layer. A leaked study signing key can mint configurations that any build with an empty anchor map accepts, and configurations already signed with it stay valid until they expire; a short validity window is the only control. Where a build does pin signers, that set is fixed and auditable at build time, and retiring one of those keys requires shipping a new APK.

**Key loss.** Losing the researcher's HPKE private key makes every export from that study permanently unreadable. There is no escrow, and the keyset validation mandates exactly one key, so multi-recipient encryption is not available. Losing the device's Keystore key — through device wipe, uninstall, or clearing app data — destroys all un-exported local data. Neither case has a recovery path.

**Per-collector supervision is not fail-closed.** A collector that crashes or loses its permission is marked `FAILED` or `BLOCKED_ACCESS`, and the study continues with the others. This keeps one flaky data source from ending someone's participation, but a dataset can be missing one collector's data for a period while the study looks healthy overall. The collector's status and the resulting gap are visible in the data.

## Deployment requirements

The shipped build pins no signers. It accepts any correctly signed configuration and tells the participant that the publisher is unverified. That is the deployment model rather than an outstanding task, and it retires a blocker that earlier releases carried: the default build no longer compiles in the demonstration signer as its only trust anchor, so it no longer trusts a key whose private half is published in this repository. The demo signer now has no standing that any other signer lacks.

The demonstration keys are still public. **The demonstration signing private key is published**, so anyone can sign a configuration that presents itself as the demo study. **The demonstration HPKE private key is published too**, so exports produced under the demo study are readable by anyone who clones the repository. Both fixture files carry an `INSECURE-` prefix for this reason; see [`researcher-tools/examples/README.md`](../researcher-tools/examples/README.md).

What follows from that is a build-variant boundary rather than a trust decision: **the release variant ships no demonstration study at all.** The signed envelope (`res/raw/demo_study_envelope.txt`) and the code that reads it are in the app's `debug` source set, so neither is compiled or packaged into a release APK, and the dashboard renders no entry point for it. A participant who installs a release therefore cannot start a study whose export key is public. The demo remains available in debug builds, which is what the instrumentation test exercises. Note what this boundary does not claim: it removes a foot-gun, not an attack. Anyone can still sign their own configuration with the published demo key and hand the file to someone, because pinning no signers is the deployment model — the consent screen is what carries that, by reporting the publisher as unverified and showing the fingerprint.

What a real deployment owes participants is its own key pairs and a published signing key fingerprint, distributed through the channel that recruits them, so the fingerprint on the consent screen can be compared against something. An institution that wants one build to run only its own studies pins its key in `CollectorApplication.TRUSTED_SIGNING_KEYS` and ships that build, which then refuses every other signer.

## Supply chain

The dependency set is small: Tink for cryptography, Gson for parsing, AndroidX Compose, Lifecycle and WorkManager, Google Play Services Location for the fused location provider, and OkHttp 5.3.0 for the upload request. There is no analytics, crash reporter, or telemetry library. OkHttp is used from one class, `OkHttpStudyUploader`, which builds a single POST to the endpoint the signed configuration names; Play Services Location is the only other dependency with a plausible network surface.

The release runtime classpath resolves to 136 Maven components. On 2026-08-03, the exact Gradle-resolved graph was exported as a CycloneDX SBOM and scanned with Trivy 0.70.0 against vulnerability and Java databases downloaded that day; no known vulnerability at any severity was found. A separate source and `pnpm-lock.yaml` scan found no secret or known vulnerability. These are point-in-time results rather than a continuing guarantee, so re-run both scans before any deployment.

## Verifying this yourself

```bash
# Permissions actually present in a built artifact
aapt dump permissions app/build/outputs/apk/release/app-release.apk

# Whether a study uploads, and where to: the `upload` block of its canonical configuration
jq .upload ./study-canonical.json

# Who signed a study, its key fingerprint, and whether the signer is pinned
./gradlew :researcher-tools:run --args="check-config --envelope ./study.adccfg"

# No analytics or crash reporting in the dependency declarations
grep -rniE "firebase|crashlytics|analytics|retrofit" --include="*.kts" --include="*.toml" .

# Encrypted-at-rest assertions, on a device or emulator
./gradlew :core:storage:connectedDebugAndroidTest
```

## Related documents

- [System design](system-design.md) — the implemented architecture in full
- [Data dictionary](data-dictionary.md) — every field on every event
- [Researcher guide](researcher-guide.md) — key custody and study design obligations
- [Participant guide](participant-guide.md) — the same behaviour, addressed to participants
- [SECURITY.md](../SECURITY.md) — reporting a vulnerability
