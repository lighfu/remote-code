package host

import (
	"math"
	"testing"
)

func TestCodexCacheSavingsUseActualModelAndTier(t *testing.T) {
	rows := []M{
		{"type": "turn_context", "payload": M{"model": "gpt-6.1-sol"}},
		{"type": "token_usage_record", "payload": M{"response_id": "one", "usage": M{"input_tokens": 150000, "cached_input_tokens": 100000, "cache_write_input_tokens": 20000, "output_tokens": 1000}}},
		{"type": "event_msg", "payload": M{"type": "thread_settings_applied", "thread_settings": M{"model": "gpt-6.2-astra", "service_tier": "priority"}}},
		{"type": "token_usage_record", "payload": M{"response_id": "two", "usage": M{"input_tokens": 300000, "output_tokens": 1000, "input_tokens_details": M{
			"cached_tokens": 200000, "cache_write_tokens": 40000, "cache_write_tokens_details": M{"ttl_30m_tokens": 10000, "ttl_12h_tokens": 30000},
		}}}},
	}
	// Sol saves 100k * (2 - 0.1) less the 20k * 0.5 write premium.
	// Long, priority Astra saves 200k * (32 - 1.6) less both write premiums.
	wantSaved := .18 + (200000*(32-1.6)-10000*(40-32)-30000*(80-32))/1e6
	wantCost := .13 + (60000*32+200000*1.6+10000*40+30000*80+1000*120)/1e6
	rows = append(rows, rows[len(rows)-1]) // repeated completed response
	stats, detail, err := analyticsRead("codex", analyticsFixture(t, rows), nil)
	if err != nil || math.Abs(N(detail, "savedUsd")-wantSaved) > 1e-10 || math.Abs(N(stats, "costUsd")-wantCost) > 1e-10 ||
		!B(detail, "savedKnown") || B(detail, "savedPartial") {
		t.Fatalf("expected savings %v and cost %v, got %v, %v (%v)", wantSaved, wantCost, detail, stats, err)
	}
}

func TestCodexCacheSavingsDistinguishMissingPartialAndZero(t *testing.T) {
	var s analyticsSeries
	check := func(known, partial bool, amount float64) {
		t.Helper()
		_, d := s.result(0)
		if B(d, "savedKnown") != known || B(d, "savedPartial") != partial || (d["savedUsd"] != nil) != known || math.Abs(N(d, "savedUsd")-amount) > 1e-10 {
			t.Fatal(d)
		}
	}
	check(false, false, 0)
	s.add("codex", "unknown", analyticsUsage{Model: "unpriced", Read: 100}, nil)
	check(false, true, 0)
	s.add("codex", "one", analyticsUsage{Model: "gpt-6.2-sol", Read: 100000}, nil)
	check(true, true, .19)
	s.add("codex", "unknown", analyticsUsage{Model: "gpt-6.2-sol", Fresh: 100}, nil)
	check(true, false, .19)
	s.add("codex", "one", analyticsUsage{Model: "gpt-6.2-sol", Fresh: 100000}, nil)
	check(true, false, 0)
}

func TestCodexCacheSavingsBoundaryAndUltrafastPromotion(t *testing.T) {
	for _, tc := range []struct {
		at          string
		input, want float64
	}{
		{"2027-03-01T07:59:59Z", 272000, .57},
		{"2027-03-01T08:00:00Z", 272000, 1.14},
		{"2027-03-01T08:00:00Z", 272001, 2.28},
	} {
		u := analyticsUsage{Model: "gpt-6.2-sol", Tier: "ultrafast", At: providerStamp(tc.at), Read: 100000, Fresh: tc.input - 100000}
		_, saved, _, known := analyticsCharge("codex", u, nil)
		if !known || math.Abs(saved-tc.want) > 1e-10 {
			t.Errorf("%+v: known=%v saved=%v", tc, known, saved)
		}
	}
}

func TestCodexCacheFallbackAccumulatesOnlyObservedRequests(t *testing.T) {
	t.Setenv("CODEX_HOME", t.TempDir())
	store := providerTestStore(t)
	rec := store.Create(M{"provider": "codex", "externalId": "thread", "model": "gpt-6.2-sol", "fastMode": true})
	p := &nativeProvider{name: "codex", store: store}
	id := S(rec.Info, "id")
	push := func(i int) {
		store.recordCodexLiveUsage(id, "thread", M{"total": M{"inputTokens": i * 1000, "cachedInputTokens": i * 800, "outputTokens": i * 50},
			"last": M{"inputTokens": 1000, "cachedInputTokens": 800, "outputTokens": 50, "cacheWriteInputTokens": 100}})
	}
	if d := p.cacheDetail(rec); d["savedUsd"] != nil || B(d, "savedKnown") {
		t.Fatal(d)
	}
	push(5)
	push(5)
	push(6)
	d := p.cacheDetail(store.Get(id))
	if !B(d, "savedKnown") || !B(d, "savedPartial") || math.Abs(N(d, "savedUsd")-2*(800*3.8-100*1.0)/1e6) > 1e-10 {
		t.Fatal(d)
	}
}
