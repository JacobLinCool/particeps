package trafficshaping

import (
	"context"
	"fmt"
	"sync"
	"testing"
	"time"
)

type fakeClock struct {
	mu  sync.Mutex
	now time.Time
}

func newFakeClock() *fakeClock {
	return &fakeClock{now: time.Unix(1_700_000_000, 0)}
}

func (clock *fakeClock) Now() time.Time {
	clock.mu.Lock()
	defer clock.mu.Unlock()
	return clock.now
}

func (clock *fakeClock) advance(duration time.Duration) {
	clock.mu.Lock()
	clock.now = clock.now.Add(duration)
	clock.mu.Unlock()
}

type advancingWaiter struct {
	mu    sync.Mutex
	clock *fakeClock
	waits []time.Duration
}

func (waiter *advancingWaiter) Wait(ctx context.Context, duration time.Duration, wake <-chan struct{}) error {
	if duration < 0 {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-wake:
			return nil
		}
	}
	waiter.mu.Lock()
	waiter.waits = append(waiter.waits, duration)
	waiter.mu.Unlock()
	waiter.clock.advance(duration)
	return nil
}

func (waiter *advancingWaiter) totalWait() time.Duration {
	waiter.mu.Lock()
	defer waiter.mu.Unlock()
	var total time.Duration
	for _, duration := range waiter.waits {
		total += duration
	}
	return total
}

func readyLimiter(rateKbps *uint64) (*directionLimiter, *fakeClock, *advancingWaiter) {
	clock := newFakeClock()
	waiter := &advancingWaiter{clock: clock}
	limiter := newDirectionLimiter(protocolMTU, clock, waiter)
	limiter.apply(rateKbps)
	if err := limiter.resume(); err != nil {
		panic(err)
	}
	return limiter, clock, waiter
}

func drainInitialCredit(t *testing.T, limiter *directionLimiter) {
	t.Helper()
	ctx := context.Background()
	capacityBytes := limiter.capacityTokens / bytesToTokens(1)
	for capacityBytes > 0 {
		packetBytes := min(capacityBytes, limiter.mtu)
		if _, err := limiter.acquire(ctx, int(packetBytes)); err != nil {
			t.Fatal(err)
		}
		capacityBytes -= packetBytes
	}
}

func TestAggregateBucketIsSharedAcrossPackets(t *testing.T) {
	rate := uint64(64)
	limiter, _, waiter := readyLimiter(&rate)
	ctx := context.Background()
	if got, want := limiter.capacityTokens, bytesToTokens(16_000); got != want {
		t.Fatalf("capacity = %d tokens, want %d", got, want)
	}
	drainInitialCredit(t, limiter)
	if _, err := limiter.acquire(ctx, protocolMTU); err != nil {
		t.Fatal(err)
	}
	if got, want := waiter.totalWait(), 187_500*time.Microsecond; got != want {
		t.Fatalf("first packet beyond aggregate credit waited %v, want %v", got, want)
	}
	if got, want := limiter.throttledDuration(), uint64(187_500*time.Microsecond); got != want {
		t.Fatalf("throttled duration = %d, want %d", got, want)
	}
}

func TestDirectionsOwnIndependentBuckets(t *testing.T) {
	rate := uint64(64)
	uplink, _, uplinkWaiter := readyLimiter(&rate)
	downlink, _, downlinkWaiter := readyLimiter(&rate)
	ctx := context.Background()
	if _, err := uplink.acquire(ctx, protocolMTU); err != nil {
		t.Fatal(err)
	}
	if _, err := downlink.acquire(ctx, protocolMTU); err != nil {
		t.Fatal(err)
	}
	if uplinkWaiter.totalWait() != 0 || downlinkWaiter.totalWait() != 0 {
		t.Fatal("one direction consumed the other direction's initial credit")
	}
}

