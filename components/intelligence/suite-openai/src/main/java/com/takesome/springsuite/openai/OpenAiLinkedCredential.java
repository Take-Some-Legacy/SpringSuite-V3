package com.takesome.springsuite.openai;

import java.time.Instant;

public record OpenAiLinkedCredential(
        String type,
        String apiKey,
        String organizationId,
        String projectId,
        String fingerprint,
        Instant createdAt,
        Instant updatedAt
) {
    public OpenAiLinkedCredential {
        type = type == null || type.isBlank() ? "api_key" : type.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        organizationId = organizationId == null ? "" : organizationId.trim();
        projectId = projectId == null ? "" : projectId.trim();
        fingerprint = fingerprint == null ? "" : fingerprint.trim();
        Instant now = Instant.now();
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }
}
