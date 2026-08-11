package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopActionExecutionResult;
import java.util.List;
import java.util.Map;

public abstract class DisabledDesktopActionExecutor implements DesktopActionExecutor {
    private final Descriptor descriptor;

    protected DisabledDesktopActionExecutor(
            String id,
            String name,
            List<String> capabilities,
            List<String> supportedActions,
            String futureBackend
    ) {
        this.descriptor = new Descriptor(
                id,
                name,
                false,
                false,
                capabilities,
                supportedActions,
                Map.of(
                        "futureBackend", futureBackend,
                        "realDesktopInput", false,
                        "enabled", false,
                        "contract", "Disabled skeleton backend. It exposes future integration metadata and performs no real input."
                )
        );
    }

    @Override
    public Descriptor descriptor() {
        return descriptor;
    }

    @Override
    public DesktopActionExecutionResult execute(ExecutionContext context) {
        String tokenId = context == null || context.token() == null ? "" : context.token().tokenId();
        String snapshotId = context == null || context.snapshot() == null ? "" : context.snapshot().snapshotId();
        return DesktopActionExecutionResult.failed(
                "executor_disabled",
                descriptor.id() + " is a disabled skeleton backend and cannot execute desktop actions.",
                tokenId,
                snapshotId,
                List.of("Executor backend is disabled; no real desktop input was performed."),
                Map.of("executor", descriptor)
        );
    }
}
