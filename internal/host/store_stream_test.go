package host

import (
	"strings"
	"testing"
	"time"
	"unicode/utf8"
)

func streamStoreFixture(t *testing.T) (*Store, string, string) {
	t.Helper()
	s, e := OpenStore(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() {
		if e := s.Close(); e != nil {
			t.Error(e)
		}
	})
	rec := s.Create(M{"provider": "claude"})
	id := S(rec.Info, "id")
	entry := s.Add(id, M{"kind": "assistant", "streaming": true, "text": "A"})
	return s, id, S(entry, "id")
}
func nextStreamEvent(t *testing.T, ch <-chan M) M {
	t.Helper()
	select {
	case event, ok := <-ch:
		if !ok {
			t.Fatal("subscriber unexpectedly closed")
		}
		return event
	case <-time.After(time.Second):
		t.Fatal("stream event timed out")
		return nil
	}
}
func assertNoStreamEvent(t *testing.T, ch <-chan M, d time.Duration) {
	t.Helper()
	select {
	case event := <-ch:
		t.Fatalf("unexpected event: %v", event)
	case <-time.After(d):
	}
}

func TestStoreBatchesDeltasAndFlushesBeforeReplacement(t *testing.T) {
	s, id, entryID := streamStoreFixture(t)
	events, unsubscribe := s.Subscribe()
	defer unsubscribe()
	s.Delta(id, entryID, "text", "B")
	s.Delta(id, entryID, "text", "C")
	s.Delta(id, entryID, "output", "one")
	s.Delta(id, entryID, "output", "two")
	s.Patch(id, entryID, M{"streaming": false})
	first := nextStreamEvent(t, events)
	second := nextStreamEvent(t, events)
	replacement := nextStreamEvent(t, events)
	if S(first, "type") != "delta" || S(first, "text") != "BC" || S(first, "field") != "text" {
		t.Fatalf("wrong first delta: %v", first)
	}
	if S(second, "type") != "delta" || S(second, "text") != "onetwo" || S(second, "field") != "output" {
		t.Fatalf("wrong output delta: %v", second)
	}
	if S(replacement, "type") != "entry" {
		t.Fatalf("replacement came before deltas: %v", replacement)
	}
	full := replacement["entry"].(map[string]any)
	if S(full, "text") != "ABC" || S(full, "output") != "onetwo" {
		t.Fatalf("replacement missing deltas: %v", full)
	}
}

func TestStoreDeltaTimerCoalescesTinyChunks(t *testing.T) {
	s, id, entryID := streamStoreFixture(t)
	events, cancel := s.Subscribe()
	defer cancel()
	for i := 0; i < 25; i++ {
		s.Delta(id, entryID, "text", "x")
	}
	event := nextStreamEvent(t, events)
	if S(event, "type") != "delta" || S(event, "text") != strings.Repeat("x", 25) {
		t.Fatalf("unbatched chunks: %v", event)
	}
	assertNoStreamEvent(t, events, storeDeltaInterval*2)
	if S(s.Get(id).Entries[0], "text") != "A"+strings.Repeat("x", 25) {
		t.Fatal("batched send changed in-memory content")
	}
}

func TestStoreClipsOutputAndCoalescesResetEntries(t *testing.T) {
	s, id, entryID := streamStoreFixture(t)
	s.Patch(id, entryID, M{"output": strings.Repeat("0", storeMaxOutput-1)})
	events, cancel := s.Subscribe()
	defer cancel()
	s.Delta(id, entryID, "output", "A")
	s.Delta(id, entryID, "output", "BC")
	for i := 0; i < 25; i++ {
		s.Delta(id, entryID, "output", "x")
	}
	delta := nextStreamEvent(t, events)
	if S(delta, "type") != "delta" || S(delta, "text") != "A" {
		t.Fatalf("prior delta not flushed first: %v", delta)
	}
	suffix := "ABC" + strings.Repeat("x", 25)
	for {
		event := nextStreamEvent(t, events)
		if S(event, "type") != "entry" {
			t.Fatalf("clipping must replace entry: %v", event)
		}
		output := S(providerMap(event["entry"]), "output")
		if !strings.HasPrefix(output, storeTruncatedPrefix) || len(output) > storeMaxOutput+len(storeTruncatedPrefix) {
			t.Fatalf("bad clipped output length %d", len(output))
		}
		// On a slow host the batch timer can also emit while the chunks arrive.
		if strings.HasSuffix(output, suffix) {
			break
		}
	}
	assertNoStreamEvent(t, events, storeDeltaInterval*2)
}

