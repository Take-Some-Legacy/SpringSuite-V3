package com.takesome.springsuite.workspace.fs;

import com.takesome.springsuite.workspace.WorkspaceEntry;
import java.nio.file.Path;
import java.util.List;

public interface WorkspaceFsBackend extends AutoCloseable {
    WorkspaceFsBackendKind kind();

    boolean isAvailable();

    List<WorkspaceEntry> list(Path target, int maxEntries, WorkspacePathPolicy pathPolicy);

    List<WorkspaceEntry> tree(Path target, int maxDepth, int maxEntries, WorkspacePathPolicy pathPolicy);

    byte[] readAllBytes(Path target);

    @Override
    default void close() {
        // no-op
    }
}
