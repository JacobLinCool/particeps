package trafficshaping

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"
)

func TestPacketQueueRequiresExplicitCongestionPolicy(t *testing.T) {
	for _, policy := range []queueCongestionPolicy{0, 255} {
		t.Run(fmt.Sprint(policy), func(t *testing.T) {
			defer func() {
				if recover() == nil {
					t.Fatal("unknown policy silently selected a congestion behavior")
				}
			}()
			newPacketQueue(newFakeClock(), policy)
		})
	}
}

func TestLimitedTailDropIncludesIncomingAndNeverWaitsOnCapacity(t *testing.T) {
	for _, size := range []int{64, protocolMTU} {
		t.Run(fmt.Sprint(size), func(t *testing.T) {
			q := newPacketQueue(newFakeClock(), queueFattestTailDrop)
			q.apply(queueTestRate(512))
			q.resume()
			defer q.close(errEngineStopped)
			count := min(packetQueueMaxPackets, q.byteLimit/size)
			for i := range count {
				putQueuePacket(t, q, size, byte(i))
			}
			pending := make(chan error, 1)
			go func() { pending <- q.enqueue(context.Background(), queueTestPacket(size, 255, 1)) }()
			if err := awaitQueueResult(t, pending); err != nil {
				t.Fatal(err)
			}
			if got := q.snapshot(); got.queuedPackets != count || got.queuedBytes != count*size || got.capacityDropPackets != 1 || got.capacityDropBytes != uint64(size) {
				t.Fatalf("virtual incoming tail not discarded at the hard bound: %+v", got)
			}
			for i := range count {
				if packet := takeQueuePacket(t, q); packet.data[28] != byte(i) {
					t.Fatalf("resident arrival %d was replaced by incoming tail", i)
				}
			}
			assertQueueStructure(t, q)
		})
	}
}

func TestLimitedTailDropRemovesMinimumFattestTailsAndPreservesGlobalOrder(t *testing.T) {
	q := newPacketQueue(newFakeClock(), queueFattestTailDrop)
	q.apply(queueTestRate(1_000_000))
	q.resume()
	flows := distinctQueueFlows(t, q, 3)
	for i := range 64 {
		enqueueFlowPacket(t, q, 1000, byte(i), flows[0])
	}
	for i := range 3 {
		enqueueFlowPacket(t, q, 500, byte(i+100), flows[1])
	}
	// 65,500 resident bytes require exactly two 1,000-byte tails to be
	// removed before this distinct 1,500-byte flow fits. No head batch drops.
	enqueueFlowPacket(t, q, 1500, 200, flows[2])
	if got := q.snapshot(); got.queuedPackets != 66 || got.queuedBytes != 65_000 || got.capacityDropPackets != 2 || got.capacityDropBytes != 2000 {
		t.Fatalf("tail admission removed too many or wrong-sized packets: %+v", got)
	}
	assertQueueStructure(t, q)
	q.apply(nil)
	for i := range 62 {
		if got := takeQueuePacket(t, q); packetFlow(got) != flows[0] || got.data[28] != byte(i) {
			t.Fatalf("resident prefix changed at %d", i)
		}
	}
	for i := range 3 {
		if got := takeQueuePacket(t, q); packetFlow(got) != flows[1] || got.data[28] != byte(i+100) {
			t.Fatal("other resident flow changed")
		}
	}
	if got := takeQueuePacket(t, q); packetFlow(got) != flows[2] || got.data[28] != 200 {
		t.Fatal("new flow was lost or globally reordered")
	}
	assertQueueStructure(t, q)
}

