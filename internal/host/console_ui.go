package host

import (
	"context"
	"fmt"
	"strings"
	"time"
	"unicode"

	"github.com/gdamore/tcell/v2"
	"github.com/mattn/go-runewidth"
	qrcode "github.com/skip2/go-qrcode"
)

type ConsoleSnapshot struct {
	Version, Host, URL, PairLink, APKURL, APKVersion string
	Port                                             int
	Providers                                        map[string]bool
	Sessions, Running, Waiting, Terminals            int
	Logs                                             []string
}

type ConsoleAction string

const (
	ConsoleRefresh ConsoleAction = "refresh"
	ConsoleOpen    ConsoleAction = "open"
	ConsoleCopy    ConsoleAction = "copy"
	ConsoleCopyAPK ConsoleAction = "copy-apk"
	ConsoleRestart ConsoleAction = "restart"
	ConsoleStop    ConsoleAction = "stop"
)

var consoleBase = tcell.StyleDefault.Background(tcell.NewHexColor(0x101a1c)).Foreground(tcell.NewHexColor(0xe0ece7))
var consoleMuted = consoleBase.Foreground(tcell.NewHexColor(0x9cafaa))
var consoleAccent = consoleBase.Foreground(tcell.NewHexColor(0x8edbc3)).Bold(true)
var consoleButton = consoleBase.Background(tcell.NewHexColor(0x263c37))
var consoleSelected = consoleBase.Background(tcell.NewHexColor(0x9ae1c9)).Foreground(tcell.NewHexColor(0x09271e)).Bold(true)
var consoleDanger = consoleBase.Foreground(tcell.NewHexColor(0xffb4a8))

type consoleHitRect struct{ x, y, w, h, id int }

func (r consoleHitRect) contains(x, y int) bool {
	return x >= r.x && x < r.x+r.w && y >= r.y && y < r.y+r.h
}

type consoleResizeAttempt struct {
	tab, columns, rows, neededColumns, neededRows int
}

type consoleModel struct {
	tab, focus, scroll int
	confirm            ConsoleAction
	confirmYes, busy   bool
	message            string
	buttons            []consoleHitRect
	mouseDown          bool
	qrValue            string
	qr                 [][]bool
	resizeWindow       func(columns, rows, neededColumns, neededRows int) bool
	lastResize         consoleResizeAttempt
}
type consoleResult struct {
	action ConsoleAction
	err    error
}

func RunConsole(ctx context.Context, snapshot func() ConsoleSnapshot, action func(context.Context, ConsoleAction) error) error {
	screen, err := tcell.NewScreen()
	if err != nil {
		return err
	}
	return runConsoleScreen(ctx, screen, snapshot, action, resizeConsoleWindow)
}

func consoleEvents(screen tcell.Screen) (<-chan tcell.Event, func()) {
	events := make(chan tcell.Event, 16)
	quit := make(chan struct{})
	done := make(chan struct{})
	go func() { defer close(done); screen.ChannelEvents(events, quit) }()
	return events, func() { close(quit); screen.DisableMouse(); screen.Fini(); <-done }
}

func RunConsoleScreen(ctx context.Context, screen tcell.Screen, snapshot func() ConsoleSnapshot, action func(context.Context, ConsoleAction) error) error {
	return runConsoleScreen(ctx, screen, snapshot, action, nil)
}

