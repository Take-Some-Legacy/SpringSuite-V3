package com.takesome.springsuite.workspace;

import java.util.List;

public record WorkspaceSummary(
        boolean enabled,
        boolean allowRead,
        boolean allowWrite,
        boolean allowDelete,
        boolean createBackups,
        int maxReadBytes,
        int maxSearchResults,
        int maxTreeItems,
        long maxFileSizeBytes,
        List<String> roots,
        List<String> denySegments,
        List<String> textExtensions,
        List<WorkspaceOperationDescriptor> operations
) {
    public WorkspaceSummary {
        roots = roots == null ? List.of() : List.copyOf(roots);
        denySegments = denySegments == null ? List.of() : List.copyOf(denySegments);
        textExtensions = textExtensions == null ? List.of() : List.copyOf(textExtensions);
        operations = operations == null ? List.of() : List.copyOf(operations);
    }
}
