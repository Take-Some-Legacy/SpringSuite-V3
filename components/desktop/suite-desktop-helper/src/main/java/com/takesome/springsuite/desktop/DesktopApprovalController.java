package com.takesome.springsuite.desktop;

import com.takesome.springsuite.core.api.SuiteApiResponse;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopActionDryRunRequest;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopActionDryRunResult;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopActionExecutionRequest;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopActionExecutionResult;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovalRequest;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovalResult;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovalToken;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DesktopApprovalController {
    private final DesktopApprovalService desktopApprovalService;

    public DesktopApprovalController(DesktopApprovalService desktopApprovalService) {
        this.desktopApprovalService = desktopApprovalService;
    }

    @GetMapping("/api/desktop-helper/approvals")
    public SuiteApiResponse<Map<String, Object>> approvals() {
        return SuiteApiResponse.ok(desktopApprovalService.summary());
    }

    @GetMapping("/api/desktop-helper/approvals/{tokenId}")
    public SuiteApiResponse<DesktopApprovalToken> approval(@PathVariable String tokenId) {
        return desktopApprovalService.find(tokenId)
                .map(SuiteApiResponse::ok)
                .orElseGet(() -> SuiteApiResponse.failed("approval_token_not_found", "Approval token was not found or has expired.", null));
    }

    @PostMapping("/api/desktop-helper/approvals")
    public SuiteApiResponse<DesktopApprovalResult> createApproval(@RequestBody(required = false) DesktopApprovalRequest request) {
        DesktopApprovalResult result = desktopApprovalService.createApproval(request);
        return result.ok()
                ? SuiteApiResponse.ok(result)
                : SuiteApiResponse.failed(result.code(), result.message(), result);
    }

    @PostMapping("/api/desktop-helper/actions/dry-run")
    public SuiteApiResponse<DesktopActionDryRunResult> dryRun(@RequestBody(required = false) DesktopActionDryRunRequest request) {
        DesktopActionDryRunResult result = desktopApprovalService.dryRun(request);
        return result.ok()
                ? SuiteApiResponse.ok(result)
                : SuiteApiResponse.failed(result.code(), result.message(), result);
    }

    @PostMapping("/api/desktop-helper/actions/execute")
    public SuiteApiResponse<DesktopActionExecutionResult> execute(@RequestBody(required = false) DesktopActionExecutionRequest request) {
        DesktopActionExecutionResult result = desktopApprovalService.execute(request);
        return result.ok()
                ? SuiteApiResponse.ok(result)
                : SuiteApiResponse.failed(result.code(), result.message(), result);
    }
}
