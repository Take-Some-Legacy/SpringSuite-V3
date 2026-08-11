package com.takesome.springsuite.workspace;

public record WorkspaceSearchMatch(
        String path,
        int lineNumber,
        String line
) {
}
