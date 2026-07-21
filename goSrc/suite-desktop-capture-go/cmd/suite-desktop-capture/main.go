package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"image"
	"image/png"
	"io"
	"os"
	"runtime"
	"strings"
	"time"

	"github.com/kbinani/screenshot"
)

const appName = "suite-desktop-capture"
const appVersion = "0.2.0"

type Result struct {
	OK             bool              `json:"ok"`
	Schema         string            `json:"schema"`
	CapturedAt     string            `json:"capturedAt"`
	Tool           map[string]string `json:"tool"`
	Target         string            `json:"target"`
	MimeType       string            `json:"mimeType"`
	Format         string            `json:"format"`
	Width          int               `json:"width"`
	Height         int               `json:"height"`
	OriginalWidth  int               `json:"originalWidth"`
	OriginalHeight int               `json:"originalHeight"`
	Scaled         bool              `json:"scaled"`
	PNGBytes       int               `json:"pngBytes"`
	SHA256         string            `json:"sha256"`
	Base64         string            `json:"base64,omitempty"`
	Output         string            `json:"output,omitempty"`
}

func main() {
	if err := run(os.Args[1:], os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run(args []string, out io.Writer) error {
	cmd := "screenshot"
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		cmd = args[0]
		args = args[1:]
	}
	switch cmd {
	case "screenshot", "capture":
		return screenshotCommand(args, out)
	case "inspect":
		return inspectCommand(args, out)
	case "fill":
		return fillCommand(args, os.Stdin, out)
	case "serve":
		return serveCommand(args, out)
	case "doctor":
		return doctor(out)
	case "version", "-v", "--version":
		fmt.Fprintf(out, "%s %s %s/%s\n", appName, appVersion, runtime.GOOS, runtime.GOARCH)
		return nil
	case "help", "-h", "--help":
		printHelp(out)
		return nil
	default:
		return fmt.Errorf("unknown command: %s", cmd)
	}
}

func screenshotCommand(args []string, out io.Writer) error {
	fs := flag.NewFlagSet(appName+" screenshot", flag.ContinueOnError)
	target := fs.String("target", "virtual", "capture target: virtual or primary")
	maxWidth := fs.Int("max-width", 1600, "maximum output PNG width; 0 disables scaling")
	includeBase64 := fs.Bool("base64", true, "include base64 PNG data")
	jsonOut := fs.Bool("json", true, "emit JSON result")
	output := fs.String("output", "", "optional PNG output path")
	if err := fs.Parse(args); err != nil {
		return err
	}

	img, normalizedTarget, err := captureImage(*target)
	if err != nil {
		return err
	}
	originalWidth := img.Bounds().Dx()
	originalHeight := img.Bounds().Dy()
	var final image.Image = img
	if *maxWidth > 0 && originalWidth > *maxWidth {
		final = resizeNearest(img, *maxWidth)
	}

	var buf bytes.Buffer
	if err := png.Encode(&buf, final); err != nil {
		return err
	}
	data := buf.Bytes()
	if strings.TrimSpace(*output) != "" {
		if err := os.WriteFile(*output, data, 0600); err != nil {
			return err
		}
	}
	if !*jsonOut {
		_, err := out.Write(data)
		return err
	}

	sum := sha256.Sum256(data)
	b := final.Bounds()
	result := Result{
		OK:         true,
		Schema:     "spring-suite.desktop_capture.v1",
		CapturedAt: time.Now().UTC().Format(time.RFC3339Nano),
		Tool:       map[string]string{"name": appName, "version": appVersion, "goos": runtime.GOOS, "goarch": runtime.GOARCH},
		Target:     normalizedTarget,
		MimeType:   "image/png",
		Format:     "png",
		Width:      b.Dx(), Height: b.Dy(),
		OriginalWidth: originalWidth, OriginalHeight: originalHeight,
		Scaled:   b.Dx() != originalWidth || b.Dy() != originalHeight,
		PNGBytes: len(data),
		SHA256:   hex.EncodeToString(sum[:]),
		Output:   strings.TrimSpace(*output),
	}
	if *includeBase64 {
		result.Base64 = base64.StdEncoding.EncodeToString(data)
	}
	enc := json.NewEncoder(out)
	enc.SetEscapeHTML(false)
	return enc.Encode(result)
}

func captureImage(target string) (image.Image, string, error) {
	target = strings.ToLower(strings.TrimSpace(target))
	if target == "" || target == "desktop" || target == "all" {
		target = "virtual"
	}
	count := screenshot.NumActiveDisplays()
	if count <= 0 {
		return nil, target, fmt.Errorf("no active displays detected")
	}
	if target == "primary" {
		bounds := screenshot.GetDisplayBounds(0)
		img, err := screenshot.CaptureRect(bounds)
		return img, "primary", err
	}
	if target != "virtual" {
		return nil, target, fmt.Errorf("unsupported target: %s", target)
	}
	union := screenshot.GetDisplayBounds(0)
	for i := 1; i < count; i++ {
		union = union.Union(screenshot.GetDisplayBounds(i))
	}
	dst := image.NewRGBA(image.Rect(0, 0, union.Dx(), union.Dy()))
	for i := 0; i < count; i++ {
		bounds := screenshot.GetDisplayBounds(i)
		img, err := screenshot.CaptureRect(bounds)
		if err != nil {
			return nil, "virtual", err
		}
		offsetX := bounds.Min.X - union.Min.X
		offsetY := bounds.Min.Y - union.Min.Y
		for y := 0; y < bounds.Dy(); y++ {
			for x := 0; x < bounds.Dx(); x++ {
				dst.Set(offsetX+x, offsetY+y, img.At(bounds.Min.X+x, bounds.Min.Y+y))
			}
		}
	}
	return dst, "virtual", nil
}

func resizeNearest(src image.Image, maxWidth int) image.Image {
	b := src.Bounds()
	srcW := b.Dx()
	srcH := b.Dy()
	if maxWidth <= 0 || srcW <= maxWidth {
		return src
	}
	dstW := maxWidth
	dstH := int(float64(srcH) * float64(dstW) / float64(srcW))
	if dstH < 1 {
		dstH = 1
	}
	dst := image.NewRGBA(image.Rect(0, 0, dstW, dstH))
	for y := 0; y < dstH; y++ {
		sy := b.Min.Y + y*srcH/dstH
		for x := 0; x < dstW; x++ {
			sx := b.Min.X + x*srcW/dstW
			dst.Set(x, y, src.At(sx, sy))
		}
	}
	return dst
}

func doctor(out io.Writer) error {
	info := map[string]any{
		"ok":                         true,
		"schema":                     "spring-suite.desktop_capture.doctor.v1",
		"tool":                       map[string]string{"name": appName, "version": appVersion, "goos": runtime.GOOS, "goarch": runtime.GOARCH},
		"captureAvailable":           screenshot.NumActiveDisplays() > 0,
		"displayCount":               screenshot.NumActiveDisplays(),
		"desktopInspectionAvailable": desktopInspectionAvailable(),
		"desktopWriteAvailable":      desktopWriteAvailable(),
	}
	enc := json.NewEncoder(out)
	enc.SetEscapeHTML(false)
	return enc.Encode(info)
}

func printHelp(out io.Writer) {
	fmt.Fprint(out, `suite-desktop-capture

Usage:
  suite-desktop-capture doctor
  suite-desktop-capture screenshot --target virtual --max-width 1600 --json --base64=true
  suite-desktop-capture screenshot --target primary --output screenshot.png --base64=false
  suite-desktop-capture inspect --json
  suite-desktop-capture fill --json < fill-request.json
  suite-desktop-capture serve --listen 127.0.0.1:17654 --token <secret>
`)
}
