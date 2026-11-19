package host

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strings"
	"time"
)

func (p *nativeProvider) ensure(ctx context.Context, rec *Record) (*nativeSession, error) {
	return p.ensureWithCodexSettings(ctx, rec, false)
}

func (p *nativeProvider) ensureWithCodexSettings(ctx context.Context, rec *Record, preserve bool) (*nativeSession, error) {
	p.startMu.Lock()
	defer p.startMu.Unlock()
	sid := S(rec.Info, "id")
	if r := p.session(sid); r != nil {
		if p.name == "claude" {
			p.clearClaudeIdle(r)
		}
		return r, nil
	}
	p.mu.Lock()
	closed := p.closed
	p.mu.Unlock()
	if closed {
		return nil, errors.New("Provider is shutting down")
	}
	if p.name == "antigravity" {
		// A mode change may have been saved while this session was disconnected.
		if current := p.store.infoRecord(sid); current != nil {
			rec = current
		}
	}
	if p.name == "codex" && B(rec.Info, "desktop") {
		return nil, errors.New("This conversation is open in Codex Desktop. It cannot be resumed in another process")
	}
	if p.name == "codex" && desktopThreadID.MatchString(S(rec.Info, "externalId")) {
		owner, err := p.desktopClient().findOwner(ctx, S(rec.Info, "externalId"))
		if err != nil && !errors.Is(err, os.ErrNotExist) && !desktopEndpointMissing(err) {
			return nil, fmt.Errorf("Could not verify Codex Desktop ownership: %w", err)
		}
		if owner != "" {
			p.store.Update(sid, M{"desktop": true}, false)
			return nil, errors.New("This conversation is owned by Codex Desktop. Please reconnect before sending")
		}
	}
	r := &nativeSession{external: S(rec.Info, "externalId"), blocks: map[int]string{}, parents: map[string]string{}}
	if p.name == "claude" {
		if r.External() != "" && !B(rec.Info, "_takeover") {
			active, err := p.claudeActivePC(ctx, r.External())
			if err != nil {
				return nil, err
			}
			if active {
				return p.startClaudeRemote(ctx, rec, r)
			}
		}
		p.mu.Lock()
		if stop := p.followers[sid]; stop != nil {
			stop()
			delete(p.followers, sid)
		}
		p.mu.Unlock()
		p.store.Update(sid, M{"watching": false}, false)
		bin, err := FindProviderExecutable(p.name)
		if err != nil {
			return nil, err
		}
		args := p.claudeArgs(rec, false)
		w, err := startProviderWire(bin, args, S(rec.Info, "cwd"), true, func(m M) { p.claudeMessage(sid, r, m) })
		if err != nil {
			p.mu.Lock()
			delete(p.sessions, sid)
			p.mu.Unlock()
			return nil, err
		}
		r.SetWire(w)
		p.mu.Lock()
		if p.closed {
			p.mu.Unlock()
			_ = w.close()
			return nil, errors.New("Provider is shutting down")
		}
		p.sessions[sid] = r
		p.mu.Unlock()
		init, err := w.request(ctx, "initialize", p.claudeInitialize())
		if err != nil {
			p.mu.Lock()
			if p.sessions[sid] == r {
				delete(p.sessions, sid)
			}
			p.mu.Unlock()
			_ = w.close()
			return nil, fmt.Errorf("Claude initialization failed: %w", err)
		}
		p.rememberClaudeInit(S(rec.Info, "cwd"), init)
	} else {
		w, err := p.ensureRPC(ctx)
		if err != nil {
			return nil, err
		}
		r.SetWire(w)
		p.mu.Lock()
		p.sessions[sid] = r
		if r.External() != "" {
			p.byExternal[r.External()] = sid
		}
		p.mu.Unlock()
		var response M
		if p.name == "codex" {
			_, turnRevision := r.codexTurn()
			if r.External() != "" {
				params := M{"threadId": r.External(), "excludeTurns": true, "initialTurnsPage": M{"limit": 1, "sortDirection": "desc", "itemsView": "notLoaded"}}
				if !preserve {
					providerMerge(params, providerThreadMode(S(rec.Info, "mode")))
					if model := S(rec.Info, "model"); model != "" {
						params["model"] = model
					}
				}
				response, err = w.request(ctx, "thread/resume", params)
			} else {
				params := M{"cwd": rec.Info["cwd"]}
				providerMerge(params, providerThreadMode(S(rec.Info, "mode")))
				if model := S(rec.Info, "model"); model != "" {
					params["model"] = model
				}
				if f, ok := rec.Info["fastMode"].(bool); ok {
					params["serviceTier"] = nil
					if f {
						params["serviceTier"] = "priority"
					}
				}
				response, err = w.request(ctx, "thread/start", params)
			}
			if err == nil {
				thread := providerMap(response["thread"])
				r.SetExternal(S(thread, "id"))
				patch := M{}
				if model := S(response, "model"); model != "" {
					patch["model"] = model
				}
				if tier, ok := response["serviceTier"]; ok {
					patch["fastMode"] = tier == "priority"
					patch["serviceTier"] = tier
				}
				if preserve || S(rec.Info, "mode") == "" {
					if mode := codexModeFromSettings(response["approvalPolicy"], response["sandbox"]); mode != "" {
						patch["mode"] = mode
					}
				}
				var turn string
				turn, err = resumedCodexTurn(ctx, w, response)
				if err == nil {
					r.mu.Lock()
					if r.codexTurnRevision == turnRevision {
						r.turn = turn
						state := providerMap(thread["status"])
						if S(state, "type") == "active" || turn != "" {
							patch["status"] = "running"
							if len(array(state["activeFlags"])) > 0 {
								patch["status"] = "waiting"
							}
						}
					}
					if len(patch) > 0 {
						p.store.Update(sid, patch, false)
					}
					r.mu.Unlock()
				}
			}
		} else {
			params := M{"cwd": rec.Info["cwd"], "mcpServers": []any{}}
			method := "session/new"
			if r.External() != "" {
				method = "session/resume"
				params["sessionId"] = r.External()
			}
			response, err = w.request(ctx, method, params)
			if err != nil && method == "session/resume" {
				response, err = w.request(ctx, "session/load", params)
			}
			if err == nil && S(response, "sessionId") != "" {
				r.SetExternal(S(response, "sessionId"))
			}
		}
		if err != nil {
			p.mu.Lock()
			delete(p.sessions, sid)
			if external := S(rec.Info, "externalId"); p.byExternal[external] == sid {
				delete(p.byExternal, external)
			}
			p.mu.Unlock()
			return nil, err
		}
		if r.External() == "" {
			return nil, errors.New("Agent returned no session ID")
		}
		p.mu.Lock()
		p.byExternal[r.External()] = sid
		p.mu.Unlock()
		if p.name == "antigravity" {
			if err := p.initializeAgySession(ctx, rec, r, response); err != nil {
				_ = p.detach(ctx, rec)
				return nil, err
			}
		}
	}
	p.store.Update(sid, M{"live": true, "externalId": r.External()}, false)
	if p.name == "antigravity" {
		p.watchAgyRoot(sid, r.External())
	}
	return r, nil
}
func (p *nativeProvider) ensureRPC(ctx context.Context) (*providerWire, error) {
	p.initializeMu.Lock()
	defer p.initializeMu.Unlock()
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return nil, errors.New("Provider is shutting down")
	}
	if p.closed {
		p.mu.Unlock()
		return nil, errors.New("Provider is shutting down")
	}
	w := p.rpc
	p.mu.Unlock()
	if w != nil {
		select {
		case <-w.done:
		default:
			return w, nil
		}
	}
	bin, err := FindProviderExecutable(p.name)
	if err != nil {
		return nil, err
	}
	args := []string{"app-server"}
	if p.name == "antigravity" {
		args = []string{"acp"}
	}
	ready := make(chan struct{})
	announced := false
	defer func() {
		if !announced {
			close(ready)
		}
	}()
	onMessage := func(m M) {
		<-ready
		p.mu.Lock()
		current := p.rpc == w
		p.mu.Unlock()
		if !current {
			return
		}
		if p.name == "codex" {
			p.codexMessage(m)
		} else {
			p.agyMessage(m)
		}
	}
	if p.name == "codex" {
		config, configErr := LoadConfig(p.data)
		if configErr != nil {
			return nil, configErr
		}
		if config.Raw["codexDaemon"] != false {
			if executable := providerDaemonExecutable(ctx, bin); executable != "" {
				w, err = startProviderProxy(ctx, executable, onMessage)
				if err != nil {
					return nil, err
				}
			}
		}
	}
	if w == nil {
		w, err = startProviderWire(bin, args, "", false, onMessage)
	}
	if err != nil {
		return nil, err
	}
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		_ = w.close()
		return nil, errors.New("Provider is shutting down")
	}
	p.rpc = w
	p.mu.Unlock()
	close(ready)
	announced = true
	params := M{"clientInfo": M{"name": "remote_code", "title": "Remote Code", "version": Version}, "capabilities": M{"experimentalApi": true, "requestAttestation": false}}
	if p.name == "antigravity" {
		params = M{"protocolVersion": 1, "clientCapabilities": M{"fs": M{"readTextFile": false, "writeTextFile": false}, "terminal": false}}
	}
	if _, err = w.request(ctx, "initialize", params); err != nil {
		_ = w.close()
		p.mu.Lock()
		if p.rpc == w {
			p.rpc = nil
		}
		p.mu.Unlock()
		return nil, err
	}
	if p.name == "codex" {
		if err = w.write(M{"method": "initialized"}); err != nil {
			return nil, err
		}
	}
	return w, nil
}
func (p *nativeProvider) claudeArgs(rec *Record, probe bool) []string {
	args := []string{"--print", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--include-partial-messages", "--permission-prompt-tool", "stdio", "--allow-dangerously-skip-permissions", "--setting-sources", "user,project,local"}
	if probe {
		return append(args, "--no-session-persistence")
	}
	if id := S(rec.Info, "externalId"); id != "" {
		args = append(args, "--resume", id)
	}
	if id := S(rec.Info, "resumeAt"); id != "" {
		args = append(args, "--resume-session-at", id)
	}
	if mode := S(rec.Info, "mode"); mode != "" {
		args = append(args, "--permission-mode", mode)
	}
	if model := S(rec.Info, "model"); model != "" {
		args = append(args, "--model", model)
	}
	if effort := S(rec.Info, "effort"); effort != "" {
		args = append(args, "--effort", effort)
	}
	settings := M{}
	if val, ok := rec.Info["fastMode"].(bool); ok {
		settings["fastMode"] = val
	}
	if B(rec.Info, "ultra") {
		settings["ultracode"] = true
	}
	if n := N(rec.Info, "compactWindow"); n > 0 {
		settings["autoCompactWindow"] = n
	}
	if len(settings) > 0 {
		args = append(args, "--settings", providerPretty(settings))
	}
	return args
}
func (p *nativeProvider) claudeInitialize() M {
	return M{"agentProgressSummaries": true, "forwardSubagentText": true, "hooks": M{"PreToolUse": []M{{"matcher": "AskUserQuestion", "hookCallbackIds": []string{"remote-code-question"}, "timeout": 86400}}}}
}

func (p *nativeProvider) claudeContextDetail(ctx context.Context, rec *Record) (M, error) {
	if session := p.session(S(rec.Info, "id")); session != nil {
		if wire := session.Wire(); wire != nil {
			return wire.claudeContextUsage(ctx)
		}
	}
	external := S(rec.Info, "externalId")
	if external == "" {
		return nil, errors.New("Conversation has not started yet")
	}
	if B(rec.Info, "watching") {
		// Use the recorded PC bridge as the source of live context.
		if bridge, err := p.claudeBridgeID(external); err == nil {
			detail, err := claudeRemoteContextDetail(ctx, bridge)
			if err == nil || ctx.Err() != nil {
				return detail, err
			}
		}
	}
	bin, err := FindProviderExecutable("claude")
	if err != nil {
		return nil, err
	}
	// A temporary fork reads the saved context in its own CLI process.
	args := append(p.claudeArgs(rec, false), "--fork-session", "--no-session-persistence")
	wire, err := startProviderWireContext(ctx, bin, args, S(rec.Info, "cwd"), true, func(M) {})
	if err != nil {
		return nil, err
	}
	defer wire.close()
	if _, err := wire.request(ctx, "initialize", M{}); err != nil {
		return nil, fmt.Errorf("Claude context initialization failed: %w", err)
	}
	return wire.claudeContextUsage(ctx)
}

func (p *nativeProvider) rememberClaudeInit(cwd string, init M) {
	models := []M{}
	for _, m := range providerRows(init["models"]) {
		id := S(m, "value")
		if id == "" {
			id = S(m, "id")
		}
		if id == "" {
			continue
		}
		efforts := m["supportedEffortLevels"]
		if efforts == nil {
			efforts = []string{}
		}
		label := S(m, "displayName")
		if label == "" {
			label = id
		}
		models = append(models, M{"id": id, "label": label, "description": S(m, "description"), "efforts": efforts, "isDefault": id == "default", "supportsFastMode": B(m, "supportsFastMode"), "resolvedModel": m["resolvedModel"]})
	}
	commands := []M{}
	for _, m := range providerRows(init["commands"]) {
		commands = append(commands, M{"name": S(m, "name"), "description": S(m, "description"), "argumentHint": S(m, "argumentHint"), "kind": "slash"})
	}
	p.mu.Lock()
	if len(models) > 0 {
		p.models = models
	}
	p.mu.Unlock()
	if len(models) > 0 {
		p.saveCache("models", models)
	}
	if init["commands"] != nil {
		p.saveClaudeCommands(cwd, commands)
	}
}
func (p *nativeProvider) probeClaude(ctx context.Context, cwd string) (M, error) {
	bin, err := FindProviderExecutable("claude")
	if err != nil {
		return nil, err
	}
	if cwd == "" {
		cwd, _ = os.UserHomeDir()
	}
	rec := &Record{Info: M{}}
	w, err := startProviderWire(bin, p.claudeArgs(rec, true), cwd, true, func(M) {})
	if err != nil {
		return nil, err
	}
	defer w.close()
	result, err := w.request(ctx, "initialize", M{})
	if err == nil {
		p.rememberClaudeInit(cwd, result)
	}
	return result, err
}
func (p *nativeProvider) listModels(ctx context.Context) ([]M, error) {
	p.mu.Lock()
	rows := p.models
	valid := time.Now().Before(p.modelsUntil)
	p.mu.Unlock()
	if len(rows) == 0 {
		rows = p.loadCache("models")
	}
	if len(rows) > 0 {
		if !valid {
			p.mu.Lock()
			refresh := !p.modelsRefreshing && !p.closed
			if refresh {
				p.modelsRefreshing = true
			}
			p.mu.Unlock()
			if refresh {
				go func() {
					c, cancel := context.WithTimeout(context.Background(), 30*time.Second)
					defer cancel()
					_, _ = p.refreshModels(c)
					p.mu.Lock()
					p.modelsRefreshing = false
					p.mu.Unlock()
				}()
			}
		}
		return clone(rows), nil
	}
	return p.refreshModels(ctx)
}
func (p *nativeProvider) refreshModels(ctx context.Context) ([]M, error) {
	p.modelsMu.Lock()
	defer p.modelsMu.Unlock()
	p.mu.Lock()
	fresh := time.Now().Before(p.modelsUntil)
	cached := clone(p.models)
	p.mu.Unlock()
	if fresh && len(cached) > 0 {
		return cached, nil
	}
	rows, err := p.queryModels(ctx)
	if err != nil {
		return nil, err
	}
	if len(rows) > 0 {
		p.mu.Lock()
		p.models = rows
		p.modelsUntil = time.Now().Add(30 * time.Minute)
		if p.name == "antigravity" {
			p.modelsUntil = time.Now().Add(12 * time.Hour)
		}
		p.mu.Unlock()
		p.saveCache("models", rows)
	}
	return clone(rows), nil
}
func (p *nativeProvider) queryModels(ctx context.Context) ([]M, error) {
	if p.name == "claude" {
		_, err := p.probeClaude(ctx, "")
		p.mu.Lock()
		defer p.mu.Unlock()
		return p.models, err
	}
	if p.name == "antigravity" {
		return p.agyModels(ctx)
	}
	w, err := p.ensureRPC(ctx)
	if err != nil {
		return nil, err
	}
	result, err := w.request(ctx, "model/list", M{"limit": 100})
	if err != nil {
		return nil, err
	}
	rows := []M{}
	for _, m := range providerRows(result["data"]) {
		efforts := []string{}
		for _, e := range providerRows(m["supportedReasoningEfforts"]) {
			efforts = append(efforts, S(e, "reasoningEffort"))
		}
		id := S(m, "model")
		if id == "" {
			id = S(m, "id")
		}
		if id == "" {
			continue
		}
		fast := false
		for _, tier := range providerRows(m["serviceTiers"]) {
			fast = fast || S(tier, "id") == "priority"
		}
		label := S(m, "displayName")
		if label == "" {
			label = id
		}
		rows = append(rows, M{"id": id, "label": label, "description": S(m, "description"), "efforts": efforts, "isDefault": B(m, "isDefault"), "supportsFastMode": fast})
	}
	p.mu.Lock()
	p.models = rows
	p.mu.Unlock()
	p.saveCache("models", rows)
	return rows, nil
}
func (p *nativeProvider) listCommands(ctx context.Context, rec *Record) ([]M, error) {
	if p.name == "claude" {
		return p.listClaudeCommands(ctx, rec)
	}
	if p.name == "antigravity" {
		return p.listAgyCommands(rec), nil
	}
	w, err := p.ensureRPC(ctx)
	if err != nil {
		return nil, err
	}
	cwds := []string{}
	if rec != nil {
		cwds = append(cwds, S(rec.Info, "cwd"))
	}
	rows := []M{{"name": "compact", "description": "Summarize conversation", "argumentHint": "", "kind": "action"}, {"name": "review", "description": "Review uncommitted changes", "argumentHint": "", "kind": "action"}}
	res, err := w.request(ctx, "skills/list", M{"cwds": cwds})
	if err != nil {
		return rows, nil
	}
	seen := map[string]bool{}
	for _, group := range providerRows(res["data"]) {
		for _, skill := range providerRows(group["skills"]) {
			name := S(skill, "name")
			if enabled, known := skill["enabled"].(bool); known && !enabled || name == "" || seen[name] {
				continue
			}
			seen[name] = true
			description := S(skill, "shortDescription")
			if description == "" {
				description = S(skill, "description")
			}
			rows = append(rows, M{"name": name, "description": description, "argumentHint": "", "kind": "skill", "path": S(skill, "path")})
		}
	}
	return rows, nil
}
func (p *nativeProvider) providerSID(external string) string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.byExternal[external]
}
func (p *nativeProvider) onExit(sid string, r *nativeSession, message string) {
	p.mu.Lock()
	if p.sessions[sid] != r {
		p.mu.Unlock()
		return
	}
	delete(p.sessions, sid)
	for external, owner := range p.byExternal {
		if owner == sid {
			delete(p.byExternal, external)
		}
	}
	p.clearDisconnectedLocked(sid, false, message)
	p.mu.Unlock()
}

