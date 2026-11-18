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
