//go:build !windows

package host

import (
	"context"
	"net"
	"os"
	"path/filepath"
)

func desktopDial(ctx context.Context) (net.Conn, error) {
	home := os.Getenv("CODEX_HOME")
	if home == "" {
		user, _ := os.UserHomeDir()
		home = filepath.Join(user, ".codex")
	}
	return (&net.Dialer{}).DialContext(ctx, "unix", filepath.Join(home, "ipc", "ipc.sock"))
}
func desktopEndpointMissing(err error) bool { return os.IsNotExist(err) }
