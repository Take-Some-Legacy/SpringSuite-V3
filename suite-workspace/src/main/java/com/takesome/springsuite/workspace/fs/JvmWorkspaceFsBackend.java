package com.takesome.springsuite.workspace.fs;

import com.takesome.springsuite.workspace.WorkspaceEntry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class JvmWorkspaceFsBackend implements WorkspaceFsBackend {
    @Override
    public WorkspaceFsBackendKind kind() {
        return WorkspaceFsBackendKind.JVM;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public List<WorkspaceEntry> list(Path target, int maxEntries, WorkspacePathPolicy pathPolicy) {
        try (Stream<Path> stream = Files.list(target)) {
            return stream
                    .filter(pathPolicy::isNotDenied)
                    .sorted(Comparator.comparing(item -> item.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .limit(Math.max(1, maxEntries))
                    .map(item -> entry(item, pathPolicy))
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to list directory", ex);
        }
    }

    @Override
    public List<WorkspaceEntry> tree(Path target, int maxDepth, int maxEntries, WorkspacePathPolicy pathPolicy) {
        try (Stream<Path> stream = Files.walk(target, Math.max(0, maxDepth), FileVisitOption.FOLLOW_LINKS)) {
            return stream
                    .filter(item -> !item.equals(target))
                    .filter(pathPolicy::isNotDenied)
                    .sorted(Comparator.comparing(item -> pathPolicy.displayPath(item).toLowerCase(Locale.ROOT)))
                    .limit(Math.max(1, maxEntries))
                    .map(item -> entry(item, pathPolicy))
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to build tree", ex);
        }
    }

    @Override
    public byte[] readAllBytes(Path target) {
        try {
            return Files.readAllBytes(target);
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to read file", ex);
        }
    }

    private WorkspaceEntry entry(Path path, WorkspacePathPolicy pathPolicy) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            return new WorkspaceEntry(
                    pathPolicy.displayPath(path),
                    path.getFileName() == null ? pathPolicy.displayPath(path) : path.getFileName().toString(),
                    attrs.isDirectory(),
                    attrs.isRegularFile(),
                    attrs.isRegularFile() ? attrs.size() : 0L,
                    attrs.lastModifiedTime().toInstant()
            );
        } catch (IOException ex) {
            return new WorkspaceEntry(
                    pathPolicy.displayPath(path),
                    path.getFileName() == null ? pathPolicy.displayPath(path) : path.getFileName().toString(),
                    Files.isDirectory(path),
                    Files.isRegularFile(path),
                    0L,
                    Instant.EPOCH
            );
        }
    }
}
