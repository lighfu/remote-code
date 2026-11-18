package host

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func dependencyAPIResponse(t *testing.T, response *httptest.ResponseRecorder) M {
	t.Helper()
	var body M
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("dependency API returned invalid JSON: %v / %s", err, response.Body)
	}
	return body
}

func dependencyAPIRow(t *testing.T, state M, target string) M {
	t.Helper()
	for _, row := range providerRows(state["dependencies"]) {
		if S(row, "target") == target {
			return row
		}
	}
	t.Fatalf("dependency state omitted %s: %v", target, state)
	return nil
}

func waitDependencyAPIStatus(t *testing.T, a *App, target, status string) M {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	var last M
	for time.Now().Before(deadline) {
		response := testRequest(a, http.MethodGet, "/api/dependencies", "")
		if response.Code != http.StatusOK {
			t.Fatalf("polling dependency status: %d / %s", response.Code, response.Body)
		}
		last = dependencyAPIResponse(t, response)
		row := dependencyAPIRow(t, last, target)
		if S(row, "status") == status && !B(last, "installing") {
			return row
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("dependency %s did not reach %s: %v", target, status, last)
	return nil
}

func TestDependencyAPIRequiresPairingToken(t *testing.T) {
	a := testApp(t)
	for _, method := range []string{http.MethodGet, http.MethodPost} {
		path := "/api/dependencies"
		if method == http.MethodPost {
			path += "/codex/install"
		}
		for _, authorization := range []string{"", "Bearer wrong-token"} {
			request := httptest.NewRequest(method, path, strings.NewReader("{}"))
			request.RemoteAddr = "127.0.0.1:12345"
			request.Header.Set("Authorization", authorization)
			response := httptest.NewRecorder()
			a.Handler().ServeHTTP(response, request)
			if response.Code != http.StatusUnauthorized {
				t.Fatalf("%s %s without pairing token: %d / %s", method, path, response.Code, response.Body)
			}
		}
	}
}

func TestDependencyAPIListsAllFourTargets(t *testing.T) {
	a := testApp(t)
	a.dependencyResolve = func(target string) (string, error) {
		if target == "codex" {
			return `C:\Test Install\codex.exe`, nil
		}
		return "", errors.New("not installed")
	}
	response := testRequest(a, http.MethodGet, "/api/dependencies", "")
	if response.Code != http.StatusOK {
		t.Fatalf("list dependencies: %d / %s", response.Code, response.Body)
	}
	state := dependencyAPIResponse(t, response)
	if B(state, "supported") != (runtime.GOOS == "windows") || B(state, "installing") {
		t.Fatalf("incorrect initial support/busy state: %v", state)
	}
	if got := len(providerRows(state["dependencies"])); got != 4 {
		t.Fatalf("listed %d dependencies, want four: %v", got, state)
	}
	for _, target := range []string{"claude", "codex", "antigravity", "cloudflared"} {
		row := dependencyAPIRow(t, state, target)
		if S(row, "name") == "" || S(row, "status") == "" {
			t.Fatalf("incomplete %s dependency: %v", target, row)
		}
		if B(row, "installed") != (target == "codex") {
			t.Fatalf("incorrect availability for %s: %v", target, row)
		}
	}
}

func TestDependencyAPIAcceptsAsyncInstallAndRejectsConcurrentRequests(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("native dependency installation is Windows only")
	}
	a := testApp(t)
	var installed atomic.Bool
	var launches atomic.Int32
	started := make(chan context.Context, 1)
	release := make(chan struct{})
	a.dependencyResolve = func(target string) (string, error) {
		if target == "codex" && installed.Load() {
			return `C:\Test Install\codex.exe`, nil
		}
		return "", errors.New("not installed")
	}
	a.dependencyRun = func(ctx context.Context, target string, progress func(string)) error {
		launches.Add(1)
		progress("Verified fixture download")
		started <- ctx
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-release:
			installed.Store(true)
			return nil
		}
	}
	requestCtx, cancelRequest := context.WithCancel(t.Context())
	defer cancelRequest()
	request := httptest.NewRequest(http.MethodPost, "/api/dependencies/codex/install", strings.NewReader("{}"))
	request = request.WithContext(requestCtx)
	request.RemoteAddr = "127.0.0.1:12345"
	request.Header.Set("Authorization", "Bearer "+a.Config.Token)
	response := httptest.NewRecorder()
	a.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusAccepted {
		t.Fatalf("async install was not accepted: %d / %s", response.Code, response.Body)
	}
	cancelRequest()
	select {
	case workerCtx := <-started:
		if err := workerCtx.Err(); err != nil {
			t.Fatalf("disconnect canceled an accepted installation: %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("accepted installation never started")
	}
	state := dependencyAPIResponse(t, testRequest(a, http.MethodGet, "/api/dependencies", ""))
	row := dependencyAPIRow(t, state, "codex")
	if !B(state, "installing") || S(row, "status") != "Installing..." || !strings.Contains(S(row, "detail"), "Verified fixture") {
		t.Fatalf("in-progress install cannot be monitored: %v", state)
	}
	console := attachConsole(a)
	t.Cleanup(console.detach)
	consoleRow := console.snapshot().Dependencies[1]
	if consoleRow.Target != "codex" || consoleRow.Status != "Installing..." || !strings.Contains(consoleRow.Detail, "Verified fixture") {
		t.Fatalf("dashboard and Android disagree about the active installation: %+v", consoleRow)
	}
	if err := console.installDependency(t.Context(), "claude"); err == nil {
		t.Fatal("dashboard started a second installer during an Android request")
	} else {
		var conflict *HTTPError
		if !errors.As(err, &conflict) || conflict.Status != http.StatusConflict {
			t.Fatalf("dashboard did not receive the shared conflict: %v", err)
		}
	}
	var callers sync.WaitGroup
	errorsFound := make(chan string, 16)
	for range 4 {
		for _, target := range []string{"claude", "codex", "antigravity", "cloudflared"} {
			callers.Go(func() {
				response := testRequest(a, http.MethodPost, "/api/dependencies/"+target+"/install", "{}")
				if response.Code != http.StatusConflict {
					errorsFound <- target + ": " + response.Body.String()
				}
			})
		}
	}
	callers.Wait()
	close(errorsFound)
	for problem := range errorsFound {
		t.Errorf("overlapping request did not return 409: %s", problem)
	}
	if launches.Load() != 1 {
		t.Fatalf("overlapping requests launched %d installers", launches.Load())
	}
	close(release)
	row = waitDependencyAPIStatus(t, a, "codex", "Installed")
	if !B(row, "installed") || S(row, "path") != `C:\Test Install\codex.exe` {
		t.Fatalf("installation result missing from polling: %v", row)
	}
}

func TestDependencyAPIReservesBeforeConcurrentFirstResponses(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("native dependency installation is Windows only")
	}
	a := testApp(t)
	var installed atomic.Bool
	var launches atomic.Int32
	release := make(chan struct{})
	a.dependencyResolve = func(target string) (string, error) {
		if installed.Load() {
			return `C:\Test Install\` + target + ".exe", nil
		}
		return "", errors.New("not installed")
	}
	a.dependencyRun = func(ctx context.Context, target string, progress func(string)) error {
		launches.Add(1)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-release:
			installed.Store(true)
			return nil
		}
	}
	gate := make(chan struct{})
	statuses := make(chan int, 24)
	var callers sync.WaitGroup
	for index := range 24 {
		target := []string{"claude", "codex", "antigravity", "cloudflared"}[index%4]
		callers.Go(func() {
			<-gate
			statuses <- testRequest(a, http.MethodPost, "/api/dependencies/"+target+"/install", "{}").Code
		})
	}
	close(gate)
	callers.Wait()
	close(statuses)
	accepted, conflicts := 0, 0
	for status := range statuses {
		switch status {
		case http.StatusAccepted:
			accepted++
		case http.StatusConflict:
			conflicts++
		default:
			t.Errorf("concurrent initial request returned %d", status)
		}
	}
	close(release)
	if accepted != 1 || conflicts != 23 {
		t.Fatalf("concurrent first requests: accepted=%d conflicts=%d", accepted, conflicts)
	}
	if err := a.Close(); err != nil {
		t.Fatal(err)
	}
	if launches.Load() != 1 {
		t.Fatalf("concurrent initial requests launched %d installers", launches.Load())
	}
}

func TestDependencyAPIReportsFailureAndSupportsRetry(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("native dependency installation is Windows only")
	}
	a := testApp(t)
	var attempts atomic.Int32
	var installed atomic.Bool
	a.dependencyResolve = func(string) (string, error) {
		if installed.Load() {
			return `C:\Test Install\claude.exe`, nil
		}
		return "", errors.New("not installed")
	}
	a.dependencyRun = func(ctx context.Context, target string, progress func(string)) error {
		if attempts.Add(1) == 1 {
			progress("Fixture server unavailable")
			return errors.New("fixture network failure")
		}
		installed.Store(true)
		return nil
	}
	response := testRequest(a, http.MethodPost, "/api/dependencies/claude/install", "{}")
	if response.Code != http.StatusAccepted {
		t.Fatalf("first request: %d / %s", response.Code, response.Body)
	}
	failed := waitDependencyAPIStatus(t, a, "claude", "Failed")
	if B(failed, "installed") || !strings.Contains(S(failed, "detail"), "fixture network failure") {
		t.Fatalf("failure details were lost: %v", failed)
	}
	response = testRequest(a, http.MethodPost, "/api/dependencies/claude/install", "{}")
	if response.Code != http.StatusAccepted {
		t.Fatalf("retry request: %d / %s", response.Code, response.Body)
	}
	waitDependencyAPIStatus(t, a, "claude", "Installed")
	if attempts.Load() != 2 {
		t.Fatalf("retry ran %d attempts", attempts.Load())
	}
}

func TestDependencyAPIRejectsUnsupportedInvalidAndDrainingRequests(t *testing.T) {
	a := testApp(t)
	var launches atomic.Int32
	a.dependencyRun = func(context.Context, string, func(string)) error { launches.Add(1); return nil }
	if runtime.GOOS == "windows" {
		for _, target := range []string{"unknown", "codex;anything"} {
			response := testRequest(a, http.MethodPost, "/api/dependencies/"+target+"/install", "{}")
			if response.Code != http.StatusBadRequest {
				t.Fatalf("unknown dependency %s: %d / %s", target, response.Code, response.Body)
			}
		}
		response := testRequest(a, http.MethodPost, "/api/dependencies/codex/install", "{")
		if response.Code != http.StatusBadRequest {
			t.Fatalf("invalid JSON: %d / %s", response.Code, response.Body)
		}
	} else {
		response := testRequest(a, http.MethodPost, "/api/dependencies/codex/install", "{}")
		if response.Code != http.StatusNotImplemented {
			t.Fatalf("unsupported platform: %d / %s", response.Code, response.Body)
		}
	}
	a.beginDrain()
	response := testRequest(a, http.MethodPost, "/api/dependencies/codex/install", "{}")
	if response.Code != http.StatusServiceUnavailable {
		t.Fatalf("draining generation accepted install: %d / %s", response.Code, response.Body)
	}
	if launches.Load() != 0 {
		t.Fatalf("rejected requests launched %d installers", launches.Load())
	}
	if runtime.GOOS == "windows" {
		if err := a.startDependencyInstall("codex"); err == nil {
			t.Fatal("draining between request admission and task scheduling accepted install")
		}
		if B(a.dependencyState(), "installing") {
			t.Fatal("failed task scheduling left an installation reservation behind")
		}
	}
}

func TestDependencyAPICancelsAcceptedInstallWhenAppCloses(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("native dependency installation is Windows only")
	}
	a := testApp(t)
	started, stopped := make(chan struct{}), make(chan struct{})
	a.dependencyResolve = func(string) (string, error) { return "", errors.New("not installed") }
	a.dependencyRun = func(ctx context.Context, target string, progress func(string)) error {
		close(started)
		<-ctx.Done()
		close(stopped)
		return ctx.Err()
	}
	response := testRequest(a, http.MethodPost, "/api/dependencies/cloudflared/install", "{}")
	if response.Code != http.StatusAccepted {
		t.Fatalf("accept cloudflared install: %d / %s", response.Code, response.Body)
	}
	select {
	case <-started:
	case <-time.After(3 * time.Second):
		t.Fatal("accepted installation never started")
	}
	closed := make(chan error, 1)
	go func() { closed <- a.Close() }()
	select {
	case err := <-closed:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("App.Close did not cancel/drain the accepted installer")
	}
	select {
	case <-stopped:
	default:
		t.Fatal("App.Close returned before installer stopped")
	}
}
