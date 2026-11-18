package host

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

type agyLogHub struct {
	p        *nativeProvider
	mu       sync.Mutex
	closed   bool
	children map[string]string
	tasks    map[string]string
	stops    map[string]context.CancelFunc
	reports  map[string]string
}

func (p *nativeProvider) agyHub() *agyLogHub {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.agyLogs == nil {
		p.agyLogs = &agyLogHub{p: p, children: map[string]string{}, tasks: map[string]string{}, stops: map[string]context.CancelFunc{}, reports: map[string]string{}}
	}
	return p.agyLogs
}
func (p *nativeProvider) closeAgyLogs() {
	p.mu.Lock()
	hub := p.agyLogs
	p.mu.Unlock()
	if hub == nil {
		return
	}
	hub.mu.Lock()
	hub.closed = true
	for _, stop := range hub.stops {
		stop()
	}
	hub.mu.Unlock()
}
func (p *nativeProvider) stopAgyLogs(sid string) {
	p.mu.Lock()
	hub := p.agyLogs
	p.mu.Unlock()
	if hub == nil {
		return
	}
	hub.mu.Lock()
	for key, stop := range hub.stops {
		if strings.HasPrefix(key, sid+"\x00") {
			stop()
			delete(hub.stops, key)
			delete(hub.children, key)
			delete(hub.tasks, key)
			delete(hub.reports, key)
		}
	}
	hub.mu.Unlock()
}

var agyChildID = regexp.MustCompile(`(?i)"conversationId"\s*:\s*"([a-f0-9-]{36})"`)
var agyChildURI = regexp.MustCompile(`(?i)"logAbsoluteUri"\s*:\s*"(file:///[^"]+)"`)
var agyBackgroundID = regexp.MustCompile(`(?i)running as a background task with task id:\s*(\S+)`)
var agyBackgroundURI = regexp.MustCompile(`(?i)Task logs are available at:\s*(file:///\S+)`)
var agyReport = regexp.MustCompile(`(?is)sender=([a-f0-9-]{36}(?:/[\w-]+)?)[^\n]*?content=(.*?)(?:</SYSTEM_MESSAGE>|$)`)
var agyTaskCode = regexp.MustCompile(`(?i)exited with code\s+(-?\d+)`)

