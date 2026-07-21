//go:build windows

package main

import (
	"fmt"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"time"
	"unsafe"

	"github.com/lxn/win"
	"golang.org/x/sys/windows"
)

const (
	bsTypeMask        = 0x0000000f
	bsCheckbox        = 0x00000002
	bsAutoCheckbox    = 0x00000003
	bsRadioButton     = 0x00000004
	bs3State          = 0x00000005
	bsAuto3State      = 0x00000006
	bsAutoRadioButton = 0x00000009
	cbErr             = -1
)

type nativeControl struct {
	handle    win.HWND
	className string
	text      string
	bounds    DesktopRect
	style     int32
	focused   bool
	visible   bool
	enabled   bool
}

func desktopInspectionAvailable() bool { return true }
func desktopWriteAvailable() bool      { return true }

func performDesktopInspect() (DesktopInspection, error) {
	return performDesktopInspectWindow(0)
}

func performDesktopInspectWindow(windowHandle uint64) (DesktopInspection, error) {
	foreground := win.HWND(windowHandle)
	if foreground == 0 {
		foreground = win.GetForegroundWindow()
	}
	if foreground == 0 {
		return DesktopInspection{}, fmt.Errorf("no foreground window")
	}

	active := inspectWindow(foreground)
	focus := focusedWindow()
	warnings := []string{}
	fields, focused, uiaErr := inspectUIAControls(foreground)
	adapter := "windows-uia"
	method := "windows-ui-automation"
	if uiaErr != nil {
		warnings = append(warnings, "Windows UI Automation inspection failed: "+uiaErr.Error())
	}
	if len(fields) == 0 {
		native := enumerateNativeControls(foreground, focus)
		fields, focused = buildFields(native, active.Handle)
		adapter = "win32-standard-controls"
		method = "win32-message"
	}
	form := DesktopForm{
		ID:     fmt.Sprintf("hwnd:%d", active.Handle),
		Name:   active.Title,
		Action: "active-window",
		Method: method,
		Fields: fields,
		Metadata: map[string]any{
			"windowHandle": active.Handle,
			"windowClass":  active.ClassName,
			"adapter":      adapter,
		},
	}

	focusedRole := ""
	focusedName := ""
	if focused != nil {
		focusedRole = focused.Role
		focusedName = focused.Label
	}
	screenParts := []string{active.Title}
	for _, field := range fields {
		if field.Label != "" {
			screenParts = append(screenParts, field.Label)
		}
	}
	if len(fields) == 0 {
		warnings = append(warnings, "No fillable controls were exposed by Windows UI Automation or the standard Win32 control tree.")
	}
	context := DesktopFocusContext{
		Platform:           "windows",
		ActiveApplication:  active.ProcessName,
		ActiveWindowTitle:  active.Title,
		URL:                "",
		FocusedElementRole: focusedRole,
		FocusedElementName: focusedName,
		SelectedText:       "",
		ClipboardPreview:   "",
		ScreenText:         strings.Join(screenParts, "\n"),
		Form:               form,
		Metadata: map[string]any{
			"activeWindow":  active,
			"nativeAdapter": adapter,
		},
	}
	return DesktopInspection{
		OK:             true,
		Schema:         "spring-suite.desktop_inspection.v1",
		Source:         appName,
		CapturedAt:     nowUTC(),
		Tool:           map[string]string{"name": appName, "version": appVersion, "goos": runtime.GOOS, "goarch": runtime.GOARCH},
		ActiveWindow:   active,
		FocusedControl: focused,
		Form:           form,
		Context:        context,
		Warnings:       warnings,
		Metadata: map[string]any{
			"fieldCount":       len(fields),
			"foregroundHandle": active.Handle,
			"focusHandle":      uint64(focus),
		},
	}, nil
}

