package host

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestClaudeWatcherFollowsPCAgentActivity(t *testing.T) {
	store := providerTestStore(t)
	rec := store.Create(M{"provider": "claude", "externalId": "external", "watching": true})
	sid := S(rec.Info, "id")
	p := NewProvider("claude", store, t.TempDir()).(*nativeProvider)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	p.followers = map[string]context.CancelFunc{sid: cancel}
	check := func(raw, expected string, expectedLive bool, grew time.Time) {
		t.Helper()
		agents, err := parseClaudePCAgents([]byte(raw))
		if err != nil {
			t.Fatal(err)
		}
		p.claudePC.agents = agents
		p.updateClaudeWatchActivity(ctx, sid, "external", grew)
		info := store.Get(sid).Info
		if S(info, "status") != expected || B(info, "live") != expectedLive {
			t.Fatalf("PC status = %s (live %t), want %s (live %t)", S(info, "status"), B(info, "live"), expected, expectedLive)
		}
	}
	check(`[{"sessionId":"external","status":"working","kind":"background","id":"owner"}]`, "running", true, time.Time{})
	check(`[{"sessionId":"external","state":"waiting_for_input"}]`, "waiting", true, time.Time{})
	check(`[{"sessionId":"external","status":"completed"}]`, "idle", true, time.Time{})
	check(`[{"sessionId":"external","status":"idle"}]`, "running", true, time.Now())
	check(`[]`, "idle", false, time.Time{})
	p.stopTranscriptFollower(sid)
	check(`[{"sessionId":"external","status":"working"}]`, "idle", false, time.Time{})
}

func TestClaudeHistoryShowsPCAgentActivityWithCachedRows(t *testing.T) {
	store := providerTestStore(t)
	p := NewProvider("claude", store, t.TempDir()).(*nativeProvider)
	p.history = []M{{"externalId": "first", "live": false, "running": false}, {"externalId": "second", "live": false, "running": false}}
	p.historyUntil = time.Now().Add(time.Minute)
	p.claudePC.checked = time.Now()
	for _, tc := range []struct {
		status  string
		running bool
	}{{"running", true}, {"waiting", true}, {"idle", false}} {
		p.claudePC.agents = map[string]claudePCAgent{"first": {status: tc.status}, "second": {status: tc.status}}
		rows, err := p.listHistory(context.Background())
		if err != nil || len(rows) != 2 {
			t.Fatalf("unexpected PC history: %v, %v", rows, err)
		}
		for _, row := range rows {
			if !B(row, "live") || B(row, "running") != tc.running {
				t.Fatalf("PC history does not reflect %q: %v", tc.status, row)
			}
		}
	}
}

func TestClaudeWatcherFindsBridgeSuccessorAfterClear(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "old.jsonl")
	next := filepath.Join(dir, "new.jsonl")
	other := filepath.Join(dir, "other.jsonl")
	const attached = "{\"type\":\"bridge-session\",\"bridgeSessionId\":\"bridge-id\"}\n"
	const detached = "{\"type\":\"bridge-session\",\"bridgeSessionId\":\"\"}\n"
	var bridge claudeBridgeWatch
	read := func() {
		t.Helper()
		info, err := os.Stat(path)
		if err != nil {
			t.Fatal(err)
		}
		if err := bridge.read(path, info); err != nil {
			t.Fatal(err)
		}
	}
	write := func(file, body string) {
		t.Helper()
		if err := os.WriteFile(file, []byte(body), 0600); err != nil {
			t.Fatal(err)
		}
	}
	write(path, attached)
	read()
	if bridge.id != "bridge-id" || !bridge.detached.IsZero() {
		t.Fatalf("invalid initial bridge state: %+v", bridge)
	}
	write(path, attached+detached)
	read()
	write(next, attached)
	write(other, "{\"type\":\"user\",\"bridgeSessionId\":\"bridge-id\"}\n")
	if got := bridge.successor(path, time.Now()); got != next {
		t.Fatalf("bridge successor = %q, want %q", got, next)
	}

	store := providerTestStore(t)
	rec := store.Create(M{"provider": "claude", "externalId": "old", "watching": true, "context": M{"used": 100}, "plan": M{"title": "old plan"}})
	sid := S(rec.Info, "id")
	p := NewProvider("claude", store, dir).(*nativeProvider)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	p.followers = map[string]context.CancelFunc{sid: cancel}
	p.sessions[sid] = &nativeSession{remote: true, external: "old"}
	p.byExternal["old"] = sid
	if !p.switchClaudeWatch(ctx, sid, "old", "new") {
		t.Fatal("watch did not switch to the successor")
	}
	info := store.Get(sid).Info
	if S(info, "externalId") != "new" || info["context"] != nil || info["plan"] != nil || p.providerSID("old") != "" || p.providerSID("new") != sid || p.session(sid).External() != "new" {
		t.Fatalf("old conversation state survived /clear: %v", info)
	}
}

