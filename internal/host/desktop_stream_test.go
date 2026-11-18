package host

import (
	"bufio"
	"context"
	"image"
	"io"
	"mime"
	"net/http"
	"net/http/httptest"
	"net/textproto"
	"strconv"
	"sync/atomic"
	"testing"
	"time"
)

func TestDesktopStreamFramesAndShutdown(t *testing.T) {
	var calls atomic.Int32
	const tag = "\"desktop-one\""
	backend := desktopBackend{supported: true, frame: func(target string, opt desktopFrameOptions) (desktopFrame, error) {
		frame := desktopFrame{layout: "layout", capture: "gdi", cursor: "hidden", etag: tag}
		if target != "all" || opt.codec != "png" {
			t.Errorf("unexpected stream selection %s / %s", target, opt.codec)
		}
		if calls.Add(1) == 1 {
			frame.data = []byte("first image")
		} else {
			if opt.previousETag != tag {
				t.Errorf("next capture did not reuse image validator: %q", opt.previousETag)
			}
			frame.cursor = "0.5,0.25"
		}
		return frame, nil
	}}
	done := make(chan struct{})
	mux := http.NewServeMux()
	registerDesktop(mux, backend, done)
	app := testApp(t)
	server := httptest.NewServer(app.middleware(mux))
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx, "GET", server.URL+"/api/desktop/stream?display=all&codec=png&fps=30&cursor=separate", nil)
	req.Header.Set("Authorization", "Bearer "+app.Config.Token)
	resp, err := server.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	mediaType, params, err := mime.ParseMediaType(resp.Header.Get("Content-Type"))
	if err != nil || mediaType != "multipart/x-mixed-replace" || params["boundary"] != desktopStreamBoundary {
		t.Fatalf("unexpected stream response: %d %s", resp.StatusCode, resp.Header.Get("Content-Type"))
	}
	reader := textproto.NewReader(bufio.NewReader(resp.Body))
	readPart := func() (textproto.MIMEHeader, string) {
		t.Helper()
		line, err := reader.ReadLine()
		if err == nil && line == "" { // CRLF between the previous payload and boundary
			line, err = reader.ReadLine()
		}
		if err != nil || line != "--"+desktopStreamBoundary {
			t.Fatalf("bad stream boundary %q: %v", line, err)
		}
		h, err := reader.ReadMIMEHeader()
		if err != nil {
			t.Fatal(err)
		}
		n, err := strconv.Atoi(h.Get("Content-Length"))
		if err != nil || n < 0 || n > 100 {
			t.Fatalf("invalid part length %q", h.Get("Content-Length"))
		}
		b := make([]byte, n)
		if _, err := io.ReadFull(reader.R, b); err != nil {
			t.Fatal(err)
		}
		return h, string(b)
	}
	h, body := readPart()
	if body != "first image" || h.Get("Content-Type") != "image/png" || h.Get("ETag") != tag {
		t.Fatalf("first frame %v %q", h, body)
	}
	h, body = readPart()
	if body != "" || h.Get("Content-Type") != "application/octet-stream" || h.Get("X-Remote-Code-Desktop-Cursor") != "0.5,0.25" {
		t.Fatalf("cursor-only frame %v %q", h, body)
	}
	h, body = readPart()
	if body != "" || h.Get("ETag") != tag {
		t.Fatalf("idle heartbeat %v %q", h, body)
	}
	close(done)
	if _, err := reader.R.ReadByte(); err != io.EOF {
		t.Fatalf("stream did not end on shutdown: %v", err)
	}
}

