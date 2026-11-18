package host

import (
	"path/filepath"
	"regexp"
	"strings"
)

// agy settings may contain either the CLI ID or the name shown by "agy models".
var agyEffortSuffix = regexp.MustCompile(`(?i)(?:-(low|medium|high|xhigh|max)| \((low|medium|high|xhigh|max)\))$`)

func splitAgyModel(value string) (string, string) {
	value = strings.TrimSpace(value)
	match := agyEffortSuffix.FindStringSubmatchIndex(value)
	if match == nil {
		return value, ""
	}
	level := strings.Trim(value[match[0]:], "- ()")
	return value[:match[0]], strings.ToLower(level)
}

func agyPCDefaults() M {
	settings, _ := manageRead(filepath.Join(providerHome("antigravity"), "antigravity-cli", "settings.json"))
	return agySettingsDefaults(settings)
}

func agySettingsDefaults(settings M) M {
	defaults := M{}
	name, level := splitAgyModel(S(settings, "model"))
	if !agyModelUnset(name) {
		defaults["model"] = name
	}
	if effort := strings.TrimSpace(S(settings, "effort")); effort != "" {
		level = effort
	}
	if level != "" {
		defaults["effort"] = level
	}
	return defaults
}