func runConsoleScreen(ctx context.Context, screen tcell.Screen, snapshot func() ConsoleSnapshot, action func(context.Context, ConsoleAction) error, resizeWindow func(columns, rows, neededColumns, neededRows int) bool) error {
	if err := screen.Init(); err != nil {
		screen.Fini()
		return err
	}
	events, finish := consoleEvents(screen)
	defer finish()
	screen.SetStyle(consoleBase)
	screen.EnableMouse(tcell.MouseButtonEvents)
	screen.HideCursor()
	model := consoleModel{resizeWindow: resizeWindow}
	results := make(chan consoleResult, 1)
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	var actionCancel context.CancelFunc
	var queued ConsoleAction
	defer func() {
		if actionCancel != nil {
			actionCancel()
		}
	}()
	start := func(a ConsoleAction) {
		if model.busy {
			if a == ConsoleStop {
				queued = a
				if actionCancel != nil {
					actionCancel()
				}
				model.message = "Waiting for the current action to stop..."
			}
			return
		}
		model.busy = true
		model.message = "Working..."
		actionContext, cancel := context.WithCancel(ctx)
		actionCancel = cancel
		go func() {
			defer cancel()
			var err error
			defer func() {
				if recover() != nil {
					err = fmt.Errorf("Console action failed")
				}
				select {
				case results <- consoleResult{a, err}:
				case <-ctx.Done():
				}
			}()
			if action != nil {
				err = action(actionContext, a)
			}
		}()
	}
	current := snapshot()
	model.draw(screen, current)
	for {
		select {
		case <-ctx.Done():
			return nil
		case result := <-results:
			model.busy = false
			actionCancel = nil
			if queued != "" {
				next := queued
				queued = ""
				start(next)
				break
			}
			if result.err != nil {
				model.message = "Error: " + result.err.Error()
			} else {
				model.message = consoleActionDone(result.action)
				if result.action == ConsoleRestart || result.action == ConsoleStop {
					return nil
				}
			}
		case <-ticker.C:
		case event, ok := <-events:
			if !ok {
				return nil
			}
			if resize, ok := event.(*tcell.EventResize); ok {
				_, _ = resize.Size()
				screen.Sync()
			}
			if a := model.event(event); a != "" {
				start(a)
			}
		}
		current = snapshot()
		model.draw(screen, current)
	}
}

