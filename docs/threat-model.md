# Threat model

A reference description of the protections in the current release, the limitations that come with them, and a few checks anyone can run. It is written to be attached to an ethics submission or read by a security reviewer, and it describes the system as implemented in this repository. Where a protection is weaker than it looks, that is stated here.

The [normative Protocol v1 contract](../protocol/v1/README.md), its
[collector catalog](../protocol/v1/collector-catalog.json), the
[system design](system-design.md), and
[Collector capability policy](../assurance/README.md) are the implementation sources behind the
claims in this document.

## What is being protected

| Asset | Primary concern |
| --- | --- |
| Study events on the device | Confidentiality while the phone is out of the participant's hands |
| The exported or uploaded bundle | Confidentiality in transit and at the destination |
| The study configuration | Integrity — a participant gets exactly the study they consented to |
| Researcher Ed25519 and HPKE private keys | Preventing forged studies and unauthorized bundle decryption |
| Receiver R2/S3 credentials and ciphertext objects | Durable, bounded custody without exposing a decrypt path |
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

Events and metadata are encrypted with AES-256-GCM under a per-study Android Keystore key marked non-exportable, with a fresh provider-generated 96-bit IV per record and a 128-bit tag. Each frame's AAD binds the format tag, an opaque per-study locator derived by SHA-256, and the record's sequence number. A frame therefore cannot be moved between studies, reordered, or replayed without failing authentication.

One detail matters for review. The app does **not** request StrongBox and does **not** verify that the key landed in secure hardware, so this document does not claim the key is hardware-backed. On most current devices an `AndroidKeyStore` AES key is TEE-backed, but the app neither requires nor checks it. The key also carries no user-authentication requirement, so it is usable whenever the app process runs. The protection therefore covers data at rest on a powered-off or locked device, backed by Android's file-based encryption and the Keystore — not an attacker who can run code as the app.

### Study configuration integrity

The researcher Web tool is a static client-side application. It generates or imports raw private
keys in the browser, keeps the draft and keys only in memory, downloads private-key files directly,
and has no analytics or application network destination. That reduces the number of places a key
can land; it does not make the browser a trusted hardware boundary. A malicious extension,
compromised same-origin deployment, or injected dependency can read keys while the page is open or
substitute what is signed. High-risk deployments should use the CLI in a controlled environment,
archive the canonical configuration beside the key material, and verify the resulting signer
fingerprint through an independent recruitment channel.

A configuration is Ed25519-signed inside a `PTCCFG01` envelope. The signing public key travels inside the signed bytes, in a mandatory `signer` block. A configuration therefore certifies itself, and one published app can verify any researcher's study without a rebuild.

**What a signature proves is that the configuration is unchanged since it was signed.** It does not prove who wrote it. A verified configuration establishes that identity mode, collectors, localized surveys, intervention actions and triggers, duration, consent, export key, and `upload` block are exactly the bytes the signer produced. It establishes nothing about the identity behind that key unless the build pins that signer.

On import the app bounds the fixed `PTCCFG01` framing and strictly decodes and byte-for-byte rechecks RFC 8785 JCS. It requires the declared `signer.key_id` to equal the envelope key ID, then verifies the fixed 64-byte signature over the exact configuration bytes. It also checks validity, Android platform, and the decimal-string client build floor. Ed25519 and X25519 wire keys must be raw 32-byte values encoded as unpadded base64url; X.509, PKCS#8, padded base64, and library keysets are rejected. Nothing decoded is acted on until the signature verifies. Every object has an exact key set and an unknown collector fails even when optional. Protocol v1 is a destructive pre-1.0 replacement: former-v1 artifacts fail rather than entering a compatibility branch.

A build may additionally pin signers, as `CollectorApplication.TRUSTED_SIGNING_KEYS`. That map is empty in the shipped build, which therefore accepts any correctly signed configuration and reports the publisher as unverified to the participant. A non-empty map is strictly exclusive: only listed signers are accepted. The pinned key overrides the one the configuration declares and must equal it, so a configuration cannot claim a pinned key ID while carrying a different key. `ConfigurationVerifier` returns both the configuration and whether its signer was pinned, and the consent screen renders that distinction.

Failures are fail-closed: a failed import does not activate a study, and a failed recovery at boot lands the app in the no-study state, which collects nothing. The `signerKeyId` in the envelope sits outside the signature and asserts nothing. It must match the key ID inside the signed bytes, so a forged one fails that check, fails to resolve against a pinned map, or fails signature verification.

