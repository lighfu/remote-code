package host

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"mime"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"
)

var mediaImages = map[string]string{".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif", ".webp": "image/webp", ".bmp": "image/bmp", ".svg": "image/svg+xml"}
var mediaVideos = map[string]string{".mp4": "video/mp4", ".m4v": "video/mp4", ".mov": "video/quicktime", ".webm": "video/webm", ".mkv": "video/x-matroska", ".avi": "video/x-msvideo", ".3gp": "video/3gpp", ".3gpp": "video/3gpp"}
var mediaNonLocal = regexp.MustCompile(`(?i)^(https?|data|content):`)
var mediaDriveURL = regexp.MustCompile(`^[\\/]+([A-Za-z]:[\\/])`)

const mediaVideoLimit = 512 * 1024 * 1024

const mediaTextExtensions = " txt md markdown json jsonl yaml yml toml ini cfg conf log csv tsv xml svg html htm css scss js mjs cjs ts tsx jsx py rb go rs java kt kts c h cpp hpp cs swift php sh bash zsh ps1 bat cmd sql gradle properties env gitignore dockerfile makefile lua r dart vue svelte "

func mediaMime(path string) string {
	ext := strings.ToLower(filepath.Ext(path))
	if v := mediaImages[ext]; v != "" {
		return v
	}
	if v := mediaVideos[ext]; v != "" {
		return v
	}
	if strings.Contains(mediaTextExtensions, " "+strings.TrimPrefix(ext, ".")+" ") {
		if ext == ".json" {
			return "application/json"
		}
		if ext == ".md" {
			return "text/markdown"
		}
		if ext == ".html" || ext == ".htm" {
			return "text/html"
		}
		return "text/plain"
	}
	if v := mime.TypeByExtension(ext); v != "" {
		return strings.SplitN(v, ";", 2)[0]
	}
	return "application/octet-stream"
}
func MediaID(path string) string { return base64.RawURLEncoding.EncodeToString([]byte(path)) }
func mediaPath(raw, cwd string) string {
	p := strings.TrimSpace(raw)
	p = strings.TrimPrefix(p, "@")
	if len(p) >= 2 && ((p[0] == '"' && p[len(p)-1] == '"') || (p[0] == '\'' && p[len(p)-1] == '\'')) {
		p = p[1 : len(p)-1]
	}
	if p == "" || mediaNonLocal.MatchString(p) {
		return ""
	}
	if strings.HasPrefix(strings.ToLower(p), "file://") {
		p = p[len("file://"):]
		if p == "" {
			return ""
		}
	}
	if strings.Contains(p, "%") {
		if decoded, err := url.PathUnescape(p); err == nil {
			p = decoded
		}
	}
	if p[0] == '/' || p[0] == '\\' {
		p = mediaDriveURL.ReplaceAllString(p, "$1")
	}
	if !filepath.IsAbs(p) {
		if cwd == "" {
			return ""
		}
		p = filepath.Join(cwd, p)
	}
	return filepath.Clean(p)
}
func mediaAvailable(path string, video bool) bool {
	st, err := os.Stat(path)
	if err != nil || !st.Mode().IsRegular() {
		return false
	}
	limit := int64(40 * 1024 * 1024)
	if video {
		limit = mediaVideoLimit
	}
	return st.Size() <= limit
}
func MediaFromPath(raw, cwd string) string {
	path := mediaPath(raw, cwd)
	ext := strings.ToLower(filepath.Ext(path))
	if mediaImages[ext] == "" || ext == ".svg" || !mediaAvailable(path, false) {
		return ""
	}
	return MediaID(path)
}
func VideoFromPath(raw, cwd string) string {
	path := mediaPath(raw, cwd)
	if mediaVideos[strings.ToLower(filepath.Ext(path))] == "" || !mediaAvailable(path, true) {
		return ""
	}
	return MediaID(path)
}
func MediaFromBase64(dataDir, data, contentType string) string {
	if at := strings.Index(data, ","); strings.HasPrefix(data, "data:") && at >= 0 {
		data = data[at+1:]
	}
	if len(data) > 56*1024*1024 {
		return ""
	}
	bytes, err := base64.StdEncoding.DecodeString(data)
	if err != nil || len(bytes) > 40*1024*1024 {
		return ""
	}
	ext := ".png"
	for k, v := range mediaImages {
		if v == contentType && k != ".svg" {
			ext = k
			break
		}
	}
	directory := filepath.Join(dataDir, "media")
	if os.MkdirAll(directory, 0700) != nil {
		return ""
	}
	path := filepath.Join(directory, fileDigest(bytes)[:32]+ext)
	if _, err = os.Stat(path); err != nil {
		if os.WriteFile(path, bytes, 0600) != nil {
			return ""
		}
	}
	return MediaID(path)
}

