package com.takesome.springsuite.ai;

import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AiAuditService {
    private final OperatorLogService logService;

    public AiAuditService(OperatorLogService logService) {
        this.logService = logService;
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

    public void debug(String message, Map<String, Object> metadata) {
        append(OperatorLogLevel.DEBUG, message, metadata);
    }

    private void append(OperatorLogLevel level, String message, Map<String, Object> metadata) {
        logService.append(level, "ai", message, sanitize(metadata));
    }

    private Map<String, Object> sanitize(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        metadata.forEach((key, value) -> out.put(key, sanitizeValue(key, value)));
        return out;
    }

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
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return "Bearer <redacted>";
        }
        if (trimmed.startsWith("sk-") || trimmed.startsWith("sess-")) {
            return "<redacted>";
        }
        return value;
    }
}
