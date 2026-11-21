package host

import (
	"path/filepath"
	"sort"
)

const (
	claudeWorkflowAgentDetailLimit   = 1_000
	claudeWorkflowAgentActivityLimit = 240
	claudeWorkflowAgentResultLimit   = 1_200
)

func isClaudeWorkflowEntry(entry M) bool {
	if S(entry, "kind") != "tool" {
		return false
	}
	switch S(entry, "tool") {
	case "Agent", "Task", "Workflow":
		return true
	}
	return false
}

func (a *App) claudeWorkflow(sid string) ([]M, error) {
	external, entries, found := a.Store.claudeWorkflowSnapshot(sid)
	if !found {
		return nil, &HTTPError{404, "Claude conversation not found"}
	}
	var runs []claudeWorkflowRun
	if provider, ok := a.Providers["claude"].(*nativeProvider); ok {
		if sessionDir := provider.claudeWorkflowSessionDir(external); sessionDir != "" {
			runs = readClaudeWorkflowRuns(sessionDir)
			provider.claudeWorkflow.enrich(sessionDir, runs, entries)
		}
	}
	return reconcileClaudeWorkflow(entries, runs), nil
}

func (p *nativeProvider) claudeWorkflowSessionDir(external string) string {
	if claudeWorkflowAgentID.MatchString(external) {
		if transcript := p.transcriptPath(external); transcript != "" {
			return filepath.Join(filepath.Dir(transcript), external)
		}
	}
	return ""
}

// Copy only workflow metadata and the prompt and ancestry needed by the view.
func (s *Store) claudeWorkflowSnapshot(sid string) (string, []M, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	record := s.records[sid]
	if record == nil || S(record.Info, "provider") != "claude" {
		return "", nil, false
	}
	entries := make([]M, 0)
	byID := make(map[string]M, len(record.Entries))
	workByID := make(map[string]M)
	included := make(map[string]bool)
	for _, entry := range record.Entries {
		id := S(entry, "id")
		byID[id] = entry
		if id == "" || !(isClaudeWorkflowEntry(entry) || S(entry, "kind") == "user" && S(entry, "parent") == "") {
			continue
		}
		projected := M{"id": id, "kind": S(entry, "kind"), "ts": N(entry, "ts")}
		for _, field := range []string{"parent", "tool", "title", "status", "messageOrigin", "claudeTaskId", "claudeWorkflowRunId"} {
			if value := S(entry, field); value != "" {
				projected[field] = value
			}
		}
		for _, field := range []string{"detail", "progress", "output", "text"} {
			if value := S(entry, field); value != "" {
				limit := 4_000
				switch {
				case field == "progress":
					limit = claudeWorkflowAgentActivityLimit
				case field == "text":
					limit = 600
				case field == "detail" && isClaudeWorkflowAgent(entry):
					limit = claudeWorkflowAgentDetailLimit
				case field == "output" && isClaudeWorkflowAgent(entry):
					limit = claudeWorkflowAgentResultLimit
				}
				projected[field] = providerTruncate(value, limit)
			}
		}
		for _, field := range []string{"streaming", "ok"} {
			if value, ok := entry[field].(bool); ok {
				projected[field] = value
			}
		}
		if stamp := N(entry, "activityAt"); stamp > 0 {
			projected["activityAt"] = stamp
		}
		if isClaudeWorkflowEntry(entry) {
			workByID[id] = projected
		}
		entries = append(entries, projected)
		included[id] = true
	}
	addClaudeWorkflowTimelineActivity(record.Entries, workByID)
	for _, entry := range entries {
		for parent := S(entry, "parent"); parent != "" && !included[parent]; {
			original := byID[parent]
			if original == nil {
				break
			}
			ancestor := S(original, "parent")
			entries = append(entries, M{"id": parent, "kind": "tool", "parent": ancestor})
			included[parent] = true
			parent = ancestor
		}
	}
	return S(record.Info, "externalId"), entries, true
}

// Direct assistant text and tool activity also cover agents whose conversation
// is streamed into the parent's timeline instead of a workflow journal.
func addClaudeWorkflowTimelineActivity(entries []M, workByID map[string]M) {
	seenActivity := make(map[string]bool)
	for i := len(entries) - 1; i >= 0; i-- {
		entry := entries[i]
		parentID := S(entry, "parent")
		parent := workByID[parentID]
		if parent == nil {
			continue
		}
		activity := ""
		stamp := N(entry, "ts")
		switch S(entry, "kind") {
		case "assistant":
			activity = S(entry, "text")
			if activity != "" && !seenActivity[parentID] && S(parent, "status") == "done" && S(parent, "output") == "" && !B(entry, "streaming") {
				parent["output"] = providerTruncate(activity, claudeWorkflowAgentResultLimit)
			}
		case "tool":
			description := S(entry, "text")
			if isClaudeWorkflowEntry(entry) {
				description = S(entry, "title")
			}
			activity = claudeWorkflowToolActivity(S(entry, "tool"), description, S(entry, "path"))
		}
		if activity != "" {
			seenActivity[parentID] = true
			if stamp > N(parent, "activityAt") || S(parent, "progress") == "" {
				parent["progress"] = providerTruncate(activity, claudeWorkflowAgentActivityLimit)
				if stamp > 0 {
					parent["activityAt"] = stamp
				}
			}
		}
	}
}

