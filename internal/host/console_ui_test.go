package host

import (
	"context"
	"errors"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gdamore/tcell/v2"
)

func consoleTestSnapshot() ConsoleSnapshot {
	return ConsoleSnapshot{Version: "0.18.1", Host: "TEST-PC", URL: "http://127.0.0.1:8787", Port: 8787, PairLink: "remotecode://pair?url=https%3A%2F%2Fsample.trycloudflare.com&token=secret-pairing-token-should-not-appear-in-status&name=TEST-PC", APKURL: "https://sample.trycloudflare.com/dl/only-apk-key/remote-code.apk", APKVersion: "0.17.6", Providers: map[string]bool{"claude": true, "codex": true}, Sessions: 4, Running: 1, Waiting: 2, Terminals: 3, Logs: []string{"first line", "second line"}}
}
func consoleTestScreen(t *testing.T, w, h int) tcell.SimulationScreen {
	t.Helper()
	s := tcell.NewSimulationScreen("UTF-8")
	if err := s.Init(); err != nil {
		t.Fatal(err)
	}
	s.SetSize(w, h)
	t.Cleanup(s.Fini)
	return s
}
func consoleScreenText(s tcell.SimulationScreen) string {
	cells, w, h := s.GetContents()
	var text strings.Builder
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			r := cells[y*w+x].Runes
			if len(r) > 0 {
				text.WriteRune(r[0])
			} else {
				text.WriteRune(' ')
			}
		}
		text.WriteByte('\n')
	}
	return text.String()
}
func TestConsoleDestructiveKeysRequireExplicitConfirmation(t *testing.T) {
	m := consoleModel{}
	for _, key := range []tcell.Key{tcell.KeyCtrlC} {
		if a := m.event(tcell.NewEventKey(key, 0, 0)); a != "" || m.confirm != ConsoleStop {
			t.Fatal(a, m.confirm)
		}
	}
	if a := m.event(tcell.NewEventKey(tcell.KeyEnter, 0, 0)); a != "" || m.confirm != "" {
		t.Fatal("default accepted destructive action", a)
	}
	_ = m.event(tcell.NewEventKey(tcell.KeyRune, 'r', 0))
	if m.confirm != ConsoleRestart || m.confirmYes {
		t.Fatal("restart was not safely prompted")
	}
	_ = m.event(tcell.NewEventKey(tcell.KeyRight, 0, 0))
	if a := m.event(tcell.NewEventKey(tcell.KeyEnter, 0, 0)); a != ConsoleRestart {
		t.Fatal(a)
	}
	_ = m.event(tcell.NewEventKey(tcell.KeyRune, 'q', 0))
	_ = m.event(tcell.NewEventKey(tcell.KeyEsc, 0, 0))
	if m.confirm != "" {
		t.Fatal("escape did not cancel")
	}
}
func TestConsoleKeyboardNavigationAndMouseActions(t *testing.T) {
	s := consoleTestScreen(t, 100, 32)
	m := consoleModel{}
	m.draw(s, consoleTestSnapshot())
	_ = m.event(tcell.NewEventKey(tcell.KeyRune, '2', 0))
	if m.tab != 1 {
		t.Fatal(m.tab)
	}
	_ = m.event(tcell.NewEventKey(tcell.KeyEsc, 0, 0))
	for i := 0; i < 6; i++ {
		_ = m.event(tcell.NewEventKey(tcell.KeyTab, 0, 0))
	}
	if a := m.event(tcell.NewEventKey(tcell.KeyEnter, 0, 0)); a != ConsoleCopy {
		t.Fatal(a, m.focus)
	}
	m.draw(s, consoleTestSnapshot())
	var stop consoleHitRect
	for _, rect := range m.buttons {
		if rect.id == 8 {
			stop = rect
		}
	}
	if a := m.event(tcell.NewEventMouse(stop.x+1, stop.y, tcell.Button1, 0)); a != "" || m.confirm != ConsoleStop {
		t.Fatal(a, m.confirm)
	}
	m.draw(s, consoleTestSnapshot())
	var confirm consoleHitRect
	for _, rect := range m.buttons {
		if rect.id == 10 {
			confirm = rect
		}
	}
	if a := m.event(tcell.NewEventMouse(confirm.x, confirm.y, tcell.Button1, 0)); a != "" {
		t.Fatal("drag activated destructive button")
	}
	_ = m.event(tcell.NewEventMouse(confirm.x, confirm.y, tcell.ButtonNone, 0))
	if a := m.event(tcell.NewEventMouse(confirm.x, confirm.y, tcell.Button1, 0)); a != ConsoleStop {
		t.Fatal(a)
	}
}
func TestConsoleQRUsesAvailableSpaceAndKeepsPairingTextPrivate(t *testing.T) {
	s := consoleTestScreen(t, 100, 40)
	snapshot := consoleTestSnapshot()
	m := consoleModel{}
	m.draw(s, snapshot)
	if text := consoleScreenText(s); strings.Contains(text, "secret-pairing") || !strings.Contains(text, "TEST-PC") || !strings.Contains(text, "Installed") {
		t.Fatal(text)
	}
	m.tab = 1
	m.draw(s, snapshot)
	text := consoleScreenText(s)
	if strings.Contains(text, "QR needs") || strings.Count(text, "█")+strings.Count(text, "▀")+strings.Count(text, "▄") < 100 {
		t.Fatal("QR did not render", text)
	}
	s.SetSize(80, 25)
	m.draw(s, snapshot)
	if text = consoleScreenText(s); !strings.Contains(text, "QR needs") {
		t.Fatal("small QR was silently clipped", text)
	}
	if strings.Contains(text, "secret-pairing") {
		t.Fatal("pairing token rendered as text")
	}
	s.SetSize(100, 40)
	m.tab = 2
	m.draw(s, snapshot)
	if text = consoleScreenText(s); !strings.Contains(text, "Android v0.17.6") || !strings.Contains(text, snapshot.APKURL) || strings.Contains(text, "secret-pairing") {
		t.Fatal(text)
	}
}

