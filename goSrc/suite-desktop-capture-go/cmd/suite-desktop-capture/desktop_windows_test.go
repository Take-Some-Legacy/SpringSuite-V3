//go:build windows

package main

import (
	"fmt"
	"os"
	"testing"
	"time"
	"unsafe"

	"github.com/lxn/win"
	"golang.org/x/sys/windows"
)

type nativeTestForm struct {
	window    win.HWND
	first     win.HWND
	email     win.HWND
	subscribe win.HWND
}

func TestNativeFormInspectAndFill(t *testing.T) {
	form := newNativeTestForm(t)

	inspection, err := performDesktopInspectWindow(uint64(form.window))
	if err != nil {
		t.Fatalf("inspect native form: %v", err)
	}
	if !inspection.OK {
		t.Fatalf("inspection is not OK: %+v", inspection)
	}
	if got := len(inspection.Form.Fields); got != 3 {
		t.Fatalf("expected 3 fillable fields, got %d: %+v", got, inspection.Form.Fields)
	}

	fields := make(map[string]DesktopControl)
	for _, field := range inspection.Form.Fields {
		fields[field.Label] = field
	}
	first, ok := fields["First Name"]
	if !ok {
		t.Fatalf("First Name field was not detected: %+v", inspection.Form.Fields)
	}
	email, ok := fields["Email"]
	if !ok {
		t.Fatalf("Email field was not detected: %+v", inspection.Form.Fields)
	}
	subscribe, ok := fields["Subscribe"]
	if !ok {
		t.Fatalf("Subscribe checkbox was not detected: %+v", inspection.Form.Fields)
	}

	actions := []DesktopFillAction{
		{ActionID: "test:first", Action: "fill", TargetFieldID: first.ID, Value: "Kayla"},
		{ActionID: "test:email", Action: "fill", TargetFieldID: email.ID, Value: "kayla@example.test"},
		{ActionID: "test:subscribe", Action: "check", TargetFieldID: subscribe.ID},
	}
	for _, action := range actions {
		step := performOneDesktopAction(form.window, action, false, false)
		if !step.OK || !step.Performed {
			t.Fatalf("action %s failed: %+v", action.ActionID, step)
		}
	}

	if got := getWindowText(form.first, 512); got != "Kayla" {
		t.Fatalf("first name mismatch: %q", got)
	}
	if got := getWindowText(form.email, 512); got != "kayla@example.test" {
		t.Fatalf("email mismatch: %q", got)
	}
	if got := win.SendMessage(form.subscribe, win.BM_GETCHECK, 0, 0); got != win.BST_CHECKED {
		t.Fatalf("checkbox mismatch: %d", got)
	}
}

func TestNativeFormRejectsForeignTarget(t *testing.T) {
	approved := newNativeTestForm(t)
	foreign := newNativeTestForm(t)

	action := DesktopFillAction{
		ActionID:      "test:foreign",
		Action:        "fill",
		TargetFieldID: fmt.Sprintf("hwnd:%d", uint64(foreign.first)),
		Value:         "must-not-write",
	}
	step := performOneDesktopAction(approved.window, action, false, false)
	if step.OK || step.Performed || step.Code != "window_mismatch" {
		t.Fatalf("foreign target was not blocked: %+v", step)
	}
	if got := getWindowText(foreign.first, 512); got != "" {
		t.Fatalf("foreign field was modified: %q", got)
	}
}

func TestNativeFormBlocksSensitiveAndSubmit(t *testing.T) {
	form := newNativeTestForm(t)

	sensitive := performOneDesktopAction(form.window, DesktopFillAction{
		ActionID:      "test:sensitive",
		Action:        "fill",
		TargetFieldID: fmt.Sprintf("hwnd:%d", uint64(form.first)),
		Value:         "secret",
		Sensitive:     true,
	}, false, false)
	if sensitive.Code != "sensitive_blocked" || sensitive.Performed {
		t.Fatalf("sensitive write was not blocked: %+v", sensitive)
	}

	submit := performOneDesktopAction(form.window, DesktopFillAction{
		ActionID:      "test:submit",
		Action:        "submit",
		TargetFieldID: fmt.Sprintf("hwnd:%d", uint64(form.subscribe)),
		Submit:        true,
	}, false, false)
	if submit.Code != "submit_blocked" || submit.Performed {
		t.Fatalf("submit was not blocked: %+v", submit)
	}
}

func newNativeTestForm(t *testing.T) nativeTestForm {
	t.Helper()
	instance := win.GetModuleHandle(nil)
	className := utf16Test(fmt.Sprintf("SpringSuiteNativeFormTest_%d_%d", os.Getpid(), time.Now().UnixNano()))
	windowProc := windows.NewCallback(func(hwnd win.HWND, message uint32, wParam, lParam uintptr) uintptr {
		return win.DefWindowProc(hwnd, message, wParam, lParam)
	})
	wc := win.WNDCLASSEX{
		CbSize:        uint32(unsafe.Sizeof(win.WNDCLASSEX{})),
		LpfnWndProc:   windowProc,
		HInstance:     instance,
		HbrBackground: win.HBRUSH(win.COLOR_WINDOW + 1),
		LpszClassName: className,
	}
	if win.RegisterClassEx(&wc) == 0 {
		t.Fatal("RegisterClassEx failed")
	}
	window := win.CreateWindowEx(0, className, utf16Test("SpringSuite Native Form Test"), win.WS_OVERLAPPEDWINDOW|win.WS_VISIBLE,
		100, 100, 520, 300, 0, 0, instance, nil)
	if window == 0 {
		t.Fatal("CreateWindowEx failed")
	}
	t.Cleanup(func() {
		win.DestroyWindow(window)
	})

	win.CreateWindowEx(0, utf16Test("STATIC"), utf16Test("First Name"), win.WS_CHILD|win.WS_VISIBLE,
		24, 35, 110, 24, window, 1001, instance, nil)
	first := win.CreateWindowEx(win.WS_EX_CLIENTEDGE, utf16Test("EDIT"), utf16Test(""), win.WS_CHILD|win.WS_VISIBLE|win.WS_TABSTOP|win.ES_AUTOHSCROLL,
		150, 30, 310, 28, window, 1002, instance, nil)
	win.CreateWindowEx(0, utf16Test("STATIC"), utf16Test("Email"), win.WS_CHILD|win.WS_VISIBLE,
		24, 85, 110, 24, window, 1003, instance, nil)
	email := win.CreateWindowEx(win.WS_EX_CLIENTEDGE, utf16Test("EDIT"), utf16Test(""), win.WS_CHILD|win.WS_VISIBLE|win.WS_TABSTOP|win.ES_AUTOHSCROLL,
		150, 80, 310, 28, window, 1004, instance, nil)
	subscribe := win.CreateWindowEx(0, utf16Test("BUTTON"), utf16Test("Subscribe"), win.WS_CHILD|win.WS_VISIBLE|win.WS_TABSTOP|bsAutoCheckbox,
		150, 130, 160, 28, window, 1005, instance, nil)

	if first == 0 || email == 0 || subscribe == 0 {
		t.Fatal("CreateWindowEx child control failed")
	}
	return nativeTestForm{window: window, first: first, email: email, subscribe: subscribe}
}

func utf16Test(value string) *uint16 {
	ptr, err := windows.UTF16PtrFromString(value)
	if err != nil {
		panic(err)
	}
	return ptr
}
