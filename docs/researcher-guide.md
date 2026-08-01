# Researcher guide

Android Data Collector runs a study from a signed configuration file, so a new study does
not need a new app. You describe the study in JSON — which collectors run and with what
sampling parameters, how long it lasts, what the consent summary says, which prompts are
scheduled, how much local storage it may use, which public key its bundles are encrypted to,
and whether it delivers them to an endpoint on a schedule — sign that file with your study
key, and hand it to participants. The participant app verifies the signature, presents the
study, and runs exactly what the configuration specifies.

v1 ships seven collectors — app lifecycle, accelerometer, network state, network usage,
usage events, location, and research-keyboard touch dynamics — and runs the complete
on-device loop on Android 14–17 (`minSdk 34`, `compileSdk`/`targetSdk 37`). Changing which
of them a study uses, how often they sample, or how long the study lasts is a configuration
change. Adding a collector that does not exist yet is a code change; see
[`data-collector-implementation-guide.md`](data-collector-implementation-guide.md).

Deploying a study is a short pipeline, and this guide follows it:

1. Generate the study signing key and the export encryption keyset (section 3).
2. Write the study configuration (section 4).
3. Canonicalise, sign, and verify it (section 5).
4. Distribute the participant app and the `.adccfg` file, and publish your signing key
   fingerprint in the material that recruits participants (section 6).
5. Pilot on the Android versions and hardware your study targets (section 7).
6. Receive encrypted bundles — exported by participants, uploaded by their devices, or
   both — and decrypt them (section 9).

Sections 1 and 2 come first because they shape the design: what the data can and cannot
support, and how the two key pairs must be handled.

The app runs the on-device loop; it does not run your study. Before you recruit anyone, the
research team is responsible for:

- ethics review and approval,
- the consent document and the accuracy of the consent summary you ship,
- generating and protecting the study signing key and the export decryption key,
- publishing your signing key fingerprint where participants can check it against the
  consent screen,
- validating behaviour on the devices your study actually targets,
- data governance after an export reaches you, and
- incident response if a key, a device, or a decrypted dataset is compromised.

The app enforces none of these. It enforces what is written into the signed study
configuration, and it fails closed when it cannot.

## 1. Understand what the data can and cannot support

Read this table before you design the study. The right-hand column is the one that
matters: it is the set of claims your data cannot carry, no matter how the analysis is
written up.

| Collector | What you obtain | What you cannot claim |
| --- | --- | --- |
| `app_lifecycle.v1` | Lifecycle transitions of this app's own Activities, each with the Activity class name | Anything about time spent in other apps. This instruments the collector app, not the participant's phone use. |
| `accelerometer.v1` | Raw x/y/z acceleration in m/s² in device coordinates, the sensor's own timestamp, and the platform accuracy code | A recognised movement, posture, or activity. The app ships no classifier and produces no ground-truth label. |
| `network_state.v1` | Transport flags for the default network (`wifi`, `mobile`, `ethernet`, `vpn`), `validated`/`metered`/`roaming`, and optional link bandwidth estimates | SSID, BSSID, IP address, hostname, URL, packet contents, or who the device communicated with. None of it is read. |
| `network_usage.v1` | Device-total `rx_bytes`/`tx_bytes`/`rx_packets`/`tx_packets` per transport, over an explicit `[coverage_start_utc_millis, coverage_end_utc_millis]` window | An instantaneous throughput, a per-app attribution, or the precise time traffic occurred. The coverage window is the finest resolution that exists in the data. |
| `usage_events.v1` | Raw platform events — activity resumed/paused/stopped, screen interactive/non-interactive, keyguard shown/hidden, device startup/shutdown — with the reporting package name where the platform supplies one | A complete or real-time session stream. The platform delays events, omits events, and does not guarantee that resume and pause pair up. |
| `location.v1` | Fused Location fixes with latitude/longitude, per-fix accuracy fields, the fix's own source time, and the platform `mock` flag | That the participant was at that coordinate, or that the track is continuous. Fixes are estimates, sampled and batched, with gaps you did not choose. |
| `keyboard_touch.v1` | Touch dynamics inside the research keyboard: key-relative x/y in `[0,1]`, event and down uptimes, `pressure`, `size`, `orientation_radians`, `tool_type`, and key category | Touches anywhere else on the system, the text that was typed, or a calibrated physical force. `pressure` and `size` are device-specific relative values. |

Package names, location, fine-grained timing, acceleration, and keyboard dynamics can all
be highly identifying, individually and in combination. Apply data minimisation as a
design constraint, not an afterthought:

- **Fewest collectors.** Include a collector only if a stated research question fails
  without it.
- **Lowest usable frequency.** Higher sampling rates raise re-identification risk and
  battery cost faster than they raise analytical value.
- **Shortest duration.** `duration_hours` is a ceiling you are asking a participant to
  accept; ask for the smallest one that answers the question.
- **Smallest local quota.** `storage.maximum_local_bytes` bounds how much of a
  participant's data can accumulate on their device before the store refuses writes.

A field-level reference for every collector's payload is in
[`data-dictionary.md`](data-dictionary.md). The adversary model, and what the design does
and does not defend against, is in [`threat-model.md`](threat-model.md).

## 2. Key responsibilities

v1 uses two key pairs with different purposes. They are not interchangeable.

| Key | What the private key does | Where the public key goes |
| --- | --- | --- |
| Ed25519 study signing key | Signs the canonical study configuration bytes | The signed study configuration itself, as `signer.public_key` |
| Tink X25519/HPKE keyset | Decrypts every bundle, exported or uploaded | The signed study configuration, as `export.tink_hpke_public_keyset` |

