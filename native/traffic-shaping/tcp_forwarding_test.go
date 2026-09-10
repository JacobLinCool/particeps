package trafficshaping

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const (
	testTCPFin = 0x01
	testTCPSyn = 0x02
	testTCPRst = 0x04
	testTCPPsh = 0x08
	testTCPAck = 0x10
)

type tcpTunFixture struct {
	t           *testing.T
	engine      Engine
	state       *engineState
	protector   *recordingProtector
	terminal    *terminalRecorder
	fd          int
	source      netip.Addr
	destination netip.Addr
	packets     chan []byte
	readErrors  chan error
	stopRead    chan struct{}
	readDone    chan struct{}
}

func newTCPTunFixture(t *testing.T, ipv6 bool, dial func(context.Context, *M.Metadata) (net.Conn, error)) *tcpTunFixture {
	t.Helper()
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	// A real TUN queues packets; the small default Unix datagram buffers can
	// instead reject a brief response burst under race instrumentation.
	for _, fd := range fds {
		for _, option := range []int{unix.SO_SNDBUF, unix.SO_RCVBUF} {
			if err := unix.SetsockoptInt(fd, unix.SOL_SOCKET, option, 64<<10); err != nil {
				unix.Close(fds[0])
				unix.Close(fds[1])
				t.Fatal(err)
			}
		}
	}
	protector := &recordingProtector{allow: true}
	terminal := newTerminalRecorder()
	engine, err := CreateEngine(int64(fds[0]), protocolMTU, protector, terminal)
	if err != nil {
		unix.Close(fds[1])
		t.Fatal(err)
	}
	f := &tcpTunFixture{
		t: t, engine: engine, state: engine.(*mobileEngine).state,
		protector: protector, terminal: terminal, fd: fds[1],
		source: netip.MustParseAddr("10.111.222.1"), destination: netip.MustParseAddr("198.18.0.1"),
		packets: make(chan []byte, 64), readErrors: make(chan error, 1),
		stopRead: make(chan struct{}), readDone: make(chan struct{}),
	}
	if ipv6 {
		f.source, f.destination = netip.MustParseAddr("fd00:7061:7274::1"), netip.MustParseAddr("2001:db8::1")
	}
	t.Cleanup(func() {
		engine.Stop()
		close(f.stopRead)
		<-f.readDone
		unix.Close(f.fd)
	})
	if err := unix.SetsockoptTimeval(f.fd, unix.SOL_SOCKET, unix.SO_RCVTIMEO, &unix.Timeval{Usec: 100000}); err != nil {
		close(f.readDone)
		t.Fatal(err)
	}
	go f.readPackets()
	if _, err := engine.ApplyProfile([]byte(`{"downlink_kbps":null,"id":"baseline","uplink_kbps":null}`)); err != nil {
		t.Fatal(err)
	}
	if err := engine.Start(); err != nil {
		t.Fatal(err)
	}
	if dial != nil {
		f.state.tcpForwarder.dial = dial
	}
	return f
}

func (f *tcpTunFixture) resume() {
	f.t.Helper()
	if err := f.engine.Resume(); err != nil {
		f.t.Fatal(err)
	}
}

func (f *tcpTunFixture) readPackets() {
	defer close(f.readDone)
	for {
		select {
		case <-f.stopRead:
			return
		default:
		}
		buffer := make([]byte, protocolMTU)
		n, err := unix.Read(f.fd, buffer)
		if errors.Is(err, unix.EAGAIN) || errors.Is(err, unix.EWOULDBLOCK) || errors.Is(err, unix.EINTR) {
			continue
		}
		if err != nil {
			f.readErrors <- err
			return
		}
		select {
		case f.packets <- buffer[:n]:
		case <-f.stopRead:
			return
		}
	}
}

func (f *tcpTunFixture) send(sequence, acknowledgment uint32, flags byte, payload []byte) {
	f.t.Helper()
	packet := makeTCPTestPacket(f.source, f.destination, sequence, acknowledgment, flags, payload)
	if _, err := unix.Write(f.fd, packet); err != nil {
		select {
		case terminal := <-f.terminal.codes:
			f.t.Fatalf("TUN write failed: %v; engine terminal: %s", err, terminal)
		default:
			f.t.Fatal(err)
		}
	}
}

