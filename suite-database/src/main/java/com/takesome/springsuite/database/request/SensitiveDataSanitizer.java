package com.takesome.springsuite.database.request;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class SensitiveDataSanitizer {
    private static final String REDACTED = "<redacted>";
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+\\-/=]+");
    private static final Pattern OPENAI_KEY_PATTERN = Pattern.compile("(?i)\\b(?:sk|sess)-[A-Za-z0-9._-]{8,}\\b");
    private static final Pattern JSON_SECRET_PATTERN = Pattern.compile(
            "(?i)(\\\"(?:access_token|refresh_token|id_token|api[_-]?key|password|secret|client_secret|authorization)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")"
    );

    private final ObjectMapper objectMapper;
    private final boolean redact;
    private final int maxHeaderValueChars;

    SensitiveDataSanitizer(ObjectMapper objectMapper, boolean redact, int maxHeaderValueChars) {
        this.objectMapper = objectMapper;
        this.redact = redact;
        this.maxHeaderValueChars = Math.max(256, maxHeaderValueChars);
    }

    String requestHeaders(HttpServletRequest request) {
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                headers.put(name, sanitizeHeaderValues(name, Collections.list(request.getHeaders(name))));
            }
        }
        return writeJson(headers);
    }

    String responseHeaders(HttpServletResponse response) {
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : response.getHeaderNames()) {
            headers.put(name, sanitizeHeaderValues(name, new ArrayList<>(response.getHeaders(name))));
        }
        return writeJson(headers);
    }

    String queryString(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank() || !redact) {
            return rawQuery == null ? "" : rawQuery;
        }
        StringBuilder output = new StringBuilder(rawQuery.length());
        String[] pairs = rawQuery.split("&", -1);
        for (int index = 0; index < pairs.length; index++) {
            if (index > 0) output.append('&');
            String pair = pairs[index];
            int separator = pair.indexOf('=');
            String encodedKey = separator < 0 ? pair : pair.substring(0, separator);
            String key = decode(encodedKey);
            output.append(encodedKey);
            if (separator >= 0) {
                output.append('=').append(isSensitiveName(key) ? REDACTED : sanitizeText(pair.substring(separator + 1)));
            }
        }
        return output.toString();
    }

    String body(byte[] bytes, String contentType, String characterEncoding, boolean truncated, long totalBytes) {
        if (bytes == null || bytes.length == 0) return "";
        if (!isTextual(contentType)) {
            return "<binary content omitted; captured=" + bytes.length + " bytes; total=" + totalBytes + " bytes>";
        }
        Charset charset = resolveCharset(characterEncoding);
        String text = new String(bytes, charset);
        String sanitized = redact ? sanitizeStructuredText(text, contentType) : text;
        if (truncated) {
            sanitized += "\n<truncated; captured=" + bytes.length + " bytes; total=" + totalBytes + " bytes>";
        }
        return sanitized;
    }

    String sanitizeThrowableMessage(String message) {
        return redact ? sanitizeText(message) : valueOrEmpty(message);
    }

    private List<String> sanitizeHeaderValues(String name, List<String> values) {
        if (redact && isSensitiveName(name)) return List.of(REDACTED);
        return values.stream().map(this::sanitizeText).map(this::limitHeader).toList();
    }

    private String sanitizeStructuredText(String text, String contentType) {
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (normalized.contains("json") || looksLikeJson(text)) {
            try {
                JsonNode root = objectMapper.readTree(text);
                sanitizeJson(root);
                return objectMapper.writeValueAsString(root);
            } catch (JsonProcessingException ignored) {
                // Preserve malformed payloads while still removing obvious credentials below.
            }
        }
        if (normalized.contains("x-www-form-urlencoded")) return queryString(text);
        return sanitizeText(text);
    }

    private void sanitizeJson(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode value = object.get(name);
                if (isSensitiveName(name)) {
                    object.put(name, REDACTED);
                } else {
                    sanitizeJson(value);
                    if (value != null && value.isTextual()) object.put(name, sanitizeText(value.asText()));
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                JsonNode value = array.get(index);
                sanitizeJson(value);
                if (value != null && value.isTextual()) {
                    array.set(index, objectMapper.getNodeFactory().textNode(sanitizeText(value.asText())));
                }
            }
        }
    }

    private String sanitizeText(String value) {
        String safe = valueOrEmpty(value);
        if (!redact || safe.isBlank()) return safe;
        safe = BEARER_PATTERN.matcher(safe).replaceAll("$1" + REDACTED);
        safe = OPENAI_KEY_PATTERN.matcher(safe).replaceAll(REDACTED);
        safe = JSON_SECRET_PATTERN.matcher(safe).replaceAll("$1" + REDACTED + "$2");
        return safe;
    }

    private String limitHeader(String value) {
        if (value.length() <= maxHeaderValueChars) return value;
        return value.substring(0, maxHeaderValueChars) + "<truncated>";
    }

    private boolean isSensitiveName(String name) {
        String normalized = valueOrEmpty(name).toLowerCase(Locale.ROOT).replace('-', '_');
        return normalized.equals("authorization")
                || normalized.equals("proxy_authorization")
                || normalized.equals("cookie")
                || normalized.equals("set_cookie")
                || normalized.contains("password")
                || normalized.contains("passphrase")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("api_key")
                || normalized.contains("apikey")
                || normalized.contains("credential")
                || normalized.contains("private_key");
    }

    private boolean isTextual(String contentType) {
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return normalized.isBlank()
                || normalized.startsWith("text/")
                || normalized.contains("json")
                || normalized.contains("xml")
                || normalized.contains("javascript")
                || normalized.contains("graphql")
                || normalized.contains("x-www-form-urlencoded")
                || normalized.contains("yaml");
    }

    private boolean looksLikeJson(String text) {
        String trimmed = valueOrEmpty(text).trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    private String decode(String value) {
        try { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
        catch (IllegalArgumentException ex) { return value; }
    }

    private Charset resolveCharset(String value) {
        try { return value == null || value.isBlank() ? StandardCharsets.UTF_8 : Charset.forName(value); }
        catch (RuntimeException ex) { return StandardCharsets.UTF_8; }
    }

    private String writeJson(Map<String, List<String>> value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { return "{}"; }
    }

    private static String valueOrEmpty(String value) { return value == null ? "" : value; }
}
