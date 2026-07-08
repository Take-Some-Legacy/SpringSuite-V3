package com.takesome.springsuite.core.ai;

import java.util.Map;

public record AiToolCall(
        String id,
        String name,
        Map<String, Object> arguments
) {
    public AiToolCall {
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name.trim();
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
