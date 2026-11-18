package host

import (
	"bufio"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

func providerHome(name string) string {
	home, _ := os.UserHomeDir()
	if name == "claude" {
		if dir := os.Getenv("CLAUDE_CONFIG_DIR"); dir != "" {
			return dir
		}
		return filepath.Join(home, ".claude")
	}
	if name == "codex" {
		if dir := os.Getenv("CODEX_HOME"); dir != "" {
			return dir
		}
		return filepath.Join(home, ".codex")
	}
	return filepath.Join(home, ".gemini")
}
func (p *nativeProvider) listHistory(ctx context.Context) ([]M, error) {
	p.historyMu.Lock()
	defer p.historyMu.Unlock()
	if p.history != nil && time.Now().Before(p.historyUntil) {
		rows := clone(p.history)
		if p.name == "claude" {
			p.updateClaudeHistoryActivity(ctx, rows)
		}
		return rows, nil
	}
	rows := []M{}
	if p.name == "claude" {
		root := filepath.Join(providerHome("claude"), "projects")
		dirs, err := os.ReadDir(root)
		if err != nil {
			if os.IsNotExist(err) {
				return rows, nil
			}
			return nil, err
		}
		type file struct {
			path string
			at   int64
		}
		files := []file{}
		for _, dir := range dirs {
			if !dir.IsDir() {
				continue
			}
			entries, e := os.ReadDir(filepath.Join(root, dir.Name()))
			if e != nil {
				continue
			}
			for _, entry := range entries {
				if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".jsonl") {
					continue
				}
				info, e := entry.Info()
				if e == nil {
					files = append(files, file{filepath.Join(root, dir.Name(), entry.Name()), info.ModTime().UnixMilli()})
				}
			}
		}
		sort.Slice(files, func(i, j int) bool { return files[i].at > files[j].at })
		if len(files) > 200 {
			files = files[:200]
		}
		for _, f := range files {
			row := p.claudeHistoryRow(f.path, f.at)
			if row != nil {
				rows = append(rows, row)
			}
		}
	} else {
		w, err := p.ensureRPC(ctx)
		if err != nil {
			return nil, err
		}
		method := "thread/list"
		params := M{"limit": 200, "sortKey": "updated_at", "useStateDbOnly": true}
		if p.name == "antigravity" {
			method = "session/list"
			params = M{}
		}
		res, err := w.request(ctx, method, params)
		if err != nil {
			return nil, err
		}
		data := providerRows(res["data"])
		if p.name == "antigravity" {
			data = providerRows(res["sessions"])
		}
		for _, s := range data {
			id := S(s, "id")
			if p.name == "antigravity" {
				id = S(s, "sessionId")
			}
			if id == "" {
				continue
			}
			title := S(s, "title")
			if title == "" {
				title = S(s, "name")
			}
			if title == "" {
				title = S(s, "preview")
			}
			if title == "" {
				title = "(Untitled)"
			}
			rows = append(rows, M{"externalId": id, "title": providerTruncate(title, 160), "cwd": S(s, "cwd"), "updatedAt": providerStamp(s["updatedAt"]), "preview": S(s, "preview"), "sessionId": nil, "live": false, "running": S(providerMap(s["status"]), "type") == "active"})
		}
	}
	if p.name != "claude" {
		for _, row := range rows {
			if rec := p.store.FindExternal(p.name, S(row, "externalId")); rec != nil {
				row["sessionId"] = rec.Info["id"]
				row["live"] = B(rec.Info, "live")
				row["running"] = S(rec.Info, "status") == "running" || S(rec.Info, "status") == "waiting"
			}
		}
	}
	sort.Slice(rows, func(i, j int) bool { return N(rows[i], "updatedAt") > N(rows[j], "updatedAt") })
	p.history = rows
	p.historyUntil = time.Now().Add(10 * time.Second)
	rows = clone(rows)
	if p.name == "claude" {
		p.updateClaudeHistoryActivity(ctx, rows)
	}
	return rows, nil
}

