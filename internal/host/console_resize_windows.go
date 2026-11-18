//go:build windows

package host

import (
	"fmt"
	"math"
	"os"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	consoleWindowHandle = consoleKernel.NewProc("GetConsoleWindow")
	consoleMaxWindow    = consoleKernel.NewProc("GetLargestConsoleWindowSize")
	consoleSetBuffer    = consoleKernel.NewProc("SetConsoleScreenBufferSize")
	consoleSetWindow    = consoleKernel.NewProc("SetConsoleWindowInfo")

	consoleUser32       = windows.NewLazySystemDLL("user32.dll")
	consoleAncestor     = consoleUser32.NewProc("GetAncestor")
	consoleWindowPID    = consoleUser32.NewProc("GetWindowThreadProcessId")
	consoleWindowRect   = consoleUser32.NewProc("GetWindowRect")
	consoleMonitor      = consoleUser32.NewProc("MonitorFromWindow")
	consoleMonitorInfo  = consoleUser32.NewProc("GetMonitorInfoW")
	consoleSetPosition  = consoleUser32.NewProc("SetWindowPos")
	consoleWindowZoomed = consoleUser32.NewProc("IsZoomed")
	consoleWindowIconic = consoleUser32.NewProc("IsIconic")
)

func resizeConsoleWindow(columns, rows, neededColumns, neededRows int) bool {
	if columns < 1 || rows < 1 || neededColumns > math.MaxInt16 || neededRows > math.MaxInt16 {
		return false
	}
	output, err := windows.Open("CONOUT$", os.O_RDWR, 0)
	if err != nil {
		return false
	}
	defer windows.CloseHandle(output)
	var info windows.ConsoleScreenBufferInfo
	if windows.GetConsoleScreenBufferInfo(output, &info) != nil {
		return false
	}
	columns = max(columns, int(info.Window.Right)-int(info.Window.Left)+1)
	rows = max(rows, int(info.Window.Bottom)-int(info.Window.Top)+1)
	neededColumns, neededRows = max(columns, neededColumns), max(rows, neededRows)
	if columns == neededColumns && rows == neededRows {
		return false
	}

	hwnd, _, _ := consoleWindowHandle.Call()
	if host := windowsTerminalOwner(hwnd); host != 0 {
		return resizeTerminalOwner(host, columns, rows, neededColumns, neededRows)
	}
	if hwnd != 0 && os.Getenv("WT_SESSION") == "" && windows.IsWindowVisible(windows.HWND(hwnd)) {
		return resizeClassicConsole(output, info, columns, rows, neededColumns, neededRows)
	}
	// A pseudoconsole's HWND is normally hidden. Ask its renderer through the
	// console's own output, which keeps the request scoped to this terminal.
	return requestTerminalResize(output, neededColumns, neededRows)
}

func resizeClassicConsole(output windows.Handle, info windows.ConsoleScreenBufferInfo, columns, rows, neededColumns, neededRows int) bool {
	if largest, _, _ := consoleMaxWindow.Call(uintptr(output)); largest != 0 {
		neededColumns = max(columns, min(neededColumns, int(uint16(largest))))
		neededRows = max(rows, min(neededRows, int(uint16(largest>>16))))
	}
	if columns == neededColumns && rows == neededRows {
		return false
	}
	width, height := int16(neededColumns), int16(neededRows)
	buffer := consoleCoord{X: max(info.Size.X, width), Y: max(info.Size.Y, height)}
	if info.Size.X != buffer.X || info.Size.Y != buffer.Y {
		if ok, _, _ := consoleSetBuffer.Call(uintptr(output), consolePack(buffer)); ok == 0 {
			return false
		}
	}
	left := min(info.Window.Left, buffer.X-width)
	top := min(info.Window.Top, buffer.Y-height)
	window := windows.SmallRect{Left: left, Top: top, Right: left + width - 1, Bottom: top + height - 1}
	ok, _, _ := consoleSetWindow.Call(uintptr(output), 1, uintptr(unsafe.Pointer(&window)))
	return ok != 0
}

