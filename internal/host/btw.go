package host

import (
	"context"
	"encoding/json"
	"errors"
	gomaps "maps"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"time"
)

const (
	btwSavedLimit    = 100
	btwHistoryLimit  = 12
	btwAnswerTimeout = 5 * time.Minute
)

var btwUnsafeFileChars = regexp.MustCompile(`[^A-Za-z0-9_-]`)

type btwProgressKey struct{}

func (a *App) btwFile(sid string) string {
	return filepath.Join(a.DataDir, "btw", btwUnsafeFileChars.ReplaceAllString(sid, "_")+".json")
}

// The caller holds btwMu while reading or updating a session's quick questions.
func (a *App) btwRows(sid string) ([]M, error) {
	if rows, ok := a.btw[sid]; ok {
		return rows, nil
	}
	rows := []M{}
	raw, err := os.ReadFile(a.btwFile(sid))
	if err == nil {
		if err = json.Unmarshal(raw, &rows); err != nil {
			return nil, err
		}
		for _, item := range rows {
			if S(item, "status") == "pending" {
				item["status"] = "error"
				item["error"] = "Server stopped while waiting for response"
			}
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	if rows == nil {
		rows = []M{}
	}
	a.btw[sid] = rows
	return rows, nil
}

func (a *App) saveBtwRows(sid string, rows []M) error {
	if err := atomicJSON(a.btwFile(sid), rows); err != nil {
		return err
	}
	a.btw[sid] = rows
	return nil
}

func (a *App) registerBtw(mux *http.ServeMux) {
	route(mux, "GET /api/sessions/{id}/btw", func(r *http.Request, _ M) (any, error) {
		sid := r.PathValue("id")
		if _, err := a.sessionInfoRecord(sid); err != nil {
			return nil, err
		}
		a.btwMu.Lock()
		defer a.btwMu.Unlock()
		rows, err := a.btwRows(sid)
		return clone(rows), err
	})
	route(mux, "POST /api/sessions/{id}/btw", func(r *http.Request, body M) (any, error) {
		rec, err := a.sessionInfoRecord(r.PathValue("id"))
		if err != nil {
			return nil, err
		}
		question := strings.TrimSpace(S(body, "question"))
		if question == "" || len(question) > 20000 {
			return nil, errors.New("Question is empty or too long")
		}
		if S(rec.Info, "provider") == "antigravity" {
			return nil, errors.New("Antigravity does not support quick questions")
		}
		return a.askBtw(rec, question)
	})
	for _, pattern := range []string{"DELETE /api/sessions/{id}/btw", "DELETE /api/sessions/{id}/btw/{item}"} {
		route(mux, pattern, func(r *http.Request, _ M) (any, error) {
			sid := r.PathValue("id")
			if _, err := a.sessionInfoRecord(sid); err != nil {
				return nil, err
			}
			return nil, a.removeBtw(sid, r.PathValue("item"))
		})
	}
}

func (a *App) askBtw(rec *Record, question string) (M, error) {
	sid := S(rec.Info, "id")
	a.btwMu.Lock()
	defer a.btwMu.Unlock()
	rows, err := a.btwRows(sid)
	if err != nil {
		return nil, err
	}
	var history []M
	for _, row := range rows {
		if S(row, "status") == "pending" {
			return nil, errors.New("Waiting for response to previous quick question")
		}
		if S(row, "status") == "done" && !B(row, "synthetic") && S(row, "response") != "" {
			history = append(history, M{"question": S(row, "question"), "response": S(row, "response")})
		}
	}
	history = history[max(0, len(history)-btwHistoryLimit):]
	id := NewID()
	item := M{"id": id, "question": question, "status": "pending", "at": nowMS()}
	next := append(slices.Clone(rows[max(0, len(rows)-btwSavedLimit+1):]), item)
	ctx, cancel := context.WithTimeout(a.taskCtx, btwAnswerTimeout)
	ctx = context.WithValue(ctx, btwProgressKey{}, func(text string) { a.publishBtwProgress(sid, id, text) })
	if err = a.saveBtwRows(sid, next); err != nil {
		cancel()
		return nil, err
	}
	a.btwCancel[id] = cancel
	if !a.startTask(func() {
		defer cancel()
		value, err := a.do(ctx, "sideQuestion", rec, M{"question": question, "history": history})
		a.finishBtw(sid, id, value, err)
	}) {
		cancel()
		delete(a.btwCancel, id)
		if err = a.saveBtwRows(sid, rows); err != nil {
			return nil, err
		}
		return nil, &HTTPError{503, "Server is restarting or stopping. Please reconnect."}
	}
	a.Store.Emit(M{"type": "btw", "sessionId": sid, "item": item})
	return clone(item), nil
}

// Keep partial replies available on reconnect without writing to disk on each delta.
func (a *App) publishBtwProgress(sid, id, text string) {
	a.btwMu.Lock()
	defer a.btwMu.Unlock()
	for index, item := range a.btw[sid] {
		if S(item, "id") != id || S(item, "status") != "pending" || S(item, "response") == text {
			continue
		}
		next := gomaps.Clone(item)
		next["response"] = text
		next["responseRevision"] = int(N(item, "responseRevision")) + 1
		a.btw[sid][index] = next
		a.Store.Emit(M{"type": "btw", "sessionId": sid, "item": next})
		return
	}
}

func (a *App) finishBtw(sid, id string, value any, err error) {
	a.btwMu.Lock()
	defer a.btwMu.Unlock()
	delete(a.btwCancel, id)
	rows := a.btw[sid]
	for i, item := range rows {
		if S(item, "id") != id {
			continue
		}
		answer := providerMap(value)
		response := S(answer, "response")
		if text, ok := value.(string); ok {
			response = text
		}
		if err == nil && strings.TrimSpace(response) == "" {
			err = errors.New("No response returned")
		}
		result := clone(item)
		result["doneAt"] = nowMS()
		if err != nil {
			result["status"] = "error"
			result["error"] = err.Error()
		} else {
			result["status"] = "done"
			result["response"] = response
			if B(answer, "synthetic") {
				result["synthetic"] = true
			}
		}
		next := slices.Clone(rows)
		next[i] = result
		if saveErr := a.saveBtwRows(sid, next); saveErr != nil {
			consoleLogf("[btw] Could not save quick question: %v", saveErr)
			a.btw[sid] = next
		}
		a.Store.Emit(M{"type": "btw", "sessionId": sid, "item": result})
		return
	}
}

func (a *App) removeBtw(sid, id string) error {
	a.btwMu.Lock()
	defer a.btwMu.Unlock()
	rows, err := a.btwRows(sid)
	if err != nil {
		return err
	}
	kept := make([]M, 0, len(rows))
	for _, row := range rows {
		if id != "" && S(row, "id") != id {
			kept = append(kept, row)
		}
	}
	if err = a.saveBtwRows(sid, kept); err != nil {
		return err
	}
	for _, row := range rows {
		item := S(row, "id")
		if cancel := a.btwCancel[item]; cancel != nil && (id == "" || id == item) {
			cancel()
			delete(a.btwCancel, item)
		}
	}
	event := M{"type": "btw_removed", "sessionId": sid}
	if id != "" {
		event["itemId"] = id
	}
	a.Store.Emit(event)
	return nil
}
