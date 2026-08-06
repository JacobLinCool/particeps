# Researcher guide

Particeps runs a study from a signed configuration file, so a new study does
not need a new app. You describe the study in JSON — which collectors run and with what
sampling parameters, how long it lasts, what the consent summary says, which interventions and surveys are
scheduled, how much local storage it may use, which public key its bundles are encrypted to,
and whether it delivers them to an endpoint on a schedule — sign that file with your study
key, and hand it to participants. The participant app verifies the signature, presents the
study, and runs exactly what the configuration specifies.

v1 ships twelve selectable collectors — app lifecycle, accelerometer, battery state, temporal
context, gyroscope, ambient light, proximity, network state, network usage, usage events,
location, and research-keyboard touch dynamics — and runs the complete
on-device loop on Android 14–17 (`minSdk 34`, `compileSdk`/`targetSdk 37`). Changing which
of them a study uses, how often they sample, or how long the study lasts is a configuration
change. Adding a collector that does not exist yet is a code change; see
[`data-collector-implementation-guide.md`](data-collector-implementation-guide.md).

Deploying a study is a short pipeline, and this guide follows it:

1. Generate the study signing key and the export encryption key (section 3).
2. Write the study configuration (section 4).
3. Canonicalise, sign, and verify it (section 5).
4. Distribute the participant app and the `.partcfg` file, and publish your signing key
   fingerprint in the material that recruits participants (section 6).
5. Pilot on the Android versions and hardware your study targets (section 7).
6. Receive encrypted bundles — exported by participants, uploaded by their devices, or
   both — and decrypt them (section 9).

Sections 1 and 2 come first because they shape the design: what the data can and cannot
support, and how the two key pairs must be handled.

For implementation work, start with the [normative Protocol v1 contract](../protocol/v1/README.md),
its [collector catalog](../protocol/v1/collector-catalog.json), the
[system design](system-design.md), and the
[Collector capability policy](../assurance/README.md). Configuration, envelope, export, outbox, and
HTTP behavior live beside their tests in `core/study-definition`, `core/protocol`, `core/export`,
and `app/src/{main,test}/…/platform`; those links are indexed in the repository README.

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
| `app_lifecycle.v1` | Lifecycle transitions of this app's own Activities, each with the Activity class name | Anything about time spent in other apps. This instruments Particeps itself, not the participant's phone use. |
| `accelerometer.v1` | Raw x/y/z acceleration in m/s² in device coordinates, the sensor's own timestamp, and the platform accuracy code | A recognised movement, posture, or activity. The app ships no classifier and produces no ground-truth label. |
| `battery_state.v1` | Whole battery percentage, charging state/source, and power-save state | Battery health, temperature, capacity, hardware identity, or the cause of a change. |
| `temporal_context.v1` | Time-zone ID, UTC offset, DST state, and a bounded reason for a time-context snapshot | Physical location or travel. A configured time zone is not location evidence. |
| `gyroscope.v1` | Raw x/y/z angular velocity in rad/s, sensor time, and accuracy | Orientation, posture, activity, or gesture labels. No inference is performed. |
| `ambient_light.v1` | Raw illuminance in lux, sensor time, and accuracy | Image content, a calibrated environment across devices, or whether a person is present. |
| `proximity.v1` | Raw sensor distance/range and the device's near/far interpretation | Comparable physical distance across devices or presence; many sensors are binary. |
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
| Raw X25519/HPKE key | Decrypts every bundle, exported or uploaded | The signed study configuration, as `export.hpke_public_key` |

Both public halves therefore travel inside the configuration, and neither requires an app
build. A configuration certifies itself: the app verifies the signature with the key the
file carries. What that buys you is one published app running any study; what it costs is
that a valid signature proves the configuration is unchanged since signing, not who wrote
it. Section 6 covers the fingerprint you publish so participants can close that gap.

The consequences differ, so track them separately.

- **Signing private key lost.** You cannot issue or reissue configurations under that key
  ID. Existing `.partcfg` files already in participants' hands keep working until they
  expire. That is true of current-format files only. An `.adccfg` from before the rename to
  Particeps does not keep working: Particeps rejects it outright, as does every current tool,
  so a configuration signed under the old identity has to be re-signed with the current
  tooling regardless of how much of its validity window is left. Recovery means generating a
  new key, putting it in the `signer` block of a new configuration, and re-signing — no app
  release is involved.