Both public halves therefore travel inside the configuration, and neither requires an app
build. A configuration certifies itself: the app verifies the signature with the key the
file carries. What that buys you is one published app running any study; what it costs is
that a valid signature proves the configuration is unchanged since signing, not who wrote
it. Section 6 covers the fingerprint you publish so participants can close that gap.

The consequences differ, so track them separately.

- **Signing private key lost.** You cannot issue or reissue configurations under that key
  ID. Existing `.adccfg` files already in participants' hands keep working until they
  expire. Recovery means generating a new key, putting it in the `signer` block of a new
  configuration, and re-signing — no app release is involved.
- **Signing private key leaked.** Anyone holding it can mint a configuration that verifies
  as yours, including one that enables more collectors, and it will carry your published
  fingerprint. Treat this as an incident: stop distributing the affected `.adccfg`, publish
  a new key and fingerprint, re-sign under the new key ID, and notify participants. There is
  no revocation mechanism, so configurations already signed under the old key remain valid
  until they expire — which is a reason to keep validity windows short.
- **HPKE private key lost.** Every export encrypted to that keyset is permanently
  unreadable. There is no escrow and no recovery path. Participant devices cannot re-encrypt.
- **HPKE private key leaked.** Anyone holding it can decrypt any export bundle for that
  study that they can obtain. Rotating the keyset requires a new `configuration_id`, a new
  signature, and fresh consent.

Do not reuse one key pair for both roles, and do not reuse either across unrelated studies.
Real private keys must never enter the app, Git, a configuration file, chat, a ticketing
system, or a participant device. Before the first participant is enrolled, write down the
custodian, the encrypted backup location, the recovery rehearsal, the rotation date, the
revocation and disclosure procedure, and the destruction date.

Note that the Android APK signing key is a third, separate key. It is not either of the
above.

The private keys under [`researcher-tools/examples`](../researcher-tools/examples) carry an
`INSECURE-` filename prefix because they are committed to a public repository. They exist so
a debug build can exercise the whole loop, and they are equivalent to fully disclosed keys:
anyone can sign a configuration under the `demo-signer-2026` key ID, and anyone can decrypt
an export encrypted to that HPKE key. They must never be used for a study involving real
participants.

A release build ships no demonstration study — the signed envelope and the code that loads
it are in the app's `debug` source set only — so a participant who installs a release can
run nothing but a configuration you signed and gave them. That is a packaging boundary, not
a trust one: the build pins no signers, so a configuration signed with the demo key would
still verify if someone handed one over. What the participant has to work with in that case
is the consent screen's signer fingerprint and your published copy of it.

## 3. Use the researcher CLI

Requirement: JDK 17. No command overwrites an existing output path. The key, canonicalisation,
and signing commands open their output with `CREATE_NEW`; `decrypt` checks the destination
first and then stages its plaintext through a temporary file, for the reason given in
section 9.

Generate a production signing key:

```bash
./gradlew :researcher-tools:run --args="signing-keygen \
  --private /secure/study-signing-private.key \
  --public /secure/study-signing-public.key"
```

Generate the export HPKE keyset:

```bash
./gradlew :researcher-tools:run --args="hpke-keygen \
  --private /secure/export-hpke-private.json \
  --public ./export-hpke-public.json"
```

The public signing key is a base64 X.509 Ed25519 key. Paste it into the study
configuration's `signer.public_key`, alongside the key ID you will sign with, and paste the
HPKE public keyset JSON object into `export.tink_hpke_public_keyset`. Both private keys stay
in the controlled research environment. Neither public key goes into an app build.

An institution that wants one build to accept only its own studies can additionally pin the
signer — see the end of section 6.

The full command surface is:

```text
signing-keygen --private FILE --public FILE
hpke-keygen    --private FILE --public FILE
canonicalize   --input FILE --output FILE
sign           --config FILE --private FILE --key-id ID --output FILE
check-config   --envelope FILE [--public FILE --key-id ID] [--app-version N] [--now ISO_INSTANT]
decrypt        --bundle FILE --private FILE --config FILE --output FILE
```

## 4. Write the study configuration

Use [`researcher-tools/examples/demo-study.json`](../researcher-tools/examples/demo-study.json)
as a runnable starting point. The root object must contain exactly these keys, no more and
no fewer:

```text
schema_version, experiment_id, configuration_id,
issued_at, expires_at, minimum_app_version,
title, researcher, purpose, duration_hours,
consent, collectors, prompts, storage, signer, export, upload
```

The decoder rejects unknown keys, missing keys, and wrong JSON types outright. There is no
lenient mode. `upload` is mandatory as a key: a study that does not upload writes `"upload": {}`.

Constraints enforced by
[`core/study-definition`](../core/study-definition/src/main/kotlin/cool/linc/androiddatacollector/core/definition/StudyConfiguration.kt):

- `schema_version` is always `1`. There is no fallback reader and no migration path: a
  configuration either matches the current schema exactly or is refused.
- IDs (`experiment_id`, `configuration_id`, prompt `id`, `signer.key_id`,
  `export.researcher_key_id`) are
  3–64 characters matching `[a-z0-9][a-z0-9-]{2,63}`: lowercase alphanumerics and `-`, with
  an alphanumeric first character.
- `issued_at` must precede `expires_at`. Verification requires the current time to be at or
  after `issued_at` and strictly before `expires_at`; the expiry instant itself is already
  expired.
- `minimum_app_version` must be positive.
- `title` 1–120 characters; `researcher.name` 1–120; `researcher.contact` 3–240;
  `purpose` 1–2,000.
