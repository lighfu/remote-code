package host

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"
)

type migrationAPIProvider struct {
	fakeProvider
	history []M
	usage   M
}

func (p *migrationAPIProvider) Do(ctx context.Context, action string, rec *Record, body M) (any, error) {
	switch action {
	case "listHistory":
		return p.history, nil
	case "usage":
		return p.usage, nil
	default:
		return p.fakeProvider.Do(ctx, action, rec, body)
	}
}

func migrationAPIResponse(t *testing.T, app *App, path string) M {
	t.Helper()
	response := testRequest(app, http.MethodGet, path, "")
	if response.Code != http.StatusOK {
		t.Fatalf("GET %s returned %d: %s", path, response.Code, response.Body)
	}
	var value M
	if err := json.Unmarshal(response.Body.Bytes(), &value); err != nil {
		t.Fatal(err)
	}
	return value
}

func TestUsageRefreshKeepsSavedWindowsOnProviderFailure(t *testing.T) {
	data := t.TempDir()
	before := M{"windows": []M{{"label": "Weekly", "usedPercent": 42}}, "tickets": M{"available": 2}, "at": int64(123)}
	path := filepath.Join(data, "usage-cache.json")
	if err := atomicJSON(path, M{"codex": before}); err != nil {
		t.Fatal(err)
	}
	app, err := NewApp(data)
	if err != nil {
		t.Fatal(err)
	}
	defer app.Close()
	app.Providers = map[string]Provider{"codex": &migrationAPIProvider{usage: M{"windows": []M{}, "error": "temporary failure", "at": int64(456)}}}
	events, cancel := app.Store.Subscribe()
	defer cancel()
	initial := migrationAPIResponse(t, app, "/api/usage?force=1")
	if len(maps(providerMap(initial["codex"])["windows"])) != 1 {
		t.Fatal("the immediate response lost the saved quota", initial)
	}
	select {
	case event := <-events:
		updated := providerMap(event["usage"])
		if N(updated, "at") != 123 || len(maps(updated["windows"])) != 1 || S(updated, "error") != "temporary failure" || N(providerMap(updated["tickets"]), "available") != 2 {
			t.Fatal("the failure snapshot did not preserve the saved quota", updated)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("no usage event received")
	}
	current := migrationAPIResponse(t, app, "/api/usage")
	if len(maps(providerMap(current["codex"])["windows"])) != 1 {
		t.Fatal("GET after failure lost the saved quota", current)
	}
	disk, err := manageRead(path)
	if err != nil || len(maps(providerMap(disk["codex"])["windows"])) != 1 {
		t.Fatal("usage cache on disk lost the saved quota", disk, err)
	}
}

func TestCodexUsagePushPersistsAndRefreshesDetails(t *testing.T) {
	app, err := NewApp(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer app.Close()
	native := app.Providers["codex"].(*nativeProvider)
	pushedAt := nowMS()
	windows := []M{{"label": "Weekly", "usedPercent": 42}}
	app.Providers = map[string]Provider{"codex": &migrationAPIProvider{usage: M{"windows": windows, "at": pushedAt + 1, "tickets": M{"available": 2}}}}
	events, cancel := app.Store.Subscribe()
	defer cancel()
	native.rememberUsage(M{"windows": windows, "at": pushedAt})
	for _, at := range []int64{pushedAt, pushedAt + 1} {
		select {
		case event := <-events:
			if got := providerMap(event["usage"]); S(event, "type") != "usage" || int64(N(got, "at")) != at {
				t.Fatalf("unexpected event at %d: %v", at, event)
			}
		case <-time.After(2 * time.Second):
			t.Fatalf("missing usage event at %d", at)
		}
	}
	native.rememberUsage(M{"windows": []M{}, "at": pushedAt - 1})
	current := providerMap(migrationAPIResponse(t, app, "/api/usage")["codex"])
	if len(maps(current["windows"])) != 1 || N(providerMap(current["tickets"]), "available") != 2 {
		t.Fatal("stale push replaced usage details", current)
	}
	disk, err := manageRead(filepath.Join(app.DataDir, "usage-cache.json"))
	if err != nil || N(providerMap(providerMap(disk["codex"])["tickets"]), "available") != 2 {
		t.Fatal("usage details were not persisted", disk, err)
	}
}

func TestSessionReloadResolvesNewlyAvailableMedia(t *testing.T) {
	app := testApp(t)
	dir := t.TempDir()
	sid := S(app.Store.Create(M{"provider": "codex", "cwd": dir}).Info, "id")
	readPath := filepath.Join(dir, "render folder", "file with spaces.png")
	if err := os.MkdirAll(filepath.Dir(readPath), 0700); err != nil {
		t.Fatal(err)
	}
	app.Store.Add(sid, M{"id": "message", "kind": "assistant", "text": "Generated [preview](render%20folder/file%20with%20spaces.png)"})
	app.Store.Add(sid, M{"id": "read", "kind": "tool", "tool": "Read", "title": "Read", "files": []string{readPath}})
	if err := os.WriteFile(readPath, []byte("fixture"), 0600); err != nil {
		t.Fatal(err)
	}
	got := maps(migrationAPIResponse(t, app, "/api/sessions/"+sid)["entries"])
	if len(got) != 2 || !reflect.DeepEqual(providerAnswerList(got[0]["media"]), []string{MediaID(readPath)}) || !reflect.DeepEqual(providerAnswerList(got[1]["media"]), []string{MediaID(readPath)}) {
		t.Fatal("late-created media was not enriched on reload", got)
	}
	for _, entry := range app.Store.Get(sid).Entries {
		if entry["media"] != nil {
			t.Fatal("a read unexpectedly changed stored entries")
		}
	}
}

func TestDirsMergesAppAndPCProviderHistory(t *testing.T) {
	app := testApp(t)
	shared, pcOnly := t.TempDir(), t.TempDir()
	if err := os.WriteFile(filepath.Join(pcOnly, ".git"), []byte("gitdir: fixture\n"), 0600); err != nil {
		t.Fatal(err)
	}
	app.Store.Create(M{"provider": "claude", "cwd": shared})
	app.Providers = map[string]Provider{
		"codex":       &migrationAPIProvider{history: []M{{"cwd": shared, "updatedAt": nowMS()}}},
		"antigravity": &migrationAPIProvider{history: []M{{"cwd": pcOnly, "updatedAt": nowMS()}}},
	}
	app.refreshFolderHistory()
	rows := maps(migrationAPIResponse(t, app, "/api/dirs")["recent"])
	byPath := make(map[string]M)
	for _, row := range rows {
		byPath[fileKey(S(row, "path"))] = row
	}
	combined := byPath[fileKey(shared)]
	if N(combined, "count") != 2 || !reflect.DeepEqual(providerAnswerList(combined["providers"]), []string{"claude", "codex"}) {
		t.Fatal("same folder not aggregated by provider", combined)
	}
	if history := byPath[fileKey(pcOnly)]; N(history, "count") != 1 || !B(history, "git") {
		t.Fatal("PC-only folder or worktree was omitted", history)
	}
}

func TestMetaReturnsPCDefaultsAndClaudeCachedModel(t *testing.T) {
	claudeHome, codexHome := t.TempDir(), t.TempDir()
	t.Setenv("CLAUDE_CONFIG_DIR", claudeHome)
	t.Setenv("CODEX_HOME", codexHome)
	path := filepath.Join(claudeHome, "settings.json")
	if err := atomicJSON(path, M{"effortLevel": "high", "permissions": M{"defaultMode": "plan"}}); err != nil {
		t.Fatal(err)
	}
	om := []byte("model = 'pc-model'\nmodel_reasoning_effort = \"med\"\nsandbox_mode = \"workspace-write\"\n\n[profiles.other]\nmodel = \"unrelated\"\n")
	if err := os.WriteFile(filepath.Join(codexHome, "config.toml"), om, 0600); err != nil {
		t.Fatal(err)
	}
	app := testApp(t)
	cache := M{"list": []M{{"id": "default", "isDefault": true, "description": "Current Opus · option details"}, {"id": "explicit", "label": "Chosen model"}}}
	if err := atomicJSON(filepath.Join(app.DataDir, "models-claude.json"), cache); err != nil {
		t.Fatal(err)
	}
	defaults := providerMap(migrationAPIResponse(t, app, "/api/meta")["defaults"])
	if got := providerMap(defaults["codex"]); S(got, "model") != "pc-model" || S(got, "effort") != "med" || S(got, "mode") != "auto" {
		t.Fatal("Codex PC defaults missing", got)
	}
	if got := providerMap(defaults["claude"]); S(got, "model") != "Current Opus" || S(got, "effort") != "high" || S(got, "mode") != "plan" {
		t.Fatal("Claude PC defaults missing", got)
	}
	if err := atomicJSON(path, M{"model": "explicit"}); err != nil {
		t.Fatal(err)
	}
	defaults = providerMap(migrationAPIResponse(t, app, "/api/meta")["defaults"])
	if got := S(providerMap(defaults["claude"]), "model"); got != "Chosen model" {
		t.Fatal("configured Claude model label missing", got)
	}
}

func TestClaudeManagementPreservesInstalledStatusAndScope(t *testing.T) {
	available := []M{{"pluginId": "one@community", "name": "One", "description": "Example"}, {"pluginId": "two@community", "name": "Two", "description": "Example"}}
	installed := []M{{"id": "one@community"}}
	rows := claudePluginSearchRows(available, installed, "example")
	if len(rows) != 2 || !B(rows[0], "installed") || B(rows[1], "installed") || S(rows[0], "marketplace") != "community" {
		t.Fatal("incorrect plugin search metadata", rows)
	}
	for input, want := range map[string]string{"Scope: User (available in all your projects)\r\n": "user", "  scope: Project\nType: stdio\n": "project", "Scope: Local\n": "local", "Type: stdio\n": ""} {
		if got := claudeMCPScope(input); got != want {
			t.Fatalf("scope %q, want %q", got, want)
		}
	}
}
