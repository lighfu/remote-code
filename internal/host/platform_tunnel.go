package host

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"time"
)

var quickTunnelURL = regexp.MustCompile(`https://[a-z0-9-]+\.trycloudflare\.com\b`)

type quickTunnelState struct {
	PID       int       `json:"pid"`
	Port      int       `json:"port"`
	URL       string    `json:"url"`
	StartedAt time.Time `json:"startedAt"`
}

func readQuickTunnel(path string) (quickTunnelState, error) {
	var state quickTunnelState
	b, err := os.ReadFile(path)
	if err != nil {
		return state, err
	}
	if json.Unmarshal(b, &state) != nil || state.PID <= 0 || state.Port <= 0 || quickTunnelURL.FindString(state.URL) != state.URL || state.URL == "" {
		return quickTunnelState{}, errors.New("invalid tunnel state")
	}
	return state, nil
}

func cloudflaredPath() string {
	if bin := os.Getenv("CLOUDFLARED_BIN"); bin != "" {
		return bin
	}
	return platformFind("cloudflared", `C:\Program Files\cloudflared\cloudflared.exe`, `C:\Program Files (x86)\cloudflared\cloudflared.exe`)
}

func cloudflaredName(bin string) string {
	if bin != "" {
		return filepath.Base(bin)
	}
	if runtime.GOOS == "windows" {
		return "cloudflared.exe"
	}
	return "cloudflared"
}

func platformTunnel(ctx context.Context, a *App, args []string) {
	bin := cloudflaredPath()
	if bin == "" {
		consoleLogf("[cloudflared] Not installed.")
		return
	}
	path := filepath.Join(a.DataDir, "tunnel.json")
	name := cloudflaredName(bin)
	state, _ := readQuickTunnel(path)
	var done <-chan struct{}
	if platformIsProcess(state.PID, name) {
		if platformFlag(args, "--new-url") || state.Port != a.Config.Port || !quickTunnelHealthy(ctx, state.URL) {
			if ctx.Err() != nil {
				return
			}
			if err := platformKillProcess(state.PID); err != nil && platformIsProcess(state.PID, name) {
				consoleLogf("[cloudflared] Could not replace the previous tunnel: %v", err)
				return
			}
			state = quickTunnelState{}
		}
	} else {
		state = quickTunnelState{}
	}
	backoff := 3 * time.Second
	for ctx.Err() == nil {
		if state.PID == 0 {
			var err error
			state, done, err = launchQuickTunnel(ctx, bin, a.DataDir, a.Config.Port)
			if err != nil {
				if ctx.Err() != nil {
					return
				}
				consoleLogf("[cloudflared] %v. Retrying in %s.", err, backoff)
				if !waitTunnelRetry(ctx, backoff) {
					return
				}
				backoff = min(backoff*2, time.Minute)
				continue
			}
			if err = atomicJSON(path, state); err != nil {
				consoleLogf("[cloudflared] Could not save tunnel state: %v", err)
				if platformIsProcess(state.PID, name) {
					_ = platformKillProcess(state.PID)
				}
				return
			}
		}
		platformTunnelReady(ctx, a, args, state.URL)
		if !watchQuickTunnel(ctx, done, func() bool { return platformIsProcess(state.PID, name) }, 5*time.Second) {
			return
		}
		target := a.Config.PublicURL
		_ = saveTunnelURL(a, target)
		consoleURLReady(a, target)
		backoff = 3 * time.Second
		consoleLogf("[cloudflared] Connection lost. Checking for restart in %s.", backoff)
		if !waitTunnelRetry(ctx, backoff) {
			return
		}
		// An explicit --tunnel-stop removes the saved state before terminating its process.
		if saved, err := readQuickTunnel(path); err != nil || saved.PID != state.PID {
			return
		}
		state, done = quickTunnelState{}, nil
	}
}

