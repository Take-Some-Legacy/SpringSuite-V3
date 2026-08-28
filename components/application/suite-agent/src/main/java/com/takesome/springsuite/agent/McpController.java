package com.takesome.springsuite.agent;

import com.takesome.springsuite.agent.audit.AgentAuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class McpController {
    private static final String MCP_PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";

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

    @GetMapping("/mcp/discovery")
    public ResponseEntity<Map<String, Object>> discovery(HttpServletRequest request) {
        long started = System.nanoTime();
        audit.mcpDiscovery(request);
        Map<String, Object> body = mcpService.discovery(authService.baseUrl(request));
        audit.mcpCompleted(request, "discovery", null, 200, System.nanoTime() - started, body);
        return ResponseEntity.ok(body);
    }

    /**
     * Streamable HTTP reserves GET /mcp for a server-initiated event stream. SpringSuite does not
     * expose one, so the transport path explicitly reports POST-only instead of returning a
     * non-MCP discovery document from the MCP resource itself.
     */
    @GetMapping("/mcp")
    public ResponseEntity<Void> mcpGet() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.ALLOW, "POST")
                .build();
    }

    @PostMapping("/mcp")
    public ResponseEntity<?> mcp(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        long started = System.nanoTime();
        Map<String, Object> safeBody = body == null ? Map.of() : body;
        String method = String.valueOf(safeBody.getOrDefault("method", ""));
        boolean initialize = "initialize".equals(method);

        String protocolVersion = initialize
                ? mcpProperties.getProtocolVersion()
                : protocolVersionForRequest(request);
        if (!initialize && !mcpProperties.supportsProtocolVersion(protocolVersion)) {
            Map<String, Object> response = protocolError(safeBody.get("id"), protocolVersion);
            audit.mcpCompleted(request, method, null, 400, System.nanoTime() - started, response);
            return ResponseEntity.badRequest().body(response);
        }

        AuthContext auth = authService.authenticate(request);
        if (authProperties.isEnabled() && authProperties.isRequireAuthForMcp() && !auth.authenticated()) {
            audit.mcpRejected(request, method, "unauthorized");
            LinkedHashMap<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", safeBody.get("id"));
            response.put("error", Map.of("code", -32001, "message", "Unauthorized: OAuth Bearer or X-NorthStar-Bridge-Token required"));
            audit.mcpCompleted(request, method, auth, 401, System.nanoTime() - started, response);
            return ResponseEntity.status(401)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer resource_metadata=\"" + authService.unauthorizedResourceMetadataUrl(request, mcpProperties.getEndpoint()) + "\"")
                    .body(response);
        }

        audit.mcpAccepted(request, method, auth);
        Map<String, Object> response = mcpService.handle(safeBody, auth, protocolVersion);

        if (!safeBody.containsKey("id")) {
            audit.mcpCompleted(request, method, auth, 202, System.nanoTime() - started, Map.of("notification", true));
            return ResponseEntity.accepted().build();
        }

        audit.mcpCompleted(request, method, auth, 200, System.nanoTime() - started, response);
        return ResponseEntity.ok(response);
    }

    private String protocolVersionForRequest(HttpServletRequest request) {
        String header = request.getHeader(MCP_PROTOCOL_VERSION_HEADER);
        if (header == null || header.isBlank()) {
            return SuiteMcpProperties.LEGACY_PROTOCOL_VERSION;
        }
        return header.trim();
    }

    private Map<String, Object> protocolError(Object id, String version) {
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of(
                "code", -32600,
                "message", "Unsupported MCP-Protocol-Version: " + version,
                "data", Map.of("supported", mcpProperties.getSupportedProtocolVersions())
        ));
        return response;
    }
}
