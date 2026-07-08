package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovalToken;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovedAction;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopDryRunStep;
import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ExecutionGuardService {
    public GuardResult validateExecutorReady(
            DesktopActionExecutor executor,
            DesktopActionExecutor.Descriptor effectiveDescriptor,
            DesktopApprovalToken token,
            DesktopSnapshot snapshot,
            String requestedSnapshotId,
            List<DesktopApprovedAction> actions,
            List<DesktopDryRunStep> dryRunSteps
    ) {
        ArrayList<String> guards = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>();

        if (executor == null) {
            return GuardResult.failed("executor_missing", "No DesktopActionExecutor is available.", guards, warnings, Map.of());
        }
        DesktopActionExecutor.Descriptor descriptor = effectiveDescriptor == null ? executor.descriptor() : effectiveDescriptor;
        guards.add("executor:present:" + descriptor.id());
        if (!descriptor.enabled()) {
            warnings.add("Executor backend is disabled and cannot run actions.");
            return GuardResult.failed("executor_disabled", "Desktop action executor is disabled.", guards, warnings, Map.of("executor", descriptor));
        }
        guards.add("executor:enabled");
        if (descriptor.realInputEnabled()) {
            warnings.add("Real-input executor is not allowed in Full Desktop Integration v1; use NoopDesktopActionExecutor until explicit real-backend policy exists.");
            return GuardResult.failed("real_executor_blocked", "Real desktop input executor is blocked by policy.", guards, warnings, Map.of("executor", descriptor));
        }
        guards.add("executor:no-real-input");

        if (token == null) {
            return GuardResult.failed("approval_token_missing", "Approval token is required.", guards, warnings, Map.of());
        }
        if (token.used()) {
            return GuardResult.failed("approval_token_used", "Approval token has already been consumed.", guards, warnings, Map.of("tokenId", token.tokenId()));
        }
        if (token.expired()) {
            return GuardResult.failed("approval_token_expired", "Approval token has expired.", guards, warnings, Map.of("tokenId", token.tokenId(), "expiresAt", token.expiresAt()));
        }
        if (!token.scopes().contains("desktop.actions.execute")) {
            return GuardResult.failed("approval_scope_denied", "Approval token does not include desktop.actions.execute scope.", guards, warnings, Map.of("scopes", token.scopes()));
        }
        guards.add("approval:token-valid");
        guards.add("approval:scope-execute");

        if (snapshot == null) {
            return GuardResult.failed("snapshot_missing", "Fresh current snapshot is required.", guards, warnings, Map.of());
        }
        String effectiveSnapshotId = requestedSnapshotId == null || requestedSnapshotId.isBlank() ? token.snapshotId() : requestedSnapshotId.trim();
        if (!effectiveSnapshotId.isBlank() && !effectiveSnapshotId.equals(snapshot.snapshotId())) {
            return GuardResult.failed("snapshot_mismatch", "Current snapshot does not match requested snapshotId.", guards, warnings, Map.of("requestedSnapshotId", effectiveSnapshotId, "currentSnapshotId", snapshot.snapshotId()));
        }
        if (!token.snapshotId().isBlank() && !token.snapshotId().equals(snapshot.snapshotId())) {
            return GuardResult.failed("token_snapshot_mismatch", "Approval token snapshotId does not match current snapshot.", guards, warnings, Map.of("tokenSnapshotId", token.snapshotId(), "currentSnapshotId", snapshot.snapshotId()));
        }
        guards.add("snapshot:fresh");
        guards.add("snapshot:matched");

        if (actions == null || actions.isEmpty()) {
            return GuardResult.failed("actions_missing", "No actions are available for execution.", guards, warnings, Map.of());
        }
        guards.add("actions:present");

        if (dryRunSteps == null || dryRunSteps.isEmpty()) {
            return GuardResult.failed("dry_run_steps_missing", "Prior dry-run steps are required before execution.", guards, warnings, Map.of());
        }
        for (DesktopDryRunStep step : dryRunSteps) {
            if (!step.allowed()) {
                return GuardResult.failed("dry_run_step_blocked", "Prior dry-run contains blocked steps.", guards, warnings, Map.of("step", step));
            }
        }
        guards.add("dry-run:pass");
        guards.add("dry-run:all-steps-allowed");

        return GuardResult.ok("Execution guards passed.", guards, warnings, Map.of("executor", descriptor));
    }

    public record GuardResult(
            boolean ok,
            String code,
            String message,
            List<String> guards,
            List<String> warnings,
            Map<String, Object> metadata
    ) {
        public GuardResult {
            code = code == null || code.isBlank() ? (ok ? "ok" : "failed") : code.trim();
            message = message == null ? "" : message;
            guards = guards == null ? List.of() : List.copyOf(guards);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            metadata = DesktopHelperModels.safeMap(metadata);
        }

        static GuardResult ok(String message, List<String> guards, List<String> warnings, Map<String, Object> metadata) {
            return new GuardResult(true, "ok", message, guards, warnings, metadata);
        }

        static GuardResult failed(String code, String message, List<String> guards, List<String> warnings, Map<String, Object> metadata) {
            return new GuardResult(false, code, message, guards, warnings, metadata);
        }
    }
}
