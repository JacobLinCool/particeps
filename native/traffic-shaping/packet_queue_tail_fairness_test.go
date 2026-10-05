package trafficshaping

import (
	"fmt"
	"testing"
	"time"
)

func newTailFairnessQueue(t *testing.T, rate uint64) *packetQueue {
	t.Helper()
	q := newPacketQueue(newFakeClock(), queueFattestTailDrop)
	q.apply(queueTestRate(rate))
	q.resume()
	t.Cleanup(func() { q.close(errEngineStopped) })
	return q
}

func tailFlowOccupancy(q *packetQueue, flow uint16) int {
	return q.flows[queueTestBucket(q, queueTestPacket(64, 0, flow))].packets
}

func TestLimitedTailDropEveryLateBucketEntersAndReceivesServiceDespiteResidentRefill(t *testing.T) {
	// Fresh salts exercise different hash positions; each synthetic flow takes
	// the late-entrant role. No test depends on the numeric bucket ordering.
	for seed := range 16 {
		for late := range 5 {
			t.Run(fmt.Sprintf("salt%d/late%d", seed, late), func(t *testing.T) {
				q := newTailFairnessQueue(t, 500)
				flows := distinctQueueFlows(t, q, 5)
				for index, flow := range flows {
					if index != late {
						enqueueFlowPacket(t, q, 1500, byte(index), flow)
					}
				}
				enqueueFlowPacket(t, q, 1500, byte(late), flows[late])
				if tailFlowOccupancy(q, flows[late]) != 1 {
					t.Fatal("equal resident backlogs excluded late entrant")
				}
				if got := q.snapshot(); got.capacityDropPackets != 1 || got.queuedBytes != 6000 {
					t.Fatalf("admission did not replace exactly one resident: %+v", got)
				}
				served := false
				for range 4 {
					packet := takeQueuePacket(t, q)
					if packetFlow(packet) == flows[late] {
						served = true
						break
					}
					// Recreate the original counterexample's hot refill order.
					enqueueFlowPacket(t, q, 1500, packet.data[28], packetFlow(packet))
					enqueueFlowPacket(t, q, 1500, byte(late), flows[late])
					assertQueueStructure(t, q)
				}
				if !served {
					t.Fatal("admitted late entrant did not receive an FQ turn")
				}
				assertQueueStructure(t, q)
			})
		}
	}
}

func TestLimitedTailDropVictimsRotateAcrossEveryHashPosition(t *testing.T) {
	for seed := range 16 {
		t.Run(fmt.Sprint(seed), func(t *testing.T) {
			q := newTailFairnessQueue(t, 500)
			flows := distinctQueueFlows(t, q, 5)
			for _, flow := range flows[:4] {
				enqueueFlowPacket(t, q, 1500, 1, flow)
			}
			incoming := flows[4]
			victims := make(map[uint16]int)
			for range 25 {
				enqueueFlowPacket(t, q, 1500, 1, incoming)
				if tailFlowOccupancy(q, incoming) != 1 {
					t.Fatal("incoming singleton lost an equal-largest tie")
				}
				missing := 0
				for _, flow := range flows {
					if tailFlowOccupancy(q, flow) == 0 {
						victims[flow]++
						incoming = flow
						missing++
					}
				}
				if missing != 1 {
					t.Fatal("replacement did not retain exactly four singletons")
				}
				assertQueueStructure(t, q)
			}
			for _, flow := range flows {
				if victims[flow] < 4 || victims[flow] > 6 {
					t.Fatal("fixed hash position systematically selected or spared a tied victim")
				}
			}
		})
	}
}

func TestLimitedTailDropStrictlyHigherQuantumIncomingStillDropsItsOwnTail(t *testing.T) {
	q := newTailFairnessQueue(t, 64)
	flows := distinctQueueFlows(t, q, 2)
	enqueueFlowPacket(t, q, 1000, 1, flows[0])
	enqueueFlowPacket(t, q, 500, 2, flows[1])
	// The incoming bucket would occupy two quanta; the other occupies one.
	enqueueFlowPacket(t, q, 1500, 3, flows[0])
	if got := q.snapshot(); got.queuedBytes != 1500 || got.capacityDropPackets != 1 || got.capacityDropBytes != 1500 || tailFlowOccupancy(q, flows[0]) != 1 {
		t.Fatalf("strict highest-quantum policy changed: %+v", got)
	}
	q.apply(nil)
	for _, id := range []byte{1, 2} {
		if takeQueuePacket(t, q).data[28] != id {
			t.Fatal("resident order changed")
		}
	}
	assertQueueStructure(t, q)
}

func TestLimitedTailDropSameBucketAdmissionPreservesQueuedPrefix(t *testing.T) {
	q := newTailFairnessQueue(t, 500)
	flows := distinctQueueFlows(t, q, 1)
	for i := range 4 {
		enqueueFlowPacket(t, q, 1500, byte(i), flows[0])
	}
	enqueueFlowPacket(t, q, 1500, 99, flows[0])
	if got := q.snapshot(); got.capacityDropPackets != 1 || got.queuedBytes != 6000 {
		t.Fatalf("virtual same-bucket tail was not dropped: %+v", got)
	}
	q.apply(nil)
	for i := range 4 {
		if takeQueuePacket(t, q).data[28] != byte(i) {
			t.Fatal("same-bucket prefix was evicted or reordered")
		}
	}
	assertQueueStructure(t, q)
}

