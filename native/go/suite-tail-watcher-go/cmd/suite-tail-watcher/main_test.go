package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestTailLines(t *testing.T) {
	tmp := t.TempDir()
	file := filepath.Join(tmp, "app.log")
	if err := os.WriteFile(file, []byte("a\nb\nc\nd\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	lines, err := tailLines(file, 2)
	if err != nil {
		t.Fatal(err)
	}
	if len(lines) != 2 || lines[0] != "c" || lines[1] != "d" {
		t.Fatalf("bad tail: %#v", lines)
	}
}

func TestDiffSnapshots(t *testing.T) {
	now := time.Now()
	oldSnap := map[string]FileState{"a.log": {Path: "a.log", Size: 1, ModTime: now}}
	newSnap := map[string]FileState{
		"a.log": {Path: "a.log", Size: 2, ModTime: now.Add(time.Second)},
		"b.log": {Path: "b.log", Size: 1, ModTime: now},
	}
	events := diffSnapshots(oldSnap, newSnap)
	if len(events) != 2 {
		t.Fatalf("expected 2 events, got %#v", events)
	}
}

func TestDoctorJSON(t *testing.T) {
	tmp := t.TempDir()
	var out bytes.Buffer
	if err := run([]string{"doctor", "--path", tmp}, &out, &out); err != nil {
		t.Fatal(err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(out.Bytes(), &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded["exists"] != true {
		t.Fatalf("expected exists true: %#v", decoded)
	}
}

func TestResolveTailPathDirectory(t *testing.T) {
	tmp := t.TempDir()
	file := filepath.Join(tmp, "spring-suite.log")
	if err := os.WriteFile(file, []byte("ok\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	got, err := resolveTailPath(tmp)
	if err != nil {
		t.Fatal(err)
	}
	if got != file {
		t.Fatalf("expected %q, got %q", file, got)
	}
}
