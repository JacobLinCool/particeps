package trafficshaping

import (
	"context"
	"errors"
	"math"
	"sync"
	"time"
)

const (
	bitsPerByte            = uint64(8)
	tokenNanosecondsPerBit = uint64(time.Second)
	// The 60-second conformance interval permits up to three seconds of
	// aggregate credit. Two seconds absorbs Android/Go timer jitter at the
	// highest v1 rate without exceeding the protocol's 105% upper bound.
	bucketCapacityWindow = 2 * time.Second
)

var (
	errLimiterClosed    = errors.New("traffic limiter is closed")
	errPacketExceedsMTU = errors.New("packet exceeds configured MTU")
)

type monotonicClock interface {
	Now() time.Time
}

type interruptibleWaiter interface {
	Wait(context.Context, time.Duration, <-chan struct{}) error
}

type systemClock struct{}

func (systemClock) Now() time.Time { return time.Now() }

type timerWaiter struct{}

func (timerWaiter) Wait(ctx context.Context, duration time.Duration, wake <-chan struct{}) error {
	if duration < 0 {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-wake:
			return nil
		}
	}
	timer := time.NewTimer(duration)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-wake:
		return nil
	case <-timer.C:
		return nil
	}
}

// directionLimiter holds one aggregate TUN-layer bucket. Production has one
// caller per TUN direction; the lock also makes profile changes and snapshots
// race-safe.
type directionLimiter struct {
	// Serialize complete acquisitions so concurrent tests or future TUN adapters
	// cannot schedule multiple packets against the same future credit.
	acquireMu sync.Mutex
	mu        sync.Mutex

	clock  monotonicClock
	waiter interruptibleWaiter
	mtu    uint64

	rateBitsPerSecond uint64
	capacityTokens    uint64
	tokens            uint64
	lastRefill        time.Time
	unlimited         bool
	suspended         bool
	closed            bool
	version           uint64
	wake              chan struct{}

	throttledNanos uint64
}

func newDirectionLimiter(mtu int, clock monotonicClock, waiter interruptibleWaiter) *directionLimiter {
	return &directionLimiter{
		clock:      clock,
		waiter:     waiter,
		mtu:        uint64(mtu),
		suspended:  true,
		unlimited:  true,
		lastRefill: clock.Now(),
		wake:       make(chan struct{}),
	}
}

func (l *directionLimiter) apply(rateKbps *uint64) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.bumpVersionLocked()
	l.lastRefill = l.clock.Now()
	l.throttledNanos = 0
	if rateKbps == nil {
		l.unlimited = true
		l.rateBitsPerSecond = 0
		l.capacityTokens = 0
		l.tokens = 0
		return
	}
	l.unlimited = false
	l.rateBitsPerSecond = *rateKbps * 1_000
	capacityBytes := l.rateBitsPerSecond * uint64(bucketCapacityWindow) /
		uint64(time.Second) / bitsPerByte
	if capacityBytes < l.mtu {
		capacityBytes = l.mtu
	}
	l.capacityTokens = bytesToTokens(capacityBytes)
	l.tokens = l.capacityTokens
}

func (l *directionLimiter) suspend() {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.closed || l.suspended {
		return
	}
	l.suspended = true
	l.bumpVersionLocked()
}

func (l *directionLimiter) resume() error {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.closed {
		return errLimiterClosed
	}
	if !l.suspended {
		return nil
	}
	l.suspended = false
	l.lastRefill = l.clock.Now()
	l.bumpVersionLocked()
	return nil
}

func (l *directionLimiter) close() {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.closed {
		return
	}
	l.closed = true
	l.bumpVersionLocked()
}

func (l *directionLimiter) waitOpen(ctx context.Context) error {
	for {
		l.mu.Lock()
		if l.closed {
			l.mu.Unlock()
			return errLimiterClosed
		}
		if !l.suspended {
			l.mu.Unlock()
			return nil
		}
		wake := l.wake
		l.mu.Unlock()
		if err := l.waiter.Wait(ctx, -1, wake); err != nil {
			return err
		}
	}
}

