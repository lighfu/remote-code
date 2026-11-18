package host

import (
	"fmt"
	"reflect"
	"strconv"
)

func claudeStreamBlockKey(parent, messageID string, index int) string {
	return fmt.Sprintf("%s:message:%s:block:%d", parent, messageID, index)
}

// Claude emits one assistant envelope per completed content block. The envelope
// UUID identifies that row; its local content index is not the API block index.
func claudeAssistantBlockOffset(r *nativeSession, msg M, count int) int {
	if value, ok := msg["_transcript_block_offset"]; ok {
		return int(N(M{"offset": value}, "offset"))
	}
	messageID, uuid := S(providerMap(msg["message"]), "id"), S(msg, "uuid")
	if messageID == "" || uuid == "" {
		return 0
	}
	key := S(msg, "parent_tool_use_id") + ":assistant:" + messageID
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.parents == nil {
		r.parents = map[string]string{}
	}
	if saved, ok := r.parents[key+":row:"+uuid]; ok {
		offset, _ := strconv.Atoi(saved)
		return offset
	}
	offset, _ := strconv.Atoi(r.parents[key+":next"])
	r.parents[key+":row:"+uuid] = strconv.Itoa(offset)
	r.parents[key+":next"] = strconv.Itoa(offset + count)
	return offset
}

type claudeAssistantIdentity struct {
	messageID, id, legacyID, localID, kind, text, parent string
	ts                                                   int64
}

// Repair only entries whose IDs belong to a known transcript API message and
// whose content is confirmed by that same message. Identical text in another
// message, or in multiple real blocks of this message, remains separate.
func (s *Store) repairClaudeAssistantEntries(sid string, identities []claudeAssistantIdentity) {
	if len(identities) == 0 {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	rec := s.records[sid]
	if rec == nil {
		return
	}
	groups := map[string][]claudeAssistantIdentity{}
	aliases := map[string]string{}
	for _, identity := range identities {
		if identity.messageID == "" {
			continue
		}
		group := identity.messageID
		duplicate := false
		for i, old := range groups[group] {
			if old.id == identity.id {
				groups[group][i] = identity
				duplicate = true
				break
			}
		}
		if !duplicate {
			groups[group] = append(groups[group], identity)
		}
		for _, id := range []string{identity.id, identity.legacyID, identity.localID} {
			if id != "" {
				aliases[id] = group
			}
		}
	}
	existing := map[string][]M{}
	invalid := map[string]bool{}
	first := map[string]int{}
	for index, entry := range rec.Entries {
		group := aliases[S(entry, "id")]
		if group == "" {
			continue
		}
		if len(existing[group]) == 0 {
			first[group] = index
		}
		existing[group] = append(existing[group], entry)
		matches := false
		for _, identity := range groups[group] {
			if identity.kind == "tool" {
				matches = matches || S(entry, "id") == identity.id
			} else {
				matches = matches || S(entry, "kind") == identity.kind && S(entry, "text") == identity.text
			}
		}
		if !matches || B(entry, "streaming") {
			invalid[group] = true
		}
	}
	replacements := map[string][]M{}
	for group, entries := range existing {
		if invalid[group] {
			continue
		}
		byID := map[string]M{}
		for _, entry := range entries {
			byID[S(entry, "id")] = entry
		}
		ordered := []M{}
		for _, identity := range groups[group] {
			var entry M
			for _, id := range []string{identity.id, identity.legacyID, identity.localID} {
				candidate := byID[id]
				if candidate != nil && (identity.kind == "tool" && id == identity.id || S(candidate, "kind") == identity.kind && S(candidate, "text") == identity.text) {
					entry = clone(candidate)
					break
				}
			}
			if identity.kind == "tool" {
				if entry != nil {
					ordered = append(ordered, entry)
				}
				continue
			}
			if entry == nil {
				entry = M{"kind": identity.kind, "text": identity.text, "streaming": false, "ts": identity.ts}
				if identity.parent != "" {
					entry["parent"] = identity.parent
				}
				if identity.ts == 0 {
					entry["ts"] = entries[0]["ts"]
				}
			}
			entry["id"] = identity.id
			storeLimitEntry(entry)
			ordered = append(ordered, entry)
		}
		if !reflect.DeepEqual(entries, ordered) {
			replacements[group] = ordered
		}
	}
	if len(replacements) == 0 {
		return
	}
	s.flushDeltasLocked(sid, "")
	entries := make([]M, 0, len(rec.Entries))
	for index, entry := range rec.Entries {
		group := aliases[S(entry, "id")]
		if replacement, repair := replacements[group]; repair {
			if index == first[group] {
				entries = append(entries, replacement...)
			}
			continue
		}
		entries = append(entries, entry)
	}
	rec.Entries = trimTimeline(entries, storeMaxEntries)
	s.dirty[sid] = true
	s.emitLocked(M{"type": "entries_removed", "sessionId": sid, "fromId": ""})
}
