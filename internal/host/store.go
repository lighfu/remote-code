package host

import (
	"encoding/json"
	"errors"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const storeMaxEntries = 3000
const storeRecentActivity = 600
const storeMaxOutput = 24000
const storeMaxText = 200000
const storeDeltaInterval = 60 * time.Millisecond
const storeTruncatedPrefix = "...(beginning truncated)...\n"

type storeDeltaKey struct{ sessionID, entryID, field string }
type storePendingDelta struct {
	key  storeDeltaKey
	text string
}

type Store struct {
	mu         sync.RWMutex
	persistMu  sync.Mutex
	directory  string
	records    map[string]*Record
	dirty      map[string]bool
	listeners  map[chan M]bool
	stop       chan struct{}
	done       chan struct{}
	closeOnce  sync.Once
	resumeIDs  []string
	deltas     map[storeDeltaKey]*storePendingDelta
	deltaOrder []storeDeltaKey
	deltaTimer *time.Timer
	deltaEpoch uint64
	closed     bool

	timelineRevision int64
	usagePrices      M
	detailsChanged   func(string, detailChange)
}

func OpenStore(directory string) (*Store, error) {
	if err := os.MkdirAll(directory, 0700); err != nil {
		return nil, err
	}
	s := &Store{directory: directory, records: map[string]*Record{}, dirty: map[string]bool{}, listeners: map[chan M]bool{}, stop: make(chan struct{}), done: make(chan struct{}), deltas: map[storeDeltaKey]*storePendingDelta{}}
	files, err := os.ReadDir(directory)
	if err != nil {
		return nil, err
	}
	var activeDesktopIDs []string
	for _, file := range files {
		if file.IsDir() || !strings.HasSuffix(file.Name(), ".json") {
			continue
		}
		raw, e := os.ReadFile(filepath.Join(directory, file.Name()))
		if e != nil {
			consoleLogf("[store] Cannot read %s: %v", file.Name(), e)
			continue
		}
		var rec Record
		if e = json.Unmarshal(raw, &rec); e != nil {
			consoleLogf("[store] Cannot load %s: %v", file.Name(), e)
			continue
		}
		id := S(rec.Info, "id")
		if id == "" || filepath.Base(id) != id {
			consoleLogf("[store] Invalid session ID in %s", file.Name())
			continue
		}
		context := providerMap(rec.Info["context"])
		if percent, ok := context["percent"].(float64); ok {
			// Older Go builds persisted fractional percentages, which Android cannot decode as Int.
			context["percent"] = math.Round(percent)
		}
		activeDesktop := activeCodexDesktopRecord(rec.Info)
		if activeDesktop {
			activeDesktopIDs = append(activeDesktopIDs, id)
		} else if !B(rec.Info, "archived") && S(rec.Info, "externalId") != "" && (B(rec.Info, "live") || B(rec.Info, "watching") || B(rec.Info, "desktop")) {
			s.resumeIDs = append(s.resumeIDs, id)
		}
		rec.Info["live"] = false
		if rec.Entries == nil {
			rec.Entries = []M{}
		}
		for _, entry := range rec.Entries {
			storeLimitEntry(entry)
		}
		// Codex Desktop owns its turn independently of this server's lifetime.
		if !activeDesktop {
			resetSessionRuntime(&rec, nil)
		}
		rec.Entries = trimTimeline(rec.Entries, storeMaxEntries)
		s.records[id] = &rec
	}
	s.resumeIDs = append(activeDesktopIDs, s.resumeIDs...)
	go func() {
		defer close(s.done)
		t := time.NewTicker(time.Second)
		defer t.Stop()
		for {
			select {
			case <-t.C:
				if err := s.Flush(); err != nil {
					consoleLogf("[store] %v", err)
				}
			case <-s.stop:
				return
			}
		}
	}()
	return s, nil
}

func activeCodexDesktopRecord(info M) bool {
	status := S(info, "status")
	return (status == "running" || status == "waiting") && S(info, "provider") == "codex" &&
		B(info, "desktop") && !B(info, "archived") && desktopThreadID.MatchString(S(info, "externalId"))
}

func resetSessionRuntime(rec *Record, onEntryChanged func(M)) {
	rec.Info["pending"] = 0
	delete(rec.Info, "backgroundCommandCount")
	if S(rec.Info, "status") != "error" {
		rec.Info["status"] = "idle"
	}
	for _, entry := range rec.Entries {
		changed := B(entry, "streaming")
		kind, status := S(entry, "kind"), S(entry, "status")
		switch {
		case (kind == "approval" || kind == "question") && status == "pending":
			entry["status"] = "cancelled"
			changed = true
		case kind == "tool" && status == "running":
			entry["status"] = "error"
			changed = true
		}
		entry["streaming"] = false
		if changed && onEntryChanged != nil {
			onEntryChanged(entry)
		}
	}
}

func (s *Store) markProviderDisconnected(id string, detach bool) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	rec := s.records[id]
	if s.closed || rec == nil {
		return false
	}
	status := S(rec.Info, "status")
	active := status == "running" || status == "waiting" || N(rec.Info, "pending") > 0
	s.flushDeltasLocked(id, "")
	resetSessionRuntime(rec, func(entry M) {
		active = true
		s.emitLocked(M{"type": "entry", "sessionId": id, "entry": entry})
	})
	rec.Info["live"] = false
	if detach {
		rec.Info["watching"] = false
		rec.Info["status"] = "idle"
	}
	s.dirty[id] = true
	s.emitLocked(M{"type": "session", "session": rec.Info})
	return active
}