var mediaQuoted = regexp.MustCompile(`(?:@)?["']([^"'\r\n]+)["']`)
var mediaMarkdown = regexp.MustCompile(`!?\[[^\]]*\]\(\s*<?([^)>]+)>?\s*\)`)
var mediaBare = regexp.MustCompile("[^\\s`'\"<>|*?()\\[\\]]+\\.(?i:png|jpe?g|gif|webp|bmp|mp4|m4v|mov|webm|mkv|avi|3gpp?)\\b")

func MediaInText(text, cwd string) ([]string, []string) {
	images, videos := []string{}, []string{}
	seen := map[string]bool{}
	paths := []string{}
	for _, re := range []*regexp.Regexp{mediaQuoted, mediaMarkdown} {
		for _, m := range re.FindAllStringSubmatch(text, 64) {
			paths = append(paths, strings.TrimSpace(m[1]))
		}
	}
	paths = append(paths, mediaBare.FindAllString(text, 64)...)
	for _, p := range paths {
		if id := MediaFromPath(p, cwd); id != "" && !seen[id] && len(images) < 12 {
			seen[id] = true
			images = append(images, id)
		}
		if id := VideoFromPath(p, cwd); id != "" && !seen[id] && len(videos) < 12 {
			seen[id] = true
			videos = append(videos, id)
		}
	}
	return images, videos
}
func EnrichEntryMedia(dataDir, cwd string, entry M) M {
	text := S(entry, "text") + "\n" + S(entry, "output") + "\n" + S(entry, "detail") + "\n" + S(entry, "title")
	images, videos := MediaInText(text, cwd)
	if strings.EqualFold(S(entry, "tool"), "Read") {
		paths := append([]string{S(entry, "path"), S(entry, "title")}, providerAnswerList(entry["files"])...)
		for _, path := range paths {
			if image := MediaFromPath(path, cwd); image != "" {
				images = append(images, image)
			}
			if video := VideoFromPath(path, cwd); video != "" {
				videos = append(videos, video)
			}
		}
	}
	merge := func(key string, ids []string) {
		seen := map[string]bool{}
		out := []string{}
		switch v := entry[key].(type) {
		case []string:
			out = append(out, v...)
		case []any:
			for _, x := range v {
				if s, ok := x.(string); ok {
					out = append(out, s)
				}
			}
		}
		for _, v := range out {
			seen[v] = true
		}
		for _, v := range ids {
			if !seen[v] {
				out = append(out, v)
				seen[v] = true
			}
		}
		if len(out) > 0 {
			entry[key] = out
		}
	}
	merge("media", images)
	merge("videos", videos)
	return entry
}

var mediaLineFragment = regexp.MustCompile(`(?i)#L(\d+)(?:-L?\d+)?$`)
var mediaLineColon = regexp.MustCompile(`^(.*?):(\d+)(?::\d+)?$`)