### Scope of collection

Collectors are selected by ID from modules compiled into the APK: no plugin download, scripting,
or dynamic loading. Every parameter has a validated range. The module graph enforces the boundary.
A feature collector depends on `core:collector-api`, `core:study-definition`, and optionally the
narrow `collector:sensor-common` helper, so storage, protocol/export, study-state, and UI code are
not on its classpath.

That boundary is also checked rather than merely documented. CI scans collector source, compiled
constants, and dependency graphs for network, files/database/preferences, dynamic loading,
logging, activity/service launch, protocol/export/storage, and cryptographic capabilities. The
runtime validates catalog identity/schema and enforces `maximumEncodedEventBytes` before append. A
new collector also needs catalog metadata, disclosure, bounds, lifecycle/access tests, and power
and storage estimates.

**What the participant is told about that scope is the app's text, not the researcher's.** Setup is
five steps with one panel each: study, data, consent, access, start. The data step comes before the
consent text and lists every collector the signed configuration enables. Each entry is
described from a template compiled into the app and filled in from signed parameters: motion-sensor
rates, ambient-light/proximity gates, polling intervals, and location interval/displacement. No
configuration field changes the wording, so a researcher cannot understate what a collector
captures on the screen a participant reads immediately before consenting. This integrity property
constrains each enabled source's description, not the honesty of the researcher's consent prose.

One template hedges on purpose. The accelerometer entry reads "about N times per second **or more**". Android treats a sampling period as a hint rather than a contract, and a device is free to deliver faster than the study asked for. A current emulator image was observed at over ten times the requested rate. Stating the configured rate alone would understate what is recorded.

Every participant-facing app string lives in resources and ships in English and Traditional Chinese. The interface follows Android's per-app language. Ordinary researcher prose — title, purpose, researcher name, contact, and consent summary — renders exactly as signed. Survey content is the explicit exception: every localized value and its signed default are inside the signed configuration, and selection never creates unsigned text.

### Identity and survey integrity

Every import mints a new random UUID, even when the same configuration is imported twice. A personalized configuration may also contain one opaque assigned code restricted to a small ASCII grammar. The consent screen distinguishes the two modes and shows the assigned code for comparison. Both appear in encrypted metadata and bundles. Clear upload URLs and headers contain neither one, nor the experiment or configuration ID; they are not participant or device authentication.

An optional `particeps://join/v1` link adds an untrusted HTTPS host only as artifact transport. The link
binds the complete artifact SHA-256 and signer fingerprint. Android disables redirects and implicit
retry, verifies the digest before the normal signature flow, and never polls for updates. A hostile
host can deny service, but changing bytes fails before consent and cannot alter an accepted study.
Personalized authoring requires a random opaque path and rejects the assigned ID in the URL. The
URL still reaches the host and may appear in infrastructure logs, so researchers must not encode a
roster identifier into its path or operational labels.

The link controls one unauthenticated HTTPS `GET`. Redirects and connection retries are disabled,
no HTTP cookies or participant credentials are attached, and the response is tightly bounded. No
response becomes a study without the pinned digest, signature, and fingerprint. The parser does
not resolve the DNS name itself or filter the address to which DNS maps it, however. Opening an
untrusted join link can therefore make the participant's device contact an HTTPS service reachable
from its current network even though the app neither reveals that response nor accepts it as a
configuration. Join links should be treated as recruitment links, not as harmless display text.

An intervention occurrence is keyed by a deterministic SHA-256 identity over its signed logical
schedule position. A random-window trigger selects its instant locally with a CSPRNG and persists
the occurrence before scheduling. Retry and reboot do not redraw it, time-zone reconciliation only
plans future local dates, and there is no server trigger. Durable state distinguishes scheduled,
notification posted, opened, submitted, and expired. Survey submission validates stable question
and option IDs, then uses an encrypted transaction journal to commit one event and terminal
metadata together. These controls prevent duplicate commits and partial durable answers; they do
not prove that a participant saw a notification or personally supplied an answer.

The configuration admits at most 512 lifetime occurrences. This is a security and reliability bound, not an authoring suggestion. Retaining every terminal identity is what prevents an old logical firing from reappearing after recovery, and the bound keeps that set under the authenticated metadata limit instead of silently weakening idempotency.

