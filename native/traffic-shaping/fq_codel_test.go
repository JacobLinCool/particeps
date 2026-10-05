package trafficshaping

import (
	"context"
	"encoding/binary"
	"fmt"
	"math/rand"
	"reflect"
	"testing"
	"time"
)

func distinctQueueFlows(t *testing.T, q *packetQueue, count int) []uint16 {
	t.Helper()
	result := make([]uint16, 0, count)
	used := make(map[int]bool)
	for candidate := 1; candidate <= 65535 && len(result) < count; candidate++ {
		bucket := queueTestBucket(q, queueTestPacket(64, 0, uint16(candidate)))
		if !used[bucket] {
			result = append(result, uint16(candidate))
			used[bucket] = true
		}
	}
	if len(result) != count {
		t.Fatal("could not construct distinct test buckets")
	}
	return result
}

func enqueueFlowPacket(t *testing.T, q *packetQueue, size int, id byte, flow uint16) {
	t.Helper()
	if err := q.enqueue(context.Background(), queueTestPacket(size, id, flow)); err != nil {
		t.Fatal(err)
	}
}

func packetFlow(packet queuedPacket) uint16 { return binary.BigEndian.Uint16(packet.data[20:22]) }

func TestFQDeficitSchedulesBytesRatherThanPacketCounts(t *testing.T) {
	q, _ := readyUplinkQueue(true)
	flows := distinctQueueFlows(t, q, 2)
	for i := range 20 {
		enqueueFlowPacket(t, q, 1500, byte(i), flows[0])
	}
	for i := range 60 {
		enqueueFlowPacket(t, q, 500, byte(i), flows[1])
	}
	totals := map[uint16]int{}
	packets := map[uint16]int{}
	for range 10 {
		for range 4 {
			packet := takeQueuePacket(t, q)
			totals[packetFlow(packet)] += packet.size
			packets[packetFlow(packet)]++
		}
		if totals[flows[0]] != totals[flows[1]] {
			t.Fatalf("unequal packet sizes skewed byte service: %v", totals)
		}
	}
	if packets[flows[0]] != 10 || packets[flows[1]] != 30 {
		t.Fatal("DRR did not use byte deficit")
	}
	assertQueueStructure(t, q)
}

func TestFQEmptyNewQueueMustVisitOldListBeforeReactivation(t *testing.T) {
	q, _ := readyUplinkQueue(true)
	flows := distinctQueueFlows(t, q, 2)
	for i := range 20 {
		enqueueFlowPacket(t, q, 1500, byte(i), flows[0])
	}
	enqueueFlowPacket(t, q, 64, 1, flows[1])
	if packetFlow(takeQueuePacket(t, q)) != flows[0] || packetFlow(takeQueuePacket(t, q)) != flows[1] {
		t.Fatal("new queues not visited")
	}
	if packetFlow(takeQueuePacket(t, q)) != flows[0] {
		t.Fatal("backlogged flow was skipped")
	}
	sparseBucket := queueTestBucket(q, queueTestPacket(64, 0, flows[1]))
	if q.flows[sparseBucket].membership != flowOld {
		t.Fatal("empty new queue removed before its old-list turn")
	}
	for i := range 8 {
		enqueueFlowPacket(t, q, 64, byte(i+2), flows[1])
		if i == 0 && q.flows[sparseBucket].membership != flowOld {
			t.Fatal("sparse arrival regained new priority before its old-list visit")
		}
		a, b := takeQueuePacket(t, q), takeQueuePacket(t, q)
		if packetFlow(a) != flows[1] || packetFlow(b) != flows[0] {
			t.Fatal("sparse arrivals starved the bulk queue")
		}

	}
	assertQueueStructure(t, q)
}

