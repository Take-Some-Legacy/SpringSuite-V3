package com.takesome.springsuite.core.ai;

import java.util.Map;

public record AiToolDefinition(
        String name,
        String description,
        Map<String, Object> inputSchema
) {
    public AiToolDefinition {
        name = name == null ? "" : name.trim();
        description = description == null ? "" : description.trim();
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }
}