func (f *tcpTunFixture) packet(timeout time.Duration) []byte {
	f.t.Helper()
	select {
	case packet := <-f.packets:
		return packet
	case err := <-f.readErrors:
		f.t.Fatalf("reading TCP response from TUN: %v", err)
	case <-time.After(timeout):
		f.t.Fatal("timed out waiting for TCP response from TUN")
	}
	return nil
}

func TestTCPDoesNotAcknowledgeBeforeUpstreamConnects(t *testing.T) {
	for _, family := range []struct {
		name string
		ipv6 bool
	}{{"IPv4", false}, {"IPv6", true}} {
		t.Run(family.name, func(t *testing.T) {
			entered := make(chan struct{})
			cancelled := make(chan struct{})
			f := newTCPTunFixture(t, family.ipv6, func(ctx context.Context, _ *M.Metadata) (net.Conn, error) {
				close(entered)
				<-ctx.Done()
				close(cancelled)
				return nil, ctx.Err()
			})
			f.resume()
			f.send(100, 0, testTCPSyn, nil)
			select {
			case <-entered:
			case packet := <-f.packets:
				t.Fatalf("VPN replied before attempting upstream connection: TCP flags %#x", tcpTestSegment(packet)[13])
			case <-time.After(2 * time.Second):
				t.Fatal("upstream connection attempt was not started")
			}
			select {
			case packet := <-f.packets:
				t.Fatalf("VPN replied while upstream connection was pending: TCP flags %#x", tcpTestSegment(packet)[13])
			case <-time.After(100 * time.Millisecond):
			}
			stopTCPEngine(t, f.engine)
			select {
			case <-cancelled:
			default:
				t.Fatal("stopping the engine did not cancel the pending upstream dial")
			}
		})
	}
}

func makeTCPTestPacket(source, destination netip.Addr, sequence, acknowledgment uint32, flags byte, payload []byte) []byte {
	headerSize := 40
	if source.Is4() {
		headerSize = 20
	}
	packet := make([]byte, headerSize+20+len(payload))
	segment := packet[headerSize:]
	binary.BigEndian.PutUint16(segment[0:2], 42001)
	binary.BigEndian.PutUint16(segment[2:4], 443)
	binary.BigEndian.PutUint32(segment[4:8], sequence)
	binary.BigEndian.PutUint32(segment[8:12], acknowledgment)
	segment[12], segment[13] = 5<<4, flags
	binary.BigEndian.PutUint16(segment[14:16], 65535)
	copy(segment[20:], payload)
	if source.Is4() {
		packet[0], packet[8], packet[9] = 0x45, 64, 6
		binary.BigEndian.PutUint16(packet[2:4], uint16(len(packet)))
		copy(packet[12:16], source.AsSlice())
		copy(packet[16:20], destination.AsSlice())
		binary.BigEndian.PutUint16(packet[10:12], internetChecksum(packet[:20]))
	} else {
		packet[0], packet[6], packet[7] = 0x60, 6, 64
		binary.BigEndian.PutUint16(packet[4:6], uint16(len(segment)))
		copy(packet[8:24], source.AsSlice())
		copy(packet[24:40], destination.AsSlice())
	}
	binary.BigEndian.PutUint16(segment[16:18], tcpTestChecksum(source, destination, segment))
	return packet
}

func tcpTestSegment(packet []byte) []byte {
	if packet[0]>>4 == 4 {
		return packet[int(packet[0]&0xf)*4:]
	}
	return packet[40:]
}

func tcpTestChecksum(source, destination netip.Addr, segment []byte) uint16 {
	pseudoSize := 40
	if source.Is4() {
		pseudoSize = 12
	}
	pseudo := make([]byte, pseudoSize+len(segment))
	addressSize := source.BitLen() / 8
	copy(pseudo[:addressSize], source.AsSlice())
	copy(pseudo[addressSize:2*addressSize], destination.AsSlice())
	if source.Is4() {
		pseudo[9] = 6
		binary.BigEndian.PutUint16(pseudo[10:12], uint16(len(segment)))
	} else {
		binary.BigEndian.PutUint32(pseudo[32:36], uint32(len(segment)))
		pseudo[39] = 6
	}
	copy(pseudo[pseudoSize:], segment)
	return internetChecksum(pseudo)
}

