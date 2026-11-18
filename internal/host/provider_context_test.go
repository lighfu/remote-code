package host

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCodexContextBreakdownUsesRolloutRecordsAndMeasuredUsage(t *testing.T) {
	home := t.TempDir()
	t.Setenv("CODEX_HOME", home)
	dir := filepath.Join(home, "sessions")
	if err := os.Mkdir(dir, 0700); err != nil {
		t.Fatal(err)
	}
	thread := NewID()
	path := filepath.Join(dir, "rollout-"+thread+".jsonl")
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	writeRows := func(rows ...M) {
		t.Helper()
		encoder := json.NewEncoder(file)
		for _, row := range rows {
			if err := encoder.Encode(row); err != nil {
				t.Fatal(err)
			}
		}
	}
	writeRows(
		M{"type": "session_meta", "payload": M{"base_instructions": M{"text": "System prompt"}}},
		M{"type": "world_state", "payload": M{"full": true, "state": M{"agents_md": "Instructions", "environments": "Workspace"}}},
		M{"type": "response_item", "payload": M{"type": "additional_tools", "tools": []M{{"name": "example"}}}},
		M{"type": "response_item", "payload": M{"type": "message", "role": "developer", "content": []M{{"type": "input_text", "text": "Permissions"}}}},
		M{"type": "response_item", "payload": M{"type": "message", "role": "user", "content": []M{{"type": "input_text", "text": "Question"}, {"type": "input_image", "image_url": strings.Repeat("A", 32_000)}}}},
		M{"type": "response_item", "payload": M{"type": "reasoning", "summary": []M{{"text": "Thinking"}}}},
		M{"type": "response_item", "payload": M{"type": "custom_tool_call_output", "output": "tool output"}},
		M{"type": "turn_context", "payload": M{"model": "test-model"}},
		M{"type": "event_msg", "timestamp": "2026-11-17T12:00:00Z", "payload": M{"type": "token_count", "info": M{
			"last_token_usage":     M{"input_tokens": 2000, "output_tokens": 33},
			"total_token_usage":    M{"input_tokens": 2000, "output_tokens": 33},
			"model_context_window": 475000,
		}}},
	)
	config := M{"codexPrices": M{"test-model": M{"input": 1, "output": 1}}}
	first, err := codexContextBreakdown(thread, config)
	if err != nil {
		t.Fatal(err)
	}
	if first.used != 2033 || first.window != 475000 || first.model != "test-model" {
		t.Fatalf("Unexpected measured context: %+v", first)
	}
	if first.weights[codexContextImages] != 6000 || first.weights[codexContextMessages] >= 1000 {
		t.Fatalf("Base64 influenced the context estimate: %v", first.weights)
	}
	var total float64
	parts := first.categories(first.used)
	for _, part := range parts {
		total += N(part, "tokens")
	}
	if total != first.used || len(parts) != 8 {
		t.Fatalf("Breakdown = %v, total = %v", parts, total)
	}

	// Cache and context share the incremental reader, including configured pricing.
	stats, _, err := analyticsRead("codex", path, config)
	if err != nil || N(stats, "costUsd") != 0.002033 {
		t.Fatalf("Analytics after context read = %v, %v", stats, err)
	}
	writeRows(M{"type": "compacted", "payload": M{"replacement_history": []M{
		{"type": "compaction", "encrypted_content": "summary-block"},
		{"type": "message", "role": "user", "content": []M{{"type": "input_text", "text": "Current question"}}},
	}}})
	compacted, err := codexContextBreakdown(thread, config)
	if err != nil {
		t.Fatal(err)
	}
	if compacted.weights[codexContextSummary] == 0 || compacted.weights[codexContextMessages] == 0 || compacted.weights[codexContextToolRuns] != 0 || compacted.weights[codexContextImages] != 0 {
		t.Fatalf("Compaction did not replace the previous history: %v", compacted.weights)
	}
	for _, retained := range []int{codexContextSystem, codexContextTools, codexContextWorld} {
		if compacted.weights[retained] != first.weights[retained] {
			t.Fatalf("Compaction lost persistent context: %v -> %v", first.weights, compacted.weights)
		}
	}
	writeRows(M{"type": "world_state", "payload": M{"full": false, "state": M{"environments": nil}}})
	last, err := codexContextBreakdown(thread, config)
	if err != nil {
		t.Fatal(err)
	}
	if weight := last.weights[codexContextWorld]; weight <= 0 || weight >= first.weights[codexContextWorld] {
		t.Fatalf("Partial environment update erased or did not replace a field: %v", last.weights)
	}
	if repeated, err := codexContextBreakdown(thread, config); err != nil || repeated != last {
		t.Fatalf("Reading the same file changed weights: %+v, %v", repeated, err)
	}
}
