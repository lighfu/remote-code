package host

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"testing/iotest"
	"time"
)

func uploadTestRequest(t *testing.T, handler http.Handler, method, path string, body io.Reader, length int64, wantCode int) M {
	t.Helper()
	r := httptest.NewRequest(method, path, body)
	r.Header.Set("Content-Type", "application/octet-stream")
	r.ContentLength = length
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, r)
	if response.Code != wantCode {
		t.Fatalf("%s %s: got %d, want %d: %s", method, path, response.Code, wantCode, response.Body.String())
	}
	if response.Code == http.StatusNoContent {
		return nil
	}
	var result M
	if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	return result
}

func uploadTestHandler(dir string) *http.ServeMux {
	mux := http.NewServeMux()
	registerMedia(&App{DataDir: dir}, mux)
	return mux
}

func TestChunkedUploadPublishesAttachmentForChat(t *testing.T) {
	dir := t.TempDir()
	server := uploadTestHandler(dir)
	init := uploadTestRequest(t, server, "POST", "/api/uploads/chunked?name=CON.txt&size=5", nil, 0, 200)
	id := S(init, "id")
	path := "/api/uploads/chunked/" + id
	destination := filepath.Join(dir, "attachments", id)
	marker := filepath.Join(dir, "upload-parts", id+".json")
	first := uploadTestRequest(t, server, "PUT", path+"?offset=0", strings.NewReader("he"), 2, 200)
	if N(first, "offset") != 2 {
		t.Fatalf("wrong offset: %+v", first)
	}
	uploadTestRequest(t, server, "PUT", path+"?offset=0", strings.NewReader("he"), 2, 409)
	uploadTestRequest(t, server, "PUT", path+"?offset=2", strings.NewReader("x"), 3, 400)
	uploadTestRequest(t, server, "PUT", path+"?offset=2&final=true", nil, 0, 409)
	if _, err := ResolveUploads(dir, []any{id}); err == nil {
		t.Fatal("incomplete file was visible to chat")
	}
	if current, err := os.ReadFile(filepath.Join(destination, "file", "_CON.txt")); err != nil || string(current) != "he" {
		t.Fatalf("partial contents in their final location: %q %v", current, err)
	}
	if _, err := os.Stat(filepath.Join(destination, "metadata.json")); !os.IsNotExist(err) {
		t.Fatalf("attachment metadata published before completion: %v", err)
	}
	// Offsets and declared length persist when the same data directory is reopened.
	server = uploadTestHandler(dir)
	uploadTestRequest(t, server, "PUT", path+"?offset=2", strings.NewReader("llo"), 3, 200)
	final := uploadTestRequest(t, server, "PUT", path+"?offset=5&final=true", nil, 0, 200)
	if S(final, "id") != id || S(final, "name") != "CON.txt" || N(final, "size") != 5 {
		t.Fatalf("final response: %+v", final)
	}
	finalRetry := uploadTestRequest(t, server, "PUT", path+"?offset=5&final=true", nil, 0, 200)
	if S(finalRetry, "id") != id {
		t.Fatalf("retry did not return attachment: %+v", finalRetry)
	}
	// Even if the process stops just after committing, a leftover marker cannot make DELETE remove a published file.
	if _, err := os.Stat(marker); !os.IsNotExist(err) {
		t.Fatalf("pending marker remained after commit: %v", err)
	}
	if err := os.WriteFile(marker, []byte(`{"name":"CON.txt","mime":"text/plain","size":5}`), 0600); err != nil {
		t.Fatal(err)
	}
	uploadTestRequest(t, server, "DELETE", path, nil, 0, 204)
	files, err := ResolveUploads(dir, []any{id})
	if err != nil || len(files) != 1 {
		t.Fatalf("attachment unavailable after completion: %v, %v", files, err)
	}
	filePath := S(files[0], "path")
	content, err := os.ReadFile(filePath)
	if err != nil || string(content) != "hello" || filepath.Base(filePath) != "_CON.txt" {
		t.Fatalf("unexpected content or storage name: %q %q %v", filePath, content, err)
	}
	if prompt := AttachmentsPrompt("question", files); !strings.Contains(prompt, filepath.ToSlash(filePath)) {
		t.Fatalf("missing finalized path in chat prompt: %s", prompt)
	}
}

