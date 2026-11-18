package host

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"
)

const codexModelsCacheSchema = 1

func (r *nativeSession) codexTurn() (string, uint64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.turn, r.codexTurnRevision
}

func (r *nativeSession) setCodexTurnFromReply(revision uint64, turn string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if turn != "" && revision == r.codexTurnRevision {
		r.turn = turn
		r.codexTurnRevision++
	}
}

func codexModeFromSettings(approval any, sandbox any) string {
	switch S(providerMap(sandbox), "type") {
	case "dangerFullAccess":
		if approval == "never" {
			return "full"
		}
	case "readOnly":
		return "read-only"
	case "workspaceWrite":
		switch approval {
		case "untrusted":
			return "untrusted"
		case "on-request":
			return "auto"
		}
	}
	return ""
}

func activeCodexTurn(rows any) string {
	for _, row := range providerRows(rows) {
		if id := S(row, "id"); S(row, "status") == "inProgress" && id != "" {
			return id
		}
	}
	return ""
}

func resumedCodexTurn(ctx context.Context, w *providerWire, reply M) (string, error) {
	thread := providerMap(reply["thread"])
	for _, rows := range []any{providerMap(reply["initialTurnsPage"])["data"], thread["turns"]} {
		if turn := activeCodexTurn(rows); turn != "" {
			return turn, nil
		}
	}
	if S(providerMap(thread["status"]), "type") != "active" || S(thread, "id") == "" {
		return "", nil
	}
	id := S(thread, "id")
	page, err := w.request(ctx, "thread/turns/list", M{"threadId": id, "limit": 1, "sortDirection": "desc", "itemsView": "notLoaded"})
	if err == nil {
		return activeCodexTurn(page["data"]), nil
	}
	if !providerUnsupported(err) {
		return "", err
	}
	legacy, err := w.request(ctx, "thread/read", M{"threadId": id, "includeTurns": true})
	return activeCodexTurn(providerMap(legacy["thread"])["turns"]), err
}

func waitCodexIdle(ctx context.Context, idle func() (bool, error)) error {
	ctx, cancel := context.WithTimeout(ctx, 12*time.Second)
	defer cancel()
	ticker := time.NewTicker(50 * time.Millisecond)
	defer ticker.Stop()
	for {
		if ready, err := idle(); ready || err != nil {
			return err
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("Waiting for Codex to finish stopping: %w", ctx.Err())
		case <-ticker.C:
		}
	}
}

func codexCommandText(prefix, name, args string) string {
	if args == "" {
		return prefix + name
	}
	return prefix + name + " " + args
}

func (p *nativeProvider) runCodexCommand(ctx context.Context, rec *Record, name, args string) error {
	if name != "review" && name != "compact" {
		return p.send(ctx, rec, M{"text": codexCommandText("$", name, args)})
	}
	r, err := p.ensure(ctx, rec)
	if err != nil {
		return err
	}
	sid := S(rec.Info, "id")
	p.store.Add(sid, M{"kind": "user", "text": codexCommandText("/", name, args)})
	p.store.Update(sid, M{"status": "running"}, true)
	method := "thread/compact/start"
	params := M{"threadId": r.External()}
	if name == "review" {
		method = "review/start"
		params["target"] = M{"type": "uncommittedChanges"}
		if instructions := strings.TrimSpace(args); instructions != "" {
			params["target"] = M{"type": "custom", "instructions": instructions}
		}
		params["delivery"] = "inline"
	}
	_, revision := r.codexTurn()
	res, err := r.Wire().request(ctx, method, params)
	if err != nil {
		p.failed(sid, err)
		return err
	}
	r.setCodexTurnFromReply(revision, S(providerMap(res["turn"]), "id"))
	return nil
}

func (p *nativeProvider) requireCodexFastMode(ctx context.Context, model string) error {
	rows, err := p.refreshModels(ctx)
	if err != nil {
		return err
	}
	for _, row := range rows {
		if (model == "" && B(row, "isDefault") || S(row, "id") == model) && B(row, "supportsFastMode") {
			return nil
		}
	}
	return errors.New("This Codex model does not support Fast mode")
}

func (p *nativeProvider) configureCodexFastMode(ctx context.Context, rec *Record, on bool) error {
	r, err := p.ensureWithCodexSettings(ctx, rec, true)
	if err != nil {
		return err
	}
	sid := S(rec.Info, "id")
	var tier any
	if on {
		if current := p.store.Get(sid); current != nil {
			rec = current
		}
		if err := p.requireCodexFastMode(ctx, S(rec.Info, "model")); err != nil {
			return err
		}
		tier = "priority"
	}
	if _, err := r.Wire().request(ctx, "thread/settings/update", M{"threadId": r.External(), "serviceTier": tier}); err != nil {
		return err
	}
	p.store.Update(sid, M{"fastMode": on}, false)
	return nil
}