func TestFQCapacityDropsHalfOfFattestByteBacklogFromHead(t *testing.T) {
	q, _ := readyUplinkQueue(true)
	flows := distinctQueueFlows(t, q, 3)
	for i := range 40 {
		enqueueFlowPacket(t, q, 1500, byte(i), flows[0])
	}
	for i := range 80 {
		enqueueFlowPacket(t, q, 64, byte(i), flows[1])
	}
	enqueueFlowPacket(t, q, 1500, 99, flows[2])
	stats := q.snapshot()
	if stats.capacityDropPackets != 20 || stats.capacityDropBytes != 30_000 || stats.queuedBytes != 36_620 || stats.queuedPackets != 101 {
		t.Fatalf("capacity selected packet count or arriving flow instead of fattest bytes: %+v", stats)
	}
	fattest := &q.flows[queueTestBucket(q, queueTestPacket(64, 0, flows[0]))]
	if q.slots[fattest.head].packet.data[28] != 20 {
		t.Fatal("capacity did not drop oldest packets")
	}
	if q.flows[queueTestBucket(q, queueTestPacket(64, 0, flows[1]))].packets != 80 {
		t.Fatal("unrelated small flow lost packets")
	}
	assertQueueStructure(t, q)
}

func TestFQCapacityIncludesIncomingPacketWithoutExceedingPool(t *testing.T) {
	q, _ := readyUplinkQueue(true)
	flows := distinctQueueFlows(t, q, 129)
	for _, flow := range flows[:128] {
		enqueueFlowPacket(t, q, 64, 1, flow)
	}
	// Each resident bucket contains just 64 bytes. The virtual incoming 1500
	// bytes form the fattest singleton, so it is discarded without a slot.
	enqueueFlowPacket(t, q, 1500, 2, flows[128])
	stats := q.snapshot()
	if stats.queuedPackets != 128 || stats.queuedBytes != 8192 || stats.capacityDropPackets != 1 || stats.capacityDropBytes != 1500 {
		t.Fatalf("virtual incoming was not included in overflow selection: %+v", stats)
	}
	assertQueueStructure(t, q)
}

func TestFQPerBucketCodelCanEmptyOneBucketButGlobalMTUGuardPreservesService(t *testing.T) {
	q, clock := readyUplinkQueue(true)
	flows := distinctQueueFlows(t, q, 2)
	enqueueFlowPacket(t, q, 1500, 1, flows[0])
	for i := range 3 {
		enqueueFlowPacket(t, q, 1500, byte(i+10), flows[1])
	}
	clock.advance(time.Second)
	bucket := queueTestBucket(q, queueTestPacket(64, 0, flows[0]))
	q.flows[bucket].codel.firstAbove = clock.Now().Add(-time.Millisecond)
	got := takeQueuePacket(t, q)
	if packetFlow(got) != flows[1] || got.data[28] != 10 || q.snapshot().codelDropPackets != 1 {
		t.Fatal("CoDel did not safely drain an individual bucket and continue scheduling")
	}
	// The remaining two MTUs must both survive, regardless of their age.
	clock.advance(time.Hour)
	takeQueuePacket(t, q)
	takeQueuePacket(t, q)
	if q.snapshot().codelDropPackets != 1 {
		t.Fatal("aggregate MTU guard was replaced by per-flow dropping")
	}
	assertQueueStructure(t, q)
}

func TestFQModesKeepGlobalUnlimitedFIFOAndPerFlowOrder(t *testing.T) {
	q, _ := readyUplinkQueue(true)
	flows := distinctQueueFlows(t, q, 2)
	for id := byte(0); id < 6; id++ {
		enqueueFlowPacket(t, q, 1500, id, flows[id%2])
	}
	if takeQueuePacket(t, q).data[28] != 0 || takeQueuePacket(t, q).data[28] != 1 {
		t.Fatal("unexpected initial round")
	}
	q.apply(nil)
	for id := byte(2); id < 6; id++ {
		if takeQueuePacket(t, q).data[28] != id {
			t.Fatal("unlimited did not restore surviving arrival FIFO")
		}
	}
	for id := byte(6); id < 12; id++ {
		enqueueFlowPacket(t, q, 1500, id, flows[id%2])
	}
	q.pause()
	q.apply(queueTestRate(64))
	q.resume()
	last := map[uint16]byte{flows[0]: 4, flows[1]: 5}
	for range 6 {
		packet := takeQueuePacket(t, q)
		flow := packetFlow(packet)
		if packet.data[28] != last[flow]+2 {
			t.Fatal("mode or rate change reordered a flow")
		}
		last[flow] = packet.data[28]
	}
	if q.snapshot().capacityDropPackets != 0 || q.snapshot().codelDropPackets != 0 {
		t.Fatal("uncongested mode change discarded packets")
	}
	assertQueueStructure(t, q)
}

