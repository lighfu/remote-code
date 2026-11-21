package host

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"time"
)

const (
	claudeAgentTranscriptHead = 128 << 10
	claudeAgentTranscriptTail = 2 << 20
	claudeAgentTranscriptRows = 120
	claudeAgentTranscriptText = 4000
)

type claudeWorkflowAgentSource struct {
	sessionDir, path, agentID, prompt, status string
	stored                                    bool
}

func (a *App) claudeWorkflowAgentSource(sid, entryID string) (claudeWorkflowAgentSource, error) {
	if entryID == "" || len(entryID) > 512 {
		return claudeWorkflowAgentSource{}, &HTTPError{400, "A valid agent entry ID is required"}
	}
	external, taskID, prompt, status, storedAgent, found := a.Store.claudeWorkflowAgentSelection(sid, entryID)
	if !found {
		return claudeWorkflowAgentSource{}, &HTTPError{404, "Claude conversation not found"}
	}
	source := claudeWorkflowAgentSource{agentID: taskID, prompt: prompt, status: status, stored: storedAgent}
	if provider, ok := a.Providers["claude"].(*nativeProvider); ok {
		source.sessionDir = provider.claudeWorkflowSessionDir(external)
	}
	if source.sessionDir != "" {
		if synthetic, ok := strings.CutPrefix(entryID, "claude-workflow:"); ok {
			runID, workerID, ok := strings.Cut(synthetic, ":agent:")
			if ok && claudeWorkflowRunID.MatchString(runID) && workerID != "" {
				if status, found := claudeWorkflowJournalAgent(source.sessionDir, runID, workerID); found {
					source.agentID, source.status = workerID, status
					source.path = claudeWorkflowWorkerLogPath(claudeWorkflowAgentJournal(runID), workerID)
					return source, nil
				}
			}
		}
		if path := claudeWorkflowLocalAgentLogPath(taskID); storedAgent && path != "" {
			if root, err := os.OpenRoot(source.sessionDir); err == nil {
				info, statErr := root.Lstat(path)
				root.Close()
				if statErr == nil && info.Mode().IsRegular() && info.Size() > 0 {
					source.path = path
					return source, nil
				}
			}
			// Older live records use their original tool card ID as the visible row ID.
			for _, run := range readClaudeWorkflowRuns(source.sessionDir) {
				for _, worker := range run.Workers {
					if worker.ID == taskID {
						source.path = claudeWorkflowWorkerLogPath(claudeWorkflowAgentJournal(run.ID), taskID)
						if worker.Status != "unknown" || source.status == "" {
							source.status = worker.Status
						}
						return source, nil
					}
				}
			}
		}
	}
	if !storedAgent {
		return claudeWorkflowAgentSource{}, &HTTPError{404, "Claude agent not found"}
	}
	return source, nil
}

func (a *App) claudeWorkflowAgentTranscript(sid, entryID string) ([]M, error) {
	source, err := a.claudeWorkflowAgentSource(sid, entryID)
	if err != nil {
		return nil, err
	}
	if source.path != "" {
		if transcript := readClaudeAgentTranscript(source.sessionDir, source.path, source.agentID); len(transcript) > 0 || !source.stored {
			return transcript, nil
		}
	}
	return a.Store.claudeWorkflowAgentMessages(sid, entryID, source.prompt), nil
}

func (s *Store) claudeWorkflowAgentSelection(sid, entryID string) (external, taskID, prompt, status string, agentFound, sessionFound bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	rec := s.records[sid]
	if rec == nil || S(rec.Info, "provider") != "claude" {
		return "", "", "", "", false, false
	}
	external = S(rec.Info, "externalId")
	for _, entry := range rec.Entries {
		if S(entry, "id") == entryID && isClaudeWorkflowAgent(entry) {
			return external, S(entry, "claudeTaskId"), S(entry, "detail"), S(entry, "status"), true, true
		}
	}
	return external, "", "", "", false, true
}

func isClaudeWorkflowAgent(entry M) bool {
	return S(entry, "kind") == "tool" && (S(entry, "tool") == "Agent" || S(entry, "tool") == "Task")
}

func claudeWorkflowAgentJournal(runID string) string {
	return filepath.Join("subagents", "workflows", runID, "journal.jsonl")
}

