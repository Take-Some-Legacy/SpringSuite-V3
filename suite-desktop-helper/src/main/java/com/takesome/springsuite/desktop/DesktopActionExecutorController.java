package com.takesome.springsuite.desktop;

import com.takesome.springsuite.core.api.SuiteApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DesktopActionExecutorController {
    private final DesktopActionExecutorRegistry registry;

    public DesktopActionExecutorController(DesktopActionExecutorRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/api/desktop-helper/executors")
    public SuiteApiResponse<Map<String, Object>> executors() {
        return SuiteApiResponse.ok(Map.of(
                "summary", registry.summary(),
                "executors", registry.descriptors()
        ));
    }

    @GetMapping("/api/desktop-helper/executors/{id}")
    public SuiteApiResponse<DesktopActionExecutor.Descriptor> executor(@PathVariable String id) {
        return registry.descriptor(id)
                .map(SuiteApiResponse::ok)
                .orElseGet(() -> SuiteApiResponse.failed("executor_not_found", "Desktop action executor not found: " + id, null));
    }
}
