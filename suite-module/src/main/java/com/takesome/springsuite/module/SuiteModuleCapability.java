package com.takesome.springsuite.module;

import java.util.Map;

public record SuiteModuleCapability(
        String id,
        String kind,
        String description,
        Map<String, Object> metadata
) {
    public SuiteModuleCapability {
        id = id == null || id.isBlank() ? "unknown" : id.trim();
        kind = kind == null || kind.isBlank() ? "generic" : kind.trim();
        description = description == null ? "" : description.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
