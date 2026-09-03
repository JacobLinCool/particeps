package trafficshaping

import (
	"io"
	"os"
	"sync"
	"sync/atomic"

	"golang.org/x/sys/unix"
)

type tunReadWriteCloser interface {
	io.Reader
	io.Writer
	io.Closer
}

type ownedTun struct {
	device    tunReadWriteCloser
	closeOnce sync.Once
	closed    atomic.Bool
	err       error
}

func ownedTunFromFD(fd int) *ownedTun {
	// os.NewFile only registers a transferred descriptor with the Go runtime
	// poller when it is already non-blocking. Android hands VpnService TUN
	// descriptors to us in blocking mode. A plain Close from another goroutine
	// is not required to interrupt an in-flight read on Linux, which would leave
	// iobased.Endpoint.Wait blocked forever during shutdown. Native owns the
	// detached descriptor at this point, so make it pollable before wrapping it;
	// os.File keeps ordinary blocking Read/Write semantics through netpoll and
	// Close then reliably wakes both operations.
	if err := unix.SetNonblock(fd, true); err != nil {
		_ = unix.Close(fd)
		return nil
	}
	file := os.NewFile(uintptr(fd), "particeps-tun")
	if file == nil {
		_ = unix.Close(fd)
		return nil
	}
	return &ownedTun{device: file}
}

func (t *ownedTun) Read(buffer []byte) (int, error) {
	return t.device.Read(buffer)
}

func (t *ownedTun) Write(buffer []byte) (int, error) {
	return t.device.Write(buffer)
}

func (t *ownedTun) Close() error {
	t.closeOnce.Do(func() {
		t.closed.Store(true)
		t.err = t.device.Close()
	})
	return t.err
}

func (t *ownedTun) IsOpen() bool {
	return !t.closed.Load()
}
