package host

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"path/filepath"
	"strings"
	"testing"
)

func TestClaudeWorkflowAgentTranscriptEndpointScopesAgentAndSession(t *testing.T) {
	home := t.TempDir()
	t.Setenv("CLAUDE_CONFIG_DIR", home)
	a := testApp(t)
	a.Providers["claude"] = NewProvider("claude", a.Store, t.TempDir())
	external, run := "test-session", "wf_review"
	project := filepath.Join(home, "projects", "test-project")
	writeClaudeWorkflowFixture(t, project, external+".jsonl", "")
	dir := filepath.Join(project, external)
	writeClaudeWorkflowFixture(t, dir, "subagents/workflows/"+run+"/journal.jsonl", `{"type":"started","agentId":"reviewer","label":"Review"}
{"type":"started","agentId":"second","label":"Second"}
`)
	writeClaudeWorkflowFixture(t, dir, "subagents/workflows/"+run+"/agent-reviewer.jsonl", `{"type":"user","uuid":"prompt","agentId":"reviewer","timestamp":"2026-11-20T09:00:00Z","message":{"content":"Review the tree."}}
{"type":"assistant","uuid":"tools","agentId":"reviewer","message":{"content":[{"type":"thinking","text":"Hidden reasoning"},{"type":"tool_use","name":"Read","id":"tool","input":{"description":"Read the branch model","command":"raw input"}}]}}
{"type":"user","uuid":"result","message":{"content":[{"type":"tool_result","tool_use_id":"tool","is_error":false,"content":"raw output"}]}}
{"type":"api-request","agentId":"reviewer","body":"Internal request"}
{"type":"assistant","uuid":"different-agent","agentId":"second","message":{"content":"Not this agent"}}
{"type":"user","isMeta":true,"message":{"content":"Hidden reminder"}}
{"type":"assistant","uuid":"answer","message":{"content":[{"type":"text","text":"Unfinished reply"}]}}
{"type":"assistant","uuid":"answer","message":{"content":[{"type":"text","text":"The branch is connected."}]}}
`)
	writeClaudeWorkflowFixture(t, dir, "subagents/workflows/"+run+"/agent-unlisted.jsonl", `{"type":"user","message":{"content":"Unlisted"}}`)
	writeClaudeWorkflowFixture(t, dir, "subagents/agent-ordinary.jsonl", `{"type":"assistant","agentId":"ordinary","message":{"content":"Ordinary subagent reply"}}`)
	claude := a.Store.Create(M{"provider": "claude", "externalId": external})
	sid := S(claude.Info, "id")
	a.Store.Add(sid, M{"id": "second-tool", "kind": "tool", "tool": "Agent", "claudeTaskId": "second", "detail": "Check the summary"})
	a.Store.Add(sid, M{"id": "second-reply", "kind": "assistant", "parent": "second-tool", "text": "Summary is correct."})
	a.Store.Add(sid, M{"id": "ordinary-tool", "kind": "tool", "tool": "Agent", "claudeTaskId": "ordinary"})
	other := a.Store.Create(M{"provider": "claude", "externalId": "other-session"})
	readerID := "claude-workflow:" + run + ":agent:reviewer"
	get := func(sessionID, id string, wantCode int) []M {
		t.Helper()
		response := testRequest(a, http.MethodGet, "/api/sessions/"+sessionID+"/workflow/agent?entryId="+url.QueryEscape(id), "")
		if response.Code != wantCode {
			t.Fatalf("%q: HTTP %d, want %d: %s", id, response.Code, wantCode, response.Body)
		}
		if wantCode != http.StatusOK {
			return nil
		}
		var messages []M
		if err := json.Unmarshal(response.Body.Bytes(), &messages); err != nil {
			t.Fatal(err)
		}
		return messages
	}
	messages := get(sid, readerID, http.StatusOK)
	if len(messages) != 3 || S(messages[0], "text") != "Review the tree." || N(messages[0], "ts") == 0 ||
		S(messages[1], "tool") != "Read" || S(messages[1], "text") != "Read the branch model" || S(messages[1], "status") != "done" ||
		S(messages[2], "text") != "The branch is connected." {
		t.Fatalf("unexpected workflow transcript: %#v", messages)
	}
	if messages := get(sid, "second-tool", http.StatusOK); len(messages) != 2 || S(messages[0], "text") != "Check the summary" || S(messages[1], "text") != "Summary is correct." {
		t.Fatalf("unexpected stored child messages: %#v", messages)
	}
	if messages := get(sid, "ordinary-tool", http.StatusOK); len(messages) != 1 || S(messages[0], "text") != "Ordinary subagent reply" {
		t.Fatalf("unexpected ordinary agent transcript: %#v", messages)
	}
	get(sid, "claude-workflow:"+run+":agent:unlisted", http.StatusNotFound)
	get(sid, "claude-workflow:"+run+":agent:../unlisted", http.StatusNotFound)
	get(sid, "claude-workflow:"+run, http.StatusNotFound)
	get(S(other.Info, "id"), readerID, http.StatusNotFound)
}

func TestClaudeWorkflowAgentTranscriptKeepsInstructionAndRecentRows(t *testing.T) {
	dir := t.TempDir()
	path := "subagents/agent-worker.jsonl"
	var transcript strings.Builder
	transcript.WriteString("{\"type\":\"user\",\"message\":{\"content\":\"Read this instruction\"}}\n")
	fmt.Fprintf(&transcript, "{\"type\":\"api-request\",\"data\":\"%s\"}\n", strings.Repeat("x", claudeAgentTranscriptTail))
	for i := range claudeAgentTranscriptRows + 3 {
		fmt.Fprintf(&transcript, "{\"type\":\"assistant\",\"message\":{\"content\":\"reply %d\"}}\n", i)
	}
	writeClaudeWorkflowFixture(t, dir, path, transcript.String())
	messages := readClaudeAgentTranscript(dir, filepath.FromSlash(path), "worker")
	if len(messages) != claudeAgentTranscriptRows+1 || S(messages[0], "text") != "Read this instruction" ||
		S(messages[1], "text") != "reply 3" || S(messages[len(messages)-1], "text") != fmt.Sprintf("reply %d", claudeAgentTranscriptRows+2) {
		t.Fatalf("unexpected retained transcript length or messages: %d", len(messages))
	}
}
