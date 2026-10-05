package trafficshaping

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"runtime"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/core/option"
	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
)

// A real production stack/FQ and a second pinned TCP stack negotiate their own
// SACK and ACKs. One shared TCP processor must retain duplex progress every
// second, payload integrity and bounded storage, even under queue congestion.
// The complete window also retains the Android acceptance floor of 85%.
func TestControlledColdStartDuplexTCP(t *testing.T) {
	// TCP sizes its shared dispatcher from GOMAXPROCS when the stack is made.
	// This test is not parallel: force both connections to share a processor,
	// then restore the setting only after all stack and transport cleanup.
	previousProcessors := runtime.GOMAXPROCS(1)
	t.Cleanup(func() { runtime.GOMAXPROCS(previousProcessors) })
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	start := make(chan struct{})
	var servers, clients, bridge sync.WaitGroup
	var offered, uploaded, downloaded atomic.Uint64
	var sackConnections atomic.Uint32
	failures := make(chan error, 4)
	reportFailure := func(operation string, err error) {
		if ctx.Err() != nil {
			return
		}
		select {
		case failures <- fmt.Errorf("%s: %w", operation, err):
		default:
		}
	}
	engine, state, tun, terminal := newColdStartEngine(t, func(_ context.Context, m *M.Metadata) (net.Conn, error) {
		origin, remote := net.Pipe()
		servers.Add(1)
		go func() {
			defer servers.Done()
			defer remote.Close()
			stop := context.AfterFunc(ctx, func() { _ = remote.Close() })
			defer stop()
			select {
			case <-start:
			case <-ctx.Done():
				return
			}
			data := bytes.Repeat([]byte{0x5a}, 32*1024)
			if m.DstPort == 443 {
				for {
					n, err := remote.Write(data)
					offered.Add(uint64(n))
					if err != nil {
						reportFailure("download producer", err)
						return
					}
				}
			}
			for {
				n, err := remote.Read(data)
				for _, b := range data[:n] {
					if b != 0x55 {
						reportFailure("upload payload", io.ErrUnexpectedEOF)
						return
					}
				}
				uploaded.Add(uint64(n))
				if err != nil {
					reportFailure("upload receiver", err)
					return
				}
			}
		}()
		return origin, nil
	})
	peer := stack.New(stack.Options{NetworkProtocols: []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol}, TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol}})
	link := channel.New(128, protocolMTU, "")
	t.Cleanup(func() {
		cancel()
		engine.Stop()
		_ = tun.Close()
		peer.Close()
		link.Close()
		peer.Wait()
		bridge.Wait()
		clients.Wait()
		servers.Wait()
	})
	if err := option.WithDefault()(peer); err != nil {
		t.Fatal(err)
	}
	if err := peer.CreateNIC(1, link); err != nil {
		t.Fatal(err)
	}
	if err := peer.AddProtocolAddress(1, tcpip.ProtocolAddress{Protocol: header.IPv4ProtocolNumber, AddressWithPrefix: tcpip.AddrFrom4([4]byte{10, 111, 222, 1}).WithPrefix()}, stack.AddressProperties{}); err != nil {
		t.Fatal(err)
	}
	peer.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}})
	bridge.Add(2)
	go func() {
		defer bridge.Done()
		for {
			p := link.ReadContext(ctx)
			if p == nil {
				return
			}
			b := p.ToBuffer()
			data := b.Flatten()
			n, err := tun.Write(data)
			if err == nil && n != len(data) {
				err = io.ErrShortWrite
			}
			b.Release()
			p.DecRef()
			if err != nil {
				reportFailure("peer-to-TUN transport", err)
				return
			}
		}
	}()
	go func() {
		defer bridge.Done()
		data := make([]byte, protocolMTU+1)
		for {
			n, err := tun.Read(data)
			if err != nil {
				reportFailure("TUN-to-peer transport", err)
				return
			}
			if n > protocolMTU || n < header.IPv4MinimumSize || !header.IPv4(data[:n]).IsValid(n) {
				reportFailure("TUN packet framing", io.ErrUnexpectedEOF)
				return
			}
			ip := header.IPv4(data[:n])
			if ip.TransportProtocol() != header.TCPProtocolNumber || int(ip.TotalLength()) != n || n-int(ip.HeaderLength()) < header.TCPMinimumSize {
				reportFailure("TUN TCP framing", io.ErrUnexpectedEOF)
				return
			}
			segment := header.TCP(data[ip.HeaderLength():n])
			if int(segment.DataOffset()) < header.TCPMinimumSize || int(segment.DataOffset()) > len(segment) {
				reportFailure("TUN TCP options", io.ErrUnexpectedEOF)
				return
			}
			if segment.Flags().Contains(header.TCPFlagSyn | header.TCPFlagAck) {
				if err := recordColdStartSACK(&sackConnections, segment.SourcePort(), header.ParseSynOptions(segment.Options(), true).SACKPermitted); err != nil {
					reportFailure("SYN-ACK connection proof", err)
					return
				}
			}
			p := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(bytes.Clone(data[:n]))})
			link.InjectInbound(header.IPv4ProtocolNumber, p)
			p.DecRef()
		}
	}()

	if err := engine.Resume(); err != nil {
		t.Fatal(err)
	}
	dial := func(port uint16) *gonet.TCPConn {
		t.Helper()
		c, stop := context.WithTimeout(ctx, 2*time.Second)
		defer stop()
		conn, err := gonet.DialContextTCP(c, peer, tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{198, 18, 0, 1}), Port: port}, header.IPv4ProtocolNumber)
		if err != nil {
			t.Fatal(err)
		}
		return conn
	}
	down, up := dial(443), dial(444)
	if sackConnections.Load() != 3 {
		t.Fatal("both real TCP handshakes must negotiate SACK")
	}
	defer down.Close()
	defer up.Close()
	clients.Add(2)
	go func() {
		defer clients.Done()
		data := make([]byte, 32*1024)
		for {
			n, err := down.Read(data)
			for _, b := range data[:n] {
				if b != 0x5a {
					reportFailure("download payload", io.ErrUnexpectedEOF)
					return
				}
			}
			downloaded.Add(uint64(n))
			if err != nil {
				reportFailure("download receiver", err)
				return
			}
		}
	}()
	go func() {
		defer clients.Done()
		data := bytes.Repeat([]byte{0x55}, 32*1024)
		select {
		case <-start:
		case <-ctx.Done():
			return
		}
		for {
			if _, err := up.Write(data); err != nil {
				reportFailure("upload producer", err)
				return
			}
		}
	}()
	close(start)
	started := time.Now()
	var last coldStartSample
	// Keep each complete second visible to the liveness oracle. The detailed
	// 10 ms baseline/A-B traces are archived separately from this regression.
	var previous coldStartSample
	for second := 1; second <= 20; second++ {
		at := time.Duration(second) * time.Second
		if at > 0 {
			timer := time.NewTimer(time.Until(started.Add(at)))
			select {
			case <-timer.C:
			case err := <-failures:
				timer.Stop()
				t.Fatalf("bridge failed: %T %v", err, err)
			case code := <-terminal.codes:
				timer.Stop()
				t.Fatalf("terminal: %s", code)
			}
		}
		tcpStats := state.stack.Stats().TCP
		uq, dq := state.shaped.queue.snapshot(), state.downlinkQueue.snapshot()
		last = coldStartSample{Seconds: time.Since(started).Seconds(), DownlinkAdmitted: state.counters.downlinkBytes.Load(), DownloadedUnique: downloaded.Load(), UplinkAccepted: uploaded.Load(), DownlinkOffered: offered.Load(), DownlinkThrottleNanos: state.downlink.throttledDuration(), DownlinkQueuedBytes: dq.queuedBytes, DownlinkCapacityDrops: dq.capacityDropPackets, DownlinkCodelDrops: dq.codelDropPackets, UplinkCapacityDrops: uq.capacityDropPackets, UplinkCodelDrops: uq.codelDropPackets, TCPRetransmits: tcpStats.Retransmits.Value(), TCPRTOs: tcpStats.Timeouts.Value(), TCPSACKRecovery: tcpStats.SACKRecovery.Value(), PeerSendErrors: peer.Stats().TCP.SegmentSendErrors.Value(), EngineSendErrors: tcpStats.SegmentSendErrors.Value()}
		if dq.queuedBytes < 0 || dq.queuedBytes > packetQueueMaxBytes || uq.queuedBytes < 0 || uq.queuedBytes > packetQueueMaxBytes ||
			dq.queuedPackets < 0 || dq.queuedPackets > packetQueueMaxPackets || uq.queuedPackets < 0 || uq.queuedPackets > packetQueueMaxPackets {
			t.Fatalf("queue exceeded fixed bounds at second %d: up=%+v down=%+v", second, uq, dq)
		}
		if dq.codelDropPackets != 0 {
			t.Fatalf("controlled stack downlink used CoDel at second %d: %+v", second, dq)
		}
		if last.DownloadedUnique <= previous.DownloadedUnique || last.UplinkAccepted <= previous.UplinkAccepted {
			t.Fatalf("duplex stopped making unique progress in second %d: before=%+v after=%+v", second, previous, last)
		}
		select {
		case err := <-failures:
			t.Fatalf("transport or payload failed: %v", err)
		default:
		}
		previous = last
	}
	if last.PeerSendErrors != 0 || last.EngineSendErrors != 0 {
		t.Fatal("test bridge or endpoint rejected TCP packets")
	}
	const minimumUniqueBytes = 512_000 * 20 / 8 * 85 / 100
	if last.DownloadedUnique < minimumUniqueBytes || last.UplinkAccepted < minimumUniqueBytes {
		t.Fatalf("20-second duplex payload fell below 85%% of 512 kbps (%d bytes per direction): %+v", minimumUniqueBytes, last)
	}
	if last.DownloadedUnique > last.DownlinkAdmitted {
		t.Fatal("unique bytes exceeded L3 admission")
	}
	b, err := json.Marshal(last)
	if err != nil {
		t.Fatal(err)
	}
	t.Log(string(b))
	// Congestion drops and retransmit/RTO totals are diagnostics, not a zero-loss oracle.
}