- `duration_hours` is 1–8,760, measured from the participant's first explicit start.
- `consent.document_version` is 1–64 characters. `consent.summary` is 1–8,000 characters
  and must describe the data, the purpose, the duration, the risks, the access the study
  needs, export, withdrawal, deletion, what the research team retains, and how to reach you.
- At least one collector; collector IDs must be unique. An unknown collector ID is
  rejected for the whole configuration — it is never skipped because it was marked
  optional.
- Prompt IDs must be unique, `delay_minutes` is 1–525,600 from first start, and `message`
  is 1–500 characters. Prompt delivery uses WorkManager and is inexact; do not build a
  protocol that assumes a prompt lands at a precise minute. A configuration containing any
  prompt makes notification access a required access.
- `storage.maximum_local_bytes` is 8 MiB–8 GiB (8,388,608–8,589,934,592).
- `signer` carries exactly `key_id` and `public_key`. `public_key` is the base64 X.509
  Ed25519 public half of the key you sign with, 32–1,024 characters. `key_id` must equal the
  `--key-id` you pass to `sign`, and the app checks it against the envelope's signer key ID
  on import.
- `export.tink_hpke_public_keyset` must be a JSON object, 32–16,384 characters once
  serialised.
- `upload` is either the empty object `{}`, meaning the study does not upload, or an object
  carrying exactly `endpoint`, `interval_minutes`, and `allow_metered`. A partially filled
  block is rejected, so no endpoint or cadence is ever inherited from a default.
  `endpoint` is 8–2,048 characters, must begin `https://`, and must parse to a URI with a
  non-empty host. `interval_minutes` is 1–10,080: the floor is a minute so that a pilot shows
  within a minute whether delivery works at all, and the ceiling is a week. `allow_metered` is
  a boolean; `false` restricts delivery to unmetered networks.

The `signer` block looks like this:

```json
"signer": {
  "key_id": "lab-signer-2026",
  "public_key": "MCowBQYDK2Vw…the contents of study-signing-public.key"
}
```

Per-collector configuration:

| ID | Config object |
| --- | --- |
| `app_lifecycle.v1` | `{}` |
| `accelerometer.v1` | `sampling_period_us` 5,000–1,000,000; `maximum_report_latency_us` 0–60,000,000 |
| `network_state.v1` | `include_bandwidth_estimates` boolean |
| `network_usage.v1` | `transports` non-empty subset of `wifi`/`mobile`; `poll_interval_minutes` 1–1,440 |
| `usage_events.v1` | `poll_interval_minutes` 1–1,440 |
| `location.v1` | `interval_millis` 1,000–3,600,000; `minimum_interval_millis` 500 to `interval_millis`; `maximum_batch_delay_millis` 0–86,400,000; `minimum_displacement_meters` 0–10,000; `priority` `BALANCED` or `HIGH_ACCURACY`. This collector always requires precise location; `priority` trades power against accuracy within it, and there is no coarse-only mode. |
| `keyboard_touch.v1` | `trajectory_sampling_hz` 1–120 |

Both polling collectors, and scheduled delivery, accept a one-minute floor. That floor exists
for piloting: it lets you confirm within a minute that a collector produces events and that a
bundle reaches your endpoint, rather than waiting out a quarter of an hour to find out that
neither does. Treat a minute as a diagnostic setting rather than a study setting. It costs
battery, and for `network_usage.v1` it does not buy resolution — Android's own accounting is
coarse and lags, so a one-minute poll gives you finer windows without giving you finer truth.

`required: true` means the study cannot start until that access is granted. An optional
collector still appears in the data step described below, marked *Optional*, and on the
participant dashboard; when its access is missing it is shown as blocked. The app does not
substitute, interpolate, or synthesise data for a blocked collector.

### What the app tells participants each collector does

Setup is five steps, one screen each: study, data, consent, access, start. The second of
them is not yours. Before the consent text is shown, the app lists every collector the
signed configuration enables and describes each one from its own template — text compiled
into the app, in the participant's app language, that you can neither write nor edit.

Each entry is a name and a description filled in from that study's parameters, so a study
sampling location every ten seconds and one sampling it every ten minutes do not read alike.
What the screen does not carry is a negative: it states what each source records, not what it
cannot see. Where a participant needs that — and an ethics submission usually does — it has
to come from your consent document. The parameters that
reach the screen are `accelerometer.v1`'s `sampling_period_us` (as a rate in hertz, stated as
"or more" because Android treats a sampling period as a hint and devices deliver faster than
asked), the `poll_interval_minutes` of `network_usage.v1` and `usage_events.v1`, and
`location.v1`'s `interval_millis` and `minimum_displacement_meters`. The other three
collectors read the same in every study. The exact wording is in
[`app/src/main/res/values/strings.xml`](../app/src/main/res/values/strings.xml) and its
`values-zh-rTW` counterpart; read it before you write your consent summary, because your
participants will.

**This is a floor, not a substitute for your consent summary** — the same argument made for
the upload disclosure under *Scheduled upload* below. What the data step gives a participant
is which sources are on, at what rate, and what each one cannot see. What it cannot give them
is why you are collecting any of it, how long you keep it, who can reach it, what the risks
are, what happens to their data if they withdraw, and how to contact you. `consent.summary`
is where all of that lives; the constraint list above says what it has to cover, and no code
can check that it does.

The direction of the constraint is worth noting when you write that summary: because the
collector descriptions are the app's and not yours, you cannot phrase a source more mildly
than it is. A summary that understates a collector is contradicted by the screen the
participant reads immediately before it. Write the summary to agree with the data step, and
check the two against each other while piloting (section 7).

### The app's language, and yours

