// Scheduling follows RFC 8290 Sections 4 and 5; see THIRD_PARTY_NOTICES.md.
package trafficshaping

import (
	"context"
	"hash/maphash"
	"math"
	"sync"
	"time"
)

const (
	packetQueueMaxPackets = 128
	packetQueueMaxBytes   = 64 * 1024
	flowBuckets           = 1024
	fqQuantum             = protocolMTU
	noSlot                = -1
)

const (
	flowInactive = iota
	flowNew
	flowOld
)

// Congestion policy is independent of limited/unlimited scheduling. The local
// downlink callback must not block a shared TCP processor; the kernel TUN
// reader must also keep draining. Unlimited queues retain FIFO backpressure.
type queueCongestionPolicy uint8

const (
	queueActiveManagement queueCongestionPolicy = iota + 1
	queueFattestTailDrop
)

type queuedPacket struct {
	data     [protocolMTU]byte
	size     int
	enqueued time.Time
}

type packetSlot struct {
	packet                               queuedPacket
	nextFlow, previousGlobal, nextGlobal int
	bucket                               int
}

type flowQueue struct {
	head, tail, packets, bytes, deficit int
	previous, next, membership          int
	codel                               codelState
}

type flowList struct{ head, tail int }

// Diagnostics contain only aggregate counts, never flow identities or hashes.
type packetQueueStats struct {
	queuedPackets, queuedBytes            int
	capacityDropPackets, codelDropPackets uint64
	capacityDropBytes, codelDropBytes     uint64
}

// All buckets share one fixed packet pool and both hard occupancy limits.
// Packet slots belong to both a per-bucket FIFO and a global arrival FIFO:
// Limited scheduling uses byte-deficit FQ with an explicit congestion policy;
// unlimited scheduling uses global FIFO with backpressure. Neither scheduling
// mode nor profile changes reorder packets within a bucket.
type packetQueue struct {
	mu                           sync.Mutex
	clock                        monotonicClock
	congestion                   queueCongestionPolicy
	seed                         maphash.Seed
	slots                        [packetQueueMaxPackets]packetSlot
	flows                        [flowBuckets]flowQueue
	free, globalHead, globalTail int
	newFlows, oldFlows           flowList
	stats                        packetQueueStats
	byteLimit                    int
	tailDropCursor               int
	limited, paused              bool
	pausedAt                     time.Time
	parameters                   codelParameters
	err                          error
	changed                      chan struct{}
}

func newPacketQueue(clock monotonicClock, congestion queueCongestionPolicy) *packetQueue {
	switch congestion {
	case queueActiveManagement, queueFattestTailDrop:
	default:
		panic("invalid packet queue congestion policy")
	}
	q := &packetQueue{clock: clock, congestion: congestion, seed: maphash.MakeSeed(), paused: true,
		pausedAt: clock.Now(), byteLimit: packetQueueMaxBytes, changed: make(chan struct{})}
	q.initializeStorageLocked()
	return q
}

func (q *packetQueue) initializeStorageLocked() {
	q.globalHead, q.globalTail, q.free = noSlot, noSlot, 0
	q.slots = [packetQueueMaxPackets]packetSlot{}
	for i := range q.slots {
		q.slots[i].nextFlow = i + 1
	}
	q.slots[len(q.slots)-1].nextFlow = noSlot
	q.flows = [flowBuckets]flowQueue{}
	for i := range q.flows {
		q.flows[i].head, q.flows[i].tail = noSlot, noSlot
	}
	q.newFlows, q.oldFlows = flowList{noSlot, noSlot}, flowList{noSlot, noSlot}
}

func (q *packetQueue) signalLocked() {
	close(q.changed)
	q.changed = make(chan struct{})
}

