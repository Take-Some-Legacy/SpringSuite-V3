package com.takesome.springsuite.toolbelt.inventory;

import com.takesome.springsuite.toolbelt.ToolDescriptor;
import com.takesome.springsuite.toolbelt.ToolInventory;
import com.takesome.springsuite.toolbelt.ToolbeltSummary;
import com.takesome.springsuite.toolbelt.search.ToolSearchEngine;
import com.takesome.springsuite.toolbelt.support.ToolDescriptorValues;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ToolbeltInventoryFactory {
    private final ToolSearchEngine searchEngine;

    public ToolbeltInventoryFactory(ToolSearchEngine searchEngine) {
        this.searchEngine = searchEngine;
    }

    public ToolbeltSummary summary(boolean enabled, Instant scannedAt, List<ToolDescriptor> snapshot) {
        long available = snapshot.stream().filter(ToolDescriptor::available).count();
        return new ToolbeltSummary(
                enabled,
                snapshot.size(),
                (int) available,
                snapshot.size() - (int) available,
                scannedAt,
                groupBy(snapshot, ToolDescriptor::source),
                groupBy(snapshot, ToolDescriptor::kind)
        );
    }

    public ToolInventory inventory(
            boolean enabled,
            Instant scannedAt,
            List<ToolDescriptor> snapshot,
            List<String> roots,
            List<String> diagnostics
    ) {
        int descriptorCount = (int) snapshot.stream().filter(tool -> "descriptor".equals(tool.source())).count();
        int pathToolCount = (int) snapshot.stream().filter(tool -> "path".equals(tool.source())).count();
        int availableCount = (int) snapshot.stream().filter(ToolDescriptor::available).count();
        return new ToolInventory(
                enabled,
                snapshot.size(),
                descriptorCount,
                pathToolCount,
                availableCount,
                snapshot.size() - availableCount,
                scannedAt,
                roots,
                diagnostics,
                groupBy(snapshot, ToolDescriptor::source),
                groupBy(snapshot, ToolDescriptor::kind),
                groupBy(snapshot, ToolDescriptor::owner),
                groupBy(snapshot, ToolDescriptor::maturity),
                groupBy(snapshot, ToolDescriptor::sourceType),
                groupTags(snapshot),
                searchEngine.index(snapshot)
        );
    }

    private Map<String, Long> groupBy(List<ToolDescriptor> snapshot, Function<ToolDescriptor, String> keyFunction) {
        return snapshot.stream()
                .map(keyFunction)
                .map(value -> value == null || value.isBlank() ? "unknown" : value)
                .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()));
    }

    private Map<String, Long> groupTags(List<ToolDescriptor> snapshot) {
        return snapshot.stream()
                .flatMap(tool -> ToolDescriptorValues.mergeLists(tool.tags(), tool.capabilities(), tool.formats(), tool.contentKinds()).stream())
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()));
    }
}
