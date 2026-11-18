//go:build windows

package host

import (
	"os"
	"syscall"
	"time"
)

func claudeTranscriptCreation(info os.FileInfo) time.Time {
	if stat, ok := info.Sys().(*syscall.Win32FileAttributeData); ok && stat.CreationTime.Nanoseconds() > 0 {
		return time.Unix(0, stat.CreationTime.Nanoseconds())
	}
	return time.Time{}
}