func (p *nativeProvider) updateClaudeHistoryActivity(ctx context.Context, rows []M) {
	if len(rows) == 0 {
		return
	}
	p.claudePC.mu.RLock()
	agents := p.claudePC.agents
	p.claudePC.mu.RUnlock()
	if agents == nil {
		agents, _ = p.loadClaudePCAgents(ctx, false)
	} else {
		p.claudePC.refresh(context.WithoutCancel(ctx))
	}
	for _, row := range rows {
		external := S(row, "externalId")
		if rec := p.store.FindExternal(p.name, external); rec != nil {
			row["sessionId"] = rec.Info["id"]
			row["live"] = B(rec.Info, "live")
			row["running"] = S(rec.Info, "status") == "running" || S(rec.Info, "status") == "waiting"
		}
		if agent, ok := agents[external]; ok {
			row["live"] = true
			row["running"] = B(row, "running") || agent.status != "idle"
		}
	}
}
func (p *nativeProvider) claudeHistoryRow(path string, at int64) M {
	file, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer file.Close()
	id := strings.TrimSuffix(filepath.Base(path), ".jsonl")
	row := M{"externalId": id, "title": "(Untitled)", "cwd": "", "updatedAt": at, "preview": "", "sessionId": nil, "live": false, "running": false}
	read := func(reader io.Reader) {
		scanner := bufio.NewScanner(reader)
		scanner.Buffer(make([]byte, 4096), 2*1024*1024)
		for scanner.Scan() {
			var msg M
			if json.Unmarshal(scanner.Bytes(), &msg) != nil {
				continue
			}
			if cwd := S(msg, "cwd"); cwd != "" {
				row["cwd"] = cwd
			}
			if title := S(msg, "customTitle"); title != "" {
				row["title"] = title
			}
			if S(row, "title") == "(Untitled)" && S(msg, "summary") != "" {
				row["title"] = providerTruncate(S(msg, "summary"), 160)
			}
			if S(msg, "type") == "user" && !B(msg, "isMeta") {
				text := providerText(providerMap(msg["message"])["content"])
				if text != "" && S(row, "preview") == "" {
					row["preview"] = providerTruncate(text, 140)
					if S(row, "title") == "(Untitled)" {
						row["title"] = providerTruncate(text, 100)
					}
				}
			}
		}
	}
	read(io.LimitReader(file, 256*1024))
	if stat, e := file.Stat(); e == nil && stat.Size() > 256*1024 {
		_, _ = file.Seek(max(0, stat.Size()-128*1024), 0)
		read(file)
	}
	return row
}
func (p *nativeProvider) transcriptPath(external string) string {
	if external == "" || filepath.Base(external) != external {
		return ""
	}
	if p.name == "claude" {
		dirs, _ := os.ReadDir(filepath.Join(providerHome("claude"), "projects"))
		for _, dir := range dirs {
			path := filepath.Join(providerHome("claude"), "projects", dir.Name(), external+".jsonl")
			if info, e := os.Stat(path); e == nil && !info.IsDir() {
				return path
			}
		}
	} else if p.name == "antigravity" {
		for _, folder := range []string{"antigravity", "antigravity-ide", "antigravity-cli"} {
			path := filepath.Join(providerHome("antigravity"), folder, "brain", external, ".system_generated", "logs", "transcript.jsonl")
			if _, e := os.Stat(path); e == nil {
				return path
			}
		}
	}
	return ""
}
func (p *nativeProvider) importHistory(ctx context.Context, rec *Record) (bool, error) {
	if S(rec.Info, "externalId") == "" {
		return false, nil
	}
	sid := S(rec.Info, "id")
	if p.name == "codex" {
		w, err := p.ensureRPC(ctx)
		if err != nil {
			return false, err
		}
		thread, turns, err := p.readCodexHistory(ctx, w, S(rec.Info, "externalId"))
		if err != nil {
			return false, err
		}
		patch := M{}
		if cwd := S(thread, "cwd"); cwd != "" {
			patch["cwd"] = cwd
		}
		if !B(rec.Info, "titlePinned") {
			if title := S(thread, "name"); title != "" {
				patch["title"] = title
			}
		}
		p.store.Update(sid, patch, false)
		for _, turn := range turns {
			for _, item := range providerRows(turn["items"]) {
				p.codexItem(sid, item, S(turn, "status") != "inProgress", "")
			}
		}
		_, err = p.ensure(ctx, rec)
		return err == nil, err
	}
	path := p.transcriptPath(S(rec.Info, "externalId"))
	if path == "" {
		if p.name == "antigravity" {
			_, err := p.ensure(ctx, rec)
			return false, err
		}
		return false, errors.New("Claude conversation transcript was not found")
	}
	if err := p.readTranscript(rec, path); err != nil {
		return false, err
	}
	if p.name == "claude" {
		active, err := p.claudeActivePC(ctx, S(rec.Info, "externalId"))
		if active || B(rec.Info, "watching") {
			p.watchTranscript(rec, path)
			return true, nil
		}
		return false, err
	}
	if info, err := os.Stat(path); err == nil && time.Since(info.ModTime()) < 5*time.Minute {
		p.watchTranscript(rec, path)
		return true, nil
	}
	return false, nil
}
func (p *nativeProvider) readCodexHistory(ctx context.Context, w *providerWire, id string) (M, []M, error) {
	meta, err := w.request(ctx, "thread/read", M{"threadId": id, "includeTurns": false})
	if err != nil {
		return nil, nil, err
	}
	thread := providerMap(meta["thread"])
	if len(thread) == 0 {
		return nil, nil, errors.New("Codex conversation history not found")
	}
	turns := providerRows(thread["turns"])
	if len(turns) > 0 && S(thread, "historyMode") != "paginated" {
		if len(turns) > 60 {
			turns = turns[len(turns)-60:]
		}
		return thread, turns, nil
	}
	page, err := w.request(ctx, "thread/turns/list", M{"threadId": id, "limit": 60, "sortDirection": "desc", "itemsView": "full"})
	if err != nil {
		if !providerUnsupported(err) {
			return nil, nil, err
		}
		all, e := w.request(ctx, "thread/read", M{"threadId": id, "includeTurns": true})
		if e != nil {
			return nil, nil, e
		}
		turns = providerRows(providerMap(all["thread"])["turns"])
		if len(turns) > 60 {
			turns = turns[len(turns)-60:]
		}
		return thread, turns, nil
	}
	turns = providerRows(page["data"])
	for i, j := 0, len(turns)-1; i < j; i, j = i+1, j-1 {
		turns[i], turns[j] = turns[j], turns[i]
	}
	for _, turn := range turns {
		if S(turn, "itemsView") == "notLoaded" || turn["items"] == nil {
			items := []M{}
			cursor := ""
			seen := map[string]bool{}
			for {
				params := M{"threadId": id, "turnId": S(turn, "id"), "limit": 200, "sortDirection": "desc"}
				if cursor != "" {
					params["cursor"] = cursor
				}
				res, e := w.request(ctx, "thread/items/list", params)
				if e != nil {
					return nil, nil, e
				}
				for _, row := range providerRows(res["data"]) {
					item := providerMap(row["item"])
					if len(item) > 0 {
						items = append(items, item)
					}
				}
				cursor = S(res, "nextCursor")
				if cursor == "" || len(items) >= 3000 {
					break
				}
				if seen[cursor] {
					return nil, nil, errors.New("Codex history pagination did not advance")
				}
				seen[cursor] = true
			}
			for i, j := 0, len(items)-1; i < j; i, j = i+1, j-1 {
				items[i], items[j] = items[j], items[i]
			}
			turn["items"] = items
		}
	}
	return thread, turns, nil
}
func providerUnsupported(err error) bool {
	if err == nil {
		return false
	}
	s := strings.ToLower(err.Error())
	return strings.Contains(s, "method not found") || strings.Contains(s, "unknown method") || strings.Contains(s, "unknown variant") || strings.Contains(s, "not supported") || strings.Contains(s, "unrecognized")
}
func (p *nativeProvider) readTranscript(rec *Record, path string) error {
	p.transcriptMu.Lock()
	defer p.transcriptMu.Unlock()
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()
	key := S(rec.Info, "id") + "\x00" + path
	if p.transcriptSeen == nil {
		p.transcriptSeen = map[string]map[string]bool{}
		p.transcriptSizes = map[string]int64{}
	}
	info, err := file.Stat()
	if err != nil {
		return err
	}
	if info.Size() < p.transcriptSizes[key] {
		delete(p.transcriptSeen, key)
	}
	p.transcriptSizes[key] = info.Size()
	seen := p.transcriptSeen[key]
	if seen == nil {
		seen = map[string]bool{}
		p.transcriptSeen[key] = seen
	}
	partial := false
	if info.Size() > 32*1024*1024 {
		_, err = file.Seek(info.Size()-32*1024*1024, io.SeekStart)
		if err != nil {
			return err
		}
		partial = true
	}
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 64*1024), 32*1024*1024)
	messages := []M{}
	recent := map[string]bool{}
	for scanner.Scan() {
		if partial {
			partial = false
			continue
		}
		var m M
		if json.Unmarshal(scanner.Bytes(), &m) == nil {
			hash := fmt.Sprintf("%x", sha256.Sum256(scanner.Bytes()))
			m["_transcript_hash"] = hash
			recent[hash] = true
			messages = append(messages, m)
			if len(messages) > 6000 {
				messages = messages[1000:]
			}
		}
	}
	if err = scanner.Err(); err != nil {
		return err
	}
	defer func() {
		for hash := range recent {
			seen[hash] = true
		}
		if len(seen) > 15000 {
			p.transcriptSeen[key] = recent
		}
	}()
	sid := S(rec.Info, "id")
	if p.name == "claude" {
		byID := map[string]M{}
		last := ""
		for _, m := range messages {
			if id := S(m, "uuid"); id != "" {
				byID[id] = m
				if !B(m, "isSidechain") && (S(m, "type") == "user" || S(m, "type") == "assistant" || S(m, "type") == "system") {
					last = id
				}
			}
		}
		chain := map[string]bool{}
		for id := last; id != "" && !chain[id]; {
			chain[id] = true
			m := byID[id]
			id = S(m, "parentUuid")
			if id == "" {
				id = S(m, "logicalParentUuid")
			}
		}
		p.readClaudeTranscript(rec, messages, chain, seen)
		return nil
	}
	pending := []string{}
	for _, m := range messages {
		id := fmt.Sprintf("pc:%s:%.0f", S(rec.Info, "externalId"), N(m, "step_index"))
		ts := providerStamp(m["created_at"])
		switch S(m, "type") {
		case "USER_INPUT":
			if seen[S(m, "_transcript_hash")] {
				continue
			}
			text := S(m, "content")
			if start := strings.Index(text, "<USER_REQUEST>"); start >= 0 {
				rest := text[start+len("<USER_REQUEST>"):]
				if end := strings.Index(rest, "</USER_REQUEST>"); end >= 0 {
					text = rest[:end]
				}
			}
			p.store.Add(sid, M{"id": id, "ts": ts, "kind": "user", "text": strings.TrimSpace(text), "title": "From PC"})
		case "SYSTEM_MESSAGE":
			continue
		case "PLANNER_RESPONSE":
			known := seen[S(m, "_transcript_hash")]
			if thought := S(m, "thinking"); thought != "" {
				if !known {
					p.store.Add(sid, M{"id": id + ":thought", "ts": ts, "kind": "thinking", "text": thought})
				}
			}
			if text := S(m, "content"); text != "" {
				if !known {
					p.store.Add(sid, M{"id": id, "ts": ts, "kind": "assistant", "text": text})
				}
			}
			for i, tc := range providerRows(m["tool_calls"]) {
				toolID := fmt.Sprintf("%s:tool:%d", id, i)
				entry := M{"id": toolID, "ts": ts, "kind": "tool", "tool": S(tc, "name"), "title": S(tc, "name"), "detail": providerPretty(tc["args"]), "status": "running"}
				if agySubagent(S(tc, "name"), providerMap(tc["args"])) {
					entry["tool"] = "Agent"
					entry["title"] = S(providerMap(tc["args"]), "Role")
					if S(entry, "title") == "" {
						entry["title"] = S(providerMap(tc["args"]), "TypeName")
					}
					entry["detail"] = S(providerMap(tc["args"]), "Prompt")
				}
				if !known {
					p.store.Add(sid, entry)
				}
				pending = append(pending, toolID)
			}
		default:
			if len(pending) > 0 {
				status := "done"
				if S(m, "status") == "ERROR" {
					status = "error"
				}
				if child, _ := agyParseChild(S(m, "content")); child != "" && status != "error" {
					status = "running"
				}
				if task, _, _ := agyParseBackground(S(m, "content")); task != "" {
					status = "running"
				}
				if !seen[S(m, "_transcript_hash")] {
					p.store.Patch(sid, pending[0], M{"status": status, "output": S(m, "content")})
				}
				pending = pending[1:]
			}
		}
	}
	p.agyObserveRecords(p.store.Get(sid), path, messages)
	return nil
}

