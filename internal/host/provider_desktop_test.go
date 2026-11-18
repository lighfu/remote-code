package host

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"io"
	"net"
	"reflect"
	"sync"
	"testing"
	"time"
)

func TestDesktopPatchAtomicAndCanonicalTurns(t *testing.T) {
	state := M{"title": "before", "turns": []any{M{"id": "one", "text": "old"}}}
	change := M{"type": "patches", "revision": float64(2), "baseRevision": float64(1), "patches": []any{M{"op": "replace", "path": []any{"title"}, "value": "after"}, M{"op": "replace", "path": []any{"turns", float64(0), "text"}, "value": "new"}, M{"op": "add", "path": []any{"turns", "-"}, "value": M{"id": "two"}}}}
	next, revision, ok := desktopApplyChange(state, 1, change)
	if !ok || revision != 2 || S(next, "title") != "after" || S(providerRows(next["turns"])[0], "text") != "new" {
		t.Fatalf("patch failed %#v %v %v", next, revision, ok)
	}
	if S(state, "title") != "before" || S(providerRows(state["turns"])[0], "text") != "old" {
		t.Fatal("original snapshot was mutated")
	}
	for _, path := range [][]any{{"__proto__", "polluted"}, {"turns", "0"}, {"missing", "text"}} {
		bad := clone(change)
		bad["patches"] = []any{M{"op": "replace", "path": path, "value": "bad"}}
		if _, _, ok = desktopApplyChange(state, 1, bad); ok {
			t.Fatalf("invalid patch accepted %#v", path)
		}
	}
	bad := clone(change)
	bad["baseRevision"] = float64(0)
	if _, _, ok = desktopApplyChange(state, 1, bad); ok {
		t.Fatal("revision gap accepted")
	}
	canonical := M{"turnHistory": M{"kind": "canonical", "history": M{"entitiesByKey": M{"a": M{"id": "one"}, "b": M{"id": "two"}}, "islands": []any{M{"entries": []any{M{"value": "b"}, M{"value": "a"}, M{"value": "b"}}}}}}}
	turns := desktopTurns(canonical)
	if len(turns) != 2 || S(turns[0], "id") != "two" || S(turns[1], "id") != "one" {
		t.Fatalf("bad canonical order %#v", turns)
	}
}
func TestDesktopAcceptedPCMessagesDeduplicate(t *testing.T) {
	steering := M{"id": "steer", "type": "steeringUserMessage", "status": "accepted", "serverUserMessageId": "server", "serverClientUserMessageId": "client", "input": []any{M{"type": "text", "text": "PC instruction"}}, "restoreMessage": M{"acceptedAtMs": float64(100)}}
	rows := desktopMessages([]M{steering})
	if len(rows) != 1 || S(rows[0], "type") != "userMessage" || S(rows[0], "id") != "server" || providerText(rows[0]["content"]) != "PC instruction" {
		t.Fatalf("steering not normalized %#v", rows)
	}
	ordinary := M{"id": "server", "clientId": "client", "type": "userMessage", "content": []any{M{"type": "text", "text": "PC instruction"}}}
	rows = desktopMessages([]M{steering, ordinary})
	if len(rows) != 1 || !reflect.DeepEqual(rows[0], ordinary) {
		t.Fatalf("duplicate PC messages %#v", rows)
	}
	steering["status"] = "pending"
	if len(desktopMessages([]M{steering})) != 0 {
		t.Fatal("unaccepted PC message exposed")
	}
}
func desktopTestRead(c net.Conn) (M, error) {
	var header [4]byte
	if _, err := io.ReadFull(c, header[:]); err != nil {
		return nil, err
	}
	body := make([]byte, binary.LittleEndian.Uint32(header[:]))
	if _, err := io.ReadFull(c, body); err != nil {
		return nil, err
	}
	var m M
	err := json.Unmarshal(body, &m)
	return m, err
}
func desktopTestWrite(c net.Conn, m M) error {
	raw, err := json.Marshal(m)
	if err != nil {
		return err
	}
	frame := make([]byte, len(raw)+4)
	binary.LittleEndian.PutUint32(frame, uint32(len(raw)))
	copy(frame[4:], raw)
	for len(frame) > 0 {
		n := min(3, len(frame))
		if _, err = c.Write(frame[:n]); err != nil {
			return err
		}
		frame = frame[n:]
	}
	return nil
}

