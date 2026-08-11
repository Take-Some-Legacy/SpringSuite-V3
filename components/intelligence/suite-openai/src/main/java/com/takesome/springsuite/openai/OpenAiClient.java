package com.takesome.springsuite.openai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OpenAiClient {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final OpenAiProperties properties;
    private final OpenAiTokenProvider tokenProvider;
    private final ObjectMapper objectMapper;
    private final OpenAiAuditService audit;
    private final HttpClient httpClient;

    public OpenAiClient(OpenAiProperties properties, OpenAiTokenProvider tokenProvider, ObjectMapper objectMapper, OpenAiAuditService audit) {
        this.properties = properties;
        this.tokenProvider = tokenProvider;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.httpClient = HttpClient.newBuilder().connectTimeout(properties.getResponses().getRequestTimeout()).build();
    }

    public OpenAiResponseResult createResponse(OpenAiResponseRequest request) {
        long started = System.nanoTime();
        if (!properties.isEnabled()) {
            audit.warn("OpenAI request rejected: integration disabled", Map.of("endpoint", properties.getResponses().getEndpoint()));
            throw new OpenAiException("suite.openai.enabled=false");
        }
        String input = request == null ? "" : request.input();
        if (input == null || input.isBlank()) {
            audit.warn("OpenAI request rejected: empty input", Map.of("endpoint", properties.getResponses().getEndpoint()));
            throw new OpenAiException("OpenAI response input is empty");
        }

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        String model = firstNonBlank(request.model(), properties.getResponses().getModel());
        payload.put("model", model);
        payload.put("input", input);
        payload.put("store", request.store() == null ? properties.getResponses().isStore() : request.store());
        Integer maxOutputTokens = request.maxOutputTokens() == null ? properties.getResponses().getMaxOutputTokens() : request.maxOutputTokens();
        if (maxOutputTokens != null && maxOutputTokens > 0) {
            payload.put("max_output_tokens", maxOutputTokens);
        }
        boolean temperatureSupported = supportsTemperature(model);
        if (request.temperature() != null && temperatureSupported) {
            payload.put("temperature", request.temperature());
        }
        if (request.metadata() != null && !request.metadata().isEmpty()) {
            payload.put("metadata", request.metadata());
        }

        audit.info("OpenAI response request started", Map.of(
                "model", model,
                "endpoint", properties.getResponses().getEndpoint(),
                "inputChars", input.length(),
                "maxOutputTokens", maxOutputTokens == null ? 0 : maxOutputTokens,
                "store", payload.get("store"),
                "temperatureRequested", request.temperature() != null,
                "temperatureSent", payload.containsKey("temperature")
        ));

        try {
            String json = objectMapper.writeValueAsString(payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder(responseUri())
                    .timeout(properties.getResponses().getRequestTimeout())
                    .header("Content-Type", "application/json")
                    .header("Authorization", tokenProvider.authorizationHeader())
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            String organizationId = tokenProvider.organizationId();
            if (!organizationId.isBlank()) {
                builder.header("OpenAI-Organization", organizationId);
            }
            String projectId = tokenProvider.projectId();
            if (!projectId.isBlank()) {
                builder.header("OpenAI-Project", projectId);
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            String requestId = response.headers().firstValue("x-request-id").orElse("");
            JsonNode root = parse(response.body());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                OpenAiResponseResult result = new OpenAiResponseResult(
                        true,
                        response.statusCode(),
                        requestId,
                        text(root, "id"),
                        text(root, "model"),
                        extractOutputText(root),
                        usage(root),
                        "",
                        ""
                );
                audit.info("OpenAI response request completed", Map.of(
                        "httpStatus", result.httpStatus(),
                        "requestId", result.requestId(),
                        "responseId", result.responseId(),
                        "model", result.model(),
                        "durationMs", durationMs,
                        "outputChars", result.outputText().length(),
                        "usage", result.usage()
                ));
                return result;
            }
            JsonNode error = root == null ? null : root.get("error");
            OpenAiResponseResult result = new OpenAiResponseResult(
                    false,
                    response.statusCode(),
                    requestId,
                    text(root, "id"),
                    text(root, "model"),
                    "",
                    Map.of(),
                    text(error, "code"),
                    firstNonBlank(text(error, "message"), safeBody(response.body()))
            );
            audit.warn("OpenAI response request failed", Map.of(
                    "httpStatus", result.httpStatus(),
                    "requestId", result.requestId(),
                    "errorCode", result.errorCode(),
                    "errorMessage", result.errorMessage(),
                    "model", model,
                    "durationMs", durationMs
            ));
            return result;
        } catch (IOException ex) {
            audit.error("OpenAI response request I/O failure", Map.of("model", model, "error", safeMessage(ex), "durationMs", (System.nanoTime() - started) / 1_000_000L));
            throw new OpenAiException("OpenAI Responses API request failed: " + safeMessage(ex), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            audit.error("OpenAI response request interrupted", Map.of("model", model, "durationMs", (System.nanoTime() - started) / 1_000_000L));
            throw new OpenAiException("OpenAI Responses API request interrupted", ex);
        } catch (RuntimeException ex) {
            audit.error("OpenAI response request runtime failure", Map.of("model", model, "error", safeMessage(ex), "durationMs", (System.nanoTime() - started) / 1_000_000L));
            throw ex;
        }
    }

    static boolean supportsTemperature(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isBlank()) {
            return true;
        }
        return !normalized.startsWith("gpt-5")
                && !normalized.matches("^o[134](?:-|$).*");
    }

    private URI responseUri() {
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        return URI.create(base + properties.getResponses().getEndpoint());
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

    private Map<String, Object> usage(JsonNode root) {
        JsonNode usage = root == null ? null : root.get("usage");
        if (usage == null || usage.isNull()) {
            return Map.of();
        }
        try {
            return objectMapper.convertValue(usage, MAP_TYPE);
        } catch (IllegalArgumentException ex) {
            return Map.of();
        }
    }

    private String extractOutputText(JsonNode root) {
        if (root == null || root.isNull()) {
            return "";
        }
        String direct = text(root, "output_text");
        if (!direct.isBlank()) {
            return direct;
        }
        StringBuilder out = new StringBuilder();
        JsonNode output = root.get("output");
        if (output != null && output.isArray()) {
            for (JsonNode item : output) {
                JsonNode content = item.get("content");
                if (content != null && content.isArray()) {
                    for (JsonNode part : content) {
                        String type = text(part, "type");
                        if ("output_text".equals(type) || "text".equals(type)) {
                            String text = text(part, "text");
                            if (!text.isBlank()) {
                                if (!out.isEmpty()) {
                                    out.append(System.lineSeparator());
                                }
                                out.append(text);
                            }
                        }
                    }
                }
            }
        }
        return out.toString();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
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

    private String safeBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String compact = body.replaceAll("\s+", " ").trim();
        return compact.length() > 1200 ? compact.substring(0, 1200) + "..." : compact;
    }
}
