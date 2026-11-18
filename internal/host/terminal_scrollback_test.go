package host

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

type terminalScrollbackPTY struct {
	resizes int
}

func (*terminalScrollbackPTY) Read([]byte) (int, error)    { return 0, io.EOF }
func (*terminalScrollbackPTY) Write(p []byte) (int, error) { return len(p), nil }
func (*terminalScrollbackPTY) Close() error                { return nil }
func (*terminalScrollbackPTY) Wait(context.Context) (uint32, error) {
	return 0, nil
}
func (*terminalScrollbackPTY) Pid() int { return 0 }
func (p *terminalScrollbackPTY) Resize(int, int) error {
	p.resizes++
	return nil
}

func terminalScrollbackFixture(t *testing.T) (*nativeTerminal, []string) {
	t.Helper()
	term := &nativeTerminal{
		info: M{"id": "scrollback", "cols": 40, "rows": 12, "alive": true},
		pty:  &terminalScrollbackPTY{}, clients: map[*terminalClient]bool{},
	}
	term.screen = newTerminalScreen(term, 40, 12)
	var output strings.Builder
	var wanted []string
	for i := 0; i < 80; i++ {
		line := fmt.Sprintf("Line %d", i)
		if i == 10 || i == 11 {
			line = "Repeated output"
		}
		fmt.Fprintf(&output, "\x1b[31m%s\x1b[0m\r\n", line)
		wanted = append(wanted, line)
	}
	term.screen.Write([]byte(output.String()))
	return term, wanted
}

func requireTerminalScrollback(t *testing.T, frame M, wanted []string) {
	t.Helper()
	var received []string
	for _, line := range frame["lines"].([]any) {
		if text := terminalPlain(line); text != "" {
			received = append(received, text)
		}
	}
	if !slices.Equal(wanted, received) {
		t.Fatalf("terminal received %d of %d lines: %v", len(received), len(wanted), received)
	}
}

func TestTerminalScrollbackSurvivesBurstAndAlternateScreen(t *testing.T) {
	term, wanted := terminalScrollbackFixture(t)
	requireTerminalScrollback(t, terminalScreen(term), wanted)
	term.screen.Write([]byte("\x1b[?1049h" + strings.Repeat("Alternate\r\n", 30)))
	frame := terminalScreen(term)
	if !B(frame, "alt") || len(frame["lines"].([]any)) != 12 {
		t.Fatalf("alternate screen should render only its viewport: %#v", frame)
	}
	term.screen.Write([]byte("\x1b[?1049l"))
	frame = terminalScreen(term)
	if B(frame, "alt") {
		t.Fatal("alternate screen did not close")
	}
	requireTerminalScrollback(t, frame, wanted)
}

func TestTerminalScrollbackReconnectAndIdleResize(t *testing.T) {
	a := testApp(t)
	term, wanted := terminalScrollbackFixture(t)
	set := terminalsFor(a)
	set.terms["scrollback"] = term
	srv := httptest.NewServer(a.Handler())
	defer srv.Close()
	h := http.Header{"Authorization": []string{"Bearer " + a.Config.Token}}
	ws, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(srv.URL, "http")+"/api/terminals/scrollback/ws", h)
	if err != nil {
		t.Fatal(err)
	}
	defer ws.Close()
	read := func(kind string) M {
		t.Helper()
		if err := ws.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
			t.Fatal(err)
		}
		var frame M
		if err := ws.ReadJSON(&frame); err != nil {
			t.Fatalf("did not receive %s: %v", kind, err)
		}
		if S(frame, "type") != kind {
			t.Fatalf("got frame %s instead of %s", S(frame, "type"), kind)
		}
		return frame
	}
	send := func(message M) {
		t.Helper()
		if err := ws.WriteJSON(message); err != nil {
			t.Fatal(err)
		}
	}
	read("info")
	requireTerminalScrollback(t, read("screen"), wanted)
	send(M{"type": "resize", "cols": 40, "rows": 12})
	send(M{"type": "resync"})
	requireTerminalScrollback(t, read("screen"), wanted)
	send(M{"type": "resize", "cols": 35, "rows": 6})
	frame := read("screen")
	if N(frame, "cols") != 35 || N(frame, "rows") != 6 {
		t.Fatalf("resize dimensions: %.0f columns, %.0f rows", N(frame, "cols"), N(frame, "rows"))
	}
	requireTerminalScrollback(t, frame, wanted)
	term.mu.Lock()
	resizes := term.pty.(*terminalScrollbackPTY).resizes
	term.mu.Unlock()
	if resizes != 1 {
		t.Fatalf("got %d PTY resizes, want 1", resizes)
	}
}
