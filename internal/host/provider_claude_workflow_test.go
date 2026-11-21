package host

import "testing"

func TestClaudeWorkflowPreservesNestedTaskRuns(t *testing.T) {
	store := providerTestStore(t)
	rec := store.Create(M{"provider": "claude", "status": "running"})
	sid := S(rec.Info, "id")
	p := NewProvider("claude", store, t.TempDir()).(*nativeProvider)
	r := &nativeSession{}
	send := func(message M) { p.claudeMessage(sid, r, message) }
	task := func(subtype string, fields M) {
		fields["type"], fields["subtype"] = "system", subtype
		send(fields)
	}
	tool := func(id, name string, input M) {
		send(M{"type": "assistant", "message": M{"id": "launch-" + id, "content": []M{{
			"type": "tool_use", "id": id, "name": name, "input": input,
		}}}})
	}
	assertEntry := func(id, wantTool, wantStatus, wantParent string) M {
		t.Helper()
		for _, entry := range store.Get(sid).Entries {
			if S(entry, "id") != id {
				continue
			}
			if S(entry, "tool") != wantTool || S(entry, "status") != wantStatus || S(entry, "parent") != wantParent {
				t.Fatalf("unexpected row for %s: %v", id, entry)
			}
			return entry
		}
		t.Fatalf("row %s missing", id)
		return nil
	}

	tool("workflow-tool", "Workflow", M{"name": "Review"})
	// A reconnect snapshot can introduce a child before the caller's tool entry arrives.
	task("background_tasks_changed", M{"tasks": []M{
		{"task_id": "branch", "task_type": "local_agent", "parent_task_id": "flow", "description": "Branch"},
		{"task_id": "grandchild", "task_type": "local_agent", "parent_task_id": "branch", "description": "Grandchild"},
		{"task_id": "bash", "task_type": "local_bash", "description": "Shell"},
		{"task_id": "watcher", "task_type": "local_agent", "ambient": true},
	}})
	assertEntry("agent:branch", "Agent", "running", "agent:flow")
	assertEntry("agent:grandchild", "Agent", "running", "agent:branch")
	send(M{"type": "user", "tool_use_result": M{"status": "async_launched", "taskId": "flow", "runId": "workflow-run"}, "message": M{"content": []M{{
		"type": "tool_result", "tool_use_id": "workflow-tool", "content": "Workflow launched",
	}}}})
	if entry := assertEntry("workflow-tool", "Workflow", "running", ""); S(entry, "claudeWorkflowRunId") != "workflow-run" {
		t.Fatalf("workflow run ID missing: %v", entry)
	}
	if len(store.Get(sid).Entries) != 3 {
		t.Fatalf("Non-agent tasks were turned into agents: %v", store.Get(sid).Entries)
	}
	assertEntry("agent:branch", "Agent", "running", "workflow-tool")
	tool("branch-tool", "Agent", M{"description": "Branch"})
	task("task_started", M{"task_type": "local_agent", "task_id": "branch", "parent_task_id": "flow", "tool_use_id": "branch-tool", "run_id": "01"})
	assertEntry("branch-tool", "Agent", "running", "workflow-tool")
	assertEntry("agent:grandchild", "Agent", "running", "branch-tool")
	if len(store.Get(sid).Entries) != 3 {
		t.Fatalf("Task appeared twice: %v", store.Get(sid).Entries)
	}
	send(M{"type": "result", "subtype": "success"})
	assertEntry("workflow-tool", "Workflow", "running", "")
	task("task_updated", M{"task_id": "branch", "run_id": "01", "patch": M{"status": "failed", "error": "Review failed"}})
	assertEntry("branch-tool", "Agent", "error", "workflow-tool")
	task("task_started", M{"task_id": "branch", "run_id": "02", "tool_use_id": "branch-tool"})
	task("task_notification", M{"task_id": "branch", "run_id": "01", "status": "failed"})
	assertEntry("branch-tool", "Agent", "running", "workflow-tool")
	task("task_notification", M{"task_id": "branch", "run_id": "02", "status": "stopped"})
	task("task_progress", M{"task_id": "branch", "run_id": "02", "summary": "Delayed update"})
	tool("branch-tool", "Agent", M{"description": "Branch"})
	assertEntry("branch-tool", "Agent", "cancelled", "workflow-tool")
	task("task_notification", M{"task_id": "flow", "status": "completed", "summary": "Done"})
	assertEntry("workflow-tool", "Workflow", "done", "")
	task("background_tasks_changed", M{"tasks": []M{}})
	assertEntry("agent:grandchild", "Agent", "unknown", "branch-tool")
	assertEntry("branch-tool", "Agent", "cancelled", "workflow-tool")
}

