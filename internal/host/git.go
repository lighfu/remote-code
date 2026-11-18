package host

import (
	"bytes"
	"context"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

var gitWrites sync.Mutex
var gitSnapshots = newGitStatusCache(2)
var gitBase = []string{"--no-pager", "--literal-pathspecs", "-c", "color.ui=false", "-c", "core.quotepath=false", "-c", "core.fsmonitor=false", "-c", "diff.renameLimit=200", "-c", "status.renameLimit=200"}

type gitBuffer struct {
	data     bytes.Buffer
	max      int
	overflow bool
}

func (b *gitBuffer) Write(p []byte) (int, error) {
	n := len(p)
	remaining := b.max - b.data.Len()
	if remaining > 0 {
		if remaining > n {
			remaining = n
		}
		_, _ = b.data.Write(p[:remaining])
	}
	if n > remaining {
		b.overflow = true
	}
	return n, nil
}
func gitRun(cwd, input string, timeout time.Duration, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, "git", append(append([]string{}, gitBase...), args...)...)
	cmd.Dir = cwd
	fileHideCommand(cmd)
	env := []string{}
	removed := map[string]bool{"GIT_DIR": true, "GIT_WORK_TREE": true, "GIT_INDEX_FILE": true, "GIT_OBJECT_DIRECTORY": true, "GIT_ALTERNATE_OBJECT_DIRECTORIES": true, "GIT_COMMON_DIR": true, "GIT_PREFIX": true, "GIT_TERMINAL_PROMPT": true, "GCM_INTERACTIVE": true, "GIT_OPTIONAL_LOCKS": true, "GIT_PAGER": true, "LC_ALL": true}
	for _, v := range os.Environ() {
		key := strings.SplitN(v, "=", 2)[0]
		if !removed[key] {
			env = append(env, v)
		}
	}
	cmd.Env = append(env, "GIT_TERMINAL_PROMPT=0", "GCM_INTERACTIVE=Never", "GIT_OPTIONAL_LOCKS=0", "GIT_PAGER=cat", "LC_ALL=C")
	if os.Getenv("GIT_SSH_COMMAND") == "" {
		cmd.Env = append(cmd.Env, "GIT_SSH_COMMAND=ssh -oBatchMode=yes")
	}
	out := &gitBuffer{max: 8 * 1024 * 1024}
	stderr := &gitBuffer{max: 64 * 1024}
	cmd.Stdout = out
	cmd.Stderr = stderr
	cmd.Stdin = strings.NewReader(input)
	err := cmd.Run()
	if ctx.Err() != nil {
		return "", fileProblem(504, "Git operation timed out. Please check the results before trying again.")
	}
	if out.overflow {
		return "", fileProblem(413, "Git response too large. Please reduce the scope of changes.")
	}
	if err != nil {
		if _, ok := err.(*exec.Error); ok {
			return "", fileProblem(503, "Git not found. Please install Git on your PC.")
		}
		message := strings.TrimSpace(stderr.data.String())
		if message == "" {
			message = strings.TrimSpace(out.data.String())
		}
		if message == "" {
			message = err.Error()
		}
		message = gitSanitize(message)
		return "", fileProblem(409, message)
	}
	return out.data.String(), nil
}

var gitCredentials = regexp.MustCompile(`(https?://)[^\s/@]+@`)
var gitANSI = regexp.MustCompile("\x1b\\[[0-9;]*[A-Za-z]")
var gitDrivePrefix = regexp.MustCompile(`^[A-Za-z]:`)

