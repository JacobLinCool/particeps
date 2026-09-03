package trafficshaping

import (
	"context"
	"errors"
	"net"
	"syscall"
	"time"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"github.com/xjasonlyu/tun2socks/v2/proxy"
)

var (
	errSocketProtection    = errors.New("outbound socket protection failed")
	errUnexpectedTransport = errors.New("protected TCP dial returned an unexpected transport")
	errInvalidMetadata     = errors.New("outbound network metadata is invalid")
)

type protectedDirectProxy struct {
	protect func(int64) bool
	fail    func(string)
}

var _ proxy.Proxy = (*protectedDirectProxy)(nil)

func (p *protectedDirectProxy) DialContext(ctx context.Context, metadata *M.Metadata) (net.Conn, error) {
	dialer := net.Dialer{Control: p.control}
	connection, err := dialer.DialContext(ctx, "tcp", metadata.DestinationAddress())
	if err != nil {
		return nil, err
	}
	if tcp, ok := connection.(*net.TCPConn); ok {
		_ = tcp.SetKeepAlive(true)
		_ = tcp.SetKeepAlivePeriod(30 * time.Second)
		return tcp, nil
	}
	_ = connection.Close()
	return nil, errUnexpectedTransport
}

func (p *protectedDirectProxy) DialUDP(metadata *M.Metadata) (net.PacketConn, error) {
	if metadata == nil || !metadata.DstIP.IsValid() {
		return nil, errInvalidMetadata
	}
	network := "udp6"
	if metadata.DstIP.Is4() {
		network = "udp4"
	}
	listen := net.ListenConfig{Control: p.control}
	connection, err := listen.ListenPacket(context.Background(), network, "")
	if err != nil {
		return nil, err
	}
	return connection, nil
}

func (p *protectedDirectProxy) control(_, _ string, raw syscall.RawConn) error {
	protected := false
	controlErr := raw.Control(func(fd uintptr) {
		protected = p.safeProtect(int64(fd))
	})
	if controlErr != nil || !protected {
		p.fail(TerminalProtectFailed)
		return errSocketProtection
	}
	return nil
}

func (p *protectedDirectProxy) safeProtect(fd int64) (protected bool) {
	defer func() {
		if recover() != nil {
			protected = false
		}
	}()
	return p.protect(fd)
}
