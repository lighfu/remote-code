//go:build !windows

package host

import (
	"context"
	"errors"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

func platformPipeName(scope, digest string) string {
	return filepath.Join(os.TempDir(), "remote-code-"+scope+"-"+digest+".sock")
}
func platformListenPipe(path string) (net.Listener, error) { return net.Listen("unix", path) }
func platformDialPipe(path string) (net.Conn, error)       { return net.Dial("unix", path) }
func platformCommand(ctx context.Context, path string, args ...string) *exec.Cmd {
	return exec.CommandContext(ctx, path, args...)
}
func HostCommand(ctx context.Context, path string, args ...string) *exec.Cmd {
	return platformCommand(ctx, path, args...)
}
func platformDetached(path string, args ...string) *exec.Cmd { return exec.Command(path, args...) }
func platformKillProcess(pid int) error {
	p, e := os.FindProcess(pid)
	if e != nil {
		return e
	}
	return p.Kill()
}
func platformIsProcess(pid int, name string) bool {
	if pid <= 0 {
		return false
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, "ps", "-p", strconv.Itoa(pid), "-o", "comm=").Output()
	return err == nil && filepath.Base(strings.TrimSpace(string(out))) == name
}
func platformPTY(shell, cwd string, cols, rows int) (terminalPTY, error) {
	return nil, errors.New("interactive terminals require Windows ConPTY")
}

type platformProcess struct {
	PID, Parent   uint32
	Name, Command string
}

func platformProcesses() ([]platformProcess, error) { return nil, nil }
func runConsoleBridge(args []string) error          { return errors.New("console bridge requires Windows") }
