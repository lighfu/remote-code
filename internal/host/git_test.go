package host

import (
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

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
	status, err = gitStatus(root, true)
	if err != nil || N(status, "stagedFiles") != 1 {
		t.Fatalf("staging failed %#v %v", status, err)
	}
	if _, err = gitMutation(root, "unstage", M{"paths": []any{name}}); err != nil {
		t.Fatal(err)
	}
	status, err = gitStatus(root, true)
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
