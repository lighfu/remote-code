package host

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"
)

type nativeSession struct {
	mu                            sync.Mutex
	agyPromptMu                   sync.Mutex
	agyCommands                   []M
	claudeIdleTimer               *time.Timer
	wire                          *providerWire
	external, turn, text, thought string
	codexTurnRevision             uint64
	codexModeDirty                bool
	blocks                        map[int]string
	parents                       map[string]string
	remote                        bool
}
type nativePending struct {
	sid, entry, method, tool string
	rawID                    any
	wire                     *providerWire
	input, request           M
}
type nativeProvider struct {
	name, data       string
	store            *Store
	mu               sync.Mutex
	startMu          sync.Mutex
	initializeMu     sync.Mutex
	sessions         map[string]*nativeSession
	byExternal       map[string]string
	pending          map[string]nativePending
	rpc              *providerWire
	models           []M
	modelsMu         sync.Mutex
	modelsUntil      time.Time
	modelsRefreshing bool
	commands         []M
	claudeCommands   map[string]claudeCommandSnapshot
	claudePC         claudePCState
	usage            M
	usageUntil       time.Time
	usageObserved    func(M)
	closed           bool
	desktop          *providerDesktop
	usageMu          sync.Mutex
	historyMu        sync.Mutex
	history          []M
	historyUntil     time.Time
	transcriptMu     sync.Mutex
	transcriptSeen   map[string]map[string]bool
	transcriptSizes  map[string]int64
	agyLogs          *agyLogHub
	followers        map[string]context.CancelFunc
	sides            map[string]*providerSide
}

