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

The blocking throughput stage runs two selected targets concurrently for 60 seconds at each signed
64, 512, and 4,096 kbps resource profile. It checks their combined TCP payload reaches 85% of the
Layer-3 cap and stays below the cap plus 5% and one MTU. The payload floor accounts for IP/TCP
headers and virtual-device scheduling jitter; it does not relax the upper limit. A simultaneous
unselected control connection must exceed that upper bound, proving it bypasses both the local VPN
and limiter.
Before that measurement, the same TCP/UDP/DNS/IPv4/IPv6 attempt matrix runs while the signed VPN
resource is verified; the host requires the original Particeps process and study to remain RUNNING.

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

The script writes only `android-host-harness.xml` and `fixture-metrics.ndjson` under
`build/reports/android-host-harness/`. CI uploads exactly that directory.
