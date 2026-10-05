package trafficshaping

import (
	"errors"
	"runtime"
	"sync"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// shapedLinkEndpoint schedules packets before the paced TUN write. The embedded
// channel supplies only link metadata and inbound dispatch: its zero-capacity
// output is never used. In particular, download data cannot trap upload ACKs
// behind the upstream library's 1024-packet output FIFO.
type shapedLinkEndpoint struct {
	*channel.Endpoint
	shaped *shapedTun
	queue  *packetQueue

	producerMu      sync.Mutex
	lifecycleMu     sync.Mutex
	started, closed bool
	workers         sync.WaitGroup
	closeOnce       sync.Once
}

var _ stack.LinkEndpoint = (*shapedLinkEndpoint)(nil)

func newShapedLinkEndpoint(shaped *shapedTun, queue *packetQueue) *shapedLinkEndpoint {
	return &shapedLinkEndpoint{
		Endpoint: channel.New(0, uint32(shaped.mtu), ""),
		shaped:   shaped, queue: queue,
	}
}

func (e *shapedLinkEndpoint) Attach(dispatcher stack.NetworkDispatcher) {
	if dispatcher == nil {
		// Stack.Wait may detach and then call Wait while holding its own lock,
		// before calling Close. Wake workers here, without joining them.
		e.Close()
		return
	}
	e.lifecycleMu.Lock()
	defer e.lifecycleMu.Unlock()
	if e.closed {
		return
	}
	e.Endpoint.Attach(dispatcher)
	if !e.started {
		e.workers.Add(2)
		e.started = true
		go e.run(e.dispatchInbound)
		go e.run(e.deliverDownlink)
	}
}

func (e *shapedLinkEndpoint) Wait() {
	e.lifecycleMu.Lock()
	started := e.started
	e.lifecycleMu.Unlock()
	// All Adds precede publishing started; no Add can race an active Wait.
	if started {
		e.workers.Wait()
	}
}

func (e *shapedLinkEndpoint) Close() {
	closedHere := false
	e.closeOnce.Do(func() {
		e.lifecycleMu.Lock()
		e.closed = true
		e.lifecycleMu.Unlock()
		e.Endpoint.Attach(nil)
		e.Endpoint.Close()
		e.queue.close(errEngineStopped)
		e.shaped.queue.close(errEngineStopped)
		e.shaped.uplink.close()
		e.shaped.downlink.close()
		_ = e.shaped.device.Close()
		closedHere = true
	})
	// Never call the engine while holding a queue, producer or lifecycle lock,
	// and never join a worker from a worker's terminal path.
	if closedHere && e.shaped.ctx.Err() == nil && !e.shaped.stopping() {
		e.shaped.fail(TerminalNativeStackFailure)
	}
}

func (e *shapedLinkEndpoint) run(work func()) {
	defer e.workers.Done()
	defer e.Close()
	defer func() {
		if recover() != nil {
			e.shaped.fail(TerminalNativeStackFailure)
		}
	}()
	work()
}

func (e *shapedLinkEndpoint) WritePackets(packets stack.PacketBufferList) (count int, result tcpip.Error) {
	defer func() {
		if recover() != nil {
			e.shaped.fail(TerminalNativeStackFailure)
			e.Close()
			result = &tcpip.ErrInvalidEndpointState{}
		}
	}()
	var err error
	count, err = e.enqueuePackets(packets)
	if err != nil {
		if errors.Is(err, errInvalidTunPacket) {
			e.shaped.fail(TerminalInvalidTunPacket)
			e.Close()
		}
		return count, &tcpip.ErrInvalidEndpointState{}
	}
	return count, nil
}

func (e *shapedLinkEndpoint) enqueuePackets(packets stack.PacketBufferList) (int, error) {
	count := 0
	for _, packet := range packets.AsSlice() {
		if packet.Size() <= 0 || packet.Size() > e.shaped.mtu {
			return count, errInvalidTunPacket
		}
		if err := e.enqueuePacket(packet); err != nil {
			return count, err
		}
		// Accepted includes congestion drops. Only successful paced TUN writes
		// are counted as admitted Layer-3 traffic.
		count++
		// Give a ready paced consumer a turn before a synchronous stack batch
		// overruns the queue despite available token credit. Our producer,
		// copy and queue scopes have ended; the transport caller may still
		// hold its own endpoint lock. This does not wait for queue capacity.
		if e.queue.isLimited() {
			runtime.Gosched()
		}
	}
	return count, nil
}

func (e *shapedLinkEndpoint) enqueuePacket(packet *stack.PacketBuffer) error {
	// Hold at most one MTU-sized producer copy. Release between packets so a
	// large stack batch cannot retain admission ahead of another flow's ACK.
	// Close never takes this lock and queue closure wakes a blocked producer.
	e.producerMu.Lock()
	defer e.producerMu.Unlock()
	// The caller retains the original packet/list reference. Only this buffer
	// clone is ours; enqueue copies into fixed storage before it is released.
	data := packet.ToBuffer()
	defer data.Release()
	return e.queue.enqueue(e.shaped.ctx, data.Flatten())
}

func (e *shapedLinkEndpoint) deliverDownlink() {
	for {
		packet, err := e.queue.dequeue(e.shaped.ctx)
		if err != nil {
			return
		}
		if _, err := e.shaped.Write(packet.data[:packet.size]); err != nil {
			return
		}
	}
}

func (e *shapedLinkEndpoint) dispatchInbound() {
	for {
		data := make([]byte, e.shaped.mtu)
		size, err := e.shaped.Read(data)
		if err != nil {
			return
		}
		e.injectPacket(data[:size])
	}
}

func (e *shapedLinkEndpoint) injectPacket(data []byte) {
	packet := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(data)})
	defer packet.DecRef()
	switch header.IPVersion(data) {
	case header.IPv4Version:
		e.InjectInbound(header.IPv4ProtocolNumber, packet)
	case header.IPv6Version:
		e.InjectInbound(header.IPv6ProtocolNumber, packet)
	default:
		e.shaped.fail(TerminalInvalidTunPacket)
	}
}
