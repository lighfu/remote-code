package host

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestConsoleShutdownClosesEventsAndPersistsConversation(t *testing.T) {
	a := testApp(t)
	server := httptest.NewServer(a.Handler())
	defer server.Close()
	header := http.Header{"Authorization": []string{"Bearer " + a.Config.Token}}
	ws, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(server.URL, "http")+"/api/events", header)
	if err != nil {
		t.Fatal(err)
	}
	defer ws.Close()
	_ = ws.SetReadDeadline(time.Now().Add(3 * time.Second))
	var hello M
	if err = ws.ReadJSON(&hello); err != nil || S(hello, "type") != "hello" {
		t.Fatalf("Initial event: %v %v", hello, err)
	}
	r := a.Store.Create(M{"provider": "codex", "cwd": t.TempDir(), "title": "Retained on restart"})
	id := S(r.Info, "id")
	entry := a.Store.Add(id, M{"kind": "assistant", "streaming": true, "text": "before"})
	a.Store.Delta(id, S(entry, "id"), "text", " restart")
	if err = a.Close(); err != nil {
		t.Fatal(err)
	}
	for {
		var event M
		if err = ws.ReadJSON(&event); err != nil {
			break
		}
	}
	if strings.Contains(strings.ToLower(err.Error()), "timeout") {
		t.Fatal("Event connection was not closed during shutdown")
	}
	raw, err := os.ReadFile(filepath.Join(a.DataDir, "sessions", id+".json"))
	if err != nil {
		t.Fatal(err)
	}
	var saved Record
	if err = json.Unmarshal(raw, &saved); err != nil {
		t.Fatal(err)
	}
	if S(saved.Info, "title") != "Retained on restart" || len(saved.Entries) != 1 || S(saved.Entries[0], "text") != "before restart" {
		t.Fatalf("Latest state was not flushed: %+v", saved)
	}
	reopened, err := OpenStore(filepath.Join(a.DataDir, "sessions"))
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	restored := reopened.Get(id)
	if restored == nil || B(restored.Entries[0], "streaming") || S(restored.Entries[0], "text") != "before restart" {
		t.Fatalf("Saved conversation could not resume: %+v", restored)
	}
}

