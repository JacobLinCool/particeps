# Threat model

This document describes the current Particeps Protocol v1 implementation, including the durable
event-driven runtime and optional per-App traffic shaping. It is suitable as an implementation
attachment to an ethics/security review, not as a generic claim about every Android device.

Normative implementation sources are [Protocol v1](../protocol/v1/README.md), the
[event-source registry](../protocol/v1/event-source-registry.json), [system design](system-design.md),
and the [Collector capability policy](../assurance/README.md).

## Security and privacy goals

Particeps aims to:

- accept only one exact, bounded, canonical, correctly signed study configuration;
- let the participant control start, pause, resume, completion, withdrawal, export, and deletion;
- admit collector data only while the complete required resource vector is verified;
- preserve observation, reducer, timer/action, resource, epoch, and lifecycle causality through
  process/power/storage failures;
- encrypt local study state with an Android Keystore key and exported bundles to the signed
  researcher X25519 key;
- reveal no participant identity in clear upload routing metadata;
- prevent one collector/actuator or researcher-authored rule from escaping its declared capability;
- avoid collecting VPN packet content, destination, DNS name, installed-app inventory, or per-App
  shaped-flow totals;
- avoid exposing derived treatment/control state in Particeps-generated participant UI.

It does not aim to provide device attestation, defend a rooted/compromised OS, prove the participant
read a notification, make Android usage history complete/realtime, make external side effects
exactly-once, anonymize research data after decryption, or hide the presence of Android’s VPN icon.

## Assets

- participant consent and control state;
- signed study configuration and publisher fingerprint;
- encrypted local collector/system events, source coverage, survey answers, and opaque participant
  identifiers;
- reducer checkpoint, timers, actions, desired/applied resource evidence, and condition epochs;
- traffic-shaping target-package declaration and aggregate counters;
- local Android Keystore key;
- researcher Ed25519 signing private key and X25519 export private key;
- immutable ciphertext uploads and canonical receipts;
- build provenance, dependency checksums, registry digest, and release-signing identity.

## Trust boundaries and actors

### Participant and Android device

The participant is trusted to make their own consent/control choices but is not assumed to validate
binary protocol details. Android is trusted to enforce app sandbox, Keystore, permissions,
`VpnService`, package ownership, UID, and network capabilities. The hardware/OS can still drop,
delay, redact, or approximate sensor/usage/network information.

A rooted device, malicious custom OS, instrumentation with equivalent privilege, or stolen unlocked
phone can bypass these assumptions. Particeps detects inconsistencies it can observe; it does not
attest the device to the researcher.

### Researcher/configuration author

A signer is allowed to request only registry-declared resources, fields, predicates, schedules, and
actions within fixed bounds. Signing establishes integrity and key possession, not ethics,
institutional identity, or appropriateness. A malicious authorized researcher can still write
coercive consent text, choose sensitive allowed sources, or disclose treatment in free text.

Mitigations are signer fingerprint comparison, closed-world capability/access/privacy metadata,
participant review/decline/withdraw, Web blinding acknowledgement, ethics review, and offline
analysis provenance. Runtime cannot semantically police the researcher-authored study title,
purpose, researcher name/contact, consent, notification, or survey wording that it renders
verbatim.

### Artifact host, network, and upload receiver

The configuration host and receiver are untrusted for plaintext. Immutable join links bind HTTPS
artifact URL, SHA-256, and signer fingerprint; Ed25519 binds exact configuration bytes. TLS remains
important for availability/metadata privacy but host substitution fails digest/signature checks.

Uploads are HPKE/AES-GCM ciphertext. The receiver learns request time, byte size, bundle UUID,
configuration digest, researcher key ID, complete commit range/count, and event count. It does not
receive participant ID or plaintext routing fields. A malicious receiver can drop, delay, reject,
replay, or retain ciphertext; it cannot forge a matching exact receipt without returning the
canonical metadata/digest Particeps verifies. Availability is not guaranteed.