func consoleActionDone(action ConsoleAction) string {
	switch action {
	case ConsoleCopy:
		return "Pairing link copied."
	case ConsoleCopyAPK:
		return "APK download link copied."
	case ConsoleOpen:
		return "Opened server status in your browser."
	case ConsoleRefresh:
		return "Status refreshed."
	case ConsoleRestart:
		return "Restarting server..."
	case ConsoleStop:
		return "Stopping server..."
	}
	return "Done."
}
func (m *consoleModel) activate(id int) ConsoleAction {
	if m.confirm != "" {
		if id == 10 {
			m.confirmYes = true
			a := m.confirm
			m.confirm = ""
			return a
		}
		if id == 11 {
			m.confirm = ""
			m.confirmYes = false
		}
		return ""
	}
	if m.busy && id != 8 {
		return ""
	}
	if id < 4 {
		if m.tab != id {
			m.message = ""
			m.lastResize = consoleResizeAttempt{}
		}
		m.tab = id
		m.focus = id
		m.scroll = 0
		return ""
	}
	if id == 7 || id == 8 {
		if id == 7 {
			m.confirm = ConsoleRestart
		} else {
			m.confirm = ConsoleStop
		}
		m.confirmYes = false
		return ""
	}
	switch id {
	case 4:
		return ConsoleRefresh
	case 5:
		return ConsoleOpen
	case 6:
		if m.tab == 2 {
			return ConsoleCopyAPK
		}
		return ConsoleCopy
	}
	return ""
}
func (m *consoleModel) event(event tcell.Event) ConsoleAction {
	switch ev := event.(type) {
	case *tcell.EventKey:
		if m.confirm != "" {
			switch ev.Key() {
			case tcell.KeyEsc, tcell.KeyCtrlC:
				m.confirm = ""
				m.confirmYes = false
			case tcell.KeyLeft, tcell.KeyRight, tcell.KeyTab, tcell.KeyBacktab:
				m.confirmYes = !m.confirmYes
			case tcell.KeyEnter:
				if m.confirmYes {
					return m.activate(10)
				}
				return m.activate(11)
			case tcell.KeyRune:
				switch unicode.ToLower(ev.Rune()) {
				case 'y', '1':
					return m.activate(10)
				case 'n', '2':
					return m.activate(11)
				}
			}
			return ""
		}
		if m.busy && ev.Key() != tcell.KeyCtrlC && !(ev.Key() == tcell.KeyRune && unicode.ToLower(ev.Rune()) == 'q') {
			return ""
		}
		switch ev.Key() {
		case tcell.KeyCtrlC:
			return m.activate(8)
		case tcell.KeyTab, tcell.KeyRight, tcell.KeyDown:
			m.focus = (m.focus + 1) % 9
		case tcell.KeyBacktab, tcell.KeyLeft, tcell.KeyUp:
			m.focus = (m.focus + 8) % 9
		case tcell.KeyEnter:
			return m.activate(m.focus)
		case tcell.KeyF5:
			return m.activate(4)
		case tcell.KeyPgUp:
			m.tab = 3
			m.scroll += 5
		case tcell.KeyPgDn:
			m.tab = 3
			m.scroll = max(0, m.scroll-5)
		case tcell.KeyHome:
			if m.tab == 3 {
				m.scroll = 1000000
			}
		case tcell.KeyEnd:
			m.scroll = 0
		case tcell.KeyEsc:
			m.tab = 0
			m.focus = 0
		case tcell.KeyRune:
			switch unicode.ToLower(ev.Rune()) {
			case '1', '2', '3', '4':
				return m.activate(int(ev.Rune() - '1'))
			case 's':
				return m.activate(0)
			case 'p':
				return m.activate(1)
			case 'a':
				return m.activate(2)
			case 'l':
				return m.activate(3)
			case 'o':
				return m.activate(5)
			case 'c':
				return m.activate(6)
			case 'r':
				return m.activate(7)
			case 'q':
				return m.activate(8)
			}
		}
	case *tcell.EventMouse:
		buttons := ev.Buttons()
		down := buttons&tcell.Button1 != 0
		pressed := down && !m.mouseDown
		m.mouseDown = down
		if buttons&tcell.WheelUp != 0 {
			m.tab = 3
			m.scroll += 3
		}
		if buttons&tcell.WheelDown != 0 {
			m.tab = 3
			m.scroll = max(0, m.scroll-3)
		}
		if pressed {
			x, y := ev.Position()
			for _, rect := range m.buttons {
				if rect.contains(x, y) {
					if m.confirm == "" {
						m.focus = rect.id
					}
					return m.activate(rect.id)
				}
			}
		}
	}
	return ""
}

