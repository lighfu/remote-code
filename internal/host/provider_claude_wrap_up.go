package host

import (
	"encoding/json"
	"fmt"
	"strings"
)

func claudeWrapUpCard(raw M) M {
	if S(raw, "kind") != "tool" || S(raw, "status") != "done" {
		return nil
	}
	switch S(raw, "tool") {
	case "mcp__ccd_turn__wrap_up", "mcp__ccd-turn__wrap_up":
	default:
		return nil
	}
	var input M
	if json.Unmarshal([]byte(S(raw, "detail")), &input) != nil {
		return nil
	}
	ask, recap := strings.TrimSpace(S(input, "ask")), S(input, "recap")
	if ask == "" && strings.TrimSpace(recap) == "" {
		return nil
	}
	options := []M{}
	for i, action := range providerRows(input["actions"]) {
		label := strings.TrimSpace(S(action, "label"))
		// Desktop application actions are not remote permission requests. Only
		// explicit reply labels can be sent back through the normal message API.
		if label == "" || S(action, "action") != "" {
			options = nil
			break
		}
		options = append(options, M{"id": fmt.Sprintf("wrap-up-action-%d", i), "label": label, "kind": "other"})
	}
	if strings.HasPrefix(strings.TrimSpace(S(raw, "output")), "Not shown:") {
		// A failed Desktop offer must not turn into an actionable remote offer.
		ask, options = "", nil
	}
	if ask == "" {
		options = nil
	}
	card := M{"id": S(raw, "id") + ":wrap-up", "kind": "tool", "tool": "wrap_up", "title": ask, "text": recap, "options": options, "status": "done", "ts": raw["ts"]}
	if parent := S(raw, "parent"); parent != "" {
		card["parent"] = parent
	}
	return card
}

// Retain the original tool input/output and insert its readable next-step card
// beside it. In particular, restored questions must stay before later user replies.
func (s *Store) repairClaudeWrapUpCards(sessionID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return
	}
	for sid, rec := range s.records {
		if sessionID != "" && sid != sessionID || S(rec.Info, "provider") != "claude" {
			continue
		}
		known := make(map[string]bool, len(rec.Entries))
		for _, entry := range rec.Entries {
			known[S(entry, "id")] = true
		}
		entries := make([]M, 0, len(rec.Entries))
		changed := false
		for _, entry := range rec.Entries {
			entries = append(entries, entry)
			if known[S(entry, "id")+":wrap-up"] {
				continue
			}
			if card := claudeWrapUpCard(entry); card != nil {
				storeLimitEntry(card)
				entries = append(entries, card)
				changed = true
			}
		}
		if changed {
			s.flushDeltasLocked(sid, "")
			rec.Entries = trimTimeline(entries, storeMaxEntries)
			s.dirty[sid] = true
			// A timeline invalidation preserves insertion order in existing apps.
			s.emitLocked(M{"type": "entries_removed", "sessionId": sid, "fromId": ""})
		}
	}
}
