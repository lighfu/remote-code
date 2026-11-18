package host

import (
	"context"
	"fmt"
	"reflect"
	"strings"
	"testing"
	"time"
)

func codexParityWire(reply func(method string, params M) (M, error)) *providerWire {
	w := &providerWire{shared: true, waiting: map[string]chan providerReply{}, done: make(chan struct{}), closeFunc: func() error { return nil }}
	w.sendFunc = func(msg M) error {
		result, err := reply(S(msg, "method"), providerMap(msg["params"]))
		if err == nil {
			w.receive(M{"id": msg["id"], "result": result})
		}
		return err
	}
	return w
}

func TestCodexSideQuestionRepliesToSideInputAndDoesNotWaitForCleanup(t *testing.T) {
	store := providerTestStore(t)
	p := NewProvider("codex", store, t.TempDir()).(*nativeProvider)
	defer p.Close()
	rec := store.Create(M{"provider": "codex", "externalId": "main"})
	started := make(chan struct{})
	cleanup := make(chan any, 1)
	inputReply := make(chan M, 1)
	w := &providerWire{waiting: map[string]chan providerReply{}, done: make(chan struct{}), closeFunc: func() error { return nil }}
	w.sendFunc = func(msg M) error {
		switch S(msg, "method") {
		case "thread/turns/list":
			w.receive(M{"id": msg["id"], "result": M{"data": []any{}}})
		case "thread/fork":
			w.receive(M{"id": msg["id"], "result": M{"thread": M{"id": "side"}}})
		case "turn/start":
			w.receive(M{"id": msg["id"], "result": M{}})
			close(started)
		case "thread/unsubscribe":
			cleanup <- msg["id"]
		case "":
			inputReply <- msg
		}
		return nil
	}
	p.rpc = w
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	answer := make(chan providerReply, 1)
	go func() {
		v, err := p.codexSideQuestion(ctx, rec, M{"question": "example"})
		answer <- providerReply{value: providerMap(v), err: err}
	}()
	select {
	case <-started:
	case <-ctx.Done():
		t.Fatal("side turn did not start")
	}
	p.codexMessage(M{"id": "input", "method": "item/tool/requestUserInput", "params": M{"threadId": "side"}})
	select {
	case v := <-inputReply:
		if S(v, "id") != "input" || !reflect.DeepEqual(providerMap(v["result"])["answers"], M{}) {
			t.Fatalf("unexpected side input reply: %v", v)
		}
	case <-ctx.Done():
		t.Fatal("side turn was left waiting for user input")
	}
	p.codexMessage(M{"method": "item/agentMessage/delta", "params": M{"threadId": "side", "delta": "Example answer"}})
	p.codexMessage(M{"method": "turn/completed", "params": M{"threadId": "side", "turn": M{"status": "completed"}}})
	select {
	case output := <-answer:
		if output.err != nil || S(output.value, "response") != "Example answer" {
			t.Fatalf("unexpected side answer: %v %v", output.value, output.err)
		}
	case <-ctx.Done():
		t.Fatal("answer waited for the unsubscribe response")
	}
	select {
	case id := <-cleanup:
		w.receive(M{"id": id, "result": M{}})
	case <-ctx.Done():
		t.Fatal("ephemeral subscription was not cleaned up")
	}
}

