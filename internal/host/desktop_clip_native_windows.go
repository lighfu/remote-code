//go:build windows

package host

import (
	"errors"
	"net/http"
	"slices"
	"strings"
	"sync"
	"time"
	"unicode/utf16"
	"unicode/utf8"
	"unsafe"

	"golang.org/x/sys/windows"
)

const cfUnicodeText = 13

var (
	clipKernel         = windows.NewLazySystemDLL("kernel32.dll")
	clipAlloc          = clipKernel.NewProc("GlobalAlloc")
	clipFree           = clipKernel.NewProc("GlobalFree")
	clipLock           = clipKernel.NewProc("GlobalLock")
	clipUnlock         = clipKernel.NewProc("GlobalUnlock")
	clipSize           = clipKernel.NewProc("GlobalSize")
	clipCopy           = clipKernel.NewProc("RtlMoveMemory")
	clipModule         = clipKernel.NewProc("GetModuleHandleW")
	clipWindow         = desktopUser32.NewProc("CreateWindowExW")
	clipDestroyWindow  = desktopUser32.NewProc("DestroyWindow")
	clipOpen           = desktopUser32.NewProc("OpenClipboard")
	clipClose          = desktopUser32.NewProc("CloseClipboard")
	clipEmpty          = desktopUser32.NewProc("EmptyClipboard")
	clipGet            = desktopUser32.NewProc("GetClipboardData")
	clipSet            = desktopUser32.NewProc("SetClipboardData")
	clipSequence       = desktopUser32.NewProc("GetClipboardSequenceNumber")
	clipOwner          = desktopUser32.NewProc("GetClipboardOwner")
	clipHasFormat      = desktopUser32.NewProc("IsClipboardFormatAvailable")
	clipRegister       = desktopUser32.NewProc("RegisterClipboardFormatW")
	clipPrivacyFormats = sync.OnceValues(func() (clipPrivacyIDs, error) {
		register := func(format string) uintptr {
			name, _ := windows.UTF16PtrFromString(format)
			value, _, _ := clipRegister.Call(uintptr(unsafe.Pointer(name)))
			return value
		}
		ids := clipPrivacyIDs{
			exclude: register("ExcludeClipboardContentFromMonitorProcessing"),
			upload:  register("CanUploadToCloudClipboard"),
		}
		if ids.exclude == 0 || ids.upload == 0 {
			return ids, errors.New("Windows clipboard privacy formats are unavailable")
		}
		return ids, nil
	})
)

type clipPrivacyIDs struct{ exclude, upload uintptr }

// These are the documented Windows flags for cross-device clipboard sync.
// If a source sets a policy that cannot be read, do not request its text.
func (ids clipPrivacyIDs) allowsTransfer() bool {
	if present, _, _ := clipHasFormat.Call(ids.exclude); present != 0 {
		return false
	}
	if present, _, _ := clipHasFormat.Call(ids.upload); present == 0 {
		return true
	}
	block, _, _ := clipGet.Call(ids.upload)
	if size, _, _ := clipSize.Call(block); size < 4 {
		return false
	}
	address, _, _ := clipLock.Call(block)
	if address == 0 {
		return false
	}
	defer clipUnlock.Call(block)
	var allow uint32
	clipCopy.Call(uintptr(unsafe.Pointer(&allow)), address, 4)
	return allow == 1
}

func clipboardSequence() uint32 {
	value, _, _ := clipSequence.Call()
	return uint32(value)
}

func openDesktopClipboard(owner uintptr) error {
	// Clipboard managers can briefly hold the global clipboard lock.
	for attempt := 0; attempt < 4; attempt++ {
		if attempt != 0 {
			time.Sleep(10 * time.Millisecond)
		}
		if ok, _, _ := clipOpen.Call(owner); ok != 0 {
			return nil
		}
	}
	return errors.New("Windows clipboard is busy")
}

// Global blocks may include allocator padding. Only bytes before the first
// UTF-16 terminator are text. Each normalized LF can occupy two Windows units.
func readClipboardBlock(block uintptr) (*string, bool, error) {
	size, _, _ := clipSize.Call(block)
	if size < 2 {
		return nil, false, errors.New("Invalid Windows clipboard text")
	}
	address, _, _ := clipLock.Call(block)
	if address == 0 {
		return nil, false, errors.New("Windows clipboard text could not be locked")
	}
	defer clipUnlock.Call(block)
	units := make([]uint16, min(size/2, 2*desktopClipboardLimit+1))
	clipCopy.Call(uintptr(unsafe.Pointer(&units[0])), address, uintptr(len(units)*2))
	end := slices.Index(units, uint16(0))
	if end < 0 {
		if len(units) > 2*desktopClipboardLimit {
			return nil, true, nil
		}
		return nil, false, errors.New("Unterminated Windows clipboard text")
	}
	text := strings.ReplaceAll(string(utf16.Decode(units[:end])), "\r\n", "\n")
	if len(text) > desktopClipboardLimit {
		return nil, true, nil
	}
	return &text, false, nil
}

