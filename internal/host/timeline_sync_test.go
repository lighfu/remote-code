package host

import (
	"encoding/json"
	"net/http"
	"testing"
	"time"
)

func TestTimelineSnapshotIncludesPendingDeltaRevision(t *testing.T) {
	a := testApp(t)
	sid := S(a.Store.Create(M{"provider": "codex"}).Info, "id")
	events, unsubscribe := a.Store.Subscribe()
	defer unsubscribe()
	next := func() M {
		t.Helper()
		deadline := time.After(time.Second)
		for {
			select {
			case event := <-events:
				if N(event, "timelineRevision") > 0 {
					return event
				}
			case <-deadline:
				t.Fatal("missing timeline event")
			}
		}
	}
	a.Store.Add(sid, M{"id": "reply", "kind": "assistant", "text": "Started", "streaming": true})
	start := next()
	a.Store.Delta(sid, "reply", "text", " streaming")
	res := testRequest(a, http.MethodGet, "/api/sessions/"+sid, "")
	var snapshot M
	if res.Code != http.StatusOK || json.Unmarshal(res.Body.Bytes(), &snapshot) != nil {
		t.Fatal(res.Code, res.Body)
	}
	delta := next()
	if S(delta, "type") != "delta" || N(delta, "timelineRevision") <= N(start, "timelineRevision") || N(delta, "timelineRevision") != N(snapshot, "timelineRevision") {
		t.Fatalf("delta and snapshot revisions differ: %v %v", delta, snapshot)
	}
	if entries := maps(snapshot["entries"]); len(entries) != 1 || S(entries[0], "text") != "Started streaming" {
		t.Fatal(entries)
	}
	a.Store.Delta(sid, "reply", "text", " further")
	a.Store.Add(sid, M{"id": "reply", "kind": "assistant", "streaming": false})
	newDelta, final := next(), next()
	if S(newDelta, "type") != "delta" || S(final, "type") != "entry" || N(final, "timelineRevision") <= N(newDelta, "timelineRevision") || S(providerMap(final["entry"]), "text") != "Started streaming further" {
		t.Fatalf("full update overtook its pending delta: %v %v", newDelta, final)
	}
}
