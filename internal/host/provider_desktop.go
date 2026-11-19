package host

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"os"
	"reflect"
	"regexp"
	"strings"
	"sync"
	"time"
)

type desktopReply struct {
	result M
	owner  string
	err    error
}
type desktopPending struct {
	method string
	reply  chan desktopReply
}
type desktopProjectedItem struct {
	item      M
	completed bool
}
type desktopBinding struct {
	sid, parent, owner string
	state              M
	revision           float64
	items              map[string]desktopProjectedItem
	itemTurns          map[string]string
	aliases            map[string]string
	children           map[string]string
	ready              chan struct{}
	resync             bool
}
type providerDesktop struct {
	p                                         *nativeProvider
	startMu, writeMu, mu, projectMu, followMu sync.Mutex
	conn                                      net.Conn
	clientID                                  string
	pending                                   map[string]desktopPending
	follows                                   map[string]*desktopBinding
	historyChildren                           map[string]bool
	answered                                  map[string]bool
	asks                                      map[string]M
	closed                                    bool
	generation                                int
	dial                                      func(context.Context) (net.Conn, error)
}

var desktopThreadID = regexp.MustCompile(`(?i)^[a-f0-9]{8}-[a-f0-9-]{27}$`)

func (p *nativeProvider) desktopClient() *providerDesktop {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.desktop == nil {
		p.desktop = &providerDesktop{p: p, pending: map[string]desktopPending{}, follows: map[string]*desktopBinding{}, historyChildren: map[string]bool{}, asks: map[string]M{}, answered: map[string]bool{}, clientID: "initializing-client", dial: desktopDial}
	}
	return p.desktop
}
func (p *nativeProvider) closeDesktop() {
	p.mu.Lock()
	d := p.desktop
	p.mu.Unlock()
	if d != nil {
		d.close()
	}
}
func (d *providerDesktop) start(ctx context.Context) error {
	d.startMu.Lock()
	defer d.startMu.Unlock()
	d.mu.Lock()
	if d.closed {
		d.mu.Unlock()
		return errors.New("Connection to Codex Desktop has ended")
	}
	if d.conn != nil && d.clientID != "initializing-client" {
		d.mu.Unlock()
		return nil
	}
	d.mu.Unlock()
	cctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	conn, err := d.dial(cctx)
	if err != nil {
		return err
	}
	d.mu.Lock()
	if d.closed {
		d.mu.Unlock()
		_ = conn.Close()
		return errors.New("Connection to Codex Desktop has ended")
	}
	d.conn = conn
	d.clientID = "initializing-client"
	d.generation++
	d.mu.Unlock()
	go d.read(conn)
	reply, err := d.requestRaw(cctx, "initialize", M{"clientType": "remote-code"}, 0, "", 5*time.Second)
	if err != nil {
		d.fail(conn, err)
		return err
	}
	id := S(reply.result, "clientId")
	if id == "" {
		err = errors.New("Invalid Codex Desktop initialization")
		d.fail(conn, err)
		return err
	}
	d.mu.Lock()
	if d.conn != conn {
		d.mu.Unlock()
		return errors.New("Codex Desktop connection changed")
	}
	d.clientID = id
	d.mu.Unlock()
	return nil
}
func (d *providerDesktop) write(message M) error {
	raw, err := json.Marshal(message)
	if err != nil {
		return err
	}
	if len(raw) == 0 || len(raw) > 32*1024*1024 {
		return errors.New("Codex Desktop frame is too large")
	}
	frame := make([]byte, len(raw)+4)
	binary.LittleEndian.PutUint32(frame, uint32(len(raw)))
	copy(frame[4:], raw)
	d.writeMu.Lock()
	defer d.writeMu.Unlock()
	d.mu.Lock()
	conn := d.conn
	d.mu.Unlock()
	if conn == nil {
		return errors.New("Not connected to Codex Desktop")
	}
	_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	for len(frame) > 0 {
		n, e := conn.Write(frame)
		if e != nil {
			d.fail(conn, e)
			return e
		}
		frame = frame[n:]
	}
	return nil
}
func (d *providerDesktop) requestRaw(ctx context.Context, method string, params M, version int, owner string, timeout time.Duration) (desktopReply, error) {
	id := NewID()
	ch := make(chan desktopReply, 1)
	d.mu.Lock()
	if d.conn == nil {
		d.mu.Unlock()
		return desktopReply{}, errors.New("Not connected to Codex Desktop")
	}
	client := d.clientID
	d.pending[id] = desktopPending{method, ch}
	d.mu.Unlock()
	defer func() { d.mu.Lock(); delete(d.pending, id); d.mu.Unlock() }()
	message := M{"type": "request", "requestId": id, "sourceClientId": client, "version": version, "method": method, "params": params, "timeoutMs": timeout.Milliseconds()}
	if owner != "" {
		message["targetClientId"] = owner
	}
	if err := d.write(message); err != nil {
		return desktopReply{}, err
	}
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case reply := <-ch:
		return reply, reply.err
	case <-ctx.Done():
		return desktopReply{}, ctx.Err()
	case <-timer.C:
		return desktopReply{}, errors.New("Codex Desktop request timed out")
	}
}
func (d *providerDesktop) read(conn net.Conn) {
	for {
		var header [4]byte
		if _, err := io.ReadFull(conn, header[:]); err != nil {
			d.fail(conn, err)
			return
		}
		size := binary.LittleEndian.Uint32(header[:])
		if size == 0 || size > 32*1024*1024 {
			d.fail(conn, errors.New("Invalid Codex Desktop frame length"))
			return
		}
		raw := make([]byte, size)
		if _, err := io.ReadFull(conn, raw); err != nil {
			d.fail(conn, err)
			return
		}
		var message M
		if err := json.Unmarshal(raw, &message); err != nil {
			d.fail(conn, errors.New("Invalid Codex Desktop frame"))
			return
		}
		d.mu.Lock()
		current := d.conn == conn
		d.mu.Unlock()
		if !current {
			return
		}
		switch S(message, "type") {
		case "response":
			id := S(message, "requestId")
			d.mu.Lock()
			pending, ok := d.pending[id]
			if ok {
				delete(d.pending, id)
			}
			d.mu.Unlock()
			if !ok {
				continue
			}
			reply := desktopReply{result: providerMap(message["result"]), owner: S(message, "handledByClientId")}
			if S(message, "resultType") == "error" {
				reply.err = errors.New("Codex Desktop: " + S(message, "error"))
			} else if S(message, "resultType") != "success" || S(message, "method") != pending.method {
				reply.err = errors.New("Codex Desktop response method mismatch")
			}
			pending.reply <- reply
		case "client-discovery-request":
			_ = d.write(M{"type": "client-discovery-response", "requestId": message["requestId"], "response": M{"canHandle": false}})
		case "request":
			_ = d.write(M{"type": "response", "requestId": message["requestId"], "resultType": "error", "error": "no-handler-for-request"})
		case "broadcast":
			d.mu.Lock()
			client := d.clientID
			d.mu.Unlock()
			targets := providerAnswerList(message["targetClientIds"])
			if message["targetClientIds"] != nil && !desktopContains(targets, client) {
				continue
			}
			d.receive(conn, message)
		}
	}
}
func desktopContains(values []string, value string) bool {
	for _, v := range values {
		if v == value {
			return true
		}
	}
	return false
}
func (d *providerDesktop) fail(conn net.Conn, reason error) {
	d.mu.Lock()
	if d.conn != conn {
		d.mu.Unlock()
		return
	}
	d.conn = nil
	d.clientID = "initializing-client"
	d.generation++
	pending := d.pending
	d.pending = map[string]desktopPending{}
	sids := map[string]bool{}
	for _, follow := range d.follows {
		follow.owner = ""
		follow.state = nil
		follow.resync = false
		sids[follow.sid] = true
	}
	closed := d.closed
	d.mu.Unlock()
	_ = conn.Close()
	for _, p := range pending {
		select {
		case p.reply <- desktopReply{err: reason}:
		default:
		}
	}
	for sid := range sids {
		d.p.store.Update(sid, M{"live": false}, false)
	}
	if !closed && len(sids) > 0 {
		go d.reconnect()
	}
}
func (d *providerDesktop) reconnect() {
	timer := time.NewTimer(5 * time.Second)
	defer timer.Stop()
	<-timer.C
	d.mu.Lock()
	if d.closed {
		d.mu.Unlock()
		return
	}
	follows := map[string]*desktopBinding{}
	for id, b := range d.follows {
		if b.owner == "" {
			follows[id] = b
		}
	}
	d.mu.Unlock()
	for id, b := range follows {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		err := d.follow(ctx, id, b.sid, b.parent)
		cancel()
		if err != nil {
			go d.reconnect()
			return
		}
	}
}
func (d *providerDesktop) broadcast(method string, params M, owner string) error {
	d.mu.Lock()
	client := d.clientID
	d.mu.Unlock()
	return d.write(M{"type": "broadcast", "method": method, "params": params, "version": 1, "sourceClientId": client, "targetClientIds": []string{owner}})
}
func (d *providerDesktop) findOwner(ctx context.Context, id string) (string, error) {
	if err := d.start(ctx); err != nil {
		return "", err
	}
	reply, err := d.requestRaw(ctx, "thread-owner-discovery", M{"hostId": "local", "conversationId": id}, 1, "", 5*time.Second)
	if err != nil && strings.Contains(err.Error(), "no-client-found") {
		return "", nil
	}
	return reply.owner, err
}
func (d *providerDesktop) follow(ctx context.Context, id, sid, parent string) error {
	d.followMu.Lock()
	defer d.followMu.Unlock()
	d.mu.Lock()
	b := d.follows[id]
	if b != nil && b.owner != "" && b.state != nil && b.ready == nil && d.conn != nil {
		d.mu.Unlock()
		return nil
	}
	d.mu.Unlock()
	owner, err := d.findOwner(ctx, id)
	if err != nil {
		return err
	}
	if owner == "" {
		return errors.New("Codex Desktop with this conversation open not found")
	}
	d.mu.Lock()
	b = d.follows[id]
	if b == nil {
		b = &desktopBinding{sid: sid, parent: parent}
		d.follows[id] = b
	}
	b.owner = owner
	b.state = nil
	b.revision = -1
	b.ready = make(chan struct{})
	ready := b.ready
	d.mu.Unlock()
	if err = d.broadcast("thread-stream-following-changed", M{"hostId": "local", "conversationId": id, "following": true}, owner); err != nil {
		return err
	}
	timer := time.NewTimer(10 * time.Second)
	defer timer.Stop()
	select {
	case <-ready:
		d.mu.Lock()
		valid := d.follows[id] == b && b.owner == owner && b.state != nil
		d.mu.Unlock()
		if !valid {
			return errors.New("Codex Desktop subscription target changed")
		}
		return nil
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return errors.New("Timed out receiving conversation from Codex Desktop")
	}
}
func (d *providerDesktop) receive(conn net.Conn, message M) {
	d.projectMu.Lock()
	defer d.projectMu.Unlock()
	params := providerMap(message["params"])
	if S(message, "method") == "client-status-changed" && S(params, "status") == "disconnected" {
		d.mu.Lock()
		sids := map[string]bool{}
		for _, b := range d.follows {
			if b.owner == S(params, "clientId") {
				b.owner = ""
				b.state = nil
				sids[b.sid] = true
			}
		}
		d.mu.Unlock()
		for sid := range sids {
			d.p.store.Update(sid, M{"live": false}, false)
		}
		if len(sids) > 0 {
			go d.reconnect()
		}
		return
	}
	if S(message, "method") != "thread-stream-state-changed" || S(params, "hostId") != "local" {
		return
	}
	id := S(params, "conversationId")
	d.mu.Lock()
	b := d.follows[id]
	if d.conn != conn || b == nil || b.owner == "" || b.owner != S(message, "sourceClientId") {
		d.mu.Unlock()
		return
	}
	if N(message, "version") != 13 {
		b.owner = ""
		b.state = nil
		d.mu.Unlock()
		d.p.store.Update(b.sid, M{"live": false}, false)
		return
	}
	change := providerMap(params["change"])
	state, revision, ok := desktopApplyChange(b.state, b.revision, change)
	if !ok {
		if b.resync {
			d.mu.Unlock()
			return
		}
		b.resync = true
		owner := b.owner
		d.mu.Unlock()
		_ = d.broadcast("thread-stream-following-changed", M{"hostId": "local", "conversationId": id, "following": false}, owner)
		d.mu.Lock()
		b.state = nil
		b.revision = -1
		d.mu.Unlock()
		_ = d.broadcast("thread-stream-following-changed", M{"hostId": "local", "conversationId": id, "following": true}, owner)
		return
	}
	b.state = state
	b.revision = revision
	b.resync = false
	ready := b.ready
	d.mu.Unlock()
	d.project(id, b, state)
	if ready != nil {
		d.mu.Lock()
		if b.ready == ready {
			close(ready)
			b.ready = nil
		}
		d.mu.Unlock()
	}
}
func desktopApplyChange(current M, revision float64, change M) (M, float64, bool) {
	next, ok := change["revision"].(float64)
	if !ok || next < 0 || next != float64(int64(next)) {
		return nil, revision, false
	}
	if current != nil && next <= revision {
		return current, revision, true
	}
	if S(change, "type") == "snapshot" {
		state, ok := change["conversationState"].(map[string]any)
		return state, next, ok
	}
	if S(change, "type") != "patches" || current == nil || N(change, "baseRevision") != revision || next <= revision {
		return nil, revision, false
	}
	patches, ok := change["patches"].([]any)
	if !ok || len(patches) > 20000 {
		return nil, revision, false
	}
	var result any = current
	for _, raw := range patches {
		patch, ok := raw.(map[string]any)
		if !ok {
			return nil, revision, false
		}
		op := S(patch, "op")
		if op != "add" && op != "replace" && op != "remove" {
			return nil, revision, false
		}
		path, ok := patch["path"].([]any)
		if !ok || len(path) > 128 {
			return nil, revision, false
		}
		if op != "remove" {
			if _, ok := patch["value"]; !ok {
				return nil, revision, false
			}
		}
		if len(path) == 0 {
			state, ok := patch["value"].(map[string]any)
			if op != "replace" || !ok {
				return nil, revision, false
			}
			result = state
			continue
		}
		var success bool
		result, success = desktopPatchAt(result, path, op, patch["value"])
		if !success {
			return nil, revision, false
		}
	}
	state, ok := result.(map[string]any)
	return state, next, ok
}
func desktopPatchAt(current any, path []any, op string, value any) (any, bool) {
	if len(path) == 0 {
		return nil, false
	}
	part := path[0]
	if s, ok := part.(string); ok && (s == "__proto__" || s == "constructor" || s == "prototype") {
		return nil, false
	}
	last := len(path) == 1
	switch container := current.(type) {
	case map[string]any:
		key := ""
		switch v := part.(type) {
		case string:
			key = v
		case float64:
			if v < 0 || v != float64(int64(v)) {
				return nil, false
			}
			key = fmt.Sprint(int64(v))
		default:
			return nil, false
		}
		next := M{}
		for k, v := range container {
			next[k] = v
		}
		if !last {
			child, ok := container[key]
			if !ok {
				return nil, false
			}
			changed, ok := desktopPatchAt(child, path[1:], op, value)
			if !ok {
				return nil, false
			}
			next[key] = changed
			return next, true
		}
		if op == "remove" {
			delete(next, key)
		} else {
			next[key] = value
		}
		return next, true
	case []any:
		index := -1
		if n, ok := part.(float64); ok && n >= 0 && n == float64(int64(n)) {
			index = int(n)
		}
		if last && op == "add" && part == "-" {
			index = len(container)
		}
		max := len(container) - 1
		if last && op == "add" {
			max++
		}
		if index < 0 || index > max {
			return nil, false
		}
		next := append([]any{}, container...)
		if !last {
			child, ok := desktopPatchAt(next[index], path[1:], op, value)
			if !ok {
				return nil, false
			}
			next[index] = child
			return next, true
		}
		switch op {
		case "add":
			next = append(next, nil)
			copy(next[index+1:], next[index:])
			next[index] = value
		case "remove":
			next = append(next[:index], next[index+1:]...)
		case "replace":
			next[index] = value
		}
		return next, true
	}
	return nil, false
}
func desktopTurns(state M) []M {
	history := providerMap(state["turnHistory"])
	if S(history, "kind") != "canonical" {
		return providerRows(state["turns"])
	}
	data := providerMap(history["history"])
	entities := providerMap(data["entitiesByKey"])
	result := []M{}
	seen := map[string]bool{}
	for _, island := range providerRows(data["islands"]) {
		for _, entry := range providerRows(island["entries"]) {
			key := S(entry, "value")
			if seen[key] || key == "__proto__" || key == "constructor" || key == "prototype" {
				continue
			}
			if value, ok := entities[key].(map[string]any); ok {
				result = append(result, value)
				seen[key] = true
			}
		}
	}
	return result
}
func desktopMessages(items []M) []M {
	ordinary := map[string]M{}
	steeringReceipts := map[string]string{}
	for _, item := range items {
		switch S(item, "type") {
		case "userMessage":
			if id := S(item, "id"); id != "" {
				ordinary["id:"+id] = item
			}
			if client := S(item, "clientId"); client != "" {
				ordinary["client:"+client] = item
			}
		case "steeringUserMessage":
			id, client := S(item, "serverUserMessageId"), desktopMessageClientID(item)
			if S(item, "status") == "accepted" && item["input"] != nil && id != "" && client != "" {
				steeringReceipts[id] = client
			}
		}
	}
	seen := map[string]bool{}
	result := []M{}
	for _, source := range items {
		item := source
		if S(item, "type") == "steeringUserMessage" {
			if S(item, "status") != "accepted" || item["input"] == nil {
				continue
			}
			item = clone(item)
			item["type"] = "userMessage"
			item["content"] = item["input"]
			if id := S(item, "serverUserMessageId"); id != "" {
				item["id"] = id
			}
			if id := S(item, "serverClientUserMessageId"); id != "" {
				item["clientId"] = id
			} else {
				item["clientId"] = item["clientUserMessageId"]
			}
			restore := providerMap(item["restoreMessage"])
			if at := N(restore, "acceptedAtMs"); at > 0 {
				item["startedAtMs"] = at
			} else if created := restore["createdAt"]; created != nil {
				item["startedAtMs"] = providerStamp(created)
			}
		}
		if S(item, "type") == "userMessage" {
			receipt := desktopMessageClientID(item)
			if v := ordinary["id:"+S(item, "id")]; v != nil {
				item = v
			} else if v := ordinary["client:"+S(item, "clientId")]; v != nil {
				item = v
			}
			if original := steeringReceipts[S(item, "id")]; original != "" {
				receipt = original
			}
			if receipt != "" {
				item = clone(item)
				item["remoteCodeClientMessageId"] = receipt
			}
			id, client := "id:"+S(item, "id"), "client:"+S(item, "clientId")
			if seen[id] || S(item, "clientId") != "" && seen[client] {
				continue
			}
			seen[id] = true
			if S(item, "clientId") != "" {
				seen[client] = true
			}
		}
		result = append(result, item)
	}
	return result
}

