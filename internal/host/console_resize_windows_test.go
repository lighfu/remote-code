package host

import (
	"testing"

	"golang.org/x/sys/windows"
)

func TestGrowConsoleWindowAddsRowsWithoutNarrowing(t *testing.T) {
	window := windows.Rect{Left: 30, Top: 100, Right: 1130, Bottom: 1000}
	work := windows.Rect{Right: 1920, Bottom: 1080}
	want := windows.Rect{Left: 30, Top: 60, Right: 1130, Bottom: 1080}
	if got := growConsoleWindow(window, work, 74, 30, 61, 34); got != want {
		t.Fatalf("window = %+v, want %+v", got, want)
	}
}

func TestGrowConsoleWindowCapsGrowthAtMonitorWorkingArea(t *testing.T) {
	window := windows.Rect{Left: -950, Top: 15, Right: -300, Bottom: 800}
	work := windows.Rect{Left: -1000, Top: 10, Right: 0, Bottom: 900}
	if got := growConsoleWindow(window, work, 40, 26, 120, 70); got != work {
		t.Fatalf("window = %+v, want %+v", got, work)
	}
	if got := growConsoleWindow(work, work, 65, 40, 80, 50); got != work {
		t.Fatalf("window with no space for growth moved: %+v", got)
	}
}
