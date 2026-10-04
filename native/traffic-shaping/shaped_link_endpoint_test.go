package trafficshaping

import (
	"bytes"
	"context"
	"fmt"
	"net/netip"
	"sync"
	"testing"
	"time"

	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

type shapedLinkFixture struct {
	endpoint *shapedLinkEndpoint
	clock    *fakeClock
	waiter   *controlledDownlinkWaiter
	device   *downlinkHOLDevice
	cancel   context.CancelFunc
	failures chan string
}

func newShapedLinkFixture(t *testing.T, rate *uint64) *shapedLinkFixture {
	t.Helper()
	clock := newFakeClock()
	waiter := &controlledDownlinkWaiter{waits: make(chan controlledDownlinkWait, 8)}
	device := &downlinkHOLDevice{closed: make(chan struct{}), writes: make(chan []byte, 1)}
	ctx, cancel := context.WithCancel(context.Background())
	up, down := newDirectionLimiter(protocolMTU, clock, waiter), newDirectionLimiter(protocolMTU, clock, waiter)
	up.apply(nil)
	down.apply(rate)
	if err := up.resume(); err != nil {
		t.Fatal(err)
	}
	if err := down.resume(); err != nil {
		t.Fatal(err)
	}
	uq, dq := newPacketQueue(clock), newPacketQueue(clock)
	uq.apply(nil)
	uq.resume()
	dq.apply(rate)
	dq.resume()
	failures := make(chan string, 8)
	shaped := &shapedTun{ctx: ctx, device: &ownedTun{device: device}, mtu: protocolMTU,
		uplink: up, downlink: down, counters: &aggregateCounters{}, gate: &sync.RWMutex{}, queue: uq,
		readerDone: make(chan struct{}), stopping: func() bool { return ctx.Err() != nil },
		fail: func(code string) { failures <- code; cancel() }}
	fixture := &shapedLinkFixture{newShapedLinkEndpoint(shaped, dq), clock, waiter, device, cancel, failures}
	t.Cleanup(func() { cancel(); fixture.endpoint.Close(); waitLinkStopped(t, fixture.endpoint) })
	return fixture
}

func waitLinkStopped(t *testing.T, endpoint *shapedLinkEndpoint) {
	t.Helper()
	done := make(chan struct{})
	go func() { endpoint.Wait(); endpoint.shaped.waitReader(); close(done) }()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("link workers or TUN pump did not join")
	}
}

func writeLinkPacket(t *testing.T, endpoint *shapedLinkEndpoint, data []byte) {
	t.Helper()
	packet := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(append([]byte{}, data...))})
	defer packet.DecRef()
	var packets stack.PacketBufferList
	packets.PushBack(packet)
	if count, err := endpoint.WritePackets(packets); count != 1 || err != nil {
		t.Fatalf("link enqueue=%d,%v", count, err)
	}
	// The caller's reference remains valid; the link owns a fixed byte copy.
	original := packet.ToBuffer()
	defer original.Release()
	if !bytes.Equal(original.Flatten(), data) {
		t.Fatal("link mutated or released caller-owned packet")
	}
}

func nextLinkWrite(t *testing.T, fixture *shapedLinkFixture) []byte {
	t.Helper()
	select {
	case p := <-fixture.device.writes:
		return p
	case <-time.After(time.Second):
		t.Fatal("no TUN write")
		return nil
	}
}

func releaseLinkWait(t *testing.T, f *shapedLinkFixture, request controlledDownlinkWait) []byte {
	t.Helper()
	f.clock.advance(request.duration)
	close(request.proceed)
	return nextLinkWrite(t, f)
}

func TestShapedLinkACKBypassesBackloggedDownloadFlow(t *testing.T) {
	f := newShapedLinkFixture(t, queueTestRate(512))
	drainInitialCredit(t, f.endpoint.shaped.downlink)
	data := makeTCPTestPacket(netip.MustParseAddr("198.18.0.2"), netip.MustParseAddr("10.111.222.1"), 1, 1, testTCPAck|testTCPPsh, make([]byte, 1460))
	var ack []byte
	for i := 3; i < 255; i++ { // Avoid making a probabilistic fairness claim across a hash collision.
		ack = makeTCPTestPacket(netip.AddrFrom4([4]byte{198, 18, 0, byte(i)}), netip.MustParseAddr("10.111.222.2"), 1, 65536, testTCPAck, nil)
		if queueTestBucket(f.endpoint.queue, data) != queueTestBucket(f.endpoint.queue, ack) {
			break
		}
	}
	if queueTestBucket(f.endpoint.queue, data) == queueTestBucket(f.endpoint.queue, ack) {
		t.Fatal("no distinct test buckets")
	}
	writeLinkPacket(t, f.endpoint, data)
	started := f.clock.Now()
	f.endpoint.Attach(&noInboundDispatcher{})
	first := nextDownlinkWait(t, f.waiter)
	for range 40 {
		writeLinkPacket(t, f.endpoint, data)
	}
	writeLinkPacket(t, f.endpoint, ack)
	if got := f.endpoint.queue.snapshot(); got.queuedPackets != 41 || got.capacityDropPackets != 0 {
		t.Fatalf("unexpected backlog: %+v", got)
	}
	if !bytes.Equal(releaseLinkWait(t, f, first), data) {
		t.Fatal("inflight DATA changed")
	}
	second := nextDownlinkWait(t, f.waiter)
	if second.duration != 625*time.Microsecond {
		t.Fatalf("ACK must follow the single inflight DATA, wait=%s", second.duration)
	}
	if !bytes.Equal(releaseLinkWait(t, f, second), ack) {
		t.Fatal("sparse ACK trapped behind bulk download")
	}
	if delay := f.clock.Now().Sub(started); delay != 24062500*time.Nanosecond {
		t.Fatalf("ACK delay=%s", delay)
	}
	_ = nextDownlinkWait(t, f.waiter) // The ACK Write returned and recorded its admission.
	if got := f.endpoint.shaped.counters.downlinkBytes.Load(); got != 1540 {
		t.Fatalf("admitted bytes=%d", got)
	}
	if f.endpoint.NumQueued() != 0 {
		t.Fatal("embedded FIFO was used")
	}
}

