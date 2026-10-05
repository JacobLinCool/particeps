package trafficshaping

import (
	"testing"

	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
)

func TestEngineStartsWithClassicSACKRecoveryBeforeAdmission(t *testing.T) {
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer unix.Close(fds[1])
	engine, err := newEngine(fds[0], protocolMTU, &recordingProtector{allow: true}, newTerminalRecorder())
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Stop()
	if _, err := engine.ApplyProfile([]byte(`{"downlink_kbps":512,"id":"readback","uplink_kbps":512}`)); err != nil {
		t.Fatal(err)
	}
	if err := engine.Start(); err != nil {
		t.Fatal(err)
	}
	if !engine.IsSuspended() || !engine.IsHealthy() {
		t.Fatal("readback requires healthy, suspended engine before admission")
	}
	s := engine.(*mobileEngine).state.stack
	recovery := tcpip.TCPRecovery(-1)
	if err := s.TransportProtocolOption(tcp.ProtocolNumber, &recovery); err != nil {
		t.Fatal(err)
	}
	var sack tcpip.TCPSACKEnabled
	if err := s.TransportProtocolOption(tcp.ProtocolNumber, &sack); err != nil {
		t.Fatal(err)
	}
	var congestion tcpip.CongestionControlOption
	if err := s.TransportProtocolOption(tcp.ProtocolNumber, &congestion); err != nil {
		t.Fatal(err)
	}
	if recovery != 0 || !bool(sack) || congestion != "reno" {
		t.Fatalf("recovery=%d sack=%t congestion=%s", recovery, sack, congestion)
	}
	t.Logf("before_admission=true recovery=%d sack=%t congestion=%s", recovery, sack, congestion)
}
