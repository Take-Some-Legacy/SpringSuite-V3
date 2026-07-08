package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormFillPlan;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class DesktopApprovalModels {
    private DesktopApprovalModels() {
    }

    public record DesktopApprovedAction(
            String actionId,
            String action,
            String targetFieldId,
            String label,
            String value,
            boolean write,
            boolean sensitive,
            boolean submit,
            String reason,
            Map<String, Object> metadata
    ) {
        public DesktopApprovedAction {
            action = textOr(action, "noop").toLowerCase();
            targetFieldId = text(targetFieldId);
            actionId = textOr(actionId, targetFieldId.isBlank() ? action : action + ":" + targetFieldId);
            label = text(label);
            value = text(value);
            write = write || isWriteAction(action);
            submit = submit || isSubmitAction(action);
            reason = text(reason);
            metadata = DesktopHelperModels.safeMap(metadata);
        }
    }

    public record DesktopApprovalRequest(
            String snapshotId,
            String purpose,
            String operator,
            DesktopFormFillPlan plan,
            List<DesktopApprovedAction> actions,
            List<String> scopes,
            boolean allowSensitiveActions,
            boolean allowSubmitActions,
            Integer ttlSeconds,
            Map<String, Object> metadata
    ) {
        public DesktopApprovalRequest {
            snapshotId = text(snapshotId);
            purpose = textOr(purpose, "desktop-action-approval");
            operator = text(operator);
            actions = actions == null ? List.of() : List.copyOf(actions);
            scopes = scopes == null || scopes.isEmpty() ? List.of("desktop.actions.dry-run") : List.copyOf(scopes);
            ttlSeconds = ttlSeconds == null ? 0 : ttlSeconds;
            metadata = DesktopHelperModels.safeMap(metadata);
        }
    }

    public record DesktopApprovalToken(
            String tokenId,
            String purpose,
            String operator,
            String snapshotId,
            Instant issuedAt,
            Instant expiresAt,
            boolean used,
            List<String> scopes,
            List<DesktopApprovedAction> actions,
            Map<String, Object> metadata
    ) {
        public DesktopApprovalToken {
            tokenId = text(tokenId);
            purpose = textOr(purpose, "desktop-action-approval");
            operator = text(operator);
            snapshotId = text(snapshotId);
            issuedAt = issuedAt == null ? Instant.now() : issuedAt;
            expiresAt = expiresAt == null ? issuedAt : expiresAt;
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
            actions = actions == null ? List.of() : List.copyOf(actions);
            metadata = DesktopHelperModels.safeMap(metadata);
        }

        public boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }

        public DesktopApprovalToken markUsed() {
            return new DesktopApprovalToken(tokenId, purpose, operator, snapshotId, issuedAt, expiresAt, true, scopes, actions, metadata);
        }
    }

    public record DesktopApprovalResult(
            boolean ok,
            String code,
            String message,
            DesktopApprovalToken token,
            List<String> warnings,
            Map<String, Object> metadata
    ) {
        public DesktopApprovalResult {
            code = textOr(code, ok ? "ok" : "failed");
            message = text(message);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            metadata = DesktopHelperModels.safeMap(metadata);
        }

        public static DesktopApprovalResult ok(String message, DesktopApprovalToken token, List<String> warnings, Map<String, Object> metadata) {
            return new DesktopApprovalResult(true, "ok", message, token, warnings, metadata);
        }

        public static DesktopApprovalResult failed(String code, String message, List<String> warnings, Map<String, Object> metadata) {
            return new DesktopApprovalResult(false, code, message, null, warnings, metadata);
        }
    }

    public record DesktopActionDryRunRequest(
            String approvalToken,
            String snapshotId,
            List<DesktopApprovedAction> actions,
            boolean markTokenUsed,
            Map<String, Object> metadata
    ) {
        public DesktopActionDryRunRequest {
            approvalToken = text(approvalToken);
            snapshotId = text(snapshotId);
            actions = actions == null ? List.of() : List.copyOf(actions);
            metadata = DesktopHelperModels.safeMap(metadata);
        }
    }

    public record DesktopActionDryRunResult(
            boolean ok,
            String code,
            String message,
            String tokenId,
            String snapshotId,
            boolean wouldExecute,
            List<DesktopDryRunStep> steps,
            List<String> warnings,
            Map<String, Object> metadata
    ) {
        public DesktopActionDryRunResult {
            code = textOr(code, ok ? "ok" : "failed");
            message = text(message);
            tokenId = text(tokenId);
            snapshotId = text(snapshotId);
            steps = steps == null ? List.of() : List.copyOf(steps);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            metadata = DesktopHelperModels.safeMap(metadata);
        }

        public static DesktopActionDryRunResult failed(String code, String message, String tokenId, String snapshotId, List<String> warnings, Map<String, Object> metadata) {
            return new DesktopActionDryRunResult(false, code, message, tokenId, snapshotId, false, List.of(), warnings, metadata);
        }
    }

    public record DesktopDryRunStep(
            int order,
            String actionId,
            String action,
            String targetFieldId,
            String preview,
            boolean write,
            boolean sensitive,
            boolean allowed,
            List<String> guards,
            List<String> warnings,
            Map<String, Object> metadata
    ) {
        public DesktopDryRunStep {
            actionId = text(actionId);
            action = textOr(action, "noop").toLowerCase();
            targetFieldId = text(targetFieldId);
            preview = text(preview);
            guards = guards == null ? List.of() : List.copyOf(guards);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            metadata = DesktopHelperModels.safeMap(metadata);
        }
    }

    static boolean isWriteAction(String action) {
        return switch (text(action).toLowerCase()) {
            case "fill", "type", "paste", "select", "check", "uncheck", "click", "hotkey", "submit" -> true;
            default -> false;
        };
    }

    static boolean isSubmitAction(String action) {
        return "submit".equals(text(action).toLowerCase());
    }

    static String text(String value) {
        return value == null ? "" : value.trim();
    }

    static String textOr(String value, String fallback) {
        String normalized = text(value);
        return normalized.isBlank() ? text(fallback) : normalized;
    }
}