### Data leaving the device

Study data leaves the device two ways, and both carry the same encrypted bundle.

The first is export: the participant picks a Storage Access Framework destination and the app writes the bundle there. The second is upload, which exists only if the signed configuration carries a populated `upload` block naming an `https://` endpoint, an interval, and whether metered networks are allowed. The app then posts bundles to that endpoint and to no other. A study whose `upload` block is empty transmits nothing. Which of the two applies is fixed by the configuration the participant consented to. The endpoint host, the cadence, and the network condition are rendered into the consent step from the signed bytes rather than from the researcher's free-text summary. The app asserts that block itself, directly below the summary and on the same panel as it. The manifest declares `android.permission.INTERNET`, so a build's permission list no longer distinguishes an uploading study from a non-uploading one. The signed configuration is what a reviewer should read, and the checks at the end of this document cover both.

Each bundle carries a fresh content key that is sealed to the researcher public key in the signed configuration, so only the matching private key opens it. The app never holds that private key. The authenticated document embeds the exact configuration and its original Ed25519 signature, so a decrypted bundle carries the study it came from. The [Protocol v1 contract](../protocol/v1/README.md) specifies the HPKE suite, the `PTCEXP01` container, and the context bytes that bind both cryptographic layers to the bundle, the configuration digest, and the researcher key ID.

A participant-directed export streams ciphertext to the chosen destination. Automatic upload is
different by design: before HTTP, the app durably stages one complete ciphertext bundle and a
bounded recovery manifest in no-backup storage. The bundle contains no plaintext. Only one entry exists,
and every retry after process death, reboot, timeout, or response loss sends exactly those bytes.
The manifest contains only bounded bundle/receipt bookkeeping and an optional terminal code—no
participant, assigned, experiment, or configuration ID—and is never placed in an HTTP request.

Decryption on the researcher's machine streams too, because a bundle is bounded by the study's storage quota rather than by a fixed size. One detail is deliberate and worth a reviewer's attention. `ResearchExport.decrypt` drives the AES-GCM cipher directly rather than reading through `CipherInputStream`, which reports an authentication failure as an ordinary end of stream. Through `CipherInputStream` a tampered bundle would decrypt into a silently truncated file that looks like a short study; driving the cipher directly keeps a bad tag an exception. Plaintext reaches only a mode-`0600` staging file before the tag is verified. The CLI then streams staging through `ResearchBundleVerifier`, which rechecks canonical bytes, embedded configuration and signature, identities, ranges, transition history, and catalog event contracts. It atomically publishes the file only when both phases pass and deletes staging on any failure. Anything else consuming the lower-level decrypt API inherits the same obligation: authenticate and validate the complete document before publishing plaintext.

What an upload endpoint therefore sees:

| The endpoint learns | The endpoint does not learn |
| --- | --- |
| A ciphertext bundle UUID, receive time, body size/digest, configuration digest, researcher key ID, and claimed range/count | Event content; the body is ciphertext only the researcher private key opens |
| That the same bundle UUID was replayed, and whether its bytes/metadata match | The participant instance ID, assigned ID, experiment ID, configuration ID, name, account, device identifier, or advertising ID |
| A stable configuration digest may link bundles from one issued artifact or cohort | Whether a submission came from an enrolled participant or genuine device |

All clear headers are untrusted routing claims. Receiver ingestion identity is the immutable bundle
UUID plus exact bytes and metadata, never a participant/range pair. The participant UUID and any
assigned code become linkable only after authorized decryption and remain personal data there.

Transport is TLS. The endpoint must be `https://`, validated when the configuration is decoded, and `usesCleartextTraffic="false"` remains set, so a plaintext HTTP endpoint cannot be configured or reached. There is **no certificate pinning**. The connection trusts the device's system trust store. An attacker holding a certificate that store accepts — an enterprise or otherwise installed CA, for example — can see the delivery metadata above and can substitute their own endpoint. They still cannot read a bundle.

Delivery is durable rather than best-effort. The client advances `uploadedThroughSequence` only when the endpoint returns a receipt whose every field matches the outbox manifest for that staged bundle, so an ambiguous, malformed, or mismatched response commits nothing. A terminal delivery failure ends that bundle's attempts but does not stop collection, and the participant can still export by hand. The [Protocol v1 contract](../protocol/v1/README.md) defines the status codes, the receipt fields, and which failures retry.

