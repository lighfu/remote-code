package host

import (
	"bufio"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	claudeWorkflowMaxRuns      = 64
	claudeWorkflowMaxWorkers   = 1024
	claudeWorkflowDirEntries   = 512
	claudeWorkflowSummaryBytes = 1 << 20
	claudeWorkflowJournalBytes = 4 << 20
	claudeWorkflowReadBudget   = 16 << 20
	claudeWorkflowLineBytes    = 512 << 10
	claudeWorkflowActiveAge    = 6 * time.Hour
)

var claudeWorkflowRunID = regexp.MustCompile(`^wf_[A-Za-z0-9][A-Za-z0-9_-]{0,92}$`)

type claudeWorkflowRun struct {
	ID, TaskID, Name, Status, Summary string
	StartedAt                         int64
	AgentCount                        int
	Phases                            []claudeWorkflowPhase
	Workers                           []claudeWorkflowWorker
}

type claudeWorkflowPhase struct {
	Title  string `json:"title"`
	Detail string `json:"detail"`
}

type claudeWorkflowWorker struct {
	ID, Label, Phase, Status, Output string
	Detail, Progress                 string
	ActivityAt                       int64
	Usage                            *claudeWorkflowUsage
}

type claudeWorkflowFiles struct {
	id      string
	summary string
	journal string
	started time.Time
	updated time.Time
}

type claudeWorkflowSummary struct {
	ID         string                `json:"runId"`
	TaskID     string                `json:"taskId"`
	Name       string                `json:"workflowName"`
	Status     string                `json:"status"`
	StartTime  json.RawMessage       `json:"startTime"`
	Timestamp  json.RawMessage       `json:"timestamp"`
	Summary    json.RawMessage       `json:"summary"`
	Result     json.RawMessage       `json:"result"`
	Error      json.RawMessage       `json:"error"`
	AgentCount int                   `json:"agentCount"`
	Phases     []claudeWorkflowPhase `json:"phases"`
}

type claudeWorkflowEvent struct {
	Type      string          `json:"type"`
	Name      string          `json:"workflowName"`
	TaskID    string          `json:"taskId"`
	StartTime json.RawMessage `json:"startTime"`
	Timestamp json.RawMessage `json:"timestamp"`
	TS        json.RawMessage `json:"ts"`
	AgentID   string          `json:"agentId"`
	Key       string          `json:"key"`
	Label     string          `json:"label"`
	Phase     string          `json:"phase"`
	Result    json.RawMessage `json:"result"`
	Error     json.RawMessage `json:"error"`
}

// Claude journals are read only when the workflow view requests them. All paths
// below are relative to the session directory and constrained by os.Root.
func readClaudeWorkflowRuns(sessionDir string) []claudeWorkflowRun {
	root, err := os.OpenRoot(sessionDir)
	if err != nil {
		return nil
	}
	defer root.Close()
	files := make(map[string]claudeWorkflowFiles)
	for _, entry := range claudeWorkflowReadDir(root, "workflows") {
		name := entry.Name()
		id, ok := strings.CutSuffix(name, ".json")
		if !ok || !claudeWorkflowRunID.MatchString(id) || !entry.Type().IsRegular() {
			continue
		}
		if info, err := entry.Info(); err == nil && info.Mode().IsRegular() {
			files[id] = claudeWorkflowFiles{id: id, summary: filepath.Join("workflows", name), updated: info.ModTime()}
		}
	}
	journalDir := filepath.Join("subagents", "workflows")
	for _, entry := range claudeWorkflowReadDir(root, journalDir) {
		id := entry.Name()
		if !entry.IsDir() || !claudeWorkflowRunID.MatchString(id) {
			continue
		}
		journal := filepath.Join(journalDir, id, "journal.jsonl")
		if info, err := root.Lstat(journal); err == nil && info.Mode().IsRegular() {
			file := files[id]
			file.id, file.journal = id, journal
			if dir, err := entry.Info(); err == nil {
				file.started = dir.ModTime()
			}
			if info.ModTime().After(file.updated) {
				file.updated = info.ModTime()
			}
			files[id] = file
		}
	}
	ordered := make([]claudeWorkflowFiles, 0, len(files))
	for _, file := range files {
		ordered = append(ordered, file)
	}
	sort.Slice(ordered, func(i, j int) bool {
		if ordered[i].updated.Equal(ordered[j].updated) {
			return ordered[i].id < ordered[j].id
		}
		return ordered[i].updated.After(ordered[j].updated)
	})
	budget, now := int64(claudeWorkflowReadBudget), time.Now()
	runs := make([]claudeWorkflowRun, 0, min(len(ordered), claudeWorkflowMaxRuns))
	for _, file := range ordered[:min(len(ordered), claudeWorkflowMaxRuns)] {
		if budget <= 0 {
			break
		}
		if run, ok := readClaudeWorkflowRun(root, file, now.Sub(file.updated) <= claudeWorkflowActiveAge, &budget); ok {
			runs = append(runs, run)
		}
	}
	return runs
}

