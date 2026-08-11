package com.takesome.springsuite.agent;

import java.util.List;

public record AuthStatus(
        boolean enabled,
        String scheme,
        boolean requireAuthForMcp,
        String runtimeRoot,
        String bridgeTokenPath,
        boolean bridgeTokenPresent,
        String bridgeTokenFingerprint,
        String oauthRoot,
        List<String> supportedScopes,
        List<String> defaultScopes
) {
    public AuthStatus {
        supportedScopes = supportedScopes == null ? List.of() : List.copyOf(supportedScopes);
        defaultScopes = defaultScopes == null ? List.of() : List.copyOf(defaultScopes);
    }
}
