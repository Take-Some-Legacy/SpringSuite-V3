package com.takesome.springsuite.core.mode;

import java.util.Locale;

public final class SuiteOperatorMode {
    public static final String PROPERTY = "suite.operator.mode";
    public static final String SOURCE_PROPERTY = "suite.operator.mode.source";

    private SuiteOperatorMode() {
    }

    public static void promoteFromArgs(String[] args) {
        if (isElevated()) {
            return;
        }
        if (args == null) {
            return;
        }
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            String normalized = arg.trim().toLowerCase(Locale.ROOT);
            if (normalized.equals("--elevated")
                    || normalized.equals("--suite-elevated")
                    || normalized.equals("--operator-elevated")
                    || normalized.equals("--suite.operator.mode=elevated")
                    || normalized.equals("--suite.operator.elevated=true")) {
                System.setProperty(PROPERTY, "elevated");
                System.setProperty(SOURCE_PROPERTY, "command-line");
                return;
            }
        }
    }

    public static boolean isElevated() {
        return isElevatedValue(System.getProperty(PROPERTY))
                || truthy(System.getProperty("suite.operator.elevated"))
                || truthy(System.getProperty("suite.elevated"))
                || truthy(System.getenv("SPRING_SUITE_ELEVATED"))
                || truthy(System.getenv("NOESIS_SUITE_ELEVATED"));
    }

    public static String name() {
        return isElevated() ? "ELEVATED" : "STANDARD";
    }

    public static String source() {
        String explicit = System.getProperty(SOURCE_PROPERTY, "").trim();
        if (!explicit.isBlank()) {
            return explicit;
        }
        if (isElevatedValue(System.getProperty(PROPERTY))
                || truthy(System.getProperty("suite.operator.elevated"))
                || truthy(System.getProperty("suite.elevated"))) {
            return "system-property";
        }
        if (truthy(System.getenv("SPRING_SUITE_ELEVATED")) || truthy(System.getenv("NOESIS_SUITE_ELEVATED"))) {
            return "environment";
        }
        return "default";
    }

    private static boolean isElevatedValue(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.trim().equalsIgnoreCase("elevated") || truthy(value);
    }

    private static boolean truthy(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y", "on", "enabled", "enable", "elevated", "admin" -> true;
            default -> false;
        };
    }
}