The app's own screens ship in English and Traditional Chinese. They follow the phone's system
language by default, and a picker in the header changes the language for this app alone; it
writes through Android's `LocaleManager`, so it is the same setting as the system's per-app
language screen rather than a second one that can disagree with it. Everything the app
authors is translated: the step names, the collector descriptions, the signature and upload
disclosures, the dashboard, and the confirmation dialogs.

**Nothing you supply is translated.** `title`, `purpose`, `researcher.name`,
`researcher.contact`, and `consent.summary` are part of the signed bytes and render exactly
as they were signed, in whatever language you wrote them, whatever language the app is in. A
configuration written in English stays English on a phone set to Chinese, and the reverse.
That is a property of signing rather than an omission: text translated on the device would be
text nobody signed, and the consent summary has to be the wording your ethics committee
approved.

The deployment consequence is real and worth planning for. **A study recruiting across
languages needs one signed configuration per language** — same collectors, same parameters,
its own consent document version, its own `configuration_id`, and its own signature — with
each participant given the one written in theirs. Keep `experiment_id` shared across them so
the arms are recognisable as one study, and remember that bundles are de-duplicated on
`experiment_id` + `configuration_id` + `collector_id` + `sequence_number` (section 10), so
the split reaches your analysis. Telling a participant to switch the app's language does not
change a single word you wrote.

### Scheduled upload

`upload: {}` gives you the participant-initiated flow: nothing leaves the phone until a
participant exports a bundle and sends it to you. A populated block adds scheduled delivery
of the same encrypted bundles to an endpoint you run. It answers two problems that manual
export does not:

- **Timeliness.** You see data during the study rather than after it, so a misconfigured
  collector, an access grant that was never completed, or a device that stopped reporting is
  visible while you can still act on it.
- **Resilience.** Data that has been delivered survives a lost, broken, wiped, or
  never-returned phone. Nothing on the device is recoverable once its Keystore key is gone,
  and a participant who stops responding takes an un-exported dataset with them.

A populated block looks like this:

```json
"upload": {
  "endpoint": "https://collect.example.edu/adc/v2/bundle",
  "interval_minutes": 360,
  "allow_metered": false
}
```

**When delivery happens.** Delivery is a chain of one-time WorkManager jobs. The first is
enqueued when the participant starts the study, with an initial delay of `interval_minutes`,
and each run enqueues its successor. It is not a `PeriodicWorkRequest`: that floor is 15
minutes, and silently clamping a shorter configured cadence would make the frequency the
consent screen states untrue. The cost of a chain is that it has no platform-side repetition to
fall back on, so it is re-established whenever the app's session initialises.

Every link is constrained to an unmetered network unless `allow_metered` is true, plus a
battery that is not low. Those constraints are why `interval_minutes` is a floor and not a
promise: a phone on mobile data all week delivers nothing until it reaches Wi-Fi. A failed
attempt retries with exponential backoff from one minute.

Delivery continues while the study is `PAUSED`, for data collected before the pause, and it
continues after the study ends: finishing, completing on the duration deadline, and withdrawing
cancel prompts and the study deadline but leave delivery running, so an undelivered tail still
reaches you. The chain stops renewing once the study is `COMPLETED` or `WITHDRAWN` and
everything it collected has been delivered. Deleting local data cancels delivery outright, so
plan for a tail you may never receive and keep manual export in your protocol as the fallback.

**How much each run sends.** There is no configured chunk size. Each run asks for everything
outstanding, and how much fits is decided while the bundle streams: it stops at the first event
boundary past a 16 MiB plaintext budget, and the receipt records where it actually stopped. The
next run resumes from there. The budget is a transport constant in `OkHttpStudyUploader`, not a
per-study setting — `interval_minutes` is what paces delivery, and the budget only binds while a
backlog is being worked off, so a study keeping up with its cadence never meets it.

The consequence for you is that chunk boundaries are not predictable from the configuration.
Read `first_sequence_number` and `last_sequence_number` out of each bundle rather than deriving
them from `interval_minutes` or an event count.

**What upload does not do.** It does not gate collection: a study whose endpoint is down,
misconfigured, or never deployed keeps recording, and a delivery failure is not treated as a
collection incident on the participant's screen.

**When delivery fails.** The participant's dashboard shows a fixed code for the last failed
attempt, derived from the transport failure and never from response content: `UPLOAD_TIMEOUT`,
`UPLOAD_HOST_UNRESOLVED`, `UPLOAD_CONNECT_REFUSED`, `UPLOAD_TLS_HANDSHAKE_FAILED`,
`UPLOAD_TLS_FAILED`, `UPLOAD_INTERRUPTED`, `UPLOAD_IO_FAILED`, `UPLOAD_HTTP_<status>` for a
non-2xx response, or `UPLOAD_FAILED` for anything else. That code is what to ask a participant
to read out when your endpoint has seen nothing from them, because it separates a name
resolution or TLS problem on your side from a phone that never had a network. It does not
overwrite the incident code a storage or access problem sets, and collection carries on either
way.

**What confirmed delivery does to local storage.** A study that comfortably fits its quota
keeps every event on the phone. Once storage passes 80% of `storage.maximum_local_bytes`, a
confirmed upload lets the device release whole leading segments of already-delivered events,
down to 60%, and it stops there. Events your endpoint has not confirmed are never released:
if nothing qualifies, the quota fills and the study fail-closes to `PAUSED` exactly as it
would without an endpoint. Size the quota for the study you are running, not on the
assumption that delivery keeps it clear — a phone that spends a month off Wi-Fi delivers
nothing and reclaims nothing.