func TestShapedLinkUnlimitedBackpressureAndPausedProducerClose(t *testing.T) {
	for _, paused := range []bool{false, true} {
		t.Run(fmt.Sprint(paused), func(t *testing.T) {
			f := newShapedLinkFixture(t, nil)
			data := queueTestPacket(1500, 1, 1)
			if paused {
				f.endpoint.queue.pause()
			} else {
				for range packetQueueMaxBytes / 1500 {
					writeLinkPacket(t, f.endpoint, data)
				}
			}
			done := make(chan struct{})
			go func() {
				defer close(done)
				packet := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(data)})
				defer packet.DecRef()
				var list stack.PacketBufferList
				list.PushBack(packet)
				n, err := f.endpoint.WritePackets(list)
				if n != 0 || err == nil {
					t.Errorf("closed blocked enqueue=%d,%v", n, err)
				}
			}()
			select {
			case <-done:
				t.Fatal("producer bypassed full/paused queue")
			case <-time.After(10 * time.Millisecond):
			}
			f.cancel()
			f.endpoint.Close()
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatal("Close blocked behind producer")
			}
			waitLinkStopped(t, f.endpoint)
		})
	}
}

func TestShapedLinkHeldPacketUsesReplacementProfileAfterPause(t *testing.T) {
	f := newShapedLinkFixture(t, queueTestRate(64))
	drainInitialCredit(t, f.endpoint.shaped.downlink)
	data := queueTestPacket(1500, 1, 1)
	writeLinkPacket(t, f.endpoint, data)
	writeLinkPacket(t, f.endpoint, queueTestPacket(1500, 2, 1))
	f.endpoint.Attach(&noInboundDispatcher{})
	_ = nextDownlinkWait(t, f.waiter)
	f.endpoint.shaped.downlink.suspend()
	f.endpoint.queue.pause()
	f.endpoint.shaped.gate.Lock()
	f.endpoint.shaped.gate.Unlock()
	f.clock.advance(time.Hour)
	f.endpoint.shaped.downlink.apply(nil)
	f.endpoint.queue.apply(nil)
	if f.endpoint.shaped.counters.downlinkPackets.Load() != 0 {
		t.Fatal("paused/old permit admitted a packet")
	}
	select {
	case <-f.device.writes:
		t.Fatal("paused delivery")
	default:
	}
	if err := f.endpoint.shaped.downlink.resume(); err != nil {
		t.Fatal(err)
	}
	f.endpoint.queue.resume()
	if !bytes.Equal(nextLinkWrite(t, f), data) {
		t.Fatal("held packet not first after unlimited transition")
	}
	if got := nextLinkWrite(t, f); got[28] != 2 {
		t.Fatal("queued packet order changed")
	}
}

func TestShapedLinkRejectsOversizeAfterAcceptedPrefixWithoutTakingReferences(t *testing.T) {
	f := newShapedLinkFixture(t, nil)
	good := queueTestPacket(64, 7, 1)
	packets := []*stack.PacketBuffer{
		stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(good)}),
		stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(make([]byte, protocolMTU+1))}),
	}
	var list stack.PacketBufferList
	for _, p := range packets {
		list.PushBack(p)
		defer p.DecRef()
	}
	if n, err := f.endpoint.WritePackets(list); n != 1 || err == nil {
		t.Fatalf("invalid batch accepted=%d,%v", n, err)
	}
	select {
	case code := <-f.failures:
		if code != TerminalInvalidTunPacket {
			t.Fatal(code)
		}
	case <-time.After(time.Second):
		t.Fatal("invalid packet not terminal")
	}
	for _, p := range packets {
		if p.Size() == 0 {
			t.Fatal("caller reference was released")
		}
	}
	if f.endpoint.shaped.counters.downlinkPackets.Load() != 0 {
		t.Fatal("enqueued prefix counted as admitted")
	}
}

