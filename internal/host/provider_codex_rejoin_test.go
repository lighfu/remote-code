package host

import (
	"context"
	"reflect"
	"slices"
	"testing"
)

func TestCodexRejoinDoesNotImportSavedHistory(t *testing.T) {
	for _, tc := range []struct {
		name    string
		shared  bool
		loaded  []any
		joined  bool
		methods []string
	}{
		{name: "private server"},
		{name: "unloaded shared thread", shared: true, loaded: []any{"other"}, methods: []string{"thread/loaded/list"}},
		{name: "loaded shared thread", shared: true, loaded: []any{"thread"}, joined: true, methods: []string{"thread/loaded/list", "thread/resume"}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			store := providerTestStore(t)
			p := NewProvider("codex", store, t.TempDir()).(*nativeProvider)
			defer p.Close()
			rec := store.Create(M{"provider": "codex", "externalId": "thread"})
			sid := S(rec.Info, "id")
			store.Add(sid, M{"id": "existing", "kind": "assistant", "ts": 100, "text": "Saved reply"})
			before := store.Get(sid)
			var methods []string
			w := &providerWire{
				shared:    tc.shared,
				waiting:   map[string]chan providerReply{},
				done:      make(chan struct{}),
				closeFunc: func() error { return nil },
			}
			w.sendFunc = func(m M) error {
				method := S(m, "method")
				methods = append(methods, method)
				var result M
				switch method {
				case "thread/loaded/list":
					result = M{"data": tc.loaded}
				case "thread/resume":
					result = M{"thread": M{"id": "thread"}}
				default:
					t.Fatalf("unexpected history or runtime request %q", method)
				}
				w.receive(M{"id": m["id"], "result": result})
				return nil
			}
			p.rpc = w

			joined, err := p.Do(context.Background(), "rejoin", before, nil)
			if err != nil || joined != tc.joined || !slices.Equal(methods, tc.methods) {
				t.Fatalf("rejoin = %v, %v; requests = %v", joined, err, methods)
			}
			after := store.Get(sid)
			if !reflect.DeepEqual(before.Entries, after.Entries) || N(before.Info, "updatedAt") != N(after.Info, "updatedAt") {
				t.Fatal("reconnecting rewrote saved history or changed session order")
			}
		})
	}
}
