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
	uplinkQueueMaxPackets = 128
	uplinkQueueMaxBytes   = 64 * 1024
	uplinkFlowBuckets     = 1024
	fqQuantum             = protocolMTU
	noSlot                = -1
)

const (
	flowInactive = iota
	flowNew
	flowOld
)

type queuedUplinkPacket struct {
	data     [protocolMTU]byte
	size     int
	enqueued time.Time
}

type uplinkPacketSlot struct {
	packet                               queuedUplinkPacket
	nextFlow, previousGlobal, nextGlobal int
	bucket                               int
}

type uplinkFlowQueue struct {
	head, tail, packets, bytes, deficit int
	previous, next, membership          int
	codel                               codelState
}

type uplinkFlowList struct{ head, tail int }

// Diagnostics contain only aggregate counts, never flow identities or hashes.
type uplinkQueueStats struct {
	queuedPackets, queuedBytes            int
	capacityDropPackets, codelDropPackets uint64
	capacityDropBytes, codelDropBytes     uint64
}

// All buckets share one fixed packet pool and both hard occupancy limits.
// Packet slots belong to both a per-bucket FIFO and a global arrival FIFO:
// limited uses FQ-CoDel, unlimited uses lossless FIFO backpressure. Neither
// mode nor profile changes reorder packets within a bucket.
type uplinkPacketQueue struct {
	mu                           sync.Mutex
	clock                        monotonicClock
	seed                         maphash.Seed
	slots                        [uplinkQueueMaxPackets]uplinkPacketSlot
	flows                        [uplinkFlowBuckets]uplinkFlowQueue
	free, globalHead, globalTail int
	newFlows, oldFlows           uplinkFlowList
	stats                        uplinkQueueStats
	limited, paused              bool
	pausedAt                     time.Time
	parameters                   codelParameters
	err                          error
	changed                      chan struct{}
}

func newUplinkPacketQueue(clock monotonicClock) *uplinkPacketQueue {
	q := &uplinkPacketQueue{clock: clock, seed: maphash.MakeSeed(), paused: true,
		pausedAt: clock.Now(), changed: make(chan struct{})}
	q.initializeStorageLocked()
	return q
}

func (q *uplinkPacketQueue) initializeStorageLocked() {
	q.globalHead, q.globalTail, q.free = noSlot, noSlot, 0
	q.slots = [uplinkQueueMaxPackets]uplinkPacketSlot{}
	for i := range q.slots {
		q.slots[i].nextFlow = i + 1
	}
	q.slots[len(q.slots)-1].nextFlow = noSlot
	q.flows = [uplinkFlowBuckets]uplinkFlowQueue{}
	for i := range q.flows {
		q.flows[i].head, q.flows[i].tail = noSlot, noSlot
	}
	q.newFlows, q.oldFlows = uplinkFlowList{noSlot, noSlot}, uplinkFlowList{noSlot, noSlot}
}

func (q *uplinkPacketQueue) signalLocked() {
	close(q.changed)
	q.changed = make(chan struct{})
}

