package host

import (
	"encoding/json"
	"math"
	"os"
	"strings"
)

const (
	codexContextSystem = iota
	codexContextTools
	codexContextWorld
	codexContextDeveloper
	codexContextSummary
	codexContextMessages
	codexContextToolRuns
	codexContextReasoning
	codexContextImages
	codexContextCount
)

var codexContextLabels = [codexContextCount]struct{ id, name string }{
	codexContextSystem:    {"codex_system", "System prompt (Codex basic instructions)"},
	codexContextTools:     {"codex_tools", "Tool definitions"},
	codexContextWorld:     {"codex_world", "Environment info (AGENTS.md, etc.)"},
	codexContextDeveloper: {"codex_developer", "Developer instructions (permissions, mode, etc.)"},
	codexContextSummary:   {"codex_summary", "Previous conversation summary"},
	codexContextMessages:  {"codex_messages", "Conversation"},
	codexContextToolRuns:  {"codex_toolRuns", "Tool execution (calls and results)"},
	codexContextReasoning: {"codex_reasoning", "Thinking"},
	codexContextImages:    {"codex_images", "Images"},
}

type codexContextSnapshot struct {
	used, window float64
	model        string
	weights      [codexContextCount]int64
}

func codexContextBreakdown(threadID string, config M) (codexContextSnapshot, error) {
	path := analyticsPath("codex", threadID)
	if path == "" {
		return codexContextSnapshot{}, os.ErrNotExist
	}
	s := analyticsStateFor(path)
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, err := s.read("codex", path, config); err != nil {
		return codexContextSnapshot{}, err
	}
	return codexContextSnapshot{s.used, s.window, s.model, s.codexContext.weights}, nil
}

func (s codexContextSnapshot) categories(used float64) []M {
	var total int64
	for _, weight := range s.weights {
		total += weight
	}
	if total == 0 || used <= 0 {
		return nil
	}
	var categories []M
	var weightSoFar, tokensSoFar int64
	for i, weight := range s.weights {
		if weight <= 0 {
			continue
		}
		weightSoFar += weight
		// Round the cumulative shares so the categories add up to measured usage.
		next := int64(math.Round(used * float64(weightSoFar) / float64(total)))
		label := codexContextLabels[i]
		categories = append(categories, M{"id": label.id, "name": label.name, "tokens": next - tokensSoFar, "kind": "estimated", "color": ""})
		tokensSoFar = next
	}
	return categories
}

type codexContextWeights struct {
	weights    [codexContextCount]int64
	worldSizes map[string]int64
}

func (c *codexContextWeights) record(kind string, payload M, size int64) {
	switch kind {
	case "session_meta":
		base := payload["base_instructions"]
		if text, ok := base.(string); ok {
			c.weights[codexContextSystem] = int64(len(text))
		} else if text := S(providerMap(base), "text"); text != "" {
			c.weights[codexContextSystem] = int64(len(text))
		}
	case "response_item":
		c.addItem(payload, size)
	case "world_state":
		state := providerMap(payload["state"])
		if payload["state"] == nil {
			c.weights[codexContextWorld] = size
			return
		}
		if B(payload, "full") || c.worldSizes == nil {
			c.worldSizes = make(map[string]int64, len(state))
		}
		for key, value := range state {
			if value == nil {
				delete(c.worldSizes, key)
			} else if encoded, err := json.Marshal(value); err == nil {
				c.worldSizes[key] = int64(len(key) + len(encoded))
			}
		}
		c.weights[codexContextWorld] = 0
		for _, weight := range c.worldSizes {
			c.weights[codexContextWorld] += weight
		}
	case "compacted":
		// The replacement history supersedes the previous conversation window.
		clear(c.weights[codexContextDeveloper:])
		for _, item := range maps(payload["replacement_history"]) {
			if encoded, err := json.Marshal(item); err == nil {
				c.addItem(item, int64(len(encoded)))
			}
		}
		if c.weights[codexContextSummary] == 0 {
			c.weights[codexContextSummary] = int64(len(S(payload, "message")))
		}
	}
}

func (c *codexContextWeights) addItem(item M, size int64) {
	kind := S(item, "type")
	switch kind {
	case "":
		return
	case "additional_tools":
		c.weights[codexContextTools] = size
	case "message", "agent_message":
		category := codexContextMessages
		switch S(item, "role") {
		case "developer":
			category = codexContextDeveloper
		case "system":
			// Some rollout formats include the system text in a response item.
			category = codexContextSystem
		}
		for _, part := range maps(item["content"]) {
			if S(part, "type") == "input_image" {
				// Estimate one image at 1,500 tokens so base64 size cannot dominate.
				c.weights[codexContextImages] += 6000
				size -= int64(len(S(part, "image_url")))
			}
		}
		if category == codexContextSystem {
			c.weights[category] = max(c.weights[category], size)
		} else {
			c.weights[category] += max(0, size)
		}
	case "reasoning":
		c.weights[codexContextReasoning] += int64(math.Round(float64(size) * 0.4))
	case "compaction", "compaction_summary", "summary":
		c.weights[codexContextSummary] += size
	default:
		if strings.Contains(kind, "call") || strings.Contains(kind, "output") || strings.Contains(kind, "search") || strings.Contains(kind, "shell") {
			c.weights[codexContextToolRuns] += size
		} else {
			c.weights[codexContextMessages] += size
		}
	}
}
