package host

import (
	"encoding/json"
	"errors"
	"math"
	"unicode/utf8"
)

const agyDefaultContextWindow = 1_048_576

const (
	agyContextUser = iota
	agyContextAssistant
	agyContextReasoning
	agyContextToolRuns
	agyContextSystem
	agyContextCount
)

var agyContextLabels = [agyContextCount]struct{ id, name string }{
	agyContextUser:      {"agy_user", "Conversation (You)"},
	agyContextAssistant: {"agy_assistant", "Conversation (Antigravity)"},
	agyContextReasoning: {"agy_reasoning", "Thinking"},
	agyContextToolRuns:  {"agy_toolRuns", "Tool execution (calls and results)"},
	agyContextSystem:    {"agy_system", "System notification"},
}

type agyContextWeights struct {
	weights             [agyContextCount]int64
	firstPrompt         float64
	initialChars        int64
	firstPromptRecorded bool
	model               string
}

func agyContentSize(value any) int64 {
	switch v := value.(type) {
	case nil:
		return 0
	case string:
		return int64(utf8.RuneCountInString(v))
	default:
		encoded, _ := json.Marshal(v)
		return int64(utf8.RuneCount(encoded))
	}
}

func (c *agyContextWeights) record(kind string, row M) {
	content := agyContentSize(row["content"])
	switch kind {
	case "USER_INPUT", "SYSTEM_MESSAGE":
		category := agyContextUser
		if kind == "SYSTEM_MESSAGE" {
			category = agyContextSystem
		}
		c.weights[category] += content
		if !c.firstPromptRecorded {
			c.initialChars += content
		}
	case "PLANNER_RESPONSE":
		c.weights[agyContextAssistant] += content
		c.weights[agyContextReasoning] += int64(math.Round(float64(agyContentSize(row["thinking"])) * 0.4))
		c.weights[agyContextToolRuns] += agyContentSize(row["tool_calls"])
		if model := S(row, "model"); !agyModelUnset(model) {
			c.model = model
		}
		if prompt := N(row, "input_tokens") + N(row, "cache_read_tokens"); !c.firstPromptRecorded && prompt > 0 {
			c.firstPrompt = prompt
			c.firstPromptRecorded = true
		}
	default:
		c.weights[agyContextToolRuns] += content
	}
}

func (c agyContextWeights) categories(used int64, transcriptUsed float64) []M {
	if used <= 0 {
		return nil
	}
	var total int64
	for _, weight := range c.weights {
		total += weight
	}
	var categories []M
	// The first prompt includes the system instructions and tool definitions.
	if transcriptUsed <= 0 {
		transcriptUsed = float64(used)
	}
	fixedEstimate := max(0, min(transcriptUsed, c.firstPrompt-math.Round(float64(c.initialChars)/4)))
	fixed := int64(math.Round(float64(used) * fixedEstimate / transcriptUsed))
	if fixed > 0 {
		categories = append(categories, agyContextCategory("agy_base", "System prompt and tool definitions", fixed))
	}
	if total == 0 {
		if fixed > 0 && fixed < used {
			categories = append(categories, agyContextCategory("messages", "Conversation", used-fixed))
		}
		return categories
	}
	var weightSoFar, tokensSoFar int64
	for i, weight := range c.weights {
		if weight == 0 {
			continue
		}
		weightSoFar += weight
		next := int64(math.Round(float64(used-fixed) * float64(weightSoFar) / float64(total)))
		label := agyContextLabels[i]
		categories = append(categories, agyContextCategory(label.id, label.name, next-tokensSoFar))
		tokensSoFar = next
	}
	return categories
}

func agyContextCategory(id, name string, tokens int64) M {
	return M{"id": id, "name": name, "tokens": tokens, "kind": "estimated", "color": ""}
}

func agyTranscriptContext(external string, config M) (agyContextWeights, float64) {
	path := analyticsPath("antigravity", external)
	if path == "" {
		return agyContextWeights{}, 0
	}
	s := analyticsStateFor(path)
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, err := s.read("antigravity", path, config); err != nil {
		return agyContextWeights{}, 0
	}
	return s.agyContext, s.used
}

func agyContextDetail(rec *Record, config M) (M, error) {
	transcript, transcriptUsed := agyTranscriptContext(S(rec.Info, "externalId"), config)
	current := providerMap(rec.Info["context"])
	used, window := N(current, "used"), N(current, "max")
	if used <= 0 {
		used = transcriptUsed
	}
	if window <= 0 && transcriptUsed > 0 {
		window = agyDefaultContextWindow
	}
	if window <= 0 {
		return nil, errors.New("Context size is not known yet")
	}
	model := S(rec.Info, "model")
	if agyModelUnset(model) {
		model = transcript.model
	}
	categories := transcript.categories(int64(used), transcriptUsed)
	if len(categories) == 0 {
		categories = []M{agyContextCategory("messages", "Conversation", int64(used))}
	}
	categories = append(categories, M{"id": "free_space", "name": "Available", "tokens": int64(max(0, window-used)), "kind": "free", "color": ""})
	return M{
		"model": model, "totalTokens": int64(used), "maxTokens": int64(window),
		"percentage": 100 * used / window, "estimated": true, "categories": categories,
		"isAutoCompactEnabled": false, "autoCompactThreshold": nil,
		"mcpServers": []M{}, "memoryFiles": []M{}, "skills": []M{}, "agents": []M{}, "messages": nil,
	}, nil
}
