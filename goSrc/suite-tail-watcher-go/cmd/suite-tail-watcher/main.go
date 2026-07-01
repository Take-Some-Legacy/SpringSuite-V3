package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

const appName = "suite-tail-watcher"
const appVersion = "0.1.0"

type Config struct {
	Path      string        `json:"path"`
	Lines     int           `json:"lines"`
	Follow    bool          `json:"follow"`
	JSON      bool          `json:"json"`
	Interval  time.Duration `json:"interval"`
	Recursive bool          `json:"recursive"`
	Once      bool          `json:"once"`
	Tail      bool          `json:"tail"`
}

type Event struct {
	Time    string `json:"time"`
	Type    string `json:"type"`
	Path    string `json:"path,omitempty"`
	Line    string `json:"line,omitempty"`
	Message string `json:"message,omitempty"`
	Size    int64  `json:"size,omitempty"`
	ModTime string `json:"modTime,omitempty"`
}

type FileState struct {
	Path    string    `json:"path"`
	Size    int64     `json:"size"`
	ModTime time.Time `json:"modTime"`
}

func main() {
	if err := run(os.Args[1:], os.Stdout, os.Stderr); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run(args []string, stdout io.Writer, stderr io.Writer) error {
	cmd := "tail"
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		cmd, args = args[0], args[1:]
	}
	switch cmd {
	case "tail":
		cfg, err := parse(args)
		if err != nil {
			return err
		}
		return tailCommand(cfg, stdout)
	case "watch":
		cfg, err := parse(args)
		if err != nil {
			return err
		}
		cfg.Tail = false
		return watchCommand(cfg, stdout)
	case "stream":
		cfg, err := parse(args)
		if err != nil {
			return err
		}
		cfg.JSON = true
		return streamCommand(cfg, stdout)
	case "doctor":
		cfg, err := parse(args)
		if err != nil {
			return err
		}
		return doctor(cfg, stdout)
	case "version", "--version", "-v":
		fmt.Fprintf(stdout, "%s %s\n", appName, appVersion)
		return nil
	case "help", "--help", "-h":
		printHelp(stdout)
		return nil
	default:
		return fmt.Errorf("unknown command: %s", cmd)
	}
}

func parse(args []string) (Config, error) {
	cfg := Config{Path: "logs", Lines: 100, Follow: false, JSON: false, Interval: time.Second, Recursive: true, Once: false, Tail: true}
	fs := flag.NewFlagSet(appName, flag.ContinueOnError)
	fs.StringVar(&cfg.Path, "path", cfg.Path, "file or directory path")
	fs.IntVar(&cfg.Lines, "lines", cfg.Lines, "number of lines for tail")
	fs.BoolVar(&cfg.Follow, "follow", cfg.Follow, "follow appended lines")
	fs.BoolVar(&cfg.JSON, "json", cfg.JSON, "emit JSON lines")
	fs.DurationVar(&cfg.Interval, "interval", cfg.Interval, "poll interval")
	fs.BoolVar(&cfg.Recursive, "recursive", cfg.Recursive, "watch directories recursively")
	fs.BoolVar(&cfg.Once, "once", cfg.Once, "single snapshot then exit")
	fs.BoolVar(&cfg.Tail, "tail", cfg.Tail, "stream command also tails newest log file")
	if err := fs.Parse(args); err != nil {
		return cfg, err
	}
	if cfg.Lines < 0 {
		cfg.Lines = 0
	}
	if cfg.Interval <= 0 {
		cfg.Interval = time.Second
	}
	return cfg, nil
}

func tailCommand(cfg Config, out io.Writer) error {
	path, err := resolveTailPath(cfg.Path)
	if err != nil {
		return err
	}
	lines, err := tailLines(path, cfg.Lines)
	if err != nil {
		return err
	}
	for _, line := range lines {
		emitLine(out, cfg.JSON, path, line)
	}
	if !cfg.Follow {
		return nil
	}
	return followFile(path, cfg, out)
}

func watchCommand(cfg Config, out io.Writer) error {
	oldSnap, err := snapshot(cfg.Path, cfg.Recursive)
	if err != nil {
		return err
	}
	if cfg.Once {
		return writeJSON(out, map[string]any{"time": now(), "type": "snapshot", "path": cfg.Path, "files": oldSnap})
	}
	ticker := time.NewTicker(cfg.Interval)
	defer ticker.Stop()
	for range ticker.C {
		newSnap, err := snapshot(cfg.Path, cfg.Recursive)
		if err != nil {
			emitEvent(out, cfg.JSON, Event{Time: now(), Type: "watch_error", Path: cfg.Path, Message: err.Error()})
			continue
		}
		for _, ev := range diffSnapshots(oldSnap, newSnap) {
			emitEvent(out, cfg.JSON, ev)
		}
		oldSnap = newSnap
	}
	return nil
}

func streamCommand(cfg Config, out io.Writer) error {
	if cfg.Tail {
		path, err := resolveTailPath(cfg.Path)
		if err == nil {
			lines, _ := tailLines(path, cfg.Lines)
			for _, line := range lines {
				emitLine(out, true, path, line)
			}
		}
	}
	return watchCommand(cfg, out)
}

func followFile(path string, cfg Config, out io.Writer) error {
	pos := fileSize(path)
	for {
		time.Sleep(cfg.Interval)
		st, err := os.Stat(path)
		if err != nil {
			emitEvent(out, cfg.JSON, Event{Time: now(), Type: "tail_error", Path: path, Message: err.Error()})
			continue
		}
		if st.Size() < pos {
			pos = 0
		}
		if st.Size() == pos {
			continue
		}
		file, err := os.Open(path)
		if err != nil {
			emitEvent(out, cfg.JSON, Event{Time: now(), Type: "tail_error", Path: path, Message: err.Error()})
			continue
		}
		_, _ = file.Seek(pos, io.SeekStart)
		scanner := bufio.NewScanner(file)
		for scanner.Scan() {
			emitLine(out, cfg.JSON, path, scanner.Text())
		}
		pos, _ = file.Seek(0, io.SeekCurrent)
		_ = file.Close()
	}
}

