package com.takesome.springsuite.toolbelt;

import java.time.Instant;
import java.util.List;

public record ToolRunResult(
        boolean ok,
        String toolId,
        List<String> commandPreview,
        String cwd,
        Integer exitCode,
        long durationMs,
        String stdout,
        String stderr,
        String message,
        boolean dryRun,
        Instant timestamp
) {
    public ToolRunResult {
        commandPreview = commandPreview == null ? List.of() : List.copyOf(commandPreview);
        cwd = cwd == null ? "" : cwd;
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        message = message == null ? "" : message;
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}