func (p *nativeProvider) readClaudeTranscript(rec *Record, messages []M, chain, seen map[string]bool) {
	sid := S(rec.Info, "id")
	var savedAt float64
	saved := map[string]M{}
	if len(seen) == 0 {
		if current := p.store.Get(sid); current != nil {
			for _, entry := range current.Entries {
				saved[S(entry, "id")] = entry
				if S(entry, "kind") == "user" || S(entry, "kind") == "assistant" {
					savedAt = max(savedAt, N(entry, "ts"))
				}
			}
		}
	}
	rows := make([]M, 0, len(messages))
	owners := map[string]string{}
	lastSaved, lastBeforeSaved := -1, -1
	repairs := map[string]transcriptUserRepair{}
	skillEntries := []M{}
	for _, m := range messages {
		typ := S(m, "type")
		uuid := S(m, "uuid")
		if typ != "user" && typ != "assistant" || uuid != "" && len(chain) > 0 && !chain[uuid] {
			continue
		}
		if typ == "user" {
			m = p.claudeSkillMessage(sid, m)
		}
		if B(m, "isMeta") && !claudeHasInjectedSkill(m) {
			continue
		}
		if typ == "user" {
			for _, entry := range claudeUserTextEntries(m, true) {
				if S(entry, "kind") == "skill" {
					entry["ts"] = providerStamp(m["timestamp"])
					skillEntries = append(skillEntries, entry)
				}
			}
		}
		message := providerMap(m["message"])
		blocks := providerRows(message["content"])
		matched := typ == "user" && (saved[uuid] != nil || saved[uuid+":img"] != nil)
		if typ == "user" && uuid != "" && len(saved) > 0 {
			for i, block := range blocks {
				if S(block, "type") == "text" && saved[fmt.Sprintf("%s:%d", uuid, i)] != nil {
					matched = true
				}
			}
			if before := saved[uuid]; before != nil {
				if repair, ok := p.claudeSavedUserRepair(sid, m, before); ok {
					repairs[uuid] = repair
				}
			}
		}
		if typ == "assistant" {
			ids := make([]string, len(blocks))
			for i, block := range blocks {
				kind, text := claudeTranscriptBlock(block)
				if kind == "" {
					continue
				}
				legacyID := fmt.Sprintf("%s:%d", uuid, i)
				canonicalID := fmt.Sprintf("%s-%d", S(message, "id"), i)
				id := canonicalID
				if kind == "tool" {
					id = S(block, "id")
				} else if uuid != "" {
					// A transcript can put different blocks from one API message in
					// separate rows, reusing both its message ID and block index.
					if S(message, "id") == "" || (owners[id] != "" && owners[id] != uuid) {
						id = legacyID
					} else {
						owners[id] = uuid
					}
				}
				ids[i] = id
				entry := saved[id]
				if kind != "tool" && uuid != "" && saved[legacyID] != nil {
					matched = true
				}
				if kind != "tool" && entry == nil {
					entry = saved[canonicalID]
				}
				if entry != nil && (kind == "tool" || S(entry, "kind") == kind && S(entry, "text") == text) {
					matched = true
				}
			}
			m["_transcript_entry_ids"] = ids
		}
		if matched {
			lastSaved = len(rows)
		}
		if savedAt > 0 && S(m, "timestamp") != "" && float64(providerStamp(m["timestamp"])) <= savedAt {
			lastBeforeSaved = len(rows)
		}
		rows = append(rows, m)
	}
	if lastSaved < 0 {
		// Older installations did not assign stable IDs to every imported assistant row.
		lastSaved = lastBeforeSaved
	}
	p.store.repairTranscriptUserEntries(sid, repairs)
	r := &nativeSession{external: S(rec.Info, "externalId"), parents: map[string]string{}, blocks: map[int]string{}}
	for _, m := range rows[lastSaved+1:] {
		if !seen[S(m, "_transcript_hash")] {
			p.claudeMessage(sid, r, m)
		}
	}
	p.store.repairClaudeSkillEntries(sid, skillEntries)
}

