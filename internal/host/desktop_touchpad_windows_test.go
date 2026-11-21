package host

import (
	"image"
	"math"
	"testing"
)

func TestDesktopTouchpadPositionAndClick(t *testing.T) {
	monitors := []desktopDisplay{{ID: "left", X: -100, Width: 100, Height: 100}, {ID: "primary", Width: 100, Height: 100, Primary: true}}
	cursor := image.Pt(-50, 50)
	p, err := desktopTouchpadInput(desktopInput{Display: "all", Type: "moveBy", X: .5, Y: 0}, monitors, cursor)
	if err != nil || p.Type != "move" || int(math.Round(p.X*199))-100 != 50 {
		t.Fatalf("relative move: %+v %v", p, err)
	}
	events, err := desktopInputEvents(p, monitors)
	if err != nil || len(events) != 1 || events[0].mouse.flags&(mouseLeftDown|mouseRightDown) != 0 {
		t.Fatal("moving the pointer pressed a mouse button")
	}
	for _, tc := range []struct {
		typ  string
		flag uint32
	}{{"cursorClick", mouseLeftDown}, {"cursorRightClick", mouseRightDown}} {
		p, err = desktopTouchpadInput(desktopInput{Display: "left", Type: tc.typ}, monitors, cursor)
		if err != nil {
			t.Fatal(err)
		}
		events, err = desktopInputEvents(p, monitors)
		if err != nil || len(events) != 3 || events[1].mouse.flags != tc.flag || int(math.Round(p.X*99))-100 != cursor.X {
			t.Fatalf("cursor click: %+v %v", p, err)
		}
	}
	if _, err = desktopTouchpadInput(desktopInput{Display: "primary", Type: "cursorClick"}, monitors, cursor); err == nil {
		t.Fatal("clicked on a different monitor")
	}
}

func TestDesktopLiveDragNativeEvents(t *testing.T) {
	monitors := []desktopDisplay{{ID: "panel", Width: 1920, Height: 1080, Primary: true}}
	for _, tc := range []struct {
		typ   string
		flags uint32
	}{
		{"cursorDragStart", mouseLeftDown},
		{"cursorDragMove", mouseMove | mouseAbsolute | mouseVirtual | mouseNoCoalesce},
		{"cursorDragEnd", mouseLeftUp},
	} {
		t.Run(tc.typ, func(t *testing.T) {
			in := desktopInput{Display: "panel", Type: tc.typ, X: 0.03, Y: -0.014}
			available := monitors
			if tc.typ == "cursorDragEnd" {
				available = nil // A vanished monitor must not prevent button release.
			} else {
				var err error
				in, err = desktopTouchpadInput(in, monitors, image.Pt(1000, 500))
				if err != nil {
					t.Fatal(err)
				}
			}
			events, err := desktopInputEvents(in, available)
			if err != nil || len(events) != 1 || events[0].mouse.flags != tc.flags {
				t.Fatalf("input sequence = %+v, err = %v", events, err)
			}
		})
	}
}
