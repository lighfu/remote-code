//go:build !windows && !linux

package host

import "os/exec"

func fileRenameExclusive(from, to string) error {
	return fileProblem(501, "Safe renaming is not available on this PC")
}
func fileIdentity(path string) (string, error) { return "", nil }
func fileHideCommand(cmd *exec.Cmd)            {}
