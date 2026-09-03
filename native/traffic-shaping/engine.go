package trafficshaping

import (
	"context"
	"errors"
	"reflect"
	"sync"
	"sync/atomic"

	"github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/device/iobased"
	tunlog "github.com/xjasonlyu/tun2socks/v2/log"
	"github.com/xjasonlyu/tun2socks/v2/tunnel"
	"github.com/xjasonlyu/tun2socks/v2/tunnel/statistic"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

const protocolMTU = 1500

var (
	errInvalidEngineArguments = errors.New("invalid traffic shaping engine arguments")
	errEngineStopped          = errors.New("traffic shaping engine is stopped")
	errEngineTerminal         = errors.New("traffic shaping engine has failed")
	errEngineAlreadyStarted   = errors.New("traffic shaping engine is already started")
	errEngineNotStarted       = errors.New("traffic shaping engine is not started")
	errProfileRequired        = errors.New("traffic shaping profile is required")
	errSuspensionRequired     = errors.New("traffic shaping engine must be suspended")
	errEngineAlreadyActive    = errors.New("another traffic shaping engine is active")
	errNativeStack            = errors.New("native network stack failed")
)

var activeEngine atomic.Pointer[engineState]

type engineState struct {
	operationMu sync.Mutex
	mu          sync.Mutex

	tun       *ownedTun
	protector Protector
	listener  TerminalListener
	mtu       int

	ctx    context.Context
	cancel context.CancelFunc

	uplink   *directionLimiter
	downlink *directionLimiter
	counters *aggregateCounters
	gate     sync.RWMutex
	shaped   *shapedTun

	endpoint *iobased.Endpoint
	stack    *stack.Stack
	tunnel   *tunnel.Tunnel

	profile    *appliedProfile
	generation int64
	started    bool
	starting   bool
	suspended  bool
	terminal   bool
	stopping   atomic.Bool
	stopped    bool
}

func newEngine(tunFD int, mtu int, protector Protector, listener TerminalListener) (_ Engine, err error) {
	if tunFD < 0 {
		return nil, errInvalidEngineArguments
	}
	owned := ownedTunFromFD(tunFD)
	if owned == nil {
		return nil, errInvalidEngineArguments
	}
	success := false
	defer func() {
		if recover() != nil {
			err = errInvalidEngineArguments
		}
		if !success {
			_ = owned.Close()
		}
	}()
	if mtu != protocolMTU || interfaceIsNil(protector) || interfaceIsNil(listener) {
		return nil, errInvalidEngineArguments
	}

	ctx, cancel := context.WithCancel(context.Background())
	clock := systemClock{}
	waiter := timerWaiter{}
	state := &engineState{
		tun:       owned,
		protector: protector,
		listener:  listener,
		mtu:       mtu,
		ctx:       ctx,
		cancel:    cancel,
		uplink:    newDirectionLimiter(mtu, clock, waiter),
		downlink:  newDirectionLimiter(mtu, clock, waiter),
		counters:  &aggregateCounters{},
		suspended: true,
	}
	state.shaped = &shapedTun{
		ctx:      ctx,
		device:   owned,
		mtu:      mtu,
		uplink:   state.uplink,
		downlink: state.downlink,
		counters: state.counters,
		gate:     &state.gate,
		fail:     state.failTerminal,
		stopping: func() bool { return state.stopping.Load() },
	}
	success = true
	return &mobileEngine{state: state}, nil
}

func (e *engineState) applyProfile(canonicalProfile []byte) (_ *ProfileReceipt, err error) {
	profile, err := parseCanonicalProfile(canonicalProfile)
	if err != nil {
		return nil, err
	}
	e.operationMu.Lock()
	defer e.operationMu.Unlock()
	defer func() {
		if recover() != nil {
			e.failTerminal(TerminalNativeStackFailure)
			err = errNativeStack
		}
	}()

	e.mu.Lock()
	defer e.mu.Unlock()
	if e.stopped {
		return nil, errEngineStopped
	}
	if e.terminal {
		return nil, errEngineTerminal
	}
	if !e.suspended {
		return nil, errSuspensionRequired
	}
	e.generation++
	e.uplink.apply(profile.uplinkKbps)
	e.downlink.apply(profile.downlinkKbps)
	e.counters.reset()
	e.profile = profile
	return receiptFor(profile, e.generation), nil
}

func (e *engineState) start() (err error) {
	e.operationMu.Lock()
	defer e.operationMu.Unlock()
	var endpoint *iobased.Endpoint
	var networkStack *stack.Stack
	var forwarder *tunnel.Tunnel
	committed := false
	defer func() {
		if recover() != nil {
			e.startFailed()
			cleanupNetworkStack(endpoint, networkStack, forwarder)
			err = errNativeStack
			return
		}
		if !committed && (endpoint != nil || networkStack != nil || forwarder != nil) {
			cleanupNetworkStack(endpoint, networkStack, forwarder)
		}
	}()

	e.mu.Lock()
	if e.stopped {
		e.mu.Unlock()
		return errEngineStopped
	}
	if e.terminal {
		e.mu.Unlock()
		return errEngineTerminal
	}
	if e.started || e.starting {
		e.mu.Unlock()
		return errEngineAlreadyStarted
	}
	if e.profile == nil {
		e.mu.Unlock()
		return errProfileRequired
	}
	e.starting = true
	e.mu.Unlock()

	if !activeEngine.CompareAndSwap(nil, e) {
		e.mu.Lock()
		e.starting = false
		e.mu.Unlock()
		return errEngineAlreadyActive
	}
	if err := installSilentLogger(); err != nil {
		e.startFailed()
		return errNativeStack
	}

	endpoint, err = iobased.New(e.shaped, uint32(e.mtu), 0)
	if err != nil {
		e.startFailed()
		return errNativeStack
	}
	direct := &protectedDirectProxy{
		protect: e.protectSocket,
		fail:    e.failTerminal,
	}
	forwarder = tunnel.New(direct, statistic.DefaultManager)
	forwarder.ProcessAsync()
	networkStack, err = core.CreateStack(&core.Config{
		LinkEndpoint:     endpoint,
		TransportHandler: forwarder,
	})
	if err != nil {
		e.startFailed()
		return errNativeStack
	}

	e.mu.Lock()
	if e.stopped || e.terminal {
		e.starting = false
		e.mu.Unlock()
		activeEngine.CompareAndSwap(e, nil)
		return errEngineTerminal
	}
	e.endpoint = endpoint
	e.stack = networkStack
	e.tunnel = forwarder
	e.started = true
	e.starting = false
	e.mu.Unlock()
	committed = true
	return nil
}

func (e *engineState) suspend() (err error) {
	e.operationMu.Lock()
	defer e.operationMu.Unlock()
	defer func() {
		if recover() != nil {
			e.failTerminal(TerminalNativeStackFailure)
			err = errNativeStack
		}
	}()

	e.mu.Lock()
	if e.stopped {
		e.mu.Unlock()
		return errEngineStopped
	}
	if e.terminal {
		e.mu.Unlock()
		return errEngineTerminal
	}
	if !e.started {
		e.mu.Unlock()
		return errEngineNotStarted
	}
	if e.suspended {
		e.mu.Unlock()
		return nil
	}
	e.suspended = true
	e.mu.Unlock()
	e.uplink.suspend()
	e.downlink.suspend()
	e.gate.Lock()
	e.gate.Unlock()
	e.mu.Lock()
	terminal := e.terminal
	stopped := e.stopped
	e.mu.Unlock()
	if stopped {
		return errEngineStopped
	}
	if terminal {
		return errEngineTerminal
	}
	return nil
}

func (e *engineState) resume() (err error) {
	e.operationMu.Lock()
	defer e.operationMu.Unlock()
	defer func() {
		if recover() != nil {
			e.failTerminal(TerminalNativeStackFailure)
			err = errNativeStack
		}
	}()

	e.mu.Lock()
	defer e.mu.Unlock()
	if e.stopped {
		return errEngineStopped
	}
	if e.terminal {
		return errEngineTerminal
	}
	if !e.started {
		return errEngineNotStarted
	}
	if e.profile == nil {
		return errProfileRequired
	}
	if !e.suspended {
		return nil
	}
	if err := e.uplink.resume(); err != nil {
		return errEngineTerminal
	}
	if err := e.downlink.resume(); err != nil {
		e.uplink.suspend()
		return errEngineTerminal
	}
	e.suspended = false
	return nil
}

func (e *engineState) stop() {
	e.operationMu.Lock()
	defer e.operationMu.Unlock()
	defer func() { activeEngine.CompareAndSwap(e, nil) }()
	defer func() { _ = recover() }()

	e.mu.Lock()
	if e.stopped {
		e.mu.Unlock()
		return
	}
	e.stopping.Store(true)
	e.stopped = true
	e.started = false
	e.starting = false
	e.suspended = true
	endpoint := e.endpoint
	networkStack := e.stack
	forwarder := e.tunnel
	e.endpoint = nil
	e.stack = nil
	e.tunnel = nil
	e.mu.Unlock()

	e.cancel()
	e.uplink.close()
	e.downlink.close()
	_ = e.tun.Close()
	cleanupNetworkStack(endpoint, networkStack, forwarder)
}

func (e *engineState) failTerminal(code string) {
	e.mu.Lock()
	if e.stopped || e.stopping.Load() || e.terminal {
		e.mu.Unlock()
		return
	}
	e.terminal = true
	e.suspended = true
	listener := e.listener
	e.mu.Unlock()

	e.cancel()
	e.uplink.close()
	e.downlink.close()
	_ = e.tun.Close()
	notifyTerminal(listener, code)
}

func (e *engineState) startFailed() {
	activeEngine.CompareAndSwap(e, nil)
	e.mu.Lock()
	e.starting = false
	e.mu.Unlock()
	e.failTerminal(TerminalNativeStackFailure)
}

func (e *engineState) protectSocket(fd int64) bool {
	if e.stopping.Load() {
		return false
	}
	return e.protector.Protect(fd)
}

func (e *engineState) healthy() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.started && e.profile != nil && !e.terminal && !e.stopped
}

