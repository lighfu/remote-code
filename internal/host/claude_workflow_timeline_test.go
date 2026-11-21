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

func TestClaudeWorkflowAgentTimelineEndpointPagesRecordedConversation(t *testing.T) {
	home := t.TempDir()
	t.Setenv("CLAUDE_CONFIG_DIR", home)
	app := testApp(t)
	app.Providers["claude"] = NewProvider("claude", app.Store, t.TempDir())
	external, runID, agent := "test-session", "wf_timeline", "worker"
	project := filepath.Join(home, "projects", "test-project")
	writeClaudeWorkflowFixture(t, project, external+".jsonl", "")
	dir := filepath.Join(project, external)
	journal := "subagents/workflows/" + runID + "/journal.jsonl"
	writeClaudeWorkflowFixture(t, dir, journal, "{\"type\":\"started\",\"agentId\":\"worker\"}\n")
	var transcript strings.Builder
	transcript.WriteString("{\"type\":\"user\",\"message\":{\"content\":\"First instruction\"}}\n")
	fmt.Fprintf(&transcript, "{\"type\":\"api-request\",\"data\":\"%s\"}\n", strings.Repeat("x", 2*claudeAgentTimelineScan))
	for i := range claudeAgentTimelineRows + 10 {
		fmt.Fprintf(&transcript, "{\"type\":\"assistant\",\"message\":{\"content\":\"reply %d\"}}\n", i)
	}
	transcript.WriteString(`{"type":"assistant","uuid":"tools","agentId":"worker","message":{"content":[{"type":"tool_use","name":"Read","id":"tool","input":{"description":"Read the branch model","file_path":"module.go"}}]}}
{"type":"user","uuid":"result","message":{"content":[{"type":"tool_result","tool_use_id":"tool","content":[{"type":"text","text":"Source file"}]}]}}
{"type":"assistant","uuid":"another","agentId":"different","message":{"content":"Do not show this reply"}}
`)
	path := "subagents/workflows/" + runID + "/agent-" + agent + ".jsonl"
	writeClaudeWorkflowFixture(t, dir, path, transcript.String())
	sid := S(app.Store.Create(M{"provider": "claude", "externalId": external}).Info, "id")
	entryID := "claude-workflow:" + runID + ":agent:" + agent
	get := func(target, cursor string, code int) M {
		t.Helper()
		uri := "/api/sessions/" + sid + "/workflow/agent/timeline?entryId=" + url.QueryEscape(target) + "&before=" + url.QueryEscape(cursor)
		response := testRequest(app, http.MethodGet, uri, "")
		if response.Code != code {
			t.Fatalf("GET agent timeline: HTTP %d, want %d: %s", response.Code, code, response.Body.String())
		}
		var page M
		if err := json.Unmarshal(response.Body.Bytes(), &page); err != nil {
			t.Fatal(err)
		}
		return page
	}
	page := get(entryID, "", 200)
	first := providerRows(page["entries"])
	if len(first) != claudeAgentTimelineRows || !B(page, "active") || S(page, "before") == "" {
		t.Fatalf("newest page had %d entries, active %v and cursor %q", len(first), page["active"], S(page, "before"))
	}
	tool := first[len(first)-1]
	if S(tool, "id") != "agent-tool:tool" || S(tool, "status") != "done" || S(tool, "output") != "Source file" || S(tool, "detail") != "module.go" || S(tool, "path") != "module.go" {
		t.Fatalf("newest tool entry lacks recorded result and input: %#v", tool)
	}
	last := get(entryID, S(page, "before"), 200)
	seen := map[string]bool{}
	for _, entry := range first {
		seen[S(entry, "id")] = true
	}
	for i := 0; ; i++ {
		entries := providerRows(last["entries"])
		for _, entry := range entries {
			id := S(entry, "id")
			if seen[id] {
				t.Fatalf("repeated timeline entry %q in older page", id)
			}
			seen[id] = true
		}
		cursor := S(last, "before")
		if cursor == "" {
			if len(entries) != 1 || S(entries[0], "text") != "First instruction" {
				t.Fatalf("oldest page did not recover the original instruction: %#v", entries)
			}
			break
		}
		if i > 8 {
			t.Fatal("pagination failed to reach the transcript beginning")
		}
		last = get(entryID, cursor, 200)
	}
	if len(seen) != claudeAgentTimelineRows+12 {
		t.Fatalf("recovered %d timeline entries across pages", len(seen))
	}
	get(entryID, "not-a-cursor", 400)
	get("claude-workflow:"+runID+":agent:../other", "", 404)
	writeClaudeWorkflowFixture(t, dir, journal, "{\"type\":\"started\",\"agentId\":\"worker\"}\n{\"type\":\"result\",\"agentId\":\"worker\"}\n")
	if active := get(entryID, "", 200)["active"]; active != false {
		t.Fatalf("finished worker active = %v", active)
	}
}

