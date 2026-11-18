package host

import (
	"archive/zip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const releaseRepository = "lighfu/remote-code"
const releaseApplicationID = "net.ajisaiflow.remotecode"
const releaseAPKLimit = 64 << 20

var releaseVersionPattern = regexp.MustCompile(`^(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})$`)
var releaseSHA = regexp.MustCompile(`^[a-f0-9]{64}$`)
var releaseClient = &http.Client{Timeout: 60 * time.Second, CheckRedirect: func(req *http.Request, via []*http.Request) error {
	if len(via) >= 6 {
		return errors.New("too many release redirects")
	}
	return releaseTrustedURL(req.URL)
}}

func releaseTrustedURL(u *url.URL) error {
	allowed := map[string]bool{"api.github.com": true, "github.com": true, "release-assets.githubusercontent.com": true, "objects.githubusercontent.com": true}
	if u.Scheme != "https" || u.User != nil || u.Port() != "" && u.Port() != "443" || !allowed[strings.ToLower(u.Hostname())] {
		return errors.New("Distribution destination not allowed")
	}
	return nil
}
func releaseRequest(ctx context.Context, target string, limit int64) ([]byte, error) {
	u, e := url.Parse(target)
	if e != nil {
		return nil, e
	}
	if e = releaseTrustedURL(u); e != nil {
		return nil, e
	}
	r, e := http.NewRequestWithContext(ctx, "GET", target, nil)
	if e != nil {
		return nil, e
	}
	r.Header.Set("User-Agent", "Remote-Code/"+Version)
	r.Header.Set("Accept", "application/vnd.github+json")
	r.Header.Set("X-GitHub-Api-Version", "2026-03-10")
	resp, e := releaseClient.Do(r)
	if e != nil {
		return nil, e
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("release download failed (%d)", resp.StatusCode)
	}
	if resp.ContentLength > limit {
		return nil, errors.New("release file exceeds size limit")
	}
	b, e := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if e != nil {
		return nil, e
	}
	if int64(len(b)) > limit {
		return nil, errors.New("release file exceeds size limit")
	}
	return b, nil
}
func releaseHash(b []byte) string { s := sha256.Sum256(b); return hex.EncodeToString(s[:]) }
func releaseAsset(release M, tag, name string, limit int64) (M, error) {
	assets, _ := release["assets"].([]any)
	var out M
	for _, raw := range assets {
		a, _ := raw.(map[string]any)
		if S(a, "name") != name {
			continue
		}
		if out != nil {
			return nil, errors.New("duplicate release asset")
		}
		out = a
	}
	if out == nil {
		return nil, errors.New("release asset not found: " + name)
	}
	digest := strings.TrimPrefix(S(out, "digest"), "sha256:")
	expected := "https://github.com/" + releaseRepository + "/releases/download/" + tag + "/" + name
	if S(out, "state") != "uploaded" || N(out, "size") < 1 || N(out, "size") > float64(limit) || S(out, "browser_download_url") != expected || !releaseSHA.MatchString(digest) {
		return nil, errors.New("invalid release asset metadata")
	}
	return M{"url": expected, "bytes": out["size"], "sha256": digest}, nil
}
func releaseDownloadAsset(ctx context.Context, asset M) ([]byte, error) {
	b, e := releaseRequest(ctx, S(asset, "url"), int64(N(asset, "bytes")))
	if e != nil {
		return nil, e
	}
	if int64(len(b)) != int64(N(asset, "bytes")) || releaseHash(b) != S(asset, "sha256") {
		return nil, errors.New("release asset SHA-256 mismatch")
	}
	return b, nil
}
func releaseLatest(ctx context.Context) (M, error) {
	b, e := releaseRequest(ctx, "https://api.github.com/repos/"+releaseRepository+"/releases/latest", 1<<20)
	if e != nil {
		return nil, e
	}
	var m M
	if e = json.Unmarshal(b, &m); e != nil {
		return nil, e
	}
	tag := S(m, "tag_name")
	if B(m, "draft") || B(m, "prerelease") || !strings.HasPrefix(tag, "v") || !releaseVersionPattern.MatchString(tag[1:]) {
		return nil, errors.New("not a stable release")
	}
	return m, nil
}
func releaseCompare(a, b string) int {
	if !releaseVersionPattern.MatchString(a) || !releaseVersionPattern.MatchString(b) {
		return 0
	}
	x, y := strings.Split(a, "."), strings.Split(b, ".")
	for i := 0; i < 3; i++ {
		p, _ := strconv.Atoi(x[i])
		q, _ := strconv.Atoi(y[i])
		if p < q {
			return -1
		}
		if p > q {
			return 1
		}
	}
	return 0
}
func releaseRegular(path string) (os.FileInfo, error) {
	s, e := os.Lstat(path)
	if e != nil {
		return nil, e
	}
	if !s.Mode().IsRegular() {
		return nil, errors.New("distribution file must be a regular file")
	}
	return s, nil
}
func releaseValidateAPK(m M) error {
	apk, _ := m["apk"].(map[string]any)
	v := S(m, "versionName")
	if N(m, "schemaVersion") != 1 || S(m, "repository") != releaseRepository || S(m, "platform") != "android" || S(m, "applicationId") != releaseApplicationID || !releaseVersionPattern.MatchString(v) || N(m, "versionCode") < 1 || N(m, "versionCode") > 2147483647 || S(apk, "name") != "remote-code-v"+v+".apk" || N(apk, "bytes") < 1 || N(apk, "bytes") > releaseAPKLimit || !releaseSHA.MatchString(S(apk, "sha256")) {
		return errors.New("invalid Android release manifest")
	}
	return nil
}

type releaseAPKState struct {
	mu             sync.Mutex
	loaded         bool
	lastCheck      time.Time
	refreshing     bool
	bundleManifest string
	catalog        map[string]releaseAPKCatalogEntry
	sourceDir      string
	sourceInfo     M
	source         releaseSourceStamp
}

type releaseAPKCatalogEntry struct {
	metadata, file os.FileInfo
	manifest       M
	path           string
	info           M
}

type releaseSourceStamp struct {
	apkSize, metadataSize         int64
	apkModified, metadataModified time.Time
}

var releaseAPKStates sync.Map

func releaseState(a *App) *releaseAPKState {
	v, _ := releaseAPKStates.LoadOrStore(a, &releaseAPKState{})
	return v.(*releaseAPKState)
}
func releaseSnapshot(dir string, m M) (M, error) {
	if e := releaseValidateAPK(m); e != nil {
		return nil, e
	}
	apk := providerMap(m["apk"])
	path := filepath.Join(dir, S(apk, "name"))
	stat, e := releaseRegular(path)
	if e != nil {
		return nil, e
	}
	return readReleaseAPK(path, stat, m)
}

func sameReleaseFile(a, b os.FileInfo) bool {
	return a != nil && b != nil && os.SameFile(a, b) && a.Size() == b.Size() && a.ModTime().Equal(b.ModTime())
}

func readReleaseAPK(path string, stat os.FileInfo, manifest M) (M, error) {
	apk := providerMap(manifest["apk"])
	if stat.Size() != int64(N(apk, "bytes")) {
		return nil, errors.New("APK size mismatch")
	}
	f, e := os.Open(path)
	if e != nil {
		return nil, e
	}
	defer f.Close()
	current, e := f.Stat()
	if e != nil || !sameReleaseFile(stat, current) {
		return nil, errors.New("APK changed before verification")
	}
	digest := sha256.New()
	size, e := io.Copy(digest, io.LimitReader(f, stat.Size()+1))
	if e != nil || size != stat.Size() || hex.EncodeToString(digest.Sum(nil)) != S(apk, "sha256") {
		return nil, errors.New("APK SHA-256 mismatch")
	}
	current, e = releaseRegular(path)
	if e != nil || !sameReleaseFile(stat, current) {
		return nil, errors.New("APK changed during verification")
	}
	return M{"path": path, "sha256": apk["sha256"], "size": size, "builtAt": stat.ModTime().UnixMilli(), "versionName": manifest["versionName"], "versionCode": manifest["versionCode"]}, nil
}

func (s *releaseAPKState) catalogAPK(pointer string, legacy bool) M {
	metadata, err := releaseRegular(pointer)
	if err != nil || metadata.Size() < 1 || metadata.Size() > 128<<10 {
		delete(s.catalog, pointer)
		return nil
	}
	entry := s.catalog[pointer]
	if !sameReleaseFile(entry.metadata, metadata) {
		entry = releaseAPKCatalogEntry{metadata: metadata}
		raw, err := os.ReadFile(pointer)
		var record M
		if err == nil && len(raw) <= 128<<10 && json.Unmarshal(raw, &record) == nil {
			manifest := record
			if legacy {
				manifest = providerMap(record["manifest"])
			}
			if releaseValidateAPK(manifest) == nil {
				apk := providerMap(manifest["apk"])
				name := S(apk, "name")
				if legacy {
					name = S(apk, "sha256") + ".apk"
				}
				entry.manifest = manifest
				entry.path = filepath.Join(filepath.Dir(pointer), name)
			}
		}
	}
	if entry.manifest != nil {
		stat, err := releaseRegular(entry.path)
		if err != nil {
			entry.file, entry.info = nil, nil
		} else if !sameReleaseFile(entry.file, stat) {
			entry.file = stat
			entry.info, _ = readReleaseAPK(entry.path, stat, entry.manifest)
		}
	}
	s.catalog[pointer] = entry
	return entry.info
}

func releaseAPKCache(data string) string {
	base, err := filepath.EvalSymlinks(data)
	if err != nil {
		return ""
	}
	dir := filepath.Join(base, "app-updates")
	actual, err := filepath.EvalSymlinks(dir)
	if err != nil || !platformSameResource(dir, actual) {
		return ""
	}
	return dir
}

func releaseSourceAPKDir() string {
	root, err := os.Getwd()
	if err != nil {
		return ""
	}
	root, err = filepath.EvalSymlinks(root)
	if err != nil {
		return ""
	}
	module, err := os.ReadFile(filepath.Join(root, "go.mod"))
	firstLine, _, _ := strings.Cut(string(module), "\n")
	if err != nil || strings.TrimSpace(firstLine) != "module github.com/"+releaseRepository {
		return ""
	}
	// go run places its executable outside the checkout, so locate Gradle's
	// release output relative to the source directory used to launch the server.
	return filepath.Join(root, "android", "app", "build", "outputs", "apk", "release")
}

func (s *releaseAPKState) sourceAPK() M {
	if s.sourceDir == "" {
		return nil
	}
	resolved, err := filepath.EvalSymlinks(s.sourceDir)
	if err != nil || !platformSameResource(s.sourceDir, resolved) {
		return nil
	}
	apkPath := filepath.Join(s.sourceDir, "app-release.apk")
	apk, err := releaseRegular(apkPath)
	if err != nil || apk.Size() < 1 || apk.Size() > releaseAPKLimit {
		return nil
	}
	metadataPath := filepath.Join(s.sourceDir, "output-metadata.json")
	metadata, err := releaseRegular(metadataPath)
	if err != nil || metadata.Size() < 1 || metadata.Size() > 128<<10 {
		return nil
	}
	stamp := releaseSourceStamp{apk.Size(), metadata.Size(), apk.ModTime(), metadata.ModTime()}
	if s.sourceInfo != nil && s.source == stamp {
		return s.sourceInfo
	}
	var output struct {
		ApplicationID string `json:"applicationId"`
		Variant       string `json:"variantName"`
		Elements      []struct {
			File        string `json:"outputFile"`
			VersionName string `json:"versionName"`
			VersionCode int    `json:"versionCode"`
		} `json:"elements"`
	}
	raw, err := os.ReadFile(metadataPath)
	if err != nil || json.Unmarshal(raw, &output) != nil || output.ApplicationID != releaseApplicationID || output.Variant != "release" || len(output.Elements) != 1 {
		return nil
	}
	entry := output.Elements[0]
	if entry.File != "app-release.apk" || !releaseVersionPattern.MatchString(entry.VersionName) || entry.VersionCode < 1 || entry.VersionCode > 2147483647 {
		return nil
	}
	file, err := os.Open(apkPath)
	if err != nil {
		return nil
	}
	defer file.Close()
	hash := sha256.New()
	size, err := io.Copy(hash, io.LimitReader(file, releaseAPKLimit+1))
	after, statErr := file.Stat()
	if err != nil || statErr != nil || size != apk.Size() || !after.ModTime().Equal(apk.ModTime()) || !os.SameFile(apk, after) {
		return nil
	}
	s.source = stamp
	s.sourceInfo = M{"path": apkPath, "sha256": hex.EncodeToString(hash.Sum(nil)), "size": size, "builtAt": apk.ModTime().UnixMilli(), "versionName": entry.VersionName, "versionCode": entry.VersionCode}
	return s.sourceInfo
}

func platformAPK(a *App) M {
	s := releaseState(a)
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.loaded {
		s.loaded = true
		s.sourceDir = releaseSourceAPKDir()
		s.catalog = make(map[string]releaseAPKCatalogEntry)
		exe, _ := os.Executable()
		s.bundleManifest = filepath.Join(filepath.Dir(exe), "android", "remote-code-android.json")
	}
	best := s.catalogAPK(s.bundleManifest, false)
	consider := func(info M) {
		if info != nil && (best == nil || N(info, "versionCode") >= N(best, "versionCode")) {
			best = info
		}
	}
	if dir := releaseAPKCache(a.DataDir); dir != "" {
		consider(s.catalogAPK(filepath.Join(dir, "current.json"), true))
		consider(s.catalogAPK(filepath.Join(dir, "remote-code-android.json"), false))
	}
	if manifest := os.Getenv("REMOTE_CODE_APK_MANIFEST"); manifest != "" {
		consider(s.catalogAPK(manifest, false))
	}
	consider(s.sourceAPK())
	return clone(best)
}
func platformAppUpdates(ctx context.Context, a *App) {
	tick := time.NewTicker(15 * time.Minute)
	defer tick.Stop()
	for {
		releaseRefreshAPK(ctx, a)
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
	}
}
func releaseRefreshAPK(ctx context.Context, a *App) error {
	s := releaseState(a)
	platformAPK(a)
	s.mu.Lock()
	if s.refreshing || time.Since(s.lastCheck) < time.Minute {
		s.mu.Unlock()
		return nil
	}
	s.refreshing = true
	s.lastCheck = time.Now()
	s.mu.Unlock()
	defer func() { s.mu.Lock(); s.refreshing = false; s.mu.Unlock() }()
	release, e := releaseLatest(ctx)
	if e != nil {
		return e
	}
	tag := S(release, "tag_name")
	asset, e := releaseAsset(release, tag, "remote-code-android.json", 1<<20)
	if e != nil {
		return e
	}
	raw, e := releaseDownloadAsset(ctx, asset)
	if e != nil {
		return e
	}
	var manifest M
	if e = json.Unmarshal(raw, &manifest); e != nil {
		return e
	}
	if e = releaseValidateAPK(manifest); e != nil {
		return e
	}
	current := platformAPK(a)
	if current != nil && N(manifest, "versionCode") <= N(current, "versionCode") {
		return nil
	}
	apk, _ := manifest["apk"].(map[string]any)
	asset, e = releaseAsset(release, tag, S(apk, "name"), releaseAPKLimit)
	if e != nil {
		return e
	}
	if N(asset, "bytes") != N(apk, "bytes") || S(asset, "sha256") != S(apk, "sha256") {
		return errors.New("APK release manifest does not match asset")
	}
	bytes, e := releaseDownloadAsset(ctx, asset)
	if e != nil {
		return e
	}
	base, e := filepath.EvalSymlinks(a.DataDir)
	if e != nil {
		return e
	}
	dir := filepath.Join(base, "app-updates")
	if e = os.MkdirAll(dir, 0700); e != nil {
		return e
	}
	actual, e := filepath.EvalSymlinks(dir)
	if e != nil || !platformSameResource(actual, dir) {
		return errors.New("invalid APK cache path")
	}
	if _, err := releaseSnapshot(dir, manifest); err != nil {
		if err := writeReleaseAPK(filepath.Join(dir, S(apk, "name")), bytes); err != nil {
			return err
		}
	}
	return atomicJSON(filepath.Join(dir, "remote-code-android.json"), manifest)
}

func writeReleaseAPK(path string, data []byte) error {
	if _, err := releaseRegular(path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	f, err := os.CreateTemp(filepath.Dir(path), ".apk-")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	_, err = f.Write(data)
	if err == nil {
		err = f.Sync()
	}
	if err = errors.Join(err, f.Close()); err != nil {
		return err
	}
	return os.Rename(f.Name(), path)
}

func releaseSelect(root string) (string, string, error) {
	raw, e := os.ReadFile(filepath.Join(root, "current.json"))
	if e != nil {
		return "", "", e
	}
	var state M
	if e = json.Unmarshal(raw, &state); e != nil {
		return "", "", e
	}
	v := S(state, "version")
	if !releaseVersionPattern.MatchString(v) {
		return "", "", errors.New("invalid current release version")
	}
	path := filepath.Join(root, "releases", v, "remote-code.exe")
	if _, e = releaseRegular(path); e != nil {
		return "", "", e
	}
	canonical, e := filepath.EvalSymlinks(path)
	if e != nil || !strings.EqualFold(canonical, path) {
		return "", "", errors.New("invalid release executable path")
	}
	return v, path, nil
}

func portableServerArgs(data string, args []string) []string {
	childArgs := []string{"--serve", "--data", data}
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--data":
			i++
		case "--serve":
		default:
			childArgs = append(childArgs, args[i])
		}
	}
	if !platformFlag(childArgs, "--no-tunnel") && !platformFlag(childArgs, "--tunnel") {
		config, err := LoadConfig(data)
		if err == nil && config.PublicURL == "" {
			childArgs = append(childArgs, "--tunnel")
		}
	}
	return childArgs
}

