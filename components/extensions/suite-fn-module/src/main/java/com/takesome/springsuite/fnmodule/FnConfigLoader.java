package com.takesome.springsuite.fnmodule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class FnConfigLoader {
    private FnConfigLoader() {
    }

    public static FnConfig load() {
        Path path = configPath();
        if (!Files.isRegularFile(path)) {
            return FnConfig.fallback();
        }
        try {
            return parse(Files.readAllLines(path));
        } catch (IOException ex) {
            return FnConfig.fallback();
        }
    }

    public static Path configPath() {
        String configDir = System.getProperty("suite.config.dir", "config");
        return Paths.get(configDir).resolve("suite-fn.yml").toAbsolutePath().normalize();
    }

    private static FnConfig parse(List<String> lines) {
        boolean enabled = true;
        int buttonCount = 12;
        String namespace = "fn";
        String dispatchMode = "explicit-operator-action";
        String defaultDestination = "active-chat";
        ArrayList<FnBinding> buttons = new ArrayList<>();

        Map<String, String> current = null;
        Map<String, String> currentArgs = null;
        boolean inButtons = false;
        boolean inArgs = false;

        for (String raw : lines) {
            String noComment = stripComment(raw);
            if (noComment.trim().isEmpty()) {
                continue;
            }
            String trimmed = noComment.trim();
            int indent = countIndent(noComment);

            if (trimmed.equals("buttons:")) {
                inButtons = true;
                inArgs = false;
                continue;
            }
            if (!inButtons && trimmed.contains(":")) {
                String[] kv = splitKeyValue(trimmed);
                String key = normalizeKey(kv[0]);
                String value = unquote(kv[1]);
                switch (key) {
                    case "enabled" -> enabled = Boolean.parseBoolean(value);
                    case "button-count" -> buttonCount = parseInt(value, buttonCount);
                    case "namespace" -> namespace = value;
                    case "dispatch-mode" -> dispatchMode = value;
                    case "default-destination" -> defaultDestination = value;
                    default -> {
                    }
                }
                continue;
            }
            if (inButtons && trimmed.startsWith("- ")) {
                if (current != null) {
                    buttons.add(toBinding(current, currentArgs, defaultDestination));
                }
                current = new LinkedHashMap<>();
                currentArgs = new LinkedHashMap<>();
                inArgs = false;
                String tail = trimmed.substring(2).trim();
                if (tail.contains(":")) {
                    String[] kv = splitKeyValue(tail);
                    current.put(normalizeKey(kv[0]), unquote(kv[1]));
                }
                continue;
            }
            if (current != null && inButtons) {
                if (trimmed.equals("args:")) {
                    inArgs = true;
                    continue;
                }
                if (trimmed.contains(":")) {
                    String[] kv = splitKeyValue(trimmed);
                    if (inArgs && indent >= 10) {
                        currentArgs.put(normalizeKey(kv[0]), unquote(kv[1]));
                    } else {
                        inArgs = false;
                        current.put(normalizeKey(kv[0]), unquote(kv[1]));
                    }
                }
            }
        }
        if (current != null) {
            buttons.add(toBinding(current, currentArgs, defaultDestination));
        }
        if (buttons.isEmpty()) {
            return FnConfig.fallback();
        }
        return new FnConfig(enabled, buttonCount, namespace, dispatchMode, defaultDestination, buttons);
    }

    private static FnBinding toBinding(Map<String, String> values, Map<String, String> args, String defaultDestination) {
        String code = values.getOrDefault("code", "FN-00").toUpperCase(Locale.ROOT);
        int index = indexOf(code);
        return new FnBinding(
                code,
                index,
                Boolean.parseBoolean(values.getOrDefault("enabled", "false")),
                values.getOrDefault("title", "Unassigned"),
                values.getOrDefault("route", ""),
                values.getOrDefault("risk-tier", values.getOrDefault("riskTier", "none")),
                values.getOrDefault("destination", defaultDestination),
                args == null ? Map.of() : args
        );
    }

    private static int indexOf(String code) {
        String digits = code.replaceAll("\\D+", "");
        return parseInt(digits, 0);
    }

    private static String stripComment(String line) {
        int index = line.indexOf('#');
        return index >= 0 ? line.substring(0, index) : line;
    }

    private static int countIndent(String line) {
        int count = 0;
        while (count < line.length() && Character.isWhitespace(line.charAt(count))) {
            count++;
        }
        return count;
    }

    private static String[] splitKeyValue(String line) {
        int index = line.indexOf(':');
        if (index < 0) {
            return new String[]{line, ""};
        }
        return new String[]{line.substring(0, index).trim(), line.substring(index + 1).trim()};
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.trim();
    }

    private static String unquote(String value) {
        String out = value == null ? "" : value.trim();
        if ((out.startsWith("\"") && out.endsWith("\"")) || (out.startsWith("'") && out.endsWith("'"))) {
            return out.substring(1, out.length() - 1);
        }
        return out;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ex) {
            return fallback;
        }
    }
}