- **Signing private key leaked.** Anyone holding it can mint a configuration that verifies
  as yours, including one that enables more collectors, and it will carry your published
  fingerprint. Treat this as an incident: stop distributing the affected `.partcfg`, publish
  a new key and fingerprint, re-sign under the new key ID, and notify participants. There is
  no revocation mechanism, so configurations already signed under the old key remain valid
  until they expire — which is a reason to keep validity windows short.
- **HPKE private key lost.** Every export encrypted to that key is permanently
  unreadable. There is no escrow and no recovery path. Participant devices cannot re-encrypt.
- **HPKE private key leaked.** Anyone holding it can decrypt any export bundle for that
  study that they can obtain. Rotating the key requires a new `configuration_id`, a new
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

Generate the export HPKE key:

```bash
./gradlew :researcher-tools:run --args="hpke-keygen \
  --private /secure/export-hpke-private.key \
  --public ./export-hpke-public.key"
```

Each `.key` file contains one raw 32-byte key encoded as unpadded base64url. Paste the
Ed25519 public value into `signer.public_key` and the X25519 public value into
`export.hpke_public_key`. Tink JSON/protobuf keysets, X.509, PKCS#8, padded base64, and
standard-base64 keys are not Protocol v1 wire values. Both private keys stay in the controlled
research environment; neither public key goes into an app build.

An institution that wants one build to accept only its own studies can additionally pin the
signer — see the end of section 6.

The full command surface is:

```text
signing-keygen --private FILE --public FILE
hpke-keygen    --private FILE --public FILE
canonicalize   --input FILE --output FILE [--assigned-participant-id ID]
sign           --config FILE --private FILE --key-id ID --output FILE [--assigned-participant-id ID]
personalize    --config FILE --mapping TSV --private FILE --key-id ID --output-dir DIRECTORY
check-config   --envelope FILE [--public FILE --key-id ID] [--app-version N] [--now ISO_INSTANT]
decrypt        --bundle FILE --private FILE --config FILE --output FILE
```

## 4. Write the study configuration

Use [`researcher-tools/examples/demo-study.json`](../researcher-tools/examples/demo-study.json)
as a runnable starting point. The root object must contain exactly these keys, no more and
no fewer:

```text
schema_version, experiment_id, configuration_id, assigned_participant_id,
issued_at, expires_at, platform, minimum_client_version,
title, researcher, purpose, duration_hours,
consent, collectors, surveys, interventions, storage, signer, export, upload
```

The decoder rejects unknown keys, missing keys, and wrong JSON types outright. There is no
lenient mode. `upload` is mandatory as a key: a study that does not upload writes `"upload": {}`.

Constraints enforced by
[`core/study-definition`](../core/study-definition/src/main/kotlin/cool/jacoblin/particeps/core/definition/StudyConfiguration.kt):

- `schema_version` is always `1`. There is no fallback reader and no migration path: a
  configuration either matches the current schema exactly or is refused.
- Stable IDs (`experiment_id`, `configuration_id`, survey/question/option/intervention/trigger IDs, `signer.key_id`,
  `export.researcher_key_id`) are
  3–64 characters matching `[a-z0-9][a-z0-9-]{2,63}`: lowercase alphanumerics and `-`, with
  an alphanumeric first character.
- `issued_at` must precede `expires_at`. Verification requires the current time to be at or
  after `issued_at` and strictly before `expires_at`; the expiry instant itself is already
  expired.
- `platform` is exactly `"android"`. `minimum_client_version` is a positive canonical decimal
  string (`"1"`, never a JSON number or a zero-padded string).
- `title` 1–120 characters; `researcher.name` 1–120; `researcher.contact` 3–240;
  `purpose` 1–2,000.
- `duration_hours` is 1–8,760, measured from the participant's first explicit start.
- `consent.document_version` is 1–64 characters. `consent.summary` is 1–8,000 characters
  and must describe the data, the purpose, the duration, the risks, the access the study
  needs, export, withdrawal, deletion, what the research team retains, and how to reach you.
- At least one collector; collector IDs must be unique. An unknown collector ID is
  rejected for the whole configuration — it is never skipped because it was marked
  optional.
- `assigned_participant_id` is either `null` (anonymous/pseudonymous distribution) or an opaque
  1–64 byte code matching `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`. Do not put names, email addresses,
  phone numbers, or other direct identifiers in it.
