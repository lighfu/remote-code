package host

import (
	"context"
	"errors"
	"math"
	"sort"
	"time"
)

func normalizeContext(v any) M {
	c := clone(providerMap(v))
	if len(c) == 0 {
		return c
	}
	if _, ok := c["model"].(string); !ok {
		c["model"] = ""
	}
	for _, key := range []string{"categories", "mcpServers", "memoryFiles", "skills", "agents"} {
		if c[key] == nil {
			c[key] = []M{}
		}
	}
	if _, ok := c["messages"]; !ok {
		c["messages"] = nil
	}
	if c["autoCompactThreshold"] == nil {
		c["autoCompactThreshold"] = nil
	}
	c["isAutoCompactEnabled"] = B(c, "isAutoCompactEnabled")
	servers := map[string]M{}
	for _, tool := range maps(c["mcpTools"]) {
		name := S(tool, "serverName")
		s := servers[name]
		if s == nil {
			s = M{"name": name, "tokens": 0, "tools": 0}
			servers[name] = s
		}
		s["tokens"] = N(s, "tokens") + N(tool, "tokens")
		s["tools"] = N(s, "tools") + 1
	}
	if len(servers) > 0 {
		rows := []M{}
		for _, s := range servers {
			rows = append(rows, s)
		}
		sort.Slice(rows, func(i, j int) bool { return N(rows[i], "tokens") > N(rows[j], "tokens") })
		c["mcpServers"] = rows
	}
	if skills, ok := c["skills"].(map[string]any); ok {
		c["skills"] = maps(skills["skillFrontmatter"])
	}
	for _, agent := range maps(c["agents"]) {
		if S(agent, "name") == "" {
			agent["name"] = S(agent, "agentType")
		}
	}
	if mb, ok := c["messageBreakdown"].(map[string]any); ok {
		tools := []M{}
		for _, t := range maps(mb["toolCallsByType"]) {
			tools = append(tools, M{"name": S(t, "name"), "tokens": N(t, "callTokens") + N(t, "resultTokens")})
		}
		c["messages"] = M{"toolCalls": N(mb, "toolCallTokens"), "toolResults": N(mb, "toolResultTokens"), "attachments": N(mb, "attachmentTokens"), "assistant": N(mb, "assistantMessageTokens"), "user": N(mb, "userMessageTokens"), "byTool": tools}
	}
	return c
}
func (a *App) contextSnapshot(ctx context.Context, rec *Record, cached bool) (M, error) {
	return a.loadContextSnapshot(ctx, rec, cached, func(ctx context.Context) (any, error) {
		switch S(rec.Info, "provider") {
		case "codex":
			return a.codexContextDetail(rec)
		case "antigravity":
			return agyContextDetail(rec, a.Config.Raw)
		default:
			return a.do(ctx, "contextDetail", rec, M{"cached": false})
		}
	})
}

func (a *App) codexContextDetail(rec *Record) (M, error) {
	snapshot, _ := codexContextBreakdown(S(rec.Info, "externalId"), a.Config.Raw)
	used, window := snapshot.used, snapshot.window
	// The socket can report a new measurement before it appears in the rollout.
	if window <= 0 || time.Since(rec.contextReportedAt) < sessionUsageFlushWait {
		current := providerMap(rec.Info["context"])
		used, window = N(current, "used"), N(current, "max")
	}
	if window <= 0 {
		return nil, errors.New("Context size is not known yet")
	}
	model := S(rec.Info, "model")
	if model == "" {
		model = snapshot.model
	}
	categories := snapshot.categories(used)
	if len(categories) == 0 {
		categories = []M{{"id": "messages", "name": "Conversation", "tokens": int64(used), "kind": "estimated", "color": ""}}
	}
	categories = append(categories, M{"id": "free_space", "name": "Available", "tokens": int64(max(0, window-used)), "kind": "free", "color": ""})
	return M{
		"model": model, "totalTokens": int64(used), "maxTokens": int64(window),
		"percentage": 100 * used / window, "estimated": true, "categories": categories,
	}, nil
}

func (a *App) refreshLiveContext(ctx context.Context, rec *Record) {
	if p, ok := a.Providers[S(rec.Info, "provider")].(*nativeProvider); ok && p.name == "claude" {
		// Following a PC transcript marks it live even when no control channel is attached.
		if session := p.session(S(rec.Info, "id")); session != nil {
			_, _ = a.loadContextSnapshot(ctx, rec, false, session.claudeContextUsage)
		}
		return
	}
	_, _ = a.contextSnapshot(ctx, rec, false)
}

