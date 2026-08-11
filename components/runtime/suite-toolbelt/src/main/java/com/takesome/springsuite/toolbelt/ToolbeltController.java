package com.takesome.springsuite.toolbelt;

import com.takesome.springsuite.core.api.SuiteApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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

    @GetMapping("/api/toolbelt/inventory")
    public SuiteApiResponse<ToolInventory> inventory() {
        return SuiteApiResponse.ok(toolbeltService.inventory());
    }

    @GetMapping("/api/toolbelt/index")
    public SuiteApiResponse<List<ToolIndexEntry>> index() {
        return SuiteApiResponse.ok(toolbeltService.index());
    }

    @GetMapping("/api/toolbelt/search")
    public SuiteApiResponse<List<ToolDescriptor>> search(
            @RequestParam(name = "q", required = false, defaultValue = "") String query,
            @RequestParam(name = "limit", required = false, defaultValue = "50") int limit,
            @RequestParam(name = "source", required = false, defaultValue = "") String source,
            @RequestParam(name = "kind", required = false, defaultValue = "") String kind,
            @RequestParam(name = "available", required = false) Boolean available,
            @RequestParam(name = "tag", required = false, defaultValue = "") String tag
    ) {
        return SuiteApiResponse.ok(toolbeltService.search(query, limit, source, kind, available, tag));
    }

    @GetMapping("/api/toolbelt/tools")
    public SuiteApiResponse<List<ToolDescriptor>> tools(
            @RequestParam(name = "q", required = false, defaultValue = "") String query,
            @RequestParam(name = "limit", required = false, defaultValue = "500") int limit,
            @RequestParam(name = "source", required = false, defaultValue = "") String source,
            @RequestParam(name = "kind", required = false, defaultValue = "") String kind,
            @RequestParam(name = "available", required = false) Boolean available,
            @RequestParam(name = "tag", required = false, defaultValue = "") String tag
    ) {
        if (query.isBlank() && source.isBlank() && kind.isBlank() && available == null && tag.isBlank()) {
            return SuiteApiResponse.ok(toolbeltService.listTools());
        }
        return SuiteApiResponse.ok(toolbeltService.search(query, limit, source, kind, available, tag));
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

    @PostMapping("/api/toolbelt/reindex")
    public SuiteApiResponse<ToolInventory> reindex() {
        toolbeltService.refresh();
        return SuiteApiResponse.ok("toolbelt reindexed", toolbeltService.inventory());
    }

    @PostMapping("/api/toolbelt/run")
    public SuiteApiResponse<ToolRunResult> run(@RequestBody ToolRunRequest request) {
        return SuiteApiResponse.ok(toolbeltService.run(request));
    }
}
