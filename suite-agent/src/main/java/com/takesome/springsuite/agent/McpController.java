package com.takesome.springsuite.agent;

import com.takesome.springsuite.agent.audit.AgentAuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class McpController {
    private final SuiteAuthProperties authProperties;
    private final SuiteMcpProperties mcpProperties;
    private final SuiteAuthService authService;
    private final McpService mcpService;
    private final AgentAuditService audit;

    public McpController(SuiteAuthProperties authProperties, SuiteMcpProperties mcpProperties, SuiteAuthService authService, McpService mcpService, AgentAuditService audit) {
        this.authProperties = authProperties;
        this.mcpProperties = mcpProperties;
        this.authService = authService;
        this.mcpService = mcpService;
        this.audit = audit;
    }

    @GetMapping("/mcp")
    public ResponseEntity<Map<String, Object>> discovery(HttpServletRequest request) {
        audit.mcpDiscovery(request);
        return ResponseEntity.ok(mcpService.discovery(authService.baseUrl(request)));
    }

    @PostMapping("/mcp")
    public ResponseEntity<Map<String, Object>> mcp(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        AuthContext auth = authService.authenticate(request);
        String method = body == null ? "" : String.valueOf(body.getOrDefault("method", ""));
        if (authProperties.isEnabled() && authProperties.isRequireAuthForMcp() && !auth.authenticated()) {
            audit.mcpRejected(request, method, "unauthorized");
            return ResponseEntity.status(401)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer resource_metadata=\"" + authService.unauthorizedResourceMetadataUrl(request, mcpProperties.getEndpoint()) + "\"")
                    .body(Map.of("jsonrpc", "2.0", "id", body == null ? null : body.get("id"), "error", Map.of("code", -32001, "message", "Unauthorized: OAuth Bearer or X-NorthStar-Bridge-Token required")));
        }
        audit.mcpAccepted(request, method, auth);
        return ResponseEntity.ok(mcpService.handle(body == null ? Map.of() : body, auth));
    }
}
