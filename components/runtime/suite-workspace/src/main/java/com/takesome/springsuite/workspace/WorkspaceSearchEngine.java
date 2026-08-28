package com.takesome.springsuite.workspace;

import com.takesome.springsuite.workspace.fs.WorkspacePathPolicy;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Pattern;

final class WorkspaceSearchEngine {
    private final WorkspaceProperties properties;
    private final WorkspacePathPolicy pathPolicy;
    private final WorkspaceTextFilePolicy textFilePolicy;

    WorkspaceSearchEngine(WorkspaceProperties properties, WorkspacePathPolicy pathPolicy, WorkspaceTextFilePolicy textFilePolicy) {
        this.properties = properties;
        this.pathPolicy = pathPolicy;
        this.textFilePolicy = textFilePolicy;
    }

    WorkspaceSearchResult search(String query, String path, int limit, boolean regex, boolean caseSensitive) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("search query is required");
        }
        Path target = pathPolicy.resolveSafe(path == null || path.isBlank() ? "." : path);
        if (!pathPolicy.isNotDeniedForScan(target)) {
            throw new IllegalArgumentException("search target denied by recursive workspace policy: " + pathPolicy.displayPath(target));
        }

        int safeLimit = effectiveLimit(limit, properties.getMaxSearchResults());
        ArrayList<WorkspaceSearchMatch> matches = new ArrayList<>(Math.min(safeLimit, 256));
        Pattern pattern = regex ? Pattern.compile(query, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : null;
        String needle = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
        SearchBudget budget = new SearchBudget(properties);
        boolean[] truncated = {false};

        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            try {
                long size = Files.size(target);
                if (budget.visitFile(size) && textFilePolicy.isSearchableText(target, size) && budget.beginContentScan(size)) {
                    searchFile(target, needle, pattern, caseSensitive, matches, safeLimit, truncated, budget);
                } else if (budget.exhausted()) {
                    truncated[0] = true;
                }
            } catch (IOException ex) {
                throw new IllegalArgumentException("search failed: " + ex.getMessage(), ex);
            }
            sortMatches(matches);
            return result(query, target, regex, caseSensitive, truncated[0], matches);
        }

        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("search target is not a regular file or directory: " + pathPolicy.displayPath(target));
        }

        try {
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (budget.exhausted()) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (!dir.equals(target) && !pathPolicy.isNotDeniedForScan(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (budget.exhausted()) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (!attrs.isRegularFile() || !pathPolicy.isNotDeniedForScan(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    long size = attrs.size();
                    if (!budget.visitFile(size)) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (!textFilePolicy.isSearchableText(file, size)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!budget.beginContentScan(size)) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    searchFile(file, needle, pattern, caseSensitive, matches, safeLimit, truncated, budget);
                    if (truncated[0] || matches.size() >= safeLimit || budget.exhausted()) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    if (budget.exhausted()) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            throw new IllegalArgumentException("search failed: " + ex.getMessage(), ex);
        }

        sortMatches(matches);
        return result(query, target, regex, caseSensitive, truncated[0], matches);
    }

    private void searchFile(
            Path file,
            String needle,
            Pattern pattern,
            boolean caseSensitive,
            ArrayList<WorkspaceSearchMatch> matches,
            int limit,
            boolean[] truncated,
            SearchBudget budget
    ) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if ((lineNumber & 0xFF) == 0 && budget.exhausted()) {
                    truncated[0] = true;
                    return;
                }
                boolean hit = pattern == null
                        ? (caseSensitive ? line : line.toLowerCase(Locale.ROOT)).contains(needle)
                        : pattern.matcher(line).find();
                if (!hit) {
                    continue;
                }
                matches.add(new WorkspaceSearchMatch(pathPolicy.displayPath(file), lineNumber, line));
                if (matches.size() >= limit) {
                    truncated[0] = true;
                    return;
                }
            }
        } catch (Exception ignored) {
            // Search is best-effort across unreadable, concurrently changed, or malformed text files.
        }
    }

    private WorkspaceSearchResult result(
            String query,
            Path target,
            boolean regex,
            boolean caseSensitive,
            boolean truncated,
            ArrayList<WorkspaceSearchMatch> matches
    ) {
        return new WorkspaceSearchResult(
                query,
                pathPolicy.displayPath(target),
                regex,
                caseSensitive,
                truncated,
                matches.size(),
                matches
        );
    }

    private static void sortMatches(ArrayList<WorkspaceSearchMatch> matches) {
        matches.sort(Comparator.comparing(WorkspaceSearchMatch::path, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(WorkspaceSearchMatch::lineNumber));
    }

    private static int effectiveLimit(int requested, int configured) {
        if (requested > 0) {
            return configured > 0 ? Math.min(requested, configured) : requested;
        }
        return configured > 0 ? configured : Integer.MAX_VALUE - 1;
    }

    private static final class SearchBudget {
        private final long deadlineNanos;
        private final int maxFiles;
        private final long maxBytes;
        private int visitedFiles;
        private long contentBytes;
        private boolean exhausted;

        private SearchBudget(WorkspaceProperties properties) {
            long durationNanos = properties.getMaxSearchDuration().isZero()
                    ? 0L
                    : properties.getMaxSearchDuration().toNanos();
            this.deadlineNanos = durationNanos <= 0L ? 0L : System.nanoTime() + durationNanos;
            this.maxFiles = properties.getMaxSearchFiles();
            this.maxBytes = properties.getMaxSearchBytes();
        }

        private boolean visitFile(long ignoredSize) {
            if (exhausted()) {
                return false;
            }
            visitedFiles++;
            if (maxFiles > 0 && visitedFiles > maxFiles) {
                exhausted = true;
                return false;
            }
            return true;
        }

        private boolean beginContentScan(long size) {
            if (exhausted()) {
                return false;
            }
            if (maxBytes > 0 && size > maxBytes - contentBytes) {
                exhausted = true;
                return false;
            }
            contentBytes += Math.max(0L, size);
            return true;
        }

        private boolean exhausted() {
            if (exhausted) {
                return true;
            }
            if (deadlineNanos > 0L && System.nanoTime() >= deadlineNanos) {
                exhausted = true;
            }
            return exhausted;
        }
    }
}
