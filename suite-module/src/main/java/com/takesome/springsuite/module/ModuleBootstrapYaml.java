package com.takesome.springsuite.module;

import java.util.ArrayList;
import java.util.List;

final class ModuleBootstrapYaml {
    private ModuleBootstrapYaml() {
    }

    static String findScalar(String yaml, String dottedKey) {
        String[] parts = dottedKey.split("\\.");
        int expectedIndent = 0;
        for (String rawLine : yaml.split("\\R")) {
            String line = stripComment(rawLine);
            if (line.isBlank()) {
                continue;
            }
            int indent = countIndent(line);
            String trimmed = line.trim();
            if (!trimmed.contains(":")) {
                continue;
            }
            String key = trimmed.substring(0, trimmed.indexOf(':')).trim();
            String value = trimmed.substring(trimmed.indexOf(':') + 1).trim();
            if (expectedIndent < parts.length - 1 && indent == expectedIndent * 2 && key.equals(parts[expectedIndent]) && value.isBlank()) {
                expectedIndent++;
                continue;
            }
            if (expectedIndent == parts.length - 1 && indent == expectedIndent * 2 && key.equals(parts[expectedIndent])) {
                return stripQuotes(value);
            }
        }
        return "";
    }

    static List<String> findList(String yaml, String dottedKey) {
        String[] parts = dottedKey.split("\\.");
        int expectedIndent = 0;
        int listIndent = -1;
        ArrayList<String> values = new ArrayList<>();
        for (String rawLine : yaml.split("\\R")) {
            String line = stripComment(rawLine);
            if (line.isBlank()) {
                continue;
            }
            int indent = countIndent(line);
            String trimmed = line.trim();
            if (listIndent >= 0) {
                if (indent < listIndent) {
                    break;
                }
                if (indent == listIndent && trimmed.startsWith("-")) {
                    values.add(stripQuotes(trimmed.substring(1).trim()));
                    continue;
                }
            }
            if (!trimmed.contains(":")) {
                continue;
            }
            String key = trimmed.substring(0, trimmed.indexOf(':')).trim();
            String value = trimmed.substring(trimmed.indexOf(':') + 1).trim();
            if (expectedIndent < parts.length - 1 && indent == expectedIndent * 2 && key.equals(parts[expectedIndent]) && value.isBlank()) {
                expectedIndent++;
                continue;
            }
            if (expectedIndent == parts.length - 1 && indent == expectedIndent * 2 && key.equals(parts[expectedIndent])) {
                if (!value.isBlank()) {
                    ModuleTrustPolicyValues.addCsv(values, stripQuotes(value));
                }
                listIndent = indent + 2;
            }
        }
        return values;
    }

    private static String stripComment(String line) {
        int index = line.indexOf('#');
        return index >= 0 ? line.substring(0, index) : line;
    }

    private static int countIndent(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static String stripQuotes(String value) {
        String v = value == null ? "" : value.trim();
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}
