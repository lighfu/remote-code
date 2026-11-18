package host

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strings"
	"sync"
	"unicode/utf16"
	"unicode/utf8"
)

const projectTextLimit = 2 * 1024 * 1024

var projectWrites sync.Mutex
var fileHashPattern = regexp.MustCompile(`^[a-f0-9]{64}$`)
var fileBadName = regexp.MustCompile(`[<>:"|?*]`)
var fileReservedName = regexp.MustCompile(`(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)`)

type fileHTTPError struct {
	status  int
	message string
}

func (e *fileHTTPError) Error() string             { return e.message }
func fileProblem(status int, message string) error { return &fileHTTPError{status, message} }
func fileError(w http.ResponseWriter, err error) {
	status := 400
	var e *fileHTTPError
	if errors.As(err, &e) {
		status = e.status
	} else if errors.Is(err, os.ErrNotExist) {
		status = 404
	} else if errors.Is(err, os.ErrPermission) {
		status = 403
	} else if errors.Is(err, os.ErrExist) {
		status = 409
	}
	writeJSON(w, status, M{"error": err.Error()})
}
func fileRoute(a *App, mux *http.ServeMux, pattern string, fn func(*http.Request, *Record, M) (any, error)) {
	mux.HandleFunc(pattern, func(w http.ResponseWriter, r *http.Request) {
		var rec *Record
		if id := r.PathValue("id"); id != "" {
			rec = a.Store.infoRecord(id)
			if rec == nil {
				fileError(w, fileProblem(404, "Session not found"))
				return
			}
		}
		body := M{}
		if r.Method != "GET" && r.Method != "HEAD" {
			var err error
			body, err = readJSON(w, r)
			if err != nil {
				fileError(w, err)
				return
			}
		}
		value, err := fn(r, rec, body)
		if err != nil {
			fileError(w, err)
			return
		}
		writeJSON(w, 200, value)
	})
}
func fileKey(path string) string {
	if runtime.GOOS == "windows" {
		return strings.ToLower(path)
	}
	return path
}
func fileInside(root, path string) bool {
	rel, err := filepath.Rel(root, path)
	return err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && !filepath.IsAbs(rel)
}
func fileInput(path string) error {
	if len(path) > 8192 || strings.Contains(path, "://") {
		return fileProblem(400, "Invalid file path")
	}
	for _, r := range path {
		if r < 32 || r == 127 {
			return fileProblem(400, "Invalid file path")
		}
	}
	return nil
}
func fileChecked(root, path string) error {
	if !fileInside(root, path) {
		return fileProblem(403, "Cannot open files outside the project")
	}
	rel, _ := filepath.Rel(root, path)
	for _, part := range strings.FieldsFunc(rel, func(r rune) bool { return r == '/' || r == '\\' }) {
		if strings.EqualFold(part, ".git") {
			return fileProblem(403, "Cannot edit Git internal data")
		}
	}
	if runtime.GOOS == "windows" && strings.Contains(rel, ":") {
		return fileProblem(403, "Invalid file path")
	}
	return nil
}
func fileRoot(cwd string) (string, error) {
	if !filepath.IsAbs(cwd) {
		return "", fileProblem(400, "Project working folder does not exist")
	}
	root, err := filepath.EvalSymlinks(cwd)
	if err != nil {
		return "", err
	}
	st, err := os.Stat(root)
	if err != nil {
		return "", err
	}
	if !st.IsDir() {
		return "", fileProblem(400, "Project working folder does not exist")
	}
	for _, part := range strings.FieldsFunc(root, func(r rune) bool { return r == '/' || r == '\\' }) {
		if strings.EqualFold(part, ".git") {
			return "", fileProblem(403, "Cannot open Git internal data")
		}
	}
	return root, nil
}
func fileLexical(root, path string) (string, error) {
	if err := fileInput(path); err != nil {
		return "", err
	}
	path = filepath.FromSlash(strings.ReplaceAll(path, "\\", "/"))
	if !filepath.IsAbs(path) {
		path = filepath.Join(root, path)
	}
	path = filepath.Clean(path)
	return path, fileChecked(root, path)
}
func fileTarget(root, path string) (string, error) {
	p, err := fileLexical(root, path)
	if err != nil {
		return "", err
	}
	p, err = filepath.EvalSymlinks(p)
	if err != nil {
		return "", err
	}
	return p, fileChecked(root, p)
}
func fileExpected(root, expected string) error {
	if !filepath.IsAbs(expected) || fileKey(filepath.Clean(expected)) != fileKey(root) {
		return fileProblem(409, "The working folder has changed. Please reopen the file")
	}
	return nil
}
func fileName(name string) error {
	if name == "" || name == "." || name == ".." || strings.ContainsAny(name, "/\\") {
		return fileProblem(400, "Invalid filename")
	}
	if runtime.GOOS == "windows" && (fileBadName.MatchString(name) || strings.HasSuffix(name, ".") || strings.HasSuffix(name, " ") || fileReservedName.MatchString(name)) {
		return fileProblem(400, "This name cannot be used")
	}
	return fileInput(name)
}
func fileRelative(root, path string) string {
	p, _ := filepath.Rel(root, path)
	if p == "." {
		return ""
	}
	return filepath.ToSlash(p)
}
func fileDigest(data []byte) string { h := sha256.Sum256(data); return hex.EncodeToString(h[:]) }
func fileVersion(path string) (string, error) {
	st, err := os.Lstat(path)
	if err != nil {
		return "", err
	}
	link := ""
	if st.Mode()&os.ModeSymlink != 0 {
		link, err = os.Readlink(path)
		if err != nil {
			return "", err
		}
	}
	identity, err := fileIdentity(path)
	if err != nil {
		return "", err
	}
	return fileDigest([]byte(fmt.Sprintf("%s:%d:%d:%d:%s:%s", path, st.Size(), st.ModTime().UnixNano(), st.Mode(), identity, link))), nil
}
func fileEntry(root, path string) (M, error) {
	lexical, err := fileLexical(root, path)
	if err != nil {
		return nil, err
	}
	real, err := fileTarget(root, path)
	if err != nil {
		return nil, err
	}
	link, err := os.Lstat(lexical)
	if err != nil {
		return nil, err
	}
	st, err := os.Stat(real)
	if err != nil {
		return nil, err
	}
	if !st.IsDir() && !st.Mode().IsRegular() {
		return nil, fileProblem(400, "Please select a regular file or folder")
	}
	kind := "file"
	size := st.Size()
	if st.IsDir() {
		kind = "dir"
		size = 0
	}
	version, err := fileVersion(lexical)
	if err != nil {
		return nil, err
	}
	value := M{"root": root, "path": fileRelative(root, lexical), "name": filepath.Base(lexical), "kind": kind, "size": size, "mtime": st.ModTime().UnixMilli(), "symlink": link.Mode()&os.ModeSymlink != 0, "version": version}
	if st.IsDir() {
		list, err := fileList(root, real, "")
		if err != nil {
			return nil, err
		}
		value["fileCount"] = list["fileCount"]
		value["folderCount"] = list["folderCount"]
	}
	return value, nil
}
func fileList(root, path, query string) (M, error) {
	if len(query) > 800 {
		return nil, fileProblem(400, "File search query can be up to 200 characters")
	}
	if err := fileInput(query); err != nil {
		return nil, err
	}
	path, err := fileTarget(root, path)
	if err != nil {
		return nil, err
	}
	items, err := os.ReadDir(path)
	if err != nil {
		return nil, err
	}
	entries := []M{}
	fileCount, folderCount, matching := 0, 0, 0
	for _, item := range items {
		if strings.EqualFold(item.Name(), ".git") {
			continue
		}
		lexical := filepath.Join(path, item.Name())
		real, err := fileTarget(root, lexical)
		if err != nil {
			continue
		}
		st, err := os.Stat(real)
		if err != nil {
			continue
		}
		kind := "file"
		size := st.Size()
		if st.IsDir() {
			kind = "dir"
			size = 0
			folderCount++
		} else if st.Mode().IsRegular() {
			fileCount++
		} else {
			continue
		}
		if !strings.Contains(strings.ToLower(item.Name()), strings.ToLower(strings.TrimSpace(query))) {
			continue
		}
		matching++
		entries = append(entries, M{"path": fileRelative(root, lexical), "name": item.Name(), "kind": kind, "size": size, "mtime": st.ModTime().UnixMilli()})
	}
	sort.Slice(entries, func(i, j int) bool {
		if S(entries[i], "kind") != S(entries[j], "kind") {
			return S(entries[i], "kind") == "dir"
		}
		return strings.ToLower(S(entries[i], "name")) < strings.ToLower(S(entries[j], "name"))
	})
	if len(entries) > 3000 {
		entries = entries[:3000]
	}
	var parent any
	if fileKey(path) != fileKey(root) {
		parent = fileRelative(root, filepath.Dir(path))
	}
	return M{"root": root, "path": fileRelative(root, path), "parent": parent, "entries": entries, "truncated": matching > 3000, "fileCount": fileCount, "folderCount": folderCount, "matchingCount": matching}, nil
}

