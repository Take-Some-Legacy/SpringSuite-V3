package com.takesome.springsuite.desktop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovedAction;
import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class WindowsUiAutomationBridgeAdapter implements DesktopBridgeAdapter {
    private static final String ID = "windows-ui-automation-bridge-adapter";

    private final DesktopAgentSidecarProperties sidecarProperties;
    private final DesktopAgentSidecarRuntime sidecarRuntime;
    private final ObjectMapper objectMapper;

    public WindowsUiAutomationBridgeAdapter(
            DesktopAgentSidecarProperties sidecarProperties,
            DesktopAgentSidecarRuntime sidecarRuntime,
            ObjectMapper objectMapper
    ) {
        this.sidecarProperties = sidecarProperties;
        this.sidecarRuntime = sidecarRuntime;
        this.objectMapper = objectMapper;
    }

    @Override
    public Descriptor descriptor() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
        DesktopAgentSidecarRuntime.SidecarStatus sidecar = sidecarRuntime.status();
        return new Descriptor(
                ID,
                "Windows Native Form Bridge",
                windows && sidecarProperties.isEnabled(),
                true,
                List.of(
                        "windows-win32",
                        "active-form",
                        "standard-controls",
                        "guarded-write",
                        "foreground-window-check",
                        "external-go-sidecar"
                ),
                List.of("fill", "type", "paste", "select", "check", "uncheck", "click", "submit"),
                Map.of(
                        "realDesktopInput", true,
                        "backend", "suite-desktop-agent-go",
                        "sidecar", sidecar,
                        "scope", "Windows UI Automation and standard Win32 controls in the approved foreground window",
                        "limitations", "Custom-rendered applications may expose no actionable accessibility controls."
                )
        );
    }

    @Override
    public BridgeActionResult perform(BridgeActionContext context) {
        if (context == null || context.action() == null) {
            return BridgeActionResult.failed(
                    "action_missing",
                    "Desktop action is missing.",
                    ID,
                    "",
                    List.of(),
                    Map.of()
            );
        }

        DesktopApprovedAction action = context.action();
        DesktopSnapshot snapshot = context.snapshot();
        long expectedWindowHandle = expectedWindowHandle(snapshot);
        if (expectedWindowHandle <= 0) {
            return BridgeActionResult.failed(
                    "native_window_handle_missing",
                    "The approved snapshot does not contain a native foreground-window handle.",
                    ID,
                    action.actionId(),
                    List.of("Capture a fresh active-form snapshot through the Windows desktop sidecar."),
                    Map.of()
            );
        }

        LinkedHashMap<String, Object> nativeAction = new LinkedHashMap<>();
        nativeAction.put("actionId", action.actionId());
        nativeAction.put("action", action.action());
        nativeAction.put("targetFieldId", action.targetFieldId());
        nativeAction.put("value", action.value());
        nativeAction.put("sensitive", action.sensitive());
        nativeAction.put("submit", action.submit());
        nativeAction.put("metadata", action.metadata());

        Map<String, Object> payload = Map.of(
                "expectedWindowHandle", expectedWindowHandle,
                "allowSensitive", action.sensitive(),
                "allowSubmit", action.submit(),
                "actions", List.of(nativeAction),
                "metadata", Map.of(
                        "bridge", ID,
                        "snapshotId", snapshot == null ? "" : snapshot.snapshotId()
                )
        );

        try {
            Map<String, Object> result = sidecarRuntime.fill(payload);
            boolean ok = booleanValue(result.get("ok"));
            boolean performed = booleanValue(result.get("performed"));
            String message = firstText(stepMessage(result), ok ? "Native form action completed." : "Native form action was blocked.");
            List<String> warnings = objectMapper.convertValue(result.getOrDefault("warnings", List.of()), new TypeReference<List<String>>() {
            });
            return new BridgeActionResult(
                    ok,
                    ok ? "ok" : firstText(stepCode(result), "native_action_blocked"),
                    message,
                    ID,
                    action.actionId(),
                    true,
                    performed,
                    context.guards(),
                    warnings,
                    Map.of(
                            "sidecar", sidecarRuntime.status(),
                            "expectedWindowHandle", expectedWindowHandle,
                            "result", result
                    )
            );
        } catch (Exception ex) {
            return BridgeActionResult.failed(
                    "sidecar_protocol_error",
                    "Windows desktop sidecar protocol failed: " + safeMessage(ex),
                    ID,
                    action.actionId(),
                    List.of("No unguarded fallback input was attempted."),
                    Map.of("exception", ex.getClass().getName())
            );
        }
    }

    private long expectedWindowHandle(DesktopSnapshot snapshot) {
        if (snapshot == null || snapshot.context() == null || snapshot.context().form() == null) {
            return 0;
        }
        Object direct = snapshot.context().form().metadata().get("windowHandle");
        long value = longValue(direct);
        if (value > 0) {
            return value;
        }
        Object activeWindow = snapshot.context().metadata().get("activeWindow");
        if (activeWindow instanceof Map<?, ?> map) {
            return longValue(map.get("handle"));
        }
        return 0;
    }

    private String stepMessage(Map<String, Object> result) {
        Object stepsValue = result.get("steps");
        if (stepsValue instanceof List<?> steps && !steps.isEmpty() && steps.get(0) instanceof Map<?, ?> step) {
            return text(step.get("message"));
        }
        return "";
    }

    private String stepCode(Map<String, Object> result) {
        Object stepsValue = result.get("steps");
        if (stepsValue instanceof List<?> steps && !steps.isEmpty() && steps.get(0) instanceof Map<?, ?> step) {
            return text(step.get("code"));
        }
        return "";
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(text(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(text(value));
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
