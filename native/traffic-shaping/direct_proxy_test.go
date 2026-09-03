package trafficshaping

import (
	"bytes"
	"context"
	"io"
	"net"
	"net/netip"
	"sync"
	"testing"
	"time"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
)

type recordingProtector struct {
	mu     sync.Mutex
	allow  bool
	called []int64
}

func (protector *recordingProtector) Protect(fd int64) bool {
	protector.mu.Lock()
	protector.called = append(protector.called, fd)
	protector.mu.Unlock()
	return protector.allow
}

func (protector *recordingProtector) count() int {
	protector.mu.Lock()
	defer protector.mu.Unlock()
	return len(protector.called)
}

func readyDirectProxy(protect func(int64) bool, fail func(string)) *protectedDirectProxy {
	return &protectedDirectProxy{
		protect: protect, fail: fail,
	}
}

func TestDirectProxyProtectsTCPAndUDPSockets(t *testing.T) {
	protector := &recordingProtector{allow: true}
	proxy := readyDirectProxy(
		protector.Protect,
		func(string) { t.Error("successful protection reported terminal failure") },
	)

	tcpListener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer tcpListener.Close()
	tcpAddress := tcpListener.Addr().(*net.TCPAddr)
	tcpAccepted := make(chan net.Conn, 1)
	go func() {
		connection, acceptErr := tcpListener.Accept()
		if acceptErr == nil {
			tcpAccepted <- connection
		}
	}()
	tcpConnection, err := proxy.DialContext(context.Background(), &M.Metadata{
		Network: M.TCP,
		DstIP:   netip.MustParseAddr(tcpAddress.IP.String()),
		DstPort: uint16(tcpAddress.Port),
	})
	if err != nil {
		t.Fatalf("dial protected TCP: %v", err)
	}
	defer tcpConnection.Close()
	select {
	case accepted := <-tcpAccepted:
		defer accepted.Close()
	case <-time.After(time.Second):
		t.Fatal("TCP listener did not accept protected connection")
	}

	udpServer, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	defer udpServer.Close()
	udpAddress := udpServer.LocalAddr().(*net.UDPAddr)
	udpConnection, err := proxy.DialUDP(&M.Metadata{
		Network: M.UDP,
		DstIP:   netip.MustParseAddr(udpAddress.IP.String()),
		DstPort: uint16(udpAddress.Port),
	})
	if err != nil {
		t.Fatalf("create protected UDP socket: %v", err)
	}
	defer udpConnection.Close()
	dnsQuery := []byte{0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 0x00, 0x00, 0x01, 0x00, 0x01}
	if _, err := udpConnection.WriteTo(dnsQuery, udpServer.LocalAddr()); err != nil {
		t.Fatalf("write UDP: %v", err)
	}
	if err := udpServer.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	received := make([]byte, 512)
	count, _, err := udpServer.ReadFromUDP(received)
	if err != nil {
		t.Fatalf("read UDP: %v", err)
	}
	if !bytes.Equal(received[:count], dnsQuery) {
		t.Fatal("DNS-shaped UDP payload changed in the direct transport")
	}

	if got := protector.count(); got != 2 {
		t.Fatalf("protector called %d times for one TCP and one UDP socket, want 2", got)
	}
}

func TestDirectProxyRelaysTCPPayloadWithoutMutation(t *testing.T) {
	protector := &recordingProtector{allow: true}
	proxy := readyDirectProxy(
		protector.Protect,
		func(code string) { t.Fatalf("transport relay failed terminally: %s", code) },
	)
	listener, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	payload := make([]byte, 18_000)
	received := make(chan error, 1)
	go func() {
		connection, acceptErr := listener.AcceptTCP()
		if acceptErr != nil {
			received <- acceptErr
			return
		}
		defer connection.Close()
		_, readErr := io.ReadFull(connection, make([]byte, len(payload)))
		received <- readErr
	}()
	address := listener.Addr().(*net.TCPAddr)
	connection, err := proxy.DialContext(context.Background(), &M.Metadata{
		Network: M.TCP,
		DstIP:   netip.MustParseAddr(address.IP.String()),
		DstPort: uint16(address.Port),
	})
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	if count, writeErr := connection.Write(payload); writeErr != nil || count != len(payload) {
		t.Fatalf("write = %d, %v", count, writeErr)
	}
	if err := <-received; err != nil {
		t.Fatal(err)
	}
}

