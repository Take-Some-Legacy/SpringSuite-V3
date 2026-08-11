package main

import (
	"bufio"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const protocolVersion = 1

type Request struct {
	V          int    `json:"v"`
	ID         string `json:"id"`
	Op         string `json:"op"`
	Root       string `json:"root"`
	Path       string `json:"path,omitempty"`
	MaxDepth   int    `json:"maxDepth,omitempty"`
	MaxEntries int    `json:"maxEntries,omitempty"`
	MaxBytes   int64  `json:"maxBytes,omitempty"`
}

type Response struct {
	V     int         `json:"v"`
	ID    string      `json:"id"`
	OK    bool        `json:"ok"`
	Data  interface{} `json:"data,omitempty"`
	Error *ErrorBody  `json:"error,omitempty"`
}

type ErrorBody struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type Entry struct {
	Path       string `json:"path"`
	Type       string `json:"type"`
	SizeBytes  int64  `json:"sizeBytes"`
	ModifiedAt string `json:"modifiedAt"`
}

func main() {
	scanner := bufio.NewScanner(os.Stdin)
	scanner.Buffer(make([]byte, 0, 1024*1024), 32*1024*1024)
	encoder := json.NewEncoder(os.Stdout)

	for scanner.Scan() {
		var req Request
		if err := json.Unmarshal(scanner.Bytes(), &req); err != nil {
			write(encoder, fail("", "INVALID_JSON", err.Error()))
			continue
		}
		write(encoder, handle(req))
	}

	if err := scanner.Err(); err != nil {
		write(encoder, fail("", "SCANNER_ERROR", err.Error()))
	}
}

func write(encoder *json.Encoder, resp Response) {
	_ = encoder.Encode(resp)
}

func handle(req Request) Response {
	if req.V != protocolVersion {
		return fail(req.ID, "UNSUPPORTED_PROTOCOL", fmt.Sprintf("expected protocol v%d", protocolVersion))
	}
	if req.ID == "" {
		return fail(req.ID, "MISSING_ID", "request id is required")
	}

	switch req.Op {
	case "ping":
		return ok(req.ID, map[string]interface{}{"pong": true, "protocolVersion": protocolVersion})
	case "capabilities":
		return ok(req.ID, map[string]interface{}{"protocolVersion": protocolVersion, "ops": []string{"ping", "capabilities", "list", "walk", "readAll"}})
	case "list":
		return opList(req)
	case "walk":
		return opWalk(req)
	case "readAll":
		return opReadAll(req)
	default:
		return fail(req.ID, "UNKNOWN_OPERATION", "unknown op: "+req.Op)
	}
}

func opList(req Request) Response {
	full, _, err := safeJoin(req.Root, req.Path)
	if err != nil {
		return fail(req.ID, "INVALID_PATH", err.Error())
	}
	items, err := os.ReadDir(full)
	if err != nil {
		return fail(req.ID, "LIST_FAILED", err.Error())
	}

	rootAbs := absClean(req.Root)
	capacity := len(items)
	if req.MaxEntries > 0 && req.MaxEntries < capacity {
		capacity = req.MaxEntries
	}
	entries := make([]Entry, 0, capacity)
	for _, item := range items {
		if req.MaxEntries > 0 && len(entries) >= req.MaxEntries {
			break
		}
		child := filepath.Join(full, item.Name())
		info, err := os.Lstat(child)
		if err != nil {
			continue
		}
		rel, err := filepath.Rel(rootAbs, child)
		if err != nil {
			continue
		}
		entries = append(entries, toEntry(rel, info))
	}

	return ok(req.ID, map[string]interface{}{"entries": entries})
}

func opWalk(req Request) Response {
	full, _, err := safeJoin(req.Root, req.Path)
	if err != nil {
		return fail(req.ID, "INVALID_PATH", err.Error())
	}
	if req.MaxDepth < 0 {
		req.MaxDepth = 0
	}

	rootAbs := absClean(req.Root)
	entries := make([]Entry, 0, 1024)
	walkErr := filepath.WalkDir(full, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		rel, err := filepath.Rel(rootAbs, path)
		if err != nil || rel == "." {
			return nil
		}
		if req.MaxDepth > 0 && depthOf(rel) > req.MaxDepth {
			if d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if req.MaxEntries > 0 && len(entries) >= req.MaxEntries {
			return filepath.SkipAll
		}
		info, err := os.Lstat(path)
		if err != nil {
			return nil
		}
		entries = append(entries, toEntry(rel, info))
		return nil
	})
	if walkErr != nil {
		return fail(req.ID, "WALK_FAILED", walkErr.Error())
	}
	return ok(req.ID, map[string]interface{}{"entries": entries})
}

func opReadAll(req Request) Response {
	full, _, err := safeJoin(req.Root, req.Path)
	if err != nil {
		return fail(req.ID, "INVALID_PATH", err.Error())
	}
	file, err := os.Open(full)
	if err != nil {
		return fail(req.ID, "OPEN_FAILED", err.Error())
	}
	defer file.Close()

	var reader io.Reader = file
	if req.MaxBytes > 0 {
		reader = io.LimitReader(file, req.MaxBytes+1)
	}
	content, err := io.ReadAll(reader)
	if err != nil {
		return fail(req.ID, "READ_FAILED", err.Error())
	}
	truncated := false
	if req.MaxBytes > 0 && int64(len(content)) > req.MaxBytes {
		content = content[:req.MaxBytes]
		truncated = true
	}
	return ok(req.ID, map[string]interface{}{
		"bytesRead": len(content),
		"truncated": truncated,
		"base64":    base64.StdEncoding.EncodeToString(content),
	})
}

func safeJoin(root string, relPath string) (string, string, error) {
	if root == "" {
		return "", "", errors.New("missing root")
	}
	rootAbs := absClean(root)
	if relPath == "" {
		relPath = "."
	}
	if filepath.IsAbs(relPath) {
		return "", "", errors.New("absolute path is not allowed")
	}
	candidate := absClean(filepath.Join(rootAbs, relPath))
	rel, err := filepath.Rel(rootAbs, candidate)
	if err != nil {
		return "", "", err
	}
	if rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) {
		return "", "", errors.New("path escapes root")
	}
	return candidate, rel, nil
}

func absClean(path string) string {
	abs, err := filepath.Abs(path)
	if err != nil {
		return filepath.Clean(path)
	}
	return filepath.Clean(abs)
}

func toEntry(path string, info fs.FileInfo) Entry {
	entryType := "other"
	if info.Mode()&os.ModeSymlink != 0 {
		entryType = "symlink"
	} else if info.IsDir() {
		entryType = "directory"
	} else if info.Mode().IsRegular() {
		entryType = "file"
	}
	size := info.Size()
	if info.IsDir() {
		size = 0
	}
	return Entry{
		Path:       filepath.ToSlash(path),
		Type:       entryType,
		SizeBytes:  size,
		ModifiedAt: info.ModTime().UTC().Format(time.RFC3339Nano),
	}
}

func ok(id string, data interface{}) Response {
	return Response{V: protocolVersion, ID: id, OK: true, Data: data}
}

func fail(id string, code string, message string) Response {
	return Response{V: protocolVersion, ID: id, OK: false, Error: &ErrorBody{Code: code, Message: message}}
}

func depthOf(rel string) int {
	rel = filepath.Clean(rel)
	if rel == "." {
		return 0
	}
	return strings.Count(filepath.ToSlash(rel), "/") + 1
}

func min(a int, b int) int {
	if a < b {
		return a
	}
	return b
}
