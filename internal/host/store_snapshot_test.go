package host

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
)

func TestStoreSnapshotParityAndIsolation(t *testing.T) {
	r := &Record{
		Info:    M{"pending": 1, "context": M{"limit": int64(123), "missing": []string(nil)}},
		Entries: []M{{"parts": []any{M{"text": "original"}, nil}, "media": []string{"img"}, "empty": []M{}}},
	}
	got := snapshotRecord(r)
	if !reflect.DeepEqual(got, clone(r)) {
		t.Fatal("snapshot differs from JSON record")
	}
	got.Info["context"].(M)["limit"] = 0
	got.Entries[0]["parts"].([]any)[0].(M)["text"] = "changed"
	got.Entries[0]["media"].([]any)[0] = "changed"
	if N(r.Info["context"].(M), "limit") != 123 || r.Entries[0]["parts"].([]any)[0].(M)["text"] != "original" || r.Entries[0]["media"].([]string)[0] != "img" {
		t.Fatal("snapshot shares mutable data")
	}
}

func TestStoreFlushDuringUpdatesAndRemoval(t *testing.T) {
	dir := t.TempDir()
	s, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	s.Create(M{"id": "keep"})
	s.Create(M{"id": "remove"})
	var wg sync.WaitGroup
	for range 2 {
		wg.Go(func() {
			for range 8 {
				if err := s.Flush(); err != nil {
					t.Error(err)
					return
				}
			}
		})
	}
	for i := range 30 {
		s.Update("keep", M{"n": i}, false)
	}
	if err := s.Remove("remove"); err != nil {
		t.Fatal(err)
	}
	wg.Wait()
	if err := s.Flush(); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(filepath.Join(dir, "keep.json"))
	if err != nil {
		t.Fatal(err)
	}
	var rec Record
	if err := json.Unmarshal(raw, &rec); err != nil || N(rec.Info, "n") != 29 {
		t.Fatal("last update was not persisted")
	}
	if _, err := os.Stat(filepath.Join(dir, "remove.json")); !os.IsNotExist(err) {
		t.Fatal("removed session reappeared on disk")
	}
}

func BenchmarkSessionMetadataLookup(b *testing.B) {
	record := &Record{Info: M{"id": "session", "cwd": "work"}, Entries: make([]M, 2000)}
	text := strings.Repeat("x", 4096)
	for i := range record.Entries {
		record.Entries[i] = M{"text": text, "ts": i}
	}
	store := &Store{records: map[string]*Record{"session": record}}
	b.Run("full-json-copy", func(b *testing.B) {
		for b.Loop() {
			_ = clone(record).Info["cwd"]
		}
	})
	b.Run("metadata", func(b *testing.B) {
		for b.Loop() {
			_ = store.infoRecord("session").Info["cwd"]
		}
	})
}
