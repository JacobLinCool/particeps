package trafficshaping

import (
	"bytes"
	"context"
	"io"
	"net/netip"
	"sync"
	"testing"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/core/device/iobased"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

type controlledDownlinkWait struct {
	duration time.Duration
	proceed  chan struct{}
}

type controlledDownlinkWaiter struct{ waits chan controlledDownlinkWait }

func (w *controlledDownlinkWaiter) Wait(ctx context.Context, duration time.Duration, wake <-chan struct{}) error {
	request := controlledDownlinkWait{duration: duration, proceed: make(chan struct{})}
	select {
	case w.waits <- request:
	case <-ctx.Done():
		return ctx.Err()
	}
	select {
	case <-request.proceed:
		return nil
	case <-wake:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

type downlinkHOLDevice struct {
	closed chan struct{}
	writes chan []byte
	once   sync.Once
}

func (d *downlinkHOLDevice) Read([]byte) (int, error) { <-d.closed; return 0, io.EOF }
func (d *downlinkHOLDevice) Write(packet []byte) (int, error) {
	select {
	case d.writes <- append([]byte{}, packet...):
		return len(packet), nil
	case <-d.closed:
		return 0, io.ErrClosedPipe
	}
}
func (d *downlinkHOLDevice) Close() error { d.once.Do(func() { close(d.closed) }); return nil }

type noInboundDispatcher struct{}

func (*noInboundDispatcher) DeliverNetworkPacket(tcpip.NetworkProtocolNumber, *stack.PacketBuffer) {}
func (*noInboundDispatcher) DeliverLinkPacket(tcpip.NetworkProtocolNumber, *stack.PacketBuffer)    {}

// This is a reproduction of the pinned lower-layer FIFO, not a claim that the
// Android failure had this exact occupancy. Packet generation and time are
// controlled; the actual iobased channel/outboundLoop and shapedTun.Write run.
func TestPinnedDownlinkFIFOBlocksUploadACKBehindDownloadData(t *testing.T) {
	const precedingDataPackets = 256
	var isolated, backlogged time.Duration
	t.Run("isolated_ack", func(t *testing.T) { isolated = measureDownlinkACKDelay(t, 0) })
	t.Run("ack_behind_download_data", func(t *testing.T) { backlogged = measureDownlinkACKDelay(t, precedingDataPackets) })
	const wantACKSerialization = 625 * time.Microsecond // 40 L3 bytes at 512 kbps.
	const wantAddedDelay = 6 * time.Second              // 256 * 1500 L3 bytes at 512 kbps.
	if isolated != wantACKSerialization || backlogged-isolated != wantAddedDelay {
		t.Fatalf("ACK delay isolated=%s, backlogged=%s; preceding DATA must account for exactly %s", isolated, backlogged, wantAddedDelay)
	}
}

func measureDownlinkACKDelay(t *testing.T, dataPackets int) time.Duration {
	t.Helper()
	clock := newFakeClock()
	waiter := &controlledDownlinkWaiter{waits: make(chan controlledDownlinkWait, 1)}
	device := &downlinkHOLDevice{closed: make(chan struct{}), writes: make(chan []byte, 1)}
	ctx, cancel := context.WithCancel(context.Background())
	uplink := newDirectionLimiter(protocolMTU, clock, &advancingWaiter{clock: clock})
	downlink := newDirectionLimiter(protocolMTU, clock, waiter)
	uplink.apply(nil)
	downlink.apply(queueTestRate(512))
	if err := uplink.resume(); err != nil {
		t.Fatal(err)
	}
	if err := downlink.resume(); err != nil {
		t.Fatal(err)
	}
	drainInitialCredit(t, downlink)
	queue := newPacketQueue(clock)
	queue.apply(nil)
	queue.resume()
	shaped := &shapedTun{ctx: ctx, device: &ownedTun{device: device}, mtu: protocolMTU,
		uplink: uplink, downlink: downlink, counters: &aggregateCounters{}, gate: &sync.RWMutex{},
		queue: queue, readerDone: make(chan struct{}), stopping: func() bool { return ctx.Err() != nil },
		fail: func(code string) { t.Errorf("unexpected terminal failure: %s", code) }}
	endpoint, err := iobased.New(shaped, protocolMTU, 0)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		cancel()
		uplink.close()
		downlink.close()
		queue.close(errEngineStopped)
		_ = shaped.device.Close()
		endpoint.Close()
		done := make(chan struct{})
		go func() { endpoint.Wait(); shaped.waitReader(); close(done) }()
		select {
		case <-done:
		case <-time.After(time.Second):
			t.Error("downlink endpoint/pump failed to join")
		}
	})
	download := makeTCPTestPacket(netip.MustParseAddr("198.18.0.2"), netip.MustParseAddr("10.111.222.1"), 1, 1, testTCPAck|testTCPPsh, make([]byte, 1460))
	ack := makeTCPTestPacket(netip.MustParseAddr("198.18.0.3"), netip.MustParseAddr("10.111.222.2"), 1, 65536, testTCPAck, nil)
	first := ack
	if dataPackets > 0 {
		first = download
	}
	enqueuePinnedPacket(t, endpoint, first)
	started := clock.Now()
	endpoint.Attach(&noInboundDispatcher{})
	request := nextDownlinkWait(t, waiter)
	if endpoint.NumQueued() != 0 {
		t.Fatal("first packet was not dequeued into the blocked paced writer")
	}
	if dataPackets > 0 {
		for range dataPackets - 1 {
			enqueuePinnedPacket(t, endpoint, download)
		}
		enqueuePinnedPacket(t, endpoint, ack)
		// NumQueued excludes the DATA currently held by shapedTun.Write.
		if endpoint.NumQueued() != dataPackets {
			t.Fatal("unexpected pinned FIFO occupancy")
		}
	}
	select {
	case <-device.writes:
		t.Fatal("empty credit allowed delivery before a permit")
	default:
	}
	for index := 0; index <= dataPackets; index++ {
		expected := download
		if index == dataPackets {
			expected = ack
		}
		expectedWait := time.Duration(len(expected)*8) * time.Second / 512000
		if request.duration != expectedWait {
			t.Fatalf("packet %d wait=%s, want=%s", index, request.duration, expectedWait)
		}
		clock.advance(request.duration)
		close(request.proceed)
		select {
		case packet := <-device.writes:
			if !bytes.Equal(packet, expected) {
				t.Fatal("ACK bypassed preceding FIFO DATA or a packet was altered")
			}
		case <-time.After(time.Second):
			t.Fatal("released packet did not reach the TUN")
		}
		if index < dataPackets {
			request = nextDownlinkWait(t, waiter)
			if endpoint.NumQueued() != dataPackets-index-1 {
				t.Fatal("FIFO queue did not drain one packet at a time")
			}
		}
	}
	if endpoint.NumQueued() != 0 {
		t.Fatal("completed FIFO retained packets")
	}
	return clock.Now().Sub(started)
}

func enqueuePinnedPacket(t *testing.T, endpoint *iobased.Endpoint, data []byte) {
	t.Helper()
	packet := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(append([]byte{}, data...))})
	defer packet.DecRef() // The channel clones its own reference on WritePackets.
	var packets stack.PacketBufferList
	packets.PushBack(packet)
	if count, err := endpoint.WritePackets(packets); err != nil || count != 1 {
		t.Fatalf("pinned enqueue=%d,%v", count, err)
	}
}

func nextDownlinkWait(t *testing.T, waiter *controlledDownlinkWaiter) controlledDownlinkWait {
	t.Helper()
	select {
	case request := <-waiter.waits:
		return request
	case <-time.After(time.Second):
		t.Fatal("paced downlink did not reach its controlled wait")
		return controlledDownlinkWait{}
	}
}