func claudeWorkflowReadDir(root *os.Root, path string) []os.DirEntry {
	if info, err := root.Stat(path); err != nil || !info.IsDir() {
		return nil
	}
	dir, err := root.Open(path)
	if err != nil {
		return nil
	}
	defer dir.Close()
	entries, _ := dir.ReadDir(claudeWorkflowDirEntries)
	return entries
}

func claudeWorkflowOpen(root *os.Root, path string) (*os.File, int64) {
	if path == "" {
		return nil, 0
	}
	if info, err := root.Lstat(path); err != nil || !info.Mode().IsRegular() {
		return nil, 0
	}
	file, err := root.Open(path)
	if err != nil {
		return nil, 0
	}
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() {
		file.Close()
		return nil, 0
	}
	return file, info.Size()
}

func readClaudeWorkflowRun(root *os.Root, files claudeWorkflowFiles, recent bool, budget *int64) (claudeWorkflowRun, bool) {
	run := claudeWorkflowRun{ID: files.id, Status: "unknown"}
	hasSummary := false
	file, size := claudeWorkflowOpen(root, files.summary)
	if file != nil {
		limit := min(*budget, claudeWorkflowSummaryBytes)
		if size <= limit {
			data, err := io.ReadAll(io.LimitReader(file, limit))
			*budget -= int64(len(data))
			var summary claudeWorkflowSummary
			if err == nil && json.Unmarshal(data, &summary) == nil && summary.ID == files.id {
				run.TaskID = providerTruncate(summary.TaskID, 160)
				run.Name = providerTruncate(summary.Name, 240)
				run.Status = claudeWorkflowStatus(summary.Status)
				run.StartedAt = claudeWorkflowTimestamp(summary.StartTime, summary.Timestamp)
				run.Summary = claudeWorkflowText(summary.Summary, summary.Result, summary.Error)
				run.AgentCount = max(0, summary.AgentCount)
				for _, phase := range summary.Phases[:min(len(summary.Phases), claudeWorkflowMaxWorkers)] {
					if phase.Title != "" {
						run.Phases = append(run.Phases, claudeWorkflowPhase{providerTruncate(phase.Title, 240), providerTruncate(phase.Detail, 2000)})
					}
				}
				hasSummary = true
			}
		}
		file.Close()
	}
	journal, size := claudeWorkflowOpen(root, files.journal)
	hasJournal := false
	if journal != nil {
		limit := min(*budget, claudeWorkflowJournalBytes)
		if limit > 0 {
			input := &io.LimitedReader{R: journal, N: limit}
			hasJournal = readClaudeWorkflowEvents(input, &run)
			*budget -= limit - input.N
			if size > limit {
				recent = false // The missing tail could have recorded an exit.
			}
		}
		journal.Close()
	}
	if run.StartedAt == 0 && !files.started.IsZero() {
		// Journal rows have no timestamp. The run directory was written when its journal was created.
		run.StartedAt = files.started.UnixMilli()
	}
	if !recent && run.Status == "running" {
		run.Status = "unknown"
	}
	for i := range run.Workers {
		worker := &run.Workers[i]
		if worker.Status != "running" {
			continue
		}
		switch {
		case run.Status == "cancelled":
			worker.Status = "cancelled"
		case !recent || run.Status == "done" || run.Status == "error":
			worker.Status = "unknown"
		case run.Status == "unknown":
			run.Status = "running"
		}
	}
	run.AgentCount = max(run.AgentCount, len(run.Workers))
	return run, hasSummary || hasJournal
}

