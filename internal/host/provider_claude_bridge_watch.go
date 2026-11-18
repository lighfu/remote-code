package host

import (
	"bufio"
	"bytes"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

type claudeBridgeWatch struct {
	id        string
	offset    int64
	detached  time.Time
	lastCheck time.Time
	file      os.FileInfo
}

var claudeBridgeName = regexp.MustCompile(`^[A-Za-z0-9_-]+$`)

func parseClaudeBridgeRow(line []byte) (string, bool) {
	if !bytes.Contains(line, []byte(`"bridgeSessionId"`)) {
		return "", false
	}
	var row struct {
		Type     string  `json:"type"`
		BridgeID *string `json:"bridgeSessionId"`
	}
	if json.Unmarshal(line, &row) != nil || row.Type != "bridge-session" || row.BridgeID == nil {
		return "", false
	}
	id := *row.BridgeID
	return id, id == "" || claudeBridgeName.MatchString(id)
}

func (w *claudeBridgeWatch) read(path string, info os.FileInfo) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()
	if w.file == nil || !os.SameFile(w.file, info) || info.Size() < w.offset {
		*w = claudeBridgeWatch{}
	}
	w.file = info
	if _, err := file.Seek(w.offset, io.SeekStart); err != nil {
		return err
	}
	reader := bufio.NewReader(io.LimitReader(file, info.Size()-w.offset))
	var length int64
	for {
		line, err := reader.ReadSlice('\n')
		length += int64(len(line))
		if err == bufio.ErrBufferFull {
			continue
		}
		if err == io.EOF {
			return nil // Leave an incomplete row for the next pass.
		}
		if err != nil {
			return err
		}
		if length == int64(len(line)) {
			if id, ok := parseClaudeBridgeRow(line); ok {
				if id != "" {
					w.id, w.detached = id, time.Time{}
				} else if w.id != "" {
					w.detached = info.ModTime()
				}
			}
		}
		w.offset += length
		length = 0
	}
}

func (w *claudeBridgeWatch) successor(path string, now time.Time) string {
	if w.detached.IsZero() || now.Sub(w.lastCheck) < 2*time.Second {
		return ""
	}
	w.lastCheck = now
	dir := filepath.Dir(path)
	files, err := os.ReadDir(dir)
	if err != nil {
		return ""
	}
	var best string
	var newest time.Time
	cutoff := w.detached.Add(-2 * time.Minute)
	for _, entry := range files {
		candidate := filepath.Join(dir, entry.Name())
		if candidate == path || entry.IsDir() || !strings.HasSuffix(entry.Name(), ".jsonl") {
			continue
		}
		info, err := entry.Info()
		if err != nil || !info.Mode().IsRegular() || info.ModTime().Before(cutoff) || !info.ModTime().After(newest) {
			continue
		}
		if created := claudeTranscriptCreation(info); !created.IsZero() && created.Before(cutoff) {
			continue
		}
		if claudeTranscriptBeginsWithBridge(candidate, w.id) {
			best, newest = candidate, info.ModTime()
		}
	}
	return best
}

func claudeTranscriptBeginsWithBridge(path, bridge string) bool {
	file, err := os.Open(path)
	if err != nil {
		return false
	}
	defer file.Close()
	scanner := bufio.NewScanner(io.LimitReader(file, 16<<10))
	for scanner.Scan() {
		if id, ok := parseClaudeBridgeRow(scanner.Bytes()); ok && id == bridge {
			return true
		}
	}
	return false
}