func TestConsoleAPKCopyUsesTheActiveTab(t *testing.T) {
	s := consoleTestScreen(t, 100, 40)
	m := consoleModel{}
	m.event(tcell.NewEventKey(tcell.KeyRune, '3', 0))
	snapshot := consoleTestSnapshot()
	snapshot.APKURL = ""
	m.draw(s, snapshot)
	if text := consoleScreenText(s); !strings.Contains(text, "Android APK is not available yet.") || !strings.Contains(text, "C Copy APK link") {
		t.Fatal(text)
	}
	copyKey := tcell.NewEventKey(tcell.KeyRune, 'c', 0)
	if a := m.event(copyKey); a != ConsoleCopyAPK || consoleActionDone(a) != "APK download link copied." {
		t.Fatalf("wrong copy action for App QR: %q", a)
	}
	for _, rect := range m.buttons {
		if rect.id == 6 {
			if a := m.event(tcell.NewEventMouse(rect.x+1, rect.y, tcell.Button1, 0)); a != ConsoleCopyAPK {
				t.Fatalf("wrong mouse copy action for App QR: %q", a)
			}
		}
	}
	m.event(tcell.NewEventKey(tcell.KeyRune, '2', 0))
	if a := m.event(copyKey); a != ConsoleCopy {
		t.Fatalf("wrong copy action for Pair QR: %q", a)
	}
}

func TestConsoleQRRequestsWindowGrowthOnlyWhenNeeded(t *testing.T) {
	for _, tc := range []struct {
		name string
		tab  rune
	}{{"pair", '2'}, {"app", '3'}} {
		t.Run(tc.name, func(t *testing.T) {
			s := consoleTestScreen(t, 40, 12)
			snapshot := consoleTestSnapshot()
			var attempts [][4]int
			m := consoleModel{resizeWindow: func(columns, rows, neededColumns, neededRows int) bool {
				attempts = append(attempts, [4]int{columns, rows, neededColumns, neededRows})
				return true
			}}
			m.draw(s, snapshot)
			if len(attempts) != 0 {
				t.Fatal("non-QR tab requested a resize")
			}
			m.event(tcell.NewEventKey(tcell.KeyRune, tc.tab, 0))
			m.draw(s, snapshot)
			m.draw(s, snapshot)
			if len(attempts) != 1 || attempts[0][0] != 40 || attempts[0][1] != 12 || attempts[0][2] < 40 || attempts[0][3] <= 12 {
				t.Fatalf("unexpected resize attempts: %v", attempts)
			}
			s.SetSize(attempts[0][2], attempts[0][3])
			m.draw(s, snapshot)
			text := consoleScreenText(s)
			if len(attempts) != 1 || strings.Contains(text, "QR needs") || strings.Count(text, "█")+strings.Count(text, "▀")+strings.Count(text, "▄") < 100 {
				t.Fatalf("QR did not fit the requested size: %v\n%s", attempts, text)
			}
			s.SetSize(40, 3)
			m.draw(s, snapshot)
			if len(attempts) != 2 || attempts[1][1] != 3 {
				t.Fatalf("manual shrink did not request a new resize: %v", attempts)
			}
		})
	}
}

func TestConsoleLogsAndResizeRemainOperable(t *testing.T) {
	s := consoleTestScreen(t, 80, 25)
	m := consoleModel{tab: 3}
	snapshot := consoleTestSnapshot()
	m.draw(s, snapshot)
	if text := consoleScreenText(s); !strings.Contains(text, "second line") {
		t.Fatal(text)
	}
	for i := 0; i < 30; i++ {
		snapshot.Logs = append(snapshot.Logs, "extra log line")
	}
	_ = m.event(tcell.NewEventKey(tcell.KeyPgUp, 0, 0))
	m.draw(s, snapshot)
	if m.scroll == 0 {
		t.Fatal("log scroll unavailable")
	}
	_ = m.event(tcell.NewEventKey(tcell.KeyEnd, 0, 0))
	if m.scroll != 0 {
		t.Fatal("End does not follow logs")
	}
	for _, size := range [][2]int{{30, 12}, {12, 5}, {8, 3}, {100, 40}} {
		s.SetSize(size[0], size[1])
		m.draw(s, snapshot)
	}
	if a := m.event(tcell.NewEventKey(tcell.KeyRune, 'r', 0)); a != "" || m.confirm != ConsoleRestart {
		t.Fatal("resize broke restart prompt")
	}
}

