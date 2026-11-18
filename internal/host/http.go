package host

import (
	"compress/gzip"
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type handler func(*http.Request, M) (any, error)

func route(mux *http.ServeMux, pattern string, f handler) {
	mux.HandleFunc(pattern, func(w http.ResponseWriter, r *http.Request) {
		body := M{}
		if r.Method != "GET" && r.Method != "HEAD" && r.ContentLength != 0 {
			var err error
			body, err = readJSON(w, r)
			if err != nil {
				apiError(w, err)
				return
			}
		}
		v, err := f(r, body)
		if err != nil {
			apiError(w, err)
			return
		}
		if v == nil {
			v = M{"ok": true}
		}
		writeJSON(w, 200, v)
	})
}
func (a *App) session(id string) (*Record, error) {
	rec := a.Store.Get(id)
	if rec == nil {
		return nil, &HTTPError{404, "Session not found"}
	}
	return rec, nil
}

func (a *App) sessionInfoRecord(id string) (*Record, error) {
	rec := a.Store.infoRecord(id)
	if rec == nil {
		return nil, &HTTPError{404, "Session not found"}
	}
	return rec, nil
}
func (a *App) provider(name string) (Provider, error) {
	p := a.Providers[name]
	if p == nil {
		return nil, errors.New("Unknown provider: " + name)
	}
	return p, nil
}
func (a *App) do(ctx context.Context, action string, rec *Record, body M) (any, error) {
	p, err := a.provider(S(rec.Info, "provider"))
	if err != nil {
		return nil, err
	}
	return p.Do(ctx, action, rec, body)
}
func (a *App) Handler() http.Handler {
	return a.handler(a.closed)
}

func (a *App) handler(serverDone <-chan struct{}) http.Handler {
	mux := http.NewServeMux()
	route(mux, "GET /api/dependencies", func(r *http.Request, b M) (any, error) { return a.dependencyState(), nil })
	mux.HandleFunc("POST /api/dependencies/{target}/install", func(w http.ResponseWriter, r *http.Request) {
		if r.ContentLength != 0 {
			if _, err := readJSON(w, r); err != nil {
				apiError(w, err)
				return
			}
		}
		if err := a.startDependencyInstall(r.PathValue("target")); err != nil {
			apiError(w, err)
			return
		}
		writeJSON(w, http.StatusAccepted, a.dependencyState())
	})
	route(mux, "GET /api/health", func(r *http.Request, b M) (any, error) { return M{"ok": true, "name": "remote-code"}, nil })
	route(mux, "GET /api/meta", func(r *http.Request, b M) (any, error) {
		home, _ := os.UserHomeDir()
		hostname, _ := os.Hostname()
		ps := M{}
		for name, p := range a.Providers {
			modes, _ := p.Do(r.Context(), "modes", nil, M{})
			if modes == nil {
				modes = []M{}
			}
			ps[name] = M{"available": p.Available(), "modes": modes}
		}
		publicURL := a.Config.PublicURL
		if raw, err := os.ReadFile(filepath.Join(a.DataDir, "runtime.json")); err == nil {
			var rt M
			if json.Unmarshal(raw, &rt) == nil {
				publicURL = S(rt, "publicUrl")
			}
		}
		return M{"name": "remote-code", "version": Version, "instanceId": a.InstanceID, "startedAt": a.StartedAt, "host": hostname, "home": home, "publicUrl": publicURL, "providers": ps, "usdJpy": a.fx.usdJpy(defaultNumber(a.Config.Raw, "usdJpy", 150)), "defaults": pcDefaults(a.DataDir), "acceptingWrites": true}, nil
	})
	route(mux, "GET /api/sessions", func(r *http.Request, b M) (any, error) { return a.Store.List(), nil })
	route(mux, "POST /api/sessions", func(r *http.Request, b M) (any, error) {
		p, err := a.provider(S(b, "provider"))
		if err != nil {
			return nil, err
		}
		cwd := strings.TrimSpace(S(b, "cwd"))
		if cwd == "" {
			cwd, _ = os.UserHomeDir()
		}
		cwd, err = filepath.Abs(cwd)
		if err != nil {
			return nil, err
		}
		st, err := os.Stat(cwd)
		if err != nil || !st.IsDir() {
			return nil, errors.New("Folder not found: " + cwd)
		}
		text, err := a.messageText(b)
		if err != nil {
			return nil, err
		}
		title := strings.TrimSpace(S(b, "title"))
		if title == "" {
			title = preview(S(b, "text"))
		}
		if title == "" {
			title = filepath.Base(cwd)
		}
		thinking := S(b, "thinking")
		if thinking != "summary" && thinking != "off" {
			thinking = "full"
		}
		init := M{"provider": S(b, "provider"), "cwd": cwd, "title": title, "mode": S(b, "mode"), "model": b["model"], "effort": b["effort"], "thinking": thinking}
		if _, ok := b["fastMode"]; ok {
			if _, ok = b["fastMode"].(bool); !ok {
				return nil, errors.New("Fast mode must be specified as ON or OFF")
			}
			init["fastMode"] = b["fastMode"]
		}
		rec := a.Store.Create(init)
		if text != "" || len(array(b["images"])) > 0 {
			body := clone(b)
			body["text"] = text
			if _, err = p.Do(r.Context(), "send", rec, body); err != nil {
				return nil, err
			}
		} else if S(init, "provider") == "antigravity" && agyModelUnset(S(init, "model")) && agyModelUnset(S(agyPCDefaults(), "model")) {
			// With no PC preference, only the new ACP session knows the account's default.
			// The empty draft remains usable if the CLI is temporarily offline.
			_, _ = p.Do(r.Context(), "initialize", rec, nil)
		}
		return a.Store.Get(S(rec.Info, "id")).Info, nil
	})
	route(mux, "GET /api/sessions/{id}", func(r *http.Request, b M) (any, error) {
		rec, revision := a.Store.timelineSnapshot(r.PathValue("id"))
		if rec == nil {
			return nil, &HTTPError{404, "Session not found"}
		}
		n, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		entries := rec.Entries
		if S(rec.Info, "provider") == "claude" {
			a.refreshClaudePlan(rec)
			entries = claudeStoredTimeline(entries)
		}
		entries = selectEntries(entries, n)
		for _, entry := range entries {
			kind := S(entry, "kind")
			if !B(entry, "streaming") && (kind == "user" || kind == "assistant" || strings.EqualFold(S(entry, "tool"), "Read")) {
				EnrichEntryMedia(a.DataDir, S(rec.Info, "cwd"), entry)
			}
		}
		a.startTask(func() { a.refreshAnalytics(&Record{Info: clone(rec.Info)}) })
		return M{"session": rec.Info, "entries": entries, "total": len(rec.Entries), "timelineRevision": revision}, nil
	})
	route(mux, "PATCH /api/sessions/{id}", func(r *http.Request, b M) (any, error) {
		rec, err := a.session(r.PathValue("id"))
		if err != nil {
			return nil, err
		}
		id := S(rec.Info, "id")
		patch := M{}
		if title := strings.TrimSpace(S(b, "title")); title != "" {
			patch["title"] = title
			patch["titlePinned"] = true
		}
		if v, ok := b["archived"].(bool); ok {
			patch["archived"] = v
		}
		a.Store.Update(id, patch, false)
		_, model := b["model"]
		_, effort := b["effort"]
		_, fastMode := b["fastMode"].(bool)
		_, ultra := b["ultra"]
		combineCodexSettings := S(rec.Info, "provider") == "codex" && (model || effort) && fastMode && !ultra
		for _, item := range []struct{ key, action string }{{"mode", "setMode"}, {"thinking", "setThinking"}, {"model", "setModel"}, {"ultra", "setUltra"}, {"fastMode", "setFastMode"}} {
			_, has := b[item.key]
			if item.key == "model" {
				has = model || effort
				if combineCodexSettings {
					item.action = "setModelAndFastMode"
				}
			} else if item.key == "fastMode" && combineCodexSettings {
				continue
			}
			if has {
				if _, err = a.do(r.Context(), item.action, a.Store.Get(id), b); err != nil {
					return nil, err
				}
			}
		}
		return a.Store.Get(id).Info, nil
	})
	route(mux, "DELETE /api/sessions/{id}", func(r *http.Request, b M) (any, error) {
		rec, err := a.session(r.PathValue("id"))
		if err != nil {
			return nil, err
		}
		if _, err = a.do(r.Context(), "detach", rec, b); err != nil {
			return nil, err
		}
		return nil, a.Store.Remove(S(rec.Info, "id"))
	})
	for _, action := range []string{"messages", "interrupt", "detach", "respond", "takeover", "command", "rewind", "compact", "compact-window"} {
		action := action
		route(mux, "POST /api/sessions/{id}/"+action, func(r *http.Request, b M) (any, error) {
			rec, err := a.session(r.PathValue("id"))
			if err != nil {
				return nil, err
			}
			mapped := action
			switch action {
			case "messages":
				mapped = "send"
				text, e := a.messageText(b)
				if e != nil {
					return nil, e
				}
				b["text"] = text
				if text == "" && len(array(b["images"])) == 0 {
					return nil, errors.New("Message is empty")
				}
				if B(b, "now") && S(rec.Info, "status") != "idle" {
					_, _ = a.do(r.Context(), "interrupt", rec, M{})
					rec, err = a.session(r.PathValue("id"))
					if err != nil {
						return nil, err
					}
				}
			case "command":
				mapped = "runCommand"
				b["name"] = strings.TrimLeft(strings.TrimSpace(S(b, "name")), "/$")
				if S(b, "name") == "" {
					return nil, errors.New("Missing command name")
				}
			case "compact-window":
				mapped = "setCompactWindow"
				if b["tokens"] != nil && (N(b, "tokens") < 100000 || N(b, "tokens") > 1000000) {
					return nil, errors.New("Length must be between 100k and 1M tokens")
				}
			case "respond":
				if S(b, "requestId") == "" {
					return nil, errors.New("Missing requestId")
				}
			}
			v, err := a.do(r.Context(), mapped, rec, b)
			if err != nil {
				return nil, err
			}
			if action == "compact-window" || action == "takeover" {
				return a.Store.Get(S(rec.Info, "id")).Info, nil
			}
			if action == "rewind" {
				return v, nil
			}
			return M{"ok": true}, nil
		})
	}
	for _, item := range []struct{ path, action string }{{"context", "contextDetail"}, {"cache", "cacheDetail"}} {
		item := item
		route(mux, "GET /api/sessions/{id}/"+item.path, func(r *http.Request, b M) (any, error) {
			rec, err := a.sessionInfoRecord(r.PathValue("id"))
			if err != nil {
				return nil, err
			}
			if item.path == "cache" {
				_, detail, analysisErr := a.analytics(rec)
				if analysisErr == nil && detail["summary"] != nil {
					return detail, nil
				}
			}
			if item.path == "context" {
				return a.contextSnapshot(r.Context(), rec, r.URL.Query().Get("cached") == "1")
			}
			return a.do(r.Context(), item.action, rec, M{"cached": r.URL.Query().Get("cached") == "1"})
		})
	}
	for _, item := range []struct{ path, action string }{{"models", "listModels"}, {"commands", "listCommands"}, {"history", "listHistory"}} {
		item := item
		route(mux, "GET /api/"+item.path+"/{provider}", func(r *http.Request, b M) (any, error) {
			name := r.PathValue("provider")
			p, err := a.provider(name)
			if err != nil {
				return nil, err
			}
			v, err := p.Do(r.Context(), item.action, a.Store.Get(r.URL.Query().Get("session")), b)
			if err != nil {
				return nil, err
			}
			if item.action == "listHistory" {
				rows := maps(v)
				a.rememberFolderHistory(name, rows)
				sessions := a.Store.historySessions(name)
				for _, row := range rows {
					row["sessionId"] = nil
					if session, ok := sessions[S(row, "externalId")]; ok {
						row["sessionId"] = session.id
					}
				}
				return rows, nil
			}
			return v, nil
		})
	}
	route(mux, "POST /api/history/{provider}/import", func(r *http.Request, b M) (any, error) {
		name := r.PathValue("provider")
		p, err := a.provider(name)
		if err != nil {
			return nil, err
		}
		externalID := S(b, "externalId")
		if externalID == "" {
			return nil, errors.New("Missing externalId")
		}
		if rec := a.Store.FindExternal(name, externalID); rec != nil {
			check, cancel := context.WithTimeout(r.Context(), 20*time.Second)
			_, _ = p.Do(check, "rejoin", rec, b)
			cancel()
			current, err := a.session(S(rec.Info, "id"))
			if err != nil {
				return nil, err
			}
			return current.Info, nil
		}
		cwd := S(b, "cwd")
		if cwd == "" {
			cwd, _ = os.UserHomeDir()
		}
		rec := a.Store.Create(M{"provider": name, "cwd": cwd, "title": S(b, "title"), "mode": "", "model": nil, "effort": nil, "thinking": "full", "externalId": externalID})
		id := S(rec.Info, "id")
		if _, err = p.Do(r.Context(), "importHistory", rec, b); err != nil {
			a.Store.Add(id, M{"kind": "system", "level": "error", "text": "Failed to load history: " + err.Error()})
		}
		current, err := a.session(id)
		if err != nil {
			return nil, err
		}
		return current.Info, nil
	})
	route(mux, "GET /api/usage", func(r *http.Request, b M) (any, error) { return a.getUsage(r.URL.Query().Get("force") == "1"), nil })
	route(mux, "POST /api/client/crash", func(r *http.Request, b M) (any, error) {
		text := S(b, "text")
		if text == "" {
			return nil, errors.New("Transcript is empty")
		}
		if len(text) > 60000 {
			text = text[:60000]
		}
		dir := filepath.Join(a.DataDir, "crash")
		if err := os.MkdirAll(dir, 0700); err != nil {
			return nil, err
		}
		return nil, os.WriteFile(filepath.Join(dir, time.Now().UTC().Format("2006-01-02T15-04-05.000Z")+".txt"), []byte(text), 0600)
	})
	mux.HandleFunc("GET /api/events", a.events)
	registerDesktopClipboard(mux, desktopSupported(), desktopClipboardRead, desktopClipboardWrite)
	registerDesktop(mux, desktopBackend{desktopSupported(), listDesktopDisplays, desktopCaptureMethods, captureDesktopFrame, applyDesktopInput}, serverDone)
	a.registerBtw(mux)
	a.registerFolders(mux)
	a.registerManage(mux)
	a.registerControlPaths(mux)
	RegisterFiles(a, mux)
	RegisterPlatform(a, mux)
	return a.middleware(mux)
}
func defaultNumber(m M, key string, fallback float64) float64 {
	if n := N(m, key); n != 0 {
		return n
	}
	return fallback
}
func array(v any) []any {
	a, _ := v.([]any)
	if a != nil {
		return a
	}
	if ms, ok := v.([]M); ok {
		a = make([]any, len(ms))
		for i, m := range ms {
			a[i] = m
		}
	}
	return a
}
func maps(v any) []M {
	if rows, ok := v.([]M); ok {
		return rows
	}
	rows := []M{}
	for _, x := range array(v) {
		if m, ok := x.(map[string]any); ok {
			rows = append(rows, m)
		}
	}
	return rows
}
func (a *App) messageText(body M) (string, error) {
	files, err := ResolveUploads(a.DataDir, body["files"])
	if err != nil {
		return "", err
	}
	return AttachmentsPrompt(strings.TrimSpace(S(body, "text")), files), nil
}
func selectEntries(entries []M, limit int) []M {
	visible := make([]M, 0, len(entries))
	for _, entry := range entries {
		if S(entry, "kind") == "thinking" && !B(entry, "streaming") && strings.TrimSpace(S(entry, "text")) == "" {
			continue
		}
		visible = append(visible, entry)
	}
	entries = visible
	if limit <= 0 || limit >= len(entries) {
		return entries
	}
	byID := map[string]M{}
	selected := map[string]bool{}
	for _, e := range entries {
		byID[S(e, "id")] = e
	}
	include := func(e M) {
		for e != nil && !selected[S(e, "id")] {
			selected[S(e, "id")] = true
			e = byID[S(e, "parent")]
		}
	}
	for _, e := range entries[len(entries)-limit:] {
		include(e)
	}
	for _, e := range entries {
		if isTimelineMessage(e) || S(e, "kind") == "tool" && S(e, "tool") == "Agent" {
			include(e)
		}
	}
	out := []M{}
	for _, e := range entries {
		if selected[S(e, "id")] {
			out = append(out, e)
		}
	}
	return out
}
func (a *App) getUsage(force bool) M {
	a.mu.Lock()
	defer a.mu.Unlock()
	for name, p := range a.Providers {
		if force || time.Since(a.usageAt[name]) >= a.usageIntervalLocked(name) {
			a.startUsageRefreshLocked(name, p, force)
		}
	}
	out := clone(a.usage)
	for name := range a.Providers {
		if out[name] == nil {
			out[name] = M{"windows": []M{}, "at": nowMS()}
		}
	}
	return out
}

func (a *App) observeProviderUsage(name string, usage M) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.updateUsageLocked(name, usage, S(usage, "error"))
	// Codex pushes may omit the reset-credit details that its explicit read returns.
	if name == "codex" && time.Since(a.usageAt[name]) >= a.usageIntervalLocked(name) {
		a.startUsageRefreshLocked(name, a.Providers[name], true)
	}
}

