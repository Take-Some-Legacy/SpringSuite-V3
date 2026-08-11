package com.takesome.springsuite.workspace;

public record WorkspaceDeleteRequest(
        String path,
        boolean recursive,
        boolean dryRun
) {
    public WorkspaceDeleteRequest {
        path = path == null ? "" : path;
    }
}
