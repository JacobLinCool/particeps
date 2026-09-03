# Changelog

What changed between releases, and what each change asks of someone who already installed one.

This project is pre-1.0. Several early release candidates changed something that a device treats as
identity — the application ID, the file formats, or the signing certificate. Do not infer update
compatibility from the version number; each release below states what an existing installation
must do.

## v1.0.0-rc.8 — 2026-09-03

- Protocol v1 is replaced in place by a durable event-driven study runtime. The event-source
  registry now defines both collector and system events; signed configurations define named
  resource profiles, reusable one-shot actions, and closed-world automations. Lifecycle, timer,
  action, resource, and condition-epoch records share one ordered event and commit history.
- Encrypted storage now uses authenticated append-only `EngineCommit` frames. A source observation,
  reducer checkpoint, timer/action/resource mutation, generated audit event, and successor
  projection either commit together or do not exist. Encrypted snapshots are recovery caches;
  opening replays only complete commits after the newest authenticated snapshot, and export/upload
  revalidate every complete commit they read. This preserves the cold-start performance work that
  avoids reconstructing a long study one event at a time without weakening the commit-chain truth.
- Collectors are stateful resources controlled by signed binding automations. Start and Resume do
  not hard-code continuous collection: required resources must apply and verify before the first
  condition epoch is committed and collector data admission opens. Any resource-vector change uses
  one global flush/drain/apply/verify barrier.
- Added source-built, fail-closed Android per-App traffic shaping. A local `VpnService` forwards only
  signed target packages through a gVisor userspace stack and applies aggregate uplink/downlink token
  buckets. VPN ownership, TUN/native health, package identity, socket protection, and the exact
  applied profile must all verify; loss closes admission and safely pauses the study.
- The participant App keeps the existing five setup steps and compact running surface. Shaping
  studies add one fixed high-level inline disclosure and Android's mandatory permission/consent
  surfaces, but no trigger, treatment, profile, rate, timer, epoch, digest, or diagnostic dashboard.
  Web authoring and encrypted analysis retain the complete signed and causal record.
- The starting screen reports what startup is doing. After a short patience window it shows the
  running activation stage — reading the study, checking authenticated storage, and restoring the
  runtime — with an indeterminate bar. Participant UI remains generic; debug builds log stage
  transitions with elapsed time so a development-device stall can be located.
- Platform acknowledgements are bounded: durable timer/action wakeups, foreground-host acquisition,
  and Play services location registration/removal cannot leave startup waiting indefinitely. A
  timeout follows the same fail-closed resource path as an explicit platform failure.