func TestDesktopStreamValidatesBeforeCapture(t *testing.T) {
	mux := http.NewServeMux()
	calls := 0
	registerDesktop(mux, desktopBackend{supported: true, frame: func(string, desktopFrameOptions) (desktopFrame, error) {
		calls++
		return desktopFrame{}, errDesktopDisplayChanged
	}})
	app := testApp(t)
	handler := app.middleware(mux)
	for _, tc := range []struct {
		method, query string
		paired        bool
		status        int
	}{
		{"GET", "", false, 401}, {"GET", "?fps=0", true, 400}, {"GET", "?fps=61", true, 400},
		{"GET", "?codec=bad", true, 400}, {"HEAD", "?fps=60", true, 200}, {"GET", "", true, 409},
		{"HEAD", "?bitrateKbps=499", true, 400}, {"HEAD", "?bitrateKbps=150001", true, 400},
		{"HEAD", "?bitrateKbps=oops", true, 400}, {"HEAD", "?bitrateKbps=16000", true, 200},
	} {
		req := httptest.NewRequest(tc.method, "/api/desktop/stream"+tc.query, nil)
		req.RemoteAddr = "127.0.0.1:12345"
		if tc.paired {
			req.Header.Set("Authorization", "Bearer "+app.Config.Token)
		}
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, req)
		if w.Code != tc.status {
			t.Errorf("%s %s: got %d, want %d", tc.method, tc.query, w.Code, tc.status)
		}
	}
	if calls != 1 {
		t.Fatalf("captured %d frames, expected only the final request", calls)
	}
}

func TestDesktopStreamBudgetAndQuality(t *testing.T) {
	const kbps = 16000
	rate := desktopStreamRate{bytesPerSecond: kbps * 125, targetPeriod: time.Second / 60}
	// A native keyframe followed by motion on one monitor and a clock on the other.
	start := time.Unix(1, 0)
	rate.observeJPEG(5760*2160, 86, 900000)
	period := rate.nextPeriod(start, 25*time.Millisecond, 900000)
	if period > 2*rate.targetPeriod {
		t.Fatal("keyframe stalled subsequent patches", period)
	}
	rate.observeJPEG(1920*1080, 86, 300000)
	videoQuality := rate.jpegQuality(1920*1080, 86)
	textQuality := rate.jpegQuality(128*128, 86)
	if videoQuality >= textQuality || textQuality != 86 || videoQuality < 32 {
		t.Fatal(videoQuality, textQuality)
	}
	for i := 0; i < 60; i++ {
		start = start.Add(period)
		period = rate.nextPeriod(start, 20*time.Millisecond, 100000)
	}
	// A stable 100 KB/frame costs 50 ms at 16 Mbps.
	if period < 49*time.Millisecond || period > 51*time.Millisecond {
		t.Fatal("unsteady sustained byte rate", period)
	}
	start = start.Add(3 * time.Second)
	if wait := rate.nextPeriod(start, time.Millisecond, 0); wait != rate.targetPeriod {
		t.Fatal("idle frame retained debt", wait)
	}
}

func TestDesktopRegionNegotiation(t *testing.T) {
	mux := http.NewServeMux()
	var got bool
	registerDesktop(mux, desktopBackend{supported: true, frame: func(_ string, opts desktopFrameOptions) (desktopFrame, error) {
		got = opts.regions != nil
		return desktopFrame{}, errDesktopDisplayChanged
	}})
	for _, tc := range []struct {
		url  string
		want bool
	}{
		{"/api/desktop/stream?regions=1", true}, {"/api/desktop/stream", false}, {"/api/desktop/frame?regions=1", false},
	} {
		got = !tc.want
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, httptest.NewRequest("GET", tc.url, nil))
		if got != tc.want || w.Code != 409 {
			t.Fatal("incorrect region negotiation for", tc.url)
		}
	}
	h := make(http.Header)
	r := desktopRegion{dest: image.Rect(128, 256, 384, 512)}
	frame := desktopFrame{regions: []desktopRegion{r}, baseETag: "base"}
	writeDesktopFrameHeaders(h, frame, "jpeg")
	if h.Get("X-Remote-Code-Desktop-Region") != "128,256,256,256" || h.Get("X-Remote-Code-Desktop-Base-ETag") != "base" {
		t.Fatal(h)
	}
	h = make(http.Header)
	frame.regions = append(frame.regions, desktopRegion{dest: image.Rect(0, 0, 128, 128), source: image.Pt(256, 0)})
	writeDesktopFrameHeaders(h, frame, "png")
	if h.Get("X-Remote-Code-Desktop-Regions") != "128,256,256,256,0,0;0,0,128,128,256,0" || h.Get("X-Remote-Code-Desktop-Region") != "" {
		t.Fatal(h)
	}
}