// Record only which of the two known test connections negotiated SACK. A
// retransmitted SYN-ACK must never stand in for proof of the other connection.
func recordColdStartSACK(proof *atomic.Uint32, sourcePort uint16, permitted bool) error {
	var bit uint32
	switch sourcePort {
	case 443:
		bit = 1
	case 444:
		bit = 2
	default:
		return errors.New("unexpected test connection")
	}
	if permitted {
		proof.Or(bit)
	}
	return nil
}

func TestColdStartSACKProofRequiresBothConnections(t *testing.T) {
	var proof atomic.Uint32
	for range 2 {
		if err := recordColdStartSACK(&proof, 443, true); err != nil {
			t.Fatal(err)
		}
	}
	if err := recordColdStartSACK(&proof, 444, false); err != nil {
		t.Fatal(err)
	}
	if proof.Load() != 1 {
		t.Fatal("duplicate SYN-ACKs proved an unnegotiated connection")
	}
	if err := recordColdStartSACK(&proof, 445, true); err == nil || proof.Load() != 1 {
		t.Fatal("unknown connection must fail without changing the proof")
	}
	if err := recordColdStartSACK(&proof, 444, true); err != nil || proof.Load() != 3 {
		t.Fatal("both known SACK connections must complete the proof")
	}
}

