package host

import (
	"context"
	"errors"
	"fmt"
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
	// The Setup tab sits after Logs; the existing action IDs stay stable.
	for i := 0; i < 7; i++ {
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

func consoleSetupSnapshot() ConsoleSnapshot {
	s := consoleTestSnapshot()
	s.InstallerSupported = true
	s.Dependencies = []ConsoleDependency{
		{Target: "claude", Name: "Claude Code", Installed: true, Status: "Installed", Path: `C:\Tools\claude.exe`},
		{Target: "codex", Name: "Codex", Status: "Not installed", Detail: "Ready to install."},
		{Target: "antigravity", Name: "Antigravity", Status: "Failed", Detail: "Package download failed; try again."},
		{Target: "cloudflared", Name: "Cloudflare Tunnel", Status: "Not installed", Detail: "Downloading with winget."},
	}
	return s
}

func TestConsoleSetupShowsDependencyStateAndDispatchesOnlyEligibleInstalls(t *testing.T) {
	s := consoleTestScreen(t, 100, 35)
	m := consoleModel{}
	m.event(tcell.NewEventKey(tcell.KeyRune, '5', 0))
	m.draw(s, consoleSetupSnapshot())
	text := consoleScreenText(s)
	for _, want := range []string{"5 Setup", "Claude Code", `C:\Tools\claude.exe`, "Install Codex", "Failed", "Package download failed", "Downloading with winget"} {
		if !strings.Contains(text, want) {
			t.Fatalf("Setup omitted %q:\n%s", want, text)
		}
	}
	if m.tab != 4 || m.focus != 9 || m.activate(12) != "" {
		t.Fatal("Setup tab or disabled install eligibility is wrong")
	}
	m.event(tcell.NewEventKey(tcell.KeyTab, 0, 0))
	if m.focus != 13 {
		t.Fatalf("focus must skip the installed provider, got %d", m.focus)
	}
	if a := m.event(tcell.NewEventKey(tcell.KeyEnter, 0, 0)); a != ConsoleInstallCodex {
		t.Fatalf("Codex install not dispatched: %q", a)
	}
	var retry consoleHitRect
	for _, rect := range m.buttons {
		if rect.id == 14 {
			retry = rect
		}
	}
	if retry.w == 0 {
		t.Fatal("failed dependency has no retry control")
	}
	if a := m.event(tcell.NewEventMouse(retry.x+1, retry.y, tcell.Button1, 0)); a != ConsoleInstallAntigravity {
		t.Fatalf("retry click did not select the correct installer: %q", a)
	}
	m.event(tcell.NewEventMouse(retry.x+1, retry.y, tcell.ButtonNone, 0))
	snapshot := consoleSetupSnapshot()
	// An executable can exist after a partial install but still fail verification.
	snapshot.Dependencies[0].Status = "Failed"
	snapshot.Dependencies[0].Detail = "The executable failed its version check."
	m.draw(s, snapshot)
	if a := m.activate(12); a != ConsoleInstallClaude || !strings.Contains(consoleScreenText(s), "Retry Claude Code") {
		t.Fatal("failed existing executable cannot be repaired", a)
	}
	snapshot.InstallerSupported = false
	m.draw(s, snapshot)
	if a := m.activate(13); a != "" || !strings.Contains(consoleScreenText(s), "Windows only") {
		t.Fatal("unsupported installer remained executable", a)
	}
}

func TestConsoleSetupPreventsDuplicateInstallWhileAppRequestIsRunning(t *testing.T) {
	s := consoleTestScreen(t, 100, 35)
	m := consoleModel{tab: 4, focus: 9}
	snapshot := consoleSetupSnapshot()
	snapshot.Dependencies[3].Status = "Installing..."
	m.draw(s, snapshot)
	for id := 12; id <= 15; id++ {
		if action := m.activate(id); action != "" {
			t.Fatal("App installation allowed duplicate console action", action)
		}
	}
	if !strings.Contains(consoleScreenText(s), "Downloading with winget.") {
		t.Fatal("App install progress was not visible in console")
	}
}

func TestConsoleSetupSmallScreenFollowsInstallFocusAndPreservesExistingActions(t *testing.T) {
	s := consoleTestScreen(t, 50, 20)
	m := consoleModel{}
	m.activate(9)
	snapshot := consoleSetupSnapshot()
	snapshot.Dependencies[3].Status = "Not installed"
	m.draw(s, snapshot)
	for _, want := range []int{13, 14, 15} {
		m.event(tcell.NewEventKey(tcell.KeyTab, 0, 0))
		m.draw(s, snapshot)
		if m.focus != want {
			t.Fatalf("wrong install focus: %d, want %d", m.focus, want)
		}
		visible := false
		for _, rect := range m.buttons {
			if rect.id == want && rect.y >= 0 && rect.y < 18 {
				visible = true
			}
		}
		if !visible {
			t.Fatalf("keyboard-focused install %d was clipped", want)
		}
	}
	if m.scroll == 0 {
		t.Fatal("small Setup view did not scroll to Cloudflare Tunnel")
	}
	if a := m.event(tcell.NewEventKey(tcell.KeyEnter, 0, 0)); a != ConsoleInstallCloudflared {
		t.Fatal(a)
	}
	m.event(tcell.NewEventKey(tcell.KeyTab, 0, 0))
	if m.focus != 4 || m.event(tcell.NewEventKey(tcell.KeyEnter, 0, 0)) != ConsoleRefresh {
		t.Fatal("Setup changed the existing refresh action")
	}
}

func TestConsoleSetupManualScrollDoesNotSnapBackToFocusedButton(t *testing.T) {
	s := consoleTestScreen(t, 50, 20)
	m := consoleModel{tab: 4, focus: 9}
	snapshot := consoleSetupSnapshot()
	snapshot.Dependencies[1].Detail = strings.Repeat("Installer output with troubleshooting details. ", 12)
	m.draw(s, snapshot)
	m.event(tcell.NewEventKey(tcell.KeyTab, 0, 0))
	m.draw(s, snapshot)
	if m.focus != 13 {
		t.Fatal("Codex install button did not get focus")
	}
	before := m.scroll
	m.event(tcell.NewEventKey(tcell.KeyPgDn, 0, 0))
	m.draw(s, snapshot)
	if m.scroll <= before {
		t.Fatalf("manual scroll snapped back to focus: %d -> %d", before, m.scroll)
	}
	after := m.scroll
	m.draw(s, snapshot)
	if m.scroll != after {
		t.Fatal("live status refresh moved the manually scrolled view")
	}
	m.event(tcell.NewEventKey(tcell.KeyHome, 0, 0))
	m.draw(s, snapshot)
	if m.scroll != 0 {
		t.Fatal("Home could not return to the beginning while an install has focus")
	}
}

func TestConsoleSetupRendersAllDependenciesAtCommonDashboardSizes(t *testing.T) {
	for _, size := range [][2]int{{120, 40}, {80, 25}} {
		t.Run(fmt.Sprintf("%dx%d", size[0], size[1]), func(t *testing.T) {
			s := consoleTestScreen(t, size[0], size[1])
			m := consoleModel{tab: 4, focus: 9}
			m.draw(s, consoleSetupSnapshot())
			text := consoleScreenText(s)
			for _, name := range []string{"Claude Code", "Codex", "Antigravity", "Cloudflare Tunnel", "Ready to install.", "Package download failed", "Downloading with winget."} {
				if !strings.Contains(text, name) {
					t.Fatalf("Setup clipped %q at %dx%d:\n%s", name, size[0], size[1], text)
				}
			}
			found := map[int]bool{}
			for _, rect := range m.buttons {
				if rect.id >= 12 && rect.id <= 15 {
					if rect.x < 0 || rect.x+rect.w > size[0] || rect.y >= size[1]-3 {
						t.Fatalf("install button overlaps the footer: %+v", rect)
					}
					found[rect.id] = true
				}
			}
			if len(found) != 4 {
				t.Fatalf("only %d dependency controls fit", len(found))
			}
		})
	}
}

func TestConsoleBusyInstallAllowsViewingTabsAndLogsWithoutAnotherMutation(t *testing.T) {
	s := consoleTestScreen(t, 100, 35)
	m := consoleModel{busy: true, message: "Working..."}
	m.activate(9)
	m.draw(s, consoleSetupSnapshot())
	if m.activate(13) != "" || m.activate(7) != "" || m.confirm != "" {
		t.Fatal("busy install admitted a duplicate installer or restart")
	}
	m.event(tcell.NewEventKey(tcell.KeyRune, '1', 0))
	if m.tab != 0 || m.message != "Working..." {
		t.Fatal("busy install prevented viewing Status or cleared progress")
	}
	m.event(tcell.NewEventKey(tcell.KeyRune, '4', 0))
	m.event(tcell.NewEventKey(tcell.KeyPgUp, 0, 0))
	if m.tab != 3 || m.scroll == 0 {
		t.Fatal("busy install prevented reading earlier logs")
	}
	m.event(tcell.NewEventKey(tcell.KeyRune, '5', 0))
	m.draw(s, consoleSetupSnapshot())
	if m.tab != 4 || !strings.Contains(consoleScreenText(s), "Downloading with winget") {
		t.Fatal("busy install hid its live progress")
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

func TestConsoleShutdownWaitsForInstallerCleanup(t *testing.T) {
	s := &consoleLifecycleScreen{SimulationScreen: tcell.NewSimulationScreen("UTF-8"), ready: make(chan struct{})}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	started, cancelled, release := make(chan struct{}), make(chan struct{}), make(chan struct{})
	done := make(chan error, 1)
	go func() {
		done <- RunConsoleScreen(ctx, s, consoleSetupSnapshot, func(actionContext context.Context, action ConsoleAction) error {
			if action != ConsoleInstallCodex {
				return nil
			}
			close(started)
			<-actionContext.Done()
			close(cancelled)
			<-release
			return actionContext.Err()
		})
	}()
	<-s.ready
	s.InjectKey(tcell.KeyRune, '5', 0)
	s.InjectKey(tcell.KeyTab, 0, 0)
	s.InjectKey(tcell.KeyEnter, 0, 0)
	select {
	case <-started:
	case <-time.After(3 * time.Second):
		t.Fatal("installer did not start")
	}
	cancel()
	select {
	case <-cancelled:
	case <-time.After(3 * time.Second):
		t.Fatal("shutdown did not cancel installer")
	}
	select {
	case <-done:
		t.Fatal("console returned before installer cleanup finished")
	case <-time.After(20 * time.Millisecond):
	}
	close(release)
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("console did not finish after installer cleanup")
	}
	if s.finished.Load() != 1 {
		t.Fatal("cleanup did not restore the terminal exactly once")
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
