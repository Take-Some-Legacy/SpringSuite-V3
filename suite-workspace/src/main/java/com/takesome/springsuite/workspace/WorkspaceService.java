package com.takesome.springsuite.workspace;

import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import com.takesome.springsuite.workspace.fs.WorkspacePathPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceService {
    private final WorkspaceProperties properties;
    private final OperatorLogService logService;
    private final WorkspacePathPolicy pathPolicy;

    public WorkspaceService(WorkspaceProperties properties, OperatorLogService logService, WorkspacePathPolicy pathPolicy) {
        this.properties = properties;
        this.logService = logService;
        this.pathPolicy = pathPolicy;
    }

    public WorkspaceSummary summary() {
        return new WorkspaceSummary(
                properties.isEnabled(),
                properties.isAllowRead(),
                properties.isAllowWrite(),
                properties.isAllowDelete(),
                properties.isCreateBackups(),
                properties.getMaxReadBytes(),
                properties.getMaxSearchResults(),
                properties.getMaxTreeItems(),
                properties.getMaxFileSizeBytes(),
                pathPolicy.allowedRoots().stream().map(Path::toString).toList(),
                properties.getDenySegments(),
                properties.getTextExtensions(),
                operations()
        );
    }

    public List<WorkspaceOperationDescriptor> operations() {
        return List.of(
                new WorkspaceOperationDescriptor("list", "GET", "/api/workspace/list?path=.&limit=100", "workspace list [path]", "READ_ONLY", "List directory entries.", "Bounded directory listing inside configured workspace roots."),
                new WorkspaceOperationDescriptor("tree", "GET", "/api/workspace/tree?path=.&depth=3&limit=500", "workspace tree [path] [depth]", "READ_ONLY", "List a bounded recursive tree.", "Recursive tree view with depth and item limits for agent orientation and human inspection."),
                new WorkspaceOperationDescriptor("read", "GET", "/api/workspace/read?path=...&offset=0&maxBytes=65536", "workspace read <path>", "READ_ONLY", "Read a UTF-8 text file.", "Bounded text read with SHA-256 for optimistic edit checks."),
                new WorkspaceOperationDescriptor("search", "GET", "/api/workspace/search?q=...&path=.&limit=100", "workspace search <query> [path]", "READ_ONLY", "Search text files.", "Searches configured text files under a safe workspace path."),
                new WorkspaceOperationDescriptor("write", "POST", "/api/workspace/write", "workspace write <path> <content>", "LOCAL_MUTATION", "Create or replace a text file.", "Write is gated by suite.workspace.allow-write and creates backups when enabled."),
                new WorkspaceOperationDescriptor("mkdir", "POST", "/api/workspace/mkdir?path=...", "workspace mkdir <path>", "LOCAL_MUTATION", "Create a directory.", "Creates a directory inside configured workspace roots when writing is enabled."),
                new WorkspaceOperationDescriptor("delete", "POST", "/api/workspace/delete", "workspace delete <path> [--recursive] [--dry-run]", "LOCAL_MUTATION", "Delete a path.", "Delete is separately gated by suite.workspace.allow-delete and supports dry-run.")
        );
    }

    public WorkspaceListResult list(String path, int limit) {
        ensureRead();
        Path target = pathPolicy.resolveSafe(path);
        if (!Files.isDirectory(target)) {
            throw new IllegalArgumentException("not a directory: " + pathPolicy.displayPath(target));
        }
        int safeLimit = limit <= 0 ? 100 : Math.min(limit, properties.getMaxTreeItems());
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
                        entries.add(entry(item));
                    });
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to list directory: " + ex.getMessage(), ex);
        }
        return new WorkspaceListResult(pathPolicy.displayPath(target), truncated.get(), entries.size(), entries);
    }

    public WorkspaceListResult tree(String path, int depth, int limit) {
        ensureRead();
        Path target = pathPolicy.resolveSafe(path);
        int safeDepth = Math.max(0, Math.min(depth <= 0 ? 3 : depth, 12));
        int safeLimit = limit <= 0 ? properties.getMaxTreeItems() : Math.min(limit, properties.getMaxTreeItems());
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
                        entries.add(entry(item));
                    });
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to build tree: " + ex.getMessage(), ex);
        }
        return new WorkspaceListResult(pathPolicy.displayPath(target), truncated.get(), entries.size(), entries);
    }

    public WorkspaceReadResult read(String path, int offset, int maxBytes) {
        ensureRead();
        Path target = pathPolicy.resolveSafe(path);
        ensureTextFile(target);
        int safeOffset = Math.max(0, offset);
        int safeMax = maxBytes <= 0 ? properties.getMaxReadBytes() : Math.min(maxBytes, properties.getMaxReadBytes());
        try {
            byte[] all = Files.readAllBytes(target);
            int start = Math.min(safeOffset, all.length);
            int end = Math.min(all.length, start + safeMax);
            byte[] slice = java.util.Arrays.copyOfRange(all, start, end);
            boolean truncated = end < all.length;
            return new WorkspaceReadResult(
                    pathPolicy.displayPath(target),
                    all.length,
                    start,
                    slice.length,
                    truncated,
                    sha256(all),
                    new String(slice, StandardCharsets.UTF_8)
            );
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to read file: " + ex.getMessage(), ex);
        }
    }

    public WorkspaceWriteResult write(WorkspaceWriteRequest request) {
        ensureWrite();
        Path target = pathPolicy.resolveSafe(request.path());
        byte[] content = request.content().getBytes(StandardCharsets.UTF_8);
        boolean created = !Files.exists(target);
        String newSha = sha256(content);
        if (request.dryRun()) {
            return new WorkspaceWriteResult(true, pathPolicy.displayPath(target), created, true, "", content.length, newSha, "dry run");
        }
        try {
            if (Files.exists(target)) {
                ensureTextFile(target);
                if (!request.expectedSha256().isBlank()) {
                    String current = sha256(Files.readAllBytes(target));
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
                Path backupPath = backupPath(target);
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
        ensureWrite();
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
        if (!properties.isEnabled()) {
            throw new IllegalStateException("workspace disabled");
        }
        if (!properties.isAllowDelete()) {
            throw new IllegalStateException("workspace delete disabled by suite.workspace.allow-delete=false");
        }
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
        ensureRead();
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("search query is required");
        }
        Path target = pathPolicy.resolveSafe(path == null || path.isBlank() ? "." : path);
        int safeLimit = limit <= 0 ? properties.getMaxSearchResults() : Math.min(limit, properties.getMaxSearchResults());
        ArrayList<WorkspaceSearchMatch> matches = new ArrayList<>();
        AtomicBoolean truncated = new AtomicBoolean(false);
        Pattern pattern = regex ? Pattern.compile(query, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : null;
        String needle = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
        try (Stream<Path> stream = Files.walk(target, 16)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(pathPolicy::isNotDenied)
                    .filter(this::isProbablyText)
                    .sorted(Comparator.comparing(item -> pathPolicy.displayPath(item).toLowerCase(Locale.ROOT)))
                    .toList();
            for (Path file : files) {
                if (matches.size() >= safeLimit) {
                    truncated.set(true);
                    break;
                }
                searchFile(file, query, needle, pattern, caseSensitive, matches, safeLimit, truncated);
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("search failed: " + ex.getMessage(), ex);
        }
        return new WorkspaceSearchResult(query, pathPolicy.displayPath(target), regex, caseSensitive, truncated.get(), matches.size(), matches);
    }

    private void searchFile(Path file, String query, String needle, Pattern pattern, boolean caseSensitive,
                            ArrayList<WorkspaceSearchMatch> matches, int limit, AtomicBoolean truncated) {
        try {
            if (Files.size(file) > properties.getMaxFileSizeBytes()) {
                return;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                boolean hit = pattern == null
                        ? (caseSensitive ? line : line.toLowerCase(Locale.ROOT)).contains(needle)
                        : pattern.matcher(line).find();
                if (!hit) {
                    continue;
                }
                if (matches.size() >= limit) {
                    truncated.set(true);
                    return;
                }
                matches.add(new WorkspaceSearchMatch(pathPolicy.displayPath(file), i + 1, line));
            }
        } catch (Exception ignored) {
            // Search must continue across unreadable or non-UTF files.
        }
    }

    private WorkspaceEntry entry(Path path) {
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

    private void ensureRead() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("workspace disabled");
        }
        if (!properties.isAllowRead()) {
            throw new IllegalStateException("workspace read disabled by suite.workspace.allow-read=false");
        }
    }

    private void ensureWrite() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("workspace disabled");
        }
        if (!properties.isAllowWrite()) {
            throw new IllegalStateException("workspace write disabled by suite.workspace.allow-write=false");
        }
    }

    private Path legacyResolveSafe(String rawPath) {
        Path path = rawPath == null || rawPath.isBlank() ? Paths.get(".") : Paths.get(rawPath);
        Path resolved = path.isAbsolute()
                ? path.toAbsolutePath().normalize()
                : pathPolicy.runtimeRoot().resolve(path).toAbsolutePath().normalize();
        Optional<Path> root = pathPolicy.allowedRoots().stream().filter(allowed -> startsWith(resolved, allowed)).findFirst();
        if (root.isEmpty()) {
            throw new IllegalArgumentException("path escapes configured workspace roots: " + rawPath);
        }
        if (!pathPolicy.isNotDenied(resolved)) {
            throw new IllegalArgumentException("path contains denied segment: " + rawPath);
        }
        return resolved;
    }

    private List<Path> allowedRoots() {
        ArrayList<Path> roots = new ArrayList<>();
        for (String raw : properties.getRoots()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            Path path = Paths.get(raw);
            roots.add(path.isAbsolute() ? path.toAbsolutePath().normalize() : pathPolicy.runtimeRoot().resolve(path).toAbsolutePath().normalize());
        }
        return roots.isEmpty() ? List.of(runtimeRoot()) : List.copyOf(roots);
    }

    private boolean startsWith(Path path, Path root) {
        return path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize());
    }

    private boolean legacyIsNotDenied(Path path) {
        Set<String> denied = properties.getDenySegments().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        for (Path part : path) {
            if (denied.contains(part.toString().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private void ensureTextFile(Path target) {
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("not a regular file: " + pathPolicy.displayPath(target));
        }
        try {
            if (Files.size(target) > properties.getMaxFileSizeBytes()) {
                throw new IllegalArgumentException("file exceeds suite.workspace.max-file-size-bytes: " + pathPolicy.displayPath(target));
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to stat file: " + ex.getMessage(), ex);
        }
        if (!isProbablyText(target)) {
            throw new IllegalArgumentException("file is not in configured text extensions and does not look UTF-8 text: " + pathPolicy.displayPath(target));
        }
    }

    private boolean isProbablyText(Path target) {
        String file = target.getFileName() == null ? "" : target.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : properties.getTextExtensions()) {
            String normalized = ext.toLowerCase(Locale.ROOT);
            if (file.equals(normalized) || file.endsWith(normalized)) {
                return true;
            }
        }
        try {
            if (!Files.isRegularFile(target) || Files.size(target) > properties.getMaxFileSizeBytes()) {
                return false;
            }
            byte[] sample = readPrefix(target, 4096);
            for (byte b : sample) {
                if (b == 0) {
                    return false;
                }
            }
            new String(sample, StandardCharsets.UTF_8);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private byte[] readPrefix(Path target, int limit) throws IOException {
        byte[] all = Files.readAllBytes(target);
        return all.length <= limit ? all : java.util.Arrays.copyOfRange(all, 0, limit);
    }

    private Path backupPath(Path target) {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneId.systemDefault()).format(Instant.now());
        return target.resolveSibling(target.getFileName() + ".bak-" + timestamp);
    }

    private String legacyDisplayPath(Path path) {
        try {
            return pathPolicy.runtimeRoot().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (IllegalArgumentException ex) {
            return path.toAbsolutePath().normalize().toString();
        }
    }

    private Path runtimeRoot() {
        return Paths.get(System.getProperty("suite.project.root", System.getProperty("user.dir"))).toAbsolutePath().normalize();
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
