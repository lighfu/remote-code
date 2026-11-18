package host

import (
	"encoding/json"
	"strings"
)

const claudeSkillDirectoryPrefix = "Base directory for this skill:"

func claudeSkillDirectory(text string) (string, string, bool) {
	line, _, _ := strings.Cut(text, "\n")
	if !strings.HasPrefix(line, claudeSkillDirectoryPrefix) {
		return "", "", false
	}
	directory := strings.TrimSpace(strings.TrimPrefix(line, claudeSkillDirectoryPrefix))
	if directory == "" {
		return "", "", false
	}
	name := strings.TrimRight(directory, `/\`)
	if i := strings.LastIndexAny(name, `/\`); i >= 0 {
		name = name[i+1:]
	}
	if name == "" || name == "." || name == ".." {
		name = "Skill"
	}
	return directory, name, true
}

func claudeInjectedSkill(msg M, text string) (M, bool) {
	directory, name, valid := claudeSkillDirectory(text)
	if !valid || !B(msg, "isMeta") && S(msg, "_trusted_skill_name") == "" {
		return nil, false
	}
	if trusted := S(msg, "_trusted_skill_name"); trusted != "" {
		name = trusted
	}
	entry := M{"kind": "skill", "tool": "Skill", "title": name, "text": text, "path": directory}
	for _, key := range []string{"sourceToolUseID", "source_tool_use_id", "_trusted_skill_source"} {
		if id := S(msg, key); id != "" {
			entry["sourceToolUseId"] = id
			break
		}
	}
	return entry, true
}

func claudeHasInjectedSkill(msg M) bool {
	for _, entry := range claudeUserTextEntries(msg, false) {
		if S(entry, "kind") == "skill" {
			return true
		}
	}
	return false
}

func claudeHasSkillMetadata(msg M, text string) bool {
	_, ok := claudeInjectedSkill(msg, text)
	return ok
}

// Some live transports omit isMeta. A source tool ID, or a tool result in the
// same worker message, can still prove this is injected Skill content. A bare
// user-typed "Base directory..." prefix is never enough.
func (p *nativeProvider) claudeSkillMessage(sid string, msg M) M {
	if B(msg, "isMeta") || S(msg, "type") != "user" {
		return msg
	}
	hasSkillBody := false
	content := providerMap(msg["message"])["content"]
	if text, ok := content.(string); ok {
		_, _, hasSkillBody = claudeSkillDirectory(text)
	} else {
		for _, block := range providerRows(content) {
			if S(block, "type") == "text" {
				if _, _, ok := claudeSkillDirectory(S(block, "text")); ok {
					hasSkillBody = true
					break
				}
			}
		}
	}
	if !hasSkillBody {
		return msg
	}
	ids := []string{}
	for _, key := range []string{"sourceToolUseID", "source_tool_use_id"} {
		if id := S(msg, key); id != "" {
			ids = append(ids, id)
		}
	}
	for _, block := range providerRows(providerMap(msg["message"])["content"]) {
		if S(block, "type") == "tool_result" && !B(block, "is_error") {
			ids = append(ids, S(block, "tool_use_id"))
		}
	}
	if len(ids) == 0 {
		return msg
	}
	rec := p.store.Get(sid)
	if rec == nil {
		return msg
	}
	for _, entry := range rec.Entries {
		if S(entry, "tool") != "Skill" || S(entry, "kind") != "tool" || S(entry, "status") != "done" {
			continue
		}
		for _, id := range ids {
			if id == "" || id != S(entry, "id") {
				continue
			}
			var input M
			if json.Unmarshal([]byte(S(entry, "detail")), &input) != nil || S(input, "skill") == "" {
				continue
			}
			out := clone(msg)
			out["_trusted_skill_name"] = S(input, "skill")
			out["_trusted_skill_source"] = id
			return out
		}
	}
	return msg
}

// Correct only exact transcript-backed rows; retain their IDs, times, complete
// text, attachments and source. Missing metadata rows are inserted at their
// recorded time rather than after a later user answer.
func (s *Store) repairClaudeSkillEntries(sid string, injected []M) {
	if len(injected) == 0 {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	rec := s.records[sid]
	if s.closed || rec == nil || S(rec.Info, "provider") != "claude" {
		return
	}
	changed := false
	previewWasSkill := false
	for _, skill := range injected {
		id := S(skill, "id")
		if id == "" {
			continue
		}
		found := false
		for _, entry := range rec.Entries {
			if S(entry, "id") != id {
				continue
			}
			found = true
			oldRendering := M{"kind": "user", "text": S(skill, "text")}
			cleanClaudePCMessage(oldRendering)
			if S(entry, "kind") != "user" || S(entry, "text") != S(skill, "text") && S(entry, "text") != S(oldRendering, "text") || S(entry, "messageOrigin") == "peer" {
				break
			}
			previewWasSkill = previewWasSkill || S(rec.Info, "preview") == preview(S(entry, "text"))
			for _, key := range []string{"kind", "tool", "title", "text", "path", "sourceToolUseId"} {
				if value, ok := skill[key]; ok {
					entry[key] = clone(value)
				}
			}
			changed = true
			break
		}
		if found || N(skill, "ts") <= 0 {
			continue
		}
		entry := clone(skill)
		storeLimitEntry(entry)
		at := len(rec.Entries)
		for i, old := range rec.Entries {
			if N(old, "ts") > N(entry, "ts") {
				at = i
				break
			}
		}
		rec.Entries = append(rec.Entries, nil)
		copy(rec.Entries[at+1:], rec.Entries[at:])
		rec.Entries[at] = entry
		changed = true
	}
	if changed {
		s.flushDeltasLocked(sid, "")
		rec.Entries = trimTimeline(rec.Entries, storeMaxEntries)
		s.dirty[sid] = true
		s.emitLocked(M{"type": "entries_removed", "sessionId": sid, "fromId": ""})
		if previewWasSkill {
			last := lastPreviewMessage(rec.Entries)
			s.setPreviewLocked(rec, last, int64(N(last, "ts")))
		}
	}
}

// Loaded sessions can contain an old live user rendering even when they are no
// longer being watched. Repair those at startup using the original transcript.
func (p *nativeProvider) repairLoadedClaudeSkillHistory() {
	for _, info := range p.store.List() {
		if S(info, "provider") != "claude" || S(info, "externalId") == "" {
			continue
		}
		rec := p.store.Get(S(info, "id"))
		if rec == nil {
			continue
		}
		needsRepair := false
		for _, entry := range rec.Entries {
			if S(entry, "kind") == "user" && S(entry, "title") == "From PC" {
				if _, _, ok := claudeSkillDirectory(S(entry, "text")); ok {
					needsRepair = true
					break
				}
			}
		}
		if needsRepair {
			if path := p.transcriptPath(S(info, "externalId")); path != "" {
				if err := p.readTranscript(rec, path); err != nil {
					consoleLogf("[claude] Skill history repair deferred: %v", err)
				}
			}
		}
	}
}
