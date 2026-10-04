package trafficshaping

import (
	"bytes"
	"context"
	"errors"
	"io"
	"sync"
	"testing"
)

type memoryTun struct {
	mu         sync.Mutex
	readBuffer *bytes.Reader
	written    bytes.Buffer
	closed     int
	readErr    error
	closedCh   chan struct{}
}

func (tun *memoryTun) Read(target []byte) (int, error) {
	if tun.readErr != nil {
		return 0, tun.readErr
	}
	n, err := tun.readBuffer.Read(target)
	if err == io.EOF && tun.closedCh != nil {
		<-tun.closedCh
	}
	return n, err
}

func (tun *memoryTun) Write(source []byte) (int, error) {
	tun.mu.Lock()
	defer tun.mu.Unlock()
	return tun.written.Write(source)
}

func (tun *memoryTun) Close() error {
	tun.mu.Lock()
	tun.closed++
	if tun.closedCh != nil {
		close(tun.closedCh)
	}
	tun.mu.Unlock()
	return nil
}

func TestShapedTunCountsOnlyAggregateSuccessfulPackets(t *testing.T) {
	payload := queueTestPacket(128, 0, 1)
	memory := &memoryTun{readBuffer: bytes.NewReader(payload), closedCh: make(chan struct{})}
	owned := &ownedTun{device: memory}
	clock := newFakeClock()
	waiter := &advancingWaiter{clock: clock}
	uplink := newDirectionLimiter(protocolMTU, clock, waiter)
	downlink := newDirectionLimiter(protocolMTU, clock, waiter)
	uplink.apply(nil)
	downlink.apply(nil)
	if err := uplink.resume(); err != nil {
		t.Fatal(err)
	}
	if err := downlink.resume(); err != nil {
		t.Fatal(err)
	}
	counters := &aggregateCounters{}
	var gate sync.RWMutex
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	queue := newPacketQueue(clock)
	queue.resume()
	device := &shapedTun{
		ctx:      ctx,
		device:   owned,
		mtu:      protocolMTU,
		uplink:   uplink,
		downlink: downlink,
		counters: counters,
		gate:     &gate,
		fail:     func(code string) { t.Fatalf("unexpected terminal failure %s", code) },
		stopping: func() bool { return false },
		queue:    queue, readerDone: make(chan struct{}),
	}
	readTarget := make([]byte, protocolMTU)
	read, err := device.Read(readTarget)
	if err != nil || read != len(payload) {
		t.Fatalf("read = %d, %v", read, err)
	}
	written, err := device.Write(payload)
	if err != nil || written != len(payload) {
		t.Fatalf("write = %d, %v", written, err)
	}
	if counters.uplinkBytes.Load() != 128 || counters.uplinkPackets.Load() != 1 {
		t.Fatal("uplink counters are not aggregate TUN counters")
	}
	if counters.downlinkBytes.Load() != 128 || counters.downlinkPackets.Load() != 1 {
		t.Fatal("downlink counters are not aggregate TUN counters")
	}
	cancel()
	if err := owned.Close(); err != nil {
		t.Fatal(err)
	}
	device.waitReader()
	if err := owned.Close(); err != nil {
		t.Fatal(err)
	}
	if memory.closed != 1 {
		t.Fatalf("owned TUN closed %d times", memory.closed)
	}
}

func TestShapedTunReportsNonSensitiveTerminalCodeOnceAtEngineBoundary(t *testing.T) {
	memory := &memoryTun{readBuffer: bytes.NewReader(nil), readErr: io.EOF}
	owned := &ownedTun{device: memory}
	clock := newFakeClock()
	waiter := &advancingWaiter{clock: clock}
	uplink := newDirectionLimiter(protocolMTU, clock, waiter)
	downlink := newDirectionLimiter(protocolMTU, clock, waiter)
	uplink.apply(nil)
	downlink.apply(nil)
	if err := uplink.resume(); err != nil {
		t.Fatal(err)
	}
	failures := make(chan string, 1)
	var gate sync.RWMutex
	device := &shapedTun{
		ctx: context.Background(), device: owned, mtu: protocolMTU,
		uplink: uplink, downlink: downlink, counters: &aggregateCounters{}, gate: &gate,
		fail: func(code string) { failures <- code }, stopping: func() bool { return false },
		queue: newPacketQueue(clock), readerDone: make(chan struct{}),
	}
	device.queue.resume()
	if _, err := device.Read(make([]byte, protocolMTU)); !errors.Is(err, io.EOF) {
		t.Fatalf("read error = %v", err)
	}
	if code := <-failures; code != TerminalTunEOF {
		t.Fatalf("terminal code = %q", code)
	}
	device.waitReader()
}

func TestShapedTunRejectsConcurrentDirectionIOInsteadOfQueueing(t *testing.T) {
	failures := make(chan string, 1)
	device := &shapedTun{fail: func(code string) { failures <- code }}
	device.readActive.Store(true)
	if _, err := device.Read(make([]byte, protocolMTU)); !errors.Is(err, errConcurrentTunIO) {
		t.Fatalf("concurrent read error = %v", err)
	}
	if code := <-failures; code != TerminalConcurrentTunIO {
		t.Fatalf("terminal code = %q", code)
	}
}
