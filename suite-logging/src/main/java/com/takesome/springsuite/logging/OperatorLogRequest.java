package com.takesome.springsuite.logging;

import java.util.Map;

public record OperatorLogRequest(
        OperatorLogLevel level,
        String source,
        String message,
        Map<String, Object> metadata
) {
}
