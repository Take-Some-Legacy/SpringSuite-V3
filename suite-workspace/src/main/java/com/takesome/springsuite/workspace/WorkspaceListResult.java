package com.takesome.springsuite.workspace;

import java.util.List;

public record WorkspaceListResult(
        String path,
        boolean truncated,
        int count,
        List<WorkspaceEntry> entries
) {
    public WorkspaceListResult {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
