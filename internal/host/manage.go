package host

import (
	"context"
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/pelletier/go-toml/v2"
)

//go:embed setting-fields.json
var settingFieldsJSON []byte

//go:embed control-paths.json
var controlPathsJSON []byte

func (a *App) registerControlPaths(mux *http.ServeMux) {
	route(mux, "GET /api/control-paths", func(r *http.Request, b M) (any, error) {
		var snapshot M
		_ = json.Unmarshal(controlPathsJSON, &snapshot)
		snapshot["at"] = nowMS()
		for _, p := range maps(snapshot["providers"]) {
			name := S(p, "id")
			for _, mode := range maps(p["modes"]) {
				count := 0
				for _, s := range a.Store.List() {
					if S(s, "provider") == name && B(s, "live") {
						count++
					}
				}
				mode["activeSessions"] = count
			}
			if name == "claude" {
				for _, path := range maps(p["routes"]) {
					for _, step := range maps(path["steps"]) {
						if S(step, "kind") == "sdk" || S(step, "id") == "sdk" {
							step["label"] = "Installed Claude Code"
							step["description"] = "Communicates directly with the locally installed CLI using streamed JSON control messages."
							step["kind"] = "cli"
							step["protocol"] = "stdio / stream-json"
						}
					}
					for _, key := range []string{"summary", "title"} {
						path[key] = strings.ReplaceAll(S(path, key), "Agent SDK", "Installed CLI")
					}
					if S(path, "id") == "usage" {
						path["notes"] = []string{"Uses PC CLI credentials. Successful responses are cached."}
					}
				}
			}
		}
		return snapshot, nil
	})
}
func manageHome(provider string) string {
	home, _ := os.UserHomeDir()
	switch provider {
	case "claude":
		if p := os.Getenv("CLAUDE_CONFIG_DIR"); p != "" {
			return p
		}
		return filepath.Join(home, ".claude")
	case "codex":
		if p := os.Getenv("CODEX_HOME"); p != "" {
			return p
		}
		return filepath.Join(home, ".codex")
	case "antigravity":
		return filepath.Join(home, ".gemini")
	}
	return ""
}
func manageFiles(provider string) []M {
	root := manageHome(provider)
	var files []M
	switch provider {
	case "claude":
		files = []M{{"id": "settings", "label": "settings.json", "path": filepath.Join(root, "settings.json"), "description": "Claude Code settings"}, {"id": "rules", "label": "CLAUDE.md", "path": filepath.Join(root, "CLAUDE.md"), "description": "Shared instructions"}}
	case "codex":
		files = []M{{"id": "config", "label": "config.toml", "path": filepath.Join(root, "config.toml"), "description": "Codex settings"}, {"id": "agents", "label": "AGENTS.md", "path": filepath.Join(root, "AGENTS.md"), "description": "Shared instructions"}}
	case "antigravity":
		files = []M{{"id": "settings", "label": "settings.json", "path": filepath.Join(root, "antigravity-cli", "settings.json"), "description": "Antigravity settings"}, {"id": "mcp", "label": "mcp_config.json", "path": filepath.Join(root, "config", "mcp_config.json"), "description": "MCP servers"}, {"id": "rules", "label": "GEMINI.md", "path": filepath.Join(root, "GEMINI.md"), "description": "Shared instructions"}}
	}
	for _, f := range files {
		_, err := os.Stat(S(f, "path"))
		f["exists"] = err == nil
	}
	return files
}
func manageFile(provider, id string) (M, error) {
	for _, f := range manageFiles(provider) {
		if S(f, "id") == id {
			return f, nil
		}
	}
	return nil, errors.New("Cannot open this file")
}

var manageSecret = regexp.MustCompile(`(?im)(["']?[\w.-]*(?:token|secret|password|api_key|apikey|api-key|auth)[\w.-]*["']?\s*[=:]\s*["']?)([^"'\r\n]{5,})`)
var manageBearer = regexp.MustCompile(`(?i)(Bearer\s+)[A-Za-z0-9._~+/=-]{6,}`)
var manageSecretKey = regexp.MustCompile(`(?i)token|secret|key|password|authorization`)

func manageMaskDeep(v any, secret bool) any {
	switch value := v.(type) {
	case map[string]any:
		out := M{}
		for k, x := range value {
			out[k] = manageMaskDeep(x, secret || manageSecretKey.MatchString(k) || k == "env" || k == "headers" || k == "http_headers" || k == "env_http_headers")
		}
		return out
	case []any:
		out := make([]any, len(value))
		for i, x := range value {
			out[i] = manageMaskDeep(x, secret)
		}
		return out
	case string:
		if secret {
			return "••••"
		}
		return value
	default:
		return v
	}
}

