package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

const appName = "suite-cloudflared-wrapper"
const appVersion = "0.1.0"

var tryCloudflareURL = regexp.MustCompile(`https://[-a-zA-Z0-9.]+\.trycloudflare\.com`)

type Config struct {
	Cloudflared string   `json:"cloudflared"`
	Mode        string   `json:"mode"`
	URL         string   `json:"url"`
	Tunnel      string   `json:"tunnel"`
	RuntimeDir  string   `json:"runtimeDir"`
	ExtraArgs   []string `json:"extraArgs"`
	JSON        bool     `json:"json"`
}

type Event struct {
	Time     string            `json:"time"`
	Type     string            `json:"type"`
	Message  string            `json:"message,omitempty"`
	PID      int               `json:"pid,omitempty"`
	URL      string            `json:"url,omitempty"`
	ExitCode int               `json:"exitCode,omitempty"`
	Runtime  string            `json:"runtimeDir,omitempty"`
	Command  []string          `json:"command,omitempty"`
	Metadata map[string]string `json:"metadata,omitempty"`
}

func main() {
	if err := run(os.Args[1:], os.Stdout, os.Stderr); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run(args []string, stdout io.Writer, stderr io.Writer) error {
	command := "run"
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		command = args[0]
		args = args[1:]
	}

	switch command {
	case "run", "start":
		cfg, err := parseRun(args)
		if err != nil {
			return err
		}
		return runCloudflared(cfg, stdout, stderr)
	case "doctor":
		cfg, err := parseRun(args)
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
		return fmt.Errorf("unknown command: %s", command)
	}
}

func parseRun(args []string) (Config, error) {
	cfg := Config{
		Cloudflared: "cloudflared",
		Mode:        "quick",
		URL:         "http://localhost:8090",
		RuntimeDir:  ".springsuite/cloudflared",
	}

	split := len(args)
	for i, arg := range args {
		if arg == "--" {
			split = i
			cfg.ExtraArgs = append(cfg.ExtraArgs, args[i+1:]...)
			break
		}
	}

	fs := flag.NewFlagSet(appName, flag.ContinueOnError)
	fs.StringVar(&cfg.Cloudflared, "cloudflared", cfg.Cloudflared, "cloudflared executable path")
	fs.StringVar(&cfg.Mode, "mode", cfg.Mode, "quick or run")
	fs.StringVar(&cfg.URL, "url", cfg.URL, "local service URL exposed through cloudflared")
	fs.StringVar(&cfg.Tunnel, "tunnel", cfg.Tunnel, "named tunnel for mode=run")
	fs.StringVar(&cfg.RuntimeDir, "runtime-dir", cfg.RuntimeDir, "local runtime/cache directory")
	fs.BoolVar(&cfg.JSON, "json", cfg.JSON, "emit wrapper events as JSON lines to stderr")
	if err := fs.Parse(args[:split]); err != nil {
		return cfg, err
	}
	cfg.Mode = strings.ToLower(strings.TrimSpace(cfg.Mode))
	if cfg.Mode != "quick" && cfg.Mode != "run" {
		return cfg, fmt.Errorf("bad --mode: %s", cfg.Mode)
	}
	if cfg.URL == "" {
		return cfg, errors.New("--url is required")
	}
	if cfg.Mode == "run" && cfg.Tunnel == "" {
		return cfg, errors.New("--tunnel is required when --mode=run")
	}
	return cfg, nil
}

func runCloudflared(cfg Config, stdout io.Writer, stderr io.Writer) error {
	runtimeDir, cacheDir, err := prepareRuntime(cfg.RuntimeDir)
	if err != nil {
		return err
	}

	command := cloudflaredArgs(cfg)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()

	cmd := exec.CommandContext(ctx, cfg.Cloudflared, command...)
	cmd.Dir = runtimeDir
	cmd.Env = localEnv(os.Environ(), runtimeDir, cacheDir)

	outPipe, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	errPipe, err := cmd.StderrPipe()
	if err != nil {
		return err
	}

	events, closeEvents, err := eventWriter(filepath.Join(runtimeDir, "events.ndjson"), cfg.JSON, stderr)
	if err != nil {
		return err
	}
	defer closeEvents()

	if err := cmd.Start(); err != nil {
		writeEvent(events, Event{Time: now(), Type: "start_failed", Message: err.Error(), Runtime: runtimeDir, Command: append([]string{cfg.Cloudflared}, command...)})
		return err
	}

	writeEvent(events, Event{Time: now(), Type: "started", PID: cmd.Process.Pid, Runtime: runtimeDir, Command: append([]string{cfg.Cloudflared}, command...)})
	_ = writeState(runtimeDir, Event{Time: now(), Type: "running", PID: cmd.Process.Pid, Runtime: runtimeDir, Command: append([]string{cfg.Cloudflared}, command...)})

	done := make(chan struct{}, 2)
	go stream(outPipe, stdout, events, done)
	go stream(errPipe, stderr, events, done)

	err = cmd.Wait()
	<-done
	<-done

	exitCode := 0
	if err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			exitCode = exitErr.ExitCode()
		} else if ctx.Err() != nil {
			exitCode = 130
		} else {
			exitCode = 1
		}
	}
	writeEvent(events, Event{Time: now(), Type: "exited", ExitCode: exitCode, Runtime: runtimeDir})
	_ = writeState(runtimeDir, Event{Time: now(), Type: "exited", ExitCode: exitCode, Runtime: runtimeDir})
	return err
}