func TestTCPRejectedUpstreamResetsWithoutHandshake(t *testing.T) {
	for _, ipv6 := range []bool{false, true} {
		t.Run(tcpFamilyName(ipv6), func(t *testing.T) {
			f := newTCPTunFixture(t, ipv6, func(context.Context, *M.Metadata) (net.Conn, error) {
				return nil, &net.OpError{Op: "dial", Net: "tcp", Err: unix.ENETUNREACH}
			})
			f.resume()
			f.send(100, 0, testTCPSyn, nil)
			segment := f.segment(f.packet(2 * time.Second))
			if segment[13] != testTCPRst|testTCPAck || binary.BigEndian.Uint32(segment[8:12]) != 101 {
				t.Fatalf("unreachable upstream must reject the SYN with RST/ACK, got flags %#x", segment[13])
			}
			if !f.engine.IsHealthy() {
				t.Fatal("an unreachable destination must not terminate other VPN connections")
			}
		})
	}
}

func TestTCPForwardsPayloadAndPreservesHalfClose(t *testing.T) {
	for _, ipv6 := range []bool{false, true} {
		t.Run(tcpFamilyName(ipv6), func(t *testing.T) {
			listener := tcpLoopbackListener(t, ipv6)
			entered, release := make(chan struct{}), make(chan struct{})
			f := newTCPTunFixture(t, ipv6, nil)
			f.state.tcpForwarder.dial = func(ctx context.Context, metadata *M.Metadata) (net.Conn, error) {
				close(entered)
				select {
				case <-release:
				case <-ctx.Done():
					return nil, ctx.Err()
				}
				return f.dialLoopback(ctx, metadata, listener)
			}
			f.resume()
			f.send(100, 0, testTCPSyn, nil)
			select {
			case <-entered:
			case packet := <-f.packets:
				t.Fatalf("received premature TCP flags %#x", f.segment(packet)[13])
			case <-time.After(2 * time.Second):
				t.Fatal("upstream dial did not start")
			}
			select {
			case packet := <-f.packets:
				t.Fatalf("received premature TCP flags %#x", f.segment(packet)[13])
			case <-time.After(100 * time.Millisecond):
			}
			close(release)
			peer := acceptTCPTestPeer(t, listener)
			serverSequence := f.finishHandshake()
			request := bytes.Repeat([]byte("client bytes\x00\xff"), 257)
			clientSequence := uint32(101)
			for offset := 0; offset < len(request); {
				end := min(offset+1000, len(request))
				f.send(clientSequence, serverSequence, testTCPAck|testTCPPsh, request[offset:end])
				clientSequence += uint32(end - offset)
				offset = end
			}
			f.send(clientSequence, serverSequence, testTCPAck|testTCPFin, nil)
			clientSequence++
			observed, err := io.ReadAll(peer)
			if err != nil {
				t.Fatalf("upstream did not receive client half-close: %v", err)
			}
			if !bytes.Equal(observed, request) {
				t.Fatal("upstream request bytes changed")
			}
			response := bytes.Repeat([]byte("server after EOF\x00\xfe"), 271)
			if _, err := peer.Write(response); err != nil {
				t.Fatalf("client half-close prematurely closed server response: %v", err)
			}
			if err := peer.CloseWrite(); err != nil {
				t.Fatal(err)
			}
			if got := f.receiveUntilFIN(clientSequence, serverSequence); !bytes.Equal(got, response) {
				t.Fatal("TUN response bytes changed or were truncated")
			}
			if f.protector.count() != 1 {
				t.Fatalf("protected sockets = %d, want exactly one upstream socket", f.protector.count())
			}
		})
	}
}

