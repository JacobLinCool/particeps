# Android host-test fixtures

These application modules exist only for the blocking emulator harness. They are independent APKs,
are not dependencies of `:app`, disable every release variant, and must never be distributed with
Particeps.

- `traffic-target-a`, `traffic-target-b`, and `traffic-control` run the same deterministic
  TCP/UDP/DNS/IPv4/IPv6 attempt workload. The DNS operation emits a fixed minimal query datagram,
  so its presence does not depend on resolver caching. Target A also has a higher-version
  replacement variant.
- `shared-uid-target` and `shared-uid-peer` are debug-signed fixtures for the Android shared-UID
  edge case. The peer is deliberately absent from the signed target list.
- `competing-vpn` establishes a local black-hole VPN after the host grants Android's VPN app-op, so
  Android revokes/replaces the Particeps VPN without a fake production callback.

The blocking throughput stage runs two selected targets concurrently for 60 seconds in each of
three independently imported, signed fixed-profile studies: 64, 512, and 4,096 kbps. Each study has
one traffic profile and no elapsed-time condition, so Android startup and cleanup time cannot move
the measurement into another speed condition. Before and after each measurement, the host checks
the original App process and the same durably verified resource epoch, vector, profile digest and
generation. It checks their combined TCP payload reaches 85% of the
Layer-3 cap and stays below the cap plus 5% and one MTU. The payload floor accounts for IP/TCP
headers and virtual-device scheduling jitter; it does not relax the upper limit. A simultaneous
unselected control connection must exceed that upper bound, proving it bypasses both the local VPN
and limiter. All host payload counts use read completion in the half-open measurement window
`[0, duration)`: a chunk completing at or after the deadline is excluded in full.
Every selected connection must also make progress: after the first 10 seconds, no target may have
more than five consecutive complete one-second buckets with zero delivered payload. For a 60-second
measurement, this examines buckets 10 through 59; sparse missing buckets are zero and deadline-tail
buckets are excluded. This guards against starvation under the fixture's continuous local demand,
not Internet latency or equal throughput shares. Metrics report `rate_passed`, `liveness_passed`,
each target's longest zero-payload run, the fixed window and failure reasons; both checks must pass.
A separate dynamic-profile case observes the signed 64 → 512 → 4,096 kbps changes at 30 and 60
active seconds. It waits for each durably verified applied profile with open admission and requires
new epochs and advancing resource generations in the original App process. This checks live profile
application; it does not claim that a long-lived TCP connection spans a profile barrier. Neither case
infers the applied profile from a fixed host sleep.

A separate 60-second duplex case uses the fixed 512/512 kbps profile: target A uploads while target B
downloads a fixed all-`Z` payload, alongside an unselected upload control. The host counts delivered
upload bytes. Android counts actual download read completions, validates every payload byte, and
atomically publishes exactly 60 one-second buckets after its own window ends. A fresh measurement ID
binds both observations to the barrier; the host requires the download barrier acknowledgement within
one second. Host and Android use their respective monotonic clocks, so their origins are bounded by
that handshake rather than claimed identical. The sender stops within 61.5 seconds; its socket write
counts are diagnostic only. Both received directions must meet the same rate and liveness bounds,
using only chunks completed inside their respective 60-second windows. Receiver EOF/errors, an
early sender stop, stale IDs, or truncated windows fail the case; a sender error after the receiver's
normal deadline closure is expected and retained as a diagnostic. Applied-profile and process
proofs bracket the transfer; by default it makes no periodic device queries. Run this case alone with
`tools/android-host-harness.sh --skip-build --duplex-only`.
For the same case at 64/64 kbps, add `--duplex-cap-kbps 64`; the cap option accepts only
64 or 512 and requires `--duplex-only`. It selects the existing signed fixed-cap study and
brackets the transfer with that profile's verified receipts. Both caps use the same 60-second
window, 85–105% payload bounds (with the existing one-MTU upper allowance), control bypass,
and at most five consecutive zero-payload seconds after the ten-second warmup. The default
14-case release suite retains its 512/512 duplex case.

`host-study.json` is the dynamic source fixture. Regenerate its signed asset and the three fixed
variants with `./gradlew :researcher-tools:installDist` followed by
`python3 tools/generate_android_host_studies.py`. The generator uses the production canonicalizer
and signer with the repository's explicitly public `INSECURE-demo-signing-private.key`; these
envelopes belong only in instrumentation assets. The JVM asset test verifies every signature.
Before that measurement, the same TCP/UDP/DNS/IPv4/IPv6 attempt matrix runs while the signed VPN
resource is verified; the host requires the original Particeps process and study to remain RUNNING.
This matrix exercises packet submission and failed-connection handling against documentation-only
addresses. It does not require a response and does not establish UDP, DNS, or IPv6 connectivity.

