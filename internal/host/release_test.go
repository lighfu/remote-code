package host

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/hmac"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/hinshun/vt10x"
)

func TestReleaseTargetsAndManifestValidation(t *testing.T) {
	for _, target := range []string{"http://github.com/x", "https://github.com.evil.test/x", "https://user:secret@github.com/x", "https://github.com:444/x", "https://127.0.0.1/x"} {
		u, _ := url.Parse(target)
		if releaseTrustedURL(u) == nil {
			t.Errorf("accepted %s", target)
		}
	}
	u, _ := url.Parse("https://release-assets.githubusercontent.com/asset")
	if e := releaseTrustedURL(u); e != nil {
		t.Fatal(e)
	}
	manifest := M{"schemaVersion": 1, "repository": releaseRepository, "platform": "android", "applicationId": releaseApplicationID, "versionName": "1.2.3", "versionCode": 53, "apk": M{"name": "remote-code-v1.2.3.apk", "bytes": 1024, "sha256": strings.Repeat("a", 64)}}
	if e := releaseValidateAPK(manifest); e != nil {
		t.Fatal(e)
	}
	manifest["applicationId"] = "other.app"
	if releaseValidateAPK(manifest) == nil {
		t.Fatal("accepted wrong package")
	}
	if releaseCompare("0.18.0", "0.17.99") <= 0 || releaseCompare("1.0.0", "1.0.0") != 0 {
		t.Fatal("version ordering")
	}
}

func TestReleaseCandidatePreventsRollbackAndRetryLoops(t *testing.T) {
	pointer := M{"highestVersion": "2.0.0", "failedVersions": []any{"2.0.1"}}
	if releaseCandidateAllowed(pointer, "1.0.0", "1.5.0") {
		t.Fatal("accepted downgrade below previous high water mark")
	}
	if releaseCandidateAllowed(pointer, "2.0.0", "2.0.1") {
		t.Fatal("retried failed release")
	}
	if !releaseCandidateAllowed(pointer, "2.0.0", "2.0.2") {
		t.Fatal("rejected newer release")
	}
}

func releaseTestZIP(t *testing.T, entries map[string]string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "test.zip")
	f, e := os.Create(path)
	if e != nil {
		t.Fatal(e)
	}
	z := zip.NewWriter(f)
	for name, text := range entries {
		w, e := z.Create(name)
		if e != nil {
			t.Fatal(e)
		}
		w.Write([]byte(text))
	}
	if e = z.Close(); e != nil {
		t.Fatal(e)
	}
	f.Close()
	return path
}
func TestNativeReleaseExtractionStaysWithinVersion(t *testing.T) {
	root := t.TempDir()
	archive := releaseTestZIP(t, map[string]string{"remote-code.exe": "bootstrap", "releases/1.2.3/remote-code.exe": "server", "data/config.json": "must not overwrite"})
	if e := releaseExtract(archive, root, "releases/1.2.3/"); e != nil {
		t.Fatal(e)
	}
	b, e := os.ReadFile(filepath.Join(root, "releases", "1.2.3", "remote-code.exe"))
	if e != nil || string(b) != "server" {
		t.Fatal("version executable missing")
	}
	for _, name := range []string{"remote-code.exe", "data/config.json"} {
		if _, e = os.Stat(filepath.Join(root, name)); !os.IsNotExist(e) {
			t.Errorf("extracted unapproved path %s", name)
		}
	}
}
func TestNativeReleaseExtractionRejectsUnsafeZIP(t *testing.T) {
	for _, name := range []string{"../escaped", "releases/1.2.3/../../escaped", `releases\1.2.3\server.exe`, "C:/escaped", "/escaped", "releases/1.2.3/./server.exe"} {
		t.Run(name, func(t *testing.T) {
			archive := releaseTestZIP(t, map[string]string{name: "bad"})
			if e := releaseExtract(archive, t.TempDir(), "releases/1.2.3/"); e == nil {
				t.Fatal("accepted unsafe path")
			}
		})
	}
	archive := releaseTestZIP(t, map[string]string{"releases/1.2.3/A": "first", "releases/1.2.3/a": "second"})
	if releaseExtract(archive, t.TempDir(), "releases/1.2.3/") == nil {
		t.Fatal("accepted case-duplicate file")
	}
}