func waitTunnelRetry(ctx context.Context, delay time.Duration) bool {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

func watchQuickTunnel(ctx context.Context, done <-chan struct{}, alive func() bool, interval time.Duration) bool {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return false
		case <-done:
			return ctx.Err() == nil
		case <-ticker.C:
			if done == nil && !alive() {
				return ctx.Err() == nil
			}
		}
	}
}

func launchQuickTunnel(ctx context.Context, bin, data string, port int) (quickTunnelState, <-chan struct{}, error) {
	logPath := filepath.Join(data, "cloudflared.log")
	log, err := os.OpenFile(logPath, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return quickTunnelState{}, nil, err
	}
	cmd := platformDetached(bin, "tunnel", "--url", fmt.Sprintf("http://127.0.0.1:%d", port), "--no-autoupdate", "--protocol", "http2")
	cmd.Stdout, cmd.Stderr = log, log
	if err = cmd.Start(); err != nil {
		_ = log.Close()
		return quickTunnelState{}, nil, err
	}
	done := make(chan struct{})
	go func() { _ = cmd.Wait(); _ = log.Close(); close(done) }()
	deadline := time.NewTimer(time.Minute)
	defer deadline.Stop()
	ticker := time.NewTicker(250 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			_ = cmd.Process.Kill()
			return quickTunnelState{}, nil, ctx.Err()
		case <-deadline.C:
			_ = cmd.Process.Kill()
			<-done
			return quickTunnelState{}, nil, errors.New("No public URL received. See data/cloudflared.log")
		case <-done:
			return quickTunnelState{}, nil, errors.New("Cloudflared stopped before publishing a URL")
		case <-ticker.C:
			b, err := os.ReadFile(logPath)
			if err == nil {
				if target := quickTunnelURL.FindString(string(b)); target != "" {
					return quickTunnelState{PID: cmd.Process.Pid, Port: port, URL: target, StartedAt: time.Now().UTC()}, done, nil
				}
			}
		}
	}
}

func quickTunnelHealthy(ctx context.Context, target string) bool {
	client := &http.Client{Timeout: 3 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	for i := 0; i < 3 && ctx.Err() == nil; i++ {
		req, err := http.NewRequestWithContext(ctx, "GET", target+"/api/health", nil)
		if err != nil {
			return false
		}
		resp, err := client.Do(req)
		if err == nil {
			var result M
			decodeErr := json.NewDecoder(io.LimitReader(resp.Body, 4096)).Decode(&result)
			_ = resp.Body.Close()
			if resp.StatusCode == http.StatusOK && decodeErr == nil && S(result, "name") == "remote-code" && B(result, "ok") {
				return true
			}
		}
		if i < 2 && !waitTunnelRetry(ctx, time.Second) {
			return false
		}
	}
	return false
}

func saveTunnelURL(a *App, target string) error {
	return atomicJSON(filepath.Join(a.DataDir, "runtime.json"), M{"publicUrl": target, "updatedAt": time.Now().UTC().Format(time.RFC3339)})
}

func platformTunnelReady(ctx context.Context, a *App, args []string, target string) {
	if err := saveTunnelURL(a, target); err != nil {
		consoleLogf("[cloudflared] Could not save public URL: %v", err)
	}
	a.Store.Emit(M{"type": "url", "url": target})
	if !consoleURLReady(a, target) {
		platformDisplay(a, args, target)
	}
	consoleLogf("[cloudflared] Connected: %s", target)
	a.startTask(func() { platformPushPair(ctx, a, platformPair(a, target)) })
}

func platformStopTunnel(data string) error {
	path := filepath.Join(data, "tunnel.json")
	state, err := readQuickTunnel(path)
	if err != nil {
		return err
	}
	if !platformIsProcess(state.PID, cloudflaredName(cloudflaredPath())) {
		return errors.New("saved tunnel process could not be verified")
	}
	if err = os.Remove(path); err != nil {
		return err
	}
	if err = platformKillProcess(state.PID); err != nil {
		return errors.Join(err, atomicJSON(path, state))
	}
	return nil
}