func readClaudeWorkflowEvents(input io.Reader, run *claudeWorkflowRun) bool {
	byID, byKey := map[string]int{}, map[string]int{}
	sawEvent := false
	readClaudeWorkflowLines(input, func(line []byte) {
		var event claudeWorkflowEvent
		if json.Unmarshal(line, &event) != nil {
			return
		}
		switch event.Type {
		case "launched", "started", "result", "failed", "cancelled", "canceled", "killed":
			sawEvent = true
		default:
			return
		}
		if run.StartedAt == 0 {
			run.StartedAt = claudeWorkflowTimestamp(event.StartTime, event.Timestamp, event.TS)
		}
		if event.Type == "launched" {
			if run.Name == "" {
				run.Name = providerTruncate(event.Name, 240)
			}
			if run.TaskID == "" {
				run.TaskID = providerTruncate(event.TaskID, 160)
			}
			return
		}
		if event.AgentID == "" && event.Key == "" || len(event.AgentID) > 160 || len(event.Key) > 240 {
			return
		}
		index, exists := byID[event.AgentID]
		if !exists && event.Key != "" {
			index, exists = byKey[event.Key]
			if exists && event.AgentID != "" && run.Workers[index].ID != "key:"+event.Key {
				exists = false // Separate agents can reuse a workflow key.
			}
		}
		if !exists {
			if len(run.Workers) >= claudeWorkflowMaxWorkers {
				return
			}
			index = len(run.Workers)
			run.Workers = append(run.Workers, claudeWorkflowWorker{ID: "key:" + event.Key, Status: "unknown"})
		}
		worker := &run.Workers[index]
		if event.AgentID != "" {
			worker.ID = event.AgentID
			byID[event.AgentID] = index
		}
		if event.Key != "" {
			byKey[event.Key] = index
		}
		if event.Label != "" || worker.Label == "" {
			label := event.Label
			if label == "" {
				label = event.Key
			}
			worker.Label = providerTruncate(label, 240)
		}
		if event.Phase != "" {
			worker.Phase = providerTruncate(event.Phase, 240)
		}
		if stamp := claudeWorkflowTimestamp(event.Timestamp, event.TS); stamp > worker.ActivityAt {
			worker.ActivityAt = stamp
		}
		switch event.Type {
		case "started":
			if worker.Status == "unknown" {
				worker.Status = "running"
			}
		case "result", "failed", "cancelled", "canceled", "killed":
			worker.Status = claudeWorkflowStatus(event.Type)
			if output := claudeWorkflowText(event.Result, event.Error); output != "" {
				worker.Output = output
			}
		}
	})
	return sawEvent
}

func readClaudeWorkflowLines(input io.Reader, visit func([]byte)) {
	reader := bufio.NewReaderSize(input, 32<<10)
	var stored []byte
	oversized := false
	for {
		part, more, err := reader.ReadLine()
		if err != nil {
			return
		}
		if len(stored)+len(part) > claudeWorkflowLineBytes {
			oversized = true
		}
		if !oversized {
			if !more && stored == nil {
				visit(part)
			} else {
				stored = append(stored, part...)
				if !more {
					visit(stored)
				}
			}
		}
		if !more {
			stored = nil
			oversized = false
		}
	}
}

func claudeWorkflowStatus(status string) string {
	switch strings.ToLower(strings.TrimSpace(status)) {
	case "completed", "done", "succeeded", "result":
		return "done"
	case "failed", "error":
		return "error"
	case "killed", "cancelled", "canceled", "stopped", "interrupted":
		return "cancelled"
	case "running", "started", "pending", "in_progress":
		return "running"
	default:
		return "unknown"
	}
}

func claudeWorkflowTimestamp(values ...json.RawMessage) int64 {
	for _, value := range values {
		var text string
		if json.Unmarshal(value, &text) != nil {
			text = string(value)
		}
		if stamp, err := strconv.ParseInt(text, 10, 64); err == nil && stamp > 0 {
			if stamp < 1e12 {
				stamp *= 1000
			}
			return stamp
		}
		if stamp, err := time.Parse(time.RFC3339Nano, text); err == nil {
			return stamp.UnixMilli()
		}
	}
	return 0
}

func claudeWorkflowText(values ...json.RawMessage) string {
	for _, value := range values {
		if len(value) == 0 || string(value) == "null" {
			continue
		}
		var text string
		if json.Unmarshal(value, &text) != nil {
			text = string(value)
		}
		if text = strings.TrimSpace(text); text != "" {
			return providerTruncate(text, 4000)
		}
	}
	return ""
}
