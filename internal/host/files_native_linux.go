//go:build linux

package host

import (
	"fmt"
	"golang.org/x/sys/unix"
	"os/exec"
)

func fileIdentity(path string) (string, error) {
	var st unix.Stat_t
	if err := unix.Lstat(path, &st); err != nil {
		return "", err
	}
	return fmt.Sprintf("%d:%d:%d:%d", st.Dev, st.Ino, st.Ctim.Sec, st.Ctim.Nsec), nil
}

func fileRenameExclusive(from, to string) error {
	return unix.Renameat2(unix.AT_FDCWD, from, unix.AT_FDCWD, to, unix.RENAME_NOREPLACE)
}
func fileHideCommand(cmd *exec.Cmd) {}
