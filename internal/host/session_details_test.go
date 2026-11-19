package host

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"slices"
	"sync"
	"testing"
	"time"
)

func TestSessionDetailEventWaitsForDelayedUsageAppend(t *testing.T) {
	a := testApp(t)
	home := t.TempDir()
	t.Setenv("CODEX_HOME", home)
	external := NewID()
	dir := filepath.Join(home, "sessions")
	if err := os.MkdirAll(dir, 0700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "rollout-"+external+".jsonl")
	appendUsage := func(last, total int) {
		t.Helper()
		f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0600)
		if err != nil {
			t.Fatal(err)
		}
		defer f.Close()
		err = json.NewEncoder(f).Encode(M{"type": "event_msg", "payload": M{"type": "token_count", "info": M{
			"model_context_window": 1000, "last_token_usage": M{"input_tokens": last, "cached_input_tokens": last / 2},
			"total_token_usage": M{"input_tokens": total, "cached_input_tokens": total / 2},
		}}})
		if err != nil {
			t.Fatal(err)
		}
	}
	appendUsage(100, 100)
	rec := a.Store.Create(M{"provider": "codex", "externalId": external})
	id := S(rec.Info, "id")
	a.readSessionAnalytics(rec)
	if _, err := a.contextSnapshot(t.Context(), rec, false); err != nil {
		t.Fatal(err)
	}
	events, cancel := a.Store.Subscribe()
	defer cancel()
	a.Store.Update(id, M{"context": M{"used": 200, "max": 1000}}, false)
	// An intervening tool row can grow the file without persisting the usage yet.
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_APPEND, 0600)
	if err != nil {
		t.Fatal(err)
	}
	_, err = f.WriteString("{\"type\":\"response_item\",\"payload\":{\"type\":\"function_call_output\"}}\n")
	f.Close()
	if err != nil {
		t.Fatal(err)
	}
	response := testRequest(a, "GET", "/api/sessions/"+id+"/context", "")
	var current M
	if response.Code != 200 || json.Unmarshal(response.Body.Bytes(), &current) != nil || N(current, "totalTokens") != 200 || N(providerMap(a.Store.infoRecord(id).Info["context"]), "used") != 200 {
		t.Fatalf("live summary regressed before the provider flush: %s", response.Body)
	}
	timer := time.NewTimer(500 * time.Millisecond)
	defer timer.Stop()
	for {
		select {
		case ev := <-events:
			if S(ev, "type") == "session_details" {
				t.Fatal("signalled before the usage record reached disk", ev)
			}
		case <-timer.C:
			appendUsage(200, 300)
			goto persisted
		}
	}
persisted:
	timeout := time.After(3 * time.Second)
	for {
		select {
		case ev := <-events:
			if S(ev, "type") != "session_details" {
				continue
			}
			fields, _ := ev["fields"].([]any)
			if !slices.Contains(fields, "context") || !slices.Contains(fields, "cache") {
				t.Fatal(ev)
			}
			for _, route := range []string{"context?cached=1", "cache"} {
				resp := testRequest(a, "GET", "/api/sessions/"+id+"/"+route, "")
				var detail M
				if err := json.Unmarshal(resp.Body.Bytes(), &detail); err != nil || resp.Code != 200 {
					t.Fatalf("%s: %d %s", route, resp.Code, resp.Body)
				}
				if route == "cache" && N(providerMap(detail["totals"]), "read") != 150 || route != "cache" && N(detail, "totalTokens") != 200 {
					t.Fatalf("%s returned pre-event usage: %v", route, detail)
				}
			}
			return
		case <-timeout:
			t.Fatal("missing usage notification")
		}
	}
}

func TestSessionDetailInvalidationWithoutListenersSkipsTranscriptScan(t *testing.T) {
	a := testApp(t)
	home := t.TempDir()
	t.Setenv("CODEX_HOME", home)
	external := NewID()
	dir := filepath.Join(home, "sessions")
	if err := os.MkdirAll(dir, 0700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "rollout-"+external+".jsonl")
	if err := os.WriteFile(path, []byte("{}\n"), 0600); err != nil {
		t.Fatal(err)
	}
	rec := a.Store.Create(M{"provider": "codex", "externalId": external})
	id := S(rec.Info, "id")
	key := contextKey(rec.Info)
	a.contexts[id] = contextCacheValue{key: key, detail: M{"totalTokens": 10}}
	a.Store.Update(id, M{"context": M{"used": 30, "max": 100}}, false)
	deadline := time.Now().Add(2 * time.Second)
	for {
		a.contextMu.Lock()
		_, exists := a.contexts[id]
		a.contextMu.Unlock()
		if !exists {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("cached detail was not invalidated")
		}
		time.Sleep(20 * time.Millisecond)
	}
	if _, scanned := analyticsFiles.Load(path); scanned {
		t.Fatal("unobserved update scanned a transcript")
	}
}