// acquire returns a permit version. The caller must validate that version
// under its forwarding gate immediately before exposing the packet.
func (l *directionLimiter) acquire(ctx context.Context, packetBytes int) (uint64, error) {
	l.acquireMu.Lock()
	defer l.acquireMu.Unlock()
	if packetBytes <= 0 || uint64(packetBytes) > l.mtu {
		return 0, errPacketExceedsMTU
	}
	required := bytesToTokens(uint64(packetBytes))
	for {
		l.mu.Lock()
		if l.closed {
			l.mu.Unlock()
			return 0, errLimiterClosed
		}
		if l.suspended {
			wake := l.wake
			l.mu.Unlock()
			if err := l.waiter.Wait(ctx, -1, wake); err != nil {
				return 0, err
			}
			continue
		}
		if l.unlimited {
			version := l.version
			l.mu.Unlock()
			return version, nil
		}

		l.refillLocked(l.clock.Now())
		if l.tokens >= required {
			l.tokens -= required
			version := l.version
			l.mu.Unlock()
			return version, nil
		}

		deficit := required - l.tokens
		waitDuration := time.Duration(ceilDiv(deficit, l.rateBitsPerSecond))
		wake := l.wake
		waitStarted := l.clock.Now()
		l.mu.Unlock()

		err := l.waiter.Wait(ctx, waitDuration, wake)
		waitEnded := l.clock.Now()
		if waited := waitEnded.Sub(waitStarted); waited > 0 {
			l.addThrottledDuration(waited)
		}
		if err != nil {
			return 0, err
		}
	}
}

// openVersion waits for forwarding admission without consuming rate credit.
// TUN reads call it before blocking on the descriptor so suspension closes the
// resource boundary before the next device packet is accepted.
func (l *directionLimiter) openVersion(ctx context.Context) (uint64, error) {
	for {
		l.mu.Lock()
		if l.closed {
			l.mu.Unlock()
			return 0, errLimiterClosed
		}
		if !l.suspended {
			version := l.version
			l.mu.Unlock()
			return version, nil
		}
		wake := l.wake
		l.mu.Unlock()
		if err := l.waiter.Wait(ctx, -1, wake); err != nil {
			return 0, err
		}
	}
}

func (l *directionLimiter) permitValid(version uint64) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	return !l.closed && !l.suspended && l.version == version
}

func (l *directionLimiter) throttledDuration() uint64 {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.throttledNanos
}

func (l *directionLimiter) refillLocked(now time.Time) {
	if !now.After(l.lastRefill) || l.tokens >= l.capacityTokens {
		l.lastRefill = now
		return
	}
	elapsed := uint64(now.Sub(l.lastRefill))
	l.lastRefill = now
	missing := l.capacityTokens - l.tokens
	fillNanos := ceilDiv(missing, l.rateBitsPerSecond)
	if elapsed >= fillNanos || elapsed > math.MaxUint64/l.rateBitsPerSecond {
		l.tokens = l.capacityTokens
		return
	}
	l.tokens += elapsed * l.rateBitsPerSecond
}

func (l *directionLimiter) addThrottledDuration(duration time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	value := uint64(duration)
	if math.MaxUint64-l.throttledNanos < value {
		l.throttledNanos = math.MaxUint64
		return
	}
	l.throttledNanos += value
}

func (l *directionLimiter) bumpVersionLocked() {
	close(l.wake)
	l.wake = make(chan struct{})
	l.version++
}

func bytesToTokens(bytes uint64) uint64 {
	return bytes * bitsPerByte * tokenNanosecondsPerBit
}

func ceilDiv(numerator, denominator uint64) uint64 {
	return numerator/denominator + boolToUint64(numerator%denominator != 0)
}

func boolToUint64(value bool) uint64 {
	if value {
		return 1
	}
	return 0
}
