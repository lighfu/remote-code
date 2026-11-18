package host

import (
	"context"
	"fmt"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
	"unicode"
)

type consoleRuntime struct {
	mu        sync.Mutex
	app       *App
	url       string
	providers map[string]bool
	logs      []string
	active    bool
}

var consoleCurrent struct {
	sync.RWMutex
	value *consoleRuntime
}

type consoleLogWriter struct{}

func (consoleLogWriter) Write(p []byte) (int, error) {
	consoleLogf("%s", strings.TrimRight(string(p), "\r\n"))
	return len(p), nil
}

func attachConsole(a *App) *consoleRuntime {
	c := &consoleRuntime{app: a, url: a.Config.PublicURL, providers: map[string]bool{}, active: true}
	c.refresh()
	consoleCurrent.Lock()
	consoleCurrent.value = c
	consoleCurrent.Unlock()
	return c
}
func (c *consoleRuntime) detach() {
	consoleCurrent.Lock()
	if consoleCurrent.value == c {
		consoleCurrent.value = nil
	}
	consoleCurrent.Unlock()
	c.mu.Lock()
	c.active = false
	c.mu.Unlock()
}
func consoleLogf(format string, args ...any) {
	text := fmt.Sprintf(format, args...)
	consoleCurrent.RLock()
	c := consoleCurrent.value
	consoleCurrent.RUnlock()
	if c == nil {
		fmt.Fprintln(os.Stderr, text)
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, line := range strings.Split(strings.TrimRight(text, "\r\n"), "\n") {
		line = strings.Map(func(r rune) rune {
			if unicode.IsControl(r) && r != '\t' {
				return -1
			}
			return r
		}, line)
		if c.app.Config.Token != "" {
			line = strings.ReplaceAll(line, c.app.Config.Token, "[token]")
		}
		if secret := c.app.Config.AccessClientSecret; secret != "" {
			line = strings.ReplaceAll(line, secret, "[secret]")
		}
		if len(line) > 2000 {
			line = string([]rune(line)[:min(1500, len([]rune(line)))]) + "…"
		}
		c.logs = append(c.logs, time.Now().Format("15:04:05")+"  "+line)
	}
	if len(c.logs) > 300 {
		c.logs = append([]string{}, c.logs[len(c.logs)-300:]...)
	}
}
func consoleURLReady(a *App, target string) bool {
	consoleCurrent.RLock()
	c := consoleCurrent.value
	consoleCurrent.RUnlock()
	if c == nil || c.app != a {
		return false
	}
	c.mu.Lock()
	c.url = target
	active := c.active
	c.mu.Unlock()
	return active
}
func (c *consoleRuntime) refresh() {
	values := map[string]bool{}
	for name, p := range c.app.Providers {
		values[name] = p.Available()
	}
	c.mu.Lock()
	c.providers = values
	c.mu.Unlock()
}
func (c *consoleRuntime) snapshot() ConsoleSnapshot {
	c.mu.Lock()
	target := c.url
	logs := append([]string{}, c.logs...)
	providers := map[string]bool{}
	for name, value := range c.providers {
		providers[name] = value
	}
	c.mu.Unlock()
	a := c.app
	if target == "" {
		target = fmt.Sprintf("http://127.0.0.1:%d", a.Config.Port)
	}
	host, _ := os.Hostname()
	rows := a.Store.List()
	running, waiting := 0, 0
	for _, row := range rows {
		switch S(row, "status") {
		case "running":
			running++
		case "waiting":
			waiting++
		}
	}
	terminals := 0
	if value, ok := terminalSets.Load(a); ok {
		set := value.(*terminalSet)
		set.mu.Lock()
		for _, t := range set.terms {
			t.mu.Lock()
			if B(t.info, "alive") {
				terminals++
			}
			t.mu.Unlock()
		}
		set.mu.Unlock()
	}
	snapshot := ConsoleSnapshot{Version: Version, Host: host, URL: target, PairLink: platformPair(a, target), Port: a.Config.Port, Providers: providers, Sessions: len(rows), Running: running, Waiting: waiting, Terminals: terminals, Logs: logs}
	snapshot.InstallerSupported = runtime.GOOS == "windows"
	snapshot.Dependencies = c.app.dependencySnapshot()
	if info := platformAPK(a); info != nil {
		snapshot.APKURL = strings.TrimRight(target, "/") + platformDownloadPath(a.Config.Token)
		snapshot.APKVersion = S(info, "versionName")
	}
	return snapshot
}
func consoleCopy(ctx context.Context, text string) error {
	var command *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		command = HostCommand(ctx, filepath.Join(os.Getenv("SystemRoot"), "System32", "clip.exe"))
	case "darwin":
		command = HostCommand(ctx, "pbcopy")
	default:
		if path, err := exec.LookPath("wl-copy"); err == nil {
			command = HostCommand(ctx, path)
		} else if path, err := exec.LookPath("xclip"); err == nil {
			command = HostCommand(ctx, path, "-selection", "clipboard")
		} else {
			return fmt.Errorf("Clipboard is not available in this terminal")
		}
	}
	command.Stdin = strings.NewReader(text)
	if err := command.Run(); err != nil {
		return fmt.Errorf("Could not copy the link: %w", err)
	}
	return nil
}
func consoleOpen(ctx context.Context, target string) error {
	u, err := url.Parse(target)
	if err != nil || u.Host == "" || u.User != nil || (u.Scheme != "http" && u.Scheme != "https") {
		return fmt.Errorf("Invalid server URL")
	}
	u.Path = strings.TrimRight(u.Path, "/") + "/api/health"
	u.RawQuery = ""
	u.Fragment = ""
	var command *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		command = HostCommand(ctx, filepath.Join(os.Getenv("SystemRoot"), "System32", "rundll32.exe"), "url.dll,FileProtocolHandler", u.String())
	case "darwin":
		command = HostCommand(ctx, "open", u.String())
	default:
		command = HostCommand(ctx, "xdg-open", u.String())
	}
	return command.Run()
}