func agyLogPath(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "file" || u.Host != "" && u.Host != "localhost" {
		return ""
	}
	path, err := url.PathUnescape(u.EscapedPath())
	if err != nil {
		return ""
	}
	if len(path) > 2 && path[0] == '/' && path[2] == ':' {
		path = path[1:]
	}
	path = filepath.FromSlash(path)
	if !filepath.IsAbs(path) {
		return ""
	}
	return path
}
func agyParseChild(raw string) (string, string) {
	m := agyChildID.FindStringSubmatch(raw)
	if m == nil {
		return "", ""
	}
	path := ""
	if uri := agyChildURI.FindStringSubmatch(raw); uri != nil {
		path = agyLogPath(uri[1])
	}
	return m[1], path
}
func agyParseBackground(raw string) (string, string, string) {
	m := agyBackgroundID.FindStringSubmatch(raw)
	if m == nil {
		return "", "", ""
	}
	path := ""
	if uri := agyBackgroundURI.FindStringSubmatch(raw); uri != nil {
		path = agyLogPath(uri[1])
	}
	output := ""
	if at := strings.Index(raw, "Log output:\n"); at >= 0 {
		output = raw[at+len("Log output:\n"):]
		if end := strings.Index(output, "\nYOU MUST"); end >= 0 {
			output = output[:end]
		}
	}
	return m[1], path, strings.TrimRight(strings.ReplaceAll(output, "\r\n", "\n"), "\n")
}
func agySubagent(name string, args M) bool {
	return strings.Contains(strings.ToLower(name), "invoke_subagent") || S(args, "Prompt") != "" && (args["TypeName"] != nil || args["Role"] != nil)
}
func (p *nativeProvider) agyObserveTool(sid, entryID, raw string) {
	if p.name != "antigravity" {
		return
	}
	hub := p.agyHub()
	rec := p.store.Get(sid)
	if rec == nil {
		return
	}
	agent := false
	for _, entry := range rec.Entries {
		if S(entry, "id") == entryID {
			agent = S(entry, "tool") == "Agent"
			break
		}
	}
	if agent {
		if child, path := agyParseChild(raw); child != "" {
			hub.linkChild(sid, entryID, child, path)
		}
	}
	if task, path, output := agyParseBackground(raw); task != "" {
		hub.startTask(sid, entryID, task, path, output)
	}
}
func (p *nativeProvider) agyObserveRecords(rec *Record, path string, messages []M) {
	if p.name != "antigravity" || rec == nil {
		return
	}
	sid := S(rec.Info, "id")
	pending := []string{}
	hub := p.agyHub()
	for _, row := range messages {
		step := N(row, "step_index")
		id := fmt.Sprintf("pc:%s:%.0f", S(rec.Info, "externalId"), step)
		kind := S(row, "type")
		if kind == "PLANNER_RESPONSE" {
			for i, call := range providerRows(row["tool_calls"]) {
				toolID := fmt.Sprintf("%s:tool:%d", id, i)
				if agySubagent(S(call, "name"), providerMap(call["args"])) {
					title := S(providerMap(call["args"]), "Role")
					if title == "" {
						title = S(providerMap(call["args"]), "TypeName")
					}
					for _, entry := range rec.Entries {
						if S(entry, "tool") == "Agent" && S(entry, "title") == title && S(entry, "detail") == S(providerMap(call["args"]), "Prompt") {
							toolID = S(entry, "id")
							break
						}
					}
				}
				pending = append(pending, toolID)
			}
			continue
		}
		if kind == "SYSTEM_MESSAGE" {
			hub.report(sid, S(row, "content"))
			continue
		}
		if kind == "USER_INPUT" {
			continue
		}
		if len(pending) > 0 {
			toolID := pending[0]
			pending = pending[1:]
			raw := S(row, "content")
			if child, log := agyParseChild(raw); child != "" {
				hub.linkChild(sid, toolID, child, log)
			}
			if task, log, output := agyParseBackground(raw); task != "" {
				hub.startTask(sid, toolID, task, log, output)
			}
		}
	}
	_ = path
}
func (h *agyLogHub) report(sid, raw string) {
	m := agyReport.FindStringSubmatch(raw)
	if m == nil {
		return
	}
	sender, content := m[1], strings.TrimSpace(m[2])
	key := sid + "\x00" + sender
	h.mu.Lock()
	if h.reports[key] == content {
		h.mu.Unlock()
		return
	}
	entryID := h.tasks[key]
	task := entryID != ""
	if !task {
		entryID = h.children[key]
	}
	if entryID != "" {
		h.reports[key] = content
	}
	stop := h.stops[key]
	if task {
		delete(h.tasks, key)
		delete(h.stops, key)
	}
	h.mu.Unlock()
	if entryID == "" {
		return
	}
	status := "done"
	if task {
		if code := agyTaskCode.FindStringSubmatch(content); code != nil && code[1] != "0" {
			status = "error"
		}
		if at := strings.Index(content, "Command output:\n"); at >= 0 {
			content = content[at+len("Command output:\n"):]
		}
	}
	h.p.store.Patch(sid, entryID, M{"status": status, "output": providerTruncate(content, 20000)})
	if stop != nil {
		if task {
			stop()
		} else {
			time.AfterFunc(20*time.Second, stop)
		}
	}
}
func (h *agyLogHub) linkChild(sid, entryID, child, path string) {
	key := sid + "\x00" + child
	h.mu.Lock()
	if h.closed || h.children[key] != "" || len(h.children) > 128 {
		h.mu.Unlock()
		return
	}
	h.children[key] = entryID
	ctx, stop := context.WithCancel(context.Background())
	h.stops[key] = stop
	h.mu.Unlock()
	h.p.store.Patch(sid, entryID, M{"status": "running"})
	go h.followChild(ctx, sid, entryID, child, path)
}
func (h *agyLogHub) startTask(sid, entryID, task, path, output string) {
	key := sid + "\x00" + task
	h.mu.Lock()
	if h.closed || h.tasks[key] != "" {
		h.mu.Unlock()
		return
	}
	h.tasks[key] = entryID
	ctx, stop := context.WithCancel(context.Background())
	h.stops[key] = stop
	h.mu.Unlock()
	patch := M{"status": "running"}
	if output != "" {
		patch["output"] = providerTruncate(output, 20000)
	}
	h.p.store.Patch(sid, entryID, patch)
	if path == "" {
		return
	}
	go func() {
		tick := time.NewTicker(700 * time.Millisecond)
		defer tick.Stop()
		last := int64(-1)
		for {
			select {
			case <-ctx.Done():
				return
			case <-tick.C:
				f, err := os.Open(path)
				if err != nil {
					continue
				}
				st, err := f.Stat()
				if err != nil || !st.Mode().IsRegular() || st.Size() == last {
					_ = f.Close()
					continue
				}
				last = st.Size()
				_, _ = f.Seek(max(0, st.Size()-20000), io.SeekStart)
				bytes, err := io.ReadAll(io.LimitReader(f, 20000))
				_ = f.Close()
				if err == nil {
					h.p.store.Patch(sid, entryID, M{"output": strings.ToValidUTF8(strings.TrimRight(strings.ReplaceAll(string(bytes), "\r\n", "\n"), "\n"), "�")})
				}
			}
		}
	}()
}
func (h *agyLogHub) followChild(ctx context.Context, sid, parent, child, path string) {
	var offset int64
	rest := ""
	pending := []string{}
	seen := map[int64]bool{}
	tick := time.NewTicker(2 * time.Second)
	defer tick.Stop()
	read := func() {
		if path == "" {
			path = h.p.transcriptPath(child)
		}
		if path == "" {
			return
		}
		file, err := os.Open(path)
		if err != nil {
			return
		}
		defer file.Close()
		st, err := file.Stat()
		if err != nil || !st.Mode().IsRegular() {
			return
		}
		if st.Size() < offset {
			offset = 0
			rest = ""
			seen = map[int64]bool{}
			pending = nil
		}
		if st.Size() == offset {
			return
		}
		_, err = file.Seek(offset, io.SeekStart)
		if err != nil {
			return
		}
		raw, err := io.ReadAll(io.LimitReader(file, 8*1024*1024))
		if err != nil {
			return
		}
		offset += int64(len(raw))
		lines := strings.Split(rest+string(raw), "\n")
		rest = lines[len(lines)-1]
		for _, line := range lines[:len(lines)-1] {
			var row M
			if json.Unmarshal([]byte(line), &row) != nil {
				continue
			}
			step := int64(N(row, "step_index"))
			if seen[step] {
				continue
			}
			seen[step] = true
			id := fmt.Sprintf("agyc:%s:%d", child, step)
			ts := providerStamp(row["created_at"])
			add := func(entry M) { entry["parent"] = parent; entry["ts"] = ts; h.p.store.Add(sid, entry) }
			switch S(row, "type") {
			case "USER_INPUT":
				continue
			case "SYSTEM_MESSAGE":
				h.report(sid, S(row, "content"))
			case "PLANNER_RESPONSE":
				if text := S(row, "thinking"); text != "" {
					add(M{"id": id + ":thought", "kind": "thinking", "text": text})
				}
				if text := S(row, "content"); text != "" {
					add(M{"id": id, "kind": "assistant", "text": text})
					h.p.store.Patch(sid, parent, M{"progress": providerTruncate(text, 180)})
				}
				for i, call := range providerRows(row["tool_calls"]) {
					toolID := fmt.Sprintf("%s:tool:%d", id, i)
					name := S(call, "name")
					args := providerMap(call["args"])
					entry := M{"id": toolID, "kind": "tool", "tool": name, "title": name, "detail": providerPretty(args), "status": "running"}
					if agySubagent(name, args) {
						entry["tool"] = "Agent"
						entry["title"] = S(args, "Role")
						if S(entry, "title") == "" {
							entry["title"] = S(args, "TypeName")
						}
						entry["detail"] = S(args, "Prompt")
					}
					if name == "send_message" && S(args, "Message") != "" {
						h.p.store.Patch(sid, parent, M{"progress": providerTruncate(S(args, "Message"), 180)})
					}
					add(entry)
					pending = append(pending, toolID)
				}
			default:
				content := S(row, "content")
				status := "done"
				if S(row, "status") == "ERROR" {
					status = "error"
				}
				if len(pending) > 0 {
					toolID := pending[0]
					pending = pending[1:]
					if childID, log := agyParseChild(content); childID != "" {
						h.linkChild(sid, toolID, childID, log)
					} else {
						h.p.store.Patch(sid, toolID, M{"status": status, "output": providerTruncate(content, 20000)})
					}
					if task, log, out := agyParseBackground(content); task != "" {
						h.startTask(sid, toolID, task, log, out)
					}
				} else if content != "" {
					add(M{"id": id, "kind": "tool", "tool": strings.ToLower(S(row, "type")), "output": providerTruncate(content, 20000), "status": status})
				}
			}
		}
	}
	for {
		read()
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
			if h.p.store.Get(sid) == nil {
				return
			}
		}
	}
}
func (p *nativeProvider) agyBackgroundEntry(sid, id string) bool {
	p.mu.Lock()
	hub := p.agyLogs
	p.mu.Unlock()
	if hub == nil {
		return false
	}
	hub.mu.Lock()
	defer hub.mu.Unlock()
	for key, entry := range hub.tasks {
		if strings.HasPrefix(key, sid+"\x00") && entry == id {
			return true
		}
	}
	return false
}