func TestClaudeRecognizesWorkflowLaunchErrorsInTranscript(t *testing.T) {
	store := providerTestStore(t)
	rec := store.Create(M{"provider": "claude"})
	sid := S(rec.Info, "id")
	p := NewProvider("claude", store, t.TempDir()).(*nativeProvider)
	rows := []M{
		{"type": "assistant", "uuid": "call", "_transcript_hash": "call", "message": M{"id": "assistant", "content": []M{{
			"type": "tool_use", "id": "workflow", "name": "Workflow", "input": M{"name": "Review"},
		}}}},
		{"type": "user", "uuid": "launch", "_transcript_hash": "launch", "toolUseResult": M{
			"status": "async_launched", "taskId": "ignored", "error": "Invalid script",
		}, "message": M{"content": []M{{"type": "tool_result", "tool_use_id": "workflow", "content": "Could not start"}}}},
	}
	p.readClaudeTranscript(rec, rows, nil, nil)
	entries := store.Get(sid).Entries
	if len(entries) != 1 || S(entries[0], "status") != "error" || S(entries[0], "progress") != "Invalid script" || B(entries[0], "claudeTaskLaunched") {
		t.Fatalf("Workflow launch failure lost: %v", entries)
	}
}

func TestClaudeRecoversWorkflowMetadataFromSavedTranscript(t *testing.T) {
	store := providerTestStore(t)
	rec := store.Create(M{"provider": "claude"})
	sid := S(rec.Info, "id")
	p := NewProvider("claude", store, t.TempDir()).(*nativeProvider)
	store.Add(sid, M{"id": "workflow", "kind": "tool", "tool": "Agent", "status": "done"})
	store.Add(sid, M{"id": "reply-0", "kind": "assistant", "text": "Started review"})
	rows := []M{
		{"type": "assistant", "uuid": "call", "_transcript_hash": "call", "message": M{"id": "assistant", "content": []M{{
			"type": "tool_use", "id": "workflow", "name": "Workflow", "input": M{"name": "Review"},
		}}}},
		{"type": "user", "uuid": "launch", "_transcript_hash": "launch", "toolUseResult": M{
			"status": "async_launched", "taskType": "local_workflow", "taskId": "task", "runId": "wf_review", "workflowName": "Review",
		}, "message": M{"content": []M{{"type": "tool_result", "tool_use_id": "workflow", "content": "Workflow started"}}}},
		{"type": "assistant", "uuid": "reply", "_transcript_hash": "reply", "message": M{"id": "reply", "content": []M{{
			"type": "text", "text": "Started review",
		}}}},
	}
	p.readClaudeTranscript(rec, rows, nil, nil)
	entries := store.Get(sid).Entries
	if len(entries) != 2 || S(entries[0], "tool") != "Workflow" || S(entries[0], "claudeWorkflowRunId") != "wf_review" || S(entries[0], "claudeTaskId") != "task" {
		t.Fatalf("Workflow identity not recovered: %v", entries)
	}
}

func TestClaudeTaskEventsDoNotReplaceTranscriptHead(t *testing.T) {
	store := providerTestStore(t)
	rec := store.Create(M{"provider": "claude"})
	sid := S(rec.Info, "id")
	p := NewProvider("claude", store, t.TempDir()).(*nativeProvider)
	path := claudeIdentityFixtureFile(t, []M{
		{"type": "user", "uuid": "prompt", "message": M{"content": "Review files"}},
		{"type": "assistant", "uuid": "call", "parentUuid": "prompt", "message": M{"id": "assistant", "content": []M{{
			"type": "tool_use", "id": "agent", "name": "Agent", "input": M{"description": "Review"},
		}}}},
		{"type": "system", "subtype": "task_started", "uuid": "event", "task_type": "local_agent", "task_id": "parent", "tool_use_id": "agent"},
		{"type": "system", "subtype": "task_started", "uuid": "child", "task_type": "local_agent", "task_id": "child", "parent_task_id": "parent"},
	})
	if err := p.readTranscript(rec, path); err != nil {
		t.Fatal(err)
	}
	entries := store.Get(sid).Entries
	if len(entries) != 3 || S(entries[0], "id") != "prompt" || S(entries[1], "id") != "agent" || S(entries[2], "parent") != "agent" {
		t.Fatalf("Transcript lost the conversation or hierarchy: %v", entries)
	}
}