- Survey, question, option, intervention, and trigger IDs are unique in their respective scope.
  A survey has 1–100 questions and localized text has a required default plus at most 32 BCP 47
  overrides. Question types are `short_text`, `scale`, `single_choice`, and `multiple_choice`.
- An intervention owns one notification or survey action and one or more reusable triggers.
  One-time and interval triggers explicitly choose `CALENDAR_TIME` or `ACTIVE_RUNNING_TIME`;
  daily triggers use `HH:mm` in the device's current time zone. Offsets must fall inside the study,
  availability is 1–525,600 minutes, and the signed study is capped at 512 total occurrences so
  durable idempotency metadata remains inside its encrypted 1 MiB bound.
  WorkManager timing is best effort, not an exact alarm. Any intervention requires notification access.
- `storage.maximum_local_bytes` is 8 MiB–8 GiB (8,388,608–8,589,934,592).
- `signer` carries exactly `key_id` and `public_key`. `public_key` is the raw 32-byte Ed25519
  public half encoded as unpadded base64url. `key_id` must equal the
  `--key-id` you pass to `sign`, and the app checks it against the envelope's signer key ID
  on import.
- `export.hpke_public_key` is a raw 32-byte X25519 public key encoded as unpadded base64url.
- `upload` is either the empty object `{}`, meaning the study does not upload, or an object
  carrying exactly `endpoint`, `interval_minutes`, and `allow_metered`. A partially filled
  block is rejected, so no endpoint or cadence is ever inherited from a default.
  `endpoint` is 8–2,048 characters, must begin `https://`, and must parse to a URI with a
  non-empty host. `interval_minutes` is 1–10,080: the floor is a minute so that a pilot shows
  within a minute whether delivery works at all, and the ceiling is a week. `allow_metered` is
  a boolean; `false` restricts delivery to unmetered networks.

### Identity modes and personalized batches

With `"assigned_participant_id": null`, one signed artifact may be distributed to everyone. Each
import independently mints a random `participant_instance_id`, including repeated imports of the
same file. With a non-null assigned code, make a distinct artifact and `configuration_id` for each
participant. The assigned code remains inside encrypted metadata and exports; only the random
instance UUID distinguishes events after decryption. Neither value appears in upload routing
headers.

For a batch, supply a UTF-8 tab-separated mapping with exactly
`configuration_id<TAB>assigned_participant_id` per line, then run:

```bash
researcher-tools personalize --config template.json --mapping participants.tsv \
  --private /secure/signing.key --key-id lab-signer-2026 --output-dir issued
```

The command validates every row and rejects duplicate configuration IDs before creating the output
directory. It writes `<configuration_id>.json` and `<configuration_id>.partcfg`; assigned codes are
never placed in filenames or printed. `canonicalize` and `sign` also accept
`--assigned-participant-id` for issuing one artifact.

### Interventions and surveys

An action is defined once and reused by all of an intervention's triggers. Calendar-relative
schedules include pauses; active-running schedules exclude them. Daily local schedules follow the
phone's current time zone and are recomputed after time or zone changes. For both daily-local and
random-window schedules, a local minute that does not exist during a DST gap is skipped instead of
shifted outside the signed time; if a minute occurs twice during a DST overlap, the first
chronological occurrence is used. Each planned firing has a
SHA-256 `occurrence_id` derived from its configuration, intervention, trigger, and schedule key, so
reboot, process recovery, WorkManager retry, or duplicate execution cannot create a second firing.
Occurrences stop at the study lifetime and expire after their availability window.
While a study is paused, the app removes pending prompt work and visible intervention
notifications, and it rejects prompt claims, opens, expiries, and survey submissions. Calendar
time and signed availability windows still advance; on resume the app reconciles the durable
occurrences, expires anything whose window elapsed, and schedules only what remains eligible.

