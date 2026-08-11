package com.takesome.springsuite.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.takesome.springsuite.core.ai.AiCapability;
import com.takesome.springsuite.core.ai.AiChatRequest;
import com.takesome.springsuite.core.ai.AiChatResponse;
import com.takesome.springsuite.core.ai.AiCredentialStatus;
import com.takesome.springsuite.core.ai.AiGenerationOptions;
import com.takesome.springsuite.core.ai.AiMessage;
import com.takesome.springsuite.core.ai.AiProvider;
import com.takesome.springsuite.core.ai.AiProviderDescriptor;
import com.takesome.springsuite.core.ai.AiToolCall;
import com.takesome.springsuite.core.ai.AiToolDefinition;
import com.takesome.springsuite.core.ai.AiUsage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class OpenAiCompatibleChatProvider implements AiProvider {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final String id;
    private final AiProperties.Provider config;
    private final ObjectMapper objectMapper;
    private final AiAuditService audit;
    private final HttpClient httpClient;
    private volatile ProbeSnapshot probeSnapshot;

    public OpenAiCompatibleChatProvider(String id, AiProperties.Provider config, ObjectMapper objectMapper, AiAuditService audit) {
        this.id = id == null ? "" : id.trim();
        this.config = config == null ? new AiProperties.Provider() : config;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.config.getRequestTimeout()).build();
    }

    @Override
    public AiProviderDescriptor descriptor() {
        return new AiProviderDescriptor(
                id,
                config.getName().isBlank() ? id : config.getName(),
                config.getVendor(),
                config.getType(),
                config.getDefaultModel(),
                capabilities(),
                config.isEnabled()
        );
    }

    @Override
    public AiCredentialStatus status() {
        if (!config.isEnabled()) {
            return AiCredentialStatus.unavailable(id, false, "AI provider disabled");
        }
        if (config.getBaseUrl().isBlank()) {
            return AiCredentialStatus.unavailable(id, true, "AI provider base-url is not configured");
        }
        String key = apiKey();
        if (config.isRequiresAuth() && key.isBlank()) {
            return AiCredentialStatus.unavailable(id, true, "API key is not configured; set " + config.getApiKeyEnv());
        }
        String source = keySource(key);
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("baseUrl", config.getBaseUrl());
        metadata.put("chatEndpoint", config.getChatEndpoint());
        metadata.put("defaultModel", config.getDefaultModel());

        if (config.getProbe().isEnabled()) {
            ProbeSnapshot probe = probe(key);
            metadata.putAll(probe.metadata());
            return new AiCredentialStatus(
                    id,
                    true,
                    probe.available(),
                    config.isRequiresAuth() ? "api_key" : "none",
                    source,
                    key.isBlank() ? "" : fingerprint(key),
                    "",
                    probe.message(),
                    metadata
            );
        }
        return new AiCredentialStatus(
                id,
                true,
                true,
                config.isRequiresAuth() ? "api_key" : "none",
                source,
                key.isBlank() ? "" : fingerprint(key),
                "",
                config.isRequiresAuth() ? "API key available" : "provider does not require authorization",
                metadata
        );
    }

    @Override
    public AiChatResponse chat(AiChatRequest request) {
        long started = System.nanoTime();
        AiCredentialStatus status = status();
        String model = resolveModel(request);
        if (!status.available()) {
            return AiChatResponse.failed(id, model, "ai_provider_unavailable", status.message());
        }
        try {
            LinkedHashMap<String, Object> payload = payload(request, model);
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri())
                    .timeout(config.getRequestTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            String key = apiKey();
            if (!key.isBlank()) {
                builder.header("Authorization", "Bearer " + key);
            }

            audit.info("AI OpenAI-compatible chat request started", Map.of(
                    "provider", id,
                    "model", model,
                    "endpoint", config.getChatEndpoint(),
                    "messages", request.messages().size(),
                    "tools", request.tools().size()
            ));
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            String requestId = response.headers().firstValue("x-request-id")
                    .or(() -> response.headers().firstValue("x-zai-request-id"))
                    .orElse("");
            JsonNode root = parse(response.body());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                AiChatResponse result = successfulResponse(root, response.statusCode(), requestId, durationMs, model);
                audit.info("AI OpenAI-compatible chat request completed", Map.of(
                        "provider", id,
                        "model", result.model(),
                        "responseId", result.responseId(),
                        "requestId", requestId,
                        "durationMs", durationMs,
                        "outputChars", result.outputText().length(),
                        "usage", result.usage()
                ));
                return result;
            }
            AiChatResponse result = failedResponse(root, response.statusCode(), requestId, model);
            audit.warn("AI OpenAI-compatible chat request failed", Map.of(
                    "provider", id,
                    "model", model,
                    "httpStatus", response.statusCode(),
                    "requestId", requestId,
                    "durationMs", durationMs,
                    "errorCode", result.errorCode(),
                    "errorMessage", result.errorMessage()
            ));
            return result;
        } catch (IOException ex) {
            audit.error("AI OpenAI-compatible chat I/O failure", Map.of("provider", id, "model", model, "error", safeMessage(ex)));
            return AiChatResponse.failed(id, model, "ai_io_error", safeMessage(ex));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            audit.error("AI OpenAI-compatible chat interrupted", Map.of("provider", id, "model", model));
            return AiChatResponse.failed(id, model, "ai_interrupted", "AI request interrupted");
        } catch (RuntimeException ex) {
            audit.error("AI OpenAI-compatible chat runtime failure", Map.of("provider", id, "model", model, "error", safeMessage(ex)));
            return AiChatResponse.failed(id, model, "ai_runtime_error", safeMessage(ex));
        }
    }

    private LinkedHashMap<String, Object> payload(AiChatRequest request, String model) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages(request.messages()));

        AiGenerationOptions options = request.options();
        Integer maxTokens = options.maxTokens() == null ? config.getDefaultMaxTokens() : options.maxTokens();
        if (maxTokens != null && maxTokens > 0) {
            payload.put("max_tokens", maxTokens);
        }
        Double temperature = options.temperature() == null ? config.getDefaultTemperature() : options.temperature();
        if (temperature != null) {
            payload.put("temperature", temperature);
        }
        Double topP = options.topP() == null ? config.getDefaultTopP() : options.topP();
        if (topP != null) {
            payload.put("top_p", topP);
        }
        if (options.stream() != null) {
            payload.put("stream", options.stream());
        }
        if (!request.tools().isEmpty()) {
            payload.put("tools", tools(request.tools()));
        }
        boolean thinkingEnabled = Boolean.TRUE.equals(options.thinking()) || config.getThinking().isEnabled();
        if (thinkingEnabled) {
            payload.put("thinking", Map.of("type", config.getThinking().getType()));
        }
        String reasoningEffort = firstNonBlank(options.reasoningEffort(), config.getThinking().getReasoningEffort());
        if (!reasoningEffort.isBlank()) {
            payload.put("reasoning_effort", reasoningEffort);
        }
        payload.putAll(config.getVendorOptions());
        payload.putAll(options.vendorOptions());
        return payload;
    }

    private List<Map<String, Object>> messages(List<AiMessage> messages) {
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (AiMessage message : messages) {
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.role().wireName());
            item.put("content", message.content());
            if (!message.name().isBlank()) {
                item.put("name", message.name());
            }
            if (!message.toolCallId().isBlank()) {
                item.put("tool_call_id", message.toolCallId());
            }
            out.add(item);
        }
        return out;
    }

    private List<Map<String, Object>> tools(List<AiToolDefinition> tools) {
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (AiToolDefinition tool : tools) {
            LinkedHashMap<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            fn.put("parameters", tool.inputSchema().isEmpty() ? Map.of("type", "object", "properties", Map.of()) : tool.inputSchema());
            out.add(Map.of("type", "function", "function", fn));
        }
        return out;
    }

    private AiChatResponse successfulResponse(JsonNode root, int httpStatus, String requestId, long durationMs, String requestedModel) {
        JsonNode choice = root == null ? null : root.path("choices").path(0);
        JsonNode message = choice == null ? null : choice.path("message");
        String output = text(message, "content");
        String model = firstNonBlank(text(root, "model"), requestedModel);
        String responseId = text(root, "id");
        return new AiChatResponse(
                true,
                id,
                model,
                responseId,
                output,
                toolCalls(message),
                usage(root == null ? null : root.path("usage")),
                "",
                "",
                Map.of(
                        "httpStatus", httpStatus,
                        "requestId", requestId,
                        "durationMs", durationMs,
                        "finishReason", text(choice, "finish_reason")
                )
        );
    }

    private AiChatResponse failedResponse(JsonNode root, int httpStatus, String requestId, String model) {
        JsonNode error = root == null ? null : root.path("error");
        return new AiChatResponse(
                false,
                id,
                model,
                text(root, "id"),
                "",
                List.of(),
                AiUsage.empty(),
                firstNonBlank(text(error, "code"), "http_" + httpStatus),
                firstNonBlank(text(error, "message"), root == null ? "HTTP " + httpStatus : root.toString()),
                Map.of("httpStatus", httpStatus, "requestId", requestId)
        );
    }

    private List<AiToolCall> toolCalls(JsonNode message) {
        JsonNode calls = message == null ? null : message.get("tool_calls");
        if (calls == null || !calls.isArray()) {
            return List.of();
        }
        ArrayList<AiToolCall> out = new ArrayList<>();
        for (JsonNode call : calls) {
            JsonNode function = call.path("function");
            out.add(new AiToolCall(text(call, "id"), text(function, "name"), parseArguments(text(function, "arguments"))));
        }
        return out;
    }

    private Map<String, Object> parseArguments(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception ex) {
            return Map.of("raw", value);
        }
    }

    private AiUsage usage(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) {
            return AiUsage.empty();
        }
        long input = usage.path("prompt_tokens").asLong(usage.path("input_tokens").asLong(0));
        long output = usage.path("completion_tokens").asLong(usage.path("output_tokens").asLong(0));
        long total = usage.path("total_tokens").asLong(input + output);
        return new AiUsage(input, output, total);
    }

    private URI uri() {
        return uri(config.getChatEndpoint());
    }

    private URI uri(String endpoint) {
        String path = endpoint == null || endpoint.isBlank() ? "/" : endpoint.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return URI.create(config.getBaseUrl().replaceAll("/+$", "") + path);
    }

    private ProbeSnapshot probe(String key) {
        long now = System.nanoTime();
        ProbeSnapshot cached = probeSnapshot;
        if (cached != null && now < cached.expiresAtNanos()) {
            return cached;
        }
        synchronized (this) {
            cached = probeSnapshot;
            now = System.nanoTime();
            if (cached != null && now < cached.expiresAtNanos()) {
                return cached;
            }
            ProbeSnapshot refreshed = executeProbe(key, now);
            probeSnapshot = refreshed;
            return refreshed;
        }
    }

    private ProbeSnapshot executeProbe(String key, long now) {
        AiProperties.Probe probe = config.getProbe();
        URI endpoint = uri(probe.getEndpoint());
        long expiresAt = now + probe.getCacheTtl().toNanos();
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("probeEndpoint", endpoint.toString());
        metadata.put("probeCheckedAt", Instant.now().toString());
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(probe.getTimeout())
                    .header("Accept", "application/json")
                    .GET();
            if (key != null && !key.isBlank()) {
                builder.header("Authorization", "Bearer " + key);
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            metadata.put("probeHttpStatus", response.statusCode());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ProbeSnapshot(false, "Provider probe returned HTTP " + response.statusCode(), metadata, expiresAt);
            }

            List<String> models = modelIds(parse(response.body()));
            metadata.put("availableModels", models);
            metadata.put("availableModelCount", models.size());
            if (probe.isRequireDefaultModel() && !config.getDefaultModel().isBlank() && !containsModel(models, config.getDefaultModel())) {
                return new ProbeSnapshot(
                        false,
                        "Provider is reachable, but default model '" + config.getDefaultModel() + "' is not installed",
                        metadata,
                        expiresAt
                );
            }
            return new ProbeSnapshot(true, "Provider reachable; " + models.size() + " model(s) available", metadata, expiresAt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ProbeSnapshot(false, "Provider probe interrupted", metadata, expiresAt);
        } catch (IOException | RuntimeException ex) {
            metadata.put("probeError", safeMessage(ex));
            return new ProbeSnapshot(false, "Provider probe failed: " + safeMessage(ex), metadata, expiresAt);
        }
    }

    private List<String> modelIds(JsonNode root) {
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isArray()) {
            return List.of();
        }
        ArrayList<String> models = new ArrayList<>();
        for (JsonNode item : data) {
            String model = text(item, "id");
            if (!model.isBlank()) {
                models.add(model);
            }
        }
        return List.copyOf(models);
    }

    private boolean containsModel(List<String> models, String configuredModel) {
        String expected = normalizeModel(configuredModel);
        for (String model : models) {
            if (normalizeModel(model).equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeModel(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith(":latest") ? normalized.substring(0, normalized.length() - ":latest".length()) : normalized;
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            return null;
        }
    }

    private String resolveModel(AiChatRequest request) {
        return firstNonBlank(request == null ? "" : request.model(), config.getDefaultModel());
    }

    private String apiKey() {
        String envName = config.getApiKeyEnv();
        if (envName != null && !envName.isBlank()) {
            String value = System.getenv(envName.trim());
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return config.getApiKey();
    }

    private String keySource(String key) {
        if (key == null || key.isBlank()) {
            return config.isRequiresAuth() ? "missing" : "none";
        }
        return config.getApiKeyEnv().isBlank() ? "config" : "env:" + config.getApiKeyEnv();
    }

    private Set<AiCapability> capabilities() {
        EnumSet<AiCapability> set = EnumSet.of(AiCapability.CHAT, AiCapability.CHAT_COMPLETIONS_API);
        for (String value : config.getCapabilities()) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                set.add(AiCapability.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Unknown capability is ignored to keep config forward-compatible.
            }
        }
        if (config.getThinking().isEnabled()) {
            set.add(AiCapability.THINKING_MODE);
        }
        if (!config.getThinking().getReasoningEffort().isBlank()) {
            set.add(AiCapability.REASONING_EFFORT);
        }
        return set;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.isMissingNode() ? "" : value.asText("");
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

    private String fingerprint(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((token == null ? "" : token).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception ex) {
            return "sha256:unavailable";
        }
    }

    private record ProbeSnapshot(boolean available, String message, Map<String, Object> metadata, long expiresAtNanos) {
        private ProbeSnapshot {
            message = message == null ? "" : message;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