func TestTCPStopClosesActiveUpstream(t *testing.T) {
	for _, ipv6 := range []bool{false, true} {
		for _, completeHandshake := range []bool{false, true} {
			stateName := "AwaitingClientACK"
			if completeHandshake {
				stateName = "Established"
			}
			t.Run(tcpFamilyName(ipv6)+"/"+stateName, func(t *testing.T) {
				listener := tcpLoopbackListener(t, ipv6)
				f := newTCPTunFixture(t, ipv6, nil)
				f.state.tcpForwarder.dial = func(ctx context.Context, metadata *M.Metadata) (net.Conn, error) {
					return f.dialLoopback(ctx, metadata, listener)
				}
				f.resume()
				f.send(100, 0, testTCPSyn, nil)
				peer := acceptTCPTestPeer(t, listener)
				if completeHandshake {
					serverSequence := f.finishHandshake()
					f.send(101, serverSequence, testTCPAck|testTCPPsh, []byte("active"))
					buffer := make([]byte, len("active"))
					if _, err := io.ReadFull(peer, buffer); err != nil {
						t.Fatalf("establishing active stream: %v", err)
					}
				} else {
					segment := f.segment(f.packet(2 * time.Second))
					if segment[13]&testTCPSyn == 0 {
						t.Fatal("expected SYN-ACK before withholding client ACK")
					}
				}
				stopTCPEngine(t, f.engine)
				if err := peer.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
					t.Fatal(err)
				}
				_, err := peer.Read(make([]byte, 1))
				if err == nil {
					t.Fatal("stopped engine left upstream socket readable")
				}
				if timeout, ok := err.(net.Error); ok && timeout.Timeout() {
					t.Fatal("stopped engine left upstream socket open")
				}
			})
		}
	}
}

func TestTCPStopFencesEndpointCreation(t *testing.T) {
	for _, ipv6 := range []bool{false, true} {
		t.Run(tcpFamilyName(ipv6), func(t *testing.T) {
			listener := tcpLoopbackListener(t, ipv6)
			f := newTCPTunFixture(t, ipv6, nil)
			f.state.tcpForwarder.dial = func(ctx context.Context, metadata *M.Metadata) (net.Conn, error) {
				return f.dialLoopback(ctx, metadata, listener)
			}
			entered, release := make(chan struct{}), make(chan struct{})
			released := false
			defer func() {
				if !released {
					close(release)
				}
			}()
			create := f.state.tcpForwarder.createEndpoint
			f.state.tcpForwarder.createEndpoint = func(request *tcp.ForwarderRequest, queue *waiter.Queue) (tcpip.Endpoint, tcpip.Error) {
				close(entered)
				<-release
				return create(request, queue)
			}
			f.resume()
			f.send(100, 0, testTCPSyn, nil)
			peer := acceptTCPTestPeer(t, listener)
			select {
			case <-entered:
			case <-time.After(2 * time.Second):
				t.Fatal("local endpoint creation did not start")
			}
			stopped := make(chan struct{})
			cancelled := f.state.tcpForwarder.ctx.Done()
			go func() {
				f.engine.Stop()
				close(stopped)
			}()
			select {
			case <-cancelled:
			case <-time.After(2 * time.Second):
				t.Fatal("Stop did not cancel the forwarder")
			}
			select {
			case <-stopped:
				t.Fatal("Stop returned before the in-flight endpoint creation finished")
			case <-time.After(50 * time.Millisecond):
			}
			close(release)
			released = true
			select {
			case <-stopped:
			case <-time.After(2 * time.Second):
				t.Fatal("Stop left an endpoint handshake registered after stack shutdown")
			}
			if err := peer.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
				t.Fatal(err)
			}
			_, err := peer.Read(make([]byte, 1))
			if err == nil {
				t.Fatal("upstream remained open after cancelled endpoint creation")
			}
			if timeout, ok := err.(net.Error); ok && timeout.Timeout() {
				t.Fatal("upstream did not close after cancelled endpoint creation")
			}
		})
	}
}

func tcpFamilyName(ipv6 bool) string {
	if ipv6 {
		return "IPv6"
	}
	return "IPv4"
}

func tcpLoopbackListener(t *testing.T, ipv6 bool) *net.TCPListener {
	t.Helper()
	address, network := "127.0.0.1:0", "tcp4"
	if ipv6 {
		address, network = "[::1]:0", "tcp6"
	}
	addr, err := net.ResolveTCPAddr(network, address)
	if err != nil {
		t.Fatal(err)
	}
	listener, err := net.ListenTCP(network, addr)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	return listener
}

func acceptTCPTestPeer(t *testing.T, listener *net.TCPListener) *net.TCPConn {
	t.Helper()
	if err := listener.SetDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatal(err)
	}
	peer, err := listener.AcceptTCP()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { peer.Close() })
	if err := peer.SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
		t.Fatal(err)
	}
	return peer
}