A `random_window` trigger fixes one to eight sorted, non-overlapping local-time windows plus
`occurrences_per_window`, daily and total caps, and `minimum_separation_minutes` in the signed
configuration. The phone uses a CSPRNG to choose each instant and persists the occurrence before
WorkManager is scheduled. Process death, retry, and reboot therefore reuse the same choice.
If a daily or total cap is smaller than the signed slots, the planner considers local dates in
planning order, then windows in their signed array order, then occurrence ordinals; the first
eligible slots consume capacity. A past or DST-nonexistent slot consumes nothing. The CSPRNG
chooses the minute inside a selected slot, not which window survives the cap.
Already materialized occurrences are never moved after a clock or time-zone change; only future
local dates are planned under the new context. Because repeated wall-clock edits can expose more
local dates than study duration alone implies, each random trigger contributes its full signed
`maximum_occurrences_total` to the global 512-occurrence safety bound. The Web editor shows that
true worst-case prompt count and the window bounds, never a participant's selected instants. Daily
local schedules still use the conservative UTC-18 through UTC+18 reachable-date bound. There is no
server trigger.

A survey action references a reusable survey by ID. Display text uses `{ "default": "...",
"translations": { "zh-TW": "..." } }`; stable IDs, never labels, appear in answers. Submissions
are validated and appended atomically once to the encrypted event stream. Draft typing, abandoned
answers, and validation failures are not research events. A submitted response is review-only.

The `signer` block looks like this:

```json
"signer": {
  "key_id": "lab-signer-2026",
  "public_key": "<unpadded-base64url raw Ed25519 public key>"
}
```

Per-collector configuration:

| ID | Config object |
| --- | --- |
| `app_lifecycle.v1` | `{}` |
| `accelerometer.v1` | `sampling_period_us` 5,000–1,000,000; `maximum_report_latency_us` 0–60,000,000 |
| `battery_state.v1` | `{}` |
| `temporal_context.v1` | `{}` |
| `gyroscope.v1` | `sampling_period_us` 5,000–1,000,000; `maximum_report_latency_us` 0–60,000,000 |
| `ambient_light.v1` | `sampling_period_us` 200,000–10,000,000; `change_threshold_millilux` 0–100,000,000 |
| `proximity.v1` | `minimum_event_interval_ms` 100–60,000; `change_threshold_millimeters` 0–10,000 |
| `network_state.v1` | `include_bandwidth_estimates` boolean |
| `network_usage.v1` | `transports` non-empty subset of `wifi`/`mobile`; `poll_interval_minutes` 1–1,440 |
| `usage_events.v1` | `poll_interval_minutes` 1–1,440 |
| `location.v1` | `interval_millis` 1,000–3,600,000; `minimum_interval_millis` 500 to `interval_millis`; `maximum_batch_delay_millis` 0–86,400,000; `minimum_displacement_millimeters` 0–10,000,000; `priority` `BALANCED` or `HIGH_ACCURACY`. This collector always requires precise location; `priority` trades power against accuracy within it, and there is no coarse-only mode. |
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
The description states what each source records and selected limits the implementation can
guarantee, such as omitted battery identity, text, inference, or presence claims. It is not an
exhaustive threat model, so study-specific risks and every additional participant commitment still
belong in your consent document. The parameters that
reach the screen include the accelerometer and gyroscope `sampling_period_us` (as a rate in hertz,
stated as "or more" because Android treats the period as a hint), the ambient-light interval and
threshold, the proximity interval and threshold, the `poll_interval_minutes` of
`network_usage.v1` and `usage_events.v1`, and
`location.v1`'s `interval_millis` and `minimum_displacement_millimeters` (displayed in metres).
Collectors without configuration fields read the same in every study. The exact wording is in
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

**Study-level prose is not translated.** `title`, `purpose`, `researcher.name`,
`researcher.contact`, and `consent.summary` render exactly as signed. Survey titles, descriptions,
questions, endpoint labels, and choice labels are the exception: author their required default and
explicit BCP 47 overrides in the signed localized-text objects. The app selects an exact or
compatible signed language tag, then the signed default; it never invents a translation.

The deployment consequence is real and worth planning for. **A study recruiting across
languages may still need one signed configuration per language for study and consent prose — same collectors, same parameters,
its own consent document version, its own `configuration_id`, and its own signature — with
each participant given the one written in theirs. Keep `experiment_id` shared across them so
the arms are recognisable as one study, and remember that events are de-duplicated on
`experiment_id` + `configuration_id` + `participant_instance_id` + `sequence_number` (section 10), so
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
  "endpoint": "https://collect.example.edu/particeps/v1/bundle",
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
promise: a phone on mobile data all week delivers nothing until it reaches Wi-Fi.