func (p *nativeProvider) watchAgyRoot(sid, external string) {
	if p.name != "antigravity" || external == "" {
		return
	}
	hub := p.agyHub()
	key := sid + "\x00root:" + external
	hub.mu.Lock()
	if hub.closed || hub.stops[key] != nil {
		hub.mu.Unlock()
		return
	}
	ctx, stop := context.WithCancel(context.Background())
	hub.stops[key] = stop
	hub.mu.Unlock()
	go func() {
		tick := time.NewTicker(2 * time.Second)
		defer tick.Stop()
		var lastSize int64 = -1
		var lastTime int64
		for {
			rec := p.store.Get(sid)
			if rec == nil {
				return
			}
			path := p.transcriptPath(external)
			if path != "" {
				file, err := os.Open(path)
				if err == nil {
					st, statErr := file.Stat()
					if statErr == nil && st.Mode().IsRegular() && (st.Size() != lastSize || st.ModTime().UnixNano() != lastTime) {
						lastSize, lastTime = st.Size(), st.ModTime().UnixNano()
						start := max(0, st.Size()-8*1024*1024)
						_, _ = file.Seek(start, io.SeekStart)
						raw, readErr := io.ReadAll(io.LimitReader(file, 8*1024*1024))
						if readErr == nil {
							rows := []M{}
							lines := strings.Split(string(raw), "\n")
							if start > 0 && len(lines) > 0 {
								lines = lines[1:]
							}
							for _, line := range lines {
								var row M
								if json.Unmarshal([]byte(line), &row) == nil {
									rows = append(rows, row)
								}
							}
							p.agyObserveRecords(rec, path, rows)
						}
					}
					_ = file.Close()
				}
			}
			select {
			case <-ctx.Done():
				return
			case <-tick.C:
			}
		}
	}()
}
