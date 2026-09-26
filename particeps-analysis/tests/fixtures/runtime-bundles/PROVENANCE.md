# Runtime bundle fixtures

These files are `PTCEXP01` research-bundle exports written by the Particeps runtime itself. Each
`.partexp` is a byte-for-byte copy of what the runtime exported; nothing here was edited, re-encrypted
or re-signed afterwards. They exist so that every reader is tested against real runtime output
rather than against hand-built commit chains.

**They carry no participant data.** Every run used a signed study configuration whose `signer`
and `export` blocks were replaced with the public demonstration keys, and every bundle is encrypted
to the public INSECURE demonstration HPKE key `demo-hpke-2026`
([`researcher-tools/examples/INSECURE-demo-hpke-private.key`](../../../../researcher-tools/examples/INSECURE-demo-hpke-private.key)).
Anyone with this repository can decrypt them. The emulator runs used an Android emulator with
emulated sensors, and the JVM runs used scripted collector input and, in the containment runs,
scripted platform failures.

## Runtimes

| Label | Source | Producer `client_version` |
| --- | --- | --- |
| `rc13` | `4010b55`, v1.0.0-rc.13, the build the five-day pilot runs | `38` |
| `branch` | `feat/participant-transparency-and-efficiency`, from a working-tree snapshot taken before `cb6c3f1`. Its runtime, automation, and model sources equal `cb6c3f1`; its `StudyApplication.onTimerDue` did not yet map a stale-generation wake to success, which writes no commit. Rerunning the scenario from a `cb6c3f1` `git archive` reproduces every commit byte for byte and the same plaintext apart from the random bundle ID | `39` |

## Fixtures

`manifest.json` pins each file's SHA-256 and size, and the dataset the analyzer must publish from
it: commit and event counts, final state, condition-epoch count, the recovery timer rendering, and
the row count of every Parquet partition. Its `covers` labels name the Protocol v1 shapes each
fixture records; `test_runtime_bundle_fixtures.py` detects every label in the decrypted chain.

| File | Runtime | Source run | Scenario | SHA-256 |
| --- | --- | --- | --- | --- |
| `rc13-emulator-complete-from-running.partexp` (1,212,296 B) | `rc13` | emulator export `s2` | Setup, Start, real collection with Usage Access and the VPN up, then participant Complete from `RUNNING`, whose closing commit flushes the usage-events source (coverage and cursor; no events) | `aa97120c87743dbf8689a9416804439b5950555a585f181432425d252bcfef24` |
| `rc13-emulator-reboot-while-running.partexp` (178,007 B) | `rc13` | emulator export `s4` | Setup, Start, then a clean reboot while `RUNNING`; the new boot records `RECOVERY` and stays `PAUSED` | `d59704aa4cb846fb2cd0487cf3e80c5a255f8f0cdea68d63e72a283f8e3aed30` |
| `rc13-jvm-pilot-process-restart.partexp` (746,191 B) | `rc13` | JVM cross-version run `A` | Window barriers, participant pauses and resumes, a survey requested at a 17:00 barrier, process death and `RECOVERY` in the same boot, Resume, then the signed-duration deadline stop | `959c00571c4aea6a652d7adf3fd9ff36775f434d36f8a1a65b95874d49b35dc1` |
| `rc13-jvm-pilot-reboot.partexp` (747,828 B) | `rc13` | JVM cross-version run `D` | The run `A` scenario, except that the restart is a reboot with a new boot session | `2402851e8d3260927e3a85e17da9a4a9d6b3e0c0ad9962163ff8dfbc822bdea1` |
| `rc13-jvm-survey-no-resources.partexp` (48,027 B) | `rc13` | JVM pipeline harness run `min-zero` | A study with no collector and no actuator: a one-time survey timer fires and is answered, Pause, Resume, then participant Complete from `RUNNING` | `0cbfee1ea5a38b1f776e33f33003c5e167a959c08528a8930708d0825d335b14` |
| `branch-jvm-pilot-process-restart.partexp` (743,317 B) | `branch` | JVM cross-version run `B` | The run `A` scenario on this branch's runtime: its commits equal run `A`'s until the `RECOVERY` commit, which records the net timer rendering instead of the complete one | `d3c890827bda59fd248f1645d0bd627dd768ddada289d0000298ec77143f9a45` |
| `rc13-jvm-demo-barrier-flush-events.partexp` (168,530 B) | `rc13` | JVM pipeline harness run `demo-simple` | The demonstration study, whose `network_usage` collector is retrospective: a one-time survey is answered, then Pause, Resume, and participant Complete from `RUNNING`; each closing commit's barrier flush carries two `network_usage` events | `884310b31f4236a82f42ba393ee0b5cbb364840ca03d717fac359c2f56af33d2` |
| `rc13-jvm-empty-flush-and-vpn-revoked.partexp` (229,784 B) | `rc13` | JVM containment run `flush-and-revoke` | A participant Pause in the same millisecond as a usage-events poll, whose barrier flush is the empty `[t, t)` interval; Resume; the VPN revoked while `RUNNING` with the boundary audit unable to read the traffic counters, so the safety pause records no traffic audit; Resume and participant Complete from `RUNNING` | `5161423af8c63266506ca071b9f6e85303ae10d49668e44df4f5f34d7b828975` |
| `rc13-jvm-release-fails-then-resume.partexp` (176,924 B) | `rc13` | JVM containment run `release-fails` | Participant Complete from `RUNNING` whose resource release fails after the `PAUSING` commit, so a safety pause of the `PAUSING` study leaves it `PAUSED` without a deadline; Resume re-arms the deadline at generation 1; participant Complete from `RUNNING` | `b8ae7d68b7d8e1249c85569d0b123728bfe4e66c774e9f7fc85304a23a36e96b` |
| `rc13-jvm-death-while-pausing.partexp` (114,370 B) | `rc13` | JVM containment run `pausing-death` | The process dies right after the `PAUSING` commit of a participant Complete from `RUNNING`; the next process records `RECOVERY` from `PAUSING`, which re-arms the deadline at generation 1; participant Complete from `PAUSED` | `cdbd6bad3e5d1c6cc2e701995d5b1ec072481b754ec88e82caa9c20b4b1eb810` |
| `rc13-jvm-reboot-without-trusted-time.partexp` (123,402 B) | `rc13` | JVM containment run `untrusted-reboot` | A reboot while `RUNNING` after which no trusted UTC is available, so the `RECOVERY` commit keeps the previous boot's clock anchor as `committed_at` while its deactivation lies at the recovery instant in the new boot; the study stays `PAUSED` | `220185a31d52ec786a1f9e2970c665525c59edc8df264cd259a3185b1e51bd90` |

