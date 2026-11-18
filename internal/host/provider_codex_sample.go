package host

import (
	"context"
	"errors"
	"strings"
	"time"
)

// Sampling uses the loaded conversation's latest turn (including an active
// turn). A tool-call response has no text and does not execute the tool.
func codexSampleSideQuestion(ctx context.Context, w *providerWire, thread, turn, prompt string) (M, error) {
	if turn == "" {
		page, err := w.request(ctx, "thread/turns/list", M{"threadId": thread, "limit": 1, "sortDirection": "desc", "itemsView": "notLoaded"})
		if err != nil {
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			// Only metadata was requested. A file-backed fork can still work.
			return nil, nil
		}
		rows := providerRows(page["data"])
		if len(rows) == 0 || S(rows[0], "id") == "" {
			return nil, nil
		}
		turn = S(rows[0], "id")
	}
	id := NewID()
	result, err := w.requestWithID(ctx, id, "thread/sample", M{
		"threadId": thread, "expectedTurnId": turn, "allowInProgressTurn": true,
		"prompt": prompt, "effort": "low", "disableMcp": true, "interruptOnCancel": true,
	})
	if err != nil {
		if ctx.Err() != nil {
			go func() {
				cleanup, cancel := context.WithTimeout(context.Background(), 3*time.Second)
				defer cancel()
				_, _ = w.request(cleanup, "thread/sample/cancel", M{"requestId": id})
			}()
			return nil, ctx.Err()
		}
		if codexSampleUnavailable(err) {
			return nil, nil
		}
		return nil, err
	}
	response := strings.TrimSpace(S(result, "text"))
	if response == "" {
		return nil, errors.New("No response returned")
	}
	return M{"response": response, "synthetic": false}, nil
}

// Only fall back on pre-inference rejections. Retrying timeouts or upstream
// failures could submit a second model request for the same question.
func codexSampleUnavailable(err error) bool {
	s := strings.ToLower(err.Error())
	return strings.Contains(s, "method not found") || strings.Contains(s, "unknown method") ||
		strings.Contains(s, "unknown variant") && strings.Contains(s, "thread/sample") ||
		strings.HasPrefix(s, "thread/sample requires ") ||
		strings.HasPrefix(s, "sample source must be ") || strings.HasPrefix(s, "sample source turn must be ")
}
