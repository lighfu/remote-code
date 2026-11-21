package host

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

func providerClaudeToken() (string, error) {
	raw, err := os.ReadFile(filepath.Join(providerHome("claude"), ".credentials.json"))
	if err != nil {
		return "", errors.New("Not logged into Claude Code on PC")
	}
	var credentials M
	if json.Unmarshal(raw, &credentials) != nil {
		return "", errors.New("Failed to read Claude Code login credentials")
	}
	token := S(providerMap(credentials["claudeAiOauth"]), "accessToken")
	if token == "" {
		return "", errors.New("Claude Code login credentials not found")
	}
	return token, nil
}

type claudePCAgent struct {
	status string
	kind   string
	id     string
}

type claudePCState struct {
	mu      sync.RWMutex
	loadMu  sync.Mutex
	agents  map[string]claudePCAgent
	checked time.Time
	err     error
}

func parseClaudePCAgents(raw []byte) (map[string]claudePCAgent, error) {
	var rows []M
	if json.Unmarshal(raw, &rows) != nil {
		return nil, errors.New("Could not read active Claude sessions")
	}
	agents := make(map[string]claudePCAgent, len(rows))
	for _, row := range rows {
		external := S(row, "sessionId")
		if external == "" {
			continue
		}
		state := S(row, "status")
		if state == "" {
			state = S(row, "state")
		}
		state = strings.ToLower(state)
		status := "running"
		switch {
		case state == "idle" || state == "done" || state == "completed":
			status = "idle"
		case state == "blocked" || strings.Contains(state, "wait") || strings.Contains(state, "input"):
			status = "waiting"
		}
		agents[external] = claudePCAgent{status: status, kind: S(row, "kind"), id: S(row, "id")}
	}
	return agents, nil
}

func readClaudePCAgents(ctx context.Context) (map[string]claudePCAgent, error) {
	bin, err := FindProviderExecutable("claude")
	if err != nil {
		return nil, err
	}
	c, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	out, err := HostCommand(c, bin, "agents", "--json").Output()
	if err != nil {
		return nil, fmt.Errorf("Could not check the existing Claude session owner: %w", err)
	}
	return parseClaudePCAgents(out)
}

func (p *nativeProvider) loadClaudePCAgents(ctx context.Context, force bool) (map[string]claudePCAgent, error) {
	state := &p.claudePC
	state.loadMu.Lock()
	defer state.loadMu.Unlock()
	return state.load(ctx, force)
}