func consoleText(screen tcell.Screen, x, y, width int, text string, style tcell.Style) {
	sw, sh := screen.Size()
	if y < 0 || y >= sh || width <= 0 {
		return
	}
	limit := min(sw, x+width)
	for _, r := range text {
		if r == '\n' || r == '\r' {
			r = ' '
		}
		if unicode.IsControl(r) {
			continue
		}
		rw := runewidth.RuneWidth(r)
		if rw <= 0 {
			continue
		}
		if x+rw > limit {
			if x < limit {
				screen.SetContent(x, y, '…', nil, style)
			}
			break
		}
		if x >= 0 {
			screen.SetContent(x, y, r, nil, style)
		}
		x += rw
	}
}
func consoleFill(screen tcell.Screen, x, y, width, height int, style tcell.Style) {
	sw, sh := screen.Size()
	for yy := max(0, y); yy < min(sh, y+height); yy++ {
		for xx := max(0, x); xx < min(sw, x+width); xx++ {
			screen.SetContent(xx, yy, ' ', nil, style)
		}
	}
}
func consoleBox(screen tcell.Screen, x, y, width, height int, style tcell.Style) {
	if width < 2 || height < 2 {
		return
	}
	consoleFill(screen, x, y, width, height, style)
	consoleText(screen, x, y, width, "╭"+strings.Repeat("─", width-2)+"╮", style)
	consoleText(screen, x, y+height-1, width, "╰"+strings.Repeat("─", width-2)+"╯", style)
	for yy := y + 1; yy < y+height-1; yy++ {
		consoleText(screen, x, yy, 1, "│", style)
		consoleText(screen, x+width-1, yy, 1, "│", style)
	}
}
func consoleWrapped(text string, width int) []string {
	if width <= 0 {
		return nil
	}
	out := []string{}
	for _, line := range strings.Split(text, "\n") {
		if line == "" {
			out = append(out, "")
			continue
		}
		var part strings.Builder
		n := 0
		for _, r := range line {
			if unicode.IsControl(r) {
				continue
			}
			w := runewidth.RuneWidth(r)
			if n+w > width && n > 0 {
				out = append(out, part.String())
				part.Reset()
				n = 0
			}
			part.WriteRune(r)
			n += w
		}
		out = append(out, part.String())
	}
	return out
}
func (m *consoleModel) button(screen tcell.Screen, id, x, y int, label string, selected bool) {
	style := consoleButton
	if selected {
		style = consoleSelected
	}
	width := runewidth.StringWidth(label) + 4
	consoleFill(screen, x, y, width, 1, style)
	consoleText(screen, x+2, y, width-4, label, style)
	m.buttons = append(m.buttons, consoleHitRect{x, y, width, 1, id})
}
func (m *consoleModel) draw(screen tcell.Screen, s ConsoleSnapshot) {
	w, h := screen.Size()
	screen.Fill(' ', consoleBase)
	m.buttons = nil
	compactQR := m.tab == 1 || m.tab == 2
	if !compactQR && (w < 12 || h < 5) {
		consoleText(screen, 0, 0, w, "Remote Code", consoleAccent)
		consoleText(screen, 0, 2, w, "Resize console", consoleMuted)
		screen.Show()
		return
	}
	if !compactQR {
		consoleText(screen, 2, 1, w-4, "Remote Code  v"+s.Version, consoleAccent)
		consoleText(screen, 2, 2, w-4, fmt.Sprintf("RUNNING  %s  :%d", s.Host, s.Port), consoleMuted)
		consoleText(screen, 2, 3, w-4, fmt.Sprintf("Sessions %d   Running %d   Waiting %d   Terminals %d", s.Sessions, s.Running, s.Waiting, s.Terminals), consoleBase)
	}
	labels := []string{"1 Status", "2 Pair QR", "3 App QR", "4 Logs"}
	x := 2
	y := 5
	if compactQR {
		y = 0
	}
	for id, label := range labels {
		bw := runewidth.StringWidth(label) + 4
		if x+bw > w-2 {
			x = 2
			y++
		}
		m.button(screen, id, x, y, label, m.tab == id || m.focus == id)
		x += bw + 1
	}
	contentY := y + 2
	if compactQR {
		contentY = y + 1
	}
	actions := []string{"F5 Refresh", "O Open status", "C Copy link", "R Restart", "Q Stop"}
	if m.tab == 2 {
		actions[2] = "C Copy APK link"
	}
	rows := 1
	x = 2
	for _, label := range actions {
		bw := runewidth.StringWidth(label) + 4
		if x+bw > w-2 {
			x = 2
			rows++
		}
		x += bw + 1
	}
	actionY := max(contentY, h-rows-2)
	x = 2
	y = actionY
	for index, label := range actions {
		bw := runewidth.StringWidth(label) + 4
		if x+bw > w-2 {
			x = 2
			y++
		}
		m.button(screen, index+4, x, y, label, m.focus == index+4)
		x += bw + 1
	}
	contentH := max(0, actionY-contentY-1)
	switch m.tab {
	case 0:
		lines := []string{"Connection", s.URL, "", "Agents"}
		for _, name := range []string{"claude", "codex", "antigravity"} {
			status := "Not installed"
			if s.Providers[name] {
				status = "Installed"
			}
			lines = append(lines, fmt.Sprintf("  %-12s %s", name, status))
		}
		lines = append(lines, "", "Use Pair QR to connect Android.", "Use App QR to install or update the Android app.")
		for i, line := range lines {
			if i >= contentH {
				break
			}
			style := consoleBase
			if line == "Connection" || line == "Agents" {
				style = consoleAccent
			}
			consoleText(screen, 2, contentY+i, w-4, line, style)
		}
	case 1:
		m.drawQR(screen, s.PairLink, "Scan with Remote Code to connect.", contentY, contentH, rows, false)
	case 2:
		caption := "Scan to install the Android app."
		if s.APKVersion != "" {
			caption = "Android v" + s.APKVersion + " · Scan to install."
		}
		m.drawQR(screen, s.APKURL, caption, contentY, contentH, rows, true)
	case 3:
		consoleText(screen, 2, contentY, w-4, "Server logs  ·  wheel / PgUp / PgDn  ·  End = follow", consoleAccent)
		lines := []string{}
		for _, line := range s.Logs {
			lines = append(lines, consoleWrapped(line, max(1, w-4))...)
		}
		if len(lines) == 0 {
			consoleText(screen, 2, contentY+2, w-4, "No log messages.", consoleMuted)
		} else {
			available := max(0, contentH-1)
			m.scroll = min(m.scroll, max(0, len(lines)-available))
			end := len(lines) - m.scroll
			start := max(0, end-available)
			for i, line := range lines[start:end] {
				consoleText(screen, 2, contentY+1+i, w-4, line, consoleMuted)
			}
		}
	}
	if m.message != "" {
		style := consoleAccent
		if strings.HasPrefix(m.message, "Error:") {
			style = consoleDanger
		}
		consoleText(screen, 2, h-2, w-4, m.message, style)
	}
	consoleText(screen, 2, h-1, w-4, "Tab / arrows: select  Enter: activate  Mouse: click  Esc: back", consoleMuted)
	if m.confirm != "" {
		title := "Stop server?"
		if m.confirm == ConsoleRestart {
			title = "Restart server?"
		}
		question := fmt.Sprintf("%d running, %d waiting, %d terminal(s).\nOwned agent processes and terminals will close.\nPC-owned conversations stay on the PC.", s.Running, s.Waiting, s.Terminals)
		m.drawConfirm(screen, title, question)
	}
	screen.Show()
}
func (m *consoleModel) drawQR(screen tcell.Screen, value, caption string, y, height, actionRows int, showLink bool) {
	w, h := screen.Size()
	if height > 0 {
		consoleText(screen, 2, y, w-4, caption, consoleAccent)
	}
	if value == "" {
		m.lastResize = consoleResizeAttempt{}
		message := "Pairing link is not available yet."
		if showLink {
			message = "Android APK is not available yet."
		}
		if height > 1 {
			consoleText(screen, 2, y+1, w-4, message, consoleMuted)
		}
		return
	}
	offset := 2
	if showLink {
		link := consoleWrapped(value, w-4)
		if len(link) < height {
			for i, line := range link {
				consoleText(screen, 2, y+i+1, w-4, line, consoleBase)
			}
		} else {
			if height > 1 {
				consoleText(screen, 2, y+1, w-4, "Press C to copy the APK download link.", consoleMuted)
			}
		}
		offset += len(link)
	}
	if m.qrValue != value {
		m.qrValue = value
		m.qr = nil
		if code, err := qrcode.New(value, qrcode.Medium); err == nil {
			m.qr = code.Bitmap()
		}
	}
	size := len(m.qr)
	if size == 0 {
		return
	}
	qrHeight := (size + 1) / 2
	if size > w-4 || qrHeight > height-offset {
		neededColumns := size + 4
		neededContentRows := qrHeight + offset
		if showLink && neededColumns > w {
			neededContentRows = qrHeight + 2 + len(consoleWrapped(value, neededColumns-4))
		}
		// The rows above and below the QR include the tabs, action buttons,
		// status line, and spacing between the QR and its actions.
		neededRows := y + neededContentRows + actionRows + 3
		attempt := consoleResizeAttempt{m.tab, w, h, neededColumns, neededRows}
		if m.resizeWindow != nil && m.lastResize != attempt {
			m.lastResize = attempt
			m.resizeWindow(w, h, neededColumns, neededRows)
		}
		if offset < height {
			consoleText(screen, 2, y+offset, w-4, fmt.Sprintf("QR needs %d columns and %d rows.", neededColumns, neededRows), consoleMuted)
		}
		if offset+1 < height {
			consoleText(screen, 2, y+offset+1, w-4, "Enlarge the terminal or use C to copy the link.", consoleMuted)
		}
		return
	}
	m.lastResize = consoleResizeAttempt{}
	x := (w - size) / 2
	style := tcell.StyleDefault.Background(tcell.ColorWhite).Foreground(tcell.ColorBlack)
	for row := 0; row < size; row += 2 {
		for col := 0; col < size; col++ {
			top := m.qr[row][col]
			bottom := row+1 < size && m.qr[row+1][col]
			r := ' '
			switch {
			case top && bottom:
				r = '█'
			case top:
				r = '▀'
			case bottom:
				r = '▄'
			}
			screen.SetContent(x+col, y+offset+row/2, r, nil, style)
		}
	}
}
func (m *consoleModel) drawConfirm(screen tcell.Screen, title, question string) {
	w, h := screen.Size()
	width := min(68, max(12, w-4))
	lines := consoleWrapped(question, max(1, width-4))
	height := min(h, max(7, len(lines)+5))
	x := (w - width) / 2
	y := (h - height) / 2
	consoleBox(screen, x, y, width, height, consoleBase)
	consoleText(screen, x+2, y+1, width-4, title, consoleDanger)
	for i, line := range lines {
		if i >= height-5 {
			break
		}
		consoleText(screen, x+2, y+2+i, width-4, line, consoleBase)
	}
	m.buttons = nil
	if width >= 30 {
		m.button(screen, 10, x+2, y+height-2, "Confirm", m.confirmYes)
		m.button(screen, 11, x+16, y+height-2, "Cancel", !m.confirmYes)
	} else {
		consoleText(screen, x+2, y+height-2, width-4, "Y confirm / Esc cancel", consoleMuted)
	}
}