A failed delivery is reported to the participant as a fixed reason code — `UPLOAD_TIMEOUT`, `UPLOAD_TLS_FAILED`, `UPLOAD_HTTP_<status>`, `UPLOAD_IO_FAILED` and a few others. `StudyUploadException` carries the code instead of a message, and validates it against the same `[A-Z][A-Z0-9_]{2,63}` pattern a collector's health reason uses. A string that reaches the screen or the log therefore cannot carry study data, an endpoint's response body, or a URL. The underlying transport exception is written to the Android log, where it is a network error rather than research data. Collector health failures surface their reason code the same way.

Confirmed delivery is also what makes local data reclaimable. Once a study's storage passes 80% of its configured quota, a successful upload lets the device release whole leading segments, down to 60%. A segment is eligible only if the endpoint confirmed every event in it, and never if it is the segment still being written. `StudyMetadata.retainedFromSequence` records the lowest sequence still present, and the participant's dashboard states how many earlier events were delivered and removed. Below that threshold a study keeps everything, and an endpoint that never confirms anything reclaims nothing.

### State boundaries and failure behaviour

Entering `RUNNING` mints an admission epoch token that collectors must present, and events carry their original observation time. On pause, completion, or withdrawal the runtime takes a monotonic boundary, asks sources to release their callbacks, closes the epochs, and waits for every write already admitted before the boundary. Only then does it persist the participant transition. A failed or cancelled release leaves a typed `COLLECTION_TEARDOWN_FAILURE` marker or acknowledged background retry instead of claiming a clean pause or terminal boundary. A delayed callback cannot smuggle post-pause data into the dataset, and caller cancellation cannot erase the cleanup obligation.

A storage write failure or exhausted quota force-closes every admission gate and synchronously persists a typed witness before the failing mutation returns; the study then fail-closes to `PAUSED`. Safety-critical documents use a repo-owned acknowledged atomic writer with two independently synced copies. It directory-syncs `.pending` and `.replacement`, atomically renames only `.replacement` over the base while `.pending` remains an uncertainty witness, then requires exact base readback and another parent-directory sync before acknowledging the mutation. Witness retirement happens only afterwards; a cleanup failure may conservatively block reopening but cannot make higher layers roll back an already acknowledged base. Any remaining witness, rc.5 framework-`AtomicFile` `.new`/`.bak` residue, or unknown event-directory entry blocks recovery rather than being promoted, ignored, or parsed. Only an explicit replacement with caller-supplied known bytes may durably retire all residue before beginning a new two-copy write. There is no ring buffer and no silent dropping of events. Reclaiming space is a different thing from either: it can only release events an endpoint has already confirmed receiving, so a quota that fills with nothing delivered stops the study rather than making room. The retained floor commits before physical unlink and cannot be rolled back by stale metadata; unlink stops at the first failure so recovery always sees a contiguous suffix. What was released is recorded in `retainedFromSequence`, declared in every bundle's `first_sequence_number`, and stated on the participant's dashboard. Nothing that has not reached the research team is ever discarded to free space.

Occurrence lifecycle events and survey submissions have a stronger two-record boundary: before touching the event log, encrypted `PTCTXN01` records the proposed successor with a synthetic `RUNNING → PAUSED / STORAGE_FAILURE` transition. Recovery authenticates an exactly matching durable tail and keeps it inside that PAUSED boundary; an absent or truncated event instead recovers the prior event boundary PAUSED. The journal remains provenance until the runtime applies the first app-owned safety reason, including after repeated process deaths. A same-boundary journal is stale only when a later acknowledged main mutation proves it so. Any other boundary or content mismatch fails closed. No unverified fallback reconstructs a response from UI state.

A corrupt segment, index gap, AEAD failure, or missing key is a hard failure, and only an incomplete trailing frame in the final segment may be recovered. Event segments missing *below* the retained floor are a hard failure too, because a prefix that disappeared without being reclaimed is indistinguishable from one that was tampered away. Main metadata must name the durable tail unless the authenticated one-event journal proves the single permitted append-recovery state; no durable count is guessed or rebuilt as fallback. An export that cannot read its whole window to the boundary fails rather than producing a partial file. Missing required access at setup leaves the study in `ACCESS_SETUP`; a failed first-Start preflight leaves it `READY`, and a failed Resume preflight leaves it `PAUSED`. Only required access lost after durable `RUNNING` produces the authenticated `RUNNING → PAUSED / REQUIRED_ACCESS_MISSING` transition. A foreground service that fails to start similarly leaves or returns the runtime to its non-collecting boundary. A dataset is therefore either complete over the window it declares or absent, rather than quietly partial.