func (q *uplinkPacketQueue) resetSchedulerLocked() {
	q.newFlows, q.oldFlows = uplinkFlowList{noSlot, noSlot}, uplinkFlowList{noSlot, noSlot}
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

func (q *uplinkPacketQueue) apply(rateKbps *uint64) {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.limited = rateKbps != nil
	q.parameters = codelParametersForRate(rateKbps)
	q.resetSchedulerLocked()
	q.signalLocked()
}

func (q *uplinkPacketQueue) pause() {
	q.mu.Lock()
	defer q.mu.Unlock()
	if !q.paused {
		q.paused, q.pausedAt = true, q.clock.Now()
		q.resetSchedulerLocked()
		q.signalLocked()
	}
}

func (q *uplinkPacketQueue) resume() {
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

func (q *uplinkPacketQueue) close(err error) {
	if err == nil {
		panic("closing an uplink queue requires an error")
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

func (q *uplinkPacketQueue) enqueue(ctx context.Context, packet []byte) error {
	if len(packet) == 0 || len(packet) > protocolMTU {
		return errInvalidTunPacket
	}
	key, err := classifyUplinkPacket(packet)
	if err != nil {
		return err
	}
	bucket := int(maphash.Bytes(q.seed, key[:]) % uplinkFlowBuckets)
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

func (q *uplinkPacketQueue) hasRoomLocked(size int) bool {
	return q.stats.queuedPackets < len(q.slots) && q.stats.queuedBytes+size <= uplinkQueueMaxBytes
}

// Model the incoming packet at its bucket's tail while selecting the fattest
// bucket. Drop before allocating its slot, so neither hard bound is exceeded
// even transiently. RFC 8290 Section 4.1 specifies half the packet count (at
// least one here for singleton queues), capped at 64, dropped from the head.
func (q *uplinkPacketQueue) makeRoomLocked(incoming, size int) bool {
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

func (q *uplinkPacketQueue) appendPacketLocked(bucket int, packet []byte) {
	index := q.free
	slot, flow := &q.slots[index], &q.flows[bucket]
	q.free = slot.nextFlow
	*slot = uplinkPacketSlot{bucket: bucket, nextFlow: noSlot, previousGlobal: q.globalTail, nextGlobal: noSlot,
		packet: queuedUplinkPacket{size: len(packet), enqueued: q.clock.Now()}}
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

func (q *uplinkPacketQueue) dequeue(ctx context.Context) (queuedUplinkPacket, error) {
	q.mu.Lock()
	defer q.mu.Unlock()
	for {
		if q.err != nil {
			return queuedUplinkPacket{}, q.err
		}
		if err := ctx.Err(); err != nil {
			return queuedUplinkPacket{}, err
		}
		if !q.paused && q.stats.queuedPackets != 0 {
			var packet queuedUplinkPacket
			if q.limited {
				packet = q.dequeueFQLocked(q.clock.Now())
			} else {
				packet = q.popFlowPacketLocked(q.slots[q.globalHead].bucket)
			}
			q.signalLocked()
			return packet, nil
		}
		if err := q.waitLocked(ctx); err != nil {
			return queuedUplinkPacket{}, err
		}
	}
}

// All blocking waits release the queue lock. Profile, lifecycle and capacity
// changes wake both sides; no wake-up depends on another packet arriving.
func (q *uplinkPacketQueue) waitLocked(ctx context.Context) error {
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

func (q *uplinkPacketQueue) popFlowPacketLocked(bucket int) queuedUplinkPacket {
	flow := &q.flows[bucket]
	index := flow.head
	slot := &q.slots[index]
	packet := slot.packet
	flow.head = slot.nextFlow
	if flow.head == noSlot {
		flow.tail = noSlot
	}
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
	*slot = uplinkPacketSlot{nextFlow: q.free}
	q.free = index
	return packet
}

func (q *uplinkPacketQueue) flowListLocked(membership int) *uplinkFlowList {
	if membership == flowNew {
		return &q.newFlows
	}
	return &q.oldFlows
}

func (q *uplinkPacketQueue) appendFlowLocked(bucket, membership int) {
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

func (q *uplinkPacketQueue) removeFlowLocked(bucket int) {
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

func (q *uplinkPacketQueue) dequeueFQLocked(now time.Time) queuedUplinkPacket {
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
		packet, ok := q.dequeueCodelLocked(bucket, now)
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

func (q *uplinkPacketQueue) dequeueCodelLocked(bucket int, now time.Time) (queuedUplinkPacket, bool) {
	flow := &q.flows[bucket]
	if flow.head == noSlot {
		flow.codel.firstAbove, flow.codel.dropping = time.Time{}, false
		return queuedUplinkPacket{}, false
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
				return queuedUplinkPacket{}, false
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
			return queuedUplinkPacket{}, false
		}
		packet = q.popFlowPacketLocked(bucket)
		flow.codel.aboveTarget(now, packet.enqueued, q.stats.queuedBytes, protocolMTU, q.parameters)
		flow.codel.enterDropping(now, q.parameters.interval)
	}
	return packet, true
}

func (q *uplinkPacketQueue) recordCodelDropLocked(size int) {
	q.stats.codelDropPackets++
	q.stats.codelDropBytes += uint64(size)
}

func (q *uplinkPacketQueue) snapshot() uplinkQueueStats {
	q.mu.Lock()
	defer q.mu.Unlock()
	return q.stats
}
