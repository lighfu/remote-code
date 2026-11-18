package host

import "testing"

func TestAgySettingsDefaultModel(t *testing.T) {
	for _, tc := range []struct {
		settings     M
		name, effort string
	}{
		{M{"model": "Gemini 4 Argon (Medium)"}, "Gemini 4 Argon", "medium"},
		{M{"model": "gemini-4-argon-high", "effort": "low"}, "gemini-4-argon", "low"},
		{M{"model": "Orchestrator (Carbon)"}, "Orchestrator (Carbon)", ""},
		{M{"model": "default"}, "", ""},
		{M{}, "", ""},
	} {
		got := agySettingsDefaults(tc.settings)
		if S(got, "model") != tc.name || S(got, "effort") != tc.effort {
			t.Errorf("settings %v yielded %v", tc.settings, got)
		}
	}
}
