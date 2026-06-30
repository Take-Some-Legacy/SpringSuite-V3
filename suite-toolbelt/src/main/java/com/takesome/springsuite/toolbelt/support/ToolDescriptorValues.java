package com.takesome.springsuite.toolbelt.support;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ToolDescriptorValues {
    private ToolDescriptorValues() {
    }

    public static String stringAt(Map<String, Object> raw, String path) {
        Object value = objectAt(raw, path);
        return stringFromObject(value);
    }

    public static String stringFromObject(Object value) {
        return value instanceof String string ? string.trim() : "";
    }

    public static boolean boolAt(Map<String, Object> raw, String... paths) {
        for (String path : paths) {
            if (boolFromObject(objectAt(raw, path))) {
                return true;
            }
        }
        return false;
    }

    public static boolean boolFromObject(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            String normalized = string.trim().toLowerCase(Locale.ROOT);
            return normalized.equals("true") || normalized.equals("yes") || normalized.equals("1");
        }
        return false;
    }

    public static Object objectAt(Map<String, Object> raw, String path) {
        Object value = raw;
        for (String part : path.split("\\.")) {
            if (!(value instanceof Map<?, ?> map)) {
                return null;
            }
            value = map.get(part);
        }
        return value;
    }

    public static List<String> listAt(Map<String, Object> raw, String... paths) {
        for (String path : paths) {
            Object value = objectAt(raw, path);
            if (value instanceof List<?> list) {
                return list.stream()
                        .map(String::valueOf)
                        .filter(item -> !item.isBlank())
                        .toList();
            }
            if (value instanceof String string && !string.isBlank()) {
                return List.of(string.trim());
            }
        }
        return List.of();
    }

    public static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public static List<String> safeCommandIds(Map<String, Object> raw) {
        LinkedHashSet<String> safe = new LinkedHashSet<>(listAt(raw, "safeCommandIds", "safe_command_ids", "safeCommands"));
        Object commandsRaw = objectAt(raw, "commands");
        if (commandsRaw instanceof List<?> commands) {
            for (Object commandRaw : commands) {
                if (!(commandRaw instanceof Map<?, ?> command)) {
                    continue;
                }
                String commandId = firstNonBlank(
                        stringFromObject(command.get("id")),
                        stringFromObject(command.get("command_id")),
                        stringFromObject(command.get("commandId"))
                );
                if (commandId.isBlank()) {
                    continue;
                }
                boolean explicitlySafe = boolFromObject(command.get("safe"));
                boolean readOnlySafe = boolFromObject(command.get("readOnlyHint"))
                        && boolFromObject(command.get("idempotentHint"))
                        && !boolFromObject(command.get("destructiveHint"));
                if (explicitlySafe || readOnlySafe) {
                    safe.add(commandId);
                }
            }
        }
        return List.copyOf(safe);
    }

    @SafeVarargs
    public static List<String> mergeLists(List<String>... lists) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (List<String> values : lists) {
            if (values == null) {
                continue;
            }
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    merged.add(value.trim());
                }
            }
        }
        return List.copyOf(merged);
    }
}