func (a *App) usageIntervalLocked(name string) time.Duration {
	if S(providerMap(a.usage[name]), "error") != "" {
		return 30 * time.Second
	}
	if name == "codex" {
		return 2 * time.Minute
	}
	return 5 * time.Minute
}

func (a *App) startUsageRefreshLocked(name string, provider Provider, force bool) {
	if provider == nil || a.usageLoading[name] {
		return
	}
	a.usageLoading[name] = a.startTask(func() {
		startedAt := time.Now()
		ctx, cancel := context.WithTimeout(a.taskCtx, 45*time.Second)
		defer cancel()
		value, err := provider.Do(ctx, "usage", nil, M{"force": force})
		a.mu.Lock()
		defer a.mu.Unlock()
		a.usageLoading[name] = false
		if a.taskCtx.Err() != nil {
			return
		}
		a.usageAt[name] = time.Now()
		next := providerMap(value)
		message := S(next, "error")
		if err != nil {
			message = err.Error()
		}
		if message == "" || !a.usageSeenAt[name].After(startedAt) {
			a.updateUsageLocked(name, next, message)
		}
	})
}

func (a *App) updateUsageLocked(name string, next M, message string) {
	previous := providerMap(a.usage[name])
	if N(previous, "at") > N(next, "at") && message == "" {
		return
	}
	if message != "" && len(previous) > 0 {
		next = previous
	}
	next = clone(next)
	if len(next) == 0 {
		next = M{"windows": []M{}, "at": nowMS()}
	}
	if name == "codex" {
		for _, key := range []string{"credits", "tickets", "plan"} {
			if next[key] == nil && previous[key] != nil {
				next[key] = previous[key]
			}
		}
	}
	if message != "" {
		next["error"] = message
	}
	a.usageSeenAt[name] = time.Now()
	a.usage[name] = next
	_ = atomicJSON(filepath.Join(a.DataDir, "usage-cache.json"), a.usage)
	a.Store.Emit(M{"type": "usage", "provider": name, "usage": next})
}