func (e *engineState) hasOpenTun() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return !e.stopped && e.tun.IsOpen()
}

func (e *engineState) isSuspended() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.suspended
}

func (e *engineState) appliedDigest() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.profile == nil {
		return ""
	}
	return e.profile.digest
}

func (e *engineState) snapshot() *CountersSnapshot {
	e.mu.Lock()
	defer e.mu.Unlock()
	digest := ""
	if e.profile != nil {
		digest = e.profile.digest
	}
	return &CountersSnapshot{
		generation:             e.generation,
		profileDigest:          digest,
		uplinkBytes:            signedSaturated(e.counters.uplinkBytes.Load()),
		uplinkPackets:          signedSaturated(e.counters.uplinkPackets.Load()),
		downlinkBytes:          signedSaturated(e.counters.downlinkBytes.Load()),
		downlinkPackets:        signedSaturated(e.counters.downlinkPackets.Load()),
		uplinkThrottledNanos:   signedSaturated(e.uplink.throttledDuration()),
		downlinkThrottledNanos: signedSaturated(e.downlink.throttledDuration()),
	}
}

func receiptFor(profile *appliedProfile, generation int64) *ProfileReceipt {
	receipt := &ProfileReceipt{
		profileID:  profile.id,
		digest:     profile.digest,
		generation: generation,
	}
	if profile.uplinkKbps != nil {
		receipt.uplinkLimited = true
		receipt.uplinkKbps = int64(*profile.uplinkKbps)
	}
	if profile.downlinkKbps != nil {
		receipt.downLimited = true
		receipt.downlinkKbps = int64(*profile.downlinkKbps)
	}
	return receipt
}

func installSilentLogger() error {
	logger, err := tunlog.NewLeveled(tunlog.SilentLevel)
	if err != nil {
		return err
	}
	tunlog.SetLogger(logger)
	return nil
}

func cleanupNetworkStack(
	endpoint *iobased.Endpoint,
	networkStack *stack.Stack,
	forwarder *tunnel.Tunnel,
) {
	if forwarder != nil {
		forwarder.Close()
	}
	if networkStack != nil {
		networkStack.Close()
		networkStack.Wait()
	}
	if endpoint != nil {
		endpoint.Close()
		endpoint.Wait()
	}
}

func notifyTerminal(listener TerminalListener, code string) {
	defer func() { _ = recover() }()
	listener.OnTerminalFailure(code)
}

func interfaceIsNil(value any) bool {
	if value == nil {
		return true
	}
	reflected := reflect.ValueOf(value)
	switch reflected.Kind() {
	case reflect.Chan, reflect.Func, reflect.Interface, reflect.Map, reflect.Pointer, reflect.Slice:
		return reflected.IsNil()
	default:
		return false
	}
}