func (s *Store) clearUnconfirmedDesktopRuntime(source *Record) {
	id := S(source.Info, "id")
	s.mu.Lock()
	defer s.mu.Unlock()
	rec := s.records[id]
	if s.closed || rec == nil || B(rec.Info, "live") || !activeCodexDesktopRecord(rec.Info) || S(rec.Info, "externalId") != S(source.Info, "externalId") {
		return
	}
	resetSessionRuntime(rec, func(entry M) { s.emitLocked(M{"type": "entry", "sessionId": id, "entry": entry}) })
	s.dirty[id] = true
	s.emitLocked(M{"type": "session", "session": rec.Info})
}

func (s *Store) List() []M {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.listLocked()
}
func (s *Store) listLocked() []M {
	out := make([]M, 0, len(s.records))
	for _, r := range s.records {
		out = append(out, clone(r.Info))
	}
	sort.Slice(out, func(i, j int) bool { return N(out[i], "updatedAt") > N(out[j], "updatedAt") })
	return out
}
func (s *Store) Get(id string) *Record {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return snapshotRecord(s.records[id])
}

// Used by routes which only need session metadata, even for very large histories.
func (s *Store) infoRecord(id string) *Record {
	s.mu.RLock()
	defer s.mu.RUnlock()
	r := s.records[id]
	if r == nil {
		return nil
	}
	return &Record{Info: snapshotMap(r.Info), contextReportedAt: r.contextReportedAt}
}

func snapshotRecord(r *Record) *Record {
	if r == nil {
		return nil
	}
	result := &Record{Info: snapshotMap(r.Info), contextReportedAt: r.contextReportedAt}
	if r.Entries != nil {
		result.Entries = make([]M, len(r.Entries))
		for i, entry := range r.Entries {
			result.Entries[i] = snapshotMap(entry)
		}
	}
	return result
}

// Copy JSON containers and share immutable strings. Keep the wire representation
// of typed values that have not yet been normalized by a save and reload.
func snapshotMap(m M) M {
	if m == nil {
		return nil
	}
	out := make(M, len(m))
	for k, v := range m {
		out[k] = snapshotValue(v)
	}
	return out
}

func snapshotValue(v any) any {
	switch value := v.(type) {
	case nil, string, float64, bool:
		return value
	case int:
		return float64(value)
	case int64:
		return float64(value)
	case M:
		if value == nil {
			return nil
		}
		return snapshotMap(value)
	case []any:
		if value == nil {
			return nil
		}
		items := make([]any, len(value))
		for i, item := range value {
			items[i] = snapshotValue(item)
		}
		return items
	default:
		return clone(v)
	}
}

func (s *Store) timelineSnapshot(id string) (*Record, int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	// Assign revisions to the deltas already reflected in the snapshot's text.
	s.flushDeltasLocked(id, "")
	return snapshotRecord(s.records[id]), s.timelineRevision
}