func desktopClipboardRead(after *uint32) (desktopClip, error) {
	leave, err := enterInteractiveDesktop()
	if err != nil {
		return desktopClip{}, err
	}
	defer leave()
	result := desktopClip{Sequence: clipboardSequence()}
	if after == nil || *after == result.Sequence {
		return result, nil
	}
	privacy, err := clipPrivacyFormats()
	if err != nil {
		return desktopClip{}, err
	}
	if err = openDesktopClipboard(0); err != nil {
		return desktopClip{}, err
	}
	defer clipClose.Call()
	result.Sequence = clipboardSequence()
	if *after == result.Sequence {
		return result, nil
	}
	if !privacy.allowsTransfer() {
		result.Sequence = clipboardSequence()
		return result, nil
	}
	if available, _, _ := clipHasFormat.Call(cfUnicodeText); available == 0 {
		return result, nil
	}
	block, _, _ := clipGet.Call(cfUnicodeText)
	if block == 0 {
		return desktopClip{}, errors.New("Windows clipboard text is unavailable")
	}
	result.Text, result.TooLarge, err = readClipboardBlock(block)
	// GetClipboardData can cause a delayed-rendering owner to advance the revision.
	result.Sequence = clipboardSequence()
	return result, err
}

func makeClipboardBlock(text string) (uintptr, error) {
	if len(text) > desktopClipboardLimit || !utf8.ValidString(text) {
		return 0, errors.New("Invalid Windows clipboard text")
	}
	lines := strings.ReplaceAll(strings.ReplaceAll(text, "\r\n", "\n"), "\n", "\r\n")
	units, err := windows.UTF16FromString(lines)
	if err != nil { // An embedded NUL cannot round-trip through CF_UNICODETEXT.
		return 0, errors.New("Invalid Windows clipboard text")
	}
	size := uintptr(len(units) * 2)
	block, _, _ := clipAlloc.Call(2 /* GMEM_MOVEABLE */, size)
	if block == 0 {
		return 0, errors.New("Windows could not allocate clipboard memory")
	}
	address, _, _ := clipLock.Call(block)
	if address == 0 {
		clipFree.Call(block)
		return 0, errors.New("Windows could not lock clipboard memory")
	}
	clipCopy.Call(address, uintptr(unsafe.Pointer(&units[0])), size)
	clipUnlock.Call(block)
	return block, nil
}

func newClipboardWindow() (uintptr, error) {
	// EmptyClipboard transfers ownership to the HWND passed to OpenClipboard.
	// STATIC is a built-in class; HWND_MESSAGE creates a non-visual owner.
	class, _ := windows.UTF16PtrFromString("STATIC")
	module, _, _ := clipModule.Call(0)
	window, _, _ := clipWindow.Call(0, uintptr(unsafe.Pointer(class)), 0, 0,
		0, 0, 0, 0, ^uintptr(2), 0, module, 0)
	if window == 0 {
		return 0, errors.New("Could not create Windows clipboard owner")
	}
	return window, nil
}

func desktopClipboardWrite(expected uint32, text string) (desktopClip, error) {
	leave, err := enterInteractiveDesktop()
	if err != nil {
		return desktopClip{}, err
	}
	defer leave()
	block, err := makeClipboardBlock(text)
	if err != nil {
		return desktopClip{}, err
	}
	defer func() {
		if block != 0 {
			clipFree.Call(block)
		}
	}()
	window, err := newClipboardWindow()
	if err != nil {
		return desktopClip{}, err
	}
	defer clipDestroyWindow.Call(window)
	if err = openDesktopClipboard(window); err != nil {
		return desktopClip{}, err
	}
	open := true
	defer func() {
		if open {
			clipClose.Call()
		}
	}()
	if clipboardSequence() != expected {
		return desktopClip{}, &HTTPError{http.StatusConflict, "Windows clipboard changed"}
	}
	if ok, _, _ := clipEmpty.Call(); ok == 0 {
		return desktopClip{}, errors.New("Could not open Windows clipboard for writing")
	}
	if ok, _, _ := clipSet.Call(cfUnicodeText, block); ok == 0 {
		return desktopClip{}, errors.New("Could not write Windows clipboard text")
	}
	block = 0 // Windows now owns the movable memory.
	// Windows synthesizes formats on close and advances the sequence again.
	// Reacquire the lock and verify ownership before acknowledging that revision.
	if ok, _, _ := clipClose.Call(); ok == 0 {
		return desktopClip{}, errors.New("Could not finish updating the Windows clipboard")
	}
	open = false
	if err = openDesktopClipboard(window); err != nil {
		return desktopClip{}, &HTTPError{http.StatusConflict, "Windows clipboard changed"}
	}
	open = true
	if owner, _, _ := clipOwner.Call(); owner != window {
		return desktopClip{}, &HTTPError{http.StatusConflict, "Windows clipboard changed"}
	}
	return desktopClip{Sequence: clipboardSequence()}, nil
}
