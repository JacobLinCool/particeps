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

	device   *ownedTun
	mtu      int
	uplink   *directionLimiter
	downlink *directionLimiter
	counters *aggregateCounters
	gate     *sync.RWMutex
	fail     func(string)
	stopping func() bool

	readActive  atomic.Bool
	writeActive atomic.Bool
}

func (t *shapedTun) Read(packet []byte) (int, error) {
	if !t.readActive.CompareAndSwap(false, true) {
		t.fail(TerminalConcurrentTunIO)
		return 0, errConcurrentTunIO
	}
	defer t.readActive.Store(false)

	if _, err := t.uplink.openVersion(t.ctx); err != nil {
		return 0, err
	}
	count, err := t.device.Read(packet)
	if err != nil {
		t.handleReadFailure(err)
		return count, err
	}
	if count <= 0 || count > t.mtu {
		t.fail(TerminalInvalidTunPacket)
		return 0, errInvalidTunPacket
	}

	for {
		permit, acquireErr := t.uplink.acquire(t.ctx, count)
		if acquireErr != nil {
			return 0, acquireErr
		}
		t.gate.RLock()
		if t.uplink.permitValid(permit) {
			t.counters.recordUplink(count)
			t.gate.RUnlock()
			return count, nil
		}
		t.gate.RUnlock()
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
	if t.stopping() || errors.Is(err, context.Canceled) || errors.Is(err, errLimiterClosed) {
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
