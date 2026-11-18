//go:build windows

package host

import (
	"runtime"
	"strings"
	"testing"
	"unsafe"
)

// Exercise the same movable global blocks as CF_UNICODETEXT without opening
// or modifying the real desktop clipboard.
func TestDesktopClipboardMemoryRoundTrip(t *testing.T) {
	for _, s := range []string{"", "語𠮷😀\r\nline\n", strings.Repeat("\n", desktopClipboardLimit)} {
		block, err := makeClipboardBlock(s)
		if err != nil {
			t.Fatal(err)
		}
		text, tooLarge, err := readClipboardBlock(block)
		clipFree.Call(block)
		if err != nil || tooLarge || text == nil || *text != strings.ReplaceAll(s, "\r\n", "\n") {
			t.Fatalf("clipboard round-trip failed for %d bytes, exceeded=%v err=%v", len(s), tooLarge, err)
		}
	}
	for _, s := range []string{"NUL\x00text", "\xff", strings.Repeat("x", desktopClipboardLimit+1)} {
		if block, err := makeClipboardBlock(s); err == nil {
			clipFree.Call(block)
			t.Fatal("invalid clipboard text produced a Windows block")
		}
	}
}

func TestDesktopClipboardLimitsNormalizedUTF8(t *testing.T) {
	// These UTF-16 units fit in the scan limit but exceed the UTF-8 wire limit.
	units := make([]uint16, desktopClipboardLimit/3+2)
	for i := range units[:len(units)-1] {
		units[i] = '語'
	}
	size := uintptr(len(units) * 2)
	block, _, _ := clipAlloc.Call(2, size)
	if block == 0 {
		t.Fatal("could not allocate private fixture")
	}
	defer clipFree.Call(block)
	addr, _, _ := clipLock.Call(block)
	if addr == 0 {
		t.Fatal("could not lock private fixture")
	}
	clipCopy.Call(addr, uintptr(unsafe.Pointer(&units[0])), size)
	clipUnlock.Call(block)
	text, tooLarge, err := readClipboardBlock(block)
	if err != nil || !tooLarge || text != nil {
		t.Fatalf("oversize text was not omitted: tooLarge=%v err=%v", tooLarge, err)
	}
}

func TestDesktopClipboardMessageOnlyOwner(t *testing.T) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	owner, err := newClipboardWindow()
	if err != nil {
		t.Fatal(err)
	}
	defer clipDestroyWindow.Call(owner)
	if ok, _, _ := desktopUser32.NewProc("IsWindow").Call(owner); ok == 0 {
		t.Fatal("invalid message-only window")
	}
	if visible, _, _ := desktopUser32.NewProc("IsWindowVisible").Call(owner); visible != 0 {
		t.Fatal("clipboard owner is visible")
	}
}
