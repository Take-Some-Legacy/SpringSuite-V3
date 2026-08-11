package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopCaptureRequest;
import com.takesome.springsuite.toolbelt.ToolRunRequest;
import com.takesome.springsuite.toolbelt.ToolRunResult;
import com.takesome.springsuite.toolbelt.ToolbeltService;
import org.springframework.stereotype.Component;

@Component
public class ToolbeltDesktopCaptureAdapter implements DesktopCaptureAdapter {
    private final DesktopHelperProperties properties;
    private final ToolbeltService toolbeltService;

    public ToolbeltDesktopCaptureAdapter(DesktopHelperProperties properties, ToolbeltService toolbeltService) {
        this.properties = properties;
        this.toolbeltService = toolbeltService;
    }

    @Override
    public ToolRunResult capture(DesktopCaptureRequest request) {
        DesktopCaptureRequest safeRequest = request == null ? DesktopCaptureRequest.defaults() : request;
        return toolbeltService.run(new ToolRunRequest(
                properties.getCaptureToolId(),
                safeRequest.args(),
                safeRequest.cwd(),
                "",
                safeRequest.timeoutSec(),
                safeRequest.maxStdoutBytes(),
                64_000,
                safeRequest.dryRun()
        ));
    }
}