func manageMask(text string) string {
	return manageSecret.ReplaceAllString(manageBearer.ReplaceAllString(text, "${1}••••"), "${1}••••")
}
func manageBackup(path string) error {
	raw, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	backup := path + ".remote-code.bak"
	if info, e := os.Lstat(backup); e == nil && !info.Mode().IsRegular() {
		return errors.New("Invalid settings backup destination")
	} else if e != nil && !errors.Is(e, os.ErrNotExist) {
		return e
	}
	f, e := os.CreateTemp(filepath.Dir(path), ".settings-backup-*.tmp")
	if e != nil {
		return e
	}
	defer os.Remove(f.Name())
	if e = f.Chmod(0600); e == nil {
		_, e = f.Write(raw)
	}
	if e == nil {
		e = f.Sync()
	}
	closeErr := f.Close()
	if e != nil {
		return e
	}
	if closeErr != nil {
		return closeErr
	}
	return os.Rename(f.Name(), backup)
}
func manageWrite(path, text string) error {
	if strings.Contains(text, "••••") {
		return errors.New("Masked values remain; files containing secrets cannot be saved here")
	}
	if strings.HasSuffix(path, ".json") {
		var v any
		if err := json.Unmarshal([]byte(text), &v); err != nil {
			return err
		}
	}
	if strings.HasSuffix(path, ".toml") {
		var v M
		if err := toml.Unmarshal([]byte(text), &v); err != nil {
			return err
		}
	}
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	if err := manageBackup(path); err != nil {
		return err
	}
	f, err := os.CreateTemp(filepath.Dir(path), ".settings-*.tmp")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	_ = f.Chmod(0600)
	_, err = f.WriteString(text)
	if err == nil {
		err = f.Sync()
	}
	cerr := f.Close()
	if err != nil {
		return err
	}
	if cerr != nil {
		return cerr
	}
	return os.Rename(f.Name(), path)
}
func manageRead(path string) (M, error) {
	raw, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return M{}, nil
	}
	if err != nil {
		return nil, err
	}
	var m M
	if strings.HasSuffix(path, ".toml") {
		err = toml.Unmarshal(raw, &m)
	} else {
		err = json.Unmarshal(raw, &m)
	}
	if err != nil {
		return nil, err
	}
	if m == nil {
		m = M{}
	}
	return m, nil
}
func managePath(m M, key string) any {
	keys := strings.Split(key, ".")
	var v any = m
	for _, k := range keys {
		cur, ok := v.(map[string]any)
		if !ok {
			return nil
		}
		v = cur[k]
	}
	return v
}
func manageSet(m M, key string, v any) {
	keys := strings.Split(key, ".")
	for _, k := range keys[:len(keys)-1] {
		next, ok := m[k].(map[string]any)
		if !ok {
			next = M{}
			m[k] = next
		}
		m = next
	}
	k := keys[len(keys)-1]
	if v == nil {
		delete(m, k)
	} else {
		m[k] = v
	}
}
func manageFields(provider string) []M {
	var all M
	_ = json.Unmarshal(settingFieldsJSON, &all)
	key := "CLAUDE_FIELDS"
	if provider == "codex" {
		key = "CODEX_FIELDS"
	}
	if provider == "antigravity" {
		key = "AGY_FIELDS"
	}
	return maps(all[key])
}
func manageRun(ctx context.Context, provider string, args ...string) (string, error) {
	path, err := FindProviderExecutable(provider)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()
	cmd := HostCommand(ctx, path, args...)
	cmd.Dir, _ = os.UserHomeDir()
	cmd.Env = append(os.Environ(), "NO_COLOR=1", "FORCE_COLOR=0")
	out, err := cmd.CombinedOutput()
	text := string(out)
	if err != nil {
		return text, fmt.Errorf("%s: %s", provider, manageMask(strings.TrimSpace(text)))
	}
	return text, nil
}
func manageJSON(text string) any {
	var v any
	if json.Unmarshal([]byte(strings.TrimSpace(text)), &v) == nil {
		return v
	}
	lines := strings.Split(text, "\n")
	for i := len(lines) - 1; i >= 0; i-- {
		if json.Unmarshal([]byte(strings.TrimSpace(lines[i])), &v) == nil {
			return v
		}
	}
	return nil
}
func manageArg(value string) error {
	if value == "" || strings.HasPrefix(value, "-") || strings.ContainsAny(value, "\x00\r\n") {
		return errors.New("Invalid name")
	}
	return nil
}
func (a *App) registerManage(mux *http.ServeMux) {
	route(mux, "GET /api/manage/{provider}/settings", func(r *http.Request, b M) (any, error) {
		provider := r.PathValue("provider")
		if _, err := a.provider(provider); err != nil {
			return nil, err
		}
		files := manageFiles(provider)
		m, err := manageRead(S(files[0], "path"))
		if err != nil {
			return nil, err
		}
		if provider == "codex" {
			result, e := a.Providers[provider].Do(r.Context(), "manageRequest", nil, M{"method": "config/read", "params": M{}})
			if e != nil {
				return nil, e
			}
			if obj, ok := result.(map[string]any); ok {
				if config, ok := obj["config"].(map[string]any); ok {
					m = config
				}
			}
		}
		fields := manageFields(provider)
		for _, f := range fields {
			v := managePath(m, S(f, "key"))
			if v != nil {
				f["value"] = fmt.Sprint(v)
			} else {
				f["value"] = nil
			}
		}
		return M{"fields": fields, "files": files}, nil
	})
	route(mux, "POST /api/manage/{provider}/settings/set", func(r *http.Request, b M) (any, error) {
		p := r.PathValue("provider")
		if _, err := a.provider(p); err != nil {
			return nil, err
		}
		key := S(b, "key")
		var field M
		for _, f := range manageFields(p) {
			if S(f, "key") == key {
				field = f
				break
			}
		}
		if field == nil {
			return nil, errors.New("Immutable setting: " + key)
		}
		value := b["value"]
		if value == "" {
			value = nil
		}
		if value != nil {
			switch S(field, "type") {
			case "bool":
				value = value == true || value == "true"
			case "choice":
				valid := false
				for _, opt := range maps(field["options"]) {
					if opt["id"] == value {
						valid = true
					}
				}
				if !valid {
					return nil, errors.New("Invalid option")
				}
			default:
				value = fmt.Sprint(value)
			}
		}
		if p == "codex" {
			_, err := a.Providers[p].Do(r.Context(), "manageRequest", nil, M{"method": "config/value/write", "params": M{"keyPath": key, "value": value, "mergeStrategy": "replace"}})
			return nil, err
		}
		path := S(manageFiles(p)[0], "path")
		m, err := manageRead(path)
		if err != nil {
			return nil, err
		}
		manageSet(m, key, value)
		raw, err := json.MarshalIndent(m, "", "  ")
		if err != nil {
			return nil, err
		}
		return nil, manageWrite(path, string(raw))
	})
	for _, action := range []string{"read", "write"} {
		action := action
		route(mux, "POST /api/manage/{provider}/file/"+action, func(r *http.Request, b M) (any, error) {
			f, err := manageFile(r.PathValue("provider"), S(b, "id"))
			if err != nil {
				return nil, err
			}
			path := S(f, "path")
			if action == "write" {
				return nil, manageWrite(path, S(b, "text"))
			}
			raw, err := os.ReadFile(path)
			if err != nil && !errors.Is(err, os.ErrNotExist) {
				return nil, err
			}
			text := manageMask(string(raw))
			return M{"path": path, "text": text, "masked": text != string(raw)}, nil
		})
	}
	for _, provider := range []string{"claude", "codex", "antigravity"} {
		provider := provider
		prefix := "/api/manage/" + provider
		if provider == "claude" {
			prefix = "/api/claude"
		}
		route(mux, "GET "+prefix+"/mcp", func(r *http.Request, b M) (any, error) { return manageMCPList(r.Context(), provider) })
		for _, action := range []string{"get", "add", "remove", "enable"} {
			action := action
			route(mux, "POST "+prefix+"/mcp/"+action, func(r *http.Request, b M) (any, error) { return a.manageMCP(r.Context(), provider, action, b) })
		}
		route(mux, "GET "+prefix+"/skills", func(r *http.Request, b M) (any, error) { return a.manageSkills(r.Context(), provider) })
		for _, action := range []string{"read", "create", "write", "delete", "enable"} {
			action := action
			route(mux, "POST "+prefix+"/skills/"+action, func(r *http.Request, b M) (any, error) { return a.manageSkill(r.Context(), provider, action, b) })
		}
		route(mux, "GET "+prefix+"/plugins", func(r *http.Request, b M) (any, error) { return managePlugins(r.Context(), provider) })
		route(mux, "POST "+prefix+"/plugins/action", func(r *http.Request, b M) (any, error) {
			return managePluginAction(r.Context(), provider, S(b, "action"), b)
		})
	}
	route(mux, "GET /api/claude/plugins/search", func(r *http.Request, b M) (any, error) {
		text, err := manageRun(r.Context(), "claude", "plugin", "list", "--json", "--available")
		if err != nil {
			return nil, err
		}
		available := maps(providerMap(manageJSON(text))["available"])
		if len(available) == 0 {
			return []M{}, nil
		}
		text, err = manageRun(r.Context(), "claude", "plugin", "list", "--json")
		if err != nil {
			return nil, err
		}
		return claudePluginSearchRows(available, maps(manageJSON(text)), r.URL.Query().Get("q")), nil
	})
	route(mux, "POST /api/claude/plugins/install", func(r *http.Request, b M) (any, error) {
		return managePluginAction(r.Context(), "claude", "install", b)
	})
	route(mux, "POST /api/claude/marketplaces/add", func(r *http.Request, b M) (any, error) {
		source := S(b, "source")
		if err := manageArg(source); err != nil {
			return nil, err
		}
		out, err := manageRun(r.Context(), "claude", "plugin", "marketplace", "add", source, "--json")
		return M{"message": strings.TrimSpace(out)}, err
	})
	route(mux, "POST /api/claude/marketplaces/action", func(r *http.Request, b M) (any, error) {
		action := S(b, "action")
		if action != "update" && action != "remove" {
			return nil, errors.New("Invalid action")
		}
		name := S(b, "name")
		if err := manageArg(name); err != nil {
			return nil, err
		}
		out, err := manageRun(r.Context(), "claude", "plugin", "marketplace", action, name)
		return M{"message": strings.TrimSpace(out)}, err
	})
}