func performDesktopFill(request DesktopFillRequest) (DesktopFillResult, error) {
	foreground := win.GetForegroundWindow()
	if foreground == 0 {
		return DesktopFillResult{}, fmt.Errorf("no foreground window")
	}
	foregroundHandle := uint64(foreground)
	if request.ExpectedWindowHandle != 0 && request.ExpectedWindowHandle != foregroundHandle {
		return DesktopFillResult{
			OK:           false,
			Schema:       "spring-suite.desktop_fill.v1",
			ExecutedAt:   nowUTC(),
			WindowHandle: foregroundHandle,
			Performed:    false,
			Steps:        []DesktopFillStep{},
			Warnings:     []string{"Foreground window changed after approval; no input was performed."},
			Metadata:     map[string]any{"expectedWindowHandle": request.ExpectedWindowHandle},
		}, nil
	}
	if len(request.Actions) == 0 {
		return DesktopFillResult{}, fmt.Errorf("fill request contains no actions")
	}

	steps := make([]DesktopFillStep, 0, len(request.Actions))
	allOK := true
	anyPerformed := false
	for _, action := range request.Actions {
		step := performOneDesktopAction(foreground, action, request.AllowSensitive, request.AllowSubmit)
		steps = append(steps, step)
		if !step.OK {
			allOK = false
		}
		if step.Performed {
			anyPerformed = true
		}
	}
	return DesktopFillResult{
		OK:           allOK,
		Schema:       "spring-suite.desktop_fill.v1",
		ExecutedAt:   nowUTC(),
		WindowHandle: foregroundHandle,
		Performed:    anyPerformed,
		Steps:        steps,
		Warnings:     []string{},
		Metadata:     map[string]any{"actionCount": len(request.Actions)},
	}, nil
}

func performOneDesktopAction(foreground win.HWND, action DesktopFillAction, allowSensitive bool, allowSubmit bool) DesktopFillStep {
	normalized := strings.ToLower(strings.TrimSpace(action.Action))
	if strings.HasPrefix(strings.ToLower(strings.TrimSpace(action.TargetFieldID)), "uia:") {
		return performUIAAction(foreground, action, allowSensitive, allowSubmit)
	}
	step := DesktopFillStep{
		ActionID:      action.ActionID,
		TargetFieldID: action.TargetFieldID,
		Action:        normalized,
		OK:            false,
		Code:          "blocked",
		Message:       "Action was not performed.",
		Performed:     false,
		Metadata:      map[string]any{},
	}

	target, err := parseControlHandle(action.TargetFieldID)
	if err != nil {
		step.Code = "invalid_target"
		step.Message = err.Error()
		return step
	}
	targetRoot := win.GetAncestor(target, win.GA_ROOT)
	if targetRoot == 0 {
		step.Code = "target_missing"
		step.Message = "Target control no longer exists."
		return step
	}
	if targetRoot != foreground && !win.IsChild(foreground, target) {
		step.Code = "window_mismatch"
		step.Message = "Target control does not belong to the approved foreground window."
		return step
	}

	className := getClassName(target)
	style := win.GetWindowLong(target, win.GWL_STYLE)
	nativeSensitive := isSensitiveControl(className, style, "")
	if (action.Sensitive || nativeSensitive) && !allowSensitive {
		step.Code = "sensitive_blocked"
		step.Message = "Sensitive field write requires explicit approval."
		return step
	}
	isSubmit := action.Submit || normalized == "submit" || normalized == "click"
	if isSubmit && !allowSubmit {
		step.Code = "submit_blocked"
		step.Message = "Submit/click action requires explicit approval."
		return step
	}
	if !win.IsWindowEnabled(target) {
		step.Code = "target_disabled"
		step.Message = "Target control is disabled."
		return step
	}

	switch normalized {
	case "fill", "type", "paste":
		if isReadOnlyControl(className, style) {
			step.Code = "target_read_only"
			step.Message = "Target control is read-only."
			return step
		}
		if err := setWindowText(target, action.Value); err != nil {
			step.Code = "write_failed"
			step.Message = err.Error()
			return step
		}
	case "select":
		if err := selectComboValue(target, action.Value); err != nil {
			step.Code = "select_failed"
			step.Message = err.Error()
			return step
		}
	case "check":
		win.SendMessage(target, win.BM_SETCHECK, win.BST_CHECKED, 0)
	case "uncheck":
		win.SendMessage(target, win.BM_SETCHECK, win.BST_UNCHECKED, 0)
	case "click", "submit":
		win.SendMessage(target, win.BM_CLICK, 0, 0)
	default:
		step.Code = "unsupported_action"
		step.Message = "Unsupported native action: " + normalized
		return step
	}

	step.OK = true
	step.Code = "ok"
	step.Message = "Native control action completed."
	step.Performed = true
	step.Metadata = map[string]any{"handle": uint64(target), "className": className}
	return step
}