### Analysis environment

The holder of the researcher X25519 private key can decrypt all fields authorized by the study,
including location, usage packages, survey answers, opaque participant code, and causal treatment
record. Encryption is not anonymization. Access control, retention, roster separation, and research
governance after decryption are outside the Android app and remain researcher obligations.

## Configuration and automation attacks

All JSON is strict UTF-8 RFC 8785 canonical form. Unknown/duplicate members, noncanonical numbers,
invalid strings, oversized lengths, wrong platform/version, expired configuration, invalid key,
signature mismatch, and hostile old shapes fail before import.

The event-source registry and generated codecs reject unknown source/event/profile fields and
physical bounds. Automation compilation rejects arbitrary code/regex/SQL/remote triggers, illegal
field/operator/clock combinations, unbounded memory, cycles, multiple resource owners,
self-disabling trigger sources, output-event feedback, and impossible liveness.

Residual risk: a valid bounded automation can still implement an ethically undesirable
intervention. Limits contain runtime capability and resource use; they do not replace study review.

The pure reducer cannot read clocks, randomness, Android, or storage. Coordinator observations,
random selections, and epoch UUIDs are durable inputs. Cross-language checkpoint digest equality
makes an implementation-specific interpretation detectable in conformance/analysis.

## Local confidentiality and integrity

Study data lives under Android `noBackupFilesDir`. A non-exportable Android Keystore AES key encrypts
runtime snapshot, pending input, and each commit frame with AES-GCM and fresh IV. Sequence/current
commit digest are authenticated as associated data. Android backup rules exclude study material.

This protects against ordinary filesystem extraction without the device key. It does not protect
plaintext while the app process legitimately handles it, an unlocked compromised OS, screenshots
of researcher-authored UI, or forensic access with platform/Keystore compromise.

### Append-only commits

`EngineCommit` is the incremental truth. It binds previous/current commit digests, input kind,
observation manifest and event range, typed mutations, committed time, successor projection, and
resulting checkpoint digest. The encrypted footer and frame identity must agree with decrypted
content. Source-observation SHA-256 independently binds batch identity, generation, epoch, coverage,
and exact events.

Appending acknowledges the complete frame before the runtime snapshot cache advances. A torn final
uncommitted tail can be truncated; segment index gap, missing interior frame, AEAD failure, digest
divergence, noncontiguous commit/event/observation sequence, or invalid successor is hard failure.
No count, event, or resource state is guessed from a surviving neighbor.

The bounded encrypted pending-input slot protects a causal batch while admission is closed for a
resource barrier. A commit can consume it only by naming the exact digest. Recovery with a staged
cause commits a quality gap and safety pause if safe continuation cannot be proved.

### Snapshot performance boundary

Normal open authenticates the latest runtime snapshot, verifies its named commit boundary, and
decrypts/replays complete frames after that revision. It does not reprocess historical event payloads
already represented by the authenticated checkpoint, which keeps cold-start cost bounded for long
studies. Export/upload and any ranged commit read still decrypt and authenticate every selected
frame.

Thus corruption in a historical reclaimed-unread frame is detected when that range is read rather
than by every clean startup. It cannot enter a verified bundle: export/upload fails before
publishing/staging success. A missing/corrupt snapshot falls back only to full authenticated
commit-chain replay, not to unauthenticated metadata reconstruction.

Old layout files are explicitly detected and rejected. The app uses a participant-confirmed generic
reset flow; it neither migrates nor silently deletes/uploads them.

## Lifecycle and crash safety

Lifecycle commands and results are ordered `study_runtime.v1` events. Start/Resume enters
`ACTIVATING`; data admission remains closed until every required resource applies/verifies and one
condition epoch plus full vector digest commits. Pause/Complete/Withdraw closes admission first,
flushes/drains, closes the epoch, releases resources, and only then finishes lifecycle.

