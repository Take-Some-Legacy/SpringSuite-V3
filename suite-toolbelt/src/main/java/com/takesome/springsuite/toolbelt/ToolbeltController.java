package com.takesome.springsuite.toolbelt;

import com.takesome.springsuite.core.api.SuiteApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ToolbeltController {
    private final ToolbeltService toolbeltService;

    public ToolbeltController(ToolbeltService toolbeltService) {
        this.toolbeltService = toolbeltService;
    }

    @GetMapping("/api/toolbelt")
    public SuiteApiResponse<ToolbeltSummary> summary() {
        return SuiteApiResponse.ok(toolbeltService.summary());
    }

    @GetMapping("/api/toolbelt/tools")
    public SuiteApiResponse<List<ToolDescriptor>> tools() {
        return SuiteApiResponse.ok(toolbeltService.listTools());
    }

    @GetMapping("/api/toolbelt/tools/{id}")
    public SuiteApiResponse<ToolDescriptor> tool(@PathVariable String id) {
        return toolbeltService.find(id)
                .map(SuiteApiResponse::ok)
                .orElseGet(() -> SuiteApiResponse.failed("not_found", "tool not found: " + id, null));
    }

    @PostMapping("/api/toolbelt/refresh")
    public SuiteApiResponse<ToolbeltSummary> refresh() {
        return SuiteApiResponse.ok("toolbelt refreshed", toolbeltService.refresh());
    }

    @PostMapping("/api/toolbelt/run")
    public SuiteApiResponse<ToolRunResult> run(@RequestBody ToolRunRequest request) {
        return SuiteApiResponse.ok(toolbeltService.run(request));
    }
}
