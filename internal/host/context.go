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
	if window <= 0 {
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
	key    contextCacheKey
	at     time.Time
	detail M
}

func (a *App) loadContextSnapshot(ctx context.Context, rec *Record, cached bool, load func(context.Context) (any, error)) (M, error) {
	key := contextKey(rec.Info)
	a.contextMu.Lock()
	entry := a.contexts[key.id]
	at := entry.at
	flight := a.contextFlights[key]
	if entry.key == key && entry.detail != nil && (cached || time.Since(at) < 10*time.Second) {
		previous := clone(entry.detail)
		a.contextMu.Unlock()
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
		case <-flight:
			a.contextMu.Lock()
			value := a.contexts[key.id]
			a.contextMu.Unlock()
			if value.key != key || value.detail == nil {
				return nil, errors.New("Context could not be read")
			}
			return clone(value.detail), nil
		}
	}
	flight = make(chan struct{})
	a.contextFlights[key] = flight
	a.contextMu.Unlock()
	defer func() { a.contextMu.Lock(); delete(a.contextFlights, key); close(flight); a.contextMu.Unlock() }()
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
	a.contextMu.Lock()
	defer a.contextMu.Unlock()
	if !a.Store.applyContextSnapshot(key, c) {
		return nil, errors.New("Conversation changed while reading context")
	}
	a.contexts[key.id] = contextCacheValue{key: key, at: time.Now(), detail: clone(c)}
	return c, nil
}

func (s *Store) applyContextSnapshot(key contextCacheKey, detail M) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	rec := s.records[key.id]
	if s.closed || rec == nil || contextKey(rec.Info) != key {
		return false
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
	return true
}