const fileBinaryExtensions = " bin pdf png jpg jpeg gif webp avif heic bmp ico mp3 wav flac ogg mp4 mov webm avi mkv zip gz 7z rar tar exe dll so a class jar wasm doc docx xls xlsx ppt pptx sqlite db ttf otf woff woff2 "

func fileHasBinaryExtension(path string) bool {
	return strings.Contains(fileBinaryExtensions, " "+strings.TrimPrefix(strings.ToLower(filepath.Ext(path)), ".")+" ")
}

func fileNormalize(text string) string {
	return strings.ReplaceAll(strings.ReplaceAll(text, "\r\n", "\n"), "\r", "\n")
}
func fileText(data []byte, partial bool) (text, encoding string, ok bool) {
	encoding = "utf-8"
	if bytes.HasPrefix(data, []byte{239, 187, 191}) {
		encoding = "utf-8-bom"
		data = data[3:]
	} else if bytes.HasPrefix(data, []byte{255, 254}) || bytes.HasPrefix(data, []byte{254, 255}) {
		encoding = "utf-16le"
		var order binary.ByteOrder = binary.LittleEndian
		if data[0] == 254 {
			encoding = "utf-16be"
			order = binary.BigEndian
		}
		data = data[2:]
		if partial {
			data = data[:len(data)/2*2]
			if len(data) >= 2 {
				last := order.Uint16(data[len(data)-2:])
				if last >= 0xd800 && last <= 0xdbff {
					data = data[:len(data)-2]
				}
			}
		}
		if len(data)%2 != 0 {
			return
		}
		units := make([]uint16, len(data)/2)
		for i := range units {
			units[i] = order.Uint16(data[i*2:])
		}
		for i, v := range units {
			if v >= 0xd800 && v <= 0xdbff && (i+1 >= len(units) || units[i+1] < 0xdc00 || units[i+1] > 0xdfff) {
				return
			}
			if v >= 0xdc00 && v <= 0xdfff && (i == 0 || units[i-1] < 0xd800 || units[i-1] > 0xdbff) {
				return
			}
		}
		text = string(utf16.Decode(units))
		data = nil
	}
	if data != nil {
		if partial && len(data) > 0 {
			i := len(data) - 1
			for i > 0 && len(data)-i < utf8.UTFMax && !utf8.RuneStart(data[i]) {
				i--
			}
			if !utf8.FullRune(data[i:]) {
				data = data[:i]
			}
		}
		if !utf8.Valid(data) {
			return
		}
		text = string(data)
	}
	for _, r := range text {
		if r < 9 || r == 11 || r >= 14 && r <= 31 {
			return
		}
	}
	return text, encoding, true
}
func fileDecode(data []byte, path string) (text, encoding, ending string, ok bool) {
	encoding, ending = "utf-8", "lf"
	if fileHasBinaryExtension(path) {
		return
	}
	text, encoding, ok = fileText(data, false)
	if !ok {
		return
	}
	crlf := strings.Count(text, "\r\n")
	rest := strings.ReplaceAll(text, "\r\n", "")
	lf, cr := strings.Count(rest, "\n"), strings.Count(rest, "\r")
	if crlf > 0 && crlf >= lf && crlf >= cr {
		ending = "crlf"
	} else if cr > lf {
		ending = "cr"
	}
	text = fileNormalize(text)
	return
}
func fileEncode(text, encoding, ending string) []byte {
	text = fileNormalize(text)
	if ending == "crlf" {
		text = strings.ReplaceAll(text, "\n", "\r\n")
	} else if ending == "cr" {
		text = strings.ReplaceAll(text, "\n", "\r")
	}
	switch encoding {
	case "utf-8-bom":
		return append([]byte{239, 187, 191}, []byte(text)...)
	case "utf-16le", "utf-16be":
		var order binary.ByteOrder = binary.LittleEndian
		out := []byte{255, 254}
		if encoding == "utf-16be" {
			order = binary.BigEndian
			out = []byte{254, 255}
		}
		for _, v := range utf16.Encode([]rune(text)) {
			b := make([]byte, 2)
			order.PutUint16(b, v)
			out = append(out, b...)
		}
		return out
	}
	return []byte(text)
}
func fileDocument(root, path string) (M, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return nil, err
	}
	if !st.Mode().IsRegular() {
		return nil, fileProblem(400, "Please select a file to open code")
	}
	result := M{"root": root, "path": fileRelative(root, path), "absolutePath": path, "name": filepath.Base(path), "text": nil, "editable": false, "revision": "", "size": st.Size(), "mtime": st.ModTime().UnixMilli(), "encoding": "unknown", "lineEnding": "unknown"}
	if st.Size() > projectTextLimit {
		result["reason"] = "For files over 2 MB, please view or download them"
		return result, nil
	}
	data, err := io.ReadAll(io.LimitReader(f, projectTextLimit+1))
	if err != nil {
		return nil, err
	}
	if len(data) > projectTextLimit {
		result["reason"] = "For files over 2 MB, please view or download them"
		return result, nil
	}
	result["revision"] = fileDigest(data)
	text, encoding, ending, ok := fileDecode(data, path)
	if !ok {
		result["reason"] = "Cannot edit binary or unsupported character encodings"
		return result, nil
	}
	result["text"] = text
	result["encoding"] = encoding
	result["lineEnding"] = ending
	result["editable"] = st.Mode().Perm()&0222 != 0
	if !B(result, "editable") {
		result["reason"] = "You do not have permission to save this file"
	}
	return result, nil
}
func fileSave(cwd string, b M) (M, error) {
	projectWrites.Lock()
	defer projectWrites.Unlock()
	text, ok := b["text"].(string)
	if !ok || !utf8.ValidString(text) || len(text) > projectTextLimit*2 || !fileHashPattern.MatchString(S(b, "revision")) {
		return nil, fileProblem(400, "Please specify the file content and the revision at read time")
	}
	for _, r := range text {
		if r < 9 || r == 11 || r >= 14 && r <= 31 {
			return nil, fileProblem(400, "Contains characters that cannot be saved")
		}
	}
	root, err := fileRoot(cwd)
	if err != nil {
		return nil, err
	}
	if err = fileExpected(root, S(b, "root")); err != nil {
		return nil, err
	}
	path, err := fileTarget(root, S(b, "path"))
	if err != nil {
		return nil, err
	}
	original, err := fileDocument(root, path)
	if err != nil {
		return nil, err
	}
	if !B(original, "editable") {
		return nil, fileProblem(403, S(original, "reason"))
	}
	if S(original, "revision") != S(b, "revision") {
		return nil, fileProblem(409, "The file was modified on the PC. Please reload to review the content")
	}
	if fileNormalize(text) == S(original, "text") {
		return original, nil
	}
	data := fileEncode(text, S(original, "encoding"), S(original, "lineEnding"))
	if len(data) > projectTextLimit {
		return nil, fileProblem(413, "Files to save must be 2 MB or less")
	}
	st, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), ".remote-code-*.tmp")
	if err != nil {
		return nil, err
	}
	defer os.Remove(tmp.Name())
	if err = tmp.Chmod(st.Mode().Perm()); err == nil {
		_, err = tmp.Write(data)
	}
	if err == nil {
		err = tmp.Sync()
	}
	closeErr := tmp.Close()
	if err != nil {
		return nil, err
	}
	if closeErr != nil {
		return nil, closeErr
	}
	again, err := fileTarget(root, S(b, "path"))
	if err != nil {
		return nil, err
	}
	current, err := fileDocument(root, path)
	if err != nil {
		return nil, err
	}
	if fileKey(again) != fileKey(path) || S(current, "revision") != S(original, "revision") {
		return nil, fileProblem(409, "The file on the PC was modified while saving. Please reload")
	}
	if err = os.Rename(tmp.Name(), path); err != nil {
		return nil, err
	}
	return fileDocument(root, path)
}
func fileCreate(cwd string, b M) (M, error) {
	projectWrites.Lock()
	defer projectWrites.Unlock()
	if S(b, "kind") != "file" && S(b, "kind") != "dir" {
		return nil, fileProblem(400, "Please specify a file or folder")
	}
	root, err := fileRoot(cwd)
	if err != nil {
		return nil, err
	}
	if err = fileExpected(root, S(b, "root")); err != nil {
		return nil, err
	}
	path, err := fileLexical(root, S(b, "path"))
	if err != nil {
		return nil, err
	}
	if fileKey(path) == fileKey(root) {
		return nil, fileProblem(409, "The project folder already exists")
	}
	if err = fileName(filepath.Base(path)); err != nil {
		return nil, err
	}
	parent, err := fileTarget(root, filepath.Dir(path))
	if err != nil {
		return nil, err
	}
	path = filepath.Join(parent, filepath.Base(path))
	if S(b, "kind") == "dir" {
		err = os.Mkdir(path, 0755)
	} else {
		var f *os.File
		f, err = os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0644)
		if err == nil {
			err = f.Close()
		}
	}
	if err != nil {
		return nil, err
	}
	return M{"path": fileRelative(root, path)}, nil
}
func fileCopyTree(root, source, dest string) error {
	st, err := os.Lstat(source)
	if err != nil {
		return err
	}
	if st.Mode()&os.ModeSymlink != 0 {
		if _, err = fileTarget(root, source); err != nil {
			return err
		}
		link, err := os.Readlink(source)
		if err != nil {
			return err
		}
		return os.Symlink(link, dest)
	}
	if st.IsDir() {
		if err = os.Mkdir(dest, st.Mode().Perm()); err != nil {
			return err
		}
		items, err := os.ReadDir(source)
		if err != nil {
			return err
		}
		for _, item := range items {
			if strings.EqualFold(item.Name(), ".git") {
				return fileProblem(403, "Cannot copy Git internal data")
			}
			if err = fileCopyTree(root, filepath.Join(source, item.Name()), filepath.Join(dest, item.Name())); err != nil {
				return err
			}
		}
		return nil
	}
	if !st.Mode().IsRegular() {
		return fileProblem(400, "Only regular files and folders can be copied")
	}
	src, err := os.Open(source)
	if err != nil {
		return err
	}
	defer src.Close()
	dst, err := os.OpenFile(dest, os.O_CREATE|os.O_EXCL|os.O_WRONLY, st.Mode().Perm())
	if err != nil {
		return err
	}
	_, err = io.Copy(dst, src)
	closeErr := dst.Close()
	if err != nil {
		return err
	}
	return closeErr
}
func fileAction(cwd string, b M) (M, error) {
	projectWrites.Lock()
	defer projectWrites.Unlock()
	action := S(b, "action")
	if action != "delete" && action != "copy" && action != "move" && action != "rename" {
		return nil, fileProblem(400, "Invalid file operation")
	}
	if !fileHashPattern.MatchString(S(b, "version")) {
		return nil, fileProblem(400, "Please specify file information at confirmation time")
	}
	if action == "delete" && !B(b, "confirm") {
		return nil, fileProblem(400, "Please confirm the target to delete")
	}
	root, err := fileRoot(cwd)
	if err != nil {
		return nil, err
	}
	if err = fileExpected(root, S(b, "root")); err != nil {
		return nil, err
	}
	source, err := fileLexical(root, S(b, "path"))
	if err != nil {
		return nil, err
	}
	real, err := fileTarget(root, source)
	if err != nil {
		return nil, err
	}
	if fileKey(source) == fileKey(root) {
		return nil, fileProblem(403, "Cannot perform operations on the project root")
	}
	version, err := fileVersion(source)
	if err != nil {
		return nil, err
	}
	if version != S(b, "version") {
		return nil, fileProblem(409, "The target was modified on the PC. Please refresh the list and try again")
	}
	if action == "delete" {
		err = os.RemoveAll(source)
		if err != nil {
			return nil, err
		}
		return M{"path": fileRelative(root, source), "action": action}, nil
	}
	dest, err := fileLexical(root, S(b, "destination"))
	if err != nil {
		return nil, err
	}
	parent, err := fileTarget(root, filepath.Dir(dest))
	if err != nil {
		return nil, err
	}
	if err = fileName(filepath.Base(dest)); err != nil {
		return nil, err
	}
	dest = filepath.Join(parent, filepath.Base(dest))
	if fileKey(dest) == fileKey(root) {
		return nil, fileProblem(403, "The project root cannot be used as the destination")
	}
	if action == "rename" {
		sp, err := fileTarget(root, filepath.Dir(source))
		if err != nil {
			return nil, err
		}
		if fileKey(sp) != fileKey(parent) {
			return nil, fileProblem(400, "When renaming, please specify a name within the same folder")
		}
	}
	if source == dest && action != "copy" {
		return M{"path": fileRelative(root, dest), "action": action}, nil
	}
	st, err := os.Lstat(source)
	if err != nil {
		return nil, err
	}
	if st.IsDir() && fileInside(real, parent) {
		return nil, fileProblem(400, "Cannot copy or move a folder into itself")
	}
	if _, err = os.Lstat(dest); err == nil {
		return nil, fileProblem(409, "An item with the same name already exists in the destination")
	}
	if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	if action == "copy" {
		tmp := filepath.Join(parent, ".remote-code-copy-"+NewID())
		defer os.RemoveAll(tmp)
		if err = fileCopyTree(root, source, tmp); err != nil {
			return nil, err
		}
		after, err := fileVersion(source)
		if err != nil {
			return nil, err
		}
		if after != version {
			return nil, fileProblem(409, "The target changed during copying. Please try again")
		}
		err = fileRenameExclusive(tmp, dest)
	} else {
		err = fileRenameExclusive(source, dest)
	}
	if err != nil {
		return nil, err
	}
	return M{"path": fileRelative(root, dest), "action": action}, nil
}

