package host

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

type desktopDisplay struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	X       int    `json:"x"`
	Y       int    `json:"y"`
	Width   int    `json:"width"`
	Height  int    `json:"height"`
	Primary bool   `json:"primary"`
}

// The native backend returns displays in stable order. Bind input to the
// geometry of the frame the phone saw, including monitor positions and sizes.
func desktopLayoutID(displays []desktopDisplay) string {
	data, _ := json.Marshal(displays)
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:16])
}

var errDesktopDisplayChanged = errors.New("Display layout changed. Refresh the remote desktop.")
var errDesktopFramePending = errors.New("Waiting for the first desktop frame")

type desktopInput struct {
	Display, Layout, Type string
	FrameAt               int64
	X, Y, EndX, EndY      float64
	Delta                 int
	Text, Key             string
}

type desktopBackend struct {
	supported      bool
	displays       func() ([]desktopDisplay, error)
	captureMethods func([]desktopDisplay) []string
	frame          func(display string, options desktopFrameOptions) (desktopFrame, error)
	input          func(desktopInput) error
}

type desktopFrameOptions struct {
	highDetail, native, separateCursor bool
	capture, codec, previousETag       string
	regions                            *desktopRegionState
	rate                               *desktopStreamRate
}

type desktopFrame struct {
	data                                          []byte
	layout, capture, etag, cursor, cursorPosition string
	revision                                      uint64
	frameAt                                       int64
	elapsed                                       time.Duration
	baseETag                                      string
	regions                                       []desktopRegion
}

// The sampled pointer position and revision describe the same applied inputs.
var desktopCursorState struct {
	sync.Mutex
	revision uint64
}

func parseDesktopFrameQuery(query url.Values) (string, desktopFrameOptions, error) {
	target := query.Get("display")
	if target == "" {
		target = "all"
	}
	detail, cursor := query.Get("detail"), query.Get("cursor")
	opts := desktopFrameOptions{capture: query.Get("capture"), codec: query.Get("codec"), highDetail: detail == "high", native: detail == "native", separateCursor: cursor == "separate"}
	// Older clients requested GDI frames without specifying a capture method.
	if opts.capture == "" {
		opts.capture = "gdi"
	}
	if opts.codec == "" {
		opts.codec = "jpeg"
	}
	if len(target) > 256 || (detail != "" && detail != "normal" && !opts.highDetail && !opts.native) ||
		(opts.capture != "auto" && opts.capture != "gdi" && opts.capture != "dxgi") ||
		(opts.codec != "jpeg" && opts.codec != "png") || (cursor != "" && cursor != "embedded" && !opts.separateCursor) {
		return "", desktopFrameOptions{}, &HTTPError{http.StatusBadRequest, "Invalid desktop frame options"}
	}
	return target, opts, nil
}

// Give a reconnect or second viewer a turn after a capture already in progress.
// Channel waiters queue in order. Slow HTTP writes never hold this gate.
type desktopGate chan struct{}

func (gate desktopGate) acquire(ctx context.Context, shutdown <-chan struct{}, busy string) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-shutdown:
		return context.Canceled
	default:
	}
	select {
	case gate <- struct{}{}:
		return nil
	default:
	}
	timer := time.NewTimer(750 * time.Millisecond)
	defer timer.Stop()
	select {
	case gate <- struct{}{}:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	case <-shutdown:
		return context.Canceled
	case <-timer.C:
		return &HTTPError{http.StatusTooManyRequests, busy}
	}
}

func (gate desktopGate) release() { <-gate }

func captureDesktopHTTPFrame(ctx context.Context, shutdown <-chan struct{}, backend desktopBackend, gate desktopGate, target string, options desktopFrameOptions) (desktopFrame, error) {
	if err := gate.acquire(ctx, shutdown, "Desktop capture is busy"); err != nil {
		return desktopFrame{}, err
	}
	defer gate.release()
	if err := ctx.Err(); err != nil {
		return desktopFrame{}, err
	}
	start := time.Now()
	frame, err := backend.frame(target, options)
	frame.frameAt = start.UnixMilli()
	frame.elapsed = time.Since(start)
	return frame, err
}

