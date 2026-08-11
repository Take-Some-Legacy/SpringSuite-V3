package com.takesome.springsuite.openai;



public record OpenAiCredentialStatus(

        boolean enabled,

        boolean available,

        String mode,

        String credentialKind,

        String source,

        String fingerprint,

        String issuedAt,

        String expiresAt,

        String refreshAt,

        String scope,

        String cachePath,

        boolean cached,

        String message

) {

    public static OpenAiCredentialStatus from(OpenAiProperties properties, OpenAiCredential credential, String cachePath) {

        return new OpenAiCredentialStatus(

                properties.isEnabled(),

                credential.available(),

                credential.mode(),

                credential.credentialKind(),

                credential.source(),

                credential.fingerprint(),

                credential.issuedAt() == null ? "" : credential.issuedAt().toString(),

                credential.expiresAt() == null ? "" : credential.expiresAt().toString(),

                credential.refreshAt() == null ? "" : credential.refreshAt().toString(),

                credential.scope(),

                cachePath,

                credential.cached(),

                credential.message()

        );

    }

}
