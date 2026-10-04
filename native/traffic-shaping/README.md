# Particeps traffic-shaping native core

This module is the source-built gomobile boundary for Particeps' local Android
VPN. It composes the pinned tun2socks gVisor stack directly instead of invoking
the upstream process-style engine. No compiled AAR or native library belongs in
the repository.

## Binding contract

`CreateEngine` takes ownership of the detached TUN file descriptor on every
outcome and closes it exactly once. Protocol v1 accepts MTU 1500 only. The
engine starts suspended and requires this order:

1. `ApplyProfile` with exact RFC 8785 bytes shaped as
   `{"downlink_kbps":null|integer,"id":"profile-id","uplink_kbps":null|integer}`.
2. `Start`, then verify `IsHealthy`, `HasOpenTun`, and the returned SHA-256 digest.
3. `Resume` to admit packets.

A profile may change only after `Suspend`. Applying it resets both directional
buckets and all per-generation aggregate counters. `Stop` is permanent and
idempotent.

The native callback surface contains only a mandatory socket protector and a
one-shot terminal failure code. Every TCP and UDP socket is synchronously
protected before connect or bind. Protection failure makes the entire engine
unhealthy. The terminal callback is synchronous and may only close admission
and wake the runtime; it must not call back into the engine.

## Shaping semantics

- Delivery from the uplink queue into the network stack, and successful TUN
  writes on downlink, are the aggregate Layer-3 accounting, shaping, and
  resource-barrier boundaries. Rate credit is consumed before delivery, so
  audit counters contain exactly the packets admitted under the cap.
- `1 kbps` is 1,000 aggregate Layer-3 bits per second, including IP and
  transport headers and admitted retransmitted packets.
- Each direction owns one token bucket with capacity
  `max(1500 bytes, floor(rate * 2 s / 8))`. The two-second initial credit is
  large enough for TCP's initial congestion window at the minimum supported
  rate while keeping a saturated 60-second interval below the protocol's 105%
  upper bound.
- A new profile starts with one full bucket and inherits no prior credit.
- One uplink reader drains the kernel TUN independently from paced delivery.
  Its aggregate FIFO has 128 fixed 1500-byte slots and a separate 64 KiB
  payload-occupancy limit, plus at most one packet held by each of the reader
  and consumer. It does not classify flows or retain packet identities.
- Limited uplink profiles use RFC 8289 CoDel (5 ms target, 100 ms interval)
  and tail drop at the hard capacity limit. CoDel suppresses drops when at
  most one MTU remains queued, preserving utilization at low rates. Discarded
  packets consume no credit and enter no successful-delivery audit counters.
  TCP and UDP share this policy; no loss, latency, or per-flow fairness
  guarantee is made. Queue-drop diagnostics remain process-local aggregates.
- Unlimited uplink profiles use bounded FIFO backpressure without CoDel or
  capacity drops. A mode change wakes a producer waiting for queue capacity.
- Suspension and profile replacement interrupt waits. Queued and held packets
  remain bounded and must obtain the current profile's permit before delivery.
  Suspension blocks queue admission and delivery, excludes paused time from
  sojourn measurements, and resets CoDel's congestion history. Profile changes
  also reset that history. Stop closes the descriptor and queue, then joins
  the single reader; terminal failures close forwarding admission immediately.

The direct proxy is intentionally thin: it opens raw TCP/UDP sockets and
synchronously protects each descriptor. Shaping remains at the Layer-3 boundary,
with the pinned tun2socks/gVisor stack handling IP and transport packets.

TCP forwarding connects the protected upstream socket before acknowledging
the application's connection through TUN. An unreachable destination rejects
that connection without reporting a successful local handshake, so applications
can continue their IPv4/IPv6 connection race. Each upstream dial is limited to
five seconds and canceled when the engine stops. Established connections retain
TCP half-close behavior with a 60-second drain deadline; stopping closes both
sides and waits for forwarding handlers to exit. Ordinary connection failures
do not fail the engine; socket-protection failures remain terminal.

The upstream logger is set to its silent implementation before the network
stack is created. This module has no logging surface and never reports packet
contents, source/destination addresses, ports, or DNS names.

## Reproducible source build

Use Go 1.26.3 and Android NDK 30.0.14904198. Dependency acquisition must set:

```text
GOPROXY=https://proxy.golang.org
GOSUMDB=sum.golang.org
```

Do not add `direct` to `GOPROXY`. `sbom-input.json`, `go.mod`, and `go.sum` are
the release-verifier inputs; `THIRD_PARTY_NOTICES.md` carries the policy-pinned
upstream notices, and the tun2socks MIT text is embedded in every generated
AAR. `build-aar.sh` enforces tool versions, checksums, four ABIs, 16 KiB
alignment for 64-bit libraries, provenance, and the embedded notice.
