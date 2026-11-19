package host

import (
	"encoding/json"
	"fmt"
	"math"
	"regexp"
	"slices"
	"strings"
)

func (p *nativeProvider) claudeMessage(sid string, r *nativeSession, msg M) {
	if msg["_exit"] != nil {
		p.onExit(sid, r, S(msg, "_exit"))
		return
	}
	parent := S(msg, "parent_tool_use_id")
	kind := S(msg, "type")
	if kind == "control_request" {
		req := providerMap(msg["request"])
		subtype := S(req, "subtype")
		if subtype == "can_use_tool" {
			p.addPermission(sid, r.Wire(), msg["request_id"], subtype, req, S(req, "tool_name"), providerMap(req["input"]))
			return
		}
		if subtype == "hook_callback" && S(req, "callback_id") == "remote-code-question" {
			input := providerMap(req["input"])
			p.addPermission(sid, r.Wire(), msg["request_id"], "hook_question", req, "AskUserQuestion", providerMap(input["tool_input"]))
			return
		}
		_ = r.Wire().write(M{"type": "control_response", "response": M{"subtype": "error", "request_id": msg["request_id"], "error": "Unsupported control request: " + subtype}})
		return
	}
	if kind == "control_cancel_request" {
		p.mu.Lock()
		ids := []string{}
		for id, pending := range p.pending {
			if pending.sid == sid && fmt.Sprint(pending.rawID) == S(msg, "request_id") {
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
		return
	}
	if parent == "" {
		if state := S(msg, "fast_mode_state"); state != "" {
			p.store.Update(sid, M{"fastMode": state != "off", "fastModeState": state}, false)
		}
	}
	switch kind {
	case "system":
		switch S(msg, "subtype") {
		case "init":
			if !r.Remote() {
				r.SetExternal(S(msg, "session_id"))
			}
			p.mu.Lock()
			p.byExternal[r.External()] = sid
			p.mu.Unlock()
			patch := M{"externalId": r.External(), "resumeAt": nil}
			if model := S(msg, "model"); model != "" {
				patch["model"] = model
			}
			if mode := S(msg, "permissionMode"); mode != "" {
				patch["mode"] = mode
			}
			p.store.Update(sid, patch, false)
		case "compact_boundary":
			p.store.Add(sid, M{"kind": "system", "text": "Summarized conversation to free up context", "level": "info"})
		case "status":
			if mode := S(msg, "permissionMode"); mode != "" {
				p.store.Update(sid, M{"mode": mode}, false)
			}
		case "task_started", "task_progress", "task_notification":
			id := S(msg, "tool_use_id")
			task := S(msg, "task_id")
			if id == "" {
				id = r.Parent("task:" + task)
			}
			if id == "" {
				id = "agent:" + task
			}
			r.SetParent("task:"+task, id)
			patch := M{"id": id, "kind": "tool", "tool": "Agent", "status": "running"}
			if parent != "" {
				patch["parent"] = parent
			}
			if title := S(msg, "description"); title != "" {
				patch["title"] = title
			}
			if title := S(msg, "summary"); title != "" {
				patch["progress"] = title
			}
			if S(msg, "subtype") == "task_notification" {
				patch["status"] = providerStatus(S(msg, "status"))
			}
			p.store.Add(sid, patch)
		case "local_command_output":
			p.store.Add(sid, M{"kind": "assistant", "text": S(msg, "content")})
		}
	case "stream_event":
		event := providerMap(msg["event"])
		typ := S(event, "type")
		index := int(N(event, "index"))
		key := fmt.Sprintf("%s:block:%d", parent, index)
		switch typ {
		case "message_start":
			m := providerMap(event["message"])
			r.SetParent(parent+":message", S(m, "id"))
		case "content_block_start":
			block := providerMap(event["content_block"])
			id := r.Parent(parent+":message") + fmt.Sprintf("-%d", index)
			if S(block, "type") == "tool_use" {
				id = S(block, "id")
			}
			if id == fmt.Sprintf("-%d", index) {
				id = NewID()
			}
			r.SetParent(key, id)
			r.SetParent(claudeStreamBlockKey(parent, r.Parent(parent+":message"), index), id)
			entry := M{"id": id}
			if parent != "" {
				entry["parent"] = parent
			}
			switch S(block, "type") {
			case "text":
				entry["kind"] = "assistant"
				entry["text"] = S(block, "text")
				entry["streaming"] = true
			case "thinking":
				entry["kind"] = "thinking"
				entry["text"] = S(block, "thinking")
				entry["streaming"] = true
			case "tool_use":
				providerMerge(entry, p.describeClaudeTool(S(block, "name"), providerMap(block["input"])))
				entry["status"] = "running"
			default:
				return
			}
			p.store.Add(sid, entry)
		case "content_block_delta":
			id := r.Parent(key)
			if id == "" {
				return
			}
			delta := providerMap(event["delta"])
			switch S(delta, "type") {
			case "text_delta":
				p.store.Delta(sid, id, "text", S(delta, "text"))
			case "thinking_delta":
				p.store.Delta(sid, id, "text", S(delta, "thinking"))
			case "input_json_delta":
				rawKey := "input:" + id
				r.AppendParent(rawKey, S(delta, "partial_json"))
				p.store.Patch(sid, id, M{"detail": r.Parent(rawKey)})
			}
		case "content_block_stop":
			if id := r.Parent(key); id != "" {
				p.store.Patch(sid, id, M{"streaming": false})
			}
		}
	case "assistant":
		message := providerMap(msg["message"])
		messageID := S(message, "id")
		transcriptIDs, _ := msg["_transcript_entry_ids"].([]string)
		blocks := providerRows(message["content"])
		offset := claudeAssistantBlockOffset(r, msg, len(blocks))
		for i, block := range blocks {
			id := messageID + fmt.Sprintf("-%d", offset+i)
			if messageID == "" {
				id = fmt.Sprintf("%s:%d", S(msg, "uuid"), i)
			}
			if v := r.Parent(claudeStreamBlockKey(parent, messageID, offset+i)); v != "" {
				id = v
			}
			if S(block, "type") == "tool_use" {
				id = S(block, "id")
			}
			if i < len(transcriptIDs) {
				id = transcriptIDs[i]
			}
			entry := M{"id": id, "streaming": false}
			if parent != "" {
				entry["parent"] = parent
			}
			switch S(block, "type") {
			case "text":
				entry["kind"] = "assistant"
				entry["text"] = S(block, "text")
			case "thinking":
				entry["kind"] = "thinking"
				entry["text"] = S(block, "thinking")
			case "tool_use":
				providerMerge(entry, p.describeClaudeTool(S(block, "name"), providerMap(block["input"])))
				entry["status"] = "running"
			default:
				continue
			}
			p.addClaudeMessageEntry(sid, msg, entry)
		}
		p.claudeStats(sid, r, msg)
	case "user":
		p.claudeUserMessage(sid, r.Remote() || S(msg, "_transcript_hash") != "", msg)
	case "result":
		if parent != "" {
			p.store.Patch(sid, parent, M{"status": providerStatus(S(msg, "subtype")), "progress": S(msg, "result")})
			return
		}
		ok := S(msg, "subtype") == "success" && !B(msg, "is_error")
		text := "Completed"
		if !ok {
			text = "Stopped due to error"
		}
		p.settle(sid, ok, text)
		p.claudeStats(sid, r, msg)
		p.armClaudeIdle(sid, r)
	case "tool_progress":
		id := S(msg, "tool_use_id")
		if id != "" {
			p.store.Patch(sid, id, M{"progress": S(msg, "tool_name") + fmt.Sprintf(" · %.0fs", N(msg, "elapsed_time_seconds"))})
		}
	case "conversation_reset":
		if r.Remote() {
			return
		}
		if id := S(msg, "new_conversation_id"); id != "" {
			r.SetExternal(id)
			p.store.Update(sid, M{"externalId": id, "context": nil, "plan": nil}, false)
		}
	}
}

var claudePCMetadata = regexp.MustCompile(`(?s)<system-reminder>.*?</system-reminder>|<local-command-caveat>.*?</local-command-caveat>|<command-message>.*?</command-message>|<command-args>.*?</command-args>`)
var claudePCCommandName = regexp.MustCompile(`(?s)<command-name>(.*?)</command-name>`)
var claudePCCommandOutput = regexp.MustCompile(`(?s)^<local-command-stdout>(.*?)</local-command-stdout>$`)
var claudeImageResizeNotice = regexp.MustCompile(`(?m)^[\t ]*\[Image: original \d+x\d+, displayed at \d+x\d+\. Multiply coordinates by \d+(?:\.\d+)? to map to original image\.\][\t ]*\r?$`)

func stripClaudeImageResizeNotice(text string) string {
	return strings.TrimSpace(claudeImageResizeNotice.ReplaceAllString(text, ""))
}

func cleanClaudePCMessage(entry M) {
	text := strings.TrimSpace(claudePCMetadata.ReplaceAllString(S(entry, "text"), ""))
	if output := claudePCCommandOutput.FindStringSubmatch(text); output != nil {
		entry["kind"] = "system"
		entry["level"] = "info"
		delete(entry, "title")
		text = strings.TrimSpace(output[1])
	}
	entry["text"] = stripClaudeImageResizeNotice(claudePCCommandName.ReplaceAllString(text, "$1"))
}

func claudeStoredTimeline(entries []M) []M {
	visible := make([]M, 0, len(entries))
	for _, entry := range entries {
		if S(entry, "kind") == "user" && S(entry, "title") == "From PC" && claudeImageResizeNotice.MatchString(S(entry, "text")) {
			text := stripClaudeImageResizeNotice(S(entry, "text"))
			if text == "" && !claudeHasAttachments(entry) {
				continue
			}
			entry = clone(entry)
			entry["text"] = text
		}
		visible = append(visible, entry)
	}
	return visible
}

func claudeHasAttachments(entry M) bool {
	for _, key := range []string{"media", "videos", "files"} {
		if claudeHasReferences(entry[key]) {
			return true
		}
	}
	return N(entry, "images") > 0
}

func claudeHasReferences(value any) bool {
	switch values := value.(type) {
	case []string:
		return len(values) > 0
	case []any:
		return len(values) > 0
	default:
		return false
	}
}

func claudeUserTextEntries(msg M, fromPC bool) []M {
	content := providerMap(msg["message"])["content"]
	blocks := providerRows(content)
	if text, ok := content.(string); ok {
		blocks = []M{{"type": "text", "text": text}}
	}
	uuid := S(msg, "uuid")
	newEntry := func(id, text string) M {
		entry := M{"id": id, "kind": "user", "text": text, "title": "From PC"}
		if skill, ok := claudeInjectedSkill(msg, text); ok {
			providerMerge(entry, skill)
		}
		if parent := S(msg, "parent_tool_use_id"); parent != "" {
			entry["parent"] = parent
		}
		return entry
	}
	// A cross-session envelope describes one logical message, even when its
	// text arrives in several Claude content blocks.
	if text := providerText(content); strings.Contains(text, "<cross-session-message") && !claudeHasSkillMetadata(msg, text) {
		peer := newEntry(uuid, text)
		providerCrossSession(peer)
		if S(peer, "messageOrigin") == "peer" {
			return []M{peer}
		}
	}
	entries := []M{}
	for index, block := range blocks {
		if S(block, "type") != "text" {
			continue
		}
		id := uuid
		if id != "" && len(entries) > 0 {
			id = fmt.Sprintf("%s:%d", uuid, index)
		}
		entry := newEntry(id, S(block, "text"))
		if fromPC && S(entry, "kind") == "user" {
			cleanClaudePCMessage(entry)
		}
		if S(entry, "text") != "" {
			entries = append(entries, entry)
		}
	}
	return entries
}

func (p *nativeProvider) claudeUserMessage(sid string, fromPC bool, msg M) {
	content := providerMap(msg["message"])["content"]
	blocks := providerRows(content)
	toolResult := false
	for _, block := range blocks {
		if S(block, "type") != "tool_result" {
			continue
		}
		id := S(block, "tool_use_id")
		toolResult = true
		status := "done"
		if B(block, "is_error") {
			status = "error"
		}
		p.store.Patch(sid, id, M{"output": providerText(block["content"]), "status": status})
		p.addToolMedia(sid, id, block["content"])
	}
	if toolResult {
		p.store.repairClaudeWrapUpCards(sid)
	}
	msg = p.claudeSkillMessage(sid, msg)
	entries := claudeUserTextEntries(msg, fromPC)
	if media, videos := p.claudeBlockMedia(sid, blocks); len(media) > 0 || len(videos) > 0 {
		if len(entries) == 0 {
			entry := M{"id": S(msg, "uuid"), "kind": "user", "title": "From PC", "text": ""}
			if parent := S(msg, "parent_tool_use_id"); parent != "" {
				entry["parent"] = parent
			}
			entries = append(entries, entry)
		}
		if len(media) > 0 {
			entries[0]["media"] = media
		}
		if len(videos) > 0 {
			entries[0]["videos"] = videos
		}
	}
	for _, entry := range entries {
		p.addClaudeMessageEntry(sid, msg, entry)
	}
}

func (p *nativeProvider) addClaudeMessageEntry(sid string, msg, entry M) {
	if S(msg, "_transcript_hash") == "" {
		p.store.Add(sid, entry)
		return
	}
	entry["ts"] = providerStamp(msg["timestamp"])
	p.store.addTranscriptEntry(sid, entry)
}

func (p *nativeProvider) describeClaudeTool(name string, input M) M {
	entry := M{"kind": "tool", "tool": name, "title": name, "detail": providerPretty(input)}
	if name == "ExitPlanMode" {
		entry["title"] = "Plans"
		if plan := S(input, "plan"); plan != "" {
			entry["detail"] = plan
		}
		return entry
	}
	if command := S(input, "command"); command != "" {
		entry["title"] = command
		entry["detail"] = command
	}
	if desc := S(input, "description"); desc != "" {
		entry["text"] = desc
	}
	for _, key := range []string{"file_path", "path", "absolute_path", "notebook_path"} {
		if path := S(input, key); path != "" {
			entry["title"] = path
			entry["path"] = path
			entry["files"] = []string{path}
			break
		}
	}
	if content := S(input, "content"); content != "" {
		entry["detail"] = content
	}
	if old := S(input, "old_string"); old != "" || S(input, "new_string") != "" {
		entry["detail"] = "--- before\n+++ after\n-" + strings.ReplaceAll(old, "\n", "\n-") + "\n+" + strings.ReplaceAll(S(input, "new_string"), "\n", "\n+")
	}
	if name == "Agent" || name == "Task" {
		entry["tool"] = "Agent"
		title := S(input, "description")
		if title == "" {
			title = S(input, "subagent_type")
		}
		if title == "" {
			title = "Subagent"
		}
		entry["title"] = title
		entry["detail"] = S(input, "prompt")
		entry["text"] = S(input, "model")
	}
	return entry
}
func (p *nativeProvider) claudeStats(sid string, runtime *nativeSession, msg M) {
	u := providerMap(msg["usage"])
	if len(u) == 0 {
		u = providerMap(providerMap(msg["message"])["usage"])
	}
	if len(u) == 0 {
		return
	}
	input := N(u, "input_tokens")
	cached := N(u, "cache_read_input_tokens")
	created := N(u, "cache_creation_input_tokens")
	output := N(u, "output_tokens")
	total, read := input+cached+created, cached
	var contextTokens float64
	var contextModel string
	if S(msg, "type") == "assistant" {
		message := providerMap(msg["message"])
		id := S(message, "id")
		if id == "" {
			id = S(msg, "uuid")
		}
		if id == "" {
			return
		}
		runtime.mu.Lock()
		if S(msg, "parent_tool_use_id") == "" && total > 0 {
			contextTokens, contextModel = total+output, S(message, "model")
			runtime.claudeContextTokens, runtime.claudeContextModel = contextTokens, contextModel
		}
		runtime.claudeUsage.add("claude", id, analyticsUsage{Fresh: input, Read: cached, Write5: created, Output: output}, nil)
		total, read, output = runtime.claudeUsage.input, runtime.claudeUsage.cached, runtime.claudeUsage.output
		runtime.mu.Unlock()
	} else if S(msg, "type") == "result" {
		runtime.mu.Lock()
		contextTokens, contextModel = runtime.claudeContextTokens, runtime.claudeContextModel
		runtime.mu.Unlock()
	}
	p.store.mu.Lock()
	defer p.store.mu.Unlock()
	rec := p.store.records[sid]
	if rec == nil || p.store.closed || S(rec.Info, "provider") != "claude" {
		return
	}
	stats := providerMap(rec.Info["stats"])
	var changed detailChange
	if contextTokens > 0 {
		current := providerMap(rec.Info["context"])
		window := N(providerMap(providerMap(msg["modelUsage"])[contextModel]), "contextWindow")
		if window <= 0 {
			window = N(current, "max")
		}
		if window > 0 && (N(current, "used") != contextTokens || N(current, "max") != window) {
			rec.Info["context"] = M{"used": contextTokens, "max": window, "percent": math.Round(100 * contextTokens / window)}
			changed = detailUsage
		}
	}
	prior := providerMap(stats["tokens"])
	coversPrior := total >= N(prior, "input") && read >= N(prior, "cached") && output >= N(prior, "output")
	if coversPrior {
		if N(prior, "input") != total || N(prior, "cached") != read || N(prior, "output") != output {
			changed = detailUsage
		}
		stats["tokens"] = M{"input": total, "cached": read, "output": output}
	}
	if cost, ok := reportedAmount(msg, "total_cost_usd"); ok && (coversPrior || stats["costUsd"] == nil) {
		stats["costUsd"], stats["costSource"], stats["partial"] = cost, "provider", !coversPrior
	} else if stats["costUsd"] == nil || coversPrior && (N(prior, "input") != total || N(prior, "output") != output || N(prior, "cached") != read) {
		stats["partial"] = true
	}
	ttl := float64(300000)
	if N(providerMap(u["cache_creation"]), "ephemeral_1h_input_tokens") > 0 {
		ttl = 3600000
	}
	if prompt := input + cached + created; prompt > 0 && (S(msg, "type") == "assistant" || stats["cache"] == nil) {
		at := providerNow()
		if stamp := S(msg, "timestamp"); stamp != "" {
			at = providerStamp(stamp)
		}
		if float64(at) >= N(providerMap(stats["cache"]), "lastAt") {
			stats["cache"] = M{"lastAt": at, "ttlMs": ttl, "lastHit": cached / prompt}
		}
	}
	rec.Info["stats"] = stats
	p.store.dirty[sid] = true
	p.store.emitLocked(M{"type": "session", "session": rec.Info})
	p.store.noteSessionDetails(sid, changed)
}
func (p *nativeProvider) claudeBlockMedia(sid string, blocks []M) (media, videos []string) {
	cwd := ""
	readCWD := false
	for _, block := range blocks {
		kind := S(block, "type")
		if kind != "image" && kind != "video" || kind == "image" && len(media) >= 12 || kind == "video" && len(videos) >= 12 {
			continue
		}
		src := providerMap(block["source"])
		if kind == "image" {
			data, mime := S(block, "data"), S(block, "mimeType")
			if S(src, "type") == "base64" {
				data, mime = S(src, "data"), S(src, "media_type")
			}
			if data != "" {
				if id := MediaFromBase64(p.data, data, mime); id != "" && !slices.Contains(media, id) {
					media = append(media, id)
				}
				continue
			}
		}
		path := S(block, "path")
		if path == "" {
			path = S(src, "path")
		}
		if path == "" {
			path = S(src, "url")
		}
		if path == "" {
			continue
		}
		if !readCWD {
			if rec := p.store.Get(sid); rec != nil {
				cwd = S(rec.Info, "cwd")
			}
			readCWD = true
		}
		if kind == "image" {
			if id := MediaFromPath(path, cwd); id != "" && !slices.Contains(media, id) {
				media = append(media, id)
			}
		} else if id := VideoFromPath(path, cwd); id != "" && !slices.Contains(videos, id) {
			videos = append(videos, id)
		}
	}
	return media, videos
}

func (p *nativeProvider) addToolMedia(sid, entryID string, content any) {
	media, videos := p.claudeBlockMedia(sid, providerRows(content))
	patch := M{}
	if len(media) > 0 {
		patch["media"] = media
	}
	if len(videos) > 0 {
		patch["videos"] = videos
	}
	if len(patch) > 0 {
		p.store.Patch(sid, entryID, patch)
	}
}
func providerCrossSession(entry M) {
	text := S(entry, "text")
	start := strings.Index(text, "<cross-session-message")
	if start < 0 {
		return
	}
	headEnd := strings.Index(text[start:], ">")
	if headEnd < 0 {
		return
	}
	contentStart := start + headEnd + 1
	contentEnd := strings.Index(text[contentStart:], "</cross-session-message>")
	if contentEnd < 0 {
		return
	}
	header := text[start:contentStart]
	meta := M{}
	for attr, key := range map[string]string{"from-name": "senderName", "from-session": "senderSessionId", "from-mode": "senderMode", "from": "senderAddress"} {
		marker := attr + "=\""
		if i := strings.Index(header, marker); i >= 0 {
			rest := header[i+len(marker):]
			if j := strings.Index(rest, "\""); j >= 0 {
				meta[key] = rest[:j]
			}
		}
	}
	entry["text"] = strings.TrimSpace(text[contentStart : contentStart+contentEnd])
	entry["crossSession"] = meta
	entry["messageOrigin"] = "peer"
	entry["title"] = "Cross-session message"
}
func (p *nativeProvider) addPermission(sid string, w *providerWire, rawID any, method string, request M, tool string, input M) {
	if p.store.Get(sid) == nil {
		if w != nil {
			_ = w.reply(rawID, M{"behavior": "deny", "message": "Conversation is not attached"})
		}
		return
	}
	requestID := NewID()
	entry := M{"kind": "approval", "requestId": requestID, "status": "pending", "tool": tool, "title": tool, "detail": providerPretty(input), "text": "Allow this action?"}
	providerMerge(entry, p.describeClaudeTool(tool, input))
	entry["kind"] = "approval"
	questions := providerQuestionRows(input, p.name == "codex")
	if tool == "AskUserQuestion" || method == "item/tool/requestUserInput" {
		entry["kind"] = "question"
		entry["questions"] = questions
	} else if p.name == "claude" && tool == "ExitPlanMode" {
		entry["text"] = "Proceed with this plan?"
		entry["options"] = []M{
			{"id": "plan_accept_edits", "label": "Allow (Apply automatically)", "kind": "allow"},
			{"id": "plan_default", "label": "Allow (Ask every time)", "kind": "allowAlways"},
			{"id": "deny", "label": "Continue planning", "kind": "deny"},
		}
	} else {
		entry["options"] = []M{{"id": "allow", "label": "Allow", "kind": "allow"}, {"id": "deny", "label": "Reject", "kind": "deny"}}
		if p.name == "claude" && len(providerRows(request["permission_suggestions"])) > 0 {
			entry["options"] = []M{{"id": "allow", "label": "Allow", "kind": "allow"}, {"id": "allow_always", "label": "Always allow", "kind": "allowAlways"}, {"id": "deny", "label": "Reject", "kind": "deny"}}
		}
		if p.name == "codex" {
			entry["options"] = []M{{"id": "accept", "label": "Allow", "kind": "allow"}, {"id": "acceptForSession", "label": "Allow for this session", "kind": "allowAlways"}, {"id": "decline", "label": "Reject", "kind": "deny"}, {"id": "cancel", "label": "Reject and abort", "kind": "other"}}
		}
		if p.name == "antigravity" {
			if title := S(providerMap(request["toolCall"]), "title"); title != "" {
				entry["title"] = title
			}
			options := []M{}
			for _, o := range providerRows(request["options"]) {
				kind := "other"
				switch S(o, "kind") {
				case "allow_once":
					kind = "allow"
				case "allow_always":
					kind = "allowAlways"
				case "reject_once":
					kind = "deny"
				}
				id, label := S(o, "optionId"), S(o, "name")
				if label == "" {
					label = id
				}
				options = append(options, M{"id": id, "label": label, "kind": kind})
			}
			entry["options"] = options
		}
	}
	added := p.store.Add(sid, entry)
	p.mu.Lock()
	p.pending[requestID] = nativePending{sid: sid, entry: S(added, "id"), rawID: rawID, wire: w, method: method, tool: tool, input: input, request: request}
	count := 0
	for _, q := range p.pending {
		if q.sid == sid {
			count++
		}
	}
	p.mu.Unlock()
	p.store.Update(sid, M{"pending": count, "status": "waiting"}, false)
}
func (p *nativeProvider) respond(rec *Record, body M) error {
	requestID := S(body, "requestId")
	p.mu.Lock()
	q, ok := p.pending[requestID]
	if !ok || q.sid != S(rec.Info, "id") {
		p.mu.Unlock()
		return fmt.Errorf("Pending request not found")
	}
	delete(p.pending, requestID)
	count := 0
	for _, a := range p.pending {
		if a.sid == q.sid {
			count++
		}
	}
	p.mu.Unlock()
	choice := S(body, "choice")
	cancelled := choice == "__cancel"
	answers := providerMap(body["answers"])
	if q.method == agyQuestionMethod {
		return p.respondAgyQuestion(q, cancelled, answers, count)
	}
	planMode := ""
	if p.name == "claude" && q.tool == "ExitPlanMode" {
		switch choice {
		case "plan_accept_edits":
			planMode = "acceptEdits"
		case "plan_default":
			planMode = "default"
		}
	}
	allow := choice == "allow" || choice == "allow_always" || choice == "accept" || choice == "acceptForSession" || choice == "turn" || choice == "session" || planMode != "" || len(answers) > 0
	result := M{}
	switch p.name {
	case "claude":
		input := M{}
		providerMerge(input, q.input)
		if len(answers) > 0 {
			values := M{}
			for _, question := range providerQuestionRows(q.input, false) {
				values[S(question, "question")] = providerAnswerText(answers[S(question, "id")])
			}
			input["answers"] = values
		}
		result = M{"behavior": "deny", "message": "The user rejected this action.", "interrupt": cancelled}
		if allow {
			result = M{"behavior": "allow", "updatedInput": input}
			if planMode != "" {
				result["updatedPermissions"] = []M{{"type": "setMode", "mode": planMode, "destination": "session"}}
			} else if choice == "allow_always" {
				result["updatedPermissions"] = q.request["permission_suggestions"]
			}
		}
		if q.method == "hook_question" {
			decision := "deny"
			if allow {
				decision = "allow"
			}
			result = M{"hookSpecificOutput": M{"hookEventName": "PreToolUse", "permissionDecision": decision, "updatedInput": input}}
		}
	case "codex":
		if q.method == "item/tool/requestUserInput" {
			mapped := M{}
			for key, value := range answers {
				mapped[key] = M{"answers": providerAnswerList(value)}
			}
			result = M{"answers": mapped}
		} else if q.method == "item/permissions/requestApproval" {
			result = M{"permissions": M{}, "scope": "turn"}
			if allow {
				result["permissions"] = q.request["permissions"]
				if choice == "session" {
					result["scope"] = "session"
				}
			}
		} else {
			if choice == "allow" {
				choice = "accept"
			}
			if choice == "deny" || choice == "__cancel" || choice == "" {
				choice = "decline"
			}
			result = M{"decision": choice}
		}
	case "antigravity":
		if cancelled {
			result = M{"outcome": M{"outcome": "cancelled"}}
		} else {
			result = M{"outcome": M{"outcome": "selected", "optionId": choice}}
		}
	}
	var sendErr error
	if q.wire == nil {
		sendErr = fmt.Errorf("Agent connection was closed")
	} else {
		sendErr = q.wire.reply(q.rawID, result)
	}
	status := "resolved"
	if cancelled || sendErr != nil {
		status = "cancelled"
	}
	p.store.Patch(q.sid, q.entry, M{"status": status, "choice": choice, "answers": answers})
	state := "running"
	if current := p.store.Get(q.sid); current != nil {
		state = S(current.Info, "status")
		if state == "waiting" {
			state = "running"
		}
	}
	if cancelled || sendErr != nil {
		state = "idle"
	}
	if count > 0 {
		state = "waiting"
	}
	patch := M{"pending": count, "status": state}
	if sendErr == nil && planMode != "" {
		patch["mode"] = planMode
	}
	p.store.Update(q.sid, patch, false)
	return sendErr
}
func providerJSONText(s string) M { var m M; _ = json.Unmarshal([]byte(s), &m); return m }