func TestFQHashCollisionSharesFIFOWithoutAliasingOrUnboundedState(t *testing.T) {
	q, _ := readyUplinkQueue(true)
	first := uint16(1)
	bucket := queueTestBucket(q, queueTestPacket(64, 0, first))
	var collision uint16
	for candidate := 2; candidate <= 65535; candidate++ {
		if queueTestBucket(q, queueTestPacket(64, 0, uint16(candidate))) == bucket {
			collision = uint16(candidate)
			break
		}
	}
	if collision == 0 {
		t.Fatal("no collision found in bounded fixture search")
	}
	for i := range 100 {
		enqueueFlowPacket(t, q, 64, byte(i), []uint16{first, collision}[i%2])
	}
	for i := range 100 {
		packet := takeQueuePacket(t, q)
		if packet.data[28] != byte(i) {
			t.Fatal("colliding flows lost shared FIFO order")
		}
	}
	if reflect.TypeOf(q).Elem().Size() > 512*1024 {
		t.Fatal("fixed queue allocation exceeds 512 KiB")
	}
	assertQueueStructure(t, q)
}

func TestFQLowRateParametersAndProfileReset(t *testing.T) {
	for _, tc := range []struct {
		rate             uint64
		target, interval time.Duration
	}{
		{64, 187500 * time.Microsecond, 100 * time.Millisecond},
		{512, 23437500 * time.Nanosecond, 100 * time.Millisecond},
		{4096, 5 * time.Millisecond, 100 * time.Millisecond},
	} {
		q, clock := readyUplinkQueue(true)
		q.apply(&tc.rate)
		if q.parameters.target != tc.target || q.parameters.interval != tc.interval {
			t.Fatalf("rate %d parameters=%+v", tc.rate, q.parameters)
		}
		for i := range 6 {
			putQueuePacket(t, q, 1500, byte(i))
		}
		clock.advance(tc.target)
		takeQueuePacket(t, q)
		clock.advance(tc.interval - time.Nanosecond)
		takeQueuePacket(t, q)
		if q.snapshot().codelDropPackets != 0 {
			t.Fatal("CoDel reacted before a low-rate interval")
		}
		clock.advance(time.Nanosecond)
		takeQueuePacket(t, q)
		if q.snapshot().codelDropPackets != 1 {
			t.Fatal("persistent low-rate queue did not signal congestion")
		}
		q.pause()
		q.apply(queueTestRate(4096))
		q.resume()
		for i := range q.flows {
			if !q.flows[i].codel.firstAbove.IsZero() || q.flows[i].codel.dropping {
				t.Fatal("profile retained old CoDel history")
			}
		}
		assertQueueStructure(t, q)
	}
}

func TestFQPoolAndListsRemainBoundedAcrossReuseDropsAndModeChanges(t *testing.T) {
	for _, policy := range []queueCongestionPolicy{queueActiveManagement, queueFattestTailDrop} {
		t.Run(fmt.Sprint(policy), func(t *testing.T) {
			clock := newFakeClock()
			q := newPacketQueue(clock, policy)
			q.apply(queueTestRate(512))
			q.resume()
			random := rand.New(rand.NewSource(483901))
			for step := range 6000 {
				switch random.Intn(10) {
				case 0:
					q.pause()
					clock.advance(time.Hour)
					q.resume()
				case 1:
					if q.limited {
						q.apply(nil)
					} else {
						q.apply(queueTestRate([]uint64{64, 512, 4096}[random.Intn(3)]))
					}
				case 2, 3, 4:
					if q.snapshot().queuedPackets > 0 {
						takeQueuePacket(t, q)
					}
				default:
					size := 64 + random.Intn(1437)
					if q.limited || q.hasRoomLocked(size) {
						enqueueFlowPacket(t, q, size, byte(step), uint16(1+random.Intn(2000)))
					}
				}
				clock.advance(time.Duration(random.Intn(200)) * time.Millisecond)
				assertQueueStructure(t, q)
			}
			q.close(errEngineStopped)
			assertQueueStructure(t, q)
		})
	}
}

