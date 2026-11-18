package host

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

const claudeTestResizeNotice = "[Image: original 1560x2280, displayed at 1368x2000. Multiply coordinates by 1.14 to map to original image.]"
const claudeTestPNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/Jn8AAAAASUVORK5CYII="

func TestClaudePCUserMessagePreservesTextAndAttachments(t *testing.T) {
	s := providerTestStore(t)
	data := t.TempDir()
	p := NewProvider("claude", s, data).(*nativeProvider)
	record := s.Create(M{"provider": "claude"})
	sid := S(record.Info, "id")
	s.Add(sid, M{"id": "tool", "kind": "tool", "status": "running"})
	image := M{"type": "image", "source": M{"type": "base64", "media_type": "image/png", "data": claudeTestPNG}}
	p.claudeMessage(sid, &nativeSession{remote: true}, M{"type": "user", "uuid": "message", "message": M{"content": []M{
		{"type": "text", "text": claudeTestResizeNotice}, image,
		{"type": "text", "text": "First actual message"},
		{"type": "tool_result", "tool_use_id": "tool", "content": "Tool result"},
		{"type": "text", "text": "Second actual message"},
	}}})
	p.claudeMessage(sid, &nativeSession{remote: true}, M{"type": "user", "uuid": "resize", "message": M{"content": claudeTestResizeNotice}})
	p.claudeMessage(sid, &nativeSession{remote: true}, M{"type": "user", "uuid": "image-only", "message": M{"content": []M{image}}})
	entries := s.Get(sid).Entries
	if len(entries) != 4 {
		t.Fatalf("got %d entries, want updated tool and three user entries", len(entries))
	}
	if S(entries[0], "status") != "done" || S(entries[0], "output") != "Tool result" {
		t.Fatalf("tool result was not applied: %v", entries[0])
	}
	if S(entries[1], "id") != "message" || S(entries[1], "text") != "First actual message" || len(array(entries[1]["media"])) != 1 {
		t.Fatalf("first entry lost text, identity or image: %v", entries[1])
	}
	if S(entries[2], "id") != "message:4" || S(entries[2], "text") != "Second actual message" || len(array(entries[2]["media"])) != 0 {
		t.Fatalf("second entry was merged or repeated its attachment: %v", entries[2])
	}
	if S(entries[3], "id") != "image-only" || S(entries[3], "text") != "" || len(array(entries[3]["media"])) != 1 {
		t.Fatalf("image-only message was dropped: %v", entries[3])
	}
}

func TestClaudeStoredHistoryHidesImageMetadataAndRetainsAttachments(t *testing.T) {
	rows := []M{
		{"id": "metadata", "kind": "user", "title": "From PC", "text": claudeTestResizeNotice},
		{"id": "attachment", "kind": "user", "title": "From PC", "text": claudeTestResizeNotice, "media": []string{"photo"}},
		{"id": "actual", "kind": "user", "title": "From PC", "text": "Keep actual text\n" + claudeTestResizeNotice},
		{"id": "manual", "kind": "user", "text": claudeTestResizeNotice},
	}
	got := claudeStoredTimeline(rows)
	if len(got) != 3 || S(got[0], "id") != "attachment" || S(got[0], "text") != "" || !claudeHasAttachments(got[0]) ||
		S(got[1], "text") != "Keep actual text" || S(got[2], "id") != "manual" || S(got[2], "text") != claudeTestResizeNotice {
		t.Fatalf("visible history = %v", got)
	}
	if S(rows[1], "text") != claudeTestResizeNotice {
		t.Fatal("legacy cleanup changed the stored record")
	}
}

func TestClaudeRejoinRepairsExactlyMatchedCollapsedUserRows(t *testing.T) {
	s := providerTestStore(t)
	record := s.Create(M{"provider": "claude"})
	sid := S(record.Info, "id")
	s.Add(sid, M{"id": "old", "kind": "user", "title": "From PC", "text": "One\nTwo", "ts": 120, "media": []string{"photo"}})
	s.Add(sid, M{"id": "with-image", "kind": "user", "title": "From PC", "text": "Keep the photo", "ts": 125})
	s.Add(sid, M{"id": "edited", "kind": "user", "title": "From PC", "text": "edited text", "ts": 130})
	s.Add(sid, M{"id": "last", "kind": "assistant", "text": "reply", "ts": 150})
	path := filepath.Join(t.TempDir(), "conversation.jsonl")
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	encoder := json.NewEncoder(file)
	image := M{"type": "image", "source": M{"type": "base64", "media_type": "image/png", "data": claudeTestPNG}}
	for _, msg := range []M{
		{"uuid": "old", "type": "user", "message": M{"content": []M{{"type": "text", "text": "One"}, {"type": "text", "text": "Two"}}}},
		{"uuid": "with-image", "parentUuid": "old", "type": "user", "message": M{"content": []M{image, {"type": "text", "text": "Keep the photo"}}}},
		{"uuid": "edited", "parentUuid": "with-image", "type": "user", "message": M{"content": []M{image, {"type": "text", "text": "before"}, {"type": "text", "text": "after"}}}},
	} {
		if err := encoder.Encode(msg); err != nil {
			_ = file.Close()
			t.Fatal(err)
		}
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	events, stop := s.Subscribe()
	defer stop()
	for i := 0; i < 2; i++ {
		p := NewProvider("claude", s, t.TempDir()).(*nativeProvider)
		if err := p.readTranscript(record, path); err != nil {
			t.Fatal(err)
		}
		got := s.Get(sid).Entries
		if len(got) != 5 || S(got[0], "id") != "old" || S(got[0], "text") != "One" || !claudeHasAttachments(got[0]) ||
			S(got[1], "id") != "old:1" || S(got[1], "text") != "Two" || N(got[1], "ts") != 120 || claudeHasAttachments(got[1]) ||
			S(got[2], "id") != "with-image" || N(got[2], "ts") != 125 || len(array(got[2]["media"])) != 1 ||
			S(got[3], "text") != "edited text" || claudeHasAttachments(got[3]) || S(got[4], "id") != "last" {
			t.Fatalf("split timeline = %v", got)
		}
	}
	select {
	case ev := <-events:
		if S(ev, "type") != "entries_removed" || S(ev, "sessionId") != sid {
			t.Fatalf("repair must invalidate the timeline once: %v", ev)
		}
	default:
		t.Fatal("repair did not invalidate the timeline")
	}
	select {
	case ev := <-events:
		t.Fatalf("repair sent more than one event: %v", ev)
	default:
	}
}