func TestCodexRejoinRestoresActiveTurnAndStop(t *testing.T) {
	store := providerTestStore(t)
	p := NewProvider("codex", store, t.TempDir()).(*nativeProvider)
	defer p.Close()
	rec := store.Create(M{"provider": "codex", "externalId": "thread", "mode": "read-only"})
	sid := S(rec.Info, "id")
	var stop string
	p.rpc = codexParityWire(func(method string, params M) (M, error) {
		switch method {
		case "thread/loaded/list":
			return M{"data": []any{"thread"}}, nil
		case "thread/resume":
			if params["approvalPolicy"] != nil {
				return nil, fmt.Errorf("rejoin overwrote PC settings: %v", params)
			}
			return M{"thread": M{"id": "thread", "status": M{"type": "active"}}, "model": "active-model", "approvalPolicy": "on-request", "sandbox": M{"type": "workspaceWrite"}, "serviceTier": "priority"}, nil
		case "thread/turns/list":
			return M{"data": []M{{"id": "current", "status": "inProgress"}}}, nil
		case "turn/interrupt":
			stop = S(params, "turnId")
			return M{}, nil
		default:
			return nil, fmt.Errorf("unexpected method: %s", method)
		}
	})
	joined, err := p.Do(context.Background(), "rejoin", rec, nil)
	if err != nil || joined != true {
		t.Fatalf("rejoin: %v %v", joined, err)
	}
	info := store.Get(sid).Info
	if S(info, "status") != "running" || S(info, "mode") != "auto" || S(info, "model") != "active-model" || !B(info, "fastMode") {
		t.Fatalf("rejoin lost loaded state: %v", info)
	}
	if _, err := p.Do(context.Background(), "interrupt", rec, nil); err != nil || stop != "current" {
		t.Fatalf("missing loaded turn interrupt: %s %v", stop, err)
	}
}

func TestCodexStartupRejoinsOnlyLoadedUnarchivedChats(t *testing.T) {
	store := providerTestStore(t)
	p := NewProvider("codex", store, t.TempDir()).(*nativeProvider)
	defer p.Close()
	active := store.Create(M{"provider": "codex", "externalId": "active"})
	unloaded := store.Create(M{"provider": "codex", "externalId": "unloaded"})
	archived := store.Create(M{"provider": "codex", "externalId": "archived", "archived": true})
	var methods []string
	p.rpc = codexParityWire(func(method string, params M) (M, error) {
		methods = append(methods, method)
		switch method {
		case "thread/loaded/list":
			if S(params, "cursor") == "" {
				return M{"data": []any{"archived"}, "nextCursor": "next"}, nil
			}
			return M{"data": []any{"active"}}, nil
		case "thread/resume":
			if S(params, "threadId") != "active" {
				return nil, fmt.Errorf("startup tried to resume %v", params)
			}
			return M{"thread": M{"id": "active", "status": M{"type": "active"}}, "initialTurnsPage": M{"data": []M{{"id": "current", "status": "inProgress"}}}}, nil
		default:
			return nil, fmt.Errorf("unexpected method: %s", method)
		}
	})
	if err := p.rejoinLoadedCodex(context.Background()); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(methods, []string{"thread/loaded/list", "thread/loaded/list", "thread/resume"}) {
		t.Fatalf("startup did not reuse the loaded chat list: %v", methods)
	}
	if session := p.session(S(active.Info, "id")); session == nil || session.Turn() != "current" ||
		p.session(S(unloaded.Info, "id")) != nil || p.session(S(archived.Info, "id")) != nil {
		t.Fatal("startup resumed the wrong chats or lost the current turn")
	}
}

func TestCodexSendNowWaitsForInterruptedTurnAndSteerKeepsTurn(t *testing.T) {
	store := providerTestStore(t)
	p := NewProvider("codex", store, t.TempDir()).(*nativeProvider)
	defer p.Close()
	rec := store.Create(M{"provider": "codex", "externalId": "thread", "status": "running"})
	sid := S(rec.Info, "id")
	r := &nativeSession{external: "thread", turn: "old"}
	called := make(chan string, 4)
	w := codexParityWire(func(method string, params M) (M, error) {
		called <- method
		switch method {
		case "turn/start":
			return M{"turn": M{"id": "new"}}, nil
		case "turn/interrupt", "turn/steer":
			return M{}, nil
		default:
			return nil, fmt.Errorf("unexpected method: %s", method)
		}
	})
	r.SetWire(w)
	p.sessions[sid], p.byExternal["thread"] = r, sid
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := p.send(ctx, rec, M{"text": "follow up"}); err != nil || r.Turn() != "old" || <-called != "turn/steer" {
		t.Fatalf("steering lost active turn: %v %q", err, r.Turn())
	}
	if _, err := p.Do(ctx, "interrupt", rec, nil); err != nil || <-called != "turn/interrupt" {
		t.Fatalf("interrupt: %v", err)
	}
	sent := make(chan error, 1)
	go func() { sent <- p.send(ctx, rec, M{"text": "replacement", "now": true}) }()
	select {
	case method := <-called:
		t.Fatalf("sent %s before the interrupted turn completed", method)
	case <-time.After(25 * time.Millisecond):
	}
	p.codexMessage(M{"method": "turn/completed", "params": M{"threadId": "thread", "turn": M{"id": "old", "status": "interrupted"}}})
	if err := <-sent; err != nil {
		t.Fatal(err)
	}
	if method := <-called; method != "turn/start" || r.Turn() != "new" {
		t.Fatalf("replacement requested %s with turn %s", method, r.Turn())
	}
	p.codexMessage(M{"method": "turn/completed", "params": M{"threadId": "thread", "turn": M{"id": "old", "status": "interrupted"}}})
	if r.Turn() != "new" || S(store.Get(sid).Info, "status") != "running" {
		t.Fatal("the interrupted turn cleared the replacement turn")
	}
}

