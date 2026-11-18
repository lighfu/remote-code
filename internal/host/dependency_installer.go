package host

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

var dependencyNames = []struct{ target, name string }{
	{"claude", "Claude Code"}, {"codex", "Codex"}, {"antigravity", "Antigravity CLI"}, {"cloudflared", "cloudflared"},
}

func consoleInstallTarget(action ConsoleAction) string {
	switch action {
	case ConsoleInstallClaude:
		return "claude"
	case ConsoleInstallCodex:
		return "codex"
	case ConsoleInstallAntigravity:
		return "antigravity"
	case ConsoleInstallCloudflared:
		return "cloudflared"
	}
	return ""
}

type dependencyInstaller struct {
	mu      sync.Mutex
	running bool
	states  map[string]ConsoleDependency
}

func dependencyExecutable(target string) (string, error) {
	if target == "cloudflared" {
		path := cloudflaredPath()
		if path == "" {
			return "", errors.New("cloudflared is not installed")
		}
		return providerExecutable(path)
	}
	return FindProviderExecutable(target)
}

func (i *dependencyInstaller) snapshot(resolve func(string) (string, error)) []ConsoleDependency {
	states, _ := i.snapshotState(resolve)
	return states
}

func (i *dependencyInstaller) snapshotState(resolve func(string) (string, error)) ([]ConsoleDependency, bool) {
	i.mu.Lock()
	running := i.running
	states := make(map[string]ConsoleDependency, len(i.states))
	for key, state := range i.states {
		states[key] = state
	}
	i.mu.Unlock()
	out := make([]ConsoleDependency, 0, len(dependencyNames))
	for _, item := range dependencyNames {
		state := states[item.target]
		state.Target, state.Name = item.target, item.name
		state.Path, _ = resolve(item.target)
		state.Installed = state.Path != ""
		if state.Status == "" {
			state.Status = "Not installed"
			if state.Installed {
				state.Status = "Installed"
				if item.target != "cloudflared" {
					state.Detail = fmt.Sprintf("To sign in, open PowerShell and run: & '%s'", strings.ReplaceAll(state.Path, "'", "''"))
				}
			}
		}
		out = append(out, state)
	}
	return out, running
}

// Serialize installs independently of the UI so repeated clicks cannot start two installers.
func (i *dependencyInstaller) install(ctx context.Context, target string, run func(context.Context, string, func(string)) error, resolve func(string) (string, error)) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := i.begin(target); err != nil {
		return err
	}
	return i.run(ctx, target, run, resolve)
}

func (i *dependencyInstaller) begin(target string) error {
	valid := false
	for _, item := range dependencyNames {
		valid = valid || target == item.target
	}
	if !valid {
		return &HTTPError{400, "Unknown dependency"}
	}
	i.mu.Lock()
	if i.running {
		i.mu.Unlock()
		return &HTTPError{409, "Another dependency installation is already running"}
	}
	i.running = true
	if i.states == nil {
		i.states = map[string]ConsoleDependency{}
	}
	i.states[target] = ConsoleDependency{Status: "Installing...", Detail: "Checking installed CLI and Windows installation method..."}
	i.mu.Unlock()
	return nil
}

func (i *dependencyInstaller) run(ctx context.Context, target string, run func(context.Context, string, func(string)) error, resolve func(string) (string, error)) error {
	progress := func(line string) {
		line = strings.TrimSpace(line)
		if line == "" {
			return
		}
		line = strings.Map(func(r rune) rune {
			if r < 32 || r == 127 {
				return ' '
			}
			return r
		}, line)
		if len([]rune(line)) > 500 {
			line = string([]rune(line)[:500]) + "…"
		}
		i.mu.Lock()
		state := i.states[target]
		state.Detail = line
		i.states[target] = state
		i.mu.Unlock()
		consoleLogf("[Install %s] %s", target, line)
	}
	err := run(ctx, target, progress)
	if err == nil {
		if ctx.Err() != nil {
			err = ctx.Err()
		} else if path, findErr := resolve(target); findErr != nil || path == "" {
			err = errors.New("Installer completed but the native executable could not be found; check the install log and configured executable override")
		}
	}
	i.mu.Lock()
	i.running = false
	state := i.states[target]
	if err != nil {
		state.Status = "Failed"
		state.Detail = err.Error() + ": " + state.Detail
	} else {
		state.Status = "Installed"
		path, _ := resolve(target)
		state.Detail = fmt.Sprintf("Ready. To sign in, open PowerShell and run: & '%s'", strings.ReplaceAll(path, "'", "''"))
		if target == "cloudflared" {
			state.Detail = "Ready. Starting the configured Quick Tunnel if requested."
		}
	}
	i.states[target] = state
	i.mu.Unlock()
	if err != nil {
		consoleLogf("[Install %s] Failed: %v", target, err)
	}
	return err
}

