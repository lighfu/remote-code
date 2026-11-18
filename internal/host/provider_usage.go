package host

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

func (p *nativeProvider) rememberUsage(u M) {
	p.cacheUsage(u)
	if p.usageObserved != nil {
		p.usageObserved(u)
	} else {
		p.store.Emit(M{"type": "usage", "provider": p.name, "usage": u})
	}
}

func (p *nativeProvider) cacheUsage(u M) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if N(p.usage, "at") > N(u, "at") {
		return
	}
	p.usage = u
	p.usageUntil = time.Now().Add(5 * time.Minute)
	if p.name == "codex" {
		p.usageUntil = time.Now().Add(2 * time.Minute)
	}
	if S(u, "error") != "" {
		p.usageUntil = time.Now().Add(30 * time.Second)
	}
}
func (p *nativeProvider) readUsage(ctx context.Context, force bool) (any, error) {
	p.usageMu.Lock()
	defer p.usageMu.Unlock()
	p.mu.Lock()
	cached := p.usage
	valid := time.Now().Before(p.usageUntil)
	p.mu.Unlock()
	if !force && cached != nil && valid {
		return cached, nil
	}
	var result M
	var err error
	switch p.name {
	case "claude":
		result, err = p.fetchClaudeUsage(ctx)
		if err != nil || len(providerRows(result["windows"])) == 0 {
			fallback, fallbackErr := p.nativeClaudeUsage(ctx)
			if fallbackErr == nil && len(providerRows(fallback["windows"])) > 0 {
				if result != nil && result["tickets"] != nil {
					fallback["tickets"] = result["tickets"]
				} else if cached != nil {
					fallback["tickets"] = cached["tickets"]
				}
				result = fallback
				err = nil
			} else if fallbackErr != nil {
				err = fallbackErr
			}
		}
	case "codex":
		var w *providerWire
		w, err = p.ensureRPC(ctx)
		if err == nil {
			var raw M
			raw, err = w.request(ctx, "account/rateLimits/read", M{})
			if err == nil {
				result = p.codexUsage(raw)
			}
		}
	case "antigravity":
		var bin string
		bin, err = FindProviderExecutable(p.name)
		if err == nil {
			c, cancel := context.WithTimeout(ctx, 90*time.Second)
			defer cancel()
			cmd := HostCommand(c, bin, "-p", "/usage", "--print-timeout", "60s")
			cmd.Dir = os.TempDir()
			var out []byte
			out, err = cmd.Output()
			if err == nil {
				result = parseAgyUsage(string(out))
			}
		}
	}
	if err == nil && S(result, "error") != "" {
		err = errors.New(S(result, "error"))
	}
	if err != nil {
		if cached != nil {
			previous := clone(cached)
			previous["error"] = err.Error()
			p.cacheUsage(previous)
			return previous, nil
		}
		failed := M{"windows": []M{}, "at": providerNow(), "error": err.Error()}
		p.cacheUsage(failed)
		return failed, nil
	}
	p.cacheUsage(result)
	return result, nil
}
func (p *nativeProvider) nativeClaudeUsage(ctx context.Context) (M, error) {
	p.mu.Lock()
	var wire *providerWire
	for _, runtime := range p.sessions {
		if w := runtime.Wire(); w != nil {
			select {
			case <-w.done:
			default:
				wire = w
			}
			if wire != nil {
				break
			}
		}
	}
	p.mu.Unlock()
	if wire != nil {
		raw, err := wire.request(ctx, "get_usage", M{"skip_behaviors": true})
		if err == nil {
			result := parseClaudeUsage(raw)
			if len(providerRows(result["windows"])) > 0 {
				return result, nil
			}
		}
	}
	bin, err := FindProviderExecutable("claude")
	if err != nil {
		return nil, err
	}
	home, _ := os.UserHomeDir()
	probe, err := startProviderWire(bin, p.claudeArgs(&Record{Info: M{}}, true), home, true, func(M) {})
	if err != nil {
		return nil, err
	}
	defer probe.close()
	bounded, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	if _, err = probe.request(bounded, "initialize", M{}); err != nil {
		return nil, err
	}
	raw, err := probe.request(bounded, "get_usage", M{"skip_behaviors": true})
	if err != nil {
		return nil, err
	}
	result := parseClaudeUsage(raw)
	if len(providerRows(result["windows"])) == 0 {
		return nil, errors.New("Claude Code returned no subscription usage windows")
	}
	return result, nil
}
func providerResetTime(value any) any {
	if value == nil {
		return nil
	}
	if n, ok := value.(float64); ok {
		if n <= 0 {
			return nil
		}
		if n < 1e12 {
			return int64(n * 1000)
		}
		return int64(n)
	}
	if s, ok := value.(string); ok {
		if t, err := time.Parse(time.RFC3339Nano, strings.TrimSpace(s)); err == nil {
			return t.UnixMilli()
		}
	}
	return nil
}
func (p *nativeProvider) fetchClaudeUsage(ctx context.Context) (M, error) {
	home, _ := os.UserHomeDir()
	dir := os.Getenv("CLAUDE_CONFIG_DIR")
	if dir == "" {
		dir = filepath.Join(home, ".claude")
	}
	data, err := os.ReadFile(filepath.Join(dir, ".credentials.json"))
	if err != nil {
		return nil, errors.New("Not logged into Claude Code on PC")
	}
	var creds M
	if json.Unmarshal(data, &creds) != nil {
		return nil, errors.New("Failed to read Claude Code credentials")
	}
	token := S(providerMap(creds["claudeAiOauth"]), "accessToken")
	if token == "" {
		return nil, errors.New("Claude Code login credentials not found")
	}
	version := "2.1.322"
	if bin, e := FindProviderExecutable("claude"); e == nil {
		c, cancel := context.WithTimeout(ctx, 5*time.Second)
		out, _ := HostCommand(c, bin, "--version").Output()
		cancel()
		parts := strings.Fields(string(out))
		if len(parts) > 0 {
			version = parts[0]
		}
	}
	c, cancel := context.WithTimeout(ctx, 12*time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(c, "GET", "https://api.anthropic.com/api/oauth/usage?at_wall=1&skip_spend=1", nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Authorization", "Bearer "+token)
	request.Header.Set("anthropic-beta", "oauth-2025-04-20")
	request.Header.Set("anthropic-version", "2023-06-01")
	request.Header.Set("User-Agent", "claude-cli/"+version+" (external, cli)")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		return nil, fmt.Errorf("Claude usage API returned HTTP %d", response.StatusCode)
	}
	var payload M
	if err = json.NewDecoder(io.LimitReader(response.Body, 4*1024*1024)).Decode(&payload); err != nil {
		return nil, err
	}
	return parseClaudeUsage(payload), nil
}
func parseClaudeUsage(payload M) M {
	rates := providerMap(payload["rate_limits"])
	if len(rates) == 0 {
		rates = payload
	}
	windows := []M{}
	for _, row := range providerRows(rates["limits"]) {
		percent, known := row["percent"].(float64)
		if !known || percent < 0 || percent > 100 {
			continue
		}
		scope := providerMap(row["scope"])
		name := S(providerMap(scope["model"]), "display_name")
		if name == "" {
			name = S(providerMap(scope["surface"]), "display_name")
		}
		window := M{"usedPercent": percent, "resetsAt": providerResetTime(row["resets_at"])}
		kind := S(row, "kind")
		group := S(row, "group")
		label := "Weekly"
		switch {
		case kind == "session" || group == "session":
			window["labelKey"] = "hours"
			window["labelCount"] = 5
			label = "5h"
		case kind == "weekly_all":
			window["labelKey"] = "week"
			window["scopeKey"] = "all_models"
		case kind == "weekly_scoped" || group == "weekly":
			window["labelKey"] = "week"
			if name == "" {
				window["scopeKey"] = "by_model"
			}
		default:
			if S(row, "label") == "" && name == "" {
				continue
			}
			label = S(row, "label")
		}
		if name != "" {
			window["scope"] = name
			label += " (" + name + ")"
		}
		window["label"] = label
		windows = append(windows, window)
	}
	if len(windows) == 0 {
		for _, key := range []string{"five_hour", "seven_day", "seven_day_opus", "seven_day_sonnet", "seven_day_oauth_apps"} {
			row := providerMap(rates[key])
			percent, ok := row["utilization"].(float64)
			if !ok || percent < 0 || percent > 100 {
				continue
			}
			window := M{"label": "Weekly", "labelKey": "week", "usedPercent": percent, "resetsAt": providerResetTime(row["resets_at"])}
			switch key {
			case "five_hour":
				window["label"] = "5h"
				window["labelKey"] = "hours"
				window["labelCount"] = 5
			case "seven_day":
				window["scopeKey"] = "all_models"
			case "seven_day_opus":
				window["scope"] = "Opus"
			case "seven_day_sonnet":
				window["scope"] = "Sonnet"
			case "seven_day_oauth_apps":
				window["scopeKey"] = "oauth_apps"
			}
			windows = append(windows, window)
		}
		for _, row := range providerRows(rates["model_scoped"]) {
			if percent, ok := row["utilization"].(float64); ok {
				windows = append(windows, M{"label": "Weekly (" + S(row, "display_name") + ")", "labelKey": "week", "scope": S(row, "display_name"), "usedPercent": percent, "resetsAt": providerResetTime(row["resets_at"])})
			}
		}
	}
	items := []M{}
	ce := providerMap(payload["cedar_ember"])
	if B(ce, "eligible") {
		for _, grant := range providerRows(ce["grants"]) {
			if B(grant, "paused") {
				continue
			}
			count := N(grant, "resets_left")
			if value, ok := grant["resets_left"].(string); ok {
				count, _ = strconv.ParseFloat(value, 64)
			}
			key := "weekly_model"
			title := "Reset ticket (Per-model weekly limit)"
			if values, ok := grant["clears"].([]any); ok {
				for _, value := range values {
					if value == "seven_day" {
						key = "weekly_all"
						title = "Reset ticket (Weekly limit · All models)"
					}
				}
			}
			for i := 0; i < int(min(1000, count)); i++ {
				items = append(items, M{"title": title, "titleKey": key, "expiresAt": providerResetTime(grant["ends_at"])})
			}
		}
	}
	jt := providerMap(payload["juniper_tide"])
	if B(jt, "eligible") && B(jt, "available") {
		items = append(items, M{"title": "5-hour limit reset (once a week)", "titleKey": "session_weekly", "expiresAt": nil})
	}
	result := M{"windows": windows, "tickets": nil, "plan": payload["subscription_type"], "at": providerNow()}
	if len(items) > 0 {
		result["tickets"] = M{"available": len(items), "items": items}
	}
	if len(windows) > 0 {
		result["note"] = "Using subscription"
		result["noteKey"] = "subscription"
	} else {
		result["error"] = "Failed to read usage stats"
	}
	return result
}
func (p *nativeProvider) codexUsage(raw M) M {
	snap := providerMap(raw["rateLimits"])
	if len(snap) == 0 {
		snap = providerMap(providerMap(raw["rateLimitsByLimitId"])["codex"])
	}
	if len(snap) == 0 {
		snap = raw
	}
	windows := []M{}
	for _, key := range []string{"primary", "secondary"} {
		window := providerMap(snap[key])
		if len(window) == 0 {
			continue
		}
		mins := N(window, "windowDurationMins")
		labelKey := "short_term"
		label := "Short-term"
		if key == "secondary" {
			labelKey = "long_term"
			label = "Long-term"
		}
		row := M{"usedPercent": N(window, "usedPercent"), "resetsAt": providerResetTime(window["resetsAt"])}
		if mins >= 10020 {
			labelKey = "week"
			label = "Weekly"
		} else if mins >= 1440 {
			labelKey = "days"
			row["labelCount"] = int(mins/1440 + 0.5)
			label = fmt.Sprintf("%.0fd", mins/1440)
		} else if mins > 0 {
			labelKey = "hours"
			row["labelCount"] = int(mins/60 + 0.5)
			label = fmt.Sprintf("%.0fh", mins/60)
		}
		row["label"] = label
		row["labelKey"] = labelKey
		windows = append(windows, row)
	}
	p.mu.Lock()
	previous := p.usage
	p.mu.Unlock()
	result := M{"windows": windows, "credits": snap["credits"], "tickets": nil, "plan": snap["planType"], "at": providerNow()}
	if previous != nil {
		for _, key := range []string{"tickets", "credits", "plan"} {
			if result[key] == nil {
				result[key] = previous[key]
			}
		}
	}
	summary := providerMap(raw["rateLimitResetCredits"])
	count, known := summary["availableCount"].(float64)
	if text, ok := summary["availableCount"].(string); ok {
		if n, e := strconv.ParseFloat(text, 64); e == nil {
			count = n
			known = true
		}
	}
	if known && count >= 0 {
		items := []M{}
		for _, credit := range providerRows(summary["credits"]) {
			if S(credit, "status") != "available" {
				continue
			}
			row := M{"title": S(credit, "title"), "expiresAt": providerResetTime(credit["expiresAt"])}
			if S(row, "title") == "" {
				row["title"] = "Reset quota"
				row["titleKey"] = "reset_limit"
			}
			items = append(items, row)
		}
		result["tickets"] = M{"available": count, "items": items}
	}
	return result
}