func TestCodexRestoresReviewArgumentsFastModeValidationAndInlineQuestion(t *testing.T) {
	store := providerTestStore(t)
	p := NewProvider("codex", store, t.TempDir()).(*nativeProvider)
	defer p.Close()
	rec := store.Create(M{"provider": "codex", "externalId": "thread", "model": "standard"})
	sid := S(rec.Info, "id")
	r := &nativeSession{external: "thread"}
	var target M
	var sent string
	var fastSent bool
	w := codexParityWire(func(method string, params M) (M, error) {
		switch method {
		case "review/start":
			target = providerMap(params["target"])
			return M{}, nil
		case "model/list":
			return M{"data": []M{{"id": "standard", "serviceTiers": []M{}}, {"id": "fast", "serviceTiers": []M{{"id": "priority"}}}}}, nil
		case "thread/settings/update":
			fastSent = true
			return M{}, nil
		case "turn/start":
			sent = providerText(params["input"])
			return M{}, nil
		default:
			return nil, fmt.Errorf("unexpected method: %s", method)
		}
	})
	r.SetWire(w)
	p.rpc, p.sessions[sid] = w, r
	ctx := context.Background()
	if _, err := p.Do(ctx, "runCommand", rec, M{"name": "review", "args": "  Only this change  "}); err != nil || S(target, "type") != "custom" || S(target, "instructions") != "Only this change" {
		t.Fatalf("custom review arguments lost: %v %v", target, err)
	}
	if _, err := p.Do(ctx, "setFastMode", rec, M{"on": true}); err == nil || fastSent {
		t.Fatalf("sent unsupported fast tier: %v %t", err, fastSent)
	}
	store.Update(sid, M{"model": "fast"}, false)
	if _, err := p.Do(ctx, "setFastMode", store.Get(sid), M{"on": true}); err != nil || !fastSent {
		t.Fatalf("supported fast tier not sent: %v %t", err, fastSent)
	}
	p.codexItem(sid, M{"id": "plan", "type": "plan", "text": "Plan", "questions": []M{{"title": "Choose", "options": []string{"One", "Two"}}}}, true, "")
	if _, err := p.Do(ctx, "respond", store.Get(sid), M{"requestId": "async:plan", "answers": M{"0": []string{"Two"}}}); err != nil || sent != "Two" {
		t.Fatalf("inline question response not delivered: %v %s", err, sent)
	}
}

func TestCodexGeneratedAndMCPImageCardsAttachMedia(t *testing.T) {
	store := providerTestStore(t)
	p := NewProvider("codex", store, t.TempDir()).(*nativeProvider)
	defer p.Close()
	sid := S(store.Create(M{"provider": "codex"}).Info, "id")
	image := "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jX9UAAAAASUVORK5CYII="
	p.codexItem(sid, M{"id": "image", "type": "imageGeneration", "result": image}, true, "")
	p.codexItem(sid, M{"id": "mcp", "type": "mcpToolCall", "server": "fixture", "tool": "preview", "result": M{"content": []M{{"type": "text", "text": "Preview"}, {"type": "image", "mimeType": "image/png", "data": image}}}}, true, "")
	entries := store.Get(sid).Entries
	if len(entries) != 2 || len(providerAnswerList(entries[0]["media"])) != 1 || len(providerAnswerList(entries[1]["media"])) != 1 || !strings.Contains(S(entries[1], "output"), "Preview") || strings.Contains(S(entries[1], "output"), image) {
		t.Fatalf("image results did not produce media cards: %v", entries)
	}
}

