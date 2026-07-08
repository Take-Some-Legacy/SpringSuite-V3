package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopActionExecutionRequest;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopActionExecutionResult;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovalToken;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovedAction;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopDryRunStep;
import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface DesktopActionExecutor {
    Descriptor descriptor();

    DesktopActionExecutionResult execute(ExecutionContext context);

    record Descriptor(
            String id,
            String name,
            boolean realInputEnabled,
            List<String> capabilities,
            List<String> supportedActions,
            Map<String, Object> metadata
    ) {
        public Descriptor {
            id = textOr(id, "noop");
            name = textOr(name, id);
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            supportedActions = supportedActions == null ? List.of() : List.copyOf(supportedActions);
            metadata = DesktopHelperModels.safeMap(metadata);
        }
    }

    record ExecutionContext(
            DesktopApprovalToken token,
            DesktopSnapshot snapshot,
            DesktopActionExecutionRequest request,
            List<DesktopApprovedAction> actions,
            List<DesktopDryRunStep> dryRunSteps,
            List<String> guards,
            Instant requestedAt,
            Map<String, Object> metadata
    ) {
        public ExecutionContext {
            actions = actions == null ? List.of() : List.copyOf(actions);
            dryRunSteps = dryRunSteps == null ? List.of() : List.copyOf(dryRunSteps);
            guards = guards == null ? List.of() : List.copyOf(guards);
            requestedAt = requestedAt == null ? Instant.now() : requestedAt;
            metadata = DesktopHelperModels.safeMap(metadata);
        }
    }

    private static String textOr(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        return text.isBlank() ? (fallback == null ? "" : fallback.trim()) : text;
    }
}