**When an event payload is authenticated, and when it is not.** Normal study opening decrypts no events. The sequence number is stored unencrypted at the front of each frame, so framing, segment index, and contiguity are checked from plaintext headers. Metadata — including each collector's last event — has its own AES-GCM tag. The only exception is the unique crash state where a one-boundary-ahead journal and a complete event tail are both durable. Opening then decrypts and authenticates exactly that tail before applying the journal. This keeps open cost linear in frames with no per-event crypto; recovery adds at most one event decrypt. **An event payload's authentication tag is otherwise verified when that event is read.** Corruption or tampering inside another event body surfaces on export or upload as a hard failure at that point. Nothing is accepted unverified, and a tampered event still cannot reach a bundle. But detection for non-recovery-tail events is deferred, so a device holding a damaged log can look healthy until its data is next read.

### Deletion

Local deletion removes metadata, event segments, and the study's Keystore alias. This is crypto-shredding: the ciphertext may physically persist in flash until overwritten, but the key to it is gone.

## Limitations

These are known limitations rather than bugs. Reports about them are handled as documentation issues — see [SECURITY.md](../SECURITY.md).

**A rooted or compromised device.** There is no root detection, attestation, or integrity checking. Code running as the app can ask the Keystore to decrypt, which yields the data even though the key itself cannot be extracted. Because no user-authentication requirement is set on the key, screen lock is not an obstacle to such an attacker.

**An unlocked device in someone else's hands.** There is no in-app authentication, PIN, or biometric gate. Anyone holding the unlocked phone can open the app, export the bundle to any destination, or delete the local data. At-rest encryption covers the seized-and-powered-off case, not this one.

**A malicious or careless researcher.** The researcher writes the configuration, chooses the collectors and their rates, writes the consent text, and holds the private key that decrypts everything participants send. The software constrains what is technically possible: only the compiled-in collectors, within validated parameter ranges, after the participant grants each Android permission. It cannot verify that the consent text honestly describes any of it. A participant who consents to a study is trusting that research team, not this software. Ethics review is the control here, and the platform supports it: the configuration is human-readable, signed, and reproduced verbatim inside every export.

One narrow part of this is closed by the data step described under *Scope of collection*. Which collectors are enabled, at what rate, and what each cannot see are stated by the app from the signed parameters, so those particular claims cannot be softened in the telling. Everything around them — why the data is collected, who reaches it, how long it is kept, what withdrawal means on the research side — is still the researcher's prose, and nothing in the app checks it.

**Publisher impersonation.** The researcher name and contact shown on the consent screen come from the signed configuration, which makes them text the signer chose. Anyone can generate an Ed25519 key, write a configuration naming any research team, sign it, and produce a file that verifies on a build with no pinned signers. The signature is genuine; what it certifies is the file, not its author.

Three things narrow this. The consent step shows the key fingerprint under the heading *Configuration signature*. That fingerprint is the first 16 bytes of SHA-256 over the raw 32-byte Ed25519 public key, rendered as eight uppercase groups of four hexadecimal characters; a join link pins the same value without the spaces. When the signer is not pinned, the step asks the participant to check that fingerprint against the one their research team published, noting underneath that a signature shows a file is unaltered rather than who wrote it. Deliberately none of it is in the error colour. An unpinned signer is the deployment model, not a failure, and rendering the ordinary case as an alarm trains a reader to skip the block. The mitigation here depends on the participant actually performing a comparison, so the text is an instruction rather than a warning. That wording is in the app's string resources rather than in Kotlin, so it is translated with the rest of the interface and no configuration can alter it. A team that publishes its fingerprint through the channel that recruited its participants gives them a check that copied prose does not defeat. And a configuration is not an anonymous download: it reaches a participant through a relationship that already exists, so an impersonator has to get their file in front of someone through that channel.

A build that pins its signers removes this exposure for the studies it accepts, and accepts nothing else. See the [researcher guide](researcher-guide.md) for both sides of that choice.

**What happens to a bundle after it leaves.** Once the participant picks a destination, or the app posts a bundle to the study's endpoint, the bytes are beyond the app's reach. Confidentiality holds — only the researcher's HPKE private key opens them — with three caveats:

