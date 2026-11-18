package host

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type manageRPCFixture struct {
	responses map[string]M
	last      M
}

func (p *manageRPCFixture) Available() bool { return true }
func (p *manageRPCFixture) Close() error    { return nil }
func (p *manageRPCFixture) Do(_ context.Context, action string, _ *Record, m M) (any, error) {
	if action == "manageRequest" {
		p.last = m
		return p.responses[S(m, "method")], nil
	}
	return nil, nil
}

func TestManagePluginWireShapes(t *testing.T) {
	codex := managePluginRows("codex", M{"installed": []M{{"pluginId": "example@market", "name": "Example", "version": "1.0", "enabled": false, "marketplaceName": "market"}}})
	if len(codex) != 1 || S(codex[0], "id") != "example@market" || B(codex[0], "enabled") || B(codex[0], "canToggle") {
		t.Fatalf("unexpected codex row: %v", codex)
	}
	agy := managePluginRows("antigravity", M{"plugins": []M{{"name": "example", "displayName": "Example"}}})
	if len(agy) != 1 || S(agy[0], "id") != "example" || S(agy[0], "name") != "Example" || !B(agy[0], "canToggle") {
		t.Fatalf("unexpected agy row: %v", agy)
	}
	if got := managePluginRows("antigravity", nil); got == nil {
		t.Fatal("empty list must encode []")
	}
}

func TestManageClaudePluginConfirmationSupportsLegacyText(t *testing.T) {
	const command = "echo plugin"
	hash := strings.Repeat("1", 64)
	confirmed := managePluginConfirmation(M{"text": command, "sha256": hash})
	if S(confirmed, "command") != command || S(confirmed, "sha256") != hash {
		t.Fatalf("legacy preview lost: %v", confirmed)
	}
}

func TestManageSkillRoundTripPreservesBody(t *testing.T) {
	home := t.TempDir()
	t.Setenv("CLAUDE_CONFIG_DIR", home)
	a := testApp(t)
	v, e := a.manageSkill(context.Background(), "claude", "create", M{"name": "round-trip", "description": "A: quoted description", "body": "Use this complete body.\nSecond line."})
	if e != nil {
		t.Fatal(e)
	}
	m, _ := v.(map[string]any)
	path := S(m, "path")
	raw, e := os.ReadFile(path)
	if e != nil {
		t.Fatal(e)
	}
	if !strings.Contains(string(raw), "Use this complete body.\nSecond line.") {
		t.Fatal("body lost")
	}
	name, description := manageSkillFrontmatter(string(raw))
	if name != "round-trip" || description != "A: quoted description" {
		t.Fatalf("frontmatter: %s / %s", name, description)
	}
	rows := manageSkills("claude")
	if len(rows) != 1 || S(rows[0], "description") != description {
		t.Fatal("description missing")
	}
	if _, e = a.manageSkill(context.Background(), "claude", "write", M{"path": path, "text": "lost frontmatter"}); e == nil {
		t.Fatal("removed metadata")
	}
	if _, e = a.manageSkill(context.Background(), "claude", "delete", M{"path": path}); e != nil {
		t.Fatal(e)
	}
	if _, e = os.Stat(filepath.Dir(path)); !os.IsNotExist(e) {
		t.Fatal("skill directory left behind")
	}
}

func TestManageMCPMasksUnknownEnvironmentAndHeaders(t *testing.T) {
	value := M{"command": "tool", "env": M{"CUSTOM_VALUE": "sensitive", "nested": M{"x": "sensitive2"}}, "http_headers": M{"X-Custom": "private"}, "args": []any{"normal"}}
	masked := manageMaskDeep(value, false)
	raw, _ := json.Marshal(masked)
	if strings.Contains(string(raw), "sensitive") || strings.Contains(string(raw), "private") || !strings.Contains(string(raw), "normal") {
		t.Fatalf("secret masking failed: %s", raw)
	}
	if S(value["env"].(M), "CUSTOM_VALUE") != "sensitive" {
		t.Fatal("masking mutated original")
	}
}

func TestManageCodexSettingsUseEffectiveRPCConfig(t *testing.T) {
	t.Setenv("CODEX_HOME", t.TempDir())
	a := testApp(t)
	fixture := &manageRPCFixture{responses: map[string]M{"config/read": {"config": M{"model": "effective-model", "model_reasoning_effort": "high"}}}}
	a.Providers["codex"] = fixture
	response := testRequest(a, "GET", "/api/manage/codex/settings", "")
	if response.Code != 200 {
		t.Fatal(response.Body.String())
	}
	var result M
	json.Unmarshal(response.Body.Bytes(), &result)
	found := false
	for _, field := range maps(result["fields"]) {
		if S(field, "key") == "model" {
			found = S(field, "value") == "effective-model"
		}
	}
	if !found {
		t.Fatal("effective config lost")
	}
	if S(fixture.last, "method") != "config/read" {
		t.Fatal("wrong RPC method")
	}
}

func TestManageInstalledPluginSkillsRemainReadonly(t *testing.T) {
	home := t.TempDir()
	t.Setenv("CLAUDE_CONFIG_DIR", home)
	plugin := filepath.Join(home, "plugins", "cache", "example", "1.0.0")
	skill := filepath.Join(plugin, "skills", "example", "SKILL.md")
	if e := os.MkdirAll(filepath.Dir(skill), 0700); e != nil {
		t.Fatal(e)
	}
	if e := os.WriteFile(skill, []byte("---\nname: example\ndescription: Installed skill\n---\n"), 0600); e != nil {
		t.Fatal(e)
	}
	registry := M{"version": 2, "plugins": M{"example@market": []M{{"installPath": plugin}}}}
	if e := atomicJSON(filepath.Join(home, "plugins", "installed_plugins.json"), registry); e != nil {
		t.Fatal(e)
	}
	rows := manageSkills("claude")
	found := false
	for _, row := range rows {
		if S(row, "path") == skill {
			found = true
			if B(row, "editable") {
				t.Fatal("plugin skill is writable")
			}
		}
	}
	if !found {
		t.Fatal("installed plugin skill missing")
	}
	want, err := filepath.EvalSymlinks(skill)
	if err != nil {
		t.Fatal(err)
	}
	if full, e := manageReadSkillPath("claude", skill); e != nil || full != want {
		t.Fatalf("readonly skill path = %q, error = %v, want %q", full, e, want)
	}
	if _, e := manageSkillPath("claude", skill); e == nil {
		t.Fatal("readonly plugin skill accepted for modification")
	}
}
