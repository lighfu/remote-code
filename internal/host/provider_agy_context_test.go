package host

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"slices"
	"strings"
	"testing"
)

func TestAntigravityContextDisplaysTranscriptBreakdown(t *testing.T) {
	home := t.TempDir()
	t.Setenv("USERPROFILE", home)
	t.Setenv("HOME", home)
	const external = "antigravity-context-thread"
	logs := filepath.Join(home, ".gemini", "antigravity-cli", "brain", external, ".system_generated", "logs")
	if err := os.MkdirAll(logs, 0700); err != nil {
		t.Fatal(err)
	}
	file, err := os.Create(filepath.Join(logs, "transcript_full.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	encoder := json.NewEncoder(file)
	for _, row := range []M{
		{"type": "USER_INPUT", "content": strings.Repeat("a", 433)},
		{"type": "PLANNER_RESPONSE", "content": strings.Repeat("b", 36), "thinking": strings.Repeat("c", 159), "input_tokens": 9165, "cache_read_tokens": 0, "output_tokens": 70, "model": "gemini-4-carbon-medium", "created_at": "2026-11-17T12:34:56Z"},
	} {
		if err := encoder.Encode(row); err != nil {
			file.Close()
			t.Fatal(err)
		}
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	a := testApp(t)
	rec := a.Store.Create(M{"provider": "antigravity", "externalId": external, "context": M{"used": 9240, "max": 1048576}})
	a.refreshAnalytics(rec)
	if model := S(a.Store.Get(S(rec.Info, "id")).Info, "model"); model != "gemini-4-carbon-medium" {
		t.Fatalf("missing session model was not restored: %q", model)
	}
	res := testRequest(a, "GET", "/api/sessions/"+S(rec.Info, "id")+"/context", "")
	if res.Code != http.StatusOK {
		t.Fatalf("context request returned %d: %s", res.Code, res.Body)
	}
	var detail M
	if err = json.Unmarshal(res.Body.Bytes(), &detail); err != nil {
		t.Fatal(err)
	}
	if S(detail, "model") != "gemini-4-carbon-medium" || N(detail, "totalTokens") != 9240 || !B(detail, "estimated") {
		t.Fatalf("context did not preserve measured totals and model: %v", detail)
	}
	var ids []string
	var total float64
	for _, category := range providerRows(detail["categories"]) {
		if N(category, "tokens") <= 0 {
			t.Fatalf("category has no tokens: %v", category)
		}
		ids = append(ids, S(category, "id"))
		total += N(category, "tokens")
	}
	if !slices.Equal(ids, []string{"agy_base", "agy_user", "agy_assistant", "agy_reasoning", "free_space"}) || total != N(detail, "maxTokens") {
		t.Fatalf("unexpected breakdown: %v, total %g", detail["categories"], total)
	}
	rec.Info["context"] = M{"used": 1000, "max": 1048576}
	compacted, err := agyContextDetail(rec, a.Config.Raw)
	if err != nil {
		t.Fatal(err)
	}
	for _, category := range providerRows(compacted["categories"]) {
		if N(category, "tokens") <= 0 {
			t.Fatalf("smaller live measurement discarded a category: %v", category)
		}
	}
	a.Store.Update(S(rec.Info, "id"), M{"model": "selected-model"}, false)
	a.refreshAnalytics(rec)
	if model := S(a.Store.Get(S(rec.Info, "id")).Info, "model"); model != "selected-model" {
		t.Fatalf("transcript overwrote the selected model: %q", model)
	}
}

func TestAntigravityContextEstimatesSystemAndToolRecords(t *testing.T) {
	var transcript agyContextWeights
	transcript.record("USER_INPUT", M{"content": "質問"})
	transcript.record("SYSTEM_MESSAGE", M{"content": "システムメッセージ"})
	transcript.record("PLANNER_RESPONSE", M{"input_tokens": 400, "cache_read_tokens": 100, "content": "回答", "tool_calls": []M{{"name": "list_files"}}})
	transcript.record("TOOL_RESULT", M{"content": "files"})
	categories := transcript.categories(1000, 1000)
	want := []string{"agy_base", "agy_user", "agy_assistant", "agy_toolRuns", "agy_system"}
	var ids []string
	var total float64
	for _, category := range categories {
		ids = append(ids, S(category, "id"))
		total += N(category, "tokens")
	}
	if !slices.Equal(ids, want) || total != 1000 || N(categories[0], "tokens") != 497 {
		t.Fatalf("unexpected breakdown: %v", categories)
	}
}

func TestAntigravityNewSessionAppliesProviderConfig(t *testing.T) {
	for _, tc := range []struct {
		name       string
		init       M
		wantModel  string
		wantEffort string
		wantCalls  []M
	}{
		{name: "provider default", init: M{"model": "default"}, wantModel: "gemini-4-carbon", wantEffort: "medium"},
		{name: "selected model and effort", init: M{"model": "gemini-4-argon", "effort": "high"}, wantModel: "gemini-4-argon", wantEffort: "high", wantCalls: []M{
			{"sessionId": "fake-agy-thread", "configId": "model", "value": "gemini-4-argon"},
			{"sessionId": "fake-agy-thread", "configId": "thought_level", "value": "high"},
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			store := providerTestStore(t)
			p := NewProvider("antigravity", store, t.TempDir()).(*nativeProvider)
			defer p.Close()
			tc.init["provider"] = "antigravity"
			tc.init["cwd"] = t.TempDir()
			rec := store.Create(tc.init)
			wire := &providerWire{waiting: map[string]chan providerReply{}, done: make(chan struct{}), closeFunc: func() error { return nil }}
			var calls []M
			wire.sendFunc = func(m M) error {
				result := M{}
				switch S(m, "method") {
				case "session/new":
					result = M{
						"sessionId": "fake-agy-thread",
						"configOptions": []M{
							{"id": "model", "currentValue": "gemini-4-carbon"},
							{"id": "thought_level", "currentValue": "medium"},
						},
						"modes": M{"currentModeId": "fast"},
					}
				case "session/set_config_option":
					calls = append(calls, providerMap(m["params"]))
				default:
					t.Fatalf("unexpected ACP request: %v", m)
				}
				wire.receive(M{"id": m["id"], "result": result})
				return nil
			}
			p.rpc = wire
			if _, err := p.ensure(context.Background(), rec); err != nil {
				t.Fatal(err)
			}
			info := store.Get(S(rec.Info, "id")).Info
			if S(info, "model") != tc.wantModel || S(info, "effort") != tc.wantEffort || S(info, "mode") != "fast" {
				t.Fatalf("session config: model %q, effort %q, mode %q", info["model"], info["effort"], info["mode"])
			}
			if !reflect.DeepEqual(calls, tc.wantCalls) {
				t.Fatalf("ACP settings: %v, want %v", calls, tc.wantCalls)
			}
		})
	}
}
