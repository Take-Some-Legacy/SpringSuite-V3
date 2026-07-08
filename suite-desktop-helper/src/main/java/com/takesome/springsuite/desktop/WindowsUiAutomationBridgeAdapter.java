package com.takesome.springsuite.desktop;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class WindowsUiAutomationBridgeAdapter implements DesktopBridgeAdapter {
    private static final String ID = "windows-ui-automation-bridge-adapter";

    @Override
    public Descriptor descriptor() {
        return new Descriptor(
                ID,
                "Windows UI Automation Bridge Adapter",
                false,
                false,
                List.of("windows-uia", "accessibility-tree", "control-patterns", "external-sidecar-required", "disabled"),
                List.of("fill", "select", "check", "uncheck", "click"),
                Map.of(
                        "realDesktopInput", false,
                        "futureBackend", "Windows UI Automation sidecar",
                        "contract", "Metadata skeleton only. No UIA sidecar is wired yet."
                )
        );
    }

    @Override
    public BridgeActionResult perform(BridgeActionContext context) {
        String actionId = context == null || context.action() == null ? "" : context.action().actionId();
        return BridgeActionResult.failed(
                "bridge_disabled",
                ID + " is a metadata skeleton and cannot perform UI Automation actions yet.",
                ID,
                actionId,
                List.of("No Windows UI Automation sidecar is wired."),
                Map.of("bridge", descriptor())
        );
    }
}