func (s *Store) FindExternal(provider, externalID string) *Record {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, r := range s.records {
		if S(r.Info, "provider") == provider && S(r.Info, "externalId") == externalID {
			return snapshotRecord(r)
		}
	}
	return nil
}

type historySession struct {
	id            string
	live, running bool
}

// History only needs the live session state, even when a transcript has thousands of entries.
func (s *Store) historySessions(provider string) map[string]historySession {
	s.mu.RLock()
	defer s.mu.RUnlock()
	byExternal := make(map[string]historySession)
	for _, rec := range s.records {
		info := rec.Info
		if S(info, "provider") == provider {
			byExternal[S(info, "externalId")] = historySession{
				id: S(info, "id"), live: B(info, "live"),
				running: S(info, "status") == "running" || S(info, "status") == "waiting",
			}
		}
	}
	return byExternal
}

func (s *Store) applyHistoryActivity(provider string, rows []M) {
	byExternal := s.historySessions(provider)
	for _, row := range rows {
		if session, ok := byExternal[S(row, "externalId")]; ok {
			row["sessionId"] = session.id
			row["live"] = session.live
			row["running"] = session.running
		}
	}
}
func (s *Store) emitLocked(event M) {
	switch S(event, "type") {
	case "entry", "delta", "entries_removed":
		s.timelineRevision++
		event["timelineRevision"] = s.timelineRevision
	}
	for c := range s.listeners {
		select {
		case c <- clone(event):
		default:
			close(c)
			delete(s.listeners, c)
		}
	}
}
func (s *Store) Emit(event M) { s.mu.Lock(); defer s.mu.Unlock(); s.emitLocked(event) }
func (s *Store) Subscribe() (chan M, func()) {
	c, _, cancel := s.SubscribeSnapshot()
	return c, cancel
}
func (s *Store) SubscribeSnapshot() (chan M, []M, func()) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.flushDeltasLocked("", "")
	c := make(chan M, 256)
	if s.closed {
		close(c)
	} else {
		s.listeners[c] = true
	}
	snapshot := s.listLocked()
	var once sync.Once
	return c, snapshot, func() {
		once.Do(func() {
			s.mu.Lock()
			defer s.mu.Unlock()
			if s.listeners[c] {
				delete(s.listeners, c)
				close(c)
			}
		})
	}
}
func (s *Store) Create(init M) *Record {
	s.mu.Lock()
	defer s.mu.Unlock()
	info := M{"id": NewID(), "title": "(Untitled)", "cwd": "", "status": "idle", "mode": "", "model": nil, "effort": nil, "thinking": "full", "externalId": nil, "createdAt": nowMS(), "updatedAt": nowMS(), "preview": "", "live": false, "pending": 0, "archived": false}
	for k, v := range init {
		info[k] = clone(v)
	}
	id := S(info, "id")
	if filepath.Base(id) != id || s.records[id] != nil {
		info["id"] = NewID()
		id = S(info, "id")
	}
	r := &Record{Info: info, Entries: []M{}}
	s.records[id] = r
	s.dirty[id] = true
	s.emitLocked(M{"type": "session", "session": info})
	return clone(r)
}
func (s *Store) Update(id string, patch M, touch bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.updateLocked(id, patch, touch)
}

func (s *Store) updateLocked(id string, patch M, touch bool) {
	r := s.records[id]
	if r == nil {
		return
	}
	var changed detailChange
	if ctx := providerMap(patch["context"]); len(ctx) > 0 {
		old := providerMap(r.Info["context"])
		if N(ctx, "used") != N(old, "used") || N(ctx, "max") != N(old, "max") {
			changed |= detailUsage
			r.noteContextMeasurement()
		}
	}
	if before, after := S(r.Info, "status"), S(patch, "status"); (before == "running" || before == "waiting") && (after == "idle" || after == "error") {
		changed |= detailUsage | detailGit
	}
	for k, v := range patch {
		if k != "id" {
			r.Info[k] = clone(v)
		}
	}
	if touch {
		r.Info["updatedAt"] = nowMS()
	}
	s.dirty[id] = true
	s.emitLocked(M{"type": "session", "session": r.Info})
	s.noteSessionDetails(id, changed)
}
func previewMessage(e M) bool {
	return S(e, "parent") == "" && isTimelineMessage(e) && !B(e, "streaming") && strings.TrimSpace(S(e, "text")) != ""
}

