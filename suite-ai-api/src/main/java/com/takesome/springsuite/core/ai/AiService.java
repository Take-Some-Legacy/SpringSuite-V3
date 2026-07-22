package com.takesome.springsuite.core.ai;

import java.util.List;

public interface AiService {
    AiChatResponse chat(AiChatRequest request);

    AiCredentialStatus status(String providerId);

    List<AiProviderDescriptor> providers();

    AiProviderDescriptor defaultProvider();
}
