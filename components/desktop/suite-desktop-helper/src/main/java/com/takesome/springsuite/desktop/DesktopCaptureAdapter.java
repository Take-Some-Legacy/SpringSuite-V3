package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopCaptureRequest;
import com.takesome.springsuite.toolbelt.ToolRunResult;

public interface DesktopCaptureAdapter {
    ToolRunResult capture(DesktopCaptureRequest request);
}
