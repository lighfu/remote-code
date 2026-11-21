package host

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"slices"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestDesktopLiveDragRoutes(t *testing.T) {
	var actions []string
	mux := http.NewServeMux()
	closeInput := registerDesktop(mux, desktopBackend{supported: true, input: func(input desktopInput) error {
		actions = append(actions, input.Type)
		if input.Layout != "layout" {
			return errDesktopDisplayChanged
		}
		return nil
	}})
	t.Cleanup(closeInput)
	send := func(layout, fields string, status int) string {
		t.Helper()
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, httptest.NewRequest("POST", "/api/desktop/input", strings.NewReader(
			`{"display":"all","layout":"`+layout+`",`+fields+`}`)))
		if w.Code != status {
			t.Fatalf("%s: status %d, want %d: %s", fields, w.Code, status, w.Body.String())
		}
		return w.Header().Get("X-Remote-Code-Desktop-Cursor-Revision")
	}
	const start = `"type":"cursorDragStart","dragId":"active"`
	const move = `"type":"cursorDragMove","dragId":"active","x":0.1,"y":-0.2`
	const end = `"type":"cursorDragEnd","dragId":"active"`
	send("layout", `"type":"cursorDragStart"`, 400)
	send("layout", move, 410)
	startRevision := send("layout", start, 204)
	if rev := send("layout", `"type":"cursorDragMove","dragId":"active","x":0,"y":0`, 204); rev != startRevision {
		t.Fatal("heartbeat changed the cursor revision")
	}
	send("layout", `"type":"cursorDragMove","dragId":"other","x":0.1,"y":-0.2`, 410)
	send("layout", `"type":"cursorDragEnd","dragId":"other"`, 204)
	send("layout", `"type":"cursorClick"`, 429)
	if rev := send("layout", move, 204); rev == startRevision {
		t.Fatal("movement left the cursor revision unchanged")
	}
	stale := strconv.FormatInt(time.Now().Add(-time.Minute).UnixMilli(), 10)
	send("old", end+`,"frameAt":`+stale, 204)
	send("layout", end, 204)
	send("layout", move, 410)
	send("layout", start, 204)
	send("old", move, 409)
	send("layout", move, 410)
	send("layout", start, 204)
	closeInput()
	if want := []string{"cursorDragStart", "cursorDragMove", "cursorDragMove", "cursorDragEnd", "cursorDragStart", "cursorDragEnd", "cursorDragStart", "cursorDragEnd"}; !slices.Equal(actions, want) {
		t.Fatalf("applied inputs = %v, want %v", actions, want)
	}
}

func TestDesktopLiveDragLeaseReleasesDisconnectedPointer(t *testing.T) {
	released := make(chan struct{}, 1)
	attempts := 0
	state := desktopInputState{apply: func(input desktopInput) error {
		if input.Type == "cursorDragEnd" {
			attempts++
			if attempts == 1 {
				return errors.New("the Windows user desktop is unavailable or locked")
			}
			released <- struct{}{}
		}
		return nil
	}}
	t.Cleanup(state.close)
	start := desktopInput{Type: "cursorDragStart", DragID: "active", Display: "all", Layout: "layout"}
	if _, err := state.send(context.Background(), start); err != nil {
		t.Fatal(err)
	}
	state.Lock()
	state.deadline = time.Now().Add(-time.Second)
	state.timer.Reset(0)
	state.Unlock()
	select {
	case <-released:
	case <-time.After(3 * time.Second):
		t.Fatal("expired lease did not retry releasing the mouse button")
	}
	state.Lock()
	defer state.Unlock()
	if state.drag.DragID != "" || attempts != 2 {
		t.Fatalf("button lease remained active after release: %v, %d attempts", state.drag, attempts)
	}
}