func (p *nativeProvider) claudeSavedUserRepair(sid string, msg, before M) (transcriptUserRepair, bool) {
	if S(before, "kind") != "user" || S(before, "title") != "From PC" || S(before, "messageOrigin") == "peer" {
		return transcriptUserRepair{}, false
	}
	content := providerMap(msg["message"])["content"]
	oldRendering := M{"kind": "user", "text": providerText(content)}
	cleanClaudePCMessage(oldRendering)
	if S(oldRendering, "kind") != "user" || S(oldRendering, "text") != stripClaudeImageResizeNotice(S(before, "text")) {
		return transcriptUserRepair{}, false
	}
	repair := transcriptUserRepair{original: S(before, "text")}
	parts := claudeUserTextEntries(msg, true)
	if len(parts) > 1 && S(parts[0], "id") == S(before, "id") {
		repair.parts = parts
		for _, part := range parts {
			if S(part, "kind") != "user" || S(part, "messageOrigin") == "peer" {
				repair.parts = nil
				break
			}
		}
	}
	keepImages, keepVideos := claudeHasReferences(before["media"]), claudeHasReferences(before["videos"])
	if !keepImages || !keepVideos {
		media, videos := p.claudeBlockMedia(sid, providerRows(content))
		if !keepImages {
			repair.media = media
		}
		if !keepVideos {
			repair.videos = videos
		}
	}
	return repair, len(repair.parts) > 1 || len(repair.media) > 0 || len(repair.videos) > 0
}

