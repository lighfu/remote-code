package host

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"testing"
)

func TestRawFileBoundedPages(t *testing.T) {
	root := t.TempDir()
	path := filepath.Join(root, "日本語 #1.bin")
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = f.WriteAt([]byte{0x41, 0xff, 0}, rawFileLimit); err != nil {
		t.Fatal(err)
	}
	if err = f.Close(); err != nil {
		t.Fatal(err)
	}
	a := &App{Store: &Store{records: map[string]*Record{"s": {Info: M{"cwd": root}}}}, Config: Config{Token: "paired"}}
	mux := http.NewServeMux()
	registerMedia(a, mux)
	handler := a.middleware(mux)
	request := func(byteRange, condition, validator, token string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("GET", "/api/file/raw?link="+url.QueryEscape(filepath.Base(path))+"&session=s", nil)
		r.RemoteAddr = "127.0.0.1:8080"
		r.Header.Set("Authorization", "Bearer "+token)
		r.Header.Set("Accept-Encoding", "gzip")
		r.Header.Set("Range", byteRange)
		r.Header.Set(condition, validator)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, r)
		return rec
	}
	part := fmt.Sprintf("bytes=%d-%d", rawFileLimit, rawFileLimit+9)
	r := request(part, "If-Match", "", "paired")
	if r.Code != 206 || r.Body.String() != "A\xff\x00" || r.Header().Get("Content-Range") != fmt.Sprintf("bytes %d-%d/%d", rawFileLimit, rawFileLimit+2, rawFileLimit+3) || r.Header().Get("Content-Encoding") != "" {
		t.Fatalf("invalid page: HTTP %d %v body length %d", r.Code, r.Header(), r.Body.Len())
	}
	etag := r.Header().Get("ETag")
	if etag == "" || request("bytes=0-0", "If-Match", etag, "paired").Code != 206 {
		t.Fatal("page validator rejected")
	}
	for _, tc := range []struct {
		byteRange, condition, validator, token string
		status                                 int
	}{
		{"bytes=0-0", "If-Match", "", "wrong", 401},
		{"", "If-Match", "", "paired", 413},
		{"bytes=0-", "If-Match", "", "paired", 413},
		{"bytes=0-4,8-12", "If-Match", "", "paired", 413},
		{fmt.Sprintf("bytes=0-%d", rawFilePageLimit), "If-Match", "", "paired", 413},
		{"bytes=0-1", "If-Range", `"stale"`, "paired", 413},
		{"bytes=0-1", "If-Match", `"stale"`, "paired", 412},
		{"bytes=4294967296-4294967299", "If-Match", "", "paired", 416},
	} {
		if got := request(tc.byteRange, tc.condition, tc.validator, tc.token).Code; got != tc.status {
			t.Errorf("%q %s %q: %d, want %d", tc.byteRange, tc.condition, tc.validator, got, tc.status)
		}
	}
	if err := os.WriteFile(path, []byte("tiny"), 0600); err != nil {
		t.Fatal(err)
	}
	if got := request("bytes=0-1", "If-Match", etag, "paired").Code; got != 412 {
		t.Fatalf("changed file: %d", got)
	}
	if got := request("", "If-Match", "", "paired"); got.Code != 200 || got.Body.String() != "tiny" {
		t.Fatal("normal download failed")
	}
}

func TestMediaLinkBinaryAndText(t *testing.T) {
	root := t.TempDir()
	for _, tc := range []struct {
		name       string
		data       []byte
		kind, text string
	}{
		{"empty.bin", nil, "binary", ""},
		{"doc.pdf", []byte("%PDF-1.4"), "binary", ""},
		{"invalid.dat", []byte{0xff, 0x42}, "binary", ""},
		{"controls", []byte{1, 2, 3}, "binary", ""},
		{"empty.txt", nil, "text", ""},
		{"legacy.txt", []byte{0xe9, 0x61}, "text", "�a"},
		{"source", []byte("你好\r\n"), "text", "你好\r\n"},
		{"utf16.dat", fileEncode("日本語😀", "utf-16le", "lf"), "text", "日本語😀"},
	} {
		if err := os.WriteFile(filepath.Join(root, tc.name), tc.data, 0600); err != nil {
			t.Fatal(err)
		}
		info, err := mediaLinkInfo(tc.name, root)
		if err != nil || info["kind"] != tc.kind || S(info, "text") != tc.text {
			t.Fatalf("%s: %v %v", tc.name, info, err)
		}
	}
	// The cutoff is inside a UTF-8 character or a UTF-16 surrogate pair.
	for _, encoding := range []string{"utf-8", "utf-16le", "utf-16be"} {
		data := fileEncode("a😀", encoding, "lf")
		got, _, ok := fileText(data[:len(data)-2], true)
		if !ok || got != "a" {
			t.Errorf("%s partial text = %q, ok=%t", encoding, got, ok)
		}
	}
}