func gitSanitize(message string) string {
	message = gitCredentials.ReplaceAllString(gitANSI.ReplaceAllString(message, ""), "${1}***@")
	if len(message) > 4000 {
		message = message[:4000]
	}
	return message
}
func gitRoot(cwd string) (string, error) {
	if st, err := os.Stat(cwd); err != nil || !st.IsDir() {
		return "", fileProblem(404, "Workspace folder not found.")
	}
	out, err := gitRun(cwd, "", 12*time.Second, "rev-parse", "--show-toplevel")
	if err != nil {
		if strings.Contains(err.Error(), "not a git repository") || strings.Contains(err.Error(), "must be run in a work tree") {
			return "", nil
		}
		return "", err
	}
	return filepath.EvalSymlinks(strings.TrimRight(out, "\r\n"))
}
func gitEmpty() M {
	return M{"isRepo": false, "ahead": 0, "behind": 0, "files": []M{}, "changedFiles": 0, "additions": 0, "deletions": 0, "stagedFiles": 0, "unstagedFiles": 0, "untrackedFiles": 0, "conflictedFiles": 0, "lineStatsPartial": false, "remotes": []M{}, "at": time.Now().UnixMilli()}
}
func gitParseStatus(text, root string) M {
	result := gitEmpty()
	result["isRepo"] = true
	result["root"] = root
	result["upstream"] = nil
	result["detached"] = false
	entries := strings.Split(text, "\x00")
	files := []M{}
	for i := 0; i < len(entries); i++ {
		entry := entries[i]
		switch {
		case strings.HasPrefix(entry, "# branch.head "):
			name := strings.TrimPrefix(entry, "# branch.head ")
			result["detached"] = name == "(detached)"
			if name != "(detached)" {
				result["branch"] = name
			}
		case strings.HasPrefix(entry, "# branch.oid "):
			head := strings.TrimPrefix(entry, "# branch.oid ")
			if head != "(initial)" {
				result["head"] = head
			}
		case strings.HasPrefix(entry, "# branch.upstream "):
			result["upstream"] = strings.TrimPrefix(entry, "# branch.upstream ")
		case strings.HasPrefix(entry, "# branch.ab "):
			var ahead, behind int
			fmt.Sscanf(entry, "# branch.ab +%d -%d", &ahead, &behind)
			result["ahead"] = ahead
			result["behind"] = behind
		case strings.HasPrefix(entry, "? "):
			files = append(files, M{"path": entry[2:], "indexStatus": "?", "worktreeStatus": "?", "staged": false, "unstaged": false, "untracked": true, "conflict": false, "additions": 0, "deletions": 0, "binary": false})
		case len(entry) > 2 && (entry[0] == '1' || entry[0] == '2' || entry[0] == 'u'):
			count := 9
			if entry[0] == '2' {
				count = 10
			} else if entry[0] == 'u' {
				count = 11
			}
			fields := strings.SplitN(entry, " ", count)
			if len(fields) != count || len(fields[1]) != 2 {
				continue
			}
			status := fields[1]
			file := M{"path": fields[count-1], "indexStatus": status[:1], "worktreeStatus": status[1:], "staged": status[0] != '.', "unstaged": status[1] != '.', "untracked": false, "conflict": entry[0] == 'u', "additions": 0, "deletions": 0, "binary": false}
			if entry[0] == '2' && i+1 < len(entries) {
				i++
				file["originalPath"] = entries[i]
			}
			files = append(files, file)
		}
	}
	result["files"] = files
	return result
}
func gitNumstat(text string) map[string]M {
	result := map[string]M{}
	records := strings.Split(text, "\x00")
	for i := 0; i < len(records); i++ {
		parts := strings.SplitN(records[i], "\t", 3)
		if len(parts) != 3 {
			continue
		}
		name := parts[2]
		if name == "" && i+2 < len(records) {
			i += 2
			name = records[i]
		}
		if name == "" {
			continue
		}
		v := result[name]
		if v == nil {
			v = M{"additions": 0, "deletions": 0, "binary": false}
		}
		a, _ := strconv.Atoi(parts[0])
		d, _ := strconv.Atoi(parts[1])
		v["additions"] = int(N(v, "additions")) + a
		v["deletions"] = int(N(v, "deletions")) + d
		v["binary"] = B(v, "binary") || parts[0] == "-" || parts[1] == "-"
		result[name] = v
	}
	return result
}
func gitFiles(status M) []M {
	if v, ok := status["files"].([]M); ok {
		return v
	}
	return []M{}
}
func gitWorking(root, path string, limit int) ([]byte, bool, error) {
	if err := gitPath(path); err != nil {
		return nil, false, err
	}
	p := filepath.Join(root, filepath.FromSlash(path))
	st, err := os.Lstat(p)
	if err != nil {
		return nil, false, err
	}
	if !st.Mode().IsRegular() {
		return nil, false, fileProblem(400, "Only regular files can be displayed")
	}
	real, err := filepath.EvalSymlinks(p)
	if err != nil {
		return nil, false, err
	}
	if !fileInside(root, real) {
		return nil, false, fileProblem(403, "Files outside the repository cannot be displayed")
	}
	f, err := os.Open(p)
	if err != nil {
		return nil, false, err
	}
	defer f.Close()
	actual, err := f.Stat()
	if err != nil {
		return nil, false, err
	}
	if !os.SameFile(st, actual) {
		return nil, false, fileProblem(409, "The file changed while reading")
	}
	data := make([]byte, min(int(actual.Size()), limit))
	n, err := f.Read(data)
	if err != nil && n == 0 && actual.Size() > 0 {
		return nil, false, err
	}
	return data[:n], actual.Size() > int64(limit), nil
}
func gitLines(data []byte) int {
	count := bytes.Count(data, []byte{'\n'})
	if len(data) > 0 && data[len(data)-1] != '\n' {
		count++
	}
	return count
}
func gitStatus(cwd string, force bool) (M, error) {
	// Most sessions start at the worktree root. A poll there can reuse the
	// snapshot (or pending read) without starting another Git process.
	if pending := gitSnapshots.lookup(fileKey(filepath.Clean(cwd)), force); pending != nil {
		return pending.wait()
	}
	root, err := gitRoot(cwd)
	if err != nil {
		return nil, err
	}
	if root == "" {
		return gitEmpty(), nil
	}
	return gitSnapshots.read(fileKey(root), force, func() (M, error) { return gitReadStatus(root) })
}

