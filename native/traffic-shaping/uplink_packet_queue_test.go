package trafficshaping

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"
)

func readyUplinkQueue(limited bool) (*uplinkPacketQueue, *fakeClock) {
	clock := newFakeClock()
	queue := newUplinkPacketQueue(clock)
	queue.apply(limited)
	queue.resume()
	return queue, clock
}

func putQueuePacket(t *testing.T, queue *uplinkPacketQueue, size int, id byte) {
	t.Helper()
	packet := make([]byte, size)
	packet[0] = id
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
				packet := make([]byte, size)
				packet[0] = byte(id)
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
		if stats.queuedPackets != want || stats.queuedBytes != want*size ||
			stats.capacityDropPackets != uint64(200-want) || stats.codelDropPackets != 0 {
			t.Fatalf("size %d: aggregate bounds/accounting = %+v", size, stats)
		}
		if stats.capacityDropBytes != stats.capacityDropPackets*uint64(size) {
			t.Fatal("capacity drop bytes do not match the discarded packets")
		}
	}
}

func TestUplinkQueueOwnsPacketBytesAndPreservesFIFOWithoutCongestion(t *testing.T) {
	queue, _ := readyUplinkQueue(true)
	packet := []byte{1, 2, 3}
	if err := queue.enqueue(context.Background(), packet); err != nil {
		t.Fatal(err)
	}
	packet[0] = 9
	putQueuePacket(t, queue, 3, 4)
	if first, second := takeQueuePacket(t, queue), takeQueuePacket(t, queue); first.size != 3 || first.data[0] != 1 || second.data[0] != 4 {
		t.Fatal("queued packet bytes were aliased, reordered or resized")
	}
	if queue.snapshot().queuedBytes != 0 {
		t.Fatal("drained queue retained payload occupancy")
	}
}

func TestUnlimitedQueueBackpressureWakesOnCapacityAndLimitedProfile(t *testing.T) {
	queue, _ := readyUplinkQueue(false)
	for i := range uplinkQueueMaxPackets {
		putQueuePacket(t, queue, 1, byte(i))
	}
	result := make(chan error, 1)
	go func() { result <- queue.enqueue(context.Background(), []byte{200}) }()
	assertQueueBlocked(t, result)
	takeQueuePacket(t, queue)
	if err := awaitQueueResult(t, result); err != nil {
		t.Fatal(err)
	}
	if queue.snapshot().capacityDropPackets != 0 {
		t.Fatal("unlimited backpressure dropped a packet")
	}
	go func() { result <- queue.enqueue(context.Background(), []byte{201}) }()
	assertQueueBlocked(t, result)
	queue.apply(true)
	if err := awaitQueueResult(t, result); err != nil {
		t.Fatal(err)
	}
	if stats := queue.snapshot(); stats.capacityDropPackets != 1 || stats.queuedPackets != uplinkQueueMaxPackets {
		t.Fatalf("profile switch did not wake the full queue producer: %+v", stats)
	}
}

func TestCodelRequiresPersistentDelayThenCatchesUpWithoutStarving(t *testing.T) {
	queue, clock := readyUplinkQueue(true)
	for i := range 20 {
		putQueuePacket(t, queue, protocolMTU, byte(i))
	}
	clock.advance(codelTarget)
	if got := takeQueuePacket(t, queue).data[0]; got != 0 {
		t.Fatal("initial burst was dropped")
	}
	clock.advance(codelInterval - time.Nanosecond)
	if got := takeQueuePacket(t, queue).data[0]; got != 1 {
		t.Fatal("CoDel dropped before a full above-target interval")
	}
	clock.advance(time.Nanosecond)
	if got := takeQueuePacket(t, queue).data[0]; got != 3 {
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
	if queue.codel.dropping || !queue.codel.firstAbove.IsZero() {
		t.Fatal("empty queue retained persistent congestion state")
	}
}

func TestCodelRetainsOneMTUBacklogAt64KbpsSerializationTime(t *testing.T) {
	queue, clock := readyUplinkQueue(true)
	for i := range 3 {
		putQueuePacket(t, queue, protocolMTU, byte(i))
	}
	clock.advance(187_500 * time.Microsecond)
	takeQueuePacket(t, queue)
	clock.advance(187_500 * time.Microsecond)
	if got := takeQueuePacket(t, queue).data[0]; got != 1 {
		t.Fatalf("low-rate non-starvation guard dropped packet %d", got)
	}
	if stats := queue.snapshot(); stats.codelDropPackets != 0 || stats.queuedBytes != protocolMTU {
		t.Fatalf("one-MTU guard failed: %+v", stats)
	}
}

func TestCodelRecentReentryReusesDropRateAndOldSchedule(t *testing.T) {
	now := newFakeClock().Now()
	if codelControlLaw(now, 1).Sub(now) != 100*time.Millisecond ||
		codelControlLaw(now, 4).Sub(now) != 50*time.Millisecond {
		t.Fatal("CoDel does not use interval divided by square root of count")
	}
	state := codelState{count: 10, lastCount: 3, dropNext: now.Add(-time.Second)}
	state.enterDropping(now)
	if state.count != 7 || state.lastCount != 7 || !state.dropNext.Equal(codelControlLaw(now, 7)) {
		t.Fatal("recent reentry discarded its previous effective drop rate")
	}
	state.dropNext = now.Add(-2 * time.Second)
	state.count, state.lastCount = 20, 7
	state.enterDropping(now)
	if state.count != 1 {
		t.Fatal("old congestion history was reused")
	}
}

func TestSuspensionExcludesPausedTimeAndHoldsArrivingPackets(t *testing.T) {
	queue, clock := readyUplinkQueue(true)
	putQueuePacket(t, queue, 10, 1)
	clock.advance(time.Millisecond)
	queue.pause()
	result := make(chan error, 1)
	go func() { result <- queue.enqueue(context.Background(), []byte{2}) }()
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
	queue.apply(false)
	for i := 1; i < 10; i++ {
		if got := takeQueuePacket(t, queue).data[0]; got != byte(i) {
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
				putQueuePacket(t, queue, 1, 0)
			}
			go func() { result <- queue.enqueue(context.Background(), []byte{1}) }()
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