func TestLimitedTailDropUnequalBacklogsDropOneFattestResidentTail(t *testing.T) {
	q := newTailFairnessQueue(t, 512)
	flows := distinctQueueFlows(t, q, 3)
	for index, flow := range flows[:2] {
		for seq := range 3 {
			enqueueFlowPacket(t, q, 1000, byte(index*10+seq), flow)
		}
	}
	enqueueFlowPacket(t, q, 1000, 99, flows[2])
	if got := q.snapshot(); got.queuedBytes != 6000 || got.capacityDropPackets != 1 || got.capacityDropBytes != 1000 || tailFlowOccupancy(q, flows[2]) != 1 {
		t.Fatalf("minimal fattest-tail replacement changed: %+v", got)
	}
	if !((tailFlowOccupancy(q, flows[0]) == 2 && tailFlowOccupancy(q, flows[1]) == 3) || (tailFlowOccupancy(q, flows[0]) == 3 && tailFlowOccupancy(q, flows[1]) == 2)) {
		t.Fatal("capacity drop did not remove one resident tail")
	}
	q.apply(nil)
	previous, count := -1, 0
	for q.snapshot().queuedPackets > 0 {
		packet := takeQueuePacket(t, q)
		id := int(packet.data[28])
		if id <= previous || (id != 99 && id%10 > 2) {
			t.Fatal("remaining global/flow order changed")
		}
		previous = id
		count++
	}
	if count != 6 {
		t.Fatal("wrong retained packet count")
	}
	assertQueueStructure(t, q)
}

func TestLimitedTailDropSustainedMixedArrivalsKeepAllStorageAndModeInvariants(t *testing.T) {
	for _, count := range []int{5, 17, 129} {
		t.Run(fmt.Sprint(count), func(t *testing.T) {
			q := newTailFairnessQueue(t, 500)
			flows := distinctQueueFlows(t, q, count)
			for round := range 600 {
				size := []int{64, 500, 1500}[round%3]
				enqueueFlowPacket(t, q, size, byte(round), flows[round%len(flows)])
				if round%3 == 0 && q.snapshot().queuedPackets > 0 {
					takeQueuePacket(t, q)
				}
				if round%37 == 0 {
					q.pause()
					q.apply(queueTestRate(64))
					q.apply(nil)
					q.apply(queueTestRate(500))
					q.resume()
				}
				if q.tailDropCursor < 0 || q.tailDropCursor >= flowBuckets {
					t.Fatal("tie cursor escaped fixed buckets")
				}
				assertQueueStructure(t, q)
			}
		})
	}
}

// Repeated resident refills must not permanently exclude a fifth equal-size
// bucket. Fake time specifies service order, not TCP or end-to-end timing.
func TestLimitedTailDropDoesNotPermanentlyExcludeNewFlow(t *testing.T) {
	const rounds = 640
	clock := newFakeClock()
	q := newPacketQueue(clock, queueFattestTailDrop)
	q.apply(queueTestRate(500))
	q.resume()
	defer q.close(errEngineStopped)
	if q.byteLimit != 6250 {
		t.Fatalf("unexpected bound: %d", q.byteLimit)
	}
	// Resolve five distinct buckets under this queue's random salt. The proof
	// does not depend on their numeric identities or a hash collision.
	flows := distinctQueueFlows(t, q, 5)
	for _, flow := range flows[:4] {
		enqueueFlowPacket(t, q, 1500, 1, flow)
	}
	fifthBucket := queueTestBucket(q, queueTestPacket(1500, 2, flows[4]))
	admitted := false
	attemptFifth := func() {
		enqueueFlowPacket(t, q, 1500, 2, flows[4])
		admitted = admitted || q.flows[fifthBucket].packets != 0
		got := q.snapshot()
		if got.queuedBytes > 6250 || got.queuedPackets > packetQueueMaxPackets {
			t.Fatalf("hard occupancy bound exceeded: %+v", got)
		}
		assertQueueStructure(t, q)
	}
	attemptFifth()
	served := [5]int{}
	for round := 0; round < rounds && !admitted; round++ {
		packet := takeQueuePacket(t, q)
		found := false
		for index, flow := range flows {
			if packetFlow(packet) == flow {
				served[index]++
				found = true
				break
			}
		}
		if !found || packet.size != 1500 {
			t.Fatal("unexpected packet delivery")
		}
		// One L3 MTU is 24 ms at 500 kbps. Fake time only specifies an
		// admissible arrival/service schedule; no wall-clock wait is used.
		clock.advance(24 * time.Millisecond)
		enqueueFlowPacket(t, q, 1500, 1, packetFlow(packet))
		attemptFifth()
	}
	got := q.snapshot()
	t.Logf("max_rounds=%d admitted_fifth=%t service_counts=%v occupancy_packets=%d occupancy_bytes=%d capacity_drops=%d codel_drops=%d", rounds, admitted, served, got.queuedPackets, got.queuedBytes, got.capacityDropPackets, got.codelDropPackets)
	if !admitted {
		for index, count := range served[:4] {
			if count == 0 {
				t.Fatalf("resident %d did not make progress; wrong service schedule", index)
			}
		}
		if got.capacityDropPackets != rounds+1 || got.codelDropPackets != 0 {
			t.Fatalf("unexpected loss mechanism: %+v", got)
		}
		t.Fatal("fifth bucket never admitted despite continuous resident service and 641 admission attempts")
	}
}

