package com.takesome.springsuite.openai;

import java.time.Instant;

public record OpenAiCredential(
        boolean available,
        String token,
        String mode,
        String credentialKind,
        String source,
        String fingerprint,
        Instant issuedAt,
        Instant expiresAt,
        Instant refreshAt,
        String scope,
        boolean cached,
        String message
) {
    public static OpenAiCredential unavailable(String mode, String message) {
        return new OpenAiCredential(false, "", mode, "none", "none", "", null, null, null, "", false, message);
    }

    public boolean refreshDue(Instant now) {
        return available && refreshAt != null && !now.isBefore(refreshAt);
    }

    public boolean expired(Instant now) {
        return available && expiresAt != null && !now.isBefore(expiresAt);
    }

    public OpenAiCredential withMessage(String source, String message) {
        return new OpenAiCredential(available, token, mode, credentialKind, source, fingerprint, issuedAt, expiresAt, refreshAt, scope, cached, message);
    }
}
