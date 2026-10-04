package trafficshaping

import (
	"context"
	"math"
	"sync"
	"time"
)

const (
	uplinkQueueMaxPackets = 128
	uplinkQueueMaxBytes   = 64 * 1024
)

type queuedUplinkPacket struct {
	data     [protocolMTU]byte
	size     int
	enqueued time.Time
}

// These process-local diagnostics are intentionally not part of the mobile or
// research-export API. No packet identity or payload enters a diagnostic.
type uplinkQueueStats struct {
	queuedPackets, queuedBytes            int
	capacityDropPackets, codelDropPackets uint64
	capacityDropBytes, codelDropBytes     uint64
}

// uplinkPacketQueue keeps kernel TUN reads independent from paced admission.
// Storage is fixed (128 packet slots); payload occupancy also has a 64 KiB cap.
// A limited profile uses CoDel plus a hard capacity tail drop. Unlimited uses
// lossless queue backpressure, with no CoDel or capacity drops.
type uplinkPacketQueue struct {
	mu       sync.Mutex
	clock    monotonicClock
	slots    [uplinkQueueMaxPackets]queuedUplinkPacket
	head     int
	stats    uplinkQueueStats
	limited  bool
	paused   bool
	pausedAt time.Time
	err      error
	changed  chan struct{}
	codel    codelState
}

func newUplinkPacketQueue(clock monotonicClock) *uplinkPacketQueue {
	return &uplinkPacketQueue{clock: clock, paused: true, pausedAt: clock.Now(), changed: make(chan struct{})}
}

func (q *uplinkPacketQueue) signalLocked() {
	close(q.changed)
	q.changed = make(chan struct{})
}

func (q *uplinkPacketQueue) apply(limited bool) {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.limited = limited
	q.codel = codelState{}
	q.signalLocked()
}

func (q *uplinkPacketQueue) pause() {
	q.mu.Lock()
	defer q.mu.Unlock()
	if !q.paused {
		q.paused = true
		q.pausedAt = q.clock.Now()
		q.codel = codelState{}
		q.signalLocked()
	}
}

func (q *uplinkPacketQueue) resume() {
	q.mu.Lock()
	defer q.mu.Unlock()
	if q.paused {
		// Intentional suspension is not network congestion. Preserve queued
		// packets, but exclude the suspended duration from their sojourn time.
		pausedFor := q.clock.Now().Sub(q.pausedAt)
		for i := 0; i < q.stats.queuedPackets; i++ {
			packet := &q.slots[(q.head+i)%len(q.slots)]
			packet.enqueued = packet.enqueued.Add(pausedFor)
		}
		q.paused = false
		q.codel = codelState{}
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
	q.slots = [uplinkQueueMaxPackets]queuedUplinkPacket{}
	q.stats.queuedPackets, q.stats.queuedBytes = 0, 0
	q.codel = codelState{}
	q.signalLocked()
}

func (q *uplinkPacketQueue) enqueue(ctx context.Context, packet []byte) error {
	if len(packet) == 0 || len(packet) > protocolMTU {
		return errInvalidTunPacket
	}
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
			if q.stats.queuedPackets < len(q.slots) && q.stats.queuedBytes+len(packet) <= uplinkQueueMaxBytes {
				slot := &q.slots[(q.head+q.stats.queuedPackets)%len(q.slots)]
				slot.size, slot.enqueued = len(packet), q.clock.Now()
				copy(slot.data[:], packet)
				q.stats.queuedPackets++
				q.stats.queuedBytes += len(packet)
				q.signalLocked()
				return nil
			}
			if q.limited {
				q.stats.capacityDropPackets++
				q.stats.capacityDropBytes += uint64(len(packet))
				return nil
			}
		}
		if err := q.waitLocked(ctx); err != nil {
			return err
		}
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
			packet := q.dequeueLocked(q.clock.Now())
			q.signalLocked()
			return packet, nil
		}
		if err := q.waitLocked(ctx); err != nil {
			return queuedUplinkPacket{}, err
		}
	}
}

// waitLocked releases the queue lock for all blocking waits, including during
// unlimited backpressure. Profile changes, lifecycle transitions and close
// wake both producer and consumer; none depends on a future packet arriving.
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

func (q *uplinkPacketQueue) popLocked() queuedUplinkPacket {
	packet := q.slots[q.head]
	q.slots[q.head] = queuedUplinkPacket{}
	q.head = (q.head + 1) % len(q.slots)
	q.stats.queuedPackets--
	q.stats.queuedBytes -= packet.size
	return packet
}

// dequeueLocked adapts RFC 8289 Section 5.5 (see THIRD_PARTY_NOTICES.md). The MTU
// non-starvation guard ensures that every call still returns one packet.
func (q *uplinkPacketQueue) dequeueLocked(now time.Time) queuedUplinkPacket {
	packet := q.popLocked()
	if !q.limited {
		return packet
	}
	above := q.codel.aboveTarget(now, packet.enqueued, q.stats.queuedBytes, protocolMTU)
	if q.codel.dropping {
		if !above {
			q.codel.dropping = false
		}
		for q.codel.dropping && !now.Before(q.codel.dropNext) {
			q.recordCodelDropLocked(packet.size)
			if q.codel.count != math.MaxUint32 {
				q.codel.count++
			}
			packet = q.popLocked()
			if !q.codel.aboveTarget(now, packet.enqueued, q.stats.queuedBytes, protocolMTU) {
				q.codel.dropping = false
			} else {
				q.codel.dropNext = codelControlLaw(q.codel.dropNext, q.codel.count)
			}
		}
	} else if above {
		q.recordCodelDropLocked(packet.size)
		packet = q.popLocked()
		q.codel.aboveTarget(now, packet.enqueued, q.stats.queuedBytes, protocolMTU)
		q.codel.enterDropping(now)
	}
	return packet
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
