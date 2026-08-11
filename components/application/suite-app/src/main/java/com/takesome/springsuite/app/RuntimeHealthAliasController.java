package com.takesome.springsuite.app;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compatibility alias for control-plane builds that probe the public
 * runtime port for both health and identity. The dedicated management
 * server remains the authoritative actuator surface.
 */
@RestController
public final class RuntimeHealthAliasController {
    @GetMapping("/actuator/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "groups", new String[] { "liveness", "readiness" }
        );
    }
}
