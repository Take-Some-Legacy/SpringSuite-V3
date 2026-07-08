package com.takesome.springsuite.core.ai;

public interface AiProvider {
    AiProviderDescriptor descriptor();

    AiCredentialStatus status();

    AiChatResponse chat(AiChatRequest request);

    default boolean supports(AiCapability capability) {
        return descriptor().capabilities().contains(capability);
    }
}