func (q *packetQueue) resetSchedulerLocked() {
	q.newFlows, q.oldFlows = flowList{noSlot, noSlot}, flowList{noSlot, noSlot}
	for i := range q.flows {
		f := &q.flows[i]
		f.membership, f.deficit, f.codel = flowInactive, fqQuantum, codelState{}
	}
	for slot := q.globalHead; slot != noSlot; slot = q.slots[slot].nextGlobal {
		bucket := q.slots[slot].bucket
		if q.flows[bucket].membership == flowInactive {
			q.appendFlowLocked(bucket, flowNew)
		}
	}
}

func (q *packetQueue) apply(rateKbps *uint64) {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.limited = rateKbps != nil
	q.parameters = codelParametersForRate(rateKbps)
	q.byteLimit = packetQueueMaxBytes
	if q.limited && q.congestion == queueFattestTailDrop {
		q.byteLimit = downlinkQueueByteLimit(*rateKbps)
		// A lower rate cannot inherit a standing backlog above its new
		// admission limit. Trim before reopening the profile, without waiting.
		for q.stats.queuedBytes > q.byteLimit {
			fattest, largest := noSlot, 0
			for i := range q.flows {
				if q.flows[i].bytes > largest {
					fattest, largest = i, q.flows[i].bytes
				}
			}
			packet := q.popFlowTailLocked(fattest)
			q.stats.capacityDropPackets++
			q.stats.capacityDropBytes += uint64(packet.size)
		}
	}
	q.resetSchedulerLocked()
	q.signalLocked()
}

// Bound limited downlink's queued serialization time using the existing
// congestion reaction interval. One MTU is the minimum useful packet capacity;
// this and the consumer-held MTU can exceed 100 ms at low rates. Rates have
// already passed the protocol's positive, bounded integer validation.
func downlinkQueueByteLimit(rateKbps uint64) int {
	bytes := rateKbps * 1_000 * uint64(codelInterval) / uint64(time.Second) / bitsPerByte
	return int(min(uint64(packetQueueMaxBytes), max(uint64(protocolMTU), bytes)))
}

// This is a scheduling hint, not a forwarding permit. A concurrent profile
// change may make one hint stale; the queue and delivery gates still enforce
// the actual mode and current profile independently.
func (q *packetQueue) isLimited() bool {
	q.mu.Lock()
	defer q.mu.Unlock()
	return q.limited
}

func (q *packetQueue) pause() {
	q.mu.Lock()
	defer q.mu.Unlock()
	if !q.paused {
		q.paused, q.pausedAt = true, q.clock.Now()
		q.resetSchedulerLocked()
		q.signalLocked()
	}
}

func (q *packetQueue) resume() {
	q.mu.Lock()
	defer q.mu.Unlock()
	if q.paused {
		// Enqueue is blocked during suspension, so every queued packet shares
		// this pause interval. Intentional suspension is not congestion.
		pausedFor := q.clock.Now().Sub(q.pausedAt)
		for slot := q.globalHead; slot != noSlot; slot = q.slots[slot].nextGlobal {
			q.slots[slot].packet.enqueued = q.slots[slot].packet.enqueued.Add(pausedFor)
		}
		q.paused = false
		q.resetSchedulerLocked()
		q.signalLocked()
	}
}

func (q *packetQueue) close(err error) {
	if err == nil {
		panic("closing a packet queue requires an error")
	}
	q.mu.Lock()
	defer q.mu.Unlock()
	if q.err != nil {
		return
	}
	q.err = err
	q.initializeStorageLocked()
	q.stats.queuedPackets, q.stats.queuedBytes = 0, 0
	q.signalLocked()
}

