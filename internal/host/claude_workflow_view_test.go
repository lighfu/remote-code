package host

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

func TestClaudeWorkflowEndpointCombinesJournalAndEarlierTimeline(t *testing.T) {
	home := t.TempDir()
	t.Setenv("CLAUDE_CONFIG_DIR", home)
	a := testApp(t)
	a.Providers["claude"] = NewProvider("claude", a.Store, t.TempDir())
	const external = "11111111-1111-1111-1111-111111111111"
	const run = "wf_22222222-2222-2222-2222-222222222222"
	project := filepath.Join(home, "projects", "test-project")
	sessionDir := filepath.Join(project, external)
	write := func(path, data string) {
		t.Helper()
		if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(data), 0600); err != nil {
			t.Fatal(err)
		}
	}
	write(filepath.Join(project, external+".jsonl"), "")
	write(filepath.Join(sessionDir, "workflows", run+".json"), `{"runId":"`+run+`","taskId":"workflow-task","workflowName":"Parallel review","startTime":1795000000001,"status":"completed","phases":[{"title":"Review"}]}`)
	write(filepath.Join(sessionDir, "subagents", "workflows", run, "journal.jsonl"), `{"type":"launched"}
{"type":"started","agentId":"first-worker","key":"1","label":"First task","phase":"Review"}
{"type":"started","agentId":"second-worker","key":"2","label":"Second task","phase":"Review"}
{"type":"result","agentId":"first-worker","key":"1","result":"Reported"}
{"type":"failed","agentId":"second-worker","key":"2"}
`)
	write(filepath.Join(sessionDir, "subagents", "workflows", run, "agent-first-worker.jsonl"), `{"type":"user","agentId":"first-worker","message":{"content":"Check imports"}}
{"type":"assistant","agentId":"first-worker","timestamp":"2026-11-20T12:00:00Z","message":{"content":[{"type":"tool_use","name":"Bash","input":{"description":"Examine imports","command":"unrelated raw command"}}]}}
{"type":"api-request-blob","message":{"content":"private telemetry"}}
`)
	record := a.Store.Create(M{"provider": "claude", "externalId": external})
	sid := S(record.Info, "id")
	a.Store.Add(sid, M{"id": "prompt", "kind": "user", "text": "Review the project", "ts": 1795000000000})
	a.Store.Add(sid, M{"id": "workflow-tool", "kind": "tool", "tool": "Workflow", "status": "running", "ts": 1795000000001, "claudeTaskId": "workflow-task"})
	a.Store.Add(sid, M{"id": "worker-tool", "kind": "tool", "tool": "Agent", "status": "running", "ts": 1795000000002, "claudeTaskId": "first-worker"})
	a.Store.Add(sid, M{"id": "bridge", "kind": "tool", "tool": "Other", "parent": "worker-tool", "ts": 1795000000003, "output": "not part of the workflow view"})
	a.Store.Add(sid, M{"id": "grandchild", "kind": "tool", "tool": "Agent", "parent": "bridge", "ts": 1795000000004, "status": "done"})
	a.Store.Add(sid, M{"id": "grandchild-result", "kind": "assistant", "parent": "grandchild", "text": "Checked dependencies", "ts": 1795000000010})
	for range 600 {
		a.Store.Add(sid, M{"kind": "thinking", "text": "current turn"})
	}
	response := testRequest(a, http.MethodGet, "/api/sessions/"+sid+"/workflow", "")
	if response.Code != http.StatusOK {
		t.Fatalf("HTTP %d: %s", response.Code, response.Body)
	}
	var entries []M
	if err := json.Unmarshal(response.Body.Bytes(), &entries); err != nil {
		t.Fatal(err)
	}
	rows := make(map[string]M, len(entries))
	for _, entry := range entries {
		rows[S(entry, "id")] = entry
	}
	if len(entries) != 7 || len(rows) != len(entries) || S(rows["prompt"], "kind") != "user" || S(rows["workflow-tool"], "status") != "done" {
		t.Fatalf("did not reconcile workflow once with its prompt: %v", entries)
	}
	phaseID := S(rows["worker-tool"], "parent")
	second := rows["claude-workflow:"+run+":agent:second-worker"]
	if S(rows[phaseID], "parent") != "workflow-tool" || S(rows[phaseID], "title") != "Review" || S(rows[phaseID], "status") != "error" || S(rows["worker-tool"], "output") != "Reported" ||
		S(second, "parent") != phaseID || S(second, "status") != "error" || S(rows["grandchild"], "parent") != "bridge" ||
		S(rows["bridge"], "parent") != "worker-tool" || S(rows["bridge"], "output") != "" {
		t.Fatalf("incorrect phase, sibling, or nested agent relationship: %v", entries)
	}
	worker := rows["worker-tool"]
	if S(worker, "detail") != "Check imports" || S(worker, "progress") != "Bash · Examine imports" || N(worker, "activityAt") != 1795176000000 ||
		S(worker, "claudeWorkflowRunId") != run || S(worker, "claudeWorkflowAgentId") != "first-worker" {
		t.Fatalf("missing agent instructions or latest visible activity: %v", worker)
	}
	child := rows["grandchild"]
	if S(child, "output") != "Checked dependencies" || S(child, "progress") != "Checked dependencies" || N(child, "activityAt") != 1795000000010 {
		t.Fatalf("nested agent lost direct assistant activity: %v", child)
	}
}

