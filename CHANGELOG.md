# Changelog

What changed between releases, and what each change asks of someone who already installed one.

This project is pre-1.0. Several early release candidates changed something that a device treats as
identity — the application ID, the file formats, or the signing certificate. Do not infer update
compatibility from the version number; each release below states what an existing installation
must do.

## Unreleased

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