func writeDesktopFrameHeaders(h http.Header, frame desktopFrame, codec string) {
	h.Set("Content-Type", "image/"+codec)
	h.Set("X-Remote-Code-Desktop-Layout", frame.layout)
	h.Set("X-Remote-Code-Desktop-Frame-At", strconv.FormatInt(frame.frameAt, 10))
	h.Set("X-Remote-Code-Desktop-Capture", frame.capture)
	h.Set("X-Remote-Code-Desktop-Frame-Ms", strconv.FormatFloat(float64(frame.elapsed)/float64(time.Millisecond), 'f', 2, 64))
	h.Set("X-Remote-Code-Desktop-Cursor-Revision", strconv.FormatUint(frame.revision, 10))
	if frame.cursorPosition != "" {
		h.Set("X-Remote-Code-Desktop-Cursor-Position", frame.cursorPosition)
	}
	if frame.cursor != "" {
		h.Set("X-Remote-Code-Desktop-Cursor", frame.cursor)
	}
	if frame.etag != "" {
		h.Set("ETag", frame.etag)
	}
	if len(frame.regions) > 0 {
		h.Set("X-Remote-Code-Desktop-Base-ETag", frame.baseETag)
		var coords []string
		for _, region := range frame.regions {
			r := region.dest
			value := fmt.Sprintf("%d,%d,%d,%d", r.Min.X, r.Min.Y, r.Dx(), r.Dy())
			if len(frame.regions) > 1 {
				value += fmt.Sprintf(",%d,%d", region.source.X, region.source.Y)
			}
			coords = append(coords, value)
		}
		if len(coords) == 1 {
			h.Set("X-Remote-Code-Desktop-Region", coords[0])
		} else {
			h.Set("X-Remote-Code-Desktop-Regions", strings.Join(coords, ";"))
		}
	}
}

func desktopCoordinate(body M, key string) (float64, error) {
	value, ok := body[key].(float64)
	if !ok || !(value >= 0 && value <= 1) {
		return 0, fmt.Errorf("Invalid desktop coordinate: %s", key)
	}
	return value, nil
}

func readDesktopInput(w http.ResponseWriter, r *http.Request) (desktopInput, error) {
	r.Body = http.MaxBytesReader(w, r.Body, 32<<10)
	defer r.Body.Close()
	decoder := json.NewDecoder(r.Body)
	var body M
	if err := decoder.Decode(&body); err != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			return desktopInput{}, &HTTPError{http.StatusRequestEntityTooLarge, "Desktop input is too large"}
		}
		return desktopInput{}, errors.New("Expected a desktop input object")
	}
	if decoder.Decode(new(json.RawMessage)) != io.EOF {
		return desktopInput{}, errors.New("Unexpected data after desktop input")
	}
	input := desktopInput{Display: S(body, "display"), Layout: S(body, "layout"), Type: S(body, "type")}
	if raw, present := body["frameAt"]; present {
		at, ok := raw.(float64)
		if !ok || at <= 0 || at > (1<<53)-1 || at != float64(int64(at)) {
			return input, errors.New("Invalid desktop frame timestamp")
		}
		input.FrameAt = int64(at)
	}
	if input.Display == "" || len(input.Display) > 256 || input.Layout == "" || len(input.Layout) > 128 {
		return input, errors.New("Desktop input requires a display and frame layout")
	}
	switch input.Type {
	case "text":
		input.Text = S(body, "text")
		if input.Text == "" || strings.ContainsRune(input.Text, 0) || utf8.RuneCountInString(input.Text) > 2048 {
			return input, errors.New("Invalid desktop input text")
		}
	case "key":
		input.Key = S(body, "key")
		switch input.Key {
		case "enter", "backspace", "tab", "escape", "left", "right", "up", "down", "delete", "altTab", "printScreen":
		default:
			return input, errors.New("Unknown desktop key")
		}
	case "cursorClick", "cursorDoubleClick", "cursorRightClick", "cursorScroll":
		// The destination is the live pointer on the selected display.
	case "moveBy", "cursorDragBy":
		x, xOK := body["x"].(float64)
		y, yOK := body["y"].(float64)
		if !xOK || !yOK || x < -1 || x > 1 || y < -1 || y > 1 {
			return input, errors.New("Invalid relative pointer movement")
		}
		input.X, input.Y = x, y
	case "click", "doubleClick", "rightClick", "drag", "scroll":
		var err error
		if input.X, err = desktopCoordinate(body, "x"); err != nil {
			return input, err
		}
		if input.Y, err = desktopCoordinate(body, "y"); err != nil {
			return input, err
		}
		if input.Type == "drag" {
			if input.EndX, err = desktopCoordinate(body, "endX"); err != nil {
				return input, err
			}
			if input.EndY, err = desktopCoordinate(body, "endY"); err != nil {
				return input, err
			}
		}
	default:
		return input, errors.New("Unknown desktop input type")
	}
	if input.Type == "scroll" || input.Type == "cursorScroll" {
		delta, ok := body["delta"].(float64)
		if !ok || delta == 0 || delta < -12 || delta > 12 || delta != float64(int(delta)) {
			return input, errors.New("Invalid scroll distance")
		}
		input.Delta = int(delta)
	}
	return input, nil
}

