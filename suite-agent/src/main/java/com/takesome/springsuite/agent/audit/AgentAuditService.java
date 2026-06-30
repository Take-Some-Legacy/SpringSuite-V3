package com.takesome.springsuite.agent.audit;

import com.takesome.springsuite.agent.AuthContext;
import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AgentAuditService {
    private final OperatorLogService logService;

    public AgentAuditService(OperatorLogService logService) {
        this.logService = logService;
    }

    public void mcpDiscovery(HttpServletRequest request) {
        logService.append(OperatorLogLevel.INFO, "mcp", "incoming mcp discovery", Map.of(
                "remote", safe(request.getRemoteAddr()),
                "user_agent", safe(request.getHeader("User-Agent"))
        ));
    }

    public void mcpRejected(HttpServletRequest request, String method, String reason) {
        logService.append(OperatorLogLevel.WARN, "mcp", "incoming mcp request rejected", Map.of(
                "remote", safe(request.getRemoteAddr()),
                "method", safe(method),
                "reason", safe(reason)
        ));
    }

    public void mcpAccepted(HttpServletRequest request, String method, AuthContext auth) {
        logService.append(OperatorLogLevel.INFO, "mcp", "incoming mcp request accepted", Map.of(
                "remote", safe(request.getRemoteAddr()),
                "method", safe(method),
                "subject", safe(auth.subject()),
                "token_type", safe(auth.tokenType())
        ));
    }

    public void toolCall(String name, AuthContext auth, String risk) {
        logService.append(OperatorLogLevel.INFO, "mcp", "mcp tool call", Map.of(
                "tool", safe(name),
                "subject", safe(auth.subject()),
                "token_type", safe(auth.tokenType()),
                "risk", safe(risk)
        ));
    }

    public void toolRejected(String name, AuthContext auth, List<String> requiredScopes, String risk) {
        logService.append(OperatorLogLevel.WARN, "mcp", "mcp tool call rejected", Map.of(
                "tool", safe(name),
                "subject", safe(auth.subject()),
                "required_scopes", String.join(" ", requiredScopes == null ? List.of() : requiredScopes),
                "risk", safe(risk)
        ));
    }

    public void oauthEvent(String message, Map<String, ?> fields) {
        logService.append(OperatorLogLevel.INFO, "auth", message, sanitize(fields));
    }

    private Map<String, Object> sanitize(Map<String, ?> fields) {
        if (fields == null || fields.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        fields.forEach((key, value) -> out.put(key, safe(value)));
        return out;
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
