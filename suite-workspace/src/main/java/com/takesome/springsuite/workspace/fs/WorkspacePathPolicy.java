package com.takesome.springsuite.workspace.fs;

import com.takesome.springsuite.workspace.WorkspaceProperties;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class WorkspacePathPolicy {
    private final WorkspaceProperties properties;

    public WorkspacePathPolicy(WorkspaceProperties properties) {
        this.properties = properties;
    }

    public Path resolveSafe(String rawPath) {
        Path path = rawPath == null || rawPath.isBlank() ? Paths.get(".") : Paths.get(rawPath);
        Path resolved = path.isAbsolute()
                ? path.toAbsolutePath().normalize()
                : runtimeRoot().resolve(path).toAbsolutePath().normalize();
        Optional<Path> root = allowedRoots().stream().filter(allowed -> startsWith(resolved, allowed)).findFirst();
        if (root.isEmpty()) {
            throw new IllegalArgumentException("path escapes configured workspace roots: " + rawPath);
        }
        if (!isNotDenied(resolved)) {
            throw new IllegalArgumentException("path denied by workspace policy: " + rawPath);
        }
        return resolved;
    }

    public List<Path> allowedRoots() {
        ArrayList<Path> roots = new ArrayList<>();
        for (String raw : properties.effectiveRoots()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            Path path = Paths.get(raw);
            roots.add(path.isAbsolute() ? path.toAbsolutePath().normalize() : runtimeRoot().resolve(path).toAbsolutePath().normalize());
        }
        return roots.isEmpty() ? List.of(runtimeRoot()) : List.copyOf(roots);
    }

    public boolean isNotDenied(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Optional<Path> root = allowedRoots().stream().filter(allowed -> startsWith(normalized, allowed)).findFirst();
        Path policyPath = root.map(value -> relativizeSafe(value, normalized)).orElse(normalized);
        return !hasDeniedSegment(policyPath) && !matchesDeniedGlob(policyPath);
    }

    public String displayPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (Path root : allowedRoots()) {
            if (startsWith(normalized, root)) {
                try {
                    String rel = root.relativize(normalized).toString().replace('\\', '/');
                    return rel.isBlank() ? "." : rel;
                } catch (IllegalArgumentException ignored) {
                    // Try next root.
                }
            }
        }
        try {
            return runtimeRoot().relativize(normalized).toString().replace('\\', '/');
        } catch (IllegalArgumentException ex) {
            return normalized.toString();
        }
    }

    public Path runtimeRoot() {
        return Paths.get(System.getProperty("suite.project.root", System.getProperty("user.dir"))).toAbsolutePath().normalize();
    }

    private boolean startsWith(Path path, Path root) {
        return path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize());
    }

    private Path relativizeSafe(Path root, Path path) {
        try {
            return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        } catch (IllegalArgumentException ex) {
            return path;
        }
    }

    private boolean hasDeniedSegment(Path policyPath) {
        Set<String> denied = properties.effectiveDenySegments().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        for (Path part : policyPath) {
            if (denied.contains(part.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesDeniedGlob(Path policyPath) {
        String rel = policyPath.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (rel.isBlank() || rel.equals(".")) {
            return false;
        }
        for (String raw : properties.effectiveDenyGlobs()) {
            if (globMatches(normalizeGlob(raw), rel)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeGlob(String raw) {
        return raw == null ? "" : raw.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private boolean globMatches(String glob, String rel) {
        if (glob.isBlank()) {
            return false;
        }
        if (glob.startsWith("**/") && glob.endsWith("/**")) {
            String segment = glob.substring(3, glob.length() - 3);
            return rel.equals(segment) || rel.startsWith(segment + "/") || rel.contains("/" + segment + "/") || rel.endsWith("/" + segment);
        }
        if (glob.startsWith("**/*")) {
            return rel.endsWith(glob.substring(4));
        }
        String quoted = Pattern.quote(glob)
                .replace("\\*\\*", "\\E.*\\Q")
                .replace("\\*", "\\E[^/]*\\Q")
                .replace("\\?", "\\E.\\Q");
        return Pattern.compile("^" + quoted + "$").matcher(rel).matches();
    }
}
