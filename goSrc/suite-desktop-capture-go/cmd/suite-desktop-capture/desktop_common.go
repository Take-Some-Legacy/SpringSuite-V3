package main

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"runtime"
	"strings"
	"time"
)

type DesktopRect struct {
	Left   int32 `json:"left"`
	Top    int32 `json:"top"`
	Right  int32 `json:"right"`
	Bottom int32 `json:"bottom"`
	Width  int32 `json:"width"`
	Height int32 `json:"height"`
}

type DesktopWindow struct {
	Handle      uint64      `json:"handle"`
	ProcessID   uint32      `json:"processId"`
	ProcessName string      `json:"processName"`
	ProcessPath string      `json:"processPath,omitempty"`
	Title       string      `json:"title"`
	ClassName   string      `json:"className"`
	Bounds      DesktopRect `json:"bounds"`
}

type DesktopControl struct {
	ID           string         `json:"id"`
	Handle       uint64         `json:"handle"`
	Label        string         `json:"label"`
	Name         string         `json:"name"`
	Type         string         `json:"type"`
	Role         string         `json:"role"`
	ClassName    string         `json:"className"`
	Value        string         `json:"value,omitempty"`
	ValuePresent bool           `json:"valuePresent"`
	Placeholder  string         `json:"placeholder,omitempty"`
	Required     bool           `json:"required"`
	Focused      bool           `json:"focused"`
	Sensitive    bool           `json:"sensitive"`
	ReadOnly     bool           `json:"readOnly"`
	Enabled      bool           `json:"enabled"`
	Visible      bool           `json:"visible"`
	Options      []string       `json:"options,omitempty"`
	Bounds       DesktopRect    `json:"bounds"`
	Metadata     map[string]any `json:"metadata,omitempty"`
}

type DesktopForm struct {
	ID       string           `json:"id"`
	Name     string           `json:"name"`
	Action   string           `json:"action"`
	Method   string           `json:"method"`
	Fields   []DesktopControl `json:"fields"`
	Metadata map[string]any   `json:"metadata,omitempty"`
}

type DesktopFocusContext struct {
	Platform           string         `json:"platform"`
	ActiveApplication  string         `json:"activeApplication"`
	ActiveWindowTitle  string         `json:"activeWindowTitle"`
	URL                string         `json:"url"`
	FocusedElementRole string         `json:"focusedElementRole"`
	FocusedElementName string         `json:"focusedElementName"`
	SelectedText       string         `json:"selectedText"`
	ClipboardPreview   string         `json:"clipboardPreview"`
	ScreenText         string         `json:"screenText"`
	Form               DesktopForm    `json:"form"`
	Metadata           map[string]any `json:"metadata,omitempty"`
}

type DesktopInspection struct {
	OK             bool                `json:"ok"`
	Schema         string              `json:"schema"`
	Source         string              `json:"source"`
	CapturedAt     string              `json:"capturedAt"`
	Tool           map[string]string   `json:"tool"`
	ActiveWindow   DesktopWindow       `json:"activeWindow"`
	FocusedControl *DesktopControl     `json:"focusedControl,omitempty"`
	Form           DesktopForm         `json:"form"`
	Context        DesktopFocusContext `json:"context"`
	Warnings       []string            `json:"warnings"`
	Metadata       map[string]any      `json:"metadata,omitempty"`
}

type DesktopFillAction struct {
	ActionID      string         `json:"actionId"`
	Action        string         `json:"action"`
	TargetFieldID string         `json:"targetFieldId"`
	Value         string         `json:"value"`
	Sensitive     bool           `json:"sensitive"`
	Submit        bool           `json:"submit"`
	Metadata      map[string]any `json:"metadata,omitempty"`
}

type DesktopFillRequest struct {
	ExpectedWindowHandle uint64              `json:"expectedWindowHandle"`
	AllowSensitive       bool                `json:"allowSensitive"`
	AllowSubmit          bool                `json:"allowSubmit"`
	Actions              []DesktopFillAction `json:"actions"`
	Metadata             map[string]any      `json:"metadata,omitempty"`
}

type DesktopFillStep struct {
	ActionID      string         `json:"actionId"`
	TargetFieldID string         `json:"targetFieldId"`
	Action        string         `json:"action"`
	OK            bool           `json:"ok"`
	Code          string         `json:"code"`
	Message       string         `json:"message"`
	Performed     bool           `json:"performed"`
	Metadata      map[string]any `json:"metadata,omitempty"`
}

type DesktopFillResult struct {
	OK           bool              `json:"ok"`
	Schema       string            `json:"schema"`
	ExecutedAt   string            `json:"executedAt"`
	WindowHandle uint64            `json:"windowHandle"`
	Performed    bool              `json:"performed"`
	Steps        []DesktopFillStep `json:"steps"`
	Warnings     []string          `json:"warnings"`
	Metadata     map[string]any    `json:"metadata,omitempty"`
}