func portableServerCommand(exe string, args []string) (*exec.Cmd, string) {
	cmd := platformCommand(context.Background(), exe, args...)
	cmd.Stdin = os.Stdin
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	instance := NewID()
	cmd.Env = append(os.Environ(), "REMOTE_CODE_INSTANCE="+instance)
	return cmd, instance
}

type portableChildExit struct {
	err     error
	runtime time.Duration
	started bool
}

func runPortableChild(exe string, args []string) portableChildExit {
	cmd, _ := portableServerCommand(exe, args)
	if err := cmd.Start(); err != nil {
		return portableChildExit{err: err}
	}
	started := time.Now()
	err := cmd.Wait()
	return portableChildExit{err: err, runtime: time.Since(started), started: true}
}

func supervisePortableChild(ctx context.Context, args []string, first portableChildExit, wait time.Duration, launch func([]string) portableChildExit) error {
	retryArgs := make([]string, 0, len(args))
	for _, arg := range args {
		if arg != "--new-url" {
			retryArgs = append(retryArgs, arg)
		}
	}
	shortExits := 0
	for result := first; ; result = launch(retryArgs) {
		if ctx.Err() != nil || result.err == nil {
			return nil
		}
		if result.runtime >= 30*time.Second {
			shortExits = 0
		} else {
			shortExits++
		}
		if !result.started || shortExits >= 3 {
			return fmt.Errorf("server process failed: %w", result.err)
		}
		fmt.Printf("[Startup] Server exited unexpectedly: %v. Restarting.\n", result.err)
		timer := time.NewTimer(wait)
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil
		case <-timer.C:
			if ctx.Err() != nil {
				return nil
			}
		}
	}
}

