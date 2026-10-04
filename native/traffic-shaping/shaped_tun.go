package trafficshaping

import (
	"context"
	"errors"
	"io"
	"sync"
	"sync/atomic"
)

type shapedTun struct {
	ctx context.Context

	device        *ownedTun
	mtu           int
	uplink        *directionLimiter
	downlink      *directionLimiter
	counters      *aggregateCounters
	gate          *sync.RWMutex
	fail          func(string)
	stopping      func() bool
	queue         *packetQueue
	readerOnce    sync.Once
	readerStarted atomic.Bool
	readerDone    chan struct{}

	readActive  atomic.Bool
	writeActive atomic.Bool
}

func (t *shapedTun) Read(packet []byte) (int, error) {
	if !t.readActive.CompareAndSwap(false, true) {
		t.fail(TerminalConcurrentTunIO)
		return 0, errConcurrentTunIO
	}
	defer t.readActive.Store(false)
	if len(packet) < t.mtu {
		t.fail(TerminalInvalidTunPacket)
		return 0, errInvalidTunPacket
	}
	t.readerOnce.Do(func() {
		t.readerStarted.Store(true)
		go t.drainUplink()
	})
	queued, err := t.queue.dequeue(t.ctx)
	if err != nil {
		return 0, err
	}
	count := queued.size

	for {
		permit, acquireErr := t.uplink.acquire(t.ctx, count)
		if acquireErr != nil {
			return 0, acquireErr
		}
		t.gate.RLock()
		if t.uplink.permitValid(permit) {
			copy(packet, queued.data[:count])
			t.counters.recordUplink(count)
			t.gate.RUnlock()
			return count, nil
		}
		t.gate.RUnlock()
	}
}

// Exactly one reader drains the kernel queue while admission is open. A
// limited profile never blocks this reader on token credit or queue capacity;
// congestion feedback is produced by the bounded queue instead of a hidden
// seconds-long kernel backlog. Unlimited uses ordinary bounded backpressure.
func (t *shapedTun) drainUplink() {
	defer close(t.readerDone)
	defer func() {
		if recover() != nil {
			t.queue.close(errNativeStack)
			t.fail(TerminalNativeStackFailure)
		}
	}()
	buffer := make([]byte, t.mtu+1)
	for {
		if _, err := t.uplink.openVersion(t.ctx); err != nil {
			t.queue.close(err)
			return
		}
		count, err := t.device.Read(buffer)
		if err != nil {
			t.handleReadFailure(err)
			t.queue.close(err)
			return
		}
		if count <= 0 || count > t.mtu {
			t.fail(TerminalInvalidTunPacket)
			t.queue.close(errInvalidTunPacket)
			return
		}
		if err := t.queue.enqueue(t.ctx, buffer[:count]); err != nil {
			if errors.Is(err, errInvalidTunPacket) {
				t.fail(TerminalInvalidTunPacket)
			}
			t.queue.close(err)
			return
		}
	}
}

// Call after closing the descriptor and joining the stack's dispatch loop.
// The pump itself never calls this, including when it reports a terminal error.
func (t *shapedTun) waitReader() {
	if t.readerStarted.Load() {
		<-t.readerDone
	}
}

func (t *shapedTun) Write(packet []byte) (int, error) {
	if !t.writeActive.CompareAndSwap(false, true) {
		t.fail(TerminalConcurrentTunIO)
		return 0, errConcurrentTunIO
	}
	defer t.writeActive.Store(false)

	if len(packet) == 0 || len(packet) > t.mtu {
		t.fail(TerminalInvalidTunPacket)
		return 0, errInvalidTunPacket
	}
	for {
		permit, err := t.downlink.acquire(t.ctx, len(packet))
		if err != nil {
			return 0, err
		}
		t.gate.RLock()
		if !t.downlink.permitValid(permit) {
			t.gate.RUnlock()
			continue
		}
		count, writeErr := t.device.Write(packet)
		if writeErr == nil && count != len(packet) {
			writeErr = io.ErrShortWrite
		}
		if writeErr == nil {
			t.counters.recordDownlink(count)
		}
		t.gate.RUnlock()
		if writeErr != nil {
			if !t.stopping() {
				t.fail(TerminalTunWriteFailed)
			}
			return count, writeErr
		}
		return count, nil
	}
}

func (t *shapedTun) handleReadFailure(err error) {
	if t.ctx.Err() != nil || t.stopping() || errors.Is(err, context.Canceled) || errors.Is(err, errLimiterClosed) {
		return
	}
	if errors.Is(err, io.EOF) {
		t.fail(TerminalTunEOF)
		return
	}
	t.fail(TerminalTunReadFailed)
}

var (
	errConcurrentTunIO  = errors.New("concurrent TUN I/O is not supported")
	errInvalidTunPacket = errors.New("invalid TUN packet")
)
