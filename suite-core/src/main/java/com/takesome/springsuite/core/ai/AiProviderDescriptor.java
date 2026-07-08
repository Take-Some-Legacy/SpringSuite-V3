package com.takesome.springsuite.core.ai;

import java.util.Set;

public record AiProviderDescriptor(
        String id,
        String name,
        String vendor,
        String type,
        String defaultModel,
        Set<AiCapability> capabilities,
        boolean enabled
) {
    public AiProviderDescriptor {
        id = id == null ? "" : id.trim();
        name = name == null || name.isBlank() ? id : name.trim();
        vendor = vendor == null ? "" : vendor.trim();
        type = type == null ? "" : type.trim();
        defaultModel = defaultModel == null ? "" : defaultModel.trim();
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }
}