func TestAPKSnapshotChecksDigest(t *testing.T) {
	dir := t.TempDir()
	content := []byte("test APK bytes")
	manifest := M{"schemaVersion": 1, "repository": releaseRepository, "platform": "android", "applicationId": releaseApplicationID, "versionName": "1.0.0", "versionCode": 1, "apk": M{"name": "remote-code-v1.0.0.apk", "bytes": len(content), "sha256": releaseHash(content)}}
	path := filepath.Join(dir, "remote-code-v1.0.0.apk")
	os.WriteFile(path, content, 0600)
	if _, e := releaseSnapshot(dir, manifest); e != nil {
		t.Fatal(e)
	}
	os.WriteFile(path, bytes.Repeat([]byte{'x'}, len(content)), 0600)
	if _, e := releaseSnapshot(dir, manifest); e == nil {
		t.Fatal("accepted modified APK")
	}
}

func TestAPKDownloadUsesSourceReleaseBuild(t *testing.T) {
	root := t.TempDir()
	t.Chdir(root)
	t.Setenv("REMOTE_CODE_APK_MANIFEST", "")
	if err := os.WriteFile(filepath.Join(root, "go.mod"), []byte("module github.com/"+releaseRepository+"\n"), 0600); err != nil {
		t.Fatal(err)
	}
	a := testApp(t)
	path := platformDownloadPath(a.Config.Token)
	readAPK := func(content string) {
		t.Helper()
		response := testRequest(a, http.MethodGet, path, "")
		if response.Code != http.StatusOK || response.Body.String() != content || response.Header().Get("X-APK-SHA256") != releaseHash([]byte(content)) {
			t.Fatalf("wrong APK download response: status=%d size=%d", response.Code, response.Body.Len())
		}
	}
	if response := testRequest(a, http.MethodGet, path, ""); response.Code != http.StatusServiceUnavailable {
		t.Fatalf("missing APK status = %d", response.Code)
	}
	dir := filepath.Join(root, "android", "app", "build", "outputs", "apk", "release")
	if err := os.MkdirAll(dir, 0700); err != nil {
		t.Fatal(err)
	}
	writeAPK := func(content string) {
		t.Helper()
		if err := os.WriteFile(filepath.Join(dir, "app-release.apk"), []byte(content), 0600); err != nil {
			t.Fatal(err)
		}
	}
	metadata := M{"applicationId": releaseApplicationID, "variantName": "release", "elements": []M{{"versionName": "1.2.3", "versionCode": 53, "outputFile": "app-release.apk"}}}
	writeMetadata := func() {
		t.Helper()
		raw, err := json.Marshal(metadata)
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, "output-metadata.json"), raw, 0600); err != nil {
			t.Fatal(err)
		}
	}
	writeMetadata()
	writeAPK("first release")
	readAPK("first release")
	writeAPK("rebuilt release with the same version")
	readAPK("rebuilt release with the same version")
	response := testRequest(a, http.MethodGet, "/api/app", "")
	var status M
	if err := json.Unmarshal(response.Body.Bytes(), &status); err != nil || !B(status, "available") || N(status, "versionCode") != 53 || S(status, "path") != "" {
		t.Fatalf("wrong app metadata response: %s", response.Body)
	}
	metadata["applicationId"] = "another.application"
	writeMetadata()
	if response := testRequest(a, http.MethodGet, path, ""); response.Code != http.StatusServiceUnavailable {
		t.Fatalf("invalid metadata APK status = %d", response.Code)
	}
}

