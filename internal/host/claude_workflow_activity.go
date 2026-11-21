package host

import (
	"bufio"
	"bytes"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

const (
	claudeWorkflowDetailWorkers = 256
	claudeWorkflowPromptBytes   = 32 << 10
	claudeWorkflowActivityBytes = 128 << 10
	claudeWorkflowDetailRefresh = time.Minute
)

var claudeWorkflowAgentID = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_-]{0,159}$`)

func claudeWorkflowWorkerLogPath(journal, agentID string) string {
	if journal == "" || !claudeWorkflowAgentID.MatchString(agentID) {
		return ""
	}
	return filepath.Join(filepath.Dir(journal), "agent-"+agentID+".jsonl")
}

func claudeWorkflowLocalAgentLogPath(agentID string) string {
	if !claudeWorkflowAgentID.MatchString(agentID) {
		return ""
	}
	return filepath.Join("subagents", "agent-"+agentID+".jsonl")
}

type claudeWorkflowActivityCache struct {
	mu            sync.Mutex
	sessionDir    string
	workers       map[string]claudeWorkflowLogActivity
	detailedUsage claudeWorkflowUsageDetailCache
}

type claudeWorkflowLogActivity struct {
	modified, checked time.Time
	size              int64
	prompt, latest    string
	result            string
	at                int64
	active            bool
	usage             *claudeWorkflowUsage
}

func (cache *claudeWorkflowActivityCache) enrich(sessionDir string, runs []claudeWorkflowRun, entries []M) {
	localAgents := claudeWorkflowLocalAgents(entries, runs)
	if len(runs) == 0 && len(localAgents) == 0 {
		return
	}
	root, err := os.OpenRoot(sessionDir)
	if err != nil {
		return
	}
	defer root.Close()
	cache.mu.Lock()
	defer cache.mu.Unlock()
	if cache.sessionDir != sessionDir {
		cache.sessionDir, cache.workers = sessionDir, nil
	}
	previous := cache.workers
	cache.workers = make(map[string]claudeWorkflowLogActivity)
	now := time.Now()
	read := func(path, id string, active bool) claudeWorkflowLogActivity {
		activity := previous[path]
		if active || activity.active || now.Sub(activity.checked) >= claudeWorkflowDetailRefresh {
			activity = readClaudeWorkflowLogActivity(root, path, id, activity, now)
		}
		activity.active = active
		cache.workers[path] = activity
		return activity
	}
	// Give active workers first access to the bounded transcript cache.
	for _, active := range []bool{true, false} {
		for i := range runs {
			run := &runs[i]
			journal := claudeWorkflowAgentJournal(run.ID)
			for j := range run.Workers {
				worker := &run.Workers[j]
				if (worker.Status == "running") != active {
					continue
				}
				path := claudeWorkflowWorkerLogPath(journal, worker.ID)
				if path == "" {
					continue
				}
				activity := read(path, worker.ID, active)
				worker.Detail, worker.Progress = activity.prompt, activity.latest
				worker.Usage = activity.usage
				if worker.Output == "" && worker.Status == "done" {
					worker.Output = activity.result
				}
				worker.ActivityAt = max(worker.ActivityAt, activity.at)
				if len(cache.workers) >= claudeWorkflowDetailWorkers {
					return
				}
			}
		}
		for i := len(entries) - 1; i >= 0; i-- {
			entry := entries[i]
			id := S(entry, "claudeTaskId")
			if localAgents[id] != S(entry, "id") || id == "" || (claudeWorkflowPhaseState(S(entry, "status")) == claudePhaseRunning) != active {
				continue
			}
			path := claudeWorkflowLocalAgentLogPath(id)
			if path == "" {
				continue
			}
			activity := read(path, id, active)
			if activity.usage != nil {
				entry["agentUsage"] = activity.usage
			}
			if activity.prompt != "" && S(entry, "detail") == "" {
				entry["detail"] = activity.prompt
			}
			if activity.latest != "" && (S(entry, "progress") == "" || float64(activity.at) > N(entry, "activityAt")) {
				entry["progress"] = activity.latest
				if activity.at > 0 {
					entry["activityAt"] = activity.at
				}
			}
			if activity.result != "" && S(entry, "status") == "done" && S(entry, "output") == "" {
				entry["output"] = activity.result
			}
			if len(cache.workers) >= claudeWorkflowDetailWorkers {
				return
			}
		}
	}
}

func claudeWorkflowLocalAgents(entries []M, runs []claudeWorkflowRun) map[string]string {
	agents := make(map[string]string)
	for _, entry := range entries {
		if id := S(entry, "claudeTaskId"); id != "" && isClaudeWorkflowAgent(entry) {
			if _, duplicate := agents[id]; duplicate {
				agents[id] = ""
			} else {
				agents[id] = S(entry, "id")
			}
		}
	}
	for _, run := range runs {
		for _, worker := range run.Workers {
			delete(agents, worker.ID)
		}
	}
	return agents
}

func readClaudeWorkflowLogActivity(root *os.Root, path, id string, previous claudeWorkflowLogActivity, now time.Time) claudeWorkflowLogActivity {
	info, err := root.Lstat(path)
	if err != nil || !info.Mode().IsRegular() {
		return claudeWorkflowLogActivity{checked: now}
	}
	if previous.size == info.Size() && previous.modified.Equal(info.ModTime()) {
		previous.checked = now
		return previous
	}
	file, size := claudeWorkflowOpen(root, path)
	if file == nil {
		return previous
	}
	defer file.Close()
	activity := claudeWorkflowLogActivity{size: size, modified: info.ModTime(), checked: now}
	if size > previous.size {
		activity.prompt, activity.latest, activity.result, activity.at = previous.prompt, previous.latest, previous.result, previous.at
		activity.usage = previous.usage
	}
	if activity.prompt == "" {
		activity.prompt = claudeWorkflowLogPrompt(io.NewSectionReader(file, 0, min(size, claudeWorkflowPromptBytes)), id)
	}
	if tail, partial, err := readClaudeWorkflowRecent(file, size, claudeWorkflowActivityBytes); err == nil {
		readClaudeWorkflowLogTail(tail, id, &activity, partial)
	}
	return activity
}

func claudeWorkflowLogPrompt(input io.Reader, id string) string {
	scanner := bufio.NewScanner(input)
	scanner.Buffer(make([]byte, 4<<10), claudeWorkflowPromptBytes+1)
	for scanner.Scan() {
		row, blocks, ok := parseClaudeAgentLogLine(scanner.Bytes())
		if !ok || row.Type != "user" || row.Parent != "" || row.AgentID != "" && row.AgentID != id {
			continue
		}
		var text []string
		for _, block := range blocks {
			if block.Type == "text" {
				text = append(text, block.Text)
			}
		}
		if prompt := strings.TrimSpace(strings.Join(text, "\n")); prompt != "" {
			return providerTruncate(prompt, claudeWorkflowAgentDetailLimit)
		}
	}
	return ""
}

func readClaudeWorkflowLogTail(data []byte, id string, activity *claudeWorkflowLogActivity, partial bool) {
	var samples claudeWorkflowUsageSamples
	for line := range bytes.Lines(data) {
		if !bytes.Contains(line, []byte(`"assistant"`)) {
			continue
		}
		row, blocks, ok := parseClaudeAgentLogLine(line)
		if row.Type != "assistant" || row.AgentID != "" && row.AgentID != id {
			continue
		}
		samples.addRequest(row.Message.ID, row.UUID, row.Message.Usage)
		if !ok {
			continue
		}
		for _, block := range blocks {
			latest := ""
			switch block.Type {
			case "text":
				latest = strings.TrimSpace(block.Text)
				if latest != "" && row.Message.StopReason == "end_turn" {
					activity.result = providerTruncate(latest, claudeWorkflowAgentResultLimit)
				}
			case "tool_use":
				latest = claudeWorkflowToolActivity(block.Name, block.Input.Description, block.Input.FilePath, block.Input.Path)
			}
			if latest != "" {
				activity.latest = providerTruncate(latest, claudeWorkflowAgentActivityLimit)
				activity.at = claudeWorkflowTimestamp(row.Timestamp)
			}
		}
	}
	if usage := samples.summary(partial); usage != nil {
		activity.usage = usage
	}
}

func claudeWorkflowToolActivity(name, description string, paths ...string) string {
	if name == "" {
		return ""
	}
	description = strings.TrimSpace(description)
	if description == "" {
		for _, path := range paths {
			if path = strings.TrimRight(path, `/\`); path != "" {
				description = path[strings.LastIndexAny(path, `/\`)+1:]
				break
			}
		}
	}
	if description != "" && description != name {
		return name + " · " + description
	}
	return name
}
