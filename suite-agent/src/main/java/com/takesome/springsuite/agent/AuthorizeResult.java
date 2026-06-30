package com.takesome.springsuite.agent;

import java.util.Map;

public record AuthorizeResult(
        int status,
        Map<String, String> headers,
        String body
) {
    public AuthorizeResult {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? "" : body;
    }
}