type contextCacheKey struct {
	id, provider, external, resume string
}

func contextKey(info M) contextCacheKey {
	return contextCacheKey{S(info, "id"), S(info, "provider"), S(info, "externalId"), S(info, "resumeAt")}
}

type contextCacheValue struct {
	key        contextCacheKey
	at         time.Time
	reportedAt time.Time
	detail     M
}

func (r *Record) noteContextMeasurement() {
	now := time.Now()
	if !now.After(r.contextReportedAt) {
		now = r.contextReportedAt.Add(time.Nanosecond)
	}
	r.contextReportedAt = now
}

type contextFlight struct {
	done       chan struct{}
	detail     M
	err        error
	reportedAt time.Time
}

func (a *App) invalidateSessionContext(key contextCacheKey) {
	a.contextMu.Lock()
	defer a.contextMu.Unlock()
	if a.contexts[key.id].key == key {
		delete(a.contexts, key.id)
	}
	delete(a.contextFlights, key)
}

func (a *App) loadContextSnapshot(ctx context.Context, rec *Record, cached bool, load func(context.Context) (any, error)) (_ M, err error) {
	key := contextKey(rec.Info)
	a.contextMu.Lock()
	entry := a.contexts[key.id]
	at := entry.at
	flight := a.contextFlights[key]
	if flight != nil && rec.contextReportedAt.After(flight.reportedAt) {
		delete(a.contextFlights, key)
		flight = nil
	}
	if entry.key == key && entry.detail != nil && !rec.contextReportedAt.After(entry.reportedAt) && (cached || time.Since(at) < 10*time.Second) {
		a.contextMu.Unlock()
		previous := snapshotMap(entry.detail)
		previous["cachedAt"] = at.UnixMilli()
		if cached && time.Since(at) > 30*time.Second && flight == nil {
			a.startTask(func() { _, _ = a.loadContextSnapshot(a.taskCtx, rec, false, load) })
		}
		return previous, nil
	}
	if flight != nil {
		a.contextMu.Unlock()
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-flight.done:
			return snapshotMap(flight.detail), flight.err
		}
	}
	flight = &contextFlight{done: make(chan struct{}), reportedAt: rec.contextReportedAt}
	a.contextFlights[key] = flight
	a.contextMu.Unlock()
	defer func() {
		a.contextMu.Lock()
		flight.err = err
		if a.contextFlights[key] == flight {
			delete(a.contextFlights, key)
		}
		close(flight.done)
		a.contextMu.Unlock()
	}()
	timeout := 10 * time.Second
	if S(rec.Info, "provider") == "claude" {
		timeout = 30 * time.Second
	}
	check, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	value, err := load(check)
	if err != nil {
		return nil, err
	}
	c := normalizeContext(value)
	entry = contextCacheValue{key: key, at: time.Now(), reportedAt: flight.reportedAt, detail: snapshotMap(c)}
	if !a.applyContextSnapshot(entry, flight) {
		return nil, errors.New("Conversation changed while reading context")
	}
	flight.detail = entry.detail
	return c, nil
}

func (a *App) applyContextSnapshot(entry contextCacheValue, flight *contextFlight) bool {
	s := a.Store
	key, detail := entry.key, entry.detail
	// A save or timeline update may hold the store lock. Other sessions can
	// still read their cached context while we wait to commit this result.
	s.mu.Lock()
	defer s.mu.Unlock()
	rec := s.records[key.id]
	if s.closed || rec == nil || contextKey(rec.Info) != key {
		return false
	}
	a.contextMu.Lock()
	defer a.contextMu.Unlock()
	if a.contextFlights[key] != flight || rec.contextReportedAt.After(flight.reportedAt) {
		// A live event superseded this read. Only its original callers can see it.
		return true
	}
	if max := N(detail, "maxTokens"); max > 0 {
		ctx := M{"used": N(detail, "totalTokens"), "max": max, "percent": math.Round(N(detail, "percentage"))}
		previous := providerMap(rec.Info["context"])
		if len(previous) != len(ctx) || N(previous, "used") != N(ctx, "used") || N(previous, "max") != max || N(previous, "percent") != N(ctx, "percent") {
			rec.Info["context"] = ctx
			s.dirty[key.id] = true
			s.emitLocked(M{"type": "session", "session": rec.Info})
		}
	}
	a.contexts[key.id] = entry
	return true
}