func mediaResolveLink(raw, cwd string) (string, any) {
	p := strings.TrimSpace(raw)
	if p == "" || regexp.MustCompile(`(?i)^(https?|mailto|tel):`).MatchString(p) {
		return "", nil
	}
	fileURI := strings.HasPrefix(strings.ToLower(p), "file://")
	absolute := func(value string) string {
		if !filepath.IsAbs(value) {
			if cwd == "" {
				return ""
			}
			value = filepath.Join(cwd, value)
		}
		return filepath.Clean(value)
	}
	if !fileURI {
		literal := absolute(p)
		if _, err := os.Stat(literal); err == nil {
			return literal, nil
		}
	}
	p = strings.TrimPrefix(p, "file://")
	if regexp.MustCompile(`^/[A-Za-z]:`).MatchString(p) {
		p = p[1:]
	}
	var line any
	if match := mediaLineFragment.FindStringSubmatchIndex(p); match != nil {
		n, _ := strconv.Atoi(p[match[2]:match[3]])
		line = n
		p = p[:match[0]]
	}
	if fileURI {
		if decoded, err := url.PathUnescape(p); err == nil {
			p = decoded
		}
	}
	direct := absolute(p)
	if _, err := os.Stat(direct); err == nil {
		return direct, line
	}
	if m := mediaLineColon.FindStringSubmatch(p); m != nil {
		q := absolute(m[1])
		if _, err := os.Stat(q); err == nil {
			n, _ := strconv.Atoi(m[2])
			return q, n
		}
	}
	return direct, line
}
func mediaLinkInfo(raw, cwd string) (M, error) {
	path, line := mediaResolveLink(raw, cwd)
	if path == "" {
		return nil, fileProblem(400, "This link is not a file on the PC")
	}
	info := M{"path": path, "name": filepath.Base(path), "line": line, "mime": mediaMime(path), "exists": false, "kind": "missing", "size": 0, "mtime": 0}
	st, err := os.Stat(path)
	if err != nil {
		return info, nil
	}
	info["exists"] = true
	info["mtime"] = st.ModTime().UnixMilli()
	info["size"] = st.Size()
	info["kind"] = "binary"
	if st.IsDir() {
		info["kind"] = "dir"
		info["size"] = 0
		return info, nil
	}
	ext := strings.ToLower(filepath.Ext(path))
	if mediaVideos[ext] != "" {
		info["kind"] = "video"
		info["mediaId"] = MediaID(path)
		return info, nil
	}
	if mediaImages[ext] != "" && ext != ".svg" {
		info["kind"] = "image"
		info["mediaId"] = MediaID(path)
		return info, nil
	}
	if !st.Mode().IsRegular() || fileHasBinaryExtension(path) {
		return info, nil
	}
	f, err := os.Open(path)
	if err != nil {
		return info, nil
	}
	defer f.Close()
	data, err := io.ReadAll(io.LimitReader(f, projectTextLimit))
	if err != nil {
		return info, nil
	}
	truncated := st.Size() > int64(len(data))
	text, encoding, ok := fileText(data, truncated)
	if !ok && encoding == "utf-8" && !utf8.Valid(data) && strings.Contains(mediaTextExtensions, " "+strings.TrimPrefix(ext, ".")+" ") {
		// Preserve the text preview of legacy-encoded source files.
		text, _, ok = fileText(bytes.ToValidUTF8(data, []byte("�")), truncated)
	}
	if ok {
		info["kind"] = "text"
		info["text"] = text
		info["truncated"] = truncated
	}
	return info, nil
}

var uploadIDPattern = regexp.MustCompile(`^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$`)
var uploadMIMEPattern = regexp.MustCompile(`(?i)^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$`)

const legacyUploadLimit = 50 * 1024 * 1024
const uploadLimit = 512 * 1024 * 1024

func uploadName(raw string) (string, error) {
	name := strings.TrimSpace(raw)
	if name == "" || name == "." || name == ".." || len(name) > 240 || strings.ContainsAny(name, "/\\") {
		return "", fileProblem(400, "Invalid filename")
	}
	if err := fileInput(name); err != nil {
		return "", err
	}
	return name, nil
}
func uploadStoredName(name string) string {
	name = strings.Map(func(r rune) rune {
		if strings.ContainsRune(`<>:"|?*%#`, r) {
			return '_'
		}
		return r
	}, name)
	name = strings.TrimRight(name, ". ")
	if name == "" {
		name = "attachment"
	}
	if fileReservedName.MatchString(name) {
		name = "_" + name
	}
	return name
}
func uploadBinary(r *http.Request) bool {
	return strings.EqualFold(strings.TrimSpace(strings.SplitN(r.Header.Get("Content-Type"), ";", 2)[0]), "application/octet-stream")
}

func uploadRequestInfo(r *http.Request) (string, string, error) {
	name, err := uploadName(r.URL.Query().Get("name"))
	if err != nil {
		return "", "", err
	}
	contentType := r.URL.Query().Get("mime")
	if contentType == "" {
		contentType = mediaMime(name)
	}
	if len(contentType) > 160 || !uploadMIMEPattern.MatchString(contentType) {
		return "", "", fileProblem(400, "Invalid file format")
	}
	return name, strings.ToLower(contentType), nil
}

