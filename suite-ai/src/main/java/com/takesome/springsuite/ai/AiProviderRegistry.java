package com.takesome.springsuite.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.takesome.springsuite.core.ai.AiProvider;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AiProviderRegistry {
    private final Map<String, AiProvider> providersById = new LinkedHashMap<>();

    public AiProviderRegistry(List<AiProvider> providers, AiProperties properties, ObjectMapper objectMapper, AiAuditService audit) {
        for (AiProvider provider : providers == null ? List.<AiProvider>of() : providers) {
            register(provider);
        }
        registerConfiguredProviders(properties, objectMapper, audit);
        audit.info("AI provider registry initialized", Map.of(
                "providers", providersById.keySet(),
                "count", providersById.size(),
                "defaultProvider", properties.getDefaultProvider()
        ));
    }

    public List<AiProvider> providers() {
        return providersById.values().stream()
                .sorted(Comparator.comparing(provider -> provider.descriptor().id()))
                .toList();
    }

    public AiProvider require(String providerId) {
        AiProvider provider = providersById.get(providerId == null ? "" : providerId.trim());
        if (provider == null) {
            throw new IllegalArgumentException("Unknown AI provider: " + providerId + ". Available: " + providersById.keySet());
        }
        return provider;
    }

    public boolean hasProvider(String providerId) {
        return providersById.containsKey(providerId == null ? "" : providerId.trim());
    }

    private void register(AiProvider provider) {
        if (provider == null || provider.descriptor().id().isBlank()) {
            return;
        }
        String id = provider.descriptor().id();
        if (providersById.containsKey(id)) {
            throw new IllegalStateException("Duplicate AI provider id: " + id);
        }
        providersById.put(id, provider);
    }

    private void registerConfiguredProviders(AiProperties properties, ObjectMapper objectMapper, AiAuditService audit) {
        properties.getProviders().forEach((id, config) -> {
            if (id == null || id.isBlank()) {
                return;
            }
            if (providersById.containsKey(id)) {
                audit.warn("Configured AI provider skipped because a module provider already exists", Map.of("provider", id));
                return;
            }
            String type = config.getType().toLowerCase(Locale.ROOT);
            if (type.equals("openai-chat-compatible") || type.equals("chat-completions-compatible")) {
                register(new OpenAiCompatibleChatProvider(id, config, objectMapper, audit));
                return;
            }
            audit.warn("Configured AI provider skipped due to unsupported provider type", Map.of("provider", id, "type", config.getType()));
        });
    }
}
