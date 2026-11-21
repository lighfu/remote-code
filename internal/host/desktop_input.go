package host

import (
	"context"
	"net/http"
	"sync"
	"time"
)

const desktopDragLease = 5 * time.Second

// Serialize input with expiry and shutdown so no move can race a button release.
type desktopInputState struct {
	sync.Mutex
	apply    func(desktopInput) error
	drag     desktopInput
	deadline time.Time
	timer    *time.Timer
	closed   bool
}

func (s *desktopInputState) send(ctx context.Context, input desktopInput) (uint64, error) {
	if input.Type == "cursorDragEnd" {
		s.Lock()
	} else if !s.TryLock() {
		return 0, &HTTPError{http.StatusTooManyRequests, "Another desktop input is in progress"}
	}
	defer s.Unlock()
	if s.closed {
		return 0, context.Canceled
	}
	if err := ctx.Err(); err != nil && input.Type != "cursorDragEnd" {
		return 0, err
	}
	desktopCursorState.Lock()
	defer desktopCursorState.Unlock()
	err := s.dispatch(input)
	if input.Type == "cursorDragStart" && err == nil && ctx.Err() != nil {
		_ = s.endDrag()
		return 0, ctx.Err()
	}
	return desktopCursorState.revision, err
}

func (s *desktopInputState) dispatch(input desktopInput) error {
	active := s.drag.DragID != ""
	owned := active && input.DragID == s.drag.DragID
	if input.Type == "cursorDragEnd" {
		if owned {
			return s.endDrag()
		}
		return nil
	}
	if active && !time.Now().Before(s.deadline) {
		if err := s.endDrag(); err != nil {
			return err
		}
		active, owned = false, false
	}
	if input.Type == "cursorDragMove" {
		if !owned {
			return &HTTPError{http.StatusGone, "Desktop drag ended. Start dragging again."}
		}
		if input.Display != s.drag.Display || input.Layout != s.drag.Layout {
			_ = s.endDrag()
			return errDesktopDisplayChanged
		}
	} else if active {
		return &HTTPError{http.StatusTooManyRequests, "A desktop drag is in progress"}
	}
	// Compare the capture timestamp with the same PC's clock after serializing input.
	age := time.Now().UnixMilli() - input.FrameAt
	if input.FrameAt != 0 && (age < 0 || age >= (5*time.Second).Milliseconds()) {
		if owned {
			_ = s.endDrag()
		}
		return &HTTPError{http.StatusConflict, "Desktop frame has expired. Wait for a fresh frame before sending input."}
	}
	if err := s.apply(input); err != nil {
		if owned {
			_ = s.endDrag()
		}
		return err
	}
	if input.Type == "cursorDragMove" {
		s.deadline = time.Now().Add(desktopDragLease)
		if input.X == 0 && input.Y == 0 {
			return nil // Keep the lease alive during a stationary hold.
		}
	} else if input.Type == "cursorDragStart" {
		s.startDrag(input)
	}
	desktopCursorState.revision++
	return nil
}

func (s *desktopInputState) startDrag(input desktopInput) {
	s.drag = input
	s.deadline = time.Now().Add(desktopDragLease)
	var timer *time.Timer
	timer = time.AfterFunc(desktopDragLease, func() {
		s.Lock()
		defer s.Unlock()
		if s.timer != timer {
			return
		}
		if wait := time.Until(s.deadline); wait > 0 {
			timer.Reset(wait)
			return
		}
		desktopCursorState.Lock()
		defer desktopCursorState.Unlock()
		_ = s.endDrag()
	})
	s.timer = timer
}

// The release uses the existing lease and works after a monitor change or stale frame.
func (s *desktopInputState) endDrag() error {
	release := s.drag
	release.Type = "cursorDragEnd"
	if err := s.apply(release); err != nil {
		s.deadline = time.Now()
		s.timer.Reset(time.Second)
		return err
	}
	s.drag = desktopInput{}
	s.timer.Stop()
	s.timer = nil
	desktopCursorState.revision++
	return nil
}

func (s *desktopInputState) close() {
	s.Lock()
	defer s.Unlock()
	s.closed = true
	if s.timer != nil {
		desktopCursorState.Lock()
		defer desktopCursorState.Unlock()
		_ = s.endDrag()
		if s.timer != nil {
			s.timer.Stop()
			s.timer = nil
		}
	}
}
