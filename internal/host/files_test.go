package host

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func fileFixture(t *testing.T) (string, string) {
	t.Helper()
	root := t.TempDir()
	path := filepath.Join(root, "sample.txt")
	if err := os.WriteFile(path, []byte("first\r\nsecond\r\n"), 0644); err != nil {
		t.Fatal(err)
	}
	real, err := filepath.EvalSymlinks(root)
	if err != nil {
		t.Fatal(err)
	}
	return real, path
}
func TestProjectSavePreservesEncodingAndRejectsStaleRevision(t *testing.T) {
	root, path := fileFixture(t)
	doc, err := fileDocument(root, path)
	if err != nil {
		t.Fatal(err)
	}
	if S(doc, "text") != "first\nsecond\n" || S(doc, "lineEnding") != "crlf" {
		t.Fatalf("unexpected document %#v", doc)
	}
	saved, err := fileSave(root, M{"root": root, "path": "sample.txt", "revision": S(doc, "revision"), "text": "changed\n"})
	if err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(path)
	if string(data) != "changed\r\n" {
		t.Fatalf("line ending lost: %q", data)
	}
	if S(saved, "revision") == S(doc, "revision") {
		t.Fatal("revision did not change")
	}
	if _, err = fileSave(root, M{"root": root, "path": "sample.txt", "revision": S(doc, "revision"), "text": "stale\n"}); err == nil {
		t.Fatal("stale write accepted")
	}
}
func TestProjectUTF16AndBinary(t *testing.T) {
	for _, encoding := range []string{"utf-8", "utf-8-bom", "utf-16le", "utf-16be"} {
		bytes := fileEncode("日本語😀\n", encoding, "crlf")
		text, enc, ending, ok := fileDecode(bytes, "sample.txt")
		if !ok || text != "日本語😀\n" || enc != encoding || ending != "crlf" {
			t.Fatalf("%s: %q %s %s %v", encoding, text, enc, ending, ok)
		}
	}
	if _, _, _, ok := fileDecode([]byte{0, 1, 2}, "image.png"); ok {
		t.Fatal("binary treated as text")
	}
}
func TestProjectBoundariesAndGitInternals(t *testing.T) {
	root, _ := fileFixture(t)
	for _, path := range []string{"../outside.txt", ".git/config", "sub/.GIT/config"} {
		if _, err := fileLexical(root, path); err == nil {
			t.Fatalf("unsafe path accepted %s", path)
		}
	}
	outside := t.TempDir()
	link := filepath.Join(root, "escape")
	if err := os.Symlink(outside, link); err == nil {
		if _, err = fileTarget(root, "escape"); err == nil {
			t.Fatal("escaping symlink accepted")
		}
	}
}
func TestProjectActionsNeverOverwrite(t *testing.T) {
	root, path := fileFixture(t)
	if err := os.WriteFile(filepath.Join(root, "existing.txt"), []byte("keep"), 0644); err != nil {
		t.Fatal(err)
	}
	entry, err := fileEntry(root, "sample.txt")
	if err != nil {
		t.Fatal(err)
	}
	for _, action := range []string{"copy", "move", "rename"} {
		if _, err = fileAction(root, M{"root": root, "path": "sample.txt", "version": S(entry, "version"), "action": action, "destination": "existing.txt"}); err == nil {
			t.Fatalf("%s overwrote destination", action)
		}
	}
	data, _ := os.ReadFile(filepath.Join(root, "existing.txt"))
	if string(data) != "keep" {
		t.Fatal("destination was overwritten")
	}
	if _, err = fileAction(root, M{"root": root, "path": "sample.txt", "version": S(entry, "version"), "action": "copy", "destination": "copied.txt"}); err != nil {
		t.Fatal(err)
	}
	if _, err = fileAction(root, M{"root": root, "path": "sample.txt", "version": S(entry, "version"), "action": "delete", "confirm": false}); err == nil {
		t.Fatal("delete without confirmation accepted")
	}
	if _, err = os.Stat(path); err != nil {
		t.Fatal("source unexpectedly removed")
	}
	if err = fileRenameExclusive(filepath.Join(root, "copied.txt"), filepath.Join(root, "existing.txt")); err == nil {
		t.Fatal("native exclusive rename replaced target")
	}
}
func TestProjectLargeFilesAndCounts(t *testing.T) {
	root, _ := fileFixture(t)
	if err := os.Mkdir(filepath.Join(root, "folder"), 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(root, ".git"), 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "large.txt"), bytes.Repeat([]byte("x"), projectTextLimit+1), 0644); err != nil {
		t.Fatal(err)
	}
	list, err := fileList(root, "", "")
	if err != nil {
		t.Fatal(err)
	}
	if N(list, "folderCount") != 1 || N(list, "fileCount") != 2 {
		t.Fatalf("bad counts %#v", list)
	}
	doc, err := fileDocument(root, filepath.Join(root, "large.txt"))
	if err != nil {
		t.Fatal(err)
	}
	if B(doc, "editable") || doc["text"] != nil {
		t.Fatal("large document was read for editing")
	}
}
func TestMediaUploadAndByteRanges(t *testing.T) {
	dataDir := t.TempDir()
	r := httptest.NewRequest("POST", "/api/uploads?name=CON.txt&mime=text/plain", strings.NewReader("hello"))
	r.Header.Set("Content-Type", "application/octet-stream")
	file, err := receiveUpload(dataDir, r)
	if err != nil {
		t.Fatal(err)
	}
	files, err := ResolveUploads(dataDir, []any{S(file, "id")})
	if err != nil {
		t.Fatal(err)
	}
	if len(files) != 1 || filepath.Base(S(files[0], "path")) != "_CON.txt" {
		t.Fatalf("bad upload %#v", files)
	}
	if !strings.Contains(AttachmentsPrompt("question", files), "Attachments (saved on PC):") {
		t.Fatal("attachment prompt missing")
	}
	if _, err = ResolveUploads(dataDir, []any{"../unsafe"}); err == nil {
		t.Fatal("invalid upload ID accepted")
	}
	image := filepath.Join(dataDir, "image.png")
	if err = os.WriteFile(image, []byte("0123456789"), 0644); err != nil {
		t.Fatal(err)
	}
	app := &App{DataDir: dataDir}
	mux := http.NewServeMux()
	registerMedia(app, mux)
	request := httptest.NewRequest("GET", "/api/media/"+MediaID(image), nil)
	request.Header.Set("Range", "bytes=2-5")
	reply := httptest.NewRecorder()
	mux.ServeHTTP(reply, request)
	if reply.Code != 206 || reply.Body.String() != "2345" {
		t.Fatalf("range failed %d %q", reply.Code, reply.Body.String())
	}
	request = httptest.NewRequest("GET", "/api/media/"+MediaID(image), nil)
	request.Header.Set("If-None-Match", reply.Header().Get("ETag"))
	reply = httptest.NewRecorder()
	mux.ServeHTTP(reply, request)
	if reply.Code != 304 {
		t.Fatalf("ETag condition failed %d", reply.Code)
	}
}
func TestMediaLinkLiteralPercentAndLineSuffix(t *testing.T) {
	root := t.TempDir()
	path := filepath.Join(root, "a%20b#L12.txt")
	if err := os.WriteFile(path, []byte("hello"), 0644); err != nil {
		t.Fatal(err)
	}
	resolved, line := mediaResolveLink(path, "")
	if resolved != path || line != nil {
		t.Fatalf("literal path changed %q %v", resolved, line)
	}
	resolved, line = mediaResolveLink(path+":3", "")
	if resolved != path || line != 3 {
		t.Fatalf("line reference failed %q %v", resolved, line)
	}
	if runtime.GOOS == "windows" {
		resolved, line = mediaResolveLink("file:///"+strings.NewReplacer("%", "%25", "#", "%23").Replace(filepath.ToSlash(path)), "")
		if resolved != path || line != nil {
			t.Fatalf("file URI failed %q %v", resolved, line)
		}
	}
}
func TestProjectWireJSON(t *testing.T) {
	root, _ := fileFixture(t)
	doc, err := fileDocument(root, filepath.Join(root, "sample.txt"))
	if err != nil {
		t.Fatal(err)
	}
	raw, err := json.Marshal(doc)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(raw, []byte(`"revision":`)) || !bytes.Contains(raw, []byte(`"absolutePath":`)) {
		t.Fatal("document API fields missing")
	}
	var decoded M
	if err = json.NewDecoder(io.NopCloser(bytes.NewReader(raw))).Decode(&decoded); err != nil {
		t.Fatal(err)
	}
}
