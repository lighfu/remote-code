package host

import (
	"encoding/json"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestClaudeWorkflowUsageDeduplicatesBlocksAndPreservesLatestContext(t *testing.T) {
	var samples claudeWorkflowUsageSamples
	for _, line := range []string{
		`{"type":"assistant","agentId":"worker","message":{"id":"m1","usage":{"input_tokens":12,"cache_read_input_tokens":60,"cache_creation_input_tokens":9,"output_tokens":1}}}`,
		`{"type":"assistant","agentId":"worker","uuid":"m2","message":{"usage":{"input_tokens":5,"cache_read_input_tokens":80,"output_tokens":3}}}`,
		`{"type":"assistant","agentId":"worker","message":{"id":"m1","usage":{"output_tokens":6}}}`,
		`{"type":"assistant","agentId":"other","message":{"id":"m3","usage":{"input_tokens":1000,"output_tokens":1000}}}`,
		`{"type":"api-request","agentId":"worker","message":{"id":"m4","usage":{"input_tokens":1000,"output_tokens":1000}}}`,
		`{"type":"assistant","agentId":"worker","message":{"id":"m5","usage":{"input_tokens":-1,"output_tokens":1000}}}`,
	} {
		samples.add([]byte(line), "worker")
	}
	usage := samples.summary(false)
	if usage == nil || usage.ContextTokens == nil || *usage.ContextTokens != 85 || usage.InputTokens != 166 || usage.OutputTokens != 9 ||
		usage.CachedInputTokens != 140 || usage.CacheWriteTokens != 9 || usage.RequestCount != 2 || usage.Partial {
		t.Fatalf("incorrect deduplicated usage: %+v", usage)
	}
}

func TestClaudeWorkflowUsageOverviewAndDetailedEndpoint(t *testing.T) {
	home := t.TempDir()
	t.Setenv("CLAUDE_CONFIG_DIR", home)
	a := testApp(t)
	a.Providers["claude"] = NewProvider("claude", a.Store, t.TempDir())
	const external, run, agentID = "local-session", "wf_review", "worker"
	project := filepath.Join(home, "projects", "test-project")
	sessionDir := filepath.Join(project, external)
	writeClaudeWorkflowFixture(t, project, external+".jsonl", "")
	writeClaudeWorkflowFixture(t, sessionDir, "subagents/workflows/"+run+"/journal.jsonl", `{"type":"started","agentId":"worker","label":"Review"}`)
	first := `{"type":"assistant","agentId":"worker","message":{"id":"m1","usage":{"input_tokens":12,"cache_read_input_tokens":60,"cache_creation_input_tokens":9,"output_tokens":4},"content":[{"type":"text","text":"Assistant response"}]}}` + "\n"
	private := `{"type":"api-request","detail":"` + strings.Repeat("x", claudeWorkflowActivityBytes) + `"}` + "\n"
	last := `{"type":"assistant","agentId":"worker","message":{"id":"m2","usage":{"input_tokens":5,"cache_read_input_tokens":80,"output_tokens":3},"content":[{"type":"text","text":"Assistant response"}]}}` + "\n"
	logPath := "subagents/workflows/" + run + "/agent-worker.jsonl"
	writeClaudeWorkflowFixture(t, sessionDir, logPath, first+private+last)
	record := a.Store.Create(M{"provider": "claude", "externalId": external})
	sid := S(record.Info, "id")
	agentRowID := "claude-workflow:" + run + ":agent:" + agentID
	rootURL := "/api/sessions/" + sid + "/workflow"
	overview := testRequest(a, http.MethodGet, rootURL, "")
	var entries []struct {
		ID    string               `json:"id"`
		Usage *claudeWorkflowUsage `json:"agentUsage"`
	}
	if overview.Code != http.StatusOK || json.Unmarshal(overview.Body.Bytes(), &entries) != nil {
		t.Fatalf("unexpected workflow overview: HTTP %d", overview.Code)
	}
	var preview *claudeWorkflowUsage
	for _, entry := range entries {
		if entry.ID == agentRowID {
			preview = entry.Usage
		}
	}
	if preview == nil || !preview.Partial || preview.RequestCount != 1 || preview.InputTokens != 85 || preview.OutputTokens != 3 {
		t.Fatalf("incorrect overview usage: %+v", preview)
	}
	usageURL := rootURL + "/agent/usage?entryId=" + url.QueryEscape(agentRowID)
	response := testRequest(a, http.MethodGet, usageURL, "")
	var usage claudeWorkflowUsage
	if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &usage) != nil || usage.ContextTokens == nil || *usage.ContextTokens != 85 ||
		usage.Partial || usage.RequestCount != 2 || usage.InputTokens != 166 || usage.OutputTokens != 7 || usage.CachedInputTokens != 140 || usage.CacheWriteTokens != 9 {
		t.Fatalf("incorrect detailed usage: HTTP %d %s", response.Code, response.Body)
	}
	unknown := testRequest(a, http.MethodGet, rootURL+"/agent/usage?entryId="+url.QueryEscape(agentRowID+"-unlisted"), "")
	if unknown.Code != http.StatusNotFound {
		t.Fatalf("unlisted agent returned HTTP %d", unknown.Code)
	}
	appendLine := `{"type":"assistant","agentId":"worker","message":{"id":"m3","usage":{"input_tokens":99,"output_tokens":5}}}` + "\n"
	writeClaudeWorkflowFixture(t, sessionDir, logPath, first+private+last+appendLine)
	refreshed := testRequest(a, http.MethodGet, usageURL, "")
	usage = claudeWorkflowUsage{}
	if refreshed.Code != http.StatusOK || json.Unmarshal(refreshed.Body.Bytes(), &usage) != nil || usage.ContextTokens == nil || *usage.ContextTokens != 99 ||
		usage.RequestCount != 3 || usage.InputTokens != 265 || usage.OutputTokens != 12 {
		t.Fatalf("updated log did not refresh usage: HTTP %d %s", refreshed.Code, refreshed.Body)
	}
}

func TestClaudeWorkflowUsageRecentPreservesCompleteFirstRow(t *testing.T) {
	path := filepath.Join(t.TempDir(), "agent.jsonl")
	if err := os.WriteFile(path, []byte("first row\nsecond row\nlast row\n"), 0600); err != nil {
		t.Fatal(err)
	}
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	const size = int64(len("first row\nsecond row\nlast row\n"))
	for _, test := range []struct {
		budget int64
		want   string
	}{
		{size, "first row\nsecond row\nlast row\n"},
		{size - int64(len("first row\n")), "second row\nlast row\n"},
		{size - int64(len("first row\ns")), "last row\n"},
	} {
		got, partial, err := readClaudeWorkflowRecent(f, size, test.budget)
		if err != nil || partial != (test.budget < size) || string(got) != test.want {
			t.Fatalf("budget %d: got %q, partial %v, error %v", test.budget, got, partial, err)
		}
	}
}