func claudeWorkflowJournalAgent(sessionDir, runID, workerID string) (string, bool) {
	root, err := os.OpenRoot(sessionDir)
	if err != nil {
		return "", false
	}
	defer root.Close()
	journal := claudeWorkflowAgentJournal(runID)
	info, err := root.Lstat(journal)
	if err != nil || !info.Mode().IsRegular() {
		return "", false
	}
	files := claudeWorkflowFiles{id: runID, summary: filepath.Join("workflows", runID+".json"), journal: journal, updated: info.ModTime()}
	budget := int64(claudeWorkflowJournalBytes + claudeWorkflowSummaryBytes)
	run, _ := readClaudeWorkflowRun(root, files, time.Since(info.ModTime()) <= claudeWorkflowActiveAge, &budget)
	for _, worker := range run.Workers {
		if worker.ID == workerID {
			return worker.Status, true
		}
	}
	return "", false
}

type claudeAgentLogRecord struct {
	Type      string          `json:"type"`
	UUID      string          `json:"uuid"`
	Parent    string          `json:"parentUuid"`
	AgentID   string          `json:"agentId"`
	Timestamp json.RawMessage `json:"timestamp"`
	IsMeta    bool            `json:"isMeta"`
	IsCompact bool            `json:"isCompactSummary"`
	Message   struct {
		ID         string          `json:"id"`
		Content    json.RawMessage `json:"content"`
		StopReason string          `json:"stop_reason"`
		Usage      json.RawMessage `json:"usage"`
	} `json:"message"`
}

type claudeAgentLogBlock struct {
	Type      string          `json:"type"`
	ID        string          `json:"id"`
	Name      string          `json:"name"`
	Text      string          `json:"text"`
	Thinking  string          `json:"thinking"`
	ToolUseID string          `json:"tool_use_id"`
	IsError   bool            `json:"is_error"`
	Content   json.RawMessage `json:"content"`
	Input     struct {
		Description string `json:"description"`
		Command     string `json:"command"`
		FilePath    string `json:"file_path"`
		Path        string `json:"path"`
		Pattern     string `json:"pattern"`
	} `json:"input"`
}

func parseClaudeAgentLogLine(line []byte) (claudeAgentLogRecord, []claudeAgentLogBlock, bool) {
	var record claudeAgentLogRecord
	if json.Unmarshal(line, &record) != nil {
		return claudeAgentLogRecord{}, nil, false
	}
	if record.IsMeta || record.IsCompact || (record.Type != "user" && record.Type != "assistant") {
		return record, nil, false
	}
	var blocks []claudeAgentLogBlock
	if len(record.Message.Content) == 0 {
		return record, nil, false
	}
	if record.Message.Content[0] == '"' {
		var value string
		if json.Unmarshal(record.Message.Content, &value) == nil {
			blocks = []claudeAgentLogBlock{{Type: "text", Text: value}}
		}
	} else if err := json.Unmarshal(record.Message.Content, &blocks); err != nil {
		// An older tool may use a different type for an optional input field.
		if _, ok := err.(*json.UnmarshalTypeError); !ok {
			return record, nil, false
		}
	}
	return record, blocks, len(blocks) != 0
}

