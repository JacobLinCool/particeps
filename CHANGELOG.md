# Changelog

What changed between releases, and what each change asks of someone who already installed one.

This project is pre-1.0. Several early release candidates changed something that a device treats as
identity — the application ID, the file formats, or the signing certificate. Do not infer update
compatibility from the version number; each release below states what an existing installation
must do.

## v1.0.0-rc.13 — 2026-09-14

- Manual encrypted export reads each retained commit once, using a scoped read snapshot instead of
  repeating storage recovery and a full JSON prescan. Size-limited uploads stop reading when their
  complete-commit budget is reached. Canonical JSON writing buffers characters and emits complete
  string spans while retaining Unicode validation.
- Exports show phase progress and support cancellation. Pause, completion, and withdrawal remain
  available during export, including slow destination writes. Destination open, close, and failed
  export cleanup run off the main thread; success is reported only after final close succeeds.
- Cancelled or failed exports remove their incomplete file when the document provider permits it,
  and report when manual removal is needed. Local study data remains available for retry.
- Added a 24-hour study timeline, structured survey and automation editors, and native WebMCP
  authoring tools to the researcher site. Improved simulation, import, key handling, review, and
  bundle summaries, and fixed study-rule panels collapsing into dividers.

**Application update from `v1.0.0-rc.12`:** install the signed RC13 APK over the existing app
without uninstalling or clearing its data. The application ID and production signing certificate
are unchanged, and the Android version code increases. Allow an active export to finish before
updating when possible. RC12 has no export-cancellation control; if updating interrupts an export,
discard that incomplete file and export again after reopening RC13.

**Local studies and exported data:** RC13 introduces no local-store migration, mandatory reset,
event-source-registry change, or bundle-format change from RC12. Existing signed configurations and
local study data remain in place; they do not need to be re-signed. Reopen the app after updating,
complete normal recovery and access checks, and explicitly Resume the study. Older-release
restrictions documented below still apply. An export includes the fixed data boundary captured
when it starts; later study actions are included in the next export. Only send files for which the
app reports successful completion.

**Fresh install:** install the signed RC13 APK, then scan a research-team QR code or import its
signed study file. Review the study and data collection, provide consent, complete required access
setup, and explicitly Start.

**Verification scope:** this prerelease uses a locally built production-signed APK with version
code 40. APK signing and native-packaging verification, native Go race tests and vet, host unit
tests, API 34 storage and participant-export instrumentation, Kotlin-to-Python encrypted-bundle
verification, and debug/release lint and builds passed locally. The remote API 34 complete functional
gate, host CI, and Web, receiver, and analysis consumer checks also passed.

