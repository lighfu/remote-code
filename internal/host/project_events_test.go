package host

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os/exec"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestProjectWritesRefreshGitOverEvents(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git is not installed")
	}
	root := t.TempDir()
	if _, err := gitRun(root, "", 10*time.Second, "init"); err != nil {
		t.Fatal(err)
	}
	a := testApp(t)
	id := S(a.Store.Create(M{"provider": "codex", "cwd": root}).Info, "id")
	base := "/api/sessions/" + id
	srv := httptest.NewServer(a.Handler())
	defer srv.Close()
	headers := http.Header{"Authorization": []string{"Bearer " + a.Config.Token}}
	ws, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(srv.URL, "http")+"/api/events", headers)
	if err != nil {
		t.Fatal(err)
	}
	defer ws.Close()
	read := func() M {
		t.Helper()
		_ = ws.SetReadDeadline(time.Now().Add(3 * time.Second))
		var ev M
		if err := ws.ReadJSON(&ev); err != nil {
			t.Fatal(err)
		}
		return ev
	}
	if S(read(), "type") != "hello" {
		t.Fatal("missing initial snapshot")
	}
	request := func(method, path string, body M) M {
		t.Helper()
		data, err := json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
		resp := testRequest(a, method, base+path, string(data))
		var result M
		if resp.Code != http.StatusOK || json.Unmarshal(resp.Body.Bytes(), &result) != nil {
			t.Fatalf("%s %s: %d %s", method, path, resp.Code, resp.Body)
		}
		return result
	}
	if status := request("GET", "/git", nil); !B(status, "isRepo") || N(status, "changedFiles") != 0 {
		t.Fatal(status)
	}
	// The project browser sends the canonical root from its listing, which
	// can differ from the session directory on Windows.
	projectRoot := S(request("GET", "/project/files", nil), "root")
	if projectRoot == "" {
		t.Fatal("project listing has no root")
	}
	for _, step := range []struct {
		path, count string
		body        M
	}{
		{"/project/create", "untrackedFiles", M{"root": projectRoot, "path": "note.txt", "kind": "file"}},
		{"/git/stage", "stagedFiles", M{"cwd": root, "paths": []string{"note.txt"}}},
	} {
		start := time.Now()
		request("POST", step.path, step.body)
		event := read()
		fields := array(event["fields"])
		if S(event, "type") != "session_details" || S(event, "sessionId") != id || S(event, "cwd") != root ||
			len(fields) != 1 || fields[0] != "git" {
			t.Fatalf("%s: unexpected update %v", step.path, event)
		}
		status := request("GET", "/git", nil)
		if N(status, step.count) != 1 || N(status, "changedFiles") != 1 {
			t.Fatalf("%s: stale status after notification: %v", step.path, status)
		}
		t.Logf("%s to refreshed status: %v", step.path, time.Since(start))
	}
}
