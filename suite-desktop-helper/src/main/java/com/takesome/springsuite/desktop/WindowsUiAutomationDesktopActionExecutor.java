package com.takesome.springsuite.desktop;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class WindowsUiAutomationDesktopActionExecutor extends DisabledDesktopActionExecutor {
    public WindowsUiAutomationDesktopActionExecutor() {
        super(
                "windows-ui-automation-desktop-action-executor",
                "Windows UI Automation Desktop Action Executor",
                List.of("windows-uia", "accessibility-tree", "control-patterns", "future-backend", "disabled"),
                List.of("fill", "select", "check", "uncheck", "click"),
                "WindowsUiAutomationExecutor"
        );
    }
}
