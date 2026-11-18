package host

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

type providerReply struct {
	value M
	err   error
}
type providerWire struct {
	mu              sync.Mutex
	writeMu         sync.Mutex
	cmd             *exec.Cmd
	in              io.WriteCloser
	waiting         map[string]chan providerReply
	claude          bool
	shared          bool
	done            chan struct{}
	onMessage       func(M)
	sendFunc        func(M) error
	sendContextFunc func(context.Context, M) error
	closeFunc       func() error
}

func providerMap(v any) M {
	if m, ok := v.(map[string]any); ok && m != nil {
		return m
	}
	return M{}
}
func providerRows(v any) []M {
	if a, ok := v.([]M); ok {
		return a
	}
	a, _ := v.([]any)
	rows := make([]M, 0, len(a))
	for _, v := range a {
		if m, ok := v.(map[string]any); ok {
			rows = append(rows, m)
		}
	}
	return rows
}
func providerPretty(v any) string { b, _ := json.MarshalIndent(v, "", "  "); return string(b) }
func providerText(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	parts := []string{}
	for _, p := range providerRows(v) {
		if s := S(p, "text"); s != "" {
			parts = append(parts, s)
		}
	}
	return strings.Join(parts, "\n")
}
func providerNow() int64 { return time.Now().UnixMilli() }

// Resolve native CLI binaries directly; npm wrappers must not introduce Node.js.
func FindProviderExecutable(name string) (string, error) {
	override := map[string]string{"claude": "CLAUDE_BIN", "codex": "CODEX_BIN", "antigravity": "AGY_BIN"}[name]
	if v := os.Getenv(override); v != "" {
		return providerExecutable(v)
	}
	home, _ := os.UserHomeDir()
	leaf := name
	if name == "antigravity" {
		leaf = "agy"
	}
	if runtime.GOOS == "windows" {
		leaf += ".exe"
	}
	paths := []string{filepath.Join(home, ".local", "bin", leaf), filepath.Join(home, ".gemini", "bin", leaf)}
	if name == "antigravity" {
		paths = append(paths, filepath.Join(os.Getenv("LOCALAPPDATA"), "agy", "bin", leaf))
	}
	if p, err := exec.LookPath(leaf); err == nil {
		paths = append(paths, p)
	}
	if name == "codex" && runtime.GOOS == "windows" {
		patterns := []string{filepath.Join(os.Getenv("ProgramFiles"), "WindowsApps", "OpenAI.Codex*", "resources", "codex.exe"), filepath.Join(os.Getenv("ProgramFiles"), "WindowsApps", "OpenAI.Codex*", "app", "resources", "codex.exe"), filepath.Join(os.Getenv("LOCALAPPDATA"), "Programs", "Codex", "resources", "codex.exe")}
		moduleRoots := []string{filepath.Join(os.Getenv("APPDATA"), "npm", "node_modules")}
		if wrapper, e := exec.LookPath("codex.cmd"); e == nil {
			moduleRoots = append(moduleRoots, filepath.Join(filepath.Dir(wrapper), "node_modules"))
		}
		for _, modules := range moduleRoots {
			for _, root := range []string{filepath.Join(modules, "@openai", "codex"), filepath.Join(modules, "@openai", "codex-win32-x64"), filepath.Join(modules, "@openai", "codex", "node_modules", "@openai", "codex-win32-x64")} {
				for _, folder := range []string{"bin", "codex"} {
					patterns = append(patterns, filepath.Join(root, "vendor", "*", folder, "codex.exe"))
				}
			}
		}
		for _, pattern := range patterns {
			matches, _ := filepath.Glob(pattern)
			paths = append(paths, matches...)
		}
	}
	for _, p := range paths {
		if found, err := providerExecutable(p); err == nil {
			return found, nil
		}
	}
	return "", fmt.Errorf("%s native CLI not found. Install it on this PC or set %s to its executable", name, override)
}
func providerExecutable(path string) (string, error) {
	p, err := filepath.Abs(path)
	if err != nil {
		return "", err
	}
	info, err := os.Stat(p)
	if err != nil || info.IsDir() {
		return "", fmt.Errorf("CLI executable not found: %s", p)
	}
	if runtime.GOOS == "windows" && !strings.EqualFold(filepath.Ext(p), ".exe") {
		return "", errors.New("A native .exe is required; Node.js wrappers are not supported")
	}
	return p, nil
}

func startProviderWire(path string, args []string, cwd string, claude bool, onMessage func(M)) (*providerWire, error) {
	return startProviderWireContext(context.Background(), path, args, cwd, claude, onMessage)
}

