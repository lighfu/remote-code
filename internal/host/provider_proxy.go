package host

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

func providerDaemonExecutable(ctx context.Context, bin string) string {
	run := func(args ...string) M {
		check, cancel := context.WithTimeout(ctx, 15*time.Second)
		defer cancel()
		cmd := HostCommand(check, bin, append([]string{"app-server", "daemon"}, args...)...)
		out, err := cmd.Output()
		if err != nil {
			return nil
		}
		value, _ := manageJSON(string(out)).(map[string]any)
		return value
	}
	value := run("version")
	if S(value, "status") != "running" {
		_ = run("start")
		value = run("version")
	}
	if S(value, "status") != "running" {
		return ""
	}
	path := S(value, "managedCodexPath")
	if !filepath.IsAbs(path) {
		return ""
	}
	if path, err := providerExecutable(path); err == nil {
		return path
	}
	return ""
}

type proxyConn struct {
	cmd    *exec.Cmd
	reader io.ReadCloser
	writer io.WriteCloser
	once   sync.Once
}

func (c *proxyConn) Read(b []byte) (int, error)  { return c.reader.Read(b) }
func (c *proxyConn) Write(b []byte) (int, error) { return c.writer.Write(b) }
func (c *proxyConn) Close() error {
	c.once.Do(func() {
		_ = c.writer.Close()
		_ = c.reader.Close()
		if c.cmd.Process != nil {
			_ = c.cmd.Process.Kill()
		}
	})
	return nil
}
func (c *proxyConn) LocalAddr() net.Addr              { return proxyAddr("remote-code") }
func (c *proxyConn) RemoteAddr() net.Addr             { return proxyAddr("codex-daemon") }
func (c *proxyConn) SetDeadline(time.Time) error      { return nil }
func (c *proxyConn) SetReadDeadline(time.Time) error  { return nil }
func (c *proxyConn) SetWriteDeadline(time.Time) error { return nil }

type proxyAddr string

func (a proxyAddr) Network() string { return "stdio" }
func (a proxyAddr) String() string  { return string(a) }
func startProviderProxy(ctx context.Context, path string, onMessage func(M)) (*providerWire, error) {
	cmd := HostCommand(context.Background(), path, "app-server", "proxy")
	cmd.Env = os.Environ()
	in, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	out, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return nil, err
	}
	if err = cmd.Start(); err != nil {
		return nil, err
	}
	connection := &proxyConn{cmd: cmd, reader: out, writer: in}
	go io.Copy(io.Discard, stderr)
	handshake, stop := context.WithTimeout(ctx, 15*time.Second)
	defer stop()
	finished := make(chan struct{})
	go func() {
		select {
		case <-handshake.Done():
			_ = connection.Close()
		case <-finished:
		}
	}()
	dialer := websocket.Dialer{NetDialContext: func(context.Context, string, string) (net.Conn, error) { return connection, nil }, HandshakeTimeout: 15 * time.Second}
	ws, _, err := dialer.DialContext(handshake, "ws://localhost/", nil)
	close(finished)
	if err != nil {
		_ = connection.Close()
		_ = cmd.Wait()
		return nil, err
	}
	ws.SetReadLimit(32 << 20)
	w := &providerWire{cmd: cmd, in: in, waiting: map[string]chan providerReply{}, shared: true, done: make(chan struct{}), onMessage: onMessage}
	w.sendFunc = func(msg M) error { return ws.WriteJSON(msg) }
	w.closeFunc = func() error { _ = ws.Close(); return connection.Close() }
	go func() {
		var failure error
		for {
			_, raw, e := ws.ReadMessage()
			if e != nil {
				failure = e
				break
			}
			var message M
			if json.Unmarshal(raw, &message) != nil {
				failure = errors.New("Invalid JSON from shared daemon")
				break
			}
			w.receive(message)
		}
		_ = connection.Close()
		_ = cmd.Wait()
		w.mu.Lock()
		for id, ch := range w.waiting {
			ch <- providerReply{err: failure}
			delete(w.waiting, id)
		}
		close(w.done)
		w.mu.Unlock()
		onMessage(M{"_exit": failure.Error()})
	}()
	return w, nil
}