type consoleLifecycleScreen struct {
	tcell.SimulationScreen
	ready    chan struct{}
	finished atomic.Int32
}

func (s *consoleLifecycleScreen) Init() error {
	if err := s.SimulationScreen.Init(); err != nil {
		return err
	}
	s.SetSize(100, 35)
	close(s.ready)
	return nil
}
func (s *consoleLifecycleScreen) Fini() { s.finished.Add(1); s.SimulationScreen.Fini() }
func TestConsoleRunDispatchesConfirmedRestartAndRestoresTerminal(t *testing.T) {
	s := &consoleLifecycleScreen{SimulationScreen: tcell.NewSimulationScreen("UTF-8"), ready: make(chan struct{})}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	actions := make(chan ConsoleAction, 2)
	done := make(chan error, 1)
	go func() {
		done <- RunConsoleScreen(ctx, s, consoleTestSnapshot, func(ctx context.Context, a ConsoleAction) error { actions <- a; return nil })
	}()
	<-s.ready
	s.InjectKey(tcell.KeyRune, 'r', 0)
	s.InjectKey(tcell.KeyRune, 'y', 0)
	select {
	case a := <-actions:
		if a != ConsoleRestart {
			t.Fatal(a)
		}
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	if s.finished.Load() != 1 {
		t.Fatal("terminal not finalized exactly once")
	}
}
func TestConsoleCancellationAndActionFailureRestoreTerminal(t *testing.T) {
	s := &consoleLifecycleScreen{SimulationScreen: tcell.NewSimulationScreen("UTF-8"), ready: make(chan struct{})}
	ctx, cancel := context.WithCancel(context.Background())
	called := make(chan struct{})
	done := make(chan error, 1)
	go func() {
		done <- RunConsoleScreen(ctx, s, consoleTestSnapshot, func(context.Context, ConsoleAction) error { close(called); return errors.New("test failure") })
	}()
	<-s.ready
	s.InjectKey(tcell.KeyF5, 0, 0)
	select {
	case <-called:
	case <-time.After(3 * time.Second):
		t.Fatal("action not dispatched")
	}
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("event loop did not cancel")
	}
	if s.finished.Load() != 1 {
		t.Fatal("screen not finalized")
	}
}

func TestConsoleStopCanCancelBusyAction(t *testing.T) {
	s := &consoleLifecycleScreen{SimulationScreen: tcell.NewSimulationScreen("UTF-8"), ready: make(chan struct{})}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	busy := make(chan struct{})
	stopped := make(chan struct{})
	done := make(chan error, 1)
	go func() {
		done <- RunConsoleScreen(ctx, s, consoleTestSnapshot, func(actionContext context.Context, action ConsoleAction) error {
			if action == ConsoleRefresh {
				close(busy)
				<-actionContext.Done()
				return actionContext.Err()
			}
			if action == ConsoleStop {
				close(stopped)
			}
			return nil
		})
	}()
	<-s.ready
	s.InjectKey(tcell.KeyF5, 0, 0)
	select {
	case <-busy:
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	s.InjectKey(tcell.KeyCtrlC, 0, 0)
	s.InjectKey(tcell.KeyRune, 'y', 0)
	select {
	case <-stopped:
	case <-ctx.Done():
		t.Fatal("busy action prevented stopping", ctx.Err())
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
}
func TestConsoleStartupConfirmationDefaultsCancelAndSupportsMouse(t *testing.T) {
	for _, confirm := range []bool{false, true} {
		s := &consoleLifecycleScreen{SimulationScreen: tcell.NewSimulationScreen("UTF-8"), ready: make(chan struct{})}
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		result := make(chan bool, 1)
		go func() {
			result <- consoleConfirmScreen(ctx, s, "Existing server is running.\nStop it and start this version?")
		}()
		<-s.ready
		if confirm {
			s.InjectKey(tcell.KeyRune, '1', 0)
		} else {
			s.InjectKey(tcell.KeyEnter, 0, 0)
		}
		select {
		case actual := <-result:
			if actual != confirm {
				t.Fatal(actual, confirm)
			}
		case <-ctx.Done():
			t.Fatal(ctx.Err())
		}
		cancel()
		if s.finished.Load() != 1 {
			t.Fatal("confirmation did not restore screen")
		}
	}
	s := &consoleLifecycleScreen{SimulationScreen: tcell.NewSimulationScreen("UTF-8"), ready: make(chan struct{})}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Millisecond)
	defer cancel()
	if consoleConfirmScreen(ctx, s, "Update server?") {
		t.Fatal("timeout accepted an update")
	}
	if s.finished.Load() != 1 {
		t.Fatal("timed-out screen leaked")
	}
}
