package com.takesome.springsuite.agent;

import java.util.List;

public record AuthContext(
        boolean authenticated,
        String subject,
        String tokenType,
        List<String> scopes,
        boolean bridgeToken
) {
    public AuthContext {
        subject = subject == null ? "" : subject;
        tokenType = tokenType == null ? "" : tokenType;
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    public boolean hasScope(String required) {
        return bridgeToken || scopes.contains(required) || scopes.contains("northstar.admin");
    }
}
