package host

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/gorilla/websocket"
	"github.com/hinshun/vt10x"
)

type terminalPTY interface {
	io.ReadWriteCloser
	Resize(int, int) error
	Wait(context.Context) (uint32, error)
	Pid() int
}
type terminalClient struct {
	mu sync.Mutex
	ws *websocket.Conn
}

func (c *terminalClient) send(m M) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.ws.SetWriteDeadline(time.Now().Add(5 * time.Second))
	return c.ws.WriteJSON(m)
}

type nativeTerminal struct {
	mu          sync.Mutex
	info        M
	pty         terminalPTY
	screen      vt10x.Terminal
	clients     map[*terminalClient]bool
	history     []any
	screenDirty bool
	closed      bool
}
type terminalSet struct {
	mu         sync.Mutex
	terms      map[string]*nativeTerminal
	externalAt time.Time
	external   []M
	refreshing bool
}

var terminalSets sync.Map

func terminalsFor(a *App) *terminalSet {
	v, _ := terminalSets.LoadOrStore(a, &terminalSet{terms: map[string]*nativeTerminal{}})
	return v.(*terminalSet)
}
func platformTerminalsClose(a *App) {
	if v, ok := terminalSets.LoadAndDelete(a); ok {
		s := v.(*terminalSet)
		s.mu.Lock()
		defer s.mu.Unlock()
		for _, t := range s.terms {
			t.mu.Lock()
			t.closed = true
			for c := range t.clients {
				c.ws.Close()
			}
			t.mu.Unlock()
			t.pty.Close()
		}
	}
}

