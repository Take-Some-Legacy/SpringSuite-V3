package com.takesome.springsuite.agent.audit;

import com.takesome.springsuite.agent.AuthContext;
import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
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
        LinkedHashMap<String, Object> fields = connectionFields(request);
        fields.put("flow", "mcp.discovery");
        logService.append(OperatorLogLevel.INFO, "mcp", "incoming mcp discovery", fields);
    }

    public void mcpRejected(HttpServletRequest request, String method, String reason) {
        LinkedHashMap<String, Object> fields = connectionFields(request);
        fields.put("flow", "mcp.request");
        fields.put("jsonrpc_method", safe(method));
        fields.put("status", 401);
        fields.put("reason", safe(reason));
        logService.append(OperatorLogLevel.WARN, "mcp", "incoming mcp request rejected", fields);
    }

    public void mcpAccepted(HttpServletRequest request, String method, AuthContext auth) {
        LinkedHashMap<String, Object> fields = connectionFields(request);
        fields.put("flow", "mcp.request");
        fields.put("jsonrpc_method", safe(method));
        fields.putAll(authFields(auth));
        logService.append(OperatorLogLevel.INFO, "mcp", "incoming mcp request accepted", fields);
    }

    public void mcpCompleted(HttpServletRequest request, String method, AuthContext auth, int status, long durationNanos, Map<String, Object> response) {
        LinkedHashMap<String, Object> fields = connectionFields(request);
        fields.put("flow", "mcp.response");
        fields.put("jsonrpc_method", safe(method));
        fields.put("status", status);
        fields.put("duration_ms", Math.max(0L, durationNanos) / 1_000_000.0d);
        fields.putAll(authFields(auth));
        fields.putAll(responseFields(response));
        logService.append(status >= 400 ? OperatorLogLevel.WARN : OperatorLogLevel.INFO, "mcp", "mcp request completed", fields);
    }

    public void toolCall(String name, AuthContext auth, String risk) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("tool", safe(name));
        fields.put("tool_namespace", namespaceOf(name));
        fields.putAll(authFields(auth));
        fields.put("risk", safe(risk));
        logService.append(OperatorLogLevel.INFO, "mcp", "mcp tool call", fields);
    }

    public void toolRejected(String name, AuthContext auth, List<String> requiredScopes, String risk) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("tool", safe(name));
        fields.put("tool_namespace", namespaceOf(name));
        fields.putAll(authFields(auth));
        fields.put("required_scopes", requiredScopes == null ? List.of() : requiredScopes);
        fields.put("risk", safe(risk));
        logService.append(OperatorLogLevel.WARN, "mcp", "mcp tool call rejected", fields);
    }

    public void oauthEvent(String message, Map<String, ?> fields) {
        logService.append(OperatorLogLevel.INFO, "auth", message, sanitize(fields));
    }

    private LinkedHashMap<String, Object> connectionFields(HttpServletRequest request) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("request_id", requestId(request));
        fields.put("remote_addr", safe(request.getRemoteAddr()));
        putHeader(fields, request, "x_forwarded_for", "X-Forwarded-For");
        putHeader(fields, request, "x_real_ip", "X-Real-IP");
        putHeader(fields, request, "x_forwarded_proto", "X-Forwarded-Proto");
        putHeader(fields, request, "x_forwarded_host", "X-Forwarded-Host");
        putHeader(fields, request, "host", "Host");
        fields.put("scheme", safe(request.getScheme()));
        fields.put("secure", request.isSecure());
        fields.put("server_name", safe(request.getServerName()));
        fields.put("server_port", request.getServerPort());
        fields.put("http_method", safe(request.getMethod()));
        fields.put("uri", safe(request.getRequestURI()));
        fields.put("query_present", request.getQueryString() != null && !request.getQueryString().isBlank());
        fields.put("content_type", safe(request.getContentType()));
        fields.put("content_length", request.getContentLengthLong());
        putHeader(fields, request, "accept", "Accept");
        putHeader(fields, request, "user_agent", "User-Agent");
        return fields;
    }

    private LinkedHashMap<String, Object> authFields(AuthContext auth) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        if (auth == null) {
            fields.put("authenticated", false);
            return fields;
        }
        fields.put("authenticated", auth.authenticated());
        fields.put("subject", safe(auth.subject()));
        fields.put("token_type", safe(auth.tokenType()));
        fields.put("scopes", auth.scopes() == null ? List.of() : auth.scopes());
        return fields;
    }

    private LinkedHashMap<String, Object> responseFields(Map<String, Object> response) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        if (response == null || response.isEmpty()) {
            fields.put("jsonrpc_error", false);
            return fields;
        }
        Object id = response.get("id");
        if (id != null) {
            fields.put("jsonrpc_id", String.valueOf(id));
        }
        Object error = response.get("error");
        fields.put("jsonrpc_error", error != null);
        if (error instanceof Map<?, ?> map) {
            Object code = map.get("code");
            Object message = map.get("message");
            if (code != null) {
                fields.put("jsonrpc_error_code", String.valueOf(code));
            }
            if (message != null) {
                fields.put("jsonrpc_error_message", String.valueOf(message));
            }
        }
        return fields;
    }

    private String requestId(HttpServletRequest request) {
        String explicit = firstHeader(request, "X-Request-ID", "X-Correlation-ID", "X-Amzn-Trace-Id");
        if (!explicit.isBlank()) {
            return explicit;
        }
        String traceparent = safe(request.getHeader("traceparent"));
        if (!traceparent.isBlank()) {
            return traceparent.length() <= 64 ? traceparent : traceparent.substring(0, 64);
        }
        return "";
    }

    private String firstHeader(HttpServletRequest request, String... names) {
        for (String name : names) {
            String value = safe(request.getHeader(name));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private void putHeader(Map<String, Object> fields, HttpServletRequest request, String key, String header) {
        String value = safe(request.getHeader(header));
        if (!value.isBlank()) {
            fields.put(key, value);
        }
    }

    private Map<String, Object> sanitize(Map<String, ?> fields) {
        if (fields == null || fields.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        fields.forEach((key, value) -> out.put(key, safe(value)));
        return out;
    }

    private String namespaceOf(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        int dot = name.indexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
