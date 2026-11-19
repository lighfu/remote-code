package host

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestClaudeSentVideoSurvivesRemovalOfScratchFile(t *testing.T) {
	a := testApp(t)
	cwd := t.TempDir()
	source := filepath.Join(t.TempDir(), "preview video.mp4")
	video := []byte("0000ftyp0123456789")
	if err := os.WriteFile(source, video, 0600); err != nil {
		t.Fatal(err)
	}
	session := S(a.Store.Create(M{"provider": "claude", "cwd": cwd}).Info, "id")
	p := &nativeProvider{name: "claude", store: a.Store, data: a.DataDir}
	const toolID = "send-preview"
	tool := p.describeClaudeTool("SendUserFile", M{"files": []any{source}})
	tool["id"] = toolID
	a.Store.Add(session, tool)
	p.claudeUserMessage(session, true, M{
		"type": "user",
		"message": M{"content": []any{M{
			"type": "tool_result", "tool_use_id": toolID,
			"content": []any{M{"type": "text", "text": "Sent " + "\"" + source + "\""}},
		}}},
	})
	videos := a.Store.claudeSentVideos(session, toolID)
	if len(videos) != 1 || videos[0] != MediaID(source) {
		t.Fatalf("unexpected video attachments: %v", videos)
	}
	if err := os.Remove(source); err != nil {
		t.Fatal(err)
	}
	path := "/api/media/" + videos[0]
	if response := testRequest(a, "GET", path, ""); response.Code != http.StatusOK || !bytes.Equal(response.Body.Bytes(), video) {
		t.Fatalf("saved video returned %d: %q", response.Code, response.Body)
	}
	request := httptest.NewRequest("GET", path, nil)
	request.RemoteAddr = "127.0.0.1:12345"
	request.Header.Set("Authorization", "Bearer "+a.Config.Token)
	request.Header.Set("Range", "bytes=4-7")
	response := httptest.NewRecorder()
	a.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusPartialContent || response.Body.String() != "ftyp" || response.Header().Get("Content-Type") != "video/mp4" {
		t.Fatalf("saved video did not support seeking: %d %q (%s)", response.Code, response.Body, response.Header().Get("Content-Type"))
	}
}