func registerTerminals(a *App, mux *http.ServeMux) {
	a.OnClose(func() { PlatformClose(a) })
	set := terminalsFor(a)
	mux.HandleFunc("GET /api/terminals", func(w http.ResponseWriter, r *http.Request) {
		set.mu.Lock()
		rows := []M{}
		for _, t := range set.terms {
			t.mu.Lock()
			rows = append(rows, clone(t.info))
			t.mu.Unlock()
		}
		set.mu.Unlock()
		rows = append(rows, terminalExternalList(set)...)
		sort.Slice(rows, func(i, j int) bool { return N(rows[i], "createdAt") > N(rows[j], "createdAt") })
		writeJSON(w, 200, rows)
	})
	mux.HandleFunc("POST /api/terminals", func(w http.ResponseWriter, r *http.Request) {
		m, e := readJSON(w, r)
		if e != nil {
			apiError(w, e)
			return
		}
		info, e := terminalCreate(set, S(m, "cwd"), S(m, "title"))
		if e != nil {
			apiError(w, e)
			return
		}
		writeJSON(w, 200, info)
	})
	mux.HandleFunc("GET /api/terminals/resolve", func(w http.ResponseWriter, r *http.Request) {
		tab := r.URL.Query().Get("tab")
		if !regexp.MustCompile(`^[A-Za-z0-9_-]{1,40}$`).MatchString(tab) {
			apiError(w, errors.New("Invalid tab name"))
			return
		}
		if id := terminalResolveTab(a, set, r.URL.Query().Get("session"), tab); id != "" {
			writeJSON(w, 200, M{"id": id})
			return
		}
		for _, t := range terminalExternalList(set) {
			if S(t, "id") == tab || S(t, "title") == tab {
				writeJSON(w, 200, M{"id": t["id"]})
				return
			}
		}
		apiError(w, &HTTPError{404, "Terminal not found. It may have been closed"})
	})
	for _, action := range []string{"write", "rename", "kill", "remove"} {
		action := action
		mux.HandleFunc("POST /api/terminals/{id}/"+action, func(w http.ResponseWriter, r *http.Request) {
			id := r.PathValue("id")
			m := M{}
			var e error
			if r.ContentLength != 0 {
				m, e = readJSON(w, r)
				if e != nil {
					apiError(w, e)
					return
				}
			}
			if strings.HasPrefix(id, "ext-") {
				result, e := terminalExternalAction(set, id, action, m)
				if e != nil {
					apiError(w, e)
					return
				}
				writeJSON(w, 200, result)
				return
			}
			set.mu.Lock()
			t := set.terms[id]
			set.mu.Unlock()
			if t == nil {
				apiError(w, &HTTPError{404, "This terminal was not found (it will disappear after restarting the server)"})
				return
			}
			result := M{"ok": true}
			switch action {
			case "write":
				_, e = t.pty.Write([]byte(S(m, "data")))
			case "rename":
				name := strings.TrimSpace(S(m, "title"))
				if name == "" {
					e = errors.New("Name is empty")
				} else {
					t.mu.Lock()
					t.info["title"] = name
					t.mu.Unlock()
				}
			case "kill":
				if S(m, "scope") == "command" {
					_, e = t.pty.Write([]byte{3})
				} else {
					e = t.pty.Close()
				}
				result = M{"killed": 1}
			case "remove":
				t.pty.Close()
				t.mu.Lock()
				t.closed = true
				for c := range t.clients {
					c.ws.Close()
				}
				t.mu.Unlock()
				set.mu.Lock()
				delete(set.terms, id)
				set.mu.Unlock()
			}
			if e != nil {
				apiError(w, e)
				return
			}
			writeJSON(w, 200, result)
		})
	}
	mux.HandleFunc("GET /api/terminals/{id}/ws", func(w http.ResponseWriter, r *http.Request) {
		ws, e := (&websocket.Upgrader{CheckOrigin: func(r *http.Request) bool { return r.Header.Get("Origin") == "" }}).Upgrade(w, r, nil)
		if e != nil {
			return
		}
		defer ws.Close()
		finished := make(chan struct{})
		defer close(finished)
		go func() {
			select {
			case <-a.closed:
				ws.Close()
			case <-finished:
			}
		}()
		client := &terminalClient{ws: ws}
		id := r.PathValue("id")
		if strings.HasPrefix(id, "ext-") {
			terminalExternalSocket(set, id, client)
			return
		}
		set.mu.Lock()
		t := set.terms[id]
		set.mu.Unlock()
		if t == nil {
			client.send(M{"type": "error", "message": "Terminal not found"})
			return
		}
		t.mu.Lock()
		t.clients[client] = true
		client.send(M{"type": "info", "info": clone(t.info)})
		client.send(terminalScreen(t))
		t.mu.Unlock()
		defer func() { t.mu.Lock(); delete(t.clients, client); t.mu.Unlock() }()
		ws.SetReadLimit(1 << 20)
		for {
			var m M
			if ws.ReadJSON(&m) != nil {
				return
			}
			switch S(m, "type") {
			case "input":
				t.pty.Write([]byte(S(m, "data")))
			case "resize":
				cols, rows := max(20, min(300, int(N(m, "cols")))), max(5, min(150, int(N(m, "rows"))))
				t.mu.Lock()
				if cols != int(N(t.info, "cols")) || rows != int(N(t.info, "rows")) {
					_ = t.pty.Resize(cols, rows)
					t.screen.Resize(cols, rows)
					t.info["cols"] = cols
					t.info["rows"] = rows
					terminalBroadcast(t, terminalScreen(t))
					t.screenDirty = false
				}
				t.mu.Unlock()
			case "resync":
				t.mu.Lock()
				client.send(terminalScreen(t))
				t.mu.Unlock()
			}
		}
	})
}

