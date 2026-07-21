package main

import (
    "reflect"
    "strings"
    "testing"
)

func TestNamedTunnelArgumentOrder(t *testing.T) {
    cfg := Config{Mode: "run", URL: "http://localhost:8090", Tunnel: "spring-suite-test", ExtraArgs: []string{"--no-autoupdate"}}
    want := []string{"tunnel", "--no-autoupdate", "--url", "http://localhost:8090", "run", "spring-suite-test"}
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
