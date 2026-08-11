package com.takesome.springsuite.toolbelt.discovery;

import com.takesome.springsuite.toolbelt.ToolDescriptor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ToolDiscoveryResult(
        Map<String, ToolDescriptor> tools,
        List<String> diagnostics,
        List<String> resolvedRoots
) {
    public ToolDiscoveryResult {
        tools = tools == null ? Map.of() : new LinkedHashMap<>(tools);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        resolvedRoots = resolvedRoots == null ? List.of() : List.copyOf(resolvedRoots);
    }
}