func gitReadPorcelain(root string) (M, error) {
	out, err := gitRun(root, "", 12*time.Second, "status", "--porcelain=v2", "--branch", "-z", "--untracked-files=all")
	if err != nil {
		return nil, err
	}
	status := gitParseStatus(out, root)
	files := gitFiles(status)
	if len(files) > 5000 {
		return nil, fileProblem(413, "Changed files exceed 5000. Please reduce the scope of changes.")
	}
	var staged, unstaged, untracked, conflicted int
	for _, file := range files {
		if B(file, "staged") {
			staged++
		}
		if B(file, "unstaged") {
			unstaged++
		}
		if B(file, "untracked") {
			untracked++
		}
		if B(file, "conflict") {
			conflicted++
		}
	}
	status["changedFiles"] = len(files)
	status["stagedFiles"], status["unstagedFiles"] = staged, unstaged
	status["untrackedFiles"], status["conflictedFiles"] = untracked, conflicted
	return status, nil
}

func gitReadStatus(root string) (M, error) {
	status, err := gitReadPorcelain(root)
	if err != nil {
		return nil, err
	}
	files := gitFiles(status)
	stats := []map[string]M{}
	for _, cached := range []bool{true, false} {
		if (cached && N(status, "stagedFiles") == 0) || (!cached && N(status, "unstagedFiles") == 0) {
			continue
		}
		args := []string{"diff", "--numstat", "-z", "--no-ext-diff", "--no-textconv", "--find-renames"}
		if cached {
			args = append(args, "--cached")
		}
		text, err := gitRun(root, "", 12*time.Second, args...)
		if err != nil {
			return nil, err
		}
		stats = append(stats, gitNumstat(text))
	}
	budget, scanned := 2*1024*1024, 0
	for _, file := range files {
		for _, stat := range stats {
			if v := stat[S(file, "path")]; v != nil {
				file["additions"] = int(N(file, "additions") + N(v, "additions"))
				file["deletions"] = int(N(file, "deletions") + N(v, "deletions"))
				file["binary"] = B(file, "binary") || B(v, "binary")
			}
		}
		if B(file, "untracked") {
			if budget <= 0 || scanned >= 256 {
				status["lineStatsPartial"] = true
				continue
			}
			scanned++
			content, truncated, err := gitWorking(root, S(file, "path"), min(256*1024, budget))
			if err != nil {
				status["lineStatsPartial"] = true
				continue
			}
			budget -= len(content)
			file["binary"] = bytes.IndexByte(content, 0) >= 0
			if truncated {
				status["lineStatsPartial"] = true
			} else if !B(file, "binary") {
				file["additions"] = gitLines(content)
			}
		}
		if B(file, "conflict") || B(file, "binary") {
			status["lineStatsPartial"] = true
		}
		for _, key := range []string{"additions", "deletions"} {
			status[key] = int(N(status, key) + N(file, key))
		}
	}
	remotes, err := gitRemotes(root)
	if err != nil {
		return nil, err
	}
	status["remotes"] = remotes
	return status, nil
}

