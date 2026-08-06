# Particeps

**Participant-first sensing for research.** Run a mobile data collection study without building an app. A study is a signed configuration file: choose which collectors to run, set their parameters and the study duration, sign it, and hand it to participants. Data is collected on the device and encrypted as it is written; it reaches you as an encrypted export the participant sends, or on a schedule if the study names an upload endpoint.

[![Android CI](https://github.com/JacobLinCool/particeps/actions/workflows/ci.yml/badge.svg)](https://github.com/JacobLinCool/particeps/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android 14–17](https://img.shields.io/badge/Android-14%E2%80%9317%20(API%2034%E2%80%9337)-3DDC84.svg)](#requirements)

Standing up a mobile sensing study normally means writing an Android app, getting permissions right, handling background execution, and building a data pipeline — before collecting a single sample. This platform does that part once. Designing a new study means writing a JSON file and signing it.

### The name

*Particeps* is Latin for one who takes part or shares in something, and it is the root of *participant*. Say it PAR-ti-keps.

The name describes where the design puts the participant, and it is worth being exact about what that does and does not mean. Events are written and encrypted on the participant's own phone; every collector a study enables is shown to them, with what it records and what it cannot establish, before they are asked to consent; and nothing is collected until they press Start. Those are defaults the implementation actually provides. What the name does not grant is authorship of the study: the collector set, the duration, and whether the study uploads on a schedule are fixed in the signed configuration, and a participant cannot change them, add to them, or recall a bundle once it has been delivered. Their leverage over a running study is bounded and real — decline it outright, withhold the Android access an *optional* collector needs so that collector stays off, pause, finish early, withdraw, and delete the local data. A collector the configuration marks required is not optional in that sense: withholding its access stops the study rather than trimming it.

## How a study works

1. **Generate your keys.** One Ed25519 pair to sign study configurations, one X25519 HPKE pair to decrypt bundles. `researcher-tools` writes raw 32-byte keys as unpadded base64url.
2. **Write the study.** A strict Protocol v1 RFC 8785 JSON file naming collectors, reusable surveys, scheduled interventions, anonymous or assigned-code identity mode, duration, storage quota, consent text, and signing/export public keys.
3. **Sign it.** `researcher-tools sign` produces a `.partcfg` file. Because the signing public key travels inside the signed bytes, any build of the app can verify it.
4. **Distribute.** Participants install the app and import your `.partcfg`. Setup is five steps, one screen each — the study details, what each enabled collector records and does not record, the consent text with the signer's key fingerprint, the Android access your collectors need, and the start button — and collection begins only when they press it.
5. **Collect.** Events are written to encrypted on-device storage. Participants can pause, resume, finish early, or withdraw.
6. **Export and analyse.** The participant exports an encrypted bundle and sends it to you. If the study declares an upload endpoint, the app also delivers immutable ciphertext bundles to an R2 receiver on a schedule. `particeps-analysis` inventories, verifies, decrypts, reassembles, and writes typed Parquet offline.

The full procedure, including key handling and study design guidance, is in the [researcher guide](docs/researcher-guide.md).

## What you can collect

Twelve selectable collectors ship in v1. A study enables the ones it names and configures each one's parameters within validated ranges.

| Collector | Records |
| --- | --- |
| `app_lifecycle.v1` | Lifecycle of this app's own activities |
| `accelerometer.v1` | Raw x/y/z acceleration, sensor time, accuracy |
| `battery_state.v1` | Battery percentage, charging state/source, power-save state |
| `temporal_context.v1` | Time-zone ID, UTC offset, DST state, clock-change reason |
| `gyroscope.v1` | Raw x/y/z angular velocity, sensor time, accuracy |
| `ambient_light.v1` | Raw illuminance, sensor time, accuracy |
| `proximity.v1` | Raw distance, sensor range, near/far interpretation |
| `network_state.v1` | Default network transport, validated/metered/roaming/VPN flags, bandwidth estimates |
| `network_usage.v1` | Device-total Wi-Fi and mobile rx/tx bytes and packets per interval |
| `usage_events.v1` | Raw app, screen, keyguard, and boot events |
| `location.v1` | Fused Location fixes with accuracy, speed, altitude, bearing |
| `keyboard_touch.v1` | Within-key touch position, timing, pressure, size, key category |

What each collector cannot establish is set out per collector in the [researcher guide](docs/researcher-guide.md), which is where a study is designed; field-level definitions of every event, including units and timestamp semantics, are in the [data dictionary](docs/data-dictionary.md).

Some practical notes: package names, location, fine-grained timing, acceleration, and keyboard dynamics can all be identifying, and ethics review will ask about that. Choosing the fewest collectors, the lowest usable rate, and the shortest duration that answers your question makes both the review and the analysis easier.

## Adding a collector

The collector set is meant to grow. A collector is a Gradle module implementing three things: a typed configuration that appears in the signed study file, a plugin descriptor declaring what access it needs, and a runtime instance that observes its source and emits events.

Collector modules depend only on `core:collector-api`, `core:study-definition`, and, for Android
hardware listeners, the narrow `collector:sensor-common` lifecycle helper. A new data
source does not touch storage, the runtime, or protocol/export code. The
[implementation guide](docs/data-collector-implementation-guide.md) walks through the contract and every registration step.

## Participant data protection

Studies collect from people's personal phones, so the platform is built to support a defensible ethics submission and honest commitments to participants.

- **Encrypted on the device.** Each study's events and metadata are encrypted with a per-study AES-256-GCM key from the Android Keystore, marked non-exportable, in 4 MiB event segments under the app's no-backup storage, up to the quota the configuration set. An event is appended once and never rewritten; the only thing that removes a segment is confirmed delivery to the study's endpoint, and then only under storage pressure.
- **Signed, tamper-evident studies.** A configuration is Ed25519-signed and strictly validated: RFC 8785 bytes, exact schema, known collectors, Android platform, validity window, and minimum client build. Verification failures are fail-closed — an unverifiable configuration collects nothing. A signature proves the configuration is unchanged since it was signed; it does not prove who wrote it unless the build pins that signer, and the consent screen states which of the two applies.
- **Durable interventions and native surveys.** Notification actions can use one-time, recurring, daily-local, or signed random-local-window triggers. Random instants are selected with a CSPRNG and persisted before scheduling, so retries and reboot do not redraw them; clock/time-zone changes do not rewrite already materialized occurrences. Native surveys support short text, integer scales, single choice, and multiple choice; only a confirmed, complete submission enters the encrypted event stream.
- **Separated participant identities.** Every import gets a fresh random instance UUID. A configuration may additionally carry an opaque researcher-assigned code; both appear in the encrypted document. Upload URLs and headers contain no participant, assigned, experiment, or configuration ID. Their bundle UUID, configuration digest, researcher key ID, exact range/count, size, and digest are untrusted routing claims, not participant authentication.
- **Encrypted, participant-directed export.** Getting data to the research team is an export the participant performs and directs, encrypted with a fresh key per export and wrapped to your HPKE public key. The app never holds your private key.
- **Scheduled upload, when the study asks for it.** A configuration may name an HTTPS endpoint, interval, and metered-network policy. The endpoint host, cadence, and network condition are shown before consent. Before HTTP starts, the app durably stages one immutable ciphertext bundle in no-backup storage: about 16 MiB of plaintext and at most 32 MiB on the wire. Retries send those exact bytes with fixed length and digest. Only a matching seven-field receipt on `201 Created` or exact-replay `200 OK` advances the watermark; redirects, `202`, malformed receipts, and other terminal responses do not. Finishing or withdrawing leaves delivery running until the tail arrives. Undelivered events are never reclaimed to make room.
- **Participant control over the lifecycle.** Collection starts only on an explicit action and can be paused, finished, or withdrawn. Pausing takes a monotonic boundary, so delayed callbacks cannot leak post-pause data into the dataset.
- **Storage failures stop collection.** Quota exhaustion or a write failure fail-closes the study to `PAUSED` rather than silently dropping events, so a dataset is complete over the window it declares or absent.

### Who published the study

A configuration carries its own signing public key in a mandatory `signer` block, so one published app can verify any researcher's study without a rebuild. The cost is that a signature alone says nothing about origin: the researcher name and contact shown on the consent screen are text the signer chose. The mitigation is the key fingerprint — the first 16 bytes of SHA-256 over the signing public key, rendered as eight groups of four hex characters — which the consent step shows under the heading *Configuration signature*. Publish your fingerprint in the material that recruits participants so they can compare the two, and note that a participant reaches a study through your recruitment channel rather than an anonymous download.

The shipped build pins no signer, so it accepts any correctly signed configuration and tells the participant that the publisher is unverified. An institution that wants one build to run only its own studies adds its key to `TRUSTED_SIGNING_KEYS` in `CollectorApplication` and ships that build; every other signer is then refused outright.

For ethics reviewers, the [threat model](docs/threat-model.md) documents the trust assumptions and, more usefully, what the design does *not* protect against.

## Quick start

### Requirements

JDK 17, and Android SDK platform and build tools for API 37. The app targets Android 14 through 17 (`minSdk 34`, `compileSdk`/`targetSdk 37`).

### Build and test

```bash
./gradlew test testDebugUnitTest lintDebug assembleDebug assembleRelease
```

With an emulator or device attached:

```bash
./gradlew :core:storage:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

The app suite separates the Android signed-configuration regression
([`AndroidConfigurationImportTest`](app/src/androidTest/kotlin/cool/linc/particeps/AndroidConfigurationImportTest.kt)),
the full participant UI flow ([`CoreFlowTest`](app/src/androidTest/kotlin/cool/linc/particeps/CoreFlowTest.kt)),
and the five-collector Android integration
([`P2CollectorEmulatorTest`](app/src/androidTest/kotlin/cool/linc/particeps/P2CollectorEmulatorTest.kt)).
The last test skips when gyro, light, or proximity hardware is absent. Its optional exact-value mode
expects a sensor-capable emulator that the host has already configured; the test does not fake
Android's sensor APIs:

```bash
adb -s emulator-5554 emu power ac on
adb -s emulator-5554 emu power status charging
adb -s emulator-5554 emu power capacity 73
adb -s emulator-5554 emu sensor set gyroscope 1.25:-2.5:0.5
adb -s emulator-5554 emu sensor set light 123
adb -s emulator-5554 emu sensor set proximity 1
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=cool.linc.particeps.P2CollectorEmulatorTest \
  -Pandroid.testInstrumentationRunnerArguments.p2SyntheticInputs=true
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`. A clean checkout has no signing material, so `assembleRelease` produces an unsigned release APK.

### Try it without a real study

`researcher-tools/examples` contains a demonstration study and its key pair. Those keys are public fixtures committed to this repository: anyone can sign a configuration that presents itself as the demo study, and anyone can decrypt an export encrypted to the demo HPKE key. They are fine for development and emulator testing, never for real participants.

For that reason a **release build ships no demonstration study** — the signed envelope and its loader are in the app's `debug` source set only, so a released app runs nothing but a study a research team signed and handed out. Build the debug variant if you want to try the participant flow without a configuration of your own.

### Researcher CLI

```text
signing-keygen   generate an Ed25519 signing pair
hpke-keygen      generate a raw X25519 HPKE key pair
canonicalize     strictly parse and emit a canonical configuration
sign             sign a canonical configuration into .partcfg
check-config     verify envelope, signature, platform, validity window, and client build; optionally pin the signer
decrypt          decrypt a .partexp into particeps-research-bundle-v1 JSON
```

## Architecture

```mermaid
flowchart LR
    UI[":app Compose UI"] --> VM["StudyViewModel"]
    VM --> Session[":core:study-application"]
    Session --> Runtime[":core:experiment-runtime"]
    Runtime --> API[":core:collector-api"]
    Collectors[":collector:*"] --> API
    Session --> StorePort["StudyStore port"]
    Storage[":core:storage"] --> StorePort
    Session --> Export[":core:export"]
    Export --> Crypto[":core:crypto"]
    Android[":app Android adapters"] --> Session
    Android --> Receiver["receiver/ Cloudflare Worker"]
    Receiver --> R2["private R2 ciphertext"]
    Access[":core:access"] --> Session
    Protocol[":core:protocol"] --> Definition[":core:study-definition"]
    Tools[":researcher-tools"] --> Definition
    Tools --> Protocol
    Tools --> Export
```

| Module | Responsibility |
| --- | --- |
| `:app` | Compose UI, finite UI state, SAF, and Android foreground/work/recovery/upload adapters |
| `:core:model` | Bounded study metadata, state and event models, `StudyStore` port and its retained window |
| `:core:study-definition` | Strict canonical JSON, closed-world typed study and collector configuration |
| `:core:protocol` | Signed envelope, immutable join URI, signature verification, optional signer pinning, validity and version checks |
| `:core:collector-api` | Collector lifecycle, health, registry, access contract, shared callback dispatcher |
| `:core:crypto` | Protocol v1 raw-key Ed25519 verification and fixed-suite RFC 9180 HPKE over raw X25519 keys; Tink is internal only, never a wire keyset |
| `:core:access` | Runtime permission, Usage Access, input method, and hardware preflight |
| `:core:experiment-runtime` | Command serialisation, state machine, collector supervision, event admission gate |
| `:core:study-application` | The single active-study session, recovery, port coordination, and the upload watermark |
| `:core:storage` | Keystore-backed encrypted metadata, appended event segments, and reclaiming delivered ones |
| `:core:export` | Streaming JSON to AES-GCM over a sequence window under an optional size budget, HPKE key wrapping, receipts |
| `:collector:*` | One isolated module per data source |
| `:researcher-tools` | Ed25519 and HPKE keys, canonicalise, sign, verify, decrypt CLI |
| `receiver/` | One bounded Protocol v1 upload POST, immutable ciphertext writes, and canonical receipts |

Platform-independent modules contain no `android.*` imports, which keeps the domain logic testable on the JVM. [Component boundaries](docs/component-boundaries.md) documents the contracts.

New contributors should treat [`protocol/v1`](protocol/v1/README.md) as the normative wire contract, the [collector catalog](protocol/v1/collector-catalog.json) as the schema source, and [`docs/p0-p2-implementation-contract.md`](docs/p0-p2-implementation-contract.md) as the implementation decision record. Trace one path through the [configuration codec](core/study-definition/src/main/kotlin/cool/linc/particeps/core/definition/StudyConfigurationCodec.kt), [signed envelope](core/protocol/src/main/kotlin/cool/linc/particeps/core/protocol/SignedConfiguration.kt), [bundle exporter](core/export/src/main/kotlin/cool/linc/particeps/core/export/ResearchExport.kt), [bundle verifier](core/export/src/main/kotlin/cool/linc/particeps/core/export/ResearchBundleVerifier.kt), [single-entry outbox](app/src/main/kotlin/cool/linc/particeps/platform/FileUploadOutbox.kt), [HTTP adapter](app/src/main/kotlin/cool/linc/particeps/platform/OkHttpStudyUploader.kt), [receiver handler](receiver/src/index.ts), and the offline [`particeps-analysis`](particeps-analysis/README.md) pipeline. The join path is similarly short: [Web authoring](web/src/lib/particeps/join.ts), [shared parser](core/protocol/src/main/kotlin/cool/linc/particeps/core/protocol/JoinLink.kt), [Android staging](app/src/main/kotlin/cool/linc/particeps/platform/JoinArtifactDownloader.kt), [intent entry](app/src/main/kotlin/cool/linc/particeps/MainActivity.kt), then the existing [session import](core/study-application/src/main/kotlin/cool/linc/particeps/core/application/StudyApplication.kt). The [outbox](app/src/test/kotlin/cool/linc/particeps/platform/FileUploadOutboxTest.kt), [uploader](app/src/test/kotlin/cool/linc/particeps/platform/OkHttpStudyUploaderTest.kt), and [receiver](receiver/tests/receiver.test.ts) tests make crash/replay and receipt semantics executable. Receiver deployment and R2 operations start at [`receiver/README.md`](receiver/README.md), and the Collector capability policy lives under [`assurance`](assurance/README.md).

For `random_window`, trace the signed model and bounds in
[`StudyConfiguration.kt`](core/study-definition/src/main/kotlin/cool/linc/particeps/core/definition/StudyConfiguration.kt),
its codec and [Web editor](web/src/routes/researcher/InterventionEditor.svelte), then the CSPRNG
materialization in
[`InterventionSchedulePlanner.kt`](core/study-application/src/main/kotlin/cool/linc/particeps/core/application/InterventionSchedulePlanner.kt).
The [session](core/study-application/src/main/kotlin/cool/linc/particeps/core/application/StudyApplication.kt)
persists the occurrence before scheduling; the Android delivery/expiry workers in
[`AndroidStudyPlatform.kt`](app/src/main/kotlin/cool/linc/particeps/platform/AndroidStudyPlatform.kt)
and [`BootRecoveryReceiver`](app/src/main/kotlin/cool/linc/particeps/BootRecoveryReceiver.kt)
reconcile the same ID after retries, reboot, clock, or time-zone changes. The adjacent planner,
runtime, session, and app policy tests make each boundary executable.

## Documentation

| Document | For |
| --- | --- |
| [Researcher guide](docs/researcher-guide.md) | Designing, signing, deploying, and analysing a study |
| [Data dictionary](docs/data-dictionary.md) | Every field on every event, per collector |
| [Participant guide](docs/participant-guide.md) | People taking part in a study |
| [Collector implementation guide](docs/data-collector-implementation-guide.md) | Writing a new collector |
| [System design](docs/system-design.md) | The implemented v1 architecture in full |
| [Component boundaries](docs/component-boundaries.md) | Module contracts and invariants |
| [Threat model](docs/threat-model.md) | Trust assumptions and limitations, for ethics review |
| [Normative Protocol v1](protocol/v1/README.md) | JCS, keys, join URI, binary framing, bundle document, upload, receipt, and conformance corpora |
| [P0–P2 implementation contract](docs/p0-p2-implementation-contract.md) | Locked architectural decisions and scope |
| [Collector capability policy](assurance/README.md) | Static source, bytecode, and dependency boundaries for collectors |
| [Ciphertext receiver](receiver/README.md) | R2-only Worker contract, verification commands, deployment, and operations |
| [Offline analysis](particeps-analysis/README.md) | Ciphertext inventory, verification, reassembly, and typed Parquet materialization |
| [Release process](docs/maintainers/release.md) | Maintainers |

## Contributing

New collectors are the main contribution path — see [CONTRIBUTING.md](CONTRIBUTING.md) and the [implementation guide](docs/data-collector-implementation-guide.md). To report a security or privacy issue, see [SECURITY.md](SECURITY.md) rather than opening a public issue.

## Coming from a pre-rename release candidate

This project was called Android Data Collector through its 1.0 release candidates; every tag published so far carries that identity. The rename to Particeps moved the Android `applicationId` from `cool.linc.androiddatacollector` to `cool.linc.particeps`, and Android treats those as two different applications. There is no upgrade and no migration: installing Particeps does not see, move, or convert anything belonging to an installed pre-rename build, which keeps running under its own name until it is removed. Uninstalling it takes its Keystore keys with it, and every study, encrypted event segment, undelivered outbox bundle, and imported configuration on that install becomes unrecoverable — cloud backup and device transfer were already disabled for this app, so nothing is held anywhere else. Export whatever is still wanted before uninstalling, and re-enable the research keyboard under the new app if a study uses it.

Artifacts produced before the rename are unsupported for final Protocol v1. A `.adccfg` configuration, a `.adcexp` export, an `ADCCFG01` or `ADCEXP01` container, a `research-bundle-v1` document, an `adc://join/v1` link, and an upload carrying `application/vnd.adc.research-bundle` or any `X-ADC-*` header are invalid input to every current implementation and are rejected exactly as random bytes are. Re-sign configurations with the current tooling and re-run any pilot; there is no converter, and none will be added.

## Status

This repository implements and tests the full local participant flow on Android 14–17.

**The app's own screens ship in English and Traditional Chinese.** The interface follows the phone's system language, and a picker in the app's header changes it for this app alone; that picker writes through Android's `LocaleManager`, so it is the same setting as the system's per-app language screen rather than a second one beside it. Adding a language is a `values-*` directory and one line in `res/xml/locales_config.xml`.

Researcher-supplied text is a separate matter: the study title, purpose, researcher name, contact, and consent summary are rendered exactly as they were signed, in whatever language they were written, whatever language the app is in. Recruiting across languages therefore means one signed configuration per language.

Running a real study also needs work this repository cannot do for you: ethics and legal approval, your own study signing key and a published fingerprint for it, a data governance plan, and validation on the physical devices and OEM builds you intend to support. Emulator tests passing is not ethics approval, Play policy compliance, or scientific validity.

## License and citation

MIT — see [LICENSE](LICENSE). If you use this platform in published work, please cite it using the metadata in [CITATION.cff](CITATION.cff).
