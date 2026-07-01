package com.takesome.springsuite.cloudflared;

import java.time.Instant;
import java.util.List;

public record CloudflaredTunnelStatus(
        boolean enabled,
        boolean running,
        Long pid,
        String targetUrl,
        String tunnelName,
        String hostname,
        String cacheDirectory,
        String publicUrl,
        Instant startedAt,
        Integer exitCode,
        String lastError,
        List<String> command
) {
}
