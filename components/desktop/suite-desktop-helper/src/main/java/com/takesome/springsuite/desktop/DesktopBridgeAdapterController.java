package com.takesome.springsuite.desktop;

import com.takesome.springsuite.core.api.SuiteApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DesktopBridgeAdapterController {
    private final DesktopBridgeAdapterRegistry registry;

    public DesktopBridgeAdapterController(DesktopBridgeAdapterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/api/desktop-helper/bridges")
    public SuiteApiResponse<Map<String, Object>> bridges() {
        return SuiteApiResponse.ok(Map.of(
                "summary", registry.summary(),
                "policy", registry.policySnapshot(),
                "bridges", registry.descriptors()
        ));
    }

    @GetMapping("/api/desktop-helper/bridges/policy")
    public SuiteApiResponse<Map<String, Object>> policy() {
        return SuiteApiResponse.ok(registry.policySnapshot());
    }

    @GetMapping("/api/desktop-helper/bridges/{id}")
    public SuiteApiResponse<DesktopBridgeAdapter.Descriptor> bridge(@PathVariable String id) {
        return registry.descriptor(id)
                .map(SuiteApiResponse::ok)
                .orElseGet(() -> SuiteApiResponse.failed("bridge_not_found", "Desktop bridge adapter not found: " + id, null));
    }
}
