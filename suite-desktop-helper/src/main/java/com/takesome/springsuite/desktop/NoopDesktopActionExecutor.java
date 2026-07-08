package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopActionExecutionResult;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovedAction;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopExecutionStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class NoopDesktopActionExecutor implements DesktopActionExecutor {
    @Override
    public Descriptor descriptor() {
        return new Descriptor(
                "noop-desktop-action-executor",
                "No-op Desktop Action Executor",
                false,
                List.of("execution-stub", "simulation", "audit-safe", "no-real-input"),
                List.of("fill", "type", "paste", "select", "check", "uncheck", "click", "hotkey", "submit"),
                Map.of(
                        "realDesktopInput", false,
                        "contract", "All actions are converted into simulated DesktopExecutionStep records."
                )
        );
    }

    @Override
    public DesktopActionExecutionResult execute(ExecutionContext context) {
        ArrayList<DesktopExecutionStep> steps = new ArrayList<>();
        List<DesktopApprovedAction> actions = context.actions();
        for (int i = 0; i < actions.size(); i++) {
            DesktopApprovedAction action = actions.get(i);
            steps.add(new DesktopExecutionStep(
                    i + 1,
                    action.actionId(),
                    action.action(),
                    action.targetFieldId(),
                    "simulated",
                    preview(action),
                    true,
                    false,
                    mergeGuards(context.guards(), List.of("executor:noop", "executor:no-real-input")),
                    List.of("No real desktop input was performed."),
                    Map.of(
                            "label", action.label(),
                            "reason", action.reason(),
                            "executor", descriptor().id()
                    )
            ));
        }

        String tokenId = context.token() == null ? "" : context.token().tokenId();
        String snapshotId = context.snapshot() == null ? "" : context.snapshot().snapshotId();
        return new DesktopActionExecutionResult(
                true,
                "ok",
                "No-op desktop executor completed; no real desktop input was performed.",
                tokenId,
                snapshotId,
                true,
                false,
                steps,
                List.of(),
                Map.of(
                        "executor", descriptor().id(),
                        "realInputEnabled", false,
                        "requestedAt", context.requestedAt().toString(),
                        "executionMode", "noop-simulation"
                )
        );
    }

    private List<String> mergeGuards(List<String> left, List<String> right) {
        ArrayList<String> merged = new ArrayList<>();
        if (left != null) {
            merged.addAll(left);
        }
        if (right != null) {
            merged.addAll(right);
        }
        return List.copyOf(merged);
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

    private String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, limit)) + "…";
    }
}
