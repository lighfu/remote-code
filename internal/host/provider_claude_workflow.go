package host

import (
	"encoding/json"
	"reflect"
	"slices"
	"strings"
)

func isClaudeTaskEvent(subtype string) bool {
	switch subtype {
	case "task_started", "task_progress", "task_updated", "task_notification", "background_tasks_changed":
		return true
	default:
		return false
	}
}

func claudeWorkflowTool(tool string) bool {
	return tool == "Agent" || tool == "Workflow"
}

func claudeTaskStatus(status string) string {
	switch status {
	case "pending", "running":
		return status
	case "completed", "success":
		return "done"
	case "failed", "error":
		return "error"
	case "stopped", "killed", "cancelled", "interrupted":
		return "cancelled"
	default:
		return ""
	}
}

func claudeTaskFinished(status string) bool {
	return status == "done" || status == "error" || status == "cancelled" || status == "declined"
}

func (s *Store) claudeTaskMessage(sid string, msg M) {
	s.mu.Lock()
	defer s.mu.Unlock()
	rec := s.records[sid]
	if rec == nil || s.closed {
		return
	}
	subtype := S(msg, "subtype")
	if subtype == "background_tasks_changed" {
		switch msg["tasks"].(type) {
		case []M, []any:
		default:
			return
		}
		active := map[string]bool{}
		for _, task := range providerRows(msg["tasks"]) {
			if !B(task, "ambient") {
				active[S(task, "task_id")] = true
			}
			s.updateClaudeTaskLocked(rec, task, "task_snapshot")
		}
		for _, entry := range rec.Entries {
			if B(entry, "claudeTaskInSnapshot") && !active[S(entry, "claudeTaskId")] {
				patch := M{"claudeTaskInSnapshot": false}
				if !claudeTaskFinished(S(entry, "status")) {
					patch["status"] = "unknown"
				}
				s.patchEntryLocked(sid, S(entry, "id"), patch, "")
			}
		}
		return
	}
	s.updateClaudeTaskLocked(rec, msg, subtype)
}

func (s *Store) updateClaudeTaskLocked(rec *Record, event M, subtype string) {
	taskID := S(event, "task_id")
	toolID := S(event, "tool_use_id")
	if B(event, "ambient") || taskID == "" && toolID == "" {
		return
	}
	parentTaskID := S(event, "parent_task_id")
	var taskEntry, toolEntry, parentEntry M
	fallbackID := "agent:" + taskID
	for _, entry := range rec.Entries {
		id := S(entry, "id")
		switch {
		case taskID != "" && (S(entry, "claudeTaskId") == taskID || id == fallbackID):
			taskEntry = entry
		case parentTaskID != "" && S(entry, "claudeTaskId") == parentTaskID:
			parentEntry = entry
		}
		if toolID != "" && id == toolID {
			toolEntry = entry
		}
	}
	tool := S(taskEntry, "tool")
	if tool == "" {
		tool = S(toolEntry, "tool")
	}
	switch S(event, "task_type") {
	case "local_workflow":
		tool = "Workflow"
	case "local_agent":
		tool = "Agent"
	case "remote_agent":
		if tool != "Workflow" {
			tool = "Agent"
		}
	case "":
		// Older starts identify the Agent through its tool call.
		if tool == "" && (S(event, "subagent_type") != "" || subtype == "task_started" && toolID != "") {
			tool = "Agent"
		}
	default:
		return
	}
	if !claudeWorkflowTool(tool) {
		return
	}
	parent := S(event, "parent_tool_use_id")
	if parent == "" && parentTaskID != "" && parentTaskID != taskID {
		parent = S(parentEntry, "id")
		if parent == "" {
			parent = "agent:" + parentTaskID
		}
	}
	// Earlier versions rendered every task as Agent, including Workflow runs.
	legacyWorkflow := tool == "Workflow" && S(toolEntry, "tool") == "Agent" && S(toolEntry, "claudeTaskId") == ""
	if toolID != "" && (toolID == parent || toolEntry != nil && (S(toolEntry, "tool") != tool && !legacyWorkflow || S(toolEntry, "claudeTaskId") != "" && S(toolEntry, "claudeTaskId") != taskID)) {
		// Synthetic children cannot overwrite the tool card their events refer to.
		toolID, toolEntry = "", nil
	}
	if taskEntry == nil {
		taskEntry = toolEntry
	}
	if toolID == "" && taskID == "" {
		return
	}
	entryID := S(taskEntry, "id")
	if entryID == "" {
		entryID = toolID
		if entryID == "" {
			entryID = fallbackID
		}
	}
	currentRun := S(taskEntry, "claudeTaskRunId")
	run := S(event, "run_id")
	if run != "" && currentRun != "" && run < currentRun {
		return
	}
	patch := M{"id": entryID, "kind": "tool", "tool": tool}
	if taskID != "" {
		patch["claudeTaskId"] = taskID
	}
	if run != "" {
		patch["claudeTaskRunId"] = run
	}
	if parentTaskID != "" && parentTaskID != taskID {
		patch["claudeParentTaskId"] = parentTaskID
	}
	if parent != "" && parent != entryID {
		patch["parent"] = parent
	}
	fields := event
	status := "running"
	if subtype == "task_updated" || subtype == "task_notification" {
		if subtype == "task_updated" {
			fields = providerMap(event["patch"])
		}
		status = claudeTaskStatus(S(fields, "status"))
	}
	newRun := run > currentRun && (currentRun != "" || subtype == "task_snapshot")
	if status != "" && (claudeTaskFinished(status) || !claudeTaskFinished(S(taskEntry, "status")) || newRun) {
		patch["status"] = status
	}
	if subtype == "task_snapshot" {
		patch["claudeTaskInSnapshot"] = true
	} else if claudeTaskFinished(status) && B(taskEntry, "claudeTaskInSnapshot") {
		patch["claudeTaskInSnapshot"] = false
	}
	if tool == "Workflow" && taskID != "" && subtype != "task_notification" {
		patch["claudeTaskLaunched"] = true
	}
	if title := S(fields, "description"); title != "" {
		patch["title"] = title
	} else if taskEntry == nil {
		patch["title"] = tool
	}
	if !claudeTaskFinished(S(taskEntry, "status")) || claudeTaskFinished(status) || newRun {
		if progress := S(event, "summary"); progress != "" || newRun {
			patch["progress"] = progress
		}
		if failure := S(fields, "error"); failure != "" {
			patch["progress"] = failure
		}
		if progress, exists := patch["progress"]; exists && progress != taskEntry["progress"] {
			if progress != "" {
				patch["activityAt"] = providerStamp(event["timestamp"])
			} else if N(taskEntry, "activityAt") > 0 {
				patch["activityAt"] = 0
			}
		}
	}
	if taskEntry == nil && S(event, "timestamp") != "" {
		patch["ts"] = providerStamp(event["timestamp"])
	}
	if taskEntry != nil && entryID == fallbackID && toolID != "" && toolID != entryID {
		// Identify the same task when a snapshot precedes its tool-use notification.
		// Preserve its timestamp and move its children to the canonical tool row.
		for key, value := range taskEntry {
			if _, changed := patch[key]; !changed && (toolEntry == nil || toolEntry[key] == nil) {
				patch[key] = value
			}
		}
		s.flushDeltasLocked(S(rec.Info, "id"), "")
		rec.Entries = slices.DeleteFunc(rec.Entries, func(entry M) bool { return S(entry, "id") == entryID })
		for _, child := range rec.Entries {
			if S(child, "parent") == entryID {
				child["parent"] = toolID
			}
		}
		s.emitLocked(M{"type": "entries_removed", "sessionId": S(rec.Info, "id"), "fromId": ""})
		entryID, taskEntry = toolID, toolEntry
		patch["id"] = entryID
	}
	sid := S(rec.Info, "id")
	if taskEntry == nil {
		s.addEntryLocked(sid, patch, false, false)
	} else {
		for key, value := range patch {
			if reflect.DeepEqual(taskEntry[key], value) {
				delete(patch, key)
			}
		}
		if len(patch) > 0 {
			s.patchEntryLocked(sid, entryID, patch, "")
		}
	}
	if taskID != "" && entryID != fallbackID {
		for _, child := range rec.Entries {
			childID := S(child, "id")
			if childID != entryID && S(child, "claudeParentTaskId") == taskID && S(child, "parent") == fallbackID {
				s.patchEntryLocked(sid, childID, M{"parent": entryID}, "")
			}
		}
	}
}

