package com.takesome.springsuite.workspace;

public record WorkspaceWriteRequest(
        String path,
        String content,
        boolean createParents,
        boolean dryRun,
        String expectedSha256
) {
    public WorkspaceWriteRequest {
        path = path == null ? "" : path;
        content = content == null ? "" : content;
        expectedSha256 = expectedSha256 == null ? "" : expectedSha256;
    }
}
