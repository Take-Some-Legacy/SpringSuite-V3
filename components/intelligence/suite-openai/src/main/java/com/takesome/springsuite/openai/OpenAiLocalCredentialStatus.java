package com.takesome.springsuite.openai;

public record OpenAiLocalCredentialStatus(
        boolean enabled,
        boolean linked,
        String type,
        String path,
        String fingerprint,
        String organizationId,
        String projectId,
        String createdAt,
        String updatedAt,
        String message
) {
}
