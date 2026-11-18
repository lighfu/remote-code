package host

import (
	"bytes"
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestDesktopAudioStream(t *testing.T) {
	packet := []byte{0, 64, 0, 32}
	mux := http.NewServeMux()
	opened := 0
	registerDesktopAudio(mux, true, func(_ context.Context, _ <-chan struct{}, emit func([]byte) error) error {
		opened++
		if err := emit(nil); err != nil {
			return err
		}
		return emit(packet)
	}, nil)
	request := func(method string) *httptest.ResponseRecorder {
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, httptest.NewRequest(method, "/api/desktop/audio", nil))
		return w
	}
	if w := request("HEAD"); w.Code != 200 || w.Body.Len() != 0 || opened != 0 {
		t.Fatal("HEAD opened the capture device")
	}
	w := request("GET")
	if w.Code != 200 || !w.Flushed || w.Header().Get("Content-Type") != desktopAudioType ||
		!bytes.Equal(w.Body.Bytes(), packet) || opened != 1 {
		t.Fatal("expected an unbuffered PCM stream")
	}
	for _, tc := range []struct {
		supported bool
		status    int
	}{{false, http.StatusNotImplemented}, {true, http.StatusServiceUnavailable}} {
		mux = http.NewServeMux()
		registerDesktopAudio(mux, tc.supported, func(context.Context, <-chan struct{}, func([]byte) error) error {
			if !tc.supported {
				t.Fatal("capture opened on an unsupported server")
			}
			return errors.New("no playback device")
		}, nil)
		if w := request("GET"); w.Code != tc.status || w.Header().Get("Content-Type") == desktopAudioType {
			t.Errorf("expected error %d before stream starts, got %d", tc.status, w.Code)
		}
	}
}