Delivery continues while the study is `PAUSED`, for data collected before the pause, and it
continues after the study ends: finishing, completing on the duration deadline, and withdrawing
cancel future interventions and the study deadline but leave delivery running, so an undelivered tail still
reaches you. The chain stops renewing once the study is `COMPLETED` or `WITHDRAWN` and
everything it collected has been delivered. Deleting local data cancels delivery outright, so
plan for a tail you may never receive and keep manual export in your protocol as the fallback.

**How much each run sends.** Before opening HTTP, the app selects an exact event boundary, creates
one complete `PTCEXP01` bundle under its no-backup directory, flushes it to durable storage, and
records a manifest containing its bundle UUID, exact range/count, byte count, and SHA-256. One
outbox entry exists at a time. Its target plaintext budget is about 16 MiB and its hard wire limit
is 32 MiB. Process death, reboot, timeout, and response loss reuse the same bytes; the app never
regenerates ciphertext for a retry. Chunk boundaries are therefore read from the bundle or
receipt, not derived from cadence or an expected event count.

**What upload does not do.** It does not gate collection: a study whose endpoint is down,
misconfigured, or never deployed keeps recording, and a delivery failure is not treated as a
collection incident on the participant's screen.

**When delivery fails.** Only I/O failures, `408`, `425`, `429`, and `5xx` are retryable. A
redirect, `202`, every other `4xx`, malformed receipt, or receipt mismatch is a terminal failure
for that staged bundle. It remains explicit and collection continues; the app does not silently
drop the staged bytes or advance the watermark. The dashboard's fixed incident code is derived
from transport state rather than response content, so support can distinguish DNS, connection,
TLS, timeout, I/O, and HTTP failures without logging a participant identifier.

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

What the endpoint receives is the same `PTCEXP01` bundle described in section 9: a fixed-length
`application/vnd.particeps.research-bundle` POST with `Content-Digest` and no transfer encoding.
These are the only Particeps routing headers:

| Header | Value |
| --- | --- |
| `X-Particeps-Bundle-Format` | `particeps-research-bundle-v1` |
| `X-Particeps-Bundle-Id` | Lowercase bundle UUID; the receiver's immutable object key |
| `X-Particeps-Configuration-SHA256` | SHA-256 of the exact canonical configuration bytes |
| `X-Particeps-Researcher-Key-Id` | Export recipient key ID |
| `X-Particeps-Sequence-From` | Exact claimed first sequence |
| `X-Particeps-Sequence-To` | Exact claimed last sequence |
| `X-Particeps-Event-Count` | Exact claimed event count |

`Content-Length` and `Content-Digest` are also required. The `X-Particeps-*` headers are untrusted
routing claims: the receiver can check their syntax, arithmetic, body digest, and the identities
exposed by the outer framing, but cannot authenticate the encrypted participant or sequence claims.
No `participant_instance_id`, `assigned_participant_id`, `experiment_id`, or `configuration_id`
appears in the URL or headers. Do not invent such a header or deduplicate ingestion by a
participant/range pair. Receiver replay identity is the bundle UUID plus exact stored bytes and
metadata.

After durable storage, a new object returns `201 Created`; an exact replay returns `200 OK` with
the original response bytes. Both carry compact canonical JSON containing exactly `bundle_id`,
`byte_count`, `configuration_sha256`, `event_count`, `first_sequence_number`,
`last_sequence_number`, and `sha256`, with counts and sequence values as decimal strings. Only a
receipt that matches every outbox value advances the watermark. A reused bundle ID with different
content is `409 Conflict`; `202` is never success.

**The participant instance ID.** A fresh random UUID generated on the device for every import,
stored in that study's encrypted metadata, and included in every decrypted bundle. It is
pseudonymous: it
contains no name, account, device identifier, or advertising ID, and it is not shared across
studies. Re-importing the same anonymous or personalized artifact generates a different UUID.
Treat it as personal data because it links every event one import produced, but do not use it as
receiver authentication.

**You must disclose upload in your consent text.** The app renders the endpoint host, the
cadence, the network condition, the fact that only your key can open the payload, and the
fact that a random installation code travels inside the encrypted data so datasets can be
distinguished. This block sits directly below your summary and is derived from signed state rather
than your prose. That is a floor, not a substitute — the
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

Canonicalisation emits RFC 8785 JCS bytes. The signing step parses the file again and refuses it
unless re-encoding produces exactly the same bytes, so duplicate members, noncanonical numbers,
unknown fields, and hand-edited near-canonical drafts fail closed.

Sign the canonical bytes:

