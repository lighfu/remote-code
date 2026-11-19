package host

import (
	"encoding/base64"
	"io"
	"os"
	"path/filepath"
	"strings"
)

func (s *Store) claudeSentVideos(sessionID, entryID string) []string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if record := s.records[sessionID]; record != nil {
		for _, entry := range record.Entries {
			if S(entry, "id") == entryID && strings.EqualFold(S(entry, "tool"), "SendUserFile") {
				return append([]string(nil), providerAnswerList(entry["videos"])...)
			}
		}
	}
	return nil
}

// Keep the original attachment ID usable after Claude removes its scratchpad.
func sentVideoFile(dataDir, source string) string {
	identity := fileDigest([]byte(fileKey(filepath.Clean(source))))
	return filepath.Join(dataDir, "media", "sent-videos", identity+strings.ToLower(filepath.Ext(source)))
}

func retainSentVideo(dataDir, id string) {
	decoded, err := base64.RawURLEncoding.DecodeString(id)
	if err != nil {
		return
	}
	path := string(decoded)
	if !filepath.IsAbs(path) || mediaVideos[strings.ToLower(filepath.Ext(path))] == "" {
		return
	}
	source, err := os.Open(path)
	if err != nil {
		return
	}
	defer source.Close()
	info, err := source.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Size() <= 0 || info.Size() > mediaVideoLimit {
		return
	}
	target := sentVideoFile(dataDir, path)
	if previous, err := os.Stat(target); err == nil && previous.Size() == info.Size() && previous.ModTime().Equal(info.ModTime()) {
		return
	}
	dir := filepath.Dir(target)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return
	}
	temp, err := os.CreateTemp(dir, ".video-*.tmp")
	if err != nil {
		return
	}
	defer os.Remove(temp.Name())
	_, copyErr := io.CopyN(temp, source, info.Size())
	closeErr := temp.Close()
	after, statErr := source.Stat()
	if copyErr != nil || closeErr != nil || statErr != nil || after.Size() != info.Size() || !after.ModTime().Equal(info.ModTime()) {
		return
	}
	if err := os.Chtimes(temp.Name(), info.ModTime(), info.ModTime()); err != nil {
		return
	}
	_ = os.Rename(temp.Name(), target)
}
