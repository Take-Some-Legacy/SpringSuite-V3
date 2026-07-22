package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopActionExecutionResult;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BrowserDomDesktopActionExecutor implements DesktopActionExecutor {
    private static final Descriptor DESCRIPTOR = new Descriptor(
            "browser-dom-desktop-action-executor",
            "Browser DOM Desktop Action Executor",
            false,
            false,
            List.of("browser-dom", "form-fill", "field-select", "operator-confirmed-command", "no-auto-submit"),
            List.of("fill", "select", "check", "uncheck"),
            Map.of(
                    "backend", "BrowserDomCommandService",
                    "realDesktopInput", false,
                    "enabled", false,
                    "submitEnabled", false,
                    "contract", "Browser DOM writes use the dedicated short-lived command queue after an explicit operator gesture."
            )
    );

    @Override
    public Descriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public DesktopActionExecutionResult execute(ExecutionContext context) {
        String tokenId = context == null || context.token() == null ? "" : context.token().tokenId();
        String snapshotId = context == null || context.snapshot() == null ? "" : context.snapshot().snapshotId();
        return DesktopActionExecutionResult.failed(
                "browser_dom_command_queue_required",
                "Browser DOM actions must use BrowserDomCommandService rather than the generic desktop executor path.",
                tokenId,
                snapshotId,
                List.of("Use the operator-confirmed browser command queue; automatic submit remains disabled."),
                Map.of("executor", DESCRIPTOR)
        );
    }
}
