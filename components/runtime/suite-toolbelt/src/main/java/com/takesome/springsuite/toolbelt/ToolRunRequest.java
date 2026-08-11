package com.takesome.springsuite.toolbelt;

import java.util.List;

public record ToolRunRequest(
        String toolId,
        List<String> args,
        String cwd,
        String stdin,
        Integer timeoutSec,
        Integer maxStdoutBytes,
        Integer maxStderrBytes,
        Boolean dryRun
) {
    public ToolRunRequest {
        args = args == null ? List.of() : List.copyOf(args);
        cwd = cwd == null ? "" : cwd;
        stdin = stdin == null ? "" : stdin;
        timeoutSec = timeoutSec == null ? 0 : timeoutSec;
        maxStdoutBytes = maxStdoutBytes == null ? 12000 : maxStdoutBytes;
        maxStderrBytes = maxStderrBytes == null ? 8000 : maxStderrBytes;
        dryRun = dryRun == null ? Boolean.FALSE : dryRun;
    }
}