func (f *tcpTunFixture) dialLoopback(ctx context.Context, metadata *M.Metadata, listener *net.TCPListener) (net.Conn, error) {
	// Keep valid routed addresses on TUN while restricting real sockets to localhost.
	copy := *metadata
	address := listener.Addr().(*net.TCPAddr).AddrPort()
	copy.DstIP, copy.DstPort = address.Addr(), address.Port()
	proxy := protectedDirectProxy{protect: f.state.protectSocket, fail: f.state.failTerminal}
	return proxy.DialContext(ctx, &copy)
}

func stopTCPEngine(t *testing.T, engine Engine) {
	t.Helper()
	stopped := make(chan struct{})
	go func() { engine.Stop(); close(stopped) }()
	select {
	case <-stopped:
	case <-time.After(2 * time.Second):
		t.Fatal("engine Stop did not finish with active TCP work")
	}
}

func (f *tcpTunFixture) finishHandshake() uint32 {
	f.t.Helper()
	segment := f.segment(f.packet(2 * time.Second))
	if segment[13]&testTCPSyn == 0 || segment[13]&testTCPAck == 0 || segment[13]&testTCPRst != 0 || binary.BigEndian.Uint32(segment[8:12]) != 101 {
		f.t.Fatalf("invalid SYN/ACK from VPN, flags %#x", segment[13])
	}
	next := binary.BigEndian.Uint32(segment[4:8]) + 1
	f.send(101, next, testTCPAck, nil)
	return next
}

func (f *tcpTunFixture) receiveUntilFIN(clientSequence, serverSequence uint32) []byte {
	f.t.Helper()
	var result []byte
	deadline := time.Now().Add(5 * time.Second)
	for {
		remaining := time.Until(deadline)
		if remaining <= 0 {
			f.t.Fatal("TCP response did not finish")
		}
		segment := f.segment(f.packet(remaining))
		flags := segment[13]
		if flags&testTCPRst != 0 {
			f.t.Fatal("TCP stream was reset before server half-close")
		}
		payload := segment[int(segment[12]>>4)*4:]
		sequence := binary.BigEndian.Uint32(segment[4:8])
		if len(payload) != 0 {
			if sequence != serverSequence {
				f.t.Fatalf("unexpected response sequence %d, want %d", sequence, serverSequence)
			}
			result = append(result, payload...)
			serverSequence += uint32(len(payload))
		}
		if flags&testTCPFin != 0 {
			if sequence+uint32(len(payload)) != serverSequence {
				f.t.Fatal("server FIN has an unexpected sequence")
			}
			serverSequence++
		}
		if len(payload) != 0 || flags&testTCPFin != 0 {
			f.send(clientSequence, serverSequence, testTCPAck, nil)
		}
		if flags&testTCPFin != 0 {
			return result
		}
	}
}

func (f *tcpTunFixture) segment(packet []byte) []byte {
	f.t.Helper()
	headerSize := 40
	if f.source.Is4() {
		headerSize = 20
		if len(packet) < headerSize || packet[0] != 0x45 || packet[9] != 6 || int(binary.BigEndian.Uint16(packet[2:4])) != len(packet) || internetChecksum(packet[:headerSize]) != 0 {
			f.t.Fatal("invalid IPv4 response header")
		}
	} else if len(packet) < headerSize || packet[0]>>4 != 6 || packet[6] != 6 || int(binary.BigEndian.Uint16(packet[4:6])) != len(packet)-headerSize {
		f.t.Fatal("invalid IPv6 response header")
	}
	addressSize := f.source.BitLen() / 8
	if !bytes.Equal(packet[headerSize-2*addressSize:headerSize-addressSize], f.destination.AsSlice()) || !bytes.Equal(packet[headerSize-addressSize:headerSize], f.source.AsSlice()) {
		f.t.Fatal("response IP addresses do not match TUN flow")
	}
	segment := packet[headerSize:]
	if len(segment) < 20 || int(segment[12]>>4)*4 < 20 || int(segment[12]>>4)*4 > len(segment) || binary.BigEndian.Uint16(segment[:2]) != 443 || binary.BigEndian.Uint16(segment[2:4]) != 42001 || tcpTestChecksum(f.destination, f.source, segment) != 0 {
		f.t.Fatal("invalid TCP response header or checksum")
	}
	return segment
}
