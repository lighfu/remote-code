package host

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func BenchmarkBtwGetLongConversation(b *testing.B) {
	for _, count := range []int{10, 10000} {
		b.Run(fmt.Sprint(count), func(b *testing.B) {
			entries := make([]M, count)
			for i := range entries {
				entries[i] = M{"kind": "tool", "id": fmt.Sprint(i), "text": strings.Repeat("x", 3000), "input": M{"path": "example.txt"}}
			}
			a := &App{Store: &Store{records: map[string]*Record{"s": {Info: M{"id": "s", "provider": "codex"}, Entries: entries}}}, btw: map[string][]M{"s": {}}}
			mux := http.NewServeMux()
			a.registerBtw(mux)
			r := httptest.NewRequest("GET", "/api/sessions/s/btw", nil)
			b.ReportAllocs()
			b.ResetTimer()
			for range b.N {
				result := httptest.NewRecorder()
				mux.ServeHTTP(result, r)
				if result.Code != 200 {
					b.Fatal(result.Code)
				}
			}
		})
	}
}

type btwCopyCounter int

func (n *btwCopyCounter) MarshalJSON() ([]byte, error) {
	*n++
	return []byte("0"), nil
}

func TestBtwRoutesSkipLargeMainTimeline(t *testing.T) {
	var copies btwCopyCounter
	entries := make([]M, 10000)
	for i := range entries {
		entries[i] = M{"kind": "tool", "output": "existing tool result"}
	}
	entries[len(entries)-1]["probe"] = &copies
	a := &App{DataDir: t.TempDir(), Store: &Store{records: map[string]*Record{"s": {Info: M{"id": "s", "provider": "codex"}, Entries: entries}}}, btw: map[string][]M{"s": {}}}
	mux := http.NewServeMux()
	a.registerBtw(mux)
	for _, method := range []string{"GET", "POST", "DELETE"} {
		request := httptest.NewRequest(method, "/api/sessions/s/btw", nil)
		mux.ServeHTTP(httptest.NewRecorder(), request)
		if copies != 0 {
			t.Fatalf("%s cloned the main timeline", method)
		}
	}
}

type btwFixtureProvider struct {
	fakeProvider
	started  chan M
	answer   chan string
	canceled chan struct{}
}

func (p *btwFixtureProvider) Do(ctx context.Context, action string, rec *Record, body M) (any, error) {
	if action != "sideQuestion" {
		return p.fakeProvider.Do(ctx, action, rec, body)
	}
	if progress, ok := ctx.Value(btwProgressKey{}).(func(string)); ok {
		progress("First words")
	}
	p.started <- body
	select {
	case answer := <-p.answer:
		return M{"response": answer}, nil
	case <-ctx.Done():
		p.canceled <- struct{}{}
		return nil, ctx.Err()
	}
}

func TestBtwRestoresHistoryAndPersistsAnswersAndCancellation(t *testing.T) {
	a := testApp(t)
	p := &btwFixtureProvider{started: make(chan M, 2), answer: make(chan string, 1), canceled: make(chan struct{}, 2)}
	a.Providers["codex"] = p
	sid := S(a.Store.Create(M{"provider": "codex"}).Info, "id")
	url := "/api/sessions/" + sid + "/btw"
	var legacy []M
	for i := range 15 {
		legacy = append(legacy, M{"id": fmt.Sprintf("old-%d", i), "question": fmt.Sprintf("Q%d", i), "status": "done", "response": "Saved"})
	}
	legacy = append(legacy, M{"id": "synthetic", "status": "done", "response": "Temporary", "synthetic": true}, M{"id": "stale", "status": "pending"})
	if err := atomicJSON(a.btwFile(sid), legacy); err != nil {
		t.Fatal(err)
	}
	assertRows := func(app *App, count int, lastStatus string) {
		t.Helper()
		res := testRequest(app, http.MethodGet, url, "")
		var rows []M
		if err := json.Unmarshal(res.Body.Bytes(), &rows); err != nil || res.Code != http.StatusOK || len(rows) != count || S(rows[len(rows)-1], "status") != lastStatus {
			t.Fatalf("Unexpected quick questions: %d %s (%v)", res.Code, res.Body, err)
		}
	}
	assertRows(a, 17, "error")
	events, unsubscribe := a.Store.Subscribe()
	defer unsubscribe()
	first := testRequest(a, http.MethodPost, url, `{"question":"How?"}`)
	if first.Code != http.StatusOK {
		t.Fatal(first.Body)
	}
	select {
	case request := <-p.started:
		history := providerRows(request["history"])
		if len(history) != 12 || S(history[0], "question") != "Q3" || S(history[11], "question") != "Q14" {
			t.Fatalf("unexpected provider history: %v", history)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("provider did not start")
	}
	res := testRequest(a, http.MethodGet, url, "")
	var rows []M
	if err := json.Unmarshal(res.Body.Bytes(), &rows); err != nil || len(rows) == 0 {
		t.Fatal("could not read the in-progress reply", err)
	}
	partial := rows[len(rows)-1]
	if S(partial, "status") != "pending" || S(partial, "response") != "First words" || N(partial, "responseRevision") != 1 {
		t.Fatal("partial reply was not available before completion")
	}
	if duplicate := testRequest(a, http.MethodPost, url, `{"question":"Another?"}`); duplicate.Code == http.StatusOK {
		t.Fatal("a second question ran while the first was pending")
	}
	p.answer <- "Done"
	timeout := time.After(3 * time.Second)
	for finished := false; !finished; {
		select {
		case event := <-events:
			finished = S(providerMap(event["item"]), "response") == "Done"
		case <-timeout:
			t.Fatal("the answer was not published")
		}
	}
	assertRows(a, 18, "done")

	second := testRequest(a, http.MethodPost, url, `{"question":"Cancel it"}`)
	var item M
	if err := json.Unmarshal(second.Body.Bytes(), &item); err != nil || second.Code != http.StatusOK || S(item, "id") == "" {
		t.Fatalf("could not start the cancel test: %d %s", second.Code, second.Body)
	}
	select {
	case <-p.started:
	case <-time.After(3 * time.Second):
		t.Fatal("second question did not start")
	}
	if removed := testRequest(a, http.MethodDelete, url+"/"+S(item, "id"), ""); removed.Code != http.StatusOK {
		t.Fatal(removed.Body)
	}
	select {
	case <-p.canceled:
	case <-time.After(3 * time.Second):
		t.Fatal("removing the quick question did not cancel provider work")
	}
	if err := a.Close(); err != nil {
		t.Fatal(err)
	}
	reopened, err := NewApp(a.DataDir)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	assertRows(reopened, 18, "done")
}
