package com.takesome.springsuite.openai;

public record OpenAiLinkRequest(
        String apiKey,
        String organizationId,
        String projectId,
        String setupToken
) {
    public OpenAiLinkRequest {
        apiKey = apiKey == null ? "" : apiKey.trim();
        organizationId = organizationId == null ? "" : organizationId.trim();
        projectId = projectId == null ? "" : projectId.trim();
        setupToken = setupToken == null ? "" : setupToken.trim();
    }
}
