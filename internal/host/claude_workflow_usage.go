package host

import (
	"bytes"
	"encoding/json"
	"os"
	"sync"
)

const claudeWorkflowUsageDetailBytes = 8 << 20

type claudeWorkflowUsage struct {
	ContextTokens     *int64 `json:"contextTokens,omitempty"`
	InputTokens       int64  `json:"inputTokens"`
	OutputTokens      int64  `json:"outputTokens"`
	CachedInputTokens int64  `json:"cachedInputTokens"`
	CacheWriteTokens  int64  `json:"cacheWriteTokens"`
	RequestCount      int    `json:"requestCount"`
	Partial           bool   `json:"partial"`
}

type claudeWorkflowUsageSample struct {
	Fresh  *int64 `json:"input_tokens"`
	Cached *int64 `json:"cache_read_input_tokens"`
	Write  *int64 `json:"cache_creation_input_tokens"`
	Output *int64 `json:"output_tokens"`
}

type claudeWorkflowUsageSamples struct {
	requests map[string]claudeWorkflowUsageSample
	latest   string
}

func (s *claudeWorkflowUsageSamples) add(line []byte, agentID string) {
	if !bytes.Contains(line, []byte(`"assistant"`)) || !bytes.Contains(line, []byte(`"usage"`)) {
		return
	}
	var row struct {
		Type    string `json:"type"`
		AgentID string `json:"agentId"`
		UUID    string `json:"uuid"`
		Message struct {
			ID    string          `json:"id"`
			Usage json.RawMessage `json:"usage"`
		} `json:"message"`
	}
	if json.Unmarshal(line, &row) != nil || row.Type != "assistant" || row.AgentID != "" && row.AgentID != agentID {
		return
	}
	s.addRequest(row.Message.ID, row.UUID, row.Message.Usage)
}

func (s *claudeWorkflowUsageSamples) addRequest(id, uuid string, data json.RawMessage) {
	if id == "" {
		id = uuid
	}
	if id == "" || len(id) > 160 || len(data) == 0 {
		return
	}
	var usage claudeWorkflowUsageSample
	if json.Unmarshal(data, &usage) != nil {
		return
	}
	if usage.Fresh == nil && usage.Cached == nil && usage.Write == nil && usage.Output == nil {
		return
	}
	for _, value := range []*int64{usage.Fresh, usage.Cached, usage.Write, usage.Output} {
		// A single request cannot consume this many tokens. Keep sums safe on malformed logs.
		if value != nil && (*value < 0 || *value > 1<<40) {
			return
		}
	}
	if s.requests == nil {
		s.requests = make(map[string]claudeWorkflowUsageSample)
	}
	// Claude records separate content blocks for the same assistant response.
	if last, exists := s.requests[id]; exists {
		usage.Fresh = claudeWorkflowReportedUsage(usage.Fresh, last.Fresh)
		usage.Cached = claudeWorkflowReportedUsage(usage.Cached, last.Cached)
		usage.Write = claudeWorkflowReportedUsage(usage.Write, last.Write)
		usage.Output = claudeWorkflowReportedUsage(usage.Output, last.Output)
	} else {
		s.latest = id
	}
	s.requests[id] = usage
}

func claudeWorkflowReportedUsage(current, previous *int64) *int64 {
	if current == nil {
		return previous
	}
	return current
}

func workflowTokenCount(value *int64) int64 {
	if value != nil {
		return *value
	}
	return 0
}

func (s claudeWorkflowUsageSample) input() (int64, bool) {
	return workflowTokenCount(s.Fresh) + workflowTokenCount(s.Cached) + workflowTokenCount(s.Write),
		s.Fresh != nil || s.Cached != nil || s.Write != nil
}

func (s *claudeWorkflowUsageSamples) summary(partial bool) *claudeWorkflowUsage {
	if len(s.requests) == 0 {
		return nil
	}
	result := &claudeWorkflowUsage{RequestCount: len(s.requests), Partial: partial}
	for _, usage := range s.requests {
		input, known := usage.input()
		result.InputTokens += input
		result.OutputTokens += workflowTokenCount(usage.Output)
		result.CachedInputTokens += workflowTokenCount(usage.Cached)
		result.CacheWriteTokens += workflowTokenCount(usage.Write)
		result.Partial = result.Partial || !known || usage.Output == nil
	}
	if context, known := s.requests[s.latest].input(); known {
		result.ContextTokens = &context
	}
	return result
}

type claudeWorkflowUsageDetailCache struct {
	mu     sync.Mutex
	key    string
	file   os.FileInfo
	result *claudeWorkflowUsage
}

func (a *App) claudeWorkflowAgentUsage(sid, entryID string) (*claudeWorkflowUsage, error) {
	source, err := a.claudeWorkflowAgentSource(sid, entryID)
	if err != nil || source.path == "" {
		return nil, err
	}
	if provider, ok := a.Providers["claude"].(*nativeProvider); ok {
		return provider.claudeWorkflow.detailedUsage.read(source.sessionDir, source.path, source.agentID), nil
	}
	return nil, nil
}

func (c *claudeWorkflowUsageDetailCache) read(sessionDir, path, agentID string) *claudeWorkflowUsage {
	c.mu.Lock()
	defer c.mu.Unlock()
	root, err := os.OpenRoot(sessionDir)
	if err != nil {
		return nil
	}
	defer root.Close()
	file, _ := claudeWorkflowOpen(root, path)
	if file == nil {
		return nil
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return nil
	}
	key := sessionDir + "\x00" + path + "\x00" + agentID
	if c.key == key && c.file != nil && os.SameFile(c.file, info) && c.file.Size() == info.Size() && c.file.ModTime().Equal(info.ModTime()) {
		return c.result
	}
	data, partial, err := readClaudeWorkflowRecent(file, info.Size(), claudeWorkflowUsageDetailBytes)
	if err != nil {
		return nil
	}
	var samples claudeWorkflowUsageSamples
	for line := range bytes.Lines(data) {
		samples.add(line, agentID)
	}
	c.key, c.file, c.result = key, info, samples.summary(partial)
	return c.result
}

func readClaudeWorkflowRecent(file *os.File, size, budget int64) ([]byte, bool, error) {
	start := max(0, size-budget)
	partial := start > 0
	// Include the preceding byte to tell a split record from a newline boundary.
	position := max(0, start-1)
	data := make([]byte, size-position)
	if _, err := file.ReadAt(data, position); err != nil {
		return nil, partial, err
	}
	if partial {
		_, data, _ = bytes.Cut(data, []byte{'\n'})
	}
	return data, partial, nil
}
