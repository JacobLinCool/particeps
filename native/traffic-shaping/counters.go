package trafficshaping

import (
	"math"
	"sync/atomic"
)

type aggregateCounters struct {
	uplinkBytes     atomic.Uint64
	uplinkPackets   atomic.Uint64
	downlinkBytes   atomic.Uint64
	downlinkPackets atomic.Uint64
}

func (c *aggregateCounters) recordUplink(bytes int) {
	saturatingAdd(&c.uplinkBytes, uint64(bytes))
	saturatingAdd(&c.uplinkPackets, 1)
}

func (c *aggregateCounters) recordDownlink(bytes int) {
	saturatingAdd(&c.downlinkBytes, uint64(bytes))
	saturatingAdd(&c.downlinkPackets, 1)
}

func (c *aggregateCounters) reset() {
	c.uplinkBytes.Store(0)
	c.uplinkPackets.Store(0)
	c.downlinkBytes.Store(0)
	c.downlinkPackets.Store(0)
}

func saturatingAdd(target *atomic.Uint64, increment uint64) {
	for {
		current := target.Load()
		next := current + increment
		if next < current {
			next = math.MaxUint64
		}
		if target.CompareAndSwap(current, next) {
			return
		}
	}
}

func signedSaturated(value uint64) int64 {
	if value > math.MaxInt64 {
		return math.MaxInt64
	}
	return int64(value)
}
