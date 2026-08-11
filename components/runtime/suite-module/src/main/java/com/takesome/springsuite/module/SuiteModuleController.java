package com.takesome.springsuite.module;

import com.takesome.springsuite.core.api.SuiteApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SuiteModuleController {
    private final SuiteModuleRegistry moduleRegistry;

    public SuiteModuleController(SuiteModuleRegistry moduleRegistry) {
        this.moduleRegistry = moduleRegistry;
    }

    @GetMapping("/api/modules")
    public SuiteApiResponse<SuiteModuleSummary> summary() {
        return SuiteApiResponse.ok(moduleRegistry.summary());
    }

    @GetMapping("/api/modules/list")
    public SuiteApiResponse<List<RegisteredSuiteModule>> modules() {
        return SuiteApiResponse.ok(moduleRegistry.modules());
    }

    @GetMapping("/api/modules/{id}")
    public SuiteApiResponse<RegisteredSuiteModule> module(@PathVariable String id) {
        return moduleRegistry.find(id)
                .map(SuiteApiResponse::ok)
                .orElseGet(() -> SuiteApiResponse.failed("module_not_found", "Module not found: " + id, null));
    }
}
