package com.takesome.springsuite.module;

import java.util.Map;

public record SuiteModuleSummary(
        boolean enabled,
        int discoveredCount,
        int activeCount,
        int disabledCount,
        int commandCount,
        int capabilityCount,
        Map<String, String> modules
) {
}
