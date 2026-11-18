package host

import (
	"context"
	"errors"
	"strings"
	"sync"
	"time"
)

func providerModes(name string) []M {
	rows := []M{{"id": "", "label": "Follow PC settings", "description": "Use existing PC settings"}}
	if name == "codex" {
		return append(rows, M{"id": "read-only", "label": "Read-only", "description": "Read files; ask before modifications"}, M{"id": "auto", "label": "Auto in workspace", "description": "Allow workspace edits and commands; ask outside"}, M{"id": "untrusted", "label": "Ask every time", "description": "Ask unless an action is known safe"}, M{"id": "full", "label": "Full access", "description": "Run without asking"})
	}
	edits := "acceptEdits"
	if name == "antigravity" {
		edits = "accept-edits"
	}
	rows = append(rows, M{"id": "default", "label": "Ask every time", "description": "Ask before edits and commands"}, M{"id": edits, "label": "Apply automatically", "description": "Allow edits; ask for commands"}, M{"id": "auto", "label": "Auto", "description": "Allow safe actions automatically"}, M{"id": "plan", "label": "Plan mode", "description": "Plan before execution"})
	if name == "claude" {
		rows = append(rows, M{"id": "bypassPermissions", "label": "Allow all", "description": "Run without asking"})
	}
	return rows
}

type providerSide struct {
	mu   sync.Mutex
	text strings.Builder
	onText func(string)
	lastSent time.Time
	done chan error
	once sync.Once
	wire *providerWire
}

func (p *nativeProvider) codexSideQuestion(ctx context.Context, rec *Record, body M) (any, error) {
	id := S(rec.Info, "externalId")
	if id == "" {
		return nil, errors.New("Conversation has not started yet")
	}
	w, err := p.ensureRPC(ctx)
	if err != nil {
		return nil, err
	}
	const instructions = "Answer a quick side question from the conversation only, briefly, in the language of the question. Do not run tools, edit files, or continue the main task."
	var parts []string
	for _, row := range providerRows(body["history"]) {
		parts = append(parts, "Earlier side question: "+S(row, "question")+"\nAnswer: "+S(row, "response"))
	}
	parts = append(parts, "Side question: "+S(body, "question"))
	prompt := strings.Join(parts, "\n\n")
	turn := ""
	if runtime := p.session(S(rec.Info, "id")); runtime != nil && runtime.External() == id {
		turn = runtime.Turn()
	}
	if answer, err := codexSampleSideQuestion(ctx, w, id, turn, instructions+"\n\n"+prompt); answer != nil || err != nil {
		return answer, err
	}
	res, err := w.request(ctx, "thread/fork", M{"threadId": id, "ephemeral": true, "excludeTurns": true, "approvalPolicy": "never", "sandbox": "read-only", "developerInstructions": instructions})
	if err != nil {
		return nil, err
	}
	sideID := S(providerMap(res["thread"]), "id")
	if sideID == "" {
		return nil, errors.New("Failed to create temporary thread")
	}
	side := &providerSide{done: make(chan error, 1), wire: w}
	side.onText, _ = ctx.Value(btwProgressKey{}).(func(string))
	p.mu.Lock()
	if p.sides == nil {
		p.sides = map[string]*providerSide{}
	}
	p.sides[sideID] = side
	p.mu.Unlock()
	defer func() {
		p.mu.Lock()
		delete(p.sides, sideID)
		p.mu.Unlock()
		// The answer is complete. Releasing the ephemeral subscription must not delay it.
		go func() {
			c, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			_, _ = w.request(c, "thread/unsubscribe", M{"threadId": sideID})
		}()
	}()
	_, err = w.request(ctx, "turn/start", M{"threadId": sideID, "input": []M{{"type": "text", "text": prompt, "text_elements": []any{}}}, "summary": "none"})
	if err != nil {
		return nil, err
	}
	select {
	case err = <-side.done:
		if err != nil {
			return nil, err
		}
		side.mu.Lock()
		text := strings.TrimSpace(side.text.String())
		side.mu.Unlock()
		if text == "" {
			return nil, nil
		}
		return M{"response": text, "synthetic": false}, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-w.done:
		return nil, errors.New("Agent process exited before answering the quick question")
	}
}
func (p *nativeProvider) codexSideMessage(msg M, params M) bool {
	p.mu.Lock()
	side := p.sides[S(params, "threadId")]
	p.mu.Unlock()
	if side == nil {
		return false
	}
	method := S(msg, "method")
	if id := msg["id"]; id != nil {
		// Side turns cannot run tools or wait for the main conversation's approval.
		switch method {
		case "item/commandExecution/requestApproval", "item/fileChange/requestApproval":
			_ = side.wire.reply(id, M{"decision": "decline"})
		case "item/permissions/requestApproval":
			_ = side.wire.reply(id, M{"permissions": M{}, "scope": "turn"})
		case "item/tool/requestUserInput":
			_ = side.wire.reply(id, M{"answers": M{}})
		case "mcpServer/elicitation/request":
			_ = side.wire.reply(id, M{"action": "decline", "content": nil, "_meta": nil})
		default:
			_ = side.wire.write(M{"jsonrpc": "2.0", "id": id, "error": M{"code": -32601, "message": "This request is not supported in quick questions"}})
		}
		return true
	}
	side.mu.Lock()
	defer side.mu.Unlock()
	switch method {
	case "item/agentMessage/delta":
		side.text.WriteString(S(params, "delta"))
		if side.onText != nil && side.text.Len() > 0 && time.Since(side.lastSent) >= 100*time.Millisecond {
			side.lastSent = time.Now()
			side.onText(side.text.String())
		}
	case "item/completed":
		item := providerMap(params["item"])
		if S(item, "type") == "agentMessage" && S(item, "text") != "" {
			side.text.Reset()
			side.text.WriteString(S(item, "text"))
			if side.onText != nil {
				side.onText(side.text.String())
			}
		}
	case "turn/completed":
		turn := providerMap(params["turn"])
		var err error
		if S(turn, "status") == "failed" {
			err = errors.New(S(providerMap(turn["error"]), "message"))
		}
		side.once.Do(func() { side.done <- err })
	case "error":
		if !B(params, "willRetry") {
			side.once.Do(func() { side.done <- errors.New(S(providerMap(params["error"]), "message")) })
		}
	}
	return true
}
