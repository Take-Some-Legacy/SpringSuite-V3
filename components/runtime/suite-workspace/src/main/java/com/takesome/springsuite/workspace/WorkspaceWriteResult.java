package com.takesome.springsuite.workspace;

public record WorkspaceWriteResult(
        boolean ok,
        String path,
        boolean created,
        boolean dryRun,
        String backupPath,
        int bytesWritten,
        String sha256,
        String message
) {
}