func receiveUpload(dataDir string, r *http.Request) (M, error) {
	if !uploadBinary(r) {
		return nil, fileProblem(415, "Please send files in binary format")
	}
	name, contentType, err := uploadRequestInfo(r)
	if err != nil {
		return nil, err
	}
	if r.ContentLength > legacyUploadLimit {
		return nil, fileProblem(413, "File is too large (up to 50 MB)")
	}
	id := NewID()
	directory := filepath.Join(dataDir, "attachments", id)
	if err = os.MkdirAll(filepath.Join(directory, "file"), 0700); err != nil {
		return nil, err
	}
	success := false
	defer func() {
		if !success {
			_ = os.RemoveAll(directory)
		}
	}()
	path := filepath.Join(directory, "file", uploadStoredName(name))
	f, err := os.OpenFile(path+".part", os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return nil, err
	}
	size, copyErr := io.Copy(f, io.LimitReader(r.Body, legacyUploadLimit+1))
	closeErr := f.Close()
	if size > legacyUploadLimit {
		return nil, fileProblem(413, "File is too large (up to 50 MB)")
	}
	if copyErr != nil {
		return nil, fileProblem(400, "File transfer interrupted")
	}
	if closeErr != nil {
		return nil, closeErr
	}
	if r.ContentLength >= 0 && size != r.ContentLength {
		return nil, fileProblem(400, "File transfer interrupted")
	}
	if err = os.Rename(path+".part", path); err != nil {
		return nil, err
	}
	file := M{"id": id, "name": name, "size": size, "mime": contentType}
	meta, err := json.Marshal(file)
	if err != nil {
		return nil, err
	}
	if err = os.WriteFile(filepath.Join(directory, "metadata.json"), meta, 0600); err != nil {
		return nil, err
	}
	success = true
	return file, nil
}
func (a *App) attachmentStorage() string {
	if a.Config.AttachmentStorage != "" {
		return a.Config.AttachmentStorage
	}
	return a.DataDir
}

var attachmentNotFound = fileProblem(404, "Attachment not found. Please choose the file again")

func findUpload(dataDir, id string) (M, error) {
	directory := filepath.Join(dataDir, "attachments", id)
	data, err := os.ReadFile(filepath.Join(directory, "metadata.json"))
	if err != nil {
		return nil, attachmentNotFound
	}
	var meta M
	if json.Unmarshal(data, &meta) != nil || S(meta, "id") != id {
		return nil, attachmentNotFound
	}
	name, err := uploadName(S(meta, "name"))
	if err != nil {
		return nil, err
	}
	path := filepath.Join(directory, "file", uploadStoredName(name))
	st, err := os.Stat(path)
	if err != nil || !st.Mode().IsRegular() || st.Size() != int64(N(meta, "size")) || st.Size() > uploadLimit {
		return nil, attachmentNotFound
	}
	meta["path"] = path
	return meta, nil
}

func ResolveUploads(dataDir string, value any, fallback ...string) ([]M, error) {
	result := []M{}
	if value == nil {
		return result, nil
	}
	ids, ok := value.([]any)
	if !ok || len(ids) > 10 {
		return nil, fileProblem(400, "Up to 10 attachments can be specified")
	}
	seen := map[string]bool{}
	for _, raw := range ids {
		id, ok := raw.(string)
		if !ok || !uploadIDPattern.MatchString(id) {
			return nil, fileProblem(400, "Invalid attachment ID")
		}
		if seen[id] {
			continue
		}
		seen[id] = true
		meta, err := findUpload(dataDir, id)
		for _, directory := range fallback {
			if err != attachmentNotFound {
				break
			}
			if directory != dataDir {
				meta, err = findUpload(directory, id)
			}
		}
		if err != nil {
			return nil, err
		}
		result = append(result, meta)
	}
	return result, nil
}
func AttachmentsPrompt(text string, files []M) string {
	if len(files) == 0 {
		return text
	}
	if text != "" {
		text += "\n\n"
	}
	text += "Attachments (saved on PC):"
	for _, file := range files {
		name := strings.NewReplacer("\\", "\\\\", "[", "\\[", "]", "\\]").Replace(S(file, "name"))
		text += fmt.Sprintf("\n- [%s](<%s>) (%d bytes)", name, filepath.ToSlash(S(file, "path")), int64(N(file, "size")))
	}
	return text
}

const rawFileLimit = 60 << 20
const rawFilePageLimit = 1 << 20

