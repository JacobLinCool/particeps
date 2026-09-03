package trafficshaping

import (
	"io"
	"os"
	"sync"
	"sync/atomic"
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
	file := os.NewFile(uintptr(fd), "particeps-tun")
	if file == nil {
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
