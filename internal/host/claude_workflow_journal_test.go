package host

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestClaudeWorkflowReadsArchivedAndJournalOnlyRuns(t *testing.T) {
	root := t.TempDir()
	writeClaudeWorkflowFixture(t, root, "workflows/wf_finished.json", `{
  "runId":"wf_finished","workflowName":"Parallel review","taskId":"task-1","startTime":1794875678391,
  "status":"completed","agentCount":3,"phases":[{"title":"Review","detail":"Checks"}],"summary":"All branches resolved"
}`)
	writeClaudeWorkflowFixture(t, root, "subagents/workflows/wf_finished/journal.jsonl", `
{"type":"launched"}
{"type":"started","agentId":"a1","key":"first","label":"Check imports","phase":"Review"}
{"type":"started","agentId":"a2","key":"second","label":"Check resources","phase":"Review"}
{"type":"result","agentId":"a1","key":"first","result":{"files":2}}
{"type":"failed","agentId":"a2","key":"second","error":"Build failed"}
{"type":"started","agentId":"a1","key":"first"}
{"type":"started","agentId":"a3","key":"sparse","phase":"Review"}
{"type":"resu`)
	writeClaudeWorkflowFixture(t, root, "subagents/workflows/wf_active/journal.jsonl", `
{"type":"launched","workflowName":"Independent check","timestamp":"2026-11-19T22:00:01Z"}
{"type":"started","agentId":"a4","key":"working","label":"Build UI"}`)
	stale := writeClaudeWorkflowFixture(t, root, "subagents/workflows/wf_stale/journal.jsonl", `{"type":"started","agentId":"a5","key":"stale"}`)
	old := time.Now().Add(-7 * time.Hour)
	if err := os.Chtimes(stale, old, old); err != nil {
		t.Fatal(err)
	}
	writeClaudeWorkflowFixture(t, root, "workflows/wf_cancelled.json", `{"runId":"wf_cancelled","status":"killed"}`)
	writeClaudeWorkflowFixture(t, root, "workflows/wf_mismatch.json", `{"runId":"wf_wrong","status":"completed"}`)
	writeClaudeWorkflowFixture(t, root, "workflows/not-a-workflow.json", `{"runId":"not-a-workflow","status":"completed"}`)

	runs := map[string]claudeWorkflowRun{}
	for _, run := range readClaudeWorkflowRuns(root) {
		runs[run.ID] = run
	}
	if len(runs) != 4 {
		t.Fatalf("expected four valid runs, got %#v", runs)
	}
	finished := runs["wf_finished"]
	if finished.Status != "done" || finished.TaskID != "task-1" || finished.Name != "Parallel review" ||
		finished.StartedAt != 1794875678391 || finished.Summary != "All branches resolved" ||
		finished.AgentCount != 3 || len(finished.Phases) != 1 || finished.Phases[0].Title != "Review" {
		t.Fatalf("unexpected archived run: %#v", finished)
	}
	if len(finished.Workers) != 3 || finished.Workers[0] != (claudeWorkflowWorker{ID: "a1", Label: "Check imports", Phase: "Review", Status: "done", Output: `{"files":2}`}) ||
		finished.Workers[1].Status != "error" || finished.Workers[1].Output != "Build failed" || finished.Workers[2].Status != "unknown" {
		t.Fatalf("unexpected archived workers: %#v", finished.Workers)
	}
	active := runs["wf_active"]
	if active.Name != "Independent check" || active.StartedAt != (time.Date(2026, 11, 19, 22, 0, 1, 0, time.UTC).UnixMilli()) || active.Status != "running" ||
		len(active.Workers) != 1 || active.Workers[0].Status != "running" || active.Workers[0].Phase != "" {
		t.Fatalf("unexpected journal-only run: %#v", active)
	}
	if staleRun := runs["wf_stale"]; staleRun.Status != "unknown" || len(staleRun.Workers) != 1 || staleRun.Workers[0].Status != "unknown" {
		t.Fatalf("stale journal looked active: %#v", staleRun)
	}
	if runs["wf_cancelled"].Status != "cancelled" {
		t.Fatalf("killed status was not retained: %#v", runs["wf_cancelled"])
	}
}

func TestClaudeWorkflowJournalContinuesPastOversizedRows(t *testing.T) {
	root := t.TempDir()
	writeClaudeWorkflowFixture(t, root, "subagents/workflows/wf_large/journal.jsonl", "{\"type\":\"ignored\",\"detail\":\""+
		strings.Repeat("x", claudeWorkflowLineBytes)+"\"}\n"+`{"type":"started","key":"check","label":"Run checks"}`+"\n"+
		`{"type":"result","agentId":"resolved","key":"check","result":"done"}`)

	runs := readClaudeWorkflowRuns(root)
	if len(runs) != 1 || len(runs[0].Workers) != 1 || runs[0].Workers[0] != (claudeWorkflowWorker{ID: "resolved", Label: "Run checks", Status: "done", Output: "done"}) {
		t.Fatalf("rows following an oversized event were lost: %#v", runs)
	}
}

func TestClaudeWorkflowJournalCannotReadLinksOutsideSession(t *testing.T) {
	root, outside := t.TempDir(), t.TempDir()
	externalFile := writeClaudeWorkflowFixture(t, outside, "wf_external.json", `{"runId":"wf_external","status":"completed"}`)
	workflows := filepath.Join(root, "workflows")
	if err := os.Mkdir(workflows, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(externalFile, filepath.Join(workflows, "wf_external.json")); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}
	if runs := readClaudeWorkflowRuns(root); len(runs) != 0 {
		t.Fatalf("read workflow data outside the session: %#v", runs)
	}
}

func writeClaudeWorkflowFixture(t *testing.T, root, relative, data string) string {
	t.Helper()
	path := filepath.Join(root, filepath.FromSlash(relative))
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(data), 0600); err != nil {
		t.Fatal(err)
	}
	return path
}