func TestConsoleGenerationDrainsUploadBeforeReleasingLeases(t *testing.T) {
	data := t.TempDir()
	probe, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := probe.Addr().(*net.TCPAddr).Port
	_ = probe.Close()
	token := NewID()
	if err = atomicJSON(filepath.Join(data, "config.json"), M{"port": port, "token": token, "usbReverse": false, "quickTunnel": false, "allowLan": false}); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancelCause(context.Background())
	defer cancel(nil)
	type outcome struct {
		restart bool
		err     error
	}
	finished := make(chan outcome, 1)
	go func() {
		restart, err := runServerGeneration(ctx, data, []string{"--no-ui", "--no-qr"}, 0)
		finished <- outcome{restart, err}
	}()
	t.Cleanup(func() {
		cancel(nil)
		select {
		case <-finished:
		case <-time.After(12 * time.Second):
			t.Error("Isolated server did not finish cleanup")
		}
	})
	client := &http.Client{Timeout: 5 * time.Second}
	t.Cleanup(func() { client.CloseIdleConnections() })
	base := fmt.Sprintf("http://127.0.0.1:%d", port)
	deadline := time.Now().Add(5 * time.Second)
	healthy := false
	for time.Now().Before(deadline) {
		req, _ := http.NewRequest("GET", base+"/api/meta", nil)
		req.Header.Set("Authorization", "Bearer "+token)
		resp, e := client.Do(req)
		if e == nil {
			_, _ = io.Copy(io.Discard, resp.Body)
			resp.Body.Close()
			if resp.StatusCode == 200 {
				healthy = true
				break
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	if !healthy {
		t.Fatal("Isolated server did not become healthy")
	}
	var generation *App
	platformShutdown.Range(func(key, value any) bool {
		a, ok := key.(*App)
		if ok && a.DataDir == data {
			generation = a
			return false
		}
		return true
	})
	if generation == nil {
		t.Fatal("Missing generation registration")
	}
	reader, writer := io.Pipe()
	defer reader.Close()
	defer writer.Close()
	req, _ := http.NewRequest("POST", base+"/api/uploads?name=held.txt&mime=text/plain", reader)
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/octet-stream")
	type response struct {
		status int
		body   M
		err    error
	}
	responseDone := make(chan response, 1)
	go func() {
		resp, e := client.Do(req)
		if e != nil {
			responseDone <- response{err: e}
			return
		}
		defer resp.Body.Close()
		var m M
		e = json.NewDecoder(resp.Body).Decode(&m)
		responseDone <- response{status: resp.StatusCode, body: m, err: e}
	}()
	if _, err = writer.Write([]byte("before")); err != nil {
		t.Fatal(err)
	}
	deadline = time.Now().Add(3 * time.Second)
	partFound := false
	for time.Now().Before(deadline) {
		files, _ := filepath.Glob(filepath.Join(data, "attachments", "*", "file", "*.part"))
		if len(files) > 0 {
			partFound = true
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !partFound {
		t.Fatal("Upload did not reach its request handler")
	}
	cancel(errConsoleRestart)
	deadline = time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		generation.requestMu.Lock()
		draining := generation.draining
		generation.requestMu.Unlock()
		if draining {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	select {
	case result := <-finished:
		t.Fatalf("Generation stopped before the active upload drained: %+v", result)
	default:
	}
	canonical, err := filepath.EvalSymlinks(data)
	if err != nil {
		t.Fatal(err)
	}
	endpoint := platformInstanceEndpoint("data", canonical)
	if identity := platformInstanceRequest(endpoint, "inspect", nil); !B(identity, "ok") {
		t.Fatal("Data lease was released while an HTTP request was still active")
	}
	if _, err = writer.Write([]byte(" after")); err != nil {
		t.Fatal(err)
	}
	_ = writer.Close()
	var uploaded response
	select {
	case uploaded = <-responseDone:
	case <-time.After(3 * time.Second):
		t.Fatal("Upload did not complete during drain")
	}
	if uploaded.err != nil || uploaded.status != 200 {
		t.Fatalf("Upload interrupted: %+v", uploaded)
	}
	select {
	case result := <-finished:
		if result.err != nil || !result.restart {
			t.Fatalf("Unexpected generation result: %+v", result)
		}
		finished <- result
	case <-time.After(4 * time.Second):
		t.Fatal("Generation did not finish after upload completed")
	}
	path := filepath.Join(data, "attachments", S(uploaded.body, "id"), "file", uploadStoredName("held.txt"))
	bytes, err := os.ReadFile(path)
	if err != nil || string(bytes) != "before after" {
		t.Fatalf("Accepted upload lost across restart: %q %v", bytes, err)
	}
	lease, err := platformListenInstance(endpoint, M{"kind": "server", "resource": canonical, "version": Version, "pid": os.Getpid(), "instanceId": NewID(), "canStop": true}, func() {})
	if err != nil {
		t.Fatal("Data lease was not released after finalization:", err)
	}
	_ = lease.Close()
	listener, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		t.Fatal("Server TCP port was not released:", err)
	}
	_ = listener.Close()
}

func TestConsoleExternalProcessHelper(t *testing.T) {
	if os.Getenv("REMOTE_CODE_CONSOLE_EXTERNAL_HELPER") != "1" {
		return
	}
	fmt.Println("ready")
	s := bufio.NewScanner(os.Stdin)
	for s.Scan() {
	}
	os.Exit(0)
}

func TestConsoleShutdownLeavesUnownedProcessRunning(t *testing.T) {
	t.Setenv("REMOTE_CODE_CONSOLE_EXTERNAL_HELPER", "1")
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	cmd := HostCommand(context.Background(), exe, "-test.run=^TestConsoleExternalProcessHelper$")
	in, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	out, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err = cmd.Start(); err != nil {
		t.Fatal(err)
	}
	finished := make(chan struct{})
	var processErr error
	go func() { processErr = cmd.Wait(); close(finished) }()
	ready := make(chan string, 1)
	go func() { line, _ := bufio.NewReader(out).ReadString('\n'); ready <- strings.TrimSpace(line) }()
	t.Cleanup(func() {
		_ = in.Close()
		select {
		case <-finished:
		case <-time.After(2 * time.Second):
			_ = cmd.Process.Kill()
			<-finished
		}
	})
	select {
	case line := <-ready:
		if line != "ready" {
			t.Fatal("Unowned fixture did not initialize", line)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Unowned fixture initialization timed out")
	}
	a := testApp(t)
	var mu sync.Mutex
	ownedClosed := false
	a.OnClose(func() { mu.Lock(); ownedClosed = true; mu.Unlock() })
	if err = a.Close(); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	closed := ownedClosed
	mu.Unlock()
	if !closed {
		t.Fatal("Owned resources did not receive shutdown")
	}
	select {
	case <-finished:
		t.Fatalf("Shutdown stopped an unowned process: %v", processErr)
	default:
	}
}