func TestSessionDetailSignalsIgnoreToolDeltasAndReplayedCompletions(t *testing.T) {
	a := testApp(t)
	id := S(a.Store.Create(M{"provider": "codex"}).Info, "id")
	var signals []detailChange
	a.Store.detailsChanged = func(_ string, changed detailChange) { signals = append(signals, changed) }
	a.Store.Add(id, M{"id": "tool", "kind": "tool", "tool": "Bash", "status": "running"})
	a.Store.Delta(id, "tool", "output", "progress")
	a.Store.Patch(id, "tool", M{"status": "done"})
	a.Store.Patch(id, "tool", M{"status": "done", "output": "more output"})
	a.Store.Add(id, M{"id": "tool", "kind": "tool", "tool": "Bash", "status": "done"})
	a.Store.Add(id, M{"kind": "tool", "tool": "Read", "status": "done"})
	a.Store.Add(id, M{"kind": "system", "compaction": true})
	a.Store.Add(id, M{"kind": "result"})
	if !slices.Equal(signals, []detailChange{detailGit, detailUsage, detailGit | detailUsage}) {
		t.Fatal("unexpected invalidations", signals)
	}
}

func TestSessionContextInvalidationDetachesInFlightRead(t *testing.T) {
	a := testApp(t)
	rec := a.Store.Create(M{"provider": "claude"})
	key := contextKey(rec.Info)
	started, release, done := make(chan struct{}), make(chan struct{}), make(chan struct{})
	go func() {
		defer close(done)
		_, _ = a.loadContextSnapshot(t.Context(), rec, false, func(context.Context) (any, error) {
			close(started)
			<-release
			return M{"totalTokens": 30, "maxTokens": 100}, nil
		})
	}()
	<-started
	finish := sync.OnceFunc(func() { close(release); <-done })
	defer finish()
	a.invalidateSessionContext(key)
	_, err := a.loadContextSnapshot(t.Context(), rec, true, func(context.Context) (any, error) {
		return M{"totalTokens": 15, "maxTokens": 100}, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	finish()
	if N(a.contexts[key.id].detail, "totalTokens") != 15 || N(providerMap(a.Store.infoRecord(key.id).Info["context"]), "used") != 15 {
		t.Fatal("obsolete request replaced the new cache or broadcast stale context")
	}
}

func TestWorktreeInvalidationDetachesPendingParentSnapshot(t *testing.T) {
	c := newGitStatusCache(2)
	cwd := t.TempDir()
	key := fileKey(cwd)
	started, release, done := make(chan struct{}), make(chan struct{}), make(chan struct{})
	go func() {
		defer close(done)
		_, _ = c.read(key, false, func() (M, error) {
			close(started)
			<-release
			return M{"changedFiles": 0}, nil
		})
	}()
	<-started
	finish := sync.OnceFunc(func() { close(release); <-done })
	defer finish()
	c.invalidateWorktree(filepath.Join(cwd, "nested"))
	if c.lookup(key, true) != nil {
		t.Fatal("a new reader would join the obsolete scan")
	}
	_, _ = c.read(key, false, func() (M, error) { return M{"changedFiles": 2}, nil })
	finish()
	snapshot := c.lookup(key, false)
	if snapshot == nil || N(snapshot.status, "changedFiles") != 2 {
		t.Fatal("pre-edit result replaced the new worktree cache")
	}
}

func TestClaudeLiveSummaryUsesOnlyRootRequestUsage(t *testing.T) {
	a := testApp(t)
	rec := a.Store.Create(M{"provider": "claude"})
	id := S(rec.Info, "id")
	p := NewProvider("claude", a.Store, a.DataDir).(*nativeProvider)
	runtime := &nativeSession{}
	for _, parent := range []string{"", "subagent"} {
		p.claudeStats(id, runtime, M{"type": "assistant", "parent_tool_use_id": parent, "message": M{
			"id": "msg" + parent, "model": "active-model", "usage": M{"input_tokens": 40, "cache_read_input_tokens": 50, "output_tokens": 5 + len(parent)},
		}})
	}
	p.claudeStats(id, runtime, M{"type": "result", "usage": M{"input_tokens": 50000, "output_tokens": 500}, "modelUsage": M{"active-model": M{"contextWindow": 1000}}})
	summary := providerMap(a.Store.infoRecord(id).Info["context"])
	if N(summary, "used") != 95 || N(summary, "max") != 1000 {
		t.Fatalf("session summary included aggregate or subagent tokens: %v", summary)
	}
}