func assertQueueStructure(t *testing.T, q *packetQueue) {
	t.Helper()
	q.mu.Lock()
	defer q.mu.Unlock()
	var location [packetQueueMaxPackets]uint8
	var rank [packetQueueMaxPackets]int
	count, bytesTotal, previous := 0, 0, noSlot
	for slot := q.globalHead; slot != noSlot; slot = q.slots[slot].nextGlobal {
		if slot < 0 || slot >= len(q.slots) || location[slot] != 0 {
			t.Fatal("global list cycle or invalid slot")
		}
		location[slot] = 1
		rank[slot] = count
		p := &q.slots[slot]
		if p.previousGlobal != previous || p.packet.size <= 0 || p.packet.size > protocolMTU {
			t.Fatal("broken global link or packet bounds")
		}
		previous = slot
		count++
		bytesTotal += p.packet.size
	}
	if previous != q.globalTail || count != q.stats.queuedPackets || bytesTotal != q.stats.queuedBytes || count > 128 || bytesTotal > 65536 || bytesTotal > q.byteLimit {
		t.Fatal("global occupancy mismatch")
	}
	freeCount := 0
	for slot := q.free; slot != noSlot; slot = q.slots[slot].nextFlow {
		if slot < 0 || slot >= len(q.slots) || location[slot] != 0 {
			t.Fatal("free list aliases a queued slot or has a cycle")
		}
		location[slot] = 2
		freeCount++
		if q.slots[slot].packet.size != 0 {
			t.Fatal("free slot retains a packet")
		}
	}
	if freeCount+count != len(q.slots) {
		t.Fatal("lost packet slot")
	}
	var inFlow [packetQueueMaxPackets]bool
	var inList [flowBuckets]bool
	for _, membership := range []int{flowNew, flowOld} {
		list := q.flowListLocked(membership)
		previous = noSlot
		for bucket := list.head; bucket != noSlot; bucket = q.flows[bucket].next {
			if bucket < 0 || bucket >= len(q.flows) || inList[bucket] {
				t.Fatal("scheduler list invalid or cyclic")
			}
			inList[bucket] = true
			if q.flows[bucket].previous != previous || q.flows[bucket].membership != membership {
				t.Fatal("scheduler links disagree")
			}
			previous = bucket
		}
		if previous != list.tail {
			t.Fatal("scheduler tail mismatch")
		}
	}
	for bucket := range q.flows {
		f := &q.flows[bucket]
		count, bytesTotal, lastRank, tail := 0, 0, -1, noSlot
		for slot := f.head; slot != noSlot; slot = q.slots[slot].nextFlow {
			if slot < 0 || slot >= len(q.slots) || location[slot] != 1 || inFlow[slot] {
				t.Fatal("flow list aliases slots")
			}
			inFlow[slot] = true
			if q.slots[slot].bucket != bucket || rank[slot] <= lastRank {
				t.Fatal("per-flow order differs from global order")
			}
			lastRank = rank[slot]
			tail = slot
			count++
			bytesTotal += q.slots[slot].packet.size
		}
		if tail != f.tail || count != f.packets || bytesTotal != f.bytes || (count > 0 && !inList[bucket]) || (inList[bucket] != (f.membership != flowInactive)) {
			t.Fatal("flow occupancy/membership mismatch")
		}
	}
	for slot := range q.slots {
		if (location[slot] == 1) != inFlow[slot] {
			t.Fatal("global/flow ownership mismatch")
		}
	}
}