- A bundle is not authenticated as to origin. It is encrypted *to* the researcher, not signed *by* the device. Anyone with the raw researcher public key, which is inside every signed configuration, can fabricate a syntactically valid bundle that decrypts cleanly. A decryptable bundle is not proof of who produced it, and an endpoint receiving one has no cryptographic evidence that a real participant device sent it.
- The bundle header exposes the researcher key ID in cleartext, and the suggested export filename contains the study ID and an export timestamp. Anyone handling the file can tell that this person participated in that study.
- Size discloses roughly how much data was collected. The same is true on the device: event segment file sizes and modification times leak collection volume and timing to anyone with filesystem read access, without any decryption.

**What a notification discloses to someone holding the phone.** Three ordinary notification purposes are visible without any decryption. The ongoing collection notification is present only while collection is actually running, and it carries the study title on its second line. The same channel may briefly show an app-authored neutral restoration notification after Android redelivers an old service intent; that variant contains no study title and is replaced only after revalidation or removed when the stale service stops. An intervention notification is posted only while the study is running, one per occurrence. Its title and message are the researcher's own text out of the signed configuration, so a survey prompt is on the screen in whatever words the researcher chose. Its channel is `IMPORTANCE_DEFAULT`, so unlike the other two it alerts. The third purpose is the daily status reminder. Once a study has started, `DailyStatusWorker` posts one notification a day for as long as the study is either collecting or paused. It is the only notification the app shows while a study is paused, because pausing stops the foreground service and cancels every intervention notification. Its title line is the application's own name; its body says either that collection continues or that the study is paused and since when. It carries no study title, no researcher name or contact, no counts, and no collector names. A bystander therefore learns that this phone runs Particeps and which of those two states it is in — not which study, not what that study records, and not who is running it. The channel is `IMPORTANCE_LOW`, so the reminder is silent. One notification tag is reused, so today's replaces yesterday's rather than accumulating. Notification permission and the required channels are setup prerequisites; without them a study cannot start, and revoking or disabling one while running triggers a fail-closed pause at the next access reconciliation. The app sets no lockscreen visibility on either the channel or the notification, so the device's own setting for notification content on a locked screen is what decides whether the text can be read without unlocking.

The residual risk is that the existence and the duration of a study become visible to whoever holds the phone. That is the cost of the reminder rather than a defect in it: a pause that nothing mentions is how a study meant to run for a fortnight quietly records nothing. But it is a standing daily disclosure for the study's whole length, and it is the one surface that goes on disclosing after a participant has paused. Starting or stopping collection retracts a reminder that is already showing, and finishing or withdrawing cancels both the schedule and the notification, so none of it outlives the study. Android still lets a participant disable a channel, but Particeps treats a required channel as missing access and pauses collection rather than silently continuing without the promised status surface.

**A compromised or hostile upload endpoint.** An endpoint that is taken over, misconfigured, or logging more than intended still cannot read a bundle without the researcher private key. It does learn the untrusted bundle metadata above, including a stable configuration digest that can link submissions from the same issued artifact. It can refuse delivery indefinitely; the device retains the data and collection continues. Conversely, an endpoint can fabricate a matching receipt without keeping the body. That can advance the watermark and eventually make those events reclaimable under storage pressure. Receipt matching makes response loss and accidental mismatch safe; it cannot prove remote durability against the server itself. Treat the endpoint as study infrastructure, keep the decryption key off it, minimize logs, and state its operator in consent material.

The receiver ingress has no participant authentication or device attestation. An attacker can submit
bounded bogus ciphertext and consume storage. Deployment-time configuration-digest/key allowlists,
the 32 MiB body limit, WAF/rate limits, create-only object writes, and R2 lifecycle policy bound the
cost; they do not establish origin.

**Inference from the data itself.** That location traces, motion sensors, temporal context,
keyboard touch dynamics, and app usage patterns can identify a person is a property of the data,
not a defect in the software. Minimisation and consent are the controls. The keyboard collector
cannot see text, but its timing and within-key position data are behaviourally distinctive; the
[data dictionary](data-dictionary.md) states this per collector.

**Assigned IDs and survey responses are direct governance responsibilities.** An opaque assigned code can still be identifying to the team that holds its roster, and free-text survey answers can contain names or other sensitive details. Bulk personalization keeps codes out of filenames and logs, and transport keeps them out of headers, but decryption intentionally reveals them to the private-key holder. Ethics review should minimize free text, document the roster join and retention policy, and state that closing an unfinished survey stores no answer while submission is final.

