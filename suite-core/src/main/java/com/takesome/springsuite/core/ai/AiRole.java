package com.takesome.springsuite.core.ai;

import java.util.Locale;

public enum AiRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AiRole from(String value) {
        if (value == null || value.isBlank()) {
            return USER;
        }
        try {
            return AiRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return USER;
        }
    }
}