func TestShapedLinkDetachClosesBeforeWaitAndCannotRestart(t *testing.T) {
	f := newShapedLinkFixture(t, nil)
	f.endpoint.Wait() // Before Attach must return, without creating workers.
	f.endpoint.Attach(&noInboundDispatcher{})
	f.endpoint.Attach(nil)
	waitLinkStopped(t, f.endpoint)
	f.endpoint.Attach(&noInboundDispatcher{})
	if f.endpoint.IsAttached() {
		t.Fatal("closed endpoint reattached")
	}
	select {
	case code := <-f.failures:
		if code != TerminalNativeStackFailure {
			t.Fatal(code)
		}
	case <-time.After(time.Second):
		t.Fatal("unexpected detach failed to close engine admission")
	}
}

func TestShapedLinkCloseBeforeAttachAndConcurrentWait(t *testing.T) {
	for range 30 {
		f := newShapedLinkFixture(t, nil)
		f.cancel()
		var wg sync.WaitGroup
		wg.Add(3)
		go func() { defer wg.Done(); f.endpoint.Attach(&noInboundDispatcher{}) }()
		go func() { defer wg.Done(); f.endpoint.Wait() }()
		go func() { defer wg.Done(); f.endpoint.Close() }()
		wg.Wait()
		waitLinkStopped(t, f.endpoint)
		f.endpoint.Attach(&noInboundDispatcher{})
		if f.endpoint.IsAttached() {
			t.Fatal("closed endpoint reattached")
		}
	}
}

func TestEngineSuspendedStartupAndStopBeforeResumeWithBothQueues(t *testing.T) {
	for _, rate := range []string{"null", "64", "512", "4096"} {
		t.Run(rate, func(t *testing.T) {
			fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
			if err != nil {
				t.Fatal(err)
			}
			defer unix.Close(fds[1])
			engine, err := CreateEngine(int64(fds[0]), protocolMTU, &recordingProtector{allow: true}, newTerminalRecorder())
			if err != nil {
				t.Fatal(err)
			}
			defer engine.Stop()
			if _, err := engine.ApplyProfile([]byte(fmt.Sprintf(`{"downlink_kbps":%s,"id":"startup","uplink_kbps":%s}`, rate, rate))); err != nil {
				t.Fatal(err)
			}
			started := make(chan error, 1)
			go func() { started <- engine.Start() }()
			select {
			case err := <-started:
				if err != nil {
					t.Fatal(err)
				}
			case <-time.After(time.Second):
				t.Fatal("suspended CreateStack blocked on downlink control packet")
			}
			if !engine.IsHealthy() || !engine.IsSuspended() {
				t.Fatal("startup opened admission")
			}
			stopped := make(chan struct{})
			go func() { engine.Stop(); close(stopped) }()
			select {
			case <-stopped:
			case <-time.After(time.Second):
				t.Fatal("suspended stack failed to join")
			}
		})
	}
}

func TestShapedLinkLimitedOverflowAcceptsBoundedDropsWithoutCountingAdmission(t *testing.T) {
	f := newShapedLinkFixture(t, queueTestRate(512))
	for i := range 200 {
		writeLinkPacket(t, f.endpoint, queueTestPacket(1500, byte(i), 1))
	}
	stats := f.endpoint.queue.snapshot()
	if stats.capacityDropPackets == 0 || stats.queuedPackets > packetQueueMaxPackets || stats.queuedBytes > packetQueueMaxBytes {
		t.Fatalf("limited queue failed to apply bounded congestion policy: %+v", stats)
	}
	if f.endpoint.shaped.counters.downlinkBytes.Load() != 0 || f.endpoint.shaped.counters.downlinkPackets.Load() != 0 {
		t.Fatal("queue acceptance/drop was counted as Layer-3 admission")
	}
	if f.endpoint.NumQueued() != 0 {
		t.Fatal("packet escaped into the embedded channel FIFO")
	}
	assertQueueStructure(t, f.endpoint.queue)
}

func TestShapedLinkCloseWakesAllSerializedProducers(t *testing.T) {
	f := newShapedLinkFixture(t, nil)
	f.endpoint.queue.pause()
	var producers sync.WaitGroup
	for range 8 {
		producers.Add(1)
		go func() {
			defer producers.Done()
			packet := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(queueTestPacket(1500, 1, 1))})
			defer packet.DecRef()
			var list stack.PacketBufferList
			list.PushBack(packet)
			if n, err := f.endpoint.WritePackets(list); n != 0 || err == nil {
				t.Errorf("closed serialized producer=%d,%v", n, err)
			}
		}()
	}
	f.cancel()
	f.endpoint.Close()
	done := make(chan struct{})
	go func() { producers.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("producer serialization prevented close from waking every caller")
	}
}