func (q *packetQueue) enqueue(ctx context.Context, packet []byte) error {
	if len(packet) == 0 || len(packet) > protocolMTU {
		return errInvalidTunPacket
	}
	key, err := classifyPacketFlow(packet)
	if err != nil {
		return err
	}
	bucket := int(maphash.Bytes(q.seed, key[:]) % flowBuckets)
	q.mu.Lock()
	defer q.mu.Unlock()
	for {
		if q.err != nil {
			return q.err
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		if !q.paused {
			if q.limited && !q.makeRoomLocked(bucket, len(packet)) {
				q.signalLocked()
				return nil
			}
			if q.hasRoomLocked(len(packet)) {
				q.appendPacketLocked(bucket, packet)
				q.signalLocked()
				return nil
			}
		}
		if err := q.waitLocked(ctx); err != nil {
			return err
		}
	}
}

func (q *packetQueue) hasRoomLocked(size int) bool {
	return q.stats.queuedPackets < len(q.slots) && q.stats.queuedBytes+size <= q.byteLimit
}

// Model the incoming packet at its bucket's tail while selecting the fattest
// bucket. Drop before allocating its slot, so neither hard bound is exceeded
// even transiently. Uplink uses RFC 8290 Section 4.1: half the packet count
// (at least one here for singleton queues), capped at 64, from the head.
// Downlink uses minimal tail drops to avoid blocking shared TCP processors.
func (q *packetQueue) makeRoomLocked(incoming, size int) bool {
	if q.congestion == queueFattestTailDrop {
		return q.makeRoomFromTailLocked(incoming, size)
	}
	for !q.hasRoomLocked(size) {
		fattest, largest := incoming, q.flows[incoming].bytes+size
		for i := range q.flows {
			if q.flows[i].bytes > largest {
				fattest, largest = i, q.flows[i].bytes
			}
		}
		count := q.flows[fattest].packets
		if fattest == incoming {
			count++
		}
		for range max(1, min(64, count/2)) {
			droppedSize := size
			if q.flows[fattest].head != noSlot {
				droppedSize = q.popFlowPacketLocked(fattest).size
			} else {
				// Only the virtual incoming packet can be left in this batch.
				q.stats.capacityDropPackets++
				q.stats.capacityDropBytes += uint64(droppedSize)
				return false
			}
			q.stats.capacityDropPackets++
			q.stats.capacityDropBytes += uint64(droppedSize)
		}
	}
	return true
}

// Include the incoming packet as a virtual tail before choosing the fattest
// byte backlog. Remove one tail at a time and stop as soon as both bounds hold.
// Arrival order is not TCP sequence order; drops may still require recovery.
func (q *packetQueue) makeRoomFromTailLocked(incoming, size int) bool {
	for !q.hasRoomLocked(size) {
		fattest, largest := incoming, q.flows[incoming].bytes+size
		// Equal-size resident singletons must not permanently exclude a new
		// bucket. Prefer a resident tail on a largest-backlog tie and rotate
		// ties through the fixed bucket ring. A strictly fattest incoming
		// bucket still loses its virtual tail, preserving its resident prefix.
		for offset := range len(q.flows) {
			i := (q.tailDropCursor + offset) % len(q.flows)
			backlog := q.flows[i].bytes
			if backlog > largest || (backlog == largest && fattest == incoming) {
				fattest, largest = i, backlog
			}
		}
		q.stats.capacityDropPackets++
		if fattest == incoming {
			q.stats.capacityDropBytes += uint64(size)
			return false
		}
		packet := q.popFlowTailLocked(fattest)
		q.tailDropCursor = (fattest + 1) % len(q.flows)
		q.stats.capacityDropBytes += uint64(packet.size)
	}
	return true
}

func (q *packetQueue) appendPacketLocked(bucket int, packet []byte) {
	index := q.free
	slot, flow := &q.slots[index], &q.flows[bucket]
	q.free = slot.nextFlow
	*slot = packetSlot{bucket: bucket, nextFlow: noSlot, previousGlobal: q.globalTail, nextGlobal: noSlot,
		packet: queuedPacket{size: len(packet), enqueued: q.clock.Now()}}
	copy(slot.packet.data[:], packet)
	if q.globalTail == noSlot {
		q.globalHead = index
	} else {
		q.slots[q.globalTail].nextGlobal = index
	}
	q.globalTail = index
	if flow.tail == noSlot {
		flow.head = index
	} else {
		q.slots[flow.tail].nextFlow = index
	}
	flow.tail = index
	flow.packets++
	flow.bytes += len(packet)
	q.stats.queuedPackets++
	q.stats.queuedBytes += len(packet)
	if flow.membership == flowInactive {
		flow.deficit = fqQuantum
		q.appendFlowLocked(bucket, flowNew)
	}
}

func (q *packetQueue) dequeue(ctx context.Context) (queuedPacket, error) {
	q.mu.Lock()
	defer q.mu.Unlock()
	for {
		if q.err != nil {
			return queuedPacket{}, q.err
		}
		if err := ctx.Err(); err != nil {
			return queuedPacket{}, err
		}
		if !q.paused && q.stats.queuedPackets != 0 {
			var packet queuedPacket
			if q.limited {
				packet = q.dequeueFQLocked(q.clock.Now())
			} else {
				packet = q.popFlowPacketLocked(q.slots[q.globalHead].bucket)
			}
			q.signalLocked()
			return packet, nil
		}
		if err := q.waitLocked(ctx); err != nil {
			return queuedPacket{}, err
		}
	}
}

// All blocking waits release the queue lock. Profile, lifecycle and capacity
// changes wake both sides; no wake-up depends on another packet arriving.
func (q *packetQueue) waitLocked(ctx context.Context) error {
	changed := q.changed
	q.mu.Unlock()
	select {
	case <-ctx.Done():
		q.mu.Lock()
		return ctx.Err()
	case <-changed:
		q.mu.Lock()
		return nil
	}
}

func (q *packetQueue) popFlowPacketLocked(bucket int) queuedPacket {
	flow := &q.flows[bucket]
	index := flow.head
	slot := &q.slots[index]
	flow.head = slot.nextFlow
	if flow.head == noSlot {
		flow.tail = noSlot
	}
	return q.releaseSlotLocked(index)
}

func (q *packetQueue) popFlowTailLocked(bucket int) queuedPacket {
	flow := &q.flows[bucket]
	index := flow.tail
	previous := noSlot
	// There are at most 128 resident slots. Keep the shared slot layout and
	// bounded storage unchanged; this scan never waits for a consumer.
	for slot := flow.head; slot != index; slot = q.slots[slot].nextFlow {
		previous = slot
	}
	flow.tail = previous
	if previous == noSlot {
		flow.head = noSlot
	} else {
		q.slots[previous].nextFlow = noSlot
	}
	return q.releaseSlotLocked(index)
}

func (q *packetQueue) releaseSlotLocked(index int) queuedPacket {
	slot := &q.slots[index]
	flow := &q.flows[slot.bucket]
	packet := slot.packet
	if slot.previousGlobal == noSlot {
		q.globalHead = slot.nextGlobal
	} else {
		q.slots[slot.previousGlobal].nextGlobal = slot.nextGlobal
	}
	if slot.nextGlobal == noSlot {
		q.globalTail = slot.previousGlobal
	} else {
		q.slots[slot.nextGlobal].previousGlobal = slot.previousGlobal
	}
	flow.packets--
	flow.bytes -= packet.size
	q.stats.queuedPackets--
	q.stats.queuedBytes -= packet.size
	*slot = packetSlot{nextFlow: q.free}
	q.free = index
	return packet
}

func (q *packetQueue) flowListLocked(membership int) *flowList {
	if membership == flowNew {
		return &q.newFlows
	}
	return &q.oldFlows
}

func (q *packetQueue) appendFlowLocked(bucket, membership int) {
	flow := &q.flows[bucket]
	list := q.flowListLocked(membership)
	flow.membership, flow.previous, flow.next = membership, list.tail, noSlot
	if list.tail == noSlot {
		list.head = bucket
	} else {
		q.flows[list.tail].next = bucket
	}
	list.tail = bucket
}

func (q *packetQueue) removeFlowLocked(bucket int) {
	flow := &q.flows[bucket]
	list := q.flowListLocked(flow.membership)
	if flow.previous == noSlot {
		list.head = flow.next
	} else {
		q.flows[flow.previous].next = flow.next
	}
	if flow.next == noSlot {
		list.tail = flow.previous
	} else {
		q.flows[flow.next].previous = flow.previous
	}
	flow.membership = flowInactive
}

func (q *packetQueue) dequeueFQLocked(now time.Time) queuedPacket {
	for {
		bucket := q.newFlows.head
		if bucket == noSlot {
			bucket = q.oldFlows.head
		}
		flow := &q.flows[bucket]
		if flow.deficit <= 0 {
			flow.deficit += fqQuantum
			q.removeFlowLocked(bucket)
			q.appendFlowLocked(bucket, flowOld)
			continue
		}
		packet, ok := q.dequeueFlowLocked(bucket, now)
		if !ok {
			wasNew := flow.membership == flowNew
			q.removeFlowLocked(bucket)
			// Empty new queues must first join old: repeated sparse arrivals
			// otherwise get renewed priority and can starve backlogged queues.
			if wasNew {
				q.appendFlowLocked(bucket, flowOld)
			}
			continue
		}
		flow.deficit -= packet.size
		return packet
	}
}

// Both policies use the same deficit scheduler and per-bucket ordering.
// Downlink tail admission never turns a long sojourn into a CoDel drop.
func (q *packetQueue) dequeueFlowLocked(bucket int, now time.Time) (queuedPacket, bool) {
	if q.congestion == queueActiveManagement {
		return q.dequeueCodelLocked(bucket, now)
	}
	if q.flows[bucket].head == noSlot {
		return queuedPacket{}, false
	}
	return q.popFlowPacketLocked(bucket), true
}

func (q *packetQueue) dequeueCodelLocked(bucket int, now time.Time) (queuedPacket, bool) {
	flow := &q.flows[bucket]
	if flow.head == noSlot {
		flow.codel.firstAbove, flow.codel.dropping = time.Time{}, false
		return queuedPacket{}, false
	}
	packet := q.popFlowPacketLocked(bucket)
	above := flow.codel.aboveTarget(now, packet.enqueued, q.stats.queuedBytes, protocolMTU, q.parameters)
	if flow.codel.dropping {
		if !above {
			flow.codel.dropping = false
		}
		for flow.codel.dropping && !now.Before(flow.codel.dropNext) {
			q.recordCodelDropLocked(packet.size)
			if flow.codel.count != math.MaxUint32 {
				flow.codel.count++
			}
			if flow.head == noSlot {
				flow.codel.firstAbove, flow.codel.dropping = time.Time{}, false
				return queuedPacket{}, false
			}
			packet = q.popFlowPacketLocked(bucket)
			if !flow.codel.aboveTarget(now, packet.enqueued, q.stats.queuedBytes, protocolMTU, q.parameters) {
				flow.codel.dropping = false
			} else {
				flow.codel.dropNext = codelControlLaw(flow.codel.dropNext, flow.codel.count, q.parameters.interval)
			}
		}
	} else if above {
		q.recordCodelDropLocked(packet.size)
		if flow.head == noSlot {
			flow.codel.firstAbove, flow.codel.dropping = time.Time{}, false
			return queuedPacket{}, false
		}
		packet = q.popFlowPacketLocked(bucket)
		flow.codel.aboveTarget(now, packet.enqueued, q.stats.queuedBytes, protocolMTU, q.parameters)
		flow.codel.enterDropping(now, q.parameters.interval)
	}
	return packet, true
}

func (q *packetQueue) recordCodelDropLocked(size int) {
	q.stats.codelDropPackets++
	q.stats.codelDropBytes += uint64(size)
}

func (q *packetQueue) snapshot() packetQueueStats {
	q.mu.Lock()
	defer q.mu.Unlock()
	return q.stats
}
