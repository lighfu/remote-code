package host

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"time"

	"golang.org/x/term"
)

var errConsoleRestart = errors.New("console restart requested")

func (a *App) beginRequest() bool {
	a.requestMu.Lock()
	defer a.requestMu.Unlock()
	if a.draining {
		return false
	}
	a.requests.Add(1)
	return true
}
func (a *App) beginDrain() { a.requestMu.Lock(); a.draining = true; a.requestMu.Unlock() }
func (a *App) startTask(f func()) bool {
	a.requestMu.Lock()
	if a.draining {
		a.requestMu.Unlock()
		return false
	}
	a.tasks.Add(1)
	a.requestMu.Unlock()
	go func() { defer a.tasks.Done(); f() }()
	return true
}
func runServer(data string, args []string) error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	for generation := 0; ; generation++ {
		restart, err := runServerGeneration(ctx, data, args, generation)
		if err != nil || !restart || ctx.Err() != nil {
			return err
		}
		args = restartServerArgs(args)
		if platformFlag(args, "--serve") {
			if os.Getenv(portableRestartEnv) == "1" {
				return ErrPortableRestart
			}
			if exe, err := os.Executable(); err == nil {
				if root := legacyPortableRoot(exe, os.Getenv("REMOTE_CODE_INSTANCE")); root != "" {
					return runPortable(root, data, args)
				}
			}
		}
	}
}

// A restart keeps the tunnel chosen at startup.
func restartServerArgs(args []string) []string {
	next := make([]string, 0, len(args))
	for _, arg := range args {
		if arg != "--new-url" {
			next = append(next, arg)
		}
	}
	return next
}

func runServerGeneration(parent context.Context, data string, args []string, generation int) (restart bool, result error) {
	a, err := NewApp(data)
	if err != nil {
		return false, err
	}
	if generation > 0 {
		a.InstanceID = NewID()
	}
	ctx, cancel := context.WithCancelCause(parent)
	defer cancel(nil)
	var leases []net.Listener
	defer func() {
		result = errors.Join(result, a.Close())
		for _, lease := range leases {
			result = errors.Join(result, lease.Close())
		}
	}()
	if ctx.Err() != nil {
		return false, nil
	}
	leases, err = platformAcquire(data, a.Config.Port, func() { cancel(nil) })
	if err != nil {
		return false, err
	}
	if leases == nil {
		return false, nil
	}
	if ctx.Err() != nil {
		return false, nil
	}
	if err = SaveConfig(data, a.Config); err != nil {
		return false, err
	}
	_ = atomicJSON(filepath.Join(data, "runtime.json"), M{"publicUrl": a.Config.PublicURL, "updatedAt": time.Now().UTC().Format(time.RFC3339)})
	server := &http.Server{Addr: fmt.Sprintf(":%d", a.Config.Port), Handler: a.handler(ctx.Done()), ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 90 * time.Second, MaxHeaderBytes: 32 << 10}
	server.ErrorLog = log.New(consoleLogWriter{}, "[http] ", 0)
	listener, err := net.Listen("tcp", server.Addr)
	if err != nil {
		return false, fmt.Errorf("port %d unavailable: %w", a.Config.Port, err)
	}
	defer listener.Close()
	platformShutdown.Store(a, context.CancelFunc(func() { cancel(nil) }))
	defer platformShutdown.Delete(a)
	var console *consoleRuntime
	uiDone := make(chan struct{})
	interactive := !platformFlag(args, "--no-ui") && os.Getenv("TERM") != "dumb" && term.IsTerminal(int(os.Stdin.Fd())) && term.IsTerminal(int(os.Stdout.Fd()))
	if interactive {
		console = attachConsole(a)
		go func() {
			defer close(uiDone)
			err := RunConsole(ctx, console.snapshot, func(actionCtx context.Context, action ConsoleAction) error {
				if target := consoleInstallTarget(action); target != "" {
					installCtx, done := context.WithTimeout(actionCtx, 20*time.Minute)
					defer done()
					return console.installDependency(installCtx, target)
				}
				actionCtx, done := context.WithTimeout(actionCtx, 10*time.Second)
				defer done()
				switch action {
				case ConsoleRefresh:
					console.refresh()
					consoleLogf("[Console] Status refreshed.")
					return nil
				case ConsoleOpen:
					return consoleOpen(actionCtx, console.snapshot().URL)
				case ConsoleCopy, ConsoleCopyAPK:
					state := console.snapshot()
					link := state.PairLink
					if action == ConsoleCopyAPK {
						link = state.APKURL
						if link == "" {
							return errors.New("Android APK is not available yet")
						}
					}
					if err := consoleCopy(actionCtx, link); err != nil {
						return err
					}
					consoleLogf("[Console] %s", consoleActionDone(action))
					return nil
				case ConsoleRestart:
					cancel(errConsoleRestart)
					return nil
				case ConsoleStop:
					cancel(nil)
					return nil
				}
				return fmt.Errorf("Unknown console action")
			})
			if ctx.Err() == nil {
				console.detach()
				if err != nil {
					consoleLogf("[Console] Interactive display unavailable: %v", err)
				}
				platformDisplay(a, args, a.Config.PublicURL)
			}
		}()
	} else {
		close(uiDone)
		platformDisplay(a, args, a.Config.PublicURL)
	}
	if console != nil {
		defer console.detach()
	}
	shutdownDone := make(chan struct{})
	go func() {
		defer close(shutdownDone)
		<-ctx.Done()
		a.beginDrain()
		stop, done := context.WithTimeout(context.Background(), 10*time.Second)
		defer done()
		if err := server.Shutdown(stop); err != nil {
			_ = server.Close()
		}
		a.requests.Wait()
	}()
	a.StartBackground(ctx)
	if a.Config.USBReverse {
		a.startTask(func() { platformUSB(ctx, a) })
	}
	a.startConfiguredTunnel(ctx, args)
	a.startTask(func() { platformAppUpdates(ctx, a) })
	err = server.Serve(listener)
	if err != nil && !errors.Is(err, http.ErrServerClosed) {
		result = err
	}
	cancel(context.Cause(ctx))
	<-shutdownDone
	<-uiDone
	restart = errors.Is(context.Cause(ctx), errConsoleRestart)
	return restart, result
}