func dependencyScriptPath() (string, error) {
	var candidates []string
	if exe, err := os.Executable(); err == nil {
		candidates = append(candidates, filepath.Join(filepath.Dir(exe), "scripts", "install-dependencies.ps1"))
	}
	if cwd, err := os.Getwd(); err == nil {
		if _, err := os.Stat(filepath.Join(cwd, "go.mod")); err == nil {
			candidates = append(candidates, filepath.Join(cwd, "scripts", "install-dependencies.ps1"))
		}
	}
	for _, path := range candidates {
		if info, err := os.Stat(path); err == nil && info.Mode().IsRegular() {
			return path, nil
		}
	}
	return "", errors.New("Installer script is missing; extract the complete server ZIP")
}

type dependencyProgressWriter struct {
	mu       sync.Mutex
	buffer   string
	progress func(string)
}

func (w *dependencyProgressWriter) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.buffer += string(p)
	for {
		index := strings.IndexAny(w.buffer, "\r\n")
		if index < 0 {
			break
		}
		w.progress(w.buffer[:index])
		w.buffer = w.buffer[index+1:]
	}
	if len(w.buffer) > 8192 {
		w.progress(w.buffer)
		w.buffer = ""
	}
	return len(p), nil
}

func runDependencyInstaller(ctx context.Context, target string, progress func(string)) error {
	script, err := dependencyScriptPath()
	if err != nil {
		return err
	}
	command := HostCommand(ctx, filepath.Join(os.Getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe"), "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script, "-Target", target)
	command.Dir = filepath.Dir(script)
	writer := &dependencyProgressWriter{progress: progress}
	command.Stdout, command.Stderr = writer, writer
	command.WaitDelay = 3 * time.Second
	// PowerShell invokes WinGet as a child; cancellation must stop that child too.
	command.Cancel = func() error {
		stop, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		kill := HostCommand(stop, filepath.Join(os.Getenv("SystemRoot"), "System32", "taskkill.exe"), "/PID", strconv.Itoa(command.Process.Pid), "/T", "/F")
		kill.Stdout, kill.Stderr = io.Discard, io.Discard
		if err := kill.Run(); err != nil {
			return command.Process.Kill()
		}
		return nil
	}
	err = command.Run()
	writer.mu.Lock()
	if writer.buffer != "" {
		progress(writer.buffer)
	}
	writer.mu.Unlock()
	if err != nil {
		return fmt.Errorf("Windows installer: %w", err)
	}
	return nil
}

func (c *consoleRuntime) installDependency(ctx context.Context, target string) error {
	return c.app.installDependency(ctx, target)
}

func (a *App) dependencyResolver() func(string) (string, error) {
	if a.dependencyResolve != nil {
		return a.dependencyResolve
	}
	return dependencyExecutable
}

func (a *App) dependencySnapshot() []ConsoleDependency {
	return a.dependencies.snapshot(a.dependencyResolver())
}

func (a *App) dependencyState() M {
	states, running := a.dependencies.snapshotState(a.dependencyResolver())
	items := []M{}
	for _, state := range states {
		items = append(items, M{"target": state.Target, "name": state.Name, "path": state.Path, "status": state.Status, "detail": state.Detail, "installed": state.Installed})
	}
	return M{"supported": runtime.GOOS == "windows", "installing": running, "dependencies": items}
}

func (a *App) installDependency(ctx context.Context, target string) error {
	if runtime.GOOS != "windows" {
		return &HTTPError{501, "Dependency installation is supported on Windows"}
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := a.dependencies.begin(target); err != nil {
		return err
	}
	return a.runDependencyInstall(ctx, target)
}

func (a *App) runDependencyInstall(ctx context.Context, target string) error {
	run := a.dependencyRun
	if run == nil {
		run = runDependencyInstaller
	}
	err := a.dependencies.run(ctx, target, run, a.dependencyResolver())
	consoleCurrent.RLock()
	console := consoleCurrent.value
	consoleCurrent.RUnlock()
	if console != nil && console.app == a {
		console.refresh()
	}
	if err == nil && target == "cloudflared" {
		a.retryConfiguredTunnel()
	}
	return err
}

func (a *App) startDependencyInstall(target string) error {
	if runtime.GOOS != "windows" {
		return &HTTPError{501, "Dependency installation is supported on Windows"}
	}
	if err := a.dependencies.begin(target); err != nil {
		return err
	}
	if !a.startTask(func() {
		ctx, cancel := context.WithTimeout(a.taskCtx, 20*time.Minute)
		defer cancel()
		_ = a.runDependencyInstall(ctx, target)
	}) {
		// No task was started; release the synchronous reservation before returning.
		a.dependencies.mu.Lock()
		a.dependencies.running = false
		a.dependencies.states[target] = ConsoleDependency{Status: "Failed", Detail: "The server is shutting down"}
		a.dependencies.mu.Unlock()
		return &HTTPError{503, "The server is shutting down"}
	}
	return nil
}
