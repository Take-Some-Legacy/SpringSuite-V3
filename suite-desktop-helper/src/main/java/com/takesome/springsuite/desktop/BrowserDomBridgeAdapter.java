package com.takesome.springsuite.desktop;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BrowserDomBridgeAdapter implements DesktopBridgeAdapter {
    private static final String ID = "browser-dom-bridge-adapter";

    private final BrowserDomProperties properties;

    public BrowserDomBridgeAdapter(BrowserDomProperties properties) {
        this.properties = properties;
    }

    @Override
    public Descriptor descriptor() {
        return new Descriptor(
                ID,
                "Browser DOM Form Bridge",
                properties.isEnabled(),
                false,
                List.of(
                        "browser-dom",
                        "web-form-recognition",
                        "field-detection",
                        "label-resolution",
                        "form-action-method",
                        "extension-ingest",
                        "read-only"
                ),
                List.of(),
                Map.of(
                        "recognitionEnabled", properties.isEnabled(),
                        "snapshotEndpoint", properties.getEndpointPath(),
                        "writeActionsEnabled", false,
                        "backend", "SpringSuite Form Bridge browser extension",
                        "contract", "DOM recognition is active through the snapshot endpoint. DOM mutation and submit actions remain disabled."
                )
        );
    }

    @Override
    public BridgeActionResult perform(BridgeActionContext context) {
        String actionId = context == null || context.action() == null ? "" : context.action().actionId();
        return BridgeActionResult.failed(
                "browser_dom_write_disabled",
                "Browser DOM recognition is available, but DOM write actions are not enabled.",
                ID,
                actionId,
                List.of("Use the recognized form for analysis and fill planning only."),
                Map.of(
                        "snapshotEndpoint", properties.getEndpointPath(),
                        "recognitionEnabled", properties.isEnabled()
                )
        );
    }
}
