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
	"runtime"
	"strings"
	"time"
)

const appName = "suite-cloudflared-wrapper"
const appVersion = "0.3.0"

var tryCloudflareURL = regexp.MustCompile(`https://[-a-zA-Z0-9.]+\.trycloudflare\.com`)

type Config struct {
	Cloudflared     string   `json:"cloudflared"`
	Mode            string   `json:"mode"`
	URL             string   `json:"url"`
	Tunnel          string   `json:"tunnel"`
	ConfigPath      string   `json:"configPath"`
	CredentialsFile string   `json:"credentialsFile"`
	RuntimeDir      string   `json:"runtimeDir"`
	ExtraArgs       []string `json:"extraArgs"`
	JSON            bool     `json:"json"`
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
	fs.StringVar(&cfg.ConfigPath, "config", cfg.ConfigPath, "cloudflared YAML config file")
	fs.StringVar(&cfg.CredentialsFile, "credentials-file", cfg.CredentialsFile, "named tunnel credentials JSON file")
	fs.StringVar(&cfg.RuntimeDir, "runtime-dir", cfg.RuntimeDir, "local runtime/cache directory")
	fs.BoolVar(&cfg.JSON, "json", cfg.JSON, "emit wrapper events as JSON lines to stderr")
	if err := fs.Parse(args[:split]); err != nil {
		return cfg, err
	}
	cfg.Mode = strings.ToLower(strings.TrimSpace(cfg.Mode))
	if cfg.Mode != "quick" && cfg.Mode != "run" {
		return cfg, fmt.Errorf("bad --mode: %s", cfg.Mode)
	}
	if cfg.Mode == "quick" && cfg.URL == "" {
		return cfg, errors.New("--url is required when --mode=quick")
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
	resolvedCloudflared, err := resolveCloudflared(cfg.Cloudflared)
	if err != nil {
		return err
	}
	cfg.Cloudflared = resolvedCloudflared
	if cfg.ConfigPath, err = resolveOptionalFile(cfg.ConfigPath, "config"); err != nil {
		return err
	}
	if cfg.CredentialsFile, err = resolveOptionalFile(cfg.CredentialsFile, "credentials file"); err != nil {
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
		if cfg.ConfigPath != "" {
			args = append(args, "--config", cfg.ConfigPath)
		}
		args = append(args, "run")
		// A named tunnel obtains ingress and credentials from config.yml.
		// --url belongs to quick tunnels and causes cloudflared tunnel run
		// to reject or misinterpret the command. Only use an explicit
		// credentials file when no config file is available.
		if cfg.ConfigPath == "" && cfg.CredentialsFile != "" {
			args = append(args, "--credentials-file", cfg.CredentialsFile)
		}
		args = append(args, cfg.Tunnel)
		return args
	}
	args := []string{"tunnel", "--url", cfg.URL}
	args = append(args, cfg.ExtraArgs...)
	return args
}

func resolveOptionalFile(raw string, label string) (string, error) {
	value := strings.TrimSpace(raw)
	if value == "" {
		return "", nil
	}
	absolute, err := filepath.Abs(value)
	if err != nil {
		return "", fmt.Errorf("resolve %s: %w", label, err)
	}
	info, err := os.Stat(absolute)
	if err != nil {
		return "", fmt.Errorf("%s does not exist: %s: %w", label, absolute, err)
	}
	if !info.Mode().IsRegular() {
		return "", fmt.Errorf("%s is not a regular file: %s", label, absolute)
	}
	return filepath.Clean(absolute), nil
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
	// Preserve HOME/USERPROFILE/CLOUDFLARED_HOME so cloudflared can discover
	// cert.pem and the named-tunnel credentials in the user's profile.
	return setEnv(env, "XDG_CACHE_HOME", cacheDir)
}

func setEnv(base []string, key string, value string) []string {
	prefix := strings.ToUpper(key) + "="
	result := make([]string, 0, len(base)+1)
	for _, entry := range base {
		if strings.HasPrefix(strings.ToUpper(entry), prefix) {
			continue
		}
		result = append(result, entry)
	}
	return append(result, key+"="+value)
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
	path, err := resolveCloudflared(cfg.Cloudflared)
	if err == nil {
		cfg.ConfigPath, err = resolveOptionalFile(cfg.ConfigPath, "config")
	}
	if err == nil {
		cfg.CredentialsFile, err = resolveOptionalFile(cfg.CredentialsFile, "credentials file")
	}
	status := map[string]any{
		"ok":           err == nil,
		"cloudflared":  cfg.Cloudflared,
		"resolvedPath": path,
		"runtimeDir":   runtimeDir,
		"cacheDir":     cacheDir,
		"mode":         cfg.Mode,
		"url":          cfg.URL,
		"tunnel":       cfg.Tunnel,
		"config":       cfg.ConfigPath,
		"credentials":  cfg.CredentialsFile,
	}
	data, _ := json.MarshalIndent(status, "", "  ")
	_, _ = stdout.Write(append(data, '\n'))
	if err != nil {
		return err
	}
	return nil
}

func resolveCloudflared(configured string) (string, error) {
	value := strings.TrimSpace(configured)
	if override := strings.TrimSpace(os.Getenv("SPRING_SUITE_CLOUDFLARED_EXECUTABLE")); override != "" {
		if resolved, ok := existingExecutable(override); ok {
			return resolved, nil
		}
	}
	if value == "" {
		value = "cloudflared"
	}
	if filepath.IsAbs(value) || strings.ContainsAny(value, `/\`) {
		if resolved, ok := existingExecutable(value); ok {
			return resolved, nil
		}
		return "", fmt.Errorf("cloudflared executable does not exist: %s", value)
	}
	if resolved, err := exec.LookPath(value); err == nil {
		if absolute, absErr := filepath.Abs(resolved); absErr == nil {
			return filepath.Clean(absolute), nil
		}
		return filepath.Clean(resolved), nil
	}

	candidates := make([]string, 0, 12)
	if own, err := os.Executable(); err == nil {
		directory := filepath.Dir(own)
		candidates = append(candidates, filepath.Join(directory, executableFileName(value)))
		candidates = append(candidates, filepath.Join(filepath.Dir(directory), executableFileName(value)))
	}
	if runtime.GOOS == "windows" {
		candidates = append(candidates,
			joinEnv("LOCALAPPDATA", "Microsoft", "WinGet", "Links", "cloudflared.exe"),
			joinEnv("ChocolateyInstall", "bin", "cloudflared.exe"),
			joinEnv("SCOOP", "shims", "cloudflared.exe"),
			joinEnv("USERPROFILE", ".cloudflared", "cloudflared.exe"),
			joinEnv("ProgramFiles", "cloudflared", "cloudflared.exe"),
			joinEnv("ProgramFiles(x86)", "cloudflared", "cloudflared.exe"),
		)
	}
	for _, candidate := range candidates {
		if resolved, ok := existingExecutable(candidate); ok {
			return resolved, nil
		}
	}
	return "", fmt.Errorf("cloudflared not found: %s (set SPRING_SUITE_CLOUDFLARED_EXECUTABLE or --cloudflared)", value)
}

func existingExecutable(candidate string) (string, bool) {
	value := strings.TrimSpace(candidate)
	if value == "" {
		return "", false
	}
	variants := []string{value}
	if runtime.GOOS == "windows" && filepath.Ext(value) == "" {
		variants = append([]string{value + ".exe", value + ".cmd", value + ".bat"}, variants...)
	}
	for _, variant := range variants {
		info, err := os.Stat(variant)
		if err != nil || !info.Mode().IsRegular() {
			continue
		}
		absolute, err := filepath.Abs(variant)
		if err != nil {
			return filepath.Clean(variant), true
		}
		return filepath.Clean(absolute), true
	}
	return "", false
}

func executableFileName(value string) string {
	if runtime.GOOS == "windows" && filepath.Ext(value) == "" {
		return value + ".exe"
	}
	return value
}

func joinEnv(name string, parts ...string) string {
	base := strings.TrimSpace(os.Getenv(name))
	if base == "" {
		return ""
	}
	all := append([]string{base}, parts...)
	return filepath.Join(all...)
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
                           run uses:   cloudflared tunnel --url URL run TUNNEL
  --url URL                local target URL, default: http://localhost:8090
  --tunnel NAME            named tunnel for --mode run
  --config FILE            explicit cloudflared YAML config
  --credentials-file FILE  explicit named-tunnel credentials JSON
  --runtime-dir DIR        local runtime/cache dir, default: .springsuite/cloudflared
  --json                   mirror wrapper events as JSON lines to stderr
`)
}

func now() string { return time.Now().UTC().Format(time.RFC3339Nano) }
