package com.takesome.springsuite.core.ai;

import java.util.List;
import java.util.Map;

public record AiChatResponse(
        boolean ok,
        String providerId,
        String model,
        String responseId,
        String outputText,
        List<AiToolCall> toolCalls,
        AiUsage usage,
        String errorCode,
        String errorMessage,
        Map<String, Object> metadata
) {
    public AiChatResponse {
        providerId = providerId == null ? "" : providerId.trim();
        model = model == null ? "" : model.trim();
        responseId = responseId == null ? "" : responseId.trim();
        outputText = outputText == null ? "" : outputText;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? AiUsage.empty() : usage;
        errorCode = errorCode == null ? "" : errorCode.trim();
        errorMessage = errorMessage == null ? "" : errorMessage;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static AiChatResponse failed(String providerId, String model, String code, String message) {
        return new AiChatResponse(false, providerId, model, "", "", List.of(), AiUsage.empty(), code, message, Map.of());
    }
}
