package host

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

func TestCacheHeatmapKeepsOlderRequestsAndAppliesCorrections(t *testing.T) {
	var series analyticsSeries
	const count = 3657
	for i := range count {
		usage := analyticsUsage{Read: 99, Fresh: 1, At: int64(i + 1)}
		if i == 0 {
			usage.Read, usage.Fresh = 0, 100
		}
		series.add("codex", strconv.Itoa(i), usage, nil)
	}
	_, detail := series.result(0)
	heatmap := detail["heatmap"].(cacheHeatmap)
	if heatmap.RequestsPerCell != 32 || len(heatmap.Cells) != (count+31)/32 || len(detail["recent"].([]M)) != 200 {
		t.Fatalf("incorrect grouping: %d per cell, %d cells", heatmap.RequestsPerCell, len(heatmap.Cells))
	}
	if first := heatmap.Cells[0]; first != (cacheHeatmapCell{Read: 31 * 99, Input: 3200, Low: 1}) {
		t.Fatalf("the first request disappeared when grouping: %+v", first)
	}
	if last := heatmap.Cells[len(heatmap.Cells)-1]; last.Input != (count%32)*100 {
		t.Fatalf("unexpected input for final partial group: %+v", last)
	}
	series.add("codex", "0", analyticsUsage{Read: 95, Fresh: 5}, nil)
	series.add("codex", "3", analyticsUsage{Output: 10}, nil)
	_, updated := series.result(0)
	if first := updated["heatmap"].(cacheHeatmap).Cells[0]; first != (cacheHeatmapCell{Read: 30*99 + 95, Input: 3100}) {
		t.Fatalf("incorrect correction after the request left recent history: %+v", first)
	}
	if heatmap.Cells[0].Low != 1 || N(providerMap(updated["totals"]), "requests") != count {
		t.Fatal("a correction modified the previous response or duplicated a request")
	}
}

func TestAnalyticsIncrementalReadAcrossBufferAndAppendBoundaries(t *testing.T) {
	var s analyticsState
	row, err := json.Marshal(M{"type": "turn_context", "payload": M{"padding": strings.Repeat("x", 100<<10), "model": "updated"}})
	if err != nil {
		t.Fatal(err)
	}
	n := int64(len(row))
	if _, err := s.readUntil("codex", bytes.NewReader(row), n, nil); err != nil || s.model != "" || len(s.rest) != len(row) {
		t.Fatalf("unfinished row: cursor=%d partial=%d model=%q err=%v", s.offset, len(s.rest), s.model, err)
	}
	if _, err := s.readUntil("codex", strings.NewReader("\n"), n+1, nil); err != nil || s.model != "updated" || len(s.rest) != 0 {
		t.Fatalf("finished row: model=%q err=%v", s.model, err)
	}
	if _, err := s.readUntil("codex", strings.NewReader("short"), s.offset+50, nil); !errors.Is(err, io.ErrUnexpectedEOF) ||
		s.offset != 0 || s.model != "" || len(s.rest) != 0 {
		t.Fatalf("shortened transcript kept partial state: cursor=%d model=%q err=%v", s.offset, s.model, err)
	}
}

func analyticsFixture(t *testing.T, rows []M) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "transcript.jsonl")
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, row := range rows {
		if err = json.NewEncoder(f).Encode(row); err != nil {
			t.Fatal(err)
		}
	}
	f.Close()
	return path
}

