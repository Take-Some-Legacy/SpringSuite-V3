package com.takesome.springsuite.openai;

import java.util.Map;

public record OpenAiResponseRequest(
        String input,
        String model,
        Boolean store,
        Integer maxOutputTokens,
        Double temperature,
        Map<String, Object> metadata
) {
    public OpenAiResponseRequest {
        input = input == null ? "" : input;
        model = model == null ? "" : model.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