type uploadPatternReader byte

func (b uploadPatternReader) Read(buffer []byte) (int, error) {
	for i := range buffer {
		buffer[i] = byte(b)
	}
	return len(buffer), nil
}

func TestChunkedUploadExceedsLegacyLimit(t *testing.T) {
	dir := t.TempDir()
	server := uploadTestHandler(dir)
	const size = legacyUploadLimit + 23
	id := S(uploadTestRequest(t, server, "POST", fmt.Sprintf("/api/uploads/chunked?name=large.bin&size=%d", size), nil, 0, 200), "id")
	path := "/api/uploads/chunked/" + id
	uploadTestRequest(t, server, "PUT", path+"?offset=0", nil, uploadChunkLimit+1, 413)
	for offset := int64(0); offset < size; {
		length := min(uploadChunkLimit, size-offset)
		data := io.LimitReader(uploadPatternReader('x'), length)
		result := uploadTestRequest(t, server, "PUT", fmt.Sprintf("%s?offset=%d", path, offset), data, length, 200)
		offset += length
		if N(result, "offset") != float64(offset) {
			t.Fatalf("wrong offset: %d future %+v", offset, result)
		}
	}
	uploadTestRequest(t, server, "PUT", fmt.Sprintf("%s?offset=%d&final=true", path, size), nil, 0, 200)
	files, err := ResolveUploads(dir, []any{id})
	if err != nil || len(files) != 1 || N(files[0], "size") != size {
		t.Fatalf("large attachment not resolvable: %+v %v", files, err)
	}
	file, err := os.Open(S(files[0], "path"))
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	var tail [23]byte
	if _, err := file.ReadAt(tail[:], legacyUploadLimit); err != nil || string(tail[:]) != strings.Repeat("x", len(tail)) {
		t.Fatalf("wrong bytes beyond the former limit: %q %v", tail, err)
	}
	uploadTestRequest(t, server, "POST", fmt.Sprintf("/api/uploads/chunked?name=too-large.bin&size=%d", uploadLimit+1), nil, 0, 413)
}