Any process death/reboot from `ACTIVATING`, `RUNNING`, or `PAUSING` recovers to `PAUSED`. There is no
automatic resume and no backfill of UsageStats/network accounting for the unverified interval.
Participant and safety commands outrank automation.

A reboot first observed from durable `PAUSED` also discards retrospective cursors and records a
quality gap. Resume is allowed only after trusted UTC establishes a new same-boot anchor and durable
deadline generation. If that proof is unavailable, the runtime remains fail-closed `PAUSED`;
Complete and Withdraw remain possible because they never open admission.

The signed study duration is not policed by collector traffic. A runtime-owned authenticated
deadline component binds the configuration digest, generation, same-boot target, and logical UTC
evidence. The admission gate treats that target as exclusive, so a late Android worker cannot admit
post-deadline events; its eventual verified wake retires the timer and completes the lifecycle.

The terminal callback from collector/VPN/native can only synchronously close admission and wake the
serialized coordinator. It cannot append unauthenticated system events or reopen resources. Stale
resource/timer/action generations are rejected.

Residual risks:

- Android can kill the process between a real-world side effect and recording its result. Durable
  idempotency keys/reconciliation prevent duplicate internal invocation but cannot prove an
  arbitrary notification or external component acted exactly once.
- Filesystem/flash hardware can violate documented durability below successful `fsync`. Particeps
  uses acknowledged file/directory sync and fail-closed reopen, but cannot repair dishonest storage.
- Forced process termination can delay the visible paused notification until Android restarts the
  app; no collector event gate survives the process to accept data during that gap.

## Source completeness and time

The registry makes delivery/completeness/clock limits explicit. Sensor callbacks can drop under
load. `UsageStatsManager.queryEvents()` is retrospective and not promised complete or timely.
`NetworkStatsManager` totals are coarse and lag. Location is an estimate. Android bandwidth is a
capability estimate, not achieved throughput.

Polling sources commit coverage and durable cursor even for empty polls. Ordinary resource barriers
flush and split coverage at the shared boundary. A reboot or wall-clock discontinuity instead
closes admission, discards every retrospective cursor and crossed backlog without a flush, resets
latch/keyed-presence/window/sequence state, and requires a verified replacement epoch before new
data. If the gap crosses the signed deadline, the study completes without reopening admission. The
analysis pipeline refuses a retained cursor, retrospective observation in the discard commit,
orphan epoch, or mixed attribution instead of interpolating.

The opaque activity-component token prevents class-name persistence but is stable enough within the
study to balance observed activity resume/exit. It does not make Android usage history complete and
can still be linkable within that one dataset.

## Traffic-shaping security and privacy

### Scope

The local VPN is an Android packet-forwarding mechanism, not a confidentiality VPN and not a
Particeps gateway. Selected packages keep using their ordinary underlying network. Remote
destinations still see the device’s ordinary source path; ISP/network observers retain their normal
visibility. Particeps does not add transport encryption.

Only signed packages enter the VPN allowlist. Unselected control apps bypass it. Selected apps share
aggregate uplink/downlink token buckets; throughput is bounded but latency, jitter, and packet loss
are not controlled claims.

### Permission and package visibility

Arbitrary signed package names cannot be declared statically in `<queries>`. Reliable installed and
shared-UID proof therefore uses `QUERY_ALL_PACKAGES`, but implementation queries only signed names
and their UID peers. It never saves/uploads inventory. This permission and VpnService require
Google Play declarations if distributed there.

On Android 17+, protected direct-proxy sockets that reach the LAN require local-network runtime
permission. Particeps uses it only for connections selected apps initiate and does not perform
discovery. Denial/revocation is safety failure, not silent LAN bypass.

Residual privacy risk: package installation checks occur locally and Android permission/system UI
can reveal that the app requested broad visibility/VPN/LAN access. A compromised app process/OS
could misuse granted privilege; source review and Android sandbox are the protection boundary.

### VPN ownership and recursion