func desktopError(w http.ResponseWriter, err error) {
	if errors.Is(err, errDesktopFramePending) {
		w.Header().Set("Retry-After", "1")
		apiError(w, &HTTPError{http.StatusTooManyRequests, err.Error()})
		return
	}
	if errors.Is(err, errDesktopDisplayChanged) {
		apiError(w, &HTTPError{http.StatusConflict, err.Error()})
		return
	}
	var httpErr *HTTPError
	if errors.As(err, &httpErr) {
		if httpErr.Status == http.StatusTooManyRequests {
			w.Header().Set("Retry-After", "1")
		}
		apiError(w, httpErr)
		return
	}
	apiError(w, &HTTPError{http.StatusServiceUnavailable, err.Error()})
}

func registerDesktop(mux *http.ServeMux, backend desktopBackend, serverDone ...<-chan struct{}) {
	var inputMu sync.Mutex
	var shutdown <-chan struct{}
	if len(serverDone) > 0 {
		shutdown = serverDone[0]
	}
	captureGate := make(desktopGate, 1)
	registerDesktopStream(mux, backend, captureGate, shutdown)
	supported := func(w http.ResponseWriter) bool {
		if !backend.supported {
			apiError(w, &HTTPError{http.StatusNotImplemented, "Remote desktop requires Windows"})
		}
		return backend.supported
	}
	mux.HandleFunc("GET /api/desktop/displays", func(w http.ResponseWriter, r *http.Request) {
		if !supported(w) {
			return
		}
		displays, err := backend.displays()
		if err != nil {
			desktopError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, M{"displays": displays, "layout": desktopLayoutID(displays), "captureMethods": backend.captureMethods(displays),
			"codecs": []string{"jpeg", "png"}, "touchpad": true, "clipboard": true, "nativeResolution": true, "cursorMetadata": true, "conditionalFrames": true, "frameStream": true, "regionFrames": true, "streamBitrate": true})
	})
	mux.HandleFunc("GET /api/desktop/frame", func(w http.ResponseWriter, r *http.Request) {
		if !supported(w) {
			return
		}
		target, opts, err := parseDesktopFrameQuery(r.URL.Query())
		if err != nil {
			desktopError(w, err)
			return
		}
		if r.Method == http.MethodHead {
			w.Header().Set("Content-Type", "image/"+opts.codec)
			return
		}
		opts.previousETag = r.Header.Get("If-None-Match")
		frame, err := captureDesktopHTTPFrame(r.Context(), shutdown, backend, captureGate, target, opts)
		if err != nil {
			desktopError(w, err)
			return
		}
		writeDesktopFrameHeaders(w.Header(), frame, opts.codec)
		if frame.etag != "" && frame.etag == opts.previousETag {
			w.WriteHeader(http.StatusNotModified)
			return
		}
		w.Header().Set("Content-Length", strconv.Itoa(len(frame.data)))
		_, _ = w.Write(frame.data)
	})
	mux.HandleFunc("POST /api/desktop/input", func(w http.ResponseWriter, r *http.Request) {
		if !supported(w) {
			return
		}
		input, err := readDesktopInput(w, r)
		if err != nil {
			apiError(w, err)
			return
		}
		if !inputMu.TryLock() {
			apiError(w, &HTTPError{http.StatusTooManyRequests, "Another desktop input is in progress"})
			return
		}
		defer inputMu.Unlock()
		if r.Context().Err() != nil {
			return
		}
		desktopCursorState.Lock()
		// Compare the echoed capture time to the same PC's clock, after acquiring
		// the input lock. A proxy can deliver an old image immediately to the app.
		if age := time.Now().UnixMilli() - input.FrameAt; input.FrameAt != 0 && (age < 0 || age >= (5*time.Second).Milliseconds()) {
			err = &HTTPError{http.StatusConflict, "Desktop frame has expired. Wait for a fresh frame before sending input."}
		} else {
			err = backend.input(input)
			if err == nil {
				desktopCursorState.revision++
			}
		}
		revision := desktopCursorState.revision
		desktopCursorState.Unlock()
		if err != nil {
			desktopError(w, err)
			return
		}
		w.Header().Set("X-Remote-Code-Desktop-Cursor-Revision", strconv.FormatUint(revision, 10))
		w.WriteHeader(http.StatusNoContent)
	})
}