func TestClaudeWorkflowAgentTimelineResumesWithinRecord(t *testing.T) {
	dir := t.TempDir()
	path := "subagents/agent-dense.jsonl"
	blocks := make([]M, claudeAgentTimelineRows+10)
	for i := range blocks {
		blocks[i] = M{"type": "text", "text": fmt.Sprintf("part %d", i)}
	}
	line, err := json.Marshal(M{"type": "assistant", "uuid": "dense", "message": M{"content": blocks}})
	if err != nil {
		t.Fatal(err)
	}
	writeClaudeWorkflowFixture(t, dir, path, string(line)+"\n")
	source := claudeWorkflowAgentSource{sessionDir: dir, path: filepath.FromSlash(path), agentID: "dense"}
	newest, older, err := readClaudeAgentTimelinePage(source, claudeAgentTimelinePosition{offset: -1})
	if err != nil || len(newest) != claudeAgentTimelineRows || older != (claudeAgentTimelinePosition{block: 10}) {
		t.Fatalf("bad first page: %d rows, position %+v, err %v", len(newest), older, err)
	}
	earlier, end, err := readClaudeAgentTimelinePage(source, older)
	if err != nil || len(earlier) != 10 || end != (claudeAgentTimelinePosition{}) || S(earlier[0], "text") != "part 0" {
		t.Fatalf("bad continuation: %d rows, position %+v, err %v", len(earlier), end, err)
	}
}

func TestClaudeWorkflowAgentTimelinePagesNestedStoreEntries(t *testing.T) {
	app := testApp(t)
	sid := S(app.Store.Create(M{"provider": "claude"}).Info, "id")
	app.Store.Add(sid, M{"id": "target", "kind": "tool", "tool": "Agent", "status": "done", "detail": "Begin here"})
	app.Store.Add(sid, M{"id": "different", "kind": "tool", "tool": "Agent"})
	app.Store.Add(sid, M{"id": "hidden", "kind": "assistant", "parent": "different", "text": "Different worker"})
	for i := range claudeAgentTimelineRows + 2 {
		app.Store.Add(sid, M{"id": fmt.Sprintf("reply-%d", i), "kind": "assistant", "parent": "target", "text": "Answer"})
	}
	page, err := app.claudeWorkflowAgentTimeline(sid, "target", "")
	if err != nil || len(providerRows(page["entries"])) != claudeAgentTimelineRows || S(page, "before") == "" || page["active"] != false {
		t.Fatalf("bad stored page, error: %v", err)
	}
	older, err := app.claudeWorkflowAgentTimeline(sid, "target", S(page, "before"))
	rows := providerRows(older["entries"])
	if err != nil || len(rows) != 3 || S(rows[0], "text") != "Begin here" || S(rows[1], "id") != "reply-0" || S(older, "before") != "" {
		t.Fatalf("bad older entries: %#v; error: %v", older, err)
	}
	if _, err = app.claudeWorkflowAgentTimeline(sid, "different", S(page, "before")); err == nil {
		t.Fatal("cursor could be reused for a different agent")
	}
}
