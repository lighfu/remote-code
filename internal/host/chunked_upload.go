package host

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	uploadChunkLimit     = 4 << 20
	uploadStorageReserve = 32 << 20
	partialUploadTTL     = 6 * time.Hour
)

type pendingUpload struct {
	Name string `json:"name"`
	MIME string `json:"mime"`
	Size int64  `json:"size"` // -1 when the sender does not know the size yet.
}

type chunkedUploads struct {
	data        string
	staging     string
	attachments string
	locks       [64]sync.Mutex

	mu          sync.Mutex
	reserved    uint64
	nextCleanup time.Time
}

func registerChunkedUploads(dataDir string, mux *http.ServeMux) {
	u := &chunkedUploads{data: dataDir, staging: filepath.Join(dataDir, "upload-parts"), attachments: filepath.Join(dataDir, "attachments")}
	u.pruneStale()
	mux.HandleFunc("POST /api/uploads/chunked", u.start)
	mux.HandleFunc("PUT /api/uploads/chunked/{uploadID}", u.put)
	mux.HandleFunc("DELETE /api/uploads/chunked/{uploadID}", u.abort)
}

// Give uploads their own lock stripe so one incoming body does not hold the application lock.
func (u *chunkedUploads) lock(id string) *sync.Mutex {
	b, _ := strconv.ParseUint(id[:2], 16, 8)
	return &u.locks[b%uint64(len(u.locks))]
}

func checkUploadDirectory(path string) error {
	info, err := os.Lstat(path)
	if err == nil && !info.IsDir() {
		return fileProblem(http.StatusForbidden, "Attachment storage is unavailable")
	}
	return err
}

func (u *chunkedUploads) marker(id string) string {
	return filepath.Join(u.staging, id+".json")
}

func uploadWriteError(err error) error {
	if errors.Is(err, syscall.ENOSPC) || uploadPlatformDiskFull(err) {
		return fileProblem(http.StatusInsufficientStorage, "Not enough free space on the PC to attach this file")
	}
	return err
}

func (u *chunkedUploads) checkSpaceLocked(size uint64) error {
	if free, ok := uploadFreeBytes(u.attachments); ok && free < u.reserved+size+uploadStorageReserve {
		return fileProblem(http.StatusInsufficientStorage, "Not enough free space on the PC to attach this file")
	}
	return nil
}

// Reserve only bytes for in-flight requests. Completed bytes already count against the volume's free space.
func (u *chunkedUploads) reserve(size uint64) (func(), error) {
	u.mu.Lock()
	defer u.mu.Unlock()
	if err := u.checkSpaceLocked(size); err != nil {
		return nil, err
	}
	u.reserved += size
	return func() {
		u.mu.Lock()
		u.reserved -= size
		u.mu.Unlock()
	}, nil
}

func requireEmptyUploadBody(r *http.Request, message string) error {
	if r.ContentLength > 0 {
		return fileProblem(http.StatusBadRequest, message)
	}
	// Proxies can forward even an empty request without a Content-Length header.
	if r.Body == nil || r.Body == http.NoBody {
		return nil
	}
	var probe [1]byte
	if n, err := io.ReadFull(r.Body, probe[:]); n != 0 {
		return fileProblem(http.StatusBadRequest, message)
	} else if err != io.EOF {
		return fileProblem(http.StatusBadRequest, "File transfer interrupted")
	}
	return nil
}

func (u *chunkedUploads) start(w http.ResponseWriter, r *http.Request) {
	name, mime, err := uploadRequestInfo(r)
	if err != nil {
		fileError(w, err)
		return
	}
	if err := requireEmptyUploadBody(r, "Expected attachment metadata without a request body"); err != nil {
		fileError(w, err)
		return
	}
	size := int64(-1)
	if r.URL.Query().Has("size") {
		size, err = strconv.ParseInt(r.URL.Query().Get("size"), 10, 64)
		if err != nil || size < 0 {
			fileError(w, fileProblem(http.StatusBadRequest, "Invalid attachment size"))
			return
		}
		if size > uploadLimit {
			fileError(w, fileProblem(http.StatusRequestEntityTooLarge, "File is too large (up to 512 MiB)"))
			return
		}
	}
	u.pruneStale()
	id, err := u.newUpload(pendingUpload{Name: name, MIME: mime, Size: size})
	if err != nil {
		fileError(w, uploadWriteError(err))
		return
	}
	writeJSON(w, http.StatusOK, M{"id": id})
}

