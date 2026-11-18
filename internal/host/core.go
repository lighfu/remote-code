package host

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

var Version = "0.18.9"

type M = map[string]any

func S(m M, k string) string { v, _ := m[k].(string); return v }
func N(m M, k string) float64 {
	switch v := m[k].(type) {
	case float64:
		return v
	case int:
		return float64(v)
	case int64:
		return float64(v)
	case json.Number:
		f, _ := v.Float64()
		return f
	}
	return 0
}
func B(m M, k string) bool { v, _ := m[k].(bool); return v }
func NewID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	s := hex.EncodeToString(b[:])
	return s[:8] + "-" + s[8:12] + "-" + s[12:16] + "-" + s[16:20] + "-" + s[20:]
}
func clone[T any](v T) T {
	raw, _ := json.Marshal(v)
	var out T
	_ = json.Unmarshal(raw, &out)
	return out
}
func nowMS() int64 { return time.Now().UnixMilli() }

type Config struct {
	Port               int
	Token              string
	AllowLAN           bool
	PublicURL          string
	QuickTunnel        bool
	USBReverse         bool
	AccessClientID     string
	AccessClientSecret string
	Raw                M
}

func LoadConfig(dataDir string) (Config, error) {
	m := M{}
	raw, err := os.ReadFile(filepath.Join(dataDir, "config.json"))
	if err == nil {
		if err = json.Unmarshal(raw, &m); err != nil {
			return Config{}, fmt.Errorf("Invalid config.json: %w", err)
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return Config{}, err
	}
	c := Config{Port: 8787, Token: S(m, "token"), AllowLAN: B(m, "allowLan"), PublicURL: S(m, "publicUrl"), QuickTunnel: B(m, "quickTunnel"), USBReverse: true, AccessClientID: S(m, "accessClientId"), AccessClientSecret: S(m, "accessClientSecret"), Raw: m}
	if _, ok := m["port"]; ok {
		c.Port = int(N(m, "port"))
	}
	if _, ok := m["usbReverse"]; ok {
		c.USBReverse = B(m, "usbReverse")
	}
	if env := os.Getenv("PORT"); env != "" {
		c.Port, err = strconv.Atoi(env)
		if err != nil {
			return c, errors.New("Invalid PORT")
		}
	}
	if c.Port < 1 || c.Port > 65535 {
		return c, errors.New("Port must be between 1 and 65535")
	}
	if c.Token == "" {
		var token [24]byte
		if _, err = rand.Read(token[:]); err != nil {
			return c, err
		}
		c.Token = hex.EncodeToString(token[:])
	}
	return c, nil
}
func SaveConfig(dataDir string, c Config) error {
	m := clone(c.Raw)
	if m == nil {
		m = M{}
	}
	m["port"] = c.Port
	m["token"] = c.Token
	m["allowLan"] = c.AllowLAN
	m["publicUrl"] = c.PublicURL
	m["quickTunnel"] = c.QuickTunnel
	m["usbReverse"] = c.USBReverse
	m["accessClientId"] = c.AccessClientID
	m["accessClientSecret"] = c.AccessClientSecret
	return atomicJSON(filepath.Join(dataDir, "config.json"), m)
}
func atomicJSON(path string, v any) error {
	raw, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	if err = os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	f, err := os.CreateTemp(filepath.Dir(path), ".remote-code-*.tmp")
	if err != nil {
		return err
	}
	name := f.Name()
	defer os.Remove(name)
	if err = f.Chmod(0600); err == nil {
		_, err = f.Write(raw)
	}
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	return os.Rename(name, path)
}

type Provider interface {
	Do(context.Context, string, *Record, M) (any, error)
	Available() bool
	Close() error
}
type Record struct {
	Info    M   `json:"info"`
	Entries []M `json:"entries"`
}
type App struct {
	DataDir           string
	Config            Config
	Store             *Store
	Providers         map[string]Provider
	InstanceID        string
	StartedAt         int64
	mu                sync.Mutex
	usage             M
	usageLoading      map[string]bool
	usageAt           map[string]time.Time
	usageSeenAt       map[string]time.Time
	folderHistory     map[string][]folderUse
	folderReadAt      time.Time
	folderReading     bool
	btwMu             sync.Mutex
	btw               map[string][]M
	btwCancel         map[string]context.CancelFunc
	closed            chan struct{}
	closeOnce         sync.Once
	closers           []func()
	contextMu         sync.Mutex
	contexts          map[string]contextCacheValue
	contextFlights    map[contextCacheKey]chan struct{}
	fx                fxRates
	analyticsBusy     sync.Map
	requestMu         sync.Mutex
	requests          sync.WaitGroup
	draining          bool
	taskCtx           context.Context
	taskCancel        context.CancelFunc
	tasks             sync.WaitGroup
	tunnel            configuredTunnelState
	dependencies      dependencyInstaller
	dependencyRun     func(context.Context, string, func(string)) error
	dependencyResolve func(string) (string, error)
}

func NewApp(dataDir string) (*App, error) {
	abs, err := filepath.Abs(dataDir)
	if err != nil {
		return nil, err
	}
	c, err := LoadConfig(abs)
	if err != nil {
		return nil, err
	}
	for _, dir := range []string{abs, filepath.Join(abs, "sessions"), filepath.Join(abs, "uploads")} {
		if err = os.MkdirAll(dir, 0700); err != nil {
			return nil, err
		}
	}
	store, err := OpenStore(filepath.Join(abs, "sessions"))
	if err != nil {
		return nil, err
	}
	a := &App{DataDir: abs, Config: c, Store: store, Providers: map[string]Provider{}, InstanceID: NewID(), StartedAt: nowMS(), usage: M{}, usageLoading: map[string]bool{}, usageAt: map[string]time.Time{}, btw: map[string][]M{}, closed: make(chan struct{})}
	a.usageSeenAt = map[string]time.Time{}
	a.taskCtx, a.taskCancel = context.WithCancel(context.Background())
	a.btwCancel = map[string]context.CancelFunc{}
	a.contexts = map[string]contextCacheValue{}
	a.contextFlights = map[contextCacheKey]chan struct{}{}
	a.fx.restore(abs)
	if id := os.Getenv("REMOTE_CODE_INSTANCE"); id != "" {
		a.InstanceID = id
	}
	for _, p := range []string{"claude", "codex", "antigravity"} {
		provider := NewProvider(p, store, abs)
		if native, ok := provider.(*nativeProvider); ok {
			native.usageObserved = func(usage M) { a.observeProviderUsage(p, usage) }
		}
		a.Providers[p] = provider
	}
	if raw, e := os.ReadFile(filepath.Join(abs, "usage-cache.json")); e == nil {
		var saved M
		if json.Unmarshal(raw, &saved) == nil {
			for name := range a.Providers {
				usage := providerMap(saved[name])
				at := int64(N(usage, "at"))
				if _, valid := usage["windows"].([]any); valid && at > 0 {
					a.usage[name] = usage
					a.usageAt[name] = time.UnixMilli(min(at, nowMS()))
				}
			}
		}
	}
	return a, nil
}
func (a *App) Close() error {
	var result error
	a.closeOnce.Do(func() {
		a.beginDrain()
		if a.taskCancel != nil {
			a.taskCancel()
		}
		close(a.closed)
		a.mu.Lock()
		closers := append([]func(){}, a.closers...)
		a.mu.Unlock()
		for _, f := range closers {
			f()
		}
		for _, p := range a.Providers {
			if err := p.Close(); err != nil {
				result = errors.Join(result, err)
			}
		}
		a.tasks.Wait()
		result = errors.Join(result, a.Store.Close())
		releaseAPKStates.Delete(a)
	})
	return result
}
func (a *App) OnClose(f func()) { a.mu.Lock(); defer a.mu.Unlock(); a.closers = append(a.closers, f) }

type HTTPError struct {
	Status  int
	Message string
}

func (e *HTTPError) Error() string { return e.Message }
func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
func apiError(w http.ResponseWriter, err error) {
	status := 400
	var e *HTTPError
	if errors.As(err, &e) {
		status = e.Status
	}
	var fileErr *fileHTTPError
	if errors.As(err, &fileErr) {
		status = fileErr.status
	}
	writeJSON(w, status, M{"error": err.Error()})
}
func readJSON(w http.ResponseWriter, r *http.Request) (M, error) {
	r.Body = http.MaxBytesReader(w, r.Body, 30<<20)
	defer r.Body.Close()
	d := json.NewDecoder(r.Body)
	var m M
	if err := d.Decode(&m); err != nil {
		return nil, &HTTPError{400, "Failed to parse JSON"}
	}
	var extra any
	if d.Decode(&extra) != ioEOF {
		return nil, &HTTPError{400, "Unexpected trailing JSON"}
	}
	if m == nil {
		return nil, errors.New("Expected JSON object")
	}
	return m, nil
}

var ioEOF = io.EOF

func preview(text string) string {
	r := []rune(strings.Join(strings.Fields(text), " "))
	if len(r) > 140 {
		r = r[:140]
	}
	return string(r)
}