func TestClaudePlanLoadsFromHistoryAndRefreshesDuringPCWatch(t *testing.T) {
	home := t.TempDir()
	t.Setenv("CLAUDE_CONFIG_DIR", home)
	external := NewID()
	path := filepath.Join(home, "projects", "test", external+".jsonl")
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		t.Fatal(err)
	}
	appendPlan := func(id, timestamp string, input M) {
		t.Helper()
		file, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
		if err != nil {
			t.Fatal(err)
		}
		defer file.Close()
		row := M{"type": "assistant", "timestamp": timestamp, "message": M{"content": []M{
			{"type": "tool_use", "id": id, "name": "mcp__ccd_turn__update_plan", "input": input},
		}}}
		if err := json.NewEncoder(file).Encode(row); err != nil {
			t.Fatal(err)
		}
	}
	appendPlan("first", "2026-11-18T00:00:00Z", M{"items": []M{
		{"content": "Review history", "status": "in_progress"},
		{"content": "Verify display", "status": "pending"},
	}})
	a := testApp(t)
	rec := a.Store.Create(M{"provider": "claude", "externalId": external, "watching": true})
	id := S(rec.Info, "id")
	response := testRequest(a, "GET", "/api/sessions/"+id, "")
	var detail M
	if err := json.Unmarshal(response.Body.Bytes(), &detail); err != nil {
		t.Fatal(err)
	}
	plan := providerMap(providerMap(detail["session"])["plan"])
	items := providerRows(plan["items"])
	if response.Code != http.StatusOK || len(items) != 2 || S(items[0], "status") != "in_progress" || N(plan, "updatedAt") <= 0 {
		t.Fatalf("first session response did not include its plan: %d %s", response.Code, response.Body)
	}

	events, cancel := a.Store.Subscribe()
	defer cancel()
	appendPlan("advance", "2026-11-18T00:00:01Z", M{"advance": true})
	a.refreshActiveClaudePlans(t.Context())
	advanced := false
	for len(events) > 0 {
		event := <-events
		session := providerMap(event["session"])
		items := providerRows(providerMap(session["plan"])["items"])
		if S(event, "type") == "session" && S(session, "id") == id && len(items) == 2 &&
			S(items[0], "status") == "completed" && S(items[1], "status") == "in_progress" {
			advanced = true
			break
		}
	}
	if !advanced {
		t.Fatal("Claude PC watch did not publish the new plan")
	}
	appendPlan("clear", "2026-11-18T00:00:02Z", M{"items": []M{}})
	a.refreshActiveClaudePlans(t.Context())
	if plan := a.Store.Get(id).Info["plan"]; plan != nil {
		t.Fatal("explicitly cleared plan was retained", plan)
	}
	a.Store.Update(id, M{"resumeAt": "rewind-target"}, false)
	if a.Store.applyClaudePlan(rec, plan) {
		t.Fatal("plan from before rewind was applied")
	}
}

func TestAnalyticsIncrementalCodexTotalsAndCache(t *testing.T) {
	rows := []M{{"type": "turn_context", "payload": M{"model": "custom"}}, {"type": "event_msg", "timestamp": "2026-01-01T12:00:00Z", "payload": M{"type": "token_usage_record", "usage": M{"input_tokens": 1000, "cached_input_tokens": 800, "output_tokens": 10}}}}
	path := analyticsFixture(t, rows)
	config := M{"codexPrices": M{"custom": M{"input": 2, "cachedInput": 0.2, "output": 10}}}
	stats, detail, err := analyticsRead("codex", path, config)
	if err != nil {
		t.Fatal(err)
	}
	tokens := providerMap(stats["tokens"])
	if N(tokens, "input") != 1000 || N(tokens, "cached") != 800 || N(tokens, "output") != 10 {
		t.Fatal(stats)
	}
	if N(providerMap(stats["cache"]), "lastAt") != 1767268800000 {
		t.Fatal("historical cache was renewed", stats)
	}
	cost := N(stats, "costUsd")
	if cost < 0.000659 || cost > 0.000661 {
		t.Fatal(cost)
	}
	if N(providerMap(detail["totals"]), "requests") != 1 {
		t.Fatal(detail)
	}
	stats2, _, err := analyticsRead("codex", path, config)
	if err != nil || N(providerMap(stats2["tokens"]), "input") != 1000 {
		t.Fatal("unchanged file counted twice", stats2, err)
	}
	f, _ := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0600)
	_ = json.NewEncoder(f).Encode(rows[1])
	f.Close()
	stats3, _, err := analyticsRead("codex", path, config)
	if err != nil || N(providerMap(stats3["tokens"]), "input") != 2000 {
		t.Fatal("append not aggregated", stats3, err)
	}
}

