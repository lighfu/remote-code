//go:build !windows

package host

func desktopClipboardRead(after *uint32) (desktopClip, error) {
	return desktopClip{}, errDesktopUnsupported
}

func desktopClipboardWrite(expected uint32, text string) (desktopClip, error) {
	return desktopClip{}, errDesktopUnsupported
}