func TestDesktopIPCFrameOwnerAndProjection(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	rec := store.Create(M{"provider": "codex", "externalId": "12345678-1234-1234-1234-123456789abc", "cwd": t.TempDir()})
	p := NewProvider("codex", store, t.TempDir()).(*nativeProvider)
	defer p.Close()
	d := p.desktopClient()
	client, server := net.Pipe()
	defer server.Close()
	d.dial = func(context.Context) (net.Conn, error) { return client, nil }
	var mu sync.Mutex
	methods := []string{}
	skillText := ""
	pcMessage := M{"id": "pc-msg", "type": "steeringUserMessage", "status": "accepted", "serverUserMessageId": "pc-accepted", "input": []any{M{"type": "text", "text": "From PC"}}}
	state := M{"title": "Desktop conversation", "threadRuntimeStatus": M{"type": "idle"}, "turns": []any{M{"status": "completed", "items": []any{pcMessage}}}}
	go func() {
		for {
			req, err := desktopTestRead(server)
			if err != nil {
				return
			}
			method := S(req, "method")
			mu.Lock()
			methods = append(methods, method)
			if method == "thread-follower-start-turn" {
				turn := providerMap(providerMap(req["params"])["turnStart"])
				skillText = providerText(providerMap(turn["request"])["input"])
			}
			mu.Unlock()
			if S(req, "type") == "request" {
				result := M{}
				owner := "desktop-owner"
				if method == "initialize" {
					result = M{"clientId": "remote-client"}
					owner = ""
				}
				if method == "thread-follower-update-thread-settings" {
					result = M{"applied": true}
					owner = "wrong-owner"
				}
				reply := M{"type": "response", "requestId": req["requestId"], "method": method, "resultType": "success", "result": result, "handledByClientId": owner}
				if desktopTestWrite(server, reply) != nil {
					return
				}
			} else if method == "thread-stream-following-changed" && B(providerMap(req["params"]), "following") {
				params := M{"hostId": "local", "conversationId": rec.Info["externalId"], "change": M{"type": "snapshot", "revision": float64(1), "conversationState": state}}
				broadcast := M{"type": "broadcast", "method": "thread-stream-state-changed", "sourceClientId": "desktop-owner", "version": 13, "params": params}
				if desktopTestWrite(server, broadcast) != nil {
					return
				}
			}
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	ok, err := p.tryDesktop(ctx, rec)
	if err != nil || !ok {
		t.Fatalf("follow failed %v %v", ok, err)
	}
	saved := store.Get(S(rec.Info, "id"))
	if !B(saved.Info, "desktop") || !B(saved.Info, "live") || S(saved.Info, "title") != "Desktop conversation" {
		t.Fatalf("state not projected %#v", saved.Info)
	}
	found := false
	for _, entry := range saved.Entries {
		if S(entry, "id") == "pc-accepted" && S(entry, "kind") == "user" && S(entry, "text") == "From PC" {
			found = true
		}
	}
	if !found {
		t.Fatalf("PC message missing %#v", saved.Entries)
	}
	if _, err := p.desktopDo(ctx, "runCommand", saved, M{"name": "example-skill", "args": "sample"}); err != nil {
		t.Fatalf("Desktop could not run a skill: %v", err)
	}
	_, err = d.request(ctx, S(rec.Info, "externalId"), "thread-follower-update-thread-settings", M{"threadSettings": M{"serviceTier": "priority"}})
	if err == nil {
		t.Fatal("request accepted from wrong owner")
	}
	mu.Lock()
	if len(methods) < 4 {
		t.Fatalf("requests not observed %#v", methods)
	}
	if skillText != "$example-skill sample" {
		t.Fatalf("incorrect skill forwarded to Desktop: %q", skillText)
	}
	mu.Unlock()
}

func TestDesktopFollowReadyAfterStatusProjection(t *testing.T) {
	store := providerTestStore(t)
	rec := store.Create(M{"provider": "codex", "externalId": "thread", "desktop": true})
	sid := S(rec.Info, "id")
	p := NewProvider("codex", store, t.TempDir()).(*nativeProvider)
	defer p.Close()
	d := p.desktopClient()
	client, server := net.Pipe()
	defer server.Close()
	ready := make(chan struct{})
	binding := &desktopBinding{sid: sid, owner: "desktop", revision: -1, ready: ready}
	d.conn = client
	d.follows["thread"] = binding
	msg := M{"method": "thread-stream-state-changed", "sourceClientId": "desktop", "version": 13, "params": M{
		"hostId": "local", "conversationId": "thread", "change": M{"type": "snapshot", "revision": float64(1), "conversationState": M{
			"threadRuntimeStatus": M{"type": "active"},
		}},
	}}

	store.mu.Lock()
	go d.receive(client, msg)
	accepted := false
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		d.mu.Lock()
		accepted = binding.state != nil
		d.mu.Unlock()
		if accepted {
			break
		}
		time.Sleep(time.Millisecond)
	}
	early := false
	select {
	case <-ready:
		early = true
	default:
	}
	store.mu.Unlock()
	if !accepted || early {
		t.Fatalf("snapshot acceptance and session projection were not ordered (accepted %v, early return %v)", accepted, early)
	}
	select {
	case <-ready:
	case <-time.After(2 * time.Second):
		t.Fatal("projected Desktop snapshot did not notify the follower")
	}
	if info := store.Get(sid).Info; !B(info, "live") || S(info, "status") != "running" {
		t.Fatalf("follow became ready before applying active status: %v", info)
	}
}

func TestDesktopPreviewPublishesOnlyLatestMainMessage(t *testing.T) {
	store := providerTestStore(t)
	rec := store.Create(M{"provider": "codex", "desktop": true})
	sid := S(rec.Info, "id")
	p := NewProvider("codex", store, t.TempDir()).(*nativeProvider)
	defer p.Close()
	d := p.desktopClient()
	project := func(binding *desktopBinding, history string, latest string) {
		d.project("root", binding, M{"turns": []M{
			{"id": "first", "status": "completed", "items": []M{{"id": "old", "type": "agentMessage", "text": history}}},
			{"id": "second", "status": "completed", "items": []M{{"id": "latest", "type": "agentMessage", "text": latest}}},
		}})
	}
	events, cancel := store.Subscribe()
	defer cancel()
	assertPreview := func(want string) {
		t.Helper()
		if got := S(store.Get(sid).Info, "preview"); got != want {
			t.Fatalf("preview = %q, want %q", got, want)
		}
		for {
			select {
			case event := <-events:
				if S(event, "type") == "session" && S(providerMap(event["session"]), "preview") != want {
					t.Fatalf("published an older preview: %v", event)
				}
			default:
				return
			}
		}
	}
	project(&desktopBinding{sid: sid}, "Earlier response", "Current response")
	assertPreview("Current response")
	project(&desktopBinding{sid: sid}, "Earlier replay", "Current response")
	p.codexItem(sid, M{"id": "child", "type": "agentMessage", "text": "A subagent response"}, true, "subagent-card")
	store.Patch(sid, "old", M{"text": "Earlier message edited"})
	assertPreview("Current response")
	store.Add(sid, M{"id": "unfinished", "kind": "assistant", "text": "Next response", "streaming": true})
	assertPreview("Current response")
	store.Patch(sid, "unfinished", M{"streaming": false})
	assertPreview("Next response")
}

func TestDesktopChildCardsAndRewind(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	rec := store.Create(M{"provider": "codex", "externalId": "root-thread"})
	p := NewProvider("codex", store, t.TempDir()).(*nativeProvider)
	defer p.Close()
	d := p.desktopClient()
	binding := &desktopBinding{sid: S(rec.Info, "id")}
	spawn := M{"id": "spawn", "type": "collabAgentToolCall", "tool": "spawnAgent", "prompt": "Task", "receiverThreadIds": []any{"child"}}
	child := M{"id": "activity", "type": "subAgentActivity", "agentThreadId": "child", "agentPath": "/root/tests", "kind": "completed", "model": "test-model", "reasoningEffort": "high"}
	answer := M{"id": "answer", "type": "agentMessage", "text": "Old answer"}
	d.project("root-thread", binding, M{"turns": []any{M{"turnId": "turn", "status": "completed", "items": []any{spawn, child, answer}}}})
	entries := store.Get(S(rec.Info, "id")).Entries
	agents := 0
	for _, entry := range entries {
		if S(entry, "tool") == "Agent" {
			agents++
			if S(entry, "id") != "spawn" || S(entry, "status") != "done" || S(entry, "title") != "tests" {
				t.Fatalf("bad agent %#v", entry)
			}
		}
	}
	if agents != 1 {
		t.Fatalf("duplicate child cards %#v", entries)
	}
	d.project("root-thread", binding, M{"turns": []any{M{"turnId": "turn", "status": "completed", "items": []any{spawn, child, M{"id": "replacement", "type": "agentMessage", "text": "New answer"}}}}})
	entries = store.Get(S(rec.Info, "id")).Entries
	old, newAnswer := false, false
	for _, entry := range entries {
		old = old || S(entry, "id") == "answer"
		newAnswer = newAnswer || S(entry, "id") == "replacement"
	}
	if old || !newAnswer {
		t.Fatalf("rewind did not replace tail %#v", entries)
	}
}

func TestDesktopHistoryWindowPreservesTurnsAndOrdersNewlyLoadedMessages(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	sid := S(store.Create(M{"provider": "codex"}).Info, "id")
	p := NewProvider("codex", store, t.TempDir()).(*nativeProvider)
	defer p.Close()
	d := p.desktopClient()
	binding := &desktopBinding{sid: sid}

	oldPrompt := M{"id": "old-prompt", "type": "userMessage", "content": "Same question"}
	newPrompt := M{"id": "new-prompt", "type": "userMessage", "content": "Same question"}
	oldReply := M{"id": "old-reply", "type": "agentMessage", "text": "First answer"}
	newReply := M{"id": "new-reply", "type": "agentMessage", "text": "Second answer"}
	newTurn := M{"turnId": "new-turn", "status": "completed", "turnStartedAtMs": 2000, "items": []M{newReply}}
	d.project("root-thread", binding, M{"turns": []M{
		{"turnId": "old-turn", "status": "completed", "turnStartedAtMs": 1000, "items": []M{oldPrompt, oldReply}},
		newTurn,
	}})
	store.Add(sid, M{"id": "local-note", "kind": "system", "text": "App note"})

	// Desktop no longer includes the earliest turn. A user item from the still
	// present turn arrives after its reply was stored, and has the same text as
	// the old turn's user item.
	newTurn["items"] = []M{newPrompt, newReply}
	d.project("root-thread", binding, M{"turns": []M{newTurn}})
	var ids []string
	for _, entry := range store.Get(sid).Entries {
		ids = append(ids, S(entry, "id"))
		if S(entry, "id") == "new-reply" && N(entry, "ts") != 2000 {
			t.Fatalf("Desktop message did not use its turn timestamp: %v", entry)
		}
	}
	want := []string{"old-prompt", "old-reply", "new-prompt", "local-note", "new-reply"}
	if !reflect.DeepEqual(ids, want) {
		t.Fatalf("Desktop projection changed existing conversation or order: %v, want %v", ids, want)
	}
}

func TestCodexProjectionPreservesReviewsAndMessageContent(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	sid := S(store.Create(M{"provider": "codex"}).Info, "id")
	p := NewProvider("codex", store, t.TempDir()).(*nativeProvider)
	defer p.Close()

	p.codexItem(sid, M{"id": "automatic", "type": "automaticApprovalReview"}, true, "")
	p.codexItem(sid, M{"id": "empty-reasoning", "type": "reasoning", "summary": []string{"", "  "}}, true, "")
	p.codexItem(sid, M{"id": "streamed", "type": "agentMessage", "text": "Some answer"}, false, "")
	p.codexItem(sid, M{"id": "streamed", "type": "agentMessage"}, true, "")
	p.codexItem(sid, M{"id": "from-content", "type": "agentMessage", "content": []M{{"text": "Another answer"}}}, true, "")
	store.Add(sid, M{"id": "local-prompt", "kind": "user", "text": "Repeat this"})
	p.codexItem(sid, M{"id": "local-prompt-echo", "type": "userMessage", "clientId": "local-prompt", "content": "Repeat this"}, true, "")
	p.codexItem(sid, M{"id": "separate-prompt", "type": "userMessage", "content": "Repeat this"}, true, "")
	entries := store.Get(sid).Entries
	if len(entries) != 5 || S(entries[0], "tool") != "automaticApprovalReview" || S(entries[0], "status") != "done" ||
		S(entries[1], "text") != "Some answer" || B(entries[1], "streaming") || S(entries[2], "text") != "Another answer" ||
		S(entries[3], "id") != "local-prompt" || S(entries[4], "id") != "separate-prompt" {
		t.Fatalf("unexpected Codex projection: %v", entries)
	}
}
