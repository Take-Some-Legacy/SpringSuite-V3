package com.takesome.springsuite.logging;

import java.time.Instant;
import java.util.Map;

public record OperatorLogEntry(
        String id,
        Instant timestamp,
        OperatorLogLevel level,
        String source,
        String message,
        Map<String, Object> metadata
) {
}
