package host

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const claudeSkillTestBody = "Base directory for this skill: C:\\project\\.claude\\skills\\release-bbp\n\n# Release instructions\r\nPreserve the complete body.\r\n"

func TestClaudeInjectedSkillPreservesBodyAndRequiresProvenance(t *testing.T) {
	for _, meta := range []bool{false, true} {
		msg := M{"type": "user", "uuid": "skill-message", "isMeta": meta, "sourceToolUseID": "skill-call", "message": M{"content": []M{{"type": "text", "text": claudeSkillTestBody}}}}
		entries := claudeUserTextEntries(msg, true)
		wantText := claudeSkillTestBody
		if !meta {
			wantText = strings.TrimSpace(wantText)
		}
		if len(entries) != 1 || S(entries[0], "id") != "skill-message" || S(entries[0], "text") != wantText {
			t.Fatalf("Skill content or identity changed: %v", entries)
		}
		entry := entries[0]
		if meta {
			if S(entry, "kind") != "skill" || S(entry, "tool") != "Skill" || S(entry, "title") != "release-bbp" || S(entry, "sourceToolUseId") != "skill-call" {
				t.Fatalf("Injected metadata was not separated: %v", entry)
			}
		} else if S(entry, "kind") != "user" {
			t.Fatal("A user-typed prefix was classified without validated metadata/tool provenance")
		}
	}
	// Code or examples inside a skill must not be reinterpreted as peer messages or cleaned away.
	body := claudeSkillTestBody + "\n" + claudeTestResizeNotice + "\n<cross-session-message>example</cross-session-message>"
	entries := claudeUserTextEntries(M{"uuid": "skill", "isMeta": true, "message": M{"content": body}}, true)
	if len(entries) != 1 || S(entries[0], "kind") != "skill" || S(entries[0], "text") != body {
		t.Fatal("Skill instructions were interpreted as chat metadata")
	}
}

func TestClaudeLiveSkillUsesSuccessfulSourceToolWhenMetaIsMissing(t *testing.T) {
	for _, failed := range []bool{false, true} {
		store := providerTestStore(t)
		rec := store.Create(M{"provider": "claude"})
		sid := S(rec.Info, "id")
		p := NewProvider("claude", store, t.TempDir()).(*nativeProvider)
		r := &nativeSession{parents: map[string]string{}}
		p.claudeMessage(sid, r, M{"type": "assistant", "message": M{"id": "tool-message", "content": []M{{"type": "tool_use", "id": "skill-call", "name": "Skill", "input": M{"skill": "release-bbp", "args": "patch release"}}}}})
		p.claudeMessage(sid, r, M{"type": "user", "uuid": "skill-message", "message": M{"content": []M{
			{"type": "tool_result", "tool_use_id": "skill-call", "content": "Launching skill: release-bbp", "is_error": failed},
			{"type": "text", "text": claudeSkillTestBody},
		}}})
		entries := store.Get(sid).Entries
		if len(entries) != 2 || S(entries[0], "tool") != "Skill" || S(entries[0], "output") != "Launching skill: release-bbp" {
			t.Fatalf("Skill source tool was lost: %v", entries)
		}
		kind := "skill"
		if failed {
			kind = "user"
		}
		wantText := claudeSkillTestBody
		if S(entries[1], "kind") != kind || S(entries[1], "text") != wantText {
			t.Fatalf("Wrong live skill classification (failed %t): %v", failed, entries[1])
		}
		// Mere proximity to a successful Skill call does not prove a later message is injected.
		p.claudeMessage(sid, r, M{"type": "user", "uuid": "real-user", "message": M{"content": claudeSkillTestBody}})
		if S(store.Get(sid).Entries[2], "kind") != "user" {
			t.Fatal("A real user message following a Skill call was reclassified")
		}
	}
}

