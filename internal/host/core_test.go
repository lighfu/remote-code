package host

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

type fakeProvider struct {
	mu           sync.Mutex
	calls        []string
	historyError error
}

func (p *fakeProvider) Available() bool { return true }
func (p *fakeProvider) Close() error    { return nil }
func (p *fakeProvider) Do(ctx context.Context, action string, rec *Record, b M) (any, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.calls = append(p.calls, action)
	if action == "modes" {
		return []M{{"id": "default", "label": "Default", "description": ""}}, nil
	}
	if action == "listModels" {
		return []M{}, nil
	}
	if action == "listHistory" {
		return []M{{"externalId": "external", "title": "Example", "cwd": "C:/example", "updatedAt": 1, "preview": ""}}, nil
	}
	if action == "rejoin" || action == "importHistory" {
		return nil, p.historyError
	}
	if action == "usage" {
		return M{"windows": []M{}, "at": nowMS()}, nil
	}
	return nil, nil
}
func testApp(t *testing.T) *App {
	t.Helper()
	a, err := NewApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	for name := range a.Providers {
		a.Providers[name] = &fakeProvider{}
	}
	t.Cleanup(func() {
		if err := a.Close(); err != nil {
			t.Error(err)
		}
	})
	return a
}
func testRequest(a *App, method, path, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	r.RemoteAddr = "127.0.0.1:12345"
	r.Header.Set("Authorization", "Bearer "+a.Config.Token)
	w := httptest.NewRecorder()
	a.Handler().ServeHTTP(w, r)
	return w
}
func TestConfigPreservesUnknownSettings(t *testing.T) {
	dir := t.TempDir()
	raw := `{"port":8787,"token":"keep-token","codexPrices":{"custom":{"input":42}},"future":{"enabled":true}}`
	if err := os.WriteFile(filepath.Join(dir, "config.json"), []byte(raw), 0600); err != nil {
		t.Fatal(err)
	}
	c, err := LoadConfig(dir)
	if err != nil {
		t.Fatal(err)
	}
	if err = SaveConfig(dir, c); err != nil {
		t.Fatal(err)
	}
	saved, _ := os.ReadFile(filepath.Join(dir, "config.json"))
	var m M
	_ = json.Unmarshal(saved, &m)
	if S(m, "token") != "keep-token" || m["future"] == nil || m["codexPrices"] == nil {
		t.Fatalf("lost config: %s", saved)
	}
}
func TestStoreLoadsExistingRecordAndResetsUnansweredRequests(t *testing.T) {
	dir := t.TempDir()
	id := NewID()
	r := Record{Info: M{"id": id, "provider": "claude", "status": "waiting", "live": true, "externalId": "keep", "custom": M{"v": 1}}, Entries: []M{{"id": "e", "kind": "approval", "status": "pending"}, {"id": "t", "kind": "tool", "status": "running", "streaming": true}}}
	if err := atomicJSON(filepath.Join(dir, id+".json"), r); err != nil {
		t.Fatal(err)
	}
	s, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	got := s.Get(id)
	if B(got.Info, "live") || S(got.Info, "externalId") != "keep" || S(got.Info, "status") != "idle" || S(got.Entries[0], "status") != "cancelled" || S(got.Entries[1], "status") != "error" {
		t.Fatalf("bad recovery: %+v", got)
	}
	got.Info["externalId"] = "changed"
	if S(s.Get(id).Info, "externalId") != "keep" {
		t.Fatal("snapshot mutated store")
	}
}

type blockedRejoinProvider struct {
	fakeProvider
	started chan struct{}
	release chan struct{}
}

func (p *blockedRejoinProvider) Do(ctx context.Context, action string, rec *Record, body M) (any, error) {
	if action != "rejoin" {
		return p.fakeProvider.Do(ctx, action, rec, body)
	}
	close(p.started)
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-p.release:
		return nil, errors.New("Codex Desktop did not reconnect")
	}
}

