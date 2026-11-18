package host

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

type terminalTabCall struct{ kind, command string }

func terminalResultText(value any) string {
	if s, ok := value.(string); ok {
		return s
	}
	var out strings.Builder
	for _, row := range maps(value) {
		out.WriteString(S(row, "text"))
	}
	return out.String()
}
func terminalTabClues(path, tab string) ([]string, string) {
	file, e := os.Open(path)
	if e != nil {
		return nil, ""
	}
	defer file.Close()
	tail := false
	if info, e := file.Stat(); e == nil && info.Size() > 32<<20 {
		file.Seek(-(32 << 20), io.SeekEnd)
		tail = true
	}
	reader := bufio.NewReader(file)
	if tail {
		reader.ReadBytes('\n')
	}
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 32768), 8<<20)
	calls := map[string]terminalTabCall{}
	commands := []string{}
	screen := ""
	tabPattern := regexp.MustCompile(`"tab_id"\s*:\s*"([^"]+)"`)
	for scanner.Scan() {
		line := scanner.Bytes()
		var row M
		if json.Unmarshal(line, &row) != nil {
			continue
		}
		message, _ := row["message"].(map[string]any)
		for _, block := range maps(message["content"]) {
			kind := S(block, "type")
			id := S(block, "id")
			if kind == "tool_use" {
				name := S(block, "name")
				input, _ := block["input"].(map[string]any)
				if strings.HasSuffix(name, "run_in_terminal") && S(input, "command") != "" {
					calls[id] = terminalTabCall{"run", S(input, "command")}
				} else if S(input, "tab_id") == tab {
					if strings.HasSuffix(name, "read_terminal") {
						calls[id] = terminalTabCall{kind: "read"}
					} else if strings.HasSuffix(name, "stop_terminal_tab") {
						calls[id] = terminalTabCall{kind: "stop"}
					}
				}
				continue
			}
			if kind != "tool_result" || B(block, "is_error") {
				continue
			}
			call, ok := calls[S(block, "tool_use_id")]
			if !ok {
				continue
			}
			body := terminalResultText(block["content"])
			switch call.kind {
			case "run":
				match := tabPattern.FindStringSubmatch(body)
				if len(match) > 1 && match[1] == tab {
					commands = append(commands, call.command)
				}
			case "read":
				screen = body
			case "stop":
				if i := strings.Index(body, "as JSON data"); i >= 0 {
					tail := body[i:]
					if j := strings.Index(tail, ":"); j >= 0 {
						var s string
						if json.Unmarshal([]byte(strings.TrimSpace(tail[j+1:])), &s) == nil {
							screen = s
						}
					}
				}
			}
		}
	}
	if len(commands) > 20 {
		commands = commands[len(commands)-20:]
	}
	return commands, screen
}
func terminalResolveTab(a *App, set *terminalSet, session, tab string) string {
	external := session
	if rec := a.Store.Get(session); rec != nil {
		external = S(rec.Info, "externalId")
	}
	if !regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`).MatchString(external) {
		return ""
	}
	path := ""
	root := filepath.Join(providerHome("claude"), "projects")
	filepath.WalkDir(root, func(p string, d fs.DirEntry, e error) error {
		if e != nil {
			return nil
		}
		if d.IsDir() && p != root {
			rel, _ := filepath.Rel(root, p)
			if strings.Count(rel, string(filepath.Separator)) > 1 {
				return fs.SkipDir
			}
		}
		if !d.IsDir() && d.Name() == external+".jsonl" {
			path = p
			return fs.SkipAll
		}
		return nil
	})
	if path == "" {
		return ""
	}
	commands, screen := terminalTabClues(path, tab)
	if len(commands) == 0 && screen == "" {
		return ""
	}
	normalize := func(s string) string {
		return strings.ToLower(strings.Join(strings.Fields(strings.ReplaceAll(s, "/", `\`)), " "))
	}
	for i := range commands {
		commands[i] = normalize(commands[i])
	}
	clues := []string{}
	for _, line := range strings.Split(screen, "\n") {
		line = normalize(line)
		if len(line) >= 8 && !strings.HasPrefix(line, "ps c:") {
			clues = append(clues, line)
		}
	}
	if len(clues) > 20 {
		clues = clues[len(clues)-20:]
	}
	rows := terminalExternalList(set)
	exe, _ := os.Executable()
	var wg sync.WaitGroup
	gate := make(chan struct{}, 4)
	var mu sync.Mutex
	bestScore := -1
	bestID := ""
	for _, row := range rows {
		if S(row, "source") != "Claude" || B(row, "self") {
			continue
		}
		row := row
		wg.Add(1)
		go func() {
			defer wg.Done()
			gate <- struct{}{}
			defer func() { <-gate }()
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			out, e := HostCommand(ctx, exe, "--console-bridge", "dump", strings.TrimPrefix(S(row, "id"), "ext-")).Output()
			if e != nil {
				return
			}
			var shot M
			if json.Unmarshal(out, &shot) != nil {
				return
			}
			var text strings.Builder
			for _, line := range array(shot["lines"]) {
				text.WriteString(terminalPlain(line))
				text.WriteByte('\n')
			}
			content := normalize(text.String())
			running := normalize(S(row, "running"))
			score := 0
			for i := len(commands) - 1; i >= 0; i-- {
				if strings.Contains(content, commands[i]) || strings.Contains(running, commands[i]) {
					score = 100 + i
					break
				}
			}
			hits := 0
			for _, clue := range clues {
				if strings.Contains(content, clue) {
					hits++
				}
			}
			if hits >= 2 {
				score += hits
			}
			if score == 0 {
				return
			}
			mu.Lock()
			if score > bestScore {
				bestScore = score
				bestID = S(row, "id")
			}
			mu.Unlock()
		}()
	}
	wg.Wait()
	return bestID
}
