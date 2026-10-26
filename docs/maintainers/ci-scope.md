# CI scope and test cleanup

CI separates quick pull-request feedback from release qualification. A change merged through a
PR does not trigger a second Android, analysis or receiver run. Pages still builds the merged
source before deploying it.

| Trigger | Checks |
| --- | --- |
| Android PR | Registry/vector checks, tool tests, native vet/race, JVM tests, debug lint, x86_64 debug APK |
| Analysis or receiver PR | The affected consumer's existing checks |
| Manual Android CI | Android PR checks and both complete device lanes, in parallel |
| Release tag | Android checks/device lanes, all consumers and signed release build, in parallel; publish only after all succeed |

Release qualification still exercises storage corruption, process death/reboot, VPN ownership,
throughput, permission revocation, 16 KiB native loading and signed APK identity/contents. Moving
device tests out of the PR gate means a device-only regression can be found after merge. Release
publication remains blocked by it; manual dispatch provides earlier device feedback when needed.

The host lane also runs the bounded [storage commit/recovery model](storage-verification.md),
including two expected counterexamples. Generated actual-store histories run inside the existing
Gradle tests. CI publishes model/setup and property-test timings as separate evidence; no additional
emulator lane or Gradle invocation is introduced.

## Removed duplication

- PRs no longer build a four-ABI release APK. The existing `instrumentedTestAbi` selection now
  also controls gomobile compilation, so PRs and both emulator jobs build only x86_64 native code.
  The ABI is a declared Gradle input: switching back to a normal release rebuilds all four ABIs.
  The build script checks the exact selected ABI set, provenance and 64-bit ELF alignment.
- Native compilation retains Go's content-keyed build cache under an NDK-version-specific path,
  instead of deleting it after every build. Dependency checksums, toolchain pins and packaged
  evidence checks still run. The NDK partition accounts for Go's documented inability to detect
  external C-library changes automatically; see [Go build caching](https://pkg.go.dev/cmd/go#hdr-Build_and_test_caching).
- The ordinary Kotlin export suite now includes protocol conformance, with the protocol corpus
  declared as a Gradle input. The separate init-script invocation is deleted.
- The two Kotlin-to-Python fixture producers and replay checks run in the release consumer job,
  not as additional Gradle invocations after those same JVM tests ran in Android CI.
- The registry tool tests are discovered once, without a separate invocation of the same module.
- Device lanes no longer wait for the host JVM job. Signed APK construction no longer waits for
  device tests; only publication waits. Only the publisher has repository write permission.
- API 34 builds its test APKs in a separate step before starting the emulator. A failed build
  stops the job immediately: the emulator action's pre-launch hook previously continued after
  a dependency download failure, wasting about ten minutes before missing fixture APKs failed
  harness setup. API 37's runner already stops when its prebuild fails.
- The standard Gradle `test` task already selects every required JVM/debug test with the pinned
  plugins. Removing the extra `testDebugUnitTest` argument simplifies the command; Gradle already
  deduplicated those tasks, so this is **not** a claim of fewer JVM test executions.

## Removed tests

Ten tool tests were deleted because they asserted source spelling or frozen historical prose:

- Seven tests in `test_android_host_harness.py` looked for specific substrings in workflows,
  scripts, Kotlin/Java source or manifests. They could pass with dead code and fail after a
  behaviour-preserving refactor. The executing harness, fixture validation, API 37 instrumentation
  and final APK verifier remain.
- Two changelog tests pinned the wording of RC6/RC8 announcements. The release-note parser's
  exact-section, duplicate, malformed-date and empty-content tests remain.
- One APK-verifier test inferred job permissions and execution order by splitting YAML text.
  Workflow structure is reviewed and checked with `actionlint`; the actual signer, ABI, alignment,
  manifest and provenance tests remain. A redundant source-text assertion in the certificate
  anchor test was also removed while retaining validation of the anchor itself.

One Web test was also removed because it repeated the release-version
constant, its constructed URL, and two button labels without rendering the button or checking the
asset. Release verification checks the downloadable APK and the deployed participant link instead.

Two Kotlin tests in `AndroidVpnContractTest` were also removed. They searched the unmerged
Manifest and VPN source for literal strings, without constructing the service or exercising its
behaviour. The signed-APK verifier checks the actual merged service declaration, and device
qualification still exercises selected-app routing, protocol traffic, throughput and bypass
behaviour. The repository-root system property used only by those two source tests is deleted.
This brings the cleanup to 13 removed tests; the two cold-readiness regression tests below add
behavioural coverage for the CI defect found during release.

The retained tool suite contains 195 tests, including two new cold-readiness deadline regression
tests. Tests that execute code with fake ADB/processes or
temporary fixtures are retained: they verify outcomes such as rejecting a crash despite a success
marker, preserving an unknown operation outcome, or refusing damaged APK evidence.

## Cold-start control deadline

RC18 release attempt 1 passed API 37 compatibility and 13 of 14 host scenarios. The reboot scenario
failed before a readiness receipt: `am start -W` had returned after displaying MainActivity, then
the first readiness broadcast exceeded the host's five-second command limit. Qualification stopped
after 12.43 seconds despite its existing 105-second shared deadline. This establishes the failing
control operation; it does not establish why Android delayed the reply or prove recovery succeeded.

Cold-start readiness broadcasts now use the caller's remaining readiness budget, bounded by that
same overall deadline. Live queries and mutation admissions retain their five-second command
limit. A timeout still fails without relaunching the activity or resubmitting an operation. The
reboot proof still checks safety pause, its notification, stopped data admission, stable process
identity and current-boot crash/ANR/death events. A six-second readiness fixture fails with the
previous client and succeeds with this change; a reply beyond the shared deadline still fails.

The [corrected manual workflow](https://github.com/JacobLinCool/particeps/actions/runs/40378130745)
passed its host, API 34 and API 37 jobs. API 37 completed all 14 host scenarios with zero failures
or skips and no quarantine. Its reboot proof completed in 19.89 seconds within the unchanged
105-second limit, verifying one main-process start, stable boot/process identity, the recovery
notification and two safety-paused observations with no advancing data count.

## Timing evidence

Before this change, [PR run 40359380353, attempt 1](https://github.com/JacobLinCool/particeps/actions/runs/40359380353/attempts/1)
spent 14m09s in the host job, followed by 20m32s on API 34 and 24m36s on API 37. The main Gradle
step took 10m13s; three additional Gradle invocations took another 45 seconds. API 37 failed at a
reboot-control ADB timeout, so this is a latency baseline, not a passing release result.

After cleanup, [PR run 40372237192](https://github.com/JacobLinCool/particeps/actions/runs/40372237192)
passed in 6m59s for the host job. Its main Gradle step took 3m03s, compared with 10m13s before.
The [follow-up PR run 40377990690](https://github.com/JacobLinCool/particeps/actions/runs/40377990690)
took 12m09s in the host job and 8m25s in its main Gradle step. The [final test-cleanup PR run](https://github.com/JacobLinCool/particeps/actions/runs/40381689583)
took 11m37s and 8m20s respectively. The observed PR feedback path fell
from about 39 minutes to about 7–13 minutes because the two device jobs now run at release/manual
dispatch. These are observations from individual runs with different runner/cache conditions,
not a controlled performance benchmark or a fixed duration guarantee. Overall device qualification
deadlines and throughput thresholds remain unchanged; the cold-readiness transport correction
above removes an earlier inner cutoff.
