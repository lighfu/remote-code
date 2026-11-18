package host

func (p *nativeProvider) refreshPendingState(sid string) {
	p.mu.Lock()
	count := 0
	for _, q := range p.pending {
		if q.sid == sid {
			count++
		}
	}
	p.mu.Unlock()
	patch := M{"pending": count}
	if rec := p.store.Get(sid); rec != nil {
		if count > 0 {
			patch["status"] = "waiting"
		} else if S(rec.Info, "status") == "waiting" {
			patch["status"] = "running"
		}
	}
	p.store.Update(sid, patch, false)
}

func (r *nativeSession) External() string     { r.mu.Lock(); defer r.mu.Unlock(); return r.external }
func (r *nativeSession) SetExternal(v string) { r.mu.Lock(); r.external = v; r.mu.Unlock() }
func (r *nativeSession) Turn() string         { r.mu.Lock(); defer r.mu.Unlock(); return r.turn }
func (r *nativeSession) SetTurn(v string) {
	r.mu.Lock()
	r.turn = v
	r.codexTurnRevision++
	r.mu.Unlock()
}
func (r *nativeSession) Wire() *providerWire     { r.mu.Lock(); defer r.mu.Unlock(); return r.wire }
func (r *nativeSession) SetWire(v *providerWire) { r.mu.Lock(); r.wire = v; r.mu.Unlock() }
func (r *nativeSession) Remote() bool            { r.mu.Lock(); defer r.mu.Unlock(); return r.remote }
func (r *nativeSession) SetRemote(v bool)        { r.mu.Lock(); r.remote = v; r.mu.Unlock() }
func (r *nativeSession) TextID() string          { r.mu.Lock(); defer r.mu.Unlock(); return r.text }
func (r *nativeSession) SetTextID(v string)      { r.mu.Lock(); r.text = v; r.mu.Unlock() }
func (r *nativeSession) ThoughtID() string       { r.mu.Lock(); defer r.mu.Unlock(); return r.thought }
func (r *nativeSession) SetThoughtID(v string)   { r.mu.Lock(); r.thought = v; r.mu.Unlock() }
func (r *nativeSession) Parent(key string) string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.parents[key]
}
func (r *nativeSession) SetParent(key, value string) {
	r.mu.Lock()
	if r.parents == nil {
		r.parents = map[string]string{}
	}
	r.parents[key] = value
	r.mu.Unlock()
}
func (r *nativeSession) AppendParent(key, value string) string {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.parents == nil {
		r.parents = map[string]string{}
	}
	r.parents[key] += value
	return r.parents[key]
}