func tailLines(path string, n int) ([]string, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	buf := make([]string, 0, n)
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		if n == 0 {
			continue
		}
		if len(buf) == n {
			copy(buf, buf[1:])
			buf[n-1] = scanner.Text()
		} else {
			buf = append(buf, scanner.Text())
		}
	}
	return buf, scanner.Err()
}

func snapshot(root string, recursive bool) (map[string]FileState, error) {
	info, err := os.Stat(root)
	if err != nil {
		return nil, err
	}
	result := map[string]FileState{}
	if !info.IsDir() {
		result[root] = state(root, info)
		return result, nil
	}
	walk := func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() {
			if path != root && !recursive {
				return filepath.SkipDir
			}
			if skipDir(d.Name()) && path != root {
				return filepath.SkipDir
			}
			return nil
		}
		st, err := d.Info()
		if err == nil {
			result[path] = state(path, st)
		}
		return nil
	}
	return result, filepath.WalkDir(root, walk)
}

func diffSnapshots(oldSnap, newSnap map[string]FileState) []Event {
	events := []Event{}
	for path, next := range newSnap {
		prev, ok := oldSnap[path]
		if !ok {
			events = append(events, event("file_created", next))
			continue
		}
		if prev.Size != next.Size || !prev.ModTime.Equal(next.ModTime) {
			events = append(events, event("file_modified", next))
		}
	}
	for path, prev := range oldSnap {
		if _, ok := newSnap[path]; !ok {
			events = append(events, Event{Time: now(), Type: "file_deleted", Path: path, Size: prev.Size, ModTime: prev.ModTime.UTC().Format(time.RFC3339Nano)})
		}
	}
	sort.Slice(events, func(i, j int) bool {
		if events[i].Path == events[j].Path {
			return events[i].Type < events[j].Type
		}
		return events[i].Path < events[j].Path
	})
	return events
}

func doctor(cfg Config, out io.Writer) error {
	info, err := os.Stat(cfg.Path)
	status := map[string]any{"tool": appName, "version": appVersion, "path": cfg.Path, "exists": err == nil, "json": cfg.JSON, "interval": cfg.Interval.String(), "recursive": cfg.Recursive}
	if err == nil {
		status["isDir"] = info.IsDir()
		status["size"] = info.Size()
		if tail, err := resolveTailPath(cfg.Path); err == nil {
			status["tailTarget"] = tail
		}
	}
	if err != nil {
		status["error"] = err.Error()
	}
	return writeJSON(out, status)
}

func resolveTailPath(raw string) (string, error) {
	info, err := os.Stat(raw)
	if err != nil {
		return "", err
	}
	if !info.IsDir() {
		return raw, nil
	}
	var files []FileState
	_ = filepath.WalkDir(raw, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		name := strings.ToLower(d.Name())
		if strings.HasSuffix(name, ".log") || strings.HasSuffix(name, ".txt") || strings.HasSuffix(name, ".ndjson") {
			if st, err := d.Info(); err == nil {
				files = append(files, state(path, st))
			}
		}
		return nil
	})
	if len(files) == 0 {
		return "", fmt.Errorf("no tailable log file found in %s", raw)
	}
	sort.Slice(files, func(i, j int) bool { return files[i].ModTime.After(files[j].ModTime) })
	return files[0].Path, nil
}

func emitLine(out io.Writer, asJSON bool, path string, line string) {
	if asJSON {
		emitEvent(out, true, Event{Time: now(), Type: "line", Path: path, Line: line})
		return
	}
	fmt.Fprintln(out, line)
}
func emitEvent(out io.Writer, asJSON bool, ev Event) {
	if ev.Time == "" {
		ev.Time = now()
	}
	if asJSON {
		_ = writeJSON(out, ev)
		return
	}
	if ev.Line != "" {
		fmt.Fprintln(out, ev.Line)
		return
	}
	fmt.Fprintf(out, "%s %s %s\n", ev.Time, ev.Type, ev.Path)
}
func event(kind string, st FileState) Event {
	return Event{Time: now(), Type: kind, Path: st.Path, Size: st.Size, ModTime: st.ModTime.UTC().Format(time.RFC3339Nano)}
}
func state(path string, st os.FileInfo) FileState {
	return FileState{Path: path, Size: st.Size(), ModTime: st.ModTime()}
}
func writeJSON(w io.Writer, v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	_, err = w.Write(append(b, '\n'))
	return err
}
func fileSize(path string) int64 {
	st, err := os.Stat(path)
	if err != nil {
		return 0
	}
	return st.Size()
}
func now() string { return time.Now().UTC().Format(time.RFC3339Nano) }
func skipDir(name string) bool {
	switch strings.ToLower(name) {
	case ".git", ".gradle", ".idea", ".springsuite", "build", "out", "target", "node_modules", "dist", "bin", "vendor":
		return true
	default:
		return false
	}
}
func printHelp(out io.Writer) {
	fmt.Fprint(out, `suite-tail-watcher

Usage:
  suite-tail-watcher tail --path logs --lines 100 --follow
  suite-tail-watcher watch --path logs --json
  suite-tail-watcher stream --path logs
  suite-tail-watcher doctor --path logs
`)
}
