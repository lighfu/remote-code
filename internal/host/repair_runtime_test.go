package host

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestContextRefreshWaiterReceivesProviderFailure(t *testing.T) {
	rec := &Record{Info: M{"id": "session", "provider": "claude"}}
	key := contextKey(rec.Info)
	cause := errors.New("context request timed out")
	flight := &contextFlight{done: make(chan struct{}), err: cause}
	close(flight.done)
	a := &App{
		contexts:       map[string]contextCacheValue{key.id: {key: key, at: time.Now().Add(-time.Hour), detail: M{"totalTokens": 1}}},
		contextFlights: map[contextCacheKey]*contextFlight{key: flight},
	}
	value, err := a.loadContextSnapshot(t.Context(), rec, false, func(context.Context) (any, error) {
		t.Fatal("duplicate provider request")
		return nil, nil
	})
	if value != nil || !errors.Is(err, cause) {
		t.Fatalf("refresh returned stale context: %v, %v", value, err)
	}
}

func TestDisconnectionFinalizesTimeline(t *testing.T) {
	for _, name := range []string{"codex", "claude"} {
		t.Run(name, func(t *testing.T) {
			a := testApp(t)
			rec := a.Store.Create(M{"provider": name, "externalId": "external", "live": true, "status": "waiting", "pending": 1})
			sid := S(rec.Info, "id")
			p := NewProvider(name, a.Store, a.DataDir).(*nativeProvider)
			p.sessions[sid] = &nativeSession{external: "external"}
			p.pending["request"] = nativePending{sid: sid, entry: "approval"}
			a.Store.Add(sid, M{"id": "reply", "kind": "assistant", "text": "partial reply", "streaming": true})
			a.Store.Add(sid, M{"id": "tool", "kind": "tool", "status": "running"})
			a.Store.Add(sid, M{"id": "approval", "kind": "approval", "status": "pending"})
			if name == "codex" {
				p.rpcExit(M{"_exit": "test connection closed"})
			} else if err := p.detach(t.Context(), rec); err != nil {
				t.Fatal(err)
			}
			after := a.Store.Get(sid)
			if B(after.Info, "live") || N(after.Info, "pending") != 0 || S(after.Info, "status") != "idle" || len(p.pending) != 0 {
				t.Fatal(after.Info)
			}
			entries := after.Entries
			if B(entries[0], "streaming") || S(entries[0], "text") != "partial reply" || S(entries[1], "status") != "error" || S(entries[2], "status") != "cancelled" {
				t.Fatal(entries)
			}
			if name == "codex" && (len(entries) != 4 || !strings.Contains(S(entries[3], "text"), "test connection closed")) {
				t.Fatal("missing interruption reason", entries)
			}
		})
	}
}

func TestContextAfterClearIgnoresAnInFlightReadOfOldConversation(t *testing.T) {
	a := testApp(t)
	rec := a.Store.Create(M{"provider": "claude", "externalId": "old"})
	sid := S(rec.Info, "id")
	started := make(chan struct{})
	release := make(chan struct{})
	finished := make(chan error, 1)
	go func() {
		_, err := a.loadContextSnapshot(t.Context(), rec, false, func(ctx context.Context) (any, error) {
			close(started)
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-release:
				return M{"totalTokens": 800, "maxTokens": 1000}, nil
			}
		})
		finished <- err
	}()
	<-started
	a.Store.Update(sid, M{"externalId": "new", "context": nil}, false)
	current := a.Store.Get(sid)
	ctx, cancel := context.WithTimeout(t.Context(), time.Second)
	defer cancel()
	got, err := a.loadContextSnapshot(ctx, current, false, func(context.Context) (any, error) {
		return M{"totalTokens": 70, "maxTokens": 1000}, nil
	})
	close(release)
	if err != nil || N(got, "totalTokens") != 70 {
		t.Fatalf("new context = %v, err=%v", got, err)
	}
	if err := <-finished; err == nil {
		t.Fatal("old read should report the change")
	}
	after := a.Store.Get(sid)
	if N(providerMap(after.Info["context"]), "used") != 70 {
		t.Fatal(after.Info)
	}
}

func TestClaudeCacheKeepsPreviousTTLAndDeduplicatesRequests(t *testing.T) {
	var series analyticsSeries
	start := time.Unix(1000, 0)
	series.add("claude", "one", analyticsUsage{At: start.UnixMilli(), Write1: 1000}, M{})
	second := analyticsUsage{At: start.Add(6 * time.Minute).UnixMilli(), Read: 300, Fresh: 700}
	series.add("claude", "two", second, M{})
	second.Output = 20
	series.add("claude", "two", second, M{})
	_, detail := series.result(0)
	if ttl := N(providerMap(detail["summary"]), "ttlMs"); ttl != 3600000 || N(detail, "expiredCount") != 0 || len(providerRows(detail["recent"])) != 2 ||
		N(providerMap(detail["totals"]), "requests") != 2 {
		t.Fatal(detail)
	}
}

func TestFXReadsValidQuoteAndKeepsFallback(t *testing.T) {
	api := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/valid" {
			_, _ = w.Write([]byte(`{"rates":{"JPY":152.25}}`))
		} else {
			http.Error(w, "Unavailable", http.StatusServiceUnavailable)
		}
	}))
	defer api.Close()
	rate, err := fetchUSDJPY(t.Context(), api.URL+"/valid")
	if err != nil || rate != 152.25 {
		t.Fatalf("rate = %v, error = %v", rate, err)
	}
	if _, err = fetchUSDJPY(t.Context(), api.URL+"/unavailable"); err == nil {
		t.Fatal("invalid quote was accepted")
	}
	var rates fxRates
	dir := t.TempDir()
	if rates.usdJpy(150) != 150 {
		t.Fatal("configured fallback was lost")
	}
	if err = atomicJSON(filepath.Join(dir, "fx-usdjpy.json"), fxQuote{Rate: rate, At: time.Now()}); err != nil {
		t.Fatal(err)
	}
	rates.restore(dir)
	if rates.usdJpy(150) != 152.25 {
		t.Fatal("last quote did not survive startup")
	}
}
