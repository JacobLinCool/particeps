// CoDel follows RFC 8289, Section 5, by K. Nichols, V. Jacobson,
// A. McGregor and J. Iyengar (January 2018). The algorithm is adapted from
// its reference pseudocode; see THIRD_PARTY_NOTICES.md for the IETF Trust license.
package trafficshaping

import (
	"math"
	"time"
)

const (
	codelTarget   = 5 * time.Millisecond
	codelInterval = 100 * time.Millisecond
)

// codelState is owned by the queue lock. It uses monotonic time.Time values,
// never wall-clock Unix timestamps. Capacity drops do not change its state.
type codelState struct {
	firstAbove time.Time
	dropNext   time.Time
	count      uint32
	lastCount  uint32
	dropping   bool
}

func (c *codelState) aboveTarget(now, enqueued time.Time, remainingBytes, mtu int) bool {
	// The MTU guard is essential at low rates: serializing one 1500-byte
	// packet at 64 kbps takes 187.5 ms, longer than the standard interval.
	if now.Sub(enqueued) < codelTarget || remainingBytes <= mtu {
		c.firstAbove = time.Time{}
		return false
	}
	if c.firstAbove.IsZero() {
		c.firstAbove = now.Add(codelInterval)
		return false
	}
	return !now.Before(c.firstAbove)
}

func (c *codelState) enterDropping(now time.Time) {
	delta := c.count - c.lastCount
	c.count = 1
	if delta > 1 && !c.dropNext.IsZero() && now.Sub(c.dropNext) < 16*codelInterval {
		c.count = delta
	}
	c.lastCount = c.count
	c.dropping = true
	c.dropNext = codelControlLaw(now, c.count)
}

func codelControlLaw(previous time.Time, count uint32) time.Time {
	return previous.Add(time.Duration(float64(codelInterval) / math.Sqrt(float64(count))))
}
