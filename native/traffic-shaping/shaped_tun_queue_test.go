package trafficshaping

import (
	"context"
	"errors"
	"fmt"
	"io"
	"sync"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

type packetChannelTun struct {
	packets chan []byte
	entered chan struct{}
	closed  chan struct{}
	once    sync.Once
}

func (d *packetChannelTun) Read(buffer []byte) (int, error) {
	select {
	case d.entered <- struct{}{}:
	default:
	}
	select {
	case <-d.closed:
		return 0, io.EOF
	case packet := <-d.packets:
		return copy(buffer, packet), nil
	}
}

func (d *packetChannelTun) Write(packet []byte) (int, error) { return len(packet), nil }
func (d *packetChannelTun) Close() error {
	d.once.Do(func() { close(d.closed) })
	return nil
}

func queuedTunFixture(t *testing.T, rate *uint64, clock monotonicClock, waiter interruptibleWaiter) (*shapedTun, *packetChannelTun, func()) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	device := &packetChannelTun{
		packets: make(chan []byte, 256), entered: make(chan struct{}, 1), closed: make(chan struct{}),
	}
	uplink := newDirectionLimiter(protocolMTU, clock, waiter)
	downlink := newDirectionLimiter(protocolMTU, clock, waiter)
	uplink.apply(rate)
	downlink.apply(nil)
	if err := uplink.resume(); err != nil {
		t.Fatal(err)
	}
	queue := newPacketQueue(clock)
	queue.apply(rate)
	queue.resume()
	shaped := &shapedTun{
		ctx: ctx, device: &ownedTun{device: device}, mtu: protocolMTU,
		uplink: uplink, downlink: downlink, counters: &aggregateCounters{},
		gate: &sync.RWMutex{}, queue: queue, readerDone: make(chan struct{}),
		stopping: func() bool { return ctx.Err() != nil },
		fail:     func(code string) { t.Errorf("unexpected pump terminal failure: %s", code) },
	}
	cleanup := func() {
		cancel()
		uplink.close()
		downlink.close()
		queue.close(errEngineStopped)
		_ = shaped.device.Close()
		done := make(chan struct{})
		go func() { shaped.waitReader(); close(done) }()
		select {
		case <-done:
		case <-time.After(time.Second):
			t.Fatal("TUN reader did not join after descriptor/queue close")
		}
	}
	t.Cleanup(cleanup)
	return shaped, device, cleanup
}

func TestDequeuedPacketWaitsThroughSuspendAndUsesReplacementProfile(t *testing.T) {
	rate := uint64(1)
	clock := newFakeClock()
	waiter := &signalWaiter{entered: make(chan time.Duration, 2)}
	shaped, device, _ := queuedTunFixture(t, &rate, clock, waiter)
	drainInitialCredit(t, shaped.uplink)
	device.packets <- queueTestPacket(protocolMTU, 0, 1)
	result := make(chan error, 1)
	go func() { _, err := shaped.Read(make([]byte, protocolMTU)); result <- err }()
	select {
	case duration := <-waiter.entered:
		if duration != 12*time.Second {
			t.Fatalf("old profile wait = %s", duration)
		}
	case <-time.After(time.Second):
		t.Fatal("packet did not enter old profile limiter wait")
	}
	shaped.uplink.suspend()
	shaped.queue.pause()
	shaped.uplink.apply(nil)
	shaped.queue.apply(nil)
	assertQueueBlocked(t, result)
	if shaped.counters.uplinkPackets.Load() != 0 {
		t.Fatal("paused or merely dequeued packet was counted")
	}
	if err := shaped.uplink.resume(); err != nil {
		t.Fatal(err)
	}
	shaped.queue.resume()
	if err := awaitQueueResult(t, result); err != nil {
		t.Fatal(err)
	}
	if shaped.counters.uplinkPackets.Load() != 1 || shaped.counters.uplinkBytes.Load() != protocolMTU {
		t.Fatal("replacement profile did not account for exactly the delivered packet")
	}
}