func TestProfileApplyResetsCreditAndUnlimitedNeverWaits(t *testing.T) {
	rate := uint64(64)
	limiter, _, waiter := readyLimiter(&rate)
	ctx := context.Background()
	drainInitialCredit(t, limiter)
	if _, err := limiter.acquire(ctx, protocolMTU); err != nil {
		t.Fatal(err)
	}
	waitBeforeReset := waiter.totalWait()
	if limiter.throttledDuration() == 0 {
		t.Fatal("rate-limited profile did not record throttled duration")
	}
	limiter.apply(&rate)
	if _, err := limiter.acquire(ctx, protocolMTU); err != nil {
		t.Fatal(err)
	}
	if waiter.totalWait() != waitBeforeReset || limiter.throttledDuration() != 0 {
		t.Fatal("new profile inherited an empty bucket")
	}
	limiter.apply(nil)
	for range 100 {
		if _, err := limiter.acquire(ctx, protocolMTU); err != nil {
			t.Fatal(err)
		}
	}
	if waiter.totalWait() != waitBeforeReset {
		t.Fatal("unlimited profile waited")
	}
}

func TestLimiterRefillClampsWithoutOverflow(t *testing.T) {
	rate := uint64(maximumRateKbps)
	limiter, clock, waiter := readyLimiter(&rate)
	ctx := context.Background()
	if _, err := limiter.acquire(ctx, protocolMTU); err != nil {
		t.Fatal(err)
	}
	clock.advance(time.Duration(1<<63 - 1))
	if _, err := limiter.acquire(ctx, protocolMTU); err != nil {
		t.Fatal(err)
	}
	if waiter.totalWait() != 0 {
		t.Fatal("a bucket that should have clamped to capacity waited")
	}
}

func TestSuspensionDoesNotAccrueTokenCredit(t *testing.T) {
	rate := uint64(64)
	limiter, clock, waiter := readyLimiter(&rate)
	drainInitialCredit(t, limiter)
	limiter.suspend()
	clock.advance(24 * time.Hour)
	if err := limiter.resume(); err != nil {
		t.Fatal(err)
	}
	if _, err := limiter.acquire(context.Background(), protocolMTU); err != nil {
		t.Fatal(err)
	}
	if got, want := waiter.totalWait(), 187_500*time.Microsecond; got != want {
		t.Fatalf("resume accrued paused credit: waited %v, want %v", got, want)
	}
}

func TestLimiterWakesAndRecalculatesWaitingPacketOnProfileChange(t *testing.T) {
	rate := uint64(1)
	clock := newFakeClock()
	waiter := &signalWaiter{entered: make(chan time.Duration, 1)}
	limiter := newDirectionLimiter(protocolMTU, clock, waiter)
	limiter.apply(&rate)
	if err := limiter.resume(); err != nil {
		t.Fatal(err)
	}
	if _, err := limiter.acquire(context.Background(), protocolMTU); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() {
		_, err := limiter.acquire(context.Background(), protocolMTU)
		done <- err
	}()
	select {
	case duration := <-waiter.entered:
		if duration != 12*time.Second {
			t.Fatalf("wait duration = %v, want 12s", duration)
		}
	case <-time.After(time.Second):
		t.Fatal("limiter did not start waiting")
	}
	limiter.apply(nil)
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("profile change did not wake the packet")
	}
}

type signalWaiter struct {
	entered chan time.Duration
}

func (waiter *signalWaiter) Wait(ctx context.Context, duration time.Duration, wake <-chan struct{}) error {
	if duration >= 0 {
		waiter.entered <- duration
	}
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-wake:
		return nil
	}
}

func TestSaturatedThroughputWithinProtocolBounds(t *testing.T) {
	for _, rate := range []uint64{64, 512, 4096} {
		t.Run(fmt.Sprintf("%d_kbps", rate), func(t *testing.T) {
			limiter, clock, _ := readyLimiter(&rate)
			start := clock.Now()
			var transferred uint64
			for {
				if _, err := limiter.acquire(context.Background(), protocolMTU); err != nil {
					t.Fatal(err)
				}
				if clock.Now().Sub(start) > 60*time.Second {
					break
				}
				transferred += protocolMTU
			}
			target := rate * 1_000 * 60 / 8
			upper := target*105/100 + protocolMTU
			lower := target * 90 / 100
			if transferred > upper || transferred < lower {
				t.Fatalf("rate %d kbps transferred %d bytes; expected %d..%d", rate, transferred, lower, upper)
			}
		})
	}
}