func TestLimitedTailDropSlotPressureAdmitsSparseFlow(t *testing.T) {
	q := newPacketQueue(newFakeClock(), queueFattestTailDrop)
	q.apply(queueTestRate(1_000_000))
	q.resume()
	flows := distinctQueueFlows(t, q, 2)
	for i := range packetQueueMaxPackets {
		enqueueFlowPacket(t, q, 64, byte(i), flows[0])
	}
	enqueueFlowPacket(t, q, 64, 255, flows[1])
	if got := q.snapshot(); got.queuedPackets != packetQueueMaxPackets || got.capacityDropPackets != 1 || got.capacityDropBytes != 64 {
		t.Fatalf("slot pressure failed to drop just the fattest tail: %+v", got)
	}
	for i := range (fqQuantum + 63) / 64 {
		if got := takeQueuePacket(t, q); got.data[28] != byte(i) || packetFlow(got) != flows[0] {
			t.Fatal("resident flow prefix or byte quantum changed")
		}
	}
	if got := takeQueuePacket(t, q); got.data[28] != 255 || packetFlow(got) != flows[1] {
		t.Fatal("sparse flow did not enter the existing FQ scheduler")
	}
	assertQueueStructure(t, q)
}

func TestLimitedTailDropRejectsVirtualFattestSingleton(t *testing.T) {
	q := newPacketQueue(newFakeClock(), queueFattestTailDrop)
	q.apply(queueTestRate(1_000_000))
	q.resume()
	flows := distinctQueueFlows(t, q, packetQueueMaxPackets+1)
	for _, flow := range flows[:packetQueueMaxPackets] {
		enqueueFlowPacket(t, q, 64, 1, flow)
	}
	enqueueFlowPacket(t, q, 1500, 2, flows[packetQueueMaxPackets])
	if got := q.snapshot(); got.queuedPackets != 128 || got.queuedBytes != 8192 || got.capacityDropPackets != 1 || got.capacityDropBytes != 1500 {
		t.Fatalf("virtual incoming was excluded from fattest selection: %+v", got)
	}
	assertQueueStructure(t, q)
}

func TestLimitedTailDropRetainsFQAndDoesNotDropForLongSojourn(t *testing.T) {
	clock := newFakeClock()
	q := newPacketQueue(clock, queueFattestTailDrop)
	q.apply(queueTestRate(512))
	q.resume()
	bulk := queueTestPacket(protocolMTU, 1, 1)
	ack := queueTestPacket(64, 9, 2)
	for flow := uint16(3); queueTestBucket(q, bulk) == queueTestBucket(q, ack); flow++ {
		ack = queueTestPacket(64, 9, flow)
	}
	for i := range 3 {
		bulk[28] = byte(i)
		if err := q.enqueue(context.Background(), bulk); err != nil {
			t.Fatal(err)
		}
	}
	if err := q.enqueue(context.Background(), ack); err != nil {
		t.Fatal(err)
	}
	if got := takeQueuePacket(t, q).data[28]; got != 0 {
		t.Fatal("first bulk packet reordered")
	}
	clock.advance(time.Hour)
	if got := takeQueuePacket(t, q).data[28]; got != 9 {
		t.Fatal("sparse flow trapped behind bulk")
	}
	q.apply(nil)
	for _, want := range []byte{1, 2} {
		if got := takeQueuePacket(t, q).data[28]; got != want {
			t.Fatal("remaining arrival order changed")
		}
	}
	if got := q.snapshot(); got != (packetQueueStats{}) {
		t.Fatalf("unexpected occupancy/drop: %+v", got)
	}
}

func TestUnlimitedTailPolicyCancellationAndCloseWakeWaitingProducer(t *testing.T) {
	for _, closeQueue := range []bool{false, true} {
		t.Run(fmt.Sprint(closeQueue), func(t *testing.T) {
			q := newPacketQueue(newFakeClock(), queueFattestTailDrop)
			q.apply(nil)
			q.resume()
			for range packetQueueMaxPackets {
				putQueuePacket(t, q, 64, 1)
			}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			defer q.close(errEngineStopped)
			pending := make(chan error, 1)
			go func() { pending <- q.enqueue(ctx, queueTestPacket(64, 2, 1)) }()
			assertQueueBlocked(t, pending)
			want := context.Canceled
			if closeQueue {
				want = errEngineStopped
				q.close(want)
			} else {
				cancel()
			}
			if err := awaitQueueResult(t, pending); !errors.Is(err, want) {
				t.Fatalf("wake error=%v, want=%v", err, want)
			}
		})
	}
}