// The test wire is a bounded, pollable stream with explicit packet framing.
// Darwin AF_UNIX datagrams can return ENOBUFS even in blocking mode when a peer
// is full. Framing a stream supplies lossless backpressure and cancellation;
// only the synthetic TUN transport changes, before the production stack starts.
func newColdStartEngine(t *testing.T, dial func(context.Context, *M.Metadata) (net.Conn, error)) (Engine, *engineState, *coldStartPacketWire, *terminalRecorder) {
	t.Helper()
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_STREAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	for _, fd := range fds {
		for _, option := range []int{unix.SO_SNDBUF, unix.SO_RCVBUF} {
			if err := unix.SetsockoptInt(fd, unix.SOL_SOCKET, option, 64<<10); err != nil {
				_ = unix.Close(fds[0])
				_ = unix.Close(fds[1])
				t.Fatal(err)
			}
		}
	}
	if err := unix.SetNonblock(fds[1], true); err != nil {
		_ = unix.Close(fds[0])
		_ = unix.Close(fds[1])
		t.Fatal(err)
	}
	peer := &coldStartPacketWire{File: os.NewFile(uintptr(fds[1]), "cold-start-test-tun-peer")}
	terminal := newTerminalRecorder()
	engine, err := CreateEngine(int64(fds[0]), protocolMTU, &recordingProtector{allow: true}, terminal)
	if err != nil {
		_ = peer.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() {
		engine.Stop()
		_ = peer.Close()
	})
	if _, err := engine.ApplyProfile([]byte(`{"downlink_kbps":512,"id":"cold-start","uplink_kbps":512}`)); err != nil {
		t.Fatal(err)
	}
	state := engine.(*mobileEngine).state
	state.tun.device = &coldStartPacketWire{File: state.tun.device.(*os.File)}
	if err := engine.Start(); err != nil {
		t.Fatal(err)
	}
	state.tcpForwarder.dial = dial
	return engine, state, peer, terminal
}

