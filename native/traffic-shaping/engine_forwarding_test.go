package trafficshaping

import (
	"bytes"
	"encoding/binary"
	"net"
	"net/netip"
	"testing"
	"time"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"golang.org/x/sys/unix"
)

type loopbackUDPProxy struct {
	*protectedDirectProxy
	address netip.Addr
}

func (proxy *loopbackUDPProxy) DialUDP(metadata *M.Metadata) (net.PacketConn, error) {
	// gVisor correctly rejects loopback destinations arriving over TUN. Keep
	// the test's IP packet realistic while confining real sockets to localhost.
	metadata.DstIP = proxy.address
	return proxy.protectedDirectProxy.DialUDP(metadata)
}

func TestEngineForwardsUDPPacketsThroughTun(t *testing.T) {
	for _, version := range []struct {
		name, network, loopback, source, destination string
	}{
		{"IPv4", "udp4", "127.0.0.1", "10.111.0.1", "198.18.0.1"},
		{"IPv6", "udp6", "::1", "fd00::1", "2001:db8::1"},
	} {
		t.Run(version.name, func(t *testing.T) {
			testUDPForwarding(t, version.network, netip.MustParseAddr(version.loopback),
				netip.MustParseAddr(version.source), netip.MustParseAddr(version.destination))
		})
	}
}

func testUDPForwarding(t *testing.T, network string, loopback, source, destination netip.Addr) {
	t.Helper()
	server, err := net.ListenUDP(network, net.UDPAddrFromAddrPort(netip.AddrPortFrom(loopback, 0)))
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer unix.Close(fds[1])
	listener := newTerminalRecorder()
	protector := &recordingProtector{allow: true}
	engine, err := CreateEngine(int64(fds[0]), protocolMTU, protector, listener)
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Stop()
	if _, err := engine.ApplyProfile([]byte(`{"downlink_kbps":null,"id":"baseline","uplink_kbps":null}`)); err != nil {
		t.Fatal(err)
	}
	if err := engine.Start(); err != nil {
		t.Fatal(err)
	}
	state := engine.(*mobileEngine).state
	state.tunnel.SetProxy(&loopbackUDPProxy{
		protectedDirectProxy: &protectedDirectProxy{protect: state.protectSocket, fail: state.failTerminal},
		address:              loopback,
	})
	if err := engine.Resume(); err != nil {
		t.Fatal(err)
	}
	if err := unix.SetsockoptTimeval(fds[1], unix.SOL_SOCKET, unix.SO_RCVTIMEO, &unix.Timeval{Sec: 5}); err != nil {
		t.Fatal(err)
	}
	for _, phase := range []string{"baseline", "limited"} {
		if phase == "limited" {
			if _, err := engine.ApplyProfile([]byte(`{"downlink_kbps":512,"id":"limited","uplink_kbps":256}`)); err != nil {
				t.Fatal(err)
			}
			if err := engine.Resume(); err != nil {
				t.Fatal(err)
			}
		}
		payload := []byte("tun-udp-" + phase)
		serverPort := uint16(server.LocalAddr().(*net.UDPAddr).Port)
		packet, headerSize := udpTestPacket(source, destination, serverPort, payload)
		if err := server.SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
			t.Fatal(err)
		}
		if _, err := unix.Write(fds[1], packet); err != nil {
			t.Fatal(err)
		}
		buffer := make([]byte, protocolMTU)
		n, peer, err := server.ReadFromUDP(buffer)
		if err != nil {
			t.Fatalf("%s outbound UDP did not reach server: %v; protected sockets=%d; uplink packets=%d", phase, err, protector.count(), engine.Snapshot().GetUplinkPackets())
		}
		if !bytes.Equal(buffer[:n], payload) {
			t.Fatal("forwarded UDP payload changed")
		}
		if _, err := server.WriteToUDP(payload, peer); err != nil {
			t.Fatal(err)
		}
		n, err = unix.Read(fds[1], buffer)
		if err != nil {
			t.Fatalf("%s UDP response did not reach TUN: %v", phase, err)
		}
		if n != len(packet) || !bytes.Equal(buffer[headerSize+8:n], payload) {
			t.Fatal("UDP response payload changed")
		}
		if source.Is4() {
			if buffer[0] != 0x45 || int(binary.BigEndian.Uint16(buffer[2:4])) != n || buffer[9] != 17 ||
				internetChecksum(buffer[:headerSize]) != 0 {
				t.Fatal("UDP response has an invalid IPv4 header")
			}
		} else if buffer[0]>>4 != 6 || int(binary.BigEndian.Uint16(buffer[4:6])) != n-headerSize || buffer[6] != 17 {
			t.Fatal("UDP response has an invalid IPv6 header")
		}
		if !bytes.Equal(buffer[headerSize-2*source.BitLen()/8:headerSize-source.BitLen()/8], destination.AsSlice()) ||
			!bytes.Equal(buffer[headerSize-source.BitLen()/8:headerSize], source.AsSlice()) {
			t.Fatal("UDP response IP addresses do not match the original TUN flow")
		}
		udp := buffer[headerSize:n]
		if binary.BigEndian.Uint16(udp[:2]) != serverPort || binary.BigEndian.Uint16(udp[2:4]) != 42000 ||
			int(binary.BigEndian.Uint16(udp[4:6])) != len(udp) {
			t.Fatal("UDP response ports or datagram length do not match the original TUN flow")
		}
		checksum := binary.BigEndian.Uint16(udp[6:8])
		if (source.Is6() && checksum == 0) || (checksum != 0 && udpTestChecksum(destination, source, udp) != 0) {
			t.Fatal("UDP response has an invalid transport checksum")
		}
		// Drain the forwarding boundary before observing counters or replacing
		// the profile, even when the peer read races the native write return.
		if err := engine.Suspend(); err != nil {
			t.Fatal(err)
		}
		snapshot := engine.Snapshot()
		if snapshot.GetUplinkPackets() != 1 || snapshot.GetDownlinkPackets() != 1 ||
			snapshot.GetUplinkBytes() != int64(len(packet)) || snapshot.GetDownlinkBytes() != int64(n) {
			t.Fatalf("%s TUN counters do not account for the complete IP roundtrip", phase)
		}
	}
	if protector.count() != 1 {
		t.Fatalf("protected sockets=%d, want 1", protector.count())
	}
	select {
	case code := <-listener.codes:
		t.Fatalf("packet forwarding failed terminally: %s", code)
	default:
	}
}

