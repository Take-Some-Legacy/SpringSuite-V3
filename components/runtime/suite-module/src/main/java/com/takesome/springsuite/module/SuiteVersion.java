package com.takesome.springsuite.module;

import java.util.ArrayList;
import java.util.List;

final class SuiteVersion implements Comparable<SuiteVersion> {
    private final List<Integer> parts;
    private final String raw;

    private SuiteVersion(String raw, List<Integer> parts) {
        this.raw = raw == null ? "" : raw;
        this.parts = List.copyOf(parts);
    }

    static SuiteVersion parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        String numeric = value.split("[-+ ]", 2)[0];
        ArrayList<Integer> parts = new ArrayList<>();
        for (String part : numeric.split("\\.")) {
            if (part.isBlank()) {
                parts.add(0);
                continue;
            }
            String digits = part.replaceAll("[^0-9]", "");
            parts.add(digits.isBlank() ? 0 : Integer.parseInt(digits));
        }
        while (parts.size() < 3) {
            parts.add(0);
        }
        return new SuiteVersion(value, parts);
    }

    @Override
    public int compareTo(SuiteVersion other) {
        int max = Math.max(parts.size(), other.parts.size());
        for (int i = 0; i < max; i++) {
            int left = i < parts.size() ? parts.get(i) : 0;
            int right = i < other.parts.size() ? other.parts.get(i) : 0;
            if (left != right) {
                return Integer.compare(left, right);
            }
        }
        return 0;
    }

    boolean satisfies(String operator, String requiredVersion) {
        if (requiredVersion == null || requiredVersion.isBlank()) {
            return true;
        }
        int cmp = compareTo(parse(requiredVersion));
        return switch (operator == null || operator.isBlank() ? ">=" : operator) {
            case ">=" -> cmp >= 0;
            case "<=" -> cmp <= 0;
            case ">" -> cmp > 0;
            case "<" -> cmp < 0;
            case "==" -> cmp == 0;
            case "!=" -> cmp != 0;
            default -> cmp >= 0;
        };
    }

    @Override
    public String toString() {
        return raw;
    }
}