func inspectCommand(args []string, out io.Writer) error {
	fs := flag.NewFlagSet(appName+" inspect", flag.ContinueOnError)
	jsonOut := fs.Bool("json", true, "emit JSON result")
	windowHandle := fs.Uint64("window-handle", 0, "inspect a specific native window instead of the foreground window")
	if err := fs.Parse(args); err != nil {
		return err
	}
	inspection, err := performDesktopInspectWindow(*windowHandle)
	if err != nil {
		return err
	}
	if !*jsonOut {
		fmt.Fprintf(out, "%s | %s | fields=%d\n", inspection.ActiveWindow.ProcessName, inspection.ActiveWindow.Title, len(inspection.Form.Fields))
		return nil
	}
	return encodeJSON(out, inspection)
}

func fillCommand(args []string, in io.Reader, out io.Writer) error {
	fs := flag.NewFlagSet(appName+" fill", flag.ContinueOnError)
	jsonOut := fs.Bool("json", true, "emit JSON result")
	allowSensitive := fs.Bool("allow-sensitive", false, "allow writes to sensitive controls")
	allowSubmit := fs.Bool("allow-submit", false, "allow submit/click actions")
	expectedWindow := fs.Uint64("expected-window", 0, "expected foreground window handle")
	if err := fs.Parse(args); err != nil {
		return err
	}

	var request DesktopFillRequest
	decoder := json.NewDecoder(io.LimitReader(in, 2<<20))
	if err := decoder.Decode(&request); err != nil {
		if errors.Is(err, io.EOF) {
			return fmt.Errorf("fill request JSON is required on stdin")
		}
		return fmt.Errorf("decode fill request: %w", err)
	}
	request.AllowSensitive = request.AllowSensitive || *allowSensitive
	request.AllowSubmit = request.AllowSubmit || *allowSubmit
	if request.ExpectedWindowHandle == 0 {
		request.ExpectedWindowHandle = *expectedWindow
	}

	result, err := performDesktopFill(request)
	if err != nil {
		return err
	}
	if !*jsonOut {
		fmt.Fprintf(out, "ok=%t performed=%t steps=%d\n", result.OK, result.Performed, len(result.Steps))
		return nil
	}
	return encodeJSON(out, result)
}

func serveCommand(args []string, out io.Writer) error {
	fs := flag.NewFlagSet(appName+" serve", flag.ContinueOnError)
	listen := fs.String("listen", "127.0.0.1:17654", "local HTTP listen address")
	token := fs.String("token", "", "required bearer token; empty disables authentication")
	if err := fs.Parse(args); err != nil {
		return err
	}

	host, _, err := net.SplitHostPort(*listen)
	if err != nil {
		return fmt.Errorf("invalid listen address: %w", err)
	}
	parsed := net.ParseIP(host)
	if parsed == nil || !parsed.IsLoopback() {
		return fmt.Errorf("desktop sidecar may listen only on a loopback address")
	}

	mux := http.NewServeMux()
	protect := func(next http.HandlerFunc) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			if *token != "" {
				got := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
				if subtle.ConstantTimeCompare([]byte(got), []byte(*token)) != 1 {
					writeHTTPJSON(w, http.StatusUnauthorized, map[string]any{"ok": false, "code": "unauthorized", "message": "invalid sidecar token"})
					return
				}
			}
			next(w, r)
		}
	}

	mux.HandleFunc("/health", protect(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			writeHTTPJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "code": "method_not_allowed"})
			return
		}
		writeHTTPJSON(w, http.StatusOK, map[string]any{
			"ok":                  true,
			"schema":              "spring-suite.desktop_agent.health.v1",
			"tool":                map[string]string{"name": appName, "version": appVersion, "goos": runtime.GOOS, "goarch": runtime.GOARCH},
			"inspectionAvailable": desktopInspectionAvailable(),
			"writeAvailable":      desktopWriteAvailable(),
		})
	}))

	mux.HandleFunc("/v1/inspect", protect(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodPost {
			writeHTTPJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "code": "method_not_allowed"})
			return
		}
		inspection, err := performDesktopInspect()
		if err != nil {
			writeHTTPJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "code": "inspect_failed", "message": err.Error()})
			return
		}
		writeHTTPJSON(w, http.StatusOK, inspection)
	}))

	mux.HandleFunc("/v1/fill", protect(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			writeHTTPJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "code": "method_not_allowed"})
			return
		}
		defer r.Body.Close()
		var request DesktopFillRequest
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 2<<20)).Decode(&request); err != nil {
			writeHTTPJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "code": "invalid_request", "message": err.Error()})
			return
		}
		result, err := performDesktopFill(request)
		if err != nil {
			writeHTTPJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "code": "fill_failed", "message": err.Error()})
			return
		}
		status := http.StatusOK
		if !result.OK {
			status = http.StatusConflict
		}
		writeHTTPJSON(w, status, result)
	}))

	server := &http.Server{
		Addr:              *listen,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       30 * time.Second,
	}
	fmt.Fprintf(out, "{\"ok\":true,\"schema\":\"spring-suite.desktop_agent.ready.v1\",\"listen\":%q}\n", *listen)
	return server.ListenAndServe()
}

func encodeJSON(out io.Writer, value any) error {
	enc := json.NewEncoder(out)
	enc.SetEscapeHTML(false)
	return enc.Encode(value)
}

func writeHTTPJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = encodeJSON(w, value)
}
