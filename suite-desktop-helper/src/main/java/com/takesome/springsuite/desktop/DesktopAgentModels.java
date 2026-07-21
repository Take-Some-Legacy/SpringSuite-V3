package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshot;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormFillPlan;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopHintResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class DesktopAgentModels {
    private DesktopAgentModels() {
    }

    public record DesktopAgentStatus(
            boolean enabled,
            boolean running,
            boolean paused,
            boolean trayAvailable,
            boolean overlayAvailable,
            Instant startedAt,
            Instant lastScanAt,
            Instant lastFormDetectedAt,
            String activeSignature,
            String lastCode,
            String lastMessage,
            long scanCount,
            long formDetectionCount,
            long actionExecutionCount,
            Map<String, Object> metadata
    ) {
        public DesktopAgentStatus {
            activeSignature = text(activeSignature);
            lastCode = text(lastCode);
            lastMessage = text(lastMessage);
            metadata = DesktopHelperModels.safeMap(metadata);
        }
    }

    public record DesktopFormSuggestion(
            String signature,
            DesktopSnapshot snapshot,
            DesktopFormFillPlan plan,
            DesktopHintResponse hints,
            List<DesktopApprovalModels.DesktopApprovedAction> actions,
            int x,
            int y,
            String title,
            String summary,
            Map<String, Object> metadata
    ) {
        public DesktopFormSuggestion {
            signature = text(signature);
            actions = actions == null ? List.of() : List.copyOf(actions);
            title = text(title);
            summary = text(summary);
            metadata = DesktopHelperModels.safeMap(metadata);
        }
    }

    public record DesktopActiveFormInfo(
            boolean detected,
            Instant updatedAt,
            DesktopSnapshot snapshot,
            DesktopFormSuggestion suggestion,
            Map<String, Object> metadata
    ) {
        public DesktopActiveFormInfo {
            updatedAt = updatedAt == null ? Instant.now() : updatedAt;
            metadata = DesktopHelperModels.safeMap(metadata);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
