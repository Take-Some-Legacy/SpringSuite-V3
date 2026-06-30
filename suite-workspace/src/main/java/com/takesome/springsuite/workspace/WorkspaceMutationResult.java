package com.takesome.springsuite.workspace;

public record WorkspaceMutationResult(
        boolean ok,
        String path,
        boolean dryRun,
        String message
) {
}
