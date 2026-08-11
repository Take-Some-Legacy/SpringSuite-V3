package com.takesome.springsuite.agent;

import java.util.Map;

public record TokenExchangeResult(
        int status,
        Map<String, String> headers,
        Map<String, Object> body
) {
    public TokenExchangeResult {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? Map.of() : Map.copyOf(body);
    }
}