Proof requires a fresh generation-scoped owned `Network`, `ownerUid == Process.myUid()`, open native
TUN, healthy forwarder/limiter, unchanged package/UID snapshot, successful socket protection, and
exact profile receipt digest. `VpnService.prepare()`, default VPN transport, TUN alone, or always-on
state is insufficient. Android may redact another VPN’s owner, so Particeps reports only ours/not
ours.

Particeps itself is excluded from the allowlist and every forwarder TCP/UDP socket calls
`VpnService.protect(fd)` before use. Protect failure is terminal. This prevents ordinary routing
recursion but cannot defend against OS-level misrouting.

Android permits one VPN per user/profile. Another VPN can revoke/replace Particeps; `onRevoke`,
unexpected VPN-service destruction, or loss of all owned networks closes admission and pauses.
Intentional release is distinguished before cleanup; an unexpected `onDestroy` signals the runtime
before discarding TUN/native proof. Particeps cannot safely identify a replacement VPN and does not
guess.

### Native supply chain and logging

The forwarder is built from pinned Go/tun2socks/gVisor/x-mobile source through Go proxy/checksum
database with no direct fallback. No opaque AAR/`.so` is checked in. Release verification checks
four ABIs, 16 KiB alignment, sums/provenance, SBOM/licenses, and tracked artifacts.

Upstream logging is disabled before network activity. The Kotlin/native API returns typed bounded
status/counters and never raw socket/tunnel errors containing addresses. Tests scan release logs
under traffic/failure. Residual risk is a defect in gVisor/tun2socks/Go runtime or future dependency;
pinning/reproducible source build makes review possible but is not a formal verification.

### Traffic data minimization

Only aggregate Layer-3 bytes, packets, and union of throttled wait duration by direction are kept,
with profile/resource/VPN generation and epoch evidence. No payload, packet sample, source/destination
address, port, hostname, DNS name, per-package flow, or installed-app list is recorded.

These aggregate counters can still reveal coarse activity volume/timing and are research data.
Sixty-second cadence and epoch-boundary snapshots are visible after decryption. Consent and data
governance must cover that inference.

## Condition epochs and treatment integrity

An epoch is active only after all required resource receipts verify and the canonical full vector
digest commits. Every collector observation/event carries its UUID. Resource change closes the old
epoch before admitting data under the new vector. Traffic audit binds to the generic epoch and does
not create a parallel assignment truth.

Analysis checks no overlap, exact activation/deactivation, source coverage, applied digests,
resource generations, and causal reducer replay. Missing epoch, orphan event, mixed coverage, or
digest mismatch prevents dataset publication.

This proves internal Particeps assignment/admission consistency under the trusted-device model. It
does not prove an external app consumed the network exactly as expected, a server response time was
caused only by the cap, or the participant was attending to the app.

## Participant UI and blinding

Compose receives a whitelist participant projection rather than the full signed/runtime model.
Particeps-generated screens/notifications do not carry target packages, control conditions,
resource settings, timers, epochs, vector/digests, owner UID, or typed failure reasons. Shaping adds
one fixed high-level disclosure in the existing Access step plus Android’s mandatory permission/VPN
system surfaces; it does not add a dashboard or second ongoing notification.

Accessibility semantics and screenshot/snapshot tests inject sensitive fixture values and assert
they do not appear. This protects against accidental derived UI leakage, not malicious
researcher-authored text: study title, purpose, researcher name/contact, consent, notifications,
and surveys are rendered verbatim. Those signed free-text fields are the explicit blinding
exception. Web requires the researcher to acknowledge their blinding/ethics responsibility before
signing, and ethics review remains responsible for the content.

Lock-screen observers can learn that Particeps is running and Android can show a VPN icon. Neutral
notification copy omits study/treatment identity but cannot hide app/VPN presence from the OS or a
person inspecting device settings.

## Export cryptography and metadata