const eventWriteTimeout = 10 * time.Second

var eventUpgrader = websocket.Upgrader{ReadBufferSize: 1024, WriteBufferSize: 4096, CheckOrigin: func(r *http.Request) bool { return r.Header.Get("Origin") == "" }}

func (a *App) events(w http.ResponseWriter, r *http.Request) {
	ws, err := eventUpgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer ws.Close()
	ws.SetReadLimit(1 << 20)
	_ = ws.SetReadDeadline(time.Now().Add(60 * time.Second))
	ws.SetPongHandler(func(string) error { return ws.SetReadDeadline(time.Now().Add(60 * time.Second)) })
	events, snapshot, unsubscribe := a.Store.SubscribeSnapshot()
	defer unsubscribe()
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			if _, _, err := ws.ReadMessage(); err != nil {
				return
			}
		}
	}()
	_ = ws.SetWriteDeadline(time.Now().Add(eventWriteTimeout))
	if err = ws.WriteJSON(M{"type": "hello", "sessions": snapshot}); err != nil {
		return
	}
	t := time.NewTicker(25 * time.Second)
	defer t.Stop()
	for {
		select {
		case ev, ok := <-events:
			if !ok {
				return
			}
			_ = ws.SetWriteDeadline(time.Now().Add(eventWriteTimeout))
			if err = ws.WriteJSON(ev); err != nil {
				return
			}
		case <-t.C:
			if err = ws.WriteControl(websocket.PingMessage, nil, time.Now().Add(5*time.Second)); err != nil {
				return
			}
		case <-done:
			return
		case <-a.closed:
			return
		}
	}
}

