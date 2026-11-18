package host

import (
	"context"
	"errors"
	"strconv"
	"strings"
)

func (p *nativeProvider) addCodexInlineQuestions(sid string, item M, parent string) {
	questions := providerRows(item["questions"])
	id := S(item, "id")
	if len(questions) == 0 || p.hasEntry(sid, "ask-"+id) {
		return
	}
	rows := make([]M, 0, len(questions))
	for i, question := range questions {
		var options []M
		for _, option := range providerAnswerList(question["options"]) {
			options = append(options, M{"label": option, "description": ""})
		}
		if options == nil {
			options = []M{}
		}
		rows = append(rows, M{"id": strconv.Itoa(i), "header": "", "question": S(question, "title"), "options": options, "multiSelect": false, "allowOther": true})
	}
	entry := M{"id": "ask-" + id, "kind": "question", "requestId": "async:" + id, "status": "pending", "questions": rows}
	if at := N(item, "startedAtMs"); at > 0 {
		entry["ts"] = at
	}
	if parent != "" {
		entry["parent"] = parent
	}
	p.store.Add(sid, entry)
}

func (p *nativeProvider) respondCodexInlineQuestion(ctx context.Context, rec *Record, body M) error {
	sid, requestID := S(rec.Info, "id"), S(body, "requestId")
	var entry M
	for _, row := range rec.Entries {
		if S(row, "requestId") == requestID && S(row, "kind") == "question" {
			entry = row
			break
		}
	}
	if entry == nil || S(entry, "status") != "pending" {
		return errors.New("This question is already resolved")
	}
	answers := providerMap(body["answers"])
	questions := providerRows(entry["questions"])
	lines := make([]string, 0, len(questions))
	for _, question := range questions {
		answer := providerAnswerText(answers[S(question, "id")])
		if strings.TrimSpace(answer) != "" {
			if len(questions) > 1 {
				answer = S(question, "question") + ": " + answer
			}
			lines = append(lines, answer)
		}
	}
	if len(lines) == 0 {
		return errors.New("Please answer the question")
	}
	if !p.store.claimCodexInlineQuestion(sid, requestID, answers) {
		return errors.New("This question is already resolved")
	}
	if _, err := p.Do(ctx, "send", rec, M{"text": strings.Join(lines, "\n")}); err != nil {
		p.store.Patch(sid, S(entry, "id"), M{"status": "pending", "answers": nil})
		return err
	}
	return nil
}

func (s *Store) claimCodexInlineQuestion(sid, requestID string, answers M) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	record := s.records[sid]
	if record == nil || s.closed {
		return false
	}
	for _, entry := range record.Entries {
		if S(entry, "requestId") == requestID && S(entry, "kind") == "question" && S(entry, "status") == "pending" {
			entry["status"] = "resolved"
			entry["answers"] = clone(answers)
			s.dirty[sid] = true
			s.emitLocked(M{"type": "entry", "sessionId": sid, "entry": entry})
			return true
		}
	}
	return false
}

func (s *Store) resolveCodexInlineQuestionsBefore(sid string, at float64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if record := s.records[sid]; record != nil && !s.closed {
		for _, entry := range record.Entries {
			if S(entry, "kind") == "question" && S(entry, "status") == "pending" && S(entry, "parent") == "" &&
				strings.HasPrefix(S(entry, "requestId"), "async:") && N(entry, "ts") < at {
				entry["status"] = "resolved"
				entry["choice"] = "Answered on PC"
				s.dirty[sid] = true
				s.emitLocked(M{"type": "entry", "sessionId": sid, "entry": entry})
			}
		}
	}
}

func (s *Store) codexPending(sid string) float64 {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if record := s.records[sid]; record != nil {
		return N(record.Info, "pending")
	}
	return 0
}