```bash
./gradlew :researcher-tools:run --args="sign \
  --config ./study-canonical.json \
  --private /secure/study-signing-private.key \
  --key-id lab-signer-2026 \
  --output ./study.partcfg"
```

`--key-id` must equal the configuration's `signer.key_id`, and the private key you pass must
be the one whose public half the configuration declares. Both are checked before anything is
written, because a mismatch would produce a file that signs cleanly and then fails on every
device: the second failure reads `signer.public_key in the configuration does not match
--private`.

The result is a signed study configuration: a `PTCCFG01` envelope containing magic, a two-byte
signer-key-ID length, a four-byte configuration length, the key ID, exact JCS configuration bytes,
and a fixed 64-byte Ed25519 signature over only those configuration bytes. No signature-length
field or alternate framing is accepted.
On success the command prints the IDs it signed and the fingerprint of the signing key, for
example:

```text
signed my-study-2026 my-study-config-01
fingerprint 9D0D AE5A 0D20 B29F D642 942A 0E17 4AAE
```

That fingerprint is SHA-256 over the raw 32-byte public key, truncated to 16 bytes and rendered
as eight groups of four hex characters. It is what the consent screen shows the participant,
and what you publish in your recruitment material — section 6.

Verify independently — envelope structure, signature, client build floor, platform, and validity
window — before anything reaches a participant:

```bash
./gradlew :researcher-tools:run --args="check-config \
  --envelope ./study.partcfg \
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

`--app-version` is the participant client's `versionCode`; if omitted the check treats the client
build floor as satisfied. `--now` takes an ISO instant and lets you confirm that a
configuration is refused before `issued_at` and after `expires_at` without changing the
system clock.

Any change to the configuration bytes invalidates the signature. When consent text,
collector optionality or frequency, interventions, surveys, identity mode, quota, or the export key changes, mint a new
`configuration_id`, re-sign, and obtain consent again. Never edit a `.partcfg` that has
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
not part of issuing a study: the same build verifies any correctly signed `.partcfg`.

Participants can import the `.partcfg` through the system file picker, or open an immutable
`particeps://join/v1` link / QR generated by the Web authoring surface. Join hosting is transport only:
the link fixes the artifact's complete SHA-256 and signer fingerprint, and the app downloads once,
verifies the digest before the ordinary signature flow, and never polls for replacement. Getting
data back is manual unless the study declares an upload endpoint, in which case delivery is
automatic and manual export remains available alongside it. Plan both directions separately.

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

### Optional immutable join link and QR

After signing in the Web authoring flow, enter the HTTPS location where the exact `.partcfg` bytes
will be served. The browser creates the join URI and QR locally; it does not call a QR service. The
artifact URL must use the narrow Protocol v1 profile: lowercase DNS-style HTTPS host, no credentials,
explicit default port, query, fragment, percent escape, dot segment, or repeated slash, followed by one or
more ASCII filename / token path segments. This restriction keeps Kotlin and browser URL handling
byte-for-byte identical rather than relying on either platform's silent normalization.

For a personalized configuration, publish each file at a unique path whose final segment is at
least 22 random base64url characters (128 bits or more). Never put the roster code in the path,
filename, query, CDN analytics, or access-log label. The Web authoring control rejects a URL that
contains the assigned ID or lacks the opaque token. An anonymous configuration can use a stable
immutable filename.

Treat a generated join link as part of the exact signed artifact release: changing the hosted
bytes makes its SHA-256 fail and requires a newly generated link. The app rejects redirects,
implicit retry, an oversized artifact, a fingerprint mismatch, and every join attempted while a
study or deletion is active. Download staging lives under no-backup storage and is removed at app
startup and after every outcome. Join does not add configuration refresh, remote control, or a
second consent path.

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
([`app/src/main/kotlin/cool/jacoblin/particeps/CollectorApplication.kt`](../app/src/main/kotlin/cool/jacoblin/particeps/CollectorApplication.kt))
and ships that build. The map is empty in the shipped build; populating it is strictly
exclusive, so that build refuses every signer not listed, including studies from other
teams. The pinned key also overrides whatever the configuration declares, so a configuration
cannot claim a pinned key ID while carrying a different key. The consent step then reads
"This app trusts this signer." instead of the unverified-publisher warning, and the mark
beside the heading becomes a check.

