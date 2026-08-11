package com.takesome.springsuite.core.ai;

import java.util.Map;

public record AiCredentialStatus(
        String providerId,
        boolean enabled,
        boolean available,
        String credentialKind,
        String source,
        String fingerprint,
        String expiresAt,
        String message,
        Map<String, Object> metadata
) {
    public AiCredentialStatus {
        providerId = providerId == null ? "" : providerId.trim();
        credentialKind = credentialKind == null ? "none" : credentialKind.trim();
        source = source == null ? "" : source.trim();
        fingerprint = fingerprint == null ? "" : fingerprint.trim();
        expiresAt = expiresAt == null ? "" : expiresAt.trim();
        message = message == null ? "" : message;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static AiCredentialStatus unavailable(String providerId, boolean enabled, String message) {
        return new AiCredentialStatus(providerId, enabled, false, "none", "none", "", "", message, Map.of());
    }
}