func reconcileClaudeWorkflow(entries []M, runs []claudeWorkflowRun) []M {
	workflowByRun, workflowByTask, agentByTask := map[string]M{}, map[string]M{}, map[string]M{}
	for _, entry := range entries {
		if S(entry, "tool") == "Workflow" {
			if run := S(entry, "claudeWorkflowRunId"); run != "" {
				workflowByRun[run] = entry
			} else if task := S(entry, "claudeTaskId"); task != "" {
				workflowByTask[task] = entry
			}
		} else if task := S(entry, "claudeTaskId"); task != "" {
			agentByTask[task] = entry
		}
	}
	for _, run := range runs {
		root := workflowByRun[run.ID]
		if root == nil && run.TaskID != "" {
			root = workflowByTask[run.TaskID]
			delete(workflowByTask, run.TaskID)
		}
		if root == nil {
			root = M{"id": "claude-workflow:" + run.ID, "kind": "tool", "tool": "Workflow", "ts": run.StartedAt}
			entries = append(entries, root)
		}
		if run.Name != "" {
			root["title"] = providerTruncate(run.Name, 200)
		}
		if run.Status != "" && run.Status != "unknown" || S(root, "status") == "" {
			root["status"] = run.Status
		}
		if run.Summary != "" {
			root["output"] = providerTruncate(run.Summary, 4_000)
		}
		rootID := S(root, "id")
		prefix := "claude-workflow:" + run.ID
		phases := make(map[string]M)
		phaseStates := make(map[string]claudeWorkflowPhaseStates)
		for _, phase := range run.Phases {
			if phase.Title == "" || phases[phase.Title] != nil {
				continue
			}
			entry := workflowPhase(prefix, rootID, phase.Title, run.StartedAt)
			entry["detail"] = providerTruncate(phase.Detail, 4_000)
			phases[phase.Title] = entry
			entries = append(entries, entry)
		}
		for _, worker := range run.Workers {
			parent := rootID
			var phase M
			if worker.Phase != "" {
				phase = phases[worker.Phase]
				if phase == nil {
					phase = workflowPhase(prefix, rootID, worker.Phase, run.StartedAt)
					phases[worker.Phase] = phase
					entries = append(entries, phase)
				}
				parent = S(phase, "id")
			}
			entry := agentByTask[worker.ID]
			if entry == nil {
				entry = M{"id": prefix + ":agent:" + worker.ID, "kind": "tool", "tool": "Agent", "ts": run.StartedAt}
				entries = append(entries, entry)
			}
			if existing := S(entry, "parent"); existing == "" || existing == rootID {
				entry["parent"] = parent
			}
			if worker.Label != "" {
				entry["title"] = worker.Label
			}
			entry["claudeWorkflowRunId"] = run.ID
			if claudeWorkflowAgentID.MatchString(worker.ID) {
				entry["claudeWorkflowAgentId"] = worker.ID
			}
			if worker.Status != "" && worker.Status != "unknown" || S(entry, "status") == "" {
				entry["status"] = worker.Status
			}
			if phase != nil && S(entry, "parent") == parent {
				phaseStates[worker.Phase] |= claudeWorkflowPhaseState(S(entry, "status"))
			}
			if worker.Detail != "" && S(entry, "detail") == "" {
				entry["detail"] = worker.Detail
			}
			if worker.Usage != nil {
				entry["agentUsage"] = worker.Usage
			}
			if float64(worker.ActivityAt) > N(entry, "activityAt") || S(entry, "progress") == "" {
				if worker.Progress != "" {
					entry["progress"] = worker.Progress
				}
				if worker.ActivityAt > 0 {
					entry["activityAt"] = worker.ActivityAt
				}
			}
			if worker.Output != "" {
				entry["output"] = providerTruncate(worker.Output, claudeWorkflowAgentResultLimit)
			}
		}
		for name, states := range phaseStates {
			if status := states.status(claudeTaskFinished(S(root, "status"))); status != "" {
				phases[name]["status"] = status
			}
		}
	}
	// On-disk runs outside the stored timeline still group under the closest preceding prompt.
	sort.SliceStable(entries, func(i, j int) bool { return N(entries[i], "ts") < N(entries[j], "ts") })
	return entries
}

type claudeWorkflowPhaseStates uint8

const (
	claudePhaseRunning claudeWorkflowPhaseStates = 1 << iota
	claudePhaseUnknown
	claudePhaseFailed
	claudePhaseCancelled
	claudePhaseDone
)

func claudeWorkflowPhaseState(status string) claudeWorkflowPhaseStates {
	switch status {
	case "running", "pending", "waiting", "queued", "awaiting_input", "awaiting_approval", "paused":
		return claudePhaseRunning
	case "error":
		return claudePhaseFailed
	case "cancelled", "declined":
		return claudePhaseCancelled
	case "done":
		return claudePhaseDone
	default:
		return claudePhaseUnknown
	}
}

func (states claudeWorkflowPhaseStates) status(runFinished bool) string {
	switch {
	case states&claudePhaseRunning != 0:
		return "running"
	case !runFinished || states&claudePhaseUnknown != 0:
		return ""
	case states&claudePhaseFailed != 0:
		return "error"
	case states&claudePhaseCancelled != 0:
		return "cancelled"
	case states == claudePhaseDone:
		return "done"
	default:
		return ""
	}
}

func workflowPhase(prefix, parent, name string, startedAt int64) M {
	return M{"id": prefix + ":phase:" + name, "kind": "tool", "tool": "WorkflowPhase", "parent": parent, "title": name, "ts": startedAt}
}
