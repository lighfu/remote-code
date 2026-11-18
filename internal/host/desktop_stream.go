package host

import (
	"errors"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"strconv"
	"time"
)

const desktopStreamBoundary = "rc-frame"

// Capture and write sequentially. A slow connection retains at most the current
// frame and samples the latest desktop when it can write again.
func registerDesktopStream(mux *http.ServeMux, backend desktopBackend, captureGate desktopGate, shutdown <-chan struct{}) {
	slots := make(desktopGate, 2)
	mux.HandleFunc("GET /api/desktop/stream", func(w http.ResponseWriter, r *http.Request) {
		if !backend.supported {
			apiError(w, &HTTPError{http.StatusNotImplemented, "Remote desktop requires Windows"})
			return
		}
		query := r.URL.Query()
		target, options, err := parseDesktopFrameQuery(query)
		if err != nil {
			apiError(w, err)
			return
		}
		if query.Get("regions") == "1" {
			options.regions = &desktopRegionState{id: NewID()}
		}
		fps := 10
		if query.Has("fps") {
			fps, err = strconv.Atoi(query.Get("fps"))
			if err != nil || fps < 1 || fps > 60 {
				apiError(w, &HTTPError{http.StatusBadRequest, "Desktop frame rate must be between 1 and 60"})
				return
			}
		}
		interval := time.Second / time.Duration(fps)
		if query.Has("bitrateKbps") {
			kbps, err := strconv.Atoi(query.Get("bitrateKbps"))
			if err != nil || kbps < 500 || kbps > 150000 {
				apiError(w, &HTTPError{http.StatusBadRequest, "Desktop bitrate must be between 500 and 150000 kbps"})
				return
			}
			options.rate = &desktopStreamRate{bytesPerSecond: float64(kbps * 125), targetPeriod: interval}
		}
		const mediaType = "multipart/x-mixed-replace; boundary=" + desktopStreamBoundary
		if r.Method == http.MethodHead {
			w.Header().Set("Content-Type", mediaType)
			return
		}
		if err := slots.acquire(r.Context(), shutdown, "Desktop streams are busy"); err != nil {
			desktopError(w, err)
			return
		}
		defer slots.release()
		controller := http.NewResponseController(w)
		writer := multipart.NewWriter(w)
		_ = writer.SetBoundary(desktopStreamBoundary)
		var lastSent time.Time
		var lastCursor, lastPosition, layout string
		var lastRevision uint64
		firstFrameDeadline := time.Now().Add(time.Second)
		timer := time.NewTimer(0)
		defer timer.Stop()
		defer controller.SetWriteDeadline(time.Time{})
		for {
			select {
			case <-r.Context().Done():
				return
			case <-shutdown:
				return
			case <-timer.C:
			}
			started := time.Now()
			frame, err := captureDesktopHTTPFrame(r.Context(), shutdown, backend, captureGate, target, options)
			if err != nil {
				if lastSent.IsZero() {
					if errors.Is(err, errDesktopFramePending) && time.Now().Before(firstFrameDeadline) {
						timer.Reset(min(interval, 50*time.Millisecond))
						continue
					}
					desktopError(w, err)
					return
				}
				var busy *HTTPError
				if !errors.Is(err, errDesktopFramePending) && !(errors.As(err, &busy) && busy.Status == http.StatusTooManyRequests) {
					return
				}
				timer.Reset(interval)
				continue
			}
			first := lastSent.IsZero()
			if first {
				if len(frame.data) == 0 {
					desktopError(w, errDesktopFramePending)
					return
				}
				w.Header().Set("Content-Type", mediaType)
				w.Header().Set("Cache-Control", "no-store, no-transform")
				w.Header().Set("X-Accel-Buffering", "no")
				layout = frame.layout
			} else if frame.layout != layout {
				return
			}
			cursorChanged := frame.cursor != lastCursor || frame.cursorPosition != lastPosition || frame.revision != lastRevision
			if len(frame.data) > 0 || cursorChanged || time.Since(lastSent) >= time.Second {
				header := make(http.Header)
				writeDesktopFrameHeaders(header, frame, options.codec)
				if len(frame.data) == 0 {
					header.Set("Content-Type", "application/octet-stream")
				}
				header.Set("Content-Length", strconv.Itoa(len(frame.data)))
				// Give large native/lossless frames time to cross a slow link, while
				// still reclaiming both the slot and image from a stalled reader.
				writeTimeout := min(2*time.Minute, 8*time.Second+time.Duration(len(frame.data))*time.Second/(128<<10))
				_ = controller.SetWriteDeadline(time.Now().Add(writeTimeout))
				part, err := writer.CreatePart(textproto.MIMEHeader(header))
				if err != nil {
					return
				}
				if _, err = part.Write(frame.data); err != nil || controller.Flush() != nil {
					return
				}
				lastSent = time.Now()
				lastCursor, lastPosition, lastRevision = frame.cursor, frame.cursorPosition, frame.revision
			}
			options.previousETag = frame.etag
			period := interval
			if options.rate != nil {
				period = options.rate.nextPeriod(started, frame.elapsed, len(frame.data))
			}
			timer.Reset(max(0, period-time.Since(started)))
		}
	})
}
