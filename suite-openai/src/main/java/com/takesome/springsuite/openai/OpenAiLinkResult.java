package com.takesome.springsuite.openai;

public record OpenAiLinkResult(
        OpenAiCredentialStatus credential,
        OpenAiLocalCredentialStatus localCredential,
        String setupUrl,
        String message
) {
}
