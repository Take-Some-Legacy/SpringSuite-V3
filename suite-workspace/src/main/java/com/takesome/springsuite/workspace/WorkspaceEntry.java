package com.takesome.springsuite.workspace;

import java.time.Instant;

public record WorkspaceEntry(
        String path,
        String name,
        boolean directory,
        boolean regularFile,
        long sizeBytes,
        Instant modifiedAt
) {
}