// Large files may only be fetched in bounded pages. Never serve the full file
// when an If-Range condition would cause ServeContent to ignore Range.
func boundedRawFileRange(r *http.Request, etag string) bool {
	if condition := r.Header.Get("If-Range"); condition != "" && condition != etag {
		return false
	}
	value, ok := strings.CutPrefix(r.Header.Get("Range"), "bytes=")
	if !ok {
		return false
	}
	left, right, ok := strings.Cut(value, "-")
	if !ok {
		return false
	}
	start, err1 := strconv.ParseUint(left, 10, 63)
	end, err2 := strconv.ParseUint(right, 10, 63)
	return err1 == nil && err2 == nil && start <= end && end-start < rawFilePageLimit
}

func registerMedia(a *App, mux *http.ServeMux) {
	storage := a.attachmentStorage()
	fileRoute(a, mux, "GET /api/file", func(r *http.Request, _ *Record, _ M) (any, error) {
		cwd := ""
		if rec := a.Store.infoRecord(r.URL.Query().Get("session")); rec != nil {
			cwd = S(rec.Info, "cwd")
		}
		return mediaLinkInfo(r.URL.Query().Get("link"), cwd)
	})
	mux.HandleFunc("GET /api/file/raw", func(w http.ResponseWriter, r *http.Request) {
		cwd := ""
		if rec := a.Store.infoRecord(r.URL.Query().Get("session")); rec != nil {
			cwd = S(rec.Info, "cwd")
		}
		path, _ := mediaResolveLink(r.URL.Query().Get("link"), cwd)
		st, err := os.Stat(path)
		if err != nil || !st.Mode().IsRegular() {
			fileError(w, fileProblem(404, "File not found"))
			return
		}
		f, err := os.Open(path)
		if err != nil {
			fileError(w, err)
			return
		}
		defer f.Close()
		st, err = f.Stat()
		if err != nil || !st.Mode().IsRegular() {
			fileError(w, fileProblem(404, "File not found"))
			return
		}
		etag := fmt.Sprintf(`"%x-%x"`, st.Size(), st.ModTime().UnixNano())
		if st.Size() > rawFileLimit && !boundedRawFileRange(r, etag) {
			fileError(w, fileProblem(413, "File is too large (up to 60 MB)"))
			return
		}
		w.Header().Set("Content-Type", mediaMime(path))
		w.Header().Set("Content-Disposition", "attachment; filename*=UTF-8''"+url.PathEscape(filepath.Base(path)))
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("ETag", etag)
		http.ServeContent(w, r, filepath.Base(path), st.ModTime(), io.NewSectionReader(f, 0, st.Size()))
	})
	mux.HandleFunc("POST /api/uploads", func(w http.ResponseWriter, r *http.Request) {
		file, err := receiveUpload(storage, r)
		if err != nil {
			fileError(w, err)
			return
		}
		writeJSON(w, 200, file)
	})
	registerChunkedUploads(storage, mux)
	mux.HandleFunc("GET /api/media/{mediaID}", func(w http.ResponseWriter, r *http.Request) {
		decoded, err := base64.RawURLEncoding.DecodeString(r.PathValue("mediaID"))
		if err != nil {
			fileError(w, fileProblem(404, "Image not found"))
			return
		}
		path := string(decoded)
		if !filepath.IsAbs(path) {
			fileError(w, fileProblem(404, "Image not found"))
			return
		}
		ext := strings.ToLower(filepath.Ext(path))
		video := mediaVideos[ext] != ""
		available := (video || mediaImages[ext] != "") && mediaAvailable(path, video)
		if video && !available {
			path = sentVideoFile(a.DataDir, path)
			available = mediaAvailable(path, true)
		}
		if !available {
			fileError(w, fileProblem(404, "Media file not found"))
			return
		}
		st, err := os.Stat(path)
		if err != nil {
			fileError(w, err)
			return
		}
		w.Header().Set("Content-Type", mediaMime(path))
		w.Header().Set("Cache-Control", "private, max-age=60")
		w.Header().Set("Vary", "Authorization, CF-Access-Client-Id, CF-Access-Client-Secret")
		w.Header().Set("ETag", fmt.Sprintf(`W/"%x-%x"`, st.Size(), st.ModTime().UnixMilli()))
		f, err := os.Open(path)
		if err != nil {
			fileError(w, err)
			return
		}
		defer f.Close()
		http.ServeContent(w, r, filepath.Base(path), st.ModTime(), f)
	})
}
