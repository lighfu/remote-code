package host

import (
	"bytes"
	"encoding/json"
	"errors"
	"image"
	"image/jpeg"
	"net/http"
	"net/http/httptest"
	"slices"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestDesktopPairedRoutes(t *testing.T) {
	displays := []desktopDisplay{
		{ID: "panel", Width: 1920, Height: 1080, Primary: true},
		{ID: "side", X: -1200, Y: -320, Width: 1200, Height: 1920},
	}
	layout := desktopLayoutID(displays)
	changed := append([]desktopDisplay(nil), displays...)
	changed[1].Y++
	if desktopLayoutID(changed) == layout {
		t.Fatal("moving the secondary display must invalidate the previous frame layout")
	}
	var frame bytes.Buffer
	if err := jpeg.Encode(&frame, image.NewRGBA(image.Rect(0, 0, 2, 2)), nil); err != nil {
		t.Fatal(err)
	}
	frameCalls := 0
	var actions []desktopInput
	var display string
	var selected desktopFrameOptions
	mux := http.NewServeMux()
	registerDesktop(mux, desktopBackend{
		supported:      true,
		displays:       func() ([]desktopDisplay, error) { return displays, nil },
		captureMethods: func([]desktopDisplay) []string { return []string{"auto", "gdi", "dxgi"} },
		frame: func(id string, options desktopFrameOptions) (desktopFrame, error) {
			frameCalls++
			display, selected = id, options
			if id == "missing" {
				return desktopFrame{}, errDesktopDisplayChanged
			}
			if id == "idle" {
				return desktopFrame{}, errDesktopFramePending
			}
			return desktopFrame{data: frame.Bytes(), layout: layout, capture: "gdi", etag: `"pixels"`, cursor: "0.5,0.2", revision: 7}, nil
		},
		input: func(in desktopInput) error {
			if in.Layout != layout {
				return errDesktopDisplayChanged
			}
			actions = append(actions, in)
			return nil
		},
	})
	app := testApp(t)
	handler := app.middleware(mux)
	request := func(method, path, body string, paired bool, etags ...string) *httptest.ResponseRecorder {
		t.Helper()
		req := httptest.NewRequest(method, path, strings.NewReader(body))
		req.RemoteAddr = "127.0.0.1:12345"
		if paired {
			req.Header.Set("Authorization", "Bearer "+app.Config.Token)
		}
		if len(etags) > 0 {
			req.Header.Set("If-None-Match", etags[0])
		}
		req.Header.Set("Accept-Encoding", "gzip")
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, req)
		return w
	}
	for _, endpoint := range []struct{ method, path string }{
		{"GET", "/api/desktop/displays"}, {"GET", "/api/desktop/frame"}, {"POST", "/api/desktop/input"}, {"GET", "/api/desktop/audio"},
	} {
		if w := request(endpoint.method, endpoint.path+"?token="+app.Config.Token, "", false); w.Code != http.StatusUnauthorized {
			t.Fatalf("unpaired %s returned %d", endpoint.path, w.Code)
		}
	}
	list := request("GET", "/api/desktop/displays", "", true)
	var result struct {
		Layout                                                                                string
		Displays                                                                              []desktopDisplay
		CaptureMethods, Codecs                                                                []string
		Touchpad, Clipboard, NativeResolution, CursorMetadata, ConditionalFrames, FrameStream bool
		CompressionControls                                                                   bool
	}
	if err := json.Unmarshal(list.Body.Bytes(), &result); err != nil || result.Layout != layout || len(result.Displays) != 2 || result.Displays[1].X != -1200 ||
		!slices.Equal(result.Codecs, []string{"jpeg", "png"}) || !slices.Contains(result.CaptureMethods, "dxgi") || !result.Touchpad || !result.Clipboard || !result.NativeResolution || !result.CursorMetadata || !result.ConditionalFrames || !result.FrameStream || !result.CompressionControls {
		t.Fatalf("display list: %s", list.Body)
	}
	request("HEAD", "/api/desktop/frame", "", true)
	if frameCalls != 0 {
		t.Fatal("unexpected capture before requesting a frame")
	}
	w := request("GET", "/api/desktop/frame", "", true)
	if w.Code != 200 || display != "all" || selected != (desktopFrameOptions{capture: "gdi", codec: "jpeg"}) || !bytes.Equal(w.Body.Bytes(), frame.Bytes()) ||
		w.Header().Get("X-Remote-Code-Desktop-Layout") != layout || w.Header().Get("Cache-Control") != "no-store" ||
		w.Header().Get("Content-Type") != "image/jpeg" || w.Header().Get("Content-Encoding") != "" {
		t.Fatalf("bad all-display JPEG response: %d %v", w.Code, w.Header())
	}
	w = request("GET", "/api/desktop/frame?display=side&detail=high&capture=auto&codec=png", "", true)
	if w.Code != 200 || display != "side" || selected != (desktopFrameOptions{highDetail: true, capture: "auto", codec: "png"}) ||
		w.Header().Get("X-Remote-Code-Desktop-Capture") != "gdi" || w.Header().Get("Content-Type") != "image/png" {
		t.Fatal("selected high-detail monitor did not reach capture backend")
	}
	w = request("GET", "/api/desktop/frame?display=side&detail=native&cursor=separate", "", true, `"pixels"`)
	if !selected.native || !selected.separateCursor || selected.previousETag != `"pixels"` ||
		w.Code != http.StatusNotModified || w.Body.Len() != 0 || w.Header().Get("X-Remote-Code-Desktop-Layout") != layout ||
		w.Header().Get("X-Remote-Code-Desktop-Cursor") != "0.5,0.2" || w.Header().Get("X-Remote-Code-Desktop-Cursor-Revision") != "7" || w.Header().Get("ETag") != `"pixels"` {
		t.Fatalf("unchanged desktop: %d %v %+v", w.Code, w.Header(), selected)
	}
	for _, q := range []string{"codec=unknown", "capture=unknown", "detail=invalid", "cursor=invalid"} {
		if w := request("GET", "/api/desktop/frame?"+q, "", true); w.Code != 400 {
			t.Fatalf("%s returned %d", q, w.Code)
		}
	}
	if w := request("GET", "/api/desktop/frame?display=missing", "", true); w.Code != 409 {
		t.Fatalf("removed display: %d", w.Code)
	}
	if w := request("GET", "/api/desktop/frame?display=idle&capture=dxgi", "", true); w.Code != 429 || w.Header().Get("Retry-After") != "1" {
		t.Fatalf("waiting for first DXGI frame: %d %v", w.Code, w.Header())
	}
	send := func(fields string) *httptest.ResponseRecorder {
		return request("POST", "/api/desktop/input", `{"layout":"`+layout+`","display":"side",`+fields+`}`, true)
	}
	w = send(`"type":"drag","x":0,"y":1,"endX":0.7,"endY":0.2`)
	if w.Code != 204 || w.Body.Len() != 0 || len(actions) != 1 || actions[0].X != 0 || actions[0].EndX != 0.7 || actions[0].Display != "side" {
		t.Fatalf("bad input delivery: %v %v", w, actions)
	}
	if revision, err := strconv.ParseUint(w.Header().Get("X-Remote-Code-Desktop-Cursor-Revision"), 10, 64); err != nil || revision == 0 {
		t.Fatalf("invalid accepted input revision: %v", w.Header())
	}
	for _, fields := range []string{
		`"type":"click","x":0`, `"type":"click","x":null,"y":0`,
		`"type":"click","x":-0.1,"y":0`, `"type":"click","x":0,"y":1.01`,
		`"type":"scroll","x":0,"y":0,"delta":1.5`, `"type":"drag","x":0,"y":0`,
		`"type":"text","text":""`, `"type":"text","text":"` + strings.Repeat("a", 2049) + `"`,
		`"type":"key","key":"unknown"`, `"type":"unknown"`,
	} {
		if w := send(fields); w.Code != 400 {
			t.Fatalf("invalid input returned %d", w.Code)
		}
	}
	w = request("POST", "/api/desktop/input", `{"type":"text","display":"side","layout":"old","text":"hi"}`, true)
	if w.Code != 409 || len(actions) != 1 {
		t.Fatal("dispatched input for stale layout or invalid input")
	}
	w = send(`"type":"text","text":"` + strings.Repeat("a", 33<<10) + `"`)
	if w.Code != 413 {
		t.Fatalf("oversize input returned %d", w.Code)
	}
}

