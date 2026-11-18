package host

import (
	"reflect"
	"testing"
)

func claudeWrapUpTestInput() M {
	return M{"recap": "Unity tests passed; master has not been pushed.", "ask": "master を origin へプッシュしますか？", "actions": []M{{"label": "プッシュする"}, {"label": "まだしない"}}}
}

func TestClaudeWrapUpAddsReplyCardWithoutReplacingToolOrPermission(t *testing.T) {
	store := providerTestStore(t)
	rec := store.Create(M{"provider": "claude", "status": "running"})
	sid := S(rec.Info, "id")
	p := NewProvider("claude", store, t.TempDir()).(*nativeProvider)
	r := &nativeSession{parents: map[string]string{}}
	input := claudeWrapUpTestInput()
	call := M{"type": "assistant", "message": M{"id": "message", "content": []M{{"type": "tool_use", "id": "wrap", "name": "mcp__ccd_turn__wrap_up", "input": input}}}}
	p.claudeMessage(sid, r, call)
	if entries := store.Get(sid).Entries; len(entries) != 1 {
		t.Fatalf("A not-yet-completed tool created a reply offer: %v", entries)
	}
	const output = "The user is working over Remote Control and may not see the next-step box."
	result := M{"type": "user", "message": M{"content": []M{{"type": "tool_result", "tool_use_id": "wrap", "content": []M{{"type": "text", "text": output}}}}}}
	p.claudeMessage(sid, r, result)
	entries := store.Get(sid).Entries
	if len(entries) != 2 {
		t.Fatalf("Missing next-step card: %v", entries)
	}
	raw, card := entries[0], entries[1]
	if S(raw, "tool") != "mcp__ccd_turn__wrap_up" || S(raw, "detail") != providerPretty(input) || S(raw, "output") != output || S(raw, "status") != "done" {
		t.Fatalf("Original tool display was changed: %v", raw)
	}
	if S(card, "id") != "wrap:wrap-up" || S(card, "tool") != "wrap_up" || S(card, "title") != S(input, "ask") || S(card, "text") != S(input, "recap") {
		t.Fatalf("Wrong next-step card fields: %v", card)
	}
	options := providerRows(card["options"])
	if len(options) != 2 || S(options[0], "id") != "wrap-up-action-0" || S(options[0], "label") != "プッシュする" || S(options[1], "label") != "まだしない" {
		t.Fatalf("Reply labels were not retained: %v", options)
	}
	if len(p.pending) != 0 || N(store.Get(sid).Info, "pending") != 0 || S(card, "requestId") != "" || S(store.Get(sid).Info, "status") != "running" {
		t.Fatal("A completed Desktop wrap_up was incorrectly turned into a pending permission")
	}
	p.claudeMessage(sid, r, call)
	p.claudeMessage(sid, r, result)
	if entries := store.Get(sid).Entries; len(entries) != 2 {
		t.Fatalf("Replayed messages duplicated the card: %v", entries)
	}
}

func TestClaudeWrapUpBackfillStaysBeforeLaterRepliesAndIsIdempotent(t *testing.T) {
	store := providerTestStore(t)
	rec := store.Create(M{"provider": "claude", "preview": "Saved preview", "updatedAt": 500})
	sid := S(rec.Info, "id")
	raw := M{"id": "wrap", "kind": "tool", "tool": "mcp__ccd-turn__wrap_up", "detail": providerPretty(claudeWrapUpTestInput()), "output": "Shown in the next-step box.", "status": "done", "ts": 100}
	store.Add(sid, raw)
	store.Add(sid, M{"id": "reply", "kind": "user", "text": "まだしない", "ts": 200})
	before := store.Get(sid)
	events, cancel := store.Subscribe()
	defer cancel()
	for i := 0; i < 2; i++ {
		NewProvider("claude", store, t.TempDir())
		got := store.Get(sid)
		if len(got.Entries) != 3 || S(got.Entries[0], "id") != "wrap" || S(got.Entries[1], "id") != "wrap:wrap-up" || S(got.Entries[2], "id") != "reply" {
			t.Fatalf("Historical offer moved after its answer: %v", got.Entries)
		}
		if !reflect.DeepEqual(got.Entries[0], before.Entries[0]) || !reflect.DeepEqual(got.Info, before.Info) {
			t.Fatal("Backfill changed the raw tool or session activity/preview")
		}
	}
	if ev := <-events; S(ev, "type") != "entries_removed" || S(ev, "fromId") != "" {
		t.Fatalf("No timeline insertion invalidation: %v", ev)
	}
	select {
	case ev := <-events:
		t.Fatalf("Unchanged backfill emitted another event: %v", ev)
	default:
	}
}

func TestClaudeWrapUpDoesNotInventDesktopActionsOrSuccessfulTools(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status string
		tool   string
		input  M
		output string
		card   bool
	}{
		{"application action", "done", "mcp__ccd_turn__wrap_up", M{"ask": "Create a PR?", "actions": []M{{"action": "create_pr"}, {"label": "Later"}}}, "", true},
		{"rejected offer", "done", "mcp__ccd_turn__wrap_up", claudeWrapUpTestInput(), "Not shown: create_pr is unavailable.", true},
		{"failed tool", "error", "mcp__ccd_turn__wrap_up", claudeWrapUpTestInput(), "failed", false},
		{"running tool", "running", "mcp__ccd_turn__wrap_up", claudeWrapUpTestInput(), "", false},
		{"unrelated tool", "done", "OtherTool", claudeWrapUpTestInput(), "", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			card := claudeWrapUpCard(M{"id": "wrap", "kind": "tool", "tool": tc.tool, "status": tc.status, "detail": providerPretty(tc.input), "output": tc.output, "parent": "agent"})
			if (card != nil) != tc.card || len(providerRows(card["options"])) != 0 {
				t.Fatalf("An unsupported reply action was invented: %v", card)
			}
			if card != nil && S(card, "parent") != "agent" {
				t.Fatal("A subagent offer escaped into the parent conversation")
			}
		})
	}
}
