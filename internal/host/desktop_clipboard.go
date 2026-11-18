package host

import (
	"encoding/json"
	"io"
	"net/http"
	"strconv"
	"strings"
)

const desktopClipboardLimit = 64 << 10

type desktopClip struct {
	Sequence uint32  `json:"sequence"`
	Text     *string `json:"text,omitempty"`
	TooLarge bool    `json:"tooLarge,omitempty"`
}

func registerDesktopClipboard(mux *http.ServeMux, supported bool, read func(*uint32) (desktopClip, error), write func(uint32, string) (desktopClip, error)) {
	mux.HandleFunc("GET /api/desktop/clipboard", func(w http.ResponseWriter, r *http.Request) {
		if !supported {
			apiError(w, &HTTPError{501, "Clipboard sync requires Windows"})
			return
		}
		if r.Method == http.MethodHead {
			return
		}
		var after *uint32
		if r.URL.Query().Has("after") {
			seq, err := strconv.ParseUint(r.URL.Query().Get("after"), 10, 32)
			if err != nil {
				apiError(w, &HTTPError{400, "Invalid clipboard revision"})
				return
			}
			n := uint32(seq)
			after = &n
		}
		clip, err := read(after)
		if err != nil {
			desktopError(w, err)
			return
		}
		writeJSON(w, 200, clip)
	})
	mux.HandleFunc("POST /api/desktop/clipboard", func(w http.ResponseWriter, r *http.Request) {
		if !supported {
			apiError(w, &HTTPError{501, "Clipboard sync requires Windows"})
			return
		}
		var body struct {
			Sequence *uint32 `json:"sequence"`
			Text     *string `json:"text"`
		}
		r.Body = http.MaxBytesReader(w, r.Body, desktopClipboardLimit*6+256)
		defer r.Body.Close()
		d := json.NewDecoder(r.Body)
		if d.Decode(&body) != nil || d.Decode(new(json.RawMessage)) != io.EOF || body.Sequence == nil || body.Text == nil || strings.ContainsRune(*body.Text, 0) {
			apiError(w, &HTTPError{400, "Invalid clipboard update"})
			return
		}
		if len(*body.Text) > desktopClipboardLimit {
			apiError(w, &HTTPError{413, "Clipboard text exceeds 64 KiB"})
			return
		}
		if r.Context().Err() != nil {
			return
		}
		clip, err := write(*body.Sequence, *body.Text)
		if err != nil {
			desktopError(w, err)
			return
		}
		writeJSON(w, 200, desktopClip{Sequence: clip.Sequence})
	})
}
