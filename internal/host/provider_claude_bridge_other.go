//go:build !windows

package host

import (
	"os"
	"time"
)

func claudeTranscriptCreation(os.FileInfo) time.Time { return time.Time{} }
