//go:build linux || darwin

package host

import (
	"errors"

	"golang.org/x/sys/unix"
)

func uploadFreeBytes(path string) (uint64, bool) {
	var info unix.Statfs_t
	if unix.Statfs(path, &info) != nil {
		return 0, false
	}
	return uint64(info.Bavail) * uint64(info.Bsize), true
}

func uploadPlatformDiskFull(err error) bool { return errors.Is(err, unix.EDQUOT) }