func (u *chunkedUploads) newUpload(meta pendingUpload) (string, error) {
	for _, path := range []string{u.staging, u.attachments} {
		if err := os.MkdirAll(path, 0700); err != nil {
			return "", err
		}
		if err := checkUploadDirectory(path); err != nil {
			return "", err
		}
	}
	u.mu.Lock()
	err := u.checkSpaceLocked(uint64(max(0, meta.Size)))
	u.mu.Unlock()
	if err != nil {
		return "", err
	}
	id := NewID()
	marker := u.marker(id)
	if err = atomicJSON(marker, meta); err != nil {
		return "", err
	}
	dir := filepath.Join(u.attachments, id)
	if err = os.Mkdir(dir, 0700); err != nil {
		_ = os.Remove(marker)
		return "", err
	}
	success := false
	defer func() {
		if !success {
			_ = os.RemoveAll(dir)
			_ = os.Remove(marker)
		}
	}()
	if err = os.Mkdir(filepath.Join(dir, "file"), 0700); err != nil {
		return "", err
	}
	if err = os.WriteFile(filepath.Join(dir, "file", uploadStoredName(meta.Name)), nil, 0600); err != nil {
		return "", err
	}
	success = true
	return id, nil
}

func readPendingUpload(dir, marker string) (pendingUpload, string, os.FileInfo, error) {
	var meta pendingUpload
	if info, err := os.Lstat(dir); err != nil {
		return meta, "", nil, err
	} else if !info.IsDir() {
		return meta, "", nil, fileProblem(http.StatusNotFound, "Attachment upload not found")
	}
	body, err := os.ReadFile(marker)
	if err != nil {
		return meta, "", nil, err
	}
	if json.Unmarshal(body, &meta) != nil || meta.Size < -1 || meta.Size > uploadLimit {
		return meta, "", nil, fileProblem(http.StatusNotFound, "Attachment upload not found")
	}
	if _, err = uploadName(meta.Name); err != nil {
		return meta, "", nil, err
	}
	path := filepath.Join(dir, "file", uploadStoredName(meta.Name))
	info, err := os.Lstat(path)
	if err != nil {
		return meta, "", nil, err
	}
	if !info.Mode().IsRegular() {
		return meta, "", nil, fileProblem(http.StatusNotFound, "Attachment upload not found")
	}
	return meta, path, info, nil
}

func (u *chunkedUploads) put(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("uploadID")
	if !uploadIDPattern.MatchString(id) {
		fileError(w, fileProblem(http.StatusBadRequest, "Invalid attachment ID"))
		return
	}
	if !uploadBinary(r) {
		fileError(w, fileProblem(http.StatusUnsupportedMediaType, "Please send files in binary format"))
		return
	}
	offset, err := strconv.ParseInt(r.URL.Query().Get("offset"), 10, 64)
	if err != nil || offset < 0 || offset > uploadLimit {
		fileError(w, fileProblem(http.StatusBadRequest, "Invalid attachment upload offset"))
		return
	}
	final := false
	if r.URL.Query().Has("final") {
		final, err = strconv.ParseBool(r.URL.Query().Get("final"))
		if err != nil {
			fileError(w, fileProblem(http.StatusBadRequest, "Invalid attachment upload completion marker"))
			return
		}
	}
	if final {
		if err := requireEmptyUploadBody(r, "Finishing an attachment must not include any file bytes"); err != nil {
			fileError(w, err)
			return
		}
	}
	if !final && r.ContentLength <= 0 {
		fileError(w, fileProblem(http.StatusLengthRequired, "Attachment chunk length is required"))
		return
	}
	if r.ContentLength > uploadChunkLimit {
		fileError(w, fileProblem(http.StatusRequestEntityTooLarge, "Attachment chunk is too large (up to 4 MiB)"))
		return
	}
	lock := u.lock(id)
	lock.Lock()
	defer lock.Unlock()
	result, err := u.write(id, offset, final, r)
	if err != nil {
		fileError(w, uploadWriteError(err))
		return
	}
	writeJSON(w, http.StatusOK, result)
}