func inspectWindow(hwnd win.HWND) DesktopWindow {
	var pid uint32
	win.GetWindowThreadProcessId(hwnd, &pid)
	processPath := processImagePath(pid)
	processName := filepath.Base(processPath)
	if processName == "." || processName == "" {
		processName = fmt.Sprintf("pid-%d", pid)
	}
	return DesktopWindow{
		Handle:      uint64(hwnd),
		ProcessID:   pid,
		ProcessName: processName,
		ProcessPath: processPath,
		Title:       getWindowText(hwnd, 4096),
		ClassName:   getClassName(hwnd),
		Bounds:      getWindowBounds(hwnd),
	}
}

func enumerateNativeControls(parent win.HWND, focus win.HWND) []nativeControl {
	controls := make([]nativeControl, 0, 32)
	callback := windows.NewCallback(func(hwnd win.HWND, lParam uintptr) uintptr {
		className := getClassName(hwnd)
		visible := win.IsWindowVisible(hwnd)
		if className != "" && visible {
			controls = append(controls, nativeControl{
				handle:    hwnd,
				className: className,
				text:      getWindowText(hwnd, 2048),
				bounds:    getWindowBounds(hwnd),
				style:     win.GetWindowLong(hwnd, win.GWL_STYLE),
				focused:   hwnd == focus,
				visible:   visible,
				enabled:   win.IsWindowEnabled(hwnd),
			})
		}
		return 1
	})
	win.EnumChildWindows(parent, callback, 0)
	return controls
}

func buildFields(native []nativeControl, windowHandle uint64) ([]DesktopControl, *DesktopControl) {
	labels := make([]nativeControl, 0)
	for _, control := range native {
		if strings.EqualFold(control.className, "Static") && strings.TrimSpace(control.text) != "" {
			labels = append(labels, control)
		}
	}

	fields := make([]DesktopControl, 0)
	for _, control := range native {
		fieldType, role, ok := classifyControl(control)
		if !ok {
			continue
		}
		label := strings.TrimSpace(control.text)
		if fieldType == "text" || fieldType == "password" || fieldType == "select" || fieldType == "number" {
			label = nearestLabel(control, labels)
		}
		if label == "" {
			label = fmt.Sprintf("%s %d", role, len(fields)+1)
		}
		sensitive := isSensitiveControl(control.className, control.style, label)
		readOnly := isReadOnlyControl(control.className, control.style)
		value := ""
		valuePresent := false
		if !sensitive {
			value = control.text
			valuePresent = value != ""
		} else {
			valuePresent = control.text != ""
		}
		options := []string{}
		if fieldType == "select" {
			options = comboOptions(control.handle)
		}
		field := DesktopControl{
			ID:           fmt.Sprintf("hwnd:%d", uint64(control.handle)),
			Handle:       uint64(control.handle),
			Label:        label,
			Name:         label,
			Type:         fieldType,
			Role:         role,
			ClassName:    control.className,
			Value:        value,
			ValuePresent: valuePresent,
			Required:     strings.Contains(label, "*") || strings.Contains(strings.ToLower(label), "required"),
			Focused:      control.focused,
			Sensitive:    sensitive,
			ReadOnly:     readOnly,
			Enabled:      control.enabled,
			Visible:      control.visible,
			Options:      options,
			Bounds:       control.bounds,
			Metadata: map[string]any{
				"nativeHandle": uint64(control.handle),
				"windowHandle": windowHandle,
				"nativeStyle":  uint32(control.style),
				"adapter":      "win32-standard-controls",
			},
		}
		fields = append(fields, field)
	}
	sort.SliceStable(fields, func(i, j int) bool {
		if fields[i].Bounds.Top == fields[j].Bounds.Top {
			return fields[i].Bounds.Left < fields[j].Bounds.Left
		}
		return fields[i].Bounds.Top < fields[j].Bounds.Top
	})
	var focused *DesktopControl
	for i := range fields {
		if fields[i].Focused {
			copy := fields[i]
			focused = &copy
			break
		}
	}
	return fields, focused
}