func gitRemotes(root string) ([]M, error) {
	text, err := gitRun(root, "", 12*time.Second, "remote", "-v")
	if err != nil {
		return nil, err
	}
	remotes := []M{}
	seen := map[string]int{}
	for _, line := range strings.Split(text, "\n") {
		parts := strings.Fields(line)
		if len(parts) < 3 {
			continue
		}
		remote := M{"name": parts[0], "url": gitRemoteURL(parts[1])}
		if at, ok := seen[parts[0]]; ok {
			if parts[2] == "(push)" {
				remotes[at] = remote
			}
		} else {
			seen[parts[0]] = len(remotes)
			remotes = append(remotes, remote)
		}
	}
	return remotes, nil
}
func gitRemoteURL(raw string) string {
	if u, err := url.Parse(raw); err == nil && (u.Scheme == "https" || u.Scheme == "http") {
		u.User = nil
		u.RawQuery = ""
		u.Fragment = ""
		return u.String()
	}
	return raw
}
func gitPath(path string) error {
	if path == "" || path == "." || len(path) > 4096 || strings.ContainsRune(path, 0) || strings.HasPrefix(path, "/") || strings.HasPrefix(path, "\\") || filepath.IsAbs(path) || gitDrivePrefix.MatchString(path) {
		return fileProblem(400, "Please specify a file within the repository.")
	}
	for _, part := range strings.FieldsFunc(path, func(r rune) bool { return r == '/' || r == '\\' }) {
		if part == ".." || strings.EqualFold(part, ".git") {
			return fileProblem(400, "Please specify a file within the repository.")
		}
	}
	return nil
}
func gitDiff(cwd, path string, staged bool) (M, error) {
	if err := gitPath(path); err != nil {
		return nil, err
	}
	root, err := gitRoot(cwd)
	if err != nil {
		return nil, err
	}
	if root == "" {
		return nil, fileProblem(404, "No modified files found. Please refresh and check again.")
	}
	status, err := gitReadPorcelain(root)
	if err != nil {
		return nil, err
	}
	var file M
	for _, v := range gitFiles(status) {
		if S(v, "path") == path {
			file = v
			break
		}
	}
	if file == nil {
		return nil, fileProblem(404, "No modified files found. Please refresh and check again.")
	}
	result := M{"path": path, "staged": staged, "text": "", "binary": false, "truncated": false}
	var text string
	if B(file, "untracked") && !staged {
		content, truncated, err := gitWorking(root, path, 256*1024)
		if err != nil {
			return nil, err
		}
		result["truncated"] = truncated
		result["binary"] = bytes.IndexByte(content, 0) >= 0
		if B(result, "binary") {
			result["text"] = "Binary file."
			return result, nil
		}
		lines := strings.Split(string(content), "\n")
		for i := range lines {
			if i < len(lines)-1 || lines[i] != "" {
				lines[i] = "+" + lines[i]
			}
		}
		text = fmt.Sprintf("diff --git a/%s b/%s\nnew file\n--- /dev/null\n+++ b/%s\n@@ -0,0 +1,%d @@\n%s", path, path, path, gitLines(content), strings.Join(lines, "\n"))
	} else {
		args := []string{"diff"}
		if staged {
			args = append(args, "--cached")
		}
		args = append(args, "--no-ext-diff", "--no-textconv", "--no-color", "--find-renames", "--", path)
		if original := S(file, "originalPath"); original != "" {
			args = append(args, original)
		}
		text, err = gitRun(root, "", 12*time.Second, args...)
		if err != nil {
			return nil, err
		}
		result["binary"] = strings.Contains(text, "Binary files ") || strings.Contains(text, "GIT binary patch")
	}
	if len(text) > 512*1024 {
		text = text[:512*1024]
		result["truncated"] = true
	}
	rows := strings.Split(text, "\n")
	if len(rows) > 2000 {
		rows = rows[:2000]
		result["truncated"] = true
	}
	for i, row := range rows {
		runes := []rune(row)
		if len(runes) > 2000 {
			rows[i] = string(runes[:1999]) + "…"
			result["truncated"] = true
		}
	}
	result["text"] = strings.Join(rows, "\n")
	return result, nil
}
func gitLog(cwd string) ([]M, error) {
	root, err := gitRoot(cwd)
	if err != nil {
		return nil, err
	}
	if root == "" {
		return nil, fileProblem(404, "This folder is not managed by Git.")
	}
	status, err := gitStatus(root, false)
	if err != nil {
		return nil, err
	}
	result := []M{}
	if S(status, "head") == "" {
		return result, nil
	}
	text, err := gitRun(root, "", 12*time.Second, "log", "-30", "--format=%H%x00%h%x00%s%x00%an%x00%aI%x00", "--no-show-signature")
	if err != nil {
		return nil, err
	}
	fields := strings.Split(text, "\x00")
	for i := 0; i+4 < len(fields); i += 5 {
		hash := strings.TrimSpace(fields[i])
		if hash != "" {
			result = append(result, M{"hash": hash, "shortHash": fields[i+1], "subject": fields[i+2], "author": fields[i+3], "date": fields[i+4]})
		}
	}
	return result, nil
}
func gitBranches(cwd string) ([]M, error) {
	root, err := gitRoot(cwd)
	if err != nil {
		return nil, err
	}
	if root == "" {
		return nil, fileProblem(404, "This folder is not managed by Git.")
	}
	text, err := gitRun(root, "", 12*time.Second, "for-each-ref", "--format=%(refname)%00%(HEAD)", "refs/heads", "refs/remotes")
	if err != nil {
		return nil, err
	}
	result := []M{}
	for _, line := range strings.Split(text, "\n") {
		parts := strings.Split(line, "\x00")
		if len(parts) != 2 || strings.HasSuffix(parts[0], "/HEAD") {
			continue
		}
		remote := strings.HasPrefix(parts[0], "refs/remotes/")
		name := strings.TrimPrefix(strings.TrimPrefix(parts[0], "refs/heads/"), "refs/remotes/")
		result = append(result, M{"name": name, "current": parts[1] == "*", "remote": remote})
	}
	return result, nil
}
func gitMutation(cwd, action string, b M) (M, error) {
	gitWrites.Lock()
	defer gitWrites.Unlock()
	root, err := gitRoot(cwd)
	if err != nil {
		return nil, err
	}
	if root == "" {
		return nil, fileProblem(404, "This folder is not managed by Git.")
	}
	defer gitSnapshots.invalidate(fileKey(root))
	status, err := gitReadPorcelain(root)
	if err != nil {
		return nil, err
	}
	args := []string{}
	input := ""
	timeout := 12 * time.Second
	message := "Operation completed."
	switch action {
	case "stage", "unstage":
		raw, ok := b["paths"].([]any)
		if !ok || len(raw) == 0 || len(raw) > 5000 {
			return nil, fileProblem(400, "Please select a file to operate on")
		}
		selected := []string{}
		seen := map[string]bool{}
		for _, value := range raw {
			path, ok := value.(string)
			if !ok {
				return nil, fileProblem(400, "Invalid file path")
			}
			if err = gitPath(path); err != nil {
				return nil, err
			}
			var file M
			for _, v := range gitFiles(status) {
				if S(v, "path") == path {
					file = v
					break
				}
			}
			if file == nil {
				return nil, fileProblem(409, "Selected modified file not found. Please refresh and check again.")
			}
			for _, p := range []string{path, S(file, "originalPath")} {
				if p != "" && !seen[p] {
					if err = gitPath(p); err != nil {
						return nil, err
					}
					seen[p] = true
					selected = append(selected, p)
				}
			}
		}
		input = strings.Join(selected, "\x00") + "\x00"
		if action == "stage" {
			args = []string{"add", "--all", "--pathspec-from-file=-", "--pathspec-file-nul"}
			message = "Staged selected file(s)."
		} else if S(status, "head") != "" {
			args = []string{"restore", "--staged", "--source=HEAD", "--pathspec-from-file=-", "--pathspec-file-nul"}
			message = "Unstaged selected file(s)."
		} else {
			format, err := gitRun(root, "", timeout, "rev-parse", "--show-object-format")
			if err != nil {
				return nil, err
			}
			zero := strings.Repeat("0", 40)
			if strings.TrimSpace(format) == "sha256" {
				zero = strings.Repeat("0", 64)
			}
			input = ""
			for _, p := range selected {
				input += "0 " + zero + "\t" + p + "\x00"
			}
			args = []string{"update-index", "-z", "--index-info"}
			message = "Unstaged selected file(s)."
		}
	case "commit":
		messageText := strings.TrimSpace(S(b, "message"))
		if messageText == "" || len(messageText) > 16384 || strings.ContainsRune(messageText, 0) {
			return nil, fileProblem(400, "Please enter a commit message (up to 16 KB).")
		}
		if B(status, "detached") {
			return nil, fileProblem(409, "Please select a branch before committing.")
		}
		if N(status, "conflictedFiles") > 0 {
			return nil, fileProblem(409, "Please resolve conflicts before committing.")
		}
		if N(status, "stagedFiles") == 0 {
			return nil, fileProblem(409, "Please stage changes before committing.")
		}
		args = []string{"commit", "--file=-"}
		input = messageText + "\n"
		timeout = 45 * time.Second
		message = "Committed."
	case "fetch":
		args = []string{"fetch", "--all", "--no-recurse-submodules"}
		timeout = 45 * time.Second
		message = "Fetched remote information."
	case "pull":
		if B(status, "detached") || S(status, "upstream") == "" {
			return nil, fileProblem(409, "Please select a branch with an upstream tracking branch.")
		}
		if N(status, "conflictedFiles") > 0 {
			return nil, fileProblem(409, "Please resolve conflicts before pulling.")
		}
		args = []string{"pull", "--ff-only", "--no-rebase", "--no-recurse-submodules"}
		timeout = 45 * time.Second
		message = "Pulled."
	case "checkout", "branch":
		name := S(b, "branch")
		if action == "branch" {
			name = S(b, "name")
		}
		if name == "" || len(name) > 300 || strings.HasPrefix(name, "-") || strings.ContainsRune(name, 0) {
			return nil, fileProblem(400, "Please specify a branch.")
		}
		if action == "branch" {
			if _, err = gitRun(root, "", timeout, "check-ref-format", "--branch", name); err != nil {
				return nil, err
			}
			args = []string{"switch", "-c", name}
			message = "Created branch."
		} else {
			branches, err := gitBranches(root)
			if err != nil {
				return nil, err
			}
			found := false
			for _, branch := range branches {
				if S(branch, "name") == name && !B(branch, "remote") {
					found = true
				}
			}
			if !found {
				return nil, fileProblem(400, "Please select a local branch.")
			}
			args = []string{"switch", "--no-guess", name}
			message = "Switched branch."
		}
	case "push":
		return gitPush(root, status, b)
	default:
		return nil, fileProblem(400, "Unknown Git operation")
	}
	out, err := gitRun(root, input, timeout, args...)
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(out) != "" {
		message = gitSanitize(strings.TrimSpace(out))
	}
	return M{"message": message}, nil
}
func gitConfig(root, key string) string {
	out, err := gitRun(root, "", 12*time.Second, "config", "--get", key)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(out)
}
func gitPush(root string, status, b M) (M, error) {
	branch, head := S(status, "branch"), S(status, "head")
	if B(status, "detached") || branch == "" {
		return nil, fileProblem(409, "Please select a branch before pushing.")
	}
	if head == "" {
		return nil, fileProblem(409, "Please create a commit first.")
	}
	if N(status, "conflictedFiles") > 0 {
		return nil, fileProblem(409, "Please resolve conflicts before pushing.")
	}
	if v, ok := b["setUpstream"]; ok {
		if _, ok = v.(bool); !ok {
			return nil, fileProblem(400, "Invalid tracking branch specified")
		}
	}
	configured := gitConfig(root, "branch."+branch+".remote")
	merge := gitConfig(root, "branch."+branch+".merge")
	remote := S(b, "remote")
	if remote == "" {
		remote = configured
	}
	valid := false
	remotes, err := gitRemotes(root)
	if err != nil {
		return nil, err
	}
	for _, v := range remotes {
		if S(v, "name") == remote {
			valid = true
		}
	}
	if !valid {
		return nil, fileProblem(409, "Select a remote destination and run the initial push.")
	}
	if !B(b, "setUpstream") && S(b, "remote") == "" && merge == "" {
		return nil, fileProblem(409, "No upstream tracking branch. Select a destination and run the initial push.")
	}
	target := "refs/heads/" + branch
	if !B(b, "setUpstream") && remote == configured && strings.HasPrefix(merge, "refs/heads/") {
		target = merge
	}
	if _, err := gitRun(root, "", 12*time.Second, "check-ref-format", target); err != nil {
		return nil, err
	}
	out, err := gitRun(root, "", 45*time.Second, "-c", "remote."+remote+".mirror=false", "push", "--no-force", "--no-follow-tags", "--recurse-submodules=no", "--", remote, head+":"+target)
	if err != nil {
		return nil, err
	}
	if B(b, "setUpstream") {
		after, err := gitReadPorcelain(root)
		if err != nil {
			return nil, err
		}
		if S(after, "branch") != branch || S(after, "head") != head {
			return nil, fileProblem(409, "Pushed successfully, but upstream was not set because the branch on the PC changed. Please refresh and check.")
		}
		if _, err = gitRun(root, "", 12*time.Second, "config", "branch."+branch+".remote", remote); err != nil {
			return nil, err
		}
		if _, err = gitRun(root, "", 12*time.Second, "config", "branch."+branch+".merge", target); err != nil {
			return nil, err
		}
	}
	message := strings.TrimSpace(out)
	if message == "" {
		message = "Pushed."
	}
	return M{"message": gitSanitize(message)}, nil
}
func registerGit(a *App, mux *http.ServeMux) {
	fileRoute(a, mux, "GET /api/sessions/{id}/git", func(r *http.Request, rec *Record, _ M) (any, error) {
		return gitStatus(S(rec.Info, "cwd"), r.URL.Query().Get("force") == "1")
	})
	fileRoute(a, mux, "GET /api/sessions/{id}/git/diff", func(r *http.Request, rec *Record, _ M) (any, error) {
		return gitDiff(S(rec.Info, "cwd"), r.URL.Query().Get("path"), r.URL.Query().Get("staged") == "1")
	})
	fileRoute(a, mux, "GET /api/sessions/{id}/git/log", func(_ *http.Request, rec *Record, _ M) (any, error) { return gitLog(S(rec.Info, "cwd")) })
	fileRoute(a, mux, "GET /api/sessions/{id}/git/branches", func(_ *http.Request, rec *Record, _ M) (any, error) { return gitBranches(S(rec.Info, "cwd")) })
	for _, action := range []string{"stage", "unstage", "commit", "push", "fetch", "pull", "checkout", "branch"} {
		fileRoute(a, mux, "POST /api/sessions/{id}/git/"+action, func(_ *http.Request, rec *Record, b M) (any, error) {
			cwd := S(rec.Info, "cwd")
			if expected, ok := b["cwd"]; ok {
				value, ok := expected.(string)
				if !ok {
					return nil, fileProblem(400, "Invalid workspace folder specified")
				}
				left, _ := filepath.Abs(value)
				right, _ := filepath.Abs(cwd)
				if fileKey(left) != fileKey(right) {
					return nil, fileProblem(409, "Workspace folder has changed. Please reopen Git details")
				}
			}
			return gitMutation(cwd, action, b)
		})
	}
}