func TestClaudeWorkflowOverviewReadsOrdinaryAgentSidecar(t *testing.T) {
	home := t.TempDir()
	t.Setenv("CLAUDE_CONFIG_DIR", home)
	a := testApp(t)
	a.Providers["claude"] = NewProvider("claude", a.Store, t.TempDir())
	const external = "11111111-1111-1111-1111-111111111111"
	const agentID = "abc123"
	sessionDir := filepath.Join(home, "projects", "test-project", external)
	writeClaudeWorkflowFixture(t, filepath.Dir(sessionDir), external+".jsonl", "")
	const start = `{"type":"user","agentId":"abc123","message":{"content":"Inspect the project"}}
{"type":"assistant","agentId":"abc123","timestamp":"2026-11-20T12:00:00Z","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"C:\\project\\file.kt"}}]}}
`
	writeClaudeWorkflowFixture(t, sessionDir, "subagents/agent-"+agentID+".jsonl", start)
	record := a.Store.Create(M{"provider": "claude", "externalId": external})
	sid := S(record.Info, "id")
	a.Store.Add(sid, M{"id": "agent-tool", "kind": "tool", "tool": "Agent", "status": "running", "claudeTaskId": agentID})
	read := func() M {
		t.Helper()
		response := testRequest(a, http.MethodGet, "/api/sessions/"+sid+"/workflow", "")
		var entries []M
		if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &entries) != nil || len(entries) != 1 {
			t.Fatalf("unexpected response: HTTP %d %s", response.Code, response.Body)
		}
		return entries[0]
	}
	if row := read(); S(row, "detail") != "Inspect the project" || S(row, "progress") != "Read · file.kt" || N(row, "activityAt") == 0 {
		t.Fatalf("ordinary agent missing its latest local activity: %v", row)
	}
	writeClaudeWorkflowFixture(t, sessionDir, "subagents/agent-"+agentID+".jsonl", start+`{"type":"assistant","agentId":"abc123","timestamp":"2026-11-20T12:01:00Z","message":{"stop_reason":"end_turn","content":[{"type":"text","text":"Project checked"}]}}`)
	a.Store.Patch(sid, "agent-tool", M{"status": "done"})
	if row := read(); S(row, "progress") != "Project checked" || S(row, "output") != "Project checked" {
		t.Fatalf("ordinary agent result missing after completion: %v", row)
	}
}
