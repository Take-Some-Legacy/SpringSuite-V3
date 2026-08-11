package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopActionExecutionResult;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovedAction;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopExecutionStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RealDesktopActionExecutor implements DesktopActionExecutor {
    private static final String ID = "real-desktop-action-executor";

    private final DesktopBridgeAdapterRegistry bridgeRegistry;

    public RealDesktopActionExecutor(DesktopBridgeAdapterRegistry bridgeRegistry) {
        this.bridgeRegistry = bridgeRegistry;
    }

    @Override
    public Descriptor descriptor() {
        return new Descriptor(
                ID,
                "Real Desktop Action Executor",
                false,
                true,
                List.of("real-input", "bridge-backed", "clipboard", "keyboard", "mouse", "audit-required"),
                List.of("fill", "type", "paste", "click", "hotkey", "submit"),
                Map.of(
                        "realDesktopInput", true,
                        "requiresPolicy", "executor.allowed-real-input=true, executors.real-desktop-action-executor.enabled=true, bridge.allowed-real-input=true, selected bridge enabled=true",
                        "contract", "Delegates approved actions to DesktopBridgeAdapterRegistry."
                )
        );
    }

    @Override
    public DesktopActionExecutionResult execute(ExecutionContext context) {
        ArrayList<DesktopExecutionStep> steps = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>();
        boolean allOk = true;
        boolean anyPerformed = false;

        List<DesktopApprovedAction> actions = context.actions();
        for (int i = 0; i < actions.size(); i++) {
            DesktopApprovedAction action = actions.get(i);
            var bridge = bridgeRegistry.selectForAction(action);
            if (bridge.isEmpty()) {
                allOk = false;
                warnings.add("No enabled bridge adapter is available for action `" + action.action() + "`.");
                steps.add(blockedStep(i + 1, action, "bridge_missing", "No enabled bridge adapter is available for this action."));
                continue;
            }

            DesktopBridgeAdapter adapter = bridge.get();
            DesktopBridgeAdapter.Descriptor bridgeDescriptor = bridgeRegistry.effectiveDescriptor(adapter);
            DesktopBridgeAdapter.BridgeActionResult bridgeResult = adapter.perform(new DesktopBridgeAdapter.BridgeActionContext(
                    action,
                    context.snapshot(),
                    context.guards(),
                    context.requestedAt(),
                    Map.of(
                            "executor", ID,
                            "bridge", bridgeDescriptor.id(),
                            "bridgeDescriptor", bridgeDescriptor
                    )
            ));

            if (!bridgeResult.ok()) {
                allOk = false;
            }
            if (bridgeResult.realInputPerformed()) {
                anyPerformed = true;
            }
            warnings.addAll(bridgeResult.warnings());
            steps.add(new DesktopExecutionStep(
                    i + 1,
                    action.actionId(),
                    action.action(),
                    action.targetFieldId(),
                    bridgeResult.ok() ? "executed" : "blocked",
                    bridgeResult.message(),
                    false,
                    bridgeResult.realInputPerformed(),
                    bridgeResult.guards(),
                    bridgeResult.warnings(),
                    Map.of(
                            "bridge", bridgeDescriptor,
                            "bridgeCode", bridgeResult.code(),
                            "realInputAttempted", bridgeResult.realInputAttempted(),
                            "realInputPerformed", bridgeResult.realInputPerformed()
                    )
            ));
        }

        String tokenId = context.token() == null ? "" : context.token().tokenId();
        String snapshotId = context.snapshot() == null ? "" : context.snapshot().snapshotId();
        return new DesktopActionExecutionResult(
                allOk,
                allOk ? "ok" : "real_input_partial_or_blocked",
                allOk ? "Real desktop action executor completed." : "Real desktop action executor blocked or failed at least one step.",
                tokenId,
                snapshotId,
                false,
                anyPerformed,
                steps,
                warnings,
                Map.of(
                        "executor", descriptor(),
                        "bridgeRegistry", bridgeRegistry.summary(),
                        "requestedAt", context.requestedAt().toString(),
                        "executionMode", "real-bridge-backed"
                )
        );
    }

    private DesktopExecutionStep blockedStep(int order, DesktopApprovedAction action, String code, String message) {
        return new DesktopExecutionStep(
                order,
                action.actionId(),
                action.action(),
                action.targetFieldId(),
                "blocked",
                message,
                false,
                false,
                List.of("executor:real", "bridge:missing"),
                List.of(message),
                Map.of("code", code)
        );
    }
}
