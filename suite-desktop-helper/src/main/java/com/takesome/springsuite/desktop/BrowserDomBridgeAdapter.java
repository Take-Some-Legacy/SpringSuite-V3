package com.takesome.springsuite.desktop;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BrowserDomBridgeAdapter implements DesktopBridgeAdapter {
    private static final String ID = "browser-dom-bridge-adapter";

    @Override
    public Descriptor descriptor() {
        return new Descriptor(
                ID,
                "Browser DOM Bridge Adapter",
                false,
                false,
                List.of("browser-dom", "form-fill", "field-select", "submit", "external-extension-required", "disabled"),
                List.of("fill", "select", "check", "uncheck", "submit"),
                Map.of(
                        "realDesktopInput", false,
                        "futureBackend", "Browser extension or local browser bridge",
                        "contract", "Metadata skeleton only. No DOM bridge process is wired yet."
                )
        );
    }

    @Override
    public BridgeActionResult perform(BridgeActionContext context) {
        String actionId = context == null || context.action() == null ? "" : context.action().actionId();
        return BridgeActionResult.failed(
                "bridge_disabled",
                ID + " is a metadata skeleton and cannot perform DOM actions yet.",
                ID,
                actionId,
                List.of("No browser DOM bridge is wired."),
                Map.of("bridge", descriptor())
        );
    }
}