func ConsoleConfirm(question string, timeout time.Duration) bool {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	screen, err := tcell.NewScreen()
	if err != nil {
		return false
	}
	return consoleConfirmScreen(ctx, screen, question)
}
func consoleConfirmScreen(ctx context.Context, screen tcell.Screen, question string) bool {
	if err := screen.Init(); err != nil {
		screen.Fini()
		return false
	}
	events, finish := consoleEvents(screen)
	defer finish()
	screen.EnableMouse(tcell.MouseButtonEvents)
	screen.HideCursor()
	m := consoleModel{confirm: ConsoleStop}
	draw := func() {
		screen.Fill(' ', consoleBase)
		m.drawConfirm(screen, "Confirm server operation", question)
		screen.Show()
	}
	draw()
	for {
		select {
		case <-ctx.Done():
			return false
		case event, ok := <-events:
			if !ok {
				return false
			}
			if key, ok := event.(*tcell.EventKey); ok && (key.Key() == tcell.KeyEsc || key.Key() == tcell.KeyCtrlC || key.Key() == tcell.KeyEnter && !m.confirmYes || key.Key() == tcell.KeyRune && (key.Rune() == 'n' || key.Rune() == 'N' || key.Rune() == '2')) {
				return false
			}
			if _, ok := event.(*tcell.EventResize); ok {
				screen.Sync()
			}
			a := m.event(event)
			if a == ConsoleStop {
				return true
			}
			if m.confirm == "" {
				return false
			}
			draw()
		}
	}
}