This is a build-time decision with no revocation path: retiring a pinned key means shipping
a new APK. Reproduce it before release with `check-config --public … --key-id …`, which must
print `pinned yes` for the `.partcfg` you intend to distribute.

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
an uploading study your endpoint sees ciphertext object arrivals, but the clear routing metadata
does not identify a participant and is not a health check. Silence can mean pause, withdrawal,
network constraints, a lost phone, or a terminal upload error; those cases look alike from the
endpoint.

## 9. Receive and decrypt bundles

A participant's `.partexp` is ciphertext when it reaches you, and so is every chunk your
endpoint stores. Keep the original bytes, limit who can read them, and log receipt under your
data governance procedure. Filenames and receipt timestamps are metadata a participant
controls; they are not evidence of identity or integrity. An uploaded chunk carries no more
proof of origin than an emailed export does — see [`threat-model.md`](threat-model.md).

Decrypt with the matching canonical configuration and raw HPKE private key:

```bash
./gradlew :researcher-tools:run --args="decrypt \
  --bundle ./participant-export.partexp \
  --private /secure/export-hpke-private.key \
  --config ./study-canonical.json \
  --output /controlled/participant-export.json"
```

The same command decrypts an uploaded chunk; point `--bundle` at the stored request body.

`decrypt` streams, so a bundle larger than your machine's memory still decrypts. A manual bundle
has no separate transport ceiling: it is bounded by `storage.maximum_local_bytes`, so an end-of-study
export can scale to the 8 GiB quota. Automatic upload bodies are instead capped at 32 MiB.

The command refuses to overwrite an existing `--output` path. It creates a mode-`0600` temporary
file in the destination directory, decrypts into it, then rereads it through the sole closed-world
bundle verifier. Only after AEAD, JCS, repeated identities, configuration signature, range/count,
transition history, and every catalog event contract pass does it flush and atomically move the
file into place. The AES-GCM tag is verified only at EOF, so failed decryption or semantic
verification deletes staging and publishes no partial plaintext.

The bundle is a `PTCEXP01` container: a per-bundle AES-256-GCM content key wrapped with RFC 9180
base-mode X25519/HKDF-SHA256/AES-256-GCM HPKE, over one authenticated JCS document with this
shape:

```text
bundle_id, bundle_kind, format  outer UUID, manual_export/automatic_upload, particeps-research-bundle-v1
configuration_sha256            SHA-256 of the exact embedded canonical configuration
configuration                   the exact signed configuration object
configuration_signature         signer_key_id and raw Ed25519 signature provenance
producer                        platform and client_version
exported_at_utc_millis           decimal string
experiment:
  experiment_id, configuration_id, participant_instance_id,
  assigned_participant_id (nullable), state,
  next_sequence_number, retained_from_sequence, durable_through_sequence,
  uploaded_through_sequence, event_count,
  transitions[]:
    from, to, reason,
    time: { wall_time_utc_millis, monotonic_time_nanos, boot_session_id }
  events[]:
    sequence_number, collector_id, payload_schema_version,
    observed_time: { wall_time_utc_millis, monotonic_time_nanos, boot_session_id },
    payload_type, fields
  first_sequence_number, last_sequence_number
```

`first_sequence_number` and `last_sequence_number` are the inclusive window this bundle covers,
so a chunk is never mistaken for a whole study. An uploaded chunk starts after the last sequence
the endpoint confirmed. A manual export starts at 1, or at the lowest sequence still on the
phone if the device has reclaimed a delivered prefix. `participant_instance_id` is the
pseudonymous per-import identifier described in section 4. A personalized export additionally
carries `assigned_participant_id`; use it only as the researcher's opaque join key.

All sequence, count, wall-time, monotonic-time, byte-count, and client-version values are
canonical decimal strings. Every value inside `fields` is also a JSON string, but its exact field
set, type interpretation, units, clock basis, and bound come from
[`protocol/v1/collector-catalog.json`](../protocol/v1/collector-catalog.json); do not infer a
schema from observed data. The embedded configuration, its digest, its original signature,
producer, outer identities, range/count contiguity, and every catalog payload are verified before
plaintext is published.

The JCS context binds `particeps-research-bundle-v1`, bundle UUID, full configuration SHA-256, and
researcher key ID into HPKE `info` and document AES-GCM AAD. A wrong key, context, framing byte,
embedded identity, or artifact predating this definition — a pre-rename `.adcexp` among them —
fails closed. This Protocol v1 definition is a destructive pre-1.0 replacement; there is no
former-v1 fallback.

