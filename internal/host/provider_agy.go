package host

import (
	"context"
	"fmt"
	"math"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
)

const agyQuestionMethod = "agy/ask_question"

var agyQuestionTitle = regexp.MustCompile(`\bask_question\b`)
var agyWindowsPath = regexp.MustCompile(`^/[A-Za-z]:`)

func agyToolName(kind, title string) string {
	switch kind {
	case "execute":
		return "Bash"
	case "edit", "delete", "move":
		return "Edit"
	case "read":
		return "Read"
	case "search":
		return "Grep"
	case "fetch":
		return "WebFetch"
	case "think":
		return "Think"
	}
	if name, _, _ := strings.Cut(title, " "); name != "" {
		return name
	}
	return kind
}

func agyPath(path string) string {
	if agyWindowsPath.MatchString(path) {
		return path[1:]
	}
	return path
}

func agyDiff(oldText, newText string) string {
	if oldText == newText {
		return ""
	}
	lines := func(text string) []string {
		if text == "" {
			return nil
		}
		return strings.Split(strings.TrimSuffix(strings.ReplaceAll(text, "\r\n", "\n"), "\n"), "\n")
	}
	before, after := lines(oldText), lines(newText)
	start, endBefore, endAfter := 0, len(before), len(after)
	for start < endBefore && start < endAfter && before[start] == after[start] {
		start++
	}
	for endBefore > start && endAfter > start && before[endBefore-1] == after[endAfter-1] {
		endBefore--
		endAfter--
	}
	first := max(0, start-2)
	tail := min(2, len(before)-endBefore)
	oldCount, newCount := endBefore-first+tail, endAfter-first+tail
	oldLine, newLine := first+1, first+1
	if oldCount == 0 {
		oldLine--
	}
	if newCount == 0 {
		newLine--
	}
	diff := []string{fmt.Sprintf("@@ -%d,%d +%d,%d @@", oldLine, oldCount, newLine, newCount)}
	for _, line := range before[first:start] {
		diff = append(diff, " "+line)
	}
	for _, line := range before[start:endBefore] {
		diff = append(diff, "-"+line)
	}
	for _, line := range after[start:endAfter] {
		diff = append(diff, "+"+line)
	}
	for _, line := range before[endBefore : endBefore+tail] {
		diff = append(diff, " "+line)
	}
	if strings.HasSuffix(oldText, "\n") != strings.HasSuffix(newText, "\n") {
		diff = append(diff, `\ newline at EOF changed`)
	}
	return strings.Join(diff, "\n")
}

func (p *nativeProvider) closeAgyStreams(sid string, r *nativeSession) {
	r.mu.Lock()
	text, thought := r.text, r.thought
	r.text, r.thought = "", ""
	r.mu.Unlock()
	for _, id := range []string{text, thought} {
		if id != "" {
			p.store.Patch(sid, id, M{"streaming": false})
		}
	}
}

func (p *nativeProvider) sendAgyPrompt(sid string, r *nativeSession, input []any) {
	go func() {
		r.agyPromptMu.Lock()
		defer r.agyPromptMu.Unlock()
		if p.session(sid) != r {
			return
		}
		p.closeAgyStreams(sid, r)
		p.store.Update(sid, M{"status": "running"}, false)
		ctx, cancel := context.WithTimeout(context.Background(), 24*time.Hour)
		defer cancel()
		res, err := r.Wire().request(ctx, "session/prompt", M{"sessionId": r.External(), "prompt": input})
		if p.session(sid) != r {
			return
		}
		p.closeAgyStreams(sid, r)
		if err != nil {
			p.failed(sid, err)
			return
		}
		switch stop := S(res, "stopReason"); stop {
		case "", "end_turn":
			p.settle(sid, true, "Completed")
		case "cancelled":
			p.settle(sid, false, "Interrupted")
		default:
			p.settle(sid, false, "Exited: "+stop)
		}
	}()
}

