//go:build windows

package host

import (
	"errors"
	"fmt"
	"image"
	"math"
	"net/http"
	"slices"
	"strings"
	"unicode/utf16"
	"unsafe"
)

var desktopSendInput = desktopUser32.NewProc("SendInput")
var desktopCursorPos = desktopUser32.NewProc("GetCursorPos")

const (
	mouseMove       = 0x0001
	mouseLeftDown   = 0x0002
	mouseLeftUp     = 0x0004
	mouseRightDown  = 0x0008
	mouseRightUp    = 0x0010
	mouseWheel      = 0x0800
	mouseNoCoalesce = 0x2000
	mouseVirtual    = 0x4000
	mouseAbsolute   = 0x8000

	winKeyExtended = 0x01
	winKeyUp       = 0x02
	winKeyUnicode  = 0x04
)

// The Windows INPUT union has the size and alignment of MOUSEINPUT, even
// when it carries a smaller KEYBDINPUT. These layouts also work on 32-bit Go.
type desktopMouseEvent struct {
	x, y              int32
	data, flags, time uint32
	extra             uintptr
}

type desktopKeyEvent struct {
	virtual, scan uint16
	flags, time   uint32
	extra         uintptr
}

type desktopWinEvent struct {
	kind  uint32
	mouse desktopMouseEvent
}

func desktopKey(virtual, scan uint16, flags uint32) desktopWinEvent {
	e := desktopWinEvent{kind: 1 /* INPUT_KEYBOARD */}
	*(*desktopKeyEvent)(unsafe.Pointer(&e.mouse)) = desktopKeyEvent{virtual: virtual, scan: scan, flags: flags}
	return e
}

func desktopButton(flags uint32) desktopWinEvent {
	return desktopWinEvent{mouse: desktopMouseEvent{flags: flags}}
}

func desktopMove(target, virtual image.Rectangle, monitors []desktopDisplay, x, y float64) (desktopWinEvent, error) {
	if !(x >= 0 && x <= 1 && y >= 0 && y <= 1) {
		return desktopWinEvent{}, errors.New("pointer position outside display")
	}
	p := image.Pt(int(math.Round(x*float64(target.Dx()-1))), int(math.Round(y*float64(target.Dy()-1)))).Add(target.Min)
	for _, monitor := range monitors {
		if p.In(monitorRectangle(monitor)) {
			// Windows bins 0..65535 into physical pixels of the virtual screen.
			// Aim at the pixel center so the left edge of a monitor maps exactly.
			center := func(offset, length int) int32 {
				return int32((2*int64(offset) + 1) * 65536 / (2 * int64(length)))
			}
			return desktopWinEvent{mouse: desktopMouseEvent{
				x: center(p.X-virtual.Min.X, virtual.Dx()), y: center(p.Y-virtual.Min.Y, virtual.Dy()),
				flags: mouseMove | mouseAbsolute | mouseVirtual | mouseNoCoalesce,
			}}, nil
		}
	}
	return desktopWinEvent{}, &HTTPError{http.StatusUnprocessableEntity, "Pointer is between displays"}
}

var desktopKeyCodes = map[string]uint16{
	"enter": 0x0d, "tab": 0x09, "backspace": 0x08, "escape": 0x1b,
	"left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28, "delete": 0x2e,
	"printScreen": 0x2c,
}