func claudeTranscriptBlock(block M) (kind, text string) {
	switch S(block, "type") {
	case "text":
		return "assistant", S(block, "text")
	case "thinking":
		return "thinking", S(block, "thinking")
	case "tool_use":
		return "tool", ""
	default:
		return "", ""
	}
}

func (p *nativeProvider) watchTranscript(rec *Record, path string) {
	sid := S(rec.Info, "id")
	p.mu.Lock()
	if p.followers == nil {
		p.followers = map[string]context.CancelFunc{}
	}
	if p.followers[sid] != nil {
		p.mu.Unlock()
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	p.followers[sid] = cancel
	patch := M{"live": true, "watching": true}
	if p.name == "claude" {
		if agent, active, known := p.claudePC.activity(S(rec.Info, "externalId")); known {
			patch["live"], patch["status"] = active, "idle"
			if active {
				patch["status"] = agent.status
			}
		}
	}
	p.store.Update(sid, patch, false)
	p.mu.Unlock()
	go func() {
		defer func() {
			p.mu.Lock()
			defer p.mu.Unlock()
			if ctx.Err() == nil {
				delete(p.followers, sid)
				cancel()
			}
		}()
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		var bridge claudeBridgeWatch
		external := S(rec.Info, "externalId")
		var grew time.Time
		var size int64 = -1
		var stamp time.Time
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if runtime := p.session(sid); runtime != nil && !runtime.Remote() {
					return
				}
				current := p.store.Get(sid)
				if current == nil || !B(current.Info, "watching") || S(current.Info, "externalId") != external {
					return
				}
				info, err := os.Stat(path)
				if err == nil && (size != info.Size() || !stamp.Equal(info.ModTime())) {
					if size >= 0 && size < info.Size() {
						grew = time.Now()
					}
					size, stamp = info.Size(), info.ModTime()
					if p.name == "claude" {
						_ = bridge.read(path, info)
					}
					_ = p.readTranscript(current, path)
				}
				if p.name != "claude" {
					continue
				}
				p.claudePC.refresh(ctx)
				if next := bridge.successor(path, time.Now()); next != "" {
					newExternal := strings.TrimSuffix(filepath.Base(next), ".jsonl")
					if !p.switchClaudeWatch(ctx, sid, external, newExternal) {
						return
					}
					path, external, size, bridge = next, newExternal, -1, claudeBridgeWatch{}
					grew = time.Now()
				}
				p.updateClaudeWatchActivity(ctx, sid, external, grew)
			}
		}
	}()
}