func classifyControl(control nativeControl) (string, string, bool) {
	className := strings.ToLower(control.className)
	switch {
	case className == "edit" || strings.Contains(className, "richedit"):
		if control.style&win.ES_PASSWORD != 0 {
			return "password", "textbox", true
		}
		if control.style&win.ES_NUMBER != 0 {
			return "number", "textbox", true
		}
		return "text", "textbox", true
	case className == "combobox" || strings.Contains(className, "combobox"):
		return "select", "combobox", true
	case className == "button":
		switch control.style & bsTypeMask {
		case bsCheckbox, bsAutoCheckbox, bs3State, bsAuto3State:
			return "checkbox", "checkbox", true
		case bsRadioButton, bsAutoRadioButton:
			return "radio", "radio", true
		default:
			return "", "", false
		}
	default:
		return "", "", false
	}
}

func nearestLabel(field nativeControl, labels []nativeControl) string {
	best := ""
	bestScore := int64(1<<62 - 1)
	centerY := int64(field.bounds.Top+field.bounds.Bottom) / 2
	for _, label := range labels {
		labelCenterY := int64(label.bounds.Top+label.bounds.Bottom) / 2
		vertical := abs64(centerY - labelCenterY)
		horizontal := int64(field.bounds.Left - label.bounds.Right)
		if horizontal < -20 {
			continue
		}
		if vertical > int64(max32(60, field.bounds.Height*2)) {
			continue
		}
		if horizontal < 0 {
			horizontal = abs64(horizontal) + 200
		}
		score := vertical*4 + horizontal
		if label.bounds.Bottom <= field.bounds.Top {
			above := int64(field.bounds.Top - label.bounds.Bottom)
			if above < 45 {
				score = above*3 + abs64(int64(label.bounds.Left-field.bounds.Left))
			}
		}
		if score < bestScore {
			bestScore = score
			best = strings.TrimSpace(label.text)
		}
	}
	return best
}

func focusedWindow() win.HWND {
	info := windows.GUIThreadInfo{Size: uint32(unsafe.Sizeof(windows.GUIThreadInfo{}))}
	if err := windows.GetGUIThreadInfo(0, &info); err != nil {
		return 0
	}
	return win.HWND(info.Focus)
}

func getWindowText(hwnd win.HWND, limit int) string {
	length := int(win.SendMessage(hwnd, win.WM_GETTEXTLENGTH, 0, 0))
	if length < 0 {
		return ""
	}
	if length > limit {
		length = limit
	}
	buffer := make([]uint16, length+1)
	if len(buffer) == 0 {
		return ""
	}
	win.SendMessage(hwnd, win.WM_GETTEXT, uintptr(len(buffer)), uintptr(unsafe.Pointer(&buffer[0])))
	return strings.TrimSpace(windows.UTF16ToString(buffer))
}

func getClassName(hwnd win.HWND) string {
	buffer := make([]uint16, 256)
	count, err := win.GetClassName(hwnd, &buffer[0], len(buffer))
	if err != nil || count <= 0 {
		return ""
	}
	return windows.UTF16ToString(buffer[:count])
}

