package main

import (
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestNamedTunnelArgumentOrder(t *testing.T) {
	cfg := Config{
		Mode: "run", URL: "http://localhost:8090", Tunnel: "spring-suite-test",
		ConfigPath:      `C:\Users\Aiden\.cloudflared\config.yml`,
		CredentialsFile: `C:\Users\Aiden\.cloudflared\tunnel.json`,
		ExtraArgs:       []string{"--no-autoupdate"},
	}
	want := []string{
		"tunnel", "--no-autoupdate", "--config", `C:\Users\Aiden\.cloudflared\config.yml`,
		"run", "spring-suite-test",
	}
	if got := cloudflaredArgs(cfg); !reflect.DeepEqual(got, want) {
		t.Fatalf("cloudflaredArgs() = %#v, want %#v", got, want)
	}
}

func TestLocalEnvPreservesNamedTunnelCredentialsHome(t *testing.T) {
	base := []string{"USERPROFILE=C:\\Users\\Aiden", "HOME=C:\\Users\\Aiden", "CLOUDFLARED_HOME=C:\\Credentials", "XDG_CACHE_HOME=old"}
	got := localEnv(base, "runtime", "new-cache")
	joined := strings.Join(got, "\n")
	for _, expected := range base[:3] {
		if !strings.Contains(joined, expected) {
			t.Fatalf("environment lost %q: %v", expected, got)
		}
	}
	if !strings.Contains(joined, "XDG_CACHE_HOME=new-cache") || strings.Contains(joined, "XDG_CACHE_HOME=old") {
		t.Fatalf("cache environment not replaced: %v", got)
	}
}

func TestResolveCloudflaredFromEnvironmentOverride(t *testing.T) {
	executable := filepath.Join(t.TempDir(), executableFileName("cloudflared-custom"))
	if err := os.WriteFile(executable, []byte("test"), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("SPRING_SUITE_CLOUDFLARED_EXECUTABLE", executable)

	got, err := resolveCloudflared("cloudflared")
	if err != nil {
		t.Fatal(err)
	}
	want, _ := filepath.Abs(executable)
	if got != filepath.Clean(want) {
		t.Fatalf("resolveCloudflared() = %q, want %q", got, want)
	}
}

func TestResolveCloudflaredFromPath(t *testing.T) {
	t.Setenv("SPRING_SUITE_CLOUDFLARED_EXECUTABLE", "")
	bin := t.TempDir()
	executable := filepath.Join(bin, executableFileName("cloudflared"))
	if err := os.WriteFile(executable, []byte("test"), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", bin)

	got, err := resolveCloudflared("cloudflared")
	if err != nil {
		t.Fatal(err)
	}
	want, _ := filepath.Abs(executable)
	if got != filepath.Clean(want) {
		t.Fatalf("resolveCloudflared() = %q, want %q", got, want)
	}
}

func TestNamedTunnelNeverContainsQuickTunnelURL(t *testing.T) {
	cfg := Config{Mode: "run", URL: "http://localhost:8090", Tunnel: "named", ConfigPath: "config.yml"}
	got := cloudflaredArgs(cfg)
	for _, arg := range got {
		if arg == "--url" || arg == cfg.URL {
			t.Fatalf("named tunnel command contains quick-tunnel URL argument: %#v", got)
		}
	}
}

func TestNamedTunnelWithoutConfigUsesExplicitCredentials(t *testing.T) {
	cfg := Config{Mode: "run", Tunnel: "named", CredentialsFile: "credentials.json"}
	want := []string{"tunnel", "run", "--credentials-file", "credentials.json", "named"}
	if got := cloudflaredArgs(cfg); !reflect.DeepEqual(got, want) {
		t.Fatalf("cloudflaredArgs() = %#v, want %#v", got, want)
	}
}