func RegisterFiles(a *App, mux *http.ServeMux) {
	fileRoute(a, mux, "GET /api/sessions/{id}/project/files", func(r *http.Request, rec *Record, _ M) (any, error) {
		root, err := fileRoot(S(rec.Info, "cwd"))
		if err != nil {
			return nil, err
		}
		return fileList(root, r.URL.Query().Get("path"), r.URL.Query().Get("query"))
	})
	fileRoute(a, mux, "GET /api/sessions/{id}/project/file", func(r *http.Request, rec *Record, _ M) (any, error) {
		root, err := fileRoot(S(rec.Info, "cwd"))
		if err != nil {
			return nil, err
		}
		path, err := fileTarget(root, r.URL.Query().Get("path"))
		if err != nil {
			return nil, err
		}
		return fileDocument(root, path)
	})
	fileRoute(a, mux, "POST /api/sessions/{id}/project/file", func(_ *http.Request, rec *Record, b M) (any, error) { return fileSave(S(rec.Info, "cwd"), b) })
	fileRoute(a, mux, "POST /api/sessions/{id}/project/create", func(_ *http.Request, rec *Record, b M) (any, error) { return fileCreate(S(rec.Info, "cwd"), b) })
	fileRoute(a, mux, "GET /api/sessions/{id}/project/entry", func(r *http.Request, rec *Record, _ M) (any, error) {
		root, err := fileRoot(S(rec.Info, "cwd"))
		if err != nil {
			return nil, err
		}
		return fileEntry(root, r.URL.Query().Get("path"))
	})
	fileRoute(a, mux, "POST /api/sessions/{id}/project/action", func(_ *http.Request, rec *Record, b M) (any, error) { return fileAction(S(rec.Info, "cwd"), b) })
	registerGit(a, mux)
	registerMedia(a, mux)
}