func runPortable(root, data string, args []string) error {
	var marker M
	raw, e := os.ReadFile(filepath.Join(root, "portable.json"))
	if e != nil {
		return e
	}
	if json.Unmarshal(raw, &marker) != nil || N(marker, "launcherProtocol") != 3 || S(marker, "runtime") != "native-go" || S(marker, "repository") != releaseRepository {
		return errors.New("unsupported native portable installation")
	}
	if _, e = os.Stat(filepath.Join(root, ".git")); e == nil {
		return errors.New("cannot update a Git checkout")
	}
	canonicalRoot, e := filepath.EvalSymlinks(root)
	if e != nil {
		return e
	}
	key := releaseHash([]byte(strings.ToLower(canonicalRoot)))[:32]
	lease, e := platformListenPipe(platformPipeName("update", key))
	if e != nil {
		return errors.New("another launcher is preparing this installation; keep the existing terminal open")
	}
	var releaseOnce sync.Once
	unlock := func() { releaseOnce.Do(func() { lease.Close() }) }
	defer unlock()
	version, exe, e := releaseSelect(root)
	if e != nil {
		return e
	}
	previousState, e := os.ReadFile(filepath.Join(root, "current.json"))
	if e != nil {
		return e
	}
	previousExe := exe
	var oldPointer M
	if json.Unmarshal(previousState, &oldPointer) != nil {
		return errors.New("invalid current release pointer")
	}
	updated := false
	checkCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	candidate, checkErr := releaseLatest(checkCtx)
	cancel()
	if checkErr == nil && releaseCandidateAllowed(oldPointer, version, strings.TrimPrefix(S(candidate, "tag_name"), "v")) {
		next := strings.TrimPrefix(S(candidate, "tag_name"), "v")
		fmt.Printf("\n[Update] Server %s -> %s\n[Update] Source: https://github.com/%s/releases/tag/v%s\n", version, next, releaseRepository, next)
		if platformPrompt("Download and use this update? [y/N]: ") {
			if e = releaseStage(root, candidate); e != nil {
				fmt.Println("[Update] Keeping current version:", e)
			} else {
				if e = atomicJSON(filepath.Join(root, "current.json"), M{"schemaVersion": 2, "version": next, "highestVersion": next, "previousVersion": version}); e != nil {
					return e
				}
				updated = true
				_, exe, e = releaseSelect(root)
				if e != nil {
					var previous M
					if json.Unmarshal(previousState, &previous) == nil {
						_ = atomicJSON(filepath.Join(root, "current.json"), previous)
					}
					return e
				}
			}
		}
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	childArgs := portableServerArgs(data, args)
	launch := func(args []string) portableChildExit { return runPortableChild(exe, args) }
	const restartDelay = 1500 * time.Millisecond
	if ctx.Err() != nil {
		if updated {
			return atomicJSON(filepath.Join(root, "current.json"), oldPointer)
		}
		return nil
	}
	if !updated {
		unlock()
		return supervisePortableChild(ctx, childArgs, launch(childArgs), restartDelay, launch)
	}
	cmd, instance := portableServerCommand(exe, childArgs)
	if e = cmd.Start(); e == nil {
		started := time.Now()
		finished := make(chan error, 1)
		go func() { finished <- cmd.Wait() }()
		tick := time.NewTicker(250 * time.Millisecond)
		defer tick.Stop()
		deadline := time.NewTimer(90 * time.Second)
		defer deadline.Stop()
		for {
			select {
			case <-ctx.Done():
				<-finished
				return atomicJSON(filepath.Join(root, "current.json"), oldPointer)
			case childErr := <-finished:
				if childErr == nil || ctx.Err() != nil {
					return atomicJSON(filepath.Join(root, "current.json"), oldPointer)
				}
				e = errors.New("updated server exited before healthy startup")
				goto rollback
			case <-deadline.C:
				cmd.Process.Kill()
				<-finished
				e = errors.New("updated server did not become healthy")
				goto rollback
			case <-tick.C:
				config, err := LoadConfig(data)
				if err != nil {
					continue
				}
				probe, cancel := context.WithTimeout(ctx, time.Second)
				req, _ := http.NewRequestWithContext(probe, "GET", fmt.Sprintf("http://127.0.0.1:%d/api/meta", config.Port), nil)
				req.Header.Set("Authorization", "Bearer "+config.Token)
				response, err := (&http.Client{CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }}).Do(req)
				if err == nil {
					var meta M
					json.NewDecoder(io.LimitReader(response.Body, 1<<20)).Decode(&meta)
					response.Body.Close()
					if response.StatusCode == 200 && S(meta, "instanceId") == instance && S(meta, "version") == strings.TrimPrefix(S(candidate, "tag_name"), "v") {
						cancel()
						unlock()
						result := portableChildExit{err: <-finished, runtime: time.Since(started), started: true}
						return supervisePortableChild(ctx, childArgs, result, restartDelay, launch)
					}
				}
				cancel()
			}
		}
	}
rollback:
	var oldState M
	if json.Unmarshal(previousState, &oldState) != nil {
		return errors.New("failed to restore previous release pointer")
	}
	failed := array(oldState["failedVersions"])
	failed = append(failed, strings.TrimPrefix(S(candidate, "tag_name"), "v"))
	oldState["failedVersions"] = failed
	if err := atomicJSON(filepath.Join(root, "current.json"), oldState); err != nil {
		return err
	}
	fmt.Println("[Update] Restored previous version:", e)
	unlock()
	if ctx.Err() != nil {
		return nil
	}
	exe = previousExe
	return supervisePortableChild(ctx, childArgs, launch(childArgs), restartDelay, launch)
}