func getWindowBounds(hwnd win.HWND) DesktopRect {
	var rect win.RECT
	if !win.GetWindowRect(hwnd, &rect) {
		return DesktopRect{}
	}
	return DesktopRect{
		Left:   rect.Left,
		Top:    rect.Top,
		Right:  rect.Right,
		Bottom: rect.Bottom,
		Width:  rect.Right - rect.Left,
		Height: rect.Bottom - rect.Top,
	}
}

func processImagePath(pid uint32) string {
	handle, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
	if err != nil {
		return ""
	}
	defer windows.CloseHandle(handle)
	buffer := make([]uint16, 32768)
	size := uint32(len(buffer))
	if err := windows.QueryFullProcessImageName(handle, 0, &buffer[0], &size); err != nil {
		return ""
	}
	return windows.UTF16ToString(buffer[:size])
}

func isSensitiveControl(className string, style int32, label string) bool {
	if (strings.EqualFold(className, "Edit") || strings.Contains(strings.ToLower(className), "richedit")) && style&win.ES_PASSWORD != 0 {
		return true
	}
	normalized := strings.ToLower(label)
	for _, hint := range []string{"password", "passcode", "passwd", "pin", "secret", "token", "api key", "cvv", "cvc", "card number", "iban", "bank", "passport", "social security", "ssn"} {
		if strings.Contains(normalized, hint) {
			return true
		}
	}
	return false
}

func isReadOnlyControl(className string, style int32) bool {
	lowered := strings.ToLower(className)
	return (lowered == "edit" || strings.Contains(lowered, "richedit")) && style&win.ES_READONLY != 0
}

func comboOptions(hwnd win.HWND) []string {
	count := int(win.SendMessage(hwnd, win.CB_GETCOUNT, 0, 0))
	if count < 1 || count > 200 {
		return []string{}
	}
	result := make([]string, 0, count)
	for i := 0; i < count; i++ {
		length := int(win.SendMessage(hwnd, win.CB_GETLBTEXTLEN, uintptr(i), 0))
		if length < 0 || length > 2048 {
			continue
		}
		buffer := make([]uint16, length+1)
		ret := int(win.SendMessage(hwnd, win.CB_GETLBTEXT, uintptr(i), uintptr(unsafe.Pointer(&buffer[0]))))
		if ret == cbErr {
			continue
		}
		result = append(result, windows.UTF16ToString(buffer))
	}
	return result
}

func parseControlHandle(fieldID string) (win.HWND, error) {
	raw := strings.TrimSpace(fieldID)
	raw = strings.TrimPrefix(strings.ToLower(raw), "hwnd:")
	handle, err := strconv.ParseUint(raw, 10, 64)
	if err != nil || handle == 0 {
		return 0, fmt.Errorf("invalid native target field id: %s", fieldID)
	}
	return win.HWND(handle), nil
}

func setWindowText(hwnd win.HWND, value string) error {
	ptr, err := windows.UTF16PtrFromString(value)
	if err != nil {
		return fmt.Errorf("encode value: %w", err)
	}
	result := win.SendMessage(hwnd, win.WM_SETTEXT, 0, uintptr(unsafe.Pointer(ptr)))
	runtime.KeepAlive(ptr)
	if result == 0 {
		return fmt.Errorf("WM_SETTEXT was rejected by the target control")
	}
	return nil
}

func selectComboValue(hwnd win.HWND, value string) error {
	ptr, err := windows.UTF16PtrFromString(value)
	if err != nil {
		return fmt.Errorf("encode selection: %w", err)
	}
	result := win.SendMessage(hwnd, win.CB_SELECTSTRING, ^uintptr(0), uintptr(unsafe.Pointer(ptr)))
	runtime.KeepAlive(ptr)
	if int32(result) == cbErr {
		return setWindowText(hwnd, value)
	}
	return nil
}

func nowUTC() string { return time.Now().UTC().Format(time.RFC3339Nano) }

func abs64(value int64) int64 {
	if value < 0 {
		return -value
	}
	return value
}
func max32(a, b int32) int32 {
	if a > b {
		return a
	}
	return b
}