func terminalCreate(set *terminalSet, cwd, title string) (M, error) {
	if strings.HasPrefix(cwd, "~") {
		home, _ := os.UserHomeDir()
		if cwd == "~" || strings.HasPrefix(cwd, "~/") || strings.HasPrefix(cwd, `~\`) {
			cwd = filepath.Join(home, strings.TrimLeft(cwd[1:], `\/`))
		}
	}
	cwd, e := filepath.Abs(cwd)
	if e != nil {
		return nil, e
	}
	stat, e := os.Stat(cwd)
	if e != nil || !stat.IsDir() {
		return nil, fmt.Errorf("Folder not found: %s", cwd)
	}
	set.mu.Lock()
	defer set.mu.Unlock()
	alive := 0
	for _, t := range set.terms {
		t.mu.Lock()
		if B(t.info, "alive") {
			alive++
		}
		t.mu.Unlock()
	}
	if alive >= 12 {
		return nil, errors.New("You can open up to 12 terminals. Please close unused ones")
	}
	shell := platformFind("pwsh.exe", `C:\Program Files\PowerShell\7\pwsh.exe`)
	if shell == "" {
		shell = "powershell.exe"
	}
	pty, e := platformPTY(shell, cwd, 80, 30)
	if e != nil {
		return nil, e
	}
	if strings.TrimSpace(title) == "" {
		title = "Terminal"
	}
	id := strings.ReplaceAll(NewID(), "-", "")[:12]
	now := time.Now().UnixMilli()
	t := &nativeTerminal{info: M{"id": id, "title": title, "cwd": cwd, "shell": shell, "alive": true, "exitCode": nil, "createdAt": now, "lastOutputAt": now, "lastLine": "", "cols": 80, "rows": 30, "kind": "own", "source": "Remote Code", "self": false}, pty: pty, clients: map[*terminalClient]bool{}}
	t.screen = newTerminalScreen(t, 80, 30)
	set.terms[id] = t
	go terminalRead(t)
	return clone(t.info), nil
}

func newTerminalScreen(t *nativeTerminal, cols, rows int) vt10x.Terminal {
	return vt10x.New(vt10x.WithSize(cols, rows), vt10x.WithWriter(t.pty), vt10x.WithScrollback(func(row []vt10x.Glyph) {
		line := terminalLine(len(row), func(x int) vt10x.Glyph { return row[x] })
		appendTerminalHistory(&t.history, line)
	}))
}

func terminalBroadcast(t *nativeTerminal, frame M) {
	for client := range t.clients {
		if client.send(frame) != nil {
			delete(t.clients, client)
			client.ws.Close()
		}
	}
}

func terminalRead(t *nativeTerminal) {
	done := make(chan struct{})
	exit := make(chan uint32, 1)
	go func() {
		code, _ := t.pty.Wait(context.Background())
		time.Sleep(80 * time.Millisecond)
		t.pty.Close()
		<-done
		exit <- code
	}()
	go func() {
		defer close(done)
		buf := make([]byte, 32768+utf8.UTFMax-1)
		remaining := 0
		for {
			n, e := t.pty.Read(buf[remaining:])
			if n > 0 {
				t.mu.Lock()
				data := buf[:remaining+n]
				written, _ := t.screen.Write(data)
				remaining = copy(buf, data[written:])
				t.info["lastOutputAt"] = time.Now().UnixMilli()
				t.screenDirty = true
				t.mu.Unlock()
			}
			if e != nil {
				return
			}
		}
	}()
	tick := time.NewTicker(40 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case code := <-exit:
			t.mu.Lock()
			t.info["alive"] = false
			t.info["exitCode"] = int(code)
			terminalBroadcast(t, terminalScreen(t))
			terminalBroadcast(t, M{"type": "exit", "code": int(code)})
			t.mu.Unlock()
			return
		case <-tick.C:
			t.mu.Lock()
			if t.closed {
				t.mu.Unlock()
				return
			}
			if t.screenDirty {
				terminalBroadcast(t, terminalScreen(t))
				t.screenDirty = false
			}
			t.mu.Unlock()
		}
	}
}

func terminalColor(c vt10x.Color) int {
	v := int(c)
	if c == vt10x.DefaultFG || c == vt10x.DefaultBG || c == vt10x.DefaultCursor {
		return -1
	}
	if v > 255 {
		return v | 0x1000000
	}
	return v
}
func terminalScreen(t *nativeTerminal) M {
	t.screen.Lock()
	defer t.screen.Unlock()
	cols, rows := t.screen.Size()
	lines := make([]any, rows)
	for y := 0; y < rows; y++ {
		lines[y] = terminalLine(cols, func(x int) vt10x.Glyph { return t.screen.Cell(x, y) })
	}
	alt := t.screen.Mode()&vt10x.ModeAltScreen != 0
	history := t.history
	if alt {
		history = nil
	}
	cursor := t.screen.Cursor()
	all := append(append([]any{}, history...), lines...)
	for y := len(lines) - 1; y >= 0; y-- {
		s := terminalPlain(lines[y])
		if strings.TrimSpace(s) != "" {
			t.info["lastLine"] = strings.TrimSpace(s)
			break
		}
	}
	return M{"type": "screen", "reset": true, "drop": 0, "from": 0, "lines": all, "cursor": []int{cursor.X, len(history) + cursor.Y}, "cols": cols, "rows": rows, "alt": alt, "appCursor": t.screen.Mode()&vt10x.ModeAppCursor != 0}
}
func terminalLine(cols int, cell func(int) vt10x.Glyph) []any {
	runs := []any{}
	var text strings.Builder
	fg, bg, flags := -1, -1, 0
	flush := func() {
		if text.Len() == 0 {
			return
		}
		s := text.String()
		if fg == -1 && bg == -1 && flags == 0 {
			runs = append(runs, []any{s})
		} else {
			runs = append(runs, []any{s, fg, bg, flags})
		}
		text.Reset()
	}
	for x := 0; x < cols; x++ {
		g := cell(x)
		f, b := terminalColor(g.FG), terminalColor(g.BG)
		fl := 0
		if g.Mode&1 != 0 {
			fl |= 8
		}
		if g.Mode&2 != 0 {
			fl |= 4
		}
		if g.Mode&4 != 0 {
			fl |= 1
		}
		if g.Mode&16 != 0 {
			fl |= 2
		}
		if f != fg || b != bg || fl != flags {
			flush()
			fg, bg, flags = f, b, fl
		}
		if g.Char == 0 {
			text.WriteByte(' ')
		} else {
			text.WriteRune(g.Char)
		}
	}
	flush()
	return runs
}
func terminalPlain(line any) string {
	var text strings.Builder
	switch rows := line.(type) {
	case []any:
		for _, r := range rows {
			if x, ok := r.([]any); ok && len(x) > 0 {
				if s, ok := x[0].(string); ok {
					text.WriteString(s)
				}
			}
		}
	}
	return strings.TrimRight(text.String(), " ")
}
func terminalRemember(history *[]any, previous, next []any) {
	h := min(len(previous), len(next))
	if h < 3 {
		return
	}
	equal := func(a, b any) bool { x, _ := json.Marshal(a); y, _ := json.Marshal(b); return string(x) == string(y) }
	if equal(previous[0], next[0]) {
		return
	}
	for k := 1; k < h; k++ {
		ok, filled := true, false
		for j := 0; j < h-k-1; j++ {
			if !equal(previous[k+j], next[j]) {
				ok = false
				break
			}
			if terminalPlain(previous[k+j]) != "" {
				filled = true
			}
		}
		if ok && filled {
			appendTerminalHistory(history, previous[:k]...)
			return
		}
	}
}

func appendTerminalHistory(history *[]any, lines ...any) {
	*history = append(*history, lines...)
	if len(*history) > 3000 {
		*history = (*history)[len(*history)-3000:]
	}
}

func terminalExternalDiscover() []M {
	processes, e := platformProcesses()
	if e != nil {
		return nil
	}
	byID := map[uint32]platformProcess{}
	for _, p := range processes {
		byID[p.PID] = p
	}
	hosts := map[string]string{"claude.exe": "Claude", "codex.exe": "Codex", "antigravity.exe": "Antigravity"}
	shells := map[string]bool{"pwsh.exe": true, "powershell.exe": true, "cmd.exe": true, "bash.exe": true}
	rows := []M{}
	for _, p := range processes {
		parent := byID[p.Parent]
		source := hosts[strings.ToLower(parent.Name)]
		if source == "" || !shells[strings.ToLower(p.Name)] {
			continue
		}
		self := false
		cur := byID[uint32(os.Getpid())]
		for i := 0; i < 30 && cur.PID != 0; i++ {
			if cur.Parent == p.PID {
				self = true
				break
			}
			cur = byID[cur.Parent]
		}
		lower := strings.ToLower(p.Command)
		if strings.Contains(lower, "-noninteractive") || strings.ToLower(p.Name) == "cmd.exe" && strings.Contains(lower, " /c ") || strings.ToLower(p.Name) == "bash.exe" && strings.Contains(lower, " -c ") {
			continue
		}
		running := []string{}
		for _, child := range processes {
			if child.Parent == p.PID && !strings.EqualFold(child.Name, "conhost.exe") && !strings.EqualFold(child.Name, "openconsole.exe") {
				command := strings.TrimSpace(child.Command)
				if command == "" {
					command = child.Name
				}
				running = append(running, command)
			}
		}
		title := strings.TrimSuffix(p.Name, ".exe")
		if len(running) > 0 {
			title = strings.Join(running, " / ")
			if len(title) > 160 {
				title = title[:160]
			}
		}
		rows = append(rows, M{"id": fmt.Sprintf("ext-%d", p.PID), "pid": int(p.PID), "title": title, "running": strings.Join(running, " / "), "cwd": "", "shell": p.Name, "source": source, "self": self, "alive": true, "exitCode": nil, "createdAt": 0, "lastOutputAt": 0, "lastLine": "", "cols": 80, "rows": 24, "kind": "external"})
	}
	return rows
}
func terminalExternalList(set *terminalSet) []M {
	set.mu.Lock()
	defer set.mu.Unlock()
	if set.externalAt.IsZero() {
		set.external = terminalExternalDiscover()
		set.externalAt = time.Now()
	}
	if time.Since(set.externalAt) > 4*time.Second && !set.refreshing {
		set.refreshing = true
		go func() {
			rows := terminalExternalDiscover()
			set.mu.Lock()
			set.external = rows
			set.externalAt = time.Now()
			set.refreshing = false
			set.mu.Unlock()
		}()
	}
	if set.external == nil {
		return []M{}
	}
	return clone(set.external)
}
func terminalExternalKnown(set *terminalSet, id string) (M, error) {
	for _, m := range terminalExternalDiscover() {
		if S(m, "id") == id {
			if B(m, "self") {
				return nil, errors.New("Cannot control the server's own terminal")
			}
			return m, nil
		}
	}
	return nil, errors.New("Terminal not found (it may have been closed)")
}
func terminalExternalAction(set *terminalSet, id, action string, m M) (M, error) {
	if action == "rename" || action == "remove" {
		return nil, errors.New("Terminals opened by other applications cannot be renamed or removed from here")
	}
	row, e := terminalExternalKnown(set, id)
	if e != nil {
		return nil, e
	}
	pid := int(N(row, "pid"))
	if action == "kill" && S(m, "scope") != "command" {
		if e = platformKillProcess(pid); e != nil {
			return nil, e
		}
		return M{"killed": 1}, nil
	}
	text := S(m, "data")
	if action == "kill" {
		text = "\x03"
	}
	exe, _ := os.Executable()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	cmd := platformCommand(ctx, exe, "--console-bridge", "input", strconv.Itoa(pid), base64.StdEncoding.EncodeToString([]byte(text)))
	if out, e := cmd.CombinedOutput(); e != nil {
		return nil, fmt.Errorf("Failed to write terminal: %s", strings.TrimSpace(string(out)))
	}
	if action == "kill" {
		return M{"killed": 1}, nil
	}
	return M{"ok": true}, nil
}
func terminalExternalSocket(set *terminalSet, id string, c *terminalClient) {
	info, e := terminalExternalKnown(set, id)
	if e != nil {
		c.send(M{"type": "error", "message": e.Error()})
		return
	}
	pid := int(N(info, "pid"))
	exe, _ := os.Executable()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	cmd := platformCommand(ctx, exe, "--console-bridge", "attach", strconv.Itoa(pid))
	out, e := cmd.StdoutPipe()
	if e != nil {
		return
	}
	in, e := cmd.StdinPipe()
	if e != nil {
		return
	}
	if cmd.Start() != nil {
		return
	}
	defer cmd.Wait()
	c.send(M{"type": "info", "info": info})
	var inputMu sync.Mutex
	go func() {
		defer cancel()
		for {
			var m M
			if c.ws.ReadJSON(&m) != nil {
				return
			}
			if S(m, "type") == "input" {
				inputMu.Lock()
				fmt.Fprintln(in, "I"+base64.StdEncoding.EncodeToString([]byte(S(m, "data"))))
				inputMu.Unlock()
			}
		}
	}()
	scanner := bufio.NewScanner(out)
	scanner.Buffer(make([]byte, 4096), 8<<20)
	var history, previous []any
	for scanner.Scan() {
		var m M
		if json.Unmarshal(scanner.Bytes(), &m) != nil {
			continue
		}
		if S(m, "type") == "screen" {
			lines, _ := m["lines"].([]any)
			terminalRemember(&history, previous, lines)
			previous = lines
			c.send(M{"type": "screen", "reset": true, "drop": 0, "from": 0, "lines": append(append([]any{}, history...), lines...), "cursor": []int{int(N(m, "cx")), len(history) + int(N(m, "cy"))}, "cols": m["w"], "rows": m["h"], "alt": false, "appCursor": false})
		} else {
			c.send(m)
		}
	}
}
