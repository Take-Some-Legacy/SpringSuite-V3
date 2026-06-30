package com.takesome.springsuite.workspace;

import java.util.List;

public record WorkspaceSummary(
        boolean enabled,
        String activeProfile,
        List<String> availableProfiles,
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
        List<String> denyGlobs,
        List<String> textExtensions,
        List<WorkspaceOperationDescriptor> operations
) {
    public WorkspaceSummary {
        activeProfile = activeProfile == null ? "" : activeProfile;
        availableProfiles = availableProfiles == null ? List.of() : List.copyOf(availableProfiles);
        roots = roots == null ? List.of() : List.copyOf(roots);
        denySegments = denySegments == null ? List.of() : List.copyOf(denySegments);
        denyGlobs = denyGlobs == null ? List.of() : List.copyOf(denyGlobs);
        textExtensions = textExtensions == null ? List.of() : List.copyOf(textExtensions);
        operations = operations == null ? List.of() : List.copyOf(operations);
    }
}
