package com.takesome.springsuite.agent.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AuthCodec {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public AuthCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<String> splitScopes(String scope) {
        ArrayList<String> values = new ArrayList<>();
        for (String part : scope.split("\\s+")) {
            if (!part.isBlank()) {
                values.add(part);
            }
        }
        return values;
    }

    public Map<String, String> parseBody(byte[] bodyBytes, String contentType) {
        String raw = new String(bodyBytes == null ? new byte[0] : bodyBytes, StandardCharsets.UTF_8);
        String ctype = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase();
        if (raw.isBlank()) {
            return Map.of();
        }
        if (ctype.equals("application/json") || raw.trim().startsWith("{")) {
            try {
                Map<String, Object> map = objectMapper.readValue(raw, MAP_TYPE);
                LinkedHashMap<String, String> out = new LinkedHashMap<>();
                map.forEach((key, value) -> out.put(key, String.valueOf(value)));
                return out;
            } catch (Exception ex) {
                return Map.of();
            }
        }
        return queryParams(raw);
    }

    public Map<String, String> queryParams(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            String key = urlDecode(parts[0]);
            String value = parts.length > 1 ? urlDecode(parts[1]) : "";
            out.put(key, value);
        }
        return out;
    }

    public String redirect(String uri, Map<String, String> params) {
        URI parsed = URI.create(uri);
        String existing = parsed.getRawQuery();
        StringBuilder query = new StringBuilder(existing == null || existing.isBlank() ? "" : existing + "&");
        boolean first = query.length() == 0;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            if (!first) {
                query.append('&');
            }
            first = false;
            query.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
        }
        return URI.create(parsed.getScheme() + ":" + (parsed.getRawSchemeSpecificPart().startsWith("//") ? "" : "")
                + parsed.getRawSchemeSpecificPart().split("\\?", 2)[0]
                + (query.length() == 0 ? "" : "?" + query)).toString();
    }

    public long nowEpoch() {
        return Instant.now().getEpochSecond();
    }

    public long longAt(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ex) {
            return 0;
        }
    }

    public String escapeHtml(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public LinkedHashMap<String, String> orderedStringMap(String... values) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            map.put(values[i], values[i + 1]);
        }
        return map;
    }

    public LinkedHashMap<String, Object> orderedMap(Object... values) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }

    private String urlDecode(String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
