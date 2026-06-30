package com.takesome.springsuite.workspace;

public record WorkspaceOperationDescriptor(
        String id,
        String method,
        String endpoint,
        String command,
        String risk,
        String summary,
        String description
) {
}