func TestAnalyticsCodexTokenCountDoesNotRenewCacheForRepeatedReports(t *testing.T) {
	report := func(timestamp string, total float64) M {
		return M{"type": "event_msg", "timestamp": timestamp, "payload": M{"type": "token_count", "info": M{
			"last_token_usage":  M{"input_tokens": 1000, "cached_input_tokens": 800, "output_tokens": 10},
			"total_token_usage": M{"input_tokens": 1000 * total, "cached_input_tokens": 800 * total, "output_tokens": 10 * total},
		}}}
	}
	path := analyticsFixture(t, []M{
		{"type": "turn_context", "payload": M{"model": "custom"}},
		report("2026-01-01T12:00:00Z", 1),
		report("2026-01-01T12:01:00Z", 1),
		report("2026-01-01T12:02:00Z", 2),
		report("2026-01-01T12:03:00Z", 2),
	})
	config := M{"codexPrices": M{"custom": M{"input": 2, "cachedInput": 0.2, "output": 10}}}
	stats, detail, err := analyticsRead("codex", path, config)
	if err != nil {
		t.Fatal(err)
	}
	cache := providerMap(stats["cache"])
	if N(cache, "lastHit") != 0.8 || N(cache, "ttlMs") != 300000 || N(cache, "lastAt") != float64(providerStamp("2026-01-01T12:02:00Z")) {
		t.Fatal("duplicate report changed cache summary", cache)
	}
	totals := providerMap(detail["totals"])
	tokens := providerMap(stats["tokens"])
	if N(totals, "requests") != 2 || N(totals, "hitRate") != 0.8 || len(maps(detail["recent"])) != 2 ||
		N(tokens, "input") != 2000 || N(tokens, "cached") != 1600 || N(tokens, "output") != 20 || N(stats, "costUsd") <= 0 {
		t.Fatal("token count usage not aggregated", stats, detail)
	}
}

func TestAnalyticsCodexLegacyUsageDoesNotDoubleCountTokenReports(t *testing.T) {
	path := analyticsFixture(t, []M{
		{"type": "event_msg", "timestamp": "2026-01-01T12:00:00Z", "payload": M{"type": "token_usage_record", "usage": M{
			"input_tokens": 100, "cached_input_tokens": 40, "cache_write_input_tokens": 25, "output_tokens": 10,
		}}},
		{"type": "event_msg", "timestamp": "2026-01-01T12:00:01Z", "payload": M{"type": "token_count", "info": M{
			"last_token_usage":  M{"input_tokens": 100, "cached_input_tokens": 40, "output_tokens": 10},
			"total_token_usage": M{"input_tokens": 100, "cached_input_tokens": 40, "output_tokens": 10},
		}}},
	})
	stats, detail, err := analyticsRead("codex", path, M{})
	if err != nil {
		t.Fatal(err)
	}
	totals := providerMap(detail["totals"])
	if N(totals, "requests") != 1 || N(totals, "write5m") != 25 || N(providerMap(stats["tokens"]), "input") != 100 {
		t.Fatal("legacy record was counted twice or lost cache writes", stats, detail)
	}
	f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	err = json.NewEncoder(f).Encode(M{"type": "event_msg", "timestamp": "2026-01-01T12:01:00Z", "payload": M{"type": "token_count", "info": M{
		"last_token_usage":  M{"input_tokens": 100, "cached_input_tokens": 40, "output_tokens": 10},
		"total_token_usage": M{"input_tokens": 200, "cached_input_tokens": 80, "output_tokens": 20},
	}}})
	_ = f.Close()
	if err != nil {
		t.Fatal(err)
	}
	stats, detail, err = analyticsRead("codex", path, M{})
	if err != nil || N(providerMap(detail["totals"]), "requests") != 2 || N(providerMap(stats["tokens"]), "input") != 200 {
		t.Fatal("legacy prefix froze later token-count updates", stats, detail, err)
	}
}