func TestChunkedUploadRemovesAbortedAndStaleParts(t *testing.T) {
	dir := t.TempDir()
	server := uploadTestHandler(dir)
	id := S(uploadTestRequest(t, server, "POST", "/api/uploads/chunked?name=old.txt", nil, 0, 200), "id")
	path := "/api/uploads/chunked/" + id
	uploadTestRequest(t, server, "PUT", path+"?offset=0", strings.NewReader("old"), 3, 200)
	part := filepath.Join(dir, "attachments", id, "file", "old.txt")
	old := time.Now().Add(-partialUploadTTL - time.Hour)
	if err := os.Chtimes(part, old, old); err != nil {
		t.Fatal(err)
	}
	legacy := filepath.Join(dir, "upload-parts", NewID())
	if err := os.Mkdir(legacy, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(legacy, old, old); err != nil {
		t.Fatal(err)
	}
	server = uploadTestHandler(dir)
	uploadTestRequest(t, server, "PUT", path+"?offset=3&final=true", nil, 0, 404)
	if _, err := os.Stat(part); !os.IsNotExist(err) {
		t.Fatalf("stale part remained: %v", err)
	}
	if _, err := os.Stat(legacy); !os.IsNotExist(err) {
		t.Fatalf("legacy partial directory remained: %v", err)
	}
	id = S(uploadTestRequest(t, server, "POST", "/api/uploads/chunked?name=cancel.txt", nil, 0, 200), "id")
	path = "/api/uploads/chunked/" + id
	uploadTestRequest(t, server, "DELETE", path, nil, 0, 204)
	uploadTestRequest(t, server, "PUT", path+"?offset=0&final=true", nil, 0, 404)
}

func TestChunkedUploadAcceptsEmptyBodiesWithoutContentLength(t *testing.T) {
	dir := t.TempDir()
	server := uploadTestHandler(dir)
	start := "/api/uploads/chunked?name=empty.txt&size=0"
	uploadTestRequest(t, server, "POST", start, strings.NewReader("unexpected"), -1, 400)
	id := S(uploadTestRequest(t, server, "POST", start, io.MultiReader(), -1, 200), "id")
	finish := "/api/uploads/chunked/" + id + "?offset=0&final=true"
	uploadTestRequest(t, server, "PUT", finish, strings.NewReader("unexpected"), -1, 400)
	uploadTestRequest(t, server, "PUT", finish, iotest.ErrReader(io.ErrUnexpectedEOF), -1, 400)
	result := uploadTestRequest(t, server, "PUT", finish, io.MultiReader(), -1, 200)
	if S(result, "id") != id || N(result, "size") != 0 {
		t.Fatalf("wrong result: %+v", result)
	}
}

func TestAttachmentStorageKeepsExistingChatAttachments(t *testing.T) {
	data, storage := t.TempDir(), t.TempDir()
	legacy := uploadTestRequest(t, uploadTestHandler(data), "POST", "/api/uploads?name=previous.txt", strings.NewReader("old"), 3, 200)
	if err := SaveConfig(data, Config{Port: 8787, Token: "test", AttachmentStorage: storage}); err != nil {
		t.Fatal(err)
	}
	config, err := LoadConfig(data)
	if err != nil || config.AttachmentStorage != storage {
		t.Fatalf("saved attachment directory was not loaded: %q %v", config.AttachmentStorage, err)
	}
	app := &App{DataDir: data, Config: config}
	mux := http.NewServeMux()
	registerMedia(app, mux)
	streamed := uploadTestRequest(t, mux, "POST", "/api/uploads?name=streamed.txt", strings.NewReader("new"), 3, 200)
	init := uploadTestRequest(t, mux, "POST", "/api/uploads/chunked?name=chunked.txt&size=4", nil, 0, 200)
	path := "/api/uploads/chunked/" + S(init, "id")
	uploadTestRequest(t, mux, "PUT", path+"?offset=0", strings.NewReader("data"), 4, 200)
	chunked := uploadTestRequest(t, mux, "PUT", path+"?offset=4&final=true", nil, 0, 200)

	type attachment struct {
		root string
		meta M
		body string
	}
	attachments := []attachment{{data, legacy, "old"}, {storage, streamed, "new"}, {storage, chunked, "data"}}
	ids := make([]any, 0, len(attachments))
	for _, file := range attachments {
		ids = append(ids, S(file.meta, "id"))
	}
	prompt, err := app.messageText(M{"text": "use these", "files": ids})
	if err != nil {
		t.Fatal(err)
	}
	for _, file := range attachments {
		filename := filepath.Join(file.root, "attachments", S(file.meta, "id"), "file", S(file.meta, "name"))
		body, err := os.ReadFile(filename)
		if err != nil || string(body) != file.body || !strings.Contains(prompt, filepath.ToSlash(filename)) {
			t.Fatalf("attachment not stored or included in chat: %q %v\n%s", body, err, prompt)
		}
	}
}

func TestAttachmentStorageRejectsRelativePaths(t *testing.T) {
	data := t.TempDir()
	if err := os.WriteFile(filepath.Join(data, "config.json"), []byte(`{"attachmentStorage":"relative"}`), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := LoadConfig(data); err == nil {
		t.Fatal("accepted relative attachmentStorage path")
	}
}
