package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestDiscoverRepositoriesFromCache(t *testing.T) {
	tmp := t.TempDir()
	repo := filepath.Join(tmp, "repo-a")
	if err := os.MkdirAll(filepath.Join(repo, ".git", "refs", "heads"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(repo, ".git", "HEAD"), []byte("ref: refs/heads/main\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(repo, ".git", "refs", "heads", "main"), []byte("abc123\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(repo, "main.go"), []byte("package main\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	cache := Cache{Repositories: []CacheRepo{{RootPath: repo}}}
	data, _ := json.Marshal(cache)
	cachePath := filepath.Join(tmp, "repositories.json")
	if err := os.WriteFile(cachePath, data, 0o644); err != nil {
		t.Fatal(err)
	}
	idx, err := buildIndex(Config{Repositories: cachePath, Output: filepath.Join(tmp, "index.json"), Depth: 2, MaxFileSize: 1024, Hash: true})
	if err != nil {
		t.Fatal(err)
	}
	if idx.RepositoryCount != 1 {
		t.Fatalf("expected 1 repository, got %d", idx.RepositoryCount)
	}
	if idx.Repositories[0].Branch != "main" {
		t.Fatalf("expected branch main, got %q", idx.Repositories[0].Branch)
	}
	if idx.Repositories[0].Files.HashedCount == 0 {
		t.Fatalf("expected hashed files")
	}
}

func TestNearestRepository(t *testing.T) {
	tmp := t.TempDir()
	repo := filepath.Join(tmp, "repo-b")
	nested := filepath.Join(repo, "a", "b")
	if err := os.MkdirAll(filepath.Join(repo, ".git"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(nested, 0o755); err != nil {
		t.Fatal(err)
	}
	got := nearestRepo(nested)
	if got != repo {
		t.Fatalf("expected %q, got %q", repo, got)
	}
}