func TestStoreClippingPreservesUnicodeAndAllEntryPaths(t *testing.T) {
	s, id, entryID := streamStoreFixture(t)
	s.Patch(id, entryID, M{"text": strings.Repeat("😀", storeMaxText), "output": strings.Repeat("字", storeMaxOutput+20)})
	entry := s.Get(id).Entries[0]
	if text := S(entry, "text"); !utf8.ValidString(text) || strings.ContainsRune(text, utf8.RuneError) || strings.Count(text, "😀") != storeMaxText/2 {
		t.Fatal("UTF-16 clipping split an emoji")
	}
	if strings.Count(S(entry, "output"), "字") != storeMaxOutput {
		t.Fatal("UTF-16 clipping counted UTF-8 bytes")
	}
	added := s.Add(id, M{"kind": "tool", "output": strings.Repeat("x", storeMaxOutput+1)})
	if len(S(added, "output")) > storeMaxOutput+len(storeTruncatedPrefix) {
		t.Fatal("Add did not cap output")
	}
	s.ReplaceEntries(id, []M{{"id": "replacement", "text": strings.Repeat("y", storeMaxText+1)}})
	if len(S(s.Get(id).Entries[0], "text")) > storeMaxText+len(storeTruncatedPrefix) {
		t.Fatal("ReplaceEntries did not cap text")
	}
}

func TestStoreFlushesPendingBeforeTruncationAndRemoval(t *testing.T) {
	for _, operation := range []string{"truncate", "replace", "remove"} {
		t.Run(operation, func(t *testing.T) {
			s, id, entryID := streamStoreFixture(t)
			events, cancel := s.Subscribe()
			defer cancel()
			s.Delta(id, entryID, "text", "pending")
			switch operation {
			case "truncate":
				s.TruncateFrom(id, entryID)
			case "replace":
				s.ReplaceEntries(id, nil)
			case "remove":
				if e := s.Remove(id); e != nil {
					t.Fatal(e)
				}
			}
			delta := nextStreamEvent(t, events)
			removed := nextStreamEvent(t, events)
			if S(delta, "type") != "delta" || S(delta, "text") != "pending" {
				t.Fatalf("missing prior delta: %v", delta)
			}
			expected := "entries_removed"
			if operation == "remove" {
				expected = "session_removed"
			}
			if S(removed, "type") != expected {
				t.Fatalf("wrong removal event: %v", removed)
			}
			assertNoStreamEvent(t, events, storeDeltaInterval*2)
		})
	}
}

func TestStoreSubscribeSnapshotDoesNotReplayBufferedDeltas(t *testing.T) {
	s, id, entryID := streamStoreFixture(t)
	existing, done := s.Subscribe()
	defer done()
	s.Delta(id, entryID, "text", "before")
	events, snapshot, cancel := s.SubscribeSnapshot()
	defer cancel()
	if len(snapshot) != 1 || S(snapshot[0], "id") != id {
		t.Fatal("hello snapshot missing session")
	}
	prior := nextStreamEvent(t, existing)
	if S(prior, "text") != "before" {
		t.Fatal("existing subscriber lost pending text")
	}
	select {
	case event := <-events:
		t.Fatalf("new subscriber received pre-snapshot delta: %v", event)
	default:
	}
	s.Delta(id, entryID, "text", "after")
	fresh := nextStreamEvent(t, events)
	if S(fresh, "text") != "after" {
		t.Fatalf("pre-snapshot delta appended twice: %v", fresh)
	}
	if S(s.Get(id).Entries[0], "text") != "Abeforeafter" {
		t.Fatal("snapshot altered stored content")
	}
}

func TestStoreCloseFlushesPendingThenClosesSubscribers(t *testing.T) {
	s, id, entryID := streamStoreFixture(t)
	events, cancel := s.Subscribe()
	defer cancel()
	s.Delta(id, entryID, "text", "final")
	if e := s.Close(); e != nil {
		t.Fatal(e)
	}
	event, ok := <-events
	if !ok || S(event, "type") != "delta" || S(event, "text") != "final" {
		t.Fatalf("close lost final delta: %v", event)
	}
	if _, ok = <-events; ok {
		t.Fatal("subscriber not closed")
	}
	if e := s.Close(); e != nil {
		t.Fatal(e)
	}
}

func TestTrimTimelineKeepsChatAndItsAncestorsWithinTheCap(t *testing.T) {
	entries := []M{
		{"id": "agent", "kind": "tool", "tool": "Agent"},
		{"id": "user", "kind": "user"},
		{"id": "old-tool", "kind": "tool"},
		{"id": "reply", "kind": "assistant", "parent": "agent"},
	}
	for range storeMaxEntries - 3 {
		entries = append(entries, M{"kind": "thinking"})
	}
	entries = append(entries, M{"id": "new-approval", "kind": "approval", "status": "pending"})

	trimmed := trimTimeline(entries, storeMaxEntries)
	if len(trimmed) != storeMaxEntries {
		t.Fatalf("trimmed %d entries, want %d", len(trimmed), storeMaxEntries)
	}
	for i, id := range []string{"agent", "user", "reply"} {
		if S(trimmed[i], "id") != id {
			t.Fatalf("entry %d = %v, want %s", i, trimmed[i], id)
		}
	}
	if S(trimmed[len(trimmed)-1], "id") != "new-approval" {
		t.Fatal("trimming dropped a new approval")
	}
}