func startProviderWireContext(ctx context.Context, path string, args []string, cwd string, claude bool, onMessage func(M)) (*providerWire, error) {
	cmd := HostCommand(ctx, path, args...)
	cmd.Dir = cwd
	cmd.Env = os.Environ()
	if claude {
		env := cmd.Env[:0]
		for _, v := range cmd.Env {
			if !strings.HasPrefix(v, "CLAUDECODE=") {
				env = append(env, v)
			}
		}
		cmd.Env = append(env, "CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING=true")
	}
	in, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	out, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return nil, err
	}
	w := &providerWire{cmd: cmd, in: in, waiting: map[string]chan providerReply{}, claude: claude, done: make(chan struct{}), onMessage: onMessage}
	if err = cmd.Start(); err != nil {
		return nil, err
	}
	go func() {
		scanner := bufio.NewScanner(stderr)
		scanner.Buffer(make([]byte, 4096), 1024*1024)
		for scanner.Scan() {
			line := scanner.Text()
			if len(line) > 400 {
				line = line[:400]
			}
			consoleLogf("[%s] %s", filepath.Base(path), line)
		}
	}()
	go func() {
		scanner := bufio.NewScanner(out)
		scanner.Buffer(make([]byte, 64*1024), 32*1024*1024)
		for scanner.Scan() {
			var msg M
			if json.Unmarshal(scanner.Bytes(), &msg) == nil {
				w.receive(msg)
			}
		}
		scanErr := scanner.Err()
		waitErr := cmd.Wait()
		if scanErr != nil {
			waitErr = scanErr
		}
		if waitErr == nil {
			waitErr = io.EOF
		}
		w.mu.Lock()
		for id, c := range w.waiting {
			c <- providerReply{err: waitErr}
			delete(w.waiting, id)
		}
		close(w.done)
		w.mu.Unlock()
		onMessage(M{"_exit": waitErr.Error()})
	}()
	return w, nil
}
func (w *providerWire) receive(msg M) {
	key := ""
	result := M{}
	var err error
	if w.claude && S(msg, "type") == "control_response" {
		r := providerMap(msg["response"])
		key = S(r, "request_id")
		result = providerMap(r["response"])
		if S(r, "subtype") == "error" {
			err = errors.New(S(r, "error"))
		}
	} else if msg["id"] != nil && msg["method"] == nil {
		key = fmt.Sprint(msg["id"])
		result = providerMap(msg["result"])
		if e := providerMap(msg["error"]); len(e) > 0 {
			err = errors.New(S(e, "message"))
		}
	}
	if key != "" {
		w.mu.Lock()
		c := w.waiting[key]
		delete(w.waiting, key)
		w.mu.Unlock()
		if c != nil {
			c <- providerReply{value: result, err: err}
		}
		return
	}
	w.onMessage(msg)
}
func (w *providerWire) write(msg M) error {
	return w.writeContext(context.Background(), msg)
}
func (w *providerWire) writeContext(ctx context.Context, msg M) error {
	if w.sendContextFunc != nil {
		return w.sendContextFunc(ctx, msg)
	}
	w.writeMu.Lock()
	defer w.writeMu.Unlock()
	if err := ctx.Err(); err != nil {
		return err
	}
	if w.sendFunc != nil {
		return w.sendFunc(msg)
	}
	b, err := json.Marshal(msg)
	if err != nil {
		return err
	}
	_, err = w.in.Write(append(b, '\n'))
	return err
}
func (w *providerWire) request(ctx context.Context, method string, params M) (M, error) {
	id := NewID()
	c := make(chan providerReply, 1)
	w.mu.Lock()
	select {
	case <-w.done:
		w.mu.Unlock()
		return nil, errors.New("Agent process is not running")
	default:
	}
	w.waiting[id] = c
	w.mu.Unlock()
	msg := M{"jsonrpc": "2.0", "id": id, "method": method, "params": params}
	if w.claude {
		request := M{"subtype": method}
		for k, v := range params {
			request[k] = v
		}
		msg = M{"type": "control_request", "request_id": id, "request": request}
	}
	defer func() { w.mu.Lock(); delete(w.waiting, id); w.mu.Unlock() }()
	if err := w.writeContext(ctx, msg); err != nil {
		return nil, err
	}
	select {
	case r := <-c:
		return r.value, r.err
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-w.done:
		return nil, errors.New("Agent process exited")
	}
}
func (w *providerWire) reply(id any, value M) error {
	if w.claude {
		return w.write(M{"type": "control_response", "response": M{"subtype": "success", "request_id": id, "response": value}})
	}
	return w.write(M{"jsonrpc": "2.0", "id": id, "result": value})
}
func (w *providerWire) close() error {
	if w.closeFunc != nil {
		return w.closeFunc()
	}
	_ = w.in.Close()
	select {
	case <-w.done:
		return nil
	case <-time.After(2 * time.Second):
		return w.cmd.Process.Kill()
	}
}
