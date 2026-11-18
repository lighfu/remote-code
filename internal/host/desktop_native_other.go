//go:build !windows

package host

import (
	"context"
	"errors"
)

var errDesktopUnsupported = errors.New("desktop control requires Windows")

func desktopSupported() bool                          { return false }
func listDesktopDisplays() ([]desktopDisplay, error)  { return nil, errDesktopUnsupported }
func desktopCaptureMethods([]desktopDisplay) []string { return nil }
func captureDesktopFrame(string, desktopFrameOptions) (desktopFrame, error) {
	return desktopFrame{}, errDesktopUnsupported
}
func applyDesktopInput(input desktopInput) error { return errDesktopUnsupported }
func captureDesktopAudio(context.Context, <-chan struct{}, func([]byte) error) error {
	return errDesktopUnsupported
}