**Notification timing is not participation evidence.** WorkManager is inexact, devices can delay work, and `NOTIFICATION_POSTED` only records that Android accepted the post. An occurrence ID prevents duplicate logical delivery across recovery; it cannot prove visibility, attention, or who tapped. Analyses must keep scheduled, posted, opened, submitted, and expired as separate outcomes.

**Configuration replay and clock manipulation.** The signed envelope has no nonce and no device binding, so the same configuration can be imported on any number of devices until it expires. Validity is checked against the device wall clock, so a participant who moves their clock backwards can revive an expired configuration. Keep validity windows short; a multi-year window makes both worse.

**No signer revocation.** There is no revocation list, no in-protocol rotation, and no kill switch at any layer. A leaked study signing key can mint configurations that any build with an empty anchor map accepts, and configurations already signed with it stay valid until they expire. A short validity window is the only control. Rotating a study signing key is possible but entirely manual. The researcher generates a new key, puts it in the `signer` block of a new configuration, re-signs, and republishes the fingerprint through the channel that recruited the participants, as the [researcher guide](researcher-guide.md) sets out. No app release is involved, and nothing on a device learns that the old key was retired, so a rotation governs configurations signed after it and nothing already issued. Where a build does pin signers, that set is fixed and auditable at build time, and retiring one of those keys requires shipping a new APK.

**Key loss.** Losing the researcher HPKE private key makes every export for that configuration permanently unreadable. There is no escrow and Protocol v1 names exactly one raw recipient key, so multi-recipient encryption is unavailable. Losing the device's Keystore key — through device wipe, uninstall, or clearing app data — destroys all un-exported local data. Neither case has a recovery path.

**Per-collector supervision is not fail-closed.** A collector that crashes or loses its permission is marked `FAILED` or `BLOCKED_ACCESS`, and the study continues with the others. This keeps one flaky data source from ending someone's participation, but a dataset can be missing one collector's data for a period while the study looks healthy overall. The collector's status and the resulting gap are visible in the data.

## Deployment requirements

The shipped build pins no signers. It accepts any correctly signed configuration and tells the participant that the publisher is unverified. That is the deployment model rather than an outstanding task. The default build compiles in no trust anchor at all, so it does not privilege the demonstration signer, whose private half is published in this repository. That signer has no standing that any other signer lacks.

The demonstration keys remain public and the release variant ships no demonstration study, so a participant who installs a release cannot start a study whose export key is published in this repository. See [`researcher-tools/examples/README.md`](../researcher-tools/examples/README.md) for both.

Note what that boundary does not claim: it removes a foot-gun, not an attack. Anyone can still sign their own configuration with the published demo key and hand the file to someone, because pinning no signers is the deployment model. The consent screen is what carries that, by reporting the publisher as unverified and showing the fingerprint.

What a real deployment owes participants is its own key pairs and a published signing key fingerprint. Distributing that fingerprint through the channel that recruits participants is what gives the consent screen something to be compared against. An institution that wants one build to run only its own studies pins its key in `CollectorApplication.TRUSTED_SIGNING_KEYS` and ships that build, which then refuses every other signer.

## Supply chain

The dependency set is small: Tink for cryptography, Gson for parsing, AndroidX Compose, Lifecycle and WorkManager, Google Play Services Location for the fused location provider, and OkHttp 5.3.0 for the upload request. There is no analytics, crash reporter, or telemetry library. OkHttp is used from one class, `OkHttpStudyUploader`, which builds a single POST to the endpoint the signed configuration names. Play Services Location is the only other dependency with a plausible network surface.

Gradle and package lockfiles make the selected dependency graph reviewable. Vulnerability and
secret-scan results are point-in-time observations rather than a continuing guarantee, so re-run
the relevant scans before deployment.

## Verifying this yourself

```bash
# Permissions actually present in a built artifact
aapt dump permissions app/build/outputs/apk/release/app-release.apk

# Whether a study uploads, and where to: the `upload` block of its canonical configuration
jq .upload ./study-canonical.json

# Who signed a study, its key fingerprint, and whether the signer is pinned
./gradlew :researcher-tools:run --args="check-config --envelope ./study.partcfg"

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
