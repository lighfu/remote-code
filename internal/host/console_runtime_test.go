package host

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestConsoleRuntimeSnapshotAndPrivateLogs(t *testing.T) {
	a := testApp(t)
	a.Config.Token = "private-pair-token"
	a.Config.AccessClientSecret = "private-access-secret"
	a.Store.Create(M{"provider": "claude", "status": "running"})
	a.Store.Create(M{"provider": "codex", "status": "waiting"})
	c := attachConsole(a)
	defer c.detach()
	consoleLogf("Connecting token=%s secret=%s", a.Config.Token, a.Config.AccessClientSecret)
	if !consoleURLReady(a, "https://test.trycloudflare.com") {
		t.Fatal("current console did not observe tunnel URL")
	}
	s := c.snapshot()
	if s.Running != 1 || s.Waiting != 1 || s.Sessions != 2 || s.URL != "https://test.trycloudflare.com" {
		t.Fatal(s)
	}
	if !strings.Contains(s.PairLink, a.Config.Token) {
		t.Fatal("explicit pairing link lost authentication")
	}
	if len(s.Logs) != 1 || strings.Contains(s.Logs[0], a.Config.Token) || strings.Contains(s.Logs[0], a.Config.AccessClientSecret) {
		t.Fatal("secret exposed in logs", s.Logs)
	}
	for i := 0; i < 350; i++ {
		consoleLogf("line %d", i)
	}
	s = c.snapshot()
	if len(s.Logs) != 300 {
		t.Fatal(len(s.Logs))
	}
	if consoleURLReady(testApp(t), "https://other.example") {
		t.Fatal("another app changed this console")
	}
}
func TestConsoleOpenRejectsNonWebTargets(t *testing.T) {
	for _, target := range []string{"file:///C:/private", "javascript:alert(1)", "https://user:secret@example.com", "http:///empty", "bad\r\nvalue"} {
		if err := consoleOpen(context.Background(), target); err == nil {
			t.Errorf("accepted %q", target)
		}
	}
}

func TestConsoleShutdownJoinsTasksBeforeSavingAndEvictsAPKState(t *testing.T) {
	a := testApp(t)
	rec := a.Store.Create(M{"provider": "claude", "title": "Restart fixture"})
	id := S(rec.Info, "id")
	releaseState(a)
	if !a.startTask(func() { <-a.taskCtx.Done(); a.Store.Add(id, M{"kind": "system", "text": "Task canceled before save"}) }) {
		t.Fatal("task refused before shutdown")
	}
	if err := a.Close(); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(filepath.Join(a.DataDir, "sessions", id+".json"))
	if err != nil {
		t.Fatal(err)
	}
	var saved Record
	if err = json.Unmarshal(raw, &saved); err != nil {
		t.Fatal(err)
	}
	if len(saved.Entries) != 1 || S(saved.Entries[0], "text") != "Task canceled before save" {
		t.Fatal("late task write was not persisted", saved)
	}
	if _, ok := releaseAPKStates.Load(a); ok {
		t.Fatal("old generation retained by APK cache")
	}
	if a.startTask(func() { t.Error("task ran after shutdown") }) {
		t.Fatal("task accepted after shutdown")
	}
}
