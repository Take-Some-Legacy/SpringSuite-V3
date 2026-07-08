package com.takesome.springsuite.openai;

import com.takesome.springsuite.core.ai.AiCapability;
import com.takesome.springsuite.core.ai.AiChatRequest;
import com.takesome.springsuite.core.ai.AiChatResponse;
import com.takesome.springsuite.core.ai.AiMessage;
import com.takesome.springsuite.core.ai.AiProvider;
import com.takesome.springsuite.core.ai.AiProviderDescriptor;
import com.takesome.springsuite.core.ai.AiUsage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class OpenAiAiProvider implements AiProvider {
    private final OpenAiProperties properties;
    private final OpenAiTokenProvider tokenProvider;
    private final OpenAiClient client;

    public OpenAiAiProvider(OpenAiProperties properties, OpenAiTokenProvider tokenProvider, OpenAiClient client) {
        this.properties = properties;
        this.tokenProvider = tokenProvider;
        this.client = client;
    }

    @Override
    public AiProviderDescriptor descriptor() {
        return new AiProviderDescriptor(
                "openai",
                "OpenAI",
                "OpenAI",
                "openai-responses",
                properties.getResponses().getModel(),
                Set.of(AiCapability.CHAT, AiCapability.RESPONSES_API),
                properties.isEnabled()
        );
    }

    @Override
    public com.takesome.springsuite.core.ai.AiCredentialStatus status() {
        OpenAiCredentialStatus status = tokenProvider.status();
        return new com.takesome.springsuite.core.ai.AiCredentialStatus(
                "openai",
                status.enabled(),
                status.available(),
                status.credentialKind(),
                status.source(),
                status.fingerprint(),
                status.expiresAt(),
                status.message(),
                Map.of(
                        "mode", status.mode(),
                        "issuedAt", status.issuedAt(),
                        "refreshAt", status.refreshAt(),
                        "scope", status.scope(),
                        "cachePath", status.cachePath(),
                        "cached", status.cached()
                )
        );
    }

    @Override
    public AiChatResponse chat(AiChatRequest request) {
        String model = firstNonBlank(request.model(), properties.getResponses().getModel());
        try {
            OpenAiResponseResult result = client.createResponse(new OpenAiResponseRequest(
                    renderInput(request.messages()),
                    model,
                    request.options().store(),
                    request.options().maxTokens(),
                    request.options().temperature(),
                    request.metadata()
            ));
            return new AiChatResponse(
                    result.ok(),
                    "openai",
                    firstNonBlank(result.model(), model),
                    result.responseId(),
                    result.outputText(),
                    List.of(),
                    usage(result.usage()),
                    result.errorCode(),
                    result.errorMessage(),
                    Map.of(
                            "httpStatus", result.httpStatus(),
                            "requestId", result.requestId(),
                            "usage", result.usage()
                    )
            );
        } catch (RuntimeException ex) {
            return AiChatResponse.failed("openai", model, "openai_provider_exception", safeMessage(ex));
        }
    }

    private String renderInput(List<AiMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (AiMessage message : messages) {
            if (!out.isEmpty()) {
                out.append(System.lineSeparator()).append(System.lineSeparator());
            }
            out.append(message.role().wireName()).append(": ").append(message.content());
        }
        return out.toString();
    }

    private AiUsage usage(Map<String, Object> usage) {
        if (usage == null || usage.isEmpty()) {
            return AiUsage.empty();
        }
        long input = number(usage.get("input_tokens"), number(usage.get("prompt_tokens"), 0));
        long output = number(usage.get("output_tokens"), number(usage.get("completion_tokens"), 0));
        long total = number(usage.get("total_tokens"), input + output);
        return new AiUsage(input, output, total);
    }

    private long number(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String safeMessage(Throwable ex) {
        if (ex == null) {
            return "unknown error";
        }
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
