package host

import (
	"context"
	"os"
	"path/filepath"
	"slices"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestCloudflaredDiscoveryFindsNewUserAndMachineInstallsWithoutRestart(t *testing.T) {
	for _, location := range []string{"fallback", "user-winget", "machine-winget", "machine-x86-winget"} {
		t.Run(location, func(t *testing.T) {
			root := t.TempDir()
			local, program, programX86 := filepath.Join(root, "local"), filepath.Join(root, "program"), filepath.Join(root, "program-x86")
			t.Setenv("CLOUDFLARED_BIN", "")
			t.Setenv("PATH", root)
			t.Setenv("LOCALAPPDATA", local)
			t.Setenv("ProgramFiles", program)
			t.Setenv("ProgramFiles(x86)", programX86)
			paths := map[string]string{
				"fallback":           filepath.Join(local, "RemoteCode", "dependencies", "cloudflared", "bin", "cloudflared.exe"),
				"user-winget":        filepath.Join(local, "Microsoft", "WinGet", "Links", "cloudflared.exe"),
				"machine-winget":     filepath.Join(program, "WinGet", "Links", "cloudflared.exe"),
				"machine-x86-winget": filepath.Join(programX86, "WinGet", "Links", "cloudflared.exe"),
			}
			path := paths[location]
			if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(path, []byte("discovery fixture; never executed"), 0600); err != nil {
				t.Fatal(err)
			}
			if got := cloudflaredPath(); got != path {
				t.Fatalf("new installation not discovered: got %q, want %q", got, path)
			}
			t.Setenv("CLOUDFLARED_BIN", filepath.Join(root, "explicit-cloudflared.exe"))
			if got := cloudflaredPath(); got != os.Getenv("CLOUDFLARED_BIN") {
				t.Fatalf("explicit executable override lost: %q", got)
			}
		})
	}
}

func TestConfiguredTunnelInstallationStartsOnlyOneGenerationWorker(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	var state configuredTunnelState
	args := []string{"--tunnel", "--no-qr"}
	state.configure(ctx, args, quickTunnelRequested(Config{}, args))
	args[0] = "changed after configuration"
	var installed atomic.Bool
	var launches atomic.Int32
	started := make(chan []string, 1)
	finished := make(chan struct{}, 1)
	start := func(task func()) bool {
		launches.Add(1)
		go func() { task(); finished <- struct{}{} }()
		return true
	}
	worker := func(workerCtx context.Context, workerArgs []string) {
		started <- workerArgs
		<-workerCtx.Done()
	}
	if state.retry(installed.Load, start, worker) {
		t.Fatal("missing cloudflared started a worker")
	}
	installed.Store(true)
	var callers sync.WaitGroup
	for range 32 {
		callers.Go(func() { state.retry(installed.Load, start, worker) })
	}
	callers.Wait()
	select {
	case got := <-started:
		if !slices.Equal(got, []string{"--tunnel", "--no-qr"}) {
			t.Fatalf("generation launch options changed: %v", got)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("installation did not start the requested tunnel")
	}
	if got := launches.Load(); got != 1 {
		t.Fatalf("concurrent installation retries launched %d workers", got)
	}
	cancel()
	select {
	case <-finished:
	case <-time.After(3 * time.Second):
		t.Fatal("tunnel worker outlived its server generation")
	}
	if state.retry(installed.Load, start, worker) {
		t.Fatal("installation restarted a stopped server generation")
	}
}

func TestConfiguredTunnelHonorsOriginalStartupOptions(t *testing.T) {
	for _, tc := range []struct {
		name   string
		config Config
		args   []string
		want   bool
	}{
		{"local", Config{}, nil, false},
		{"public-url", Config{PublicURL: "https://configured.example"}, nil, false},
		{"configured", Config{QuickTunnel: true}, nil, true},
		{"explicit", Config{}, []string{"--tunnel"}, true},
		{"explicit-with-public-url", Config{PublicURL: "https://configured.example"}, []string{"--tunnel"}, true},
		{"disabled-configured", Config{QuickTunnel: true}, []string{"--no-tunnel"}, false},
		{"disabled-explicit", Config{}, []string{"--tunnel", "--no-tunnel"}, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var state configuredTunnelState
			state.configure(t.Context(), tc.args, quickTunnelRequested(tc.config, tc.args))
			// A later install must not replace the generation's explicit options.
			state.configure(t.Context(), []string{"--tunnel"}, true)
			var queued func()
			started := state.retry(func() bool { return true }, func(task func()) bool { queued = task; return true }, func(context.Context, []string) {})
			if started != tc.want {
				t.Fatalf("tunnel started = %v, want %v", started, tc.want)
			}
			if queued != nil {
				queued()
			}
		})
	}
}

func TestConfiguredTunnelCanRetryExitedWorkerButNotDrain(t *testing.T) {
	a := testApp(t)
	var state configuredTunnelState
	state.configure(t.Context(), nil, true)
	finished := make(chan struct{}, 1)
	start := func(task func()) bool { go func() { task(); finished <- struct{}{} }(); return true }
	for range 2 {
		if !state.retry(func() bool { return true }, start, func(context.Context, []string) {}) {
			t.Fatal("an exited worker prevented a later retry")
		}
		select {
		case <-finished:
		case <-time.After(3 * time.Second):
			t.Fatal("worker did not finish")
		}
	}
	a.beginDrain()
	if state.retry(func() bool { return true }, a.startTask, func(context.Context, []string) { t.Error("worker started while draining") }) {
		t.Fatal("draining server accepted an installation retry")
	}
}