Successful validation proves encryption to the configured researcher key, document integrity,
and the provenance of the exact embedded signed configuration. It proves nothing about the
participant's legal identity, participant/device authenticity, device attestation, or whether the
platform dropped data before it was recorded.

For a dataset rather than a one-file inspection, use [`particeps-analysis`](../particeps-analysis/README.md).
Its `inventory` command copies local exports or R2/S3-compatible objects into a
content-addressed ciphertext workspace before keys are used. `materialize` then verifies each
whole bundle, quarantines failures, reassembles by
`(experiment_id, configuration_id, participant_instance_id, sequence_number)`, refuses
conflicting duplicates, and atomically publishes typed Parquet plus a provenance manifest and
quality summary. It performs no schema inference and has no database sink or receiver-side
decryption path.

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
  rather than overlap. Where they abut is selected before the immutable outbox bundle is staged,
  so chunk sizes vary and are not derivable from the configuration — take the window
  from `first_sequence_number` and `last_sequence_number`.
- In a study that does not upload, every export is a whole history and the last one is the
  dataset. In an uploading study it need not be: once a device has reclaimed a delivered
  prefix, a manual export starts at the lowest sequence still on the phone. The dataset is
  then the reassembled chunks plus the final export, and `first_sequence_number` on each
  bundle tells you where it starts. Keep the chunks; do not treat a late manual export as a
  replacement for them.
- Partition a dataset by `(experiment_id, configuration_id)` and de-duplicate events on
  `(participant_instance_id, sequence_number)`. Equivalently, the complete event identity is
  `(experiment_id, configuration_id, participant_instance_id, sequence_number)`. If the same key
  carries different content, report a conflict; never choose a last writer. The sequence is global
  to collectors, intervention lifecycle, and survey responses within one import. Sequence numbers come from a single monotonic counter per study, so
  they are stable across exports and uploads alike, and reclaiming never reissues one. In an
  uploading study, `participant_instance_id` is what separates repeated imports and devices.
- A gap in the delivered sequence range is not proof of data loss. A chunk may not have been
  delivered yet, or may have been cut short when the study ended, and events below a
  participant's retained floor were released only because your endpoint confirmed them — look
  for them in the chunks you already hold. Ask for a manual export before treating a gap as
  missing data.
- Reconstruct running and paused windows from `transitions` together with
  `observed_time.monotonic_time_nanos` and `observed_time.boot_session_id`. Do not infer
  them from export times.

## 11. Analysis notes

### Intervention and survey events

Join `INTERVENTION_SCHEDULED`, `INTERVENTION_RESCHEDULED`, `NOTIFICATION_POSTED`, `SURVEY_OPENED`, `SURVEY_SUBMITTED`, and
`SURVEY_EXPIRED` on `occurrence_id`. These are app-observable states: `NOTIFICATION_POSTED` means
Android accepted `notify()`, never that the participant saw it. Parse `answers_json` by stable
question IDs and choice option IDs; labels are presentation text and may differ by language. Use
the scheduled/opened/submitted research-time objects to preserve wall, elapsed, and boot context.

### Acceleration, posture, and movement

Raw x/y/z includes gravity and is expressed in device coordinates, whose orientation
relative to the participant is unknown. Sort by the sensor timestamp, split by boot
session, and inspect sampling gaps before filtering, feature extraction, or modelling.
Posture requires estimating the gravity direction. Movement classification requires
independent labels and independent validation. The app supplies no ground truth.

Gyroscope axes use the same device coordinate system and boot-relative hardware timestamp, but
measure rad/s rather than acceleration. Combining the two can support a model; it does not turn
either stream into orientation or activity ground truth.

### Battery and temporal context

Battery percentage is a whole platform reading, and charging/power-save fields are context rather
than a causal explanation for sampling gaps. Temporal-context events identify settings and clock
changes. Treat time-zone ID as a setting, not location; split monotonic analyses by boot session
and use these events when interpreting wall-clock discontinuities.

### Ambient light and proximity

Illuminance and distance are raw, device-specific sensor values. Do not compare their numeric
precision across models without calibration. Many proximity sensors are binary, and neither a
near event nor a light change proves participant presence or behavior.

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
does not remove `.partexp` files a participant saved elsewhere, and it does not remove chunks
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