func (p *nativeProvider) addAgyQuestion(sid, id string, input M) bool {
	questions := providerQuestionRows(input, false)
	if len(questions) == 0 {
		return false
	}
	for i, row := range providerRows(input["questions"]) {
		options, _ := row["options"].([]any)
		display := providerRows(questions[i]["options"])
		for j, option := range options {
			if S(display[j], "label") == "" {
				display[j]["label"] = S(providerMap(option), "text")
			}
		}
	}
	if p.store.hasEntry(sid, id) {
		return true
	}
	requestID := NewID()
	q := nativePending{sid: sid, entry: id, method: agyQuestionMethod, input: input}
	p.mu.Lock()
	p.pending[requestID] = q
	p.mu.Unlock()
	p.store.Add(sid, M{"id": id, "kind": "question", "requestId": requestID, "questions": questions, "status": "pending"})
	p.refreshPendingState(sid)
	return true
}

func (p *nativeProvider) dismissAgyQuestions(sid string) {
	p.mu.Lock()
	var resolved []string
	for id, q := range p.pending {
		if q.sid == sid && q.method == agyQuestionMethod {
			delete(p.pending, id)
			resolved = append(resolved, q.entry)
		}
	}
	p.mu.Unlock()
	for _, id := range resolved {
		p.store.Patch(sid, id, M{"status": "resolved"})
	}
	if len(resolved) > 0 {
		p.refreshPendingState(sid)
	}
}

func agyQuestionAnswer(input, answers M) string {
	questions := providerQuestionRows(input, false)
	var parts []string
	for _, question := range questions {
		answer := strings.TrimSpace(providerAnswerText(answers[S(question, "id")]))
		if answer == "" {
			continue
		}
		if len(questions) > 1 {
			answer = S(question, "question") + ": " + answer
		}
		parts = append(parts, answer)
	}
	return strings.Join(parts, "\n")
}

func (p *nativeProvider) respondAgyQuestion(q nativePending, cancelled bool, answers M, pending int) error {
	answer := agyQuestionAnswer(q.input, answers)
	status, activity := "cancelled", "idle"
	if !cancelled && answer != "" {
		status, activity = "resolved", "running"
	}
	if pending > 0 {
		activity = "waiting"
	}
	p.store.Patch(q.sid, q.entry, M{"status": status, "answers": answers})
	p.store.Update(q.sid, M{"pending": pending, "status": activity}, false)
	if status != "resolved" {
		return nil
	}
	r := p.session(q.sid)
	if r == nil {
		return fmt.Errorf("Antigravity conversation is no longer connected")
	}
	go func() {
		// The question card can be answered before ACP finishes its current turn.
		r.agyPromptMu.Lock()
		r.agyPromptMu.Unlock()
		if p.session(q.sid) != r {
			return
		}
		if rec := p.store.Get(q.sid); rec != nil {
			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			defer cancel()
			if err := p.send(ctx, rec, M{"text": answer}); err != nil {
				p.failed(q.sid, err)
			}
		}
	}()
	return nil
}