func TestDesktopStreamReleasesSlotOnDisconnect(t *testing.T) {
	mux := http.NewServeMux()
	registerDesktop(mux, desktopBackend{supported: true, frame: func(string, desktopFrameOptions) (desktopFrame, error) {
		return desktopFrame{layout: "layout", data: []byte("image"), capture: "gdi"}, nil
	}})
	server := httptest.NewServer(mux)
	defer server.Close()
	client := &http.Client{Timeout: 4 * time.Second}
	open := func() *http.Response {
		t.Helper()
		resp, err := client.Get(server.URL + "/api/desktop/stream?fps=1")
		if err != nil {
			t.Fatal(err)
		}
		return resp
	}
	first, second := open(), open()
	defer first.Body.Close()
	defer second.Body.Close()
	if first.StatusCode != 200 || second.StatusCode != 200 {
		t.Fatalf("opening independent streams: %d, %d", first.StatusCode, second.StatusCode)
	}
	extra := open()
	extra.Body.Close()
	if extra.StatusCode != http.StatusTooManyRequests || extra.Header.Get("Retry-After") == "" {
		t.Fatal("stream limit was not enforced")
	}
	resp, err := client.Get(server.URL + "/api/desktop/frame")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatal("stream retained the capture lock while waiting")
	}
	first.Body.Close()
	deadline := time.Now().Add(time.Second)
	for {
		replacement := open()
		replacement.Body.Close()
		if replacement.StatusCode == 200 {
			break
		}
		if replacement.StatusCode != http.StatusTooManyRequests || time.Now().After(deadline) {
			t.Fatalf("disconnected stream kept its slot: %d", replacement.StatusCode)
		}
		time.Sleep(time.Millisecond)
	}
}

func TestDesktopStreamWaitsForInFlightFrame(t *testing.T) {
	var captures atomic.Int32
	blocked := make(chan struct{})
	release := make(chan struct{})
	mux := http.NewServeMux()
	registerDesktop(mux, desktopBackend{supported: true, frame: func(_ string, _ desktopFrameOptions) (desktopFrame, error) {
		if captures.Add(1) == 1 {
			close(blocked)
			<-release
		}
		return desktopFrame{layout: "stable", capture: "gdi", etag: "frame", data: []byte("abc")}, nil
	}})
	server := httptest.NewServer(mux)
	defer server.Close()
	go func() {
		resp, err := server.Client().Get(server.URL + "/api/desktop/frame")
		if err == nil {
			resp.Body.Close()
		}
	}()
	<-blocked
	started := time.Now()
	time.AfterFunc(80*time.Millisecond, func() { close(release) })
	resp, err := server.Client().Get(server.URL + "/api/desktop/stream?fps=60")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 200 || time.Since(started) < 60*time.Millisecond {
		t.Fatalf("new stream did not wait for the current capture: %d", resp.StatusCode)
	}
}

func TestDesktopGateWaitCanBeCancelled(t *testing.T) {
	gate := make(desktopGate, 1)
	gate <- struct{}{}
	for _, stop := range []bool{true, false} {
		ctx, cancel := context.WithCancel(context.Background())
		shutdown := make(chan struct{})
		done := make(chan error, 1)
		go func() { done <- gate.acquire(ctx, shutdown, "busy") }()
		if stop {
			close(shutdown)
		} else {
			cancel()
		}
		select {
		case err := <-done:
			if err != context.Canceled {
				t.Fatal(err)
			}
		case <-time.After(500 * time.Millisecond):
			t.Fatal("capture waiter ignored cancellation")
		}
		cancel()
	}
}
