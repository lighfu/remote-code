package host

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func claudeIdentityFixtureRows(text string) []M {
	return []M{
		{"type": "assistant", "uuid": "empty-row", "message": M{"id": "api-message", "content": []M{{"type": "thinking", "thinking": ""}}}},
		{"type": "assistant", "uuid": "narration-row", "parentUuid": "empty-row", "message": M{"id": "api-message", "content": []M{{"type": "thinking", "thinking": text}}}},
		{"type": "assistant", "uuid": "tool-row", "parentUuid": "narration-row", "message": M{"id": "api-message", "content": []M{{"type": "tool_use", "id": "tool", "name": "Agent", "input": M{"description": "Read the current state"}}}}},
	}
}

func claudeIdentityFixtureFile(t *testing.T, rows []M) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "transcript.jsonl")
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
	return path
}

func TestClaudeSplitAssistantEnvelopesMatchStreamBlockIndexes(t *testing.T) {
	s := providerTestStore(t)
	p := NewProvider("claude", s, t.TempDir()).(*nativeProvider)
	record := s.Create(M{"provider": "claude"})
	sid := S(record.Info, "id")
	r := &nativeSession{}
	send := func(event M) { p.claudeMessage(sid, r, M{"type": "stream_event", "event": event}) }
	send(M{"type": "message_start", "message": M{"id": "api-message"}})
	send(M{"type": "content_block_start", "index": 0, "content_block": M{"type": "thinking", "thinking": ""}})
	send(M{"type": "content_block_stop", "index": 0})
	rows := claudeIdentityFixtureRows("The change is pushed; reading the current state.")
	p.claudeMessage(sid, r, rows[0])
	send(M{"type": "content_block_start", "index": 1, "content_block": M{"type": "thinking", "thinking": ""}})
	send(M{"type": "content_block_delta", "index": 1, "delta": M{"type": "thinking_delta", "thinking": "The change is pushed; reading the current state."}})
	send(M{"type": "content_block_stop", "index": 1})
	p.claudeMessage(sid, r, rows[1])
	send(M{"type": "content_block_start", "index": 2, "content_block": providerRows(providerMap(rows[2]["message"])["content"])[0]})
	p.claudeMessage(sid, r, rows[2])
	// A redelivered completed envelope is the same block, not the next block.
	p.claudeMessage(sid, r, rows[1])
	entries := s.Get(sid).Entries
	if len(entries) != 3 || S(entries[0], "id") != "api-message-0" || S(entries[0], "text") != "" || S(entries[1], "id") != "api-message-1" || S(entries[1], "text") != "The change is pushed; reading the current state." || S(entries[2], "id") != "tool" {
		t.Fatalf("stream completion duplicated or overwrote another block: %v", entries)
	}
	path := claudeIdentityFixtureFile(t, rows)
	for range 2 {
		if err := p.readTranscript(record, path); err != nil {
			t.Fatal(err)
		}
		if got := s.Get(sid).Entries; len(got) != 3 || S(got[1], "id") != "api-message-1" {
			t.Fatalf("transcript replay duplicated the live blocks: %v", got)
		}
	}
}

func TestClaudeLateCompletionUsesItsOwnMessageStream(t *testing.T) {
	s := providerTestStore(t)
	p := NewProvider("claude", s, t.TempDir()).(*nativeProvider)
	record := s.Create(M{"provider": "claude"})
	sid := S(record.Info, "id")
	r := &nativeSession{}
	for _, message := range []string{"previous", "current"} {
		p.claudeMessage(sid, r, M{"type": "stream_event", "event": M{"type": "message_start", "message": M{"id": message}}})
		p.claudeMessage(sid, r, M{"type": "stream_event", "event": M{"type": "content_block_start", "index": 0, "content_block": M{"type": "text", "text": message}}})
	}
	p.claudeMessage(sid, r, M{"type": "assistant", "uuid": "previous-row", "message": M{"id": "previous", "content": []M{{"type": "text", "text": "Previous complete"}}}})
	entries := s.Get(sid).Entries
	if len(entries) != 2 || S(entries[0], "text") != "Previous complete" || S(entries[1], "text") != "current" || !B(entries[1], "streaming") {
		t.Fatalf("late completion overwrote the newer stream: %v", entries)
	}
}