The research consequence is in section 10: once a participant's device has reclaimed a
prefix, their manual export covers a window rather than the whole study, so an uploading
study's dataset is the reassembled chunks plus that final export.

What the endpoint receives is the same `ADCEXP01` bundle described in section 9 — ciphertext
wrapped to your HPKE public key — as an `application/octet-stream` POST body with a chunked
transfer encoding, because the bundle is generated as it is written and its length is not
known up front. Everything needed to file and de-duplicate a chunk travels in request
headers, since reading it out of the body would require the private key:

| Header | Value |
| --- | --- |
| `X-ADC-Bundle-Format` | `research-bundle-v1` |
| `X-ADC-Experiment-Id` | `experiment_id` from the configuration |
| `X-ADC-Configuration-Id` | `configuration_id` from the configuration |
| `X-ADC-Participant-Instance` | The participant instance ID |
| `X-ADC-Sequence-From` | The first sequence in this chunk. Exact, and strictly increasing across a participant's chunks |
| `X-ADC-Sequence-To-At-Most` | The last sequence durable on the device when the request began. An upper bound, not the window |

The two headers are not symmetric, and the asymmetry is in the name for a reason. Headers are
sent before the body is generated, so the device knows where a chunk starts but not yet where it
ends: the request budget can stop it at any earlier event boundary. The range a chunk actually
contains is the `first_sequence_number` and `last_sequence_number` inside it, which your endpoint
cannot read — that is ciphertext. **File and de-duplicate on `X-ADC-Sequence-From` together with
`X-ADC-Participant-Instance`**; the next chunk resumes exactly where this one stopped, so that
pair is unique per chunk. An endpoint that records `X-ADC-Sequence-To-At-Most` as a held range
will claim sequences it does not have, and nothing later will correct it.

Your endpoint must answer 2xx only once it has durably stored the body. The device advances its
watermark to wherever the bundle actually stopped, never sends those sequences again, and may
release them locally if the study's storage runs high — so a 2xx you have not earned can cost
data that exists nowhere else. Answer 408, 429, or 5xx to ask for a retry; any other 4xx is
treated as a request that will keep failing and is not worth the participant's battery.

**The participant instance ID.** A random UUID generated on the device when the study is
imported, stored in that study's metadata, and included in every bundle and every upload
request. Without it, bundles from different participants arrive indistinguishable — a manual
export carries that information out of band, an upload does not. It is pseudonymous: it
contains no name, account, device identifier, or advertising ID, and it is not shared across
studies. Treat it as personal data anyway, because it links every chunk one person produced.

**You must disclose upload in your consent text.** The app renders the endpoint host, the
cadence, the network condition, the fact that only your key can open the payload, and the
existence of the instance ID into the consent step, directly below your summary and taken
from the signed configuration rather than from it. That is a floor, not a substitute — the
same relationship the data step has to your summary: your consent document has
to say who operates the endpoint, where it is hosted, what jurisdiction it sits in, how long
chunks are retained there, and who can reach them. A participant cannot decline upload while
accepting the study, so the decision to participate is the decision to be uploaded — write
the consent text accordingly.

The endpoint is study infrastructure and belongs in your data governance plan. Keep the HPKE
private key off it; a collection server that can decrypt is a collection server that leaks
plaintext when it is compromised. See [`threat-model.md`](threat-model.md) for what an
endpoint learns even without that key.

## 5. Canonicalise, sign, and verify

Canonicalise first:

```bash
./gradlew :researcher-tools:run --args="canonicalize \
  --input ./study-draft.json \
  --output ./study-canonical.json"
```

Canonicalisation re-emits the object with a fixed key order and normalised timestamp and
number formatting. The signing step decodes the file again and refuses it if the bytes are
not already canonical, so you cannot accidentally sign a hand-edited draft.

Sign the canonical bytes:

```bash
./gradlew :researcher-tools:run --args="sign \
  --config ./study-canonical.json \
  --private /secure/study-signing-private.key \
  --key-id lab-signer-2026 \
  --output ./study.adccfg"
```

`--key-id` must equal the configuration's `signer.key_id`, and the private key you pass must
be the one whose public half the configuration declares. Both are checked before anything is
written, because a mismatch would produce a file that signs cleanly and then fails on every
device: the second failure reads `signer.public_key in the configuration does not match
--private`.

The result is a signed study configuration: an `ADCCFG01` envelope carrying the signer key
ID, the canonical configuration bytes, and the Ed25519 signature over exactly those bytes.
On success the command prints the IDs it signed and the fingerprint of the signing key, for
example:

```text
signed my-study-2026 my-study-config-01
fingerprint 9D0D AE5A 0D20 B29F D642 942A 0E17 4AAE
```

That fingerprint is SHA-256 over the encoded public key, truncated to 16 bytes and rendered
as eight groups of four hex characters. It is what the consent screen shows the participant,
and what you publish in your recruitment material — section 6.

Verify independently — envelope structure, signature, app version floor, and the validity
window — before anything reaches a participant:

```bash
./gradlew :researcher-tools:run --args="check-config \
  --envelope ./study.adccfg \
  --app-version 1"
```

`--public` and `--key-id` are optional. Without them the check verifies the configuration
against the key it carries, exactly as a build that pins no signer does, and reports:

```text
valid my-study-2026 my-study-config-01
signer lab-signer-2026 9D0D AE5A 0D20 B29F D642 942A 0E17 4AAE
pinned no (self-certifying)
```

Supply both and the check pins the signer instead, reproducing what a build listing that key
would enforce; the last line then reads `pinned yes`. A configuration that names the pinned
key ID while carrying a different public key is rejected, so pinning cannot be sidestepped.

