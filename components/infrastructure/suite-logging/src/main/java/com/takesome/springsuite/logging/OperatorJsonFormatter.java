package com.takesome.springsuite.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OperatorJsonFormatter {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectWriter PRETTY = MAPPER.writerWithDefaultPrettyPrinter();
    private static final ObjectWriter COMPACT = MAPPER.writer();

    private OperatorJsonFormatter() {
    }

    public static String pretty(Object value) {
        return write(PRETTY, value);
    }

    public static String compact(Object value) {
        return write(COMPACT, value);
    }

    public static boolean looksLikeJson(String value) {
        if (value == null) {
            return false;
        }
        String text = value.stripLeading();
        return text.startsWith("{") || text.startsWith("[");
    }

    public static Object parsePlain(String value) {
        if (!looksLikeJson(value)) {
            return value;
        }
        try {
            return toPlainObject(MAPPER.readTree(value));
        } catch (JsonProcessingException ignored) {
            return value;
        }
    }

    public static Object toPlainObject(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                out.put(field.getKey(), toPlainObject(field.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayList<Object> out = new ArrayList<>();
            node.forEach(item -> out.add(toPlainObject(item)));
            return out;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isBigInteger()) {
            return node.bigIntegerValue();
        }
        if (node.isBigDecimal()) {
            return node.decimalValue();
        }
        if (node.isIntegralNumber()) {
            return node.canConvertToLong() ? node.asLong() : new BigInteger(node.asText());
        }
        if (node.isFloatingPointNumber()) {
            return node.isBigDecimal() ? node.decimalValue() : node.asDouble();
        }
        if (node.isNumber()) {
            try {
                return new BigDecimal(node.asText());
            } catch (NumberFormatException ignored) {
                return node.asText();
            }
        }
        return node.asText();
    }

    private static String write(ObjectWriter writer, Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return "";
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return "[]";
        }
        try {
            return writer.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }
}
