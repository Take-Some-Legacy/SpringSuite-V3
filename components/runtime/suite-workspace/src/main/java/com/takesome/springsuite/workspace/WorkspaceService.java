package com.takesome.springsuite.workspace;

import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import com.takesome.springsuite.workspace.fs.WorkspacePathPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceService {
    private final WorkspaceProperties properties;
    private final OperatorLogService logService;
    private final WorkspacePathPolicy pathPolicy;
    private final WorkspaceAccessGuard accessGuard;
    private final WorkspaceTextFilePolicy textFilePolicy;
    private final WorkspaceEntryFactory entryFactory;
    private final WorkspaceSearchEngine searchEngine;
    private final RepositoryDescriptorService repositoryDescriptorService;
    private final WorkspaceBackupPlanner backupPlanner = new WorkspaceBackupPlanner();

    public WorkspaceService(
            WorkspaceProperties properties,
            OperatorLogService logService,
            WorkspacePathPolicy pathPolicy,
            RepositoryDescriptorService repositoryDescriptorService
    ) {
        this.properties = properties;
        this.logService = logService;
        this.pathPolicy = pathPolicy;
        this.repositoryDescriptorService = repositoryDescriptorService;
        this.accessGuard = new WorkspaceAccessGuard(properties);
        this.textFilePolicy = new WorkspaceTextFilePolicy(properties, pathPolicy);
        this.entryFactory = new WorkspaceEntryFactory(pathPolicy);
        this.searchEngine = new WorkspaceSearchEngine(properties, pathPolicy, textFilePolicy);
    }

    public WorkspaceSummary summary() {
        return new WorkspaceSummary(
                properties.isEnabled(),
                properties.getActiveProfile(),
                properties.availableProfiles(),
                properties.effectiveAllowRead(),
                properties.effectiveAllowWrite(),
                properties.effectiveAllowDelete(),
                properties.isCreateBackups(),
                properties.getMaxReadBytes(),
                properties.getMaxSearchResults(),
                properties.getMaxTreeItems(),
                properties.getMaxFileSizeBytes(),
                pathPolicy.allowedRoots().stream().map(Path::toString).toList(),
                properties.effectiveDenySegments(),
                properties.effectiveDenyGlobs(),
                properties.getTextExtensions(),
                operations()
        );
    }

    public List<WorkspaceOperationDescriptor> operations() {
        return List.of(
                new WorkspaceOperationDescriptor("list", "GET", "/api/workspace/list?path=.&limit=0", "workspace list [path]", "READ_ONLY", "List directory entries.", "Directory listing inside configured workspace roots; zero means unlimited."),
                new WorkspaceOperationDescriptor("tree", "GET", "/api/workspace/tree?path=.&depth=0&limit=0", "workspace tree [path] [depth]", "READ_ONLY", "List a bounded recursive tree.", "Recursive tree view; zero depth and item count mean unlimited."),
                new WorkspaceOperationDescriptor("read", "GET", "/api/workspace/read?path=...&offset=0&maxBytes=0", "workspace read <path>", "READ_ONLY", "Read a UTF-8 text file.", "Text read with SHA-256 for optimistic edit checks; zero maxBytes means unlimited."),
                new WorkspaceOperationDescriptor("search", "GET", "/api/workspace/search?q=...&path=.&limit=0", "workspace search <query> [path]", "READ_ONLY", "Search text files.", "Searches configured text files under a safe workspace path."),
                new WorkspaceOperationDescriptor("write", "POST", "/api/workspace/write", "workspace write <path> <content>", "LOCAL_MUTATION", "Create or replace a text file.", "Write is gated by suite.workspace.allow-write and creates backups when enabled."),
                new WorkspaceOperationDescriptor("mkdir", "POST", "/api/workspace/mkdir?path=...", "workspace mkdir <path>", "LOCAL_MUTATION", "Create a directory.", "Creates a directory inside configured workspace roots when writing is enabled."),
                new WorkspaceOperationDescriptor("delete", "POST", "/api/workspace/delete", "workspace delete <path> [--recursive] [--dry-run]", "LOCAL_MUTATION", "Delete a path.", "Delete is separately gated by suite.workspace.allow-delete and supports dry-run.")
        );
    }

    public WorkspaceListResult list(String path, int limit) {
        accessGuard.ensureRead();
        Path target = pathPolicy.resolveSafe(path);
        if (!Files.isDirectory(target)) {
            throw new IllegalArgumentException("not a directory: " + pathPolicy.displayPath(target));
        }
        int safeLimit = effectiveLimit(limit, properties.getMaxTreeItems());
        ArrayList<WorkspaceEntry> entries = new ArrayList<>();
        AtomicBoolean truncated = new AtomicBoolean(false);
        try (Stream<Path> stream = Files.list(target)) {
            stream.filter(pathPolicy::isNotDenied)
                    .sorted(Comparator.comparing(item -> item.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .limit(safeLimit + 1L)
                    .forEach(item -> {
                        if (entries.size() >= safeLimit) {
                            truncated.set(true);
                            return;
                        }
                        entries.add(entryFactory.entry(item));
                    });
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to list directory: " + ex.getMessage(), ex);
        }
        return new WorkspaceListResult(pathPolicy.displayPath(target), truncated.get(), entries.size(), entries);
    }

    public WorkspaceListResult tree(String path, int depth, int limit) {
        accessGuard.ensureRead();
        Path target = pathPolicy.resolveSafe(path);
        int safeDepth = depth <= 0 ? Integer.MAX_VALUE : depth;
        int safeLimit = effectiveLimit(limit, properties.getMaxTreeItems());
        ArrayList<WorkspaceEntry> entries = new ArrayList<>();
        AtomicBoolean truncated = new AtomicBoolean(false);
        try (Stream<Path> stream = Files.walk(target, safeDepth, FileVisitOption.FOLLOW_LINKS)) {
            stream.filter(item -> !item.equals(target))
                    .filter(pathPolicy::isNotDenied)
                    .sorted(Comparator.comparing(item -> pathPolicy.displayPath(item).toLowerCase(Locale.ROOT)))
                    .limit(safeLimit + 1L)
                    .forEach(item -> {
                        if (entries.size() >= safeLimit) {
                            truncated.set(true);
                            return;
                        }
                        entries.add(entryFactory.entry(item));
                    });
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to build tree: " + ex.getMessage(), ex);
        }
        return new WorkspaceListResult(pathPolicy.displayPath(target), truncated.get(), entries.size(), entries);
    }

    public WorkspaceReadResult read(String path, int offset, int maxBytes) {
        accessGuard.ensureRead();
        Path target = pathPolicy.resolveSafe(path);
        textFilePolicy.ensureTextFile(target);
        int safeOffset = Math.max(0, offset);
        int configuredMax = effectiveLimit(maxBytes, properties.getMaxReadBytes());
        try {
            byte[] all = Files.readAllBytes(target);
            int safeMax = Math.min(configuredMax, all.length);
            int start = Math.min(safeOffset, all.length);
            int end = (int) Math.min(all.length, (long) start + safeMax);
            byte[] slice = java.util.Arrays.copyOfRange(all, start, end);
            boolean truncated = end < all.length;
            return new WorkspaceReadResult(
                    pathPolicy.displayPath(target),
                    all.length,
                    start,
                    slice.length,
                    truncated,
                    WorkspaceDigest.sha256(all),
                    new String(slice, StandardCharsets.UTF_8)
            );
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to read file: " + ex.getMessage(), ex);
        }
    }

    public WorkspaceWriteResult write(WorkspaceWriteRequest request) {
        accessGuard.ensureWrite();
        Path target = pathPolicy.resolveSafe(request.path());
        byte[] content = request.content().getBytes(StandardCharsets.UTF_8);
        boolean created = !Files.exists(target);
        String newSha = WorkspaceDigest.sha256(content);
        if (request.dryRun()) {
            return new WorkspaceWriteResult(true, pathPolicy.displayPath(target), created, true, "", content.length, newSha, "dry run");
        }
        try {
            if (Files.exists(target)) {
                textFilePolicy.ensureTextFile(target);
                if (!request.expectedSha256().isBlank()) {
                    String current = WorkspaceDigest.sha256(Files.readAllBytes(target));
                    if (!current.equalsIgnoreCase(request.expectedSha256())) {
                        return new WorkspaceWriteResult(false, pathPolicy.displayPath(target), false, false, "", 0, current, "expectedSha256 mismatch");
                    }
                }
            }
            if (request.createParents()) {
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            }
            String backup = "";
            if (Files.exists(target) && properties.isCreateBackups()) {
                Path backupPath = backupPlanner.backupPath(target);
                Files.copy(target, backupPath, StandardCopyOption.COPY_ATTRIBUTES);
                backup = pathPolicy.displayPath(backupPath);
            }
            Files.write(target, content);
            logService.append(OperatorLogLevel.INFO, "workspace", "workspace file written", Map.of(
                    "path", pathPolicy.displayPath(target),
                    "bytes", content.length,
                    "backup", backup
            ));
            return new WorkspaceWriteResult(true, pathPolicy.displayPath(target), created, false, backup, content.length, newSha, "written");
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to write file: " + ex.getMessage(), ex);
        }
    }

    public WorkspaceMutationResult mkdir(String path, boolean dryRun) {
        accessGuard.ensureWrite();
        Path target = pathPolicy.resolveSafe(path);
        if (dryRun) {
            return new WorkspaceMutationResult(true, pathPolicy.displayPath(target), true, "dry run");
        }
        try {
            Files.createDirectories(target);
            logService.append(OperatorLogLevel.INFO, "workspace", "workspace directory created", Map.of("path", pathPolicy.displayPath(target)));
            return new WorkspaceMutationResult(true, pathPolicy.displayPath(target), false, "created");
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to create directory: " + ex.getMessage(), ex);
        }
    }

    public WorkspaceMutationResult delete(WorkspaceDeleteRequest request) {
        accessGuard.ensureDelete();
        Path target = pathPolicy.resolveSafe(request.path());
        if (request.dryRun()) {
            return new WorkspaceMutationResult(true, pathPolicy.displayPath(target), true, "dry run");
        }
        try {
            if (Files.isDirectory(target) && request.recursive()) {
                try (Stream<Path> stream = Files.walk(target)) {
                    List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
                    for (Path item : paths) {
                        Files.deleteIfExists(item);
                    }
                }
            } else {
                Files.deleteIfExists(target);
            }
            logService.append(OperatorLogLevel.WARN, "workspace", "workspace path deleted", Map.of("path", pathPolicy.displayPath(target)));
            return new WorkspaceMutationResult(true, pathPolicy.displayPath(target), false, "deleted");
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to delete path: " + ex.getMessage(), ex);
        }
    }

    public WorkspaceSearchResult search(String query, String path, int limit, boolean regex, boolean caseSensitive) {
        accessGuard.ensureRead();
        return searchEngine.search(query, path, limit, regex, caseSensitive);
    }
    public RepositoryDescriptorResult repoInfo(String path) {
        accessGuard.ensureRead();
        return repositoryDescriptorService.read(path);
    }

    public RepositoryDescriptorResult repoFile(String path, boolean overwrite) {
        accessGuard.ensureWrite();
        return repositoryDescriptorService.ensure(path, overwrite);
    }

    public RepositoryDescriptorCatalog repoCatalog(String path, boolean ensureMissing, boolean overwrite) {
        if (ensureMissing || overwrite) {
            accessGuard.ensureWrite();
        } else {
            accessGuard.ensureRead();
        }
        return repositoryDescriptorService.repositories(path, ensureMissing, overwrite);
    }

    public RepositoryDescriptorResult repoRemember(String path, boolean pinned) {
        accessGuard.ensureWrite();
        return repositoryDescriptorService.remember(path, pinned, "manual");
    }

    public RepositoryDescriptorCatalog repoForget(String path) {
        accessGuard.ensureWrite();
        return repositoryDescriptorService.forget(path);
    }


    private static int effectiveLimit(int requested, int configured) {
        if (requested > 0) {
            return configured > 0 ? Math.min(requested, configured) : requested;
        }
        return configured > 0 ? configured : Integer.MAX_VALUE - 1;
    }

}