func readClaudeAgentTranscript(sessionDir, path, agentID string) []M {
	root, err := os.OpenRoot(sessionDir)
	if err != nil {
		return []M{}
	}
	defer root.Close()
	file, size := claudeWorkflowOpen(root, path)
	if file == nil {
		return []M{}
	}
	defer file.Close()

	var instruction M
	var messages claudeAgentMessageRing
	tools := map[string]M{}
	read := func(reader io.Reader, instructionOnly bool) {
		lineNumber := 0
		readClaudeWorkflowLines(reader, func(line []byte) {
			lineNumber++
			if instructionOnly && instruction != nil {
				return
			}
			record, blocks, ok := parseClaudeAgentLogLine(line)
			if !ok || (record.AgentID != "" && record.AgentID != agentID) || (instructionOnly && record.Type != "user") {
				return
			}
			for index, block := range blocks {
				if block.Type == "tool_result" && record.Type == "user" {
					if tool := tools[block.ToolUseID]; tool != nil {
						tool["status"] = "done"
						if block.IsError {
							tool["status"] = "error"
						}
						delete(tools, block.ToolUseID)
					}
					continue
				}
				id := record.UUID
				if id == "" {
					id = fmt.Sprintf("line-%d", lineNumber)
					if instructionOnly {
						id = "head-" + id
					}
				}
				entry := M{"id": fmt.Sprintf("agent-message:%s:%d", providerTruncate(id, 96), index), "ts": claudeWorkflowTimestamp(record.Timestamp)}
				switch {
				case block.Type == "text" && strings.TrimSpace(block.Text) != "":
					entry["kind"], entry["text"] = record.Type, providerTruncate(block.Text, claudeAgentTranscriptText)
					if record.Type == "user" && instruction == nil {
						instruction = entry
						continue
					}
				case !instructionOnly && block.Type == "tool_use" && record.Type == "assistant" && block.Name != "":
					name := providerTruncate(block.Name, 120)
					entry["kind"], entry["tool"], entry["title"] = "tool", name, name
					if description := strings.TrimSpace(block.Input.Description); description != "" {
						entry["text"] = providerTruncate(description, 200)
					}
					if block.ID != "" {
						tools[block.ID] = entry
					}
				default:
					continue
				}
				if !instructionOnly {
					messages.add(entry)
				}
			}
		})
	}
	if size <= claudeAgentTranscriptTail {
		read(io.NewSectionReader(file, 0, size), false)
	} else {
		read(io.NewSectionReader(file, 0, claudeAgentTranscriptHead), true)
		start := max(claudeAgentTranscriptHead, size-claudeAgentTranscriptTail)
		reader := bufio.NewReader(io.NewSectionReader(file, start-1, size-start+1))
		// Begin on a complete journal row even when the byte limit cuts a record.
		for {
			_, err := reader.ReadSlice('\n')
			if err != bufio.ErrBufferFull {
				break
			}
		}
		read(reader, false)
	}
	return messages.entries(instruction)
}

// The assistant may produce many short tool events. Keep only the recent rows
// while retaining the first instruction independently.
type claudeAgentMessageRing struct {
	rows  [claudeAgentTranscriptRows]M
	count int
}

func (m *claudeAgentMessageRing) add(entry M) {
	m.rows[m.count%len(m.rows)] = entry
	m.count++
}

func (m *claudeAgentMessageRing) entries(instruction M) []M {
	result := make([]M, 0, 1+min(m.count, len(m.rows)))
	seen := make(map[string]bool, cap(result))
	if instruction != nil {
		result = append(result, instruction)
		seen[S(instruction, "id")] = true
	}
	start := len(result)
	for n := m.count - 1; n >= max(0, m.count-len(m.rows)); n-- {
		entry := m.rows[n%len(m.rows)]
		id := S(entry, "id")
		if !seen[id] {
			result = append(result, entry)
			seen[id] = true
		}
	}
	slices.Reverse(result[start:])
	return result
}

func (s *Store) claudeWorkflowAgentMessages(sid, entryID, prompt string) []M {
	s.mu.RLock()
	defer s.mu.RUnlock()
	var messages claudeAgentMessageRing
	var instruction M
	if rec := s.records[sid]; rec != nil && S(rec.Info, "provider") == "claude" {
		for _, entry := range rec.Entries {
			if S(entry, "parent") != entryID {
				continue
			}
			kind, id := S(entry, "kind"), S(entry, "id")
			if id == "" {
				continue
			}
			row := M{"id": id, "kind": kind, "ts": N(entry, "ts")}
			switch kind {
			case "assistant", "user":
				if strings.TrimSpace(S(entry, "text")) == "" {
					continue
				}
				row["text"] = providerTruncate(S(entry, "text"), claudeAgentTranscriptText)
				if kind == "user" && instruction == nil {
					instruction = row
					continue
				}
			case "tool":
				for _, field := range []string{"tool", "title", "text", "status"} {
					if value := S(entry, field); value != "" {
						row[field] = providerTruncate(value, 240)
					}
				}
			default:
				continue
			}
			messages.add(row)
		}
	}
	if instruction == nil && strings.TrimSpace(prompt) != "" {
		instruction = M{"id": "agent-instruction:" + entryID, "kind": "user", "text": providerTruncate(prompt, claudeAgentTranscriptText)}
	}
	return messages.entries(instruction)
}