func releaseCandidateAllowed(pointer M, current, candidate string) bool {
	if releaseCompare(candidate, current) <= 0 {
		return false
	}
	highest := S(pointer, "highestVersion")
	if highest != "" && releaseCompare(candidate, highest) < 0 {
		return false
	}
	for _, failed := range array(pointer["failedVersions"]) {
		if text, ok := failed.(string); ok && text == candidate {
			return false
		}
	}
	return true
}

func releaseStage(root string, release M) error {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	tag := S(release, "tag_name")
	version := strings.TrimPrefix(tag, "v")
	manifestAsset, e := releaseAsset(release, tag, "remote-code-win32-x64.json", 128<<10)
	if e != nil {
		return e
	}
	raw, e := releaseDownloadAsset(ctx, manifestAsset)
	if e != nil {
		return e
	}
	var manifest M
	if e = json.Unmarshal(raw, &manifest); e != nil {
		return e
	}
	archive, e := releaseAsset(release, tag, "remote-code-v"+version+"-win32-x64.zip", 256<<20)
	if e != nil {
		return e
	}
	desc, _ := manifest["archive"].(map[string]any)
	if N(manifest, "schemaVersion") != 2 || S(manifest, "repository") != releaseRepository || S(manifest, "version") != version || S(manifest, "platform") != "win32-x64" || S(manifest, "runtime") != "native-go" || N(manifest, "launcherProtocol") != 3 || S(desc, "sha256") != S(archive, "sha256") || N(desc, "bytes") != N(archive, "bytes") {
		return errors.New("native update manifest mismatch")
	}
	bytes, e := releaseDownloadAsset(ctx, archive)
	if e != nil {
		return e
	}
	if e = os.MkdirAll(filepath.Join(root, "releases"), 0700); e != nil {
		return e
	}
	releasesPath := filepath.Join(root, "releases")
	canonical, e := filepath.EvalSymlinks(releasesPath)
	if e != nil || !platformSameResource(canonical, releasesPath) || !strings.EqualFold(canonical, releasesPath) {
		return errors.New("invalid releases destination")
	}
	dir, e := os.MkdirTemp(root, ".stage-")
	if e != nil {
		return e
	}
	defer os.RemoveAll(dir)
	tmp := filepath.Join(dir, "update.zip")
	if e = os.WriteFile(tmp, bytes, 0600); e != nil {
		return e
	}
	dest := filepath.Join(dir, "payload")
	if e = releaseExtract(tmp, dest, "releases/"+version+"/"); e != nil {
		return e
	}
	source := filepath.Join(dest, "releases", version)
	exe := filepath.Join(source, "remote-code.exe")
	if _, e = releaseRegular(exe); e != nil {
		return e
	}
	binary, _ := manifest["executable"].(map[string]any)
	content, e := os.ReadFile(exe)
	if e != nil || N(binary, "bytes") != float64(len(content)) || S(binary, "sha256") != releaseHash(content) {
		return errors.New("updated executable digest mismatch")
	}
	final := filepath.Join(root, "releases", version)
	if _, e = os.Lstat(final); e == nil {
		return errors.New("release directory already exists")
	}
	return os.Rename(source, final)
}