// load is serialized by loadMu. Existing snapshots are kept on transient CLI errors.
func (s *claudePCState) load(ctx context.Context, force bool) (map[string]claudePCAgent, error) {
	s.mu.RLock()
	checked, cached, lastErr := s.checked, s.agents, s.err
	s.mu.RUnlock()
	if !force && time.Since(checked) < 4*time.Second {
		return cached, lastErr
	}
	agents, err := readClaudePCAgents(ctx)
	if ctx.Err() != nil {
		return cached, ctx.Err()
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if err == nil {
		s.agents = agents
	}
	s.checked, s.err = time.Now(), err
	return s.agents, err
}

func (s *claudePCState) refresh(ctx context.Context) {
	s.mu.RLock()
	recent := time.Since(s.checked) < 4*time.Second
	s.mu.RUnlock()
	if recent || !s.loadMu.TryLock() {
		return
	}
	go func() {
		defer s.loadMu.Unlock()
		_, _ = s.load(ctx, false)
	}()
}

func (s *claudePCState) activity(external string) (agent claudePCAgent, active, known bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	agent, active = s.agents[external]
	return agent, active, s.agents != nil && s.err == nil
}

func (p *nativeProvider) claudeActivePC(ctx context.Context, id string) (bool, error) {
	agents, err := p.loadClaudePCAgents(ctx, false)
	_, active := agents[id]
	return active, err
}

func (p *nativeProvider) stopClaudePCBackground(ctx context.Context, rec *Record) error {
	external := S(rec.Info, "externalId")
	if external == "" || !B(rec.Info, "watching") {
		return nil
	}
	agents, err := p.loadClaudePCAgents(ctx, true)
	if err != nil {
		return err
	}
	agent := agents[external]
	if agent.kind != "background" {
		return nil
	}
	if agent.id == "" || strings.HasPrefix(agent.id, "-") {
		return errors.New("Could not identify the running Claude background session")
	}
	bin, err := FindProviderExecutable("claude")
	if err != nil {
		return err
	}
	stopCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	if err = HostCommand(stopCtx, bin, "stop", agent.id).Run(); err != nil {
		return fmt.Errorf("Could not stop the existing Claude background session: %w", err)
	}
	p.claudePC.mu.Lock()
	p.claudePC.checked = time.Time{}
	p.claudePC.mu.Unlock()
	return nil
}

func (p *nativeProvider) claudeWatchRuntime(ctx context.Context, rec *Record) (*nativeSession, error) {
	sid := S(rec.Info, "id")
	if r := p.session(sid); r != nil || !B(rec.Info, "watching") {
		return r, nil
	}
	p.startMu.Lock()
	defer p.startMu.Unlock()
	if r := p.session(sid); r != nil {
		return r, nil
	}
	current := p.store.Get(sid)
	if current == nil || !B(current.Info, "watching") || S(current.Info, "externalId") != S(rec.Info, "externalId") {
		return nil, errors.New("The watched Claude conversation changed")
	}
	return p.startClaudeRemote(ctx, current, &nativeSession{external: S(current.Info, "externalId")})
}

func (p *nativeProvider) sendClaudeAutoCompact(r *nativeSession, tokens any) error {
	arg := "auto"
	if n := N(M{"tokens": tokens}, "tokens"); n > 0 {
		arg = fmt.Sprintf("%.0fk", n/1000)
		if n >= 1_000_000 {
			arg = "1M"
		}
	}
	return r.Wire().write(M{"type": "user", "session_id": r.External(), "origin": M{"kind": "human"},
		"parent_tool_use_id": nil, "uuid": NewID(), "message": M{"role": "user", "content": []M{{"type": "text", "text": "/autocompact " + arg}}}})
}
func (p *nativeProvider) claudeBridgeID(external string) (string, error) {
	path := p.transcriptPath(external)
	if path == "" {
		return "", errors.New("Claude transcript was not found")
	}
	info, err := os.Stat(path)
	if err != nil {
		return "", err
	}
	var bridge claudeBridgeWatch
	if err = bridge.read(path, info); err != nil {
		return "", err
	}
	if bridge.id == "" || !bridge.detached.IsZero() {
		return "", errors.New("Remote Control is not enabled for this session. Run /remote-control on PC or use Take over")
	}
	return bridge.id, nil
}
func (p *nativeProvider) startClaudeRemote(ctx context.Context, rec *Record, r *nativeSession) (*nativeSession, error) {
	bridge, err := p.claudeBridgeID(r.External())
	if err != nil {
		return nil, err
	}
	sid := S(rec.Info, "id")
	activate := make(chan struct{})
	w, err := connectClaudeRemote(ctx, bridge, func(msg M) { <-activate; p.claudeMessage(sid, r, msg) })
	if err != nil {
		close(activate)
		return nil, err
	}
	r.SetWire(w)
	r.SetRemote(true)
	p.mu.Lock()
	current := p.store.Get(sid)
	if p.closed || current == nil || S(current.Info, "externalId") != r.External() ||
		B(rec.Info, "watching") && !B(current.Info, "watching") {
		p.mu.Unlock()
		close(activate)
		_ = w.close()
		return nil, errors.New("The watched Claude conversation changed")
	}
	p.sessions[sid] = r
	p.byExternal[r.External()] = sid
	p.mu.Unlock()
	close(activate)
	init, err := w.request(ctx, "initialize", M{"forwardSubagentText": true, "agentProgressSummaries": true})
	if err != nil {
		_ = w.close()
		p.mu.Lock()
		if p.sessions[sid] == r {
			delete(p.sessions, sid)
			if p.byExternal[r.External()] == sid {
				delete(p.byExternal, r.External())
			}
		}
		p.mu.Unlock()
		return nil, err
	}
	p.rememberClaudeInit(S(rec.Info, "cwd"), init)
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.sessions[sid] != r {
		return nil, errors.New("The watched Claude conversation changed")
	}
	p.store.Update(sid, M{"live": true, "watching": true}, false)
	return r, nil
}

func connectClaudeRemote(ctx context.Context, bridge string, onMessage func(M)) (*providerWire, error) {
	base := "https://api.anthropic.com/v1/code/sessions/" + bridge + "/events"
	return startRemoteProviderWire(ctx, http.DefaultClient, base, bridge, providerClaudeToken, onMessage)
}

func claudeRemoteContextDetail(ctx context.Context, bridge string) (M, error) {
	// A closed PC window can leave a bridge ID in the transcript. Leave time
	// for a local history read if the recorded bridge no longer answers.
	connectCtx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	wire, err := connectClaudeRemote(connectCtx, bridge, func(M) {})
	if err != nil {
		return nil, err
	}
	defer wire.close()
	if _, err := wire.request(connectCtx, "initialize", M{}); err != nil {
		return nil, fmt.Errorf("Claude Remote Control initialization failed: %w", err)
	}
	return wire.claudeContextUsage(ctx)
}

var errClaudeRemoteUnauthorized = errors.New("Claude Remote Control authentication was rejected. Check the Claude Code sign-in on the PC, then reconnect.")

func claudeRemoteRequest(client *http.Client, request *http.Request, readToken func() (string, error)) (*http.Response, error) {
	token, err := readToken()
	if err != nil {
		return nil, err
	}
	request.Header.Set("anthropic-version", "2023-06-01")
	request.Header.Set("anthropic-client-platform", "android")
	for attempt := 0; ; attempt++ {
		request.Header.Set("Authorization", "Bearer "+token)
		response, err := client.Do(request)
		if err != nil || response.StatusCode != http.StatusUnauthorized {
			return response, err
		}
		_ = response.Body.Close()
		if attempt != 0 {
			return nil, errClaudeRemoteUnauthorized
		}
		if err := request.Context().Err(); err != nil {
			return nil, err
		}
		fresh, err := readToken()
		if err != nil || fresh == token {
			return nil, errClaudeRemoteUnauthorized
		}
		// The rejected request can be replayed with the same identity and content.
		request = request.Clone(request.Context())
		if request.GetBody != nil {
			request.Body, err = request.GetBody()
			if err != nil {
				return nil, err
			}
		} else if request.Body != nil && request.Body != http.NoBody {
			return nil, errClaudeRemoteUnauthorized
		}
		token = fresh
	}
}

func startRemoteProviderWire(startCtx context.Context, client *http.Client, base, bridge string, readToken func() (string, error), onMessage func(M)) (*providerWire, error) {
	ctx, cancel := context.WithCancel(context.Background())
	w := &providerWire{claude: true, waiting: map[string]chan providerReply{}, done: make(chan struct{}), onMessage: onMessage}
	var once sync.Once
	finish := func(err error) {
		once.Do(func() {
			cancel()
			w.mu.Lock()
			for id, ch := range w.waiting {
				ch <- providerReply{err: err}
				delete(w.waiting, id)
			}
			close(w.done)
			w.mu.Unlock()
			go onMessage(M{"_exit": err.Error()})
		})
	}
	w.closeFunc = func() error { finish(io.EOF); return nil }
	var initializeID string // guarded by w.mu
	sendLock := make(chan struct{}, 1)
	w.sendContextFunc = func(requestCtx context.Context, message M) error {
		select {
		case <-ctx.Done():
			return errors.New("Remote Control connection was closed")
		case <-requestCtx.Done():
			return requestCtx.Err()
		case sendLock <- struct{}{}:
		}
		defer func() { <-sendLock }()
		if S(message, "uuid") == "" {
			message["uuid"] = NewID()
		}
		if S(message, "type") == "user" {
			message["session_id"] = bridge
		}
		if S(message, "type") == "control_request" && S(providerMap(message["request"]), "subtype") == "initialize" {
			w.mu.Lock()
			initializeID = S(message, "request_id")
			w.mu.Unlock()
		}
		raw, err := json.Marshal(M{"session_id": bridge, "events": []M{{"payload": message}}})
		if err != nil {
			return err
		}
		c, stop := context.WithTimeout(requestCtx, 15*time.Second)
		defer stop()
		stopOnClose := context.AfterFunc(ctx, stop)
		defer stopOnClose()
		req, err := http.NewRequestWithContext(c, "POST", base, bytes.NewReader(raw))
		if err != nil {
			return err
		}
		req.Header.Set("Content-Type", "application/json")
		response, err := claudeRemoteRequest(client, req, readToken)
		if err != nil {
			return err
		}
		defer response.Body.Close()
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 64*1024))
		if response.StatusCode < 200 || response.StatusCode >= 300 {
			return fmt.Errorf("Remote Control send returned HTTP %d", response.StatusCode)
		}
		return nil
	}
	ready := make(chan error, 1)
	go func() {
		var sequence int64
		initialized := false
		first := true
		seenRequests := map[string]bool{}
		receive := func(payload M) {
			if S(payload, "type") == "control_request" {
				if id := S(payload, "request_id"); id != "" {
					if seenRequests[id] {
						return
					}
					seenRequests[id] = true
				}
			}
			w.receive(payload)
		}
		for attempt := 0; attempt < 12; attempt++ {
			if ctx.Err() != nil {
				return
			}
			req, err := http.NewRequestWithContext(ctx, "GET", base+"/stream", nil)
			if err != nil {
				finish(err)
				return
			}
			req.Header.Set("Accept", "text/event-stream")
			if sequence > 0 {
				req.Header.Set("Last-Event-ID", strconv.FormatInt(sequence, 10))
			}
			response, err := claudeRemoteRequest(client, req, readToken)
			if first {
				first = false
				if err != nil {
					ready <- err
					finish(err)
					return
				}
				if response.StatusCode != 200 {
					_ = response.Body.Close()
					err = fmt.Errorf("Remote Control stream returned HTTP %d", response.StatusCode)
					ready <- err
					finish(err)
					return
				}
				ready <- nil
			}
			if errors.Is(err, errClaudeRemoteUnauthorized) {
				finish(err)
				return
			}
			if err == nil && response.StatusCode == 200 {
				err = readProviderSSE(response.Body, func(event, id, data string) {
					if event != "client_event" && event != "ephemeral_event" {
						return
					}
					var envelope M
					if json.Unmarshal([]byte(data), &envelope) != nil {
						return
					}
					if event == "client_event" {
						n, _ := strconv.ParseInt(id, 10, 64)
						if n <= 0 {
							n = int64(N(envelope, "sequence_num"))
						}
						if n > 0 {
							if n <= sequence {
								return
							}
							sequence = n
						}
					}
					payload := providerMap(envelope["payload"])
					typ := S(payload, "type")
					if typ == "" {
						return
					}
					if event == "ephemeral_event" {
						if typ != "stream_event" && typ != "system" {
							return
						}
						if typ == "system" {
							sub := S(payload, "subtype")
							if sub != "status" && sub != "api_retry" && !isClaudeTaskEvent(sub) {
								return
							}
						}
					} else {
						if S(envelope, "source") != "worker" {
							if typ != "user" {
								return
							}
							for _, part := range providerRows(providerMap(payload["message"])["content"]) {
								if S(part, "type") == "tool_result" {
									return
								}
							}
						}
					}
					// The transcript supplies the history. The worker's initialize reply
					// follows the retained stream and lists approvals still awaiting an answer.
					if !initialized {
						if typ != "control_response" {
							return
						}
						reply := providerMap(payload["response"])
						w.mu.Lock()
						matches := initializeID != "" && S(reply, "request_id") == initializeID
						w.mu.Unlock()
						if !matches {
							return
						}
						initialized = S(reply, "subtype") == "success"
						if initialized {
							pending := reply["pending_permission_requests"]
							if pending == nil {
								pending = providerMap(reply["response"])["pending_permission_requests"]
							}
							for _, request := range providerRows(pending) {
								if S(request, "type") == "control_request" {
									receive(request)
								}
							}
						}
					}
					receive(payload)
				})
				_ = response.Body.Close()
			} else if response != nil {
				_ = response.Body.Close()
			}
			if ctx.Err() != nil {
				return
			}
			if err != nil && attempt == 11 {
				finish(err)
				return
			}
			select {
			case <-ctx.Done():
				return
			case <-time.After(time.Duration(min(30, 1<<min(attempt, 5))) * time.Second):
			}
		}
		finish(errors.New("Remote Control reconnect limit reached"))
	}()
	select {
	case err := <-ready:
		if err != nil {
			return nil, err
		}
		return w, nil
	case <-startCtx.Done():
		finish(startCtx.Err())
		return nil, startCtx.Err()
	}
}
func readProviderSSE(reader io.Reader, receive func(event, id, data string)) error {
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 65536), 32*1024*1024)
	event, id := "", ""
	data := []string{}
	flush := func() {
		if len(data) > 0 {
			receive(event, id, strings.Join(data, "\n"))
		}
		event = ""
		id = ""
		data = nil
	}
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			flush()
			continue
		}
		if strings.HasPrefix(line, ":") {
			continue
		}
		key, value, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		value = strings.TrimPrefix(value, " ")
		switch key {
		case "event":
			event = value
		case "id":
			id = value
		case "data":
			data = append(data, value)
		}
	}
	flush()
	return scanner.Err()
}
