package com.takesome.springsuite.core.ai;

import java.util.Map;

public record AiMessage(
        AiRole role,
        String content,
        String name,
        String toolCallId,
        Map<String, Object> metadata
) {
    public AiMessage {
        role = role == null ? AiRole.USER : role;
        content = content == null ? "" : content;
        name = name == null ? "" : name.trim();
        toolCallId = toolCallId == null ? "" : toolCallId.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static AiMessage system(String content) {
        return new AiMessage(AiRole.SYSTEM, content, "", "", Map.of());
    }

    public static AiMessage user(String content) {
        return new AiMessage(AiRole.USER, content, "", "", Map.of());
    }
}
