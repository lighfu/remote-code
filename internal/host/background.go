package host

import (
	"context"
	"time"
)

func (a *App) StartBackground(ctx context.Context) {
	a.startTask(func() { a.fx.refreshLoop(ctx, a.DataDir) })
	a.Store.mu.RLock()
	ids := append([]string{}, a.Store.resumeIDs...)
	a.Store.mu.RUnlock()
	a.startTask(func() {
		for _, id := range ids {
			if ctx.Err() != nil {
				return
			}
			rec := a.Store.Get(id)
			if rec == nil || B(rec.Info, "archived") {
				continue
			}
			check, cancel := context.WithTimeout(ctx, 20*time.Second)
			_, err := a.do(check, "rejoin", rec, M{})
			cancel()
			if err != nil {
				if ctx.Err() == nil && activeCodexDesktopRecord(rec.Info) {
					a.Store.clearUnconfirmedDesktopRuntime(rec)
				}
				consoleLogf("[session] Reconnection deferred: %v", err)
			}
		}
		if p, ok := a.Providers["codex"].(*nativeProvider); ok && ctx.Err() == nil {
			check, cancel := context.WithTimeout(ctx, 30*time.Second)
			err := p.rejoinLoadedCodex(check)
			cancel()
			if err != nil && ctx.Err() == nil {
				consoleLogf("[session] Shared Codex reconnection deferred: %v", err)
			}
		}
	})
	a.startTask(func() {
		ticker := time.NewTicker(4 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-a.closed:
				return
			case <-ticker.C:
				a.refreshActiveClaudePlans(ctx)
			}
		}
	})
	a.startTask(func() {
		ticker := time.NewTicker(30 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-a.closed:
				return
			case <-ticker.C:
				for _, info := range a.Store.List() {
					if ctx.Err() != nil {
						return
					}
					if !B(info, "archived") && S(info, "externalId") != "" {
						a.refreshAnalytics(&Record{Info: info})
						if B(info, "live") {
							a.refreshLiveContext(ctx, &Record{Info: info})
						}
					}
				}
			}
		}
	})
}

func (a *App) refreshActiveClaudePlans(ctx context.Context) {
	a.Store.mu.RLock()
	var active []*Record
	for _, rec := range a.Store.records {
		info := rec.Info
		status := S(info, "status")
		if S(info, "provider") == "claude" && !B(info, "archived") && S(info, "externalId") != "" &&
			(B(info, "live") || B(info, "watching") || status == "running" || status == "waiting") {
			active = append(active, &Record{Info: clone(info)})
		}
	}
	a.Store.mu.RUnlock()
	for _, rec := range active {
		if ctx.Err() != nil {
			return
		}
		a.refreshClaudePlan(rec)
	}
}
