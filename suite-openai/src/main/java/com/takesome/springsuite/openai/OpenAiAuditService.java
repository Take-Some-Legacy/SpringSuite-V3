package com.takesome.springsuite.openai;

import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OpenAiAuditService {
    private final OperatorLogService logService;

    public OpenAiAuditService(OperatorLogService logService) {
        this.logService = logService;
    }

    public void trace(String message, Map<String, Object> metadata) {
        append(OperatorLogLevel.TRACE, message, metadata);
    }

    public void debug(String message, Map<String, Object> metadata) {
        append(OperatorLogLevel.DEBUG, message, metadata);
    }

    public void info(String message, Map<String, Object> metadata) {
        append(OperatorLogLevel.INFO, message, metadata);
    }

    public void warn(String message, Map<String, Object> metadata) {
        append(OperatorLogLevel.WARN, message, metadata);
    }

    public void error(String message, Map<String, Object> metadata) {
        append(OperatorLogLevel.ERROR, message, metadata);
    }

    public void credential(String message, OpenAiCredentialStatus credential, Map<String, Object> extra) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (credential != null) {
            metadata.put("available", credential.available());
            metadata.put("mode", credential.mode());
            metadata.put("kind", credential.credentialKind());
            metadata.put("source", credential.source());
            metadata.put("fingerprint", credential.fingerprint());
            metadata.put("expiresAt", credential.expiresAt());
            metadata.put("refreshAt", credential.refreshAt());
            metadata.put("cached", credential.cached());
            metadata.put("cachePath", credential.cachePath());
            metadata.put("message", credential.message());
        }
        if (extra != null) {
            metadata.putAll(extra);
        }
        append(credential != null && credential.available() ? OperatorLogLevel.INFO : OperatorLogLevel.WARN, message, metadata);
    }

    private void append(OperatorLogLevel level, String message, Map<String, Object> metadata) {
        logService.append(level, "openai", message, sanitize(metadata));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitize(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        metadata.forEach((key, value) -> out.put(key, sanitizeValue(key, value)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (isSensitiveKey(key)) {
            return "<redacted>";
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            map.forEach((childKey, childValue) -> out.put(String.valueOf(childKey), sanitizeValue(String.valueOf(childKey), childValue)));
            return out;
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(sanitizeValue(key, item));
            }
            return out;
        }
        if (value instanceof String text) {
            return sanitizeString(key, text);
        }
        return value;
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return normalized.contains("token")
                || normalized.contains("api_key")
                || normalized.contains("apikey")
                || normalized.contains("secret")
                || normalized.contains("authorization")
                || normalized.contains("password")
                || normalized.equals("key");
    }

    private String sanitizeString(String key, String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String trimmed = value.trim();
        String lowerKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return "Bearer <redacted>";
        }
        if (trimmed.startsWith("sk-") || trimmed.startsWith("sess-") || lowerKey.contains("credential")) {
            return "<redacted>";
        }
        return value;
    }
}