The remote API 37 x86_64 / 16 KiB gate was blocked before instrumentation by the existing
SurfaceFlinger / `mapper.ranchu.so` DMA-capability assertion on image revision 6 and emulator
37.1.11. All three APK-install attempts failed with a package-service broken pipe. Separate API 37
/ 16 KiB ARM64 compatibility instrumentation passed on the Play Store image revision 4; this does
not establish a pass for the remote x86_64 lane or its full host harness. The automated publisher
was skipped because it depends on that gate. See the
[RC13 workflow evidence](https://github.com/JacobLinCool/particeps/actions/runs/34773740598).

For 53.3 MB of synthetic data, the desktop export-core median decreased from 1.470 seconds to
0.253 seconds; this excludes Android Keystore, storage-provider, and network time and is not a
measurement of export duration on a participant's phone. A blocking provider write or close can
delay cancellation cleanup; study controls remain available while it finishes.

## v1.0.0-rc.12 — 2026-09-11

- Fixed the local VPN completing an app's TCP handshake before its protected upstream connection
  succeeded. An unreachable IPv6 destination could appear connected and prevent the app from trying
  a working IPv4 address. The VPN now connects upstream first and rejects failed connections before
  acknowledging them, while retaining IPv4 and IPv6 support.
- VPN shutdown cancels pending TCP connections, closes upstream sockets, and waits for endpoint
  registration before shutting down the network stack. TCP half-close preserves the server's
  response after the client finishes sending.
- Added IPv4/IPv6 packet-level regressions for handshake ordering, rejected connections, complete
  bidirectional transfers, half-close, and cancellation during connection setup and shutdown.
  UDP round-trip tests verify complete packets and shaping transitions. The Android host harness
  now requires a complete VPN download; failed transfers and skipped scenarios cannot count as a pass.

**Application update from `v1.0.0-rc.11`:** retain any required research exports, then install the
signed RC12 APK over the existing app without uninstalling. The application ID and production
signing certificate are unchanged, and the Android version code increases.

**Local studies and exported data:** RC12 introduces no local-store migration, mandatory reset,
or event-source-registry change from RC11. Existing signed configurations and local study data remain
in place. Reopen the app after updating, complete normal recovery and access checks, and explicitly
Resume the study. Installing the APK does not change a study's collectors, schedule, consent, or
questionnaire. The restrictions for older configurations documented in earlier releases still apply.

**Fresh install:** install the signed RC12 APK, then scan a research-team QR code or import its
signed study file. Review the study and data collection, provide consent, complete required access
setup, and explicitly Start.

**Verification scope:** this prerelease uses a locally built production-signed APK with version
code 39. Native Go race tests and vet, host unit tests, debug/release lint, and APK signing and
native-packaging verification passed. The remote API 34 complete functional gate and Web, receiver,
and analysis consumer checks also passed. Packet tests include a complete 256 KiB download through
the capped all-App VPN. The user reported restored browsing on a Pixel 10a running Android 16 after
an ADB update of the production-signed repair.

The remote API 37 x86_64 / 16 KiB gate was blocked before instrumentation by the existing
SurfaceFlinger / `mapper.ranchu.so` DMA-capability assertion on image revision 6 and emulator
37.1.11. Its three APK-install attempts failed with a package-service broken pipe, so the automated
publish job was skipped. Separate API 37 / 16 KiB ARM64 compatibility instrumentation passed on the
Play Store image revision 4; this does not establish a pass for the remote x86_64 lane or its full
host harness. See the [RC12 workflow evidence](https://github.com/JacobLinCool/particeps/actions/runs/34508171857).

## v1.0.0-rc.11 — 2026-09-09

- Added an in-app camera scanner as the primary way to join a study. Scanning uses the same
  configuration verification, study/data review, consent, access setup, and explicit Start steps
  as file import. File import remains available, including on devices without a camera.
- Research QR codes contain an immutable HTTPS join link, the configuration's exact SHA-256,
  and its signing fingerprint. The app downloads the signed configuration, so scanning requires
  internet access. The code does not contain the complete configuration. Invalid codes, changed
  artifacts, and mismatched signatures are rejected; scanning cannot replace an existing study.
- The camera is requested only when opening the scanner and is released on backgrounding or
  leaving it. Frames are decoded locally and are not stored. Added permission recovery, retry,
  flashlight controls, and English/Traditional Chinese scanner screens.
- Added downloadable SVG QR images to web study authoring for sharing or printing. Generation
  stays in the researcher's browser, and changing or invalidating the link clears the old image.
- Moved study-download body reads and temporary-file writes off the main thread, added an overall
  download timeout, and prevented access refresh from competing with the initial import.

**Application update from `v1.0.0-rc.10`:** retain any required research exports, then install the
signed RC11 APK over the existing app without uninstalling. The application ID and production
signing certificate are unchanged, and the Android version code increases. Camera access is optional
and is not required to continue an existing study or import a file.

**Local studies and exported data:** RC11 introduces no local-store migration, mandatory reset,
or event-source-registry change from RC10. Existing studies retain their signed configuration and
remain subject to normal access, recovery, and explicit Resume checks. The RC10 restrictions for
older configurations selecting the removed notification collector still apply. Installing this
APK does not change a study's collectors, schedule, consent, or questionnaire.

**Fresh install:** install the signed RC11 APK, then scan a QR code issued by the research team
or choose its signed study file. Review the study and data collection, provide consent, complete
required access setup, and explicitly Start. Researchers must host the exact signed configuration
at the HTTPS address encoded in the QR; redirects and replacement bytes are rejected.

**Verification scope:** this prerelease uses a locally built production-signed APK. App unit tests,
targeted API 34 QR import/consent/camera-lifecycle instrumentation, web QR checks, debug/release lint,
and APK signing/native-packaging verification passed before release. This is not a claim that the
complete remote Android release gates or API 37 compatibility lane passed. Optical scanning on
physical devices and browser installation with Play Protect enabled remain to be verified.

## v1.0.0-rc.10 — 2026-09-09

- Removed direct collection of other apps' notifications from the Android app, including the
  notification listener service, its access setup, and the collector module. Particeps' own survey
  reminders and ongoing research notification remain supported.
- Removed the notification collector from web study authoring and both five-day study examples.
  Each example now selects seven collectors; the 120-hour duration, collection schedules,
  all-App speed-limit windows, and activity questionnaires retain their existing design.
- Retained the published `notification_events.v1` event contract for historical interpretation,
  while marking the implementation unavailable and the source unselectable. Newly authored or
  imported studies cannot select it; the complete event-source registry digest changes in RC10.
- Hardened API 37 release checks to require an unlocked Android user and a successful
  `Application.onCreate()` return, and to reject App crashes even when instrumentation reports success.

**Installation:** RC10 removes the notification-listener capability associated with the reported
RC9 Play Protect block and continues direct signed APK distribution. This does not establish that
Google Play Protect will accept it on every device. Browser download and installation with Play
Protect enabled on the affected physical phone remain to be verified; signing and emulator
installation do not establish that result.

**Application update from `v1.0.0-rc.9`:** export any research data that must be retained **before
updating**, then install the signed RC10 APK over the existing app; do not uninstall first. The
application ID and production signing certificate are unchanged. Android application identity and
local-study compatibility are separate checks.

**Local studies and exported data:** an RC9 study selecting `notification_events.v1` cannot resume
in RC10. Complete or stop that study and retain its export while RC9 can still open it. After the
update, the incompatible study enters recovery; a participant-confirmed recovery reset **deletes
local study data**. Only reset after retaining the export, then import a newly issued and signed
configuration without the removed collector and repeat consent/access setup. Other studies remain
subject to the app's normal recovery and supported-contract checks. Keep the RC9 researcher and
analysis tools for all RC9 export bundles and use RC10 tools for RC10 bundles, because each bundle
binds its release's complete registry digest, even when it contains no notification data. Installing
an APK does not revise a signed study configuration.

**Fresh install:** install the signed RC10 APK and import a configuration issued for RC10. The
updated examples use public demonstration keys; researchers must supply their study information,
consent text, and signing/export keys before recruitment.

## v1.0.0-rc.9 — 2026-09-08

- Added participant-relative study days and local-time windows. The five-day study examples run
  for 120 hours from each participant's Start, apply aggregate 500 kbps upload and download limits
  on local days 3–5 from 12:00 to 17:00, and invite one activity survey after each treatment window.
  All-App shaping includes newly installed apps in the current Android user; the baseline keeps
  the same VPN route with no rate cap.
- Added screen power/lock state, notification metadata, passive device-throughput counters, and
  independent VPN-state collectors. The existing default-network event contract remains unchanged.
  Gyroscope collection keeps a partial wake lock while enabled; notification contents are not read
  and throughput collection does not run active speed tests.
- Each collector can select its own collection windows and profiles. A scheduled-off collector is
  released, including its sensor registration and wake lock; selecting it again creates a new
  verified generation. Required collectors fail closed when a selected profile cannot run. Required
  actuators and collector sources used to trigger automations must remain active throughout the
  running study.
- Reduced repeated work while collecting: UI projections use one committed runtime snapshot,
  admission waits suspend instead of polling, storage accounting reads file metadata, and encrypted
  recovery snapshots are checkpointed by commit/byte thresholds. Every acknowledged event still
  has a durable authenticated commit; lifecycle boundaries and recovery retain strict checks.
- Web authoring, Kotlin execution, and Python replay agree on scheduled inactive collectors and
  applied generations. Added a five-day example collecting gyroscope data only from 12:00 to 17:00
  while the other collectors continue throughout the running study.
- Both study examples include six questions covering off-phone activities, screen-free time,
  other devices, device substitution, and substitution with activities without screens. These are
  separately signed study configurations: installing the APK does not change an existing study's
  questionnaire or schedule.
- Added a configurable feature-level energy-estimation formula and a Pixel 10a scenario report.
  The coefficients are explicit assumptions, not measurements or a validated device power model;
  the estimates do not establish battery savings on participants' phones.

**Timing and device limits:** Android background wakeups can delay a window transition or survey
notification. Use recorded epochs, resource receipts, and event times to assess the actual exposure.
Process death or reboot requires participant Resume after normal fail-closed recovery. A complete
120-hour physical-device study and battery calibration have not been performed. The existing API 37
preview-emulator quarantine remains limited to the exact platform defect described in the
[release process](docs/maintainers/release.md); it is not a full API 37 functional-pass claim.

**Application update from `v1.0.0-rc.8`:** export any research data that must be retained first,
then install the signed rc.9 APK over the existing app; do not uninstall first. The application ID
and production signing certificate are unchanged. Earlier releases retain the release-specific
Protocol/storage restrictions documented below.

**Local studies and exported data:** rc.9 introduces no local-store migration or mandatory reset
for rc.8 studies. Existing signed configurations are still checked against the current supported
contracts and access requirements; normal recovery and explicit Resume checks apply. Keep rc.8
analysis tooling for rc.8 export bundles: bundles bind the complete event-source registry digest,
which changes in rc.9. Use rc.9 analysis tooling for rc.9 exports. New collectors, schedules, and
questionnaires require a newly issued signed study configuration and participant setup.

**Fresh install:** install the signed rc.9 APK, then import a study configuration issued for rc.9.
The example configurations use public demonstration keys; researchers must supply their own study
information, consent text, and signing/export keys before recruitment.

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
