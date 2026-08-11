package com.takesome.springsuite.desktop;

import com.takesome.springsuite.core.api.SuiteApiResponse;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopCapabilitySchema;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopContextAnalysis;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFocusContext;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormFillPlan;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormFillRequest;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopHelperStatus;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopHintRequest;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopHintResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DesktopHelperController {
    private final DesktopHelperService desktopHelperService;

    public DesktopHelperController(DesktopHelperService desktopHelperService) {
        this.desktopHelperService = desktopHelperService;
    }

    @GetMapping("/api/desktop-helper/status")
    public SuiteApiResponse<DesktopHelperStatus> status() {
        return SuiteApiResponse.ok(desktopHelperService.status());
    }

    @GetMapping("/api/desktop-helper/schema")
    public SuiteApiResponse<DesktopCapabilitySchema> schema() {
        return SuiteApiResponse.ok(desktopHelperService.schema());
    }

    @PostMapping("/api/desktop-helper/context/analyze")
    public SuiteApiResponse<DesktopContextAnalysis> analyze(@RequestBody(required = false) DesktopFocusContext context) {
        DesktopContextAnalysis analysis = desktopHelperService.analyze(context);
        return analysis.ok()
                ? SuiteApiResponse.ok(analysis)
                : SuiteApiResponse.failed("desktop_context_analysis_failed", analysis.summary(), analysis);
    }

    @PostMapping("/api/desktop-helper/hints")
    public SuiteApiResponse<DesktopHintResponse> hints(@RequestBody(required = false) DesktopHintRequest request) {
        DesktopHintResponse response = desktopHelperService.hints(request);
        return response.ok()
                ? SuiteApiResponse.ok(response)
                : SuiteApiResponse.failed("desktop_hints_failed", response.summary(), response);
    }

    @PostMapping("/api/desktop-helper/form-fill/plan")
    public SuiteApiResponse<DesktopFormFillPlan> planFormFill(@RequestBody(required = false) DesktopFormFillRequest request) {
        DesktopFormFillPlan plan = desktopHelperService.planFormFill(request);
        return plan.ok()
                ? SuiteApiResponse.ok(plan)
                : SuiteApiResponse.failed("desktop_form_fill_plan_failed", plan.summary(), plan);
    }
}