func TestAnalyticsCodexReadsStandaloneUsageAcrossCompaction(t *testing.T) {
	first := M{"type": "token_usage_record", "timestamp": "2026-01-01T12:00:00Z", "payload": M{
		"response_id": "first",
		"usage":       M{"input_tokens": 100, "cached_input_tokens": 80, "output_tokens": 10},
	}}
	last := M{"type": "token_usage_record", "timestamp": "2026-01-01T12:03:00Z", "payload": M{
		"response_id": "second",
		"usage":       M{"input_tokens": 400, "cached_input_tokens": 330, "cache_write_input_tokens": 25, "output_tokens": 20},
	}}
	path := analyticsFixture(t, []M{
		first,
		first,
		{"type": "event_msg", "timestamp": "2026-01-01T12:00:01Z", "payload": M{"type": "token_count", "info": M{
			"last_token_usage":  M{"input_tokens": 100, "cached_input_tokens": 80, "output_tokens": 10},
			"total_token_usage": M{"input_tokens": 100, "cached_input_tokens": 80, "output_tokens": 10},
		}}},
		last,
		{"type": "compacted", "payload": M{}},
		{"type": "event_msg", "timestamp": "2026-01-01T12:03:01Z", "payload": M{"type": "token_count", "info": M{
			"last_token_usage":  M{"input_tokens": 80, "output_tokens": 10},
			"total_token_usage": M{"input_tokens": 100, "cached_input_tokens": 80, "output_tokens": 10},
		}}},
	})

	stats, detail, err := analyticsRead("codex", path, M{})
	if err != nil {
		t.Fatal(err)
	}
	totals, tokens := providerMap(detail["totals"]), providerMap(stats["tokens"])
	if N(totals, "requests") != 2 || len(maps(detail["recent"])) != 2 || N(totals, "write5m") != 25 ||
		N(tokens, "input") != 500 || N(tokens, "cached") != 410 || N(tokens, "output") != 30 {
		t.Fatal("completed requests were omitted or counted twice", stats, detail)
	}
	cache := providerMap(stats["cache"])
	if N(cache, "lastHit") != 0.825 || N(cache, "lastAt") != float64(providerStamp("2026-01-01T12:03:00Z")) {
		t.Fatal("stale context report replaced the latest completed request", cache)
	}
}

func TestStoreKeepsCodexStatsWhenRolloutHasNoUsageSamples(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	source := store.Create(M{"provider": "codex", "externalId": "thread", "stats": M{
		"cache":   M{"lastAt": 123, "ttlMs": 300000, "lastHit": 0.8},
		"costUsd": 1.5,
		"bytes":   1234,
		"tokens":  M{"input": 1000, "cached": 800, "output": 10},
	}})
	id := S(source.Info, "id")
	store.applyAnalyticsStats(source, M{"cache": nil, "tokens": M{}, "bytes": 1300})
	stats := providerMap(store.Get(id).Info["stats"])
	if N(providerMap(stats["cache"]), "lastAt") != 123 || N(providerMap(stats["tokens"]), "cached") != 800 || N(stats, "costUsd") != 1.5 || N(stats, "bytes") != 1300 {
		t.Fatal("missing usage erased previous stats", stats)
	}
	store.Update(id, M{"externalId": "replacement"}, false)
	store.applyAnalyticsStats(source, M{"cache": M{"lastAt": 999}, "bytes": 9999})
	if N(providerMap(store.Get(id).Info["stats"]), "bytes") != 1300 {
		t.Fatal("refresh for old thread updated replacement")
	}
}

