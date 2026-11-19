package host

import (
	"bufio"
	"bytes"
	_ "embed"
	"encoding/json"
	"errors"
	"io"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

//go:embed prices.json
var analyticsPricesJSON []byte
var analyticsPrices M

const codexCacheTTL = 5 * time.Minute

func init() { _ = json.Unmarshal(analyticsPricesJSON, &analyticsPrices) }

// A missing or malformed amount is different from an explicitly reported zero.
func reportedAmount(m M, key string) (float64, bool) {
	var amount float64
	switch value := m[key].(type) {
	case float64, int, int64:
		amount = N(m, key)
	case json.Number:
		var err error
		if amount, err = value.Float64(); err != nil {
			return 0, false
		}
	default:
		return 0, false
	}
	return amount, amount >= 0 && !math.IsInf(amount, 0) && !math.IsNaN(amount)
}

type analyticsUsage struct {
	At                                  int64
	Model, ID, Tier                     string
	Read, Write5, Write1, Fresh, Output float64
	CodexWrite12h                       float64 // included in Write5, the legacy Codex write-total field
}

type analyticsSample struct {
	usage analyticsUsage
	index int
}

type analyticsSeries struct {
	requests                                           []analyticsUsage
	seen                                               map[string]analyticsSample
	count                                              int
	input, cached, write5, write1, output, cost, saved float64
	pricedRequests, savingsSamples                     int
	missingCost, missingSavings                        bool
	last                                               analyticsUsage
	cacheBeforeRecent                                  analyticsCacheHistory
	heatmap                                            cacheHeatmap
	provider                                           string
}

type analyticsCacheHistory struct {
	at, ttl int64
	expired int
}

func (h *analyticsCacheHistory) observe(provider string, u analyticsUsage) any {
	if h.ttl == 0 {
		h.ttl = codexCacheTTL.Milliseconds()
	}
	var gap any
	if h.at > 0 {
		gap = u.At - h.at
		miss := u.Read < (u.Fresh+u.Read+u.Write5+u.Write1)*0.5
		if provider == "claude" {
			miss = u.Read == 0 && u.Write5+u.Write1 > 0
		}
		if u.At-h.at > h.ttl && miss {
			h.expired++
		}
	}
	if u.Write1 > 0 {
		h.ttl = time.Hour.Milliseconds()
	} else if u.Write5 > 0 {
		h.ttl = codexCacheTTL.Milliseconds()
	}
	h.at = u.At
	return gap
}

type analyticsState struct {
	mu          sync.Mutex
	offset      int64
	rest        []byte
	model, tier string
	analyticsSeries
	codexCounts  analyticsSeries
	codexTotal   analyticsUsage
	codexContext codexContextWeights
	agyContext   agyContextWeights
	claudePlan   claudePlanState
	used, window float64
	file         os.FileInfo
}

var analyticsFiles sync.Map
var analyticsPaths sync.Map

func analyticsPath(provider, id string) string {
	if id == "" || filepath.Base(id) != id {
		return ""
	}
	key := provider + ":" + id
	if value, ok := analyticsPaths.Load(key); ok {
		p := value.(string)
		if provider != "antigravity" || filepath.Base(p) == "transcript_full.jsonl" {
			if _, err := os.Stat(p); err == nil {
				return p
			}
		}
	}
	root := providerHome(provider)
	if provider == "codex" {
		for _, dir := range []string{"sessions", "archived_sessions"} {
			base := filepath.Join(root, dir)
			var found string
			_ = filepath.WalkDir(base, func(path string, d os.DirEntry, err error) error {
				if err != nil {
					return nil
				}
				if !d.IsDir() && strings.HasSuffix(d.Name(), "-"+id+".jsonl") {
					found = path
					return filepath.SkipAll
				}
				return nil
			})
			if found != "" {
				analyticsPaths.Store(key, found)
				return found
			}
		}
		return ""
	}
	if provider == "claude" {
		dirs, _ := os.ReadDir(filepath.Join(root, "projects"))
		for _, dir := range dirs {
			p := filepath.Join(root, "projects", dir.Name(), id+".jsonl")
			if _, err := os.Stat(p); err == nil {
				analyticsPaths.Store(key, p)
				return p
			}
		}
		return ""
	}
	for _, name := range []string{"transcript_full.jsonl", "transcript.jsonl"} {
		for _, folder := range []string{"antigravity", "antigravity-ide", "antigravity-cli"} {
			p := filepath.Join(root, folder, "brain", id, ".system_generated", "logs", name)
			if _, err := os.Stat(p); err == nil {
				analyticsPaths.Store(key, p)
				return p
			}
		}
	}
	return ""
}
func analyticsPrice(provider, model string, raw M) M {
	if provider != "claude" {
		custom := providerMap(raw[provider+"Prices"])
		if val := providerMap(custom[model]); len(val) > 0 {
			return val
		}
		if provider == "antigravity" {
			base := model
			for _, s := range []string{"-none", "-minimal", "-low", "-medium", "-high", "-xhigh", "-max"} {
				base = strings.TrimSuffix(base, s)
			}
			if val := providerMap(custom[base]); len(val) > 0 {
				return val
			}
			if strings.HasPrefix(base, "claude-") {
				price := analyticsPrice("claude", base, raw)
				if len(price) > 0 {
					return M{"input": N(price, "input"), "cachedInput": N(price, "cache_read"), "output": N(price, "output")}
				}
			}
		}
	}
	models := providerMap(analyticsPrices[provider])
	model = strings.Split(model, "[")[0]
	if len(model) > 9 && model[len(model)-9] == '-' {
		tail := model[len(model)-8:]
		digits := true
		for _, c := range tail {
			if c < '0' || c > '9' {
				digits = false
			}
		}
		if digits {
			model = model[:len(model)-9]
		}
	}
	return providerMap(models[model])
}
func analyticsCharge(provider string, u analyticsUsage, config M) (float64, float64, bool, bool) {
	p := analyticsPrice(provider, u.Model, config)
	noCache := u.Read == 0 && u.Write5 == 0 && u.Write1 == 0
	if len(p) == 0 {
		return 0, 0, false, noCache
	}
	if provider == "codex" {
		return codexCharge(p, u)
	}
	if provider == "claude" {
		long := providerMap(p["long_prompt"])
		if len(long) > 0 && u.Fresh+u.Read+u.Write1+u.Write5 > N(long, "above_prompt_tokens") {
			p = long
		}
		cost := (u.Fresh*N(p, "input") + u.Read*N(p, "cache_read") + u.Write5*N(p, "cache_write_5m") + u.Write1*N(p, "cache_write_1h") + u.Output*N(p, "output")) / 1e6
		saved := (u.Read*(N(p, "input")-N(p, "cache_read")) - u.Write5*(N(p, "cache_write_5m")-N(p, "input")) - u.Write1*(N(p, "cache_write_1h")-N(p, "input"))) / 1e6
		return cost, saved, true, true
	}
	cost := (u.Fresh*N(p, "input") + u.Read*N(p, "cachedInput") + u.Output*N(p, "output")) / 1e6
	return cost, u.Read * (N(p, "input") - N(p, "cachedInput")) / 1e6, true, true
}
func (s *analyticsSeries) add(provider, id string, u analyticsUsage, config M) {
	s.provider = provider
	u.ID = id
	sample, updated := s.seen[id]
	index := s.count
	if updated {
		old := sample.usage
		index = sample.index
		if old.At > 0 {
			u.At = old.At
		}
		if old == u {
			return
		}
		s.heatmap.replace(index, old, u)
		s.input -= old.Fresh + old.Read + old.Write5 + old.Write1
		s.cached -= old.Read
		s.write5 -= old.Write5
		s.write1 -= old.Write1
		s.output -= old.Output
		cost, saved, priced, savingsKnown := analyticsCharge(provider, old, config)
		s.cost -= cost
		s.saved -= saved
		if priced {
			s.pricedRequests--
		}
		if savingsKnown {
			s.savingsSamples--
		}
	}
	if id != "" {
		if s.seen == nil {
			s.seen = map[string]analyticsSample{}
		}
		s.seen[id] = analyticsSample{usage: u, index: index}
	}
	s.input += u.Fresh + u.Read + u.Write5 + u.Write1
	s.cached += u.Read
	s.write5 += u.Write5
	s.write1 += u.Write1
	s.output += u.Output
	cost, saved, known, savingsKnown := analyticsCharge(provider, u, config)
	s.cost += cost
	s.saved += saved
	if savingsKnown {
		s.savingsSamples++
	}
	if known {
		s.pricedRequests++
	}
	if updated {
		for i := len(s.requests) - 1; i >= 0; i-- {
			if s.requests[i].ID == id {
				s.requests[i] = u
				break
			}
		}
		if s.last.ID == id {
			s.last = u
		}
		return
	}
	s.last = u
	s.heatmap.append(index, u)
	s.requests = append(s.requests, u)
	if len(s.requests) > 200 {
		s.cacheBeforeRecent.observe(provider, s.requests[0])
		s.requests = s.requests[1:]
	}
	s.count++
}

func codexCountUsage(value M) analyticsUsage {
	input := max(0, N(value, "input_tokens"))
	details := providerMap(value["input_tokens_details"])
	read, write := N(value, "cached_input_tokens"), N(value, "cache_write_input_tokens")
	if value["cached_input_tokens"] == nil {
		read = N(details, "cached_tokens")
	}
	if value["cache_write_input_tokens"] == nil {
		write = N(details, "cache_write_tokens")
	}
	read = min(input, max(0, read))
	write = min(input-read, max(0, write))
	longWrite := N(providerMap(details["cache_write_tokens_details"]), "ttl_12h_tokens")
	return analyticsUsage{Read: read, Write5: write, CodexWrite12h: min(write, max(0, longWrite)), Fresh: input - read - write, Output: max(0, N(value, "output_tokens"))}
}

func (s *analyticsState) codexTokenCount(record, info M, config M) {
	last := providerMap(info["last_token_usage"])
	if window := N(info, "model_context_window"); len(last) > 0 && window > 0 {
		s.used = N(last, "input_tokens") + N(last, "output_tokens")
		s.window = window
	}

	// A token_count can be emitted repeatedly for the same completed request.
	// Its cumulative totals distinguish that from separate requests of equal size.
	total := codexCountUsage(providerMap(info["total_token_usage"]))
	usage := codexCountUsage(last)
	if total == (analyticsUsage{}) || total == s.codexTotal || usage == (analyticsUsage{}) {
		return
	}
	previous := s.codexTotal
	s.codexTotal = total
	usage.Model = s.model
	usage.Tier = s.tier
	usage.At = s.codexCounts.last.At
	if at, err := time.Parse(time.RFC3339Nano, S(record, "timestamp")); err == nil {
		usage.At = at.UnixMilli()
	}
	s.codexCounts.add("codex", "", usage, config)

	// If the rollout starts with an existing cumulative total or misses a
	// report, keep the known token counts without inventing request details or costs.
	fresh := total.Fresh - previous.Fresh - usage.Fresh
	read := total.Read - previous.Read - usage.Read
	write := total.Write5 - previous.Write5 - usage.Write5
	output := total.Output - previous.Output - usage.Output
	if fresh >= 0 && read >= 0 && write >= 0 && output >= 0 && fresh+read+write+output > 0 {
		s.codexCounts.input += fresh + read + write
		s.codexCounts.cached += read
		s.codexCounts.write5 += write
		s.codexCounts.output += output
		s.codexCounts.missingCost = true
		s.codexCounts.missingSavings = s.codexCounts.missingSavings || read+write > 0
	}
}
func (s *analyticsState) line(provider string, line []byte, config M) {
	var r M
	if json.Unmarshal(line, &r) != nil {
		return
	}
	kind := S(r, "type")
	var u analyticsUsage
	var id string
	switch provider {
	case "claude":
		s.claudePlan.observe(r)
		if kind != "assistant" {
			return
		}
		message := providerMap(r["message"])
		usage := providerMap(message["usage"])
		if len(usage) == 0 {
			return
		}
		u.Model = S(message, "model")
		u.At = providerStamp(r["timestamp"])
		u.Fresh = N(usage, "input_tokens")
		u.Read = N(usage, "cache_read_input_tokens")
		write := N(usage, "cache_creation_input_tokens")
		u.Write1 = min(write, N(providerMap(usage["cache_creation"]), "ephemeral_1h_input_tokens"))
		u.Write5 = write - u.Write1
		u.Output = N(usage, "output_tokens")
		id = S(message, "id")
		if id == "" {
			id = S(r, "uuid")
		}
		s.used = u.Fresh + u.Read + u.Write5 + u.Write1 + u.Output
	case "codex":
		payload := providerMap(r["payload"])
		s.codexContext.record(kind, payload, int64(len(line)))
		if kind == "turn_context" {
			s.model = S(payload, "model")
			if _, ok := payload["service_tier"]; ok {
				s.tier = S(payload, "service_tier")
			}
			return
		}
		if S(payload, "type") == "thread_settings_applied" {
			settings := providerMap(payload["thread_settings"])
			if model := S(settings, "model"); model != "" {
				s.model = model
			}
			if _, ok := settings["service_tier"]; ok {
				s.tier = S(settings, "service_tier")
			}
			return
		}
		if S(payload, "type") == "token_count" {
			s.codexTokenCount(r, providerMap(payload["info"]), config)
			return
		}
		if kind != "token_usage_record" && S(payload, "type") != "token_usage_record" {
			return
		}
		usage := providerMap(payload["usage"])
		if len(usage) == 0 {
			return
		}
		u = codexCountUsage(usage)
		u.Model, u.Tier = s.model, s.tier
		if model := S(payload, "model"); model != "" {
			u.Model = model
		}
		if tier := S(payload, "service_tier"); tier != "" {
			u.Tier = tier
		}
		u.At = providerStamp(r["timestamp"])
		id = S(payload, "response_id")
		if id == "" {
			id = S(payload, "id")
		}
	case "antigravity":
		s.agyContext.record(kind, r)
		if kind != "PLANNER_RESPONSE" {
			return
		}
		if r["input_tokens"] == nil && r["cache_read_tokens"] == nil && r["output_tokens"] == nil {
			return
		}
		u.Model = s.agyContext.model
		u.At = providerStamp(r["created_at"])
		u.Fresh = N(r, "input_tokens")
		u.Read = N(r, "cache_read_tokens")
		u.Output = N(r, "output_tokens")
		if used := u.Fresh + u.Read + u.Output; used > 0 {
			s.used = used
		}
		id = S(r, "id")
		if step, ok := reportedAmount(r, "step_index"); id == "" && ok && step == math.Trunc(step) {
			id = "step:" + strconv.FormatFloat(step, 'f', 0, 64)
		}
	default:
		return
	}
	if u.At <= 0 {
		u.At = s.last.At
	}
	s.add(provider, id, u, config)
}
func analyticsStateFor(path string) *analyticsState {
	value, _ := analyticsFiles.LoadOrStore(path, &analyticsState{})
	return value.(*analyticsState)
}

func analyticsRead(provider, path string, config M) (M, M, error) {
	s := analyticsStateFor(path)
	s.mu.Lock()
	defer s.mu.Unlock()
	size, err := s.read(provider, path, config)
	if err != nil {
		return nil, nil, err
	}
	series := &s.analyticsSeries
	if provider == "codex" {
		counts := &s.codexCounts
		// Keep explicit cache writes when both sources cover the same usage.
		if series.count == 0 || counts.last.At >= series.last.At &&
			counts.input >= series.input && counts.output >= series.output &&
			(counts.input > series.input || counts.output > series.output) {
			series = counts
		}
	}
	stats, detail := series.result(size)
	return stats, detail, nil
}

// read updates the shared file cursor with the state mutex held by the caller.
func (s *analyticsState) read(provider, path string, config M) (int64, error) {
	f, err := os.Open(path)
	if err != nil {
		return 0, err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return 0, err
	}
	if s.file != nil && (!os.SameFile(s.file, info) || info.Size() < s.offset || info.Size() == s.offset && !info.ModTime().Equal(s.file.ModTime())) {
		s.resetTranscript()
	}
	s.file = info
	if info.Size() == s.offset {
		return s.offset, nil
	}
	if _, err = f.Seek(s.offset, 0); err != nil {
		return 0, err
	}
	return s.readUntil(provider, f, info.Size(), config)
}

// Parse whole records directly out of a reusable buffer. Only a line that
// crosses a buffer or append boundary needs an owned copy.
func (s *analyticsState) readUntil(provider string, f io.Reader, end int64, config M) (int64, error) {
	reader := bufio.NewReaderSize(io.LimitReader(f, end-s.offset), 64<<10)
	pending := s.rest
	for s.offset < end {
		line, err := reader.ReadSlice('\n')
		s.offset += int64(len(line))
		if err == nil {
			line = line[:len(line)-1]
		}
		if len(pending)+len(line) > 32<<20 {
			s.resetTranscript()
			return 0, errors.New("Transcript line exceeds 32 MB")
		}
		if len(pending) != 0 || err != nil {
			pending = append(pending, line...)
			line = pending
		}
		if err == nil {
			s.line(provider, line, config)
			pending = pending[:0]
		} else if err != bufio.ErrBufferFull && (err != io.EOF || s.offset < end) {
			// The file can be rewritten while we scan it. EOF before the
			// stat size must terminate this read even when no bytes arrived.
			s.resetTranscript()
			if err == io.EOF {
				err = io.ErrUnexpectedEOF
			}
			return 0, err
		}
	}
	// Keep only the unfinished suffix between polls, not a multi-MiB buffer
	// from an earlier compaction record.
	s.rest = bytes.Clone(pending)
	return s.offset, nil
}

func (s *analyticsState) resetTranscript() {
	s.offset, s.rest, s.file, s.model, s.tier = 0, nil, nil, "", ""
	s.analyticsSeries, s.codexCounts = analyticsSeries{}, analyticsSeries{}
	s.codexTotal, s.codexContext = analyticsUsage{}, codexContextWeights{}
	s.agyContext, s.claudePlan = agyContextWeights{}, claudePlanState{}
	s.used, s.window = 0, 0
}

func (s *analyticsSeries) result(size int64) (M, M) {
	cache := s.cacheBeforeRecent
	recent := []M{}
	for _, u := range s.requests {
		gap := cache.observe(s.provider, u)
		recent = append(recent, M{"at": u.At, "model": u.Model, "read": u.Read, "write5m": u.Write5, "write1h": u.Write1, "uncached": u.Fresh, "output": u.Output, "gapMs": gap})
	}
	sort.SliceStable(recent, func(i, j int) bool { return N(recent[i], "at") > N(recent[j], "at") })
	var summary any
	if s.count > 0 {
		lastInput := s.last.Fresh + s.last.Read + s.last.Write5 + s.last.Write1
		hit := float64(0)
		if lastInput > 0 {
			hit = s.last.Read / lastInput
		}
		summary = M{"lastAt": s.last.At, "ttlMs": cache.ttl, "lastHit": hit}
	}
	var cost any
	if s.pricedRequests > 0 {
		cost = s.cost
	}
	stats := M{"costUsd": cost, "partial": s.pricedRequests < s.count || s.missingCost, "bytes": size, "cache": summary}
	if s.count > 0 {
		stats["tokens"] = M{"input": s.input, "cached": s.cached, "output": s.output}
	}
	hit := float64(0)
	if s.input > 0 {
		hit = s.cached / s.input
	}
	detail := M{"summary": summary, "totals": M{"read": s.cached, "write5m": s.write5, "write1h": s.write1, "uncached": max(0, s.input-s.cached-s.write5-s.write1), "requests": s.count, "hitRate": hit}, "expiredCount": cache.expired, "recent": recent}
	if len(s.heatmap.Cells) > 0 {
		detail["heatmap"] = cacheHeatmap{
			RequestsPerCell: s.heatmap.RequestsPerCell,
			Cells:           append([]cacheHeatmapCell(nil), s.heatmap.Cells...),
		}
	}
	detail["savedKnown"], detail["savedPartial"] = s.savingsSamples > 0, s.savingsSamples < s.count || s.missingSavings
	stats["savedKnown"], stats["savedPartial"] = detail["savedKnown"], detail["savedPartial"]
	if s.savingsSamples > 0 {
		detail["savedUsd"], stats["savedUsd"] = s.saved, s.saved
	}
	return stats, detail
}
func (a *App) analytics(rec *Record) (M, M, error) {
	p, id := S(rec.Info, "provider"), S(rec.Info, "externalId")
	path := analyticsPath(p, id)
	if path == "" {
		return nil, nil, os.ErrNotExist
	}
	stats, detail, err := analyticsRead(p, path, a.Config.Raw)
	if err != nil {
		return nil, nil, err
	}
	if p == "claude" {
		dir := filepath.Join(strings.TrimSuffix(path, ".jsonl"), "subagents")
		children, _ := os.ReadDir(dir)
		for _, child := range children {
			if !strings.HasSuffix(child.Name(), ".jsonl") {
				continue
			}
			childStats, _, e := analyticsRead(p, filepath.Join(dir, child.Name()), a.Config.Raw)
			if e != nil {
				continue
			}
			stats["bytes"] = N(stats, "bytes") + N(childStats, "bytes")
			if childStats["costUsd"] != nil {
				stats["costUsd"] = N(stats, "costUsd") + N(childStats, "costUsd")
			}
			stats["partial"] = B(stats, "partial") || B(childStats, "partial")
			t := providerMap(stats["tokens"])
			c := providerMap(childStats["tokens"])
			if len(c) == 0 {
				continue
			}
			for _, k := range []string{"input", "cached", "output"} {
				t[k] = N(t, k) + N(c, k)
			}
			stats["tokens"] = t
		}
	}
	return stats, detail, nil
}
func (a *App) refreshAnalytics(rec *Record) {
	if a.readSessionAnalytics(rec) {
		a.Store.noteSessionDetails(S(rec.Info, "id"), detailPersisted)
	}
}

func (a *App) readSessionAnalytics(rec *Record) bool {
	id := S(rec.Info, "id")
	if _, refreshing := a.analyticsBusy.LoadOrStore(id, struct{}{}); refreshing {
		return false
	}
	defer a.analyticsBusy.Delete(id)
	stats, detail, err := a.analytics(rec)
	if err == nil {
		changed := a.Store.applyAnalyticsStats(rec, stats)
		if S(rec.Info, "provider") == "antigravity" && agyModelUnset(S(rec.Info, "model")) {
			for _, request := range providerRows(detail["recent"]) {
				if model := S(request, "model"); !agyModelUnset(model) {
					a.Store.fillAgyConfig(rec, M{"model": model})
					break
				}
			}
		}
		return changed
	}
	return false
}

func (a *App) refreshClaudePlan(rec *Record) {
	path := analyticsPath("claude", S(rec.Info, "externalId"))
	if path == "" {
		return
	}
	state := analyticsStateFor(path)
	state.mu.Lock()
	defer state.mu.Unlock()
	if _, err := state.read("claude", path, a.Config.Raw); err != nil || !state.claudePlan.touched {
		return
	}
	plan := state.claudePlan.snapshot()
	if a.Store.applyClaudePlan(rec, plan) {
		rec.Info["plan"] = plan
	}
}

func (s *Store) applyClaudePlan(source *Record, plan M) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	id := S(source.Info, "id")
	current := s.records[id]
	if s.closed || current == nil || S(current.Info, "provider") != "claude" ||
		S(current.Info, "externalId") != S(source.Info, "externalId") || S(current.Info, "resumeAt") != S(source.Info, "resumeAt") {
		return false
	}
	old, _ := json.Marshal(current.Info["plan"])
	next, _ := json.Marshal(plan)
	if string(old) != string(next) {
		current.Info["plan"] = clone(plan)
		s.dirty[id] = true
		s.emitLocked(M{"type": "session", "session": current.Info})
	}
	return true
}

