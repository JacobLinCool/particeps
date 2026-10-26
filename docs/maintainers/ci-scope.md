# CI scope and test cleanup

RC18 separates quick pull-request feedback from release qualification. A change merged through a
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

## Removed duplication

- PRs no longer build a four-ABI release APK. They build one x86_64 debug APK. The release job
  builds and verifies the actual signed four-ABI APK once.
- The ordinary Kotlin export suite now includes protocol conformance, with the protocol corpus
  declared as a Gradle input. The separate init-script invocation is deleted.
- The two Kotlin-to-Python fixture producers and replay checks run in the release consumer job,
  not as additional Gradle invocations after those same JVM tests ran in Android CI.
- The registry tool tests are discovered once, without a separate invocation of the same module.
- Device lanes no longer wait for the host JVM job. Signed APK construction no longer waits for
  device tests; only publication waits. Only the publisher has repository write permission.
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

The retained tool suite contains 193 tests. Tests that execute code with fake ADB/processes or
temporary fixtures are retained: they verify outcomes such as rejecting a crash despite a success
marker, preserving an unknown operation outcome, or refusing damaged APK evidence.

## Timing evidence

Before this change, [PR run 40359380353, attempt 1](https://github.com/JacobLinCool/particeps/actions/runs/40359380353/attempts/1)
spent 14m09s in the host job, followed by 20m32s on API 34 and 24m36s on API 37. The main Gradle
step took 10m13s; three additional Gradle invocations took another 45 seconds. API 37 failed at a
reboot-control ADB timeout, so this is a latency baseline, not a passing release result.

The new PR omits the two device jobs and release build. Actual post-change duration must be read
from its Actions run; local cache timings do not predict hosted-runner performance. Device
qualification is intentionally retained for the release, and no test deadlines or throughput
thresholds were relaxed by this cleanup.
