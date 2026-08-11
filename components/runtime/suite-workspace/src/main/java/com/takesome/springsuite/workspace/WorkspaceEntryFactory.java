package com.takesome.springsuite.workspace;

import com.takesome.springsuite.workspace.fs.WorkspacePathPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;

final class WorkspaceEntryFactory {
    private final WorkspacePathPolicy pathPolicy;

    WorkspaceEntryFactory(WorkspacePathPolicy pathPolicy) {
        this.pathPolicy = pathPolicy;
    }

    WorkspaceEntry entry(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            return new WorkspaceEntry(
                    pathPolicy.displayPath(path),
                    path.getFileName() == null ? pathPolicy.displayPath(path) : path.getFileName().toString(),
                    attrs.isDirectory(),
                    attrs.isRegularFile(),
                    attrs.isRegularFile() ? attrs.size() : 0,
                    attrs.lastModifiedTime().toInstant()
            );
        } catch (IOException ex) {
            return new WorkspaceEntry(pathPolicy.displayPath(path), path.getFileName().toString(), Files.isDirectory(path), Files.isRegularFile(path), 0, Instant.EPOCH);
        }
    }
}
