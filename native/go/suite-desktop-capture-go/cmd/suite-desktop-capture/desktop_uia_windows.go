//go:build windows

package main

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"runtime"
	"sort"
	"strings"

	uia "github.com/auuunya/go-element"
	"github.com/lxn/win"
)

type uiaSelector struct {
	WindowHandle uint64 `json:"windowHandle"`
	AutomationID string `json:"automationId,omitempty"`
	Name         string `json:"name,omitempty"`
	ClassName    string `json:"className,omitempty"`
	ControlType  int32  `json:"controlType"`
	Ordinal      int    `json:"ordinal"`
}

type uiaSession struct {
	automation *uia.IUIAutomation
	root       *uia.IUIAutomationElement
	elements   []*uia.Element
}

func inspectUIAControls(hwnd win.HWND) ([]DesktopControl, *DesktopControl, error) {
	session, err := openUIASession(uintptr(hwnd))
	if err != nil {
		return nil, nil, err
	}
	defer session.close()

	fields := make([]DesktopControl, 0, 32)
	for _, element := range session.elements {
		fieldType, role, ok := classifyUIAControl(element.CurrentControlType, element.CurrentIsPassword != 0)
		if !ok {
			continue
		}
		if element.CurrentIsOffscreen != 0 {
			continue
		}
		label := strings.TrimSpace(element.CurrentName)
		if label == "" {
			label = strings.TrimSpace(element.CurrentAutomationId)
		}
		if label == "" {
			label = strings.TrimSpace(element.CurrentLocalizedControlType)
		}
		if label == "" {
			label = fmt.Sprintf("%s %d", role, len(fields)+1)
		}
		selector := uiaSelector{
			WindowHandle: uint64(hwnd),
			AutomationID: element.CurrentAutomationId,
			Name:         element.CurrentName,
			ClassName:    element.CurrentClassName,
			ControlType:  int32(element.CurrentControlType),
			Ordinal:      len(fields),
		}
		selectorID, err := encodeUIASelector(selector)
		if err != nil {
			continue
		}
		bounds := desktopRectFromUIA(element.CurrentBoundingRectangle)
		sensitive := element.CurrentIsPassword != 0 || isSensitiveControl(element.CurrentClassName, 0, label)
		value := ""
		valuePresent := false
		readOnly := false
		if !sensitive && (fieldType == "text" || fieldType == "number" || fieldType == "select") {
			if pattern, patternErr := element.GetValuePattern(); patternErr == nil && pattern != nil {
				if current, valueErr := pattern.Get_CurrentValue(); valueErr == nil {
					value = current
					valuePresent = current != ""
				}
				pattern.Release()
			}
		}
		field := DesktopControl{
			ID:           selectorID,
			Handle:       uint64(element.CurrentNativeWindowHandle),
			Label:        label,
			Name:         firstNonBlank(element.CurrentAutomationId, label),
			Type:         fieldType,
			Role:         role,
			ClassName:    element.CurrentClassName,
			Value:        value,
			ValuePresent: valuePresent,
			Required:     element.CurrentIsRequiredForForm != 0,
			Focused:      element.CurrentHasKeyboardFocus != 0,
			Sensitive:    sensitive,
			ReadOnly:     readOnly,
			Enabled:      element.CurrentIsEnabled != 0,
			Visible:      element.CurrentIsOffscreen == 0,
			Options:      []string{},
			Bounds:       bounds,
			Metadata: map[string]any{
				"adapter":      "windows-uia",
				"windowHandle": uint64(hwnd),
				"automationId": element.CurrentAutomationId,
				"controlType":  int32(element.CurrentControlType),
				"frameworkId":  element.CurrentFrameworkId,
				"nativeHandle": uint64(element.CurrentNativeWindowHandle),
				"selector":     selector,
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
	return fields, focused, nil
}

func performUIAAction(foreground win.HWND, action DesktopFillAction, allowSensitive bool, allowSubmit bool) DesktopFillStep {
	normalized := strings.ToLower(strings.TrimSpace(action.Action))
	step := DesktopFillStep{
		ActionID:      action.ActionID,
		TargetFieldID: action.TargetFieldID,
		Action:        normalized,
		OK:            false,
		Code:          "blocked",
		Message:       "UI Automation action was not performed.",
		Performed:     false,
		Metadata:      map[string]any{"adapter": "windows-uia"},
	}
	selector, err := decodeUIASelector(action.TargetFieldID)
	if err != nil {
		step.Code = "invalid_uia_selector"
		step.Message = err.Error()
		return step
	}
	if selector.WindowHandle != 0 && selector.WindowHandle != uint64(foreground) {
		step.Code = "window_mismatch"
		step.Message = "The UI Automation selector belongs to a different foreground window."
		return step
	}
	if action.Sensitive && !allowSensitive {
		step.Code = "sensitive_blocked"
		step.Message = "Sensitive UI Automation write requires explicit approval."
		return step
	}
	isSubmit := action.Submit || normalized == "submit" || normalized == "click"
	if isSubmit && !allowSubmit {
		step.Code = "submit_blocked"
		step.Message = "Submit/invoke action requires explicit approval."
		return step
	}

	session, err := openUIASession(uintptr(foreground))
	if err != nil {
		step.Code = "uia_unavailable"
		step.Message = err.Error()
		return step
	}
	defer session.close()
	element := findUIAElement(session.elements, selector)
	if element == nil {
		step.Code = "target_missing"
		step.Message = "The approved UI Automation element is no longer present."
		return step
	}
	if element.CurrentIsEnabled == 0 {
		step.Code = "target_disabled"
		step.Message = "The target UI Automation element is disabled."
		return step
	}
	if element.CurrentIsPassword != 0 && !allowSensitive {
		step.Code = "sensitive_blocked"
		step.Message = "Password UI Automation element requires explicit sensitive approval."
		return step
	}

	switch normalized {
	case "fill", "type", "paste":
		pattern, patternErr := element.GetValuePattern()
		if patternErr != nil || pattern == nil {
			step.Code = "value_pattern_missing"
			step.Message = "The target does not expose UIA ValuePattern."
			return step
		}
		err = pattern.SetValue(action.Value)
		pattern.Release()
	case "select":
		err = selectUIAValue(element, session.elements, action.Value)
	case "check", "uncheck":
		err = setUIAToggle(element, normalized == "check")
	case "click", "submit":
		pattern, patternErr := element.GetInvokePattern()
		if patternErr != nil || pattern == nil {
			step.Code = "invoke_pattern_missing"
			step.Message = "The target does not expose UIA InvokePattern."
			return step
		}
		err = pattern.Invoke()
		pattern.Release()
	default:
		step.Code = "unsupported_action"
		step.Message = "Unsupported UI Automation action: " + normalized
		return step
	}
	if err != nil {
		step.Code = "uia_action_failed"
		step.Message = err.Error()
		return step
	}
	step.OK = true
	step.Code = "ok"
	step.Message = "UI Automation action completed."
	step.Performed = true
	step.Metadata = map[string]any{
		"adapter":      "windows-uia",
		"automationId": element.CurrentAutomationId,
		"controlType":  int32(element.CurrentControlType),
	}
	return step
}

func openUIASession(hwnd uintptr) (*uiaSession, error) {
	runtime.LockOSThread()
	_ = uia.CoInitialize()
	instance, err := uia.CreateInstance(uia.CLSID_CUIAutomation, uia.IID_IUIAutomation, uia.CLSCTX_INPROC_SERVER)
	if err != nil {
		uia.CoUninitialize()
		runtime.UnlockOSThread()
		return nil, fmt.Errorf("create UI Automation client: %w", err)
	}
	automation := uia.NewIUIAutomation(uia.NewIUnKnown(instance))
	root, err := uia.ElementFromHandle(automation, hwnd)
	if err != nil || root == nil {
		automation.Release()
		uia.CoUninitialize()
		runtime.UnlockOSThread()
		if err == nil {
			err = fmt.Errorf("UI Automation root is nil")
		}
		return nil, fmt.Errorf("resolve UI Automation root: %w", err)
	}

	cache, err := automation.CreateCacheRequest()
	if err != nil || cache == nil {
		root.Release()
		automation.Release()
		uia.CoUninitialize()
		runtime.UnlockOSThread()
		return nil, fmt.Errorf("create UI Automation cache request: %w", err)
	}
	addUIACacheProperties(cache)
	condition := automation.CreateTrueCondition()
	array, err := root.FindAllBuildCache(uia.TreeScope_Subtree, condition, cache)
	// go-element v1.0.1 exposes invalid Release vtables for condition/cache objects.
	// The sidecar uses one-shot inspection processes, so Windows reclaims them at process exit.
	if err != nil || array == nil {
		root.Release()
		automation.Release()
		uia.CoUninitialize()
		runtime.UnlockOSThread()
		if err == nil {
			err = fmt.Errorf("UI Automation element array is nil")
		}
		return nil, fmt.Errorf("enumerate UI Automation tree: %w", err)
	}
	defer array.Release()

	count := int(array.Get_Length())
	if count > 5000 {
		count = 5000
	}
	elements := make([]*uia.Element, 0, count)
	for i := 0; i < count; i++ {
		raw, elementErr := array.GetElement(int32(i))
		if elementErr != nil || raw == nil {
			continue
		}
		element := uia.NewElement(raw)
		element.Populate(true)
		element.CurrentBoundingRectangle = raw.Get_CurrentBoundingRectangle()
		element.CurrentHasKeyboardFocus = raw.Get_CurrentHasKeyboardFocus()
		element.CurrentIsOffscreen = raw.Get_CurrentIsOffscreen()
		element.CurrentIsPassword = raw.Get_CurrentIsPassword()
		element.CurrentIsRequiredForForm = raw.Get_CurrentIsRequiredForForm()
		element.CurrentNativeWindowHandle = raw.Get_CurrentNativeWindowHandle()
		element.CurrentFrameworkId, _ = raw.Get_CurrentFrameworkId()
		elements = append(elements, element)
	}
	return &uiaSession{automation: automation, root: root, elements: elements}, nil
}

func (session *uiaSession) close() {
	if session == nil {
		return
	}
	// go-element v1.0.1 currently exposes invalid COM Release vtables for
	// several UIA objects on Windows/amd64. SpringSuite invokes inspect/fill
	// as one-shot sidecar processes, so object lifetime is bounded by process
	// lifetime. Avoiding Release is safer than crashing the desktop agent.
	uia.CoUninitialize()
	runtime.UnlockOSThread()
}

func addUIACacheProperties(cache *uia.IUIAutomationCacheRequest) {
	for _, property := range []uia.PropertyId{
		uia.UIA_NamePropertyId,
		uia.UIA_ClassNamePropertyId,
		uia.UIA_ControlTypePropertyId,
		uia.UIA_AutomationIdPropertyId,
		uia.UIA_IsEnabledPropertyId,
		uia.UIA_ProcessIdPropertyId,
		uia.UIA_LocalizedControlTypePropertyId,
		uia.UIA_BoundingRectanglePropertyId,
		uia.UIA_HasKeyboardFocusPropertyId,
		uia.UIA_IsOffscreenPropertyId,
		uia.UIA_IsPasswordPropertyId,
		uia.UIA_IsRequiredForFormPropertyId,
		uia.UIA_NativeWindowHandlePropertyId,
		uia.UIA_FrameworkIdPropertyId,
	} {
		_ = cache.AddProperty(property)
	}
	for _, pattern := range []uia.PatternId{
		uia.UIA_ValuePatternId,
		uia.UIA_InvokePatternId,
		uia.UIA_TogglePatternId,
		uia.UIA_SelectionItemPatternId,
		uia.UIA_ExpandCollapsePatternId,
	} {
		_ = cache.AddPattern(pattern)
	}
	_ = cache.Put_TreeScope(uia.TreeScope_Subtree)
}

func classifyUIAControl(controlType uia.ControlTypeId, password bool) (string, string, bool) {
	switch controlType {
	case uia.UIA_EditControlTypeId:
		if password {
			return "password", "textbox", true
		}
		return "text", "textbox", true
	case uia.UIA_ComboBoxControlTypeId:
		return "select", "combobox", true
	case uia.UIA_CheckBoxControlTypeId:
		return "checkbox", "checkbox", true
	case uia.UIA_RadioButtonControlTypeId:
		return "radio", "radio", true
	default:
		return "", "", false
	}
}

func desktopRectFromUIA(rect *uia.TagRect) DesktopRect {
	if rect == nil {
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

func encodeUIASelector(selector uiaSelector) (string, error) {
	data, err := json.Marshal(selector)
	if err != nil {
		return "", err
	}
	return "uia:" + base64.RawURLEncoding.EncodeToString(data), nil
}

func decodeUIASelector(id string) (uiaSelector, error) {
	raw := strings.TrimSpace(id)
	if !strings.HasPrefix(strings.ToLower(raw), "uia:") {
		return uiaSelector{}, fmt.Errorf("not a UI Automation selector")
	}
	data, err := base64.RawURLEncoding.DecodeString(raw[4:])
	if err != nil {
		return uiaSelector{}, fmt.Errorf("decode UI Automation selector: %w", err)
	}
	var selector uiaSelector
	if err := json.Unmarshal(data, &selector); err != nil {
		return uiaSelector{}, fmt.Errorf("parse UI Automation selector: %w", err)
	}
	return selector, nil
}

func findUIAElement(elements []*uia.Element, selector uiaSelector) *uia.Element {
	relevantOrdinal := 0
	var positional *uia.Element
	for _, element := range elements {
		_, _, relevant := classifyUIAControl(element.CurrentControlType, element.CurrentIsPassword != 0)
		if !relevant {
			continue
		}
		if selector.AutomationID != "" && element.CurrentAutomationId == selector.AutomationID {
			if selector.ControlType == 0 || int32(element.CurrentControlType) == selector.ControlType {
				return element
			}
		}
		if selector.Name != "" && element.CurrentName == selector.Name &&
			(selector.ControlType == 0 || int32(element.CurrentControlType) == selector.ControlType) &&
			(selector.ClassName == "" || element.CurrentClassName == selector.ClassName) {
			return element
		}
		if relevantOrdinal == selector.Ordinal {
			positional = element
		}
		relevantOrdinal++
	}
	return positional
}

func selectUIAValue(element *uia.Element, elements []*uia.Element, value string) error {
	if expand, err := element.GetExpandCollapsePattern(); err == nil && expand != nil {
		_ = expand.Expand()
		expand.Release()
	}
	for _, candidate := range elements {
		if strings.EqualFold(strings.TrimSpace(candidate.CurrentName), strings.TrimSpace(value)) {
			if pattern, err := candidate.GetSelectionItemPattern(); err == nil && pattern != nil {
				selectErr := pattern.Select()
				pattern.Release()
				return selectErr
			}
		}
	}
	if pattern, err := element.GetValuePattern(); err == nil && pattern != nil {
		setErr := pattern.SetValue(value)
		pattern.Release()
		return setErr
	}
	return fmt.Errorf("UIA selection item or value pattern was not found")
}

func setUIAToggle(element *uia.Element, desired bool) error {
	pattern, err := element.GetTogglePattern()
	if err != nil || pattern == nil {
		return fmt.Errorf("UIA TogglePattern is not available")
	}
	defer pattern.Release()
	current, err := pattern.Get_CurrentToggleState()
	if err != nil {
		return err
	}
	isOn := current == uia.ToggleState_On
	if isOn == desired {
		return nil
	}
	return pattern.Toggle()
}

func firstNonBlank(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return strings.TrimSpace(value)
		}
	}
	return ""
}
