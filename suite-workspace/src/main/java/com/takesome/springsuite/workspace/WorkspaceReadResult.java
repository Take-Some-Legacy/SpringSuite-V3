package com.takesome.springsuite.workspace;

public record WorkspaceReadResult(
        String path,
        long sizeBytes,
        int offset,
        int bytesRead,
        boolean truncated,
        String sha256,
        String content
) {
}
