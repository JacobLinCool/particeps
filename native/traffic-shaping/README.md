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
  The local link endpoint schedules downlink directly at `WritePackets`, before
  the paced TUN write, replacing the upstream 1024-packet FIFO shared by
  download data and upload ACKs. Each direction separately owns 128 fixed
  1500-byte packet slots, a 64 KiB payload-occupancy limit and 1024 scheduling
  buckets, plus at most one producer and one consumer packet. Downlink
  producers serialize each packet before copying bytes and release admission
  between packets of a stack batch; the stack retains ownership
  of its original packet references.
- Limited profiles use new/old byte-deficit FQ scheduling (RFC 8290), with
  per-bucket FIFO ordering and a 1500-byte quantum. Uplink applies CoDel and
  capacity management so the kernel TUN reader can keep draining.
  Uplink capacity overload drops from the head of the largest byte-backlog bucket,
  half its packet count per batch (at least one, at most 64), rather than
  preferentially discarding a newly arriving flow. Both occupancy bounds hold
  even while admitting that packet.
- Flow classification hashes the IP version, addresses, transport protocol and
  TCP/UDP ports with a per-direction random seed fixed for the engine lifetime. All fragments, including first
  and IPv6 atomic fragments, use addresses/protocol with zero ports. Unknown
  protocols and opaque extension headers also use that coarse classification;
  their payload is not parsed. Hash collisions share one FIFO. Classification keys
  are temporary; no separate tuple or hash records are retained or exported.
- Limited downlink uses the same FQ scheduler with nonblocking capacity
  admission. On overflow it includes the incoming packet in the byte backlog,
  then drops one arrival-tail packet from the fattest bucket at a time until
  both hard bounds hold. Equal-largest backlogs select a resident tail using
  a rotating cursor over the fixed buckets, so a new equal-sized bucket is not
  always rejected. Selecting a strictly larger incoming bucket drops the incoming
  packet itself. It does not apply CoDel or batch head drops. This keeps the
  stack's shared TCP processors available to handle other connections; it can
  still cause TCP loss recovery, and arrival-tail order is not TCP sequence
  order. No lossless-delivery or contiguous-sequence guarantee is made.
  Its effective byte admission limit is
  `min(65536, max(1500, floor(rate_bps * 100 ms / 8)))`: 1500, 6400, and 51200
  bytes at 64, 512, and 4096 kbps. A profile decrease trims resident fattest
  tails to the new limit before resuming; unlimited restores 64 KiB. This
  limits queued serialization delay, not end-to-end latency: the MTU floor
  plus one consumer-held packet can take 375 ms at 64 kbps. The smaller queue
  can still discard bursts. After each limited-mode enqueue, the link releases
  its producer/copy/queue scopes and yields to ready Go goroutines so an
  immediately runnable consumer can use available token credit. It never
  sleeps, retries, or waits for capacity. A brief locked mode read avoids this
  extra scheduling on the unlimited path; a concurrent profile switch can
  make one scheduling hint stale, without changing forwarding admission.
- Uplink CoDel's target is the larger of 5 ms and one MTU's serialization time at the
  configured directional rate. The interval retains the 100 ms default independently
  of the target; low-rate targets may exceed this reaction interval. At
  64/512/4096 kbps the targets are
  187.5/23.4375/5 ms, respectively. The remaining **aggregate** backlog at or
  below one MTU suppresses CoDel drops; an empty individual bucket returns to
  the scheduler safely. Uplink TCP and UDP share this congestion policy, without ECN
  marking. Discarded packets consume no credit and enter no Layer-3
  admission counters. No loss, latency, strict per-flow fairness or per-application
  fairness guarantee is made. Drop diagnostics are process-local aggregates.
- Unlimited profiles use bounded FIFO backpressure without CoDel or
  capacity drops. A mode change wakes a producer waiting for queue capacity.
  Packets keep both their per-bucket and global arrival order, so mode changes
  preserve per-bucket ordering and unlimited uses the remaining arrival FIFO.
- Suspension and profile replacement interrupt waits. Queued and held packets
  remain bounded and must obtain the current profile's permit before delivery.
  Suspension blocks queue admission and delivery, excludes paused time from
  sojourn measurements, and resets CoDel's congestion history. Profile changes
  also reset that history. Stop closes the descriptor and both queues before joining the uplink pump
  and two link workers. Detachment also wakes workers before the stack waits;
  terminal failures close forwarding admission immediately. The packet already
  held by a paced consumer is not preempted by a newly arriving sparse flow.

The stack explicitly selects classic SACK loss recovery (`TCPRecovery=0`)
with SACK negotiation and Reno congestion control retained. This configuration
is installed during suspended startup before any TCP handshake; failure to
apply it fails startup. It does not eliminate queue drops or retransmission
timeouts, and is not a claim to implement a particular upstream TCP fix.

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