func TestReadReturningDuringSuspensionCannotEnqueueOrDeliver(t *testing.T) {
	clock := newFakeClock()
	shaped, device, _ := queuedTunFixture(t, nil, clock, &advancingWaiter{clock: clock})
	result := make(chan error, 1)
	go func() { _, err := shaped.Read(make([]byte, protocolMTU)); result <- err }()
	select {
	case <-device.entered:
	case <-time.After(time.Second):
		t.Fatal("pump did not enter TUN read")
	}
	shaped.uplink.suspend()
	shaped.queue.pause()
	device.packets <- queueTestPacket(64, 1, 1)
	assertQueueBlocked(t, result)
	if shaped.queue.snapshot().queuedPackets != 0 || shaped.counters.uplinkPackets.Load() != 0 {
		t.Fatal("packet returned from an old TUN read crossed suspended admission")
	}
	rate := uint64(64)
	shaped.uplink.apply(&rate)
	shaped.queue.apply(&rate)
	if err := shaped.uplink.resume(); err != nil {
		t.Fatal(err)
	}
	shaped.queue.resume()
	if err := awaitQueueResult(t, result); err != nil {
		t.Fatal(err)
	}
	if shaped.counters.uplinkBytes.Load() != 64 {
		t.Fatal("held packet was not reconsidered under the current profile")
	}
}

func TestPumpJoinsWhenUnlimitedQueueIsFull(t *testing.T) {
	clock := newFakeClock()
	shaped, device, cleanup := queuedTunFixture(t, nil, clock, &advancingWaiter{clock: clock})
	device.packets <- queueTestPacket(64, 1, 1)
	if _, err := shaped.Read(make([]byte, protocolMTU)); err != nil {
		t.Fatal(err)
	}
	for range packetQueueMaxPackets + 1 {
		device.packets <- queueTestPacket(64, 2, 1)
	}
	deadline := time.Now().Add(time.Second)
	for shaped.queue.snapshot().queuedPackets != packetQueueMaxPackets {
		if time.Now().After(deadline) {
			t.Fatal("pump did not fill its unlimited queue")
		}
		time.Sleep(time.Millisecond)
	}
	cleanup()
	if stats := shaped.queue.snapshot(); stats.queuedPackets != 0 || stats.capacityDropPackets != 0 {
		t.Fatalf("unlimited shutdown lost backpressure or storage cleanup: %+v", stats)
	}
}

