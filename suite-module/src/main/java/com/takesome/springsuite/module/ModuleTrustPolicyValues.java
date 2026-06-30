package com.takesome.springsuite.module;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

final class ModuleTrustPolicyValues {
    private ModuleTrustPolicyValues() {
    }

    static Set<String> normalizeHashes(Set<String> hashes) {
        return hashes.stream()
                .map(ModuleTrustPolicyValues::normalizeHash)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static String normalizeHash(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-f0-9]", "");
    }

    static Set<String> normalizePublishers(Set<String> publishers) {
        return publishers.stream()
                .map(ModuleTrustPolicyValues::normalizePublisher)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static String normalizePublisher(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    static void addCsv(Set<String> target, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                target.add(part.trim());
            }
        }
    }

    static void addCsv(List<String> target, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                target.add(part.trim());
            }
        }
    }

    static SuiteModuleTrustMode readTrustMode(String primary, String secondary, SuiteModuleTrustMode fallback) {
        String raw = ModuleBootstrapPaths.firstNonBlank(primary, secondary);
        if (raw.isBlank()) {
            return fallback;
        }
        try {
            return SuiteModuleTrustMode.valueOf(raw.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    static boolean readBoolean(String primary, String secondary, boolean fallback) {
        String raw = ModuleBootstrapPaths.firstNonBlank(primary, secondary);
        if (raw.isBlank()) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "1", "on" -> true;
            case "false", "no", "0", "off" -> false;
            default -> fallback;
        };
    }
}
