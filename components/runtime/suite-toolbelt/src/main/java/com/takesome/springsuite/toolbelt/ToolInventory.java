package com.takesome.springsuite.toolbelt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ToolInventory(
        boolean enabled,
        int count,
        int descriptorCount,
        int pathToolCount,
        int availableCount,
        int unavailableCount,
        Instant scannedAt,
        List<String> roots,
        List<String> diagnostics,
        Map<String, Long> bySource,
        Map<String, Long> byKind,
        Map<String, Long> byOwner,
        Map<String, Long> byMaturity,
        Map<String, Long> bySourceType,
        Map<String, Long> byTag,
        List<ToolIndexEntry> index
) {
    public ToolInventory {
        roots = roots == null ? List.of() : List.copyOf(roots);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        bySource = bySource == null ? Map.of() : Map.copyOf(bySource);
        byKind = byKind == null ? Map.of() : Map.copyOf(byKind);
        byOwner = byOwner == null ? Map.of() : Map.copyOf(byOwner);
        byMaturity = byMaturity == null ? Map.of() : Map.copyOf(byMaturity);
        bySourceType = bySourceType == null ? Map.of() : Map.copyOf(bySourceType);
        byTag = byTag == null ? Map.of() : Map.copyOf(byTag);
        index = index == null ? List.of() : List.copyOf(index);
    }
}
