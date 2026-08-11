package com.takesome.springsuite.agent;

import com.takesome.springsuite.agent.audit.AgentAuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
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
        long started = System.nanoTime();
        audit.mcpDiscovery(request);
        Map<String, Object> body = mcpService.discovery(authService.baseUrl(request));
        audit.mcpCompleted(request, "discovery", null, 200, System.nanoTime() - started, body);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/mcp")
    public ResponseEntity<Map<String, Object>> mcp(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        long started = System.nanoTime();
        AuthContext auth = authService.authenticate(request);
        String method = body == null ? "" : String.valueOf(body.getOrDefault("method", ""));
        if (authProperties.isEnabled() && authProperties.isRequireAuthForMcp() && !auth.authenticated()) {
            audit.mcpRejected(request, method, "unauthorized");
            LinkedHashMap<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", body == null ? null : body.get("id"));
            response.put("error", Map.of("code", -32001, "message", "Unauthorized: OAuth Bearer or X-NorthStar-Bridge-Token required"));
            audit.mcpCompleted(request, method, auth, 401, System.nanoTime() - started, response);
            return ResponseEntity.status(401)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer resource_metadata=\"" + authService.unauthorizedResourceMetadataUrl(request, mcpProperties.getEndpoint()) + "\"")
                    .body(response);
        }
        audit.mcpAccepted(request, method, auth);
        Map<String, Object> response = mcpService.handle(body == null ? Map.of() : body, auth);
        audit.mcpCompleted(request, method, auth, 200, System.nanoTime() - started, response);
        return ResponseEntity.ok(response);
    }
}