type authFailure struct {
	count int
	at    time.Time
}

func (a *App) middleware(next http.Handler) http.Handler {
	var mu sync.Mutex
	fails := map[string]authFailure{}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		w.Header().Set("X-Remote-Code-Version", Version)
		w.Header().Set("X-Remote-Code-Started-At", strconv.FormatInt(a.StartedAt, 10))
		w.Header().Set("X-Remote-Code-Instance", a.InstanceID)
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		host, _, _ := net.SplitHostPort(r.RemoteAddr)
		ip := net.ParseIP(strings.SplitN(host, "%", 2)[0])
		lan := ip.IsPrivate() || ip.To4() == nil && ip.IsLinkLocalUnicast()
		if ip == nil || !(ip.IsLoopback() || a.Config.AllowLAN && lan) {
			writeJSON(w, 403, M{"error": "Connection is not allowed"})
			return
		}
		public := r.URL.Path == "/api/health" || strings.HasPrefix(r.URL.Path, "/dl/")
		if !public {
			key := host
			if ip.IsLoopback() && r.Header.Get("CF-Connecting-IP") != "" {
				key = "cf:" + r.Header.Get("CF-Connecting-IP")
			}
			mu.Lock()
			f := fails[key]
			if time.Since(f.at) > 10*time.Minute {
				f = authFailure{at: time.Now()}
			}
			if f.count >= 20 {
				mu.Unlock()
				writeJSON(w, 429, M{"error": "Please wait a moment and try again"})
				return
			}
			given := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
			valid := strings.HasPrefix(r.Header.Get("Authorization"), "Bearer ") && subtle.ConstantTimeCompare([]byte(a.Config.Token), []byte(given)) == 1
			if !valid {
				f.count++
				fails[key] = f
				if len(fails) > 5000 {
					fails = map[string]authFailure{key: f}
				}
				mu.Unlock()
				writeJSON(w, 401, M{"error": "Invalid token"})
				return
			}
			delete(fails, key)
			mu.Unlock()
		}
		if r.Header.Get("Upgrade") != "" {
			next.ServeHTTP(w, r)
			return
		}
		if !a.beginRequest() {
			writeJSON(w, 503, M{"error": "Server is restarting or stopping. Please reconnect."})
			return
		}
		defer a.requests.Done()
		w = &timedWriter{ResponseWriter: w, started: started}
		if strings.Contains(r.Header.Get("Accept-Encoding"), "gzip") && r.Method != "HEAD" && !strings.HasPrefix(r.URL.Path, "/api/desktop/") && !strings.Contains(r.URL.Path, "/media/") && !strings.HasSuffix(r.URL.Path, "/raw") && !strings.HasSuffix(r.URL.Path, ".apk") && !strings.HasSuffix(r.URL.Path, "/apk") {
			w.Header().Set("Content-Encoding", "gzip")
			w.Header().Set("Vary", "Accept-Encoding")
			gz := gzip.NewWriter(w)
			defer gz.Close()
			next.ServeHTTP(&gzipWriter{ResponseWriter: w, Writer: gz}, r)
			return
		}
		next.ServeHTTP(w, r)
	})
}

