package host

import (
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestGitProcessOutputIsBounded(t *testing.T) {
	// Pipe copies can use io.ReaderFrom without invoking Write if the
	// destination exposes an embedded bytes.Buffer.
	out := &gitBuffer{max: 4}
	n, err := io.Copy(out, io.LimitReader(strings.NewReader("0123456789"), 10))
	if err != nil || n != 10 || !out.overflow || out.data.String() != "0123" {
		t.Fatalf("stored %q, overflow %v, read %d, error %v", out.data.String(), out.overflow, n, err)
	}
}

func TestGitStatusCacheConcurrentReadsAndInvalidation(t *testing.T) {
	cache := newGitStatusCache(2)
	started, release, firstDone := make(chan struct{}), make(chan struct{}), make(chan struct{})
	go func() {
		defer close(firstDone)
		_, _ = cache.read("slow", false, func() (M, error) {
			close(started)
			<-release
			return M{"head": "old"}, nil
		})
	}()
	<-started
	finish := sync.OnceFunc(func() { close(release); <-firstDone })
	defer finish()
	old := cache.lookup("slow", true)
	if old == nil {
		t.Fatal("a refresh should join the in-flight repository read")
	}
	read := func(key, head string, force bool) {
		t.Helper()
		done := make(chan M, 1)
		go func() {
			value, _ := cache.read(key, force, func() (M, error) { return M{"head": head}, nil })
			done <- value
		}()
		select {
		case value := <-done:
			if S(value, "head") != head {
				t.Fatalf("got %v for %s, want %s", value, key, head)
			}
		case <-time.After(5 * time.Second):
			t.Fatalf("read of %s blocked behind unrelated or invalidated snapshot", key)
		}
	}
	read("other", "independent", false)
	cache.invalidate("slow")
	read("slow", "after mutation", false)
	finish()
	if v, _ := old.wait(); S(v, "head") != "old" {
		t.Fatalf("pending response was modified: %v", v)
	}
	got, err := cache.read("slow", false, func() (M, error) { return nil, errors.New("cache miss") })
	if err != nil || S(got, "head") != "after mutation" {
		t.Fatalf("obsolete read replaced the post-mutation status: %v %v", got, err)
	}
	read("slow", "manual refresh", true)
}

func TestGitCountsAllUntrackedFilesWhenLineStatsArePartial(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git is not installed")
	}
	root := t.TempDir()
	if _, err := gitRun(root, "", 12*time.Second, "init"); err != nil {
		t.Fatal(err)
	}
	for i := range 9 {
		name := filepath.Join(root, fmt.Sprintf("untracked-%d.dat", i))
		if err := os.WriteFile(name, nil, 0644); err != nil {
			t.Fatal(err)
		}
		if err := os.Truncate(name, 256*1024); err != nil {
			t.Fatal(err)
		}
	}
	status, err := gitStatus(root, true)
	if err != nil {
		t.Fatal(err)
	}
	if N(status, "untrackedFiles") != 9 || N(status, "changedFiles") != 9 || !B(status, "lineStatsPartial") {
		t.Fatalf("partial line scan lost files: %v", status)
	}
}

func BenchmarkGitStatusPoll(b *testing.B) {
	if _, err := exec.LookPath("git"); err != nil {
		b.Skip("git is not installed")
	}
	root := b.TempDir()
	if _, err := gitRun(root, "", 12*time.Second, "init"); err != nil {
		b.Fatal(err)
	}
	for _, mode := range []struct {
		name  string
		force bool
	}{{"fresh", true}, {"cached", false}} {
		b.Run(mode.name, func(b *testing.B) {
			if _, err := gitStatus(root, true); err != nil {
				b.Fatal(err)
			}
			for b.Loop() {
				if _, err := gitStatus(root, mode.force); err != nil {
					b.Fatal(err)
				}
			}
		})
	}
}

func TestGitStatusV2RenameAndNumstat(t *testing.T) {
	text := "# branch.oid abc\x00# branch.head main\x00# branch.upstream origin/main\x00# branch.ab +2 -3\x002 R. N... 100644 100644 100644 aaa bbb R100 destination name.txt\x00source name.txt\x00? untracked.txt\x00"
	status := gitParseStatus(text, "root")
	files := gitFiles(status)
	if len(files) != 2 || S(files[0], "path") != "destination name.txt" || S(files[0], "originalPath") != "source name.txt" || N(status, "ahead") != 2 || N(status, "behind") != 3 {
		t.Fatalf("bad status %#v", status)
	}
	stats := gitNumstat("2\t3\t\x00old\x00new\x00-\t-\tbinary\x00")
	if N(stats["new"], "additions") != 2 || !B(stats["binary"], "binary") {
		t.Fatalf("bad numstat %#v", stats)
	}
}
func TestGitNativeWorkflowAndLiteralPathspec(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git is not installed")
	}
	root := t.TempDir()
	run := func(args ...string) {
		t.Helper()
		if _, err := gitRun(root, "", 12*time.Second, args...); err != nil {
			t.Fatal(err)
		}
	}
	run("init", "--initial-branch=main")
	run("config", "user.name", "Test")
	run("config", "user.email", "test@example.invalid")
	name := "literal[1].txt"
	if err := os.WriteFile(filepath.Join(root, name), []byte("first\nsecond\n"), 0644); err != nil {
		t.Fatal(err)
	}
	status, err := gitStatus(root, true)
	if err != nil {
		t.Fatal(err)
	}
	if N(status, "changedFiles") != 1 || N(status, "additions") != 2 {
		t.Fatalf("bad untracked status %#v", status)
	}
	if _, err = gitMutation(root, "stage", M{"paths": []any{name}}); err != nil {
		t.Fatal(err)
	}
	status, err = gitStatus(root, false)
	if err != nil || N(status, "stagedFiles") != 1 {
		t.Fatalf("staging failed %#v %v", status, err)
	}
	if _, err = gitMutation(root, "unstage", M{"paths": []any{name}}); err != nil {
		t.Fatal(err)
	}
	status, err = gitStatus(root, false)
	if err != nil || N(status, "stagedFiles") != 0 {
		t.Fatalf("initial unstage failed %#v %v", status, err)
	}
	if _, err = gitMutation(root, "stage", M{"paths": []any{name}}); err != nil {
		t.Fatal(err)
	}
	if _, err = gitMutation(root, "commit", M{"message": "Initial files"}); err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(root, name), []byte("changed\nsecond\n"), 0644); err != nil {
		t.Fatal(err)
	}
	diff, err := gitDiff(root, name, false)
	if err != nil {
		t.Fatal(err)
	}
	if S(diff, "text") == "" {
		t.Fatal("diff missing")
	}
	logs, err := gitLog(root)
	if err != nil || len(logs) != 1 {
		t.Fatalf("log failed %v %#v", err, logs)
	}
	if _, err = gitMutation(root, "branch", M{"name": "feature/test"}); err != nil {
		t.Fatal(err)
	}
	branches, err := gitBranches(root)
	if err != nil || len(branches) != 2 {
		t.Fatalf("branches failed %v %#v", err, branches)
	}
}
func TestGitRejectsUnsafePaths(t *testing.T) {
	for _, path := range []string{"../outside", "C:\\outside", ".git/config", "sub/../file", "/absolute", "."} {
		if err := gitPath(path); err == nil {
			t.Fatalf("unsafe path accepted: %s", path)
		}
	}
}
