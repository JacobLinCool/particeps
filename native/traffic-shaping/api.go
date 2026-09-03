// Package trafficshaping provides the gomobile boundary for Particeps' local
// per-application VPN forwarder. The package never records or reports packet
// contents, endpoints, DNS names, or per-application traffic.
package trafficshaping

// Protector is implemented by the Android VpnService. Protect must synchronously
// exempt the supplied outbound socket from the VPN and return true only after
// Android has accepted the exemption.
type Protector interface {
	Protect(socketFD int64) bool
}

// TerminalListener receives a stable, non-sensitive failure code once when an
// engine can no longer forward traffic safely. The callback is synchronous so
// its implementation must only close admission and wake the runtime; it must
// return promptly and must not call back into Engine.
type TerminalListener interface {
	OnTerminalFailure(code string)
}

// Stable terminal failure codes. They intentionally carry no packet, endpoint,
// profile, or operating-system error data.
const (
	TerminalTunEOF             = "TUN_EOF"
	TerminalTunReadFailed      = "TUN_READ_FAILED"
	TerminalTunWriteFailed     = "TUN_WRITE_FAILED"
	TerminalProtectFailed      = "SOCKET_PROTECT_FAILED"
	TerminalConcurrentTunIO    = "CONCURRENT_TUN_IO"
	TerminalInvalidTunPacket   = "INVALID_TUN_PACKET"
	TerminalNativeStackFailure = "NATIVE_STACK_FAILURE"
)

// Engine owns one detached Android TUN descriptor and one userspace network
// stack. CreateEngine is the only constructor. A newly constructed engine is
// suspended; callers apply and verify a profile, start the stack, then
// explicitly resume forwarding.
type Engine interface {
	ApplyProfile(canonicalProfile []byte) (*ProfileReceipt, error)
	Start() error
	Suspend() error
	Resume() error
	Stop()
	IsHealthy() bool
	HasOpenTun() bool
	IsSuspended() bool
	GetAppliedProfileDigest() string
	Snapshot() *CountersSnapshot
}

type mobileEngine struct {
	state *engineState
}

var _ Engine = (*mobileEngine)(nil)

// ProfileReceipt is proof of the exact canonical profile applied by native
// code. The digest is lowercase hexadecimal SHA-256 over the supplied bytes.
type ProfileReceipt struct {
	profileID     string
	digest        string
	generation    int64
	uplinkLimited bool
	uplinkKbps    int64
	downLimited   bool
	downlinkKbps  int64
}

func (r *ProfileReceipt) GetProfileID() string   { return r.profileID }
func (r *ProfileReceipt) GetDigest() string      { return r.digest }
func (r *ProfileReceipt) GetGeneration() int64   { return r.generation }
func (r *ProfileReceipt) HasUplinkLimit() bool   { return r.uplinkLimited }
func (r *ProfileReceipt) GetUplinkKbps() int64   { return r.uplinkKbps }
func (r *ProfileReceipt) HasDownlinkLimit() bool { return r.downLimited }
func (r *ProfileReceipt) GetDownlinkKbps() int64 { return r.downlinkKbps }

// CountersSnapshot contains aggregate TUN-layer counters for the current
// applied generation. It never contains per-flow or per-package dimensions.
type CountersSnapshot struct {
	generation             int64
	profileDigest          string
	uplinkBytes            int64
	uplinkPackets          int64
	downlinkBytes          int64
	downlinkPackets        int64
	uplinkThrottledNanos   int64
	downlinkThrottledNanos int64
}

func (s *CountersSnapshot) GetGeneration() int64             { return s.generation }
func (s *CountersSnapshot) GetProfileDigest() string         { return s.profileDigest }
func (s *CountersSnapshot) GetUplinkBytes() int64            { return s.uplinkBytes }
func (s *CountersSnapshot) GetUplinkPackets() int64          { return s.uplinkPackets }
func (s *CountersSnapshot) GetDownlinkBytes() int64          { return s.downlinkBytes }
func (s *CountersSnapshot) GetDownlinkPackets() int64        { return s.downlinkPackets }
func (s *CountersSnapshot) GetUplinkThrottledNanos() int64   { return s.uplinkThrottledNanos }
func (s *CountersSnapshot) GetDownlinkThrottledNanos() int64 { return s.downlinkThrottledNanos }

// CreateEngine transfers ownership of tunFD to native code immediately. Native
// code closes it exactly once whether construction succeeds or fails. MTU is
// deliberately fixed to 1500 for Protocol v1.
func CreateEngine(tunFD int64, mtu int64, protector Protector, listener TerminalListener) (Engine, error) {
	if tunFD < 0 || int64(int(tunFD)) != tunFD {
		return nil, errInvalidEngineArguments
	}
	if int64(int(mtu)) != mtu {
		if owned := ownedTunFromFD(int(tunFD)); owned != nil {
			_ = owned.Close()
		}
		return nil, errInvalidEngineArguments
	}
	return newEngine(int(tunFD), int(mtu), protector, listener)
}

// ApplyProfile validates exact canonical profile JSON, resets both token
// buckets and aggregate counters, and returns native proof. It is allowed only
// while forwarding is suspended.
func (e *mobileEngine) ApplyProfile(canonicalProfile []byte) (*ProfileReceipt, error) {
	return e.state.applyProfile(canonicalProfile)
}

// Start composes and starts the silent tun2socks/gVisor stack. Forwarding stays
// suspended until Resume succeeds.
func (e *mobileEngine) Start() error { return e.state.start() }

// Suspend stops new TUN delivery and waits for in-flight TUN delivery to cross
// the native boundary. It is idempotent.
func (e *mobileEngine) Suspend() error { return e.state.suspend() }

// Resume allows forwarding under the currently applied profile.
func (e *mobileEngine) Resume() error { return e.state.resume() }

// Stop permanently releases the engine and its TUN descriptor. It is
// idempotent and does not emit a terminal failure.
func (e *mobileEngine) Stop() { e.state.stop() }

// IsHealthy reports whether the stack is started, has an applied profile, and
// has not observed a terminal failure. Suspension is a healthy state.
func (e *mobileEngine) IsHealthy() bool { return e.state.healthy() }

// HasOpenTun proves that the descriptor transferred by Android remains owned and open in native
// code. Android combines this signal with VPN ownership, engine health and profile proof; no
// descriptor number or packet metadata crosses the binding.
func (e *mobileEngine) HasOpenTun() bool { return e.state.hasOpenTun() }

// IsSuspended reports the current native forwarding gate state.
func (e *mobileEngine) IsSuspended() bool { return e.state.isSuspended() }

// GetAppliedProfileDigest returns an empty string before a profile is applied.
func (e *mobileEngine) GetAppliedProfileDigest() string { return e.state.appliedDigest() }

// Snapshot returns aggregate counters for the current profile generation.
func (e *mobileEngine) Snapshot() *CountersSnapshot { return e.state.snapshot() }