func claudePluginSearchRows(available, installed []M, query string) []M {
	installedIDs := make(map[string]bool)
	for _, row := range installed {
		id := S(row, "id")
		if id == "" {
			id = S(row, "pluginId")
		}
		installedIDs[id] = true
	}
	words := strings.Fields(strings.ToLower(query))
	out := []M{}
	for _, row := range available {
		id := S(row, "pluginId")
		if id == "" {
			continue
		}
		name, description := S(row, "name"), S(row, "description")
		hay := strings.ToLower(id + " " + name + " " + description)
		matches := true
		for _, word := range words {
			if !strings.Contains(hay, word) {
				matches = false
				break
			}
		}
		if !matches {
			continue
		}
		marketplace := S(row, "marketplace")
		if marketplace == "" {
			_, marketplace, _ = strings.Cut(id, "@")
		}
		out = append(out, M{"id": id, "name": name, "description": providerTruncate(description, 400), "marketplace": marketplace, "installed": installedIDs[id]})
		if len(out) == 80 {
			break
		}
	}
	return out
}

func manageMCPList(ctx context.Context, p string) (any, error) {
	args := []string{"mcp", "list"}
	if p == "codex" {
		args = append(args, "--json")
	}
	out, err := manageRun(ctx, p, args...)
	if err != nil {
		return nil, err
	}
	rows := []M{}
	if p == "codex" {
		for _, server := range maps(manageJSON(out)) {
			transport, _ := server["transport"].(map[string]any)
			target := S(transport, "url")
			if target == "" {
				target = S(transport, "command")
				for _, arg := range array(transport["args"]) {
					target += " " + fmt.Sprint(arg)
				}
			}
			on := B(server, "enabled")
			status := "Disabled"
			if on {
				status = "Enabled"
			}
			rows = append(rows, M{"name": server["name"], "target": target, "status": status, "ok": on, "disabled": !on, "connector": false})
		}
		return rows, nil
	}
	for _, line := range strings.Split(out, "\n") {
		if p == "claude" {
			parts := strings.SplitN(strings.TrimSpace(line), ": ", 2)
			if len(parts) != 2 {
				continue
			}
			end := strings.LastIndex(parts[1], " - ")
			if end < 0 {
				continue
			}
			status := parts[1][end+3:]
			rows = append(rows, M{"name": parts[0], "target": parts[1][:end], "status": status, "ok": strings.Contains(strings.ToLower(status), "connected") && !strings.Contains(strings.ToLower(status), "failed"), "disabled": strings.Contains(strings.ToLower(status), "disabled"), "connector": strings.HasPrefix(parts[0], "claude.ai ")})
		} else {
			cols := strings.Fields(line)
			if len(cols) < 4 || strings.EqualFold(cols[0], "name") {
				continue
			}
			on := strings.EqualFold(cols[2], "enabled")
			rows = append(rows, M{"name": cols[0], "target": strings.Join(cols[3:], " "), "status": cols[2], "ok": on, "disabled": !on, "connector": false})
		}
	}
	return rows, nil
}
func (a *App) manageMCP(ctx context.Context, p, action string, b M) (any, error) {
	name := S(b, "name")
	if err := manageArg(name); err != nil {
		return nil, err
	}
	if p == "antigravity" && action == "get" {
		config, e := manageRead(filepath.Join(manageHome(p), "config", "mcp_config.json"))
		if e != nil {
			return nil, e
		}
		servers, _ := config["mcpServers"].(map[string]any)
		server, ok := servers[name]
		if !ok {
			return nil, errors.New("MCP configuration not found")
		}
		raw, _ := json.MarshalIndent(manageMaskDeep(server, false), "", "  ")
		return M{"name": name, "scope": nil, "text": string(raw)}, nil
	}
	args := []string{"mcp", action, name}
	if action == "enable" {
		if p == "codex" {
			if !regexp.MustCompile(`^[A-Za-z0-9_-]+$`).MatchString(name) {
				return nil, errors.New("Invalid MCP server name")
			}
			rows, e := manageMCPList(ctx, p)
			if e != nil {
				return nil, e
			}
			found := false
			for _, row := range maps(rows) {
				if S(row, "name") == name {
					found = true
				}
			}
			if !found {
				return nil, errors.New("MCP server not found")
			}
			_, err := a.Providers[p].Do(ctx, "manageRequest", nil, M{"method": "config/value/write", "params": M{"keyPath": "mcp_servers." + name + ".enabled", "value": B(b, "enabled"), "mergeStrategy": "upsert"}})
			return nil, err
		}
		if !B(b, "enabled") {
			args[1] = "disable"
		}
	}
	if action == "add" {
		transport := S(b, "transport")
		if transport == "" {
			transport = "stdio"
		}
		if p == "claude" {
			scope := S(b, "scope")
			if scope == "" {
				scope = "user"
			}
			if scope != "user" && scope != "project" && scope != "local" {
				return nil, errors.New("Invalid scope")
			}
			conf := M{"type": transport}
			if transport == "stdio" {
				if S(b, "command") == "" {
					return nil, errors.New("Please enter a command to run")
				}
				conf["command"] = b["command"]
				conf["args"] = b["args"]
				conf["env"] = b["env"]
			} else {
				conf["url"] = b["url"]
				conf["headers"] = b["headers"]
			}
			raw, _ := json.Marshal(conf)
			args = []string{"mcp", "add-json", name, string(raw), "-s", scope}
		} else {
			args = []string{"mcp", "add"}
			if transport == "stdio" {
				env, _ := b["env"].(map[string]any)
				for k, v := range env {
					args = append(args, "--env", k+"="+fmt.Sprint(v))
				}
				command := S(b, "command")
				if err := manageArg(command); err != nil {
					return nil, err
				}
				args = append(args, name, "--", command)
				for _, arg := range array(b["args"]) {
					args = append(args, fmt.Sprint(arg))
				}
			} else {
				url := S(b, "url")
				if !strings.HasPrefix(url, "http://") && !strings.HasPrefix(url, "https://") {
					return nil, errors.New("Invalid MCP URL")
				}
				if p == "codex" {
					if headers, _ := b["headers"].(map[string]any); len(headers) > 0 {
						return nil, errors.New("Configure Codex HTTP MCP headers in PC config.toml")
					}
					args = append(args, name, "--url", url)
				} else {
					if headers, ok := b["headers"].(map[string]any); ok {
						for k, v := range headers {
							if strings.ContainsAny(k+fmt.Sprint(v), "\r\n\x00") {
								return nil, errors.New("Invalid MCP header")
							}
							args = append(args, "--header", k+": "+fmt.Sprint(v))
						}
					}
					args = append(args, "--type", "http", name, url)
				}
			}
		}
	}
	if action == "get" && p == "codex" {
		args = append(args, "--json")
	}
	if action == "remove" && p == "claude" && S(b, "scope") != "" {
		scope := S(b, "scope")
		if scope != "user" && scope != "local" && scope != "project" {
			return nil, errors.New("Invalid scope")
		}
		args = append(args, "-s", scope)
	}
	out, err := manageRun(ctx, p, args...)
	if err != nil {
		return nil, err
	}
	if action == "get" {
		text := manageMask(strings.TrimSpace(out))
		var scope any
		if p == "claude" {
			if parsed := claudeMCPScope(out); parsed != "" {
				scope = parsed
			}
		}
		if p == "codex" {
			if v := manageJSON(out); v != nil {
				raw, _ := json.MarshalIndent(manageMaskDeep(v, false), "", "  ")
				text = string(raw)
			}
		}
		return M{"name": name, "scope": scope, "text": text}, nil
	}
	return nil, nil
}

