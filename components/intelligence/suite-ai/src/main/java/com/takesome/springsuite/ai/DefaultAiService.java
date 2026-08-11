package com.takesome.springsuite.ai;

import com.takesome.springsuite.core.ai.AiChatRequest;
import com.takesome.springsuite.core.ai.AiChatResponse;
import com.takesome.springsuite.core.ai.AiCredentialStatus;
import com.takesome.springsuite.core.ai.AiProvider;
import com.takesome.springsuite.core.ai.AiProviderDescriptor;
import com.takesome.springsuite.core.ai.AiService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DefaultAiService implements AiService {
    private final AiProviderRegistry registry;
    private final AiProperties properties;
    private final AiAuditService audit;

    public DefaultAiService(AiProviderRegistry registry, AiProperties properties, AiAuditService audit) {
        this.registry = registry;
        this.properties = properties;
        this.audit = audit;
    }

    @Override
    public AiChatResponse chat(AiChatRequest request) {
        if (!properties.isEnabled()) {
            return AiChatResponse.failed("", request == null ? "" : request.model(), "ai_disabled", "suite.ai.enabled=false");
        }
        String providerId = request == null || request.providerId().isBlank() ? properties.getDefaultProvider() : request.providerId();
        long started = System.nanoTime();
        try {
            AiProvider provider = registry.require(providerId);
            audit.info("AI chat routed", Map.of(
                    "provider", providerId,
                    "model", request == null ? "" : request.model(),
                    "messages", request == null ? 0 : request.messages().size()
            ));
            AiChatResponse response = provider.chat(request);
            audit.info("AI chat completed", Map.of(
                    "provider", providerId,
                    "ok", response.ok(),
                    "model", response.model(),
                    "durationMs", (System.nanoTime() - started) / 1_000_000L,
                    "outputChars", response.outputText().length(),
                    "errorCode", response.errorCode()
            ));
            return response;
        } catch (RuntimeException ex) {
            audit.error("AI chat failed", Map.of(
                    "provider", providerId,
                    "durationMs", (System.nanoTime() - started) / 1_000_000L,
                    "error", safeMessage(ex)
            ));
            return AiChatResponse.failed(providerId, request == null ? "" : request.model(), "ai_exception", safeMessage(ex));
        }
    }

    @Override
    public AiCredentialStatus status(String providerId) {
        String resolved = providerId == null || providerId.isBlank() ? properties.getDefaultProvider() : providerId;
        return registry.require(resolved).status();
    }

    @Override
    public List<AiProviderDescriptor> providers() {
        return registry.providers().stream().map(AiProvider::descriptor).toList();
    }

    @Override
    public AiProviderDescriptor defaultProvider() {
        return registry.require(properties.getDefaultProvider()).descriptor();
    }

    private String safeMessage(Throwable ex) {
        String message = ex == null ? "" : ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