func TestCodexFileChangesKeepPathsAndSnapshotLineNumbers(t *testing.T) {
	store := providerTestStore(t)
	p := NewProvider("codex", store, t.TempDir()).(*nativeProvider)
	defer p.Close()
	sid := S(store.Create(M{"provider": "codex"}).Info, "id")
	p.codexItem(sid, M{"type": "fileChange", "id": "patch", "changes": []M{
		{"kind": M{"type": "add"}, "path": "dir/New file.kt", "diff": "++ header\r\n\r\n@@ not a hunk\r\n── Delete fake.kt\r\n"},
		{"kind": M{"type": "delete"}, "path": "old.go", "diff": "-- old\n\n"},
		{"kind": M{"type": "update", "movePath": "new name.go"}, "path": "rename.go", "diff": "@@ -8 +8 @@\n-before\n+after"},
		{"kind": M{"type": "add"}, "path": "empty.txt", "diff": ""},
	}}, true, "")
	entry := store.Get(sid).Entries[0]
	want := "── New dir/New file.kt\n@@ -0,0 +1,4 @@\n+++ header\n+\n+@@ not a hunk\n+── Delete fake.kt\n" +
		"── Delete old.go\n@@ -1,2 +0,0 @@\n--- old\n-\n" +
		"── Modify new name.go\n--- rename.go\n+++ new name.go\n@@ -8 +8 @@\n-before\n+after\n" +
		"── New empty.txt"
	if S(entry, "detail") != want {
		t.Fatalf("file change diff:\n%s\nwant:\n%s", S(entry, "detail"), want)
	}
	wantFiles := []string{"dir/New file.kt", "old.go", "new name.go", "empty.txt"}
	if S(entry, "title") != strings.Join(wantFiles, ", ") || !reflect.DeepEqual(providerAnswerList(entry["files"]), wantFiles) {
		t.Fatalf("file paths to open: %v, want %v", entry["files"], wantFiles)
	}
	if path, got := codexFileChange(M{"path": "notes.txt", "diff": "legacy content"}, false); path != "notes.txt" || got != "legacy content" {
		t.Fatalf("untyped single-file change was rewritten: %q %q", path, got)
	}
}

func TestCodexRenamesOpenDistinctDestinationsWithTheSameBasename(t *testing.T) {
	store := providerTestStore(t)
	p := NewProvider("codex", store, t.TempDir()).(*nativeProvider)
	defer p.Close()
	sid := S(store.Create(M{"provider": "codex"}).Info, "id")
	changes := []M{
		{"path": "old/left.go", "kind": M{"type": "update", "movePath": "left/main.go"}},
		{"path": `C:\work\old\right.go`, "kind": M{"type": "update", "movePath": `C:\work\right\main.go`}},
	}
	p.codexItem(sid, M{"type": "fileChange", "id": "renames", "changes": changes}, true, "")
	entry := store.Get(sid).Entries[0]
	destinations := []string{"left/main.go", `C:\work\right\main.go`}
	if !reflect.DeepEqual(providerAnswerList(entry["files"]), destinations) || S(entry, "title") != strings.Join(destinations, ", ") {
		t.Fatalf("file links or title point to the wrong destination: %v", entry)
	}
	for i, destination := range destinations {
		header := "── Modify " + destination + "\n--- " + S(changes[i], "path") + "\n+++ " + destination + "\n"
		if !strings.Contains(S(entry, "detail"), header) {
			t.Fatalf("missing rename section %q in %v", header, entry["detail"])
		}
	}
}