func TestDirectProxyKeepsTCPConnectionAcrossProfileSwap(t *testing.T) {
	protector := &recordingProtector{allow: true}
	proxy := readyDirectProxy(
		protector.Protect,
		func(code string) { t.Fatalf("profile swap failed terminally: %s", code) },
	)
	listener, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	received := make(chan []byte, 1)
	go func() {
		connection, acceptErr := listener.AcceptTCP()
		if acceptErr != nil {
			received <- nil
			return
		}
		defer connection.Close()
		payload := make([]byte, len("before-after"))
		if _, readErr := io.ReadFull(connection, payload); readErr != nil {
			received <- nil
			return
		}
		received <- payload
	}()
	address := listener.Addr().(*net.TCPAddr)
	connection, err := proxy.DialContext(context.Background(), &M.Metadata{
		Network: M.TCP,
		DstIP:   netip.MustParseAddr(address.IP.String()),
		DstPort: uint16(address.Port),
	})
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	if _, err := connection.Write([]byte("before-")); err != nil {
		t.Fatal(err)
	}
	if _, err := connection.Write([]byte("after")); err != nil {
		t.Fatal(err)
	}
	if got := <-received; !bytes.Equal(got, []byte("before-after")) {
		t.Fatalf("connection payload across profile swap = %q", got)
	}
	if got := protector.count(); got != 1 {
		t.Fatalf("profile swap created %d protected TCP sockets, want one", got)
	}
}

func TestDirectProxyProtectsIPv6TCPAndUDP(t *testing.T) {
	protector := &recordingProtector{allow: true}
	proxy := readyDirectProxy(
		protector.Protect,
		func(string) { t.Error("successful IPv6 protection reported terminal failure") },
	)

	tcpListener, err := net.ListenTCP("tcp6", &net.TCPAddr{IP: net.IPv6loopback})
	if err != nil {
		t.Skipf("IPv6 loopback is unavailable: %v", err)
	}
	defer tcpListener.Close()
	tcpAddress := tcpListener.Addr().(*net.TCPAddr)
	if err := tcpListener.SetDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	accepted := make(chan net.Conn, 1)
	go func() {
		connection, acceptErr := tcpListener.Accept()
		if acceptErr == nil {
			accepted <- connection
		}
	}()
	tcpConnection, err := proxy.DialContext(context.Background(), &M.Metadata{
		Network: M.TCP,
		DstIP:   netip.IPv6Loopback(),
		DstPort: uint16(tcpAddress.Port),
	})
	if err != nil {
		t.Fatalf("dial protected IPv6 TCP: %v", err)
	}
	defer tcpConnection.Close()
	select {
	case connection := <-accepted:
		defer connection.Close()
	case <-time.After(time.Second):
		t.Fatal("IPv6 TCP listener did not accept connection")
	}

	udpServer, err := net.ListenUDP("udp6", &net.UDPAddr{IP: net.IPv6loopback})
	if err != nil {
		t.Skipf("IPv6 UDP loopback is unavailable: %v", err)
	}
	defer udpServer.Close()
	udpAddress := udpServer.LocalAddr().(*net.UDPAddr)
	udpConnection, err := proxy.DialUDP(&M.Metadata{
		Network: M.UDP,
		DstIP:   netip.IPv6Loopback(),
		DstPort: uint16(udpAddress.Port),
	})
	if err != nil {
		t.Fatalf("create protected IPv6 UDP socket: %v", err)
	}
	defer udpConnection.Close()
	if _, err := udpConnection.WriteTo([]byte{1}, udpAddress); err != nil {
		t.Fatalf("write IPv6 UDP: %v", err)
	}
	if err := udpServer.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	if _, _, err := udpServer.ReadFromUDP(make([]byte, 8)); err != nil {
		t.Fatalf("read IPv6 UDP: %v", err)
	}
	if got := protector.count(); got != 2 {
		t.Fatalf("protector called %d times for IPv6 TCP and UDP, want 2", got)
	}
}

func TestDirectProxyFailsClosedWhenProtectionIsRejected(t *testing.T) {
	protector := &recordingProtector{allow: false}
	failures := make(chan string, 1)
	proxy := &protectedDirectProxy{protect: protector.Protect, fail: func(code string) { failures <- code }}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	address := listener.Addr().(*net.TCPAddr)
	connection, err := proxy.DialContext(context.Background(), &M.Metadata{
		Network: M.TCP,
		DstIP:   netip.MustParseAddr(address.IP.String()),
		DstPort: uint16(address.Port),
	})
	if connection != nil {
		connection.Close()
		t.Fatal("unprotected TCP connection was returned")
	}
	if err == nil {
		t.Fatal("protection rejection did not fail the dial")
	}
	select {
	case code := <-failures:
		if code != TerminalProtectFailed {
			t.Fatalf("failure code = %q", code)
		}
	case <-time.After(time.Second):
		t.Fatal("protection rejection did not report terminal failure")
	}
}

func TestDirectProxyRecoversProtectorPanicAsFailure(t *testing.T) {
	failures := make(chan string, 1)
	proxy := &protectedDirectProxy{
		protect: func(int64) bool { panic("sensitive platform detail") },
		fail:    func(code string) { failures <- code },
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	address := listener.Addr().(*net.TCPAddr)
	_, err = proxy.DialContext(context.Background(), &M.Metadata{
		Network: M.TCP,
		DstIP:   netip.MustParseAddr(address.IP.String()),
		DstPort: uint16(address.Port),
	})
	if err == nil {
		t.Fatal("protector panic did not fail closed")
	}
	if code := <-failures; code != TerminalProtectFailed {
		t.Fatalf("failure code = %q", code)
	}
}
