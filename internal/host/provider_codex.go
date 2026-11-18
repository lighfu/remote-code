package host

import (
	"context"
	"fmt"
	"math"
	"strings"
	"time"
)

func (p *nativeProvider) rejoinCodex(ctx context.Context, rec *Record) (bool, error) {
	external := S(rec.Info, "externalId")
	if external == "" {
		return false, nil
	}
	if p.session(S(rec.Info, "id")) != nil {
		return true, nil
	}
	loaded, err := p.loadedCodexThreads(ctx)
	if err != nil || !loaded[external] {
		return false, err
	}
	_, err = p.ensureWithCodexSettings(ctx, rec, true)
	return err == nil, err
}

func (p *nativeProvider) rejoinLoadedCodex(ctx context.Context) error {
	config, err := LoadConfig(p.data)
	if err != nil || config.Raw["codexDaemon"] == false {
		return err
	}
	var records []M
	for _, info := range p.store.List() {
		if S(info, "provider") == "codex" && S(info, "externalId") != "" && !B(info, "archived") &&
			!B(info, "desktop") && p.session(S(info, "id")) == nil {
			records = append(records, info)
		}
	}
	if len(records) == 0 {
		return nil
	}
	loaded, err := p.loadedCodexThreads(ctx)
	if err != nil {
		return err
	}
	for _, info := range records {
		if err := ctx.Err(); err != nil {
			return err
		}
		if !loaded[S(info, "externalId")] {
			continue
		}
		// Ownership and archival status may have changed since the startup snapshot.
		rec := p.store.Get(S(info, "id"))
		if rec == nil || B(rec.Info, "archived") || !loaded[S(rec.Info, "externalId")] || p.session(S(info, "id")) != nil {
			continue
		}
		check, cancel := context.WithTimeout(ctx, 20*time.Second)
		attached, err := p.tryDesktop(check, rec)
		if err == nil && !attached {
			_, err = p.ensureWithCodexSettings(check, rec, true)
		}
		cancel()
		if err != nil && ctx.Err() == nil {
			consoleLogf("[codex] Shared conversation reconnection deferred: %v", err)
		}
	}
	return nil
}

func (p *nativeProvider) loadedCodexThreads(ctx context.Context) (map[string]bool, error) {
	w, err := p.ensureRPC(ctx)
	if err != nil || !w.shared {
		return nil, err
	}
	ids := map[string]bool{}
	params := M{"limit": 200}
	seen := map[string]bool{}
	for {
		page, err := w.request(ctx, "thread/loaded/list", params)
		if err != nil {
			return nil, err
		}
		for _, value := range array(page["data"]) {
			if id, _ := value.(string); id != "" {
				ids[id] = true
			}
		}
		cursor := S(page, "nextCursor")
		if cursor == "" {
			return ids, nil
		}
		if seen[cursor] {
			return nil, fmt.Errorf("Codex loaded thread list pagination did not advance")
		}
		seen[cursor] = true
		params["cursor"] = cursor
	}
}