func TestClaudeIdleCleanupAndProjectCommandCache(t *testing.T) {
	store := providerTestStore(t)
	data := t.TempDir()
	if err := os.WriteFile(filepath.Join(data, "config.json"), []byte(`{"idleMinutes":0}`), 0600); err != nil {
		t.Fatal(err)
	}
	p := NewProvider("claude", store, data).(*nativeProvider)
	rec := store.Create(M{"provider": "claude", "status": "idle", "live": true})
	sid := S(rec.Info, "id")
	r := &nativeSession{}
	p.sessions[sid] = r
	p.armClaudeIdle(sid, r)
	deadline := time.After(2 * time.Second)
	for p.session(sid) != nil || B(store.Get(sid).Info, "live") {
		select {
		case <-deadline:
			t.Fatal("configured idle cleanup did not detach session")
		case <-time.After(time.Millisecond):
		}
	}
	for i, cwd := range []string{filepath.Join(data, "project-one"), filepath.Join(data, "project-two")} {
		expected := []string{"one", "two"}[i]
		p.rememberClaudeInit(cwd, M{"commands": []M{{"name": expected}}})
	}
	reopened := NewProvider("claude", store, data).(*nativeProvider)
	for _, tc := range []struct{ project, want string }{{"project-one", "one"}, {"project-two", "two"}} {
		rows, err := reopened.listClaudeCommands(context.Background(), &Record{Info: M{"cwd": filepath.Join(data, tc.project)}})
		if err != nil || len(rows) != 1 || S(rows[0], "name") != tc.want {
			t.Fatalf("%s commands = %v, %v", tc.project, rows, err)
		}
	}
}

func TestClaudeExitPlanModeAndPermissionSuggestions(t *testing.T) {
	store := providerTestStore(t)
	rec := store.Create(M{"provider": "claude", "mode": "plan"})
	sid := S(rec.Info, "id")
	p := NewProvider("claude", store, t.TempDir()).(*nativeProvider)
	buffer := &providerBuffer{}
	w := &providerWire{claude: true, in: buffer}
	p.addPermission(sid, w, "wire-plan", "can_use_tool", M{}, "ExitPlanMode", M{"plan": "# Planned changes"})
	entries := store.Get(sid).Entries
	card := entries[len(entries)-1]
	options := providerRows(card["options"])
	if S(card, "detail") != "# Planned changes" || len(options) != 3 || S(options[0], "id") != "plan_accept_edits" || S(options[1], "id") != "plan_default" || S(options[2], "id") != "deny" {
		t.Fatalf("incomplete exit-plan card: %v", card)
	}
	if err := p.respond(rec, M{"requestId": S(card, "requestId"), "choice": "plan_accept_edits"}); err != nil {
		t.Fatal(err)
	}
	reply := providerMap(providerMap(buffer.messages()[0]["response"])["response"])
	updates := providerRows(reply["updatedPermissions"])
	if len(updates) != 1 || S(updates[0], "mode") != "acceptEdits" || S(updates[0], "destination") != "session" || S(store.Get(sid).Info, "mode") != "acceptEdits" {
		t.Fatalf("plan approval did not update the session mode: %v", reply)
	}
	suggestions := []M{{"type": "addRules", "destination": "session"}}
	p.addPermission(sid, w, "wire-permission", "can_use_tool", M{"permission_suggestions": suggestions}, "Bash", M{"command": "echo example"})
	entries = store.Get(sid).Entries
	card = entries[len(entries)-1]
	options = providerRows(card["options"])
	if len(options) != 3 || S(options[1], "id") != "allow_always" {
		t.Fatalf("missing always-allow suggestion: %v", card)
	}
	if err := p.respond(rec, M{"requestId": S(card, "requestId"), "choice": "allow_always"}); err != nil {
		t.Fatal(err)
	}
	reply = providerMap(providerMap(buffer.messages()[1]["response"])["response"])
	updates = providerRows(reply["updatedPermissions"])
	if len(updates) != 1 || S(updates[0], "type") != "addRules" {
		t.Fatalf("suggested rules were not forwarded: %v", reply)
	}
}
