package com.takesome.springsuite.core.ai;

import java.util.List;
import java.util.Map;

public record AiChatRequest(
        String providerId,
        String model,
        List<AiMessage> messages,
        AiGenerationOptions options,
        List<AiToolDefinition> tools,
        Map<String, Object> metadata
) {
    public AiChatRequest {
        providerId = providerId == null ? "" : providerId.trim();
        model = model == null ? "" : model.trim();
        messages = messages == null ? List.of() : List.copyOf(messages);
        options = options == null ? AiGenerationOptions.defaults() : options;
        tools = tools == null ? List.of() : List.copyOf(tools);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static AiChatRequest prompt(String providerId, String model, String prompt) {
        return new AiChatRequest(providerId, model, List.of(AiMessage.user(prompt)), AiGenerationOptions.defaults(), List.of(), Map.of());
    }
}
