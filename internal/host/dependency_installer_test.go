package host

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestDependencyInstallerSerializesAndDetectsWithoutRestart(t *testing.T) {
	var installer dependencyInstaller
	started, release, result := make(chan struct{}), make(chan struct{}), make(chan error, 1)
	resolve := func(target string) (string, error) { return "/installed/" + target + ".exe", nil }
	run := func(ctx context.Context, target string, progress func(string)) error {
		progress("Using WinGet...")
		close(started)
		<-release
		return nil
	}
	go func() { result <- installer.install(context.Background(), "codex", run, resolve) }()
	<-started
	state := installer.snapshot(resolve)[1]
	if state.Status != "Installing..." || !strings.Contains(state.Detail, "WinGet") {
		t.Fatal(state)
	}
	if err := installer.install(context.Background(), "claude", run, resolve); err == nil {
		t.Fatal("Concurrent installer was started")
	}
	close(release)
	if err := <-result; err != nil {
		t.Fatal(err)
	}
	state = installer.snapshot(resolve)[1]
	if state.Status != "Installed" || !state.Installed || state.Path != "/installed/codex.exe" {
		t.Fatal(state)
	}
}

func TestDependencyInstallerFailureRetryAndVerification(t *testing.T) {
	var installer dependencyInstaller
	missing := func(string) (string, error) { return "", os.ErrNotExist }
	run := func(ctx context.Context, target string, progress func(string)) error {
		progress("Download failed: test server unreachable")
		return errors.New("exit status 1")
	}
	if installer.install(context.Background(), "claude", run, missing) == nil {
		t.Fatal("Failure was accepted")
	}
	state := installer.snapshot(missing)[0]
	if state.Status != "Failed" || state.Installed || !strings.Contains(state.Detail, "unreachable") {
		t.Fatal(state)
	}
	success := func(context.Context, string, func(string)) error { return nil }
	if installer.install(context.Background(), "claude", success, missing) == nil {
		t.Fatal("Exit 0 with missing executable was accepted")
	}
	found := func(string) (string, error) { return "/installed/claude.exe", nil }
	if err := installer.install(context.Background(), "claude", success, found); err != nil {
		t.Fatal("Retry failed", err)
	}
	if state := installer.snapshot(found)[0]; !state.Installed || state.Status != "Installed" {
		t.Fatal(state)
	}
	called := false
	runInvalid := func(context.Context, string, func(string)) error { called = true; return nil }
	if installer.install(context.Background(), "bad; command", runInvalid, found) == nil || called {
		t.Fatal("Invalid dependency reached the process runner")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if installer.install(ctx, "codex", runInvalid, found) == nil || called {
		t.Fatal("Canceled request reached process runner")
	}
}

func TestDependencyProgressWriterPreservesSplitLines(t *testing.T) {
	var lines []string
	w := dependencyProgressWriter{progress: func(s string) {
		if s != "" {
			lines = append(lines, s)
		}
	}}
	_, _ = w.Write([]byte("Using Win"))
	_, _ = w.Write([]byte("Get\rDownloading\n"))
	if strings.Join(lines, "/") != "Using WinGet/Downloading" || w.buffer != "" {
		t.Fatal(lines, w.buffer)
	}
}

func TestProviderDiscoveryFindsInstallationsOutsideInheritedPath(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("Windows installation locations")
	}
	root := t.TempDir()
	for _, key := range []string{"USERPROFILE", "LOCALAPPDATA", "APPDATA", "ProgramFiles", "PATH"} {
		t.Setenv(key, root)
	}
	for _, key := range []string{"CLAUDE_BIN", "CODEX_BIN", "AGY_BIN"} {
		t.Setenv(key, "")
	}
	for _, target := range []string{"claude", "codex", "antigravity"} {
		leaf := target + ".exe"
		if target == "antigravity" {
			leaf = "agy.exe"
		}
		for _, dir := range []string{filepath.Join(root, "Microsoft", "WinGet", "Links"), filepath.Join(root, "RemoteCode", "dependencies", target, "bin")} {
			path := filepath.Join(dir, leaf)
			if err := os.MkdirAll(dir, 0700); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(path, nil, 0600); err != nil {
				t.Fatal(err)
			}
			found, err := FindProviderExecutable(target)
			if err != nil || !strings.EqualFold(found, path) {
				t.Fatal(target, found, err)
			}
			_ = os.Remove(path)
		}
	}
}