func lastPreviewMessage(entries []M) M {
	for i := len(entries) - 1; i >= 0; i-- {
		if previewMessage(entries[i]) {
			return entries[i]
		}
	}
	return nil
}

func (s *Store) setPreviewLocked(r *Record, e M, at int64) {
	next := preview(S(e, "text"))
	if next == S(r.Info, "preview") {
		return
	}
	r.Info["preview"] = next
	r.Info["updatedAt"] = max(at, int64(N(r.Info, "updatedAt")))
	s.dirty[S(r.Info, "id")] = true
	s.emitLocked(M{"type": "session", "session": r.Info})
}

func (s *Store) touchLocked(r *Record, e M, transcript bool) {
	if !previewMessage(e) || S(lastPreviewMessage(r.Entries), "id") != S(e, "id") {
		return
	}
	at := nowMS()
	if transcript {
		at = int64(N(e, "ts"))
		previous := int64(N(r.Info, "updatedAt"))
		if at < previous {
			if previous != int64(N(r.Info, "createdAt")) {
				return
			}
			for _, old := range r.Entries {
				if int64(N(old, "ts")) >= previous && previewMessage(old) {
					return
				}
			}
		}
	}
	s.setPreviewLocked(r, e, at)
}

func (s *Store) Add(id string, entry M) M {
	return s.addEntry(id, entry, false, true)
}

func (s *Store) addTranscriptEntry(id string, entry M) M {
	return s.addEntry(id, entry, true, true)
}

func (s *Store) addDesktopEntry(id string, entry M) M {
	return s.addEntry(id, entry, false, false)
}

func (s *Store) addEntry(id string, entry M, transcript, updatePreview bool) M {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.addEntryLocked(id, entry, transcript, updatePreview)
}
func (s *Store) addEntryLocked(id string, entry M, transcript, updatePreview bool) M {
	e := clone(entry)
	s.flushDeltasLocked(id, "")
	storeLimitEntry(e)
	if rec := s.records[id]; rec != nil {
		e = EnrichEntryMedia(filepath.Dir(s.directory), S(rec.Info, "cwd"), e)
	}
	if S(e, "id") == "" {
		e["id"] = NewID()
	}
	if N(e, "ts") == 0 {
		e["ts"] = nowMS()
	}
	if S(e, "kind") == "user" && S(e, "messageOrigin") == "" {
		e["messageOrigin"] = "human"
	}
	r := s.records[id]
	if r == nil {
		return e
	}
	found := false
	var previousDetails detailChange
	for _, old := range r.Entries {
		if S(old, "id") == S(e, "id") {
			previousDetails = sessionEntryDetails(old)
			if transcript {
				return clone(old)
			}
			if S(r.Info, "provider") == "claude" && claudeWorkflowTool(S(e, "tool")) && S(e, "status") == "running" && S(old, "claudeTaskId") != "" {
				delete(e, "status")
				if title := S(e, "title"); title == "Subagent" || title == "Workflow" {
					delete(e, "title")
				}
			}
			s.flushDeltasLocked(id, S(old, "id"))
			for k, v := range e {
				old[k] = v
			}
			e = old
			found = true
			break
		}
	}
	if !found {
		r.Entries = append(r.Entries, e)
	}
	r.Entries = trimTimeline(r.Entries, storeMaxEntries)
	s.emitLocked(M{"type": "entry", "sessionId": id, "entry": e})
	s.noteSessionDetails(id, sessionEntryDetails(e)&^previousDetails)
	if updatePreview {
		s.touchLocked(r, e, transcript)
	}
	s.dirty[id] = true
	return clone(e)
}
func (s *Store) Patch(id, entryID string, patch M) {
	s.patchEntry(id, entryID, patch, "")
}
func (s *Store) patchEntry(id, entryID string, patch M, expectedStatus string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.patchEntryLocked(id, entryID, patch, expectedStatus)
}
func (s *Store) patchEntryLocked(id, entryID string, patch M, expectedStatus string) {
	r := s.records[id]
	if r == nil {
		return
	}
	for _, e := range r.Entries {
		if S(e, "id") == entryID {
			if expectedStatus != "" && S(e, "status") != expectedStatus {
				return
			}
			previousDetails := sessionEntryDetails(e)
			s.flushDeltasLocked(id, entryID)
			for k, v := range patch {
				e[k] = clone(v)
			}
			for k, v := range EnrichEntryMedia(filepath.Dir(s.directory), S(r.Info, "cwd"), e) {
				e[k] = v
			}
			storeLimitEntry(e)
			s.emitLocked(M{"type": "entry", "sessionId": id, "entry": e})
			s.noteSessionDetails(id, sessionEntryDetails(e)&^previousDetails)
			s.touchLocked(r, e, false)
			s.dirty[id] = true
			return
		}
	}
}
func (s *Store) Delta(id, entryID, field, text string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if text == "" || s.closed || field != "text" && field != "output" {
		return
	}
	r := s.records[id]
	if r == nil {
		return
	}
	for _, e := range r.Entries {
		if S(e, "id") == entryID {
			limit := storeMaxText
			if field == "output" {
				limit = storeMaxOutput
			}
			next, clipped := storeClip(S(e, field)+text, limit)
			e[field] = next
			s.dirty[id] = true
			resetKey := storeDeltaKey{id, entryID, ""}
			if s.deltas[resetKey] == nil {
				key := storeDeltaKey{id, entryID, field}
				if clipped {
					s.flushDeltasLocked(id, entryID)
					key = resetKey
					text = ""
				}
				if pending := s.deltas[key]; pending != nil {
					pending.text += text
				} else {
					s.deltas[key] = &storePendingDelta{key: key, text: text}
					s.deltaOrder = append(s.deltaOrder, key)
				}
			}
			s.scheduleDeltasLocked()
			return
		}
	}
}
func (s *Store) ReplaceEntries(id string, entries []M) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if r := s.records[id]; r != nil {
		s.flushDeltasLocked(id, "")
		r.Entries = clone(entries)
		if r.Entries == nil {
			r.Entries = []M{}
		}
		for _, entry := range r.Entries {
			storeLimitEntry(entry)
		}
		r.Entries = trimTimeline(r.Entries, storeMaxEntries)
		s.dirty[id] = true
		s.emitLocked(M{"type": "entries_removed", "sessionId": id, "fromId": ""})
		e := lastPreviewMessage(r.Entries)
		s.setPreviewLocked(r, e, int64(N(e, "ts")))
	}
}

