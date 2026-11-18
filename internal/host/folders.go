package host

import (
	"context"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"sync"
	"time"
)

type folderUse struct {
	path, provider string
	at             float64
}

func (a *App) startFolderHistoryRefresh() {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.folderReading || time.Since(a.folderReadAt) < time.Minute {
		return
	}
	a.folderReading = a.startTask(a.refreshFolderHistory)
}

func (a *App) refreshFolderHistory() {
	var requests sync.WaitGroup
	for name, provider := range a.Providers {
		requests.Go(func() {
			ctx, cancel := context.WithTimeout(a.taskCtx, 20*time.Second)
			defer cancel()
			value, err := provider.Do(ctx, "listHistory", nil, M{})
			if err == nil {
				a.rememberFolderHistory(name, maps(value))
			}
		})
	}
	requests.Wait()
	a.mu.Lock()
	a.folderReading = false
	a.folderReadAt = time.Now()
	a.mu.Unlock()
}

func (a *App) rememberFolderHistory(provider string, history []M) {
	folders := []folderUse{}
	for _, row := range history {
		if path := S(row, "cwd"); path != "" {
			folders = append(folders, folderUse{path: path, provider: provider, at: N(row, "updatedAt")})
		}
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.folderHistory == nil {
		a.folderHistory = make(map[string][]folderUse)
	}
	a.folderHistory[provider] = folders
}

func (a *App) recentFolders() []M {
	type recent struct {
		path      string
		lastUsed  float64
		count     int
		providers map[string]bool
	}
	byPath := make(map[string]*recent)
	add := func(use folderUse) {
		if use.path == "" {
			return
		}
		path, err := filepath.Abs(use.path)
		if err != nil {
			return
		}
		key := fileKey(path)
		row := byPath[key]
		if row == nil {
			row = &recent{path: path, providers: make(map[string]bool)}
			byPath[key] = row
		}
		row.lastUsed = max(row.lastUsed, use.at)
		row.count++
		if use.provider != "" {
			row.providers[use.provider] = true
		}
	}
	for _, session := range a.Store.List() {
		add(folderUse{path: S(session, "cwd"), provider: S(session, "provider"), at: N(session, "updatedAt")})
	}
	a.mu.Lock()
	pcFolders := []folderUse{}
	for _, folders := range a.folderHistory {
		pcFolders = append(pcFolders, folders...)
	}
	a.mu.Unlock()
	for _, use := range pcFolders {
		add(use)
	}
	rows := make([]*recent, 0, len(byPath))
	for _, row := range byPath {
		rows = append(rows, row)
	}
	sort.Slice(rows, func(i, j int) bool {
		if rows[i].lastUsed == rows[j].lastUsed {
			return rows[i].path < rows[j].path
		}
		return rows[i].lastUsed > rows[j].lastUsed
	})
	result := []M{}
	for _, row := range rows {
		if !isDirectory(row.path) {
			continue
		}
		providers := make([]string, 0, len(row.providers))
		for name := range row.providers {
			providers = append(providers, name)
		}
		sort.Strings(providers)
		result = append(result, M{"path": row.path, "name": filepath.Base(row.path), "lastUsed": row.lastUsed, "count": row.count, "providers": providers, "git": hasGitFolder(row.path)})
		if len(result) == 30 {
			break
		}
	}
	return result
}

func quickFolderRoots(home string) []M {
	roots := []M{{"name": "Home", "path": home}}
	for _, name := range []string{"Desktop", "Documents", "Downloads", "source/repos", "projects", "dev", "src"} {
		if path := filepath.Join(home, name); isDirectory(path) {
			roots = append(roots, M{"name": name, "path": path})
		}
	}
	if runtime.GOOS == "windows" {
		for _, path := range []string{`C:\code`, `C:\dev`, `C:\src`} {
			if isDirectory(path) {
				roots = append(roots, M{"name": path, "path": path})
			}
		}
	}
	return roots
}

func hasGitFolder(path string) bool {
	_, err := os.Stat(filepath.Join(path, ".git"))
	return err == nil
}
