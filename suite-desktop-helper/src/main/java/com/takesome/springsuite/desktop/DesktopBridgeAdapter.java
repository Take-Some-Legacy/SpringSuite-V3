package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovedAction;
import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface DesktopBridgeAdapter {
    Descriptor descriptor();

    BridgeActionResult perform(BridgeActionContext context);

    record Descriptor(
            String id,
            String name,
            boolean enabled,
            boolean realInputEnabled,
            List<String> capabilities,
            List<String> supportedActions,
            Map<String, Object> metadata
    ) {
        public Descriptor {
            id = textOr(id, "bridge");
            name = textOr(name, id);
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            supportedActions = supportedActions == null ? List.of() : List.copyOf(supportedActions);
            metadata = DesktopHelperModels.safeMap(metadata);
        }
    }

    record BridgeActionContext(
            DesktopApprovedAction action,
            DesktopSnapshot snapshot,
            List<String> guards,
            Instant requestedAt,
            Map<String, Object> metadata
    ) {
        public BridgeActionContext {
            guards = guards == null ? List.of() : List.copyOf(guards);
            requestedAt = requestedAt == null ? Instant.now() : requestedAt;
            metadata = DesktopHelperModels.safeMap(metadata);
        }
    }

    record BridgeActionResult(
            boolean ok,
            String code,
            String message,
            String bridgeId,
            String actionId,
            boolean realInputAttempted,
            boolean realInputPerformed,
            List<String> guards,
            List<String> warnings,
            Map<String, Object> metadata
    ) {
        public BridgeActionResult {
            code = textOr(code, ok ? "ok" : "failed");
            message = message == null ? "" : message;
            bridgeId = textOr(bridgeId, "bridge");
            actionId = actionId == null ? "" : actionId.trim();
            guards = guards == null ? List.of() : List.copyOf(guards);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            metadata = DesktopHelperModels.safeMap(metadata);
        }

        public static BridgeActionResult failed(String code, String message, String bridgeId, String actionId, List<String> warnings, Map<String, Object> metadata) {
            return new BridgeActionResult(false, code, message, bridgeId, actionId, false, false, List.of(), warnings, metadata);
        }
    }

    private static String textOr(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        return text.isBlank() ? (fallback == null ? "" : fallback.trim()) : text;
    }
}