func (u *chunkedUploads) write(id string, offset int64, final bool, r *http.Request) (M, error) {
	for _, root := range []string{u.staging, u.attachments} {
		if err := checkUploadDirectory(root); err != nil && !os.IsNotExist(err) {
			return nil, err
		}
	}
	dir := filepath.Join(u.attachments, id)
	if err := checkUploadDirectory(dir); err != nil && !os.IsNotExist(err) {
		return nil, err
	}
	if _, err := os.Lstat(filepath.Join(dir, "metadata.json")); err == nil {
		// A lost final response can be retried using the existing immutable attachment.
		if final {
			published, matchErr := findUpload(u.data, id)
			if matchErr == nil && int64(N(published, "size")) == offset {
				delete(published, "path")
				return published, nil
			}
		}
		return nil, fileProblem(http.StatusNotFound, "Attachment upload not found")
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	meta, path, info, err := readPendingUpload(dir, u.marker(id))
	if os.IsNotExist(err) {
		return nil, fileProblem(http.StatusNotFound, "Attachment upload not found")
	}
	if err != nil {
		return nil, err
	}
	if offset != info.Size() {
		return nil, fileProblem(http.StatusConflict, "Attachment upload offset does not match the stored length")
	}
	if err = r.Context().Err(); err != nil {
		return nil, fileProblem(http.StatusBadRequest, "File transfer interrupted")
	}
	if final {
		if meta.Size >= 0 && offset != meta.Size {
			return nil, fileProblem(http.StatusConflict, "Attachment upload is incomplete")
		}
		return u.publish(id, dir, meta, offset)
	}
	next := offset + r.ContentLength
	if next > uploadLimit {
		return nil, fileProblem(http.StatusRequestEntityTooLarge, "File is too large (up to 512 MiB)")
	}
	if meta.Size >= 0 && next > meta.Size {
		return nil, fileProblem(http.StatusConflict, "Attachment data exceeds the declared size")
	}
	release, err := u.reserve(uint64(r.ContentLength))
	if err != nil {
		return nil, err
	}
	defer release()
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_APPEND, 0600)
	if err != nil {
		return nil, err
	}
	_, copyErr := io.CopyN(file, r.Body, r.ContentLength)
	closeErr := file.Close()
	if copyErr != nil || closeErr != nil || r.Context().Err() != nil {
		if err := os.Truncate(path, offset); err != nil {
			_ = u.discardPending(id) // Never accept more bytes if the old length could not be restored.
		}
		if closeErr != nil {
			return nil, closeErr
		}
		var writeFailure *os.PathError
		if errors.As(copyErr, &writeFailure) {
			return nil, copyErr
		}
		return nil, fileProblem(http.StatusBadRequest, "File transfer interrupted")
	}
	return M{"offset": next}, nil
}

func (u *chunkedUploads) publish(id, dir string, meta pendingUpload, size int64) (M, error) {
	result := M{"id": id, "name": meta.Name, "mime": meta.MIME, "size": size}
	// ResolveUploads requires metadata.json. Publish only that file atomically;
	// Windows can refuse to move a directory while another process is scanning the payload.
	if err := atomicJSON(filepath.Join(dir, "metadata.json"), result); err != nil {
		return nil, err
	}
	_ = os.Remove(u.marker(id))
	return result, nil
}

func (u *chunkedUploads) abort(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("uploadID")
	if !uploadIDPattern.MatchString(id) {
		fileError(w, fileProblem(http.StatusBadRequest, "Invalid attachment ID"))
		return
	}
	lock := u.lock(id)
	lock.Lock()
	defer lock.Unlock()
	if err := checkUploadDirectory(u.staging); err != nil && !os.IsNotExist(err) {
		fileError(w, err)
		return
	}
	if err := u.discardPending(id); err != nil {
		fileError(w, err)
		return
	}
	// Older versions kept unfinished payloads in this separate directory.
	if err := os.RemoveAll(filepath.Join(u.staging, id)); err != nil {
		fileError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (u *chunkedUploads) discardPending(id string) error {
	marker := u.marker(id)
	info, err := os.Lstat(marker)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() {
		return fileProblem(http.StatusForbidden, "Attachment storage is unavailable")
	}
	if err = checkUploadDirectory(u.attachments); err != nil && !os.IsNotExist(err) {
		return err
	}
	dir := filepath.Join(u.attachments, id)
	if _, err = os.Lstat(filepath.Join(dir, "metadata.json")); os.IsNotExist(err) {
		if err = os.RemoveAll(dir); err != nil {
			return err
		}
	} else if err != nil {
		return err
	}
	return os.Remove(marker)
}

func (u *chunkedUploads) pruneStale() {
	now := time.Now()
	u.mu.Lock()
	if now.Before(u.nextCleanup) {
		u.mu.Unlock()
		return
	}
	u.nextCleanup = now.Add(30 * time.Minute)
	u.mu.Unlock()
	if checkUploadDirectory(u.staging) != nil {
		return
	}
	entries, err := os.ReadDir(u.staging)
	if err != nil {
		return
	}
	for _, entry := range entries {
		legacy := entry.IsDir()
		id, marker := strings.CutSuffix(entry.Name(), ".json")
		if !uploadIDPattern.MatchString(id) || legacy == marker || !legacy && !entry.Type().IsRegular() {
			continue
		}
		lock := u.lock(id)
		if !lock.TryLock() {
			continue
		}
		dir, markerPath := filepath.Join(u.attachments, id), u.marker(id)
		if legacy {
			dir = filepath.Join(u.staging, id)
			markerPath = filepath.Join(dir, "pending.json")
		}
		_, _, info, err := readPendingUpload(dir, markerPath)
		if err != nil {
			info, err = entry.Info()
		}
		if err == nil && now.Sub(info.ModTime()) >= partialUploadTTL {
			if legacy {
				_ = os.RemoveAll(dir)
			} else {
				_ = u.discardPending(id)
			}
		}
		lock.Unlock()
	}
}