func TestUnlimitedTailPolicyWakesFullProducerOnLimitedProfile(t *testing.T) {
	q := newPacketQueue(newFakeClock(), queueFattestTailDrop)
	q.apply(nil)
	q.resume()
	defer q.close(errEngineStopped)
	for range packetQueueMaxPackets {
		putQueuePacket(t, q, 64, 1)
	}
	pending := make(chan error, 1)
	go func() { pending <- q.enqueue(context.Background(), queueTestPacket(64, 2, 1)) }()
	assertQueueBlocked(t, pending)
	q.apply(queueTestRate(512))
	if err := awaitQueueResult(t, pending); err != nil {
		t.Fatal(err)
	}
	if got := q.snapshot(); got.queuedPackets != 100 || got.capacityDropPackets != 29 {
		t.Fatalf("mode change failed to select bounded nonblocking admission: %+v", got)
	}
	assertQueueStructure(t, q)
}

func TestDownlinkQueueByteLimitUsesFixedReactionWindow(t *testing.T) {
	for _, test := range []struct {
		rate  uint64
		bytes int
	}{
		{1, 1500}, {64, 1500}, {120, 1500}, {121, 1512},
		{512, 6400}, {4096, 51200}, {5242, 65525}, {5243, 65536}, {1_000_000, 65536},
	} {
		if got := downlinkQueueByteLimit(test.rate); got != test.bytes {
			t.Fatalf("rate %d: limit=%d want=%d", test.rate, got, test.bytes)
		}
	}
}

func TestDownlinkProfileShrinkTrimsFattestTailsWhilePaused(t *testing.T) {
	q := newPacketQueue(newFakeClock(), queueFattestTailDrop)
	q.apply(nil)
	q.resume()
	flows := distinctQueueFlows(t, q, 2)
	for i := range 10 {
		enqueueFlowPacket(t, q, 1000, byte(i), flows[0])
	}
	enqueueFlowPacket(t, q, 500, 100, flows[1])
	q.pause()
	q.apply(queueTestRate(512))
	if got := q.snapshot(); q.byteLimit != 6400 || got.queuedBytes != 5500 || got.queuedPackets != 6 || got.capacityDropPackets != 5 || got.capacityDropBytes != 5000 || !q.paused {
		t.Fatalf("shrink failed minimal resident-tail trim before resume: %+v", got)
	}
	assertQueueStructure(t, q)
	q.apply(queueTestRate(64))
	if got := q.snapshot(); q.byteLimit != 1500 || got.queuedBytes != 1500 || got.queuedPackets != 2 || got.capacityDropPackets != 9 || got.capacityDropBytes != 9000 {
		t.Fatalf("second shrink broke occupancy or dropped the sparse flow: %+v", got)
	}
	assertQueueStructure(t, q)
	q.apply(nil)
	q.resume()
	if q.byteLimit != packetQueueMaxBytes {
		t.Fatal("unlimited failed to restore hard byte capacity")
	}
	for _, want := range []byte{0, 100} {
		if got := takeQueuePacket(t, q); got.data[28] != want {
			t.Fatal("profile shrink or unlimited transition reordered retained arrivals")
		}
	}
	assertQueueStructure(t, q)
}

func TestUplinkProfileChangesKeepOriginalCapacity(t *testing.T) {
	q, _ := readyUplinkQueue(false)
	for i := range 40 {
		putQueuePacket(t, q, 1500, byte(i))
	}
	q.apply(queueTestRate(64))
	if got := q.snapshot(); q.byteLimit != packetQueueMaxBytes || got.queuedBytes != 60_000 || got.capacityDropPackets != 0 {
		t.Fatalf("downlink delay policy changed uplink capacity: %+v", got)
	}
	assertQueueStructure(t, q)
}