func TestClaudeSkillTranscriptRepairsLegacyBubbleAndRestoresSkippedMetadata(t *testing.T) {
	for _, hadLegacyBubble := range []bool{false, true} {
		store := providerTestStore(t)
		rec := store.Create(M{"provider": "claude", "externalId": "external"})
		sid := S(rec.Info, "id")
		store.Add(sid, M{"id": "skill-call", "kind": "tool", "tool": "Skill", "status": "done", "detail": providerPretty(M{"skill": "release-bbp"}), "output": "Launching skill: release-bbp", "ts": 100})
		if hadLegacyBubble {
			store.Add(sid, M{"id": "skill-message", "kind": "user", "title": "From PC", "text": claudeSkillTestBody, "ts": 200, "messageOrigin": "human"})
		}
		store.Add(sid, M{"id": "reply", "kind": "user", "text": "Continue", "ts": 300})
		store.Add(sid, M{"id": "final-0", "kind": "assistant", "text": "Done", "ts": 400})
		store.Update(sid, M{"preview": "Saved preview", "updatedAt": 500}, false)
		stamp := func(ms int64) string { return time.UnixMilli(ms).UTC().Format(time.RFC3339Nano) }
		rows := []M{
			{"type": "assistant", "uuid": "tool-uuid", "timestamp": stamp(100), "message": M{"id": "tool-message", "content": []M{{"type": "tool_use", "id": "skill-call", "name": "Skill", "input": M{"skill": "release-bbp"}}}}},
			{"type": "user", "uuid": "result-uuid", "parentUuid": "tool-uuid", "timestamp": stamp(150), "message": M{"content": []M{{"type": "tool_result", "tool_use_id": "skill-call", "content": "Launching skill: release-bbp"}}}},
			{"type": "user", "uuid": "skill-message", "parentUuid": "result-uuid", "isMeta": true, "turnCompanion": true, "sourceToolUseID": "skill-call", "timestamp": stamp(200), "message": M{"content": []M{{"type": "text", "text": claudeSkillTestBody}}}},
			{"type": "user", "uuid": "reply", "parentUuid": "skill-message", "timestamp": stamp(300), "message": M{"content": "Continue"}},
			{"type": "assistant", "uuid": "final-uuid", "parentUuid": "reply", "timestamp": stamp(400), "message": M{"id": "final", "content": []M{{"type": "text", "text": "Done"}}}},
		}
		path := filepath.Join(t.TempDir(), "conversation.jsonl")
		file, err := os.Create(path)
		if err != nil {
			t.Fatal(err)
		}
		for _, row := range rows {
			if err := json.NewEncoder(file).Encode(row); err != nil {
				file.Close()
				t.Fatal(err)
			}
		}
		if err := file.Close(); err != nil {
			t.Fatal(err)
		}
		events, cancel := store.Subscribe()
		for i := 0; i < 2; i++ {
			p := NewProvider("claude", store, t.TempDir()).(*nativeProvider)
			if err := p.readTranscript(rec, path); err != nil {
				t.Fatal(err)
			}
			got := store.Get(sid)
			if len(got.Entries) != 4 || S(got.Entries[1], "id") != "skill-message" || S(got.Entries[1], "kind") != "skill" || S(got.Entries[1], "text") != claudeSkillTestBody || N(got.Entries[1], "ts") != 200 || S(got.Entries[2], "id") != "reply" {
				t.Fatalf("Legacy/skipped skill body was not repaired in place: %v", got.Entries)
			}
			if S(got.Info, "preview") != "Saved preview" || N(got.Info, "updatedAt") != 500 {
				t.Fatal("Skill repair changed session activity/preview")
			}
		}
		select {
		case ev := <-events:
			if S(ev, "type") != "entries_removed" {
				t.Fatalf("Wrong skill repair event: %v", ev)
			}
		default:
			t.Fatal("Skill repair did not invalidate the timeline")
		}
		select {
		case ev := <-events:
			t.Fatalf("Skill repair emitted twice: %v", ev)
		default:
		}
		cancel()
	}
}

func TestClaudeLoadedSkillBubbleRepairsAutomaticallyAndRestoresChatPreview(t *testing.T) {
	for _, meta := range []bool{true, false} {
		config := t.TempDir()
		t.Setenv("CLAUDE_CONFIG_DIR", config)
		project := filepath.Join(config, "projects", "project")
		if err := os.MkdirAll(project, 0700); err != nil {
			t.Fatal(err)
		}
		row := M{"type": "user", "uuid": "skill-message", "isMeta": meta, "timestamp": time.UnixMilli(200).UTC().Format(time.RFC3339Nano), "message": M{"content": claudeSkillTestBody}}
		raw, err := json.Marshal(row)
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(project, "loaded.jsonl"), append(raw, '\n'), 0600); err != nil {
			t.Fatal(err)
		}
		store := providerTestStore(t)
		rec := store.Create(M{"provider": "claude", "externalId": "loaded"})
		sid := S(rec.Info, "id")
		store.Add(sid, M{"id": "earlier", "kind": "assistant", "text": "Actual chat reply", "ts": 100})
		oldText := strings.TrimSpace(claudeSkillTestBody)
		store.Add(sid, M{"id": "skill-message", "kind": "user", "title": "From PC", "text": oldText, "ts": 200})
		store.Update(sid, M{"preview": preview(oldText), "updatedAt": 500}, false)
		NewProvider("claude", store, t.TempDir())
		got := store.Get(sid)
		if len(got.Entries) != 2 || N(got.Info, "updatedAt") != 500 {
			t.Fatalf("Startup changed the session timeline/activity: %v", got)
		}
		if meta {
			if S(got.Entries[1], "kind") != "skill" || S(got.Entries[1], "text") != claudeSkillTestBody || S(got.Info, "preview") != "Actual chat reply" {
				t.Fatalf("Loaded injected skill was not normalized automatically: %v", got)
			}
		} else if S(got.Entries[1], "kind") != "user" || S(got.Info, "preview") != preview(oldText) {
			t.Fatal("Startup reclassified a real user message based only on a text prefix")
		}
	}
}
