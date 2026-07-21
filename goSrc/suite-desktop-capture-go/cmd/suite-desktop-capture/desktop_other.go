//go:build !windows

package main

import "fmt"

func desktopInspectionAvailable() bool { return false }
func desktopWriteAvailable() bool      { return false }

func performDesktopInspect() (DesktopInspection, error) {
	return performDesktopInspectWindow(0)
}

func performDesktopInspectWindow(windowHandle uint64) (DesktopInspection, error) {
	return DesktopInspection{}, fmt.Errorf("desktop form inspection is currently implemented only for Windows")
}

func performDesktopFill(request DesktopFillRequest) (DesktopFillResult, error) {
	return DesktopFillResult{}, fmt.Errorf("desktop form filling is currently implemented only for Windows")
}
