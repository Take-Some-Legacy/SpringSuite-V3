package com.takesome.springsuite.workspace;

import com.takesome.springsuite.workspace.fs.WorkspacePathPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
        int safeLimit = limit <= 0 ? properties.getMaxSearchResults() : Math.min(limit, properties.getMaxSearchResults());
        ArrayList<WorkspaceSearchMatch> matches = new ArrayList<>();
        AtomicBoolean truncated = new AtomicBoolean(false);
        Pattern pattern = regex ? Pattern.compile(query, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : null;
        String needle = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
        try (Stream<Path> stream = Files.walk(target, 16)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(pathPolicy::isNotDenied)
                    .filter(textFilePolicy::isProbablyText)
                    .sorted(Comparator.comparing(item -> pathPolicy.displayPath(item).toLowerCase(Locale.ROOT)))
                    .toList();
            for (Path file : files) {
                if (matches.size() >= safeLimit) {
                    truncated.set(true);
                    break;
                }
                searchFile(file, needle, pattern, caseSensitive, matches, safeLimit, truncated);
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("search failed: " + ex.getMessage(), ex);
        }
        return new WorkspaceSearchResult(query, pathPolicy.displayPath(target), regex, caseSensitive, truncated.get(), matches.size(), matches);
    }

    private void searchFile(Path file, String needle, Pattern pattern, boolean caseSensitive,
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
}
