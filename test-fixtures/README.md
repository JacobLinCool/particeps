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
and limiter.
A separate dynamic-profile case observes the signed 64 → 512 → 4,096 kbps changes at 30 and 60
active seconds. It waits for each durably verified applied profile with open admission and requires
new epochs and advancing resource generations in the original App process. This checks live profile
application; it does not claim that a long-lived TCP connection spans a profile barrier. Neither case
infers the applied profile from a fixed host sleep.

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
a system process kill followed by fail-closed durable recovery. Its metrics record only whether
process continuity was preserved, never either process identifier.

Fixture output contains only role, version, aggregate operation counts, attempted byte count, and
whether the competing VPN established. It never records packets, addresses, ports, hostnames, DNS
names, or exceptions.

Run the complete suite against an already booted API 34 or API 37 emulator:

```bash
tools/android-host-harness.sh
```

The script writes `android-host-harness.xml`, `fixture-metrics.ndjson`, and sanitized
`applied-profiles/*.json` observations under `build/reports/android-host-harness/`.
CI uploads that directory.