func udpTestPacket(source, destination netip.Addr, destinationPort uint16, payload []byte) ([]byte, int) {
	headerSize := 40
	if source.Is4() {
		headerSize = 20
	}
	packet := make([]byte, headerSize+8+len(payload))
	udp := packet[headerSize:]
	binary.BigEndian.PutUint16(udp[:2], 42000)
	binary.BigEndian.PutUint16(udp[2:4], destinationPort)
	binary.BigEndian.PutUint16(udp[4:6], uint16(len(udp)))
	copy(udp[8:], payload)
	if source.Is4() {
		packet[0], packet[8], packet[9] = 0x45, 64, 17
		binary.BigEndian.PutUint16(packet[2:4], uint16(len(packet)))
		copy(packet[12:16], source.AsSlice())
		copy(packet[16:20], destination.AsSlice())
		binary.BigEndian.PutUint16(packet[10:12], internetChecksum(packet[:20]))
	} else {
		packet[0], packet[6], packet[7] = 0x60, 17, 64
		binary.BigEndian.PutUint16(packet[4:6], uint16(len(udp)))
		copy(packet[8:24], source.AsSlice())
		copy(packet[24:40], destination.AsSlice())
		checksum := udpTestChecksum(source, destination, udp)
		if checksum == 0 {
			checksum = 0xffff
		}
		binary.BigEndian.PutUint16(udp[6:8], checksum)
	}
	return packet, headerSize
}

func udpTestChecksum(source, destination netip.Addr, datagram []byte) uint16 {
	pseudoSize := 40
	if source.Is4() {
		pseudoSize = 12
	}
	pseudo := make([]byte, pseudoSize+len(datagram))
	addressSize := source.BitLen() / 8
	copy(pseudo[:addressSize], source.AsSlice())
	copy(pseudo[addressSize:2*addressSize], destination.AsSlice())
	if source.Is4() {
		pseudo[9] = 17
		binary.BigEndian.PutUint16(pseudo[10:12], uint16(len(datagram)))
	} else {
		binary.BigEndian.PutUint32(pseudo[32:36], uint32(len(datagram)))
		pseudo[39] = 17
	}
	copy(pseudo[pseudoSize:], datagram)
	return internetChecksum(pseudo)
}

func internetChecksum(data []byte) uint16 {
	var sum uint32
	for len(data) >= 2 {
		sum += uint32(binary.BigEndian.Uint16(data[:2]))
		data = data[2:]
	}
	if len(data) != 0 {
		sum += uint32(data[0]) << 8
	}
	for sum > 0xffff {
		sum = sum>>16 + sum&0xffff
	}
	return ^uint16(sum)
}