`--app-version` is the participant app's `versionCode`; if omitted the check treats the app
version floor as satisfied. `--now` takes an ISO instant and lets you confirm that a
configuration is refused before `issued_at` and after `expires_at` without changing the
system clock.

Any change to the configuration bytes invalidates the signature. When consent text,
collector optionality or frequency, prompts, quota, or the export key changes, mint a new
`configuration_id`, re-sign, and obtain consent again. Never edit a `.adccfg` that has
already been distributed.

## 6. Build and distribute

```bash
./gradlew test testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Debug APKs are for internal testing only. For real deployment use the tag-triggered GitHub
Actions release workflow; the required secrets and setup are described in the repository
[`README.md`](../README.md). The workflow publishes only APKs that have passed
`apksigner verify`, and the keystore is never committed. Sideloading uses the signed APK;
Google Play distribution uses the corresponding AAB and track process. Building the app is
not part of issuing a study: the same build verifies any correctly signed `.adccfg`.

Distribution of the configuration is manual. There is no download endpoint: participants
import the `.adccfg` through the system file picker. Getting data back is manual too unless
the study declares an upload endpoint, in which case delivery is automatic and manual export
remains available alongside it. Plan the logistics of both directions into your protocol.

The app declares `android.permission.INTERNET` and sets `usesCleartextTraffic="false"`, so
the permission list of a build no longer tells you whether a given study transmits — the
signed configuration does. Check the artifact's permissions for what the collectors need:

```bash
aapt dump permissions app-release.apk
```

Check the study's `upload` block for whether, where, and how often it transmits:

```bash
jq .upload ./study-canonical.json
```

`{}` means the study never transmits. Run the second check against the exact configuration
you are about to sign, and against what your consent document tells participants.

### Publish your fingerprint

The consent step shows the key fingerprint under the heading *Configuration signature*
(設定檔簽章 when the app is in Traditional Chinese), in a block the app asserts itself below
your consent summary. The signer key ID is not on that screen; the fingerprint is what a
participant compares. When the build pins no signer — the shipped default — the block asks the participant to check
the fingerprint against the one their research team published, and notes underneath, quietly,
that a signature shows a file is unaltered rather than who wrote it. None of it is in the error
colour: an unpinned signer is the deployment model rather than a fault, and a screen that cries
wolf on the ordinary case teaches participants to skip the one line you need them to act on.

That last instruction is only actionable if you have published it. Put the fingerprint
`sign` printed into the material that recruits participants — the study information sheet,
the consent document, the lab page participants were sent to — through the same channel that
reached them, and keep it identical for every configuration signed with that key. A
participant comparing eight groups of four hex characters is the check that a researcher
name and contact in the configuration cannot provide, because those are free text the signer
chose.

The recruitment relationship carries weight here: a participant receives a configuration
from a team they have already been in contact with, not as an anonymous download. The
fingerprint is what turns that relationship into something checkable on the device.

### Pinning, for institutions

An organisation that wants one build to run only its own studies adds its key ID and public
key to `TRUSTED_SIGNING_KEYS` in the `CollectorApplication` composition root
([`app/src/main/kotlin/cool/linc/androiddatacollector/CollectorApplication.kt`](../app/src/main/kotlin/cool/linc/androiddatacollector/CollectorApplication.kt))
and ships that build. The map is empty in the shipped build; populating it is strictly
exclusive, so that build refuses every signer not listed, including studies from other
teams. The pinned key also overrides whatever the configuration declares, so a configuration
cannot claim a pinned key ID while carrying a different key. The consent step then reads
"This app trusts this signer." instead of the unverified-publisher warning, and the mark
beside the heading becomes a check.

This is a build-time decision with no revocation path: retiring a pinned key means shipping
a new APK. Reproduce it before release with `check-config --public … --key-id …`, which must
print `pinned yes` for the `.adccfg` you intend to distribute.

## 7. Pilot before recruiting

Emulator success is evidence about logic, not about background reliability on a real
phone and not about scientific validity. Before recruitment, walk through the following on
every Android version your study actually supports (14, 15, 16, 17) and on representative
OEM hardware:

- Install; signed configuration import; rejection of expired, wrong-key, and tampered
  files.
- The *Configuration signature* block on the consent step: that the fingerprint on screen is
  the one you published, and that a participant reading your recruitment material can find it
  without help.
- The data step: that every collector listed and its stated rate agree with your consent
  document, and that nothing your summary implies is absent from it.
- Both app languages your participants might use, including that your own text — title,
  purpose, contact, consent summary — reads correctly beside translated app text, and that a
  participant whose phone is set to the other language still receives a configuration written
  in theirs.
- Consent; every required and optional access; behaviour after a denial; revoking access
  mid-study.
- Start, pause, resume, finish, and withdraw for every configured collector.
- Two exports and two successful decryptions from each of `RUNNING`, `PAUSED`,
  `COMPLETED`, and `WITHDRAWN`.
- If the study uploads: the consent step's upload block against your consent document, a
  first successful delivery, decryption of a stored chunk, an endpoint that returns 5xx and
  then recovers, an endpoint that returns 400, a phone kept off Wi-Fi for the interval, an
  unreachable host so you can see the failure code a participant would report, a backlog large
  enough that one run does not clear it and the next resumes where it stopped, and what your
  endpoint holds after the participant withdraws.
- Fail-closed behaviour with the wrong private key, the wrong configuration, and truncated
  or modified ciphertext.
- Reboot, force stop, low storage, wall-clock changes, Doze, long uptime, and the OEM's
  foreground-service restrictions.
- Real accuracy, batching, and battery cost of your location and accelerometer parameters
  on the target hardware.
- Usage Events and NetworkStats latency, gaps, multi-window, VPN, Wi-Fi/mobile handover,
  and zero-traffic windows.
- Research keyboard: the sensitive-field cut-off, editors in different apps, switching
  keyboards, and the risk of a participant leaving it enabled by mistake.
- Peak event rate against the quota you chose, export time and memory, and whether the participant
  screens are comprehensible to someone outside your team.

## 8. Participant flow and support boundaries

The standard flow is import and verification, then five setup steps with one panel each and
a row of dots showing how far along they are:

1. **Study** — your title, purpose, researcher name, contact, and the duration.
2. **Data** — every enabled collector, described by the app from your parameters.
3. **Consent** — your `consent.summary`, then the signature and upload blocks the app
   asserts itself, then the agreement checkbox.
4. **Access** — the Android access the configured collectors need, one row each; tapping an
   outstanding row opens the screen that grants it, except the motion-sensor check, which is
   hardware and nothing to grant. Optional ones are labelled, and only the required ones
   block the next step.
5. **Start** — importing collects nothing; this press is what starts collection.

Re-entering the consent state returns to the data step, so nobody reaches the checkbox
without the list of sources having been on screen. Afterwards participants can pause, resume,
finish early, withdraw, export repeatedly, and delete local data; the irreversible ones ask
for confirmation. See [`participant-guide.md`](participant-guide.md) for what they are told.

Researchers must not:

- ask a participant to skip a disclosure screen, or describe optional access as required;
- tell a participant to ignore the unverified-publisher warning instead of giving them the
  fingerprint to compare it against;
- ask for passwords, PINs, one-time codes, private messages, full screen recordings, or a
  decrypted export;
- imply that pausing or withdrawing deletes copies the research team already holds;
- imply that a completed export means the file has reached the research team; or
- ask a participant to leave the research keyboard enabled as their default when the study
  does not require it.

The app sends no telemetry, no analytics, and no crash reports. In a study with an empty
`upload` block you therefore learn nothing about a participant's progress unless they tell
you, and completeness can only be assessed once they choose to share an encrypted export. In
an uploading study your endpoint sees delivery activity per participant instance, which is
the closest thing to monitoring available — and it is arrival of ciphertext, not a health
check. A silent instance may have paused, withdrawn, run out of Wi-Fi, or lost the phone,
and the four look alike from the endpoint.

## 9. Receive and decrypt bundles

A participant's `.adcexp` is ciphertext when it reaches you, and so is every chunk your
endpoint stores. Keep the original bytes, limit who can read them, and log receipt under your
data governance procedure. Filenames and receipt timestamps are metadata a participant
controls; they are not evidence of identity or integrity. An uploaded chunk carries no more
proof of origin than an emailed export does — see [`threat-model.md`](threat-model.md).

Decrypt with the matching canonical configuration and the HPKE private keyset:

```bash
./gradlew :researcher-tools:run --args="decrypt \
  --bundle ./participant-export.adcexp \
  --private /secure/export-hpke-private.json \
  --config ./study-canonical.json \
  --output /controlled/participant-export.json"
