package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	maxCacheBytes    int64 = 100 * 1024 * 1024
	thumbnailSize          = "256"
	thumbnailQuality       = "7"
)

type config struct {
	listen          string
	allowedRoot     string
	cacheDir        string
	fileBrowserBase string
}

type server struct {
	cfg        config
	httpClient *http.Client
	jobs       chan job
	queued     map[string]struct{}
	queuedMu   sync.Mutex
}

type job struct {
	path      string
	cacheFile string
}

type apiError struct {
	Error string `json:"error"`
}

func main() {
	var cfg config
	flag.StringVar(&cfg.listen, "listen", "127.0.0.1:8890", "HTTP listen address")
	flag.StringVar(&cfg.allowedRoot, "root", "/media", "allowed media root")
	flag.StringVar(&cfg.cacheDir, "cache", "/var/cache/file-server-thumbnail", "thumbnail cache directory")
	flag.StringVar(&cfg.fileBrowserBase, "filebrowser", "http://127.0.0.1:8888", "File Browser base URL")
	flag.Parse()

	var err error
	cfg.allowedRoot, err = filepath.EvalSymlinks(cfg.allowedRoot)
	if err != nil {
		log.Fatalf("resolve allowed root: %v", err)
	}
	if err := os.MkdirAll(cfg.cacheDir, 0o750); err != nil {
		log.Fatalf("create cache: %v", err)
	}

	s := &server{
		cfg: cfg,
		httpClient: &http.Client{
			Timeout:       4 * time.Second,
			CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse },
		},
		jobs:   make(chan job, 32),
		queued: make(map[string]struct{}),
	}
	go s.worker()
	go s.cleanupLoop()

	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", s.health)
	mux.HandleFunc("GET /v1/thumbnail", s.getThumbnail)
	mux.HandleFunc("POST /v1/thumbnail", s.requestThumbnail)

	log.Printf("thumbnail service listening on %s; root=%s", cfg.listen, cfg.allowedRoot)
	if err := http.ListenAndServe(cfg.listen, logging(mux)); err != nil {
		log.Fatal(err)
	}
}

func (s *server) health(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_, _ = io.WriteString(w, `{"status":"ok"}`)
}

func (s *server) getThumbnail(w http.ResponseWriter, r *http.Request) {
	path, info, ok := s.authorizedMediaPath(w, r)
	if !ok {
		return
	}
	cacheFile := s.cacheFile(path, info.Size(), info.ModTime())
	file, err := os.Open(cacheFile)
	if errors.Is(err, os.ErrNotExist) {
		writeJSON(w, http.StatusNotFound, apiError{Error: "thumbnail_not_cached"})
		return
	}
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, apiError{Error: "cache_unavailable"})
		return
	}
	defer file.Close()
	_ = os.Chtimes(cacheFile, time.Now(), time.Now())
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "private, max-age=86400")
	w.Header().Set("ETag", `"`+strings.TrimSuffix(filepath.Base(cacheFile), filepath.Ext(cacheFile))+`"`)
	_, _ = io.Copy(w, file)
}

func (s *server) requestThumbnail(w http.ResponseWriter, r *http.Request) {
	path, info, ok := s.authorizedMediaPath(w, r)
	if !ok {
		return
	}
	cacheFile := s.cacheFile(path, info.Size(), info.ModTime())
	if cached, _ := os.Stat(cacheFile); cached != nil && cached.Size() > 0 {
		writeJSON(w, http.StatusOK, map[string]string{"status": "cached"})
		return
	}

	s.queuedMu.Lock()
	if _, exists := s.queued[cacheFile]; exists {
		s.queuedMu.Unlock()
		writeJSON(w, http.StatusAccepted, map[string]string{"status": "queued"})
		return
	}
	s.queued[cacheFile] = struct{}{}
	s.queuedMu.Unlock()

	select {
	case s.jobs <- job{path: path, cacheFile: cacheFile}:
		writeJSON(w, http.StatusAccepted, map[string]string{"status": "queued"})
	default:
		s.removeQueued(cacheFile)
		writeJSON(w, http.StatusServiceUnavailable, apiError{Error: "queue_full"})
	}
}

func (s *server) authorizedMediaPath(w http.ResponseWriter, r *http.Request) (string, os.FileInfo, bool) {
	token := strings.TrimSpace(r.Header.Get("X-Auth"))
	if token == "" {
		writeJSON(w, http.StatusUnauthorized, apiError{Error: "missing_authentication"})
		return "", nil, false
	}
	rawPath := r.URL.Query().Get("path")
	resolved, info, err := s.resolveMediaPath(rawPath)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, apiError{Error: err.Error()})
		return "", nil, false
	}
	if !s.validateFileBrowserToken(r.Context(), token, rawPath) {
		writeJSON(w, http.StatusUnauthorized, apiError{Error: "invalid_authentication"})
		return "", nil, false
	}
	return resolved, info, true
}