func desktopMessageClientID(item M) string {
	for _, client := range []string{S(item, "clientUserMessageId"), S(providerMap(item["restoreMessage"]), "id"), S(item, "clientId")} {
		if desktopThreadID.MatchString(client) {
			return client
		}
	}
	return ""
}
func (d *providerDesktop) project(thread string, b *desktopBinding, state M) {
	rec := d.p.store.infoRecord(b.sid)
	if rec == nil {
		return
	}
	turns := desktopTurns(state)
	if len(turns) > 60 {
		turns = turns[len(turns)-60:]
	}
	if b.aliases == nil {
		b.aliases = map[string]string{}
	}
	if b.children == nil {
		b.children = map[string]string{}
	}
	turnMessages := make([][]M, len(turns))
	present := map[string]bool{}
	for i, turn := range turns {
		turnMessages[i] = desktopMessages(providerRows(turn["items"]))
		for _, item := range turnMessages[i] {
			present[S(item, "id")] = true
			if S(item, "type") == "collabAgentToolCall" && S(item, "tool") == "spawnAgent" {
				for _, child := range providerAnswerList(item["receiverThreadIds"]) {
					card := b.children[child]
					if card == "" {
						card = S(item, "id")
						b.children[child] = card
					}
					if card != S(item, "id") {
						b.aliases[S(item, "id")] = card
					}
				}
			}
		}
	}
	items := map[string]desktopProjectedItem{}
	itemTurns := map[string]string{}
	presentTurns := map[string]bool{}
	questionItems := map[string]bool{}
	var order []string
	for i, turn := range turns {
		turnID := S(turn, "turnId")
		if turnID == "" {
			turnID = S(turn, "id")
		}
		if turnID != "" {
			presentTurns[turnID] = true
		}
		completedIDs := map[string]bool{}
		for _, completedID := range providerAnswerList(turn["completedItemIds"]) {
			completedIDs[completedID] = true
		}
		for _, item := range turnMessages[i] {
			id := S(item, "id")
			if id == "" {
				continue
			}
			order = append(order, id)
			if kind := S(item, "type"); kind == "agentMessage" || kind == "plan" {
				questionItems[id] = len(providerRows(item["questions"])) > 0
			}
			itemTurns[id] = turnID
			completed := S(item, "type") == "userMessage" || S(turn, "status") != "inProgress" || completedIDs[id] || item["completedAtMs"] != nil || desktopContains([]string{"completed", "failed", "declined"}, S(item, "status"))
			// Desktop patches copy the changed path. Unchanged item maps retain their
			// identity; full snapshots are compared by value on reconnect.
			items[id] = desktopProjectedItem{item, completed}
			if prev, ok := b.items[id]; ok && prev.completed == completed && reflect.DeepEqual(prev.item, item) {
				continue
			}
			entryID := b.aliases[id]
			if entryID == "" {
				entryID = id
			}
			if S(item, "type") == "userMessage" && entryID == id && !d.p.hasEntry(b.sid, id) {
				if clientID := codexClientMessageID(item); clientID != "" && !present[clientID] && d.p.hasEntry(b.sid, clientID) {
					entryID = clientID
					b.aliases[id] = entryID
				}
			}
			if entryID != id {
				item = clone(item)
				item["id"] = entryID
			}
			if at := N(turn, "turnStartedAtMs"); N(item, "startedAtMs") == 0 && at > 0 {
				item = clone(item)
				item["startedAtMs"] = at
			}
			if S(item, "type") == "subAgentActivity" && S(item, "threadId") == "" {
				item = clone(item)
				item["threadId"] = item["agentThreadId"]
			}
			if S(item, "type") == "userMessage" && b.parent == "" {
				entry := M{"id": entryID, "kind": "user", "text": providerText(item["content"]), "title": "From another screen"}
				if client := S(item, "remoteCodeClientMessageId"); client != "" {
					entry["clientMessageId"] = client
				}
				if at := N(item, "startedAtMs"); at > 0 {
					entry["ts"] = at
					d.p.store.resolveCodexInlineQuestionsBefore(b.sid, at)
				}
				images := []string{}
				for _, part := range providerRows(item["content"]) {
					if S(part, "type") == "localImage" {
						if image := MediaFromPath(S(part, "path"), ""); image != "" {
							images = append(images, image)
						}
					}
				}
				if len(images) > 0 {
					entry["media"] = images
				}
				if S(entry, "text") != "" || len(images) > 0 {
					d.p.store.addDesktopEntry(b.sid, entry)
				}
				continue
			}
			if S(item, "type") == "subAgentActivity" {
				child := S(item, "threadId")
				if child == "" || child == thread {
					continue
				}
				card := b.children[child]
				if card == "" {
					card = "agent:" + child
					b.children[child] = card
				}
				b.aliases[id] = card
				name := strings.TrimRight(strings.ReplaceAll(S(item, "agentPath"), "\\", "/"), "/")
				parts := strings.Split(name, "/")
				name = parts[len(parts)-1]
				if name == "" {
					name = S(item, "agentNickname")
				}
				if name == "" {
					name = "Subagent"
				}
				entry := M{"id": card, "kind": "tool", "tool": "Agent", "title": name}
				if b.parent != "" {
					entry["parent"] = b.parent
				}
				status := ""
				switch S(item, "kind") {
				case "started":
					status = "running"
				case "completed":
					status = "done"
				case "interrupted":
					status = "declined"
				case "failed", "errored":
					status = "error"
				}
				if status != "" {
					entry["status"] = status
				}
				info := strings.Trim(strings.Join([]string{S(item, "model"), S(item, "reasoningEffort")}, " · "), " ·")
				if info != "" {
					entry["text"] = info
				}
				d.p.store.Add(b.sid, entry)
				go d.followChild(child, b.sid, card)
				continue
			}
			d.p.addCodexItem(b.sid, item, completed, b.parent, d.p.store.addDesktopEntry)
			if S(item, "type") == "collabAgentToolCall" && S(item, "tool") == "spawnAgent" {
				for _, child := range providerAnswerList(item["receiverThreadIds"]) {
					go d.followChild(child, b.sid, entryID)
				}
			}
			for child, raw := range providerMap(item["agentsStates"]) {
				if card := b.children[child]; card != "" {
					state := providerMap(raw)
					patch := M{"status": providerStatus(S(state, "status"))}
					if text := S(state, "message"); text != "" {
						patch["progress"] = providerTruncate(text, 180)
					}
					d.p.store.Patch(b.sid, card, patch)
				}
			}
		}
	}
	if b.parent == "" {
		currentEntries := map[string]bool{}
		var entryOrder []string
		for _, id := range order {
			entryID := id
			if alias := b.aliases[id]; alias != "" {
				entryID = alias
			}
			entryOrder = append(entryOrder, entryID)
			currentEntries[entryID] = true
			if questionItems[id] {
				questionID := "ask-" + entryID
				entryOrder = append(entryOrder, questionID)
				currentEntries[questionID] = true
			}
		}
		removed := map[string]bool{}
		for id, turnID := range b.itemTurns {
			// A missing turn can simply be outside the newly streamed history window.
			if present[id] || !presentTurns[turnID] {
				continue
			}
			entryID := id
			if alias := b.aliases[id]; alias != "" {
				entryID = alias
			}
			if !currentEntries[entryID] {
				removed[entryID] = true
				removed["ask-"+entryID] = true
			}
		}
		d.p.store.reconcileDesktopEntries(b.sid, entryOrder, removed)
	}
	b.items = items
	b.itemTurns = itemTurns
	if b.parent != "" {
		status := "done"
		for _, turn := range turns {
			if S(turn, "status") == "inProgress" {
				status = "running"
			}
		}
		d.p.store.Patch(b.sid, b.parent, M{"status": status})
		return
	}
	pending := d.projectRequests(thread, b.sid, providerRows(state["requests"]))
	running := false
	for _, turn := range turns {
		if S(turn, "status") == "inProgress" {
			running = true
		}
	}
	switch S(providerMap(state["threadRuntimeStatus"]), "type") {
	case "active":
		running = true
	case "idle":
		running = false
	}
	status := "idle"
	if running {
		status = "running"
	}
	if pending > 0 {
		status = "waiting"
	}
	patch := M{"desktop": true, "watching": false, "live": true, "pending": pending, "status": status}
	if title := S(state, "title"); title != "" && !B(rec.Info, "titlePinned") {
		patch["title"] = title
	}
	if cwd := S(state, "cwd"); cwd != "" {
		patch["cwd"] = cwd
	}
	if model := S(state, "latestModel"); model != "" {
		patch["model"] = model
	}
	settings := providerMap(state["latestThreadSettings"])
	patch["effort"] = state["latestReasoningEffort"]
	if effort, ok := settings["effort"]; ok {
		patch["effort"] = effort
	}
	if tier, ok := settings["serviceTier"]; ok {
		patch["fastMode"] = tier == "priority"
		patch["serviceTier"] = tier
	}
	permissions := providerMap(state["currentPermissions"])
	if len(permissions) > 0 {
		patch["mode"] = codexModeFromSettings(permissions["approvalPolicy"], permissions["sandboxPolicy"])
	}
	usage := providerMap(state["latestTokenUsageInfo"])
	last := providerMap(usage["last"])
	if len(last) == 0 {
		last = providerMap(usage["lastTokenUsage"])
	}
	max := N(usage, "modelContextWindow")
	if max > 0 {
		used := N(last, "inputTokens") + N(last, "outputTokens")
		patch["context"] = M{"used": used, "max": max, "percent": min(100, math.Round(used*100/max))}
	}
	d.p.store.Update(b.sid, patch, false)
	d.p.store.recordCodexLiveUsage(b.sid, thread, usage)
}
func (d *providerDesktop) followChild(thread, sid, parent string) {
	if !desktopThreadID.MatchString(thread) {
		return
	}
	d.mu.Lock()
	if d.closed || len(d.follows)+len(d.historyChildren) >= 96 || d.historyChildren[thread] {
		d.mu.Unlock()
		return
	}
	if b := d.follows[thread]; b != nil {
		d.mu.Unlock()
		return
	}
	d.follows[thread] = &desktopBinding{sid: sid, parent: parent}
	d.mu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	err := d.follow(ctx, thread, sid, parent)
	if err == nil {
		return
	}
	d.mu.Lock()
	delete(d.follows, thread)
	d.historyChildren[thread] = true
	d.mu.Unlock()
	defer func() { d.mu.Lock(); delete(d.historyChildren, thread); d.mu.Unlock() }()
	for {
		d.mu.Lock()
		closed := d.closed
		d.mu.Unlock()
		rec := d.p.store.Get(sid)
		if closed || rec == nil {
			return
		}
		active := false
		for _, entry := range rec.Entries {
			if S(entry, "id") == parent {
				active = S(entry, "status") == "running"
			}
		}
		cctx, stop := context.WithTimeout(context.Background(), 10*time.Second)
		wire, e := d.p.ensureRPC(cctx)
		if e == nil {
			_, turns, e := d.p.readCodexHistory(cctx, wire, thread)
			if e == nil {
				for _, turn := range turns {
					for _, item := range providerRows(turn["items"]) {
						d.p.codexItem(sid, item, S(turn, "status") != "inProgress", parent)
					}
				}
			}
		}
		stop()
		if !active {
			return
		}
		time.Sleep(2 * time.Second)
	}
}
func (d *providerDesktop) projectRequests(thread, sid string, requests []M) int {
	present := map[string]bool{}
	for _, req := range requests {
		raw := req["id"]
		if _, ok := raw.(string); !ok {
			if _, ok = raw.(float64); !ok {
				continue
			}
		}
		key := fmt.Sprintf("desktop:%s:%T:%v", thread, raw, raw)
		present[key] = true
		d.mu.Lock()
		_, exists := d.asks[key]
		answered := d.answered[key]
		d.mu.Unlock()
		if exists || answered {
			continue
		}
		params := providerMap(req["params"])
		entry := M{"id": key, "requestId": key, "status": "pending"}
		options := []M{{"id": "accept", "label": "Allow", "kind": "allow"}, {"id": "acceptForSession", "label": "Allow for this session", "kind": "allowAlways"}, {"id": "decline", "label": "Reject", "kind": "deny"}, {"id": "cancel", "label": "Reject and abort", "kind": "other"}}
		switch S(req, "method") {
		case "item/commandExecution/requestApproval":
			providerMerge(entry, M{"kind": "approval", "tool": "command", "title": S(params, "command"), "detail": S(params, "command"), "text": S(params, "reason"), "options": options})
		case "item/fileChange/requestApproval":
			providerMerge(entry, M{"kind": "approval", "tool": "fileChange", "title": "File changes", "text": S(params, "reason"), "options": options})
		case "item/permissions/requestApproval":
			providerMerge(entry, M{"kind": "approval", "tool": "permissions", "title": "Additional permissions", "detail": providerPretty(params["permissions"]), "text": S(params, "reason"), "options": []M{{"id": "turn", "label": "Allow once", "kind": "allow"}, {"id": "session", "label": "Allow for this session", "kind": "allowAlways"}, {"id": "decline", "label": "Reject", "kind": "deny"}}})
		case "item/tool/requestUserInput":
			entry["kind"] = "question"
			entry["questions"] = providerQuestionRows(params, true)
		default:
			continue
		}
		d.p.store.Add(sid, entry)
		d.mu.Lock()
		d.asks[key] = M{"sid": sid, "thread": thread, "id": raw, "method": req["method"], "params": params}
		d.mu.Unlock()
	}
	d.mu.Lock()
	removed := []string{}
	pending := 0
	for key, ask := range d.asks {
		if S(ask, "sid") != sid {
			continue
		}
		if !present[key] {
			removed = append(removed, key)
			delete(d.asks, key)
		} else {
			pending++
		}
	}
	for key := range d.answered {
		if strings.HasPrefix(key, "desktop:"+thread+":") && !present[key] {
			delete(d.answered, key)
		}
	}
	d.mu.Unlock()
	for _, key := range removed {
		d.p.store.Patch(sid, key, M{"status": "resolved", "choice": "Answered on PC"})
	}
	return pending
}

