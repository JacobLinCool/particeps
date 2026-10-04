package trafficshaping

import (
	"bytes"
	"encoding/binary"
	"testing"
)

func ipv6ClassifierPacket(next byte, payload []byte) []byte {
	packet := make([]byte, 40+len(payload))
	packet[0], packet[6], packet[7] = 0x60, next, 64
	binary.BigEndian.PutUint16(packet[4:6], uint16(len(payload)))
	packet[8], packet[23], packet[24], packet[39] = 0x20, 1, 0x20, 2
	copy(packet[40:], payload)
	return packet
}

func classifierKey(t *testing.T, packet []byte) flowKey {
	t.Helper()
	key, err := classifyUplinkPacket(packet)
	if err != nil {
		t.Fatal(err)
	}
	return key
}

func TestClassifierPreservesFiveTupleAcrossPayloadOptionsAndExtensions(t *testing.T) {
	udp4 := queueTestPacket(64, 7, 2345)
	want4 := classifierKey(t, udp4)
	options4 := append(append(append([]byte{}, udp4[:20]...), 1, 1, 1, 1), udp4[20:]...)
	options4[0] = 0x46
	binary.BigEndian.PutUint16(options4[2:4], uint16(len(options4)))
	options4[len(options4)-1] = 99
	if classifierKey(t, options4) != want4 {
		t.Fatal("IPv4 options or payload changed the flow")
	}
	tcp := make([]byte, 28)
	copy(tcp[:4], udp4[20:24])
	tcp[12] = 0x60 // Four bytes of TCP options.
	udp6 := ipv6ClassifierPacket(17, udp4[20:])
	tcp6 := ipv6ClassifierPacket(6, tcp)
	for _, original := range [][]byte{udp6, tcp6} {
		want := classifierKey(t, original)
		// Hop-by-hop -> routing -> destination -> AH -> original protocol.
		extensions := []byte{43, 0, 0, 0, 0, 0, 0, 0, 60, 0, 0, 0, 0, 0, 0, 0,
			51, 0, 0, 0, 0, 0, 0, 0, original[6], 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
		wrapped := ipv6ClassifierPacket(0, append(extensions, original[40:]...))
		if classifierKey(t, wrapped) != want {
			t.Fatal("IPv6 extensions changed the transport flow")
		}
	}
	different := append([]byte{}, udp4...)
	different[21]++
	if classifierKey(t, different) == want4 {
		t.Fatal("source ports were omitted")
	}
	if classifierKey(t, tcp6) == classifierKey(t, udp6) {
		t.Fatal("transport protocol was omitted")
	}
}

func TestClassifierAllFragmentsUseOneCoarseFlow(t *testing.T) {
	first4 := queueTestPacket(64, 0, 1234)
	first4[6] = 0x20 // MF, including the first fragment.
	later4 := append([]byte{}, first4...)
	later4[6], later4[7] = 0, 1
	for i := 20; i < len(later4); i++ {
		later4[i] = 0xff
	}
	later4[4], later4[5] = 9, 8 // IDs do not split the flow either.
	firstKey := classifierKey(t, first4)
	if firstKey != classifierKey(t, later4) || firstKey[34] != 0 || firstKey[35] != 0 {
		t.Fatal("first and later IPv4 fragments were split")
	}
	for _, next := range []byte{6, 17, 60} {
		first := []byte{next, 0, 0, 1, 0, 0, 0, 1}
		later := []byte{next, 0, 0, 8, 0, 0, 0, 1}
		atomic := []byte{next, 0, 0, 0, 0, 0, 0, 2}
		want := classifierKey(t, ipv6ClassifierPacket(44, append(first, make([]byte, 32)...)))
		for _, fragment := range [][]byte{later, atomic} {
			packet := ipv6ClassifierPacket(44, append(fragment, []byte{1, 2, 3, 4}...))
			if classifierKey(t, packet) != want {
				t.Fatal("IPv6 fragments depended on offset, ID or payload")
			}
		}
		if !bytes.Equal(want[34:], []byte{0, 0, 0, 0}) {
			t.Fatal("fragment used transport ports")
		}
	}
}

func TestClassifierAcceptsOpaqueLegalProtocolsWithoutInspectingPayload(t *testing.T) {
	for _, protocol := range []byte{1, 47, 50, 58, 59, 135, 139, 140, 253, 254} {
		a := ipv6ClassifierPacket(protocol, nil)
		b := ipv6ClassifierPacket(protocol, []byte{255, 255, 255, 255, 255})
		if classifierKey(t, a) != classifierKey(t, b) {
			t.Fatal("opaque protocol depended on payload")
		}
		v4 := queueTestPacket(64, 0, 1)
		v4[9] = protocol
		if _, err := classifyUplinkPacket(v4); err != nil {
			t.Fatal("legal IPv4 opaque protocol rejected")
		}
	}
}

func TestClassifierRejectsTruncationAndNeverReadsBeyondDeclaredLength(t *testing.T) {
	valid := [][]byte{queueTestPacket(64, 0, 1), ipv6ClassifierPacket(17, queueTestPacket(64, 0, 1)[20:]),
		ipv6ClassifierPacket(0, []byte{59, 0, 0, 0, 0, 0, 0, 0})}
	for _, packet := range valid {
		for n := 0; n < len(packet); n++ {
			if _, err := classifyUplinkPacket(packet[:n]); err == nil {
				t.Fatalf("accepted truncated packet at %d/%d", n, len(packet))
			}
		}
	}
	malformed := [][]byte{nil, {0}, {0x40}, {0x60}}
	ip4 := queueTestPacket(64, 0, 1)
	ip4[0] = 0x44
	malformed = append(malformed, ip4)
	ip4 = queueTestPacket(64, 0, 1)
	binary.BigEndian.PutUint16(ip4[2:4], 20) // A valid-looking UDP tail is outside TotalLength.
	malformed = append(malformed, ip4)
	ip6 := ipv6ClassifierPacket(17, queueTestPacket(64, 0, 1)[20:])
	binary.BigEndian.PutUint16(ip6[4:6], 0)
	malformed = append(malformed, ip6)
	for _, next := range []byte{0, 43, 60, 51, 44} {
		malformed = append(malformed, ipv6ClassifierPacket(next, []byte{17, 255, 0, 0}))
	}
	tcp := make([]byte, 20)
	tcp[12] = 0xf0
	malformed = append(malformed, ipv6ClassifierPacket(6, tcp))
	badUDP := queueTestPacket(64, 0, 1)
	binary.BigEndian.PutUint16(badUDP[24:26], 7)
	malformed = append(malformed, badUDP)
	for i, packet := range malformed {
		if _, err := classifyUplinkPacket(packet); err == nil {
			t.Fatalf("malformed case %d accepted", i)
		}
	}
	padded := queueTestPacket(64, 0, 1)
	if classifierKey(t, append(padded, bytes.Repeat([]byte{0xff}, 10)...)) != classifierKey(t, padded) {
		t.Fatal("trailing bytes beyond declared IP length entered classification")
	}
}

func FuzzUplinkClassifier(f *testing.F) {
	f.Add(queueTestPacket(64, 0, 1))
	f.Add(ipv6ClassifierPacket(44, []byte{6, 0, 0, 0, 0, 0, 0, 1}))
	f.Add([]byte{0x60})
	f.Fuzz(func(t *testing.T, packet []byte) {
		if len(packet) > protocolMTU {
			packet = packet[:protocolMTU]
		}
		first, err := classifyUplinkPacket(packet)
		second, again := classifyUplinkPacket(append([]byte{}, packet...))
		if (err == nil) != (again == nil) || first != second {
			t.Fatal("classification depends on backing storage")
		}
	})
}