func (p *nativeProvider) stopTranscriptFollower(sid string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if cancel := p.followers[sid]; cancel != nil {
		cancel()
		delete(p.followers, sid)
	}
}

func (p *nativeProvider) updateClaudeWatchActivity(ctx context.Context, sid, external string, grew time.Time) {
	agent, active, known := p.claudePC.activity(external)
	recent := time.Since(grew) < 3*time.Second
	if !known && !recent {
		return
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if ctx.Err() != nil || p.closed || p.followers[sid] == nil {
		return
	}
	if runtime := p.sessions[sid]; runtime != nil && !runtime.Remote() {
		return
	}
	rec := p.store.Get(sid)
	if rec == nil || !B(rec.Info, "watching") || S(rec.Info, "externalId") != external {
		return
	}
	live, status := B(rec.Info, "live"), "idle"
	if known {
		live = active
		if active {
			status = agent.status
		}
	}
	if status == "idle" && recent {
		status = "running"
	}
	if N(rec.Info, "pending") > 0 {
		status = "waiting"
	}
	if live != B(rec.Info, "live") || status != S(rec.Info, "status") {
		p.store.Update(sid, M{"live": live, "status": status}, status == "idle")
	}
}

func (p *nativeProvider) switchClaudeWatch(ctx context.Context, sid, oldExternal, newExternal string) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	if ctx.Err() != nil || p.closed || p.followers[sid] == nil {
		return false
	}
	rec := p.store.Get(sid)
	if rec == nil || !B(rec.Info, "watching") || S(rec.Info, "externalId") != oldExternal {
		return false
	}
	if rt := p.sessions[sid]; rt != nil {
		if !rt.Remote() {
			return false
		}
		if p.byExternal[oldExternal] == sid {
			delete(p.byExternal, oldExternal)
		}
		p.byExternal[newExternal] = sid
		rt.SetExternal(newExternal)
	}
	p.store.Update(sid, M{"externalId": newExternal, "context": nil, "stats": nil, "plan": nil}, false)
	p.store.Add(sid, M{"kind": "system", "level": "info", "text": "/clear was executed on PC. Starting a new conversation from here"})
	return true
}
