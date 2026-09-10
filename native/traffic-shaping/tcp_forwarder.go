package trafficshaping

import (
	"context"
	"io"
	"net"
	"net/netip"
	"sync"
	"time"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const (
	tcpDialTimeout      = 5 * time.Second
	tcpHalfCloseTimeout = 60 * time.Second
	maxPendingTCPDials  = 2048
)

// tcpForwarder acknowledges a TUN-side connection only after its protected
// upstream connection succeeds. Otherwise a local handshake can make an
// unreachable address family win the application's connection race.
type tcpForwarder struct {
	ctx            context.Context
	cancel         context.CancelFunc
	dial           func(context.Context, *M.Metadata) (net.Conn, error)
	fail           func(string)
	createEndpoint func(*tcp.ForwarderRequest, *waiter.Queue) (tcpip.Endpoint, tcpip.Error)

	mu       sync.Mutex
	closed   bool
	pending  map[stack.TransportEndpointID]struct{}
	creating sync.WaitGroup
	wg       sync.WaitGroup
}

func newTCPForwarder(
	parent context.Context,
	dial func(context.Context, *M.Metadata) (net.Conn, error),
	fail func(string),
) *tcpForwarder {
	ctx, cancel := context.WithCancel(parent)
	return &tcpForwarder{
		ctx: ctx, cancel: cancel, dial: dial, fail: fail,
		createEndpoint: (*tcp.ForwarderRequest).CreateEndpoint,
		pending:        make(map[stack.TransportEndpointID]struct{}),
	}
}

func (f *tcpForwarder) wrap(endpoint stack.LinkEndpoint) stack.LinkEndpoint {
	return &tcpRegistrationEndpoint{LinkEndpoint: endpoint, registered: f.finishCreation}
}

// install must run before the suspended TUN starts admitting packets.
func (f *tcpForwarder) install(networkStack *stack.Stack) {
	forwarder := tcp.NewForwarder(networkStack, 0, maxPendingTCPDials, f.handle)
	networkStack.SetTransportProtocolHandler(tcp.ProtocolNumber, forwarder.HandlePacket)
}

func (f *tcpForwarder) handle(request *tcp.ForwarderRequest) {
	f.mu.Lock()
	if f.closed || f.ctx.Err() != nil {
		f.mu.Unlock()
		request.Complete(false)
		return
	}
	f.wg.Add(1)
	f.mu.Unlock()
	defer f.wg.Done()

	completed := false
	defer func() {
		panicked := recover() != nil
		if !completed {
			request.Complete(f.ctx.Err() == nil)
		}
		if panicked {
			f.fail(TerminalNativeStackFailure)
		}
	}()

	id := request.ID()
	source, sourceValid := netip.AddrFromSlice(id.RemoteAddress.AsSlice())
	destination, destinationValid := netip.AddrFromSlice(id.LocalAddress.AsSlice())
	if !sourceValid || !destinationValid {
		return
	}
	metadata := &M.Metadata{
		Network: M.TCP,
		SrcIP:   source.Unmap(),
		SrcPort: id.RemotePort,
		DstIP:   destination.Unmap(),
		DstPort: id.LocalPort,
	}
	dialCtx, cancelDial := context.WithTimeout(f.ctx, tcpDialTimeout)
	remote, err := f.dial(dialCtx, metadata)
	cancelDial()
	if err != nil {
		// Reachability failures reject this connection, allowing the app to
		// try another address. The protector reports its own terminal errors.
		return
	}
	defer remote.Close()
	stopRemoteClose := context.AfterFunc(f.ctx, func() { _ = remote.Close() })
	defer stopRemoteClose()
	if f.ctx.Err() != nil {
		return
	}

	var queue waiter.Queue
	endpoint, endpointErr := f.createLocalEndpoint(request, &queue)
	if endpointErr != nil {
		return
	}
	origin := gonet.NewTCPConn(&queue, endpoint)
	defer origin.Close()
	request.Complete(false)
	completed = true
	if err := configureTCPKeepalive(endpoint); err != nil {
		f.fail(TerminalNativeStackFailure)
		return
	}
	stopOriginClose := context.AfterFunc(f.ctx, func() { _ = origin.Close() })
	defer stopOriginClose()
	relayTCP(origin, remote)
}

func (f *tcpForwarder) createLocalEndpoint(
	request *tcp.ForwarderRequest,
	queue *waiter.Queue,
) (tcpip.Endpoint, tcpip.Error) {
	id := request.ID()
	f.mu.Lock()
	if f.closed || f.ctx.Err() != nil {
		f.mu.Unlock()
		return nil, &tcpip.ErrConnectionAborted{}
	}
	f.pending[id] = struct{}{}
	f.creating.Add(1)
	f.mu.Unlock()
	defer f.finishCreation(id)
	return f.createEndpoint(request, queue)
}

func (f *tcpForwarder) finishCreation(id stack.TransportEndpointID) {
	f.mu.Lock()
	if _, pending := f.pending[id]; pending {
		delete(f.pending, id)
		f.creating.Done()
	}
	f.mu.Unlock()
}

// close cancels dials and sockets and fences endpoint registration. The owner
// can then close the stack to interrupt incomplete local handshakes before wait.
func (f *tcpForwarder) close() {
	f.mu.Lock()
	f.closed = true
	f.cancel()
	f.mu.Unlock()
	f.creating.Wait()
}

func (f *tcpForwarder) wait() { f.wg.Wait() }

func configureTCPKeepalive(endpoint tcpip.Endpoint) tcpip.Error {
	// Preserve the upstream forwarder's idle-connection lifecycle settings.
	endpoint.SocketOptions().SetKeepAlive(true)
	idle := tcpip.KeepaliveIdleOption(60 * time.Second)
	if err := endpoint.SetSockOpt(&idle); err != nil {
		return err
	}
	interval := tcpip.KeepaliveIntervalOption(30 * time.Second)
	if err := endpoint.SetSockOpt(&interval); err != nil {
		return err
	}
	return endpoint.SetSockOptInt(tcpip.KeepaliveCountOption, 9)
}

// gVisor registers a forwarded endpoint before synchronously writing its first
// SYN-ACK, but CreateEndpoint returns only after the application's ACK. Signal
// registration at this earlier boundary so Stop can safely close every endpoint
// without waiting for the application to complete the handshake. This wrapper
// runs before the asynchronous link queue and TUN shaping, including suspension.
// The fixed stack has no output filters or link-address resolution, and its
// routes, NIC and 1500-byte MTU remain unchanged until the fence drains. These
// invariants keep a generated SYN-ACK from failing before it reaches this link.
type tcpRegistrationEndpoint struct {
	stack.LinkEndpoint
	registered func(stack.TransportEndpointID)
}

func (e *tcpRegistrationEndpoint) WritePackets(packets stack.PacketBufferList) (int, tcpip.Error) {
	for _, packet := range packets.AsSlice() {
		if packet.TransportProtocolNumber != tcp.ProtocolNumber ||
			len(packet.TransportHeader().Slice()) < header.TCPMinimumSize {
			continue
		}
		segment := header.TCP(packet.TransportHeader().Slice())
		if !segment.Flags().Contains(header.TCPFlagSyn | header.TCPFlagAck) {
			continue
		}
		e.registered(stack.TransportEndpointID{
			LocalAddress:  packet.Network().SourceAddress(),
			LocalPort:     segment.SourcePort(),
			RemoteAddress: packet.Network().DestinationAddress(),
			RemotePort:    segment.DestinationPort(),
		})
	}
	return e.LinkEndpoint.WritePackets(packets)
}

func relayTCP(origin, remote net.Conn) {
	var directions sync.WaitGroup
	directions.Add(2)
	copyDirection := func(destination, source net.Conn) {
		defer directions.Done()
		_, _ = io.Copy(destination, source)
		if reader, ok := source.(interface{ CloseRead() error }); ok {
			_ = reader.CloseRead()
		}
		if writer, ok := destination.(interface{ CloseWrite() error }); ok {
			_ = writer.CloseWrite()
		}
		// A peer may finish uploading before the response is complete. Keep
		// the other direction open, with the same bounded drain as upstream.
		_ = destination.SetReadDeadline(time.Now().Add(tcpHalfCloseTimeout))
	}
	go copyDirection(remote, origin)
	go copyDirection(origin, remote)
	directions.Wait()
}
