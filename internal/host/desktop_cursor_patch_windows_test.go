package host

import (
	"bytes"
	"image"
	"testing"
	"unsafe"
)

// Keep the old full-canvas draw as an independent reference. Exercise all
// four monochrome AND/XOR operations and a real Windows cursor with the
// background changing both underneath and away from the pointer.
func TestDesktopCursorPatchMatchesFullCanvas(t *testing.T) {
	const cursorSide = 32
	andMask := make([]byte, cursorSide*cursorSide/8)
	xorMask := make([]byte, len(andMask))
	for i := range andMask {
		andMask[i], xorMask[i] = 0xcc, 0xaa
	}
	create := desktopUser32.NewProc("CreateCursor")
	mono, _, err := create.Call(0, 15, 9, cursorSide, cursorSide, uintptr(unsafe.Pointer(&andMask[0])), uintptr(unsafe.Pointer(&xorMask[0])))
	if mono == 0 {
		t.Fatal(err)
	}
	defer desktopUser32.NewProc("DestroyCursor").Call(mono)
	hand, _, err := desktopLoadCursor.Call(0, 32649 /* IDC_HAND */)
	if hand == 0 {
		t.Fatal(err)
	}
	size := image.Pt(367, 229)
	full := image.Rectangle{Max: size}
	target := full.Add(image.Pt(-167, -21))
	raw := make([]byte, size.X*size.Y*4)
	paintDesktopTestRect(raw, size, full, 219)
	s := desktopRegionState{}
	frame := desktopFrame{layout: "fixture", capture: "dxgi"}
	var previous []byte
	for i, step := range []struct {
		handle uintptr
		at     image.Point
	}{
		{mono, image.Pt(0, 0)}, {mono, image.Pt(0, 0)}, {mono, image.Pt(165, 80)},
		{mono, image.Pt(165, 80)}, {hand, image.Pt(165, 80)}, {mono, image.Pt(366, 228)},
		{}, {hand, image.Pt(5, 201)}, {},
	} {
		prev := ""
		if i > 0 {
			prev = "last"
		}
		fresh := s.begin(size, &frame, prev)
		s.restoreCursorBackground()
		if fresh {
			s.update(raw, size.X*4, full, true)
		} else if i == 3 {
			damage := image.Rect(157, 77, 170, 85)
			paintDesktopTestRect(raw, size, damage, 68)
			s.update(raw[(damage.Min.Y*size.X+damage.Min.X)*4:], size.X*4, damage, false)
		}
		pos := step.at.Add(target.Min)
		cursor := desktopCursorInfo{handle: step.handle, x: int32(pos.X), y: int32(pos.Y)}
		if !s.drawCursorPatch(target, cursor) {
			t.Fatalf("patch failed at step %d", i)
		}
		e := withDesktopDIB(size, func(dc, _ uintptr, buf []byte) error {
			copy(buf, raw)
			if cursor.handle != 0 {
				r, ok := desktopCursorBounds(target, size, cursor)
				if !ok || !drawDesktopCursor(dc, cursor.handle, r) {
					t.Fatalf("reference draw failed at step %d", i)
				}
				desktopFlush.Call()
			}
			if !bytes.Equal(s.pixels, buf) {
				t.Fatalf("patch changed pixels at step %d", i)
			}
			if previous != nil {
				cols := (size.X + desktopTile - 1) / desktopTile
				for p := 0; p < len(buf); p += 4 {
					x, y := (p/4)%size.X, (p/4)/size.X
					if !bytes.Equal(previous[p:p+4], buf[p:p+4]) && !s.dirty[(y/desktopTile)*cols+x/desktopTile] {
						t.Fatalf("unsent changed pixel at step %d (%d,%d)", i, x, y)
					}
				}
			}
			previous = append(previous[:0], buf...)
			return nil
		})
		if e != nil {
			t.Fatal(e)
		}
		if i == 1 && len(s.changedRects()) != 0 {
			t.Fatal("an unchanged cursor dirtied the output")
		}
		s.tag = "last"
	}
}
