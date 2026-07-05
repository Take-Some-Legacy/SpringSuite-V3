package com.takesome.springsuite.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class OpenAiLocalCredentialStore {
    private final OpenAiProperties properties;
    private final ObjectMapper objectMapper;
    private final OpenAiRuntimePaths paths;
    private final OpenAiAuditService audit;

    public OpenAiLocalCredentialStore(OpenAiProperties properties, ObjectMapper objectMapper, OpenAiRuntimePaths paths, OpenAiAuditService audit) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.paths = paths;
        this.audit = audit;
    }

    public Optional<OpenAiLinkedCredential> read() {
        if (!properties.getLocalCredential().isEnabled()) {
            return Optional.empty();
        }
        Path path = path();
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(path.toFile());
            String type = text(root, "type");
            String apiKey = text(root, "api_key");
            if (apiKey.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new OpenAiLinkedCredential(
                    type,
                    apiKey,
                    text(root, "organization_id"),
                    text(root, "project_id"),
                    text(root, "fingerprint"),
                    instant(root, "created_at"),
                    instant(root, "updated_at")
            ));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public OpenAiLocalCredentialStatus status() {
        Optional<OpenAiLinkedCredential> credential = read();
        if (credential.isEmpty()) {
            return new OpenAiLocalCredentialStatus(
                    properties.getLocalCredential().isEnabled(),
                    false,
                    "none",
                    path().toString(),
                    "",
                    "",
                    "",
                    "",
                    "",
                    Files.exists(path()) ? "local credential file exists but could not be read" : "no local OpenAI credential linked"
            );
        }
        OpenAiLinkedCredential linked = credential.get();
        return new OpenAiLocalCredentialStatus(
                properties.getLocalCredential().isEnabled(),
                true,
                linked.type(),
                path().toString(),
                linked.fingerprint(),
                linked.organizationId(),
                linked.projectId(),
                linked.createdAt().toString(),
                linked.updatedAt().toString(),
                "local OpenAI credential linked"
        );
    }

    public OpenAiLocalCredentialStatus saveApiKey(String apiKey, String organizationId, String projectId) {
        if (!properties.getLocalCredential().isEnabled()) {
            audit.warn("OpenAI local credential save rejected: local store disabled", Map.of("path", path().toString()));
            throw new OpenAiException("suite.openai.local-credential.enabled=false");
        }
        String normalizedKey = apiKey == null ? "" : apiKey.trim();
        if (normalizedKey.isBlank()) {
            audit.warn("OpenAI local credential save rejected: empty API key", Map.of("path", path().toString()));
            throw new OpenAiException("OpenAI API key is empty");
        }
        if (normalizedKey.length() < 20) {
            audit.warn("OpenAI local credential save rejected: API key too short", Map.of("path", path().toString(), "length", normalizedKey.length()));
            throw new OpenAiException("OpenAI API key is too short");
        }
        Instant now = Instant.now();
        Instant createdAt = read().map(OpenAiLinkedCredential::createdAt).orElse(now);
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "api_key");
        payload.put("api_key", normalizedKey);
        payload.put("organization_id", organizationId == null ? "" : organizationId.trim());
        payload.put("project_id", projectId == null ? "" : projectId.trim());
        payload.put("fingerprint", fingerprint(normalizedKey));
        payload.put("created_at", createdAt.toString());
        payload.put("updated_at", now.toString());
        try {
            Files.createDirectories(path().getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path().toFile(), payload);
            OpenAiLocalCredentialStatus status = status();
            audit.info("OpenAI local API key linked", Map.of(
                    "path", path().toString(),
                    "fingerprint", status.fingerprint(),
                    "organizationConfigured", !status.organizationId().isBlank(),
                    "projectConfigured", !status.projectId().isBlank()
            ));
            return status;
        } catch (IOException ex) {
            audit.error("OpenAI local credential write failed", Map.of("path", path().toString(), "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            throw new OpenAiException("failed to write OpenAI local credential: " + path(), ex);
        }
    }

    public OpenAiLocalCredentialStatus unlink() {
        try {
            boolean existed = Files.deleteIfExists(path());
            OpenAiLocalCredentialStatus status = status();
            audit.info("OpenAI local credential unlinked", Map.of("path", path().toString(), "existed", existed));
            return status;
        } catch (IOException ex) {
            audit.error("OpenAI local credential unlink failed", Map.of("path", path().toString(), "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            throw new OpenAiException("failed to remove OpenAI local credential: " + path(), ex);
        }
    }

    public Path path() {
        return paths.resolveRuntimePath(properties.getLocalCredential().getRelativePath());
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            return null;
        }
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
}
