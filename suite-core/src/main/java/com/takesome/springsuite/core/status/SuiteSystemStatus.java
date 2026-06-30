package com.takesome.springsuite.core.status;

import java.time.Instant;
import java.util.Map;

public record SuiteSystemStatus(
        String application,
        SuiteComponentStatus status,
        Instant startedAt,
        Map<String, Object> components
) {
}
