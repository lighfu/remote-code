package host

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

func (p *nativeProvider) clearClaudeIdle(r *nativeSession) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.claudeIdleTimer != nil {
		r.claudeIdleTimer.Stop()
		r.claudeIdleTimer = nil
	}
}

func (p *nativeProvider) armClaudeIdle(sid string, r *nativeSession) {
	if r.Remote() || p.session(sid) != r {
		return
	}
	delay := 30 * time.Minute
	if cfg, err := LoadConfig(p.data); err == nil {
		if minutes, ok := cfg.Raw["idleMinutes"].(float64); ok && minutes >= 0 && minutes < float64(1<<63)/float64(time.Minute) {
			delay = time.Duration(minutes * float64(time.Minute))
		}
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.claudeIdleTimer != nil {
		r.claudeIdleTimer.Stop()
	}
	var timer *time.Timer
	timer = time.AfterFunc(delay, func() {
		p.startMu.Lock()
		defer p.startMu.Unlock()
		r.mu.Lock()
		current := r.claudeIdleTimer == timer
		if current {
			r.claudeIdleTimer = nil
		}
		r.mu.Unlock()
		if !current || p.session(sid) != r {
			return
		}
		if rec := p.store.Get(sid); rec != nil && S(rec.Info, "status") == "idle" {
			_ = p.detach(context.Background(), rec)
		}
	})
	r.claudeIdleTimer = timer
}

type claudeCommandSnapshot struct {
	At   int64 `json:"at"`
	List []M   `json:"list"`

	refresh    *claudeCommandProbe
	retryAfter time.Time
}

type claudeCommandProbe struct {
	done chan struct{}
	err  error
}

func claudeCommandScope(cwd string) (path, key string) {
	if cwd == "" {
		cwd, _ = os.UserHomeDir()
	}
	path, err := filepath.Abs(cwd)
	if err != nil {
		path = filepath.Clean(cwd)
	}
	key = path
	if runtime.GOOS == "windows" {
		key = strings.ToLower(key)
	}
	return path, key
}

func (p *nativeProvider) claudeCommandCacheFile(key string) string {
	hash := sha256.Sum256([]byte(key))
	return filepath.Join(p.data, fmt.Sprintf("commands-claude-%x.json", hash[:12]))
}

func (p *nativeProvider) saveClaudeCommands(cwd string, commands []M) {
	_, key := claudeCommandScope(cwd)
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.claudeCommands == nil {
		p.claudeCommands = map[string]claudeCommandSnapshot{}
	}
	snapshot := p.claudeCommands[key]
	snapshot.List = commands
	snapshot.At = providerNow()
	snapshot.retryAfter = time.Time{}
	p.claudeCommands[key] = snapshot
	_ = atomicJSON(p.claudeCommandCacheFile(key), snapshot)
}

func (p *nativeProvider) listClaudeCommands(ctx context.Context, rec *Record) ([]M, error) {
	cwd := ""
	if rec != nil {
		cwd = S(rec.Info, "cwd")
	}
	path, key := claudeCommandScope(cwd)
	p.mu.Lock()
	if p.claudeCommands == nil {
		p.claudeCommands = map[string]claudeCommandSnapshot{}
	}
	snapshot, loaded := p.claudeCommands[key]
	if !loaded {
		if data, err := os.ReadFile(p.claudeCommandCacheFile(key)); err == nil {
			_ = json.Unmarshal(data, &snapshot)
		}
	}
	now := time.Now()
	if snapshot.At != 0 && now.Sub(time.UnixMilli(snapshot.At)) < 10*time.Minute || now.Before(snapshot.retryAfter) {
		p.claudeCommands[key] = snapshot
		p.mu.Unlock()
		return snapshot.List, nil
	}
	refresh := snapshot.refresh
	if refresh == nil {
		refresh = &claudeCommandProbe{done: make(chan struct{})}
		snapshot.refresh = refresh
		p.claudeCommands[key] = snapshot
		go func() {
			probeCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			_, err := p.probeClaude(probeCtx, path)
			p.mu.Lock()
			defer p.mu.Unlock()
			current := p.claudeCommands[key]
			current.refresh = nil
			if err != nil || current.At == snapshot.At {
				current.retryAfter = time.Now().Add(30 * time.Second)
			}
			p.claudeCommands[key] = current
			refresh.err = err
			close(refresh.done)
		}()
	}
	p.mu.Unlock()
	if snapshot.At != 0 {
		return snapshot.List, nil
	}
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-refresh.done:
		p.mu.Lock()
		commands := p.claudeCommands[key].List
		p.mu.Unlock()
		return commands, refresh.err
	}
}