func (s *Store) applyAnalyticsStats(source *Record, stats M) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	id := S(source.Info, "id")
	current := s.records[id]
	provider := S(source.Info, "provider")
	if s.closed || current == nil || S(current.Info, "provider") != provider || S(current.Info, "externalId") != S(source.Info, "externalId") {
		return false
	}
	previous := providerMap(current.Info["stats"])
	tokens, prior := providerMap(stats["tokens"]), providerMap(previous["tokens"])
	if stats["cache"] == nil && len(tokens) == 0 && len(previous) > 0 {
		// No usage samples in this read. Keep the last cache and totals snapshot.
		bytes := stats["bytes"]
		stats = clone(previous)
		stats["bytes"] = bytes
	}
	if provider == "antigravity" && S(previous, "costSource") == "provider" {
		// ACP reports the cumulative session cost independently of planner token records.
		stats["costUsd"], stats["costSource"], stats["partial"] = previous["costUsd"], "provider", false
	} else if provider == "claude" && S(previous, "costSource") == "provider" && len(tokens) > 0 {
		matches := N(tokens, "input") == N(prior, "input") && N(tokens, "cached") == N(prior, "cached") && N(tokens, "output") == N(prior, "output")
		if matches && !B(previous, "partial") || stats["costUsd"] == nil {
			stats["costUsd"], stats["costSource"], stats["partial"] = previous["costUsd"], "provider", !matches || B(previous, "partial")
		}
	}
	old, _ := json.Marshal(previous)
	next, _ := json.Marshal(stats)
	if string(old) != string(next) {
		current.Info["stats"] = clone(stats)
		s.dirty[id] = true
		s.emitLocked(M{"type": "session", "session": current.Info})
		return N(tokens, "input") != N(prior, "input") || N(tokens, "cached") != N(prior, "cached") ||
			N(tokens, "output") != N(prior, "output") || N(providerMap(stats["cache"]), "lastAt") != N(providerMap(previous["cache"]), "lastAt")
	}
	return false
}
