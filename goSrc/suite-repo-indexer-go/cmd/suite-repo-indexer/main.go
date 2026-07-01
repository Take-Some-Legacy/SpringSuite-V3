package main

import (
	"crypto/sha256"
	"encoding/hex"
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

const appName = "suite-repo-indexer"
const appVersion = "0.1.0"

type rootsFlag []string

func (r *rootsFlag) String() string { return strings.Join(*r, ",") }
func (r *rootsFlag) Set(v string) error {
	if strings.TrimSpace(v) != "" {
		*r = append(*r, v)
	}
	return nil
}

type Config struct {
	Repositories string   `json:"repositories"`
	Roots        []string `json:"roots"`
	Output       string   `json:"output"`
	Depth        int      `json:"depth"`
	MaxFileSize  int64    `json:"maxFileSize"`
	Hash         bool     `json:"hash"`
}

type Cache struct {
	Repositories []CacheRepo `json:"repositories"`
}

type CacheRepo struct {
	Name     string `json:"name"`
	RootPath string `json:"rootPath"`
	Pinned   bool   `json:"pinned"`
}

type Index struct {
	Schema          string      `json:"schema"`
	GeneratedAt     string      `json:"generatedAt"`
	Tool            ToolInfo    `json:"tool"`
	Input           Config      `json:"input"`
	RepositoryCount int         `json:"repositoryCount"`
	Repositories    []RepoIndex `json:"repositories"`
	Warnings        []string    `json:"warnings,omitempty"`
}

type ToolInfo struct {
	Name    string `json:"name"`
	Version string `json:"version"`
}

type RepoIndex struct {
	Name             string           `json:"name"`
	RootPath         string           `json:"rootPath"`
	GitDirectory     string           `json:"gitDirectory"`
	DescriptorPath   string           `json:"descriptorPath"`
	DescriptorExists bool             `json:"descriptorExists"`
	Branch           string           `json:"branch,omitempty"`
	Head             string           `json:"head,omitempty"`
	Files            FileStats        `json:"files"`
	LanguageBytes    map[string]int64 `json:"languageBytes"`
	DatasetRoots     []string         `json:"datasetRoots"`
	Examples         []string         `json:"examples"`
	Samples          []FileSample     `json:"samples"`
	Errors           []string         `json:"errors,omitempty"`
}

type FileStats struct {
	Count        int   `json:"count"`
	HashedCount  int   `json:"hashedCount"`
	TotalBytes   int64 `json:"totalBytes"`
	SkippedBytes int64 `json:"skippedBytes"`
}
type FileSample struct {
	Path   string `json:"path"`
	Bytes  int64  `json:"bytes"`
	SHA256 string `json:"sha256,omitempty"`
}

func main() {
	if err := run(os.Args[1:], os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run(args []string, out io.Writer) error {
	cmd := "index"
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		cmd, args = args[0], args[1:]
	}
	switch cmd {
	case "index", "scan":
		cfg, err := parse(args)
		if err != nil {
			return err
		}
		idx, err := buildIndex(cfg)
		if err != nil {
			return err
		}
		if err := writeIndex(cfg.Output, idx); err != nil {
			return err
		}
		return writeJSON(out, idx)
	case "doctor":
		cfg, err := parse(args)
		if err != nil {
			return err
		}
		roots, warnings := inputRoots(cfg)
		return writeJSON(out, map[string]any{"tool": appName, "version": appVersion, "repositories": cfg.Repositories, "repositoriesExists": exists(cfg.Repositories), "output": cfg.Output, "roots": roots, "warnings": warnings})
	case "version", "--version", "-v":
		fmt.Fprintf(out, "%s %s\n", appName, appVersion)
		return nil
	case "help", "--help", "-h":
		fmt.Fprint(out, "suite-repo-indexer\n\nUsage:\n  suite-repo-indexer index [--repositories PATH] [--root PATH] [--output PATH]\n  suite-repo-indexer doctor\n")
		return nil
	default:
		return fmt.Errorf("unknown command: %s", cmd)
	}
}

func parse(args []string) (Config, error) {
	cfg := Config{Repositories: ".springsuite/repositories.json", Output: ".springsuite/repo-index.json", Depth: 8, MaxFileSize: 2 * 1024 * 1024, Hash: true}
	var roots rootsFlag
	fs := flag.NewFlagSet(appName, flag.ContinueOnError)
	fs.StringVar(&cfg.Repositories, "repositories", cfg.Repositories, "Suite repositories.json path")
	fs.Var(&roots, "root", "scan root; may be repeated")
	fs.StringVar(&cfg.Output, "output", cfg.Output, "index output path")
	fs.IntVar(&cfg.Depth, "scan-depth", cfg.Depth, "scan depth")
	fs.Int64Var(&cfg.MaxFileSize, "max-file-size", cfg.MaxFileSize, "max hash file size")
	fs.BoolVar(&cfg.Hash, "hash", cfg.Hash, "hash sample files")
	if err := fs.Parse(args); err != nil {
		return cfg, err
	}
	cfg.Roots = append(cfg.Roots, roots...)
	return cfg, nil
}

func buildIndex(cfg Config) (Index, error) {
	roots, warnings := inputRoots(cfg)
	repos := discover(roots, cfg.Depth)
	items := make([]RepoIndex, 0, len(repos))
	for _, repo := range repos {
		items = append(items, indexRepo(repo, cfg))
	}
	sort.Slice(items, func(i, j int) bool { return strings.ToLower(items[i].RootPath) < strings.ToLower(items[j].RootPath) })
	return Index{Schema: "com.takesome.springsuite.repo-index.v1", GeneratedAt: time.Now().UTC().Format(time.RFC3339Nano), Tool: ToolInfo{Name: appName, Version: appVersion}, Input: cfg, RepositoryCount: len(items), Repositories: items, Warnings: warnings}, nil
}

func inputRoots(cfg Config) ([]string, []string) {
	roots := append([]string{}, cfg.Roots...)
	warnings := []string{}
	if cfg.Repositories != "" {
		cache, err := readCache(cfg.Repositories)
		if err != nil && !os.IsNotExist(err) {
			warnings = append(warnings, err.Error())
		}
		for _, r := range cache.Repositories {
			if strings.TrimSpace(r.RootPath) != "" {
				roots = append(roots, r.RootPath)
			}
		}
	}
	if len(roots) == 0 {
		roots = append(roots, ".")
	}
	return dedupeAbs(roots), warnings
}

func readCache(path string) (Cache, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return Cache{}, err
	}
	var c Cache
	if err := json.Unmarshal(b, &c); err != nil {
		return Cache{}, err
	}
	return c, nil
}

func discover(roots []string, depth int) []string {
	set := map[string]struct{}{}
	for _, raw := range roots {
		root, err := filepath.Abs(raw)
		if err != nil {
			continue
		}
		if repo := nearestRepo(root); repo != "" {
			set[repo] = struct{}{}
		}
		walkGit(root, depth, set)
	}
	out := make([]string, 0, len(set))
	for k := range set {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

func nearestRepo(start string) string {
	cur := filepath.Clean(start)
	if st, err := os.Stat(cur); err == nil && !st.IsDir() {
		cur = filepath.Dir(cur)
	}
	for {
		if exists(filepath.Join(cur, ".git")) {
			return cur
		}
		next := filepath.Dir(cur)
		if next == cur {
			return ""
		}
		cur = next
	}
}

func walkGit(root string, maxDepth int, set map[string]struct{}) {
	root = filepath.Clean(root)
	_ = filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || !d.IsDir() {
			return nil
		}
		if skipDir(d.Name()) && path != root {
			return filepath.SkipDir
		}
		if relDepth(root, path) > maxDepth {
			return filepath.SkipDir
		}
		if exists(filepath.Join(path, ".git")) {
			if abs, err := filepath.Abs(path); err == nil {
				set[filepath.Clean(abs)] = struct{}{}
			}
			return filepath.SkipDir
		}
		return nil
	})
}

func indexRepo(root string, cfg Config) RepoIndex {
	idx := RepoIndex{Name: filepath.Base(root), RootPath: root, GitDirectory: filepath.Join(root, ".git"), DescriptorPath: filepath.Join(root, ".springsuite-repository.json"), DescriptorExists: exists(filepath.Join(root, ".springsuite-repository.json")), LanguageBytes: map[string]int64{}, Samples: []FileSample{}, Errors: []string{}}
	idx.Branch, idx.Head = gitHead(root)
	idx.DatasetRoots = present(root, []string{"src/main/java", "src/main/resources", "cmd", "internal", "docs", "configs"})
	idx.Examples = present(root, []string{"docs", "configs", "examples", "suite-dashboard-module/src/main/java", "suite-diagnostics-module/src/main/java"})
	_ = filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			idx.Errors = append(idx.Errors, err.Error())
			return nil
		}
		if d.IsDir() {
			if skipDir(d.Name()) && path != root {
				return filepath.SkipDir
			}
			return nil
		}
		st, err := d.Info()
		if err != nil {
			idx.Errors = append(idx.Errors, err.Error())
			return nil
		}
		size := st.Size()
		idx.Files.Count++
		idx.Files.TotalBytes += size
		idx.LanguageBytes[lang(path)] += size
		if cfg.Hash && size <= cfg.MaxFileSize {
			if h, err := hashFile(path); err == nil {
				idx.Files.HashedCount++
				if len(idx.Samples) < 200 {
					idx.Samples = append(idx.Samples, FileSample{Path: rel(root, path), Bytes: size, SHA256: h})
				}
			} else {
				idx.Errors = append(idx.Errors, err.Error())
			}
		} else {
			idx.Files.SkippedBytes += size
		}
		return nil
	})
	return idx
}

