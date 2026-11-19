package host

import (
	"os"
	"path/filepath"
	"strings"
	"time"
)

type detailChange uint8

const (
	detailUsage detailChange = 1 << iota // The same usage record feeds context and cache.
	detailGit
	detailPersisted // An incremental transcript scan already observed new usage.
)

func (s *Store) noteSessionDetails(id string, changed detailChange) {
	if changed != 0 && s.detailsChanged != nil {
		s.detailsChanged(id, changed)
	}
}

// Each active session has one worker. Events arriving during a scan schedule
// another pass, and a continuous stream cannot postpone the first refresh.
func (a *App) queueSessionDetails(id string, changed detailChange) {
	a.detailMu.Lock()
	defer a.detailMu.Unlock()
	if _, running := a.pendingDetails[id]; running {
		a.pendingDetails[id] |= changed
		return
	}
	a.pendingDetails[id] = changed
	if !a.startTask(func() { a.refreshSessionDetails(id) }) {
		delete(a.pendingDetails, id)
	}
}

func (a *App) refreshSessionDetails(id string) {
	tick := time.NewTicker(300 * time.Millisecond)
	defer tick.Stop()
	var waiting *pendingUsageFile
	for {
		select {
		case <-a.taskCtx.Done():
			return
		case <-tick.C:
		}
		a.detailMu.Lock()
		fields := a.pendingDetails[id]
		a.pendingDetails[id] = 0
		a.detailMu.Unlock()
		rec := a.Store.infoRecord(id)
		a.Store.mu.RLock()
		observed := len(a.Store.listeners) != 0
		a.Store.mu.RUnlock()
		if rec == nil || B(rec.Info, "archived") || !observed {
			waiting = nil
		}
		if rec != nil && !B(rec.Info, "archived") {
			names := []string{}
			if fields&detailUsage != 0 {
				a.invalidateSessionContext(contextKey(rec.Info))
				if observed && (waiting == nil || waiting.key != contextKey(rec.Info)) {
					waiting = newPendingUsageFile(rec)
				}
			}
			if fields&detailGit != 0 {
				gitSnapshots.invalidateWorktree(S(rec.Info, "cwd"))
				names = append(names, "git")
			}
			if waiting != nil && waiting.key != contextKey(rec.Info) {
				waiting = nil
			}
			if fields&detailPersisted != 0 || waiting != nil && waiting.ready() {
				if fields&detailPersisted == 0 && waiting.path != "" {
					a.readSessionAnalytics(rec)
				}
				a.invalidateSessionContext(contextKey(rec.Info))
				names = append(names, "context", "cache")
				waiting = nil
			}
			if observed && len(names) != 0 {
				a.Store.Emit(M{"type": "session_details", "sessionId": id, "cwd": rec.Info["cwd"], "fields": names})
			}
		}
		a.detailMu.Lock()
		if a.pendingDetails[id] == 0 && waiting == nil {
			delete(a.pendingDetails, id)
			a.detailMu.Unlock()
			return
		}
		a.detailMu.Unlock()
	}
}

// The live RPC can arrive just before a provider appends its usage record.
// Let the writer catch up before waking cache and context HTTP readers. While
// waiting, Git updates keep flowing and only file metadata is sampled.
type pendingUsageFile struct {
	key      contextCacheKey
	path     string
	baseline os.FileInfo
	until    time.Time
}

func newPendingUsageFile(rec *Record) *pendingUsageFile {
	key := contextKey(rec.Info)
	p := &pendingUsageFile{key: key, path: analyticsPath(key.provider, key.external), until: time.Now().Add(1500 * time.Millisecond)}
	if state, ok := analyticsFiles.Load(p.path); ok {
		s := state.(*analyticsState)
		s.mu.Lock()
		p.baseline = s.file
		s.mu.Unlock()
	}
	return p
}

func (p *pendingUsageFile) ready() bool {
	if p.path == "" || p.baseline == nil || !time.Now().Before(p.until) {
		return true
	}
	st, err := os.Stat(p.path)
	return err != nil || st.Size() != p.baseline.Size() || !st.ModTime().Equal(p.baseline.ModTime())
}

func sessionEntryDetails(e M) detailChange {
	switch S(e, "kind") {
	case "result":
		return detailGit | detailUsage
	case "tool":
		switch S(e, "status") {
		case "done", "error":
			switch strings.ToLower(S(e, "tool")) {
			case "filechange", "edit", "write", "multiedit", "notebookedit", "apply_patch", "command", "bash", "powershell":
				return detailGit
			}
		}
	}
	return 0
}

func (c *gitStatusCache) invalidateWorktree(cwd string) {
	if !filepath.IsAbs(cwd) {
		return
	}
	path := fileKey(filepath.Clean(cwd))
	c.mu.Lock()
	defer c.mu.Unlock()
	for key := range c.entries {
		if fileInside(key, path) || fileInside(path, key) {
			delete(c.entries, key)
		}
	}
}