func desktopKeyboardEvents(input desktopInput) ([]desktopWinEvent, error) {
	if input.Type == "text" {
		// A single carriage return represents a line break in a Windows edit control.
		text := strings.NewReplacer("\r\n", "\r", "\n", "\r").Replace(input.Text)
		units := utf16.Encode([]rune(text))
		events := make([]desktopWinEvent, 0, len(units)*2)
		for _, unit := range units {
			events = append(events, desktopKey(0, unit, winKeyUnicode), desktopKey(0, unit, winKeyUnicode|winKeyUp))
		}
		return events, nil
	}
	if input.Key == "altTab" {
		return []desktopWinEvent{
			desktopKey(0x12, 0, 0), desktopKey(0x09, 0, 0),
			desktopKey(0x09, 0, winKeyUp), desktopKey(0x12, 0, winKeyUp),
		}, nil
	}
	vk, ok := desktopKeyCodes[input.Key]
	if !ok {
		return nil, errors.New("unsupported desktop key")
	}
	var flags uint32
	if vk >= 0x25 { // Cursor keys, Delete and Print Screen are extended keys.
		flags = winKeyExtended
	}
	return []desktopWinEvent{desktopKey(vk, 0, flags), desktopKey(vk, 0, flags|winKeyUp)}, nil
}

func desktopInputEvents(input desktopInput, monitors []desktopDisplay) ([]desktopWinEvent, error) {
	if input.Type == "cursorDragEnd" {
		return []desktopWinEvent{desktopButton(mouseLeftUp)}, nil
	}
	target, virtual, err := desktopRectangles(monitors, input.Display)
	if err != nil {
		return nil, err
	}
	if input.Type == "key" || input.Type == "text" {
		return desktopKeyboardEvents(input)
	}
	start, err := desktopMove(target, virtual, monitors, input.X, input.Y)
	if err != nil {
		return nil, err
	}
	events := []desktopWinEvent{start}
	switch input.Type {
	case "cursorDragStart":
		return []desktopWinEvent{desktopButton(mouseLeftDown)}, nil
	case "move":
		return events, nil
	case "click":
		return append(events, desktopButton(mouseLeftDown), desktopButton(mouseLeftUp)), nil
	case "doubleClick":
		return append(events, desktopButton(mouseLeftDown), desktopButton(mouseLeftUp), desktopButton(mouseLeftDown), desktopButton(mouseLeftUp)), nil
	case "rightClick":
		return append(events, desktopButton(mouseRightDown), desktopButton(mouseRightUp)), nil
	case "scroll":
		return append(events, desktopWinEvent{mouse: desktopMouseEvent{flags: mouseWheel, data: uint32(int32(input.Delta * 120))}}), nil
	case "drag":
		end, err := desktopMove(target, virtual, monitors, input.EndX, input.EndY)
		if err != nil {
			return nil, err
		}
		events = append(events, desktopButton(mouseLeftDown))
		// Send intermediate motion so apps can detect a drag and process the
		// movement that follows. Skip unoccupied areas of a tiled desktop.
		const steps = 8
		for i := 1; i < steps; i++ {
			t := float64(i) / steps
			motion, err := desktopMove(target, virtual, monitors, input.X+t*(input.EndX-input.X), input.Y+t*(input.EndY-input.Y))
			if err == nil {
				events = append(events, motion)
			}
		}
		return append(events, end, desktopButton(mouseLeftUp)), nil
	default:
		return nil, errors.New("unsupported desktop action")
	}
}

