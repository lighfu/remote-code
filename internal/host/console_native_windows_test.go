//go:build windows

package host

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/UserExistsError/conpty"
)

func TestConsoleNativeTTYHelper(t *testing.T) {
	if os.Getenv("REMOTE_CODE_CONSOLE_TTY_HELPER") != "1" {
		return
	}
	if err := runServer(os.Getenv("REMOTE_CODE_CONSOLE_TTY_DATA"), nil); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(4)
	}
	os.Exit(0)
}

type consoleNativeOutput struct {
	mu     sync.Mutex
	buffer bytes.Buffer
}

func (b *consoleNativeOutput) Write(data []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.buffer.Len() > 2<<20 {
		b.buffer.Reset()
	}
	return b.buffer.Write(data)
}
func (b *consoleNativeOutput) text() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buffer.String()
}

func TestConsoleNativeTTYKeyboardRestartAndStop(t *testing.T) {
	if !conpty.IsConPtyAvailable() {
		t.Skip("Windows ConPTY is unavailable")
	}
	data := t.TempDir()
	probe, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := probe.Addr().(*net.TCPAddr).Port
	_ = probe.Close()
	token := NewID()
	if err = atomicJSON(filepath.Join(data, "config.json"), M{"port": port, "token": token, "usbReverse": false, "quickTunnel": false, "allowLan": false}); err != nil {
		t.Fatal(err)
	}
	t.Setenv("REMOTE_CODE_CONSOLE_TTY_HELPER", "1")
	t.Setenv("REMOTE_CODE_CONSOLE_TTY_DATA", data)
	t.Setenv("REMOTE_CODE_INSTANCE", "")
	t.Setenv("TERM", "xterm-256color")
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	pty, err := conpty.Start(syscall.EscapeArg(exe)+" -test.run=^TestConsoleNativeTTYHelper$", conpty.ConPtyDimensions(100, 36), conpty.ConPtyEnv(os.Environ()))
	if err != nil {
		t.Fatal(err)
	}
	safePTY := &platformSafePTY{ConPty: pty}
	defer safePTY.Close()
	output := &consoleNativeOutput{}
	go io.Copy(output, safePTY)
	client := &http.Client{Timeout: 500 * time.Millisecond}
	defer client.CloseIdleConnections()
	meta := func() M {
		req, _ := http.NewRequest("GET", fmt.Sprintf("http://127.0.0.1:%d/api/meta", port), nil)
		req.Header.Set("Authorization", "Bearer "+token)
		resp, e := client.Do(req)
		if e != nil {
			return nil
		}
		defer resp.Body.Close()
		if resp.StatusCode != 200 {
			return nil
		}
		var value M
		_ = json.NewDecoder(resp.Body).Decode(&value)
		return value
	}
	wait := func(description string, test func() bool) {
		t.Helper()
		deadline := time.Now().Add(8 * time.Second)
		for time.Now().Before(deadline) {
			if test() {
				return
			}
			time.Sleep(20 * time.Millisecond)
		}
		text := output.text()
		if len(text) > 2500 {
			text = text[len(text)-2500:]
		}
		t.Fatalf("Timed out waiting for %s. Console tail: %q", description, text)
	}
	wait("native dashboard", func() bool { return strings.Contains(output.text(), "Remote Code") && meta() != nil })
	firstID := S(meta(), "instanceId")
	if firstID == "" {
		t.Fatal("Missing initial instance ID")
	}
	screen := consoleTestScreen(t, 100, 36)
	mouseModel := consoleModel{}
	mouseModel.draw(screen, ConsoleSnapshot{})
	var stopButton consoleHitRect
	for _, button := range mouseModel.buttons {
		if button.id == 8 {
			stopButton = button
			break
		}
	}
	if stopButton.w == 0 {
		t.Fatal("Missing stop button")
	}
	mouse := fmt.Sprintf("\x1b[<0;%d;%dM\x1b[<0;%d;%dm", stopButton.x+2, stopButton.y+1, stopButton.x+2, stopButton.y+1)
	if _, err = safePTY.Write([]byte(mouse)); err != nil {
		t.Fatal(err)
	}
	wait("stop confirmation", func() bool { return strings.Contains(output.text(), "Stop server?") })
	if _, err = safePTY.Write([]byte("\r")); err != nil {
		t.Fatal(err)
	}
	if value := meta(); S(value, "instanceId") != firstID {
		t.Fatal("Default confirmation stopped the server")
	}
	if _, err = safePTY.Write([]byte("r")); err != nil {
		t.Fatal(err)
	}
	wait("restart confirmation", func() bool { return strings.Contains(output.text(), "Restart server?") })
	oldRenders := strings.Count(output.text(), "Remote Code")
	if _, err = safePTY.Write([]byte("y")); err != nil {
		t.Fatal(err)
	}
	wait("restarted native dashboard", func() bool {
		value := meta()
		return value != nil && S(value, "instanceId") != "" && S(value, "instanceId") != firstID && strings.Count(output.text(), "Remote Code") > oldRenders
	})
	if _, err = safePTY.Write([]byte("qy")); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	code, err := safePTY.Wait(ctx)
	if err != nil || code != 0 {
		t.Fatalf("Confirmed stop failed: exit=%d error=%v", code, err)
	}
	if meta() != nil {
		t.Fatal("Stopped native server still responds")
	}
}