func isTimelineMessage(entry M) bool {
	kind := S(entry, "kind")
	return kind == "user" || kind == "assistant"
}

func trimTimeline(entries []M, limit int) []M {
	excess := len(entries) - limit
	if excess <= 0 {
		return entries
	}
	byID := make(map[string]int, len(entries))
	for i, entry := range entries {
		if id := S(entry, "id"); id != "" {
			byID[id] = i
		}
	}
	protected := make([]bool, len(entries))
	recentStart := max(0, len(entries)-min(limit, storeRecentActivity))
	for i, entry := range entries {
		if i < recentStart && !isTimelineMessage(entry) {
			continue
		}
		for !protected[i] {
			protected[i] = true
			parent, ok := byID[S(entries[i], "parent")]
			if !ok {
				break
			}
			i = parent
		}
	}

	// Keep recent activity, conversation messages and their ancestors ahead of old diagnostic rows.
	kept := entries[:0]
	for i, entry := range entries {
		if excess > 0 && !protected[i] {
			excess--
			continue
		}
		kept = append(kept, entry)
	}
	clear(entries[len(kept):])
	return kept[excess:]
}

func (s *Store) hasEntry(id, entryID string) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if r := s.records[id]; r != nil {
		for _, entry := range r.Entries {
			if S(entry, "id") == entryID {
				return true
			}
		}
	}
	return false
}