// One read and one write may run concurrently, as for a TUN descriptor. The
// production endpoint already enforces one writer; the test peer also has one.
// Storage is one fixed MTU frame per in-flight write, independent of traffic.
type coldStartPacketWire struct{ *os.File }

func (w *coldStartPacketWire) Write(packet []byte) (int, error) {
	if len(packet) == 0 || len(packet) > protocolMTU {
		return 0, errInvalidTunPacket
	}
	var frame [protocolMTU + 2]byte
	binary.BigEndian.PutUint16(frame[:2], uint16(len(packet)))
	copy(frame[2:], packet)
	n, err := w.File.Write(frame[:2+len(packet)])
	if err == nil && n != len(packet)+2 {
		err = io.ErrShortWrite
	}
	return max(0, n-2), err
}

func (w *coldStartPacketWire) Read(packet []byte) (int, error) {
	var size [2]byte
	if _, err := io.ReadFull(w.File, size[:]); err != nil {
		return 0, err
	}
	count := int(binary.BigEndian.Uint16(size[:]))
	if count == 0 || count > protocolMTU || count > len(packet) {
		return 0, errInvalidTunPacket
	}
	return io.ReadFull(w.File, packet[:count])
}

func TestColdStartTransportBlockedWriteIsCancelled(t *testing.T) {
	engine, _, peer, _ := newColdStartEngine(t, nil)
	// The suspended engine does not drain the TUN. Fill the peer's bounded
	// socket buffer using ordinary Go writes until the explicit deadline.
	defer engine.Stop()
	if err := peer.SetWriteDeadline(time.Now().Add(50 * time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	packet := make([]byte, protocolMTU)
	for {
		n, err := peer.Write(packet)
		if errors.Is(err, os.ErrDeadlineExceeded) {
			break
		}
		if err != nil || n != len(packet) {
			t.Fatalf("synthetic TUN must backpressure without losing a datagram: n=%d err=%v", n, err)
		}
	}
	if err := peer.SetWriteDeadline(time.Time{}); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { _, err := peer.Write(packet); done <- err }()
	select {
	case err := <-done:
		t.Fatalf("full transport write returned before cancellation: %v", err)
	case <-time.After(20 * time.Millisecond):
	}
	if err := peer.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if !errors.Is(err, os.ErrClosed) {
			t.Fatalf("blocked transport write must observe close: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("closing test transport did not join its blocked write")
	}
}

type coldStartSample struct {
	PeerSendErrors        uint64  `json:"peer_send_errors"`
	EngineSendErrors      uint64  `json:"engine_send_errors"`
	Seconds               float64 `json:"seconds"`
	DownlinkAdmitted      uint64  `json:"downlink_admitted"`
	DownloadedUnique      uint64  `json:"downloaded_unique"`
	UplinkAccepted        uint64  `json:"uplink_accepted"`
	DownlinkOffered       uint64  `json:"downlink_offered"`
	DownlinkThrottleNanos uint64  `json:"downlink_throttle_ns"`
	DownlinkQueuedBytes   int     `json:"downlink_queued_bytes"`
	DownlinkCapacityDrops uint64  `json:"downlink_capacity_drops"`
	DownlinkCodelDrops    uint64  `json:"downlink_codel_drops"`
	UplinkCapacityDrops   uint64  `json:"uplink_capacity_drops"`
	UplinkCodelDrops      uint64  `json:"uplink_codel_drops"`
	TCPRetransmits        uint64  `json:"tcp_retransmits"`
	TCPRTOs               uint64  `json:"tcp_rtos"`
	TCPSACKRecovery       uint64  `json:"tcp_sack_recovery"`
}
