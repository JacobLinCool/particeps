package trafficshaping

import (
	"errors"
	"sync"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

type terminalRecorder struct {
	codes chan string
}

func newTerminalRecorder() *terminalRecorder {
	return &terminalRecorder{codes: make(chan string, 8)}
}

func (recorder *terminalRecorder) OnTerminalFailure(code string) {
	recorder.codes <- code
}

func TestNewEngineClosesTransferredFDOnValidationFailure(t *testing.T) {
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer unix.Close(fds[1])
	_, err = CreateEngine(int64(fds[0]), protocolMTU-1, &recordingProtector{allow: true}, newTerminalRecorder())
	if err == nil {
		t.Fatal("invalid MTU was accepted")
	}
	if closeErr := unix.Close(fds[0]); !errors.Is(closeErr, unix.EBADF) {
		t.Fatalf("transferred fd was not closed exactly once; second close = %v", closeErr)
	}
}

func TestEngineLifecycleAndProfileProof(t *testing.T) {
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer unix.Close(fds[1])
	listener := newTerminalRecorder()
	engine, err := CreateEngine(int64(fds[0]), protocolMTU, &recordingProtector{allow: true}, listener)
	if err != nil {
		t.Fatal(err)
	}
	profile := []byte(`{"downlink_kbps":null,"id":"baseline","uplink_kbps":null}`)
	receipt, err := engine.ApplyProfile(profile)
	if err != nil {
		t.Fatal(err)
	}
	if receipt.GetDigest() == "" || receipt.GetGeneration() != 1 || receipt.GetProfileID() != "baseline" {
		t.Fatalf("invalid profile receipt: %#v", receipt)
	}
	if err := engine.Start(); err != nil {
		t.Fatalf("start: %v", err)
	}
	if !engine.IsHealthy() || !engine.IsSuspended() {
		t.Fatal("started engine did not remain healthy and suspended")
	}
	if !engine.HasOpenTun() {
		t.Fatal("started engine did not retain an open TUN")
	}
	if engine.GetAppliedProfileDigest() != receipt.GetDigest() {
		t.Fatal("native digest diverged from receipt")
	}
	if err := engine.Resume(); err != nil {
		t.Fatal(err)
	}
	if engine.IsSuspended() {
		t.Fatal("resume left engine suspended")
	}
	if _, err := engine.ApplyProfile(profile); !errors.Is(err, errSuspensionRequired) {
		t.Fatalf("profile changed outside barrier: %v", err)
	}
	if err := engine.Suspend(); err != nil {
		t.Fatal(err)
	}
	limited := []byte(`{"downlink_kbps":1024,"id":"slow","uplink_kbps":256}`)
	second, err := engine.ApplyProfile(limited)
	if err != nil {
		t.Fatal(err)
	}
	if second.GetGeneration() != 2 || !second.HasUplinkLimit() || second.GetUplinkKbps() != 256 {
		t.Fatalf("invalid second receipt: %#v", second)
	}
	snapshot := engine.Snapshot()
	if snapshot.GetGeneration() != 2 || snapshot.GetProfileDigest() != second.GetDigest() {
		t.Fatalf("snapshot does not identify applied generation: %#v", snapshot)
	}
	engine.Stop()
	engine.Stop()
	if engine.IsHealthy() {
		t.Fatal("stopped engine remained healthy")
	}
	if engine.HasOpenTun() {
		t.Fatal("stopped engine still reported an open TUN")
	}
	select {
	case code := <-listener.codes:
		t.Fatalf("normal lifecycle emitted terminal failure %q", code)
	case <-time.After(25 * time.Millisecond):
	}
	if closeErr := unix.Close(fds[0]); !errors.Is(closeErr, unix.EBADF) {
		t.Fatalf("engine did not retain and close TUN ownership: %v", closeErr)
	}
}

func TestEngineProfileAndSnapshotAreRaceSafe(t *testing.T) {
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer unix.Close(fds[1])
	engine, err := CreateEngine(int64(fds[0]), protocolMTU, &recordingProtector{allow: true}, newTerminalRecorder())
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Stop()
	profile := []byte(`{"downlink_kbps":512,"id":"race","uplink_kbps":512}`)
	var group sync.WaitGroup
	for range 8 {
		group.Add(2)
		go func() {
			defer group.Done()
			for range 100 {
				if _, applyErr := engine.ApplyProfile(profile); applyErr != nil {
					t.Errorf("apply: %v", applyErr)
					return
				}
			}
		}()
		go func() {
			defer group.Done()
			for range 100 {
				_ = engine.Snapshot().GetProfileDigest()
			}
		}()
	}
	group.Wait()
}

func TestEngineReportsOnlyFirstTerminalFailure(t *testing.T) {
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer unix.Close(fds[1])
	listener := newTerminalRecorder()
	bound, err := CreateEngine(int64(fds[0]), protocolMTU, &recordingProtector{allow: true}, listener)
	if err != nil {
		t.Fatal(err)
	}
	engine := bound.(*mobileEngine)
	engine.state.failTerminal(TerminalTunReadFailed)
	engine.state.failTerminal(TerminalProtectFailed)
	defer engine.Stop()
	select {
	case code := <-listener.codes:
		if code != TerminalTunReadFailed {
			t.Fatalf("first terminal code = %q", code)
		}
	case <-time.After(time.Second):
		t.Fatal("terminal failure was not reported")
	}
	select {
	case code := <-listener.codes:
		t.Fatalf("second terminal failure escaped: %q", code)
	case <-time.After(25 * time.Millisecond):
	}
}