// Convert touchpad deltas from the real cursor position while staying on a
// visible monitor. Windows virtual desktops can have gaps and negative origins.
func desktopTouchpadInput(in desktopInput, monitors []desktopDisplay, cursor image.Point) (desktopInput, error) {
	target, _, err := desktopRectangles(monitors, in.Display)
	if err != nil {
		return in, err
	}
	if !cursor.In(target) {
		if in.Type != "moveBy" {
			return in, &HTTPError{http.StatusUnprocessableEntity, "Move the pointer onto the selected display first"}
		}
		cursor = target.Min.Add(target.Size().Div(2))
	}
	next := cursor.Add(image.Pt(int(math.Round(in.X*float64(target.Dx()-1))), int(math.Round(in.Y*float64(target.Dy()-1)))))
	var end image.Point
	distance := math.Inf(1)
	for _, d := range monitors {
		r := target.Intersect(monitorRectangle(d))
		if r.Empty() {
			continue
		}
		p := image.Pt(max(r.Min.X, min(next.X, r.Max.X-1)), max(r.Min.Y, min(next.Y, r.Max.Y-1)))
		dx, dy := float64(p.X-next.X), float64(p.Y-next.Y)
		if squared := dx*dx + dy*dy; squared < distance {
			end, distance = p, squared
		}
	}
	normalized := func(p image.Point) (float64, float64) {
		return float64(p.X-target.Min.X) / float64(max(1, target.Dx()-1)), float64(p.Y-target.Min.Y) / float64(max(1, target.Dy()-1))
	}
	in.X, in.Y = normalized(cursor)
	switch in.Type {
	case "moveBy", "cursorDragMove":
		in.Type = "move"
		in.X, in.Y = normalized(end)
	case "cursorDragBy":
		in.Type = "drag"
		in.EndX, in.EndY = normalized(end)
	case "cursorClick":
		in.Type = "click"
	case "cursorDoubleClick":
		in.Type = "doubleClick"
	case "cursorRightClick":
		in.Type = "rightClick"
	case "cursorScroll":
		in.Type = "scroll"
	}
	return in, nil
}

func sendDesktopEvents(events []desktopWinEvent) int {
	if len(events) == 0 {
		return 0
	}
	n, _, _ := desktopSendInput.Call(uintptr(len(events)), uintptr(unsafe.Pointer(&events[0])), unsafe.Sizeof(events[0]))
	return int(n)
}

// Our actions only nest key presses (Alt+Tab) or hold a single mouse button.
// Release any presses accepted before a partial Windows input failure.
func desktopPendingReleases(sent []desktopWinEvent) []desktopWinEvent {
	var held []desktopWinEvent
	for _, e := range sent {
		if e.kind == 1 {
			k := *(*desktopKeyEvent)(unsafe.Pointer(&e.mouse))
			if k.flags&winKeyUp == 0 {
				held = append(held, desktopKey(k.virtual, k.scan, k.flags|winKeyUp))
			} else if len(held) != 0 {
				held = held[:len(held)-1]
			}
		} else {
			switch e.mouse.flags {
			case mouseLeftDown:
				held = append(held, desktopButton(mouseLeftUp))
			case mouseRightDown:
				held = append(held, desktopButton(mouseRightUp))
			case mouseLeftUp, mouseRightUp:
				held = nil
			}
		}
	}
	slices.Reverse(held)
	return held
}

func applyDesktopInput(input desktopInput) error {
	leave, err := enterInteractiveDesktop()
	if err != nil {
		return err
	}
	defer leave()
	if input.Type == "cursorDragEnd" {
		return injectDesktopInput(input, nil)
	}
	monitors, err := desktopMonitors()
	if err != nil {
		return err
	}
	if input.Layout != desktopLayoutID(monitors) {
		return errDesktopDisplayChanged
	}
	if input.Type == "moveBy" || strings.HasPrefix(input.Type, "cursor") {
		keepalive := input.Type == "cursorDragMove" && input.X == 0 && input.Y == 0
		var pos struct{ X, Y int32 }
		ok, _, e := desktopCursorPos.Call(uintptr(unsafe.Pointer(&pos)))
		if ok == 0 {
			return fmt.Errorf("cannot locate desktop pointer: %w", e)
		}
		input, err = desktopTouchpadInput(input, monitors, image.Pt(int(pos.X), int(pos.Y)))
		if err != nil {
			return err
		}
		if keepalive {
			return nil
		}
	}
	return injectDesktopInput(input, monitors)
}

func injectDesktopInput(input desktopInput, monitors []desktopDisplay) error {
	events, err := desktopInputEvents(input, monitors)
	if err != nil {
		return err
	}
	if n := sendDesktopEvents(events); n != len(events) {
		sendDesktopEvents(desktopPendingReleases(events[:n]))
		return fmt.Errorf("Windows accepted %d of %d input events", n, len(events))
	}
	return nil
}