type gzipWriter struct {
	http.ResponseWriter
	io.Writer
}

type timedWriter struct {
	http.ResponseWriter
	started time.Time
	written bool
}

func (w *timedWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func (w *timedWriter) WriteHeader(code int) {
	if w.written {
		return
	}
	w.written = true
	w.Header().Set("Server-Timing", fmt.Sprintf("app;dur=%.1f", float64(time.Since(w.started).Microseconds())/1000))
	w.ResponseWriter.WriteHeader(code)
}
func (w *timedWriter) Write(b []byte) (int, error) {
	if !w.written {
		w.WriteHeader(200)
	}
	return w.ResponseWriter.Write(b)
}

func (w *gzipWriter) Write(b []byte) (int, error) { return w.Writer.Write(b) }
func (a *App) registerFolders(mux *http.ServeMux) {
	route(mux, "GET /api/recent-dirs", func(r *http.Request, b M) (any, error) {
		out := []string{}
		seen := map[string]bool{}
		for _, s := range a.Store.List() {
			p := S(s, "cwd")
			if !seen[strings.ToLower(p)] && isDirectory(p) {
				out = append(out, p)
				seen[strings.ToLower(p)] = true
			}
			if len(out) >= 12 {
				break
			}
		}
		return out, nil
	})
	route(mux, "GET /api/dirs", func(r *http.Request, b M) (any, error) {
		a.startFolderHistoryRefresh()
		home, _ := os.UserHomeDir()
		favorites := []M{}
		for _, p := range a.favorites() {
			if isDirectory(p) {
				favorites = append(favorites, M{"path": p, "name": filepath.Base(p), "git": hasGitFolder(p)})
			}
		}
		return M{"home": home, "roots": quickFolderRoots(home), "recent": a.recentFolders(), "favorites": favorites}, nil
	})
	route(mux, "POST /api/favorites", func(r *http.Request, b M) (any, error) {
		p, err := filepath.Abs(S(b, "path"))
		if err != nil {
			return nil, err
		}
		if !isDirectory(p) {
			return nil, errors.New("Folder not found: " + p)
		}
		a.mu.Lock()
		defer a.mu.Unlock()
		list := []string{}
		if b["on"] != false {
			list = append(list, p)
		}
		for _, old := range a.favorites() {
			if !strings.EqualFold(old, p) {
				list = append(list, old)
			}
		}
		return list, atomicJSON(filepath.Join(a.DataDir, "favorites.json"), list)
	})
	route(mux, "GET /api/fs", func(r *http.Request, b M) (any, error) {
		p := r.URL.Query().Get("path")
		if p == "" {
			home, _ := os.UserHomeDir()
			dirs := []M{{"name": "Home (" + home + ")", "path": home}}
			for letter := 'A'; letter <= 'Z'; letter++ {
				path := fmt.Sprintf("%c:\\", letter)
				if isDirectory(path) {
					dirs = append(dirs, M{"name": path, "path": path})
				}
			}
			return M{"path": "", "parent": nil, "dirs": dirs}, nil
		}
		p, err := filepath.Abs(p)
		if err != nil {
			return nil, err
		}
		items, err := os.ReadDir(p)
		if err != nil {
			return nil, err
		}
		dirs := []M{}
		for _, item := range items {
			if item.IsDir() && !strings.HasPrefix(item.Name(), "$") && item.Name() != "System Volume Information" {
				path := filepath.Join(p, item.Name())
				dirs = append(dirs, M{"name": item.Name(), "path": path, "git": hasGitFolder(path)})
			}
		}
		sort.Slice(dirs, func(i, j int) bool { return S(dirs[i], "name") < S(dirs[j], "name") })
		parent := filepath.Dir(p)
		if parent == p {
			parent = ""
		}
		return M{"path": p, "parent": parent, "dirs": dirs}, nil
	})
	route(mux, "POST /api/fs/mkdir", func(r *http.Request, b M) (any, error) {
		p, err := filepath.Abs(S(b, "parent"))
		if err != nil {
			return nil, err
		}
		name := strings.TrimSpace(S(b, "name"))
		if !isDirectory(p) || name == "" || name == "." || name == ".." || strings.ContainsAny(name, "\\/:*?\"<>|\x00") {
			return nil, errors.New("Invalid folder name or parent")
		}
		path := filepath.Join(p, name)
		if err = os.Mkdir(path, 0755); err != nil {
			return nil, err
		}
		return M{"path": path}, nil
	})
}
func isDirectory(p string) bool { s, e := os.Stat(p); return e == nil && s.IsDir() }
func (a *App) favorites() []string {
	raw, _ := os.ReadFile(filepath.Join(a.DataDir, "favorites.json"))
	rows := []string{}
	_ = json.Unmarshal(raw, &rows)
	return rows
}
func pcDefaults(dataDir string) M {
	m, _ := manageRead(filepath.Join(providerHome("claude"), "settings.json"))
	permissions := providerMap(m["permissions"])
	mode := S(permissions, "defaultMode")
	if mode == "" {
		mode = "default"
	}
	model := S(m, "model")
	cache, _ := manageRead(filepath.Join(dataDir, "models-claude.json"))
	for _, row := range maps(cache["list"]) {
		id := S(row, "id")
		if model == "" && (B(row, "isDefault") || id == "default") {
			description, _, _ := strings.Cut(S(row, "description"), "·")
			model = strings.TrimSpace(description)
			break
		}
		if model != "" && model == id && S(row, "label") != "" {
			model = S(row, "label")
			break
		}
	}
	claude := M{"model": nil, "effort": m["effortLevel"], "mode": mode}
	if model != "" {
		claude["model"] = model
	}
	config, _ := manageRead(filepath.Join(providerHome("codex"), "config.toml"))
	approval, sandbox := S(config, "approval_policy"), S(config, "sandbox_mode")
	if sandbox == "" {
		sandbox = "read-only"
	}
	codex := M{"model": config["model"], "effort": config["model_reasoning_effort"], "mode": nil}
	switch {
	case sandbox == "danger-full-access" && approval == "never":
		codex["mode"] = "full"
	case approval == "untrusted":
		codex["mode"] = "untrusted"
	case sandbox == "workspace-write":
		codex["mode"] = "auto"
	case sandbox == "read-only":
		codex["mode"] = "read-only"
	}
	return M{"claude": claude, "codex": codex, "antigravity": agyPCDefaults()}
}