func TestAPKDownloadRevalidatesNativeAndLegacyCache(t *testing.T) {
	t.Chdir(t.TempDir())
	t.Setenv("REMOTE_CODE_APK_MANIFEST", "")
	a := testApp(t)
	downloadPath := platformDownloadPath(a.Config.Token)
	dir := filepath.Join(a.DataDir, "app-updates")
	if err := os.MkdirAll(dir, 0700); err != nil {
		t.Fatal(err)
	}
	makeAPK := func(version string, code int, content string, legacy bool) string {
		t.Helper()
		name := "remote-code-v" + version + ".apk"
		hash := releaseHash([]byte(content))
		manifest := M{"schemaVersion": 1, "repository": releaseRepository, "platform": "android", "applicationId": releaseApplicationID, "versionName": version, "versionCode": code, "apk": M{"name": name, "bytes": len(content), "sha256": hash}}
		pointer := "remote-code-android.json"
		if legacy {
			name, pointer, manifest = hash+".apk", "current.json", M{"manifest": manifest}
		}
		path := filepath.Join(dir, name)
		if err := writeReleaseAPK(path, []byte(content)); err != nil {
			t.Fatal(err)
		}
		if err := atomicJSON(filepath.Join(dir, pointer), manifest); err != nil {
			t.Fatal(err)
		}
		return path
	}
	check := func(content string) {
		t.Helper()
		apk := testRequest(a, http.MethodGet, downloadPath, "")
		status := testRequest(a, http.MethodGet, "/api/app", "")
		var meta M
		if err := json.Unmarshal(status.Body.Bytes(), &meta); err != nil {
			t.Fatal(err)
		}
		if content == "" {
			if B(meta, "available") || apk.Code != http.StatusServiceUnavailable {
				t.Fatalf("advertised unavailable APK: %s, HTTP %d", status.Body, apk.Code)
			}
			return
		}
		if !B(meta, "available") || S(meta, "sha256") != releaseHash([]byte(content)) || apk.Code != http.StatusOK || apk.Body.String() != content {
			t.Fatalf("wrong APK fallback: metadata %s, HTTP %d, APK %q", status.Body, apk.Code, apk.Body.String())
		}
	}
	legacy := makeAPK("1.0.0", 50, "legacy", true)
	check("legacy")
	native := makeAPK("1.0.1", 51, "standalone latest", false)
	check("standalone latest")
	if err := writeReleaseAPK(native, []byte("standalone broken")); err != nil {
		t.Fatal(err)
	}
	check("legacy")
	if err := writeReleaseAPK(native, []byte("standalone latest")); err != nil {
		t.Fatal(err)
	}
	check("standalone latest")
	if err := os.Remove(native); err != nil {
		t.Fatal(err)
	}
	check("legacy")
	if err := os.Remove(legacy); err != nil {
		t.Fatal(err)
	}
	check("")
}

func TestPortableServerArgsDefaultTunnel(t *testing.T) {
	t.Setenv("PORT", "")
	data := t.TempDir()
	base := []string{"--serve", "--data", data}
	for _, tc := range []struct{ args, want []string }{
		{nil, append(slices.Clone(base), "--tunnel")},
		{[]string{"--tunnel", "--no-tunnel"}, append(slices.Clone(base), "--tunnel", "--no-tunnel")},
		{[]string{"--data", "--no-tunnel", "--no-ui"}, append(slices.Clone(base), "--no-ui", "--tunnel")},
	} {
		if got := portableServerArgs(data, tc.args); !slices.Equal(got, tc.want) {
			t.Fatalf("args %v, got %v, want %v", tc.args, got, tc.want)
		}
	}
	if err := atomicJSON(filepath.Join(data, "config.json"), M{"publicUrl": "https://remote.example"}); err != nil {
		t.Fatal(err)
	}
	if got := portableServerArgs(data, nil); !slices.Equal(got, base) {
		t.Fatalf("configured public URL was replaced: %v", got)
	}
}