func TestLimitedPumpDrainsAndDropsWhileConsumerWaitsForCredit(t *testing.T) {
	rate := uint64(1)
	clock := newFakeClock()
	waiter := &signalWaiter{entered: make(chan time.Duration, 2)}
	shaped, device, cleanup := queuedTunFixture(t, &rate, clock, waiter)
	drainInitialCredit(t, shaped.uplink)
	device.packets <- queueTestPacket(protocolMTU, 0, 1)
	result := make(chan error, 1)
	go func() { _, err := shaped.Read(make([]byte, protocolMTU)); result <- err }()
	select {
	case <-waiter.entered:
	case <-time.After(time.Second):
		t.Fatal("consumer did not block for credit")
	}
	for range 200 {
		device.packets <- queueTestPacket(protocolMTU, 0, 1)
	}
	deadline := time.Now().Add(time.Second)
	for {
		stats := shaped.queue.snapshot()
		if uint64(stats.queuedPackets)+stats.capacityDropPackets == 200 {
			if stats.queuedBytes > packetQueueMaxBytes || stats.capacityDropPackets == 0 ||
				shaped.counters.uplinkPackets.Load() != 0 {
				t.Fatalf("paced consumer blocked draining or counted dropped packets: %+v", stats)
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("pump stopped draining behind its paced consumer: %+v", stats)
		}
		time.Sleep(time.Millisecond)
	}
	cleanup()
	if err := awaitQueueResult(t, result); err == nil {
		t.Fatal("closed limiter delivered the held packet")
	}
}

func TestAggregateQueueAndLimiterKeepAllConformanceRatesSaturated(t *testing.T) {
	for _, rate := range []uint64{64, 512, 4096} {
		t.Run(fmt.Sprintf("%d_kbps", rate), func(t *testing.T) {
			limiter, clock, _ := readyLimiter(&rate)
			queue := newPacketQueue(clock)
			queue.apply(&rate)
			queue.resume()
			for i := range 20 {
				if err := queue.enqueue(context.Background(), queueTestPacket(protocolMTU, byte(i%2), uint16(i%2+1))); err != nil {
					t.Fatal(err)
				}
			}
			end := clock.Now().Add(time.Minute)
			var admitted uint64
			for clock.Now().Before(end) {
				packet := takeQueuePacket(t, queue)
				if _, err := limiter.acquire(context.Background(), packet.size); err != nil {
					t.Fatal(err)
				}
				admitted += uint64(packet.size)
				// Two independent producers share the same finite queue and
				// limiter; no flow receives its own rate budget.
				for flow := uint16(1); flow <= 2; flow++ {
					if err := queue.enqueue(context.Background(), queueTestPacket(protocolMTU, 0, flow)); err != nil {
						t.Fatal(err)
					}
				}
			}
			ideal := float64(rate*1_000*60) / 8
			if ratio := float64(admitted) / ideal; ratio < .85 || ratio > 1.05 {
				t.Fatalf("60-second aggregate L3 ratio = %.6f", ratio)
			}
			if stats := queue.snapshot(); stats.codelDropPackets == 0 ||
				stats.queuedBytes > packetQueueMaxBytes || stats.queuedPackets > packetQueueMaxPackets {
				t.Fatalf("saturated queue did not exercise bounded congestion control: %+v", stats)
			}
		})
	}
}

func TestEngineInvalidTunReadFailsClosedAndJoinsPump(t *testing.T) {
	for name, packet := range map[string][]byte{
		"oversized":           make([]byte, protocolMTU+1),
		"truncated_ip":        {0x45, 0, 0, 64},
		"truncated_transport": ipv6ClassifierPacket(6, []byte{1, 2, 3, 4}),
	} {
		t.Run(name, func(t *testing.T) { assertEngineInvalidPacketFailsClosed(t, packet) })
	}
}

func assertEngineInvalidPacketFailsClosed(t *testing.T, packet []byte) {
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer unix.Close(fds[1])
	listener := newTerminalRecorder()
	engine, err := CreateEngine(int64(fds[0]), protocolMTU, &recordingProtector{allow: true}, listener)
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Stop()
	if _, err := engine.ApplyProfile([]byte(`{"downlink_kbps":null,"id":"baseline","uplink_kbps":null}`)); err != nil {
		t.Fatal(err)
	}
	if err := engine.Start(); err != nil {
		t.Fatal(err)
	}
	if err := engine.Resume(); err != nil {
		t.Fatal(err)
	}
	if _, err := unix.Write(fds[1], packet); err != nil {
		t.Fatal(err)
	}
	select {
	case code := <-listener.codes:
		if code != TerminalInvalidTunPacket {
			t.Fatalf("invalid TUN packet code = %s", code)
		}
	case <-time.After(time.Second):
		t.Fatal("invalid packet did not fail closed")
	}
	if engine.IsHealthy() || engine.HasOpenTun() || engine.Snapshot().GetUplinkPackets() != 0 {
		t.Fatal("invalid packet remained healthy or entered the aggregate counters")
	}
	stopped := make(chan struct{})
	go func() { engine.Stop(); close(stopped) }()
	select {
	case <-stopped:
	case <-time.After(time.Second):
		t.Fatal("terminal pump failed to join")
	}
	if closeErr := unix.Close(fds[0]); !errors.Is(closeErr, unix.EBADF) {
		t.Fatal("terminal shutdown did not close the transferred TUN")
	}
}

func TestQueueCloseRequiresAnExplicitReason(t *testing.T) {
	queue, _ := readyUplinkQueue(false)
	defer func() {
		if recover() == nil {
			t.Fatal("queue.close(nil) was accepted as a shutdown")
		}
	}()
	queue.close(nil)
}
