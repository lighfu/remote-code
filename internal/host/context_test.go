package host

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

func TestContextWireNormalization(t *testing.T) {
	v := M{"model": "example", "totalTokens": 100, "maxTokens": 1000, "percentage": 10, "mcpTools": []M{{"serverName": "server", "tokens": 5}, {"serverName": "server", "tokens": 8}}, "skills": M{"skillFrontmatter": []M{{"name": "skill", "source": "user", "tokens": 3}}}, "agents": []M{{"agentType": "worker", "tokens": 2}}, "messageBreakdown": M{"toolCallTokens": 20, "toolCallsByType": []M{{"name": "shell", "callTokens": 2, "resultTokens": 3}}}}
	c := normalizeContext(v)
	servers := maps(c["mcpServers"])
	if len(servers) != 1 || N(servers[0], "tokens") != 13 || N(servers[0], "tools") != 2 {
		t.Fatal(c)
	}
	if len(maps(c["skills"])) != 1 || S(maps(c["agents"])[0], "name") != "worker" || N(providerMap(c["messages"]), "toolCalls") != 20 {
		t.Fatal(c)
	}
	if c["categories"] == nil || c["memoryFiles"] == nil {
		t.Fatal("missing required UI arrays", c)
	}
}

func TestCodexContextHTTPUsesRolloutBreakdownAndMeasuredTotals(t *testing.T) {
	home := t.TempDir()
	t.Setenv("CODEX_HOME", home)
	const external = "context-test-codex"
	sessions := filepath.Join(home, "sessions")
	if err := os.MkdirAll(sessions, 0700); err != nil {
		t.Fatal(err)
	}
	rollout := analyticsFixture(t, []M{
		{"type": "session_meta", "payload": M{"base_instructions": M{"text": "Session instructions"}}},
		{"type": "turn_context", "payload": M{"model": "recorded-model"}},
		{"type": "response_item", "payload": M{"type": "message", "role": "user", "content": []M{{"type": "input_text", "text": "User prompt"}}}},
		{"type": "response_item", "payload": M{"type": "function_call_output", "output": "Tool result"}},
		{"type": "event_msg", "payload": M{"type": "token_count", "info": M{"model_context_window": 1000, "last_token_usage": M{"input_tokens": 200, "output_tokens": 70}}}},
	})
	if err := os.Rename(rollout, filepath.Join(sessions, "rollout-"+external+".jsonl")); err != nil {
		t.Fatal(err)
	}
	a := testApp(t)
	rec := a.Store.Create(M{"provider": "codex", "externalId": external, "context": M{"used": 100, "max": 500}})
	response := testRequest(a, "GET", "/api/sessions/"+S(rec.Info, "id")+"/context", "")
	if response.Code != http.StatusOK {
		t.Fatalf("context request returned %d: %s", response.Code, response.Body)
	}
	var detail struct {
		Model                  string
		TotalTokens, MaxTokens int64
		Categories             []struct {
			ID     string
			Tokens int64
		}
	}
	if err := json.Unmarshal(response.Body.Bytes(), &detail); err != nil {
		t.Fatal(err)
	}
	if detail.TotalTokens != 270 || detail.MaxTokens != 1000 || detail.Model != "recorded-model" {
		t.Fatalf("context lost measured totals or model: %s", response.Body)
	}
	parts := map[string]int64{}
	var sum int64
	for _, category := range detail.Categories {
		parts[category.ID] = category.Tokens
		sum += category.Tokens
	}
	if parts["codex_system"] <= 0 || parts["codex_messages"] <= 0 || parts["codex_toolRuns"] <= 0 || parts["free_space"] != 730 || sum != detail.MaxTokens {
		t.Fatalf("context breakdown did not match the rollout and measured total: %v", parts)
	}
}

func TestCodexContextKeepsMeasuredSummaryWithoutRollout(t *testing.T) {
	a := testApp(t)
	rec := a.Store.Create(M{"provider": "codex", "context": M{"used": 42, "max": 100}})
	response := testRequest(a, "GET", "/api/sessions/"+S(rec.Info, "id")+"/context", "")
	var detail M
	if err := json.Unmarshal(response.Body.Bytes(), &detail); err != nil {
		t.Fatal(err)
	}
	categories := maps(detail["categories"])
	if response.Code != http.StatusOK || N(detail, "totalTokens") != 42 || detail["model"] != "" ||
		len(categories) != 2 || S(categories[0], "id") != "messages" || N(categories[1], "tokens") != 58 {
		t.Fatalf("context fallback changed: %d %s", response.Code, response.Body)
	}
}

func TestBackgroundClaudeContextUsesOnlyAnAttachedWire(t *testing.T) {
	a := testApp(t)
	p := NewProvider("claude", a.Store, a.DataDir).(*nativeProvider)
	a.Providers["claude"] = p
	rec := a.Store.Create(M{"provider": "claude", "live": true, "watching": true})
	id := S(rec.Info, "id")
	a.refreshLiveContext(t.Context(), rec)
	if got := a.Store.Get(id); !B(got.Info, "watching") || got.Info["context"] != nil || p.session(id) != nil {
		t.Fatal("context refresh changed a session with only a transcript follower")
	}

	w := &providerWire{claude: true, waiting: map[string]chan providerReply{}, done: make(chan struct{})}
	w.closeFunc = func() error { return nil }
	w.sendFunc = func(msg M) error {
		if S(providerMap(msg["request"]), "subtype") != "get_context_usage" {
			t.Fatalf("unexpected request: %v", msg)
		}
		w.receive(M{"type": "control_response", "response": M{
			"subtype": "success", "request_id": S(msg, "request_id"),
			"response": M{"totalTokens": 248, "maxTokens": 1000, "percentage": 24.8},
		}})
		return nil
	}
	p.sessions[id] = &nativeSession{wire: w}
	a.refreshLiveContext(t.Context(), rec)
	if got := providerMap(a.Store.Get(id).Info["context"]); N(got, "percent") != 25 {
		t.Fatalf("attached session context did not refresh: %v", got)
	}
}