func gitHead(root string) (string, string) {
	b, err := os.ReadFile(filepath.Join(root, ".git", "HEAD"))
	if err != nil {
		return "", ""
	}
	s := strings.TrimSpace(string(b))
	if strings.HasPrefix(s, "ref:") {
		ref := strings.TrimSpace(strings.TrimPrefix(s, "ref:"))
		branch := strings.TrimPrefix(ref, "refs/heads/")
		if rb, err := os.ReadFile(filepath.Join(root, ".git", filepath.FromSlash(ref))); err == nil {
			return branch, strings.TrimSpace(string(rb))
		}
		return branch, ""
	}
	return "", s
}
func writeIndex(path string, idx Index) error {
	if path == "" {
		return nil
	}
	abs, err := filepath.Abs(path)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(abs), 0o755); err != nil {
		return err
	}
	b, err := json.MarshalIndent(idx, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(abs, append(b, '\n'), 0o644)
}
func writeJSON(w io.Writer, v any) error {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	_, err = w.Write(append(b, '\n'))
	return err
}
func hashFile(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}
func present(root string, items []string) []string {
	out := []string{}
	for _, item := range items {
		if exists(filepath.Join(root, filepath.FromSlash(item))) {
			out = append(out, item)
		}
	}
	return out
}
func dedupeAbs(values []string) []string {
	seen := map[string]struct{}{}
	out := []string{}
	for _, v := range values {
		if strings.TrimSpace(v) == "" {
			continue
		}
		abs, err := filepath.Abs(v)
		if err != nil {
			continue
		}
		abs = filepath.Clean(abs)
		key := strings.ToLower(abs)
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		out = append(out, abs)
	}
	return out
}
func skipDir(name string) bool {
	switch strings.ToLower(name) {
	case ".git", ".gradle", ".idea", ".springsuite", "build", "out", "target", "node_modules", "dist", "bin", "vendor":
		return true
	default:
		return false
	}
}
func relDepth(root, path string) int {
	r, err := filepath.Rel(root, path)
	if err != nil || r == "." {
		return 0
	}
	return len(strings.Split(filepath.ToSlash(r), "/"))
}
func rel(root, path string) string {
	r, err := filepath.Rel(root, path)
	if err != nil {
		return filepath.ToSlash(path)
	}
	return filepath.ToSlash(r)
}
func lang(path string) string {
	e := strings.ToLower(filepath.Ext(path))
	if e == "" {
		return "<none>"
	}
	return e
}
func exists(path string) bool { _, err := os.Stat(path); return err == nil }