func TestDesktopWorkSurvivesServerStartupWhileRejoining(t *testing.T) {
	dir := t.TempDir()
	const id = "desktop"
	if err := os.Mkdir(filepath.Join(dir, "sessions"), 0700); err != nil {
		t.Fatal(err)
	}
	saved := Record{
		Info:    M{"id": id, "externalId": "0bcde000-1234-4567-89ab-0123456789ab", "provider": "codex", "desktop": true, "status": "running", "live": true},
		Entries: []M{{"id": "tool", "kind": "tool", "status": "running", "streaming": true}},
	}
	if err := atomicJSON(filepath.Join(dir, "sessions", id+".json"), saved); err != nil {
		t.Fatal(err)
	}
	a, err := NewApp(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	p := &blockedRejoinProvider{started: make(chan struct{}), release: make(chan struct{})}
	a.Providers["codex"] = p
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	events, _, unsubscribe := a.Store.SubscribeSnapshot()
	defer unsubscribe()
	a.StartBackground(ctx)
	select {
	case <-p.started:
	case <-time.After(2 * time.Second):
		t.Fatal("Desktop rejoin did not start")
	}
	w := testRequest(a, "GET", "/api/sessions/"+id, "")
	var snapshot struct {
		Session M
		Entries []M
	}
	if err := json.Unmarshal(w.Body.Bytes(), &snapshot); err != nil {
		t.Fatal(err)
	}
	if w.Code != http.StatusOK || S(snapshot.Session, "status") != "running" || B(snapshot.Session, "live") || len(snapshot.Entries) != 1 || S(snapshot.Entries[0], "status") != "running" {
		t.Fatalf("reconnecting Desktop work was marked finished: %s", w.Body)
	}
	close(p.release)
	timeout := time.NewTimer(2 * time.Second)
	defer timeout.Stop()
	for {
		select {
		case event := <-events:
			if S(event, "type") == "session" && S(providerMap(event["session"]), "status") == "idle" {
				if S(a.Store.Get(id).Entries[0], "status") != "error" {
					t.Fatal("unconfirmed tool stayed active after the failed rejoin")
				}
				return
			}
		case <-timeout.C:
			t.Fatal("failed rejoin did not settle provisional status")
		}
	}
}

func TestSessionContextUsesIntegerPercentageOnTheWire(t *testing.T) {
	dir := t.TempDir()
	if err := os.Mkdir(filepath.Join(dir, "sessions"), 0700); err != nil {
		t.Fatal(err)
	}
	saved := Record{
		Info:    M{"id": "saved", "provider": "codex", "title": "Saved", "cwd": dir, "context": M{"used": 999, "max": 4000, "percent": 24.975}},
		Entries: []M{{"id": "message", "kind": "user", "text": "Hello"}},
	}
	if err := atomicJSON(filepath.Join(dir, "sessions", "saved.json"), saved); err != nil {
		t.Fatal(err)
	}
	a, err := NewApp(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()

	// Android decodes context.percent as an integer in lists, events, and details.
	type wireSession struct {
		ID      string
		Context struct {
			Percent int
		}
	}
	w := testRequest(a, "GET", "/api/sessions", "")
	var list []wireSession
	if err = json.Unmarshal(w.Body.Bytes(), &list); err != nil || len(list) != 1 || list[0].Context.Percent != 25 {
		t.Fatalf("saved session list did not decode: %v (%s)", err, w.Body)
	}
	w = testRequest(a, "GET", "/api/sessions/saved?limit=600", "")
	var detail struct {
		Session wireSession
		Entries []M
	}
	if err = json.Unmarshal(w.Body.Bytes(), &detail); err != nil || detail.Session.Context.Percent != 25 || len(detail.Entries) != 1 {
		t.Fatalf("saved session detail did not decode: %v (%s)", err, w.Body)
	}

	rec := a.Store.Create(M{"provider": "codex"})
	id := S(rec.Info, "id")
	p := a.Providers["codex"].(*nativeProvider)
	p.byExternal["thread"] = id
	events, unsubscribe := a.Store.Subscribe()
	defer unsubscribe()
	p.codexMessage(M{"method": "thread/tokenUsage/updated", "params": M{
		"threadId": "thread", "tokenUsage": M{"modelContextWindow": 4000, "last": M{"inputTokens": 999}},
	}})
	select {
	case event := <-events:
		raw, _ := json.Marshal(event["session"])
		var session wireSession
		if err = json.Unmarshal(raw, &session); err != nil || session.ID != id || session.Context.Percent != 25 {
			t.Fatalf("live session update did not decode: %v (%s)", err, raw)
		}
	default:
		t.Fatal("missing live session update")
	}
}

func TestStoreFlushRemoveAndEvents(t *testing.T) {
	s, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	events, done := s.Subscribe()
	defer done()
	r := s.Create(M{"provider": "codex", "cwd": t.TempDir()})
	id := S(r.Info, "id")
	e := s.Add(id, M{"kind": "assistant", "text": "A", "streaming": true})
	s.Delta(id, S(e, "id"), "text", "B")
	s.Patch(id, S(e, "id"), M{"streaming": false})
	if err = s.Flush(); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(filepath.Join(s.directory, id+".json"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(raw), "AB") {
		t.Fatal(string(raw))
	}
	if len(events) < 4 {
		t.Fatal("missing events")
	}
	if err = s.Remove(id); err != nil {
		t.Fatal(err)
	}
	if err = s.Flush(); err != nil {
		t.Fatal(err)
	}
	if _, err = os.Stat(filepath.Join(s.directory, id+".json")); !os.IsNotExist(err) {
		t.Fatal("deleted record resurrected")
	}
}
func TestSelectEntriesRetainsAgentsAncestorsAndCycles(t *testing.T) {
	entries := []M{{"id": "agent", "kind": "tool", "tool": "Agent", "parent": "cycle"}, {"id": "cycle", "parent": "agent"}, {"id": "old"}, {"id": "child", "parent": "agent"}}
	selected := selectEntries(entries, 1)
	if len(selected) != 3 {
		t.Fatalf("%+v", selected)
	}
}

func TestSessionDetailKeepsConversationWhenActivityExceedsLimit(t *testing.T) {
	a := testApp(t)
	id := S(a.Store.Create(M{"provider": "codex"}).Info, "id")
	a.Store.ReplaceEntries(id, []M{
		{"id": "prompt", "kind": "user", "text": "Prompt"},
		{"id": "agent", "kind": "tool", "tool": "Agent"},
		{"id": "reply", "kind": "assistant", "text": "Reply", "parent": "agent"},
		{"id": "old-tool", "kind": "tool", "tool": "command"},
		{"id": "current-tool", "kind": "tool", "tool": "command"},
		{"id": "review", "kind": "tool", "tool": "automaticApprovalReview"},
		{"id": "blank", "kind": "thinking", "text": "  "},
	})
	response := testRequest(a, "GET", "/api/sessions/"+id+"?limit=2", "")
	if response.Code != http.StatusOK {
		t.Fatal(response.Code)
	}
	var detail struct{ Entries []M }
	if err := json.Unmarshal(response.Body.Bytes(), &detail); err != nil {
		t.Fatal(err)
	}
	want := []string{"prompt", "agent", "reply", "current-tool", "review"}
	if len(detail.Entries) != len(want) {
		t.Fatalf("timeline entries = %v", detail.Entries)
	}
	for i, id := range want {
		if S(detail.Entries[i], "id") != id {
			t.Fatalf("entry %d = %v, want %s", i, detail.Entries[i], id)
		}
	}
}

func TestCacheDetailUsesSavedSummaryWhenRolloutHasNoUsage(t *testing.T) {
	home := t.TempDir()
	t.Setenv("CODEX_HOME", home)
	if err := os.MkdirAll(filepath.Join(home, "sessions"), 0700); err != nil {
		t.Fatal(err)
	}
	externalID := NewID()
	path := filepath.Join(home, "sessions", "rollout-"+externalID+".jsonl")
	if err := os.WriteFile(path, []byte("{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":null}}\n"), 0600); err != nil {
		t.Fatal(err)
	}
	a := testApp(t)
	a.Providers["codex"] = NewProvider("codex", a.Store, a.DataDir)
	const lastAt = 1767268800000
	rec := a.Store.Create(M{"provider": "codex", "externalId": externalID, "stats": M{
		"cache":  M{"lastAt": lastAt, "ttlMs": 300000, "lastHit": 0.9},
		"tokens": M{"input": 8000, "cached": 7000, "output": 50},
	}})
	w := testRequest(a, "GET", "/api/sessions/"+S(rec.Info, "id")+"/cache", "")
	if w.Code != http.StatusOK {
		t.Fatal(w.Code)
	}
	var detail struct {
		Summary *struct {
			LastAt, TTLms int64
			LastHit       float64
		}
		Totals struct {
			Read, Uncached int64
			HitRate        float64
		}
		Recent []M
	}
	if err := json.Unmarshal(w.Body.Bytes(), &detail); err != nil {
		t.Fatal(err)
	}
	if detail.Summary == nil || detail.Summary.LastAt != lastAt || detail.Summary.TTLms != 300000 || detail.Summary.LastHit != 0.9 ||
		detail.Totals.Read != 7000 || detail.Totals.Uncached != 1000 || detail.Totals.HitRate != 0.875 || detail.Recent == nil || len(detail.Recent) != 0 {
		t.Fatalf("cache detail lost measured data: %s", w.Body)
	}
}

func TestHTTPAuthenticationAndSessionContract(t *testing.T) {
	a := testApp(t)
	h := a.Handler()
	r := httptest.NewRequest("GET", "/api/sessions", nil)
	r.RemoteAddr = "127.0.0.1:12345"
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 401 {
		t.Fatal(w.Code)
	}
	r = httptest.NewRequest("GET", "/api/sessions", nil)
	r.RemoteAddr = "192.168.1.2:1"
	r.Header.Set("Authorization", "Bearer "+a.Config.Token)
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 403 {
		t.Fatal(w.Code)
	}
	body, _ := json.Marshal(M{"provider": "claude", "cwd": t.TempDir(), "text": "test message"})
	w = testRequest(a, "POST", "/api/sessions", string(body))
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	var info M
	if err := json.Unmarshal(w.Body.Bytes(), &info); err != nil {
		t.Fatal(err)
	}
	if S(info, "id") == "" || S(info, "provider") != "claude" {
		t.Fatal(info)
	}
	id := S(info, "id")
	w = testRequest(a, "GET", "/api/sessions/"+id+"?limit=1", "")
	var session M
	_ = json.Unmarshal(w.Body.Bytes(), &session)
	if session["entries"] == nil || session["session"] == nil || session["total"] == nil {
		t.Fatal(session)
	}
	w = testRequest(a, "PATCH", "/api/sessions/"+id, `{"title":"renamed"}`)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "renamed") {
		t.Fatal(w.Body.String())
	}
}

func TestHTTPHistoryOpenReturnsSessionWhenProviderIsUnavailable(t *testing.T) {
	a := testApp(t)
	p := a.Providers["codex"].(*fakeProvider)
	p.historyError = errors.New("provider offline")
	rec := a.Store.Create(M{"provider": "codex", "externalId": "saved"})
	id := S(rec.Info, "id")
	a.Store.Add(id, M{"kind": "assistant", "text": "Saved content"})

	w := testRequest(a, "POST", "/api/history/codex/import", `{"externalId":"saved"}`)
	var info M
	if err := json.Unmarshal(w.Body.Bytes(), &info); err != nil || w.Code != http.StatusOK || S(info, "id") != id {
		t.Fatalf("existing session could not be reopened: %v (%s)", err, w.Body)
	}
	if entries := a.Store.Get(id).Entries; len(entries) != 1 || S(entries[0], "text") != "Saved content" {
		t.Fatalf("existing conversation changed: %v", entries)
	}

	w = testRequest(a, "POST", "/api/history/codex/import", `{"externalId":"new"}`)
	if err := json.Unmarshal(w.Body.Bytes(), &info); err != nil || w.Code != http.StatusOK || S(info, "externalId") != "new" || S(info, "id") == id {
		t.Fatalf("new session could not display its import result: %v (%s)", err, w.Body)
	}
	if entries := a.Store.Get(S(info, "id")).Entries; len(entries) != 1 || S(entries[0], "level") != "error" || !strings.Contains(S(entries[0], "text"), "provider offline") {
		t.Fatalf("new session does not contain the import error: %v", entries)
	}
}

func TestBodyRejectsTrailingJSON(t *testing.T) {
	w := httptest.NewRecorder()
	r := httptest.NewRequest("POST", "/", strings.NewReader(`{"text":"ok"} {"extra":true}`))
	if _, err := readJSON(w, r); err == nil {
		t.Fatal("accepted trailing JSON")
	}
}
func TestEventWebSocketHelloAndChanges(t *testing.T) {
	t.Parallel()
	a := testApp(t)
	server := httptest.NewServer(a.Handler())
	defer server.Close()
	header := http.Header{"Authorization": []string{"Bearer " + a.Config.Token}}
	ws, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(server.URL, "http")+"/api/events", header)
	if err != nil {
		t.Fatal(err)
	}
	defer ws.Close()
	_ = ws.SetReadDeadline(time.Now().Add(time.Second * 3))
	var event M
	if err = ws.ReadJSON(&event); err != nil {
		t.Fatal(err)
	}
	if S(event, "type") != "hello" {
		t.Fatal(event)
	}
	r := a.Store.Create(M{"provider": "codex"})
	if err = ws.ReadJSON(&event); err != nil {
		t.Fatal(err)
	}
	if S(event, "type") != "session" {
		t.Fatal(event)
	}
	// The connection may be idle longer than a single write's deadline.
	time.Sleep(eventWriteTimeout + 500*time.Millisecond)
	_ = ws.SetReadDeadline(time.Now().Add(3 * time.Second))
	a.Store.Add(S(r.Info, "id"), M{"kind": "user", "text": "hi"})
	if err = ws.ReadJSON(&event); err != nil {
		t.Fatal(err)
	}
	if S(event, "type") != "entry" {
		t.Fatal(event)
	}
}
func TestHTTPGzipResponsesDecode(t *testing.T) {
	a := testApp(t)
	server := httptest.NewServer(a.Handler())
	defer server.Close()
	r, _ := http.NewRequest("GET", server.URL+"/api/meta", nil)
	r.Header.Set("Authorization", "Bearer "+a.Config.Token)
	resp, err := http.DefaultClient.Do(r)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	if !json.Valid(raw) {
		t.Fatal(string(raw))
	}
}
