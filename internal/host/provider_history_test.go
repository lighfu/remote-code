package host

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestProviderTranscriptWatcherOnlyEmitsNewRows(t *testing.T) {
	store := providerTestStore(t)
	rec := store.Create(M{"provider": "claude", "externalId": "external"})
	p := NewProvider("claude", store, t.TempDir()).(*nativeProvider)
	path := filepath.Join(t.TempDir(), "transcript.jsonl")
	write := func(rows []M, appendFile bool) {
		t.Helper()
		flags := os.O_CREATE | os.O_WRONLY | os.O_TRUNC
		if appendFile {
			flags = os.O_CREATE | os.O_WRONLY | os.O_APPEND
		}
		file, err := os.OpenFile(path, flags, 0600)
		if err != nil {
			t.Fatal(err)
		}
		for _, row := range rows {
			if err = json.NewEncoder(file).Encode(row); err != nil {
				t.Fatal(err)
			}
		}
		_ = file.Close()
	}
	write([]M{{"type": "user", "uuid": "user", "parentUuid": nil, "message": M{"content": "Hello"}}, {"type": "assistant", "uuid": "reply", "parentUuid": "user", "message": M{"id": "message", "content": []M{{"type": "text", "text": "Welcome"}}}}}, false)
	if err := p.readTranscript(rec, path); err != nil {
		t.Fatal(err)
	}
	events, stop := store.Subscribe()
	defer stop()
	if err := p.readTranscript(store.Get(S(rec.Info, "id")), path); err != nil {
		t.Fatal(err)
	}
	select {
	case event := <-events:
		t.Fatalf("Unchanged history emitted again: %v", event)
	default:
	}
	write([]M{{"type": "assistant", "uuid": "reply2", "parentUuid": "reply", "message": M{"id": "message2", "content": []M{{"type": "text", "text": "Next"}}}}}, true)
	if err := p.readTranscript(store.Get(S(rec.Info, "id")), path); err != nil {
		t.Fatal(err)
	}
	entries := 0
	for {
		select {
		case event := <-events:
			if S(event, "type") == "entry" {
				entries++
			}
		default:
			if entries != 1 {
				t.Fatalf("Expected one new entry, got %d", entries)
			}
			return
		}
	}
}

func TestClaudeTranscriptRejoinPreservesExistingTimelineAndCatchesUp(t *testing.T) {
	store := providerTestStore(t)
	rec := store.Create(M{"provider": "claude", "externalId": "external"})
	sid := S(rec.Info, "id")
	savedAt := nowMS()
	store.Add(sid, M{"id": "first:0", "kind": "user", "text": "Original prompt", "ts": savedAt - 1})
	store.Add(sid, M{"id": "message-0", "kind": "assistant", "text": "Original reply", "ts": savedAt})
	store.Update(sid, M{"updatedAt": savedAt, "preview": "Saved preview"}, false)

	stamp := savedAt - time.Minute.Milliseconds()
	old := time.UnixMilli(stamp - time.Minute.Milliseconds()).UTC().Format(time.RFC3339Nano)
	appended := time.UnixMilli(stamp).UTC().Format(time.RFC3339Nano)
	path := filepath.Join(t.TempDir(), "transcript.jsonl")
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	enc := json.NewEncoder(file)
	for _, row := range []M{
		{"type": "user", "uuid": "first", "timestamp": old, "message": M{"content": "Original prompt"}},
		{"type": "assistant", "uuid": "reply", "parentUuid": "first", "timestamp": old, "message": M{"id": "message", "content": []M{{"type": "text", "text": "Original reply"}}}},
		{"type": "user", "uuid": "next", "parentUuid": "reply", "timestamp": appended, "message": M{"content": "Next prompt\n<system-reminder>private metadata</system-reminder>"}},
		{"type": "assistant", "uuid": "thought", "parentUuid": "next", "timestamp": appended, "message": M{"id": "repeated", "content": []M{{"type": "thinking", "thinking": "Thinking"}}}},
		{"type": "assistant", "uuid": "answer", "parentUuid": "thought", "timestamp": appended, "message": M{"id": "repeated", "content": []M{{"type": "text", "text": "New answer"}}}},
		{"type": "user", "uuid": "command", "parentUuid": "answer", "timestamp": appended, "message": M{"content": "<local-command-stdout>Command output</local-command-stdout>"}},
		{"type": "user", "uuid": "notice", "parentUuid": "command", "timestamp": appended, "message": M{"content": "<system-reminder>hidden text</system-reminder>"}},
	} {
		if err := enc.Encode(row); err != nil {
			_ = file.Close()
			t.Fatal(err)
		}
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}

	events, stop := store.Subscribe()
	defer stop()
	for replay := 0; replay < 2; replay++ {
		p := NewProvider("claude", store, t.TempDir()).(*nativeProvider)
		if err := p.readTranscript(rec, path); err != nil {
			t.Fatal(err)
		}
		current := store.Get(sid)
		if N(current.Info, "updatedAt") != float64(savedAt) || S(current.Info, "preview") != "Saved preview" {
			t.Fatalf("History changed saved preview or activity time: %v", current.Info)
		}
		want := []struct {
			id, kind, text string
			ts             int64
		}{
			{"first:0", "user", "Original prompt", savedAt - 1},
			{"message-0", "assistant", "Original reply", savedAt},
			{"next", "user", "Next prompt", stamp},
			{"repeated-0", "thinking", "Thinking", stamp},
			{"repeated-1", "assistant", "New answer", stamp},
			{"command", "system", "Command output", stamp},
		}
		if len(current.Entries) != len(want) {
			t.Fatalf("Stored entries = %d, want %d", len(current.Entries), len(want))
		}
		for i, expected := range want {
			entry := current.Entries[i]
			if S(entry, "id") != expected.id || S(entry, "kind") != expected.kind || S(entry, "text") != expected.text || N(entry, "ts") != float64(expected.ts) {
				t.Fatalf("Unexpected replay row %d: %v", i, entry)
			}
		}
		for i := 2; replay == 0 && i < len(want); i++ {
			select {
			case event := <-events:
				if S(event, "type") != "entry" || S(providerMap(event["entry"]), "id") != want[i].id {
					t.Fatalf("Unexpected replay event: %v", event)
				}
			case <-time.After(time.Second):
				t.Fatal("Missing catch-up event")
			}
		}
		select {
		case event := <-events:
			t.Fatalf("Replayed an existing entry: %v", event)
		default:
		}
	}
}