Each bundle uses a fresh random 32-byte AES-256-GCM content key and 12-byte nonce. Fixed-suite RFC
9180 base-mode HPKE wraps the key to the signed raw X25519 public key. Cryptographic context binds
format, bundle UUID, configuration digest, and researcher key ID. Canonical decrypted document
repeats configuration/signature/registry and complete commit chain.

Wrong key/context, ciphertext modification, appended/truncated bytes, noncanonical document,
signature mismatch, registry mismatch, commit/observation/checkpoint/epoch inconsistency, or partial
range fails before plaintext-derived output is published.

HPKE here provides confidentiality to the configured key and integrity of the bundle, not sender
authentication or forward secrecy after researcher private-key compromise. Configuration signature
authenticates the researcher-authored definition, not the phone. A malicious device can fabricate
plausible inputs within its OS trust boundary; there is no attestation.

Clear request metadata and traffic analysis can reveal study configuration, approximate data
volume, and upload timing. Use a receiver policy and study design appropriate to that leakage.

## Analysis and publication boundary

Python analysis has an independent strict parser/reducer. It inventories ciphertext by content
digest, verifies whole bundles, reassembles complete chains, rejects conflicts/gaps, checks
observation coverage, timer/action causality, resource/epoch/vector receipts, and matches canonical
checkpoint digest after every input. Only then does it atomically publish Parquet/provenance/quality
artifacts.

One invalid bundle in the selected dataset prevents publication; there is no “skip bad row,” schema
inference, averaging across epochs, or proportional allocation of unknown coverage. This makes data
loss visible but can create an availability attack: one malicious/corrupt object can block that
dataset until it is quarantined by an explicit operator decision. Inventory records the failure so
the decision is auditable.

## Denial of service and resource exhaustion

Protocol lengths, collector rates, batch size, event fields, automations, windows/state, timers,
actions, profiles/packages, pending input, commit frame, local quota, and upload body are bounded
before allocation. Native queues/backpressure are bounded. A new desired resource generation
supersedes an unexecuted older one.

An authorized high-rate study can still consume meaningful battery, CPU, storage, mobile data, and
VPN capacity within those bounds. Web estimates and participant disclosure help; Android may throttle
work. The participant can pause/withdraw and revoke access. Required failures pause rather than
silently degrade.

## Logging, diagnostics, and deletion

Release logs use generic lifecycle/health information and exclude event fields, survey answers,
package targets, packet/tunnel address text, destinations, DNS, and payloads. Debug diagnostics are
bounded to development builds and should still avoid collected values. Participant UI receives
generic messages, not internal reason codes.

Completion/withdrawal permits participant-confirmed deletion of snapshot, pending input, commit
segments, staged upload/export metadata, and Keystore alias. File/directory operations are
acknowledged; failure remains visible and retried rather than reporting deletion early. Flash
wear-leveling means ordinary file deletion is not guaranteed forensic secure erasure; loss of the
non-exportable encryption key is the practical confidentiality boundary.

No app backup or server-side recovery exists. Researchers must document receiver-side retention and
deletion separately.

## Verification evidence

The repository gates:

- registry generation/digest and hostile/current conformance across Kotlin, TypeScript, Python;
- reducer/property/fake-clock and storage fault-injection tests;
- API 34 plus API 37 16-KiB Android lanes and host-orchestrated kill/reboot/VPN/package scenarios;
- Go vet/race, token-bucket throughput, TCP/UDP/DNS/IPv4/IPv6/protect/silent-log tests;
- Web validation/simulation/participant preview and Compose accessibility leakage sentinels;
- Python encrypted-bundle replay/materialization failures;
- release manifest/ABI/alignment/sums/SBOM/licenses/registry/no-binary/no-sensitive-log checks.

Passing these gates is evidence about the checked source and environment, not a proof against every
OEM modification, future Android change, dependency vulnerability, or malicious rooted device.
Security/privacy claims must remain no broader than the trusted boundaries and residual risks above.
