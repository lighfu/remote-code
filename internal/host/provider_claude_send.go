package host

import "context"

func (p *nativeProvider) sendClaudeRemoteUser(ctx context.Context, sid string, wire *providerWire, entry, message M, clientMessageID string) error {
	entry["ts"] = nowMS()
	// The matching echo can arrive first and carry the generic PC label.
	entry["title"] = ""
	lastResult := p.store.claudeRemoteLastResult(sid)
	err := wire.writeContext(ctx, message)
	if len(clientMessageID) > 0 && len(clientMessageID) <= 64 {
		entry["clientMessageId"] = clientMessageID
	}
	if p.store.recordClaudeRemoteSend(sid, entry, lastResult, err == nil) {
		return nil
	}
	return err
}

func (s *Store) claudeRemoteLastResult(sid string) string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return lastClaudeRemoteResult(s.records[sid])
}

func lastClaudeRemoteResult(rec *Record) string {
	if rec != nil {
		for i := len(rec.Entries) - 1; i >= 0; i-- {
			entry := rec.Entries[i]
			if S(entry, "kind") == "result" && S(entry, "parent") == "" {
				return S(entry, "id")
			}
		}
	}
	return ""
}

func (s *Store) recordClaudeRemoteSend(sid string, entry M, lastResult string, accepted bool) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	rec := s.records[sid]
	if rec == nil {
		return accepted
	}
	if !accepted {
		// The stream or transcript can confirm the exact UUID before the POST reply.
		for i := len(rec.Entries) - 1; i >= 0; i-- {
			echo := rec.Entries[i]
			if S(echo, "id") == S(entry, "id") && S(echo, "kind") == "user" && S(echo, "parent") == "" {
				accepted = true
				break
			}
		}
		if !accepted {
			return false
		}
	}
	s.addEntryLocked(sid, entry, false, true)
	// A reply may arrive after the turn finishes or asks for approval.
	if B(rec.Info, "live") && S(rec.Info, "status") != "waiting" && N(rec.Info, "pending") == 0 && lastClaudeRemoteResult(rec) == lastResult {
		s.updateLocked(sid, M{"status": "running"}, true)
	}
	return true
}
