package host

import (
	"bufio"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	qrcode "github.com/skip2/go-qrcode"
	"golang.org/x/term"
)

var platformShutdown sync.Map

func platformFlag(args []string, key string) bool {
	for _, v := range args {
		if v == key {
			return true
		}
	}
	return false
}
func platformOption(args []string, key string) string {
	for i, v := range args {
		if v == key && i+1 < len(args) {
			return args[i+1]
		}
	}
	return ""
}

func Run(args []string) error {
	if len(args) > 0 && args[0] == "--console-bridge" {
		return runConsoleBridge(args[1:])
	}
	if platformFlag(args, "--help") {
		fmt.Println("Remote Code\n\n  remote-code.exe [--tunnel | --no-tunnel] [--new-url] [--no-qr] [--no-apk-qr] [--show-token]\n  remote-code.exe --data DIRECTORY\n  remote-code.exe --tunnel-stop\n  remote-code.exe --no-ui\n\nConsole: mouse / arrows / Enter / F5 / R restart / Q stop")
		return nil
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	root := filepath.Dir(exe)
	data := platformOption(args, "--data")
	if data == "" {
		data = os.Getenv("REMOTE_CODE_DATA")
	}
	if data == "" {
		if _, e := os.Stat(filepath.Join(root, "portable.json")); e == nil {
			data = filepath.Join(root, "data")
		} else {
			cwd, _ := os.Getwd()
			data = filepath.Join(cwd, "data")
		}
	}
	data, err = filepath.Abs(data)
	if err != nil {
		return err
	}
	if platformFlag(args, "--tunnel-stop") {
		return platformStopTunnel(data)
	}
	if !platformFlag(args, "--serve") {
		if _, e := os.Stat(filepath.Join(root, "portable.json")); e == nil {
			return runPortable(root, data, args)
		}
	}
	return runServer(data, args)
}

func platformPair(a *App, target string) string {
	host, _ := os.Hostname()
	q := url.Values{"url": {target}, "token": {a.Config.Token}, "name": {host}}
	if target == a.Config.PublicURL {
		if id := S(a.Config.Raw, "accessClientId"); id != "" {
			q.Set("cfid", id)
			q.Set("cfsecret", S(a.Config.Raw, "accessClientSecret"))
		}
	}
	return "remotecode://pair?" + q.Encode()
}

func platformQR(value string) {
	if !term.IsTerminal(int(os.Stdout.Fd())) {
		return
	}
	q, err := qrcode.New(value, qrcode.Medium)
	if err != nil {
		return
	}
	bitmap := q.Bitmap()
	if w, _, e := term.GetSize(int(os.Stdout.Fd())); e == nil && len(bitmap)+4 > w {
		fmt.Printf("  QR omitted: resize terminal to %d columns.\n", len(bitmap)+4)
		return
	}
	for y := 0; y < len(bitmap); y += 2 {
		fmt.Print("  ")
		for x := range bitmap[y] {
			top, bottom := bitmap[y][x], false
			if y+1 < len(bitmap) {
				bottom = bitmap[y+1][x]
			}
			switch {
			case !top && !bottom:
				fmt.Print("█")
			case top && !bottom:
				fmt.Print("▄")
			case !top && bottom:
				fmt.Print("▀")
			default:
				fmt.Print(" ")
			}
		}
		fmt.Println()
	}
}

func platformDisplay(a *App, args []string, target string) {
	if target == "" {
		target = fmt.Sprintf("http://127.0.0.1:%d", a.Config.Port)
	}
	host, _ := os.Hostname()
	fmt.Printf("\n  Remote Code v%s\n  PC: %s\n\n  Startup Status\n    Server: Running / Port %d\n    Runtime: Native Go / no Node.js required\n", Version, host, a.Config.Port)
	for _, name := range []string{"claude", "codex", "antigravity"} {
		status := "Not installed"
		if p := a.Providers[name]; p != nil && p.Available() {
			status = "Installed"
		}
		fmt.Printf("    %s: %s\n", name, status)
	}
	fmt.Printf("\n  Connect Android App\n    URL: %s\n", target)
	if platformFlag(args, "--show-token") {
		fmt.Printf("    Token: %s\n", a.Config.Token)
	}
	if !platformFlag(args, "--no-qr") {
		platformQR(platformPair(a, target))
	}
	if !platformFlag(args, "--no-qr") && !platformFlag(args, "--no-apk-qr") {
		if info := platformAPK(a); info != nil {
			fmt.Printf("\n  Install Android App v%s\n", S(info, "versionName"))
			platformQR(target + platformDownloadPath(a.Config.Token))
		}
	}
	fmt.Print("\n  Exit: Ctrl+C\n\n")
}

func platformDownloadPath(token string) string {
	h := hmac.New(sha256.New, []byte(token))
	h.Write([]byte("remote-code apk download"))
	return "/dl/" + base64.RawURLEncoding.EncodeToString(h.Sum(nil))[:22] + "/remote-code.apk"
}

func platformPrompt(question string) bool {
	if !term.IsTerminal(int(os.Stdin.Fd())) || !term.IsTerminal(int(os.Stdout.Fd())) {
		fmt.Println("[Startup] No interactive terminal; keeping current server/version.")
		return false
	}
	return ConsoleConfirm(question, time.Minute)
}

func platformAcquire(data string, port int, stop context.CancelFunc) ([]net.Listener, error) {
	canonical, err := filepath.EvalSymlinks(data)
	if err != nil {
		return nil, err
	}
	id := M{"kind": "server", "resource": canonical, "version": Version, "pid": os.Getpid(), "instanceId": NewID(), "port": port, "canStop": true}
	var leases []net.Listener
	for _, scope := range []string{"data", "port"} {
		resource := canonical
		if scope == "port" {
			resource = strconv.Itoa(port)
		}
		owned := M{}
		for k, v := range id {
			owned[k] = v
		}
		owned["resource"] = resource
		endpoint := platformInstanceEndpoint(scope, resource)
		l, e := platformListenInstance(endpoint, owned, stop)
		if e != nil {
			existing := platformInstanceRequest(endpoint, "inspect", nil)
			identity, _ := existing["identity"].(map[string]any)
			if !B(existing, "ok") || S(identity, "kind") != "server" || !platformSameResource(S(identity, "resource"), resource) || !B(identity, "canStop") {
				for _, x := range leases {
					x.Close()
				}
				return nil, fmt.Errorf("existing server could not be verified; stop it manually (%w)", e)
			}
			fmt.Printf("\n[Startup] Server already running: v%s / PID %.0f / Port %.0f\n  Target: %s\n", S(identity, "version"), N(identity, "pid"), N(identity, "port"), S(identity, "resource"))
			if !platformPrompt("  1. Stop existing server and start\n  2. Cancel\nChoose [1/2] (default 2): ") {
				for _, x := range leases {
					x.Close()
				}
				return nil, nil
			}
			response := platformInstanceRequest(endpoint, "stop", identity)
			actual, _ := response["identity"].(map[string]any)
			if !B(response, "ok") || !platformSameIdentity(actual, identity) {
				for _, x := range leases {
					x.Close()
				}
				return nil, errors.New("existing server changed or refused shutdown")
			}
			until := time.Now().Add(30 * time.Second)
			for time.Now().Before(until) {
				time.Sleep(100 * time.Millisecond)
				l, e = platformListenInstance(endpoint, owned, stop)
				if e == nil {
					break
				}
			}
			if e != nil {
				for _, x := range leases {
					x.Close()
				}
				return nil, errors.New("existing server did not stop")
			}
		}
		leases = append(leases, l)
	}
	return leases, nil
}

func platformSameResource(a, b string) bool {
	if a == b {
		return true
	}
	if !strings.EqualFold(a, b) || !filepath.IsAbs(a) || !filepath.IsAbs(b) {
		return false
	}
	x, e := os.Stat(a)
	y, f := os.Stat(b)
	return e == nil && f == nil && x.IsDir() && y.IsDir() && os.SameFile(x, y)
}
func platformSameIdentity(a, b M) bool {
	return S(a, "kind") == S(b, "kind") && S(a, "resource") == S(b, "resource") && N(a, "pid") == N(b, "pid") && S(a, "instanceId") == S(b, "instanceId")
}
func platformInstanceEndpoint(scope, resource string) string {
	if scope == "data" {
		resource = strings.ToLower(resource)
	}
	home, _ := os.UserHomeDir()
	owner := os.Getenv("USERNAME") + "\x00" + home
	sum := sha256.Sum256([]byte(owner + "\x00" + scope + "\x00" + resource))
	return platformPipeName(scope, fmt.Sprintf("%x", sum[:16]))
}

func platformListenInstance(endpoint string, identity M, stop context.CancelFunc) (net.Listener, error) {
	l, err := platformListenPipe(endpoint)
	if err != nil {
		return nil, err
	}
	var stopping sync.Once
	go func() {
		for {
			c, e := l.Accept()
			if e != nil {
				return
			}
			go func() {
				defer c.Close()
				c.SetDeadline(time.Now().Add(2 * time.Second))
				line, e := bufio.NewReader(io.LimitReader(c, 8193)).ReadBytes('\n')
				if e != nil || len(line) > 8192 {
					return
				}
				var m M
				if json.Unmarshal(line, &m) != nil || S(m, "protocol") != "remote-code-instance" || N(m, "version") != 1 || S(m, "requestId") == "" {
					return
				}
				kind := S(m, "type")
				expected, _ := m["expected"].(map[string]any)
				ok := kind == "inspect" || kind == "stop" && B(identity, "canStop") && platformSameIdentity(identity, expected)
				json.NewEncoder(c).Encode(M{"protocol": "remote-code-instance", "version": 1, "requestId": m["requestId"], "type": kind, "ok": ok, "identity": identity})
				if kind == "stop" && ok {
					stopping.Do(stop)
				}
			}()
		}
	}()
	return l, nil
}
func platformInstanceRequest(endpoint, kind string, expected M) M {
	c, e := platformDialPipe(endpoint)
	if e != nil {
		return M{}
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(2 * time.Second))
	id := NewID()
	json.NewEncoder(c).Encode(M{"protocol": "remote-code-instance", "version": 1, "requestId": id, "type": kind, "expected": expected})
	var m M
	json.NewDecoder(io.LimitReader(c, 8192)).Decode(&m)
	if S(m, "protocol") != "remote-code-instance" || S(m, "requestId") != id || S(m, "type") != kind {
		return M{}
	}
	return m
}

func platformFind(exe string, candidates ...string) string {
	if p, e := exec.LookPath(exe); e == nil {
		return p
	}
	for _, p := range candidates {
		if s, e := os.Stat(p); e == nil && !s.IsDir() {
			return p
		}
	}
	return ""
}
func platformADB() string {
	sdk := os.Getenv("ANDROID_HOME")
	if sdk == "" {
		sdk = os.Getenv("ANDROID_SDK_ROOT")
	}
	if sdk == "" {
		sdk = filepath.Join(os.Getenv("LOCALAPPDATA"), "Android", "Sdk")
	}
	return platformFind("adb", filepath.Join(sdk, "platform-tools", "adb.exe"))
}
func platformUSB(ctx context.Context, a *App) {
	adb := platformADB()
	if adb == "" {
		return
	}
	tick := time.NewTicker(5 * time.Second)
	defer tick.Stop()
	for {
		c, cancel := context.WithTimeout(ctx, 5*time.Second)
		cmd := platformCommand(c, adb, "devices")
		out, e := cmd.Output()
		cancel()
		if e == nil {
			for _, line := range strings.Split(string(out), "\n") {
				f := strings.Fields(line)
				if len(f) < 2 || f[1] != "device" || strings.Contains(f[0], ":") {
					continue
				}
				c, cancel = context.WithTimeout(ctx, 5*time.Second)
				platformCommand(c, adb, "-s", f[0], "reverse", fmt.Sprintf("tcp:%d", a.Config.Port), fmt.Sprintf("tcp:%d", a.Config.Port)).Run()
				cancel()
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
	}
}

func platformPushPair(ctx context.Context, a *App, link string) {
	adb := platformADB()
	if adb == "" {
		return
	}
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	out, e := HostCommand(ctx, adb, "devices").Output()
	if e != nil {
		return
	}
	for _, line := range strings.Split(string(out), "\n") {
		fields := strings.Fields(line)
		if len(fields) < 2 || fields[1] != "device" || strings.Contains(fields[0], ":") {
			continue
		}
		quoted := "'" + strings.ReplaceAll(link, "'", "'\\''") + "'"
		HostCommand(ctx, adb, "-s", fields[0], "shell", "am", "broadcast", "-a", releaseApplicationID+".PAIR", "-n", releaseApplicationID+"/dev.remotecode.notify.PairReceiver", "--es", "link", quoted).Run()
	}
}
func RegisterPlatform(a *App, mux *http.ServeMux) {
	registerTerminals(a, mux)
	mux.HandleFunc("GET /api/app", func(w http.ResponseWriter, r *http.Request) {
		info := platformAPK(a)
		if info == nil {
			writeJSON(w, 200, M{"available": false})
			return
		}
		m := M{"available": true}
		for k, v := range info {
			if k != "path" {
				m[k] = v
			}
		}
		writeJSON(w, 200, m)
	})
	serve := func(w http.ResponseWriter, r *http.Request) {
		info := platformAPK(a)
		if info == nil {
			http.Error(w, "Published Android APK is not available", 503)
			return
		}
		w.Header().Set("Content-Type", "application/vnd.android.package-archive")
		w.Header().Set("X-APK-SHA256", S(info, "sha256"))
		w.Header().Set("Cache-Control", "private, no-cache")
		if strings.HasPrefix(r.URL.Path, "/dl/") {
			w.Header().Set("Content-Disposition", `attachment; filename="remote-code.apk"`)
		}
		http.ServeFile(w, r, S(info, "path"))
	}
	mux.HandleFunc("GET /api/app/apk", serve)
	mux.HandleFunc("GET /dl/{key}/remote-code.apk", func(w http.ResponseWriter, r *http.Request) {
		if !hmac.Equal([]byte("/dl/"+r.PathValue("key")+"/remote-code.apk"), []byte(platformDownloadPath(a.Config.Token))) {
			http.NotFound(w, r)
			return
		}
		serve(w, r)
	})
}

func PlatformClose(a *App) {
	platformTerminalsClose(a)
	if cancel, ok := platformShutdown.Load(a); ok {
		cancel.(context.CancelFunc)()
	}
}