func requestTerminalResize(output windows.Handle, columns, rows int) bool {
	var mode uint32
	if windows.GetConsoleMode(output, &mode) != nil || mode&windows.ENABLE_VIRTUAL_TERMINAL_PROCESSING == 0 {
		return false
	}
	sequence := windows.StringToUTF16(fmt.Sprintf("\x1b[8;%d;%dt", rows, columns))
	return windows.WriteConsole(output, &sequence[0], uint32(len(sequence)-1), nil, nil) == nil
}

func windowsTerminalOwner(hwnd uintptr) uintptr {
	if hwnd == 0 {
		return 0
	}
	// Windows Terminal marks its window as the owner of this ConPTY's HWND.
	const rootOwner = 3
	host, _, _ := consoleAncestor.Call(hwnd, rootOwner)
	if host == 0 || host == hwnd || !windows.IsWindowVisible(windows.HWND(host)) {
		return 0
	}
	var pid uint32
	consoleWindowPID.Call(host, uintptr(unsafe.Pointer(&pid)))
	if !platformIsProcess(int(pid), "WindowsTerminal.exe") {
		return 0
	}
	return host
}

type consoleMonitorBounds struct {
	size          uint32
	monitor, work windows.Rect
	flags         uint32
}

func resizeTerminalOwner(hwnd uintptr, columns, rows, neededColumns, neededRows int) bool {
	zoomed, _, _ := consoleWindowZoomed.Call(hwnd)
	iconic, _, _ := consoleWindowIconic.Call(hwnd)
	if zoomed != 0 || iconic != 0 {
		return false
	}
	var window windows.Rect
	if ok, _, _ := consoleWindowRect.Call(hwnd, uintptr(unsafe.Pointer(&window))); ok == 0 {
		return false
	}
	const monitorDefaultToNearest = 2
	monitor, _, _ := consoleMonitor.Call(hwnd, monitorDefaultToNearest)
	info := consoleMonitorBounds{size: uint32(unsafe.Sizeof(consoleMonitorBounds{}))}
	if ok, _, _ := consoleMonitorInfo.Call(monitor, uintptr(unsafe.Pointer(&info))); ok == 0 {
		return false
	}
	target := growConsoleWindow(window, info.work, columns, rows, neededColumns, neededRows)
	if target == window {
		return false
	}
	const noActivateOrZOrder = 0x0010 | 0x0004
	const asyncWindowPos = 0x4000
	ok, _, _ := consoleSetPosition.Call(hwnd, 0, uintptr(target.Left), uintptr(target.Top),
		uintptr(target.Right-target.Left), uintptr(target.Bottom-target.Top), noActivateOrZOrder|asyncWindowPos)
	return ok != 0
}

func growConsoleWindow(window, work windows.Rect, columns, rows, neededColumns, neededRows int) windows.Rect {
	width, height := int(window.Right-window.Left), int(window.Bottom-window.Top)
	if width < 1 || height < 1 {
		return window
	}
	// Estimate cell size from the window's current dimensions. The frame makes
	// this a slight overestimate and the monitor work area caps the request.
	cellWidth, cellHeight := (width+columns-1)/columns, (height+rows-1)/rows
	newWidth := max(width, min(int(work.Right-work.Left), width+(neededColumns-columns)*cellWidth))
	newHeight := max(height, min(int(work.Bottom-work.Top), height+(neededRows-rows)*cellHeight))
	if newWidth == width && newHeight == height {
		return window
	}
	left := max(work.Left, min(window.Left, work.Right-int32(newWidth)))
	top := max(work.Top, min(window.Top, work.Bottom-int32(newHeight)))
	return windows.Rect{Left: left, Top: top, Right: left + int32(newWidth), Bottom: top + int32(newHeight)}
}