func NewProvider(name string, store *Store, dataDir string) Provider {
	return &nativeProvider{name: name, data: dataDir, store: store, sessions: map[string]*nativeSession{}, byExternal: map[string]string{}, pending: map[string]nativePending{}, models: []M{}, commands: []M{}}
}
func (p *nativeProvider) Available() bool {
	_, err := FindProviderExecutable(p.name)
	return err == nil
}
func (p *nativeProvider) Close() error {
	p.startMu.Lock()
	p.initializeMu.Lock()
	p.mu.Lock()
	p.closed = true
	wires := map[*providerWire]bool{}
	if p.rpc != nil {
		wires[p.rpc] = true
	}
	for _, r := range p.sessions {
		p.clearClaudeIdle(r)
		if r.Wire() != nil {
			wires[r.Wire()] = true
		}
	}
	for _, stop := range p.followers {
		stop()
	}
	p.mu.Unlock()
	p.initializeMu.Unlock()
	p.startMu.Unlock()
	p.closeDesktop()
	p.closeAgyLogs()
	for w := range wires {
		_ = w.close()
	}
	return nil
}
func (p *nativeProvider) Do(ctx context.Context, action string, rec *Record, body M) (any, error) {
	if body == nil {
		body = M{}
	}
	switch action {
	case "modes":
		return providerModes(p.name), nil
	case "manageRequest":
		w, err := p.ensureRPC(ctx)
		if err != nil {
			return nil, err
		}
		return w.request(ctx, S(body, "method"), providerMap(body["params"]))
	case "listModels":
		return p.listModels(ctx)
	case "listCommands":
		return p.listCommands(ctx, rec)
	case "usage":
		return p.readUsage(ctx, B(body, "force"))
	case "listHistory":
		return p.listHistory(ctx)
	}
	if rec == nil {
		return nil, errors.New("Conversation not found")
	}
	sid := S(rec.Info, "id")
	if p.name == "codex" && action == "respond" && strings.HasPrefix(S(body, "requestId"), "async:") {
		return nil, p.respondCodexInlineQuestion(ctx, rec, body)
	}
	if p.name == "codex" && S(rec.Info, "externalId") != "" && (B(rec.Info, "desktop") || p.session(sid) == nil) && action != "contextDetail" && action != "cacheDetail" && action != "rewind" && action != "sideQuestion" {
		attached, err := p.tryDesktop(ctx, rec)
		if err != nil {
			return nil, err
		}
		if attached {
			return p.desktopDo(ctx, action, rec, body)
		}
	}
	switch action {
	case "send":
		return nil, p.send(ctx, rec, body)
	case "respond":
		return nil, p.respond(rec, body)
	case "importHistory":
		return p.importHistory(ctx, rec)
	case "rejoin":
		if p.name == "codex" {
			return p.rejoinCodex(ctx, rec)
		}
		return p.importHistory(ctx, rec)
	case "takeover":
		if B(rec.Info, "desktop") {
			return nil, errors.New("Desktop owns this conversation; close it in Desktop before taking over")
		}
		if p.name == "claude" {
			if err := p.stopClaudePCBackground(ctx, rec); err != nil {
				return nil, err
			}
			if err := p.detach(ctx, rec); err != nil {
				return nil, err
			}
			rec.Info["_takeover"] = true
			p.store.Update(sid, M{"watching": false}, false)
			p.store.Add(sid, M{"kind": "system", "level": "info", "text": "Took over this conversation. Please do not send from the PC session at the same time."})
		}
		_, err := p.ensure(ctx, rec)
		return nil, err
	case "detach":
		return nil, p.detach(ctx, rec)
	case "interrupt":
		if p.name == "claude" {
			r, err := p.claudeWatchRuntime(ctx, rec)
			if err != nil || r == nil {
				return nil, err
			}
			p.cancelPending(sid)
			_, err = r.Wire().request(ctx, "interrupt", M{})
			return nil, err
		}
		r := p.session(sid)
		if r == nil {
			return nil, nil
		}
		if p.name == "antigravity" {
			return nil, r.Wire().write(M{"jsonrpc": "2.0", "method": "session/cancel", "params": M{"sessionId": r.External()}})
		}
		if turn := r.Turn(); turn != "" {
			p.cancelPending(sid)
			_, err := r.Wire().request(ctx, "turn/interrupt", M{"threadId": r.External(), "turnId": turn})
			return nil, err
		}
		return nil, nil
	case "runCommand":
		if p.name == "codex" {
			return nil, p.runCodexCommand(ctx, rec, S(body, "name"), S(body, "args"))
		}
		body["text"] = "/" + S(body, "name")
		if a := S(body, "args"); a != "" {
			body["text"] = S(body, "text") + " " + a
		}
		return nil, p.send(ctx, rec, body)
	case "compact":
		body["name"] = "compact"
		body["args"] = S(body, "instructions")
		return p.Do(ctx, "runCommand", rec, body)
	case "contextDetail":
		return p.contextDetail(ctx, rec)
	case "cacheDetail":
		return p.cacheDetail(rec), nil
	case "sideQuestion":
		if p.name == "codex" {
			return p.codexSideQuestion(ctx, rec, body)
		}
		if p.name == "antigravity" {
			return nil, errors.New("Antigravity does not support quick questions")
		}
		r, err := p.ensure(ctx, rec)
		if err != nil {
			return nil, err
		}
		return r.Wire().request(ctx, "side_question", M{"question": S(body, "question"), "history": body["history"]})
	case "rewind":
		return p.rewind(ctx, rec, body)
	case "setModelAndFastMode":
		if err := p.configure(ctx, rec, "setModel", body); err != nil {
			return nil, err
		}
		return p.Do(ctx, "setFastMode", p.store.Get(sid), body)
	case "setMode", "setThinking", "setModel", "setFastMode", "setCompactWindow", "setUltra":
		return nil, p.configure(ctx, rec, action, body)
	default:
		return nil, fmt.Errorf("Unknown provider action: %s", action)
	}
}
func (p *nativeProvider) session(sid string) *nativeSession {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.sessions[sid]
}
func (p *nativeProvider) cacheFile(kind string) string {
	return filepath.Join(p.data, kind+"-"+p.name+".json")
}
func (p *nativeProvider) saveCache(kind string, rows []M) {
	record := M{"at": providerNow(), "list": rows}
	if p.name == "codex" && kind == "models" {
		record["schema"] = codexModelsCacheSchema
	}
	b, _ := json.Marshal(record)
	_ = os.WriteFile(p.cacheFile(kind), b, 0600)
}
func (p *nativeProvider) loadCache(kind string) []M {
	b, e := os.ReadFile(p.cacheFile(kind))
	if e != nil {
		return nil
	}
	var v M
	if json.Unmarshal(b, &v) != nil {
		return nil
	}
	if p.name == "codex" && kind == "models" && N(v, "schema") != codexModelsCacheSchema {
		return nil
	}
	return providerRows(v["list"])
}
func (p *nativeProvider) failed(sid string, err error) {
	p.store.Add(sid, M{"kind": "system", "level": "error", "text": err.Error()})
	p.store.Update(sid, M{"status": "error"}, true)
}
func (p *nativeProvider) settle(sid string, ok bool, text string) {
	rec := p.store.Get(sid)
	if rec == nil {
		return
	}
	for _, e := range rec.Entries {
		if B(e, "streaming") {
			p.store.Patch(sid, S(e, "id"), M{"streaming": false})
		}
		if S(e, "kind") == "tool" && S(e, "status") == "running" && S(e, "tool") != "Agent" {
			if p.name == "antigravity" && p.agyBackgroundEntry(sid, S(e, "id")) {
				continue
			}
			status := "done"
			if !ok {
				status = "error"
			}
			p.store.Patch(sid, S(e, "id"), M{"status": status})
		}
	}
	p.store.Add(sid, M{"kind": "result", "ok": ok, "text": text})
	status := "idle"
	if p.name == "antigravity" && N(rec.Info, "pending") > 0 {
		status = "waiting"
	}
	p.store.Update(sid, M{"status": status}, true)
}
func (p *nativeProvider) send(ctx context.Context, rec *Record, body M) error {
	sid := S(rec.Info, "id")
	r, err := p.ensure(ctx, rec)
	if err != nil {
		return err
	}
	if p.name == "codex" && B(body, "now") {
		if err := waitCodexIdle(ctx, func() (bool, error) { return r.Turn() == "", nil }); err != nil {
			return err
		}
	}
	text := S(body, "text")
	images := providerRows(body["images"])
	imageIDs := make([]string, len(images))
	var media []string
	for n, image := range images {
		id := MediaFromBase64(p.data, S(image, "data"), S(image, "mime"))
		if id == "" && p.name == "codex" {
			return errors.New("Failed to save image attachment")
		}
		imageIDs[n] = id
		if id != "" && !slices.Contains(media, id) {
			media = append(media, id)
		}
	}
	var turn string
	var turnRevision uint64
	if p.name == "codex" {
		turn, turnRevision = r.codexTurn()
		if turn != "" && B(body, "now") {
			return errors.New("A new Codex turn started before the message could be sent")
		}
	}
	userID := NewID()
	entry := M{"id": userID, "kind": "user", "text": text}
	if len(images) > 0 {
		entry["images"] = len(images)
	}
	if len(media) > 0 {
		entry["media"] = media
	}
	if p.name == "antigravity" {
		p.dismissAgyQuestions(sid)
	}
	p.store.Add(sid, entry)
	p.store.Update(sid, M{"status": "running"}, true)
	if p.name == "claude" {
		content := []any{}
		if text != "" {
			content = append(content, M{"type": "text", "text": text})
		}
		for _, i := range images {
			content = append(content, M{"type": "image", "source": M{"type": "base64", "media_type": S(i, "mime"), "data": S(i, "data")}})
		}
		message := M{"type": "user", "session_id": r.External(), "origin": M{"kind": "human"}, "parent_tool_use_id": nil, "uuid": userID, "message": M{"role": "user", "content": content}}
		if B(body, "now") {
			message["priority"] = "now"
		}
		return r.Wire().write(message)
	}
	input := []any{}
	if text != "" {
		input = append(input, M{"type": "text", "text": text, "text_elements": []any{}})
	}
	for n, i := range images {
		if p.name == "antigravity" {
			input = append(input, M{"type": "image", "mimeType": S(i, "mime"), "data": S(i, "data")})
			continue
		}
		path, e := attachmentPath(imageIDs[n])
		if e != nil {
			return e
		}
		input = append(input, M{"type": "localImage", "path": path})
	}
	if p.name == "antigravity" {
		p.sendAgyPrompt(sid, r, input)
		return nil
	}
	params := M{"threadId": r.External(), "input": input, "clientUserMessageId": userID}
	method := "turn/start"
	modeOverride := false
	if turn != "" {
		method = "turn/steer"
		params["expectedTurnId"] = turn
	} else {
		r.mu.Lock()
		modeOverride, r.codexModeDirty = r.codexModeDirty, false
		r.mu.Unlock()
		if modeOverride {
			if current := p.store.Get(sid); current != nil {
				providerMerge(params, providerTurnMode(S(current.Info, "mode")))
			}
		}
		params["summary"] = providerSummary(S(rec.Info, "thinking"))
		if s := S(rec.Info, "model"); s != "" {
			params["model"] = s
		}
		if s := S(rec.Info, "effort"); s != "" {
			params["effort"] = s
		}
	}
	res, err := r.Wire().request(ctx, method, params)
	if err != nil {
		if modeOverride {
			r.mu.Lock()
			r.codexModeDirty = true
			r.mu.Unlock()
		}
		p.failed(sid, err)
		return err
	}
	if method == "turn/start" {
		r.setCodexTurnFromReply(turnRevision, S(providerMap(res["turn"]), "id"))
	}
	return nil
}
func providerMerge(a, b M) {
	for k, v := range b {
		a[k] = v
	}
}
func providerSummary(mode string) string {
	if mode == "off" {
		return "none"
	}
	if mode == "summary" {
		return "concise"
	}
	return "detailed"
}
func providerThreadMode(mode string) M {
	switch mode {
	case "read-only":
		return M{"approvalPolicy": "on-request", "sandbox": "read-only"}
	case "auto":
		return M{"approvalPolicy": "on-request", "sandbox": "workspace-write"}
	case "untrusted":
		return M{"approvalPolicy": "untrusted", "sandbox": "workspace-write"}
	case "full":
		return M{"approvalPolicy": "never", "sandbox": "danger-full-access"}
	}
	return M{}
}
func providerTurnMode(mode string) M {
	m := providerThreadMode(mode)
	sandbox := S(m, "sandbox")
	delete(m, "sandbox")
	switch sandbox {
	case "read-only":
		m["sandboxPolicy"] = M{"type": "readOnly", "networkAccess": false}
	case "workspace-write":
		m["sandboxPolicy"] = M{"type": "workspaceWrite", "writableRoots": []string{}, "networkAccess": false, "excludeTmpdirEnvVar": false, "excludeSlashTmp": false}
	case "danger-full-access":
		m["sandboxPolicy"] = M{"type": "dangerFullAccess"}
	}
	return m
}
func (p *nativeProvider) detach(ctx context.Context, rec *Record) error {
	sid := S(rec.Info, "id")
	p.stopTranscriptFollower(sid)
	p.stopAgyLogs(sid)
	p.cancelPending(sid)
	p.mu.Lock()
	r := p.sessions[sid]
	delete(p.sessions, sid)
	if r != nil {
		for external, owner := range p.byExternal {
			if owner == sid {
				delete(p.byExternal, external)
			}
		}
	}
	p.clearDisconnectedLocked(sid, true, "")
	p.mu.Unlock()
	if r != nil {
		p.clearClaudeIdle(r)
	}
	if r != nil && r.Wire() != nil {
		switch p.name {
		case "claude":
			_ = r.Wire().close()
		case "codex":
			_, _ = r.Wire().request(ctx, "thread/unsubscribe", M{"threadId": r.External()})
		case "antigravity":
			_, _ = r.Wire().request(ctx, "session/close", M{"sessionId": r.External()})
		}
	}
	return nil
}
func (p *nativeProvider) configure(ctx context.Context, rec *Record, action string, body M) error {
	sid := S(rec.Info, "id")
	if p.name == "codex" && action == "setFastMode" {
		return p.configureCodexFastMode(ctx, rec, B(body, "on") || B(body, "fastMode"))
	}
	patch := M{}
	request := M{}
	method := ""
	switch action {
	case "setMode":
		patch["mode"] = S(body, "mode")
		method = "set_permission_mode"
		request["mode"] = patch["mode"]
	case "setThinking":
		patch["thinking"] = S(body, "thinking")
		method = "set_max_thinking_tokens"
		request["max_thinking_tokens"] = nil
		request["thinking_display"] = "summarized"
		if S(body, "thinking") == "off" {
			request["thinking_display"] = "omitted"
		}
	case "setModel":
		patch["model"] = body["model"]
		patch["effort"] = body["effort"]
		method = "set_model"
		request["model"] = body["model"]
	case "setFastMode":
		on := B(body, "on") || B(body, "fastMode")
		patch["fastMode"] = on
		method = "apply_flag_settings"
		request["settings"] = M{"fastMode": on}
	case "setCompactWindow":
		patch["compactWindow"] = body["tokens"]
		method = "apply_flag_settings"
		request["settings"] = M{"autoCompactWindow": body["tokens"]}
	case "setUltra":
		patch["ultra"] = B(body, "on") || B(body, "ultra")
		method = "apply_flag_settings"
		request["settings"] = M{"ultracode": patch["ultra"]}
	}
	r := p.session(sid)
	if p.name == "claude" && r == nil && B(rec.Info, "watching") && action != "setThinking" {
		var err error
		r, err = p.claudeWatchRuntime(ctx, rec)
		if err != nil {
			return err
		}
	}
	if r != nil {
		if p.name == "claude" {
			if action == "setCompactWindow" && r.Remote() {
				if err := p.sendClaudeAutoCompact(r, body["tokens"]); err != nil {
					return err
				}
			} else {
				if _, err := r.Wire().request(ctx, method, request); err != nil {
					return err
				}
			}
			if action == "setModel" && S(body, "effort") != "" {
				if _, err := r.Wire().request(ctx, "apply_flag_settings", M{"settings": M{"effortLevel": body["effort"]}}); err != nil {
					return err
				}
			}
		} else if p.name == "antigravity" {
			switch action {
			case "setMode":
				_, err := r.Wire().request(ctx, "session/set_mode", M{"sessionId": r.External(), "modeId": body["mode"]})
				if err != nil {
					return err
				}
			case "setModel":
				for _, key := range []string{"model", "effort"} {
					if val := S(body, key); val != "" {
						configID := key
						if key == "effort" {
							configID = "thought_level"
						}
						_, err := r.Wire().request(ctx, "session/set_config_option", M{"sessionId": r.External(), "configId": configID, "value": val})
						if err != nil {
							return err
						}
					}
				}
			case "setThinking":
			default:
				return errors.New("This setting is not supported by Antigravity")
			}
		}
	}
	if p.name == "antigravity" && (action == "setFastMode" || action == "setUltra" || action == "setCompactWindow") {
		return errors.New("This setting is not supported by Antigravity")
	}
	p.store.Update(sid, patch, false)
	if p.name == "codex" && action == "setMode" && r != nil {
		r.mu.Lock()
		r.codexModeDirty = true
		r.mu.Unlock()
	}
	return nil
}
func (p *nativeProvider) contextDetail(ctx context.Context, rec *Record) (any, error) {
	if p.name == "claude" {
		return p.claudeContextDetail(ctx, rec)
	}
	c := providerMap(rec.Info["context"])
	max := N(c, "max")
	used := N(c, "used")
	if max <= 0 {
		return nil, errors.New("Context size is not known yet")
	}
	return M{"model": rec.Info["model"], "totalTokens": used, "maxTokens": max, "percentage": 100 * used / max, "isAutoCompactEnabled": false, "estimated": true, "categories": []M{{"id": "messages", "name": "Conversation", "tokens": used, "kind": "estimated", "color": ""}, {"id": "free_space", "name": "Available", "tokens": max - used, "kind": "free", "color": ""}}, "mcpServers": []M{}, "memoryFiles": []M{}, "skills": []M{}, "agents": []M{}, "messages": nil}, nil
}