```

The same command decrypts an uploaded chunk; point `--bundle` at the stored request body.

`decrypt` streams, so a bundle larger than your machine's memory still decrypts. Size your
controlled environment for that. A bundle has no ceiling of its own: it is bounded by the study's
`storage.maximum_local_bytes`, so a manual export at the end of a long, high-rate study scales
with the quota you asked for, which can be 8 GiB.

The command refuses to overwrite an existing `--output` path, and writes its plaintext to a
temporary file in the destination directory first, moving it into place only after the whole
bundle has decrypted. That staging is not tidiness. The AES-GCM tag is verified only once the
last byte has been read, so a truncated or tampered bundle produces plausible-looking plaintext
right up to the point where it fails; staging means a failed verification leaves nothing behind
that could be mistaken for a partial dataset.

The bundle is an `ADCEXP01` container: a per-bundle AES-256-GCM content key wrapped to your
HPKE public keyset, over a plaintext JSON document with this shape:

```text
format                          "research-bundle-v1"
exported_at_utc_millis
configuration                   the canonical study configuration
experiment:
  experiment_id, configuration_id, participant_instance_id,
  state, next_sequence_number,
  transitions[]:
    from, to, reason,
    time: { wall_time_utc_millis, elapsed_realtime_nanos, boot_session_id }
  events[]:
    sequence_number, collector_id, payload_schema_version,
    observed_time: { wall_time_utc_millis, elapsed_realtime_nanos, boot_session_id },
    payload_type, fields
  first_sequence_number, last_sequence_number
