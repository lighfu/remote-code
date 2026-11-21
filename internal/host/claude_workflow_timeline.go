package host

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"slices"
	"strings"
)

const (
	claudeAgentTimelineRows = 80
	claudeAgentTimelineScan = 2 << 20
	claudeAgentTimelineText = 12_000
	claudeAgentTimelineTool = 6_000
)

type claudeAgentTimelinePosition struct {
	offset int64 // Transcript byte offset or index in the parent session's entries.
	block  int
}

func (a *App) claudeWorkflowAgentTimeline(sid, entryID, before string) (M, error) {
	source, err := a.claudeWorkflowAgentSource(sid, entryID)
	if err != nil {
		return nil, err
	}
	scope := sha256.Sum256([]byte(sid + "\x00" + entryID))
	position, stored, ok := decodeClaudeAgentTimelineCursor(scope[:8], before)
	if !ok || stored && !source.stored {
		return nil, &HTTPError{400, "Invalid agent timeline cursor"}
	}
	var page []M
	var older claudeAgentTimelinePosition
	if !stored && source.path != "" {
		page, older, err = readClaudeAgentTimelinePage(source, position)
		if err != nil {
			return nil, err
		}
	}
	if source.stored && (stored || before == "" && len(page) == 0 && older == (claudeAgentTimelinePosition{})) {
		page, older = a.Store.claudeStoredAgentTimelinePage(sid, entryID, source.prompt, position)
		stored = true
	}
	if page == nil {
		page = []M{}
	}
	result := M{"entries": page}
	if older != (claudeAgentTimelinePosition{}) {
		result["before"] = encodeClaudeAgentTimelineCursor(scope[:8], older, stored)
	}
	switch source.status {
	case "running", "starting", "pending", "waiting", "queued", "paused", "awaiting_input", "awaiting_approval":
		result["active"] = true
	case "done", "completed", "error", "failed", "cancelled", "declined", "stopped":
		result["active"] = false
	}
	return result, nil
}

func encodeClaudeAgentTimelineCursor(scope []byte, position claudeAgentTimelinePosition, stored bool) string {
	var raw [21]byte
	copy(raw[:8], scope)
	if stored {
		raw[8] = 1
	}
	binary.BigEndian.PutUint64(raw[9:17], uint64(position.offset))
	binary.BigEndian.PutUint32(raw[17:], uint32(position.block))
	return base64.RawURLEncoding.EncodeToString(raw[:])
}

func decodeClaudeAgentTimelineCursor(scope []byte, encoded string) (claudeAgentTimelinePosition, bool, bool) {
	if encoded == "" {
		return claudeAgentTimelinePosition{offset: -1}, false, true
	}
	if len(encoded) != base64.RawURLEncoding.EncodedLen(21) {
		return claudeAgentTimelinePosition{}, false, false
	}
	data, err := base64.RawURLEncoding.DecodeString(encoded)
	if err != nil || len(data) != 21 || !bytes.Equal(data[:8], scope) || data[8] > 1 {
		return claudeAgentTimelinePosition{}, false, false
	}
	position := claudeAgentTimelinePosition{int64(binary.BigEndian.Uint64(data[9:17])), int(binary.BigEndian.Uint32(data[17:]))}
	return position, data[8] == 1, position.offset >= 0 && position.block >= 0 && position.block <= claudeWorkflowLineBytes
}

func readClaudeAgentTimelinePage(source claudeWorkflowAgentSource, before claudeAgentTimelinePosition) ([]M, claudeAgentTimelinePosition, error) {
	root, err := os.OpenRoot(source.sessionDir)
	if err != nil {
		return nil, claudeAgentTimelinePosition{}, nil
	}
	defer root.Close()
	file, size := claudeWorkflowOpen(root, source.path)
	if file == nil {
		return nil, claudeAgentTimelinePosition{}, nil
	}
	defer file.Close()
	if before.offset < 0 || before.offset > size {
		before = claudeAgentTimelinePosition{offset: size}
	}
	start := max(0, before.offset-claudeAgentTimelineScan)
	end := before.offset + min(int64(claudeWorkflowLineBytes), size-before.offset)
	from := max(0, start-1)
	data, err := io.ReadAll(io.NewSectionReader(file, from, end-from))
	if err != nil {
		return nil, claudeAgentTimelinePosition{}, err
	}
	if start > 0 {
		lineEnd := bytes.IndexByte(data, '\n')
		if lineEnd < 0 {
			return nil, claudeAgentTimelinePosition{offset: start}, nil
		}
		from += int64(lineEnd + 1)
		data = data[lineEnd+1:]
	}
	if end < size {
		data = data[:bytes.LastIndexByte(data, '\n')+1]
	}
	entries, older := collectClaudeAgentTimelinePage(data, from, start, before, source.agentID)
	return entries, older, nil
}

func collectClaudeAgentTimelinePage(data []byte, from, start int64, before claudeAgentTimelinePosition, agentID string) ([]M, claudeAgentTimelinePosition) {
	var entries []M
	seen := make(map[string]bool)
	results := make(map[string]claudeAgentLogBlock)
	older := claudeAgentTimelinePosition{offset: start}
readLogs:
	for len(data) > 0 {
		lineEnd := len(data)
		if data[lineEnd-1] == '\n' {
			lineEnd--
		}
		lineStart := bytes.LastIndexByte(data[:lineEnd], '\n') + 1
		line := data[lineStart:lineEnd]
		data = data[:lineStart]
		if len(line) > claudeWorkflowLineBytes || !bytes.Contains(line, []byte(`"message"`)) {
			continue
		}
		offset := from + int64(lineStart)
		record, blocks, ok := parseClaudeAgentLogLine(line)
		if !ok || record.AgentID != "" && record.AgentID != agentID {
			continue
		}
		for index := len(blocks) - 1; index >= 0; index-- {
			block := blocks[index]
			if block.Type == "tool_result" && record.Type == "user" && block.ToolUseID != "" {
				if _, exists := results[block.ToolUseID]; !exists {
					results[block.ToolUseID] = block
				}
				continue
			}
			if offset > before.offset || offset == before.offset && index >= before.block {
				continue
			}
			entry := claudeAgentTimelineEntry(record, block, results, offset, index)
			if id := S(entry, "id"); id != "" && !seen[id] {
				seen[id] = true
				entries = append(entries, entry)
				if len(entries) == claudeAgentTimelineRows {
					older = claudeAgentTimelinePosition{offset, index}
					break readLogs
				}
			}
		}
	}
	slices.Reverse(entries)
	return entries, older
}