- API 37 compatibility remains a release blocker for compilation, installation, the revision 5+
  16 KiB runtime, manifest/permission contracts, source-built native loading, non-snapshot
  instrumentation, four packaged ABIs, and 16 KiB ELF alignment. The complete API 37 host harness
  is temporarily quarantined only for the exact revision 5 `mapper.ranchu.so` / `SurfaceFlinger`
  readback assertion tracked in [#33](https://github.com/JacobLinCool/particeps/issues/33); App, VPN,
  native, and test assertion failures still block. This release does not claim that the complete
  API 37 host harness passed. API 34 continues to block on the complete functional, traffic,
  throughput, lifecycle, process, package, permission, and competing-VPN harness.

**This is a destructive pre-1.0 Protocol v1 cut.** Signed configurations, encrypted storage,
bundles, receipts, readers, and scheduled-work state from every earlier build are invalid. There is
no migration, dual reader, or fallback. The app does not silently delete an incompatible local
study; the existing generic recovery/reset flow requires participant confirmation.

**Coming from `v1.0.0-rc.7`:** export any research data that must be retained before upgrading,
then install the signed rc.8 APK directly over rc.7; do not uninstall rc.7 first. The Android
application ID and production signing certificate remain unchanged, but an rc.7 study cannot resume
under this destructive Protocol/storage cut. After upgrading, use the participant-confirmed recovery
reset, import a newly issued rc.8-compatible signed configuration, and repeat consent/access setup.

**Fresh install:** install the signed rc.8 APK, then import an rc.8-compatible signed configuration.

## v1.0.0-rc.7 — 2026-08-17

- Running studies now recover automatically after a device reboot. The app durably records
  `RUNNING → PAUSED / DEVICE_REBOOT`, advances one metadata-v2 timeline with trusted cross-boot UTC,
  validates the deadline, access, WorkManager, and foreground service, then records
  `PAUSED → RUNNING / AUTOMATIC_RECOVERY`. Participant pauses never auto-resume. Missing trusted time
  or any failed check stays paused and produces a generic repair notification with a safe diagnostic
  code.
- Existing `PTCCFG01` `.partcfg` files remain valid. Recovery of an already accepted active study
  still verifies framing, schema, signature, Android platform, and minimum client build, but does not
  reinterpret the configuration's import-only `expires_at` as an early study deadline. Fresh import
  and destructive reset reuse continue to enforce the complete validity window.
- Active-study, metadata, and append-journal atomic residues are now authenticated as separate base,
  pending, and replacement candidates. Recovery proceeds only when every valid combination
  converges; deletion tombstones take priority and conflicts remain typed hard failures. The exact
  previously shipped metadata layout migrates once to v2, which adds the shared lifetime and
  active-collection checkpoint.
- The recovery screen can retry the same closed validation path or, after an irreversible warning,
  durably reset old storage and keys. A still-valid signed envelope restarts consent and access with
  a new participant instance and full duration; an unreadable or expired one returns to file import.

**Coming from `v1.0.0-rc.5` or `v1.0.0-rc.6`:** install the signed rc.7 APK directly over the
existing app. Keep the same application ID and production signing certificate, and publish it with a
higher `versionCode`; do not uninstall first. First launch performs the one-time metadata migration
and strict residue repair.

## v1.0.0-rc.6 — 2026-08-09

- Notification access is required for every study because the daily status reminder and ongoing
  collection notification are app-level guarantees, not features that depend on interventions.
  The app checks the Android permission, the app-wide notification switch, and each channel the
  study needs. It rechecks required access before both start and resume and from the running
  foreground service. A failed setup, Start, or Resume preflight leaves `ACCESS_SETUP`, `READY`, or
  `PAUSED` unchanged; required access lost after the study is already `RUNNING` creates the typed
  safety pause. An optional source is blocked and resumed independently.
- Collector access capabilities now live in each collector's static descriptor. The access step
  keeps the collector owners when shared access is de-duplicated, orders dependent operations, and
  shows app-authored English and Traditional Chinese instructions for background location, Usage
  Access, and research-keyboard setup.
- Background location is no longer requested through an Android runtime dialog that cannot grant
  it. After precise location is granted, Particeps first verifies the signed study's exact Fused
  Location request against Android settings, then explains the background behaviour and opens the
  app's Android settings page for the participant to choose Android's localized background option
  manually.
- Start and Resume now wait until Android has acknowledged the foreground service with its exact
  service types before any source may emit. A whole-study safety loss closes admission and records
  its closed reason in an identity-free typed marker; reason-bearing WorkManager retry survives a
  process restart, and each optional source has its own fail-closed event gate. Required access uses
  `REQUIRED_ACCESS_MISSING`. Once a study is durably running, losing every acknowledged foreground
  host during a type change uses `COLLECTION_HOST_FAILURE`; an untrustworthy store mutation uses
  `STORAGE_FAILURE`; and a failed or cancelled source release uses `COLLECTION_TEARDOWN_FAILURE`.
  An unacknowledged deadline, reminder, upload, intervention, or retry mutation uses
  `WORK_SCHEDULING_FAILURE`. WorkManager enqueue and cancellation must be acknowledged before the App treats
  the durable handoff or retry retirement as complete, so Resume cannot race a stale safety worker.
- The signed duration is now an absolute ceiling measured from the one durable participant Start.
  Resume, time change, and same-boot process recovery recompute and replace the deadline from that
  boundary; they cannot grant a fresh duration. Collector and occurrence admission independently
  reject every observation at or beyond the exact monotonic deadline, and the deadline worker
  rechecks due-ness before completing, so delayed or early WorkManager execution cannot widen or
  shorten the signed window. The app trusts only the monotonic clock from the
  participant-start boot. Any active study observed in another boot session fails closed with
  `WORK_SCHEDULING_FAILURE` before a foreground service or collector can reopen; wall time is never
  used as a cross-boot fallback.
- Safety-critical documents no longer rely on Android `AtomicFile`, which can log an `fsync` or
  rename failure without returning it. The repo-owned acknowledged writer keeps independently
  durable `.pending` and `.replacement` copies, preserves the first as an uncertainty witness while
  atomically replacing the base with the second, and acknowledges only after exact readback and
  directory sync. Any leftover witness or unknown event-directory entry blocks recovery instead of
  being guessed away.
- The release workflow now requires the final APK to have exactly one signer whose certificate
  matches the [rc.5 production identity anchor](.github/android-release-signing-certificate.sha256).
  A different or additional certificate stops publication. The emulator gate has read-only repository
  permission; only the dependent APK publication job receives write permission.

**Coming from `v1.0.0-rc.5`:** install the signed rc.6 APK over the existing app. The application ID
and release signing certificate are unchanged, so Android accepts it as an in-place update and the
active study and its local data remain in place. Do not uninstall rc.5 first.

**Coming from `v1.0.0-rc.4` or earlier:** none of those builds can update to rc.6 in place. Follow
the release-specific note below and export anything worth retaining with tooling that supports that
release before uninstalling it.

## v1.0.0-rc.5 — 2026-08-07

- The application ID moved from `cool.linc.particeps` to `cool.jacoblin.particeps`, and the release
  signing key was rotated so that the certificate names Particeps rather than the pre-rename
  product. Either change alone stops a device accepting the build as an update; both apply.
- The status line reports when a pause started and how long it has lasted.
- One low-importance notification a day states whether the study is still collecting, or is paused
  and since when. It names the application and collection state, never the study; a lock-screen
  reader can still infer that the phone uses Particeps. Starting or stopping collection retracts a
  standing one.

**Coming from `v1.0.0-rc.4`:** uninstall it. Its data cannot be migrated, and its exports are in the
current format, so export anything worth keeping before you remove it and current tooling will read
it. See [the participant guide](docs/participant-guide.md) for the participant-facing version.

## v1.0.0-rc.4 — 2026-08-06

The project was renamed from Android Data Collector to Particeps. Application ID
`cool.linc.particeps`.

Protocol v1 keeps `schema_version: 1` and gains no second dialect; every identity string was
replaced at once:

| | Was | Now |
| --- | --- | --- |
| Signed configuration | `.adccfg`, `ADCCFG01` | `.partcfg`, `PTCCFG01` |
| Encrypted export | `.adcexp`, `ADCEXP01` | `.partexp`, `PTCEXP01` |
| Join URI | `adc://join/v1` | `particeps://join/v1` |
| Bundle format | `research-bundle-v1` | `particeps-research-bundle-v1` |
| Upload media type | `application/vnd.adc.research-bundle` | `application/vnd.particeps.research-bundle` |
| Upload headers | `X-ADC-*` | `X-Particeps-*` |
| Offline analysis | `adc-analysis` | `particeps-analysis` |

The retired spellings are rejected inputs rather than an older dialect. Every implementation fails
closed on them, and the shared conformance corpus carries a vector for each.

**Coming from `v1.0.0-rc.3` or earlier:** uninstall it first — it is a different application ID and
runs alongside. Its exports are `.adcexp` files that current tooling refuses, so anything worth
keeping has to be exported and analysed with the pre-rename tooling before you remove it.

## v1.0.0-rc.3 — 2026-08-05

Application ID `cool.linc.androiddatacollector`. The R2 ciphertext receiver, the offline
verification and Parquet pipeline, immutable signed join links, and the battery, temporal-context,
gyroscope, ambient-light and proximity collectors.

## v1.0.0-rc.2 — 2026-08-03

Application ID `cool.linc.androiddatacollector`.

## v1.0.0-rc.1 — 2026-08-02

First release candidate. Application ID `cool.linc.androiddatacollector`.