func TestCodexLiveUsageIsOnlyAFallbackAndDoesNotRenewRepeatedTotals(t *testing.T) {
	home := t.TempDir()
	t.Setenv("CODEX_HOME", home)
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	external := NewID()
	id := S(store.Create(M{"provider": "codex", "externalId": external}).Info, "id")
	usage := M{
		"totalTokenUsage": M{"inputTokens": 1000, "cachedInputTokens": 800, "outputTokens": 10},
		"lastTokenUsage":  M{"inputTokens": 1000, "cachedInputTokens": 800, "outputTokens": 10},
	}
	store.recordCodexLiveUsage(id, external, usage)
	stats := providerMap(store.Get(id).Info["stats"])
	cache := providerMap(stats["cache"])
	if N(cache, "lastAt") <= 0 || N(cache, "ttlMs") != 300000 || N(cache, "lastHit") != 0.8 || N(providerMap(stats["tokens"]), "input") != 1000 {
		t.Fatal("valid live fallback missing", stats)
	}
	events, cancel := store.Subscribe()
	defer cancel()
	store.recordCodexLiveUsage(id, external, usage)
	select {
	case <-events:
		t.Fatal("identical usage published a cache renewal")
	default:
	}

	path := filepath.Join(home, "sessions", "rollout-"+external+".jsonl")
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, nil, 0600); err != nil {
		t.Fatal(err)
	}
	usage["totalTokenUsage"] = M{"inputTokens": 2000, "cachedInputTokens": 1600, "outputTokens": 20}
	store.recordCodexLiveUsage(id, external, usage)
	if N(providerMap(providerMap(store.Get(id).Info["stats"])["tokens"]), "input") != 1000 {
		t.Fatal("live fallback competed with a local rollout")
	}
}
func TestAnalyticsClaudeUsageDedupAndUnknownPrice(t *testing.T) {
	msg := M{"type": "assistant", "uuid": "uuid", "timestamp": "2026-01-01T12:00:00Z", "message": M{"id": "message", "model": "unknown-model", "usage": M{"input_tokens": 100, "cache_read_input_tokens": 50, "output_tokens": 10}}}
	path := analyticsFixture(t, []M{msg, msg})
	stats, detail, err := analyticsRead("claude", path, M{})
	if err != nil {
		t.Fatal(err)
	}
	if stats["costUsd"] != nil || !B(stats, "partial") {
		t.Fatal("invented unknown price", stats)
	}
	if N(providerMap(stats["tokens"]), "input") != 150 || N(providerMap(detail["totals"]), "requests") != 1 {
		t.Fatal("duplicated assistant message", stats, detail)
	}
}

func TestClaudeLiveTotalsAndLastRequestCacheRatio(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	rec := store.Create(M{"provider": "claude"})
	id := S(rec.Info, "id")
	p := &nativeProvider{store: store}
	runtime := &nativeSession{}
	send := func(message string, fresh, cached, created, output int) {
		p.claudeStats(id, runtime, M{"type": "assistant", "message": M{"id": message, "usage": M{
			"input_tokens": fresh, "cache_read_input_tokens": cached, "cache_creation_input_tokens": created, "output_tokens": output,
		}}})
	}
	send("one", 30, 60, 10, 5)
	send("one", 30, 60, 10, 7)
	send("two", 15, 5, 0, 3)
	stats := providerMap(store.Get(id).Info["stats"])
	tokens := providerMap(stats["tokens"])
	if N(tokens, "input") != 120 || N(tokens, "cached") != 65 || N(tokens, "output") != 10 || N(providerMap(stats["cache"]), "lastHit") != .25 {
		t.Fatal("live message snapshots did not match their transcript semantics", stats)
	}
	p.claudeStats(id, runtime, M{"type": "result", "total_cost_usd": 0, "usage": M{
		"input_tokens": 45, "cache_read_input_tokens": 65, "cache_creation_input_tokens": 10, "output_tokens": 10,
	}})
	store.applyAnalyticsStats(rec, M{"tokens": tokens, "costUsd": .5, "cache": stats["cache"]})
	stats = providerMap(store.Get(id).Info["stats"])
	if cost, ok := reportedAmount(stats, "costUsd"); !ok || cost != 0 || B(stats, "partial") || N(providerMap(stats["cache"]), "lastHit") != .25 {
		t.Fatal("lost the reported total or replaced last request's cache ratio", stats)
	}
	send("three", 5, 0, 0, 3)
	if !B(providerMap(store.Get(id).Info["stats"]), "partial") {
		t.Fatal("previous result was shown as a complete cost for a later request")
	}
}