func (p *nativeProvider) agyMessage(msg M) {
	if msg["_exit"] != nil {
		p.rpcExit(msg)
		return
	}
	params := providerMap(msg["params"])
	sid := p.providerSID(S(params, "sessionId"))
	method := S(msg, "method")
	p.mu.Lock()
	w := p.rpc
	p.mu.Unlock()
	if msg["id"] != nil {
		if w == nil {
			return
		}
		if method == "session/request_permission" {
			if sid == "" {
				_ = w.reply(msg["id"], M{"outcome": M{"outcome": "cancelled"}})
				return
			}
			tc := providerMap(params["toolCall"])
			p.addPermission(sid, w, msg["id"], method, params, agyToolName(S(tc, "kind"), S(tc, "title")), providerMap(tc["rawInput"]))
			return
		}
		_ = w.write(M{"jsonrpc": "2.0", "id": msg["id"], "error": M{"code": -32601, "message": "Unsupported request: " + method}})
		return
	}
	if method != "session/update" {
		return
	}
	u := providerMap(params["update"])
	typ := S(u, "sessionUpdate")
	if typ == "available_commands_update" {
		commands := []M{}
		for _, c := range providerRows(u["availableCommands"]) {
			commands = append(commands, M{"name": S(c, "name"), "description": S(c, "description"), "argumentHint": S(providerMap(c["input"]), "hint"), "kind": "slash"})
		}
		p.mu.Lock()
		p.commands = commands
		p.mu.Unlock()
		if r := p.session(sid); r != nil {
			r.mu.Lock()
			r.agyCommands = commands
			r.mu.Unlock()
		}
		p.saveCache("commands", commands)
		return
	}
	if sid == "" {
		return
	}
	r := p.session(sid)
	if r == nil {
		return
	}
	switch typ {
	case "agent_message_chunk", "agent_thought_chunk", "user_message_chunk":
		content := providerMap(u["content"])
		text := S(content, "text")
		if text == "" {
			return
		}
		if typ == "user_message_chunk" {
			p.store.Add(sid, M{"kind": "user", "text": text, "title": "From PC"})
			return
		}
		id := r.TextID()
		kind := "assistant"
		if typ == "agent_thought_chunk" {
			id = r.ThoughtID()
			kind = "thinking"
			if id == "" && r.TextID() != "" {
				p.closeAgyStreams(sid, r)
			}
		}
		if id == "" {
			entry := p.store.Add(sid, M{"kind": kind, "text": "", "streaming": true})
			id = S(entry, "id")
			if kind == "thinking" {
				r.SetThoughtID(id)
			} else {
				r.SetTextID(id)
				if r.ThoughtID() != "" {
					p.store.Patch(sid, r.ThoughtID(), M{"streaming": false})
					r.SetThoughtID("")
				}
			}
		}
		p.store.Delta(sid, id, "text", text)
	case "tool_call", "tool_call_update":
		id := S(u, "toolCallId")
		if id == "" {
			return
		}
		if typ == "tool_call" {
			p.closeAgyStreams(sid, r)
			if S(u, "name") == "ask_question" || agyQuestionTitle.MatchString(S(u, "title")) {
				input := providerMap(u["rawInput"])
				if len(input) == 0 {
					input = providerMap(u["args"])
				}
				if p.addAgyQuestion(sid, id, input) {
					r.SetParent("agyQuestion:"+id, id)
					return
				}
			}
		}
		if r.Parent("agyQuestion:"+id) != "" {
			return
		}
		entry := M{"id": id, "kind": "tool"}
		if state := S(u, "status"); state != "" {
			entry["status"] = providerStatus(state)
		} else if typ == "tool_call" {
			entry["status"] = "running"
		}
		title := S(u, "title")
		name := S(u, "name")
		if name == "" {
			name = title
		}
		if typ == "tool_call" {
			entry["title"] = title
			entry["tool"] = agyToolName(S(u, "kind"), title)
			entry["detail"] = providerPretty(u["rawInput"])
		}
		if title != "" {
			entry["text"] = title
		}
		raw := providerMap(u["rawInput"])
		if strings.Contains(strings.ToLower(name), "invoke_subagent") || raw["Prompt"] != nil && raw["Role"] != nil {
			entry["tool"] = "Agent"
			entry["title"] = S(raw, "Role")
			entry["detail"] = S(raw, "Prompt")
		}
		artifact := providerMap(raw["ArtifactMetadata"])
		if len(artifact) > 0 {
			entry["tool"] = "Artifact"
			entry["path"] = S(raw, "Path")
			entry["title"] = S(raw, "Path")
			entry["detail"] = S(raw, "Content")
			entry["text"] = S(artifact, "Summary")
			entry["requestFeedback"] = B(artifact, "RequestFeedback")
		}
		paths := []string{}
		for _, loc := range providerRows(u["locations"]) {
			if path := agyPath(S(loc, "path")); path != "" && !slices.Contains(paths, path) {
				paths = append(paths, path)
			}
		}
		var out, diffs []string
		for _, c := range providerRows(u["content"]) {
			switch S(c, "type") {
			case "content":
				part := providerMap(c["content"])
				if text := S(part, "text"); text != "" {
					out = append(out, text)
				}
			case "diff":
				if path := agyPath(S(c, "path")); path != "" && !slices.Contains(paths, path) {
					paths = append(paths, path)
				}
				if diff := agyDiff(S(c, "oldText"), S(c, "newText")); diff != "" {
					diffs = append(diffs, diff)
				}
			}
		}
		if len(paths) > 0 {
			entry["files"] = paths
		}
		if len(diffs) > 0 {
			entry["detail"] = strings.Join(diffs, "\n⋯\n")
		}
		if output := u["rawOutput"]; output != nil {
			raw, ok := output.(string)
			if !ok {
				raw = providerPretty(output)
			}
			if raw != "" && !slices.Contains(out, raw) {
				out = append(out, raw)
			}
		}
		if len(out) > 0 {
			entry["output"] = strings.Join(out, "\n")
		}
		p.store.Add(sid, entry)
		p.agyObserveTool(sid, id, S(entry, "output")+"\n"+providerPretty(u))
	case "plan":
		items := []M{}
		for _, item := range providerRows(u["entries"]) {
			items = append(items, M{"content": S(item, "content"), "status": S(item, "status")})
		}
		p.store.Update(sid, M{"plan": M{"items": items, "updatedAt": providerNow()}}, false)
	case "current_mode_update":
		if mode := S(u, "currentModeId"); mode != "" {
			p.updateAgyConfig(sid, r, M{"mode": mode}, true)
		}
	case "config_option_update", "config_options_update":
		r.rememberAgyModeConfig(u)
		if patch := agyConfigValues(u); len(patch) > 0 {
			p.updateAgyConfig(sid, r, patch, false)
		}
	case "usage_update":
		p.store.recordAgyUsage(sid, S(params, "sessionId"), u)
	case "session_info_update":
		rec := p.store.Get(sid)
		if rec != nil && !B(rec.Info, "titlePinned") && S(u, "title") != "" {
			p.store.Update(sid, M{"title": u["title"]}, false)
		}
	}
}