// Eighty independently salted / initial-DRR-phase schedules require actual
// dequeue of the larger newcomer, not merely transient admission.
func TestLimitedTailDropQuantizedUnequalSingletonReceivesService(t *testing.T) {
	for salt := range 16 {
		for phase := range 5 {
			t.Run(fmt.Sprintf("salt%d/phase%d", salt, phase), func(t *testing.T) {
				q := newTailFairnessQueue(t, 500)
				flows := distinctQueueFlows(t, q, 5)
				late := phase
				for i, f := range flows {
					if i != late {
						enqueueFlowPacket(t, q, 1450, byte(i), f)
					}
				}
				for range phase {
					p := takeQueuePacket(t, q)
					enqueueFlowPacket(t, q, 1450, p.data[28], packetFlow(p))
				}
				enqueueFlowPacket(t, q, 1500, 99, flows[late])
				served := false
				for range 8 {
					p := takeQueuePacket(t, q)
					if packetFlow(p) == flows[late] {
						if p.size != 1500 || p.data[28] != 99 {
							t.Fatal("newcomer packet changed")
						}
						served = true
						break
					}
					enqueueFlowPacket(t, q, 1450, p.data[28], packetFlow(p))
					// Repeated attempts may lose their virtual tails, but must not evict the
					// already queued prefix in the same bucket.
					enqueueFlowPacket(t, q, 1500, 100, flows[late])
					assertQueueStructure(t, q)
				}
				if !served {
					t.Fatal("larger singleton received no DRR service in eight turns")
				}
				assertQueueStructure(t, q)
			})
		}
	}
}

func TestLimitedTailDropQuantizedMixedPacketSizesReceiveActualService(t *testing.T) {
	for salt := range 16 {
		for phase := range 5 {
			t.Run(fmt.Sprintf("salt%d/phase%d", salt, phase), func(t *testing.T) {
				q := newTailFairnessQueue(t, 500)
				flows := distinctQueueFlows(t, q, 5)
				sizes := []int{64, 1400, 1450, 1472}
				for range 23 {
					enqueueFlowPacket(t, q, 64, 1, flows[0])
				}
				for i := 1; i < 4; i++ {
					enqueueFlowPacket(t, q, sizes[i], byte(i+1), flows[i])
				}
				for range phase {
					p := takeQueuePacket(t, q)
					enqueueFlowPacket(t, q, p.size, p.data[28], packetFlow(p))
				}
				enqueueFlowPacket(t, q, 1500, 99, flows[4])
				served := false
				// Byte DRR may serve multiple 64B packets within one quantum. This bound
				// counts dequeue operations, not a claim about TCP or elapsed time.
				for range 2 * packetQueueMaxPackets {
					p := takeQueuePacket(t, q)
					if packetFlow(p) == flows[4] {
						if p.data[28] != 99 {
							t.Fatal("newcomer prefix changed")
						}
						served = true
						break
					}
					enqueueFlowPacket(t, q, p.size, p.data[28], packetFlow(p))
					enqueueFlowPacket(t, q, 1500, 100, flows[4])
					assertQueueStructure(t, q)
				}
				if !served {
					t.Fatal("newcomer did not receive mixed-size DRR service")
				}
				assertQueueStructure(t, q)
			})
		}
	}
}

func TestLimitedTailDropQuantizedSmallSingletonCostIsExplicitAndBounded(t *testing.T) {
	q := newTailFairnessQueue(t, 500)
	flows := distinctQueueFlows(t, q, 98)
	for _, f := range flows[:97] {
		enqueueFlowPacket(t, q, 64, 1, f)
	}
	enqueueFlowPacket(t, q, 1500, 99, flows[97])
	got := q.snapshot()
	// All 98 singleton buckets have score 1. Admitting the large packet needs
	// at least 23 small packets of space. This is a cost, not ACK-priority proof.
	if got.queuedBytes != 6236 || got.queuedPackets != 75 || got.capacityDropPackets != 23 || got.capacityDropBytes != 1472 || tailFlowOccupancy(q, flows[97]) != 1 {
		t.Fatalf("unexpected bounded small-packet cost: %+v", got)
	}
	assertQueueStructure(t, q)
	served := false
	for range 75 {
		p := takeQueuePacket(t, q)
		if packetFlow(p) == flows[97] {
			served = true
			break
		}
	}
	if !served {
		t.Fatal("newcomer admission did not turn into service without refills")
	}
	assertQueueStructure(t, q)
}
