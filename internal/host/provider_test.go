package host

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

type providerBuffer struct {
	sync.Mutex
	bytes.Buffer
}

func (b *providerBuffer) Write(v []byte) (int, error) {
	b.Lock()
	defer b.Unlock()
	return b.Buffer.Write(v)
}
func (b *providerBuffer) Close() error { return nil }
func (b *providerBuffer) messages() []M {
	b.Lock()
	defer b.Unlock()
	out := []M{}
	scanner := bufio.NewScanner(bytes.NewReader(b.Bytes()))
	for scanner.Scan() {
		var m M
		_ = json.Unmarshal(scanner.Bytes(), &m)
		out = append(out, m)
	}
	return out
}
func providerTestStore(t *testing.T) *Store {
	t.Helper()
	s, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = s.Close() })
	return s
}
func TestProviderWireHelper(t *testing.T) {
	if os.Getenv("REMOTE_CODE_WIRE_HELPER") != "1" {
		return
	}
	scanner := bufio.NewScanner(os.Stdin)
	for scanner.Scan() {
		var msg M
		if json.Unmarshal(scanner.Bytes(), &msg) != nil {
			os.Exit(5)
		}
		if S(msg, "type") == "control_request" {
			id := S(msg, "request_id")
			req := providerMap(msg["request"])
			if S(req, "subtype") == "exit" {
				os.Exit(9)
			}
			_ = json.NewEncoder(os.Stdout).Encode(M{"type": "control_response", "response": M{"subtype": "success", "request_id": id, "response": M{"echo": req["payload"]}}})
		} else {
			if S(msg, "method") == "exit" {
				os.Exit(9)
			}
			_ = json.NewEncoder(os.Stdout).Encode(M{"id": msg["id"], "result": M{"echo": providerMap(msg["params"])["payload"]}})
		}
	}
	os.Exit(0)
}
func TestProviderWireRequestsAndExit(t *testing.T) {
	t.Setenv("REMOTE_CODE_WIRE_HELPER", "1")
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	for _, claude := range []bool{false, true} {
		t.Run(fmt.Sprint(claude), func(t *testing.T) {
			w, e := startProviderWire(exe, []string{"-test.run=^TestProviderWireHelper$"}, t.TempDir(), claude, func(M) {})
			if e != nil {
				t.Fatal(e)
			}
			defer w.close()
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			var wg sync.WaitGroup
			for i := 0; i < 20; i++ {
				wg.Add(1)
				go func(i int) {
					defer wg.Done()
					res, e := w.request(ctx, "echo", M{"payload": fmt.Sprint(i)})
					if e != nil || S(res, "echo") != fmt.Sprint(i) {
						t.Errorf("response %v %v", res, e)
					}
				}(i)
			}
			wg.Wait()
			if _, e = w.request(ctx, "exit", M{}); e == nil {
				t.Fatal("Expected process-exit error")
			}
		})
	}
}
func TestProviderClaudePermissionsFailClosedAndQuestions(t *testing.T) {
	store := providerTestStore(t)
	rec := store.Create(M{"provider": "claude"})
	p := NewProvider("claude", store, t.TempDir()).(*nativeProvider)
	buffer := &providerBuffer{}
	w := &providerWire{claude: true, in: buffer}
	sid := S(rec.Info, "id")
	p.addPermission(sid, w, "wire-ask", "can_use_tool", M{}, "Bash", M{"command": "echo example"})
	entries := store.Get(sid).Entries
	ask := entries[len(entries)-1]
	if S(ask, "kind") != "approval" || S(store.Get(sid).Info, "status") != "waiting" {
		t.Fatalf("Missing approval: %v", ask)
	}
	if err := p.respond(rec, M{"requestId": S(ask, "requestId"), "choice": "deny"}); err != nil {
		t.Fatal(err)
	}
	messages := buffer.messages()
	reply := providerMap(providerMap(messages[0]["response"])["response"])
	if S(reply, "behavior") != "deny" {
		t.Fatalf("Unexpected approval reply %v", reply)
	}
	p.addPermission(sid, w, "wire-question", "hook_question", M{}, "AskUserQuestion", M{"questions": []M{{"question": "Which file?", "header": "File", "options": []M{{"label": "README", "description": "Documentation"}}}}})
	entries = store.Get(sid).Entries
	ask = entries[len(entries)-1]
	if err := p.respond(rec, M{"requestId": S(ask, "requestId"), "answers": M{"0": []string{"README"}}}); err != nil {
		t.Fatal(err)
	}
	messages = buffer.messages()
	hook := providerMap(providerMap(providerMap(messages[len(messages)-1]["response"])["response"])["hookSpecificOutput"])
	if S(hook, "permissionDecision") != "allow" || S(providerMap(providerMap(hook["updatedInput"])["answers"]), "Which file?") != "README" {
		t.Fatalf("Invalid hook answer %v", hook)
	}
	if err := p.respond(store.Create(M{"provider": "claude"}), M{"requestId": S(ask, "requestId"), "choice": "allow"}); err == nil {
		t.Fatal("Cross-session answer accepted")
	}
}
func TestProviderClaudeStreamingAndSubagents(t *testing.T) {
	store := providerTestStore(t)
	rec := store.Create(M{"provider": "claude"})
	sid := S(rec.Info, "id")
	p := NewProvider("claude", store, t.TempDir()).(*nativeProvider)
	r := &nativeSession{parents: map[string]string{}, blocks: map[int]string{}}
	p.sessions[sid] = r
	send := func(event M) { p.claudeMessage(sid, r, M{"type": "stream_event", "event": event}) }
	send(M{"type": "message_start", "message": M{"id": "message"}})
	send(M{"type": "content_block_start", "index": 0, "content_block": M{"type": "text", "text": ""}})
	send(M{"type": "content_block_delta", "index": 0, "delta": M{"type": "text_delta", "text": "Hello"}})
	p.claudeMessage(sid, r, M{"type": "assistant", "message": M{"id": "message", "content": []M{{"type": "text", "text": "Hello"}}}})
	if got := store.Get(sid); len(got.Entries) != 1 || S(got.Entries[0], "text") != "Hello" {
		t.Fatalf("Duplicate streaming text: %v", got.Entries)
	}
	p.claudeMessage(sid, r, M{"type": "system", "subtype": "task_started", "task_id": "child", "tool_use_id": "agent", "description": "Check"})
	p.claudeMessage(sid, r, M{"type": "system", "subtype": "task_notification", "task_id": "child", "status": "completed", "summary": "Checked"})
	entries := store.Get(sid).Entries
	if len(entries) != 2 || S(entries[1], "tool") != "Agent" || S(entries[1], "status") != "done" {
		t.Fatalf("Subagent not completed: %v", entries)
	}
}
func TestProviderUsagePreservesTicketsAcrossDelta(t *testing.T) {
	p := &nativeProvider{usage: M{"tickets": M{"available": 2, "items": []M{{"title": "Full reset"}}}}}
	snap := p.codexUsage(M{"rateLimits": M{"primary": M{"usedPercent": 45.0, "windowDurationMins": 300.0, "resetsAt": 1794860000.0}}})
	if N(providerMap(snap["tickets"]), "available") != 2 {
		t.Fatalf("Tickets lost: %v", snap)
	}
	next := p.codexUsage(M{"rateLimitResetCredits": M{"availableCount": 0.0}})
	if N(providerMap(next["tickets"]), "available") != 0 {
		t.Fatal("Explicit zero not accepted")
	}
}
func TestProviderClaudeUsageDynamicScopes(t *testing.T) {
	result := parseClaudeUsage(M{"rate_limits": M{"limits": []M{{"kind": "weekly_all", "percent": 17.0, "resets_at": "2026-11-22T09:00:00Z"}, {"kind": "weekly_scoped", "percent": 21.0, "scope": M{"model": M{"display_name": "Fable"}}}}}, "cedar_ember": M{"eligible": true, "grants": []M{{"resets_left": 3.0, "clears": []any{"seven_day"}, "ends_at": "2026-12-01T00:00:00Z"}}}})
	if len(providerRows(result["windows"])) != 2 || N(providerMap(result["tickets"]), "available") != 3 || S(result, "noteKey") != "subscription" {
		t.Fatalf("Usage parse failed: %v", result)
	}
}
func TestProviderCrossSessionMarkupRemoved(t *testing.T) {
	entry := M{"text": `<cross-session-message from="uds:path" from-session="session" from-name="Other" from-mode="prompting">Please wait</cross-session-message>`}
	providerCrossSession(entry)
	if S(entry, "text") != "Please wait" || S(providerMap(entry["crossSession"]), "senderName") != "Other" || S(entry, "messageOrigin") != "peer" {
		t.Fatal(entry)
	}
}
func TestProviderExecutableRejectsScripts(t *testing.T) {
	path := filepath.Join(t.TempDir(), "fake.cmd")
	if err := os.WriteFile(path, []byte("@echo off"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := providerExecutable(path); err == nil && os.PathSeparator == '\\' {
		t.Fatal("Node wrapper accepted")
	}
}
func TestProviderInstalledClaudeInitialization(t *testing.T) {
	if os.Getenv("REMOTE_CODE_TEST_INSTALLED_CLAUDE") != "1" {
		t.Skip("Explicit read-only installed CLI probe")
	}
	store := providerTestStore(t)
	p := NewProvider("claude", store, t.TempDir()).(*nativeProvider)
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	result, err := p.probeClaude(ctx, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if len(providerRows(result["models"])) == 0 {
		t.Fatalf("No models from installed Claude: %v", result)
	}
	t.Logf("Installed Claude initialized; models=%d commands=%d", len(providerRows(result["models"])), len(providerRows(result["commands"])))
}
func TestProviderFindsNativeNpmExecutableWithoutNode(t *testing.T) {
	if os.PathSeparator != '\\' {
		t.Skip("Windows package layout")
	}
	root := t.TempDir()
	t.Setenv("USERPROFILE", root)
	t.Setenv("APPDATA", root)
	t.Setenv("CODEX_BIN", "")
	t.Setenv("PATH", root)
	path := filepath.Join(root, "npm", "node_modules", "@openai", "codex", "node_modules", "@openai", "codex-win32-x64", "vendor", "x86_64-pc-windows-msvc", "bin", "codex.exe")
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("fixture"), 0600); err != nil {
		t.Fatal(err)
	}
	found, err := FindProviderExecutable("codex")
	if err != nil || found != path {
		t.Fatalf("Native package binary not found: %q %v", found, err)
	}
}
func TestProviderInstalledNativeDiscovery(t *testing.T) {
	if os.Getenv("REMOTE_CODE_TEST_INSTALLED_PROVIDERS") != "1" {
		t.Skip("Explicit installed CLI version probe")
	}
	for _, name := range []string{"claude", "codex", "antigravity"} {
		path, err := FindProviderExecutable(name)
		if err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		output, err := HostCommand(ctx, path, "--version").Output()
		cancel()
		if err != nil {
			t.Fatal(name, err)
		}
		t.Logf("%s native CLI: %s (%s)", name, bytes.TrimSpace(output), path)
	}
}