func claudeMCPScope(output string) string {
	for _, line := range strings.Split(output, "\n") {
		key, value, found := strings.Cut(strings.TrimSpace(line), ":")
		if !found || !strings.EqualFold(strings.TrimSpace(key), "scope") {
			continue
		}
		fields := strings.Fields(strings.ToLower(value))
		if len(fields) == 0 {
			return ""
		}
		scope := fields[0]
		if strings.HasPrefix(scope, "user") {
			return "user"
		}
		if scope == "local" || scope == "project" {
			return scope
		}
		return ""
	}
	return ""
}

func manageSkillRoots(p string) []string {
	home, _ := os.UserHomeDir()
	root := manageHome(p)
	switch p {
	case "claude":
		return []string{filepath.Join(root, "skills")}
	case "codex":
		return []string{filepath.Join(root, "skills"), filepath.Join(home, ".agents", "skills")}
	default:
		return []string{filepath.Join(root, "config", "skills")}
	}
}
func manageSkills(p string) []M {
	out := []M{}
	for _, root := range manageSkillRoots(p) {
		dirs, _ := os.ReadDir(root)
		for _, dir := range dirs {
			path := filepath.Join(root, dir.Name(), "SKILL.md")
			if _, err := os.Stat(path); err != nil {
				continue
			}
			raw, _ := os.ReadFile(path)
			name, description := manageSkillFrontmatter(string(raw))
			if name == "" {
				name = dir.Name()
			}
			out = append(out, M{"name": name, "description": description, "source": "My Skills", "path": path, "editable": !strings.HasPrefix(dir.Name(), "."), "enabled": nil})
		}
	}
	for source, root := range manageExtraSkillRoots(p) {
		dirs, _ := os.ReadDir(root)
		for _, dir := range dirs {
			path := filepath.Join(root, dir.Name(), "SKILL.md")
			raw, e := os.ReadFile(path)
			if e != nil {
				continue
			}
			name, description := manageSkillFrontmatter(string(raw))
			if name == "" {
				name = dir.Name()
			}
			out = append(out, M{"name": name, "description": description, "source": source, "path": path, "editable": false, "enabled": nil})
		}
	}
	sort.SliceStable(out, func(i, j int) bool {
		if B(out[i], "editable") != B(out[j], "editable") {
			return B(out[i], "editable")
		}
		return strings.ToLower(S(out[i], "name")) < strings.ToLower(S(out[j], "name"))
	})
	return out
}
func manageExtraSkillRoots(p string) map[string]string {
	root := manageHome(p)
	out := map[string]string{}
	if p == "antigravity" {
		out["Built-in (CLI)"] = filepath.Join(root, "antigravity-cli", "builtin", "skills")
		out["Built-in"] = filepath.Join(root, "antigravity", "builtin", "skills")
	}
	if p == "claude" {
		registry, e := manageRead(filepath.Join(root, "plugins", "installed_plugins.json"))
		if e == nil {
			plugins, _ := registry["plugins"].(map[string]any)
			for id, versions := range plugins {
				for _, row := range maps(versions) {
					if path := S(row, "installPath"); path != "" {
						out[id] = filepath.Join(path, "skills")
						break
					}
				}
			}
		}
	}
	return out
}
func manageReadSkillPath(p, path string) (string, error) {
	if full, e := manageSkillPath(p, path); e == nil {
		return full, nil
	}
	abs, e := filepath.Abs(path)
	if e != nil {
		return "", e
	}
	real, e := filepath.EvalSymlinks(abs)
	if e != nil {
		return "", e
	}
	if !strings.EqualFold(filepath.Base(real), "SKILL.md") {
		return "", errors.New("Cannot open this file")
	}
	roots := manageSkillRoots(p)
	for _, root := range manageExtraSkillRoots(p) {
		roots = append(roots, root)
	}
	if p == "codex" {
		home, _ := os.UserHomeDir()
		roots = append(roots, manageHome(p), filepath.Join(home, ".cache", "codex-runtimes"))
	}
	for _, root := range roots {
		rr, e := filepath.EvalSymlinks(root)
		if e != nil {
			continue
		}
		rel, e := filepath.Rel(rr, real)
		if e == nil && rel != "." && !filepath.IsAbs(rel) && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
			return real, nil
		}
	}
	return "", errors.New("Cannot open this file")
}
func manageSkillFrontmatter(text string) (string, string) {
	text = strings.ReplaceAll(text, "\r\n", "\n")
	if !strings.HasPrefix(text, "---\n") {
		return "", ""
	}
	end := strings.Index(text[4:], "\n---")
	if end < 0 {
		return "", ""
	}
	lines := strings.Split(text[4:4+end], "\n")
	fields := map[string]string{}
	for i := 0; i < len(lines); i++ {
		line := lines[i]
		if strings.HasPrefix(line, " ") || strings.HasPrefix(line, "\t") {
			continue
		}
		key, value, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		value = strings.TrimSpace(value)
		if value == "" || strings.HasPrefix(value, ">") || strings.HasPrefix(value, "|") {
			var parts []string
			for i+1 < len(lines) && (strings.HasPrefix(lines[i+1], " ") || strings.HasPrefix(lines[i+1], "\t")) {
				i++
				parts = append(parts, strings.TrimSpace(lines[i]))
			}
			separator := " "
			if strings.HasPrefix(value, "|") {
				separator = "\n"
			}
			value = strings.Join(parts, separator)
		}
		if strings.HasPrefix(value, `"`) {
			if unquoted, e := strconv.Unquote(value); e == nil {
				value = unquoted
			}
		} else {
			value = strings.Trim(value, "'")
		}
		fields[strings.TrimSpace(key)] = value
	}
	return fields["name"], fields["description"]
}
func (a *App) manageSkills(ctx context.Context, p string) ([]M, error) {
	if p != "codex" {
		return manageSkills(p), nil
	}
	value, e := a.Providers[p].Do(ctx, "manageRequest", nil, M{"method": "skills/list", "params": M{"cwds": []string{manageHome(p)}, "forceReload": true}})
	if e != nil {
		return nil, e
	}
	response, _ := value.(map[string]any)
	rows := []M{}
	scopes := map[string]string{"user": "My Skills", "repo": "Repository", "system": "Built-in", "admin": "Admin"}
	for _, data := range maps(response["data"]) {
		for _, skill := range maps(data["skills"]) {
			path := S(skill, "path")
			_, err := manageSkillPath(p, path)
			source := S(skill, "pluginId")
			if source == "" {
				source = scopes[S(skill, "scope")]
			}
			description := S(skill, "description")
			if inter, ok := skill["interface"].(map[string]any); ok && S(inter, "shortDescription") != "" {
				description = S(inter, "shortDescription")
			}
			rows = append(rows, M{"name": S(skill, "name"), "description": description, "source": source, "path": path, "editable": err == nil, "enabled": skill["enabled"] != false})
		}
	}
	sort.SliceStable(rows, func(i, j int) bool {
		if B(rows[i], "editable") != B(rows[j], "editable") {
			return B(rows[i], "editable")
		}
		return strings.ToLower(S(rows[i], "name")) < strings.ToLower(S(rows[j], "name"))
	})
	return rows, nil
}
func manageSkillPath(p, path string) (string, error) {
	abs, err := filepath.Abs(path)
	if err != nil {
		return "", err
	}
	real, err := filepath.EvalSymlinks(abs)
	if err != nil {
		return "", err
	}
	for _, root := range manageSkillRoots(p) {
		rr, err := filepath.EvalSymlinks(root)
		if err != nil {
			continue
		}
		rel, err := filepath.Rel(rr, real)
		if err == nil && rel != "." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && rel != ".." && !strings.HasPrefix(rel, ".system"+string(filepath.Separator)) && filepath.Base(real) == "SKILL.md" {
			return real, nil
		}
	}
	return "", errors.New("This skill cannot be modified")
}
func (a *App) manageSkill(ctx context.Context, p, action string, b M) (any, error) {
	if action == "create" {
		name := S(b, "name")
		if !regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$`).MatchString(name) {
			return nil, errors.New("Invalid skill name")
		}
		root := manageSkillRoots(p)[0]
		if err := os.MkdirAll(root, 0700); err != nil {
			return nil, err
		}
		path := filepath.Join(root, name)
		if err := os.Mkdir(path, 0700); err != nil {
			return nil, err
		}
		file := filepath.Join(path, "SKILL.md")
		text := S(b, "text")
		if text == "" {
			description := strings.TrimSpace(strings.ReplaceAll(strings.ReplaceAll(S(b, "description"), "\r", " "), "\n", " "))
			if description == "" {
				os.Remove(path)
				return nil, errors.New("Please describe when to use this skill in the description")
			}
			text = "---\nname: " + name + "\ndescription: " + strconv.Quote(description) + "\n---\n\n" + strings.TrimSpace(S(b, "body")) + "\n"
		}
		return M{"path": file}, os.WriteFile(file, []byte(text), 0600)
	}
	if action == "read" {
		path, err := manageReadSkillPath(p, S(b, "path"))
		if err != nil {
			return nil, err
		}
		raw, err := os.ReadFile(path)
		_, writeErr := manageSkillPath(p, path)
		return M{"path": path, "text": string(raw), "editable": writeErr == nil}, err
	}
	if action == "enable" && p == "codex" {
		rows, e := a.manageSkills(ctx, p)
		if e != nil {
			return nil, e
		}
		requested, e := filepath.Abs(S(b, "path"))
		if e != nil {
			return nil, e
		}
		known := false
		for _, row := range rows {
			if path, e := filepath.Abs(S(row, "path")); e == nil && strings.EqualFold(path, requested) {
				known = true
				break
			}
		}
		if !known {
			return nil, errors.New("Skill not found")
		}
		_, e = a.Providers[p].Do(ctx, "manageRequest", nil, M{"method": "skills/config/write", "params": M{"path": requested, "enabled": B(b, "enabled")}})
		return nil, e
	}
	path, err := manageSkillPath(p, S(b, "path"))
	if err != nil {
		return nil, err
	}
	switch action {
	case "read":
		raw, err := os.ReadFile(path)
		return M{"path": path, "text": string(raw), "editable": true}, err
	case "write":
		if !strings.HasPrefix(strings.ReplaceAll(S(b, "text"), "\r\n", "\n"), "---\n") {
			return nil, errors.New("Do not delete the leading name and description section")
		}
		return nil, manageWrite(path, S(b, "text"))
	case "delete":
		parent := filepath.Dir(path)
		for _, root := range manageSkillRoots(p) {
			real, e := filepath.EvalSymlinks(root)
			if e == nil && platformSameResource(filepath.Dir(parent), real) {
				return nil, os.RemoveAll(parent)
			}
		}
		return nil, errors.New("This skill cannot be deleted")
	case "enable":
		if p == "codex" {
			_, err := a.Providers[p].Do(ctx, "manageRequest", nil, M{"method": "skills/config/write", "params": M{"path": path, "enabled": B(b, "enabled")}})
			return nil, err
		}
		return nil, errors.New("This skill cannot be toggled")
	}
	return nil, errors.New("Invalid skill action")
}
func managePlugins(ctx context.Context, p string) (any, error) {
	args := []string{"plugin", "list", "--json"}
	out, err := manageRun(ctx, p, args...)
	if err != nil {
		return nil, err
	}
	v := manageJSON(out)
	if p == "claude" {
		market, err := manageRun(ctx, p, "plugin", "marketplace", "list", "--json")
		if err != nil {
			return nil, err
		}
		installed := []M{}
		for _, row := range maps(v) {
			id := S(row, "id")
			if id == "" {
				id = S(row, "pluginId")
			}
			scope := S(row, "scope")
			if scope == "" {
				scope = "user"
			}
			description := S(row, "description")
			if description == "" && S(row, "installPath") != "" {
				for _, name := range []string{filepath.Join(".claude-plugin", "plugin.json"), "plugin.json"} {
					if data, e := manageRead(filepath.Join(S(row, "installPath"), name)); e == nil && S(data, "description") != "" {
						description = S(data, "description")
						break
					}
				}
			}
			installed = append(installed, M{"id": id, "version": S(row, "version"), "scope": scope, "enabled": row["enabled"] != false, "description": description})
		}
		marketplaces := []M{}
		for _, row := range maps(manageJSON(market)) {
			source := S(row, "source")
			for _, key := range []string{"repo", "url", "path"} {
				if source == "" {
					source = S(row, key)
				}
			}
			marketplaces = append(marketplaces, M{"name": S(row, "name"), "source": source})
		}
		return M{"installed": installed, "marketplaces": marketplaces}, nil
	}
	return managePluginRows(p, v), nil
}
func managePluginRows(p string, v any) []M {
	rows := maps(v)
	if object, ok := v.(map[string]any); ok {
		if p == "codex" {
			rows = maps(object["installed"])
		} else {
			rows = maps(object["plugins"])
		}
	}
	outRows := []M{}
	for _, row := range rows {
		id := S(row, "id")
		if p == "codex" {
			id = S(row, "pluginId")
		}
		if id == "" {
			id = S(row, "name")
		}
		name := S(row, "displayName")
		if name == "" {
			name = S(row, "name")
		}
		if name == "" {
			name = id
		}
		source := S(row, "source")
		for _, key := range []string{"marketplaceName", "marketplace"} {
			if source == "" {
				source = S(row, key)
			}
		}
		outRows = append(outRows, M{"id": id, "name": name, "description": S(row, "description"), "version": S(row, "version"), "enabled": row["enabled"] != false, "source": source, "canToggle": p != "codex"})
	}
	return outRows
}
func managePluginAction(ctx context.Context, p, action string, b M) (any, error) {
	if action != "install" && action != "uninstall" && action != "enable" && action != "disable" && action != "update" {
		return nil, errors.New("Invalid action")
	}
	id := S(b, "id")
	if err := manageArg(id); err != nil {
		return nil, err
	}
	verb := action
	if p == "codex" {
		if action == "install" {
			verb = "add"
		} else if action == "uninstall" {
			verb = "remove"
		} else {
			return nil, errors.New("Codex plugins cannot be toggled from here")
		}
	}
	args := []string{"plugin", verb, id}
	if p == "claude" {
		args = append(args, "--json")
		if scope := S(b, "scope"); scope != "" && action != "update" {
			if scope != "user" && scope != "project" && scope != "local" {
				return nil, errors.New("Invalid scope")
			}
			args = append(args, "-s", scope)
		}
		if accept := S(b, "acceptCommand"); accept != "" {
			if !regexp.MustCompile(`^[A-Fa-f0-9]{64}$`).MatchString(accept) {
				return nil, errors.New("Invalid confirmation mark")
			}
			args = append(args, "--accept-command", accept)
		}
	}
	out, err := manageRun(ctx, p, args...)
	v, _ := manageJSON(out).(map[string]any)
	if S(b, "acceptCommand") == "" {
		if confirmation := managePluginConfirmation(providerMap(v["shownCommand"])); confirmation != nil {
			return M{"ok": false, "needsConfirm": confirmation, "message": "Review the installation command"}, nil
		}
	}
	if err != nil {
		return nil, err
	}
	return M{"ok": true, "message": strings.TrimSpace(out)}, nil
}

func managePluginConfirmation(shown M) M {
	hash := S(shown, "sha256")
	if hash == "" {
		return nil
	}
	command := S(shown, "command")
	if command == "" {
		command = S(shown, "text")
	}
	if command == "" {
		raw, _ := json.Marshal(shown)
		command = string(raw)
	}
	return M{"command": command, "sha256": hash}
}