A separate blocking all-apps gate transfers and checks 256 KiB from a controlled local TCP server
through the research app's default socket. It verifies the VPN remains healthy, applies the 500 kbps
download cap, and releases cleanly. The harness supplies the server endpoint and requires an actual
successful instrumentation result; skipped tests and missing endpoints fail this gate. The existing
selected-app throughput stage independently checks TCP forwarding and aggregate upload shaping.

Live package replacement, uninstall, shared-UID peer installation, competing-VPN replacement, and
underlying-network handover use a debug-only, read-only state receiver. The host rejects a changed
Particeps PID, waits within a fixed bound, and confirms event admission remains quiescent after a
safety pause. It then restarts Particeps and verifies the pause had already been durably committed,
instead of accepting process-recovery fallback as evidence. Process kill and reboot have their own
durable recovery cases. On API 37, revoking `ACCESS_LOCAL_NETWORK` is another blocking live safety
case; API 34 reports that case explicitly as not applicable in JUnit.
The API 37 case accepts only two platform-defined outcomes: a same-process permission callback, or
a system process kill followed by one explicit App reopen and fail-closed durable recovery. That
reopen is used only after observing the original PID disappear/change; losing the recovery process
fails the case. Metrics distinguish `process_continuity` and `recovery_action=explicit_app_reopen`.

Debug control uses short broadcasts and a bounded process-local receipt registry. Cold setup opens
the App and polls readiness independently; reset/provision is admitted once with a fresh operation
UUID and process UUID, then its completion is polled. Changed processes, unknown outcomes, conflicting
UUID reuse, busy execution, or an exhausted 4,096-receipt capacity fail explicitly. Receipts are not
evicted or replayed. State/profile/native queries never open an Activity or restart a process;
applied-profile work takes the same runtime locks asynchronously and retains the existing proof
checks. `control-operations/` preserves bounded status traces and synthetic process identities without
the signed envelope. An unfinished operation may outlive a host timeout, which never authorizes a
second mutation.

Fixture output contains role, version, aggregate operation counts, attempted byte count, and
whether the competing VPN established. Saturation diagnostics also record aggregate write progress,
write duration, and exception class/errno without exception messages. They never record packets,
addresses, ports, hostnames, or DNS names.

Run the complete suite against an already booted API 34 or API 37 emulator:

```bash
tools/android-host-harness.sh
```

The script writes `android-host-harness.xml`, `fixture-metrics.ndjson`, and sanitized
`applied-profiles/*.json` observations under `build/reports/android-host-harness/`.
CI uploads that directory.

With `--capture-throughput-diagnostics`, each measurement additionally samples native Layer-3 counters, fixture write progress,
kernel TCP/TUN counters when accessible, and selected process-state fields into
`throughput-diagnostics/`. Every observation has host monotonic timestamps; the server records its
measurement origin in the same clock domain. Unavailable counters, command failures and timeouts
remain explicit, never zero-filled. Fixture writes count bytes accepted by the socket API; the
host counts delivered TCP payload. Failed cases capture bounded synthetic-emulator logcat before
reset or reboot can erase the failure window, including in the default suite without periodic sampling.
The report also retains every instrumentation invocation's combined output and, on failure,
bounded `dumpsys activity lastanr` and `lastanr-traces` reads. Missing, empty or failed diagnostic
reads do not establish the absence of an ANR and never change the scenario result. After reboot,
the harness requires two consecutive boot-complete and user-0 `RUNNING_UNLOCKED` observations
before instrumenting the App. It queries ActivityManager only after boot completion, since ADB
becomes available before that service; later command errors still fail the gate. This readiness
check does not waive application startup failures.

For a bounded diagnostic run, `tools/android-host-harness.sh --skip-build --fixed-cap-kbps 512 --repetitions 5`
records all five independent 60-second attempts with the same throughput bounds and enables sampling.
The cap must be 64, 512 or 4,096 kbps, paired with one to five repetitions. Add `--duration-seconds 300`
for five-minute connections; this option is valid only in the focused fixed-cap lane.
Use `--no-throughput-diagnostics` for a comparison without periodic host
queries. The regular CI invocation runs all 14 scenarios with 60-second measurements and no
periodic sampling. Debug broadcasts can affect process scheduling/importance; a passing instrumented
run alone does not establish that an intermittent stall has been fixed. The sampler has a hard
duration-plus-60-second limit and records command failures or missing observations explicitly.

For duplex diagnosis, explicitly combine `--duplex-only --capture-throughput-diagnostics`. Its
`duplex/diagnostics.ndjson` contains the same native/kernel observations and upload progress from A
and the control only. Target B's download evidence is still read once after its window; a prior B
upload-progress file is never sampled as if it described the current download. Fixed-cap and duplex
diagnostic modes are mutually exclusive.
