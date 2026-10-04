package trafficshaping

import (
	"context"
	"encoding/binary"
	"errors"
	"hash/maphash"
	"sync"
	"testing"
	"time"
)

func readyUplinkQueue(limited bool) (*uplinkPacketQueue, *fakeClock) {
	clock := newFakeClock()
	queue := newUplinkPacketQueue(clock)
	var rate *uint64
	if limited {
		rate = queueTestRate(4096)
	}
	queue.apply(rate)
	queue.resume()
	return queue, clock
}

func putQueuePacket(t *testing.T, queue *uplinkPacketQueue, size int, id byte) {
	t.Helper()
	packet := queueTestPacket(size, id, 1)
	if err := queue.enqueue(context.Background(), packet); err != nil {
		t.Fatal(err)
	}
}

func takeQueuePacket(t *testing.T, queue *uplinkPacketQueue) queuedUplinkPacket {
	t.Helper()
	packet, err := queue.dequeue(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	return packet
}

func TestUplinkQueueEnforcesBothBoundsAcrossConcurrentSources(t *testing.T) {
	for _, size := range []int{64, protocolMTU} {
		queue, _ := readyUplinkQueue(true)
		var senders sync.WaitGroup
		for source := range 2 {
			senders.Add(1)
			go func(id int) {
				defer senders.Done()
				packet := queueTestPacket(size, byte(id), uint16(id+1))
				for range 100 {
					if err := queue.enqueue(context.Background(), packet); err != nil {
						t.Error(err)
					}
				}
			}(source)
		}
		senders.Wait()
		stats := queue.snapshot()
		want := min(uplinkQueueMaxPackets, uplinkQueueMaxBytes/size)
		if stats.queuedPackets <= 0 || stats.queuedPackets > want || stats.queuedBytes != stats.queuedPackets*size ||
			stats.capacityDropPackets != uint64(200-stats.queuedPackets) || stats.codelDropPackets != 0 {
			t.Fatalf("size %d: aggregate bounds/accounting = %+v", size, stats)
		}
		if stats.capacityDropBytes != stats.capacityDropPackets*uint64(size) {
			t.Fatal("capacity drop bytes do not match the discarded packets")
		}
	}
}

func TestUplinkQueueOwnsPacketBytesAndPreservesFIFOWithoutCongestion(t *testing.T) {
	queue, _ := readyUplinkQueue(true)
	packet := queueTestPacket(64, 1, 1)
	if err := queue.enqueue(context.Background(), packet); err != nil {
		t.Fatal(err)
	}
	packet[28] = 9
	putQueuePacket(t, queue, 64, 4)
	if first, second := takeQueuePacket(t, queue), takeQueuePacket(t, queue); first.size != 64 || first.data[28] != 1 || second.data[28] != 4 {
		t.Fatal("queued packet bytes were aliased, reordered or resized")
	}
	if queue.snapshot().queuedBytes != 0 {
		t.Fatal("drained queue retained payload occupancy")
	}
}

func TestUnlimitedQueueBackpressureWakesOnCapacityAndLimitedProfile(t *testing.T) {
	queue, _ := readyUplinkQueue(false)
	for i := range uplinkQueueMaxPackets {
		putQueuePacket(t, queue, 64, byte(i))
	}
	result := make(chan error, 1)
	go func() { result <- queue.enqueue(context.Background(), queueTestPacket(64, 200, 1)) }()
	assertQueueBlocked(t, result)
	takeQueuePacket(t, queue)
	if err := awaitQueueResult(t, result); err != nil {
		t.Fatal(err)
	}
	if queue.snapshot().capacityDropPackets != 0 {
		t.Fatal("unlimited backpressure dropped a packet")
	}
	go func() { result <- queue.enqueue(context.Background(), queueTestPacket(64, 201, 1)) }()
	assertQueueBlocked(t, result)
	queue.apply(queueTestRate(4096))
	if err := awaitQueueResult(t, result); err != nil {
		t.Fatal(err)
	}
	if stats := queue.snapshot(); stats.capacityDropPackets == 0 || stats.queuedPackets+int(stats.capacityDropPackets) != uplinkQueueMaxPackets+1 {
		t.Fatalf("profile switch did not wake the full queue producer: %+v", stats)
	}
}

func TestCodelRequiresPersistentDelayThenCatchesUpWithoutStarving(t *testing.T) {
	queue, clock := readyUplinkQueue(true)
	for i := range 20 {
		putQueuePacket(t, queue, protocolMTU, byte(i))
	}
	clock.advance(codelTarget)
	if got := takeQueuePacket(t, queue).data[28]; got != 0 {
		t.Fatal("initial burst was dropped")
	}
	clock.advance(codelInterval - time.Nanosecond)
	if got := takeQueuePacket(t, queue).data[28]; got != 1 {
		t.Fatal("CoDel dropped before a full above-target interval")
	}
	clock.advance(time.Nanosecond)
	if got := takeQueuePacket(t, queue).data[28]; got != 3 {
		t.Fatalf("expected one initial congestion drop, got packet %d", got)
	}
	clock.advance(5 * codelInterval)
	takeQueuePacket(t, queue)
	if stats := queue.snapshot(); stats.codelDropPackets < 3 || stats.queuedBytes < protocolMTU {
		t.Fatalf("CoDel catch-up or MTU guard failed: %+v", stats)
	}
	for queue.snapshot().queuedPackets != 0 {
		clock.advance(time.Second)
		takeQueuePacket(t, queue)
	}
	state := queue.flows[queueTestBucket(queue, queueTestPacket(64, 0, 1))].codel
	if state.dropping || !state.firstAbove.IsZero() {
		t.Fatal("empty queue retained persistent congestion state")
	}
}

func TestCodelRetainsOneMTUBacklogAt64KbpsSerializationTime(t *testing.T) {
	queue, clock := readyUplinkQueue(true)
	queue.apply(queueTestRate(64))
	for i := range 3 {
		putQueuePacket(t, queue, protocolMTU, byte(i))
	}
	clock.advance(187_500 * time.Microsecond)
	takeQueuePacket(t, queue)
	clock.advance(187_500 * time.Microsecond)
	if got := takeQueuePacket(t, queue).data[28]; got != 1 {
		t.Fatalf("low-rate non-starvation guard dropped packet %d", got)
	}
	if stats := queue.snapshot(); stats.codelDropPackets != 0 || stats.queuedBytes != protocolMTU {
		t.Fatalf("one-MTU guard failed: %+v", stats)
	}
}

func TestCodelRecentReentryReusesDropRateAndOldSchedule(t *testing.T) {
	now := newFakeClock().Now()
	if codelControlLaw(now, 1, codelInterval).Sub(now) != 100*time.Millisecond ||
		codelControlLaw(now, 4, codelInterval).Sub(now) != 50*time.Millisecond {
		t.Fatal("CoDel does not use interval divided by square root of count")
	}
	state := codelState{count: 10, lastCount: 3, dropNext: now.Add(-time.Second)}
	state.enterDropping(now, codelInterval)
	if state.count != 7 || state.lastCount != 7 || !state.dropNext.Equal(codelControlLaw(now, 7, codelInterval)) {
		t.Fatal("recent reentry discarded its previous effective drop rate")
	}
	state.dropNext = now.Add(-2 * time.Second)
	state.count, state.lastCount = 20, 7
	state.enterDropping(now, codelInterval)
	if state.count != 1 {
		t.Fatal("old congestion history was reused")
	}
}

func TestSuspensionExcludesPausedTimeAndHoldsArrivingPackets(t *testing.T) {
	queue, clock := readyUplinkQueue(true)
	putQueuePacket(t, queue, 64, 1)
	clock.advance(time.Millisecond)
	queue.pause()
	result := make(chan error, 1)
	go func() { result <- queue.enqueue(context.Background(), queueTestPacket(64, 2, 1)) }()
	assertQueueBlocked(t, result)
	clock.advance(time.Hour)
	if stats := queue.snapshot(); stats.queuedPackets != 1 || stats.capacityDropPackets != 0 {
		t.Fatalf("paused queue accepted or dropped an arriving packet: %+v", stats)
	}
	queue.resume()
	if err := awaitQueueResult(t, result); err != nil {
		t.Fatal(err)
	}
	old, arrived := takeQueuePacket(t, queue), takeQueuePacket(t, queue)
	if age := clock.Now().Sub(old.enqueued); age != time.Millisecond {
		t.Fatalf("old packet age includes suspension: %s", age)
	}
	if age := clock.Now().Sub(arrived.enqueued); age != 0 {
		t.Fatalf("packet held outside paused queue has invalid age: %s", age)
	}
}

func TestUnlimitedDisablesCodelWithoutLosingQueuedPackets(t *testing.T) {
	queue, clock := readyUplinkQueue(true)
	for i := range 10 {
		putQueuePacket(t, queue, protocolMTU, byte(i))
	}
	clock.advance(time.Second)
	takeQueuePacket(t, queue)
	clock.advance(time.Second)
	queue.apply(nil)
	for i := 1; i < 10; i++ {
		if got := takeQueuePacket(t, queue).data[28]; got != byte(i) {
			t.Fatal("unlimited transition lost a queued packet")
		}
	}
	if queue.snapshot().codelDropPackets != 0 {
		t.Fatal("unlimited applied a congestion drop")
	}
}

func TestQueueCloseWakesEmptyConsumerAndFullUnlimitedProducer(t *testing.T) {
	for _, full := range []bool{false, true} {
		queue, _ := readyUplinkQueue(false)
		result := make(chan error, 1)
		if full {
			for range uplinkQueueMaxPackets {
				putQueuePacket(t, queue, 64, 0)
			}
			go func() { result <- queue.enqueue(context.Background(), queueTestPacket(64, 1, 1)) }()
		} else {
			go func() { _, err := queue.dequeue(context.Background()); result <- err }()
		}
		assertQueueBlocked(t, result)
		queue.close(errEngineStopped)
		if err := awaitQueueResult(t, result); !errors.Is(err, errEngineStopped) {
			t.Fatalf("close error = %v", err)
		}
		if stats := queue.snapshot(); stats.queuedPackets != 0 || stats.queuedBytes != 0 {
			t.Fatalf("closed queue retained packet storage: %+v", stats)
		}
	}
}

func assertQueueBlocked(t *testing.T, result <-chan error) {
	t.Helper()
	select {
	case err := <-result:
		t.Fatalf("queue operation should remain blocked, returned %v", err)
	case <-time.After(10 * time.Millisecond):
	}
}

func awaitQueueResult(t *testing.T, result <-chan error) error {
	t.Helper()
	select {
	case err := <-result:
		return err
	case <-time.After(time.Second):
		t.Fatal("queue operation did not wake")
		return nil
	}
}

func queueTestRate(rate uint64) *uint64 { return &rate }

func queueTestPacket(size int, id byte, flow uint16) []byte {
	packet := make([]byte, size)
	packet[0], packet[8], packet[9] = 0x45, 64, 17
	binary.BigEndian.PutUint16(packet[2:4], uint16(size))
	copy(packet[12:16], []byte{10, 0, 0, 1})
	copy(packet[16:20], []byte{10, 0, 0, 2})
	binary.BigEndian.PutUint16(packet[20:22], flow)
	binary.BigEndian.PutUint16(packet[22:24], 1234)
	binary.BigEndian.PutUint16(packet[24:26], uint16(size-20))
	packet[28] = id
	return packet
}

func queueTestBucket(q *uplinkPacketQueue, packet []byte) int {
	key, err := classifyUplinkPacket(packet)
	if err != nil {
		panic(err)
	}
	return int(maphash.Bytes(q.seed, key[:]) % uplinkFlowBuckets)
}