func cloudflaredArgs(cfg Config) []string {
	if cfg.Mode == "run" {
		args := []string{"tunnel"}
		args = append(args, cfg.ExtraArgs...)
		args = append(args, "run", "--url", cfg.URL, cfg.Tunnel)
		return args
	}
	args := []string{"tunnel", "--url", cfg.URL}
	args = append(args, cfg.ExtraArgs...)
	return args
}

func prepareRuntime(raw string) (string, string, error) {
	if raw == "" {
		raw = ".springsuite/cloudflared"
	}
	runtimeDir, err := filepath.Abs(raw)
	if err != nil {
		return "", "", err
	}
	cacheDir := filepath.Join(runtimeDir, "cache")
	if err := os.MkdirAll(cacheDir, 0o755); err != nil {
		return "", "", err
	}
	return runtimeDir, cacheDir, nil
}

func localEnv(base []string, runtimeDir string, cacheDir string) []string {
	env := append([]string{}, base...)
	env = append(env,
		"CLOUDFLARED_HOME="+runtimeDir,
		"HOME="+runtimeDir,
		"USERPROFILE="+runtimeDir,
		"XDG_CONFIG_HOME="+runtimeDir,
		"XDG_CACHE_HOME="+cacheDir,
	)
	return env
}

func stream(reader io.Reader, writer io.Writer, events io.Writer, done chan<- struct{}) {
	defer func() { done <- struct{}{} }()
	buf := make([]byte, 4096)
	var pending strings.Builder
	for {
		n, err := reader.Read(buf)
		if n > 0 {
			chunk := string(buf[:n])
			_, _ = io.WriteString(writer, chunk)
			pending.WriteString(chunk)
			text := pending.String()
			if match := tryCloudflareURL.FindString(text); match != "" {
				writeEvent(events, Event{Time: now(), Type: "public_url", URL: match})
				pending.Reset()
			}
			if pending.Len() > 8192 {
				pending.Reset()
			}
		}
		if err != nil {
			return
		}
	}
}

func eventWriter(path string, mirrorJSON bool, stderr io.Writer) (io.Writer, func(), error) {
	file, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return nil, func() {}, err
	}
	if mirrorJSON {
		return io.MultiWriter(file, stderr), func() { _ = file.Close() }, nil
	}
	return file, func() { _ = file.Close() }, nil
}

func writeEvent(writer io.Writer, event Event) {
	if writer == nil {
		return
	}
	data, err := json.Marshal(event)
	if err == nil {
		_, _ = writer.Write(append(data, '\n'))
	}
}

func writeState(runtimeDir string, event Event) error {
	data, err := json.MarshalIndent(event, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(runtimeDir, "state.json"), append(data, '\n'), 0o644)
}

func doctor(cfg Config, stdout io.Writer) error {
	runtimeDir, cacheDir, err := prepareRuntime(cfg.RuntimeDir)
	if err != nil {
		return err
	}
	path, err := exec.LookPath(cfg.Cloudflared)
	status := map[string]any{
		"ok":           err == nil,
		"cloudflared":  cfg.Cloudflared,
		"resolvedPath": path,
		"runtimeDir":   runtimeDir,
		"cacheDir":     cacheDir,
		"mode":         cfg.Mode,
		"url":          cfg.URL,
		"tunnel":       cfg.Tunnel,
	}
	data, _ := json.MarshalIndent(status, "", "  ")
	_, _ = stdout.Write(append(data, '\n'))
	if err != nil {
		return fmt.Errorf("cloudflared not found: %s", cfg.Cloudflared)
	}
	return nil
}

func printHelp(out io.Writer) {
	fmt.Fprint(out, `suite-cloudflared-wrapper

Usage:
  suite-cloudflared-wrapper run [flags] [-- extra cloudflared args]
  suite-cloudflared-wrapper doctor [flags]
  suite-cloudflared-wrapper version

Flags:
  --cloudflared PATH       cloudflared executable, default: cloudflared
  --mode quick|run         quick uses: cloudflared tunnel --url URL
                           run uses:   cloudflared tunnel run --url URL TUNNEL
  --url URL                local target URL, default: http://localhost:8090
  --tunnel NAME            named tunnel for --mode run
  --runtime-dir DIR        local runtime/cache dir, default: .springsuite/cloudflared
  --json                   mirror wrapper events as JSON lines to stderr
`)
}

func now() string { return time.Now().UTC().Format(time.RFC3339Nano) }
