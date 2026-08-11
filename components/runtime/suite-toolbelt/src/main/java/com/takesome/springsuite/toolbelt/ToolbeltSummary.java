package com.takesome.springsuite.toolbelt;

import java.time.Instant;
import java.util.Map;

public record ToolbeltSummary(
        boolean enabled,
        int count,
        int availableCount,
        int unavailableCount,
        Instant scannedAt,
        Map<String, Long> bySource,
        Map<String, Long> byKind
) {
}
