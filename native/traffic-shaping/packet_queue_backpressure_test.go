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

func TestLimitedBackpressurePreservesEveryPacketAcrossCapacityAndLongSojourn(t *testing.T) {
	for _, size := range []int{64, protocolMTU} {
		t.Run(fmt.Sprint(size), func(t *testing.T) {
			clock := newFakeClock()
			q := newPacketQueue(clock, queueBackpressure)
			q.apply(queueTestRate(512))
			q.resume()
			t.Cleanup(func() { q.close(errEngineStopped) })
			count := min(packetQueueMaxPackets, packetQueueMaxBytes/size)
			for i := range count {
				putQueuePacket(t, q, size, byte(i))
			}
			pending := make(chan error, 1)
			go func() { pending <- q.enqueue(context.Background(), queueTestPacket(size, byte(count), 1)) }()
			assertQueueBlocked(t, pending)
			// A profile replacement wakes the producer but cannot select the
			// uplink's capacity-drop policy for this downlink queue.
			q.apply(queueTestRate(64))
			assertQueueBlocked(t, pending)
			for i := 0; i <= count; i++ {
				clock.advance(time.Second)
				if packet := takeQueuePacket(t, q); packet.data[28] != byte(i) || packet.size != size {
					t.Fatalf("packet %d lost, reordered or resized", i)
				}
				if i == 0 {
					if err := awaitQueueResult(t, pending); err != nil {
						t.Fatal(err)
					}
				}
			}
			if got := q.snapshot(); got != (packetQueueStats{}) {
				t.Fatalf("backpressure retained or deliberately dropped traffic: %+v", got)
			}
		})
	}
}

func TestLimitedBackpressureRetainsFQAndUnlimitedArrivalOrder(t *testing.T) {
	clock := newFakeClock()
	q := newPacketQueue(clock, queueBackpressure)
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

func TestLimitedBackpressureCancellationAndCloseWakeWaitingProducer(t *testing.T) {
	for _, closeQueue := range []bool{false, true} {
		t.Run(fmt.Sprint(closeQueue), func(t *testing.T) {
			q := newPacketQueue(newFakeClock(), queueBackpressure)
			q.apply(queueTestRate(512))
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
