package host

import (
	"context"
	"net/http"
	"time"
)

const desktopAudioType = "audio/x-raw; format=S16LE; rate=48000; channels=2"

type desktopAudioCapture func(context.Context, <-chan struct{}, func([]byte) error) error

func registerDesktopAudio(mux *http.ServeMux, supported bool, capture desktopAudioCapture, shutdown <-chan struct{}) {
	slots := make(desktopGate, 2)
	mux.HandleFunc("GET /api/desktop/audio", func(w http.ResponseWriter, r *http.Request) {
		if !supported {
			desktopError(w, &HTTPError{http.StatusNotImplemented, "Desktop audio requires Windows"})
			return
		}
		if r.Method == http.MethodHead {
			w.Header().Set("Content-Type", desktopAudioType)
			return
		}
		if err := slots.acquire(r.Context(), shutdown, "Desktop audio is busy"); err != nil {
			desktopError(w, err)
			return
		}
		defer slots.release()
		controller := http.NewResponseController(w)
		defer controller.SetWriteDeadline(time.Time{})
		started := false
		err := capture(r.Context(), shutdown, func(pcm []byte) error {
			if !started {
				w.Header().Set("Content-Type", desktopAudioType)
				w.Header().Set("Cache-Control", "no-store, no-transform")
				w.Header().Set("X-Accel-Buffering", "no")
				started = true
			}
			_ = controller.SetWriteDeadline(time.Now().Add(3 * time.Second))
			if len(pcm) > 0 {
				if _, err := w.Write(pcm); err != nil {
					return err
				}
			}
			return controller.Flush()
		})
		if err != nil && !started && r.Context().Err() == nil {
			desktopError(w, err)
		}
	})
}