var desktopRequestVersions = map[string]int{"thread-follower-start-turn": 2, "thread-follower-steer-turn": 1, "thread-follower-interrupt-turn": 4, "thread-follower-load-complete-history": 1, "thread-follower-update-thread-settings": 2, "thread-follower-read-model-settings": 1, "thread-follower-compact-thread": 1, "thread-follower-command-approval-decision": 1, "thread-follower-file-approval-decision": 1, "thread-follower-submit-user-input": 1, "thread-follower-permissions-request-approval-response": 1, "thread-follower-submit-mcp-server-elicitation-response": 1}

func (d *providerDesktop) request(ctx context.Context, thread, method string, params M) (M, error) {
	version, ok := desktopRequestVersions[method]
	if !ok {
		return nil, errors.New("Unsupported Codex Desktop operation")
	}
	d.mu.Lock()
	b := d.follows[thread]
	if d.closed || b == nil || b.owner == "" || d.conn == nil {
		d.mu.Unlock()
		return nil, errors.New("Please check connection to Codex Desktop")
	}
	owner, generation := b.owner, d.generation
	d.mu.Unlock()
	params = clone(params)
	params["conversationId"] = thread
	reply, err := d.requestRaw(ctx, method, params, version, owner, 30*time.Second)
	if err != nil {
		return nil, err
	}
	d.mu.Lock()
	valid := d.follows[thread] == b && b.owner == owner && generation == d.generation && reply.owner == owner
	d.mu.Unlock()
	if !valid {
		return nil, errors.New("Connection to Codex Desktop changed; could not verify operation result")
	}
	return reply.result, nil
}
func (p *nativeProvider) tryDesktop(ctx context.Context, rec *Record) (bool, error) {
	if p.name != "codex" || !desktopThreadID.MatchString(S(rec.Info, "externalId")) {
		return false, nil
	}
	sid, thread := S(rec.Info, "id"), S(rec.Info, "externalId")
	d := p.desktopClient()
	if !B(rec.Info, "desktop") {
		owner, err := d.findOwner(ctx, thread)
		if err != nil {
			if errors.Is(err, os.ErrNotExist) || desktopEndpointMissing(err) {
				return false, nil
			}
			return false, fmt.Errorf("Could not verify Codex Desktop ownership: %w", err)
		}
		if owner == "" {
			return false, nil
		}
		p.store.Update(sid, M{"desktop": true}, false)
	}
	err := d.follow(ctx, thread, sid, "")
	if err != nil {
		p.store.Update(sid, M{"live": false}, false)
		return false, fmt.Errorf("Please check connection to Codex Desktop. The conversation will not resume in another process: %w", err)
	}
	return true, nil
}
func (p *nativeProvider) desktopDo(ctx context.Context, action string, rec *Record, body M) (any, error) {
	d := p.desktopClient()
	thread, sid := S(rec.Info, "externalId"), S(rec.Info, "id")
	if action == "importHistory" || action == "rejoin" {
		return true, nil
	}
	if action == "detach" {
		d.unfollow(thread)
		p.store.markProviderDisconnected(sid, true)
		return nil, nil
	}
	if action == "takeover" {
		return nil, errors.New("Desktop owns this conversation; close it in Desktop before taking over")
	}
	if action == "runCommand" {
		switch name := S(body, "name"); name {
		case "compact":
			action = "compact"
		case "review":
			return nil, errors.New("Please start reviewing this conversation from Codex Desktop")
		default:
			action = "send"
			body = M{"text": codexCommandText("$", name, S(body, "args"))}
		}
	}
	if err := d.follow(ctx, thread, sid, ""); err != nil {
		return nil, err
	}
	state, err := d.currentState(thread)
	if err != nil {
		return nil, err
	}
	switch action {
	case "send":
		input := []any{}
		if text := S(body, "text"); text != "" {
			input = append(input, M{"type": "text", "text": text, "text_elements": []any{}})
		}
		for _, img := range providerRows(body["images"]) {
			id := MediaFromBase64(p.data, S(img, "data"), S(img, "mime"))
			path, err := attachmentPath(id)
			if err != nil {
				return nil, err
			}
			input = append(input, M{"type": "localImage", "path": path})
		}
		if B(body, "now") {
			if err := waitCodexIdle(ctx, func() (bool, error) {
				state, err := d.currentState(thread)
				return !desktopStateRunning(state), err
			}); err != nil {
				return nil, err
			}
		}
		client := S(body, "clientMessageId")
		if !desktopThreadID.MatchString(client) {
			client = NewID()
		}
		if !B(body, "now") && desktopStateRunning(state) {
			return d.request(ctx, thread, "thread-follower-steer-turn", M{"clientUserMessageId": client, "input": input, "attachments": []any{}, "restoreMessage": M{"id": client, "input": input, "cwd": rec.Info["cwd"], "context": M{"workspaceRoots": []any{rec.Info["cwd"]}, "attachments": []any{}, "commentAttachments": []any{}}}})
		}
		return d.request(ctx, thread, "thread-follower-start-turn", M{"turnStart": M{"request": M{"threadId": thread, "input": input, "clientUserMessageId": client, "summary": providerSummary(S(rec.Info, "thinking"))}, "context": M{"inheritThreadSettings": true, "attachments": []any{}, "commentAttachments": []any{}}}})
	case "interrupt":
		params := M{"mode": "user-stop"}
		for _, turn := range desktopTurns(state) {
			if S(turn, "status") == "inProgress" {
				params["expectedTurnId"] = turn["turnId"]
			}
		}
		return d.request(ctx, thread, "thread-follower-interrupt-turn", params)
	case "respond":
		return nil, d.respond(ctx, rec, body)
	case "compact":
		return d.request(ctx, thread, "thread-follower-compact-thread", M{})
	case "setMode", "setThinking", "setModel", "setFastMode", "setModelAndFastMode":
		settings, patch := M{}, M{}
		includesFastMode := action == "setFastMode" || action == "setModelAndFastMode"
		switch action {
		case "setMode":
			mode := S(body, "mode")
			settings = providerTurnMode(mode)
			patch["mode"] = mode
		case "setThinking":
			settings["summary"] = providerSummary(S(body, "thinking"))
			patch["thinking"] = body["thinking"]
		case "setModel", "setModelAndFastMode":
			if model := S(body, "model"); model != "" {
				settings["model"] = model
			}
			settings["effort"] = body["effort"]
			patch["model"] = body["model"]
			patch["effort"] = body["effort"]
		}
		if includesFastMode {
			on := B(body, "on") || B(body, "fastMode")
			settings["serviceTier"] = nil
			if on {
				model := S(settings, "model")
				if model == "" {
					model = S(state, "latestModel")
				}
				if model == "" {
					model = S(rec.Info, "model")
				}
				if err := p.requireCodexFastMode(ctx, model); err != nil {
					return nil, err
				}
				settings["serviceTier"] = "priority"
			}
			patch["fastMode"] = on
		}
		res, err := d.request(ctx, thread, "thread-follower-update-thread-settings", M{"threadSettings": settings})
		if err != nil {
			return nil, err
		}
		if applied, ok := res["applied"].(bool); !applied && (ok || includesFastMode) {
			return nil, errors.New("Failed to change settings in Codex Desktop")
		}
		p.store.Update(sid, patch, false)
		return nil, nil
	}
	return nil, fmt.Errorf("This setting is not supported by Codex Desktop: %s", action)
}

