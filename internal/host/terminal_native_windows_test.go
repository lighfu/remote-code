//go:build windows

package host

import (
	"strings"
	"testing"
	"time"
)

func TestWindowsTerminalRoundTrip(t *testing.T) {
	set := &terminalSet{terms: map[string]*nativeTerminal{}}
	info, err := terminalCreate(set, t.TempDir(), "Native terminal test")
	if err != nil {
		t.Fatal(err)
	}
	set.mu.Lock()
	terminal := set.terms[S(info, "id")]
	set.mu.Unlock()
	t.Cleanup(func() { terminal.mu.Lock(); terminal.closed = true; terminal.mu.Unlock(); terminal.pty.Close() })
	if _, err = terminal.pty.Write([]byte("Write-Output '__REMOTE_CODE_NATIVE_TEST__'; exit 0\r")); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		terminal.mu.Lock()
		alive := B(terminal.info, "alive")
		code := N(terminal.info, "exitCode")
		frame := terminalScreen(terminal)
		terminal.mu.Unlock()
		if !alive {
			if code != 0 {
				t.Fatalf("shell exited %.0f", code)
			}
			lines, _ := frame["lines"].([]any)
			var text strings.Builder
			for _, line := range lines {
				text.WriteString(terminalPlain(line))
			}
			if !strings.Contains(text.String(), "__REMOTE_CODE_NATIVE_TEST__") {
				t.Fatal("PTY output missing")
			}
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("terminal did not finish")
}
