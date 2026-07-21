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
                        "operator-confirmed-fill",
                        "preserve-existing-values",
                        "no-auto-submit"
                ),
                properties.isWriteEnabled()
                        ? List.of("fill", "select", "check", "uncheck")
                        : List.of(),
                Map.of(
                        "recognitionEnabled", properties.isEnabled(),
                        "snapshotEndpoint", properties.getEndpointPath(),
                        "commandEndpoint", BrowserDomProperties.COMMAND_NEXT_ENDPOINT,
                        "writeActionsEnabled", properties.isWriteEnabled(),
                        "preserveExistingValues", properties.isPreserveExistingValues(),
                        "submitEnabled", false,
                        "backend", "SpringSuite Form Bridge browser extension",
                        "contract", "The bridge recognizes forms and accepts a short-lived fill command only after the operator clicks «Вставить». It never submits the form automatically."
                )
        );
    }

    @Override
    public BridgeActionResult perform(BridgeActionContext context) {
        String actionId = context == null || context.action() == null ? "" : context.action().actionId();
        return BridgeActionResult.failed(
                "browser_dom_batch_command_required",
                "Browser DOM actions must be queued as one operator-confirmed form command by DesktopAgentService.",
                ID,
                actionId,
                List.of("Use the SpringSuite form suggestion window and click «Вставить»."),
                Map.of(
                        "snapshotEndpoint", properties.getEndpointPath(),
                        "commandEndpoint", BrowserDomProperties.COMMAND_NEXT_ENDPOINT,
                        "recognitionEnabled", properties.isEnabled(),
                        "writeEnabled", properties.isWriteEnabled(),
                        "submitEnabled", false
                )
        );
    }
}