func (p *nativeProvider) codexMessage(msg M) {
	if msg["_exit"] != nil {
		p.rpcExit(msg)
		return
	}
	method := S(msg, "method")
	params := providerMap(msg["params"])
	if p.codexSideMessage(msg, params) {
		return
	}
	if method == "account/rateLimits/updated" {
		p.rememberUsage(p.codexUsage(params))
		return
	}
	external := S(params, "threadId")
	if external == "" {
		external = S(providerMap(params["thread"]), "id")
	}
	sid := p.providerSID(external)
	if method == "thread/started" {
		thread := providerMap(params["thread"])
		parentThread := S(thread, "parentThreadId")
		if parentThread != "" {
			parentSID := p.providerSID(parentThread)
			if parentSID != "" {
				r := p.session(parentSID)
				card := "agent:" + external
				if r != nil {
					if existing := r.Parent("child:" + external); existing != "" {
						card = existing
					}
					r.SetParent("child:"+external, card)
				}
				p.mu.Lock()
				p.byExternal[external] = parentSID
				p.mu.Unlock()
				title := S(thread, "agentNickname")
				if title == "" {
					title = "Subagent"
				}
				p.store.Add(parentSID, M{"id": card, "kind": "tool", "tool": "Agent", "title": title, "status": "running"})
			}
		}
	}
	if sid == "" {
		if msg["id"] != nil {
			p.mu.Lock()
			w := p.rpc
			p.mu.Unlock()
			if w != nil {
				_ = w.write(M{"id": msg["id"], "error": M{"code": -32601, "message": "Conversation is not attached"}})
			}
		}
		return
	}
	r := p.session(sid)
	parent := ""
	if r != nil {
		parent = r.Parent("child:" + external)
	}
	if msg["id"] != nil {
		p.codexRequest(sid, msg)
		return
	}
	if r != nil && parent == "" && strings.HasPrefix(method, "item/") && r.Turn() == "" {
		if turn := S(params, "turnId"); turn != "" {
			r.SetTurn(turn)
			if p.store.codexPending(sid) == 0 {
				p.store.Update(sid, M{"status": "running"}, false)
			}
		}
	}
	switch method {
	case "turn/started":
		if r != nil && parent == "" {
			r.SetTurn(S(providerMap(params["turn"]), "id"))
		}
		if parent == "" {
			p.store.Update(sid, M{"status": "running"}, true)
		} else {
			p.store.Patch(sid, parent, M{"status": "running"})
		}
	case "turn/completed":
		turn := providerMap(params["turn"])
		if parent != "" {
			p.store.Patch(sid, parent, M{"status": providerStatus(S(turn, "status")), "progress": S(providerMap(turn["error"]), "message")})
		} else {
			if r != nil {
				if active, completed := r.Turn(), S(turn, "id"); active != "" && completed != "" && active != completed {
					return
				}
			}
			ok := S(turn, "status") == "completed"
			text := "Completed"
			if !ok {
				text = "Stopped: " + S(turn, "status")
			}
			p.cancelPending(sid)
			p.settle(sid, ok, text)
			if r != nil {
				r.SetTurn("")
			}
		}
	case "item/started", "item/completed":
		p.codexItem(sid, providerMap(params["item"]), method == "item/completed", parent)
	case "item/agentMessage/delta", "item/plan/delta", "item/reasoning/summaryTextDelta", "item/reasoning/textDelta":
		id := S(params, "itemId")
		delta := S(params, "delta")
		if id == "" || delta == "" {
			return
		}
		if !p.hasEntry(sid, id) {
			kind := "assistant"
			if strings.Contains(method, "reasoning") {
				kind = "thinking"
			}
			p.store.Add(sid, M{"id": id, "kind": kind, "text": "", "streaming": true, "parent": parent})
		}
		p.store.Delta(sid, id, "text", delta)
	case "item/reasoning/summaryPartAdded":
		if p.hasEntry(sid, S(params, "itemId")) {
			p.store.Delta(sid, S(params, "itemId"), "text", "\n\n")
		}
	case "item/commandExecution/outputDelta":
		p.store.Delta(sid, S(params, "itemId"), "output", S(params, "delta"))
	case "thread/tokenUsage/updated":
		u := providerMap(params["tokenUsage"])
		last := providerMap(u["last"])
		max := N(u, "modelContextWindow")
		used := N(last, "inputTokens") + N(last, "outputTokens")
		if max > 0 && parent == "" {
			p.store.Update(sid, M{"context": M{"used": used, "max": max, "percent": min(100, math.Round(100*used/max))}}, false)
		}
		if parent == "" {
			p.store.recordCodexLiveUsage(sid, external, u)
		}
	case "thread/settings/updated":
		settings := providerMap(params["threadSettings"])
		if _, ok := settings["serviceTier"]; ok {
			p.store.Update(sid, M{"fastMode": S(settings, "serviceTier") == "priority"}, false)
		}
	case "thread/status/changed":
		state := providerMap(params["status"])
		if parent != "" || p.store.codexPending(sid) > 0 {
			return
		}
		switch S(state, "type") {
		case "active":
			status := "running"
			if len(array(state["activeFlags"])) > 0 {
				status = "waiting"
			}
			p.store.Update(sid, M{"status": status}, false)
		case "idle":
			if r == nil || r.Turn() == "" {
				p.store.Update(sid, M{"status": "idle"}, false)
			}
		}
	case "thread/name/updated":
		rec := p.store.Get(sid)
		if rec != nil && !B(rec.Info, "titlePinned") && parent == "" {
			title := S(params, "threadName")
			if title == "" {
				title = S(params, "name")
			}
			if title != "" {
				p.store.Update(sid, M{"title": title}, false)
			}
		}
	case "turn/plan/updated":
		if parent != "" {
			return
		}
		items := []M{}
		var details []string
		complete := true
		for _, row := range providerRows(params["plan"]) {
			status := S(row, "status")
			if status == "inProgress" {
				status = "in_progress"
			}
			step := S(row, "step")
			items = append(items, M{"content": step, "status": status})
			mark := "○"
			switch status {
			case "in_progress":
				mark = "▶"
			case "completed":
				mark = "✓"
			}
			complete = complete && status == "completed"
			details = append(details, mark+" "+step)
		}
		p.store.Update(sid, M{"plan": M{"items": items, "updatedAt": providerNow()}}, false)
		if turn := S(params, "turnId"); turn != "" {
			title := S(params, "explanation")
			if title == "" {
				title = "Update plan"
			}
			status := "running"
			if len(items) > 0 && complete {
				status = "done"
			}
			p.store.Add(sid, M{"id": "plan-" + turn, "kind": "tool", "tool": "TodoWrite", "title": title, "detail": strings.Join(details, "\n"), "status": status})
		}
	case "error":
		if !B(params, "willRetry") {
			p.store.Add(sid, M{"kind": "system", "level": "error", "text": S(providerMap(params["error"]), "message"), "parent": parent})
		}
	case "serverRequest/resolved":
		p.mu.Lock()
		for id, q := range p.pending {
			if fmt.Sprint(q.rawID) == fmt.Sprint(params["requestId"]) {
				delete(p.pending, id)
				p.store.Patch(q.sid, q.entry, M{"status": "cancelled"})
			}
		}
		p.mu.Unlock()
		p.refreshPendingState(sid)
	}
}
func (p *nativeProvider) hasEntry(sid, id string) bool {
	return id != "" && p.store.hasEntry(sid, id)
}