// clearDisconnectedLocked finalizes local state before a new connection can attach.
func (p *nativeProvider) clearDisconnectedLocked(sid string, detach bool, reason string) {
	for id, request := range p.pending {
		if request.sid == sid {
			delete(p.pending, id)
		}
	}
	if p.closed || !p.store.markProviderDisconnected(sid, detach) || detach {
		return
	}
	provider := map[string]string{"claude": "Claude", "codex": "Codex", "antigravity": "Antigravity"}[p.name]
	text := provider + " disconnected before the turn finished. It will reconnect on your next message."
	if strings.TrimSpace(reason) != "" {
		text += "\n" + providerTruncate(reason, 500)
	}
	p.store.Add(sid, M{"kind": "system", "level": "error", "text": text})
}

func (p *nativeProvider) cancelPending(sid string) {
	p.mu.Lock()
	ids := []string{}
	for id, a := range p.pending {
		if a.sid == sid {
			ids = append(ids, id)
		}
	}
	p.mu.Unlock()
	for _, id := range ids {
		rec := p.store.Get(sid)
		if rec != nil {
			_ = p.respond(rec, M{"requestId": id, "choice": "__cancel"})
		}
	}
}
func (p *nativeProvider) rpcExit(msg M) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.rpc = nil
	for id := range p.sessions {
		p.clearDisconnectedLocked(id, false, S(msg, "_exit"))
	}
	p.sessions = map[string]*nativeSession{}
	p.byExternal = map[string]string{}
}
func providerStatus(s string) string {
	switch s {
	case "completed", "done", "success", "shutdown":
		return "done"
	case "failed", "error", "errored", "notFound":
		return "error"
	case "cancelled", "interrupted", "declined":
		return "declined"
	}
	return "running"
}
func providerStamp(v any) int64 {
	if s, ok := v.(string); ok {
		if t, err := time.Parse(time.RFC3339Nano, s); err == nil {
			return t.UnixMilli()
		}
	}
	if n, ok := v.(float64); ok {
		if n < 1e12 {
			return int64(n * 1000)
		}
		return int64(n)
	}
	return providerNow()
}
func providerTruncate(s string, n int) string {
	r := []rune(s)
	if len(r) > n {
		return string(r[:n]) + "…"
	}
	return s
}
func providerQuestionRows(input M, preserveID bool) []M {
	rows := []M{}
	for i, q := range providerRows(input["questions"]) {
		id := fmt.Sprint(i)
		if preserveID && S(q, "id") != "" {
			id = S(q, "id")
		}
		opts := []M{}
		switch o := q["options"].(type) {
		case []any:
			for _, v := range o {
				if s, ok := v.(string); ok {
					opts = append(opts, M{"label": s, "description": ""})
				} else {
					m := providerMap(v)
					opts = append(opts, M{"label": S(m, "label"), "description": S(m, "description")})
				}
			}
		}
		rows = append(rows, M{"id": id, "header": S(q, "header"), "question": S(q, "question"), "options": opts, "multiSelect": B(q, "multiSelect") || B(q, "is_multi_select"), "allowOther": true})
	}
	return rows
}
func providerAnswerList(v any) []string {
	switch a := v.(type) {
	case []string:
		return a
	case []any:
		out := []string{}
		for _, v := range a {
			if s, ok := v.(string); ok {
				out = append(out, s)
			}
		}
		return out
	case string:
		return []string{a}
	}
	return []string{}
}
func providerAnswerText(v any) string { return strings.Join(providerAnswerList(v), ", ") }