func (d *providerDesktop) currentState(thread string) (M, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	binding := d.follows[thread]
	if binding == nil || binding.state == nil {
		return nil, errors.New("Please check connection to Codex Desktop")
	}
	// IPC updates replace snapshots and copy the affected paths of patched snapshots.
	return binding.state, nil
}

func desktopStateRunning(state M) bool {
	switch S(providerMap(state["threadRuntimeStatus"]), "type") {
	case "active":
		return true
	case "idle":
		return false
	}
	for _, turn := range desktopTurns(state) {
		if S(turn, "status") == "inProgress" {
			return true
		}
	}
	return false
}

func (d *providerDesktop) respond(ctx context.Context, rec *Record, body M) error {
	key := S(body, "requestId")
	d.mu.Lock()
	ask := clone(d.asks[key])
	d.mu.Unlock()
	if ask == nil || S(ask, "sid") != S(rec.Info, "id") {
		return errors.New("This confirmation is already resolved or belongs to another conversation")
	}
	choice := S(body, "choice")
	if choice == "" {
		choice = "decline"
	}
	params := M{"requestId": ask["id"]}
	method := ""
	switch S(ask, "method") {
	case "item/commandExecution/requestApproval", "item/fileChange/requestApproval":
		if !desktopContains([]string{"accept", "acceptForSession", "decline", "cancel"}, choice) {
			return errors.New("Invalid approval option")
		}
		method = "thread-follower-command-approval-decision"
		if S(ask, "method") == "item/fileChange/requestApproval" {
			method = "thread-follower-file-approval-decision"
		}
		params["decision"] = choice
	case "item/permissions/requestApproval":
		method = "thread-follower-permissions-request-approval-response"
		permissions := any(M{})
		if choice == "turn" || choice == "session" {
			permissions = providerMap(ask["params"])["permissions"]
		}
		scope := "turn"
		if choice == "session" {
			scope = "session"
		}
		params["response"] = M{"permissions": permissions, "scope": scope}
	default:
		method = "thread-follower-submit-user-input"
		answers := M{}
		for _, question := range providerRows(providerMap(ask["params"])["questions"]) {
			id := S(question, "id")
			answers[id] = M{"answers": providerAnswerList(providerMap(body["answers"])[id])}
		}
		params["response"] = M{"answers": answers}
	}
	if _, err := d.request(ctx, S(ask, "thread"), method, params); err != nil {
		return err
	}
	d.mu.Lock()
	delete(d.asks, key)
	d.answered[key] = true
	pending := 0
	for _, a := range d.asks {
		if S(a, "sid") == S(rec.Info, "id") {
			pending++
		}
	}
	d.mu.Unlock()
	d.p.store.Patch(S(rec.Info, "id"), key, M{"status": "resolved", "choice": choice, "answers": body["answers"]})
	d.p.store.Update(S(rec.Info, "id"), M{"pending": pending}, false)
	return nil
}
func (d *providerDesktop) unfollow(thread string) {
	d.mu.Lock()
	b := d.follows[thread]
	delete(d.follows, thread)
	children := map[string]*desktopBinding{}
	if b != nil {
		for id, child := range d.follows {
			if child.sid == b.sid {
				children[id] = child
				delete(d.follows, id)
			}
		}
	}
	d.mu.Unlock()
	if b != nil && b.owner != "" {
		_ = d.broadcast("thread-stream-following-changed", M{"hostId": "local", "conversationId": thread, "following": false}, b.owner)
	}
	for id, child := range children {
		if child.owner != "" {
			_ = d.broadcast("thread-stream-following-changed", M{"hostId": "local", "conversationId": id, "following": false}, child.owner)
		}
	}
}
func (d *providerDesktop) close() {
	d.mu.Lock()
	if d.closed {
		d.mu.Unlock()
		return
	}
	d.closed = true
	conn := d.conn
	threads := []string{}
	for id := range d.follows {
		threads = append(threads, id)
	}
	d.mu.Unlock()
	for _, id := range threads {
		d.unfollow(id)
	}
	if conn != nil {
		d.fail(conn, errors.New("Disconnected from Codex Desktop"))
	}
}