func (s *Store) claudeTaskLaunchResult(sid, toolID string, msg, block M) bool {
	if toolID == "" || B(block, "is_error") {
		return false
	}
	output := providerMap(msg["tool_use_result"])
	if len(output) == 0 {
		output = providerMap(msg["toolUseResult"])
	}
	if len(output) == 0 {
		text := strings.TrimSpace(providerText(block["content"]))
		if len(text) > 64*1024 || !strings.HasPrefix(text, "{") || json.Unmarshal([]byte(text), &output) != nil {
			return false
		}
	}
	if status := S(output, "status"); status != "async_launched" && status != "remote_launched" {
		return false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	rec := s.records[sid]
	if rec == nil || s.closed {
		return false
	}
	for _, entry := range rec.Entries {
		if S(entry, "id") != toolID || !claudeWorkflowTool(S(entry, "tool")) {
			continue
		}
		patch := M{"output": providerText(block["content"])}
		taskID := S(output, "taskId")
		tool := S(entry, "tool")
		if S(output, "taskType") == "local_workflow" || S(output, "workflowName") != "" {
			tool = "Workflow"
			patch["tool"] = tool
		}
		if tool == "Workflow" {
			if runID := S(output, "runId"); runID != "" {
				patch["claudeWorkflowRunId"] = runID
			}
			if name := S(output, "workflowName"); name != "" {
				patch["title"] = name
			}
		} else if taskID == "" {
			taskID = S(output, "agentId")
		}
		if taskID != "" {
			patch["claudeTaskId"] = taskID
		}
		if failure := S(output, "error"); failure != "" {
			patch["status"], patch["progress"] = "error", failure
		} else {
			patch["claudeTaskLaunched"] = true
			if taskID != "" {
				taskType := "local_agent"
				if tool == "Workflow" {
					taskType = "local_workflow"
				}
				s.updateClaudeTaskLocked(rec, M{"task_id": taskID, "tool_use_id": toolID, "task_type": taskType}, "task_launch")
			}
			if !claudeTaskFinished(S(entry, "status")) {
				patch["status"] = "running"
			}
		}
		s.patchEntryLocked(sid, toolID, patch, "")
		return true
	}
	return false
}
