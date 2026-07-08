package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopActionDryRunRequest;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopActionDryRunResult;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopActionExecutionRequest;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopActionExecutionResult;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovalRequest;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovalResult;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovalToken;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovedAction;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopDryRunStep;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopExecutionStep;
import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshot;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFieldPlan;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormField;
import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class DesktopApprovalService {
    private static final String SOURCE = "desktop-approval";

    private final DesktopHelperProperties properties;
    private final DesktopSnapshotCache snapshotCache;
    private final OperatorLogService logService;
    private final DesktopActionExecutorRegistry executorRegistry;
    private final ExecutionGuardService executionGuardService;
    private final ExecutionAuditService executionAuditService;
    private final ConcurrentHashMap<String, DesktopApprovalToken> tokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DryRunPass> dryRunPasses = new ConcurrentHashMap<>();

    public DesktopApprovalService(
            DesktopHelperProperties properties,
            DesktopSnapshotCache snapshotCache,
            OperatorLogService logService,
            DesktopActionExecutorRegistry executorRegistry,
            ExecutionGuardService executionGuardService,
            ExecutionAuditService executionAuditService
    ) {
        this.properties = properties;
        this.snapshotCache = snapshotCache;
        this.logService = logService;
        this.executorRegistry = executorRegistry;
        this.executionGuardService = executionGuardService;
        this.executionAuditService = executionAuditService;
    }

    public DesktopApprovalResult createApproval(DesktopApprovalRequest request) {
        DesktopApprovalRequest safeRequest = request == null
                ? new DesktopApprovalRequest("", "", "", null, List.of(), List.of(), false, false, 0, Map.of())
                : request;
        if (!properties.isEnabled()) {
            return DesktopApprovalResult.failed("desktop_helper_disabled", "Desktop helper is disabled.", List.of(), Map.of());
        }

        cleanupExpired();
        ArrayList<String> warnings = new ArrayList<>();
        String snapshotId = resolveSnapshotId(safeRequest.snapshotId(), warnings);
        List<DesktopApprovedAction> actions = normalizeActions(safeRequest, warnings);
        if (actions.isEmpty()) {
            return DesktopApprovalResult.failed("approval_actions_missing", "No approvable desktop actions were supplied.", warnings, Map.of("snapshotId", snapshotId));
        }

        int writeCount = 0;
        int sensitiveCount = 0;
        int submitCount = 0;
        for (DesktopApprovedAction action : actions) {
            if (action.write()) {
                writeCount++;
            }
            if (action.sensitive()) {
                sensitiveCount++;
            }
            if (action.submit()) {
                submitCount++;
            }
        }

        if (writeCount > 0 && !properties.isRequireApprovalForWriteActions()) {
            warnings.add("Write actions were approved even though require-approval-for-write-actions=false; keeping token guard active anyway.");
        }
        if (sensitiveCount > 0 && !safeRequest.allowSensitiveActions()) {
            warnings.add("Sensitive actions are present but not explicitly allowed; dry-run will mark them blocked.");
        }
        if (submitCount > 0 && !safeRequest.allowSubmitActions()) {
            warnings.add("Submit actions are present but not explicitly allowed; dry-run will mark them blocked.");
        }

        Instant issuedAt = Instant.now();
        Duration ttl = approvalTtl(safeRequest.ttlSeconds());
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.putAll(safeRequest.metadata());
        metadata.put("writeCount", writeCount);
        metadata.put("sensitiveCount", sensitiveCount);
        metadata.put("submitCount", submitCount);
        metadata.put("allowSensitiveActions", safeRequest.allowSensitiveActions());
        metadata.put("allowSubmitActions", safeRequest.allowSubmitActions());

        DesktopApprovalToken token = new DesktopApprovalToken(
                UUID.randomUUID().toString(),
                safeRequest.purpose(),
                safeRequest.operator(),
                snapshotId,
                issuedAt,
                issuedAt.plus(ttl),
                false,
                safeRequest.scopes(),
                actions,
                metadata
        );
        tokens.put(token.tokenId(), token);
        logService.append(OperatorLogLevel.INFO, SOURCE, "desktop approval token issued", Map.of(
                "tokenId", token.tokenId(),
                "snapshotId", snapshotId,
                "actions", actions.size(),
                "write", writeCount,
                "sensitive", sensitiveCount,
                "submit", submitCount,
                "expiresAt", token.expiresAt()
        ));
        return DesktopApprovalResult.ok("Desktop approval token issued.", token, warnings, Map.of(
                "activeTokens", tokens.size(),
                "activeDryRunPasses", dryRunPasses.size(),
                "ttl", ttl.toString()
        ));
    }

    public DesktopActionDryRunResult dryRun(DesktopActionDryRunRequest request) {
        DesktopActionDryRunRequest safeRequest = request == null
                ? new DesktopActionDryRunRequest("", "", List.of(), false, Map.of())
                : request;
        if (!properties.isEnabled()) {
            return DesktopActionDryRunResult.failed("desktop_helper_disabled", "Desktop helper is disabled.", "", "", List.of(), Map.of());
        }
        if (safeRequest.approvalToken().isBlank()) {
            return DesktopActionDryRunResult.failed("approval_token_missing", "Approval token is required for desktop action dry-run.", "", safeRequest.snapshotId(), List.of(), Map.of());
        }

        cleanupExpired();
        DesktopApprovalToken token = tokens.get(safeRequest.approvalToken());
        if (token == null) {
            return DesktopActionDryRunResult.failed("approval_token_not_found", "Approval token was not found or has expired.", safeRequest.approvalToken(), safeRequest.snapshotId(), List.of(), Map.of());
        }
        if (token.expired()) {
            tokens.remove(token.tokenId());
            dryRunPasses.remove(token.tokenId());
            return DesktopActionDryRunResult.failed("approval_token_expired", "Approval token has expired.", token.tokenId(), token.snapshotId(), List.of(), Map.of("expiresAt", token.expiresAt()));
        }
        if (token.used()) {
            dryRunPasses.remove(token.tokenId());
            return DesktopActionDryRunResult.failed("approval_token_used", "Approval token has already been consumed.", token.tokenId(), token.snapshotId(), List.of(), Map.of());
        }
        if (!token.scopes().contains("desktop.actions.dry-run") && !token.scopes().contains("desktop.actions.execute")) {
            return DesktopActionDryRunResult.failed("approval_scope_denied", "Approval token does not include desktop action dry-run scope.", token.tokenId(), token.snapshotId(), List.of(), Map.of("scopes", token.scopes()));
        }

        ArrayList<String> warnings = new ArrayList<>();
        Optional<DesktopSnapshot> current = snapshotCache.current();
        DesktopSnapshot snapshot = current.orElse(null);
        String requestedSnapshotId = firstText(safeRequest.snapshotId(), token.snapshotId());
        boolean snapshotOk = validateSnapshot(snapshot, requestedSnapshotId, token, warnings);
        List<DesktopApprovedAction> actions = safeRequest.actions().isEmpty() ? token.actions() : safeRequest.actions();
        if (actions.isEmpty()) {
            return DesktopActionDryRunResult.failed("dry_run_actions_missing", "No actions are available for dry-run.", token.tokenId(), requestedSnapshotId, warnings, Map.of());
        }

        ArrayList<DesktopDryRunStep> steps = new ArrayList<>();
        boolean allAllowed = snapshotOk;
        for (int i = 0; i < actions.size(); i++) {
            DesktopDryRunStep step = stepFor(snapshot, actions.get(i), i + 1, token, snapshotOk);
            if (!step.allowed()) {
                allAllowed = false;
            }
            steps.add(step);
        }

        boolean passRecorded = false;
        if (allAllowed && !safeRequest.markTokenUsed()) {
            DryRunPass pass = new DryRunPass(
                    token.tokenId(),
                    requestedSnapshotId,
                    actionSignature(actions),
                    Instant.now(),
                    minInstant(Instant.now().plus(properties.getContextTtl()), token.expiresAt()),
                    steps
            );
            dryRunPasses.put(token.tokenId(), pass);
            passRecorded = true;
        }

        if (safeRequest.markTokenUsed()) {
            tokens.computeIfPresent(token.tokenId(), (ignored, existing) -> existing.markUsed());
            dryRunPasses.remove(token.tokenId());
            warnings.add("Approval token marked as used by dry-run request.");
        }

        logService.append(OperatorLogLevel.INFO, SOURCE, "desktop action dry-run completed", Map.of(
                "tokenId", token.tokenId(),
                "snapshotId", requestedSnapshotId,
                "steps", steps.size(),
                "wouldExecute", allAllowed,
                "passRecorded", passRecorded,
                "warnings", warnings.size()
        ));
        return new DesktopActionDryRunResult(
                true,
                "ok",
                allAllowed ? "Dry-run passed all guards." : "Dry-run found blocked or unsafe steps.",
                token.tokenId(),
                requestedSnapshotId,
                allAllowed,
                steps,
                warnings,
                Map.of(
                        "snapshotFresh", snapshotOk,
                        "activeTokens", tokens.size(),
                        "activeDryRunPasses", dryRunPasses.size(),
                        "dryRunPassRecorded", passRecorded,
                        "executionMode", "dry-run-only"
                )
        );
    }

    public DesktopActionExecutionResult execute(DesktopActionExecutionRequest request) {
        DesktopActionExecutionRequest safeRequest = request == null
                ? new DesktopActionExecutionRequest("", "", List.of(), true, true, Map.of())
                : request;
        if (!properties.isEnabled()) {
            return DesktopActionExecutionResult.failed("desktop_helper_disabled", "Desktop helper is disabled.", "", "", List.of(), Map.of());
        }
        if (safeRequest.approvalToken().isBlank()) {
            return DesktopActionExecutionResult.failed("approval_token_missing", "Approval token is required for desktop action execution stub.", "", safeRequest.snapshotId(), List.of(), Map.of());
        }

        cleanupExpired();
        DesktopApprovalToken token = tokens.get(safeRequest.approvalToken());
        if (token == null) {
            return DesktopActionExecutionResult.failed("approval_token_not_found", "Approval token was not found or has expired.", safeRequest.approvalToken(), safeRequest.snapshotId(), List.of(), Map.of());
        }
        if (token.expired()) {
            tokens.remove(token.tokenId());
            dryRunPasses.remove(token.tokenId());
            executionAuditService.recordGuardFailure("approval_token_expired", "Approval token has expired.", token, token.snapshotId(), List.of(), Map.of("expiresAt", token.expiresAt()));
            return DesktopActionExecutionResult.failed("approval_token_expired", "Approval token has expired.", token.tokenId(), token.snapshotId(), List.of(), Map.of("expiresAt", token.expiresAt()));
        }
        if (token.used()) {
            dryRunPasses.remove(token.tokenId());
            executionAuditService.recordGuardFailure("approval_token_used", "Approval token has already been consumed.", token, token.snapshotId(), List.of(), Map.of());
            return DesktopActionExecutionResult.failed("approval_token_used", "Approval token has already been consumed.", token.tokenId(), token.snapshotId(), List.of(), Map.of());
        }
        if (!token.scopes().contains("desktop.actions.execute")) {
            executionAuditService.recordGuardFailure("approval_scope_denied", "Approval token does not include desktop action execute scope.", token, token.snapshotId(), List.of(), Map.of("scopes", token.scopes()));
            return DesktopActionExecutionResult.failed("approval_scope_denied", "Approval token does not include desktop action execute scope.", token.tokenId(), token.snapshotId(), List.of(), Map.of("scopes", token.scopes()));
        }

        ArrayList<String> warnings = new ArrayList<>();
        Optional<DesktopSnapshot> current = snapshotCache.current();
        DesktopSnapshot snapshot = current.orElse(null);
        String requestedSnapshotId = firstText(safeRequest.snapshotId(), token.snapshotId());
        boolean snapshotOk = validateSnapshot(snapshot, requestedSnapshotId, token, warnings);
        if (!snapshotOk) {
            executionAuditService.recordGuardFailure("snapshot_guard_failed", "Fresh snapshot validation failed; execution refused to proceed.", token, requestedSnapshotId, warnings, Map.of("requiresFreshSnapshot", true));
            return DesktopActionExecutionResult.failed("snapshot_guard_failed", "Fresh snapshot validation failed; execution refused to proceed.", token.tokenId(), requestedSnapshotId, warnings, Map.of("requiresFreshSnapshot", true));
        }

        List<DesktopApprovedAction> actions = safeRequest.actions().isEmpty() ? token.actions() : safeRequest.actions();
        if (actions.isEmpty()) {
            executionAuditService.recordGuardFailure("execution_actions_missing", "No actions are available for execution.", token, requestedSnapshotId, warnings, Map.of());
            return DesktopActionExecutionResult.failed("execution_actions_missing", "No actions are available for execution.", token.tokenId(), requestedSnapshotId, warnings, Map.of());
        }

        DryRunPass pass = dryRunPasses.get(token.tokenId());
        if (safeRequest.requireFreshDryRun()) {
            String signature = actionSignature(actions);
            if (pass == null) {
                executionAuditService.recordGuardFailure("dry_run_pass_required", "A successful dry-run pass is required before execution.", token, requestedSnapshotId, warnings, Map.of("required", true));
                return DesktopActionExecutionResult.failed("dry_run_pass_required", "A successful dry-run pass is required before execution.", token.tokenId(), requestedSnapshotId, warnings, Map.of("required", true));
            }
            if (pass.expired()) {
                dryRunPasses.remove(token.tokenId());
                executionAuditService.recordGuardFailure("dry_run_pass_expired", "The prior dry-run pass has expired; run dry-run again.", token, requestedSnapshotId, warnings, Map.of("expiresAt", pass.expiresAt()));
                return DesktopActionExecutionResult.failed("dry_run_pass_expired", "The prior dry-run pass has expired; run dry-run again.", token.tokenId(), requestedSnapshotId, warnings, Map.of("expiresAt", pass.expiresAt()));
            }
            if (!pass.snapshotId().equals(requestedSnapshotId)) {
                executionAuditService.recordGuardFailure("dry_run_snapshot_mismatch", "Dry-run pass snapshotId does not match execution request.", token, requestedSnapshotId, warnings, Map.of("dryRunSnapshotId", pass.snapshotId()));
                return DesktopActionExecutionResult.failed("dry_run_snapshot_mismatch", "Dry-run pass snapshotId does not match execution request.", token.tokenId(), requestedSnapshotId, warnings, Map.of("dryRunSnapshotId", pass.snapshotId()));
            }
            if (!pass.actionSignature().equals(signature)) {
                executionAuditService.recordGuardFailure("dry_run_action_mismatch", "Dry-run pass action set does not match execution request.", token, requestedSnapshotId, warnings, Map.of());
                return DesktopActionExecutionResult.failed("dry_run_action_mismatch", "Dry-run pass action set does not match execution request.", token.tokenId(), requestedSnapshotId, warnings, Map.of());
            }
        }

        List<DesktopDryRunStep> dryRunSteps = pass == null ? List.of() : pass.steps();
        DesktopActionExecutor actionExecutor = executorRegistry.defaultExecutor();
        ExecutionGuardService.GuardResult guard = executionGuardService.validateExecutorReady(
                actionExecutor,
                token,
                snapshot,
                requestedSnapshotId,
                actions,
                dryRunSteps
        );
        if (!guard.ok()) {
            executionAuditService.recordGuardFailure(guard.code(), guard.message(), token, requestedSnapshotId, guard.warnings(), guard.metadata());
            return DesktopActionExecutionResult.failed(guard.code(), guard.message(), token.tokenId(), requestedSnapshotId, guard.warnings(), guard.metadata());
        }

        executionAuditService.recordExecutionRequested(token, snapshot, actions, actionExecutor.descriptor());
        DesktopActionExecutionResult executorResult = actionExecutor.execute(new DesktopActionExecutor.ExecutionContext(
                token,
                snapshot,
                safeRequest,
                actions,
                dryRunSteps,
                guard.guards(),
                Instant.now(),
                Map.of(
                        "dryRunPassExpiresAt", pass == null ? "" : pass.expiresAt().toString(),
                        "executor", actionExecutor.descriptor().id()
                )
        ));

        if (safeRequest.markTokenUsed()) {
            tokens.computeIfPresent(token.tokenId(), (ignored, existing) -> existing.markUsed());
            dryRunPasses.remove(token.tokenId());
        }

        ArrayList<String> resultWarnings = new ArrayList<>();
        resultWarnings.addAll(warnings);
        resultWarnings.addAll(guard.warnings());
        resultWarnings.addAll(executorResult.warnings());
        LinkedHashMap<String, Object> resultMetadata = new LinkedHashMap<>();
        resultMetadata.putAll(executorResult.metadata());
        resultMetadata.put("activeTokens", tokens.size());
        resultMetadata.put("activeDryRunPasses", dryRunPasses.size());
        resultMetadata.put("tokenMarkedUsed", safeRequest.markTokenUsed());
        resultMetadata.put("executor", actionExecutor.descriptor());
        resultMetadata.put("executionMode", "executor-abstraction");

        DesktopActionExecutionResult result = new DesktopActionExecutionResult(
                executorResult.ok(),
                executorResult.code(),
                executorResult.message(),
                executorResult.tokenId(),
                executorResult.snapshotId(),
                executorResult.simulated(),
                executorResult.executed(),
                executorResult.steps(),
                resultWarnings,
                resultMetadata
        );
        executionAuditService.recordExecutionResult(result, actionExecutor.descriptor(), safeRequest.markTokenUsed());
        return result;
    }

    public Optional<DesktopApprovalToken> find(String tokenId) {
        cleanupExpired();
        return Optional.ofNullable(tokens.get(tokenId));
    }

    public Map<String, Object> summary() {
        cleanupExpired();
        return Map.of(
                "activeTokens", tokens.size(),
                "activeDryRunPasses", dryRunPasses.size(),
                "approvalTtl", approvalTtl(0).toString(),
                "dryRunOnly", false,
                "executionStubOnly", true
        );
    }

    private List<DesktopApprovedAction> normalizeActions(DesktopApprovalRequest request, List<String> warnings) {
        if (!request.actions().isEmpty()) {
            return request.actions();
        }
        if (request.plan() == null || request.plan().fields().isEmpty()) {
            return List.of();
        }
        ArrayList<DesktopApprovedAction> actions = new ArrayList<>();
        for (DesktopFieldPlan field : request.plan().fields()) {
            String action = field.action().toLowerCase();
            if (!DesktopApprovalModels.isWriteAction(action)) {
                continue;
            }
            if ("review".equals(action) || "ask".equals(action) || "leave".equals(action)) {
                continue;
            }
            if (field.sensitive() && field.value().isBlank()) {
                warnings.add("Sensitive field `" + field.label() + "` has no raw value in plan and remains review-only.");
            }
            actions.add(new DesktopApprovedAction(
                    action + ":" + field.fieldId(),
                    action,
                    field.fieldId(),
                    field.label(),
                    field.sensitive() ? "" : field.value(),
                    true,
                    field.sensitive(),
                    false,
                    field.reason(),
                    Map.of("confidence", field.confidence(), "needsUserReview", field.needsUserReview())
            ));
        }
        return List.copyOf(actions);
    }

    private boolean validateSnapshot(DesktopSnapshot snapshot, String requestedSnapshotId, DesktopApprovalToken token, List<String> warnings) {
        if (snapshot == null) {
            warnings.add("No fresh current snapshot is available; capture or ingest context before real execution.");
            return false;
        }
        if (!requestedSnapshotId.isBlank() && !requestedSnapshotId.equals(snapshot.snapshotId())) {
            warnings.add("Current snapshot does not match requested snapshotId.");
            return false;
        }
        if (!token.snapshotId().isBlank() && !token.snapshotId().equals(snapshot.snapshotId())) {
            warnings.add("Approval token snapshotId does not match current snapshot.");
            return false;
        }
        return true;
    }

    private DesktopDryRunStep stepFor(DesktopSnapshot snapshot, DesktopApprovedAction action, int order, DesktopApprovalToken token, boolean snapshotOk) {
        ArrayList<String> guards = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>();
        boolean allowed = true;

        if (!snapshotOk) {
            allowed = false;
            warnings.add("snapshot guard failed");
        } else {
            guards.add("snapshot:fresh");
        }
        if (action.submit()) {
            boolean allowedByPolicy = properties.isAllowSubmitActions();
            boolean allowedByToken = bool(token.metadata().get("allowSubmitActions"), false);
            guards.add("submit:explicit-approval-required");
            if (!allowedByPolicy || !allowedByToken) {
                allowed = false;
                warnings.add("submit action blocked; require allow-submit-actions=true and explicit approval request flag");
            } else {
                guards.add("submit:dry-run-only");
            }
        }
        if (action.write() && properties.isRequireApprovalForWriteActions()) {
            guards.add("approval:required");
            guards.add("approval:token-present");
        }
        if (action.sensitive()) {
            boolean allowedByToken = bool(token.metadata().get("allowSensitiveActions"), false);
            guards.add("sensitive:review-required");
            if (!allowedByToken) {
                allowed = false;
                warnings.add("sensitive action was not explicitly allowed by approval request");
            }
            if (action.value().isBlank()) {
                allowed = false;
                warnings.add("sensitive action has no value and cannot be executed automatically");
            }
        }
        if (!action.targetFieldId().isBlank() && snapshot != null && !fieldExists(snapshot, action.targetFieldId())) {
            allowed = false;
            warnings.add("target field is not present in current snapshot");
        }
        if (action.write() && !properties.isAllowAutofillExecution()) {
            guards.add("execution:dry-run-only");
        }

        return new DesktopDryRunStep(
                order,
                action.actionId(),
                action.action(),
                action.targetFieldId(),
                preview(action),
                action.write(),
                action.sensitive(),
                allowed,
                guards,
                warnings,
                Map.of("label", action.label(), "reason", action.reason())
        );
    }

    private boolean fieldExists(DesktopSnapshot snapshot, String fieldId) {
        for (DesktopFormField field : snapshot.context().form().fields()) {
            if (fieldId.equals(field.id()) || fieldId.equals(field.name())) {
                return true;
            }
        }
        return snapshot.context().form().fields().isEmpty();
    }

    private String preview(DesktopApprovedAction action) {
        if (action.sensitive()) {
            return action.action() + " `" + label(action) + "` with redacted sensitive value";
        }
        if (action.value().isBlank()) {
            return action.action() + " `" + label(action) + "`";
        }
        return action.action() + " `" + label(action) + "` = `" + truncate(action.value(), 80) + "`";
    }

    private String label(DesktopApprovedAction action) {
        if (!action.label().isBlank()) {
            return action.label();
        }
        if (!action.targetFieldId().isBlank()) {
            return action.targetFieldId();
        }
        return action.actionId();
    }

    private String resolveSnapshotId(String requestedSnapshotId, List<String> warnings) {
        if (!requestedSnapshotId.isBlank()) {
            return requestedSnapshotId;
        }
        Optional<DesktopSnapshot> current = snapshotCache.current();
        if (current.isPresent()) {
            return current.get().snapshotId();
        }
        warnings.add("No current snapshot found while issuing approval token; dry-run will fail snapshot freshness guards until a fresh snapshot is available.");
        return "";
    }

    private Duration approvalTtl(int requestedSeconds) {
        int seconds = requestedSeconds <= 0 ? properties.getApprovalTokenTtlSeconds() : requestedSeconds;
        seconds = Math.max(15, Math.min(seconds, properties.getMaxApprovalTokenTtlSeconds()));
        return Duration.ofSeconds(seconds);
    }

    private void cleanupExpired() {
        for (Map.Entry<String, DesktopApprovalToken> entry : tokens.entrySet()) {
            if (entry.getValue().expired()) {
                tokens.remove(entry.getKey());
                dryRunPasses.remove(entry.getKey());
            }
        }
        for (Map.Entry<String, DryRunPass> entry : dryRunPasses.entrySet()) {
            if (entry.getValue().expired() || !tokens.containsKey(entry.getKey())) {
                dryRunPasses.remove(entry.getKey());
            }
        }
    }

    private String actionSignature(List<DesktopApprovedAction> actions) {
        ArrayList<String> parts = new ArrayList<>();
        for (DesktopApprovedAction action : actions) {
            parts.add(String.join("|",
                    action.actionId(),
                    action.action(),
                    action.targetFieldId(),
                    Boolean.toString(action.write()),
                    Boolean.toString(action.sensitive()),
                    Boolean.toString(action.submit()),
                    action.value().isBlank() ? "" : Integer.toString(action.value().hashCode())
            ));
        }
        return String.join("\n", parts);
    }

    private Instant minInstant(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return switch (text.trim().toLowerCase()) {
                case "true", "1", "yes", "y", "on" -> true;
                case "false", "0", "no", "n", "off" -> false;
                default -> fallback;
            };
        }
        return fallback;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, limit)) + "…";
    }

    private record DryRunPass(
            String tokenId,
            String snapshotId,
            String actionSignature,
            Instant passedAt,
            Instant expiresAt,
            List<DesktopDryRunStep> steps
    ) {
        private DryRunPass {
            tokenId = tokenId == null ? "" : tokenId.trim();
            snapshotId = snapshotId == null ? "" : snapshotId.trim();
            actionSignature = actionSignature == null ? "" : actionSignature;
            passedAt = passedAt == null ? Instant.now() : passedAt;
            expiresAt = expiresAt == null ? passedAt : expiresAt;
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