func TestPortableSupervisorRetriesCrashAndHonorsStop(t *testing.T) {
	crashed := portableChildExit{err: errors.New("test crash"), started: true}
	args := []string{"--new-url", "--tunnel", "--no-ui"}
	attempts := 0
	launch := func(got []string) portableChildExit {
		attempts++
		if !slices.Equal(got, []string{"--tunnel", "--no-ui"}) {
			t.Fatalf("restart args = %v", got)
		}
		return portableChildExit{started: true}
	}
	if err := supervisePortableChild(t.Context(), args, crashed, 0, launch); err != nil || attempts != 1 {
		t.Fatalf("relaunch did not stop after success: got %d, %v", attempts, err)
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if err := supervisePortableChild(ctx, args, crashed, 0, launch); err != nil || attempts != 1 {
		t.Fatalf("relaunch did not honor canceled context: got %d, %v", attempts, err)
	}
	attempts = 0
	alwaysCrash := func([]string) portableChildExit { attempts++; return crashed }
	if err := supervisePortableChild(t.Context(), args, crashed, 0, alwaysCrash); !errors.Is(err, crashed.err) || attempts != 2 {
		t.Fatalf("relaunch did not cap repeated crashes: got %d, %v", attempts, err)
	}
	attempts = 0
	requested := portableChildExit{err: ErrPortableRestart, started: true}
	if err := supervisePortableChild(t.Context(), args, requested, 0, launch); !errors.Is(err, ErrPortableRestart) || attempts != 0 {
		t.Fatalf("restart did not return control to the launcher: %d retries, %v", attempts, err)
	}
}

func TestLegacyPortableRootOnlyMatchesTheActiveChild(t *testing.T) {
	root := t.TempDir()
	exe := filepath.Join(root, "releases", "1.2.3", "remote-code.exe")
	if err := os.MkdirAll(filepath.Dir(exe), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(exe, nil, 0600); err != nil {
		t.Fatal(err)
	}
	if err := atomicJSON(filepath.Join(root, "portable.json"), M{"launcherProtocol": 3}); err != nil {
		t.Fatal(err)
	}
	if err := atomicJSON(filepath.Join(root, "current.json"), M{"version": "1.2.3"}); err != nil {
		t.Fatal(err)
	}
	canonicalRoot, err := filepath.EvalSymlinks(root)
	if err != nil {
		t.Fatal(err)
	}
	if got := legacyPortableRoot(exe, "instance"); got != canonicalRoot {
		t.Fatalf("legacy portable root = %q, want %q", got, canonicalRoot)
	}
	if got := legacyPortableRoot(exe, ""); got != "" {
		t.Fatalf("standalone process recognized as a portable child: %q", got)
	}
	if got := legacyPortableRoot(filepath.Join(root, "releases", "1.2.2", "remote-code.exe"), "instance"); got != "" {
		t.Fatalf("inactive executable recognized as a portable child: %q", got)
	}
}

func TestAPKDownloadKeyIsSeparatedFromServerToken(t *testing.T) {
	a := platformDownloadPath("secret-a")
	b := platformDownloadPath("secret-b")
	if hmac.Equal([]byte(a), []byte(b)) || strings.Contains(a, "secret-a") || !strings.HasSuffix(a, "/remote-code.apk") {
		t.Fatal("bad APK key derivation")
	}
}

func TestInstanceProtocolRejectsChangedIdentity(t *testing.T) {
	endpoint := platformPipeName("test", strings.ReplaceAll(NewID(), "-", ""))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	identity := M{"kind": "server", "resource": t.TempDir(), "pid": os.Getpid(), "instanceId": NewID(), "version": "1.0.0", "canStop": true}
	l, e := platformListenInstance(endpoint, identity, cancel)
	if e != nil {
		t.Fatal(e)
	}
	defer l.Close()
	response := platformInstanceRequest(endpoint, "inspect", nil)
	if !B(response, "ok") {
		t.Fatal("inspect failed")
	}
	wrong := clone(identity)
	wrong["instanceId"] = NewID()
	if B(platformInstanceRequest(endpoint, "stop", wrong), "ok") {
		t.Fatal("stopped changed instance")
	}
	select {
	case <-ctx.Done():
		t.Fatal("stop callback invoked")
	default:
	}
	if !B(platformInstanceRequest(endpoint, "stop", identity), "ok") {
		t.Fatal("owned stop failed")
	}
	select {
	case <-ctx.Done():
	case <-time.After(time.Second):
		t.Fatal("stop callback missing")
	}
}

func TestTerminalScreenPreservesANSIAndScrollback(t *testing.T) {
	term := &nativeTerminal{info: M{}, screen: vt10x.New(vt10x.WithSize(20, 5))}
	term.screen.Write([]byte("\x1b[31mred\x1b[0m plain"))
	frame := terminalScreen(term)
	raw, _ := json.Marshal(frame)
	if !bytes.Contains(raw, []byte(`"red",1,-1,0`)) {
		t.Fatalf("ANSI color missing: %s", raw)
	}
	if terminalColor(vt10x.DefaultFG) != -1 || terminalColor(vt10x.DefaultBG) != -1 {
		t.Fatal("default color interpreted as RGB")
	}
	line := func(s string) any { return []any{[]any{s}} }
	previous := []any{line("one"), line("two"), line("three"), line("four")}
	next := []any{line("two"), line("three"), line("four"), line("five")}
	var history []any
	terminalRemember(&history, previous, next)
	if len(history) != 1 || terminalPlain(history[0]) != "one" {
		t.Fatal("scrollback lost")
	}
}