func TestDesktopCompressionRoutes(t *testing.T) {
	mux := http.NewServeMux()
	var captured desktopFrameOptions
	registerDesktop(mux, desktopBackend{supported: true, frame: func(_ string, options desktopFrameOptions) (desktopFrame, error) {
		captured = options
		return desktopFrame{}, errDesktopDisplayChanged
	}})
	for _, endpoint := range []string{"frame", "stream"} {
		for _, tc := range []struct{ query, key string }{
			{"", "jpeg"}, {"jpegQuality=1", "jpeg-1"}, {"jpegQuality=85", "jpeg-85"}, {"jpegQuality=100", "jpeg-100"},
			{"codec=png", "png"}, {"codec=png&pngCompression=speed", "png"}, {"codec=png&pngCompression=none", "png-none"},
			{"codec=png&pngCompression=balanced", "png-balanced"}, {"codec=png&pngCompression=best", "png-best"},
			{"jpegQuality=", ""}, {"jpegQuality=0", ""}, {"jpegQuality=101", ""}, {"jpegQuality=8.1", ""},
			{"codec=png&jpegQuality=50", ""}, {"pngCompression=best", ""}, {"codec=png&pngCompression=unknown", ""},
		} {
			captured = desktopFrameOptions{}
			r := httptest.NewRequest("GET", "/api/desktop/"+endpoint+"?"+tc.query+"&bitrateKbps=500", nil)
			w := httptest.NewRecorder()
			mux.ServeHTTP(w, r)
			if tc.key == "" {
				if w.Code != http.StatusBadRequest || captured.codec != "" {
					t.Fatalf("%s %s: invalid compression reached capture (%d)", endpoint, tc.query, w.Code)
				}
				continue
			}
			if w.Code != http.StatusConflict || captured.encodingKey() != tc.key || (captured.rate != nil) != (endpoint == "stream") {
				t.Fatalf("%s %s: status=%d options=%+v", endpoint, tc.query, w.Code, captured)
			}
		}
	}
}