func (s *server) resolveMediaPath(rawPath string) (string, os.FileInfo, error) {
	if rawPath == "" || !filepath.IsAbs(rawPath) {
		return "", nil, errors.New("invalid_path")
	}
	resolved, err := filepath.EvalSymlinks(filepath.Clean(rawPath))
	if err != nil {
		return "", nil, errors.New("file_not_found")
	}
	rel, err := filepath.Rel(s.cfg.allowedRoot, resolved)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) {
		return "", nil, errors.New("path_outside_media_root")
	}
	info, err := os.Stat(resolved)
	if err != nil || !info.Mode().IsRegular() {
		return "", nil, errors.New("not_a_regular_file")
	}
	return resolved, info, nil
}

func (s *server) validateFileBrowserToken(ctx context.Context, token, remotePath string) bool {
	requestURL := strings.TrimRight(s.cfg.fileBrowserBase, "/") + encodedResourcePath(remotePath)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, requestURL, nil)
	if err != nil {
		return false
	}
	req.Header.Set("X-Auth", token)
	resp, err := s.httpClient.Do(req)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4*1024))
	return resp.StatusCode >= 200 && resp.StatusCode < 300
}

func encodedResourcePath(path string) string {
	clean := filepath.ToSlash(filepath.Clean(path))
	parts := strings.Split(strings.TrimPrefix(clean, "/"), "/")
	for i, part := range parts {
		parts[i] = url.PathEscape(part)
	}
	if len(parts) == 1 && parts[0] == "" {
		return "/api/resources/"
	}
	return "/api/resources/" + strings.Join(parts, "/")
}

func (s *server) cacheFile(path string, size int64, modified time.Time) string {
	key := fmt.Sprintf("%s\x00%d\x00%d", path, size, modified.UnixNano())
	sum := sha256.Sum256([]byte(key))
	return filepath.Join(s.cfg.cacheDir, hex.EncodeToString(sum[:])+".jpg")
}

func (s *server) worker() {
	for next := range s.jobs {
		if err := generateThumbnail(next.path, next.cacheFile); err != nil {
			log.Printf("thumbnail failed for %q: %v", next.path, err)
			_ = os.Remove(next.cacheFile)
		} else {
			log.Printf("thumbnail cached for %q", next.path)
			s.trimCache()
		}
		s.removeQueued(next.cacheFile)
	}
}

func (s *server) removeQueued(cacheFile string) {
	s.queuedMu.Lock()
	delete(s.queued, cacheFile)
	s.queuedMu.Unlock()
}

func generateThumbnail(input, destination string) error {
	duration := probeDuration(input)
	seek := thumbnailSeek(duration)
	temporary := destination + ".tmp.jpg"
	_ = os.Remove(temporary)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "ffmpegthumbnailer",
		"-i", input,
		"-o", temporary,
		"-s", thumbnailSize,
		"-q", thumbnailQuality,
		"-t", formatSeek(seek),
	)
	cmd.Stdout = io.Discard
	var stderr strings.Builder
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("ffmpegthumbnailer: %w: %s", err, strings.TrimSpace(stderr.String()))
	}
	info, err := os.Stat(temporary)
	if err != nil || info.Size() == 0 {
		return errors.New("ffmpegthumbnailer produced no image")
	}
	if err := os.Rename(temporary, destination); err != nil {
		return err
	}
	return nil
}

func probeDuration(input string) time.Duration {
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	output, err := exec.CommandContext(ctx, "ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", input).Output()
	if err != nil {
		return 0
	}
	seconds, err := strconv.ParseFloat(strings.TrimSpace(string(output)), 64)
	if err != nil || seconds <= 0 {
		return 0
	}
	return time.Duration(seconds * float64(time.Second))
}

func thumbnailSeek(duration time.Duration) time.Duration {
	if duration <= 0 {
		return 30 * time.Second
	}
	if duration >= 5*time.Minute {
		return 5 * time.Minute
	}
	seek := time.Duration(float64(duration) * 0.35)
	if seek < 2*time.Second {
		seek = duration / 2
	}
	if seek >= duration {
		seek = duration / 2
	}
	return seek
}

func formatSeek(value time.Duration) string {
	total := int(value.Round(time.Second).Seconds())
	if total < 0 {
		total = 0
	}
	return fmt.Sprintf("%02d:%02d:%02d", total/3600, (total%3600)/60, total%60)
}

type cacheEntry struct {
	path     string
	size     int64
	accessed time.Time
}

func (s *server) cleanupLoop() {
	ticker := time.NewTicker(30 * time.Minute)
	defer ticker.Stop()
	for range ticker.C {
		s.trimCache()
	}
}

func (s *server) trimCache() {
	entries, err := os.ReadDir(s.cfg.cacheDir)
	if err != nil {
		return
	}
	files := make([]cacheEntry, 0, len(entries))
	var total int64
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".jpg") {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			continue
		}
		total += info.Size()
		files = append(files, cacheEntry{filepath.Join(s.cfg.cacheDir, entry.Name()), info.Size(), info.ModTime()})
	}
	if total <= maxCacheBytes {
		return
	}
	sort.Slice(files, func(i, j int) bool { return files[i].accessed.Before(files[j].accessed) })
	for _, file := range files {
		if total <= maxCacheBytes {
			break
		}
		if os.Remove(file.path) == nil {
			total -= file.size
		}
	}
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func logging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		next.ServeHTTP(w, r)
		log.Printf("%s %s %s", r.Method, r.URL.Path, time.Since(started).Round(time.Millisecond))
	})
}