func (r *nativeSession) claudeContextUsage(ctx context.Context) (any, error) {
	return r.Wire().claudeContextUsage(ctx)
}

func (w *providerWire) claudeContextUsage(ctx context.Context) (M, error) {
	if w == nil {
		return nil, errors.New("Claude connection is unavailable")
	}
	return w.request(ctx, "get_context_usage", M{"detail": "full"})
}

func (p *nativeProvider) cacheDetail(rec *Record) M {
	stats := providerMap(rec.Info["stats"])
	cache := providerMap(stats["cache"])
	var summary any
	lastAt, ttl, hit := N(cache, "lastAt"), N(cache, "ttlMs"), N(cache, "lastHit")
	if lastAt > 0 && ttl > 0 && cache["lastHit"] != nil && hit >= 0 && hit <= 1 {
		summary = M{"lastAt": int64(lastAt), "ttlMs": int64(ttl), "lastHit": hit}
	}
	tokens := providerMap(stats["tokens"])
	input := max(int64(N(tokens, "input")), 0)
	read := min(max(int64(N(tokens, "cached")), 0), input)
	rate := 0.0
	if input > 0 {
		rate = float64(read) / float64(input)
	}
	return M{
		"summary":      summary,
		"totals":       M{"read": read, "uncached": input - read, "hitRate": rate},
		"savedUsd":     0,
		"expiredCount": 0,
		"recent":       []M{},
	}
}
func (p *nativeProvider) rewind(ctx context.Context, rec *Record, body M) (any, error) {
	if p.name != "claude" {
		return nil, errors.New("This provider does not support rewind")
	}
	if B(rec.Info, "watching") {
		return nil, errors.New("Cannot rewind a session active on PC. Take over the session first")
	}
	if S(rec.Info, "status") == "running" || S(rec.Info, "status") == "waiting" {
		return nil, errors.New("Cannot rewind while Claude is running. Please stop it and try again")
	}
	if S(rec.Info, "externalId") == "" {
		return nil, errors.New("Conversation has not started yet")
	}
	entryID := S(body, "entryId")
	index := -1
	for i, e := range rec.Entries {
		if S(e, "id") == entryID && S(e, "kind") == "user" {
			index = i
			break
		}
	}
	if index < 0 {
		return nil, errors.New("Message to rewind was not found")
	}
	prompt, err := p.resolveClaudeRewind(rec, index)
	if err != nil {
		return nil, err
	}
	result := M{"text": S(rec.Entries[index], "text")}
	if B(body, "files") {
		r, err := p.ensure(ctx, rec)
		if err != nil {
			return nil, err
		}
		preview, previewErr := r.Wire().request(ctx, "rewind_files", M{"user_message_id": prompt.UUID, "dry_run": true})
		applied, applyErr := r.Wire().request(ctx, "rewind_files", M{"user_message_id": prompt.UUID, "dry_run": false})
		if applyErr != nil {
			result["filesError"] = applyErr.Error()
		} else if allowed, ok := applied["canRewind"].(bool); ok && !allowed {
			message := S(applied, "error")
			if message == "" {
				message = "Failed to revert files"
			}
			result["filesError"] = message
		} else {
			files := applied["filesChanged"]
			if len(providerAnswerList(files)) == 0 && previewErr == nil {
				files = preview["filesChanged"]
			}
			if files == nil {
				files = []string{}
			}
			result["filesChanged"] = files
		}
	}
	_ = p.detach(ctx, rec)
	patch := M{"resumeAt": prompt.Parent}
	if prompt.Parent == "" {
		patch = M{"externalId": nil, "resumeAt": nil, "context": nil}
	}
	p.store.Update(S(rec.Info, "id"), patch, false)
	p.store.ReplaceEntries(S(rec.Info, "id"), rec.Entries[:index])
	p.store.Add(S(rec.Info, "id"), M{"kind": "system", "level": "info", "text": "Rewound the conversation to before this message"})
	return result, nil
}