func TestClaudeTranscriptRepairsOnlyConfirmedDuplicateIdentities(t *testing.T) {
	s := providerTestStore(t)
	p := NewProvider("claude", s, t.TempDir()).(*nativeProvider)
	record := s.Create(M{"provider": "claude"})
	sid := S(record.Info, "id")
	text := "Same narration retained in its real block."
	for _, entry := range []M{
		{"id": "api-message-0", "kind": "thinking", "text": text, "streaming": false, "ts": 10},
		{"id": "api-message-1", "kind": "thinking", "text": text, "streaming": false, "ts": 11},
		{"id": "tool", "kind": "tool", "tool": "Agent", "status": "done", "output": "Keep this output", "ts": 12},
		{"id": "narration-row:0", "kind": "thinking", "text": text, "streaming": false, "ts": 13},
		{"id": "other-api-0", "kind": "thinking", "text": text, "ts": 14},
		{"id": "manual", "kind": "thinking", "text": text, "ts": 15},
	} {
		s.Add(sid, entry)
	}
	s.Update(sid, M{"preview": "Retain the preview", "updatedAt": 20}, false)
	path := claudeIdentityFixtureFile(t, claudeIdentityFixtureRows(text))
	events, stop := s.Subscribe()
	defer stop()
	for range 2 {
		if err := p.readTranscript(record, path); err != nil {
			t.Fatal(err)
		}
		got := s.Get(sid)
		if len(got.Entries) != 5 || S(got.Entries[0], "id") != "api-message-0" || S(got.Entries[0], "text") != "" || S(got.Entries[1], "id") != "api-message-1" || S(got.Entries[1], "text") != text || S(got.Entries[2], "output") != "Keep this output" || S(got.Entries[3], "id") != "other-api-0" || S(got.Entries[4], "id") != "manual" {
			t.Fatalf("identity repair changed distinct messages or missed duplicates: %v", got.Entries)
		}
		if S(got.Info, "preview") != "Retain the preview" || N(got.Info, "updatedAt") != 20 {
			t.Fatalf("historical repair changed session activity: %v", got.Info)
		}
	}
	select {
	case event := <-events:
		if S(event, "type") != "entries_removed" {
			t.Fatalf("repair broadcast old content as new live notifications: %v", event)
		}
	default:
		t.Fatal("repair did not invalidate the old timeline")
	}
	select {
	case event := <-events:
		t.Fatalf("idempotent repair emitted another event: %v", event)
	default:
	}
}

func TestClaudeIdenticalRealBlocksAndEditedEntriesRemainSeparate(t *testing.T) {
	s := providerTestStore(t)
	p := NewProvider("claude", s, t.TempDir()).(*nativeProvider)
	record := s.Create(M{"provider": "claude"})
	sid := S(record.Info, "id")
	rows := []M{
		{"type": "assistant", "uuid": "first-row", "message": M{"id": "same-api", "content": []M{{"type": "thinking", "thinking": "Repeated on purpose"}}}},
		{"type": "assistant", "uuid": "second-row", "parentUuid": "first-row", "message": M{"id": "same-api", "content": []M{{"type": "thinking", "thinking": "Repeated on purpose"}}}},
	}
	path := claudeIdentityFixtureFile(t, rows)
	if err := p.readTranscript(record, path); err != nil {
		t.Fatal(err)
	}
	if got := s.Get(sid).Entries; len(got) != 2 || S(got[0], "id") != "same-api-0" || S(got[1], "id") != "same-api-1" {
		t.Fatalf("identical real content blocks were merged: %v", got)
	}
	s.Patch(sid, "same-api-0", M{"text": "Locally changed content"})
	if err := p.readTranscript(record, path); err != nil {
		t.Fatal(err)
	}
	if got := s.Get(sid).Entries; len(got) != 2 || S(got[0], "text") != "Locally changed content" {
		t.Fatalf("unconfirmed local content was overwritten: %v", got)
	}
}
