package trafficshaping

import (
	"testing"

	tunlog "github.com/xjasonlyu/tun2socks/v2/log"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
	"go.uber.org/zap/zaptest/observer"
)

func TestTun2SocksLoggerIsSilentBeforeStackUse(t *testing.T) {
	core, observed := observer.New(zapcore.DebugLevel)
	tunlog.SetLogger(zap.New(core))
	if err := installSilentLogger(); err != nil {
		t.Fatal(err)
	}
	tunlog.Infof("source=192.0.2.1 destination=198.51.100.7 dns=private.example payload=secret")
	if observed.Len() != 0 {
		t.Fatal("tun2socks emitted sensitive connection logging after silent installation")
	}
}

func TestTerminalCodesContainNoDynamicDetail(t *testing.T) {
	for _, code := range []string{
		TerminalTunEOF,
		TerminalTunReadFailed,
		TerminalTunWriteFailed,
		TerminalProtectFailed,
		TerminalConcurrentTunIO,
		TerminalInvalidTunPacket,
		TerminalNativeStackFailure,
	} {
		for _, char := range code {
			if !(char >= 'A' && char <= 'Z') && char != '_' {
				t.Fatalf("terminal code %q is not a closed non-sensitive token", code)
			}
		}
	}
}
