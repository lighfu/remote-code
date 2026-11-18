package host

import (
	"bytes"
	"errors"
	"image"
)

// Preserve the actual desktop under a cursor Windows composites for us.
// Undo it before comparing DXGI damage or capturing its new position. This
// also restores an idle monitor after the pointer moves to another display.
func (s *desktopRegionState) restoreCursorBackground() {
	r := s.cursorArea
	if !r.Empty() {
		copyDesktopRect(s.pixels, s.size.X*4, r.Min, s.cursorBackground, r.Dx()*4, image.Rectangle{Max: r.Size()})
	}
}

func (s *desktopRegionState) markCursorArea(r image.Rectangle) {
	if r.Empty() {
		return
	}
	cols := (s.size.X + desktopTile - 1) / desktopTile
	for y := r.Min.Y / desktopTile; y <= (r.Max.Y-1)/desktopTile; y++ {
		for x := r.Min.X / desktopTile; x <= (r.Max.X-1)/desktopTile; x++ {
			s.dirty[y*cols+x] = true
		}
	}
}

// Only the cursor's rectangle needs a GDI surface. Windows still draws the
// original icon, including alpha blending and monochrome AND/XOR masks,
// against the full-color pixels underneath. Fall back to the regular
// compositor if this small surface cannot reproduce the cursor.
func (s *desktopRegionState) drawCursorPatch(target image.Rectangle, cursor desktopCursorInfo) bool {
	var bounds image.Rectangle
	if cursor.handle != 0 {
		var ok bool
		bounds, ok = desktopCursorBounds(target, s.size, cursor)
		if !ok {
			return false
		}
	}
	area := bounds.Intersect(image.Rectangle{Max: s.size})
	if area != s.cursorArea {
		s.markCursorArea(s.cursorArea)
	}
	if area.Empty() {
		s.cursorArea = image.Rectangle{}
		return true
	}
	if area.Dx() > 1024 || area.Dy() > 1024 {
		return false
	}
	err := withDesktopDIB(area.Size(), func(mem, _ uintptr, dib []byte) error {
		stride := area.Dx() * 4
		copyDesktopRect(dib, stride, image.Point{}, s.pixels, s.size.X*4, area)
		s.cursorBackground = append(s.cursorBackground[:0], dib...)
		if !drawDesktopCursor(mem, cursor.handle, bounds.Sub(area.Min)) {
			return errors.New("Windows could not draw the desktop cursor")
		}
		if ok, _, _ := desktopFlush.Call(); ok == 0 {
			return errors.New("Windows could not finish drawing the cursor")
		}
		if area != s.cursorArea || !bytes.Equal(dib, s.cursorPixels) {
			s.markCursorArea(area)
			s.cursorPixels = append(s.cursorPixels[:0], dib...)
		}
		copyDesktopRect(s.pixels, s.size.X*4, area.Min, dib, stride, image.Rectangle{Max: area.Size()})
		return nil
	})
	if err != nil {
		return false
	}
	s.cursorArea = area
	return true
}