// Reconcile the visible Desktop history window without discarding earlier turns or local entries.
func (s *Store) reconcileDesktopEntries(id string, order []string, removed map[string]bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	r := s.records[id]
	if r == nil || len(r.Entries) == 0 {
		return
	}
	byID := make(map[string]M, len(order))
	for _, entryID := range order {
		byID[entryID] = nil
	}
	slots := make([]int, 0, len(order))
	entries := make([]M, 0, len(r.Entries))
	for _, entry := range r.Entries {
		entryID := S(entry, "id")
		if removed[entryID] {
			continue
		}
		if _, tracked := byID[entryID]; tracked && S(entry, "parent") == "" {
			byID[entryID] = entry
			slots = append(slots, len(entries))
		}
		entries = append(entries, entry)
	}
	changed := len(entries) != len(r.Entries)
	position := 0
	for _, entryID := range order {
		if entry := byID[entryID]; entry != nil {
			index := slots[position]
			changed = changed || S(entries[index], "id") != entryID
			entries[index] = entry
			delete(byID, entryID)
			position++
		}
	}
	if changed {
		s.flushDeltasLocked(id, "")
		r.Entries = entries
		s.dirty[id] = true
		s.emitLocked(M{"type": "entries_removed", "sessionId": id, "fromId": ""})
	}
	e := lastPreviewMessage(r.Entries)
	s.setPreviewLocked(r, e, int64(N(e, "ts")))
}

func (s *Store) TruncateFrom(id, entryID string) []M {
	s.mu.Lock()
	defer s.mu.Unlock()
	r := s.records[id]
	if r == nil {
		return nil
	}
	for i, e := range r.Entries {
		if S(e, "id") == entryID {
			s.flushDeltasLocked(id, "")
			removed := clone(r.Entries[i:])
			r.Entries = r.Entries[:i]
			s.dirty[id] = true
			s.emitLocked(M{"type": "entries_removed", "sessionId": id, "fromId": entryID})
			s.setPreviewLocked(r, lastPreviewMessage(r.Entries), nowMS())
			return removed
		}
	}
	return nil
}

type transcriptUserRepair struct {
	original      string
	parts         []M
	media, videos []string
}

