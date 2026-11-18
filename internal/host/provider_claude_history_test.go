package host

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestClaudeHistoryRefreshKeepsUnchangedRowsAndReloadsChangedTranscripts(t *testing.T) {
	root := t.TempDir()
	t.Setenv("CLAUDE_CONFIG_DIR", root)
	store := providerTestStore(t)
	p := NewProvider("claude", store, t.TempDir()).(*nativeProvider)
	p.claudePC.agents = map[string]claudePCAgent{}
	p.claudePC.checked = time.Now()
	read := func() []M {
		t.Helper()
		rows, err := p.listHistory(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		return rows
	}
	if rows := read(); len(rows) != 0 {
		t.Fatal(rows)
	}
	project := filepath.Join(root, "projects", "example")
	if err := os.MkdirAll(project, 0700); err != nil {
		t.Fatal(err)
	}
	first := filepath.Join(project, "first.jsonl")
	second := filepath.Join(project, "second.jsonl")
	start := time.Unix(1700000000, 0)
	write := func(path, content string, at time.Time) {
		t.Helper()
		if err := os.WriteFile(path, []byte(content), 0600); err != nil {
			t.Fatal(err)
		}
		if err := os.Chtimes(path, at, at); err != nil {
			t.Fatal(err)
		}
	}
	const preview = `{"type":"user","isMeta":true,"message":{"content":"hidden"}}
{"type":"user","cwd":"/project","message":{"content":[{"type":"text","text":"first message"}]}}
`
	write(first, preview+"{\"customTitle\":\"One\"}\n", start)
	write(second, "{\"summary\":\"Second\"}\n", start.Add(time.Minute))
	rec := store.Create(M{"provider": "claude", "externalId": "first", "status": "running", "live": true})
	rows := read()
	if len(rows) != 2 || S(rows[0], "title") != "Second" || S(rows[1], "title") != "One" || S(rows[1], "preview") != "first message" || S(rows[1], "cwd") != "/project" || !B(rows[1], "running") || S(rows[1], "sessionId") != S(rec.Info, "id") {
		t.Fatalf("wrong initial history: %v", rows)
	}
	rows[1]["title"] = "caller edit"
	store.Update(S(rec.Info, "id"), M{"status": "idle", "live": false}, false)
	rows = read()
	if S(rows[1], "title") != "One" || B(rows[1], "running") || B(rows[1], "live") {
		t.Fatalf("stale activity or mutated cached title: %v", rows[1])
	}

	// Same byte count, newer modification time.
	write(first, preview+"{\"customTitle\":\"Two\"}\n", start.Add(2*time.Minute))
	p.historyUntil = time.Time{}
	rows = read()
	if len(rows) != 2 || S(rows[0], "title") != "Two" || S(rows[1], "title") != "Second" {
		t.Fatalf("changed title and order: %v", rows)
	}

	// Same modification time, different byte count, and a removed transcript.
	write(first, preview+"{\"customTitle\":\"Three\"}\n", start.Add(2*time.Minute))
	if err := os.Remove(second); err != nil {
		t.Fatal(err)
	}
	p.historyUntil = time.Time{}
	rows = read()
	if len(rows) != 1 || S(rows[0], "title") != "Three" || len(p.claudeHistory) != 1 {
		t.Fatalf("size change or removal lost: %v", rows)
	}
}
