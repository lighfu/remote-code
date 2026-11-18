package host

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestDesktopClipboardCompareAndSync(t *testing.T) {
	a := testApp(t)
	mux := http.NewServeMux()
	var seq uint32 = 7
	text := "first copy"
	registerDesktopClipboard(mux, true, func(after *uint32) (desktopClip, error) {
		clip := desktopClip{Sequence: seq}
		if after != nil && *after != seq {
			clip.Text = &text
		}
		return clip, nil
	}, func(expect uint32, next string) (desktopClip, error) {
		if expect != seq {
			return desktopClip{}, &HTTPError{409, "Changed"}
		}
		seq++
		text = next
		return desktopClip{Sequence: seq, Text: &text}, nil
	})
	handler := a.middleware(mux)
	req := func(method, query, body string, paired bool) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, "/api/desktop/clipboard"+query, strings.NewReader(body))
		r.RemoteAddr = "127.0.0.1:5454"
		if paired {
			r.Header.Set("Authorization", "Bearer "+a.Config.Token)
		}
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		return w
	}
	if w := req("GET", "?after=5", "", false); w.Code != 401 {
		t.Fatal(w.Code)
	}
	if w := req("POST", "", `{"sequence":7,"text":"ignored"}`, false); w.Code != 401 || seq != 7 {
		t.Fatal(w.Code)
	}
	for _, query := range []string{"", "?after=7"} {
		if w := req("GET", query, "", true); w.Code != 200 || strings.Contains(w.Body.String(), "text") || w.Header().Get("Cache-Control") != "no-store" {
			t.Fatal(w)
		}
	}
	if w := req("POST", "", `{"sequence":7,"text":"コピー🌐"}`, true); w.Code != 200 || strings.Contains(w.Body.String(), "text") || seq != 8 || text != "コピー🌐" {
		t.Fatal(w)
	}
	if w := req("POST", "", `{"sequence":7,"text":"old"}`, true); w.Code != 409 || text != "コピー🌐" {
		t.Fatal(w)
	}
	if w := req("GET", "?after=7", "", true); w.Code != 200 || !strings.Contains(w.Body.String(), text) {
		t.Fatal(w)
	}
	if w := req("POST", "", `{"text":"ignored"}`, true); w.Code != 400 || seq != 8 {
		t.Fatal(w)
	}
	if w := req("POST", "", `{"sequence":8,"text":"`+strings.Repeat("a", desktopClipboardLimit+1)+`"}`, true); w.Code != 413 || seq != 8 {
		t.Fatal(w.Code)
	}
}
