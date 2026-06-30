package com.takesome.springsuite.agent;

public record BridgeTokenResult(
        String path,
        String fingerprint,
        String token,
        boolean created,
        boolean rotated,
        boolean revealed
) {
}