The total is 4,488,676 bytes, under the 5,000,000-byte budget that
`test_runtime_bundle_fixtures.py` enforces.

## How each run was produced

**Emulator exports (`s2`, `s4`).** The `4010b55` tree was extracted with `git archive` and built with
`./gradlew -PreleaseVersionCode=38 :app:assembleDebug`, because the debug version code 1 is below
the pilot's `minimum_client_version` 38. The configuration was the pilot configuration
`internal-five-day-optional-usage-20260914` (published under
`web/static/studies/internal-five-day-optional-usage-20260914/`), re-signed with the demonstration
signing key and exported to the demonstration HPKE key; nothing else in it changed. The real app
ran on an Android emulator with real collectors and the real VPN actuator. Notifications, Usage
Access and VPN activation were granted, gyroscope input came from `adb emu sensor`, and the device
time zone was `Pacific/Gambier`. Import, consent, access setup, Start, Complete and Export went
through the app's UI; the export was saved through the system Save dialog and pulled with `adb`.
`s4` rebooted the emulator while the study was running.

**JVM cross-version runs (`A`, `D`, `B`).** A JVM scenario driver in `core:study-application` ran
the production `StudySessionManager`, `ExperimentRuntime` and `ResearchExport`, with the same
pilot configuration and demonstration keys. Durable state crossed the process restart through the
production storage codec, a verbatim JVM copy of `core/storage`'s `EngineDataJsonCodec` (that
module is an Android library). Platform adapters, collectors and the traffic
actuator were scripted fakes behind the production collector and resource interfaces, so every
event still passed the registry contract. Phase 1 ran the study from import through several window
barriers; phase 2 restarted the process from phase 1's durable state (run `A` and `B`) or rebooted
it with a new boot session (run `D`) and continued to the deadline. Runs `A` and `D` used the
`4010b55` tree with client version 38; run `B` used this branch's runtime with client version 39.
Phase 1 of runs `A` and `B` produced byte-identical commits.

**JVM pipeline harness (`min-zero`, `demo-simple`).** A harness test placed in the `4010b55`
tree's `actuator:traffic-shaping` test source set drove the production runtime, `ResearchExport`
and configuration verifier, with WorkManager emulated by the production wake-up delay formula.
`min-zero` used the demonstration study without collectors or bindings (`experiment_id`
`modular-sensing-demo`, `configuration_id` `demo-zero-resource`); `demo-simple` used the full
demonstration study (`demo-config-2026`), whose scripted `network_usage` history places two events
in each barrier flush.

**JVM containment runs (`flush-and-revoke`, `release-fails`, `pausing-death`,
`untrusted-reboot`).** A second scenario driver in the cross-version harness, run on the `4010b55`
tree with client version 38 and the same re-signed pilot configuration, drove the production
`StudySessionManager` and runtime with the same scripted collectors and traffic actuator. It
injects the platform failures RC13 handles, through the production interfaces only: the traffic
actuator reports a `VPN_REVOKED` terminal failure and then throws from its boundary audit, as the
real actuator does when the native engine has stopped; its release throws; the store is persisted
from inside the release that follows the `PAUSING` commit, and a second process restores it; and
the clock returns no trusted UTC after a reboot. Each run starts from its own entropy counter, so
its participant instance ID is unique. The same driver on a `cb6c3f1` `git archive` wrote
byte-identical commits for `flush-and-revoke`, `release-fails`, and `pausing-death`, and the net
timer rendering in the `untrusted-reboot` recovery commit.

## Decrypting and materializing a fixture

The analyzer requires an owner-only key file:

```sh
install -m 600 tests/fixtures/runtime-bundles/INSECURE-demo-analysis-keys.json /tmp/demo-keys.json
uv run particeps-analysis inventory --workspace /tmp/fixture-work \
  --local tests/fixtures/runtime-bundles/rc13-jvm-pilot-process-restart.partexp
uv run particeps-analysis materialize --workspace /tmp/fixture-work \
  --keys /tmp/demo-keys.json --output /tmp/fixture-dataset
```

`INSECURE-demo-analysis-keys.json` holds the same key as `INSECURE-demo-hpke-private.key`, in the
analyzer's `particeps-analysis-keys-v1` format; a test keeps the two identical. Materialize the
three pilot-scenario runs one at a time: they share one participant instance ID, the `rc13` and
`branch` process-restart runs fork that participant at its `RECOVERY` commit, and the analyzer
refuses to combine them.

The repository ignores `*.partexp` everywhere so that real research exports are never committed
by accident. This directory's `.gitignore` re-includes each fixture by name, and a test keeps that
list equal to `manifest.json`, so an export copied here for debugging stays ignored.
