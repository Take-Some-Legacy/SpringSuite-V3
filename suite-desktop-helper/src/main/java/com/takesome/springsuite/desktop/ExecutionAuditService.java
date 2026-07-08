package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopActionExecutionResult;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovalToken;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovedAction;
import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshot;
import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ExecutionAuditService {
    private static final String SOURCE = "desktop-execution";

    private final OperatorLogService logService;

    public ExecutionAuditService(OperatorLogService logService) {
        this.logService = logService;
    }

    public void recordGuardFailure(String code, String message, DesktopApprovalToken token, String requestedSnapshotId, List<String> warnings, Map<String, Object> metadata) {
        logService.append(OperatorLogLevel.WARN, SOURCE, "desktop execution guard failed", Map.of(
                "code", safe(code),
                "message", safe(message),
                "tokenId", token == null ? "" : token.tokenId(),
                "requestedSnapshotId", safe(requestedSnapshotId),
                "warnings", warnings == null ? List.of() : warnings,
                "metadata", metadata == null ? Map.of() : metadata
        ));
    }

    public void recordExecutionRequested(DesktopApprovalToken token, DesktopSnapshot snapshot, List<DesktopApprovedAction> actions, DesktopActionExecutor.Descriptor executor) {
        logService.append(OperatorLogLevel.INFO, SOURCE, "desktop execution requested", Map.of(
                "tokenId", token == null ? "" : token.tokenId(),
                "snapshotId", snapshot == null ? "" : snapshot.snapshotId(),
                "actions", actions == null ? 0 : actions.size(),
                "executor", executor == null ? "" : executor.id(),
                "realInputEnabled", executor != null && executor.realInputEnabled()
        ));
    }

    public void recordExecutionResult(DesktopActionExecutionResult result, DesktopActionExecutor.Descriptor executor, boolean tokenMarkedUsed) {
        logService.append(result.ok() ? OperatorLogLevel.INFO : OperatorLogLevel.WARN, SOURCE, "desktop execution result", Map.of(
                "ok", result.ok(),
                "code", result.code(),
                "tokenId", result.tokenId(),
                "snapshotId", result.snapshotId(),
                "simulated", result.simulated(),
                "executed", result.executed(),
                "steps", result.steps().size(),
                "executor", executor == null ? "" : executor.id(),
                "tokenMarkedUsed", tokenMarkedUsed,
                "warnings", result.warnings().size()
        ));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