// Repair exact user rows in place without broadcasting recovered history as live entries.
func (s *Store) repairTranscriptUserEntries(id string, repairs map[string]transcriptUserRepair) {
	if len(repairs) == 0 {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	r := s.records[id]
	if r == nil {
		return
	}
	existing := make(map[string]bool, len(r.Entries))
	for _, entry := range r.Entries {
		existing[S(entry, "id")] = true
	}
	entries := make([]M, 0, len(r.Entries))
	changed := false
	for _, entry := range r.Entries {
		repair, match := repairs[S(entry, "id")]
		if match {
			if parts := repair.apply(entry, existing); parts != nil {
				entries = append(entries, parts...)
				changed = true
				continue
			}
		}
		entries = append(entries, entry)
	}
	if changed {
		s.flushDeltasLocked(id, "")
		r.Entries = trimTimeline(entries, storeMaxEntries)
		s.dirty[id] = true
		s.emitLocked(M{"type": "entries_removed", "sessionId": id, "fromId": ""})
	}
}

func (repair transcriptUserRepair) apply(entry M, existing map[string]bool) []M {
	if S(entry, "kind") != "user" || S(entry, "title") != "From PC" || S(entry, "text") != repair.original || S(entry, "messageOrigin") == "peer" || B(entry, "streaming") {
		return nil
	}
	split := len(repair.parts) > 1
	if split {
		for _, part := range repair.parts[1:] {
			if id := S(part, "id"); id == "" || existing[id] {
				split = false
				break
			}
		}
	}
	addImages := len(repair.media) > 0 && !claudeHasReferences(entry["media"])
	addVideos := len(repair.videos) > 0 && !claudeHasReferences(entry["videos"])
	if !split && !addImages && !addVideos {
		return nil
	}
	first := clone(entry)
	if addImages {
		first["media"] = repair.media
	}
	if addVideos {
		first["videos"] = repair.videos
	}
	parts := []M{first}
	if split {
		first["text"] = S(repair.parts[0], "text")
		for _, part := range repair.parts[1:] {
			next := clone(part)
			next["ts"] = entry["ts"]
			for _, key := range []string{"parent", "messageOrigin"} {
				if v, exists := entry[key]; exists {
					next[key] = v
				}
			}
			storeLimitEntry(next)
			parts = append(parts, next)
			existing[S(part, "id")] = true
		}
	}
	return parts
}

func (s *Store) Remove(id string) error {
	s.persistMu.Lock()
	defer s.persistMu.Unlock()
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.records[id]; !ok {
		return errors.New("Session not found")
	}
	s.flushDeltasLocked(id, "")
	if err := os.Remove(filepath.Join(s.directory, id+".json")); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	delete(s.records, id)
	delete(s.dirty, id)
	s.emitLocked(M{"type": "session_removed", "id": id})
	return nil
}
func (s *Store) Flush() error {
	// Serialize disk writes and removals. A slow disk must not block all
	// sessions while encoding or syncing a large transcript.
	s.persistMu.Lock()
	defer s.persistMu.Unlock()
	s.mu.Lock()
	s.flushDeltasLocked("", "")
	ids := make([]string, 0, len(s.dirty))
	for id := range s.dirty {
		ids = append(ids, id)
	}
	s.mu.Unlock()
	for _, id := range ids {
		s.mu.Lock()
		record := snapshotRecord(s.records[id])
		delete(s.dirty, id)
		s.mu.Unlock()
		if record != nil {
			delete(record.Info, "backgroundCommandCount")
			if err := atomicJSON(filepath.Join(s.directory, id+".json"), record); err != nil {
				s.mu.Lock()
				s.dirty[id] = true
				s.mu.Unlock()
				return err
			}
		}
	}
	return nil
}
func (s *Store) Close() error {
	s.closeOnce.Do(func() {
		close(s.stop)
		<-s.done
		s.mu.Lock()
		s.closed = true
		if s.deltaTimer != nil {
			s.deltaTimer.Stop()
			s.deltaTimer = nil
			s.deltaEpoch++
		}
		s.flushDeltasLocked("", "")
		for listener := range s.listeners {
			close(listener)
			delete(s.listeners, listener)
		}
		s.mu.Unlock()
	})
	return s.Flush()
}

func storeClip(value string, limit int) (string, bool) {
	if len(value) <= limit {
		return value, false
	}
	units := 0
	start := len(value)
	for start > 0 {
		r, n := utf8.DecodeLastRuneInString(value[:start])
		cost := 1
		if r > 0xffff {
			cost = 2
		}
		if units+cost > limit {
			break
		}
		units += cost
		start -= n
	}
	if start == 0 {
		return value, false
	}
	return storeTruncatedPrefix + value[start:], true
}
func storeLimitEntry(entry M) {
	for _, pair := range []struct {
		field string
		limit int
	}{{"text", storeMaxText}, {"output", storeMaxOutput}} {
		if value, ok := entry[pair.field].(string); ok {
			entry[pair.field], _ = storeClip(value, pair.limit)
		}
	}
}
func (s *Store) scheduleDeltasLocked() {
	if s.deltaTimer != nil || s.closed {
		return
	}
	s.deltaEpoch++
	epoch := s.deltaEpoch
	s.deltaTimer = time.AfterFunc(storeDeltaInterval, func() {
		s.mu.Lock()
		defer s.mu.Unlock()
		if s.closed || s.deltaEpoch != epoch {
			return
		}
		s.deltaTimer = nil
		s.flushDeltasLocked("", "")
	})
}
func (s *Store) flushDeltasLocked(sessionID, entryID string) {
	if len(s.deltas) == 0 {
		return
	}
	remaining := s.deltaOrder[:0]
	for _, key := range s.deltaOrder {
		pending := s.deltas[key]
		if pending == nil {
			continue
		}
		if sessionID != "" && key.sessionID != sessionID || entryID != "" && key.entryID != entryID {
			remaining = append(remaining, key)
			continue
		}
		delete(s.deltas, key)
		if key.field == "" {
			if record := s.records[key.sessionID]; record != nil {
				for _, entry := range record.Entries {
					if S(entry, "id") == key.entryID {
						s.emitLocked(M{"type": "entry", "sessionId": key.sessionID, "entry": entry})
						break
					}
				}
			}
		} else {
			s.emitLocked(M{"type": "delta", "sessionId": key.sessionID, "entryId": key.entryID, "field": key.field, "text": pending.text})
		}
	}
	s.deltaOrder = remaining
	if len(s.deltas) == 0 && s.deltaTimer != nil {
		s.deltaTimer.Stop()
		s.deltaTimer = nil
		s.deltaEpoch++
	}
}