func claudeAgentTimelineEntry(record claudeAgentLogRecord, block claudeAgentLogBlock, results map[string]claudeAgentLogBlock, offset int64, index int) M {
	id := record.UUID
	if id == "" {
		id = fmt.Sprintf("line-%d", offset)
	}
	entry := M{"id": fmt.Sprintf("agent-message:%s:%d", providerTruncate(id, 96), index), "ts": claudeWorkflowTimestamp(record.Timestamp)}
	switch {
	case block.Type == "text" && strings.TrimSpace(block.Text) != "":
		entry["kind"], entry["text"] = record.Type, providerTruncate(block.Text, claudeAgentTimelineText)
	case block.Type == "thinking" && record.Type == "assistant" && strings.TrimSpace(block.Thinking) != "":
		entry["kind"], entry["text"] = "thinking", providerTruncate(block.Thinking, claudeAgentTimelineText)
	case block.Type == "tool_use" && record.Type == "assistant" && block.Name != "":
		name := providerTruncate(block.Name, 120)
		entry["kind"], entry["tool"] = "tool", name
		if block.ID != "" {
			entry["id"] = "agent-tool:" + providerTruncate(block.ID, 128)
		}
		if description := strings.TrimSpace(block.Input.Description); description != "" {
			entry["text"] = providerTruncate(description, 240)
		}
		if block.Input.FilePath != "" {
			entry["path"] = providerTruncate(block.Input.FilePath, claudeAgentTimelineTool)
		}
		for _, value := range []string{block.Input.Command, block.Input.FilePath, block.Input.Path, block.Input.Pattern} {
			if value != "" {
				entry["detail"] = providerTruncate(value, claudeAgentTimelineTool)
				break
			}
		}
		if result, exists := results[block.ID]; exists {
			entry["status"] = "done"
			if result.IsError {
				entry["status"] = "error"
			}
			if output := claudeAgentTimelineToolOutput(result.Content); output != "" {
				entry["output"] = output
			}
		}
	default:
		return nil
	}
	return entry
}

func claudeAgentTimelineToolOutput(content json.RawMessage) string {
	var text string
	if json.Unmarshal(content, &text) == nil {
		return providerTruncate(text, claudeAgentTimelineTool)
	}
	var blocks []struct{ Type, Text string }
	if json.Unmarshal(content, &blocks) != nil {
		return ""
	}
	textBlocks := make([]string, 0, len(blocks))
	for _, block := range blocks {
		if block.Type == "text" && block.Text != "" {
			textBlocks = append(textBlocks, block.Text)
		}
	}
	return providerTruncate(strings.Join(textBlocks, "\n"), claudeAgentTimelineTool)
}

func (s *Store) claudeStoredAgentTimelinePage(sid, entryID, prompt string, before claudeAgentTimelinePosition) ([]M, claudeAgentTimelinePosition) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	rec := s.records[sid]
	if rec == nil {
		return nil, claudeAgentTimelinePosition{}
	}
	end := len(rec.Entries)
	if before.offset >= 0 {
		end = int(min(int64(end), before.offset))
	}
	var entries []M
	var older claudeAgentTimelinePosition
	seen := make(map[string]bool)
	for index := end - 1; index >= 0; index-- {
		entry := rec.Entries[index]
		id, kind := S(entry, "id"), S(entry, "kind")
		if id == "" || S(entry, "parent") != entryID || seen[id] {
			continue
		}
		row := M{"id": id, "kind": kind, "ts": N(entry, "ts")}
		switch kind {
		case "user", "assistant", "thinking":
			if value := S(entry, "text"); strings.TrimSpace(value) != "" {
				row["text"] = providerTruncate(value, claudeAgentTimelineText)
			} else {
				continue
			}
		case "tool":
			for _, field := range []string{"tool", "title", "text", "status"} {
				if value := S(entry, field); value != "" {
					row[field] = providerTruncate(value, 240)
				}
			}
			for _, field := range []string{"detail", "output", "path"} {
				if value := S(entry, field); value != "" {
					row[field] = providerTruncate(value, claudeAgentTimelineTool)
				}
			}
		default:
			continue
		}
		seen[id] = true
		entries = append(entries, row)
		if len(entries) == claudeAgentTimelineRows {
			older = claudeAgentTimelinePosition{offset: int64(index)}
			break
		}
	}
	if older.offset == 0 && strings.TrimSpace(prompt) != "" {
		if len(entries) == claudeAgentTimelineRows {
			older.block = 1
		} else if !slices.ContainsFunc(rec.Entries, func(entry M) bool {
			return S(entry, "parent") == entryID && S(entry, "kind") == "user" && strings.TrimSpace(S(entry, "text")) != ""
		}) {
			entries = append(entries, M{"id": "agent-instruction:" + entryID, "kind": "user", "text": providerTruncate(prompt, claudeAgentTimelineText)})
		}
	}
	slices.Reverse(entries)
	return entries, older
}
