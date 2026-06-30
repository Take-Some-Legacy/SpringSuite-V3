package com.takesome.springsuite.workspace;

import java.util.List;

public record WorkspaceSearchResult(
        String query,
        String path,
        boolean regex,
        boolean caseSensitive,
        boolean truncated,
        int count,
        List<WorkspaceSearchMatch> matches
) {
    public WorkspaceSearchResult {
        matches = matches == null ? List.of() : List.copyOf(matches);
    }
}