func TestDesktopInputRequiresFreshHostFrame(t *testing.T) {
	const etag = `"unchanged"`
	var actions []desktopInput
	var captureAt int64
	mux := http.NewServeMux()
	registerDesktop(mux, desktopBackend{
		supported: true,
		frame: func(_ string, _ desktopFrameOptions) (desktopFrame, error) {
			captureAt = time.Now().UnixMilli()
			return desktopFrame{layout: "layout", etag: etag, data: []byte("image")}, nil
		},
		input: func(in desktopInput) error { actions = append(actions, in); return nil },
	})
	input := func(at string) *httptest.ResponseRecorder {
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, httptest.NewRequest("POST", "/api/desktop/input", strings.NewReader(`{"display":"all","layout":"layout","type":"cursorClick","frameAt":`+at+`}`)))
		return w
	}
	for _, status := range []int{200, 304} {
		req := httptest.NewRequest("GET", "/api/desktop/frame", nil)
		if status == 304 {
			req.Header.Set("If-None-Match", etag)
		}
		before := time.Now().UnixMilli()
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, req)
		stamp := w.Header().Get("X-Remote-Code-Desktop-Frame-At")
		at, err := strconv.ParseInt(stamp, 10, 64)
		if w.Code != status || err != nil || at < before || at > captureAt {
			t.Fatalf("frame timestamp %q status %d: %v", stamp, w.Code, err)
		}
		if in := input(stamp); in.Code != 204 {
			t.Fatalf("fresh timestamp %q rejected: %d", stamp, in.Code)
		}
	}
	for _, at := range []int64{time.Now().Add(-5 * time.Second).UnixMilli(), time.Now().Add(time.Hour).UnixMilli()} {
		if w := input(strconv.FormatInt(at, 10)); w.Code != 409 || !strings.Contains(w.Body.String(), "Desktop frame has expired") {
			t.Fatalf("timestamp %d: %d %s", at, w.Code, w.Body)
		}
	}
	for _, stamp := range []string{"0", "null", "1.5", `"text"`, "9007199254740992"} {
		if w := input(stamp); w.Code != 400 {
			t.Fatalf("accepted malformed timestamp %s: %d", stamp, w.Code)
		}
	}
	if len(actions) != 2 {
		t.Fatalf("executed %d inputs, want two fresh inputs", len(actions))
	}
}

func TestDesktopUnavailable(t *testing.T) {
	for _, tt := range []struct {
		supported bool
		status    int
	}{{false, 501}, {true, 503}} {
		mux := http.NewServeMux()
		registerDesktop(mux, desktopBackend{supported: tt.supported, displays: func() ([]desktopDisplay, error) {
			return nil, errors.New("desktop locked")
		}})
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, httptest.NewRequest("GET", "/api/desktop/displays", nil))
		if w.Code != tt.status {
			t.Fatalf("desktop availability returned %d, want %d", w.Code, tt.status)
		}
	}
}