func (s *Store) recordCodexLiveUsage(sid, external string, usage M) {
	total := providerMap(usage["total"])
	if len(total) == 0 {
		total = providerMap(usage["totalTokenUsage"])
	}
	last := providerMap(usage["last"])
	if len(last) == 0 {
		last = providerMap(usage["lastTokenUsage"])
	}
	input, cached, output := N(total, "inputTokens"), N(total, "cachedInputTokens"), N(total, "outputTokens")
	lastInput, lastCached := N(last, "inputTokens"), N(last, "cachedInputTokens")
	if external == "" || input <= 0 || cached < 0 || cached > input || output < 0 || lastInput <= 0 || lastCached < 0 || lastCached > lastInput {
		return
	}
	hit := lastCached / lastInput
	statsForUpdate := func() (stats, cache M, changed bool) {
		rec := s.records[sid]
		if s.closed || rec == nil || S(rec.Info, "provider") != "codex" || S(rec.Info, "externalId") != external {
			return nil, nil, false
		}
		stats = providerMap(rec.Info["stats"])
		tokens := providerMap(stats["tokens"])
		if N(tokens, "input") > input || N(tokens, "cached") > cached || N(tokens, "output") > output {
			return nil, nil, false
		}
		cache = providerMap(stats["cache"])
		changed = N(tokens, "input") != input || N(tokens, "cached") != cached || N(tokens, "output") != output
		if !changed && N(cache, "lastAt") > 0 && N(cache, "ttlMs") == float64(codexCacheTTL.Milliseconds()) && N(cache, "lastHit") == hit {
			return nil, nil, false
		}
		return stats, cache, changed
	}
	s.mu.RLock()
	stats, _, _ := statsForUpdate()
	s.mu.RUnlock()
	if stats == nil || analyticsPath("codex", external) != "" {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	stats, cache, changed := statsForUpdate()
	if stats == nil {
		return
	}
	at := N(cache, "lastAt")
	if changed || at == 0 {
		at = float64(providerNow())
	}
	stats["cache"] = M{"lastAt": at, "ttlMs": codexCacheTTL.Milliseconds(), "lastHit": hit}
	stats["tokens"] = M{"input": input, "cached": cached, "output": output}
	stats["partial"] = true
	rec := s.records[sid]
	rec.Info["stats"] = stats
	s.dirty[sid] = true
	s.emitLocked(M{"type": "session", "session": rec.Info})
}

func codexClientMessageID(item M) string {
	if id := S(item, "clientId"); id != "" {
		return id
	}
	return S(item, "clientUserMessageId")
}

// Return the path to open and its patch. Codex sends full file bodies for additions and deletions.
func codexFileChange(change M, multi bool) (string, string) {
	path, detail := S(change, "path"), S(change, "diff")
	kind := providerMap(change["kind"])
	changeType := S(kind, "type")
	if changeType == "" {
		changeType = S(change, "kind")
	}
	switch changeType {
	case "add", "delete":
		verb, sign := "New", "+"
		if changeType == "delete" {
			verb, sign = "Delete", "-"
		}
		var out strings.Builder
		fmt.Fprintf(&out, "── %s %s", verb, path)
		if detail == "" {
			return path, out.String()
		}
		body := strings.TrimSuffix(strings.ReplaceAll(detail, "\r\n", "\n"), "\n")
		lineCount := strings.Count(body, "\n") + 1
		if changeType == "add" {
			fmt.Fprintf(&out, "\n@@ -0,0 +1,%d @@", lineCount)
		} else {
			fmt.Fprintf(&out, "\n@@ -1,%d +0,0 @@", lineCount)
		}
		for line := range strings.SplitSeq(body, "\n") {
			out.WriteByte('\n')
			out.WriteString(sign)
			out.WriteString(line)
		}
		return path, out.String()
	case "update":
		if moved := S(kind, "movePath"); moved != "" {
			return moved, "── Modify " + moved + "\n--- " + path + "\n+++ " + moved + "\n" + detail
		}
		return path, "── Modify " + path + "\n" + detail
	default:
		if multi {
			return path, "── " + path + "\n" + detail
		}
		return path, detail
	}
}

func (p *nativeProvider) codexItem(sid string, item M, completed bool, parent string) {
	p.addCodexItem(sid, item, completed, parent, p.store.Add)
}

func (p *nativeProvider) addCodexItem(sid string, item M, completed bool, parent string, add func(string, M) M) {
	id := S(item, "id")
	if id == "" {
		return
	}
	kind := S(item, "type")
	entry := M{"id": id}
	if at := N(item, "startedAtMs"); at > 0 {
		entry["ts"] = at
	}
	if parent != "" {
		entry["parent"] = parent
	}
	status := "running"
	if completed {
		status = "done"
		if S(item, "status") == "failed" || N(item, "exitCode") != 0 {
			status = "error"
		}
	}
	switch kind {
	case "userMessage", "steeringUserMessage":
		if !completed || parent != "" {
			return
		}
		text := providerText(item["content"])
		if text == "" {
			text = S(item, "text")
		}
		entry["kind"] = "user"
		entry["text"] = text
		entry["title"] = "From another screen"
		if p.hasEntry(sid, codexClientMessageID(item)) {
			return
		}
		media := []string{}
		for _, c := range providerRows(item["content"]) {
			if S(c, "type") == "localImage" {
				if id := MediaFromPath(S(c, "path"), ""); id != "" {
					media = append(media, id)
				}
			}
		}
		if len(media) > 0 {
			entry["media"] = media
		}
	case "agentMessage", "plan":
		entry["kind"] = "assistant"
		content := S(item, "text")
		if content == "" {
			content = providerText(item["content"])
		}
		if strings.TrimSpace(content) != "" {
			entry["text"] = content
		} else if !p.hasEntry(sid, id) {
			if completed {
				p.addCodexInlineQuestions(sid, item, parent)
			}
			return
		}
		entry["streaming"] = !completed
	case "reasoning":
		entry["kind"] = "thinking"
		if summary := strings.Join(providerAnswerList(item["summary"]), "\n\n"); strings.TrimSpace(summary) != "" {
			entry["text"] = summary
		} else if !completed || !p.hasEntry(sid, id) {
			return
		}
		entry["streaming"] = !completed
	case "collabAgentToolCall":
		spawn := S(item, "tool") == "spawnAgent"
		entry["kind"] = "tool"
		entry["tool"] = "AgentControl"
		entry["title"] = S(item, "tool")
		entry["status"] = status
		entry["detail"] = providerPretty(item)
		if spawn {
			entry["tool"] = "Agent"
			entry["title"] = providerTruncate(strings.Split(S(item, "prompt"), "\n")[0], 120)
			entry["detail"] = S(item, "prompt")
			entry["text"] = S(item, "model")
			entry["status"] = "running"
			if ids, ok := item["receiverThreadIds"].([]any); ok {
				for _, v := range ids {
					if tid, ok := v.(string); ok {
						r := p.session(sid)
						if r != nil {
							r.SetParent("child:"+tid, id)
						}
						p.mu.Lock()
						p.byExternal[tid] = sid
						p.mu.Unlock()
						go p.loadCodexChild(sid, tid, id)
					}
				}
			}
		}
		for tid, value := range providerMap(item["agentsStates"]) {
			r := p.session(sid)
			if r == nil {
				continue
			}
			card := r.Parent("child:" + tid)
			if card != "" {
				state := providerMap(value)
				patch := M{"status": providerStatus(S(state, "status"))}
				if progress := S(state, "message"); progress != "" {
					patch["progress"] = progress
				}
				p.store.Patch(sid, card, patch)
			}
		}
	case "subAgentActivity":
		child := S(item, "threadId")
		if child == "" {
			child = S(item, "agentId")
		}
		if child == "" {
			return
		}
		card := "agent:" + child
		r := p.session(sid)
		if r != nil {
			if v := r.Parent("child:" + child); v != "" {
				card = v
			}
			r.SetParent("child:"+child, card)
		}
		entry["id"] = card
		entry["kind"] = "tool"
		entry["tool"] = "Agent"
		entry["title"] = S(item, "agentNickname")
		if S(entry, "title") == "" {
			entry["title"] = "Subagent"
		}
		entry["status"] = providerStatus(S(item, "status"))
		p.mu.Lock()
		p.byExternal[child] = sid
		p.mu.Unlock()
		go p.loadCodexChild(sid, child, card)
	case "contextCompaction":
		if !completed {
			return
		}
		entry["kind"] = "system"
		entry["level"] = "info"
		entry["text"] = "Summarized conversation to free up context"
	default:
		entry["kind"] = "tool"
		entry["tool"] = kind
		entry["title"] = kind
		entry["status"] = status
		switch kind {
		case "commandExecution":
			entry["tool"] = "command"
			entry["title"] = S(item, "command")
			entry["detail"] = S(item, "command")
			entry["output"] = S(item, "aggregatedOutput")
			if completed && item["exitCode"] != nil {
				entry["output"] = S(entry, "output") + fmt.Sprintf("\n(Exit code %.0f)", N(item, "exitCode"))
			}
		case "fileChange":
			paths := []string{}
			diffs := []string{}
			changes := providerRows(item["changes"])
			for _, c := range changes {
				path, detail := codexFileChange(c, len(changes) > 1)
				paths = append(paths, path)
				diffs = append(diffs, detail)
			}
			entry["title"] = strings.Join(paths, ", ")
			entry["files"] = paths
			entry["detail"] = strings.Join(diffs, "\n")
		case "mcpToolCall":
			entry["tool"] = "mcp__" + S(item, "server") + "__" + S(item, "tool")
			entry["title"] = S(item, "server") + " › " + S(item, "tool")
			entry["detail"] = providerPretty(item["arguments"])
			if completed {
				if item["error"] != nil {
					entry["status"] = "error"
					entry["output"] = providerPretty(item["error"])
				} else if parts := providerRows(providerMap(item["result"])["content"]); len(parts) > 0 {
					var media, output []string
					for _, part := range parts {
						typ := S(part, "type")
						if typ == "text" {
							output = append(output, S(part, "text"))
							continue
						}
						output = append(output, "["+typ+"]")
						if data := S(part, "data"); typ == "image" && data != "" {
							mime := S(part, "mimeType")
							if mime == "" {
								mime = "image/png"
							}
							if image := MediaFromBase64(p.data, data, mime); image != "" {
								media = append(media, image)
							}
						}
					}
					entry["output"] = strings.Join(output, "\n")
					if len(media) > 0 {
						entry["media"] = media
					}
				} else if item["result"] != nil {
					entry["output"] = providerPretty(item["result"])
				}
			}
		case "dynamicToolCall":
			entry["tool"] = "tool"
			entry["title"] = S(item, "tool")
			entry["detail"] = providerPretty(item["arguments"])
			if completed {
				var output []string
				for _, part := range providerRows(item["contentItems"]) {
					if part["text"] != nil {
						output = append(output, S(part, "text"))
					} else {
						output = append(output, "["+S(part, "type")+"]")
					}
				}
				entry["output"] = strings.Join(output, "\n")
			}
		case "webSearch":
			entry["title"] = S(item, "query")
		case "imageView":
			entry["title"] = S(item, "path")
			if image := MediaFromPath(S(item, "path"), ""); image != "" {
				entry["media"] = []string{image}
			}
		case "imageGeneration":
			entry["tool"] = "ImageGen"
			entry["title"] = "Generate image"
			entry["detail"] = S(item, "revisedPrompt")
			image := MediaFromPath(S(item, "savedPath"), "")
			if data := S(item, "result"); image == "" && data != "" {
				image = MediaFromBase64(p.data, data, "image/png")
			}
			if image != "" {
				entry["media"] = []string{image}
			}
		default:
			entry["detail"] = providerPretty(item)
		}
	}
	add(sid, entry)
	if completed && (kind == "agentMessage" || kind == "plan") {
		p.addCodexInlineQuestions(sid, item, parent)
	}
	if completed && parent != "" && S(entry, "text") != "" {
		p.store.Patch(sid, parent, M{"progress": providerTruncate(S(entry, "text"), 180)})
	}
}
func (p *nativeProvider) loadCodexChild(sid, thread, card string) {
	if rec := p.store.Get(sid); rec != nil && B(rec.Info, "desktop") {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	p.mu.Lock()
	w := p.rpc
	p.mu.Unlock()
	if w == nil {
		return
	}
	res, err := w.request(ctx, "thread/read", M{"threadId": thread, "includeTurns": true})
	if err != nil {
		return
	}
	for _, turn := range providerRows(providerMap(res["thread"])["turns"]) {
		for _, item := range providerRows(turn["items"]) {
			p.codexItem(sid, item, S(turn, "status") != "inProgress", card)
		}
	}
}
func (p *nativeProvider) codexRequest(sid string, msg M) {
	params := providerMap(msg["params"])
	method := S(msg, "method")
	p.mu.Lock()
	w := p.rpc
	p.mu.Unlock()
	if w == nil {
		return
	}
	switch method {
	case "item/commandExecution/requestApproval":
		p.addPermission(sid, w, msg["id"], method, params, "command", M{"command": S(params, "command"), "description": S(params, "reason")})
	case "item/fileChange/requestApproval":
		p.addPermission(sid, w, msg["id"], method, params, "fileChange", params)
	case "item/permissions/requestApproval":
		p.addPermission(sid, w, msg["id"], method, params, "permissions", params)
	case "item/tool/requestUserInput":
		p.addPermission(sid, w, msg["id"], method, params, "AskUserQuestion", params)
	case "mcpServer/elicitation/request":
		_ = w.reply(msg["id"], M{"action": "decline", "content": nil, "_meta": nil})
	default:
		_ = w.write(M{"id": msg["id"], "error": M{"code": -32601, "message": "Unsupported request: " + method}})
	}
}
