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

- TUN read/write is the aggregate Layer-3 accounting, shaping, and resource-
  barrier boundary. TUN read is uplink and TUN write is downlink. Rate credit
  is consumed before the packet crosses that boundary, so the traffic counted
  by the audit contract is exactly the traffic subject to the cap.
- `1 kbps` is 1,000 aggregate Layer-3 bits per second, including IP and
  transport headers and any retransmitted packets observed at the TUN.
- Each direction owns one token bucket with capacity
  `max(1500 bytes, floor(rate * 2 s / 8))`. The two-second initial credit is
  large enough for TCP's initial congestion window at the minimum supported
  rate while keeping a saturated 60-second interval below the protocol's 105%
  upper bound.
- A new profile starts with one full bucket and inherits no prior credit.
- Suspension and profile replacement interrupt waits. At most the one packet
  held by the synchronous TUN call waits for admission and is reconsidered
  under the newest profile; Particeps creates no additional packet queue and
  does not deliberately drop a packet.

The direct proxy is intentionally thin: it opens raw TCP/UDP sockets and
synchronously protects each descriptor. Shaping remains at the TUN boundary,
and Particeps does not replace or tune the upstream tun2socks/gVisor stack.

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