func releaseExtract(archive, destination, prefix string) error {
	reader, e := zip.OpenReader(archive)
	if e != nil {
		return e
	}
	defer reader.Close()
	if len(reader.File) > 8192 {
		return errors.New("too many archive entries")
	}
	seen := map[string]bool{}
	var total uint64
	for _, f := range reader.File {
		name := f.Name
		if strings.Contains(name, `\`) || strings.HasPrefix(name, "/") || strings.Contains(name, ":") || strings.ContainsRune(name, 0) || f.Mode()&os.ModeSymlink != 0 || f.UncompressedSize64 > 128<<20 {
			return errors.New("unsafe archive entry")
		}
		clean := filepath.ToSlash(filepath.Clean(name))
		if clean == ".." || strings.HasPrefix(clean, "../") || clean != strings.TrimSuffix(name, "/") {
			return errors.New("unsafe archive entry path")
		}
		key := strings.ToLower(clean)
		if seen[key] {
			return errors.New("duplicate archive entry")
		}
		seen[key] = true
		total += f.UncompressedSize64
		if total > 256<<20 {
			return errors.New("archive expands beyond limit")
		}
		if !strings.HasPrefix(name, prefix) {
			continue
		}
		target := filepath.Join(destination, filepath.FromSlash(clean))
		rel, e := filepath.Rel(destination, target)
		if e != nil || strings.HasPrefix(rel, "..") {
			return errors.New("archive path escaped root")
		}
		if f.FileInfo().IsDir() {
			if e = os.MkdirAll(target, 0700); e != nil {
				return e
			}
			continue
		}
		if !f.Mode().IsRegular() {
			return errors.New("unsupported archive file type")
		}
		if e = os.MkdirAll(filepath.Dir(target), 0700); e != nil {
			return e
		}
		input, e := f.Open()
		if e != nil {
			return e
		}
		output, e := os.OpenFile(target, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
		if e != nil {
			input.Close()
			return e
		}
		n, e := io.Copy(output, io.LimitReader(input, int64(f.UncompressedSize64)+1))
		input.Close()
		closeErr := output.Close()
		if e != nil {
			return e
		}
		if closeErr != nil {
			return closeErr
		}
		if uint64(n) != f.UncompressedSize64 {
			return errors.New("archive file size mismatch")
		}
	}
	return nil
}
