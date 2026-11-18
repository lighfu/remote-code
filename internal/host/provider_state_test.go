package host

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestProviderConcurrentSessionFields(t *testing.T) {
	r := &nativeSession{}
	var workers sync.WaitGroup
	for i := 0; i < 24; i++ {
		workers.Add(1)
		go func(i int) {
			defer workers.Done()
			for j := 0; j < 100; j++ {
				r.SetExternal(fmt.Sprint(i))
				r.SetTurn(fmt.Sprint(j))
				r.SetParent(fmt.Sprint(i), fmt.Sprint(j))
				r.AppendParent("all", "x")
				_ = r.External()
				_ = r.Turn()
				_ = r.Parent(fmt.Sprint(i))
			}
		}(i)
	}
	workers.Wait()
	if len(r.Parent("all")) != 2400 {
		t.Fatal("concurrent parent updates lost")
	}
}
func TestProviderClosedReplyCancelsPendingUI(t *testing.T) {
	store := providerTestStore(t)
	p := NewProvider("claude", store, t.TempDir()).(*nativeProvider)
	rec := store.Create(M{"provider": "claude"})
	wire := &providerWire{sendFunc: func(M) error { return errors.New("closed") }, claude: true}
	p.addPermission(S(rec.Info, "id"), wire, "request", "can_use_tool", M{}, "Bash", M{"command": "echo test"})
	requestID := S(store.Get(S(rec.Info, "id")).Entries[0], "requestId")
	if err := p.respond(rec, M{"requestId": requestID, "choice": "__cancel"}); err == nil {
		t.Fatal("expected failed wire response")
	}
	saved := store.Get(S(rec.Info, "id"))
	if N(saved.Info, "pending") != 0 || S(saved.Info, "status") != "idle" {
		t.Fatalf("pending state stuck %#v", saved.Info)
	}
	if len(saved.Entries) != 1 || S(saved.Entries[0], "status") != "cancelled" {
		t.Fatalf("entry not cancelled %#v", saved.Entries)
	}
}
func TestProviderRewindResolvesLegacyIDAndCurrentParentChain(t *testing.T) {
	home := t.TempDir()
	t.Setenv("CLAUDE_CONFIG_DIR", home)
	directory := filepath.Join(home, "projects", "project")
	if err := os.MkdirAll(directory, 0700); err != nil {
		t.Fatal(err)
	}
	session := "11111111-1111-1111-1111-111111111111"
	first := "22222222-2222-2222-2222-222222222222"
	answer := "33333333-3333-3333-3333-333333333333"
	second := "44444444-4444-4444-4444-444444444444"
	rows := []M{{"uuid": first, "parentUuid": nil, "type": "user", "message": M{"content": "repeat"}}, {"uuid": answer, "parentUuid": first, "type": "assistant", "message": M{"content": "answer"}}, {"uuid": "obsolete", "parentUuid": answer, "type": "user", "message": M{"content": "discarded branch"}}, {"uuid": second, "parentUuid": answer, "type": "user", "message": M{"content": "repeat"}}}
	var data strings.Builder
	for _, row := range rows {
		raw, _ := json.Marshal(row)
		data.Write(raw)
		data.WriteByte('\n')
	}
	if err := os.WriteFile(filepath.Join(directory, session+".jsonl"), []byte(data.String()), 0600); err != nil {
		t.Fatal(err)
	}
	store := providerTestStore(t)
	p := NewProvider("claude", store, t.TempDir()).(*nativeProvider)
	rec := store.Create(M{"provider": "claude", "externalId": session})
	id := S(rec.Info, "id")
	store.Add(id, M{"id": NewID(), "kind": "user", "text": "repeat"})
	store.Add(id, M{"id": second + ":0", "kind": "user", "text": "repeat"})
	rec = store.Get(id)
	prompt, err := p.resolveClaudeRewind(rec, 1)
	if err != nil || prompt.UUID != second || prompt.Parent != answer {
		t.Fatalf("wrong prompt %#v %v", prompt, err)
	}
	result, err := p.rewind(context.Background(), rec, M{"entryId": second + ":0", "files": false})
	if err != nil {
		t.Fatal(err)
	}
	if S(providerMap(result), "text") != "repeat" || S(store.Get(id).Info, "resumeAt") != answer {
		t.Fatal("rewind did not target parent UUID")
	}
	rec = store.Get(id)
	result, err = p.rewind(context.Background(), rec, M{"entryId": S(rec.Entries[0], "id")})
	if err != nil {
		t.Fatal(err)
	}
	if S(store.Get(id).Info, "externalId") != "" {
		t.Fatal("rewind before first prompt did not create a fresh session")
	}
}
func TestProviderAgyChildAndBackgroundLogs(t *testing.T) {
	store := providerTestStore(t)
	p := NewProvider("antigravity", store, t.TempDir()).(*nativeProvider)
	defer p.Close()
	external := "11111111-1111-1111-1111-111111111111"
	child := "22222222-2222-2222-2222-222222222222"
	rec := store.Create(M{"provider": "antigravity", "externalId": external})
	sid := S(rec.Info, "id")
	store.Add(sid, M{"id": "agent", "kind": "tool", "tool": "Agent", "title": "Tests", "status": "running"})
	store.Add(sid, M{"id": "bg", "kind": "tool", "tool": "command", "status": "done"})
	log := filepath.Join(t.TempDir(), "transcript.jsonl")
	raw, _ := json.Marshal(M{"step_index": 1, "type": "PLANNER_RESPONSE", "content": "Child answer", "tool_calls": []any{}})
	if err := os.WriteFile(log, append(raw, '\n'), 0600); err != nil {
		t.Fatal(err)
	}
	toURI := func(path string) string {
		slash := filepath.ToSlash(path)
		if !strings.HasPrefix(slash, "/") {
			slash = "/" + slash
		}
		return (&url.URL{Scheme: "file", Path: slash}).String()
	}
	created, _ := json.Marshal(M{"conversationId": child, "logAbsoluteUri": toURI(log)})
	p.agyObserveTool(sid, "agent", string(created))
	wait := func(fn func() bool) {
		t.Helper()
		until := time.Now().Add(3 * time.Second)
		for time.Now().Before(until) {
			if fn() {
				return
			}
			time.Sleep(20 * time.Millisecond)
		}
		t.Fatal("log state did not arrive")
	}
	wait(func() bool {
		for _, entry := range store.Get(sid).Entries {
			if S(entry, "parent") == "agent" && S(entry, "text") == "Child answer" {
				return true
			}
		}
		return false
	})
	taskLog := filepath.Join(t.TempDir(), "task.log")
	if err := os.WriteFile(taskLog, []byte("background output"), 0600); err != nil {
		t.Fatal(err)
	}
	task := external + "/task-1"
	p.agyObserveTool(sid, "bg", "running as a background task with task id: "+task+"\nTask logs are available at: "+toURI(taskLog))
	wait(func() bool {
		for _, entry := range store.Get(sid).Entries {
			if S(entry, "id") == "bg" {
				return S(entry, "status") == "running" && S(entry, "output") == "background output"
			}
		}
		return false
	})
	p.agyHub().report(sid, "<SYSTEM_MESSAGE>sender="+task+" content=exited with code 1\nCommand output:\nfailed</SYSTEM_MESSAGE>")
	p.agyHub().report(sid, "<SYSTEM_MESSAGE>sender="+child+" content=finished with result: done</SYSTEM_MESSAGE>")
	saved := store.Get(sid)
	for _, entry := range saved.Entries {
		if S(entry, "id") == "bg" && S(entry, "status") != "error" {
			t.Fatalf("background error lost %#v", entry)
		}
		if S(entry, "id") == "agent" && S(entry, "status") != "done" {
			t.Fatalf("agent completion lost %#v", entry)
		}
	}
}