```

`first_sequence_number` and `last_sequence_number` are the inclusive window this bundle covers,
so a chunk is never mistaken for a whole study. An uploaded chunk starts after the last sequence
the endpoint confirmed. A manual export starts at 1, or at the lowest sequence still on the
phone if the device has reclaimed a delivered prefix. `participant_instance_id` is the
pseudonymous per-install identifier described in section 4.

The two window fields are written after `events`, not before it, because a budget decides where
an uploaded bundle stops while it is still streaming. Declaring the window up front would let a
bundle claim a range it does not contain, which is worse than not declaring one. JSON object
member order carries no meaning, so this changes nothing for a parser that reads by key, and it
changes nothing about decryption — the format string is unchanged and bundles produced by
earlier builds decrypt exactly as before. It does matter to code that streams a bundle and
expects the window before the events it describes.

Every value inside `fields` is a JSON string, including numeric ones. Parsing and range
checking are your responsibility.

`research-bundle-v1` is bound into the HPKE and AES-GCM associated data, so a reader built
for a different version fails to decrypt rather than misreading one. Use
the `researcher-tools` build that matches the app you distributed.

Successful decryption proves that the HPKE context and the AES-GCM tag verified: the bundle
was encrypted to your key, for this `experiment_id`/`configuration_id`/`researcher_key_id`
triple, and has not been altered since. It proves nothing about the participant's legal
identity, nothing about device attestation, and nothing about whether the platform dropped
data before it was recorded.

Do not partially analyse a file that fails to decrypt. Quarantine it, and where
appropriate ask the participant to export a fresh encrypted bundle.

## 10. Repeated bundles and de-duplication

An export is a snapshot, not a state change:

- Exporting while the study is running does not pause any collector, and neither does an
  upload.
- Each bundle uses a fresh random AES key and nonce, so two exports of the same snapshot
  never produce identical ciphertext. Byte comparison tells you nothing.
- A later export contains the earlier events plus newer ones. Overlap between bundles from
  the same participant is expected, not an error. Uploaded chunks are the exception: each one
  starts after the sequence the previous delivery confirmed, so consecutive chunks abut
  rather than overlap. Where they abut is decided by the plaintext budget while each bundle
  streams, so chunk sizes vary and are not derivable from the configuration — take the window
  from `first_sequence_number` and `last_sequence_number`.
- In a study that does not upload, every export is a whole history and the last one is the
  dataset. In an uploading study it need not be: once a device has reclaimed a delivered
  prefix, a manual export starts at the lowest sequence still on the phone. The dataset is
  then the reassembled chunks plus the final export, and `first_sequence_number` on each
  bundle tells you where it starts. Keep the chunks; do not treat a late manual export as a
  replacement for them.
- De-duplicate on `experiment_id` + `configuration_id` + `collector_id` +
  `sequence_number`. Sequence numbers come from a single monotonic counter per study, so
  they are stable across exports and uploads alike, and reclaiming never reissues one. In an
  uploading study, `participant_instance_id` is what separates one participant's counter from
  another's.
- A gap in the delivered sequence range is not proof of data loss. A chunk may not have been
  delivered yet, or may have been cut short when the study ended, and events below a
  participant's retained floor were released only because your endpoint confirmed them — look
  for them in the chunks you already hold. Ask for a manual export before treating a gap as
  missing data.
- Reconstruct running and paused windows from `transitions` together with
  `observed_time.elapsed_realtime_nanos` and `observed_time.boot_session_id`. Do not infer
  them from export times.

## 11. Analysis notes

### Acceleration, posture, and movement

Raw x/y/z includes gravity and is expressed in device coordinates, whose orientation
relative to the participant is unknown. Sort by the sensor timestamp, split by boot
session, and inspect sampling gaps before filtering, feature extraction, or modelling.
Posture requires estimating the gravity direction. Movement classification requires
independent labels and independent validation. The app supplies no ground truth.

### Network state and usage

A state event marks a change in Android's default network or its capabilities. A VPN or
multiple transports may be active at once, and the bandwidth figures are platform
estimates, not measurements. Usage aggregates are coarse device totals over
`[coverage_start_utc_millis, coverage_end_utc_millis]`; they can arrive late, overlap, or
be zero. Spreading bytes evenly across the window and then reporting when traffic occurred
invents precision that is not in the data.

### Usage Events

Keep the raw events and reconstruct sessions in analysis code, where the assumptions are
visible and revisable. You will need to handle several Activities in one package,
multi-window and picture-in-picture, unpaired resume/pause, shutdown, missing events, and
wall-clock changes. `SCREEN_INTERACTIVE` means the screen was interactive; it does not mean
the participant was looking at it.

### Location

Use each fix's own source time and accuracy fields rather than the record's admission time;
batched delivery routinely makes admission later than the fix. The `mock` flag is platform
information, not proof of deception on its own.

### Keyboard touch

`pressure` and `size` are device-dependent relative values with no physical unit and no
cross-device calibration. There is no key identity and no text in the payload, and outputs
must not be presented as though the typed content were recoverable. Relative position, key
category, and inter-touch timing can still leak typing patterns and support
re-identification, so aggregate before release and treat per-touch traces as identifiable
data.

## 12. Completion, withdrawal, and deletion

`COMPLETED` means collection has stopped and the data can still be exported. `WITHDRAWN`
means the participant has permanently ended participation; they may still export first, or
delete local data directly. Both cancel reminders and the study deadline but leave scheduled
delivery running, so a study that ends with an undelivered backlog still sends it; the job
retires itself once the backlog is gone. Deleting local data cancels delivery outright — a
participant who deletes before the backlog clears keeps whatever had not yet been sent off
your endpoint entirely. Local deletion is only
offered from these terminal states, and it removes the encrypted store on the device. It
does not remove `.adcexp` files a participant saved elsewhere, and it does not remove chunks
your endpoint already holds.

Your consent document must state, and your procedures must actually deliver: how to contact
you to withdraw, what deletion on the research side covers — including chunks already at
your endpoint — which aggregated or published results cannot be recalled, the retention
period, when keys are destroyed, and how a breach is handled. App behaviour does not
substitute for any of this.

## Related documents

- [`participant-guide.md`](participant-guide.md) — what participants are told.
- [`data-dictionary.md`](data-dictionary.md) — field-level reference for every collector.
- [`threat-model.md`](threat-model.md) — trust assumptions, current protections, and their limitations. Written to be attached to an ethics submission.
- [`system-design.md`](system-design.md) — architecture and data flow.
- [`component-boundaries.md`](component-boundaries.md) — module responsibilities.