func TestAntigravityStepUsageAndReportedSessionCost(t *testing.T) {
	step := func(index, fresh, read, output int) M {
		return M{"type": "PLANNER_RESPONSE", "step_index": index, "model": "unpriced",
			"input_tokens": fresh, "cache_read_tokens": read, "output_tokens": output}
	}
	transcript := analyticsFixture(t, []M{step(0, 40, 60, 11), step(0, 40, 60, 13), step(1, 15, 5, 3)})
	stats, detail, err := analyticsRead("antigravity", transcript, nil)
	if err != nil {
		t.Fatal(err)
	}
	tokens := providerMap(stats["tokens"])
	if N(tokens, "input") != 120 || N(tokens, "cached") != 65 || N(tokens, "output") != 16 || N(providerMap(detail["totals"]), "requests") != 2 ||
		N(providerMap(stats["cache"]), "lastHit") != .25 || stats["costUsd"] != nil {
		t.Fatal("invalid deduplication or token normalization", stats, detail)
	}
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	rec := store.Create(M{"provider": "antigravity", "externalId": "thread"})
	id := S(rec.Info, "id")
	report := func(thread, currency string, amount any) {
		store.recordAgyUsage(id, thread, M{"used": 50, "size": 100, "cost": M{"amount": amount, "currency": currency}})
	}
	report("thread", "credits", 12)
	report("thread", "USD", "unknown")
	report("another", "USD", 1)
	saved := store.Get(id)
	before := providerMap(saved.Info["stats"])
	if N(providerMap(saved.Info["context"]), "used") != 50 || before["tokens"] != nil || before["costUsd"] != nil {
		t.Fatal("invented usage or cost from unrelated data", saved.Info)
	}
	report("thread", "USD", 1.25)
	store.applyAnalyticsStats(rec, stats)
	after := providerMap(store.Get(id).Info["stats"])
	if N(after, "costUsd") != 1.25 || B(after, "partial") || N(providerMap(after["tokens"]), "input") != 120 {
		t.Fatal("lost ACP session cost while importing transcript tokens", after)
	}
	report("thread", "USD", 0)
	store.applyAnalyticsStats(rec, M{"bytes": 500})
	after = providerMap(store.Get(id).Info["stats"])
	if amount, ok := reportedAmount(after, "costUsd"); !ok || amount != 0 || N(providerMap(after["tokens"]), "output") != 16 {
		t.Fatal("explicit zero or existing usage was lost", after)
	}
	stats, _, err = analyticsRead("antigravity", analyticsFixture(t, []M{{"type": "PLANNER_RESPONSE"}}), nil)
	if err != nil || stats["tokens"] != nil {
		t.Fatal("records without counters should leave usage unavailable", stats, err)
	}
}
func TestAnalyticsDefersPartialLineAndResetsTruncation(t *testing.T) {
	path := analyticsFixture(t, nil)
	text := `{"type":"PLANNER_RESPONSE","model":"unknown","input_tokens":5,"output_tokens":2}`
	if err := os.WriteFile(path, []byte(text[:30]), 0600); err != nil {
		t.Fatal(err)
	}
	stats, _, err := analyticsRead("antigravity", path, M{})
	if err != nil || N(providerMap(stats["tokens"]), "input") != 0 {
		t.Fatal(stats, err)
	}
	f, _ := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0600)
	_, _ = f.WriteString(text[30:] + "\n")
	f.Close()
	stats, _, err = analyticsRead("antigravity", path, M{})
	if err != nil || N(providerMap(stats["tokens"]), "input") != 5 {
		t.Fatal(stats, err)
	}
	_ = os.WriteFile(path, []byte{}, 0600)
	stats, _, err = analyticsRead("antigravity", path, M{})
	if err != nil || N(providerMap(stats["tokens"]), "input") != 0 {
		t.Fatal(stats, err)
	}
}
