package com.takesome.springsuite.core.ai;

import java.util.Map;

public record AiGenerationOptions(
        Integer maxTokens,
        Double temperature,
        Double topP,
        Boolean stream,
        String reasoningEffort,
        Boolean thinking,
        Boolean store,
        Map<String, Object> vendorOptions
) {
    public AiGenerationOptions {
        reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.trim();
        vendorOptions = vendorOptions == null ? Map.of() : Map.copyOf(vendorOptions);
    }

    public static AiGenerationOptions defaults() {
        return new AiGenerationOptions(null, null, null, false, "", null, null, Map.of());
    }
}