func (s *Store) recordAgyUsage(sid, external string, u M) {
	window := N(u, "size")
	used, hasUsage := reportedAmount(u, "used")
	cost := providerMap(u["cost"])
	usd, hasCost := reportedAmount(cost, "amount")
	hasCost = hasCost && strings.EqualFold(S(cost, "currency"), "USD")
	if !(hasUsage && window > 0) && !hasCost {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	rec := s.records[sid]
	if s.closed || rec == nil || S(rec.Info, "provider") != "antigravity" || S(rec.Info, "externalId") != external {
		return
	}
	var changed detailChange
	if hasUsage && window > 0 {
		previous := providerMap(rec.Info["context"])
		if N(previous, "used") != used || N(previous, "max") != window {
			changed = detailUsage
			rec.noteContextMeasurement()
		}
		rec.Info["context"] = M{"used": used, "max": window, "percent": math.Round(100 * used / window)}
	}
	if hasCost {
		stats := providerMap(rec.Info["stats"])
		if stats["costUsd"] == nil || N(stats, "costUsd") != usd {
			changed = detailUsage
		}
		stats["costUsd"], stats["costSource"], stats["partial"] = usd, "provider", false
		rec.Info["stats"] = stats
	}
	s.dirty[sid] = true
	s.emitLocked(M{"type": "session", "session": rec.Info})
	s.noteSessionDetails(sid, changed)
}

func (p *nativeProvider) listAgyCommands(rec *Record) []M {
	if rec != nil {
		if r := p.session(S(rec.Info, "id")); r != nil {
			r.mu.Lock()
			commands := r.agyCommands
			r.mu.Unlock()
			if commands != nil {
				return commands
			}
		}
	}
	p.mu.Lock()
	commands := p.commands
	p.mu.Unlock()
	if len(commands) == 0 {
		commands = p.loadCache("commands")
	}
	if commands == nil {
		commands = []M{}
	}
	return commands
}

func agyModelUnset(model string) bool {
	model = strings.TrimSpace(model)
	return model == "" || strings.EqualFold(model, "default")
}

func agyModeOption(response M) M {
	for _, option := range providerRows(response["configOptions"]) {
		if S(option, "id") != "" && (S(option, "category") == "mode" || S(option, "id") == "mode") {
			return option
		}
	}
	return nil
}

func (r *nativeSession) rememberAgyModeConfig(response M) {
	if id := S(agyModeOption(response), "id"); id != "" {
		r.mu.Lock()
		r.agyModeConfigID = id
		r.mu.Unlock()
	}
}

func (p *nativeProvider) updateAgyConfig(sid string, r *nativeSession, patch M, legacy bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.sessions[sid] != r {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if legacy && r.agyModeConfigID != "" {
		return
	}
	if S(patch, "mode") != "" {
		r.agyModeRevision++
	}
	p.store.Update(sid, patch, false)
}

func agyConfigValues(response M) M {
	patch := M{}
	for _, option := range providerRows(response["configOptions"]) {
		value := S(option, "currentValue")
		if value == "" {
			continue
		}
		switch S(option, "id") {
		case "model":
			if !agyModelUnset(value) {
				patch["model"] = value
			}
		case "thought_level":
			patch["effort"] = value
		}
	}
	if model := S(providerMap(response["models"]), "currentModelId"); patch["model"] == nil && !agyModelUnset(model) {
		patch["model"] = model
	}
	mode := S(agyModeOption(response), "currentValue")
	if mode == "" {
		mode = S(providerMap(response["modes"]), "currentModeId")
	}
	if mode != "" {
		patch["mode"] = mode
	}
	return patch
}

func (p *nativeProvider) configureAgyMode(ctx context.Context, sid, mode string) error {
	// Serialize with session initialization, independently of the active prompt.
	p.startMu.Lock()
	defer p.startMu.Unlock()
	if err := ctx.Err(); err != nil {
		return err
	}
	r := p.session(sid)
	if r == nil {
		current := p.store.infoRecord(sid)
		if current == nil {
			return &HTTPError{404, "Session not found"}
		}
		if status := S(current.Info, "status"); status == "running" || status == "waiting" {
			return &HTTPError{409, "Antigravity is active outside this connection. Change permissions in the session that is running on the PC."}
		}
		p.store.Update(sid, M{"mode": mode}, false)
		return nil
	}
	return p.setAgyMode(ctx, sid, r, mode)
}

func (p *nativeProvider) setAgyMode(ctx context.Context, sid string, r *nativeSession, mode string) error {
	ctx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	w, external := r.Wire(), r.External()
	if w == nil || external == "" {
		return fmt.Errorf("Antigravity conversation is no longer connected")
	}
	r.mu.Lock()
	configID, revision := r.agyModeConfigID, r.agyModeRevision
	r.mu.Unlock()
	confirmed := mode
	if configID != "" {
		response, err := w.request(ctx, "session/set_config_option", M{"sessionId": external, "configId": configID, "value": mode})
		if err != nil {
			return fmt.Errorf("Change Antigravity permissions: %w", err)
		}
		confirmed = ""
		for _, option := range providerRows(response["configOptions"]) {
			if S(option, "id") == configID {
				confirmed = S(option, "currentValue")
				break
			}
		}
		if confirmed == "" {
			return fmt.Errorf("Antigravity did not confirm the permission change")
		}
	} else if _, err := w.request(ctx, "session/set_mode", M{"sessionId": external, "modeId": mode}); err != nil {
		return fmt.Errorf("Change Antigravity permissions: %w", err)
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.sessions[sid] != r {
		return fmt.Errorf("Antigravity conversation is no longer connected")
	}
	r.mu.Lock()
	if r.agyModeRevision == revision {
		p.store.Update(sid, M{"mode": confirmed}, false)
	}
	r.mu.Unlock()
	if confirmed != mode {
		return fmt.Errorf("Antigravity kept permission mode %q instead of %q", confirmed, mode)
	}
	return nil
}

func (p *nativeProvider) initializeAgySession(ctx context.Context, rec *Record, r *nativeSession, response M) error {
	r.rememberAgyModeConfig(response)
	current := agyConfigValues(response)
	p.store.fillAgyConfig(rec, current)
	if mode := S(rec.Info, "mode"); mode != "" && mode != S(current, "mode") {
		if err := p.setAgyMode(ctx, S(rec.Info, "id"), r, mode); err != nil {
			return err
		}
	}
	for _, setting := range []struct{ field, configID string }{{"model", "model"}, {"effort", "thought_level"}} {
		value := S(rec.Info, setting.field)
		if setting.field == "model" && S(current, "effort") != "" && value == S(current, "model")+"-"+S(current, "effort") {
			continue
		}
		if !agyModelUnset(value) && value != S(current, setting.field) {
			_, _ = r.Wire().request(ctx, "session/set_config_option", M{"sessionId": r.External(), "configId": setting.configID, "value": value})
		}
	}
	return nil
}

func (s *Store) fillAgyConfig(source *Record, defaults M) {
	if len(defaults) == 0 {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	id := S(source.Info, "id")
	r := s.records[id]
	if s.closed || r == nil || S(r.Info, "provider") != "antigravity" || S(r.Info, "externalId") != S(source.Info, "externalId") {
		return
	}
	changed := false
	for key, value := range defaults {
		current := S(r.Info, key)
		if current == "" || key == "model" && agyModelUnset(current) {
			r.Info[key] = value
			changed = true
		}
	}
	if changed {
		s.dirty[id] = true
		s.emitLocked(M{"type": "session", "session": r.Info})
	}
}

func (p *nativeProvider) agyModels(ctx context.Context) ([]M, error) {
	bin, err := FindProviderExecutable("antigravity")
	if err != nil {
		return nil, err
	}
	out, err := HostCommand(ctx, bin, "models").Output()
	if err != nil {
		return nil, fmt.Errorf("Read Antigravity models: %w", err)
	}
	rows := []M{}
	suffix := regexp.MustCompile(`-(low|medium|high|xhigh)$`)
	groups := map[string][]string{}
	labels := map[string]string{}
	ids := []string{}
	for _, line := range strings.Split(string(out), "\n") {
		cols := strings.Split(strings.TrimSpace(line), "\t")
		if len(cols) < 2 {
			continue
		}
		id := strings.TrimSpace(cols[0])
		label := strings.TrimSpace(cols[1])
		match := suffix.FindStringSubmatch(id)
		base := suffix.ReplaceAllString(id, "")
		if _, ok := groups[base]; !ok {
			ids = append(ids, base)
		}
		if len(match) > 1 {
			groups[base] = append(groups[base], match[1])
		} else {
			groups[base] = append(groups[base], "")
		}
		labels[base], _ = splitAgyModel(label)
	}
	for _, base := range ids {
		efforts := groups[base]
		id := base
		if len(efforts) == 1 && efforts[0] != "" {
			id += "-" + efforts[0]
		}
		actual := []string{}
		if len(efforts) > 1 {
			for _, level := range []string{"low", "medium", "high", "xhigh"} {
				for _, e := range efforts {
					if e == level {
						actual = append(actual, e)
					}
				}
			}
		}
		rows = append(rows, M{"id": id, "label": labels[base], "description": "", "efforts": actual, "isDefault": false})
	}
	if len(rows) == 0 {
		return nil, fmt.Errorf("Antigravity returned no models")
	}
	p.mu.Lock()
	p.models = rows
	p.mu.Unlock()
	p.saveCache("models", rows)
	return rows, nil
}
func parseAgyUsage(text string) M {
	windows := []M{}
	for _, line := range strings.Split(text, "\n") {
		cols := strings.Split(strings.TrimSpace(line), "\t")
		if len(cols) < 3 || !strings.Contains(cols[2], "%") {
			continue
		}
		remaining, err := strconv.ParseFloat(strings.TrimSpace(strings.ReplaceAll(cols[2], "%", "")), 64)
		if err != nil {
			continue
		}
		scope := strings.ReplaceAll(strings.ReplaceAll(cols[0], "Gemini Models", "Gemini"), "Claude and GPT models", "Claude / GPT")
		period := strings.ToLower(cols[1])
		row := M{"usedPercent": max(0, min(100, 100-remaining)), "resetsAt": nil, "scope": scope}
		label := cols[1]
		if strings.Contains(period, "weekly") {
			row["labelKey"] = "week"
			label = "Weekly"
		} else if strings.Contains(period, "five hour") || strings.Contains(period, "5 hour") {
			row["labelKey"] = "hours"
			row["labelCount"] = 5
			label = "5h"
		}
		row["label"] = label + " (" + scope + ")"
		if len(cols) > 3 && strings.TrimSpace(cols[3]) != "" {
			row["resetsAt"] = providerResetTime(cols[3])
		}
		windows = append(windows, row)
	}
	result := M{"windows": windows, "at": providerNow()}
	if len(windows) == 0 {
		result["error"] = "Failed to read usage stats"
	}
	return result
}
