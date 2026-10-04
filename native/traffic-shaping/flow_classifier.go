package trafficshaping

import (
	"encoding/binary"

	"gvisor.dev/gvisor/pkg/tcpip/header"
)

// flowKey exists only while hashing an incoming packet. It is never retained in
// the queue, logged, or exported. Fragmented and opaque protocols deliberately
// use address/protocol classification (RFC 8290 Sections 4.1.1 and 8).
type flowKey [38]byte

func classifyUplinkPacket(packet []byte) (flowKey, error) {
	var key flowKey
	if len(packet) == 0 {
		return key, errInvalidTunPacket
	}
	var payload []byte
	switch header.IPVersion(packet) {
	case header.IPv4Version:
		ip := header.IPv4(packet)
		if !ip.IsValid(len(packet)) {
			return key, errInvalidTunPacket
		}
		key[0], key[1] = 4, uint8(ip.TransportProtocol())
		copy(key[2:18], ip.SourceAddressSlice())
		copy(key[18:34], ip.DestinationAddressSlice())
		// The first fragment must use the same classification as later ones.
		// Neither fragment IDs nor offsets split a fragmented flow into queues.
		if ip.More() || ip.FragmentOffset() != 0 {
			return key, nil
		}
		payload = packet[int(ip.HeaderLength()):int(ip.TotalLength())]
	case header.IPv6Version:
		ip := header.IPv6(packet)
		if !ip.IsValid(len(packet)) {
			return key, errInvalidTunPacket
		}
		key[0], key[1] = 6, ip.NextHeader()
		copy(key[2:18], ip.SourceAddressSlice())
		copy(key[18:34], ip.DestinationAddressSlice())
		payload = packet[header.IPv6MinimumSize : header.IPv6MinimumSize+int(ip.PayloadLength())]
		for {
			var size int
			switch key[1] {
			case 0, 43, 60: // Hop-by-Hop, Routing, Destination Options.
				if len(payload) < 2 {
					return flowKey{}, errInvalidTunPacket
				}
				size = (int(payload[1]) + 1) * 8
			case 51: // Authentication Header uses 32-bit length units.
				if len(payload) < 2 {
					return flowKey{}, errInvalidTunPacket
				}
				size = (int(payload[1]) + 2) * 4
				if size < 12 {
					return flowKey{}, errInvalidTunPacket
				}
			case header.IPv6FragmentHeader:
				fragment := header.IPv6Fragment(payload)
				if !fragment.IsValid() {
					return flowKey{}, errInvalidTunPacket
				}
				// Stop for every fragment, including first and atomic fragments.
				key[1] = fragment.NextHeader()
				return key, nil
			default:
				// ESP, unknown extension headers and other protocols remain
				// opaque. Their payload must never be mistaken for TCP ports.
				return classifyTransport(key, payload)
			}
			if size > len(payload) {
				return flowKey{}, errInvalidTunPacket
			}
			key[1] = payload[0]
			payload = payload[size:]
			// Each step consumes at least eight bytes; the MTU bounds work.
		}
	default:
		return key, errInvalidTunPacket
	}
	return classifyTransport(key, payload)
}

func classifyTransport(key flowKey, payload []byte) (flowKey, error) {
	switch key[1] {
	case uint8(header.TCPProtocolNumber):
		if len(payload) < header.TCPMinimumSize {
			return flowKey{}, errInvalidTunPacket
		}
		offset := int(header.TCP(payload).DataOffset())
		if offset < header.TCPMinimumSize || offset > len(payload) {
			return flowKey{}, errInvalidTunPacket
		}
	case uint8(header.UDPProtocolNumber):
		if len(payload) < header.UDPMinimumSize {
			return flowKey{}, errInvalidTunPacket
		}
		length := int(binary.BigEndian.Uint16(payload[4:6]))
		if length < header.UDPMinimumSize || length > len(payload) {
			return flowKey{}, errInvalidTunPacket
		}
	default:
		return key, nil
	}
	copy(key[34:38], payload[:4])
	return key, nil
}
