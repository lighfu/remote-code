package host

import (
	"context"
	"slices"
	"strings"
	"testing"
	"time"
)

func TestAgyStreamsAndQuestionsStayInTheirTurns(t *testing.T) {
	store := providerTestStore(t)
	rec := store.Create(M{"provider": "antigravity", "externalId": "external"})
	sid := S(rec.Info, "id")
	p := NewProvider("antigravity", store, t.TempDir()).(*nativeProvider)
	sent := make(chan M, 3)
	w := &providerWire{
		waiting: map[string]chan providerReply{},
		done:    make(chan struct{}),
		sendFunc: func(m M) error {
			sent <- m
			return nil
		},
	}
	t.Cleanup(func() { close(w.done) })
	r := &nativeSession{external: "external", wire: w, parents: map[string]string{}}
	p.sessions[sid], p.byExternal[r.external], p.rpc = r, sid, w
	prompt := func() M {
		t.Helper()
		select {
		case m := <-sent:
			if S(m, "method") != "session/prompt" {
				t.Fatalf("expected a prompt, received %v", m)
			}
			return m
		case <-time.After(2 * time.Second):
			t.Fatal("prompt was not sent")
			return nil
		}
	}
	update := func(u M) {
		p.agyMessage(M{"method": "session/update", "params": M{"sessionId": r.external, "update": u}})
	}
	chunk := func(kind, text string) {
		update(M{"sessionUpdate": kind, "content": M{"type": "text", "text": text}})
	}
	complete := func(m M, reason string) {
		w.receive(M{"id": m["id"], "result": M{"stopReason": reason}})
		r.agyPromptMu.Lock()
		r.agyPromptMu.Unlock()
	}
	send := func(text string) {
		t.Helper()
		if err := p.send(context.Background(), rec, M{"text": text}); err != nil {
			t.Fatal(err)
		}
	}

	send("First user prompt")
	first := prompt()
	chunk("agent_thought_chunk", "First thought")
	chunk("agent_message_chunk", "First reply")
	complete(first, "end_turn")
	send("Second user prompt")
	second := prompt()
	chunk("agent_thought_chunk", "Second thought")
	chunk("agent_message_chunk", "Second reply")
	update(M{"sessionUpdate": "tool_call", "toolCallId": "q", "name": "ask_question", "rawInput": M{"questions": []M{
		{"question": "Which result?", "options": []any{"First", M{"text": "Second"}}},
	}}})
	update(M{"sessionUpdate": "tool_call_update", "toolCallId": "q", "status": "completed"})
	current := store.Get(sid)
	entry := current.Entries[len(current.Entries)-1]
	if S(entry, "kind") != "question" || S(entry, "status") != "pending" || S(current.Info, "status") != "waiting" {
		t.Fatalf("ask_question did not appear as a pending card: %v", entry)
	}
	choices := providerRows(providerRows(entry["questions"])[0]["options"])
	if len(choices) != 2 || S(choices[1], "label") != "Second" {
		t.Fatalf("missing choice labels: %v", entry)
	}
	if err := p.respond(current, M{"requestId": S(entry, "requestId"), "answers": M{"0": []string{"Second"}}}); err != nil {
		t.Fatal(err)
	}
	select {
	case m := <-sent:
		t.Fatalf("answer overtook active prompt: %v", m)
	default:
	}
	complete(second, "end_turn")
	answer := prompt()
	content, _ := providerMap(answer["params"])["prompt"].([]any)
	if len(content) != 1 || S(providerMap(content[0]), "text") != "Second" {
		t.Fatalf("question answer not forwarded as next prompt: %v", answer)
	}
	chunk("agent_message_chunk", "Answer confirmed")
	complete(answer, "end_turn")
	var messages, thoughts []string
	for _, e := range store.Get(sid).Entries {
		switch S(e, "kind") {
		case "assistant":
			messages = append(messages, S(e, "text"))
		case "thinking":
			thoughts = append(thoughts, S(e, "text"))
		case "question":
			if S(e, "status") != "resolved" {
				t.Fatalf("question did not resolve: %v", e)
			}
		}
		if B(e, "streaming") {
			t.Fatalf("previous turn was still streaming: %v", e)
		}
	}
	if len(messages) != 3 || messages[0] != "First reply" || messages[1] != "Second reply" || messages[2] != "Answer confirmed" || len(thoughts) != 2 || thoughts[0] != "First thought" || thoughts[1] != "Second thought" {
		t.Fatalf("Antigravity merged messages or thoughts across turns: %v and %v", messages, thoughts)
	}
}

func TestAgyCommandsAreScopedToLiveSession(t *testing.T) {
	store := providerTestStore(t)
	p := NewProvider("antigravity", store, t.TempDir()).(*nativeProvider)
	for _, name := range []string{"first", "second"} {
		rec := store.Create(M{"provider": "antigravity", "externalId": name})
		sid := S(rec.Info, "id")
		p.sessions[sid] = &nativeSession{external: name}
		p.byExternal[name] = sid
		p.agyMessage(M{"method": "session/update", "params": M{"sessionId": name, "update": M{
			"sessionUpdate": "available_commands_update", "availableCommands": []M{{"name": name}},
		}}})
	}
	for name, sid := range p.byExternal {
		commands, err := p.listCommands(context.Background(), store.Get(sid))
		if err != nil || len(commands) != 1 || S(commands[0], "name") != name {
			t.Fatalf("commands for %s = %v, %v", name, commands, err)
		}
	}
}

func TestAgyToolCardsKeepCommandsFilesAndDiffs(t *testing.T) {
	store := providerTestStore(t)
	rec := store.Create(M{"provider": "antigravity", "externalId": "external"})
	sid := S(rec.Info, "id")
	p := NewProvider("antigravity", store, t.TempDir()).(*nativeProvider)
	p.sessions[sid] = &nativeSession{external: "external"}
	p.byExternal["external"] = sid
	send := func(u M) {
		p.agyMessage(M{"method": "session/update", "params": M{"sessionId": "external", "update": u}})
	}
	send(M{"sessionUpdate": "tool_call", "toolCallId": "shell", "kind": "execute", "title": "echo example"})
	send(M{"sessionUpdate": "tool_call", "toolCallId": "edit", "kind": "edit", "title": "Edit files"})
	send(M{"sessionUpdate": "tool_call_update", "toolCallId": "edit", "status": "completed", "content": []M{
		{"type": "diff", "path": "/C:/one.go", "oldText": "same\nbefore\n", "newText": "same\nafter\n"},
		{"type": "diff", "path": "/C:/two.go", "oldText": "", "newText": "added"},
		{"type": "content", "content": M{"text": "Edited files"}},
	}, "rawOutput": "Result details"})
	entries := store.Get(sid).Entries
	if len(entries) != 2 || S(entries[0], "tool") != "Bash" || S(entries[1], "tool") != "Edit" {
		t.Fatalf("wrong Antigravity tool cards: %v", entries)
	}
	diff := S(entries[1], "detail")
	files := providerAnswerList(entries[1]["files"])
	if !strings.Contains(diff, "-before\n+after") || !strings.Contains(diff, "+added") ||
		!slices.Equal(files, []string{"C:/one.go", "C:/two.go"}) || S(entries[1], "output") != "Edited files\nResult details" {
		t.Fatalf("tool output or file diff lost: %v", entries[1])
	}
}
